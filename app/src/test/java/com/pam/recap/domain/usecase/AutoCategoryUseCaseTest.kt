package com.pam.recap.domain.usecase

import com.pam.recap.core.money.Money
import com.pam.recap.core.result.AppError
import com.pam.recap.core.result.Outcome
import com.pam.recap.core.time.ClockProvider
import com.pam.recap.core.time.JakartaMonth
import com.pam.recap.domain.model.Category
import com.pam.recap.domain.model.Consent
import com.pam.recap.domain.model.ConsentDecision
import com.pam.recap.domain.model.ConsentPurpose
import com.pam.recap.domain.model.CategoryOrigin
import com.pam.recap.domain.model.CategoryQueuePhase
import com.pam.recap.domain.model.Direction
import com.pam.recap.domain.model.MonthlySummary
import com.pam.recap.domain.model.ParseStatus
import com.pam.recap.domain.model.Transaction
import com.pam.recap.domain.model.TransactionSource
import com.pam.recap.domain.repository.CategoryDrainResult
import com.pam.recap.domain.repository.ClassificationAttemptStore
import com.pam.recap.domain.repository.CategoryEnqueueResult
import com.pam.recap.domain.repository.ManualTransactionDraft
import com.pam.recap.domain.repository.ServerCategory
import com.pam.recap.domain.repository.ServerCategoryJob
import com.pam.recap.domain.repository.ServerFinanceRepository
import com.pam.recap.domain.repository.ServerImportCandidate
import com.pam.recap.domain.repository.ServerImportCounts
import com.pam.recap.domain.repository.ServerImportJob
import com.pam.recap.domain.repository.ServerTransactionPage
import com.pam.recap.testing.FakeConsentRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Instant

class AutoCategoryUseCaseTest {

    private val now = Instant.parse("2026-11-23T03:00:00Z")

    private fun tx(
        id: String,
        category: Category = Category.UNCATEGORIZED,
        origin: CategoryOrigin = CategoryOrigin.IMPORTED,
        internal: Boolean = false,
    ) = Transaction(
        id = id, ownerId = "u_1", direction = Direction.DEBIT, source = TransactionSource.EMAIL,
        amount = Money(25_000), currency = "IDR", eventAt = now, receivedAt = null,
        description = "QRIS BAKSO", merchantLabel = "BAKSO UJI", acquirer = null,
        status = ParseStatus.POSTED, fingerprint = id.padEnd(32, 'a'), parserVersion = "livin-v1",
        parserConfidence = 0.9f, category = category, categoryOrigin = origin,
        categoryConfidence = null, categoryUpdatedAt = null, isInternalTransfer = internal,
        linkedRefundOf = null, note = null, importJobId = null, revision = 1,
        createdAt = now, updatedAt = now,
    )

    private class FakeServer(
        private val rows: List<Transaction> = emptyList(),
        private val drain: Outcome<CategoryDrainResult> = Outcome.Success(CategoryDrainResult("empty", 0, 0)),
        private val enqueue: Outcome<CategoryEnqueueResult> = Outcome.Success(CategoryEnqueueResult(0, 0)),
    ) : ServerFinanceRepository {
        override val isEnabled = true
        var enqueueCalls = 0
        var drainCalls = 0
        val classifyBatches = mutableListOf<List<String>>()
        val enqueueBatches = mutableListOf<List<String>>()
        var enqueuedIds: List<String> = emptyList()
        var gate: CompletableDeferred<Unit>? = null
        val drainStarted = CompletableDeferred<Unit>()
        var classifyState = "CLASSIFIED"

        override suspend fun categories(ownerId: String): Outcome<List<ServerCategory>> =
            Outcome.Success(emptyList())

        override suspend fun createManual(ownerId: String, draft: ManualTransactionDraft, idempotencyKey: String) =
            Outcome.Failure(AppError.Configuration("unused"))

        override suspend fun listMonth(ownerId: String, month: JakartaMonth, limit: Int, cursor: String?) =
            Outcome.Success(ServerTransactionPage(rows))

        override suspend fun get(ownerId: String, transactionId: String) =
            Outcome.Failure(AppError.NotFound("unused"))

        override suspend fun updateCategory(
            ownerId: String, transactionId: String, categoryId: String, revision: Int,
        ) = Outcome.Failure(AppError.Configuration("unused"))

        override suspend fun summary(ownerId: String, month: JakartaMonth) =
            Outcome.Failure(AppError.Configuration("unused"))

        override suspend fun classifyTransactions(ownerId: String, transactionIds: List<String>): Outcome<List<ServerCategoryJob>> {
            classifyBatches += transactionIds
            return Outcome.Success(
                transactionIds.map { id ->
                    ServerCategoryJob(id = "job_$id", transactionId = id, state = classifyState)
                },
            )
        }

        override suspend fun enqueueCategories(
            ownerId: String,
            transactionIds: List<String>,
        ): Outcome<CategoryEnqueueResult> {
            enqueueCalls++
            enqueuedIds = transactionIds
            enqueueBatches += transactionIds
            gate?.await()
            return enqueue
        }

        override suspend fun drainCategories(ownerId: String): Outcome<CategoryDrainResult> {
            drainCalls++
            drainStarted.complete(Unit)
            gate?.await()
            return drain
        }

        override suspend fun createImportJob(ownerId: String, month: JakartaMonth, templateVersion: String) =
            Outcome.Failure(AppError.Configuration("unused"))

        override suspend fun importBatch(
            ownerId: String, jobId: String, batchKey: String, pageOrdinal: Int,
            candidates: List<ServerImportCandidate>,
        ) = Outcome.Failure(AppError.Configuration("unused"))

        override suspend fun finalizeImportJob(ownerId: String, jobId: String, state: String, errorCode: String?) =
            Outcome.Failure(AppError.Configuration("unused"))
    }

