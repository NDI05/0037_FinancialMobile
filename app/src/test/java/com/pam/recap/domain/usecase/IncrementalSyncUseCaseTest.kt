package com.pam.recap.domain.usecase

import com.pam.recap.core.money.Money
import com.pam.recap.core.result.AppError
import com.pam.recap.core.result.Outcome
import com.pam.recap.core.security.FingerprintFactory
import com.pam.recap.core.time.ClockProvider
import com.pam.recap.core.time.JakartaMonth
import com.pam.recap.core.util.IdGenerator
import com.pam.recap.data.gmail.LivinQueryBuilder
import com.pam.recap.data.gmail.LivinTemplateRegistry
import com.pam.recap.domain.model.AutoSyncConfig
import com.pam.recap.domain.model.GmailConnectionState
import com.pam.recap.domain.model.MailboxWatermark
import com.pam.recap.domain.model.Session
import com.pam.recap.domain.model.SyncCursor
import com.pam.recap.domain.repository.GmailMessageRef
import com.pam.recap.domain.repository.GmailPage
import com.pam.recap.domain.repository.GmailSource
import com.pam.recap.domain.repository.LivinNotificationParser
import com.pam.recap.domain.repository.LivinQuery
import com.pam.recap.domain.repository.RawMessage
import com.pam.recap.domain.model.Direction
import com.pam.recap.domain.model.ParseResult
import com.pam.recap.domain.model.ParseStatus
import com.pam.recap.domain.repository.SyncCursorStore
import com.pam.recap.testing.FakeAnalysisRepository
import com.pam.recap.testing.FakeDeletionRepository
import com.pam.recap.testing.FakeGmailConnectionRepository
import com.pam.recap.testing.FakeImportRepository
import com.pam.recap.testing.FakeSessionRepository
import com.pam.recap.testing.FakeSummaryRepository
import com.pam.recap.testing.FakeTransactionRepository
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * Incremental sync contract (plan P1).
 *
 * Proves with a fake mailbox and a mutable clock — never by waiting:
 * - a run with no new mail downloads no body;
 * - a crash before the server ack keeps the cursor, so the next run retries instead of losing mail;
 * - a crash after the financial commit creates no duplicate;
 * - UIDVALIDITY change, late email and month rollover behave;
 * - manual/foreground/background never import concurrently.
 */
class IncrementalSyncUseCaseTest {

    private val registry = LivinTemplateRegistry()
    private val ownerId = "u_sync"
    private val otherOwner = "u_other"

    private class MutableClock(var instant: Instant) : ClockProvider {
        override fun now(): Instant = instant
    }

    private var now = Instant.parse("2026-11-20T02:00:00Z")
    private val clock = MutableClock(now)

    private val sessions = FakeSessionRepository(
        Session(
            ownerId = ownerId,
            displayName = null,
            mailboxLabel = null,
            signedInAt = Instant.parse("2026-11-01T00:00:00Z"),
            isDemo = false,
        ),
    )
    private val transactions = FakeTransactionRepository()
    private val imports = FakeImportRepository()
    private val summaries = FakeSummaryRepository()
    private val analyses = FakeAnalysisRepository()
    private val connections = FakeGmailConnectionRepository()
    private val deletion = FakeDeletionRepository()
    private val mailbox = FakeMailbox()
    private val cursors = InMemoryCursorStore()
    private val status = SyncStatusHolder()
    private var idCounter = 0

    private var uploads = 0
    private var failUpload: AppError? = null
    private var ownerSwitchedOnUpload = false

    private val uploader = ImportUploader { _, _ ->
        uploads++
        if (ownerSwitchedOnUpload) {
            sessions.signIn(
                Session(
                    ownerId = otherOwner,
                    displayName = null,
                    mailboxLabel = null,
                    signedInAt = clock.now(),
                    isDemo = false,
                ),
            )
        }
        val error = failUpload
        if (error != null) Outcome.Failure(error) else Outcome.Success("server-job-1")
    }

    private val parser = FakeParser { id -> eventDates[id] ?: clock.now() }

