package com.pam.recap.ui.feature.category

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pam.recap.R
import com.pam.recap.domain.repository.ServerCategory
import com.pam.recap.ui.components.DynamicFieldsEditor
import com.pam.recap.ui.components.GlassCard
import com.pam.recap.ui.components.GlassTopBar
import com.pam.recap.ui.components.NoticeBanner
import com.pam.recap.ui.components.PamScaffold
import com.pam.recap.ui.components.PillButton
import com.pam.recap.ui.components.PillButtonStyle
import com.pam.recap.ui.theme.BodyM
import com.pam.recap.ui.theme.OnInk
import com.pam.recap.ui.theme.OnInkMuted
import com.pam.recap.ui.theme.PamSpacing
import com.pam.recap.ui.theme.TitleM
import com.pam.recap.ui.util.appErrorRes

/**
 * S3 category management: search, create, rename, attribute pairs and delete-with-reassignment.
 * Built-ins show a protected badge and never offer edit/delete actions.
 */
@Composable
fun CategoryManagementScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: CategoryManagementViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    PamScaffold(
        modifier = modifier,
        topBar = { GlassTopBar(title = stringResource(R.string.category_manage_title), onBack = onBack) },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            val editor = state.editor
            if (editor != null) {
                CategoryEditorPane(
                    editor = editor,
                    busy = state.busy,
                    errorRes = state.error?.let { appErrorRes(it) },
                    validationRule = state.validationRule,
                    onChange = viewModel::onEditorChange,
                    onSave = viewModel::save,
                    onCancel = viewModel::dismissEditor,
                )
            } else {
                CategoryList(
                    state = state,
                    onQueryChange = viewModel::onQueryChange,
                    onAdd = viewModel::startCreate,
                    onEdit = viewModel::startEdit,
                    onDelete = viewModel::requestDelete,
                )
            }
        }
    }

    val pending = state.pendingDelete
    if (pending != null) {
        DeleteCategoryDialog(
            category = pending,
            categories = state.categories,
            busy = state.busy,
            needsReassign = state.deleteNeedsReassign,
            onConfirm = viewModel::confirmDelete,
            onDismiss = viewModel::cancelDelete,
        )
    }
}

@Composable
private fun CategoryList(
    state: CategoryManagementUiState,
    onQueryChange: (String) -> Unit,
    onAdd: () -> Unit,
    onEdit: (ServerCategory) -> Unit,
    onDelete: (ServerCategory) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(PamSpacing.sm)) {
        when {
            !state.serverEnabled -> NoticeBanner(text = stringResource(R.string.category_manage_offline))
            state.error != null -> NoticeBanner(text = stringResource(appErrorRes(state.error)))
            state.validationRule == "builtin" -> NoticeBanner(text = stringResource(R.string.category_builtin_protected))
            state.validationRule != null -> NoticeBanner(text = stringResource(R.string.category_invalid))
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(PamSpacing.sm),
        ) {
            OutlinedTextField(
                value = state.query,
                onValueChange = onQueryChange,
                modifier = Modifier.weight(1f),
                label = { Text(stringResource(R.string.category_manage_search)) },
                singleLine = true,
            )
            PillButton(
                text = stringResource(R.string.category_manage_add),
                onClick = onAdd,
                enabled = state.serverEnabled,
            )
        }
        Text(
            text = stringResource(R.string.category_manage_count, state.visible.size),
            color = OnInkMuted,
            style = BodyM,
        )
        if (state.searchedOnServer && state.hasMore) {
            Text(
                text = stringResource(
                    R.string.category_more_hint,
                    state.categories.size,
                    state.serverScanned,
                ),
                color = OnInkMuted,
                style = BodyM,
            )
        }
        if (state.visible.isEmpty() && !state.isLoading) {
            NoticeBanner(text = stringResource(R.string.category_manage_empty))
        }
        LazyColumn(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(PamSpacing.sm)) {
            items(state.visible, key = { it.id }) { category ->
                CategoryRow(category = category, onEdit = { onEdit(category) }, onDelete = { onDelete(category) })
            }
        }
    }
}

