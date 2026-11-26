package com.pam.recap.ui.feature.category

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Fastfood
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Label
import androidx.compose.material.icons.rounded.MedicalServices
import androidx.compose.material.icons.rounded.Payments
import androidx.compose.material.icons.rounded.ReceiptLong
import androidx.compose.material.icons.rounded.School
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.ShoppingBag
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pam.recap.R
import com.pam.recap.domain.repository.ServerCategory
import com.pam.recap.ui.components.DynamicFieldsEditor
import com.pam.recap.ui.components.NoticeBanner
import com.pam.recap.ui.components.PamScaffold
import com.pam.recap.ui.components.PillButton
import com.pam.recap.ui.components.PillButtonStyle
import com.pam.recap.ui.theme.Accent
import com.pam.recap.ui.theme.BodyM
import com.pam.recap.ui.theme.BodyS
import com.pam.recap.ui.theme.LabelEyebrow
import com.pam.recap.ui.theme.OnAccent
import com.pam.recap.ui.theme.OnInk
import com.pam.recap.ui.theme.OnInkMuted
import com.pam.recap.ui.theme.OutlineVariant
import com.pam.recap.ui.theme.PamRadius
import com.pam.recap.ui.theme.PamSize
import com.pam.recap.ui.theme.PamSpacing
import com.pam.recap.ui.theme.Primary
import com.pam.recap.ui.theme.Surface
import com.pam.recap.ui.theme.SurfaceLow
import com.pam.recap.ui.theme.TitleL
import com.pam.recap.ui.theme.TitleM
import com.pam.recap.ui.util.appErrorRes

/**
 * S3 category management in the Stitch "Kelola kategori" layout: centered title with back,
 * search field, one grouped white list and a floating add action. Create and rename open the
 * Stitch modal (title, icon preview, name field, dark save) over the list; advanced contract
 * fields sit behind the modal's tune action so no existing capability is lost. All server
 * behaviour stays: search (local + server), attribute pairs and delete-with-reassignment.
 * Built-ins keep the protected state and never offer edit/delete.
 *
 * Deviation from the prototype: per-category transaction counts are not part of the server
 * contract or the view-model state, so rows show the real category kind instead of a made-up
 * number.
 */
@Composable
fun CategoryManagementScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: CategoryManagementViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val editor = state.editor

    PamScaffold(
        modifier = modifier,
        topBar = {
            CategoryHeader(
                title = stringResource(R.string.category_manage_title),
                onBack = onBack,
            )
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            CategoryList(
                state = state,
                onQueryChange = viewModel::onQueryChange,
                onEdit = viewModel::startEdit,
                onDelete = viewModel::requestDelete,
            )
            AddCategoryFab(
                enabled = state.serverEnabled,
                onClick = viewModel::startCreate,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(bottom = PamSpacing.xl),
            )
        }
    }

    if (editor != null) {
        CategoryEditorDialog(
            editor = editor,
            busy = state.busy,
            errorRes = state.error?.let { appErrorRes(it) },
            validationRule = state.validationRule,
            onChange = viewModel::onEditorChange,
            onSave = viewModel::save,
            onDismiss = viewModel::dismissEditor,
        )
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
private fun CategoryHeader(title: String, onBack: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .padding(horizontal = PamSpacing.md),
    ) {
        IconButton(
            onClick = onBack,
            modifier = Modifier.align(Alignment.CenterStart),
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                contentDescription = stringResource(R.string.action_back),
                tint = OnInk,
                modifier = Modifier.size(22.dp),
            )
        }
        Text(
            text = title,
            style = TitleL,
            color = OnInk,
            textAlign = TextAlign.Center,
            modifier = Modifier.align(Alignment.Center),
        )
    }
}

