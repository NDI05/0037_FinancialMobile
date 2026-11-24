package com.pam.recap.ui.feature.admin

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pam.recap.R
import com.pam.recap.domain.repository.AdminAuditEntry
import com.pam.recap.domain.repository.AdminRole
import com.pam.recap.domain.repository.AdminStatus
import com.pam.recap.domain.repository.ServerAdminAccount
import com.pam.recap.ui.components.GlassCard
import com.pam.recap.ui.components.GlassTopBar
import com.pam.recap.ui.components.MoneyTone
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
 * S4.2 admin user management (C21–C25): role gate, searchable account list, one-time invitation
 * token, revision-guarded edit/deactivate and the audit trail.
 *
 * Navigation and the Settings entry point are wired by the integration owner; this file only
 * exposes [AdminScreenRoot] to embed.
 */
@Composable
fun AdminScreenRoot(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AdminViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val clipboard = remember(context) {
        context.getSystemService(ClipboardManager::class.java)
    }
    AdminScreenContent(
        state = state,
        modifier = modifier,
        onBack = onBack,
        onRetryRole = viewModel::checkRole,
        onRefresh = viewModel::refresh,
        onQueryChange = viewModel::onQueryChange,
        onRoleFilterChange = viewModel::onRoleFilterChange,
        onStatusFilterChange = viewModel::onStatusFilterChange,
        onLoadMore = viewModel::loadMore,
        onStartInvite = viewModel::startInvite,
        onInviteEmailChange = viewModel::onInviteEmailChange,
        onInviteRoleChange = viewModel::onInviteRoleChange,
        onInviteExpiryChange = viewModel::onInviteExpiryChange,
        onSubmitInvite = viewModel::submitInvite,
        onDismissInvitation = viewModel::dismissInvitation,
        onCopyToken = { token ->
            clipboard?.setPrimaryClip(ClipData.newPlainText("admin_invitation_token", token))
        },
        onEditAccount = viewModel::startEdit,
        onEditorDisplayNameChange = viewModel::onEditorDisplayNameChange,
        onEditorRoleChange = viewModel::onEditorRoleChange,
        onEditorStatusChange = viewModel::onEditorStatusChange,
        onSaveEditor = viewModel::saveEditor,
        onReloadEditor = viewModel::reloadEditor,
        onCancelEditor = viewModel::cancelEditor,
        onRequestDeactivate = viewModel::requestDeactivate,
        onCancelDeactivate = viewModel::cancelDeactivate,
        onConfirmDeactivate = viewModel::confirmDeactivate,
        onAuditActionChange = viewModel::onAuditActionChange,
        onAuditTargetChange = viewModel::onAuditTargetChange,
        onLoadMoreAudit = viewModel::loadMoreAudit,
        onDismissNotice = viewModel::dismissNotice,
    )
}

