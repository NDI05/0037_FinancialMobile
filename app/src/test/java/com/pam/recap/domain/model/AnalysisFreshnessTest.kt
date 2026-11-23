package com.pam.recap.domain.model

import com.pam.recap.core.time.JakartaMonth
import org.junit.Test
import org.junit.Assert.assertEquals
import kotlin.time.Instant

/**
 * S2.5 unit gate for the analysis freshness rule: the server keeps storing an analysis as
 * `AVAILABLE` after the month rows move on and reports the stale state through the canonical
 * revision (`currentRevision > inputRevision`). The UI must derive the same verdict from the
 * summary revision, not from status alone.
 */
class AnalysisFreshnessTest {

    private val month = JakartaMonth.of(Instant.parse("2026-11-19T02:15:20Z"))
    private val now = Instant.parse("2026-11-19T05:00:00Z")

    private fun analysis(
        revision: Int,
        status: AnalysisStatus = AnalysisStatus.AVAILABLE,
    ) = Analysis(
        id = "analysis-1",
        ownerId = "u_owner",
        monthId = month.id(),
        summaryId = "summary-1",
        inputRevision = revision,
        consentId = "consent-1",
        providerId = "fake",
        modelId = "fake-v1",
        promptVersion = "analysis-v1",
        observations = listOf(Observation("Arus kas bersih", listOf(MetricKeys.NET_CASH_FLOW))),
        suggestions = emptyList(),
        limitations = emptyList(),
        status = status,
        validation = ValidationOutcome.valid(0, now),
        usage = null,
        createdAt = now,
    )

    private fun summary(
        revision: Int,
        freshness: SummaryFreshness = SummaryFreshness.CURRENT,
    ): MonthlySummary = MonthlySummary.empty(
        ownerId = "u_owner",
        month = month,
        mailboxLabel = "andhika@example.test",
        coverage = CoverageStatus.COMPLETE,
        inputRevision = revision,
        generatedAt = now,
    ).copy(freshness = freshness)

    @Test
    fun `the canonical revision moving ahead marks the stored analysis stale`() {
        val stored = analysis(revision = 3)
        val moved = summary(revision = 4)

        assertEquals(SummaryFreshness.STALE, stored.freshnessAgainst(moved))
        assertEquals(AnalysisStatus.OUTDATED, stored.displayStatus(moved))
    }

    @Test
    fun `an analysis frozen at the current revision stays current`() {
        val stored = analysis(revision = 4)
        val current = summary(revision = 4)

        assertEquals(SummaryFreshness.CURRENT, stored.freshnessAgainst(current))
        assertEquals(AnalysisStatus.AVAILABLE, stored.displayStatus(current))
    }

    @Test
    fun `a revision behind the analysis never turns the report stale`() {
        // A restored/historical snapshot must not make the frozen analysis look outdated.
        val stored = analysis(revision = 5)
        val older = summary(revision = 4)

        assertEquals(SummaryFreshness.CURRENT, stored.freshnessAgainst(older))
    }

    @Test
    fun `the server stale summary propagates even when revisions match`() {
        val stored = analysis(revision = 4)
        val stalePayload = summary(revision = 4, freshness = SummaryFreshness.STALE)

        assertEquals(SummaryFreshness.STALE, stored.freshnessAgainst(stalePayload))
    }

    @Test
    fun `a locally invalidated analysis stays outdated`() {
        val stored = analysis(revision = 4, status = AnalysisStatus.OUTDATED)

        assertEquals(SummaryFreshness.STALE, stored.freshnessAgainst(summary(revision = 4)))
        assertEquals(AnalysisStatus.OUTDATED, stored.displayStatus(summary(revision = 4)))
    }

    @Test
    fun `consent withdrawal keeps the historical state`() {
        val withdrawn = analysis(revision = 4, status = AnalysisStatus.HISTORICAL)

        assertEquals(SummaryFreshness.HISTORICAL, withdrawn.freshnessAgainst(summary(revision = 4)))
        assertEquals(AnalysisStatus.HISTORICAL, withdrawn.displayStatus(summary(revision = 4)))
    }

    @Test
    fun `a provisional report behind the revision is stale too`() {
        val provisional = analysis(revision = 3, status = AnalysisStatus.PROVISIONAL)

        assertEquals(SummaryFreshness.STALE, provisional.freshnessAgainst(summary(revision = 4)))
    }

    @Test
    fun `a missing summary is unknown instead of current`() {
        // B-3: without the canonical revision the screen must not claim the report is current.
        val stored = analysis(revision = 3)

        assertEquals(SummaryFreshness.UNKNOWN, stored.freshnessAgainst(null))
        assertEquals(AnalysisStatus.UNAVAILABLE, stored.displayStatus(null))
    }

    @Test
    fun `a dependency-hash stale server report is stale even without a revision bump`() {
        // B-2: the backend may flip freshness to STALE when only the dependency hash moved (prior
        // month, category or budget edits). The mapped status carries that signal.
        val stored = analysis(revision = 4, status = AnalysisStatus.OUTDATED)
        val sameRevision = summary(revision = 4)

        assertEquals(SummaryFreshness.STALE, stored.freshnessAgainst(sameRevision))
        assertEquals(AnalysisStatus.OUTDATED, stored.displayStatus(sameRevision))
    }

    @Test
    fun `the summary current revision drives the stale verdict`() {
        val stored = analysis(revision = 4)
        val bumped = summary(revision = 4).copy(currentRevision = 5)

        assertEquals(SummaryFreshness.STALE, stored.freshnessAgainst(bumped))
    }

    @Test
    fun `the published-status guard is unchanged for availability`() {
        val rejected = analysis(revision = 4, status = AnalysisStatus.REJECTED)
        val moved = summary(revision = 5)

        // A rejected analysis never renders as available, but it is not a stale published report.
        assertEquals(SummaryFreshness.CURRENT, rejected.freshnessAgainst(moved))
        assertEquals(AnalysisStatus.REJECTED, rejected.displayStatus(moved))
    }
}
