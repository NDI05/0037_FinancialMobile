package com.pam.recap.domain.repository

import com.pam.recap.core.result.Outcome
import com.pam.recap.core.time.JakartaMonth
import com.pam.recap.domain.model.AnalysisStatus
import com.pam.recap.domain.model.Observation
import com.pam.recap.domain.model.Suggestion
import com.pam.recap.domain.model.SummaryFreshness
import kotlin.time.Instant

/**
 * S2.5: one stored analysis as the backend reports it. [freshness] and [currentRevision] are the
 * server's canonical signals — the device must not re-derive staleness from the local Room copy or
 * from a revision comparison alone (the server also folds the dependency hash into this value).
 */
data class ServerAnalysis(
    val monthId: String,
    val inputRevision: Int,
    val currentRevision: Int,
    val freshness: SummaryFreshness,
    val status: AnalysisStatus,
    val promptVersion: String,
    val observations: List<Observation>,
    val suggestions: List<Suggestion>,
    val limitations: List<String>,
    val createdAt: Instant,
)

/**
 * S2.5 remote analysis port. Used by the analysis screen when a backend is configured: the month
 * summary and the stored analysis come from the server, and a regeneration posts the canonical
 * aggregate payload back to `POST /v1/analyses`.
 */
interface ServerAnalysisRepository {
    val isEnabled: Boolean

    /** Stored analysis for the month; `Success(null)` means the server has none yet. */
    suspend fun latest(ownerId: String, month: JakartaMonth): Outcome<ServerAnalysis?>

    /** Builds the aggregate payload from server data and asks the backend to analyze it. */
    suspend fun generate(ownerId: String, month: JakartaMonth): Outcome<Unit>
}
