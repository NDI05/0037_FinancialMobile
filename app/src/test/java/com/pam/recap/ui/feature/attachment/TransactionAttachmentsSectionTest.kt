package com.pam.recap.ui.feature.attachment

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasProgressBarRangeInfo
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import com.pam.recap.R
import com.pam.recap.core.result.AppError
import com.pam.recap.core.result.Outcome
import com.pam.recap.core.time.JakartaMonth
import com.pam.recap.data.attachment.PrivateAttachmentStore
import com.pam.recap.domain.model.Session
import com.pam.recap.domain.repository.AttachmentDraft
import com.pam.recap.domain.repository.AttachmentPatch
import com.pam.recap.domain.repository.CategoryDraft
import com.pam.recap.domain.repository.ManualTransactionDraft
import com.pam.recap.domain.repository.ServerAttachment
import com.pam.recap.domain.repository.ServerAttachmentPage
import com.pam.recap.domain.repository.ServerCategory
import com.pam.recap.domain.repository.ServerFinanceRepository
import com.pam.recap.domain.repository.ServerTransactionPage
import com.pam.recap.testing.FakeSessionRepository
import java.io.File
import java.util.Locale
import kotlin.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * S4 C16–C19: the stateless attachment panel. Every assertion resolves the copy through
 * `R.string.attachments_*` so the test stays valid in both locales, and the delete test proves the
 * callback is gated behind the confirmation dialog.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "id-rID-w411dp-h891dp-xxhdpi")
class TransactionAttachmentsSectionTest {

    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val temp = TemporaryFolder()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val session = Session("u_1", "Andhika", null, Instant.parse("2026-11-01T00:00:00Z"), false)
    private lateinit var store: PrivateAttachmentStore

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        store = PrivateAttachmentStore(File(temp.newFolder("files"), "attachments"))
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun text(id: Int): String = context.getString(id)

    private fun attachment(
        id: String = "att_1",
        fileName: String = "kwitansi.png",
        mimeType: String = "image/png",
        sizeBytes: Long = 5L * 1024L * 1024L,
        revision: Int = 1,
    ) = ServerAttachment(
        id = id,
        transactionId = "tx_1",
        fileName = fileName,
        mimeType = mimeType,
        sizeBytes = sizeBytes,
        sha256 = "a".repeat(64),
        storageRef = "attachments/$id.bin",
        revision = revision,
    )

    /** Runs [block] under a fixed locale so the human-readable sizes are unambiguous. */
    private fun withLocale(locale: Locale, block: () -> Unit) {
        val previous = Locale.getDefault()
        Locale.setDefault(locale)
        try {
            block()
        } finally {
            Locale.setDefault(previous)
        }
    }

    private fun render(
        state: AttachmentUiState,
        onAdd: () -> Unit = {},
        onDelete: (ServerAttachment) -> Unit = {},
        onLoadMore: () -> Unit = {},
        onSearch: (String) -> Unit = {},
        onRename: (ServerAttachment, String) -> Unit = { _, _ -> },
    ) {
        compose.setContent {
            MaterialTheme {
                TransactionAttachmentsContent(
                    state = state,
                    onAddClick = onAdd,
                    onDelete = onDelete,
                    onLoadMore = onLoadMore,
                    onSearch = onSearch,
                    onRename = onRename,
                )
            }
        }
    }

    /** Renders the real ViewModel over the fake server so the panel/repository wiring is exercised. */
    private fun renderLive(server: FakeAttachmentServer): AttachmentViewModel {
        val model = AttachmentViewModel(
            SavedStateHandle(mapOf("transactionId" to "tx_1")),
            FakeSessionRepository(session),
            server,
            store,
        )
        compose.setContent {
            MaterialTheme {
                val state by model.state.collectAsState()
                TransactionAttachmentsContent(
                    state = state,
                    onAddClick = {},
                    onDelete = model::remove,
                    onLoadMore = { model.load(loadMore = true) },
                    onSearch = model::search,
                    onRename = model::rename,
                )
            }
        }
        return model
    }

    /** The inline rename field, matched by the editable text it holds. */
    private fun renameField(value: String) = compose.onNode(
        SemanticsMatcher("editable text is $value") { node ->
            node.config.getOrNull(SemanticsProperties.EditableText)?.text == value
        },
    )

    @Test
    fun `offline disables add and says attachments need the server`() {
        render(AttachmentUiState(isLoading = false, serverEnabled = false))

        compose.onNodeWithText(text(R.string.attachments_offline)).assertExists()
        compose.onNodeWithText(text(R.string.attachments_add)).assertIsNotEnabled()
        compose.onNodeWithText("kwitansi.png").assertDoesNotExist()
        compose.onNodeWithText(text(R.string.attachments_empty)).assertDoesNotExist()
    }

