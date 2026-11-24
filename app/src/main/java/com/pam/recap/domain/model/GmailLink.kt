package com.pam.recap.domain.model

import com.pam.recap.core.time.JakartaMonth
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

/**
 * Backfill/delta progress reported by the server (contract §9.2). All counters are optional
 * projections: a `null` [phase] with zero totals just means the worker has not reported yet, and
 * the screen must say "progress not reported" instead of inventing a percentage.
 */
data class GmailSyncProgress(
    val phase: String? = null,
    val month: JakartaMonth? = null,
    val monthsDone: Int = 0,
    val monthsTotal: Int = 0,
    val pagesDone: Int = 0,
) {
    val hasMonths: Boolean get() = monthsTotal > 0
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
    /** Contract §9.2: the grant was revoked or the token cannot be refreshed. */
    val needsReauth: Boolean = false,
    val lastAttemptAt: Instant? = null,
    val lastSuccessAt: Instant? = null,
    val nextSyncAt: Instant? = null,
    /** Set while a manually requested job is waiting for the worker; null when none is pending. */
    val syncRequestedAt: Instant? = null,
    val progress: GmailSyncProgress? = null,
) {

    companion object {
        /** Contract §3: never linked before — every time field is null. */
        val NotConnected: GmailLinkStatus = GmailLinkStatus(connected = false)
    }
}

/**
 * One `POST /v1/gmail/sync` acknowledgement (contract §9.3). The endpoint only enqueues; it never
 * waits for the job, so the sheet keeps showing the *status* projection until the worker reports.
 */
data class GmailSyncRequest(
    val queued: Boolean,
    val coalesced: Boolean,
    val requestedAt: Instant? = null,
    val nextAllowedAt: Instant? = null,
)

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
