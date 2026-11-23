package com.pam.recap.ui.feature.attachment

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasProgressBarRangeInfo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.pam.recap.R
import com.pam.recap.core.result.AppError
import com.pam.recap.domain.repository.ServerAttachment
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * S4 C16–C19: the stateless attachment panel. Every assertion resolves the copy through
 * `R.string.attachments_*` so the test stays valid in both locales, and the delete test proves the
 * callback is gated behind the confirmation dialog.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "id-rID-w411dp-h891dp-xxhdpi")
class TransactionAttachmentsSectionTest {

    @get:Rule
    val compose = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()

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
    ) {
        compose.setContent {
            MaterialTheme {
                TransactionAttachmentsContent(
                    state = state,
                    onAddClick = onAdd,
                    onDelete = onDelete,
                    onLoadMore = onLoadMore,
                )
            }
        }
    }

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
}