    private val importMonth = ImportMonthUseCase(
        gmailSource = mailbox,
        queryBuilder = LivinQueryBuilder(registry),
        parser = parser,
        fingerprints = FingerprintFactory { owner -> "test-key::$owner".toByteArray() },
        categoryRules = MerchantCategoryRules(),
        transactions = transactions,
        imports = imports,
        summaries = summaries,
        analyses = analyses,
        gmailConnection = connections,
        deletion = deletion,
        clock = clock,
        idGenerator = IdGenerator { "id-${idCounter++}" },
    )

    private val sync = IncrementalSyncUseCase(
        sessions = sessions,
        gmail = mailbox,
        importMonth = importMonth,
        uploader = uploader,
        cursors = cursors,
        deletion = deletion,
        clock = clock,
        status = status,
        config = AutoSyncConfig(
            interval = 30.minutes,
            foregroundCooldown = 5.minutes,
        ),
    )

    // ---------------------------------------------------------------- fixtures

    private val eventDates = mutableMapOf<String, Instant>()

    /** Delivers one message and pins the transaction date the parser will report for it. */
    private fun deliver(id: String, eventAt: Instant): Long {
        eventDates[id] = eventAt
        return mailbox.deliver(RawMessage(
                messageId = id,
                sender = "Livin by Mandiri <notifikasi@bankmandiri.co.id>",
                subject = "Transaksi QRIS",
                internalDate = clock.now().minus(1.minutes),
                textParts = emptyList(),
            ))
    }

    // ---------------------------------------------------------------- tests

    @Test
    fun `a run with no new mail downloads no body`() = runTest {
        deliver("m-1", clock.now().minus(1.minutes))
        val first = sync.run(SyncTrigger.PERIODIC)
        assertTrue(first is SyncOutcome.Completed)
        assertEquals(1, (first as SyncOutcome.Completed).imported)
        val fetchesAfterFirst = mailbox.bodyFetches
        assertTrue("first run must download the body", fetchesAfterFirst >= 1)

        clock.instant = clock.now().plus(31.minutes)
        val second = sync.run(SyncTrigger.PERIODIC)
        assertTrue(second is SyncOutcome.Completed)
        assertEquals(0, (second as SyncOutcome.Completed).imported)
        assertEquals("no new mail means no body download", fetchesAfterFirst, mailbox.bodyFetches)
    }

    @Test
    fun `a crash before the upload ack keeps the cursor and retries the same delta`() = runTest {
        deliver("m-1", clock.now().minus(1.minutes))
        failUpload = AppError.Network("upload_failed")

        val failed = sync.run(SyncTrigger.PERIODIC)
        assertTrue(failed is SyncOutcome.Failed)
        assertNull("the cursor must not advance without an ack", cursors.read(ownerId)?.watermark)
        assertTrue(cursors.read(ownerId)?.uploadPending == true)
        assertEquals(1, transactions.all().size)

        // Restart the app: the same delta is read again, the fingerprint dedupes, the ack lands.
        failUpload = null
        clock.instant = clock.now().plus(31.minutes)
        val retried = sync.run(SyncTrigger.PERIODIC)
        assertTrue(retried is SyncOutcome.Completed)
        assertEquals("no duplicate after the retry", 1, transactions.all().size)
        assertNotNull("the ack is what finally advances the cursor", cursors.read(ownerId)?.watermark)
        assertEquals(2, uploads)
    }

    @Test
    fun `a crash after the financial commit can never duplicate a transaction`() = runTest {
        deliver("m-1", clock.now().minus(1.minutes))
        failUpload = AppError.Unknown("boom")
        sync.run(SyncTrigger.PERIODIC)
        failUpload = null

        repeat(3) {
            clock.instant = clock.now().plus(31.minutes)
            sync.run(SyncTrigger.PERIODIC)
        }
        assertEquals(1, transactions.all().size)
    }