    @Test
    fun `rows render the file name, mime type and human readable size`() {
        val png = attachment()
        val note = attachment(
            id = "att_2",
            fileName = "catatan.txt",
            mimeType = "text/plain",
            sizeBytes = 900,
        )
        withLocale(Locale.US) {
            render(
                AttachmentUiState(
                    isLoading = false,
                    serverEnabled = true,
                    attachments = listOf(png, note),
                ),
            )

            compose.onNodeWithText("kwitansi.png").assertExists()
            compose.onNodeWithText("image/png · 5.0 MB").assertExists()
            compose.onNodeWithText("catatan.txt").assertExists()
            compose.onNodeWithText("text/plain · 900 B").assertExists()
            compose.onNodeWithText(text(R.string.attachments_only_local)).assertExists()
        }
    }

    @Test
    fun `an empty server-backed panel says there are no attachments`() {
        render(AttachmentUiState(isLoading = false, serverEnabled = true))

        compose.onNodeWithText(text(R.string.attachments_empty)).assertExists()
        compose.onNodeWithText(text(R.string.attachments_offline)).assertDoesNotExist()
    }

    @Test
    fun `busy shows the progress row and disables add and delete`() {
        render(
            AttachmentUiState(
                isLoading = false,
                serverEnabled = true,
                busy = true,
                attachments = listOf(attachment()),
            ),
        )

        compose.onNode(hasProgressBarRangeInfo(ProgressBarRangeInfo.Indeterminate)).assertExists()
        compose.onNodeWithText(text(R.string.attachments_uploading)).assertExists()
        compose.onNodeWithText(text(R.string.attachments_add)).assertIsNotEnabled()
        compose
            .onNodeWithContentDescription(text(R.string.attachments_delete_confirm))
            .assertIsNotEnabled()
    }

    @Test
    fun `a conflict shows the reload copy and not the generic error`() {
        render(
            AttachmentUiState(
                isLoading = false,
                serverEnabled = true,
                attachments = listOf(attachment()),
                conflict = true,
            ),
        )

        compose.onNodeWithText(text(R.string.attachments_conflict)).assertExists()
        compose.onNodeWithText(text(R.string.attachments_error_generic)).assertDoesNotExist()
    }

    @Test
    fun `a generic failure shows the generic error and not the conflict copy`() {
        render(
            AttachmentUiState(
                isLoading = false,
                serverEnabled = true,
                attachments = listOf(attachment()),
                error = AppError.Network("offline"),
            ),
        )

        compose.onNodeWithText(text(R.string.attachments_error_generic)).assertExists()
        compose.onNodeWithText(text(R.string.attachments_conflict)).assertDoesNotExist()
    }

    @Test
    fun `a saved notice is stated after an upload`() {
        render(
            AttachmentUiState(
                isLoading = false,
                serverEnabled = true,
                notice = "attachment_saved",
                attachments = listOf(attachment()),
            ),
        )

        compose.onNodeWithText(text(R.string.attachments_saved)).assertExists()
    }

    @Test
    fun `delete waits for the confirmation dialog and only then calls back once`() {
        val deleted = mutableListOf<ServerAttachment>()
        val row = attachment()
        render(
            state = AttachmentUiState(
                isLoading = false,
                serverEnabled = true,
                attachments = listOf(row),
            ),
            onDelete = { deleted += it },
        )

        compose.onNodeWithContentDescription(text(R.string.attachments_delete_confirm)).performClick()
        compose.onNodeWithText(text(R.string.attachments_delete_title)).assertExists()
        compose.onNodeWithText(text(R.string.attachments_delete_body)).assertExists()

        // Cancel is a real gate: nothing is deleted yet.
        compose.onNodeWithText(text(R.string.action_cancel)).performClick()
        compose.onNodeWithText(text(R.string.attachments_delete_title)).assertDoesNotExist()
        assertTrue(deleted.isEmpty())

        compose.onNodeWithContentDescription(text(R.string.attachments_delete_confirm)).performClick()
        compose.onNodeWithText(text(R.string.attachments_delete_confirm)).performClick()

        assertEquals(listOf(row), deleted)
        assertEquals(1, deleted.size)
        assertEquals("att_1", deleted.single().id)
    }

    @Test
    fun `the footer loads more only when the server returned a cursor`() {
        var loadMore = 0
        render(
            state = AttachmentUiState(
                isLoading = false,
                serverEnabled = true,
                attachments = listOf(attachment()),
                nextCursor = "cursor_2",
            ),
            onLoadMore = { loadMore += 1 },
        )

        compose.onNodeWithText(text(R.string.attachments_load_more)).performClick()

        assertEquals(1, loadMore)
    }

