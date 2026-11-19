package com.pam.recap.domain.model

import com.pam.recap.core.time.JakartaMonth
import kotlin.time.Instant

/**
 * FR-19 / §7 import state machine:
 * `CREATED → RUNNING → (COMPLETE | PARTIAL | FAILED | PERMISSION_REQUIRED)`.
 * `EMPTY` (FR-20) is a distinct terminal state: "no matching emails", never "no spending".
 */
enum class ImportState {
    CREATED,
    RUNNING,
    COMPLETE,
    PARTIAL,
    FAILED,
    PERMISSION_REQUIRED,
    EMPTY,
    ;

    val isTerminal: Boolean
        get() = this == COMPLETE || this == PARTIAL || this == FAILED ||
            this == PERMISSION_REQUIRED || this == EMPTY

    val isSuccessful: Boolean get() = this == COMPLETE || this == EMPTY
}

/**
 * FR-15: the only thing persisted about a rejected candidate is this redacted reason. No
 * subject, body, sender, message id or merchant label is stored for a skipped row.
 */
enum class RedactionReason {
    MISSING_AMOUNT,
    MALFORMED_AMOUNT,
    MISSING_EVENT_TIME,
    MALFORMED_EVENT_TIME,
    MISSING_DIRECTION,
    MISSING_STATUS,
    MISSING_RECIPIENT,
    FAILED_PAYMENT,
    UNKNOWN_TEMPLATE,
    ATTACHMENT_ONLY,
    NOT_LIVIN_SOURCE,
    NOT_IN_MONTH,
    DUPLICATE,
    FINGERPRINT_COLLISION,
    CURRENCY_UNSUPPORTED,
    MALFORMED_FIELD,
}

/** FR-19 job record: pages processed + counts + terminal state. */
data class ImportJob(
    val id: String,
    val ownerId: String,
    val month: JakartaMonth,
    val state: ImportState,
    val pagesProcessed: Int,
    val pagesExpected: Int?,
    val matchedCount: Int,
    val importedCount: Int,
    val duplicateCount: Int,
    val skippedCount: Int,
    val unresolvedCount: Int,
    val templateVersion: String,
    val startedAt: Instant,
    val finishedAt: Instant?,
    /** Technical error code only (NFR-18); user guidance is chosen by the UI from [state]. */
    val errorCode: String? = null,
) {

    val isEmptyResult: Boolean get() = state == ImportState.EMPTY

    val needsReconnect: Boolean get() = state == ImportState.PERMISSION_REQUIRED

    val isRetryable: Boolean get() = state == ImportState.PARTIAL || state == ImportState.FAILED

    fun withState(
        state: ImportState,
        pagesProcessed: Int = this.pagesProcessed,
        matchedCount: Int = this.matchedCount,
        importedCount: Int = this.importedCount,
        duplicateCount: Int = this.duplicateCount,
        skippedCount: Int = this.skippedCount,
        unresolvedCount: Int = this.unresolvedCount,
        finishedAt: Instant? = this.finishedAt,
        errorCode: String? = this.errorCode,
    ): ImportJob = copy(
        state = state,
        pagesProcessed = pagesProcessed,
        matchedCount = matchedCount,
        importedCount = importedCount,
        duplicateCount = duplicateCount,
        skippedCount = skippedCount,
        unresolvedCount = unresolvedCount,
        finishedAt = finishedAt,
        errorCode = errorCode,
    )
}

/**
 * A rejected candidate. Deliberately carries only the redacted [reason] plus positional
 * information that cannot reconstruct the message (FR-15, hard rule 7).
 */
data class SkippedCandidate(
    val id: String,
    val jobId: String,
    val ownerId: String,
    val reason: RedactionReason,
    val pageOrdinal: Int,
    val candidateOrdinal: Int,
    val createdAt: Instant,
)