    @Test
    fun `a uidvalidity change invalidates the cursor instead of skipping mail`() = runTest {
        deliver("m-1", clock.now().minus(1.minutes))
        sync.run(SyncTrigger.PERIODIC)
        assertEquals(1, transactions.all().size)

        // The folder was recreated: every uid means something else now.
        mailbox.uidValidity = 99L
        deliver("m-2", clock.now().minus(1.minutes))
        clock.instant = clock.now().plus(31.minutes)

        val run = sync.run(SyncTrigger.PERIODIC)
        assertTrue(run is SyncOutcome.Completed)
        assertEquals("the new message is found despite the reset", 1, (run as SyncOutcome.Completed).imported)
        assertEquals(2, transactions.all().size)
        assertEquals(99L, cursors.read(ownerId)?.watermark?.uidValidity)
    }

    @Test
    fun `a late email whose date belongs to the previous month is routed there`() = runTest {
        // 1 December 2026, 00:30 in Jakarta — the month has just rolled over.
        clock.instant = Instant.parse("2026-11-30T17:30:00Z")
        // Delivered now, but the transaction happened on 15 November.
        deliver("m-late", Instant.parse("2026-11-15T03:00:00Z"))

        val run = sync.run(SyncTrigger.PERIODIC)
        assertTrue(run is SyncOutcome.Completed)
        assertEquals(1, (run as SyncOutcome.Completed).imported)
        val november = JakartaMonth(2026, 11)
        assertEquals(
            "the late email is stored in November, not dropped and not misfiled in December",
            1,
            transactions.listMonth(ownerId, november).size,
        )
        assertTrue(transactions.listMonth(ownerId, JakartaMonth(2026, 12)).isEmpty())
    }

    @Test
    fun `manual and background runs never import concurrently`() = runTest {
        deliver("m-1", clock.now().minus(1.minutes))
        mailbox.pauseInDelta(50)
        val results = mutableListOf<SyncOutcome>()

        val bg = backgroundScope.launch { results += sync.run(SyncTrigger.PERIODIC) }
        val fg = backgroundScope.launch { results += sync.runIfStale(SyncTrigger.FOREGROUND) }
        bg.join()
        fg.join()

        assertEquals(2, results.size)
        assertEquals(1, results.count { it is SyncOutcome.AlreadyRunning })
        assertEquals(1, results.count { it is SyncOutcome.Completed })
        assertEquals(1, transactions.all().size)
    }

    @Test
    fun `the foreground check respects the cooldown and the empty queue does not fabricate work`() = runTest {
        deliver("m-1", clock.now().minus(1.minutes))
        assertTrue(sync.runIfStale() is SyncOutcome.Completed)

        val skipped = sync.runIfStale()
        assertTrue(skipped is SyncOutcome.Skipped)
        assertEquals("cooldown", (skipped as SyncOutcome.Skipped).reason)
        assertEquals(1, mailbox.deltaReads)

        clock.instant = clock.now().plus(6.minutes)
        assertTrue(sync.runIfStale() is SyncOutcome.Completed)
        assertEquals(2, mailbox.deltaReads)
    }

    @Test
    fun `a pending upload ignores the cooldown so the queue can drain`() = runTest {
        deliver("m-1", clock.now().minus(1.minutes))
        failUpload = AppError.Network("offline")
        sync.runIfStale()
        assertTrue(cursors.read(ownerId)?.uploadPending == true)

        failUpload = null
        // Still inside the 5-minute window, but the retry must not be starved by the cooldown.
        val retried = sync.runIfStale()
        assertTrue(retried is SyncOutcome.Completed)
    }

    @Test
    fun `a result for an owner who signed out mid-run is discarded`() = runTest {
        deliver("m-1", clock.now().minus(1.minutes))
        ownerSwitchedOnUpload = true

        val outcome = sync.run(SyncTrigger.PERIODIC)
        assertTrue(outcome is SyncOutcome.Skipped)
        assertEquals("owner_changed", (outcome as SyncOutcome.Skipped).reason)
        assertNull("nothing is recorded for the old owner", cursors.read(ownerId)?.watermark)
        assertNull("and the new owner starts clean", cursors.read(otherOwner))
    }

    // ---------------------------------------------------------------- doubles

