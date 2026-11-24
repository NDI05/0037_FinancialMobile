package com.pam.recap.domain.usecase

import com.pam.recap.core.money.Money
import com.pam.recap.core.result.AppError
import com.pam.recap.core.result.Outcome
import com.pam.recap.core.security.FingerprintFactory
import com.pam.recap.core.security.FingerprintInput
import com.pam.recap.core.time.ClockProvider
import com.pam.recap.core.time.JakartaMonth
import com.pam.recap.data.gmail.LivinQueryBuilder
import com.pam.recap.data.gmail.LivinTemplateRegistry
import com.pam.recap.domain.model.Category
import com.pam.recap.domain.model.CategoryOrigin
import com.pam.recap.domain.model.Direction
import com.pam.recap.domain.model.GmailConnectionState
import com.pam.recap.domain.model.ParseResult
import com.pam.recap.domain.model.ParseStatus
import com.pam.recap.domain.model.Transaction
import com.pam.recap.domain.model.TransactionSource
import com.pam.recap.domain.repository.CategoryEnqueueResult
import com.pam.recap.domain.repository.GmailMessageRef
import com.pam.recap.domain.repository.GmailPage
import com.pam.recap.domain.repository.GmailSource
import com.pam.recap.domain.repository.LivinNotificationParser
import com.pam.recap.domain.repository.LivinQuery
import com.pam.recap.domain.repository.RawMessage
import com.pam.recap.domain.repository.ServerTransactionPage
import com.pam.recap.domain.repository.TransactionPatch
import com.pam.recap.testing.FakeTransactionRepository
import com.pam.recap.ui.feature.transactions.RecordingServerFinanceRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Instant

/**
 * P0 merchant-label repair contract.
 *
 * Every case is proven without a real mailbox or backend:
 * - only rows whose label is an identifier are candidates;
 * - the local identity is re-derived from the message ref, so an ambiguous or missing match fails
 *   closed and never writes;
 * - the PATCH carries a CAS fingerprint plus the source ref, and the new server fingerprint is
 *   never invented by the client;
 * - the marker makes the pass once-only and a transient failure withholds it;
 * - category, note and status survive the repair.
 */
class MerchantRepairUseCaseTest {

    private val ownerId = "u_repair"
    private val month = JakartaMonth.of(Instant.parse("2026-11-15T00:00:00Z"))
    private val legacyLabel = "9360012345678901234"
    private val newLabel = "Kopi Kenangan Senayan"
    private val amount = Money(17_500_000)
    private val eventAt = Instant.parse("2026-11-05T02:00:00Z")
    private val messageId = "777:100"

    private val fingerprints = FingerprintFactory { owner -> "test-key::$owner".toByteArray() }
    private class FixedClock(private val at: Instant) : ClockProvider {
        override fun now(): Instant = at
    }

    private val clock = FixedClock(Instant.parse("2026-11-24T09:00:00Z"))

    private fun localFingerprint(id: String, label: String?): String =
        fingerprints.fingerprint(
            ownerId,
            FingerprintInput(
                messageId = id,
                amountMinor = amount.minor,
                eventAtEpochMilliseconds = eventAt.toEpochMilliseconds(),
                directionCode = Direction.DEBIT.name,
                recipientLabel = label,
            ),
        )

    private fun localRow(
        id: String,
        fingerprint: String,
        label: String? = legacyLabel,
        category: Category = Category.UNCATEGORIZED,
        note: String? = null,
        source: TransactionSource = TransactionSource.EMAIL,
    ): Transaction = Transaction(
        id = id,
        ownerId = ownerId,
        direction = Direction.DEBIT,
        source = source,
        amount = amount,
        currency = "IDR",
        eventAt = eventAt,
        receivedAt = eventAt,
        description = "Pembayaran QRIS",
        merchantLabel = label,
        acquirer = "Livin",
        status = ParseStatus.POSTED,
        fingerprint = fingerprint,
        parserVersion = "livin-qris/1",
        parserConfidence = 0.9f,
        category = category,
        categoryOrigin = CategoryOrigin.IMPORTED,
        categoryConfidence = null,
        categoryUpdatedAt = null,
        isInternalTransfer = false,
        linkedRefundOf = null,
        note = note,
        importJobId = "job-1",
        revision = 1,
        createdAt = eventAt,
        updatedAt = eventAt,
    )

    private fun serverRow(id: String, fingerprint: String, label: String? = legacyLabel): Transaction =
        localRow(id = id, fingerprint = fingerprint, label = label).copy(revision = 3)