/** Stateless screen: all state comes from [AdminUiState], all effects leave through callbacks. */
@Composable
fun AdminScreenContent(
    state: AdminUiState,
    modifier: Modifier = Modifier,
    onBack: () -> Unit = {},
    onRetryRole: () -> Unit = {},
    onRefresh: () -> Unit = {},
    onQueryChange: (String) -> Unit = {},
    onRoleFilterChange: (String?) -> Unit = {},
    onStatusFilterChange: (String?) -> Unit = {},
    onLoadMore: () -> Unit = {},
    onStartInvite: () -> Unit = {},
    onInviteEmailChange: (String) -> Unit = {},
    onInviteRoleChange: (String) -> Unit = {},
    onInviteExpiryChange: (String) -> Unit = {},
    onSubmitInvite: () -> Unit = {},
    onDismissInvitation: () -> Unit = {},
    onCopyToken: (String) -> Unit = {},
    onEditAccount: (ServerAdminAccount) -> Unit = {},
    onEditorDisplayNameChange: (String) -> Unit = {},
    onEditorRoleChange: (String) -> Unit = {},
    onEditorStatusChange: (String) -> Unit = {},
    onSaveEditor: () -> Unit = {},
    onReloadEditor: () -> Unit = {},
    onCancelEditor: () -> Unit = {},
    onRequestDeactivate: (ServerAdminAccount) -> Unit = {},
    onCancelDeactivate: () -> Unit = {},
    onConfirmDeactivate: () -> Unit = {},
    onAuditActionChange: (String) -> Unit = {},
    onAuditTargetChange: (String) -> Unit = {},
    onLoadMoreAudit: () -> Unit = {},
    onDismissNotice: () -> Unit = {},
) {
    PamScaffold(
        modifier = modifier,
        topBar = {
            GlassTopBar(title = stringResource(R.string.admin_title), onBack = onBack)
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            when {
                state.checkingRole -> StatusBlock(stringResource(R.string.admin_checking_role))
                !state.enabled -> StatusBlock(
                    text = stringResource(R.string.admin_offline),
                    actionLabel = stringResource(R.string.action_retry),
                    onAction = onRetryRole,
                )

                state.forbidden -> StatusBlock(
                    text = stringResource(R.string.admin_forbidden),
                    tone = MoneyTone.Expense,
                )

                else -> AdminBody(
                    state = state,
                    onRefresh = onRefresh,
                    onQueryChange = onQueryChange,
                    onRoleFilterChange = onRoleFilterChange,
                    onStatusFilterChange = onStatusFilterChange,
                    onLoadMore = onLoadMore,
                    onStartInvite = onStartInvite,
                    onEditAccount = onEditAccount,
                    onRequestDeactivate = onRequestDeactivate,
                    onAuditActionChange = onAuditActionChange,
                    onAuditTargetChange = onAuditTargetChange,
                    onLoadMoreAudit = onLoadMoreAudit,
                    onDismissNotice = onDismissNotice,
                )
            }
        }
    }

    if (state.inviteOpen) {
        InvitationDialog(
            state = state,
            onEmailChange = onInviteEmailChange,
            onRoleChange = onInviteRoleChange,
            onExpiryChange = onInviteExpiryChange,
            onSubmit = onSubmitInvite,
            onDismiss = onDismissInvitation,
            onCopyToken = onCopyToken,
        )
    }

    state.editor?.let { editor ->
        EditorDialog(
            state = state,
            editor = editor,
            onDisplayNameChange = onEditorDisplayNameChange,
            onRoleChange = onEditorRoleChange,
            onStatusChange = onEditorStatusChange,
            onSave = onSaveEditor,
            onReload = onReloadEditor,
            onDismiss = onCancelEditor,
        )
    }

    state.pendingDeactivate?.let { account ->
        AlertDialog(
            onDismissRequest = { if (!state.busy) onCancelDeactivate() },
            title = { Text(stringResource(R.string.admin_deactivate_title)) },
            text = {
                Text(
                    text = stringResource(
                        R.string.admin_deactivate_body,
                        account.email,
                        account.revision,
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = onConfirmDeactivate, enabled = !state.busy) {
                    Text(stringResource(R.string.admin_deactivate_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = onCancelDeactivate, enabled = !state.busy) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

@Composable
private fun StatusBlock(
    text: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    tone: MoneyTone = MoneyTone.Neutral,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(PamSpacing.md),
        verticalArrangement = Arrangement.spacedBy(PamSpacing.sm),
    ) {
        NoticeBanner(text = text, tone = tone)
        if (actionLabel != null && onAction != null) {
            PillButton(
                text = actionLabel,
                onClick = onAction,
                style = PillButtonStyle.Secondary,
            )
        }
    }
}

@Composable
private fun AdminBody(
    state: AdminUiState,
    onRefresh: () -> Unit,
    onQueryChange: (String) -> Unit,
    onRoleFilterChange: (String?) -> Unit,
    onStatusFilterChange: (String?) -> Unit,
    onLoadMore: () -> Unit,
    onStartInvite: () -> Unit,
    onEditAccount: (ServerAdminAccount) -> Unit,
    onRequestDeactivate: (ServerAdminAccount) -> Unit,
    onAuditActionChange: (String) -> Unit,
    onAuditTargetChange: (String) -> Unit,
    onLoadMoreAudit: () -> Unit,
    onDismissNotice: () -> Unit,
) {
    // Keep the typed text local so fast keystrokes are not dropped or reordered while a page
    // load recomposes; the ViewModel remains the source of truth for the applied query.
    var queryText by remember { mutableStateOf(state.query) }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = PamSpacing.md, vertical = PamSpacing.sm),
        verticalArrangement = Arrangement.spacedBy(PamSpacing.sm),
    ) {
        item {
            state.notice?.let { notice ->
                NoticeBanner(
                    text = stringResource(noticeRes(notice)),
                    tone = MoneyTone.Income,
                    action = {
                        TextButton(onClick = onDismissNotice) {
                            Text(stringResource(R.string.action_close))
                        }
                    },
                )
            }
        }
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(PamSpacing.sm),
            ) {
                OutlinedTextField(
                    value = queryText,
                    onValueChange = {
                        queryText = it
                        onQueryChange(it)
                    },
                    modifier = Modifier.weight(1f).testTag("admin_search_field"),
                    label = { Text(stringResource(R.string.admin_search)) },
                    singleLine = true,
                    enabled = !state.busy,
                )
                PillButton(
                    text = stringResource(R.string.admin_invite_action),
                    onClick = onStartInvite,
                    enabled = !state.busy,
                )
            }
        }
        item {
            FilterRow(
                allLabel = stringResource(R.string.admin_filter_role_all),
                options = AdminRole.values,
                selected = state.roleFilter,
                onSelect = onRoleFilterChange,
            )
        }
        item {
            FilterRow(
                allLabel = stringResource(R.string.admin_filter_status_all),
                options = AdminStatus.filterable,
                selected = state.statusFilter,
                onSelect = onStatusFilterChange,
            )
        }
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.admin_account_count, state.accounts.size),
                    color = OnInkMuted,
                    style = BodyM,
                    modifier = Modifier.weight(1f),
                )
                PillButton(
                    text = stringResource(R.string.admin_refresh),
                    onClick = onRefresh,
                    style = PillButtonStyle.Ghost,
                    loading = state.loading,
                    enabled = !state.busy,
                )
            }
        }
        if (state.accountsError != null) {
            item {
                NoticeBanner(
                    text = stringResource(appErrorRes(state.accountsError)),
                    tone = MoneyTone.Expense,
                )
            }
        }
        if (state.lastAdmin) {
            item { NoticeBanner(text = stringResource(R.string.admin_last_admin), tone = MoneyTone.Expense) }
        }
        if (state.accounts.isEmpty() && !state.loading) {
            item { NoticeBanner(text = stringResource(R.string.admin_empty)) }
        }
        items(state.accounts, key = { it.ownerId }) { account ->
            AccountRow(
                account = account,
                onEdit = { onEditAccount(account) },
                onDeactivate = { onRequestDeactivate(account) },
            )
        }
        if (state.nextCursor != null) {
            item {
                PillButton(
                    text = stringResource(R.string.admin_load_more),
                    onClick = onLoadMore,
                    style = PillButtonStyle.Secondary,
                    loading = state.loadingMore,
                    enabled = !state.loading && !state.loadingMore,
                )
            }
        }
        item { Spacer(modifier = Modifier.height(PamSpacing.xs)) }
        item { Text(text = stringResource(R.string.admin_audit_title), style = TitleM, color = OnInk) }
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(PamSpacing.sm),
            ) {
                OutlinedTextField(
                    value = state.auditAction,
                    onValueChange = onAuditActionChange,
                    modifier = Modifier.weight(1f).testTag("admin_audit_action_field"),
                    label = { Text(stringResource(R.string.admin_audit_action)) },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = state.auditTarget,
                    onValueChange = onAuditTargetChange,
                    modifier = Modifier.weight(1f).testTag("admin_audit_target_field"),
                    label = { Text(stringResource(R.string.admin_audit_target)) },
                    singleLine = true,
                )
            }
        }
        if (state.auditError != null) {
            item {
                NoticeBanner(
                    text = stringResource(appErrorRes(state.auditError)),
                    tone = MoneyTone.Expense,
                )
            }
        }
        if (state.audit.isEmpty() && !state.auditLoading) {
            item { NoticeBanner(text = stringResource(R.string.admin_audit_empty)) }
        }
        items(state.audit, key = { it.id }) { entry -> AuditRow(entry) }
        if (state.auditCursor != null) {
            item {
                PillButton(
                    text = stringResource(R.string.admin_audit_load_more),
                    onClick = onLoadMoreAudit,
                    style = PillButtonStyle.Secondary,
                    loading = state.auditLoading,
                    enabled = !state.auditLoading,
                )
            }
        }
    }
}

