package com.pam.recap.ui.feature.attachment

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pam.recap.R
import com.pam.recap.data.attachment.AttachmentRules
import com.pam.recap.domain.repository.ServerAttachment
import java.io.InputStream

/** S4 C16–C19: per-transaction attachment panel; bytes stay private, only metadata syncs. */
@Composable
fun TransactionAttachmentsSection(
    modifier: Modifier = Modifier,
    viewModel: AttachmentViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val resolver = context.contentResolver
        val name = resolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
            ?: uri.lastPathSegment.orEmpty()
        val mime = resolver.getType(uri) ?: "application/octet-stream"
        val stream: InputStream? = runCatching { resolver.openInputStream(uri) }.getOrNull()
        if (stream == null) return@rememberLauncherForActivityResult
        viewModel.attach(name, mime, stream)
    }
    TransactionAttachmentsContent(
        state = state,
        onAddClick = { picker.launch(AttachmentRules.MIME_TYPES.toTypedArray()) },
        onDelete = viewModel::remove,
        onLoadMore = { viewModel.load(loadMore = true) },
        onSearch = viewModel::search,
        onRename = viewModel::rename,
        modifier = modifier,
    )
}

/** Stateless panel so state rendering can be tested without Hilt or the system document picker. */
@Composable
fun TransactionAttachmentsContent(
    state: AttachmentUiState,
    onAddClick: () -> Unit,
    onDelete: (ServerAttachment) -> Unit,
    onLoadMore: () -> Unit,
    modifier: Modifier = Modifier,
    onSearch: (String) -> Unit = {},
    onRename: (ServerAttachment, String) -> Unit = { _, _ -> },
) {
    var pendingDelete by remember { mutableStateOf<ServerAttachment?>(null) }
    var editingRename by remember { mutableStateOf<ServerAttachment?>(null) }
    var renameDraft by remember { mutableStateOf("") }
    var renameInvalid by remember { mutableStateOf(false) }
    var renameSubmitted by remember { mutableStateOf(false) }
    // Close only after the server confirmed the rename; a conflict keeps the editor open.
    LaunchedEffect(state.notice) {
        if (renameSubmitted && state.notice == "attachment_renamed") {
            editingRename = null
            renameSubmitted = false
        }
    }
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.attachments_title),
                style = MaterialTheme.typography.titleMedium,
            )
            TextButton(onClick = onAddClick, enabled = state.serverEnabled && !state.busy) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Text(stringResource(R.string.attachments_add))
            }
        }
        if (!state.serverEnabled) {
            Text(
                text = stringResource(R.string.attachments_offline),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Column
        }
        var searchText by remember(state.query) { mutableStateOf(state.query) }
        OutlinedTextField(
            value = searchText,
            onValueChange = { searchText = it },
            placeholder = { Text(stringResource(R.string.attachments_search_hint)) },
            trailingIcon = {
                IconButton(onClick = { onSearch(searchText) }) {
                    Icon(
                        Icons.Filled.Search,
                        contentDescription = stringResource(R.string.action_search),
                    )
                }
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSearch(searchText) }),
            modifier = Modifier.fillMaxWidth(),
        )
        if (state.busy) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp))
            Text(stringResource(R.string.attachments_uploading), style = MaterialTheme.typography.bodySmall)
        }
        if (state.notice != null) {
            val notice = when (state.notice) {
                "attachment_renamed" -> R.string.attachments_renamed
                else -> R.string.attachments_saved
            }
            Text(stringResource(notice), style = MaterialTheme.typography.bodySmall)
        }
        if (state.conflict) {
            Text(
                text = stringResource(R.string.attachments_conflict),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        } else if (state.error != null) {
            Text(
                text = stringResource(R.string.attachments_error_generic),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        if (state.attachments.isEmpty() && !state.isLoading) {
            val empty = if (state.query.isNotBlank()) {
                R.string.attachments_search_empty
            } else {
                R.string.attachments_empty
            }
            Text(stringResource(empty), style = MaterialTheme.typography.bodySmall)
        }
        state.attachments.forEach { attachment ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val editing = editingRename?.id == attachment.id
                if (editing) {
                    Column(modifier = Modifier.weight(1f)) {
                        OutlinedTextField(
                            value = renameDraft,
                            onValueChange = {
                                renameDraft = it
                                renameInvalid = false
                            },
                            label = { Text(stringResource(R.string.attachments_rename_field)) },
                            singleLine = true,
                            isError = renameInvalid || state.renameError != null,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        if (renameInvalid || state.renameError != null) {
                            Text(
                                text = stringResource(R.string.attachments_rename_invalid),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                    IconButton(
                        enabled = !state.busy,
                        onClick = {
                            val trimmed = renameDraft.trim()
                            if (attachmentFileNameRule(trimmed) != null) {
                                renameInvalid = true
                            } else {
                                renameSubmitted = true
                                onRename(attachment, trimmed)
                            }
                        },
                    ) {
                        Icon(
                            Icons.Filled.Check,
                            contentDescription = stringResource(R.string.action_save),
                        )
                    }
                    IconButton(onClick = { editingRename = null }) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = stringResource(R.string.action_cancel),
                        )
                    }
                } else {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = attachment.fileName,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = "${attachment.mimeType} · ${formatAttachmentSize(attachment.sizeBytes)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(
                        onClick = {
                            editingRename = attachment
                            renameDraft = attachment.fileName
                            renameInvalid = false
                            renameSubmitted = false
                        },
                        enabled = !state.busy,
                    ) {
                        Icon(
                            Icons.Filled.Edit,
                            contentDescription = stringResource(R.string.attachments_rename_action),
                        )
                    }
                    IconButton(onClick = { pendingDelete = attachment }, enabled = !state.busy) {
                        Icon(
                            Icons.Filled.Delete,
                            contentDescription = stringResource(R.string.attachments_delete_confirm),
                        )
                    }
                }
            }
        }
        Text(
            text = stringResource(R.string.attachments_only_local),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (state.canLoadMore) {
            TextButton(onClick = onLoadMore, enabled = !state.busy) {
                Text(stringResource(R.string.attachments_load_more))
            }
        }
        pendingDelete?.let { target ->
            AlertDialog(
                onDismissRequest = { pendingDelete = null },
                title = { Text(stringResource(R.string.attachments_delete_title)) },
                text = { Text(stringResource(R.string.attachments_delete_body)) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            pendingDelete = null
                            onDelete(target)
                        },
                    ) { Text(stringResource(R.string.attachments_delete_confirm)) }
                },
                dismissButton = {
                    TextButton(onClick = { pendingDelete = null }) {
                        Text(stringResource(R.string.action_cancel))
                    }
                },
            )
        }
    }
}

internal fun formatAttachmentSize(bytes: Long): String = when {
    // Fixed, locale-independent shape so the size never renders differently per device locale.
    bytes >= 1024L * 1024L -> "%.1f MB".format(java.util.Locale.US, bytes / (1024.0 * 1024.0))
    bytes >= 1024L -> "%d KB".format(java.util.Locale.US, bytes / 1024L)
    else -> "$bytes B"
}
