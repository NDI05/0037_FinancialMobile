package com.pam.recap.data.backend

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNames

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
    // Contract §9.2 additions. Defaults keep older servers readable.
    @SerialName("needs_reauth") val needsReauth: Boolean = false,
    @SerialName("last_attempt_at") val lastAttemptAt: String? = null,
    @SerialName("last_success_at") val lastSuccessAt: String? = null,
    @SerialName("next_sync_at") val nextSyncAt: String? = null,
    @SerialName("sync_requested_at") val syncRequestedAt: String? = null,
    val progress: RemoteGmailProgress? = null,
)

@Serializable
data class RemoteGmailProgress(
    val phase: String? = null,
    val month: String? = null,
    @SerialName("months_done") val monthsDone: Int = 0,
    @SerialName("months_total") val monthsTotal: Int = 0,
    @SerialName("pages_done") val pagesDone: Int = 0,
)

/** `POST /v1/gmail/sync` 202 body (contract §9.3); both queued and coalesced are 2xx. */
@Serializable
data class RemoteGmailSync(
    val queued: Boolean = false,
    val coalesced: Boolean = false,
    @SerialName("requested_at") val requestedAt: String? = null,
    @SerialName("next_allowed_at") val nextAllowedAt: String? = null,
)

/** Error envelope that carries the cooldown delay; never parsed for anything but machine fields. */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class RemoteGmailSyncError(
    val code: String? = null,
    val retryable: Boolean = false,
    // Contract §9.3 is camelCase; the alternative name keeps bodies from an older server readable.
    @JsonNames("retry_after_seconds") val retryAfterSeconds: Int? = null,
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