@Composable
private fun FilterRow(
    allLabel: String,
    options: List<String>,
    selected: String?,
    onSelect: (String?) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(PamSpacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FilterChipButton(
            label = allLabel,
            selected = selected == null,
            onClick = { onSelect(null) },
        )
        options.forEach { option ->
            FilterChipButton(
                label = option,
                selected = selected == option,
                onClick = { onSelect(option) },
            )
        }
    }
}

@Composable
private fun FilterChipButton(label: String, selected: Boolean, onClick: () -> Unit) {
    PillButton(
        text = label,
        onClick = onClick,
        style = if (selected) PillButtonStyle.Primary else PillButtonStyle.Secondary,
    )
}

@Composable
private fun AccountRow(
    account: ServerAdminAccount,
    onEdit: () -> Unit,
    onDeactivate: () -> Unit,
) {
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = account.displayName?.takeIf { it.isNotBlank() } ?: account.email,
                    style = TitleM,
                    color = OnInk,
                )
                Text(text = account.email, color = OnInkMuted, style = BodyM)
                Text(
                    text = stringResource(
                        R.string.admin_account_meta,
                        account.role,
                        account.status,
                        account.revision,
                    ),
                    color = OnInkMuted,
                    style = BodyM,
                )
            }
            IconButton(onClick = onEdit) {
                Icon(Icons.Rounded.Edit, contentDescription = stringResource(R.string.admin_edit_title))
            }
            IconButton(onClick = onDeactivate) {
                Icon(
                    Icons.Rounded.DeleteOutline,
                    contentDescription = stringResource(R.string.admin_deactivate),
                )
            }
        }
    }
}