    @Test
    fun `without a cursor there is no load more button`() {
        render(
            AttachmentUiState(
                isLoading = false,
                serverEnabled = true,
                attachments = listOf(attachment()),
                nextCursor = null,
            ),
        )

        compose.onNodeWithText(text(R.string.attachments_load_more)).assertDoesNotExist()
    }

    @Test
    fun `add invokes the add callback when the server is available`() {
        var add = 0
        render(
            state = AttachmentUiState(isLoading = false, serverEnabled = true),
            onAdd = { add += 1 },
        )

        compose.onNodeWithText(text(R.string.attachments_add)).performClick()

        assertEquals(1, add)
    }

    @Test
    fun `the size formatter rounds at the byte and mebibyte boundaries`() {
        withLocale(Locale.US) {
            assertEquals("1023 B", formatAttachmentSize(1023))
            assertEquals("1 KB", formatAttachmentSize(1024))
            assertEquals("1.5 MB", formatAttachmentSize(1536L * 1024L))
            assertEquals("5.0 MB", formatAttachmentSize(5L * 1024L * 1024L))
            assertEquals("10.0 MB", formatAttachmentSize(10L * 1024L * 1024L))
        }
    }

    @Test
    fun `searching forwards the query to the server and renders the matching rows`() {
        val server = FakeAttachmentServer().apply {
            rows += attachment(id = "att_1", fileName = "kwitansi.png")
            rows += attachment(id = "att_2", fileName = "catatan.txt")
        }
        val model = renderLive(server)
        compose.waitUntil(5_000) { model.state.value.attachments.size == 2 }

        compose.onNode(hasSetTextAction()).performTextInput("kwitansi")
        compose.onNodeWithContentDescription(text(R.string.action_search)).performClick()
        compose.waitUntil(5_000) {
            !model.state.value.isLoading && model.state.value.query == "kwitansi"
        }

        assertEquals("kwitansi", server.attachmentCalls.last().first)
        assertNull("a new search starts from page one", server.attachmentCalls.last().second)
        compose.onNodeWithText("kwitansi.png").assertExists()
        compose.onNodeWithText("catatan.txt").assertDoesNotExist()
    }

    @Test
    fun `clearing the search restores the unfiltered rows`() {
        val server = FakeAttachmentServer().apply {
            rows += attachment(id = "att_1", fileName = "kwitansi.png")
            rows += attachment(id = "att_2", fileName = "catatan.txt")
        }
        val model = renderLive(server)
        compose.waitUntil(5_000) { model.state.value.attachments.size == 2 }

        compose.onNode(hasSetTextAction()).performTextInput("kwitansi")
        compose.onNodeWithContentDescription(text(R.string.action_search)).performClick()
        compose.waitUntil(5_000) { model.state.value.query == "kwitansi" && !model.state.value.isLoading }
        compose.onNodeWithText("catatan.txt").assertDoesNotExist()

        compose.onNode(hasSetTextAction()).performTextClearance()
        compose.onNodeWithContentDescription(text(R.string.action_search)).performClick()
        compose.waitUntil(5_000) { model.state.value.query.isEmpty() && !model.state.value.isLoading }

        assertEquals("", server.attachmentCalls.last().first)
        assertNull(server.attachmentCalls.last().second)
        compose.onNodeWithText("kwitansi.png").assertExists()
        compose.onNodeWithText("catatan.txt").assertExists()
    }

    @Test
    fun `a search without matches uses the search-empty copy`() {
        render(
            AttachmentUiState(
                isLoading = false,
                serverEnabled = true,
                query = "tidak-ada",
            ),
        )

        compose.onNodeWithText(text(R.string.attachments_search_empty)).assertExists()
        compose.onNodeWithText(text(R.string.attachments_empty)).assertDoesNotExist()
    }

    @Test
    fun `every row offers a rename action`() {
        render(
            AttachmentUiState(
                isLoading = false,
                serverEnabled = true,
                attachments = listOf(
                    attachment(id = "att_1", fileName = "kwitansi.png"),
                    attachment(id = "att_2", fileName = "catatan.txt"),
                ),
            ),
        )

        compose
            .onAllNodesWithContentDescription(text(R.string.attachments_rename_action))
            .assertCountEquals(2)
    }

    @Test
    fun `the rename dialog is prefilled and a blank name is rejected`() {
        val renames = mutableListOf<Pair<ServerAttachment, String>>()
        render(
            state = AttachmentUiState(
                isLoading = false,
                serverEnabled = true,
                attachments = listOf(attachment()),
            ),
            onRename = { row, name -> renames += row to name },
        )

        compose.onNodeWithContentDescription(text(R.string.attachments_rename_action)).performClick()
        renameField("kwitansi.png").assertExists()

        renameField("kwitansi.png").performTextClearance()
        compose.onNodeWithContentDescription(text(R.string.action_save)).performClick()

        compose.onNodeWithText(text(R.string.attachments_rename_invalid)).assertExists()
        compose.onNode(hasSetTextAction() and isFocused()).assertExists()
        assertTrue("a blank name must never reach the server", renames.isEmpty())
    }