    private class InMemoryCursorStore : SyncCursorStore {
        private val rows = mutableMapOf<String, SyncCursor>()
        override fun read(ownerId: String): SyncCursor? = rows[ownerId]
        override fun save(cursor: SyncCursor): Boolean {
            rows[cursor.ownerId] = cursor
            return true
        }

        override fun clear(ownerId: String) {
            rows.remove(ownerId)
        }
    }

    /**
     * An IMAP-shaped mailbox: uids ascend, the watermark is folder-level, and only the delta path
     * can reach the bodies (so a full-month `search` would show up as a failure).
     */
    private class FakeMailbox : GmailSource {
        private val messages = mutableListOf<RawMessage>()

        var uidValidity = 42L
        var deltaReads = 0
        var bodyFetches = 0
        private var pauseMillis = 0L

        fun deliver(raw: RawMessage): Long {
            messages += raw
            return messages.size.toLong()
        }

        fun pauseInDelta(millis: Long) {
            pauseMillis = millis
        }

        override suspend fun authorize(): Outcome<Unit> = Outcome.Success(Unit)

        override fun connectionState(): Flow<GmailConnectionState> = flowOf(GmailConnectionState.CONNECTED)

        override suspend fun search(query: LivinQuery, pageToken: String?): Outcome<GmailPage> =
            Outcome.Failure(AppError.Configuration("full_scan_forbidden_in_delta_test"))

        override suspend fun fetchMessage(id: String): Outcome<RawMessage> {
            bodyFetches++
            val uid = id.substringAfter(':').toLongOrNull() ?: return Outcome.Failure(AppError.NotFound("bad_id"))
            val raw = messages.getOrNull((uid - 1).toInt()) ?: return Outcome.Failure(AppError.NotFound("gone"))
            return Outcome.Success(raw)
        }

        override suspend fun disconnect(): Outcome<Unit> = Outcome.Success(Unit)

        override suspend fun watermark(): Outcome<MailboxWatermark> =
            Outcome.Success(MailboxWatermark(uidValidity, messages.size.toLong()))

        override suspend fun searchDelta(
            query: LivinQuery,
            since: MailboxWatermark?,
            upTo: MailboxWatermark?,
            pageToken: String?,
        ): Outcome<GmailPage> {
            deltaReads++
            if (pauseMillis > 0) kotlinx.coroutines.delay(pauseMillis)
            val floor = since?.takeIf { it.uidValidity == uidValidity }?.highestUid ?: 0L
            val ceiling = upTo?.takeIf { it.uidValidity == uidValidity }?.highestUid
            val limit = query.pageSize
            val hits = messages.withIndex()
                .filter { (index, raw) ->
                    val uid = (index + 1).toLong()
                    uid > floor &&
                        (ceiling == null || uid <= ceiling) &&
                        raw.internalDate != null &&
                        raw.internalDate >= query.fromInclusive &&
                        raw.internalDate < query.toExclusive
                }
                .sortedByDescending { it.index }
            val offset = pageToken?.substringAfter("offset:")?.toIntOrNull() ?: 0
            val page = hits.drop(offset).take(limit)
            return Outcome.Success(
                GmailPage(
                    messages = page.map { (index, raw) ->
                        GmailMessageRef(
                            id = "$uidValidity:${index + 1}",
                            internalDate = raw.internalDate,
                        )
                    },
                    nextPageToken = if (offset + page.size < hits.size) "offset:${offset + page.size}" else null,
                    resultSizeEstimate = hits.size,
                ),
            )
        }
    }

    private class FakeParser(private val eventAtFor: (String) -> Instant) : LivinNotificationParser {
        override fun parse(message: RawMessage): ParseResult = ParseResult.Parsed(
            messageId = message.messageId,
            templateId = "livin.qris.v1",
            parserVersion = "1.0.0",
            eventAt = eventAtFor(message.messageId),
            receivedAt = message.internalDate,
            amount = Money(25_000),
            currency = "IDR",
            direction = Direction.DEBIT,
            merchantLabel = "KOPI UJI",
            acquirer = "QRIS",
            status = ParseStatus.POSTED,
            description = "Pembayaran QRIS",
            confidence = 0.95f,
        )
    }
}