@Composable
private fun CategoryList(
    state: CategoryManagementUiState,
    onQueryChange: (String) -> Unit,
    onEdit: (ServerCategory) -> Unit,
    onDelete: (ServerCategory) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(PamSpacing.md),
    ) {
        when {
            !state.serverEnabled -> NoticeBanner(text = stringResource(R.string.category_manage_offline))
            state.error != null -> NoticeBanner(text = stringResource(appErrorRes(state.error)))
            state.validationRule == "builtin" -> NoticeBanner(text = stringResource(R.string.category_builtin_protected))
            state.validationRule != null -> NoticeBanner(text = stringResource(R.string.category_invalid))
        }

        CategorySearchField(
            query = state.query,
            onQueryChange = onQueryChange,
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.category_manage_count, state.visible.size),
                style = LabelEyebrow,
                color = OnInkMuted,
            )
        }

        if (state.searchedOnServer && state.hasMore) {
            Text(
                text = stringResource(
                    R.string.category_more_hint,
                    state.categories.size,
                    state.serverScanned,
                ),
                style = BodyS,
                color = OnInkMuted,
            )
        }

        if (state.visible.isEmpty() && !state.isLoading) {
            NoticeBanner(text = stringResource(R.string.category_manage_empty))
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(PamRadius.xl))
                .background(Surface)
                .padding(vertical = PamSpacing.xxs),
            contentPadding = PaddingValues(bottom = PamSize.fab + PamSpacing.xxl),
        ) {
            itemsIndexed(state.visible, key = { _, category -> category.id }) { index, category ->
                if (index > 0) {
                    CategoryDivider()
                }
                CategoryRow(
                    category = category,
                    onEdit = { onEdit(category) },
                    onDelete = { onDelete(category) },
                )
            }
        }
    }
}

@Composable
private fun CategorySearchField(query: String, onQueryChange: (String) -> Unit) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier.fillMaxWidth().height(PamSize.minTouch),
        singleLine = true,
        textStyle = BodyS.copy(fontSize = 14.sp),
        placeholder = {
            Text(
                text = stringResource(R.string.category_search_placeholder),
                style = BodyS,
                color = OnInkMuted,
            )
        },
        leadingIcon = {
            Icon(
                imageVector = Icons.Rounded.Search,
                contentDescription = null,
                tint = OnInkMuted,
                modifier = Modifier.size(20.dp),
            )
        },
        trailingIcon = if (query.isNotEmpty()) {
            {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = stringResource(R.string.action_clear),
                        tint = OnInkMuted,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        } else {
            null
        },
        shape = RoundedCornerShape(PamRadius.sm),
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = Surface,
            unfocusedContainerColor = Surface,
            focusedBorderColor = Primary,
            unfocusedBorderColor = OutlineVariant,
            focusedTextColor = OnInk,
            unfocusedTextColor = OnInk,
            cursorColor = Primary,
        ),
    )
}

@Composable
private fun CategoryRow(
    category: ServerCategory,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !category.isBuiltin, onClick = onEdit)
            .padding(horizontal = PamSpacing.md, vertical = PamSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(PamSpacing.sm),
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(PamRadius.lg))
                .background(SurfaceLow),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = categoryIcon(category),
                contentDescription = null,
                tint = OnInk,
                modifier = Modifier.size(22.dp),
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = category.label,
                style = TitleM,
                color = OnInk,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = stringResource(
                    if (category.kind == CategoryEditor.KIND_INCOME) {
                        R.string.category_kind_income
                    } else {
                        R.string.category_kind_expense
                    },
                ),
                style = BodyS,
                color = OnInkMuted,
            )
        }
        if (category.isBuiltin) {
            Text(
                text = stringResource(R.string.category_manage_builtin),
                style = LabelEyebrow,
                color = OnInkMuted,
                modifier = Modifier
                    .clip(RoundedCornerShape(PamRadius.pill))
                    .background(SurfaceLow)
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            )
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(PamSpacing.xxs)) {
                IconButton(onClick = onEdit, modifier = Modifier.size(44.dp)) {
                    Icon(
                        imageVector = Icons.Rounded.Edit,
                        contentDescription = stringResource(R.string.category_edit_title),
                        tint = OnInkMuted,
                        modifier = Modifier.size(20.dp),
                    )
                }
                IconButton(onClick = onDelete, modifier = Modifier.size(44.dp)) {
                    Icon(
                        imageVector = Icons.Rounded.DeleteOutline,
                        contentDescription = stringResource(R.string.category_delete),
                        tint = OnInkMuted,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun CategoryDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 76.dp, end = PamSpacing.md)
            .height(1.dp)
            .background(OutlineVariant),
    )
}