    private class FixedClock(private val at: Instant) : ClockProvider {
        override fun now(): Instant = at
    }

    private class FakeAttemptStore(
        private val initial: Map<String, Int> = emptyMap(),
    ) : ClassificationAttemptStore {
        val stored = initial.toMutableMap()

        override suspend fun reviewed(ownerId: String): Map<String, Int> = stored.toMap()

        override suspend fun recordReviewed(ownerId: String, attempts: Map<String, Int>) {
            stored.putAll(attempts)
        }
    }

    private fun useCase(
        server: FakeServer,
        consents: FakeConsentRepository? = null,
        attempts: ClassificationAttemptStore? = null,
    ): Pair<AutoCategoryUseCase, CategoryStatusHolder> {
        val holder = CategoryStatusHolder()
        return AutoCategoryUseCase(server, holder, FixedClock(now), consents, null, attempts) to holder
    }

    private fun grantedConsent(purpose: ConsentPurpose) = FakeConsentRepository(
        listOf(
            Consent(
                id = "c-${purpose.name}",
                ownerId = "u_1",
                purpose = purpose,
                noticeVersion = "notice-v2",
                decision = ConsentDecision.GRANTED,
                decidedAt = now,
            ),
        ),
    )


    @Test
    fun `classifyAll sweeps every eligible row in batches of five`() = runTest {
        val rows = (1..12).map { tx("tx_$it") }
        val server = FakeServer(rows = rows)
        val (useCase, _) = useCase(server, grantedConsent(ConsentPurpose.AI_CATEGORIZATION))

        val outcome = useCase.classifyAll("u_1")

        assertTrue(outcome is Outcome.Success)
        val result = (outcome as Outcome.Success).value
        assertEquals(12, result.total)
        assertEquals(12, result.processed)
        // The backend contract caps one call at five ids; the tail must still be sent.
        assertEquals(listOf(5, 5, 2), server.classifyBatches.map { it.size })
        assertEquals(12, result.classified)
    }

    @Test
    fun `a second sweep skips rows that already reached needs review`() = runTest {
        val rows = (1..6).map { tx("tx_$it") }
        val server = FakeServer(rows = rows).apply { classifyState = "NEEDS_REVIEW" }
        val attempts = FakeAttemptStore()
        val (useCase, _) = useCase(server, grantedConsent(ConsentPurpose.AI_CATEGORIZATION), attempts)

        val first = (useCase.classifyAll("u_1") as Outcome.Success).value
        assertEquals(6, first.total)
        assertEquals(6, first.needsReview)
        assertEquals(6, attempts.stored.size)

        val second = (useCase.classifyAll("u_1") as Outcome.Success).value
        assertEquals(0, second.total)
        assertEquals(0, second.needsReview)
        // No request at all on the repeat tap: no extra token-bearing job is created.
        assertEquals(listOf(5, 1), server.classifyBatches.map { it.size })
    }

    @Test
    fun `a revision bump reopens a previously reviewed row`() = runTest {
        val row = tx("tx_1").copy(revision = 2)
        val server = FakeServer(rows = listOf(row)).apply { classifyState = "NEEDS_REVIEW" }
        val attempts = FakeAttemptStore(mapOf("tx_1" to 1))
        val (useCase, _) = useCase(server, grantedConsent(ConsentPurpose.AI_CATEGORIZATION), attempts)

        val outcome = (useCase.classifyAll("u_1") as Outcome.Success).value

        assertEquals(1, outcome.total)
        assertEquals(listOf(listOf("tx_1")), server.classifyBatches)
    }

    @Test
    fun `enqueueOnly also excludes already reviewed rows`() = runTest {
        val server = FakeServer(rows = listOf(tx("tx_1"), tx("tx_2")))
        val attempts = FakeAttemptStore(mapOf("tx_1" to 1))
        val (useCase, _) = useCase(server, attempts = attempts)

        useCase.enqueueOnly("u_1")

        assertEquals(listOf(listOf("tx_2")), server.enqueueBatches)
    }

