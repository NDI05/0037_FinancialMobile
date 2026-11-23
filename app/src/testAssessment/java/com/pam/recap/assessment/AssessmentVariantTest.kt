package com.pam.recap.assessment

import android.content.Context
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import com.pam.recap.core.money.Money
import com.pam.recap.core.result.AppError
import com.pam.recap.core.result.Outcome
import com.pam.recap.core.security.WrappedSecretStore
import com.pam.recap.core.time.JakartaMonth
import com.pam.recap.data.assessment.AssessmentOutbox
import com.pam.recap.data.assessment.AssessmentTransactionRepository
import com.pam.recap.data.security.ImportUploadJournal
import com.pam.recap.domain.model.Category
import com.pam.recap.domain.model.CategoryOrigin
import com.pam.recap.domain.model.Direction
import com.pam.recap.domain.model.ImportJob
import com.pam.recap.domain.model.ImportState
import com.pam.recap.domain.model.ParseStatus
import com.pam.recap.domain.model.PendingUploadMonth
import com.pam.recap.domain.model.PendingUploadRun
import com.pam.recap.domain.model.Transaction
import com.pam.recap.domain.model.TransactionSource
import com.pam.recap.domain.repository.ServerCategory
import com.pam.recap.domain.repository.ServerCategoryJob
import com.pam.recap.domain.repository.ServerFinanceRepository
import com.pam.recap.domain.repository.ServerImportCounts
import com.pam.recap.domain.repository.ServerImportJob
import com.pam.recap.domain.repository.ServerTransactionPage
import com.pam.recap.domain.usecase.ServerImportUploader
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.time.Instant