@Composable
private fun AddCategoryFab(
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(PamSize.fab)
            .clip(RoundedCornerShape(PamRadius.xl))
            .background(if (enabled) Accent else SurfaceLow)
            .clickable(enabled = enabled, role = androidx.compose.ui.semantics.Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Rounded.Add,
            contentDescription = stringResource(R.string.category_manage_add),
            tint = if (enabled) OnAccent else OnInkMuted,
            modifier = Modifier.size(26.dp),
        )
    }
}

private fun categoryIcon(category: ServerCategory): ImageVector =
    categoryIcon("${category.code} ${category.label}")

/** Shared by list rows and the add/edit dialog so the preview matches the saved row. */
private fun categoryIcon(key: String): ImageVector {
    val normalized = key.lowercase()
    return when {
        normalized.contains("income") || normalized.contains("pemasukan") || normalized.contains("gaji") -> Icons.Rounded.Payments
        normalized.contains("food") || normalized.contains("makan") || normalized.contains("minum") -> Icons.Rounded.Fastfood
        normalized.contains("transport") -> Icons.Rounded.DirectionsCar
        normalized.contains("housing") || normalized.contains("tinggal") || normalized.contains("sewa") -> Icons.Rounded.Home
        normalized.contains("util") || normalized.contains("tagihan") -> Icons.Rounded.ReceiptLong
        normalized.contains("shop") || normalized.contains("belanja") -> Icons.Rounded.ShoppingBag
        normalized.contains("health") || normalized.contains("kesehatan") -> Icons.Rounded.MedicalServices
        normalized.contains("edu") || normalized.contains("pendidikan") -> Icons.Rounded.School
        normalized.contains("transfer") -> Icons.Rounded.SwapHoriz
        else -> Icons.Rounded.Label
    }
}

/**
 * Stitch "Tambah / Ubah kategori" modal: title + close, a lime icon preview, a name field and one
 * full-width dark save button. The advanced contract fields (kind, code, attributes) stay available
 * behind the tune action instead of being dropped.
 */
