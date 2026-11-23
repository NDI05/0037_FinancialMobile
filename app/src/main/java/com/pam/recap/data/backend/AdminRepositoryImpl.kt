package com.pam.recap.data.backend

import com.pam.recap.core.result.AppError
import com.pam.recap.core.result.Outcome
import com.pam.recap.domain.repository.AdminAccountPage
import com.pam.recap.domain.repository.AdminAccountPatch
import com.pam.recap.domain.repository.AdminAuditEntry
import com.pam.recap.domain.repository.AdminAuditPage
import com.pam.recap.domain.repository.AdminInvitation
import com.pam.recap.domain.repository.AdminRepository
import com.pam.recap.domain.repository.ServerAdminAccount
import com.pam.recap.domain.repository.ServerAdminInvitation
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Adapter for the Go admin surface (contract §6). It only maps wire DTOs to the owner-scoped
 * domain types; the machine error codes are already preserved by [PamApiClient.adminBackendError],
 * so the UI can react to `invalid_cursor`, `revision_conflict`, `last_admin` and
 * `invitation_expired` specifically.
 *
 * No response body, token or PII is ever logged here.
 */
@Singleton
class AdminRepositoryImpl @Inject constructor(
    private val api: PamApiClient,
    private val endpoints: BackendEndpoints,
) : AdminRepository {

    override val isEnabled: Boolean get() = endpoints.isEnabled

    override suspend fun currentRole(ownerId: String): Outcome<String> =
        when (val result = api.currentRole(ownerId)) {
            is Outcome.Success -> {
                val role = result.value.role.trim().uppercase()
                if (role.isEmpty()) {
                    Outcome.Failure(AppError.Parsing("admin_role_missing"))
                } else {
                    Outcome.Success(role)
                }
            }

            is Outcome.Failure -> result
        }

    override suspend fun listAccounts(
        ownerId: String,
        query: String?,
        role: String?,
        status: String?,
        limit: Int,
        cursor: String?,
    ): Outcome<AdminAccountPage> = when (
        val result = api.adminAccounts(ownerId, query, role, status, limit, cursor)
    ) {
        is Outcome.Success -> Outcome.Success(
            AdminAccountPage(result.value.items.map { it.toDomain() }, result.value.nextCursor),
        )

        is Outcome.Failure -> result
    }

    override suspend fun getAccount(
        ownerId: String,
        targetId: String,
    ): Outcome<ServerAdminAccount> = when (val result = api.adminAccount(ownerId, targetId)) {
        is Outcome.Success -> Outcome.Success(result.value.toDomain())
        is Outcome.Failure -> result
    }

    override suspend fun createInvitation(
        ownerId: String,
        email: String,
        role: String,
        expiresInHours: Int,
    ): Outcome<AdminInvitation> =
        when (val result = api.adminInvite(ownerId, email.trim(), role, expiresInHours)) {
            is Outcome.Success -> Outcome.Success(
                AdminInvitation(
                    invitation = ServerAdminInvitation(
                        id = result.value.invitation.id,
                        email = result.value.invitation.email,
                        role = result.value.invitation.role,
                        expiresAt = result.value.invitation.expiresAt,
                        createdAt = result.value.invitation.createdAt,
                    ),
                    invitationToken = result.value.invitationToken,
                ),
            )

            is Outcome.Failure -> result
        }

    override suspend fun updateAccount(
        ownerId: String,
        targetId: String,
        patch: AdminAccountPatch,
    ): Outcome<ServerAdminAccount> = when (
        val result = api.adminUpdateAccount(
            ownerId,
            targetId,
            RemoteAdminAccountPatch(
                displayName = patch.displayName,
                role = patch.role,
                status = patch.status,
                revision = patch.revision,
            ),
        )
    ) {
        is Outcome.Success -> Outcome.Success(result.value.toDomain())
        is Outcome.Failure -> result
    }

    override suspend fun deactivateAccount(
        ownerId: String,
        targetId: String,
        revision: Int,
    ): Outcome<ServerAdminAccount> =
        when (val result = api.adminDeactivateAccount(ownerId, targetId, revision)) {
            is Outcome.Success -> Outcome.Success(result.value.toDomain())
            is Outcome.Failure -> result
        }

    override suspend fun listAudit(
        ownerId: String,
        targetOwnerId: String?,
        action: String?,
        limit: Int,
        cursor: String?,
    ): Outcome<AdminAuditPage> = when (
        val result = api.adminAudit(ownerId, targetOwnerId, action, limit, cursor)
    ) {
        is Outcome.Success -> Outcome.Success(
            AdminAuditPage(result.value.items.map { it.toDomain() }, result.value.nextCursor),
        )

        is Outcome.Failure -> result
    }

    private fun RemoteAdminAccount.toDomain(): ServerAdminAccount = ServerAdminAccount(
        ownerId = ownerId,
        email = email,
        displayName = displayName,
        role = role.uppercase(),
        status = status.uppercase(),
        revision = revision,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

    private fun RemoteAdminAuditEntry.toDomain(): AdminAuditEntry = AdminAuditEntry(
        id = id,
        actorOwnerId = actorOwnerId,
        targetOwnerId = targetOwnerId,
        action = action,
        metadata = metadata,
        createdAt = createdAt,
    )
}
