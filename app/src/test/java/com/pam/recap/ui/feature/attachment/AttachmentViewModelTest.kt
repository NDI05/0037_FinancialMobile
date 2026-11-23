package com.pam.recap.ui.feature.attachment

import androidx.lifecycle.SavedStateHandle
import com.pam.recap.core.result.AppError
import com.pam.recap.core.result.Outcome
import com.pam.recap.core.time.JakartaMonth
import com.pam.recap.data.attachment.PrivateAttachmentStore
import com.pam.recap.domain.model.MonthlySummary
import com.pam.recap.domain.model.Session
import com.pam.recap.domain.model.Transaction
import com.pam.recap.domain.repository.AttachmentDraft
import com.pam.recap.domain.repository.CategoryDraft
import com.pam.recap.domain.repository.ManualTransactionDraft
import com.pam.recap.domain.repository.ServerAttachment
import com.pam.recap.domain.repository.ServerAttachmentPage
import com.pam.recap.domain.repository.ServerCategory
import com.pam.recap.domain.repository.ServerFinanceRepository
import com.pam.recap.domain.repository.ServerTransactionPage
import com.pam.recap.testing.FakeSessionRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import kotlin.time.Instant

/**
 * S4 C16–C19: attachment metadata round-trips through the server contract, bytes stay local, a
 * failed registration leaves no orphan file, and delete uses revision CAS without forcing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AttachmentViewModelTest {

    @get:Rule val temp = TemporaryFolder()

    private lateinit var filesDir: File
    private lateinit var store: PrivateAttachmentStore
    private val session = Session("u_1", "Andhika", null, Instant.parse("2026-11-01T00:00:00Z"), false)

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        filesDir = temp.newFolder("files")
        store = PrivateAttachmentStore(File(filesDir, "attachments"))
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun vm(server: FakeAttachments) = AttachmentViewModel(
        SavedStateHandle(mapOf("transactionId" to "tx_1")),
        FakeSessionRepository(session),
        server,
        store,
    )

    private fun bytes(size: Int = 4096) = ByteArray(size) { (it % 251).toByte() }

    @Test
    fun `attaching a 5 MiB image keeps private bytes and registers metadata once`() = runTest {
        val server = FakeAttachments()
        val model = vm(server)
        model.state.first { !it.isLoading }
        model.attach("struk.png", "image/png", ByteArrayInputStream(bytes(5 * 1024 * 1024)))
        val saved = model.state.first { it.notice == "attachment_saved" }

        val draft = server.drafts.single()
        assertEquals("tx_1", draft.transactionId)
        assertEquals(5L * 1024 * 1024, draft.sizeBytes)
        assertEquals(64, draft.sha256.length)
        assertTrue(draft.sha256 == draft.sha256.lowercase())
        assertTrue(store.fileFor(draft.storageRef)?.isFile == true)
        assertEquals(listOf("att_${draft.storageRef.hashCode()}"), saved.attachments.map { it.id })
        assertFalse(saved.busy)
    }

    @Test
    fun `oversize file is rejected before any metadata request`() = runTest {
        val server = FakeAttachments()
        val model = vm(server)
        model.state.first { !it.isLoading }
        model.attach("besar.pdf", "application/pdf", ByteArrayInputStream(bytes(10 * 1024 * 1024 + 1)))
        val failed = model.state.first { !it.busy && it.error != null }
        assertTrue(failed.error is AppError.Validation)
        assertTrue(server.drafts.isEmpty())
    }

    @Test
    fun `a server rejection deletes the local copy instead of leaving an orphan`() = runTest {
        val server = FakeAttachments(createFailure = AppError.Conflict("attachment_duplicate"))
        val model = vm(server)
        model.state.first { !it.isLoading }
        model.attach("struk.png", "image/png", ByteArrayInputStream(bytes()))
        val failed = model.state.first { !it.busy && it.error != null }
        assertTrue(failed.error is AppError.Conflict)
        val refs = File(filesDir, "attachments").walkTopDown().filter { it.isFile }.toList()
        assertTrue(refs.isEmpty())
    }

    @Test
    fun `metadata survives a fresh view model and delete removes bytes with revision CAS`() = runTest {
        val server = FakeAttachments()
        val first = vm(server)
        first.state.first { !it.isLoading }
        first.attach("struk.png", "image/png", ByteArrayInputStream(bytes()))
        val saved = first.state.first { it.notice == "attachment_saved" }
        val row = saved.attachments.single()

        val reopened = vm(server)
        val again = reopened.state.first { !it.isLoading }
        assertEquals(listOf(row.id), again.attachments.map { it.id })

        reopened.remove(row)
        val after = reopened.state.first { it.attachments.isEmpty() && !it.busy }
        assertEquals(row.revision, server.lastDeleteRevision)
        assertNull(store.fileFor(server.drafts.single().storageRef))
        assertTrue(after.attachments.isEmpty())
    }

    @Test
    fun `a stale delete surfaces a conflict and keeps the attachment`() = runTest {
        val server = FakeAttachments(deleteFailure = AppError.Conflict("revision_conflict"), seed = true)
        val model = vm(server)
        val row = model.state.first { !it.isLoading }.attachments.single()
        model.remove(row)
        val conflicted = model.state.first { it.conflict }
        assertTrue(conflicted.error is AppError.Conflict)
        assertEquals(1, conflicted.attachments.size)
        assertEquals(row.id, conflicted.attachments.single().id)
    }

    private class FakeAttachments(
        private val createFailure: AppError? = null,
        private val deleteFailure: AppError? = null,
        seed: Boolean = false,
    ) : ServerFinanceRepository {
        override val isEnabled = true
        val drafts = mutableListOf<AttachmentDraft>()
        var lastDeleteRevision: Int? = null
        private val rows = mutableListOf<ServerAttachment>()

        init {
            if (seed) {
                rows += ServerAttachment(
                    id = "att_seed",
                    transactionId = "tx_1",
                    fileName = "seed.pdf",
                    mimeType = "application/pdf",
                    sizeBytes = 1024,
                    sha256 = "a".repeat(64),
                    storageRef = "attachments/u_1/tx_1/seed.pdf",
                    revision = 3,
                )
            }
        }

        override suspend fun attachments(
            ownerId: String,
            transactionId: String,
            mimeType: String?,
            query: String,
            limit: Int,
            cursor: String?,
        ) = Outcome.Success(ServerAttachmentPage(rows.filter { it.transactionId == transactionId }))

        override suspend fun createAttachment(ownerId: String, draft: AttachmentDraft): Outcome<ServerAttachment> {
            createFailure?.let { return Outcome.Failure(it) }
            drafts += draft
            val row = ServerAttachment(
                id = "att_${draft.storageRef.hashCode()}",
                transactionId = draft.transactionId,
                fileName = draft.fileName,
                mimeType = draft.mimeType,
                sizeBytes = draft.sizeBytes,
                sha256 = draft.sha256,
                storageRef = draft.storageRef,
                revision = 1,
            )
            rows += row
            return Outcome.Success(row)
        }

        override suspend fun deleteAttachment(ownerId: String, attachmentId: String, revision: Int?): Outcome<Unit> {
            deleteFailure?.let { return Outcome.Failure(it) }
            lastDeleteRevision = revision
            rows.removeAll { it.id == attachmentId }
            return Outcome.Success(Unit)
        }

        override suspend fun categories(ownerId: String) = Outcome.Success(listOf<ServerCategory>())
        override suspend fun createCategory(ownerId: String, draft: CategoryDraft) =
            Outcome.Failure(AppError.NotFound("unused"))
        override suspend fun createManual(ownerId: String, draft: ManualTransactionDraft, idempotencyKey: String) =
            Outcome.Failure(AppError.NotFound("unused"))
        override suspend fun listMonth(ownerId: String, month: JakartaMonth, limit: Int, cursor: String?) =
            Outcome.Success(ServerTransactionPage(emptyList()))
        override suspend fun get(ownerId: String, transactionId: String) =
            Outcome.Failure(AppError.NotFound("unused"))
        override suspend fun updateCategory(ownerId: String, transactionId: String, categoryId: String, revision: Int) =
            Outcome.Failure(AppError.NotFound("unused"))
        override suspend fun summary(ownerId: String, month: JakartaMonth) =
            Outcome.Failure(AppError.NotFound("unused"))
        override suspend fun classifyTransactions(ownerId: String, transactionIds: List<String>) =
            Outcome.Failure(AppError.NotFound("unused"))
        override suspend fun enqueueCategories(ownerId: String, transactionIds: List<String>) =
            Outcome.Failure(AppError.NotFound("unused"))
        override suspend fun drainCategories(ownerId: String) =
            Outcome.Failure(AppError.NotFound("unused"))
        override suspend fun createImportJob(ownerId: String, month: JakartaMonth, templateVersion: String) =
            Outcome.Failure(AppError.NotFound("unused"))
        override suspend fun importBatch(ownerId: String, jobId: String, batchKey: String, pageOrdinal: Int, candidates: List<com.pam.recap.domain.repository.ServerImportCandidate>) =
            Outcome.Failure(AppError.NotFound("unused"))
        override suspend fun finalizeImportJob(ownerId: String, jobId: String, state: String, errorCode: String?) =
            Outcome.Failure(AppError.NotFound("unused"))
    }
}
