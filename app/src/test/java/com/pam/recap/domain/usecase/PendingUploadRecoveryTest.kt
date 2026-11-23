package com.pam.recap.domain.usecase

import android.content.Context
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import com.pam.recap.core.money.Money
import com.pam.recap.core.result.AppError
import com.pam.recap.core.result.Outcome
import com.pam.recap.core.security.FingerprintFactory
import com.pam.recap.core.security.WrappedSecretStore
import com.pam.recap.core.time.ClockProvider
import com.pam.recap.core.time.JakartaMonth
import com.pam.recap.core.util.IdGenerator
import com.pam.recap.data.gmail.LivinQueryBuilder
import com.pam.recap.data.gmail.LivinTemplateRegistry
import com.pam.recap.data.security.ImportUploadJournal
import com.pam.recap.domain.model.AutoSyncConfig
import com.pam.recap.domain.model.Direction
import com.pam.recap.domain.model.GmailConnectionState
import com.pam.recap.domain.model.ImportState
import com.pam.recap.domain.model.MailboxWatermark
import com.pam.recap.domain.model.ParseResult
import com.pam.recap.domain.model.ParseStatus
import com.pam.recap.domain.model.Session
import com.pam.recap.domain.model.SyncCursor
import com.pam.recap.domain.repository.GmailMessageRef
import com.pam.recap.domain.repository.GmailPage
import com.pam.recap.domain.repository.GmailSource
import com.pam.recap.domain.repository.LivinNotificationParser
import com.pam.recap.domain.repository.LivinQuery
import com.pam.recap.domain.repository.ManualTransactionDraft
import com.pam.recap.domain.repository.RawMessage
import com.pam.recap.domain.repository.ServerCategory
import com.pam.recap.domain.repository.ServerCategoryJob
import com.pam.recap.domain.repository.ServerFinanceRepository
import com.pam.recap.domain.repository.ServerImportCandidate
import com.pam.recap.domain.repository.ServerImportCounts
import com.pam.recap.domain.repository.ServerImportJob
import com.pam.recap.domain.repository.ServerTransactionPage
import com.pam.recap.domain.repository.SyncCursorStore
import com.pam.recap.testing.FakeAnalysisRepository
import com.pam.recap.testing.FakeDeletionRepository
import com.pam.recap.testing.FakeGmailConnectionRepository
import com.pam.recap.testing.FakeImportRepository
import com.pam.recap.testing.FakeSessionRepository
import com.pam.recap.testing.FakeSummaryRepository
import com.pam.recap.testing.FakeTransactionRepository
import com.pam.recap.testing.SequentialIdGenerator
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * P1 sync/recovery ticket: a committed local run whose upload failed must be recovered by the NEXT
 * automatic tick, without the user tapping "Impor", and the mailbox watermark must never close a
 * range that still holds unacknowledged work.
 *
 * The tests use the real [ImportMonthUseCase] and the real [ServerImportUploader] over fakes for the
 * mailbox, Room-shaped repositories and the server. Only the durable journal is real (Robolectric
 * SharedPreferences + an XOR fake keystore), so "restart" means fresh instances over the same
 * durable bytes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PendingUploadRecoveryTest {

    private lateinit var context: Context

    private val registry = LivinTemplateRegistry()
    private val ownerA = "u_pending_a"
    private val ownerB = "u_pending_b"

    private class MutableClock(var instant: Instant) : ClockProvider {
        override fun now(): Instant = instant
    }

    private val clock = MutableClock(Instant.parse("2026-11-20T02:00:00Z"))

    private val sessions = FakeSessionRepository(session(ownerA))
    private val transactions = FakeTransactionRepository()
    private val imports = FakeImportRepository()
    private val summaries = FakeSummaryRepository()
    private val analyses = FakeAnalysisRepository()
    private val connections = FakeGmailConnectionRepository()
    private val deletion = FakeDeletionRepository()
    private val server = RecordingServer()
    private val mailbox = OwnerMailbox(sessions)
    private val cursors = DurableCursorStore()
    private val ids = SequentialIdGenerator("job")
    private val eventDates = mutableMapOf<String, Instant>()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().commit()
    }

    // ---------------------------------------------------------------- cases

    @Test
    fun `failure before the server commit is recovered by the next automatic run`() = runTest {
        val event = Instant.parse("2026-11-19T02:15:20Z")
        deliver(ownerA, "m-1", event)
        server.failBatches = true

        val process = newProcess()
        assertEquals(SyncOutcome.Failed("network"), process.sync.run(SyncTrigger.PERIODIC))

        // Nothing acknowledged, nothing advanced, but the run identity is durable.
        val first = fingerprintAt(ownerA, event)
        assertEquals(0, server.candidates.size)
        assertNull(cursors.read(ownerA)?.watermark)
        assertTrue(cursors.read(ownerA)?.uploadPending == true)
        val pending = process.journal.readPending(ownerA)!!
        assertEquals(listOf(first), pending.months.getValue("2026-11").fingerprints)
        assertTrue(pending.rangeComplete)
        val fetchesAfterCommit = mailbox.bodyFetches

        server.failBatches = false
        clock.instant = clock.instant.plus(31.minutes)
        val next = newProcess()
        assertTrue(next.sync.run(SyncTrigger.PERIODIC) is SyncOutcome.Completed)

        // Exactly the recorded snapshot reached the server, exactly once.
        assertEquals(listOf(first), server.candidateFingerprints())
        assertEquals(1, server.countByFingerprint()[first])
        // Retry reused the same server job and the same stable batch key/body.
        assertEquals(2, server.attempts.size)
        assertEquals(server.attempts[0].jobId, server.attempts[1].jobId)
        assertEquals(server.attempts[0].batchKey, server.attempts[1].batchKey)
        assertEquals(server.attempts[0].fingerprints, server.attempts[1].fingerprints)
        // Only after the ack may the cursor move, and no old body was downloaded again.
        assertNotNull(cursors.read(ownerA)?.watermark)
        assertEquals(fetchesAfterCommit, mailbox.bodyFetches)
        assertNull(next.journal.readPending(ownerA))
        assertEquals(1, transactionCount(ownerA))
    }

    @Test
    fun `lost response after the server commit is replayed with the same batch key`() = runTest {
        val event = Instant.parse("2026-11-19T02:15:20Z")
        deliver(ownerA, "m-1", event)
        server.loseNextBatchResponse = true

        val firstRun = newProcess()
        assertEquals(SyncOutcome.Failed("network"), firstRun.sync.run(SyncTrigger.PERIODIC))
        // The server committed, the client never learned it.
        val first = fingerprintAt(ownerA, event)
        assertEquals(listOf(first), server.candidateFingerprints())
        assertNull(cursors.read(ownerA)?.watermark)

        clock.instant = clock.instant.plus(31.minutes)
        val secondRun = newProcess()
        assertTrue(secondRun.sync.run(SyncTrigger.PERIODIC) is SyncOutcome.Completed)

        assertEquals(2, server.attempts.size)
        assertEquals(server.attempts[0].jobId, server.attempts[1].jobId)
        assertEquals(server.attempts[0].batchKey, server.attempts[1].batchKey)
        assertEquals(server.attempts[0].fingerprints, server.attempts[1].fingerprints)
        // The server deduped by batch key: the candidate exists exactly once, then the ack lands.
        assertEquals(1, server.countByFingerprint()[first])
        assertEquals(listOf(first), server.candidateFingerprints())
        assertNotNull(cursors.read(ownerA)?.watermark)
        assertNull(secondRun.journal.readPending(ownerA))
    }

    @Test
    fun `restart after the local commit resumes the run from the durable journal`() = runTest {
        deliver(ownerA, "m-1", Instant.parse("2026-11-19T02:15:20Z"))
        server.failBatches = true
        assertEquals(SyncOutcome.Failed("network"), newProcess().sync.run(SyncTrigger.PERIODIC))

        // A brand new process: new journal, new uploader, new use cases over the same storage.
        val restarted = newProcess()
        val visible = ImportUploadJournal(context, XorStore())
        assertNotSame(restarted.journal, visible)
        val durable = visible.readPending(ownerA)!!
        assertEquals(
            durable.months.getValue("2026-11").fingerprints,
            transactions.listByImportJob(ownerA, durable.localJobId).map { it.fingerprint },
        )

        server.failBatches = false
        clock.instant = clock.instant.plus(31.minutes)
        assertTrue(restarted.sync.run(SyncTrigger.PERIODIC) is SyncOutcome.Completed)

        assertEquals(durable.months.getValue("2026-11").fingerprints, server.candidateFingerprints())
        assertEquals(1, server.candidates.size)
        assertNotNull(cursors.read(ownerA)?.watermark)
        assertNull(restarted.journal.readPending(ownerA))
        // Dedupe kept the row on the original import job; the recovery must not have re-imported it.
        assertEquals(1, transactionCount(ownerA))
    }

    @Test
    fun `partial multi-month run resends only the unacknowledged month`() = runTest {
        // 1 Nov 02:00 Jakarta: the 6h recovery overlap straddles the October boundary.
        clock.instant = Instant.parse("2026-10-31T19:00:00Z")
        val octoberEvent = Instant.parse("2026-10-15T04:00:00Z")
        val novemberEvent = Instant.parse("2026-10-31T18:30:00Z")
        deliver(ownerA, "m-oct", octoberEvent)
        deliver(ownerA, "m-nov", novemberEvent)
        server.failFinalizeMonth = "2026-11"
        server.finalizeFailuresRemaining = 1

        val firstRun = newProcess()
        assertEquals(SyncOutcome.Failed("network"), firstRun.sync.run(SyncTrigger.PERIODIC))

        val october = fingerprintAt(ownerA, octoberEvent)
        val november = fingerprintAt(ownerA, novemberEvent)
        assertNull(cursors.read(ownerA)?.watermark)
        assertEquals(setOf(october, november), server.candidateFingerprints().toSet())
        val pending = firstRun.journal.readPending(ownerA)!!
        assertEquals("2026-10", pending.months.filterValues { it.acknowledged }.keys.single())
        assertNull(pending.months.getValue("2026-11").finalState)
        val octoberJob = pending.months.getValue("2026-10").serverJobId!!
        val novemberJob = pending.months.getValue("2026-11").serverJobId!!
        assertEquals("COMPLETE", server.finalizedStates[octoberJob])

        server.finalizeFailuresRemaining = 0
        clock.instant = clock.instant.plus(31.minutes)
        assertTrue(newProcess().sync.run(SyncTrigger.PERIODIC) is SyncOutcome.Completed)

        // The already-acknowledged October batch was not sent again; November kept its keys.
        assertEquals(1, server.attempts.count { it.jobId == octoberJob })
        val novemberAttempts = server.attempts.filter { it.jobId == novemberJob }
        assertEquals(2, novemberAttempts.size)
        assertEquals(novemberAttempts[0].batchKey, novemberAttempts[1].batchKey)
        assertEquals(novemberAttempts[0].fingerprints, novemberAttempts[1].fingerprints)
        assertEquals("COMPLETE", server.finalizedStates[novemberJob])
        // Exactly-once for every candidate of the pack, and no extra server job was created for
        // the already-acknowledged October month by re-sending it under the same batch key.
        assertEquals(1, server.countByFingerprint()[october])
        assertEquals(1, server.countByFingerprint()[november])
        assertEquals(1, server.createdJobs.count { it.second == "2026-10" })
        assertNotNull(cursors.read(ownerA)?.watermark)
    }

    @Test
    fun `pending run and a new email are both delivered exactly once`() = runTest {
        val firstEvent = Instant.parse("2026-11-19T02:15:20Z")
        deliver(ownerA, "m-1", firstEvent)
        server.failBatches = true
        val failed = newProcess()
        assertEquals(SyncOutcome.Failed("network"), failed.sync.run(SyncTrigger.PERIODIC))
        val failedJob = failed.journal.readPending(ownerA)!!.localJobId
        val fetchesAfterRunOne = mailbox.bodyFetches

        server.failBatches = false
        clock.instant = clock.instant.plus(31.minutes)
        val secondEvent = Instant.parse("2026-11-20T01:10:00Z")
        deliver(ownerA, "m-2", secondEvent)
        assertTrue(newProcess().sync.run(SyncTrigger.PERIODIC) is SyncOutcome.Completed)

        // Recovery went first (old snapshot), then the new delta range.
        val first = fingerprintAt(ownerA, firstEvent)
        val second = fingerprintAt(ownerA, secondEvent)
        assertEquals(listOf(first, second), server.candidateFingerprints())
        assertEquals(1, server.countByFingerprint()[first])
        assertEquals(1, server.countByFingerprint()[second])
        assertEquals(fetchesAfterRunOne + 1, mailbox.bodyFetches)
        // The recovered row stayed on its original local job; the new email got the new one.
        assertEquals(2, transactions.all().filter { it.ownerId == ownerA }.map { it.importJobId }.distinct().size)
        assertEquals(1, transactions.all().count { it.importJobId == failedJob && it.fingerprint == first })
        assertEquals(2L, cursors.read(ownerA)?.watermark?.highestUid)
    }

    @Test
    fun `the journal survives a restart and never leaks one owner's run to another`() = runTest {
        val event = Instant.parse("2026-11-19T02:15:20Z")
        deliver(ownerA, "m-1", event)
        server.failBatches = true
        assertEquals(SyncOutcome.Failed("network"), newProcess().sync.run(SyncTrigger.PERIODIC))
        val first = fingerprintAt(ownerA, event)
        // Journal is durable across instances.
        assertNotNull(ImportUploadJournal(context, XorStore()).readPending(ownerA))

        // Switch to B: B's tick must not upload A's run.
        server.failBatches = false
        sessions.signIn(session(ownerB))
        clock.instant = clock.instant.plus(31.minutes)
        val asB = newProcess()
        assertTrue(asB.sync.run(SyncTrigger.PERIODIC) is SyncOutcome.Completed)
        assertTrue(server.candidateFingerprints().isEmpty())
        assertNull(server.countByFingerprint()[first])
        assertNotNull(asB.journal.readPending(ownerA))

        // Switching back recovers A correctly.
        sessions.signIn(session(ownerA))
        clock.instant = clock.instant.plus(31.minutes)
        val asA = newProcess()
        assertTrue(asA.sync.run(SyncTrigger.PERIODIC) is SyncOutcome.Completed)
        assertEquals(listOf(first), server.candidateFingerprints())
        assertEquals(1, server.countByFingerprint()[first])
        assertNull(asA.journal.readPending(ownerA))
    }

    @Test
    fun `replaying a completed tick is idempotent`() = runTest {
        val event = Instant.parse("2026-11-19T02:15:20Z")
        deliver(ownerA, "m-1", event)
        assertTrue(newProcess().sync.run(SyncTrigger.PERIODIC) is SyncOutcome.Completed)
        val first = fingerprintAt(ownerA, event)
        assertNotNull(cursors.read(ownerA)?.watermark)
        assertEquals(listOf(first), server.candidateFingerprints())

        clock.instant = clock.instant.plus(31.minutes)
        val replay = newProcess()
        assertTrue(replay.sync.run(SyncTrigger.PERIODIC) is SyncOutcome.Completed)

        assertEquals(1, server.countByFingerprint()[first])
        assertEquals(listOf(first), server.candidateFingerprints())
        assertNull(replay.journal.readPending(ownerA))
        assertEquals(1, transactionCount(ownerA))
    }

    @Test
    fun `recovery replays only the snapshot - no full-month rescan and no old body refetch`() = runTest {
        val oneEvent = Instant.parse("2026-11-19T02:15:20Z")
        val twoEvent = Instant.parse("2026-11-19T05:40:00Z")
        deliver(ownerA, "m-1", oneEvent)
        deliver(ownerA, "m-2", twoEvent)
        server.failBatches = true
        val firstRun = newProcess()
        assertEquals(SyncOutcome.Failed("network"), firstRun.sync.run(SyncTrigger.PERIODIC))
        val fetchesAfterCommit = mailbox.bodyFetches
        val committedJob = firstRun.journal.readPending(ownerA)!!.localJobId
        assertEquals(2, transactions.listByImportJob(ownerA, committedJob).size)

        val one = fingerprintAt(ownerA, oneEvent)
        val two = fingerprintAt(ownerA, twoEvent)

        server.failBatches = false
        clock.instant = clock.instant.plus(31.minutes)
        assertTrue(newProcess().sync.run(SyncTrigger.PERIODIC) is SyncOutcome.Completed)

        // Exactly the two committed candidates, not the whole month twice.
        assertEquals(setOf(one, two), server.candidateFingerprints().toSet())
        assertEquals(2, server.candidates.size)
        assertEquals(1, server.countByFingerprint()[one])
        assertEquals(1, server.countByFingerprint()[two])
        // No body was downloaded again and the mailbox was never rescanned month-wide.
        assertEquals(fetchesAfterCommit, mailbox.bodyFetches)
        assertEquals(0, mailbox.fullScans)
        // The committed rows were never re-imported into a second local job.
        assertEquals(2, transactions.listByImportJob(ownerA, committedJob).size)
        assertEquals(2, transactionCount(ownerA))
    }

    @Test
    fun `a retry that commits rows cannot leave them unnameable for recovery`() = runTest {
        val event = Instant.parse("2026-11-19T02:15:20Z")
        deliver(ownerA, "m-1", event)

        // FR-21 retry does not go through the sync layer, so only ImportMonthUseCase can persist
        // the run identity. Call 1 is the pre-commit intent; every later journal write fails,
        // reproducing a storage/crash failure immediately after the rows were committed.
        val first = newProcess(FailingWrapStore(failFromCall = 2))
        val retry = RetryImportUseCase(first.importMonth, imports)
        val result = retry(ownerA, JakartaMonth(2026, 11))
        assertTrue(result is Outcome.Success)

        val committed = transactions.all().single()
        val intent = first.journal.readPending(ownerA)!!
        assertEquals(committed.importJobId, intent.localJobId)
        assertEquals(1, transactionCount(ownerA))
        assertEquals(0, server.candidates.size)

        // Restart: recovery names the committed rows by the durable importJobId and uploads them
        // exactly once. The watermark still waits for a delta tick that can prove its range.
        val fingerprint = fingerprintAt(ownerA, event)
        val second = newProcess()
        assertTrue(second.sync.resumeUpload(JakartaMonth(2026, 11)) is Outcome.Success)
        assertEquals(1, server.countByFingerprint()[fingerprint])
        assertNull(second.journal.readPending(ownerA))
        assertNull(cursors.read(ownerA)?.watermark)

        clock.instant = clock.instant.plus(31.minutes)
        assertTrue(second.sync.run(SyncTrigger.PERIODIC) is SyncOutcome.Completed)
        assertEquals("the retried run reached the server exactly once", 1, server.countByFingerprint()[fingerprint])
        assertNotNull(cursors.read(ownerA)?.watermark)
        assertEquals(1, transactionCount(ownerA))
    }

    // ---------------------------------------------------------------- fixtures

    private fun session(owner: String) = Session(
        ownerId = owner,
        displayName = null,
        mailboxLabel = null,
        signedInAt = Instant.parse("2026-11-01T00:00:00Z"),
        isDemo = false,
    )

    private class Process(
        val sync: IncrementalSyncUseCase,
        val journal: ImportUploadJournal,
        val importMonth: ImportMonthUseCase,
    )

    @Test
    fun `a keystore failure after the local commit cannot strand the run before the cursor advances`() = runTest {
        val event = Instant.parse("2026-11-19T02:15:20Z")
        deliver(ownerA, "m-1", event)

        // Call 1 writes the pre-commit intent; calls 2+ fail. That is the real crash window: the row
        // is committed, but the exact-snapshot pending update and the uploader's journal write both
        // fail. A retry that only looked at a fresh (deduped-to-empty) job would upload nothing.
        val first = newProcess(FailingWrapStore(failFromCall = 2))
        assertEquals(SyncOutcome.Failed("storage_unavailable"), first.sync.run(SyncTrigger.PERIODIC))

        val fingerprint = fingerprintAt(ownerA, event)
        assertEquals(0, server.candidates.size)
        assertNull(cursors.read(ownerA)?.watermark)
        // The durable identity exists BEFORE the commit, so the committed row is named even though
        // the snapshot update failed. Its fingerprint list is still empty (the pre-commit intent).
        val intent = first.journal.readPending(ownerA)!!
        assertEquals(transactions.all().single().importJobId, intent.localJobId)
        assertTrue(intent.months.getValue("2026-11").fingerprints.isEmpty())

        // Restart over the same bytes with a healthy keystore: the intent is the only lead, and the
        // rows are re-derived from Room by the recorded importJobId before anything is acknowledged.
        val second = newProcess()
        assertTrue(second.sync.resumeUpload(JakartaMonth(2026, 11)) is Outcome.Success)
        assertEquals(1, server.countByFingerprint()[fingerprint])
        assertNull(second.journal.readPending(ownerA))
        assertNull(
            "a recovered run cannot prove its range was complete, so the watermark must stay put",
            cursors.read(ownerA)?.watermark,
        )

        // Only the next tick may publish the watermark — and it must not send the row a second time.
        clock.instant = clock.instant.plus(31.minutes)
        assertTrue(second.sync.run(SyncTrigger.PERIODIC) is SyncOutcome.Completed)
        assertEquals("the server saw the committed row exactly once", 1, server.countByFingerprint()[fingerprint])
        assertEquals(listOf(fingerprint), server.candidateFingerprints())
        assertNotNull(cursors.read(ownerA)?.watermark)
        assertEquals(1, transactionCount(ownerA))
    }

    @Test
    fun `a build without a backend keeps the pending run instead of dropping it`() = runTest {
        val event = Instant.parse("2026-11-19T02:15:20Z")
        deliver(ownerA, "m-1", event)
        server.failBatches = true

        val first = newProcess()
        assertEquals(SyncOutcome.Failed("network"), first.sync.run(SyncTrigger.PERIODIC))
        val fingerprint = fingerprintAt(ownerA, event)
        server.failBatches = false
        // The backend address is gone (misconfigured build, URL removed). Disabled must never mean
        // "acknowledged": the record stays and the cursor does not move.
        server.isEnabled = false

        clock.instant = clock.instant.plus(31.minutes)
        val second = newProcess()
        assertEquals(SyncOutcome.Failed("configuration"), second.sync.run(SyncTrigger.PERIODIC))
        assertNull(cursors.read(ownerA)?.watermark)
        assertNotNull("the record must survive a build with no backend", second.journal.readPending(ownerA))
        assertEquals(0, server.candidates.size)

        // Restoring the address recovers the same run exactly once.
        server.isEnabled = true
        clock.instant = clock.instant.plus(31.minutes)
        val third = newProcess()
        assertTrue(third.sync.run(SyncTrigger.PERIODIC) is SyncOutcome.Completed)
        assertEquals(1, server.countByFingerprint()[fingerprint])
        assertNotNull(cursors.read(ownerA)?.watermark)
        assertEquals(1, transactionCount(ownerA))
    }

    @Test
    fun `a pending snapshot that no longer matches the local rows is never acknowledged as EMPTY`() = runTest {
        val event = Instant.parse("2026-11-19T02:15:20Z")
        deliver(ownerA, "m-1", event)
        server.failBatches = true

        val first = newProcess()
        assertEquals(SyncOutcome.Failed("network"), first.sync.run(SyncTrigger.PERIODIC))
        assertEquals(1, first.journal.readPending(ownerA)!!.months.getValue("2026-11").fingerprints.size)

        // The committed row disappeared from the local snapshot (eviction/corruption) while the
        // durable record still promises it. An EMPTY acknowledgement would clear the record and let
        // the cursor skip it forever, so the uploader must refuse instead.
        transactions.replaceAll(emptyList())
        server.failBatches = false
        clock.instant = clock.instant.plus(31.minutes)
        val second = newProcess()
        assertEquals(SyncOutcome.Failed("pending_snapshot_incomplete"), second.sync.run(SyncTrigger.PERIODIC))

        assertNotNull(second.journal.readPending(ownerA))
        assertNull(cursors.read(ownerA)?.watermark)
        assertEquals(0, server.candidates.size)
    }

    /** A fresh "process": durable stores are shared, every in-memory collaborator is new. */
    private fun newProcess(store: WrappedSecretStore = XorStore()): Process {
        val journal = ImportUploadJournal(context, store)
        val uploader = ServerImportUploader(server, transactions, journal)
        val importMonth = ImportMonthUseCase(
            gmailSource = mailbox,
            queryBuilder = LivinQueryBuilder(registry),
            parser = FakeParser { id -> eventDates.getValue(id) },
            fingerprints = FingerprintFactory { owner -> "test-key::$owner".toByteArray() },
            categoryRules = MerchantCategoryRules(),
            transactions = transactions,
            imports = imports,
            summaries = summaries,
            analyses = analyses,
            gmailConnection = connections,
            deletion = deletion,
            clock = clock,
            idGenerator = ids,
            journal = journal,
        )
        val status = SyncStatusHolder()
        val sync = IncrementalSyncUseCase(
            sessions = sessions,
            gmail = mailbox,
            importMonth = importMonth,
            uploader = uploader,
            imports = imports,
            cursors = cursors,
            deletion = deletion,
            clock = clock,
            status = status,
            config = AutoSyncConfig(interval = 30.minutes, foregroundCooldown = 5.minutes),
            transactions = transactions,
            journal = journal,
        )
        return Process(sync, journal, importMonth)
    }

    /** Delivers one message to [owner] and pins the transaction date the parser reports. */
    private fun deliver(owner: String, id: String, eventAt: Instant) {
        eventDates[id] = eventAt
        mailbox.deliver(owner, RawMessage(
            messageId = id,
            sender = "Livin by Mandiri <notifikasi@bankmandiri.co.id>",
            subject = "Transaksi QRIS",
            internalDate = clock.now().minus(1.minutes),
            textParts = emptyList(),
        ))
    }

    /** The committed row for a message is identified by its (unique) transaction date. */
    private fun fingerprintAt(owner: String, eventAt: Instant): String = transactions.all()
        .single { it.ownerId == owner && it.eventAt == eventAt }
        .fingerprint

    private fun transactionCount(owner: String) = transactions.all().count { it.ownerId == owner }

    private class XorStore : WrappedSecretStore {
        override fun wrap(plainSecret: ByteArray): String = Base64.encodeToString(
            plainSecret.map { (it.toInt() xor 0x21).toByte() }.toByteArray(), Base64.NO_WRAP,
        )

        override fun unwrap(wrapped: String): ByteArray = Base64.decode(wrapped, Base64.NO_WRAP)
            .map { (it.toInt() xor 0x21).toByte() }.toByteArray()
    }

    /**
     * Keystore double that dies on a chosen write, like a locked/invalidated Keystore entry. Reads
     * keep working, so the tests can inspect the durable bytes the previous "process" left behind.
     */
    private class FailingWrapStore(private val failFromCall: Int) : WrappedSecretStore {
        private val delegate = XorStore()
        var calls = 0
            private set

        override fun wrap(plainSecret: ByteArray): String {
            calls++
            if (calls >= failFromCall) throw IllegalStateException("keystore unavailable")
            return delegate.wrap(plainSecret)
        }

        override fun unwrap(wrapped: String): ByteArray = delegate.unwrap(wrapped)
    }

    /**
     * Owner-scoped IMAP-shaped mailbox: uids ascend per folder, only the delta path reaches bodies,
     * and a month-wide `search` fails loudly. Full scans are therefore impossible in these tests.
     */
    private class OwnerMailbox(private val sessions: FakeSessionRepository) : GmailSource {
        private class Folder { val messages = mutableListOf<RawMessage>() }

        private val folders = mutableMapOf<String, Folder>()
        var bodyFetches = 0
        var fullScans = 0

        override suspend fun authorize(): Outcome<Unit> = Outcome.Success(Unit)

        override fun connectionState(): Flow<GmailConnectionState> = flowOf(GmailConnectionState.CONNECTED)

        override suspend fun search(query: LivinQuery, pageToken: String?): Outcome<GmailPage> {
            // Backfill/FR-21 retry path: the same mailbox without the delta watermark window.
            fullScans++
            val owner = sessions.current()?.ownerId
                ?: return Outcome.Failure(AppError.Configuration("not_signed_in"))
            val folder = folders.getOrPut(owner) { Folder() }
            val hits = folder.messages.withIndex().filter { (_, raw) ->
                raw.internalDate != null &&
                    raw.internalDate >= query.fromInclusive &&
                    raw.internalDate < query.toExclusive
            }.sortedByDescending { it.index }
            val offset = pageToken?.substringAfter("offset:")?.toIntOrNull() ?: 0
            val page = hits.drop(offset).take(query.pageSize)
            return Outcome.Success(
                GmailPage(
                    messages = page.map { (index, raw) ->
                        GmailMessageRef(
                            id = "$owner|${index + 1}",
                            internalDate = raw.internalDate,
                        )
                    },
                    nextPageToken = (offset + page.size).takeIf { it < hits.size }?.let { "offset:$it" },
                ),
            )
        }

        override suspend fun fetchMessage(id: String): Outcome<RawMessage> {
            bodyFetches++
            val owner = id.substringBefore('|')
            val uid = id.substringAfter('|').toLongOrNull()
                ?: return Outcome.Failure(AppError.NotFound("bad_id"))
            val raw = folders[owner]?.messages?.getOrNull((uid - 1).toInt())
                ?: return Outcome.Failure(AppError.NotFound("gone"))
            return Outcome.Success(raw)
        }

        override suspend fun disconnect(): Outcome<Unit> = Outcome.Success(Unit)

        override suspend fun watermark(): Outcome<MailboxWatermark> {
            val owner = sessions.current()?.ownerId
                ?: return Outcome.Failure(AppError.Configuration("not_signed_in"))
            val folder = folders.getOrPut(owner) { Folder() }
            return Outcome.Success(MailboxWatermark(UID_VALIDITY, folder.messages.size.toLong()))
        }

        override suspend fun searchDelta(
            query: LivinQuery,
            since: MailboxWatermark?,
            upTo: MailboxWatermark?,
            pageToken: String?,
        ): Outcome<GmailPage> {
            val owner = sessions.current()?.ownerId
                ?: return Outcome.Failure(AppError.Configuration("not_signed_in"))
            val folder = folders.getOrPut(owner) { Folder() }
            val floor = since?.takeIf { it.uidValidity == UID_VALIDITY }?.highestUid ?: 0L
            val ceiling = upTo?.takeIf { it.uidValidity == UID_VALIDITY }?.highestUid
            val hits = folder.messages.withIndex().filter { (index, raw) ->
                val uid = (index + 1).toLong()
                uid > floor && (ceiling == null || uid <= ceiling) &&
                    raw.internalDate != null &&
                    raw.internalDate >= query.fromInclusive &&
                    raw.internalDate < query.toExclusive
            }.sortedByDescending { it.index }
            val offset = pageToken?.substringAfter("offset:")?.toIntOrNull() ?: 0
            val page = hits.drop(offset).take(query.pageSize)
            return Outcome.Success(
                GmailPage(
                    messages = page.map { (index, raw) ->
                        GmailMessageRef(
                            id = "$owner|${index + 1}",
                            internalDate = raw.internalDate,
                        )
                    },
                    nextPageToken = if (offset + page.size < hits.size) "offset:${offset + page.size}" else null,
                    resultSizeEstimate = hits.size,
                ),
            )
        }

        fun deliver(owner: String, raw: RawMessage) {
            folders.getOrPut(owner) { Folder() }.messages += raw
        }

        private companion object { const val UID_VALIDITY = 42L }
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

    /**
     * Server double that records every attempt (including failed ones) so the tests can prove the
     * batch key/body are stable and that an acknowledged month is never resent.
     */
    private class RecordingServer : ServerFinanceRepository {
        override var isEnabled = true

        var failBatches = false
        var loseNextBatchResponse = false
        var failFinalizeMonth: String? = null
        var finalizeFailuresRemaining = 0

        class Attempt(
            val jobId: String,
            val monthId: String,
            val batchKey: String,
            val pageOrdinal: Int,
            val fingerprints: List<String>,
        )

        class Candidate(val jobId: String, val fingerprint: String)

        val attempts = mutableListOf<Attempt>()
        val candidates = mutableListOf<Candidate>()
        val createdJobs = mutableListOf<Pair<String, String>>()
        val finalizedStates = mutableMapOf<String, String>()
        private val appliedKeys = mutableSetOf<String>()
        private val jobMonths = mutableMapOf<String, String>()
        private var jobCounter = 0

        fun candidateFingerprints(): List<String> = candidates.map { it.fingerprint }

        fun countByFingerprint(): Map<String, Int> = candidates.groupingBy { it.fingerprint }.eachCount()

        override suspend fun createImportJob(
            ownerId: String,
            month: JakartaMonth,
            templateVersion: String,
        ): Outcome<ServerImportJob> {
            val id = "server-job-${month.id()}-${jobCounter++}"
            jobMonths[id] = month.id()
            createdJobs += id to month.id()
            return Outcome.Success(ServerImportJob(id, month.id(), "CREATED"))
        }

        override suspend fun importBatch(
            ownerId: String,
            jobId: String,
            batchKey: String,
            pageOrdinal: Int,
            candidates: List<ServerImportCandidate>,
        ): Outcome<ServerImportCounts> {
            attempts += Attempt(jobId, jobMonths[jobId].orEmpty(), batchKey, pageOrdinal, candidates.map { it.fingerprint })
            if (failBatches) return Outcome.Failure(AppError.Network("import_batch_failed"))
            if (batchKey in appliedKeys) return Outcome.Success(counts())
            appliedKeys += batchKey
            candidates.forEach { this.candidates += Candidate(jobId, it.fingerprint) }
            if (loseNextBatchResponse) {
                loseNextBatchResponse = false
                return Outcome.Failure(AppError.Network("response_lost"))
            }
            return Outcome.Success(counts())
        }

        override suspend fun finalizeImportJob(
            ownerId: String,
            jobId: String,
            state: String,
            errorCode: String?,
        ): Outcome<ServerImportJob> {
            val month = jobMonths[jobId].orEmpty()
            if (failFinalizeMonth == month && finalizeFailuresRemaining > 0) {
                finalizeFailuresRemaining--
                return Outcome.Failure(AppError.Network("finalize_failed"))
            }
            finalizedStates[jobId] = state
            return Outcome.Success(ServerImportJob(jobId, month, state))
        }

        private fun counts() = ServerImportCounts(1, 1, 0, 0, 0, 1, "RUNNING")

        override suspend fun categories(ownerId: String): Outcome<List<ServerCategory>> =
            Outcome.Success(listOf(ServerCategory("cat_food", "food", "Makanan", "EXPENSE")))

        override suspend fun createManual(
            ownerId: String,
            draft: ManualTransactionDraft,
            idempotencyKey: String,
        ): Outcome<com.pam.recap.domain.model.Transaction> =
            Outcome.Failure(AppError.Configuration("unused"))

        override suspend fun listMonth(
            ownerId: String,
            month: JakartaMonth,
            limit: Int,
            cursor: String?,
        ): Outcome<ServerTransactionPage> = Outcome.Success(ServerTransactionPage(emptyList()))

        override suspend fun get(
            ownerId: String,
            transactionId: String,
        ): Outcome<com.pam.recap.domain.model.Transaction> =
            Outcome.Failure(AppError.NotFound("unused"))

        override suspend fun updateCategory(
            ownerId: String,
            transactionId: String,
            categoryId: String,
            revision: Int,
        ): Outcome<com.pam.recap.domain.model.Transaction> =
            Outcome.Failure(AppError.Configuration("unused"))

        override suspend fun summary(ownerId: String, month: JakartaMonth) =
            Outcome.Failure(AppError.Configuration("unused"))

        override suspend fun classifyTransactions(
            ownerId: String,
            transactionIds: List<String>,
        ): Outcome<List<ServerCategoryJob>> = Outcome.Success(emptyList())

        override suspend fun enqueueCategories(
            ownerId: String,
            transactionIds: List<String>,
        ): Outcome<com.pam.recap.domain.repository.CategoryEnqueueResult> =
            Outcome.Success(com.pam.recap.domain.repository.CategoryEnqueueResult(0, 0))

        override suspend fun drainCategories(
            ownerId: String,
        ): Outcome<com.pam.recap.domain.repository.CategoryDrainResult> =
            Outcome.Success(com.pam.recap.domain.repository.CategoryDrainResult("empty", 0, 0))
    }

    /** Room-shaped durable cursor store: same instance across "processes", like SharedPreferences. */
    private class DurableCursorStore : SyncCursorStore {
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

    private companion object {
        const val PREFS = "pam_backend_session"
    }
}
