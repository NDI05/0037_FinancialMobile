package com.pam.recap.domain.model

import com.pam.recap.core.time.JakartaMonth
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * Mailbox-level progress marker for incremental sync.
 *
 * Privacy boundary (SRS: no source message ids anywhere but memory): [uidValidity] identifies the
 * *folder generation* and [highestUid] is a single high-water integer. Neither is a message id, and
 * neither is stored on a transaction, sent to the backend or written to a log. The id of an
 * individual message still never leaves the parser.
 */
data class MailboxWatermark(val uidValidity: Long, val highestUid: Long) {
    fun isAtLeast(other: MailboxWatermark?): Boolean =
        other == null || (uidValidity == other.uidValidity && highestUid >= other.highestUid)
}

/**
 * Durable per-owner sync progress and honest status. One row per owner; the mailbox key is an
 * opaque, non-reversible label so a cursor can be dropped without keeping the Gmail address in
 * plaintext.
 */
data class SyncCursor(
    val ownerId: String,
    val mailboxKey: String? = null,
    val watermark: MailboxWatermark? = null,
    val lastAttemptAt: Instant? = null,
    val lastSuccessAt: Instant? = null,
    val lastErrorCode: String? = null,
    val consecutiveFailures: Int = 0,
    val uploadPending: Boolean = false,
) {
    val hasProgress: Boolean get() = watermark != null
}

/** What a status surface is allowed to say about the transaction pipeline. */
enum class SyncPhase {
    /** No attempt yet in this install/session. */
    IDLE,
    /** A run is in progress right now (spinner is legitimate here). */
    SYNCING,
    /** Last run succeeded; [SyncSnapshot.lastSuccessAt] is the only "fresh until" claim. */
    SUCCEEDED,
    /** Waiting for the next schedule. No spinner. */
    SCHEDULED,
    /** Offline or actively deferred by the OS/constraints. */
    PAUSED,
    /** Needs the user: revoked grant, credentials, or a repeated failure. */
    NEEDS_ATTENTION,
}

/**
 * The single source of truth for the top bar and the "Status pembaruan" sheet. It is written by the
 * sync layer only; opening the panel reads it and never starts work.
 */
data class SyncSnapshot(
    val phase: SyncPhase = SyncPhase.IDLE,
    val isRunning: Boolean = false,
    val lastAttemptAt: Instant? = null,
    val lastSuccessAt: Instant? = null,
    val lastErrorCode: String? = null,
    val newTransactionsLastRun: Int = 0,
    val pendingUpload: Boolean = false,
    /** Set when a run finished after the user signed out/switched: it must not be shown as success. */
    val ownerId: String? = null,
)

/** The window one automatic check actually reads: a delta, never the whole month. */
data class DeltaWindow(
    val fromInclusive: Instant,
    val toExclusive: Instant,
    /** Highest uid already committed; null means "no trustworthy cursor, scan the window". */
    val since: MailboxWatermark? = null,
    /** Watermark captured before this run: the delta never reads past it. */
    val upTo: MailboxWatermark? = null,
    /**
     * Extra months the run may route into. A late email whose transaction date falls in the
     * previous month must still be stored there instead of being skipped as "not in month".
     */
    val extraMonths: List<JakartaMonth> = emptyList(),
)

/** Tuning knobs for the automatic check; every value is a plan/CANDIDATE parameter. */
data class AutoSyncConfig(
    val interval: Duration = 30.minutes,
    val foregroundCooldown: Duration = 5.minutes,
    /**
     * Re-scan window used only when a watermark cannot be trusted (first run after upgrade, or
     * UIDVALIDITY changed). A healthy delta run re-reads nothing inside this window.
     */
    val recoveryOverlap: Duration = 360.minutes,
)
