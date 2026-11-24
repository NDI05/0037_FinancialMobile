package com.pam.recap.domain.model

import kotlin.time.Instant

/**
 * Gmail link over OAuth on the server (contract §3 in
 * `0037-Backend-Financial/docs/GMAIL_API_CONTRACT.md`).
 *
 * The app never stores the Google token; it only reads this projected status. [syncState] mirrors
 * the wire `sync_state` and stops at "error" so no internal detail leaks to the screen.
 */
enum class GmailSyncState {
    IDLE,
    BACKFILL,
    DELTA,
    ERROR,
    UNKNOWN;

    companion object {
        /** Unknown wire values degrade to [UNKNOWN] instead of failing the whole status read. */
        fun fromWire(raw: String?): GmailSyncState = when (raw?.trim()?.lowercase()) {
            "idle" -> IDLE
            "backfill" -> BACKFILL
            "delta" -> DELTA
            "error" -> ERROR
            else -> UNKNOWN
        }
    }
}

/** Owner-scoped Gmail link as reported by `GET /v1/gmail/status`. */
data class GmailLinkStatus(
    val connected: Boolean,
    val accountEmailMasked: String? = null,
    val scope: String? = null,
    val syncState: GmailSyncState = GmailSyncState.UNKNOWN,
    val connectedAt: Instant? = null,
    val lastSyncAt: Instant? = null,
    val watchExpiresAt: Instant? = null,
    val lastErrorCode: String? = null,
) {

    companion object {
        /** Contract §3: never linked before — every time field is null. */
        val NotConnected: GmailLinkStatus = GmailLinkStatus(connected = false)
    }
}

/** One `POST /v1/gmail/connect/start` response: the URL the browser/Custom Tab must open. */
data class GmailAuthorization(
    val authorizationUrl: String,
    val expiresAt: Instant? = null,
)

/** Result the OAuth callback deep link (`pam://gmail/connected`) carries back into the app. */
data class GmailLinkResult(
    val success: Boolean,
    val reason: String? = null,
) {
    companion object {
        fun fromWire(status: String?, reason: String?): GmailLinkResult =
            GmailLinkResult(success = status?.trim()?.lowercase() == "success", reason = reason)
    }
}