    @Test
    fun `classifyAll refuses to run without the classifier consent`() = runTest {
        val server = FakeServer(rows = listOf(tx("tx_1")))
        val (useCase, _) = useCase(server, FakeConsentRepository())

        val outcome = useCase.classifyAll("u_1")

        assertTrue(outcome is Outcome.Failure)
        assertEquals("ai_category_consent_required", ((outcome as Outcome.Failure).error as AppError.Validation).rule)
        assertTrue(server.classifyBatches.isEmpty())
    }

    @Test
    fun `enqueueOnly sends every candidate in batches of fifty`() = runTest {
        val server = FakeServer(rows = (1..110).map { tx("tx_$it") })
        val (useCase, _) = useCase(server)

        useCase.enqueueOnly("u_1")

        assertEquals(listOf(50, 50, 10), server.enqueueBatches.map { it.size })
        assertEquals(3, server.enqueueCalls)
    }

    @Test
    fun `only uncategorized non-user non-transfer rows are enqueued`() = runTest {
        val server = FakeServer(
            rows = listOf(
                tx("tx_ok"),
                tx("tx_user", category = Category.FOOD, origin = CategoryOrigin.USER),
                tx("tx_internal", internal = true),
                tx("tx_categorized", category = Category.FOOD),
            ),
            drain = Outcome.Success(CategoryDrainResult("drained", 1, 0)),
        )
        val (useCase, holder) = useCase(server)
        val snapshot = useCase.runIfConnected("u_1")!!

        assertEquals(listOf("tx_ok"), server.enqueuedIds)
        assertEquals(1, server.enqueueCalls)
        assertEquals(1, server.drainCalls)
        assertEquals(CategoryQueuePhase.IDLE, snapshot.phase)
        assertEquals(0, holder.snapshot.value.pending)
    }

    @Test
    fun `an empty candidate set never calls enqueue but still asks for the backlog`() = runTest {
        val server = FakeServer(rows = emptyList())
        val (useCase, holder) = useCase(server)
        useCase.runIfConnected("u_1")

        assertEquals(0, server.enqueueCalls)
        assertEquals(1, server.drainCalls)
        assertEquals(CategoryQueuePhase.IDLE, holder.snapshot.value.phase)
        assertEquals(now, holder.snapshot.value.lastCheckedAt)
    }

    @Test
    fun `a deferred drain keeps the backlog and says when it is due`() = runTest {
        val due = now + kotlin.time.Duration.parse("2h")
        val server = FakeServer(
            drain = Outcome.Success(CategoryDrainResult("not_due", 0, 4, due)),
        )
        val (useCase, holder) = useCase(server)
        val snapshot = useCase.runIfConnected("u_1")!!

        assertEquals(CategoryQueuePhase.WAITING, snapshot.phase)
        assertEquals(4, snapshot.pending)
        assertEquals(due, snapshot.nextDueAt)
        assertTrue(holder.snapshot.value.hasBacklog)
    }

    @Test
    fun `an exhausted budget pauses the queue without dropping candidates`() = runTest {
        val server = FakeServer(
            drain = Outcome.Success(CategoryDrainResult("budget_exhausted", 0, 7, null)),
        )
        val (useCase, holder) = useCase(server)
        val snapshot = useCase.runIfConnected("u_1")!!

        assertEquals(CategoryQueuePhase.PAUSED, snapshot.phase)
        assertEquals("budget_exhausted", snapshot.errorCode)
        assertEquals(7, snapshot.pending)
        // A paused drain is not active work: the indicator must not spin.
        assertTrue(!snapshot.isRunning)
    }

    @Test
    fun `a failed drain needs attention and carries the error kind`() = runTest {
        val server = FakeServer(drain = Outcome.Failure(AppError.Unauthorized("expired")))
        val (useCase, holder) = useCase(server)
        val snapshot = useCase.runIfConnected("u_1")!!

        assertEquals(CategoryQueuePhase.NEEDS_ATTENTION, snapshot.phase)
        assertEquals("unauthorized", snapshot.errorCode)
        assertNull(snapshot.nextDueAt)
        assertEquals(false, holder.snapshot.value.isRunning)
    }

    @Test
    fun `concurrent callers cannot start two drains`() = kotlinx.coroutines.runBlocking {
        val server = FakeServer()
        val gate = CompletableDeferred<Unit>()
        server.gate = gate
        val (useCase, _) = useCase(server)
        val first = async(kotlinx.coroutines.Dispatchers.Default) { useCase.runIfConnected("u_1") }
        withTimeout(20_000) { server.drainStarted.await() }
        assertEquals(1, server.drainCalls)
        val second = async(kotlinx.coroutines.Dispatchers.Default) { useCase.runIfConnected("u_1") }
        // The first caller holds the in-process gate while its drain is suspended, so the second
        // must return the current snapshot immediately instead of queueing another drain.
        second.await()
        gate.complete(Unit)
        first.await()
        assertEquals(1, server.drainCalls)
    }
}