    private class FakeMailbox(
        private val labels: Map<String, String?>,
        /** Emits every ref twice, so a single notification can never prove the row identity. */
        private val duplicateRefs: Boolean = false,
    ) : GmailSource {
        var searches = 0
        var fetches = 0

        override suspend fun authorize(): Outcome<Unit> = Outcome.Success(Unit)

        override fun connectionState(): Flow<GmailConnectionState> = flowOf(GmailConnectionState.DISCONNECTED)

        override suspend fun search(query: LivinQuery, pageToken: String?): Outcome<GmailPage> {
            searches++
            val refs = labels.keys.map { GmailMessageRef(it, null) }
            return Outcome.Success(GmailPage(if (duplicateRefs) refs + refs else refs, null))
        }

        override suspend fun fetchMessage(id: String): Outcome<RawMessage> =
            Outcome.Success(
                RawMessage(
                    messageId = id,
                    sender = null,
                    subject = null,
                    internalDate = null,
                    textParts = listOfNotNull(labels[id]),
                ),
            ).also { fetches++ }

        override suspend fun disconnect(): Outcome<Unit> = Outcome.Success(Unit)
    }

    /** The parser contract is already covered by the Gmail suite; here only the label matters. */
    private val parser = object : LivinNotificationParser {
        override fun parse(message: RawMessage): ParseResult = ParseResult.Parsed(
            messageId = message.messageId,
            templateId = "livin-qris",
            parserVersion = "livin-qris/1",
            eventAt = eventAt,
            receivedAt = eventAt,
            amount = amount,
            currency = "IDR",
            direction = Direction.DEBIT,
            merchantLabel = message.textParts.firstOrNull(),
            acquirer = "Livin",
            status = ParseStatus.POSTED,
            description = "Pembayaran QRIS",
            confidence = 0.9f,
        )
    }

    private class FakeServer : RecordingServerFinanceRepository() {
        var rows: List<Transaction> = emptyList()
        var failure: AppError? = null
        var nextCursor: String? = null
        var listCalls = 0
        val enqueued = mutableListOf<List<String>>()

        override suspend fun listMonth(
            ownerId: String,
            month: JakartaMonth,
            limit: Int,
            cursor: String?,
        ): Outcome<ServerTransactionPage> {
            listCalls++
            return Outcome.Success(ServerTransactionPage(rows, nextCursor))
        }

        override suspend fun updateTransaction(
            ownerId: String,
            transactionId: String,
            patch: TransactionPatch,
        ): Outcome<Transaction> {
            updateCalls += patch
            failure?.let { return Outcome.Failure(it) }
            val current = rows.firstOrNull { it.id == transactionId }
                ?: return Outcome.Failure(AppError.NotFound(transactionId))
            val updated = current.copy(
                merchantLabel = patch.merchantLabel ?: current.merchantLabel,
                // The server derives the replacement identity itself.
                fingerprint = "server-derived-${updateCalls.size}",
                revision = current.revision + 1,
            )
            rows = rows.map { if (it.id == transactionId) updated else it }
            return Outcome.Success(updated)
        }

        override suspend fun enqueueCategories(
            ownerId: String,
            transactionIds: List<String>,
        ): Outcome<CategoryEnqueueResult> {
            enqueued += transactionIds
            return Outcome.Success(CategoryEnqueueResult(transactionIds.size, 0))
        }
    }

    private fun repair(
        transactions: FakeTransactionRepository,
        server: FakeServer,
        mailbox: FakeMailbox,
    ) = MerchantRepairUseCase(
        gmail = mailbox,
        queryFactory = LivinQueryBuilder(LivinTemplateRegistry()),
        parser = parser,
        fingerprints = fingerprints,
        serverFinance = server,
        transactions = transactions,
        clock = clock,
    )

    @Test
    fun `repairs only identifier rows and writes its marker once`() = runTest {
        val legacy = localRow("t_legacy", localFingerprint(messageId, legacyLabel))
        val healthyLabel = "Kopi Kenangan"
        val healthy = localRow(
            id = "t_healthy",
            fingerprint = localFingerprint("777:101", healthyLabel),
            label = healthyLabel,
        )
        val transactions = FakeTransactionRepository(listOf(legacy, healthy))
        val server = FakeServer().apply { rows = listOf(serverRow("srv_1", "server-fp-1")) }
        val mailbox = FakeMailbox(mapOf(messageId to newLabel, "777:101" to healthyLabel))
        val useCase = repair(transactions, server, mailbox)

        val first = useCase.runIfNeeded(ownerId, month)

        assertTrue(first is Outcome.Success)
        val report = (first as Outcome.Success).value
        assertEquals(1, report.repaired)
        assertEquals(0, report.ambiguous)
        assertEquals(1, server.updateCalls.size)
        val patch = server.updateCalls.single()
        assertEquals("server-fp-1", patch.expectedFingerprint)
        assertEquals(messageId, patch.sourceRef)
        assertEquals(newLabel, patch.merchantLabel)
        assertNull(patch.categoryId)
        assertNull(patch.note)
        val local = transactions.all().first { it.id == "t_legacy" }
        assertEquals(newLabel, local.merchantLabel)
        assertEquals(localFingerprint(messageId, newLabel), local.fingerprint)
        assertEquals(listOf("srv_1"), server.enqueued.flatten())

        val searchesAfterFirst = mailbox.searches
        val second = useCase.runIfNeeded(ownerId, month)

        assertTrue((second as Outcome.Success).value.alreadyDone)
        assertEquals("a marker must stop a second scan", searchesAfterFirst, mailbox.searches)
        assertEquals(1, server.updateCalls.size)
    }

