package com.pam.recap.ui.feature.admin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pam.recap.core.result.AppError
import com.pam.recap.core.result.Outcome
import com.pam.recap.core.result.SafeLogger
import com.pam.recap.domain.repository.AdminAccountPatch
import com.pam.recap.domain.repository.AdminAuditEntry
import com.pam.recap.domain.repository.AdminInvitation
import com.pam.recap.domain.repository.AdminRepository
import com.pam.recap.domain.repository.AdminRole
import com.pam.recap.domain.repository.AdminRules
import com.pam.recap.domain.repository.AdminStatus
import com.pam.recap.domain.repository.ServerAdminAccount
import com.pam.recap.domain.repository.SessionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Stable form-validation codes so the screen resolves copy through `strings.xml`. */
object AdminFormRules {
    const val EMAIL = "invalid_email"
    const val ROLE = "invalid_role"
    const val STATUS = "invalid_status"
    const val EXPIRY = "invalid_expiry"
    const val DISPLAY_NAME = "display_name_too_long"
}

/** Success notices the screen may show as a transient banner. */
enum class AdminNotice { ACCOUNT_SAVED, ACCOUNT_DEACTIVATED, INVITATION_CREATED }

/** The editable slice of one account, carrying the revision used for the compare-and-swap. */
data class AdminAccountEditor(
    val ownerId: String,
    val email: String,
    val displayName: String = "",
    val role: String = AdminRole.USER,
    val status: String = AdminStatus.ACTIVE,
    val revision: Int = 0,
    val createdAt: String? = null,
    val updatedAt: String? = null,
) {
    val roleOptions: List<String> get() = AdminRole.values
    val statusOptions: List<String> get() = AdminStatus.writable
}

/** One screen state; every network phase has an explicit loading/error pair so none is silent. */
data class AdminUiState(
    val enabled: Boolean = true,
    val checkingRole: Boolean = true,
    val forbidden: Boolean = false,
    val role: String? = null,
    val loading: Boolean = false,
    val loadingMore: Boolean = false,
    val accounts: List<ServerAdminAccount> = emptyList(),
    val query: String = "",
    val roleFilter: String? = null,
    val statusFilter: String? = null,
    val nextCursor: String? = null,
    val accountsError: AppError? = null,
    val busy: Boolean = false,
    val editor: AdminAccountEditor? = null,
    val editorLoading: Boolean = false,
    val editorError: AppError? = null,
    val editorConflict: Boolean = false,
    val lastAdmin: Boolean = false,
    val inviteOpen: Boolean = false,
    val inviteEmail: String = "",
    val inviteRole: String = AdminRole.USER,
    val inviteExpiresHours: String = "72",
    val inviteValidation: String? = null,
    val inviteError: AppError? = null,
    val invitation: AdminInvitation? = null,
    val pendingDeactivate: ServerAdminAccount? = null,
    val audit: List<AdminAuditEntry> = emptyList(),
    val auditAction: String = "",
    val auditTarget: String = "",
    val auditCursor: String? = null,
    val auditLoading: Boolean = false,
    val auditError: AppError? = null,
    val notice: AdminNotice? = null,
)

/**
 * S4.2 admin user management (C21–C25) against the Go backend.
 *
 * The role gate runs first via `GET /v1/me`: a USER gets [AdminUiState.forbidden] and **no**
 * admin request is sent. List filters/cursors are server-side, and any q/role/status change
 * resets the cursor before the reload so a stale keyset scope can never be mixed in.
 */
