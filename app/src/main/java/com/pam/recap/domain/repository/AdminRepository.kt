package com.pam.recap.domain.repository

import com.pam.recap.core.result.AppError
import com.pam.recap.core.result.Outcome

/**
 * S4.2 admin user management port (acceptance C21–C25).
 *
 * The admin surface is identity/role metadata only — no other owner's finance data is reachable
 * through it. [currentRole] is the gate: the UI must check it first and must not send any admin
 * request when the role is not [AdminRole.ADMIN].
 *
 * Rule/status strings mirror the Go backend exactly:
 * - roles: [AdminRole.USER], [AdminRole.ADMIN]
 * - statuses: [AdminStatus.ACTIVE], [AdminStatus.SUSPENDED]; the backend also accepts
 *   [AdminStatus.DELETED] as a list filter. There is no PENDING account status in the backend —
 *   provisioning happens through one-time invitations instead.
 *
 * Every member has a default failure so offline fakes and non-admin feature tests compile without
 * implementing the whole surface.
 */
interface AdminRepository {

    /** False when no backend base URL is configured; the screen then never calls the API. */
    val isEnabled: Boolean get() = true

    suspend fun currentRole(ownerId: String): Outcome<String> = unavailable()

    suspend fun listAccounts(
        ownerId: String,
        query: String? = null,
        role: String? = null,
        status: String? = null,
        limit: Int = 50,
        cursor: String? = null,
    ): Outcome<AdminAccountPage> = unavailable()

    suspend fun getAccount(ownerId: String, targetId: String): Outcome<ServerAdminAccount> =
        unavailable()

    suspend fun createInvitation(
        ownerId: String,
        email: String,
        role: String,
        expiresInHours: Int = 72,
    ): Outcome<AdminInvitation> = unavailable()

    suspend fun updateAccount(
        ownerId: String,
        targetId: String,
        patch: AdminAccountPatch,
    ): Outcome<ServerAdminAccount> = unavailable()

    /** Soft-delete (status SUSPENDED + session revocation) guarded by an expected [revision]. */
    suspend fun deactivateAccount(
        ownerId: String,
        targetId: String,
        revision: Int,
    ): Outcome<ServerAdminAccount> = unavailable()

    suspend fun listAudit(
        ownerId: String,
        targetOwnerId: String? = null,
        action: String? = null,
        limit: Int = 50,
        cursor: String? = null,
    ): Outcome<AdminAuditPage> = unavailable()

    private fun <T> unavailable(): Outcome<T> =
        Outcome.Failure(AppError.Configuration("admin_port_unimplemented"))
}

/** Roles accepted by `POST /v1/admin/invitations` and `PATCH /v1/admin/accounts/{ownerId}`. */
object AdminRole {
    const val USER = "USER"
    const val ADMIN = "ADMIN"

    val values: List<String> = listOf(USER, ADMIN)
}

/** Account statuses accepted by the backend (update allows ACTIVE/SUSPENDED; filters add DELETED). */
object AdminStatus {
    const val ACTIVE = "ACTIVE"
    const val SUSPENDED = "SUSPENDED"
    const val DELETED = "DELETED"

    /** Values `PATCH /v1/admin/accounts/{ownerId}` accepts for `status`. */
    val writable: List<String> = listOf(ACTIVE, SUSPENDED)

    /** Values `GET /v1/admin/accounts` accepts for `status`. */
    val filterable: List<String> = listOf(ACTIVE, SUSPENDED, DELETED)
}

/** Stable rule codes surfaced as `AppError.debugDetail` / `AppError.Validation.rule`. */
object AdminRules {
    const val ADMIN_REQUIRED = "admin_required"
    const val INVALID_CURSOR = "invalid_cursor"
    const val REVISION_CONFLICT = "revision_conflict"
    const val LAST_ADMIN = "last_admin"
    const val INVITATION_EXPIRED = "invitation_expired"
    const val INVITATION_USED = "invitation_used"
    const val INVITATION_INVALID = "invalid_invitation"
    const val SELF_ADMIN_CHANGE = "self_admin_change"
}

data class ServerAdminAccount(
    val ownerId: String,
    val email: String,
    val displayName: String?,
    val role: String,
    val status: String,
    val revision: Int,
    val createdAt: String? = null,
    val updatedAt: String? = null,
)

data class AdminAccountPage(
    val items: List<ServerAdminAccount> = emptyList(),
    val nextCursor: String? = null,
)

data class ServerAdminInvitation(
    val id: String,
    val email: String,
    val role: String,
    val expiresAt: String? = null,
    val createdAt: String? = null,
)

/**
 * The created invitation. [invitationToken] is the raw one-time token: the server stores only its
 * hash, so this value is shown once by the UI and never persisted or logged.
 */
data class AdminInvitation(
    val invitation: ServerAdminInvitation,
    val invitationToken: String,
)

/** Only the supplied fields change; [revision] is the compare-and-swap expected revision. */
data class AdminAccountPatch(
    val displayName: String? = null,
    val role: String? = null,
    val status: String? = null,
    val revision: Int,
)

data class AdminAuditEntry(
    val id: String,
    val actorOwnerId: String,
    val targetOwnerId: String,
    val action: String,
    val metadata: Map<String, String> = emptyMap(),
    val createdAt: String? = null,
)

data class AdminAuditPage(
    val items: List<AdminAuditEntry> = emptyList(),
    val nextCursor: String? = null,
)
