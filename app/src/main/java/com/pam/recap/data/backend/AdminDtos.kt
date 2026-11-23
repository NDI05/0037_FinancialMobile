package com.pam.recap.data.backend

import kotlinx.serialization.Serializable

/**
 * Wire DTOs for the S4.2 admin surface (admin routes and `GET /v1/me`).
 *
 * The backend account view deliberately contains identity/role metadata only (ownerId, email,
 * displayName, role, status, revision, timestamps); it never exposes another owner's financial
 * data. The optional fields mirror the backend's nullable/omitted JSON members.
 */
@Serializable
data class RemoteAdminAccount(
    val ownerId: String,
    val userId: String? = null,
    val email: String,
    val displayName: String? = null,
    val role: String,
    val status: String,
    val revision: Int = 0,
    val createdAt: String? = null,
    val updatedAt: String? = null,
)

@Serializable
data class RemoteAdminAccountPage(
    val items: List<RemoteAdminAccount> = emptyList(),
    val nextCursor: String? = null,
)

/** The one-time invitation response: [invitationToken] is returned by the server exactly once. */
@Serializable
data class RemoteAdminInvitation(
    val id: String,
    val email: String,
    val role: String,
    val expiresAt: String? = null,
    val createdAt: String? = null,
)

@Serializable
data class RemoteAdminInvitationResult(
    val invitation: RemoteAdminInvitation,
    val invitationToken: String,
)

@Serializable
data class RemoteAdminInvitationCreate(
    val email: String,
    val role: String,
    val expiresInHours: Int = 72,
)

@Serializable
data class RemoteAdminAccountPatch(
    val displayName: String? = null,
    val role: String? = null,
    val status: String? = null,
    val revision: Int,
)

@Serializable
data class RemoteAdminAuditEntry(
    val id: String,
    val actorOwnerId: String = "",
    val targetOwnerId: String = "",
    val action: String,
    val metadata: Map<String, String> = emptyMap(),
    val createdAt: String? = null,
)

@Serializable
data class RemoteAdminAuditPage(
    val items: List<RemoteAdminAuditEntry> = emptyList(),
    val nextCursor: String? = null,
)

/** `GET /v1/me`: the signed-in owner's role is the admin gate, so nothing else is required here. */
@Serializable
data class RemoteAuthMe(
    val role: String = "",
    val displayName: String? = null,
)