@Composable
private fun CategoryEditorDialog(
    editor: CategoryEditor,
    busy: Boolean,
    errorRes: Int?,
    validationRule: String?,
    onChange: (CategoryEditor) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
) {
    var advanced by remember(editor.id) { mutableStateOf(false) }
    val bannerRes = when {
        validationRule == "conflict" -> R.string.category_conflict
        errorRes != null -> errorRes
        validationRule != null -> R.string.category_invalid
        else -> null
    }
    val shape = RoundedCornerShape(PamRadius.xl)
    Dialog(
        onDismissRequest = { if (!busy) onDismiss() },
        properties = DialogProperties(
            dismissOnBackPress = !busy,
            dismissOnClickOutside = !busy,
            usePlatformDefaultWidth = false,
        ),
    ) {
        // LocalView must be read inside the dialog subcomposition so the parent is its DialogLayout.
        val dialogWindow = (LocalView.current.parent as? DialogWindowProvider)?.window
        DisposableEffect(dialogWindow) {
            val applyDim = Runnable { dialogWindow?.setDimAmount(0.3f) }
            dialogWindow?.decorView?.post(applyDim)
            onDispose { dialogWindow?.decorView?.removeCallbacks(applyDim) }
        }
        Box(
            modifier = Modifier.fillMaxWidth().padding(horizontal = PamSpacing.md),
            contentAlignment = Alignment.Center,
        ) {
            androidx.compose.material3.Surface(
                modifier = Modifier
                    .widthIn(max = 353.dp)
                    .fillMaxWidth()
                    .heightIn(max = 640.dp),
                shape = shape,
                color = Surface,
                border = BorderStroke(1.dp, OutlineVariant),
            ) {
                Column(
                    modifier = Modifier
                        .verticalScroll(rememberScrollState())
                        .padding(PamSpacing.lg),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().heightIn(min = PamSize.minTouch),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(
                                if (editor.isNew) R.string.category_manage_add else R.string.category_edit_title,
                            ),
                            style = TitleL,
                            color = OnInk,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = { advanced = !advanced }, enabled = !busy) {
                            Icon(
                                imageVector = Icons.Rounded.Tune,
                                contentDescription = stringResource(R.string.category_advanced_options),
                                tint = if (advanced) OnInk else OnInkMuted,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                        IconButton(onClick = onDismiss, enabled = !busy) {
                            Icon(
                                imageVector = Icons.Rounded.Close,
                                contentDescription = stringResource(R.string.action_close),
                                tint = OnInk,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }

                    if (bannerRes != null) {
                        Spacer(modifier = Modifier.height(PamSpacing.sm))
                        NoticeBanner(text = stringResource(bannerRes))
                    }

                    Spacer(modifier = Modifier.height(PamSpacing.md))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(PamSpacing.sm),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(PamSize.minTouch)
                                .clip(RoundedCornerShape(PamRadius.md))
                                .background(Accent)
                                .border(1.dp, OutlineVariant, RoundedCornerShape(PamRadius.md)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = categoryIcon("${editor.code} ${editor.label}"),
                                contentDescription = null,
                                tint = OnAccent,
                                modifier = Modifier.size(22.dp),
                            )
                        }
                        CategoryNameField(
                            value = editor.label,
                            onValueChange = { onChange(editor.copy(label = it)) },
                            enabled = !busy,
                            modifier = Modifier.weight(1f),
                        )
                    }

                    if (advanced) {
                        Spacer(modifier = Modifier.height(PamSpacing.md))
                        Row(horizontalArrangement = Arrangement.spacedBy(PamSpacing.sm)) {
                            PillButton(
                                text = stringResource(R.string.category_kind_expense),
                                onClick = { onChange(editor.copy(kind = CategoryEditor.KIND_EXPENSE)) },
                                style = if (editor.kind == CategoryEditor.KIND_EXPENSE) {
                                    PillButtonStyle.Primary
                                } else {
                                    PillButtonStyle.Secondary
                                },
                                enabled = !busy,
                            )
                            PillButton(
                                text = stringResource(R.string.category_kind_income),
                                onClick = { onChange(editor.copy(kind = CategoryEditor.KIND_INCOME)) },
                                style = if (editor.kind == CategoryEditor.KIND_INCOME) {
                                    PillButtonStyle.Primary
                                } else {
                                    PillButtonStyle.Secondary
                                },
                                enabled = !busy,
                            )
                        }
                        Spacer(modifier = Modifier.height(PamSpacing.sm))
                        CategoryField(
                            value = editor.code,
                            onValueChange = { onChange(editor.copy(code = it.lowercase())) },
                            label = stringResource(R.string.category_field_code),
                            enabled = !busy && editor.isNew,
                            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None),
                        )
                        Spacer(modifier = Modifier.height(PamSpacing.sm))
                        DynamicFieldsEditor(
                            fields = editor.fields,
                            onFieldsChange = { onChange(editor.copy(fields = it)) },
                            enabled = !busy,
                        )
                    }

                    Spacer(modifier = Modifier.height(PamSpacing.md))
                    PillButton(
                        text = stringResource(R.string.category_save),
                        onClick = onSave,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(PamRadius.md),
                        loading = busy,
                        enabled = !busy,
                    )
                }
            }
        }
    }
}

@Composable
private fun CategoryNameField(
    value: String,
    onValueChange: (String) -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(PamRadius.md)
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier
            .heightIn(min = PamSize.minTouch)
            .clip(shape)
            .background(SurfaceLow)
            .border(1.dp, if (focused) Primary else OutlineVariant, shape),
        enabled = enabled,
        singleLine = true,
        textStyle = BodyM.copy(color = OnInk),
        cursorBrush = SolidColor(Primary),
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
        interactionSource = interaction,
        decorationBox = { innerTextField ->
            Box(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = PamSpacing.sm),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (value.isEmpty()) {
                    Text(
                        text = stringResource(R.string.category_name_placeholder),
                        style = BodyM,
                        color = OnInkMuted,
                    )
                }
                innerTextField()
            }
        },
    )
}

@Composable
private fun CategoryField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    enabled: Boolean,
    keyboardOptions: KeyboardOptions,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label) },
        singleLine = true,
        enabled = enabled,
        keyboardOptions = keyboardOptions,
        shape = RoundedCornerShape(PamRadius.sm),
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = Surface,
            unfocusedContainerColor = Surface,
            focusedBorderColor = Primary,
            unfocusedBorderColor = OutlineVariant,
            focusedTextColor = OnInk,
            unfocusedTextColor = OnInk,
            focusedLabelColor = OnInkMuted,
            unfocusedLabelColor = OnInkMuted,
            cursorColor = Primary,
        ),
    )
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
        containerColor = Surface,
        shape = RoundedCornerShape(PamRadius.xl),
    )
}