    @Test
    fun `a rename sends the loaded revision and shows the new name`() {
        val server = FakeAttachmentServer().apply {
            rows += attachment(id = "att_1", fileName = "kwitansi.png", revision = 3)
        }
        val model = renderLive(server)
        compose.waitUntil(5_000) { model.state.value.attachments.size == 1 }

        compose.onNodeWithContentDescription(text(R.string.attachments_rename_action)).performClick()
        renameField("kwitansi.png").performTextClearance()
        compose.onNode(hasSetTextAction() and isFocused()).performTextInput("  laporan-final.pdf  ")
        compose.onNodeWithContentDescription(text(R.string.action_save)).performClick()
        compose.waitUntil(5_000) { model.state.value.notice == "attachment_renamed" && !model.state.value.busy }

        val patch = server.patches.single()
        assertEquals("laporan-final.pdf", patch.fileName)
        assertEquals(3, patch.revision)
        assertEquals("laporan-final.pdf", model.state.value.attachments.single().fileName)
        compose.onNodeWithText("laporan-final.pdf").assertExists()
        // The editor closes only after the save is confirmed; the row shows the new name.
        renameField("laporan-final.pdf").assertDoesNotExist()
    }

    @Test
    fun `a conflicting rename keeps the dialog open and the row unchanged`() {
        val server = FakeAttachmentServer().apply {
            rows += attachment(id = "att_1", fileName = "kwitansi.png", revision = 3)
            patchFailure = AppError.Conflict("revision_conflict")
        }
        val model = renderLive(server)
        compose.waitUntil(5_000) { model.state.value.attachments.size == 1 }

        compose.onNodeWithContentDescription(text(R.string.attachments_rename_action)).performClick()
        renameField("kwitansi.png").performTextClearance()
        compose.onNode(hasSetTextAction() and isFocused()).performTextInput("laporan-final.pdf")
        compose.onNodeWithContentDescription(text(R.string.action_save)).performClick()
        compose.waitUntil(5_000) { model.state.value.conflict }

        compose.onNodeWithText(text(R.string.attachments_conflict)).assertExists()
        renameField("laporan-final.pdf").assertExists()
        assertEquals("kwitansi.png", model.state.value.attachments.single().fileName)
    }

    private class FakeAttachmentServer : ServerFinanceRepository {
        override val isEnabled = true
        val rows = mutableListOf<ServerAttachment>()
        val attachmentCalls = mutableListOf<Pair<String, String?>>()
        val patches = mutableListOf<AttachmentPatch>()
        var patchFailure: AppError? = null

        override suspend fun attachments(
            ownerId: String,
            transactionId: String,
            mimeType: String?,
            query: String,
            limit: Int,
            cursor: String?,
        ): Outcome<ServerAttachmentPage> {
            attachmentCalls += query to cursor
            val filtered = rows.filter {
                it.transactionId == transactionId &&
                    (query.isBlank() || it.fileName.contains(query, ignoreCase = true))
            }
            return Outcome.Success(ServerAttachmentPage(filtered.take(limit)))
        }

        override suspend fun patchAttachment(
            ownerId: String,
            attachmentId: String,
            patch: AttachmentPatch,
        ): Outcome<ServerAttachment> {
            patchFailure?.let { return Outcome.Failure(it) }
            patches += patch
            val index = rows.indexOfFirst { it.id == attachmentId }
            if (index < 0) return Outcome.Failure(AppError.NotFound("attachment"))
            val updated = rows[index].copy(
                fileName = patch.fileName ?: rows[index].fileName,
                revision = rows[index].revision + 1,
            )
            rows[index] = updated
            return Outcome.Success(updated)
        }

        override suspend fun createAttachment(ownerId: String, draft: AttachmentDraft) =
            Outcome.Failure(AppError.NotFound("unused"))
        override suspend fun deleteAttachment(ownerId: String, attachmentId: String, revision: Int?) =
            Outcome.Failure(AppError.NotFound("unused"))
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
        override suspend fun importBatch(
            ownerId: String,
            jobId: String,
            batchKey: String,
            pageOrdinal: Int,
            candidates: List<com.pam.recap.domain.repository.ServerImportCandidate>,
        ) = Outcome.Failure(AppError.NotFound("unused"))
        override suspend fun finalizeImportJob(ownerId: String, jobId: String, state: String, errorCode: String?) =
            Outcome.Failure(AppError.NotFound("unused"))
    }
}
