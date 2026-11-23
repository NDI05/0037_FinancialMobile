package com.pam.recap.domain.repository

/**
 * Background scheduling port.
 *
 * The platform owns the cadence: Android decides when (and whether) the periodic check runs, so the
 * API deliberately promises "scheduled", never "immediate". Implementations must not start a
 * foreground service to dodge those limits.
 */
interface AutoSyncScheduler {
    /** Idempotent: scheduling twice keeps one periodic request for [ownerId]. */
    fun ensureScheduled(ownerId: String)

    /** Cancels every queued/running automatic check (logout, owner switch, disconnect, deletion). */
    fun cancelAll()
}
