package com.pam.recap.domain.repository

import com.pam.recap.core.result.Outcome
import com.pam.recap.domain.model.ConsentDecision
import com.pam.recap.domain.model.ConsentPurpose

/**
 * Server-side copies of the local state the analysis gate depends on (contract §4).
 *
 * All of these are no-ops when no backend is configured, and none of them may fail a local
 * decision: the device remains the source of truth for the UI (offline-first, NFR-03).
 */
interface BackendConsentSync {
    /**
     * Fire-and-forget: records the decision on the server with a bounded retry. Never throws and
     * never delays the local ledger write.
     */
    suspend fun record(
        ownerId: String,
        purpose: ConsentPurpose,
        decision: ConsentDecision,
        noticeVersion: String,
    )
}

/** FR-33: pushes the aggregate + revision so the server freezes the same input it will analyse. */
interface BackendSummarySync {
    suspend fun push(request: AnalysisRequest): Outcome<Unit>
}

/** FR-47/FR-48: creates the server-side deletion request and returns the acknowledged SLA. */
interface BackendDeletionSync {
    /** @return the server's SLA in days, or a failure that the caller ignores. */
    suspend fun request(ownerId: String): Outcome<Int>
}