@HiltViewModel
class AdminViewModel @Inject constructor(
    private val sessions: SessionRepository,
    private val admin: AdminRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(AdminUiState())
    val state: StateFlow<AdminUiState> = _state.asStateFlow()

    private var accountsJob: Job? = null
    private var auditJob: Job? = null

    init {
        checkRole()
    }

    /** Role gate + first load. Retry-safe: the screen can call this again after a network error. */
    fun checkRole() {
        viewModelScope.launch {
            _state.update {
                it.copy(
                    checkingRole = true,
                    forbidden = false,
                    accountsError = null,
                    auditError = null,
                )
            }
            if (!admin.isEnabled) {
                _state.update { it.copy(enabled = false, checkingRole = false, loading = false) }
                return@launch
            }
            val ownerId = ownerId()
            if (ownerId == null) {
                _state.update { it.copy(enabled = false, checkingRole = false, loading = false) }
                return@launch
            }
            when (val result = admin.currentRole(ownerId)) {
                is Outcome.Success -> {
                    if (result.value == AdminRole.ADMIN) {
                        _state.update {
                            it.copy(role = result.value, checkingRole = false, forbidden = false)
                        }
                        loadAccounts(reset = true)
                        loadAudit(reset = true)
                    } else {
                        // Non-admin: stop before any admin call is made.
                        SafeLogger.warning(TAG, "admin_role_not_admin", null)
                        _state.update {
                            it.copy(
                                role = result.value,
                                checkingRole = false,
                                forbidden = true,
                                loading = false,
                                accounts = emptyList(),
                                audit = emptyList(),
                            )
                        }
                    }
                }

                is Outcome.Failure -> {
                    SafeLogger.warning(TAG, "admin_role_check_failed", result.error)
                    _state.update {
                        it.copy(
                            checkingRole = false,
                            loading = false,
                            accountsError = result.error,
                        )
                    }
                }
            }
        }
    }

    /** Full refresh: clears the one-time invitation token and reloads both lists from page one. */
    fun refresh() {
        if (!canQuery()) return
        _state.update { it.copy(invitation = null, inviteOpen = false, notice = null) }
        loadAccounts(reset = true)
        loadAudit(reset = true)
    }

    fun onQueryChange(query: String) {
        if (_state.value.query == query) return
        _state.update { it.copy(query = query) }
        resetAccounts()
    }

    fun onRoleFilterChange(role: String?) {
        if (_state.value.roleFilter == role) return
        _state.update { it.copy(roleFilter = role) }
        resetAccounts()
    }

    fun onStatusFilterChange(status: String?) {
        if (_state.value.statusFilter == status) return
        _state.update { it.copy(statusFilter = status) }
        resetAccounts()
    }

    fun loadMore() {
        val snapshot = _state.value
        if (!canQuery() || snapshot.loading || snapshot.loadingMore || snapshot.nextCursor == null) {
            return
        }
        loadAccounts(reset = false)
    }

    private fun resetAccounts() {
        if (!canQuery()) return
        loadAccounts(reset = true)
    }

    private fun loadAccounts(reset: Boolean) {
        if (!canQuery()) return
        accountsJob?.cancel()
        accountsJob = viewModelScope.launch {
            _state.update {
                if (reset) {
                    it.copy(loading = true, loadingMore = false, accountsError = null, nextCursor = null)
                } else {
                    it.copy(loadingMore = true, accountsError = null)
                }
            }
            val snapshot = _state.value
            val ownerId = ownerId() ?: return@launch
            when (
                val result = admin.listAccounts(
                    ownerId = ownerId,
                    query = snapshot.query.trim().ifBlank { null },
                    role = snapshot.roleFilter,
                    status = snapshot.statusFilter,
                    limit = PAGE_SIZE,
                    cursor = if (reset) null else snapshot.nextCursor,
                )
            ) {
                is Outcome.Success -> _state.update {
                    val merged = if (reset) {
                        result.value.items
                    } else {
                        (it.accounts + result.value.items).distinctBy { account -> account.ownerId }
                    }
                    it.copy(
                        loading = false,
                        loadingMore = false,
                        accounts = merged,
                        nextCursor = result.value.nextCursor,
                        accountsError = null,
                    )
                }

                is Outcome.Failure -> {
                    val invalidCursor = result.error.debugDetail == AdminRules.INVALID_CURSOR
                    _state.update {
                        it.copy(
                            loading = false,
                            loadingMore = false,
                            accountsError = result.error,
                            nextCursor = if (invalidCursor) null else it.nextCursor,
                        )
                    }
                    // A cursor issued for another filter scope must restart from the first page.
                    if (invalidCursor && !reset) loadAccounts(reset = true)
                }
            }
        }
    }

    fun loadMoreAudit() {
        val snapshot = _state.value
        if (!canQuery() || snapshot.auditLoading || snapshot.auditCursor == null) return
        loadAudit(reset = false)
    }

    fun onAuditActionChange(action: String) {
        if (_state.value.auditAction == action) return
        _state.update { it.copy(auditAction = action) }
        resetAudit()
    }

    fun onAuditTargetChange(target: String) {
        if (_state.value.auditTarget == target) return
        _state.update { it.copy(auditTarget = target) }
        resetAudit()
    }

    private fun resetAudit() {
        if (!canQuery()) return
        loadAudit(reset = true)
    }

    private fun loadAudit(reset: Boolean) {
        if (!canQuery()) return
        auditJob?.cancel()
        auditJob = viewModelScope.launch {
            _state.update {
                if (reset) it.copy(auditLoading = true, auditError = null, auditCursor = null)
                else it.copy(auditLoading = true, auditError = null)
            }
            val snapshot = _state.value
            val ownerId = ownerId() ?: return@launch
            when (
                val result = admin.listAudit(
                    ownerId = ownerId,
                    targetOwnerId = snapshot.auditTarget.trim().ifBlank { null },
                    action = snapshot.auditAction.trim().ifBlank { null },
                    limit = PAGE_SIZE,
                    cursor = if (reset) null else snapshot.auditCursor,
                )
            ) {
                is Outcome.Success -> _state.update {
                    val merged = if (reset) {
                        result.value.items
                    } else {
                        (it.audit + result.value.items).distinctBy { entry -> entry.id }
                    }
                    it.copy(
                        auditLoading = false,
                        audit = merged,
                        auditCursor = result.value.nextCursor,
                        auditError = null,
                    )
                }

                is Outcome.Failure -> _state.update {
                    it.copy(auditLoading = false, auditError = result.error)
                }
            }
        }
    }

    // --- Invitations (C21) -------------------------------------------------------------------

    fun startInvite() {
        if (!canQuery()) return
        _state.update {
            it.copy(
                inviteOpen = true,
                invitation = null,
                inviteValidation = null,
                inviteError = null,
            )
        }
    }

    fun onInviteEmailChange(email: String) {
        _state.update { it.copy(inviteEmail = email, inviteValidation = null) }
    }

    fun onInviteRoleChange(role: String) {
        _state.update { it.copy(inviteRole = role, inviteValidation = null) }
    }

    fun onInviteExpiryChange(hours: String) {
        _state.update { it.copy(inviteExpiresHours = hours, inviteValidation = null) }
    }

    fun dismissInvitation() {
        // The raw token lives only in this state; closing the sheet discards it for good.
        _state.update {
            it.copy(
                inviteOpen = false,
                invitation = null,
                inviteEmail = "",
                inviteRole = AdminRole.USER,
                inviteExpiresHours = "72",
                inviteValidation = null,
                inviteError = null,
                busy = false,
            )
        }
    }

    fun submitInvite() {
        val snapshot = _state.value
        if (snapshot.busy || !canQuery()) return
        val email = snapshot.inviteEmail.trim()
        val role = snapshot.inviteRole.trim().uppercase()
        val hours = snapshot.inviteExpiresHours.trim().toIntOrNull()
        val validation = when {
            !EMAIL_PATTERN.matches(email) -> AdminFormRules.EMAIL
            role !in AdminRole.values -> AdminFormRules.ROLE
            hours == null || hours !in 1..720 -> AdminFormRules.EXPIRY
            else -> null
        }
        if (validation != null) {
            _state.update { it.copy(inviteValidation = validation) }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(busy = true, inviteError = null, inviteValidation = null) }
            val ownerId = ownerId() ?: return@launch
            when (val result = admin.createInvitation(ownerId, email, role, hours ?: 72)) {
                is Outcome.Success -> _state.update {
                    it.copy(
                        busy = false,
                        invitation = result.value,
                        notice = AdminNotice.INVITATION_CREATED,
                    )
                }

                is Outcome.Failure -> _state.update {
                    it.copy(busy = false, inviteError = result.error)
                }
            }
        }
    }

    // --- Account detail / update (C22, C23) --------------------------------------------------

    fun startEdit(account: ServerAdminAccount) {
        if (!canQuery()) return
        _state.update {
            it.copy(
                editorLoading = true,
                editorError = null,
                editorConflict = false,
                lastAdmin = false,
            )
        }
        viewModelScope.launch {
            val ownerId = ownerId()
            if (ownerId == null) {
                _state.update { it.copy(editorLoading = false) }
                return@launch
            }
            when (val result = admin.getAccount(ownerId, account.ownerId)) {
                is Outcome.Success -> _state.update {
                    it.copy(editorLoading = false, editor = result.value.toEditor())
                }

                is Outcome.Failure -> _state.update {
                    // The row already carries identity metadata; keep editing but surface the
                    // failed freshness check honestly.
                    it.copy(
                        editorLoading = false,
                        editor = account.toEditor(),
                        editorError = result.error,
                    )
                }
            }
        }
    }

    fun onEditorDisplayNameChange(displayName: String) {
        val editor = _state.value.editor ?: return
        _state.update { it.copy(editor = editor.copy(displayName = displayName)) }
    }

    fun onEditorRoleChange(role: String) {
        val editor = _state.value.editor ?: return
        _state.update { it.copy(editor = editor.copy(role = role)) }
    }

    fun onEditorStatusChange(status: String) {
        val editor = _state.value.editor ?: return
        _state.update { it.copy(editor = editor.copy(status = status)) }
    }

    fun cancelEditor() {
        _state.update {
            it.copy(editor = null, editorError = null, editorConflict = false, lastAdmin = false)
        }
    }

    fun saveEditor() {
        val editor = _state.value.editor ?: return
        if (_state.value.busy) return
        val displayName = editor.displayName.trim().ifBlank { null }
        val role = editor.role.trim().uppercase()
        val status = editor.status.trim().uppercase()
        val validation = when {
            displayName != null && displayName.length > 80 -> AdminFormRules.DISPLAY_NAME
            role !in AdminRole.values -> AdminFormRules.ROLE
            status !in AdminStatus.writable -> AdminFormRules.STATUS
            else -> null
        }
        if (validation != null) {
            _state.update { it.copy(editorError = AppError.Validation(validation)) }
            return
        }
        viewModelScope.launch {
            _state.update {
                it.copy(busy = true, editorError = null, editorConflict = false, lastAdmin = false)
            }
            val ownerId = ownerId() ?: return@launch
            val patch = AdminAccountPatch(
                displayName = displayName,
                role = role,
                status = status,
                revision = editor.revision,
            )
            when (val result = admin.updateAccount(ownerId, editor.ownerId, patch)) {
                is Outcome.Success -> {
                    _state.update {
                        it.copy(
                            busy = false,
                            editor = null,
                            notice = AdminNotice.ACCOUNT_SAVED,
                            editorConflict = false,
                            lastAdmin = false,
                        )
                    }
                    loadAccounts(reset = true)
                    loadAudit(reset = true)
                }

                is Outcome.Failure -> {
                    val detail = result.error.debugDetail
                    _state.update {
                        it.copy(
                            busy = false,
                            // Keep the draft on any failure; the conflict rule is explicit.
                            editorError = result.error,
                            editorConflict = detail == AdminRules.REVISION_CONFLICT,
                            lastAdmin = detail == AdminRules.LAST_ADMIN,
                        )
                    }
                }
            }
        }
    }

    /** Reload the freshest revision after a 409 while keeping the typed display-name draft. */
    fun reloadEditor() {
        val editor = _state.value.editor ?: return
        viewModelScope.launch {
            val ownerId = ownerId() ?: return@launch
            when (val result = admin.getAccount(ownerId, editor.ownerId)) {
                is Outcome.Success -> _state.update {
                    val fresh = result.value
                    it.copy(
                        editor = (it.editor ?: fresh.toEditor()).copy(
                            revision = fresh.revision,
                            role = fresh.role,
                            status = fresh.status,
                            createdAt = fresh.createdAt,
                            updatedAt = fresh.updatedAt,
                        ),
                        editorError = null,
                        editorConflict = false,
                    )
                }

                is Outcome.Failure -> _state.update { it.copy(editorError = result.error) }
            }
        }
    }

    // --- Deactivate (C24) --------------------------------------------------------------------

    fun requestDeactivate(account: ServerAdminAccount) {
        if (!canQuery()) return
        _state.update {
            it.copy(
                pendingDeactivate = account,
                editorError = null,
                editorConflict = false,
                lastAdmin = false,
            )
        }
    }

    fun cancelDeactivate() {
        _state.update { it.copy(pendingDeactivate = null) }
    }

    fun confirmDeactivate() {
        val target = _state.value.pendingDeactivate ?: return
        if (_state.value.busy) return
        viewModelScope.launch {
            _state.update { it.copy(busy = true, accountsError = null, lastAdmin = false) }
            val ownerId = ownerId() ?: return@launch
            when (val result = admin.deactivateAccount(ownerId, target.ownerId, target.revision)) {
                is Outcome.Success -> {
                    _state.update {
                        it.copy(
                            busy = false,
                            pendingDeactivate = null,
                            notice = AdminNotice.ACCOUNT_DEACTIVATED,
                            lastAdmin = false,
                        )
                    }
                    loadAccounts(reset = true)
                    loadAudit(reset = true)
                }

                is Outcome.Failure -> _state.update {
                    it.copy(
                        busy = false,
                        pendingDeactivate = null,
                        accountsError = result.error,
                        lastAdmin = result.error.debugDetail == AdminRules.LAST_ADMIN,
                    )
                }
            }
        }
    }

    fun dismissNotice() {
        _state.update { it.copy(notice = null) }
    }

    private fun canQuery(): Boolean {
        val snapshot = _state.value
        return snapshot.enabled && !snapshot.forbidden
    }

    private suspend fun ownerId(): String? = sessions.current()?.ownerId

    private fun ServerAdminAccount.toEditor(): AdminAccountEditor = AdminAccountEditor(
        ownerId = ownerId,
        email = email,
        displayName = displayName.orEmpty(),
        role = role,
        status = status,
        revision = revision,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

    private companion object {
        const val PAGE_SIZE = 50
        val EMAIL_PATTERN = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")
    }
}

private const val TAG = "AdminVM"
