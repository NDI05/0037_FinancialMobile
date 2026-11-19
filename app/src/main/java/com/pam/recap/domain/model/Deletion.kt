package com.pam.recap.domain.model

import kotlin.time.Instant

/** FR-47/FR-48 deletion lifecycle; sync is blocked while a request is in flight. */
enum class DeletionState {
    REQUESTED,
    IN_PROGRESS,
    COMPLETED,
    FAILED,
}

/**
 * FR-47: a deletion request tied to a recent re-authentication. [completedAt] is set only after
 * every local store has been cleared; the UI reads the state, never a partial success.
 */
data class DeletionRequest(
    val id: String,
    val ownerId: String,
    val state: DeletionState,
    val requestedAt: Instant,
    val reauthenticatedAt: Instant?,
    val completedAt: Instant?,
    /** Technical failure code only. */
    val failureCode: String? = null,
) {
    val isInProgress: Boolean
        get() = state == DeletionState.REQUESTED || state == DeletionState.IN_PROGRESS

    val blocksSync: Boolean get() = isInProgress
}

/**
 * Outcome of a deletion request. [serverSlaDays] is the SLA the backend acknowledged (contract
 * §4.3) or null when the app runs without a backend; it is transport metadata, not stored state.
 */
data class DeletionResult(
    val request: DeletionRequest,
    val serverSlaDays: Int? = null,
)

/** FR-01: a signed-in session. Financial data is owner-scoped by [ownerId] (FR-02). */
data class Session(
    val ownerId: String,
    val displayName: String?,
    val mailboxLabel: String?,
    val signedInAt: Instant,
    /** OI-01: true when the app runs against bundled fixtures instead of a real mailbox. */
    val isDemo: Boolean,
)

/**
 * Result of the identity step (FR-01). [isDemo] mirrors OI-01: the bundled fixture mailbox is used
 * when no OAuth client id is configured, and the demo session seeds the sample month.
 */
data class AccountIdentity(
    val accountId: String,
    val displayName: String?,
    val mailboxLabel: String?,
    val isDemo: Boolean,
)