@Composable
private fun CategoryRow(category: ServerCategory, onEdit: () -> Unit, onDelete: () -> Unit) {
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = category.label, style = TitleM, color = OnInk)
                Text(
                    text = category.code,
                    color = OnInkMuted,
                    style = BodyM,
                )
            }
            Text(
                text = stringResource(
                    if (category.isBuiltin) R.string.category_manage_builtin else R.string.category_manage_custom,
                ),
                color = OnInkMuted,
                style = BodyM,
            )
            if (!category.isBuiltin) {
                IconButton(onClick = onEdit) {
                    Icon(Icons.Rounded.Edit, contentDescription = stringResource(R.string.category_edit_title))
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Rounded.DeleteOutline, contentDescription = stringResource(R.string.category_delete))
                }
            }
        }
        if (category.attributes.isNotEmpty()) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = category.attributes.entries.joinToString(" · ") { "${it.key}: ${it.value}" },
                color = OnInkMuted,
                style = BodyM,
            )
        }
    }
}

@Composable
private fun CategoryEditorPane(
    editor: CategoryEditor,
    busy: Boolean,
    errorRes: Int?,
    validationRule: String?,
    onChange: (CategoryEditor) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(PamSpacing.sm),
    ) {
        Text(
            text = stringResource(if (editor.isNew) R.string.category_new_title else R.string.category_edit_title),
            style = TitleM,
            color = OnInk,
        )
        when (validationRule) {
            "conflict" -> NoticeBanner(text = stringResource(R.string.category_conflict))
            null -> Unit
            else -> NoticeBanner(text = stringResource(R.string.category_invalid))
        }
        if (errorRes != null) NoticeBanner(text = stringResource(errorRes))
        OutlinedTextField(
            value = editor.label,
            onValueChange = { onChange(editor.copy(label = it)) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.category_field_label)) },
            singleLine = true,
            enabled = !busy,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
        )
        OutlinedTextField(
            value = editor.code,
            onValueChange = { onChange(editor.copy(code = it.lowercase())) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.category_field_code)) },
            singleLine = true,
            enabled = !busy && editor.isNew,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(PamSpacing.sm)) {
            PillButton(
                text = stringResource(R.string.category_kind_expense),
                onClick = { onChange(editor.copy(kind = CategoryEditor.KIND_EXPENSE)) },
                style = if (editor.kind == CategoryEditor.KIND_EXPENSE) PillButtonStyle.Primary else PillButtonStyle.Secondary,
                enabled = !busy,
            )
            PillButton(
                text = stringResource(R.string.category_kind_income),
                onClick = { onChange(editor.copy(kind = CategoryEditor.KIND_INCOME)) },
                style = if (editor.kind == CategoryEditor.KIND_INCOME) PillButtonStyle.Primary else PillButtonStyle.Secondary,
                enabled = !busy,
            )
        }
        DynamicFieldsEditor(
            fields = editor.fields,
            onFieldsChange = { onChange(editor.copy(fields = it)) },
            enabled = !busy,
        )
        Spacer(modifier = Modifier.height(PamSpacing.xs))
        Row(horizontalArrangement = Arrangement.spacedBy(PamSpacing.sm)) {
            PillButton(
                text = stringResource(R.string.category_save),
                onClick = onSave,
                loading = busy,
                enabled = !busy,
            )
            PillButton(
                text = stringResource(R.string.action_cancel),
                onClick = onCancel,
                style = PillButtonStyle.Secondary,
                enabled = !busy,
            )
        }
    }
}

@Composable
private fun DeleteCategoryDialog(
    category: ServerCategory,
    categories: List<ServerCategory>,
    busy: Boolean,
    needsReassign: Boolean,
    onConfirm: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    val options = remember(categories, category.id) { categories.filter { it.id != category.id } }
    var replacement by remember(category.id) { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(R.string.category_delete)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(PamSpacing.sm)) {
                Text(stringResource(R.string.category_delete_confirm, category.label))
                if (needsReassign) {
                    Text(stringResource(R.string.category_delete_in_use), color = OnInkMuted)
                    Text(stringResource(R.string.category_reassign_to), color = OnInkMuted)
                    options.take(6).forEach { option ->
                        TextButton(onClick = { replacement = option.id }, enabled = !busy) {
                            Text(
                                text = (if (replacement == option.id) "✓ " else "") + option.label,
                                color = OnInk,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(if (needsReassign) replacement else null) },
                enabled = !busy && (!needsReassign || replacement != null),
            ) {
                Text(stringResource(R.string.category_delete))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}
