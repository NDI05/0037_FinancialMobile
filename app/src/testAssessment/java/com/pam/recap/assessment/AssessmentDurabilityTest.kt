package com.pam.recap.assessment

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import com.pam.recap.core.money.Money
import com.pam.recap.core.result.AppError
import com.pam.recap.core.result.Outcome
import com.pam.recap.core.security.WrappedSecretStore
import com.pam.recap.core.time.JakartaMonth
import com.pam.recap.core.util.PamJson
import com.pam.recap.data.assessment.AssessmentConsentRepository
import com.pam.recap.data.assessment.AssessmentDurableState
import com.pam.recap.data.assessment.AssessmentGmailConnectionRepository
import com.pam.recap.data.assessment.AssessmentImportRepository
import com.pam.recap.data.assessment.AssessmentOutbox
import com.pam.recap.data.assessment.AssessmentTransactionRepository
import com.pam.recap.data.backend.BackendConfig
import com.pam.recap.data.security.ImportUploadJournal
import com.pam.recap.data.work.ForegroundAutoCategoryScheduler
import com.pam.recap.data.work.ForegroundAutoSyncScheduler
import com.pam.recap.domain.model.Category
import com.pam.recap.domain.model.CategoryOrigin
import com.pam.recap.domain.model.ConsentDecision
import com.pam.recap.domain.model.ConsentPurpose
import com.pam.recap.domain.model.Direction
import com.pam.recap.domain.model.GmailConnection
import com.pam.recap.domain.model.GmailConnectionState
import com.pam.recap.domain.model.ImportJob
import com.pam.recap.domain.model.ImportState
import com.pam.recap.domain.model.ParseStatus
import com.pam.recap.domain.model.Transaction
import com.pam.recap.domain.model.TransactionSource
import com.pam.recap.domain.repository.BackendSessionRepository
import com.pam.recap.domain.repository.CategoryDrainResult
import com.pam.recap.domain.repository.CategoryEnqueueResult
import com.pam.recap.domain.repository.ManualTransactionDraft
import com.pam.recap.domain.repository.ServerCategory
import com.pam.recap.domain.repository.ServerCategoryJob
import com.pam.recap.domain.repository.ServerFinanceRepository
import com.pam.recap.domain.repository.ServerImportCandidate
import com.pam.recap.domain.repository.ServerImportCounts
import com.pam.recap.domain.repository.ServerImportJob
import com.pam.recap.domain.repository.ServerTransactionPage
import com.pam.recap.domain.usecase.ServerImportUploader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.time.Instant