@Composable
private fun AuditRow(entry: AdminAuditEntry) {
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Column {
            Text(text = entry.action, style = TitleM, color = OnInk)
            Text(
                text = stringResource(
                    R.string.admin_audit_meta,
                    entry.actorOwnerId,
                    entry.targetOwnerId,
                ),
                color = OnInkMuted,
                style = BodyM,
            )
            entry.createdAt?.let { createdAt ->
                Text(text = createdAt, color = OnInkMuted, style = BodyM)
            }
        }
    }
}

@Composable
private fun InvitationDialog(
    state: AdminUiState,
    onEmailChange: (String) -> Unit,
    onRoleChange: (String) -> Unit,
    onExpiryChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onDismiss: () -> Unit,
    onCopyToken: (String) -> Unit,
) {
    val invitation = state.invitation
    var copied by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { if (!state.busy) onDismiss() },
        title = {
            Text(
                stringResource(
                    if (invitation != null) R.string.admin_invite_token_title
                    else R.string.admin_invite_title,
                ),
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(PamSpacing.sm)) {
                if (invitation != null) {
                    // The raw token is returned exactly once; the backend stores only its hash.
                    Text(
                        text = stringResource(R.string.admin_invite_token_warning),
                        color = OnInkMuted,
                    )
                    Text(
                        text = invitation.invitationToken,
                        style = TitleM,
                        color = OnInk,
                        modifier = Modifier.testTag("admin_invitation_token"),
                    )
                    Text(
                        text = stringResource(
                            R.string.admin_invite_created_meta,
                            invitation.invitation.email,
                            invitation.invitation.role,
                        ),
                        color = OnInkMuted,
                        style = BodyM,
                    )
                    if (copied) {
                        Text(
                            text = stringResource(R.string.admin_invite_token_copied),
                            color = OnInkMuted,
                            style = BodyM,
                        )
                    }
                } else {
                    AdminInvitationForm(
                        state = state,
                        onEmailChange = onEmailChange,
                        onRoleChange = onRoleChange,
                        onExpiryChange = onExpiryChange,
                    )
                }
            }
        },
        confirmButton = {
            if (invitation != null) {
                TextButton(
                    onClick = {
                        copied = true
                        onCopyToken(invitation.invitationToken)
                    },
                ) {
                    Text(stringResource(R.string.admin_invite_token_copy))
                }
            } else {
                TextButton(onClick = onSubmit, enabled = !state.busy) {
                    Text(stringResource(R.string.admin_invite_submit))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !state.busy) {
                Text(
                    stringResource(
                        if (invitation != null) R.string.admin_invite_done
                        else R.string.action_cancel,
                    ),
                )
            }
        },
    )
}