    @Test
    fun `ambiguous message identity is never written`() = runTest {
        val legacy = localRow("t_legacy", localFingerprint(messageId, legacyLabel))
        val transactions = FakeTransactionRepository(listOf(legacy))
        val server = FakeServer().apply { rows = listOf(serverRow("srv_1", "server-fp-1")) }
        // The same ref twice makes the identity non-unique: fail closed, not "pick one".
        val mailbox = FakeMailbox(mapOf(messageId to newLabel), duplicateRefs = true)
        val useCase = repair(transactions, server, mailbox)

        val result = useCase.runIfNeeded(ownerId, month)

        assertEquals(1, (result as Outcome.Success).value.ambiguous)
        assertEquals(0, result.value.repaired)
        assertTrue(server.updateCalls.isEmpty())
        assertEquals(legacyLabel, transactions.all().single().merchantLabel)
    }

    @Test
    fun `stale fingerprint fails closed and withholds the marker for a retry`() = runTest {
        val legacy = localRow("t_legacy", localFingerprint(messageId, legacyLabel))
        val transactions = FakeTransactionRepository(listOf(legacy))
        val server = FakeServer().apply {
            rows = listOf(serverRow("srv_1", "server-fp-1"))
            failure = AppError.Conflict("identity_conflict")
        }
        val mailbox = FakeMailbox(mapOf(messageId to newLabel))
        val useCase = repair(transactions, server, mailbox)

        // The server rejects the CAS: report failure, leave the row untouched and allow a retry.
        val conflict = useCase.runIfNeeded(ownerId, month)
        assertEquals(1, (conflict as Outcome.Success).value.failed)

        server.failure = null
        val retry = useCase.runIfNeeded(ownerId, month)
        assertEquals(1, (retry as Outcome.Success).value.repaired)
        assertEquals(2, server.updateCalls.size)
        assertEquals(newLabel, transactions.all().single { it.id == "t_legacy" }.merchantLabel)
    }

    @Test
    fun `category note and status survive the repair`() = runTest {
        val legacy = localRow(
            id = "t_legacy",
            fingerprint = localFingerprint(messageId, legacyLabel),
            category = Category.FOOD,
            note = "makan siang tim",
        )
        val transactions = FakeTransactionRepository(listOf(legacy))
        val server = FakeServer().apply { rows = listOf(serverRow("srv_1", "server-fp-1")) }
        val mailbox = FakeMailbox(mapOf(messageId to newLabel))

        repair(transactions, server, mailbox).runIfNeeded(ownerId, month)

        val local = transactions.all().single()
        assertEquals(Category.FOOD, local.category)
        assertEquals("makan siang tim", local.note)
        assertEquals(ParseStatus.POSTED, local.status)
        assertEquals(newLabel, local.merchantLabel)
        val patch = server.updateCalls.single()
        assertNull(patch.categoryId)
        assertNull(patch.note)
    }

    @Test
    fun `account with no identifier label does no mailbox scan`() = runTest {
        val healthyLabel = "Kopi Kenangan"
        val transactions = FakeTransactionRepository(
            listOf(localRow("t_healthy", localFingerprint("777:101", healthyLabel), label = healthyLabel)),
        )
        val server = FakeServer()
        val mailbox = FakeMailbox(mapOf("777:101" to healthyLabel))
        val useCase = repair(transactions, server, mailbox)

        val result = useCase.runIfNeeded(ownerId, month)

        assertEquals(0, (result as Outcome.Success).value.repaired)
        assertEquals(0, mailbox.searches)
        assertEquals(0, server.listCalls)
        assertTrue(useCase.runIfNeeded(ownerId, month).let { (it as Outcome.Success).value.alreadyDone })
    }
}