/**
 * Post-audit evidence for the S4.4 assessment variant (P4-1, P4-2, P4-4, P4-5):
 *
 * - outbox dedupe follows Room's *global* `(ownerId, fingerprint)` unique index, not one bucket;
 * - a failed durable write is never reported as a successful insert/replace;
 * - the consent ledger hydrates from `GET /v1/consents` and survives a restart, so the app does not
 *   ask for consent again; mailbox facts survive a restart as well (never any token);
 * - import jobs plus the upload journal restart cleanly and stay uploadable end-to-end;
 * - the assessment scheduler classes are honest foreground no-ops (no `androidx.work` here).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AssessmentDurabilityTest {

    private lateinit var context: Context
    private lateinit var secrets: WrappedSecretStore

    private val ownerId = "u_assessment"
    private val month = JakartaMonth(2026, 11)
    private val now = Instant.parse("2026-11-20T02:00:00Z")

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        listOf(
            "pam_assessment_outbox",
            "pam_backend_session",
            "pam_assessment_state",
        ).forEach { name ->
            context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
        }
        secrets = XorSecretStore()
    }

    @After
    fun tearDown() {
        // Robolectric shares the process-level Application between tests in a class; leaving the
        // wrapped state behind would make the restart tests order-dependent.
        context.getSharedPreferences("pam_assessment_state", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    // ---------------------------------------------------------------- P4-1 global dedupe

    @Test
    fun `dedupe is global per owner across import jobs so totals are never double counted`() = runTest {
        val repository = assessmentRepository()
        repository.insertIgnoringDuplicates(
            ownerId,
            listOf(
                transaction(id = "tx-1", fingerprint = "a".repeat(64), jobId = "job-1"),
                transaction(id = "tx-2", fingerprint = "b".repeat(64), jobId = "job-1"),
            ),
        )

        // A re-fetch through a different job (retry import, late arrival, manual-vs-email) sends the
        // same email fingerprint under a new import job id. Room's unique index would reject it;
        // the outbox must reject it too instead of staging a second financial row.
        val crossJob = repository.insertIgnoringDuplicates(
            ownerId,
            listOf(
                transaction(id = "tx-1-retry", fingerprint = "a".repeat(64), jobId = "job-2"),
                transaction(id = "tx-3", fingerprint = "c".repeat(64), jobId = "job-2"),
            ),
        )
        assertEquals(1, crossJob.inserted)
        assertEquals(1, crossJob.ignored)

        val rows = repository.listMonth(ownerId, month)
        assertEquals("the duplicate fingerprint must not add a row", 3, rows.size)
        assertEquals(listOf("tx-3"), repository.listByImportJob(ownerId, "job-2").map { it.id })

        val totals = repository.monthTotals(ownerId, month)
        assertEquals(3, totals.transactionCount)
        assertEquals(3, totals.debitCount)
        assertEquals(Money(75_000), totals.grossOutflow)

        // The index is per owner: another owner may legitimately keep the same fingerprint.
        val otherOwner = repository.insertIgnoringDuplicates(
            "u_other",
            listOf(transaction(id = "tx-1-other", fingerprint = "a".repeat(64), jobId = "job-9", owner = "u_other")),
        )
        assertEquals(1, otherOwner.inserted)
        assertEquals(0, otherOwner.ignored)
    }

    @Test
    fun `dedupe survives a restart because it is rebuilt from durable rows`() = runTest {
        assessmentRepository().insertIgnoringDuplicates(
            ownerId,
            listOf(transaction(id = "tx-1", fingerprint = "a".repeat(64), jobId = "job-1")),
        )
        val restarted = assessmentRepository()
        val retry = restarted.insertIgnoringDuplicates(
            ownerId,
            listOf(transaction(id = "tx-1-again", fingerprint = "a".repeat(64), jobId = "job-2")),
        )
        assertEquals(0, retry.inserted)
        assertEquals(1, retry.ignored)
        assertEquals(1, restarted.listMonth(ownerId, month).size)
    }

    // ---------------------------------------------------------------- P4-5 durable writes

    @Test
    fun `a failed commit is not reported as a successful outbox write`() = runTest {
        val failing = AssessmentOutbox(FailingPrefsContext(context), secrets)
        val row = AssessmentOutbox.StagedTransaction.fromDomain(
            transaction(id = "tx-1", fingerprint = "a".repeat(64), jobId = "job-1"),
        )

        assertEquals(0, failing.insert(ownerId, listOf(row)).inserted)
        assertFalse("replace must report the failed commit", failing.replace(ownerId, row))
        assertEquals(
            "a revision that was not persisted must not advance",
            0,
            failing.bumpMonthRevision(ownerId, month),
        )
        assertEquals(0, failing.rows(ownerId).size)
    }

    // ---------------------------------------------------------------- P4-2 durable consent

    @Test
    fun `consent is hydrated from the server so it is not asked again after a restart`() = runTest {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """
                    {"consents":[{"id":"c-1","purpose":"GMAIL_READ","decision":"GRANTED",
                    "noticeVersion":"notice-v2","decidedAt":"2026-11-01T00:00:00Z"}]}
                    """.trimIndent(),
                ),
        )
        server.start()
        try {
            val endpoints = BackendConfig(server.url("/").toString().trimEnd('/'), "")
            val firstProcess = consentRepository(endpoints)
            assertTrue(
                "a GRANTED server ledger must satisfy the session gate without a new prompt",
                firstProcess.hasGranted(ownerId, ConsentPurpose.GMAIL_READ),
            )
            val request = server.takeRequest()
            assertEquals("/v1/consents", request.path)
            assertEquals("Bearer token-1", request.getHeader("authorization"))
            assertEquals(1, firstProcess.observeLedger(ownerId).first().size)

            // Restart with no backend address at all: the decision must come back from the wrapped
            // durable ledger, not from the network and not from a fresh "no consent" answer.
            val restarted = consentRepository(BackendConfig("", ""))
            val restored = restarted.latest(ownerId, ConsentPurpose.GMAIL_READ)
            assertEquals(ConsentDecision.GRANTED, restored?.decision)
            assertTrue(restarted.hasGranted(ownerId, ConsentPurpose.GMAIL_READ))
            assertNull(restarted.latest(ownerId, ConsentPurpose.AI_PROCESSING))
        } finally {
            server.shutdown()
        }
    }

    // ---------------------------------------------------------------- P4-2 durable mailbox/import

    @Test
    fun `mailbox connection facts survive a restart and are encrypted at rest`() = runTest {
        val connection = GmailConnection(
            ownerId = ownerId,
            state = GmailConnectionState.CONNECTED,
            mailboxLabel = "user@example.com",
            scope = GmailConnection.READONLY_SCOPE,
            connectedAt = now,
            lastImportAt = now,
            lastAiAt = null,
            updatedAt = now,
        )
        AssessmentGmailConnectionRepository(AssessmentDurableState(context, secrets), PamJson)
            .save(connection)

        val restarted = AssessmentGmailConnectionRepository(
            AssessmentDurableState(context, secrets),
            PamJson,
        )
        val restored = restarted.get(ownerId)
        assertEquals(GmailConnectionState.CONNECTED, restored?.state)
        assertEquals("user@example.com", restored?.mailboxLabel)
        assertEquals(now, restored?.lastImportAt)

        val raw = context.getSharedPreferences("pam_assessment_state", Context.MODE_PRIVATE)
            .all.values.filterIsInstance<String>().joinToString("\n")
        assertTrue("durable assessment state must exist", raw.isNotBlank())
        assertFalse("mailbox label must not be plaintext", raw.contains("user@example.com"))
    }

    @Test
    fun `import job counters survive a restart without any finance rows on disk`() = runTest {
        val job = importJob(id = "job-1")
        AssessmentImportRepository(AssessmentDurableState(context, secrets), PamJson).create(job)

        val restarted = AssessmentImportRepository(AssessmentDurableState(context, secrets), PamJson)
        val restored = restarted.get("job-1")
        assertEquals(ImportState.COMPLETE, restored?.state)
        assertEquals(2, restored?.importedCount)
        assertEquals(month, restarted.latestForMonth(ownerId, month)?.month)

        val raw = context.getSharedPreferences("pam_assessment_state", Context.MODE_PRIVATE)
            .all.values.filterIsInstance<String>().joinToString("\n")
        // Import metadata is persisted, but the outbox (the only place rows live) is still empty and
        // the durable state itself is wrapped, so no description/receipt text is recoverable.
        assertFalse(raw.contains("QRIS BAKSO"))
    }

    // ---------------------------------------------------------------- P4-4 end to end

    @Test
    fun `assessment transaction repository uploads staged rows to an enabled server once`() = runTest {
        val repository = assessmentRepository()
        val rows = listOf(
            transaction(id = "tx-1", fingerprint = "a".repeat(64), jobId = "job-1"),
            transaction(id = "tx-2", fingerprint = "b".repeat(64), jobId = "job-1"),
        )
        repository.insertIgnoringDuplicates(ownerId, rows)
        val server = RecordingServer()
        val journal = ImportUploadJournal(context, secrets)
        val uploader = ServerImportUploader(server = server, transactions = repository, journal = journal)

        val outcome = uploader.upload(ownerId, importJob(id = "job-1"))

        assertTrue("upload must succeed against an enabled fake server, got $outcome", outcome is Outcome.Success)
        assertEquals(listOf("a".repeat(64), "b".repeat(64)), server.sentFingerprints.sorted())
        assertEquals(1, server.batches.size)
        assertEquals("COMPLETE", server.finalStates.single())
        assertNull("acknowledged run must clear the pending journal", journal.readPending(ownerId))

        // The next lifecycle tick runs the same pipeline with a fresh (deduped-to-empty) local job.
        // It must create/close an EMPTY server job but never resend the acknowledged rows.
        val nextTick = uploader.upload(ownerId, importJob(id = "job-2"))
        assertTrue("next tick must not fail, got $nextTick", nextTick is Outcome.Success)
        assertEquals("acknowledged rows must not be re-sent on the next tick", 1, server.batches.size)
        assertEquals(listOf("a".repeat(64), "b".repeat(64)), server.sentFingerprints.sorted())

        // The explicit recovery entry point must also be a no-op once the run is acknowledged.
        val resumed = uploader.resumePending(ownerId)
        assertEquals(Outcome.Success("no_pending"), resumed)
        assertEquals("recovery entry point must not resend", 1, server.batches.size)
        assertNull(journal.readPending(ownerId))
    }

    @Test
    fun `a throwing keystore wrapper is a durable write failure, not a crash`() = runTest {
        val failingSecrets = ThrowingSecretStore()
        val durable = AssessmentDurableState(context, failingSecrets)
        assertFalse("wrap() failure must return false", durable.write(ownerId, "consent_ledger", "[]"))
        assertNull(durable.read(ownerId, "consent_ledger"))

        val outbox = AssessmentOutbox(context, failingSecrets)
        val row = AssessmentOutbox.StagedTransaction.fromDomain(
            transaction(id = "tx-1", fingerprint = "a".repeat(64), jobId = "job-1"),
        )
        assertEquals(0, outbox.insert(ownerId, listOf(row)).inserted)
        assertFalse("replace must survive a throwing wrapper", outbox.replace(ownerId, row))
        assertEquals(
            "an unwritable revision must not advance",
            0,
            outbox.bumpMonthRevision(ownerId, month),
        )
        assertEquals(0, outbox.rows(ownerId).size)
    }

    @Test
    fun `assessment schedulers are honest no-ops without WorkManager`() {
        val sync = ForegroundAutoSyncScheduler()
        val category = ForegroundAutoCategoryScheduler()
        // Contract: callable from the same lifecycle call sites as the standard schedulers, but
        // they never claim (or need) an OS-owned schedule.
        sync.ensureScheduled(ownerId)
        sync.ensureScheduled(ownerId)
        sync.cancelAll()
        category.ensureScheduled(ownerId)
        category.cancelAll()
        assertFalse("WorkManager must not be linked by the assessment schedulers", workManagerPresent())
    }

    // ---------------------------------------------------------------- helpers

    private fun workManagerPresent(): Boolean = try {
        Class.forName("androidx.work.WorkManager")
        true
    } catch (_: ClassNotFoundException) {
        false
    }

    private fun assessmentRepository(): AssessmentTransactionRepository =
        AssessmentTransactionRepository(AssessmentOutbox(context, secrets))

    private fun consentRepository(endpoints: BackendConfig): AssessmentConsentRepository =
        AssessmentConsentRepository(
            durable = AssessmentDurableState(context, secrets),
            endpoints = endpoints,
            client = OkHttpClient(),
            sessions = FakeSessions(),
            json = PamJson,
            ioDispatcher = Dispatchers.Unconfined,
        )

    private fun transaction(
        id: String,
        fingerprint: String,
        jobId: String,
        owner: String = ownerId,
    ): Transaction = Transaction(
        id = id,
        ownerId = owner,
        direction = Direction.DEBIT,
        source = TransactionSource.EMAIL,
        amount = Money(25_000),
        currency = "IDR",
        eventAt = now,
        receivedAt = now,
        description = "QRIS BAKSO",
        merchantLabel = "BAKSO UJI",
        acquirer = null,
        status = ParseStatus.POSTED,
        fingerprint = fingerprint,
        parserVersion = "livin-v1",
        parserConfidence = 0.9f,
        category = Category.FOOD,
        categoryOrigin = CategoryOrigin.IMPORTED,
        categoryConfidence = 0.8f,
        categoryUpdatedAt = now,
        isInternalTransfer = false,
        linkedRefundOf = null,
        note = "catatan pribadi",
        importJobId = jobId,
        revision = 1,
        createdAt = now,
        updatedAt = now,
    )

    private fun importJob(id: String): ImportJob = ImportJob(
        id = id,
        ownerId = ownerId,
        month = month,
        state = ImportState.COMPLETE,
        pagesProcessed = 1,
        pagesExpected = 1,
        matchedCount = 2,
        importedCount = 2,
        duplicateCount = 0,
        skippedCount = 0,
        unresolvedCount = 0,
        templateVersion = "livin-v1",
        startedAt = now,
        finishedAt = now,
    )

    private class FakeSessions : BackendSessionRepository {
        override val isEnabled: Boolean = true

        override suspend fun register(
            email: String,
            password: String,
            displayName: String?,
        ): Outcome<Unit> = Outcome.Success(Unit)

        override suspend fun login(email: String, password: String): Outcome<Unit> = Outcome.Success(Unit)

        override suspend fun token(ownerId: String): Outcome<String> = Outcome.Success("token-1")

        override suspend fun revoke() = Unit

        override fun clear() = Unit
    }

    /** Enabled fake server that records what the uploader actually sent. */
    private class RecordingServer : ServerFinanceRepository {
        override val isEnabled: Boolean = true

        val batches = mutableListOf<List<ServerImportCandidate>>()
        val finalStates = mutableListOf<String>()
        val sentFingerprints: List<String> get() = batches.flatten().map { it.fingerprint }

        override suspend fun categories(ownerId: String): Outcome<List<ServerCategory>> =
            Outcome.Success(emptyList())

        override suspend fun createManual(
            ownerId: String,
            draft: ManualTransactionDraft,
            idempotencyKey: String,
        ): Outcome<Transaction> = unsupported()

        override suspend fun listMonth(
            ownerId: String,
            month: JakartaMonth,
            limit: Int,
            cursor: String?,
        ): Outcome<ServerTransactionPage> = unsupported()

        override suspend fun get(ownerId: String, transactionId: String): Outcome<Transaction> =
            unsupported()

        override suspend fun updateCategory(
            ownerId: String,
            transactionId: String,
            categoryId: String,
            revision: Int,
        ): Outcome<Transaction> = unsupported()

        override suspend fun summary(
            ownerId: String,
            month: JakartaMonth,
        ): Outcome<com.pam.recap.domain.model.MonthlySummary> = unsupported()

        override suspend fun classifyTransactions(
            ownerId: String,
            transactionIds: List<String>,
        ): Outcome<List<ServerCategoryJob>> = unsupported()

        override suspend fun enqueueCategories(
            ownerId: String,
            transactionIds: List<String>,
        ): Outcome<CategoryEnqueueResult> = unsupported()

        override suspend fun drainCategories(ownerId: String): Outcome<CategoryDrainResult> =
            unsupported()

        override suspend fun createImportJob(
            ownerId: String,
            month: JakartaMonth,
            templateVersion: String,
        ): Outcome<ServerImportJob> =
            Outcome.Success(ServerImportJob(id = "srv-1", monthId = month.id(), state = "RUNNING"))

        override suspend fun importBatch(
            ownerId: String,
            jobId: String,
            batchKey: String,
            pageOrdinal: Int,
            candidates: List<ServerImportCandidate>,
        ): Outcome<ServerImportCounts> {
            batches += candidates
            return Outcome.Success(
                ServerImportCounts(
                    matched = candidates.size,
                    imported = candidates.size,
                    duplicates = 0,
                    skipped = 0,
                    unresolved = 0,
                    inputRevision = 0,
                    jobState = "RUNNING",
                ),
            )
        }

        override suspend fun finalizeImportJob(
            ownerId: String,
            jobId: String,
            state: String,
            errorCode: String?,
        ): Outcome<ServerImportJob> {
            finalStates += state
            return Outcome.Success(ServerImportJob(id = jobId, monthId = "", state = state))
        }

        private fun <T> unsupported(): Outcome<T> =
            Outcome.Failure(AppError.Configuration("assessment_test_unconfigured_port"))
    }

    /**
     * A Context whose SharedPreferences *always* fail to commit, modelling a full disk / corrupted
     * prefs file: the outbox must not report those rows as inserted.
     */
    private class FailingPrefsContext(base: Context) : ContextWrapper(base) {
        override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = FailingPrefs()
    }

    private class FailingPrefs : SharedPreferences {
        override fun getAll(): MutableMap<String, *> = mutableMapOf<String, Any>()

        override fun getString(key: String?, defValue: String?): String? = defValue

        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
            defValues

        override fun getInt(key: String?, defValue: Int): Int = defValue

        override fun getLong(key: String?, defValue: Long): Long = defValue

        override fun getFloat(key: String?, defValue: Float): Float = defValue

        override fun getBoolean(key: String?, defValue: Boolean): Boolean = defValue

        override fun contains(key: String?): Boolean = false

        override fun edit(): SharedPreferences.Editor = FailingEditor()

        override fun registerOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?,
        ) = Unit

        override fun unregisterOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?,
        ) = Unit
    }

    private class FailingEditor : SharedPreferences.Editor {
        override fun putString(key: String?, value: String?): SharedPreferences.Editor = this

        override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor = this

        override fun putInt(key: String?, value: Int): SharedPreferences.Editor = this

        override fun putLong(key: String?, value: Long): SharedPreferences.Editor = this

        override fun putFloat(key: String?, value: Float): SharedPreferences.Editor = this

        override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor = this

        override fun remove(key: String?): SharedPreferences.Editor = this

        override fun clear(): SharedPreferences.Editor = this

        override fun commit(): Boolean = false

        override fun apply() = Unit
    }

    /** Keystore stand-in whose wrap/unwrap always throw, like an invalidated key. */
    private class ThrowingSecretStore : WrappedSecretStore {
        override fun wrap(plain: ByteArray): String = throw IllegalStateException("keystore_invalidated")

        override fun unwrap(wrapped: String): ByteArray = throw IllegalStateException("keystore_invalidated")
    }

    /** XOR + Base64 stand-in for the Keystore wrapper; asserts only that values are encrypted. */
    private class XorSecretStore : WrappedSecretStore {
        override fun wrap(plain: ByteArray): String =
            Base64.encodeToString(plain.map { (it.toInt() xor 0x5A).toByte() }.toByteArray(), Base64.NO_WRAP)

        override fun unwrap(wrapped: String): ByteArray =
            Base64.decode(wrapped, Base64.NO_WRAP).map { (it.toInt() xor 0x5A).toByte() }.toByteArray()
    }
}
