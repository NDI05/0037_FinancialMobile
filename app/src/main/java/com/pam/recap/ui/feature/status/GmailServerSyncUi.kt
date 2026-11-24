package com.pam.recap.ui.feature.status

import com.pam.recap.core.result.AppError
import com.pam.recap.domain.model.GmailSyncProgress
import com.pam.recap.domain.model.GmailSyncState
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** Outcome of the last explicit "Sinkronkan sekarang" tap; rendered once, then superseded. */
enum class GmailSyncNotice {
    NONE,

    /** `202 {queued:true, coalesced:false}` — a durable server job now owns the work. */
    QUEUED,

    /** `202 {queued:true, coalesced:true}` — the tap joined a job that was already active. */
    COALESCED,

    /** `429 sync_cooldown` — the sheet shows the remaining seconds from `retryAfterSeconds`. */
    COOLDOWN,

    /** `409 gmail_not_connected` — the section hides itself on the next status read. */
    NOT_CONNECTED,

    /** `503 gmail_disabled` — the server feature is off, not a transient outage. */
    DISABLED,

    /** Any other failure; the copy comes from [error] through the shared error mapping. */
    FAILED,
}

/**
 * Server-side Gmail sync projection for the "Status pembaruan" sheet (contract §9).
 *
 * [available] is false while Gmail is not linked (or while the status read has not answered yet),
 * which keeps the legacy device-sync section in place. The view model owns this state; the sheet
 * only renders it and reports taps, so opening or closing the sheet never starts or cancels a job.
 */
data class GmailServerSyncUi(
    val available: Boolean = false,
    val loading: Boolean = false,
    /** One `POST /v1/gmail/sync` is in flight; disables the button until the server answers. */
    val busy: Boolean = false,
    /** Contract §9.2 `needs_reauth`: only the "Tautkan ulang Gmail" CTA can fix it. */
    val needsReauth: Boolean = false,
    val syncState: GmailSyncState = GmailSyncState.UNKNOWN,
    /**
     * Clock reading of the last `POST /v1/gmail/sync` ack. Only a short-lived hint: the sheet must
     * derive its state from [syncState]/[progress], never from the server's `sync_requested_at`
     * marker (older backends never clear it, which would pin "Menghubungkan" forever).
     */
    val requestHintAt: Instant? = null,
    val progress: GmailSyncProgress? = null,
    val lastAttemptAt: Instant? = null,
    val lastSuccessAt: Instant? = null,
    val nextSyncAt: Instant? = null,
    /** End of the server cooldown window; null when the server sent no `retryAfterSeconds`. */
    val cooldownUntil: Instant? = null,
    val notice: GmailSyncNotice = GmailSyncNotice.NONE,
    val error: AppError? = null,
    /** Clock reading of the latest status poll; drives the cooldown countdown. */
    val observedAt: Instant? = null,
) {

    /** A worker is actively reading history or a delta; a second enqueue would be coalesced. */
    val running: Boolean
        get() = syncState == GmailSyncState.BACKFILL || syncState == GmailSyncState.DELTA

    /**
     * "Menghubungkan" while a just-enqueued request has not been superseded by a status read yet.
     * The view model clears the hint on the first status response, and the elapsed-time cap keeps a
     * stale ack from lingering if that response never arrives.
     */
    fun connecting(now: Instant): Boolean {
        if (running || needsReauth || syncState == GmailSyncState.ERROR) return false
        val hint = requestHintAt ?: return false
        return now - hint <= CONNECTING_HINT
    }

    /** Remaining cooldown in whole seconds, or null when the server did not specify one. */
    fun cooldownSeconds(now: Instant): Int? {
        val until = cooldownUntil ?: return null
        val remaining = until - now
        return if (remaining <= 0.seconds) 0 else remaining.inWholeSeconds.toInt()
    }

    /** True when the tap must be refused locally because the server told us to wait. */
    fun coolingDown(now: Instant): Boolean = (cooldownSeconds(now) ?: 0) > 0

    companion object {
        /** Upper bound for the "Menghubungkan" hint, even if no status read ever arrives. */
        val CONNECTING_HINT: kotlin.time.Duration = 20.seconds

        /** Gmail not linked (or backend disabled): the sheet keeps the legacy device-sync view. */
        val Hidden: GmailServerSyncUi = GmailServerSyncUi()
    }
}