@Composable
private fun EditorDialog(
    state: AdminUiState,
    editor: AdminAccountEditor,
    onDisplayNameChange: (String) -> Unit,
    onRoleChange: (String) -> Unit,
    onStatusChange: (String) -> Unit,
    onSave: () -> Unit,
    onReload: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!state.busy) onDismiss() },
        title = { Text(stringResource(R.string.admin_edit_title)) },
        text = {
            AdminEditorForm(
                state = state,
                editor = editor,
                onDisplayNameChange = onDisplayNameChange,
                onRoleChange = onRoleChange,
                onStatusChange = onStatusChange,
                onReload = onReload,
            )
        },
        confirmButton = {
            TextButton(onClick = onSave, enabled = !state.busy) {
                Text(stringResource(R.string.admin_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !state.busy) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

/**
 * Form body of the invitation dialog, kept as a standalone composable so it can be rendered and
 * asserted without a dialog window (Robolectric cannot idle a text field hosted in a dialog).
 */
@Composable
internal fun AdminInvitationForm(
    state: AdminUiState,
    onEmailChange: (String) -> Unit,
    onRoleChange: (String) -> Unit,
    onExpiryChange: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(PamSpacing.sm)) {
        OutlinedTextField(
            value = state.inviteEmail,
            onValueChange = onEmailChange,
            modifier = Modifier.fillMaxWidth().testTag("admin_invite_email_field"),
            label = { Text(stringResource(R.string.admin_invite_email)) },
            singleLine = true,
            enabled = !state.busy,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(PamSpacing.sm)) {
            AdminRole.values.forEach { role ->
                PillButton(
                    text = role,
                    onClick = { onRoleChange(role) },
                    style = if (state.inviteRole == role) PillButtonStyle.Primary
                    else PillButtonStyle.Secondary,
                    enabled = !state.busy,
                )
            }
        }
        OutlinedTextField(
            value = state.inviteExpiresHours,
            onValueChange = onExpiryChange,
            modifier = Modifier.fillMaxWidth().testTag("admin_invite_expiry_field"),
            label = { Text(stringResource(R.string.admin_invite_expiry)) },
            singleLine = true,
            enabled = !state.busy,
        )
        state.inviteValidation?.let { code ->
            NoticeBanner(
                text = stringResource(validationRes(code)),
                tone = MoneyTone.Expense,
            )
        }
        state.inviteError?.let { error ->
            NoticeBanner(
                text = stringResource(appErrorRes(error)),
                tone = MoneyTone.Expense,
            )
        }
    }
}

/**
 * Form body of the edit sheet. Extracted for the same reason as [AdminInvitationForm]: the editor
 * logic (draft retention, conflict reload, last-admin rule) must stay assertable in JVM tests.
 */
@Composable
internal fun AdminEditorForm(
    state: AdminUiState,
    editor: AdminAccountEditor,
    onDisplayNameChange: (String) -> Unit,
    onRoleChange: (String) -> Unit,
    onStatusChange: (String) -> Unit,
    onReload: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(PamSpacing.sm)) {
        Text(text = editor.email, style = TitleM, color = OnInk)
        Text(
            text = stringResource(R.string.admin_revision, editor.revision),
            color = OnInkMuted,
            style = BodyM,
        )
        OutlinedTextField(
            value = editor.displayName,
            onValueChange = onDisplayNameChange,
            modifier = Modifier.fillMaxWidth().testTag("admin_editor_display_name_field"),
            label = { Text(stringResource(R.string.admin_field_display_name)) },
            singleLine = true,
            enabled = !state.busy,
        )
        Text(text = stringResource(R.string.admin_field_role), style = BodyM, color = OnInkMuted)
        Row(horizontalArrangement = Arrangement.spacedBy(PamSpacing.sm)) {
            editor.roleOptions.forEach { role ->
                PillButton(
                    text = role,
                    onClick = { onRoleChange(role) },
                    style = if (editor.role == role) PillButtonStyle.Primary
                    else PillButtonStyle.Secondary,
                    enabled = !state.busy,
                )
            }
        }
        Text(text = stringResource(R.string.admin_field_status), style = BodyM, color = OnInkMuted)
        Row(horizontalArrangement = Arrangement.spacedBy(PamSpacing.sm)) {
            editor.statusOptions.forEach { status ->
                PillButton(
                    text = status,
                    onClick = { onStatusChange(status) },
                    style = if (editor.status == status) PillButtonStyle.Primary
                    else PillButtonStyle.Secondary,
                    enabled = !state.busy,
                )
            }
        }
        if (state.lastAdmin) {
            NoticeBanner(
                text = stringResource(R.string.admin_last_admin),
                tone = MoneyTone.Expense,
            )
        }
        if (state.editorConflict) {
            NoticeBanner(
                text = stringResource(R.string.admin_edit_conflict),
                tone = MoneyTone.Expense,
                action = {
                    TextButton(onClick = onReload) {
                        Text(stringResource(R.string.admin_reload))
                    }
                },
            )
        }
        state.editorError?.let { error ->
            if (!state.editorConflict) {
                NoticeBanner(
                    text = stringResource(appErrorRes(error)),
                    tone = MoneyTone.Expense,
                )
            }
        }
    }
}

private fun noticeRes(notice: AdminNotice): Int = when (notice) {
    AdminNotice.ACCOUNT_SAVED -> R.string.admin_account_saved
    AdminNotice.ACCOUNT_DEACTIVATED -> R.string.admin_deactivated
    AdminNotice.INVITATION_CREATED -> R.string.admin_invite_created
}

private fun validationRes(code: String): Int = when (code) {
    AdminFormRules.EMAIL -> R.string.admin_invite_invalid_email
    AdminFormRules.ROLE -> R.string.admin_invite_invalid_role
    AdminFormRules.STATUS -> R.string.admin_edit_invalid
    AdminFormRules.EXPIRY -> R.string.admin_invite_invalid_expiry
    AdminFormRules.DISPLAY_NAME -> R.string.admin_edit_invalid
    else -> R.string.admin_edit_invalid
}
