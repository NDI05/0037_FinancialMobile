package com.pam.recap.domain.model

import kotlin.time.Instant

/**
 * Automatic categorization is a best-effort background pipeline: candidates are queued after the
 * financial commit, a server-side drain spends the shared classifier budget in small batches, and
 * the UI shows what is still waiting instead of pretending everything is categorized.
 */
enum class CategoryQueuePhase {
    /** Nothing queued and nothing recently drained. */
    IDLE,

    /** Candidates are waiting for the next drain window. */
    WAITING,

    /** A drain is in flight. */
    RUNNING,

    /** The last drain was deferred (network, budget or an early poll). */
    PAUSED,

    /** The last attempt needs a user action (consent, auth or a repeated failure). */
    NEEDS_ATTENTION,
}

data class CategoryQueueSnapshot(
    val phase: CategoryQueuePhase = CategoryQueuePhase.IDLE,
    val pending: Int = 0,
    val nextDueAt: Instant? = null,
    val lastCheckedAt: Instant? = null,
    val lastDrained: Int = 0,
    val errorCode: String? = null,
) {
    /** True only while a real drain runs, so a scheduled wait never shows a spinner. */
    val isRunning: Boolean get() = phase == CategoryQueuePhase.RUNNING

    val hasBacklog: Boolean get() = pending > 0
}
