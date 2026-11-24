package com.pam.recap.data.backend

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire shapes for the Gmail OAuth endpoints (contract §1–§4 in
 * `0037-Backend-Financial/docs/GMAIL_API_CONTRACT.md`).
 *
 * Everything is a projection: no token, no full email address and no OAuth `code`/`state` is part
 * of these models, so they can never be logged or persisted by accident.
 */
@Serializable
data class RemoteGmailStatus(
    val connected: Boolean = false,
    @SerialName("account_email_masked") val accountEmailMasked: String? = null,
    val scope: String? = null,
    @SerialName("connected_at") val connectedAt: String? = null,
    @SerialName("last_sync_at") val lastSyncAt: String? = null,
    @SerialName("watch_expires_at") val watchExpiresAt: String? = null,
    @SerialName("sync_state") val syncState: String? = null,
    @SerialName("last_error_code") val lastErrorCode: String? = null,
)

@Serializable
data class RemoteGmailConnectStart(
    @SerialName("authorization_url") val authorizationUrl: String,
    @SerialName("expires_at") val expiresAt: String? = null,
)

/** Optional `{"reconnect": true}` body; the server replaces the previous link when set. */
@Serializable
data class RemoteGmailConnectRequest(val reconnect: Boolean = false)

@Serializable
data class RemoteGmailDisconnect(val disconnected: Boolean = false)
