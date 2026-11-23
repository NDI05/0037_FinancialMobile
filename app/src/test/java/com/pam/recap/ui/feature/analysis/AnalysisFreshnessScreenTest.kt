package com.pam.recap.ui.feature.analysis

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import com.pam.recap.core.time.JakartaMonth
import com.pam.recap.domain.model.Analysis
import com.pam.recap.domain.model.AnalysisStatus
import com.pam.recap.domain.model.CoverageStatus
import com.pam.recap.domain.model.MetricKeys
import com.pam.recap.domain.model.MonthlySummary
import com.pam.recap.domain.model.Observation
import com.pam.recap.domain.model.SummaryFreshness
import com.pam.recap.domain.model.ValidationOutcome
import com.pam.recap.ui.theme.PamTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.time.Instant

/**
 * S2.5 render gate: a stale stored analysis must be visible as such (outdated chip + a notice that
 * names the frozen and canonical revisions) instead of rendering as an available report. The three
 * states stay distinguishable: current -> available chip, stale -> outdated chip + notice,
 * consent-withdrawn -> historical chip.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "id-rID-w411dp-h891dp-xxhdpi")
class AnalysisFreshnessScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

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

    private fun setContent(analysis: Analysis, summary: MonthlySummary?) {
        composeRule.setContent {
            PamTheme {
                AnalysisFreshnessHeader(analysis = analysis, summary = summary)
            }
        }
    }

    @Test
    fun `a stale analysis shows the outdated chip and the revision notice`() {
        setContent(analysis(revision = 3), summary(revision = 4))

        composeRule.onNodeWithText("Kedaluwarsa").assertIsDisplayed()
        composeRule.onNodeWithText(
            "Analisis ini dibuat dari revisi 3, sedangkan data bulan ini sudah di revisi 4. " +
                "Buat ulang analisis agar sesuai data terbaru.",
        ).assertIsDisplayed()
    }

    @Test
    fun `a current analysis shows the available chip and no stale notice`() {
        setContent(analysis(revision = 4), summary(revision = 4))

        composeRule.onNodeWithText("Tersedia").assertIsDisplayed()
        composeRule.onAllNodesWithText("Kedaluwarsa").assertCountEquals(0)
    }

    @Test
    fun `a consent-withdrawn analysis stays historical, not outdated`() {
        setContent(analysis(revision = 4, status = AnalysisStatus.HISTORICAL), summary(revision = 4))

        composeRule.onNodeWithText("Riwayat").assertIsDisplayed()
        composeRule.onAllNodesWithText("Kedaluwarsa").assertCountEquals(0)
    }

    @Test
    fun `a missing summary renders as unavailable instead of available`() {
        // B-3: no canonical revision means "tidak tersedia", never a reassuring current chip.
        setContent(analysis(revision = 3), summary = null)

        composeRule.onNodeWithText("Tidak tersedia").assertIsDisplayed()
        composeRule.onAllNodesWithText("Tersedia").assertCountEquals(0)
        composeRule.onAllNodesWithText("Kedaluwarsa").assertCountEquals(0)
    }

    @Test
    fun `a dependency-hash stale report explains the change without new revision numbers`() {
        // B-2: the server can report STALE while the revision is unchanged (prior month, category or
        // budget edits). The banner must not print two identical revision numbers.
        setContent(
            analysis(revision = 4, status = AnalysisStatus.OUTDATED),
            summary(revision = 4),
        )

        composeRule.onNodeWithText("Kedaluwarsa").assertIsDisplayed()
        composeRule.onNodeWithText(
            "Analisis ini dibuat dari data yang sejak itu sudah berubah (revisi 4), meskipun nomor " +
                "revisi bulan tetap. Buat ulang analisis agar sesuai data terbaru.",
        ).assertIsDisplayed()
    }
}
