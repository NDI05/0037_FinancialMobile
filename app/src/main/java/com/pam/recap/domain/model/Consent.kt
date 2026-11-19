package com.pam.recap.domain.model

import kotlin.time.Instant

/** FR-03: consent is recorded per purpose, so Gmail and AI are decided independently. */
enum class ConsentPurpose {
    GMAIL_READ,
    AI_PROCESSING,
}

/** FR-03/FR-04: an explicit decision, including withdrawal, with its own timestamp. */
enum class ConsentDecision {
    GRANTED,
    DECLINED,
    WITHDRAWN,
}

/**
 * Append-only consent ledger entry (FR-03, FR-49). The ledger is never mutated: withdrawing
 * appends a `WITHDRAWN` entry so the history stays auditable.
 */
data class Consent(
    val id: String,
    val ownerId: String,
    val purpose: ConsentPurpose,
    val noticeVersion: String,
    val decision: ConsentDecision,
    val decidedAt: Instant,
) {
    val isGranted: Boolean get() = decision == ConsentDecision.GRANTED

    val isWithdrawn: Boolean get() = decision == ConsentDecision.WITHDRAWN
}