/**
 * S4.4 evidence for the assessment variant:
 *
 * 1. Room/SQLCipher and the Room-backed `PamDatabase` are *not on the assessment classpath* — the
 *    check is a class lookup against this variant's own unit-test runtime, so a future accidental
 *    `implementation` dependency fails here.
 * 2. The Room-free staging outbox is durable and encrypted, and the pending-upload journal's
 *    fingerprint snapshot is replayable purely from it (the recovery contract the sync layer
 *    depends on).
 * 3. A missing backend still fails honestly with `backend_not_configured`; there is no silent
 *    success and no hidden local database to fall back to.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AssessmentVariantTest {

    private lateinit var context: Context
    private lateinit var secrets: WrappedSecretStore

    private val ownerId = "u_assessment"
    private val month = JakartaMonth(2026, 11)
    private val now = Instant.parse("2026-11-20T02:00:00Z")

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("pam_assessment_outbox", Context.MODE_PRIVATE)
            .edit().clear().commit()
        context.getSharedPreferences("pam_backend_session", Context.MODE_PRIVATE)
            .edit().clear().commit()
        secrets = XorSecretStore()
    }

    // ------------------------------------------------------------------ classpath audit

    @Test
    fun `room and sqlcipher are absent from the assessment runtime classpath`() {
        listOf(
            "androidx.room.Room",
            "androidx.room.RoomDatabase",
            "net.zetetic.database.sqlcipher.SQLiteDatabase",
            "com.pam.recap.data.local.PamDatabase",
            "com.pam.recap.data.local.TransactionEntity",
            "com.pam.recap.core.security.DatabasePassphraseProvider",
        ).forEach { className ->
            try {
                Class.forName(className)
                fail("$className must not be on the assessment classpath (S4.4)")
            } catch (_: ClassNotFoundException) {
                // expected: the class cannot be linked in this variant at all
            }
        }
    }

    // ------------------------------------------------------------------ outbox

    @Test
    fun `staged rows survive a process restart and stay encrypted at rest`() = runTest {
        val firstProcess = assessmentRepository()
        val inserted = firstProcess.insertIgnoringDuplicates(
            ownerId,
            listOf(
                transaction(id = "tx-1", fingerprint = "a".repeat(64), jobId = "job-1"),
                transaction(id = "tx-2", fingerprint = "b".repeat(64), jobId = "job-1"),
            ),
        )
        assertEquals(2, inserted.inserted)
        assertEquals(0, inserted.ignored)

        // A duplicate fingerprint is ignored, exactly like Room's unique index on
        // (ownerId, fingerprint): retries must not double-count the same email.
        val retry = firstProcess.insertIgnoringDuplicates(
            ownerId,
            listOf(transaction(id = "tx-1-retry", fingerprint = "a".repeat(64), jobId = "job-1")),
        )
        assertEquals(0, retry.inserted)
        assertEquals(1, retry.ignored)

        // Fresh instances over the same durable bytes model the next app process.
        val nextProcess = assessmentRepository()
        val recovered = nextProcess.listByImportJob(ownerId, "job-1")
        assertEquals(listOf("tx-1", "tx-2"), recovered.map { it.id }.sorted())
        assertEquals(2, nextProcess.listMonth(ownerId, month).size)

        // The outbox is a staging area, not a plaintext JSON dump in shared preferences.
        val raw = context.getSharedPreferences("pam_assessment_outbox", Context.MODE_PRIVATE)
            .all.values.filterIsInstance<String>().joinToString("\n")
        assertTrue("outbox must not store plaintext descriptions", !raw.contains("QRIS BAKSO"))
        assertTrue("outbox must not store plaintext amounts", !raw.contains("25000"))
        assertTrue("outbox must not be empty", raw.isNotBlank())
    }

    @Test
    fun `pending journal snapshot is replayable from the outbox without Room`() = runTest {
        val process = assessmentRepository()
        val emailRows = listOf(
            transaction(id = "tx-1", fingerprint = "a".repeat(64), jobId = "job-1"),
            transaction(id = "tx-2", fingerprint = "b".repeat(64), jobId = "job-1"),
        )
        process.insertIgnoringDuplicates(ownerId, emailRows)
        val journal = ImportUploadJournal(context, secrets)
        val pending = PendingUploadRun(
            ownerId = ownerId,
            localJobId = "job-1",
            primaryMonthId = month.id(),
            localState = ImportState.COMPLETE.name,
            templateVersion = "livin-v1",
            watermark = null,
            startedAtEpochMillis = now.toEpochMilliseconds(),
            months = mapOf(
                month.id() to PendingUploadMonth(
                    monthId = month.id(),
                    fingerprints = listOf("a".repeat(64), "b".repeat(64)),
                ),
            ),
        )
        assertTrue(journal.savePending(pending))

        // "Restart": the journal and the outbox are re-read from disk by fresh objects; no Room
        // database participated, and the snapshot the uploader needs is complete.
        val restartedJournal = ImportUploadJournal(context, secrets)
        val restartedRepository = assessmentRepository()
        val recovered = restartedJournal.readPending(ownerId)
        assertNotNull(recovered)
        val fingerprintSnapshot = recovered!!.months.getValue(month.id()).fingerprints
        val staged = restartedRepository.listByImportJob(recovered.ownerId, recovered.localJobId)
        assertEquals(fingerprintSnapshot.sorted(), staged.map { it.fingerprint }.sorted())
        assertEquals(2, staged.size)
        assertTrue(recovered.rangeComplete)
        assertFalse(recovered.fullyAcknowledged)
    }

    // ------------------------------------------------------------------ honest failure

    @Test
    fun `no backend means backend_not_configured instead of silent success`() = runTest {
        val process = assessmentRepository()
        process.insertIgnoringDuplicates(
            ownerId,
            listOf(transaction(id = "tx-1", fingerprint = "a".repeat(64), jobId = "job-1")),
        )
        val uploader = ServerImportUploader(
            server = DisabledServer(),
            transactions = process,
            journal = ImportUploadJournal(context, secrets),
        )
        val outcome = uploader.upload(ownerId, importJob(id = "job-1"))
        assertTrue("upload must fail honestly, got $outcome", outcome is Outcome.Failure)
        val error = (outcome as Outcome.Failure).error
        assertEquals(AppError.Configuration("backend_not_configured"), error)
    }

    // ------------------------------------------------------------------ helpers

    private fun assessmentRepository(): AssessmentTransactionRepository =
        AssessmentTransactionRepository(AssessmentOutbox(context, secrets))

    private fun transaction(id: String, fingerprint: String, jobId: String): Transaction = Transaction(
        id = id,
        ownerId = ownerId,
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
        matchedCount = 1,
        importedCount = 1,
        duplicateCount = 0,
        skippedCount = 0,
        unresolvedCount = 0,
        templateVersion = "livin-v1",
        startedAt = now,
        finishedAt = now,
    )

    /** Minimal enabled=false port: only [isEnabled] is consulted before the honest failure. */
    private class DisabledServer : ServerFinanceRepository {
        override val isEnabled: Boolean = false

        override suspend fun categories(ownerId: String): Outcome<List<ServerCategory>> =
            unsupported()

        override suspend fun createManual(
            ownerId: String,
            draft: com.pam.recap.domain.repository.ManualTransactionDraft,
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
        ): Outcome<com.pam.recap.domain.repository.CategoryEnqueueResult> = unsupported()

        override suspend fun drainCategories(
            ownerId: String,
        ): Outcome<com.pam.recap.domain.repository.CategoryDrainResult> = unsupported()

        override suspend fun createImportJob(
            ownerId: String,
            month: JakartaMonth,
            templateVersion: String,
        ): Outcome<ServerImportJob> = unsupported()

        override suspend fun importBatch(
            ownerId: String,
            jobId: String,
            batchKey: String,
            pageOrdinal: Int,
            candidates: List<com.pam.recap.domain.repository.ServerImportCandidate>,
        ): Outcome<ServerImportCounts> = unsupported()

        override suspend fun finalizeImportJob(
            ownerId: String,
            jobId: String,
            state: String,
            errorCode: String?,
        ): Outcome<ServerImportJob> = unsupported()

        private fun <T> unsupported(): Outcome<T> =
            Outcome.Failure(AppError.Configuration("assessment_test_unconfigured_port"))
    }

    /** XOR + Base64 stand-in for the Keystore wrapper; asserts only that values are encrypted. */
    private class XorSecretStore : WrappedSecretStore {
        override fun wrap(plain: ByteArray): String =
            Base64.encodeToString(plain.map { (it.toInt() xor 0x5A).toByte() }.toByteArray(), Base64.NO_WRAP)

        override fun unwrap(wrapped: String): ByteArray =
            Base64.decode(wrapped, Base64.NO_WRAP).map { (it.toInt() xor 0x5A).toByte() }.toByteArray()
    }
}
