package com.pam.recap.domain.repository

import com.pam.recap.core.result.AppError
import com.pam.recap.core.result.Outcome
import com.pam.recap.core.result.map
import com.pam.recap.domain.model.ConsentDecision
import com.pam.recap.domain.model.ConsentPurpose

/**
 * Server-side copies of the local state the analysis gate depends on (contract §4).
 *
 * All of these are no-ops when no backend is configured, and none of them may fail a local
 * decision: the device remains the source of truth for the UI (offline-first, NFR-03).
 */
interface BackendConsentSync {
    /** True when a v0.4 server is configured. */
    val isEnabled: Boolean get() = false

    /**
     * Fire-and-forget legacy path. The v0.4 gate uses [recordConfirmed], which reports whether the
     * server ledger acknowledged the decision.
     */
    suspend fun record(
        ownerId: String,
        purpose: ConsentPurpose,
        decision: ConsentDecision,
        noticeVersion: String,
    )

    suspend fun recordConfirmed(
        ownerId: String,
        purpose: ConsentPurpose,
        decision: ConsentDecision,
        noticeVersion: String,
    ): Outcome<Unit> = runCatching {
        record(ownerId, purpose, decision, noticeVersion)
    }.fold({ Outcome.Success(Unit) }, { Outcome.Failure(AppError.Network("consent_push_failed")) })
}

/** FR-33: pushes the aggregate + revision so the server freezes the same input it will analyse. */
interface BackendSummarySync {
    suspend fun push(request: AnalysisRequest): Outcome<Unit>
}

/** FR-47/FR-48: creates the server-side deletion request and returns the acknowledged SLA. */
interface BackendDeletionSync {
    /** True when a v0.4 server is configured; local-only deletion is a different action. */
    val isEnabled: Boolean get() = false

    /** Legacy/no-reauth fallback used by older callers and offline doubles. */
    suspend fun request(ownerId: String): Outcome<Int>

    /**
     * Server-side deletion requires the account password and literal confirmation. Implementations
     * that do not support it fall back to [request] so test doubles stay source-compatible.
     */
    suspend fun request(
        ownerId: String,
        password: String,
        confirmation: String = "DELETE_WORKSPACE",
    ): Outcome<BackendDeletionAck> = request(ownerId).map {
        BackendDeletionAck("", "UNKNOWN", it, null)
    }
}

data class BackendDeletionAck(
    val id: String,
    val state: String,
    val slaDays: Int,
    val receiptToken: String?,
)
