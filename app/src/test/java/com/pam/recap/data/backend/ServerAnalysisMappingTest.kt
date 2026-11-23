package com.pam.recap.data.backend

import com.pam.recap.core.time.JakartaMonth
import com.pam.recap.domain.model.AnalysisStatus
import com.pam.recap.domain.model.SummaryFreshness
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.time.Instant

/** S2.5: the server's freshness/currentRevision is the primary stale signal for the UI. */
class ServerAnalysisMappingTest {

    private fun remote(
        freshness: String,
        status: String = "AVAILABLE",
        inputRevision: Int = 4,
        currentRevision: Int = 4,
    ) = RemoteAnalysis(
        monthId = "2026-11",
        inputRevision = inputRevision,
        currentRevision = currentRevision,
        freshness = freshness,
        status = status,
        promptVersion = "analysis-v1",
    )

    @Test
    fun `server STALE maps to an outdated report`() {
        val mapped = remote("STALE").toServer()

        assertEquals(SummaryFreshness.STALE, mapped.freshness)
        assertEquals(AnalysisStatus.OUTDATED, mapped.status)
    }

    @Test
    fun `dependency stale without a revision bump still maps to outdated`() {
        val mapped = remote("STALE", inputRevision = 4, currentRevision = 4).toServer()

        assertEquals(4, mapped.inputRevision)
        assertEquals(4, mapped.currentRevision)
        assertEquals(AnalysisStatus.OUTDATED, mapped.status)
    }

    @Test
    fun `server CURRENT keeps the stored availability`() {
        val mapped = remote("CURRENT", status = "AVAILABLE").toServer()

        assertEquals(SummaryFreshness.CURRENT, mapped.freshness)
        assertEquals(AnalysisStatus.AVAILABLE, mapped.status)
    }

    @Test
    fun `server HISTORICAL maps to a historical report`() {
        val mapped = remote("HISTORICAL", status = "HISTORICAL").toServer()

        assertEquals(SummaryFreshness.HISTORICAL, mapped.freshness)
        assertEquals(AnalysisStatus.HISTORICAL, mapped.status)
    }

    @Test
    fun `unknown freshness is never rendered as available`() {
        val mapped = remote("SOMETHING_NEW").toServer()

        assertEquals(SummaryFreshness.UNKNOWN, mapped.freshness)
        assertEquals(AnalysisStatus.UNAVAILABLE, mapped.status)
    }

    @Test
    fun `explicit null suggestion and limitation fields decode and map to empty lists`() {
        // Regression: the live backend answered `"suggestions": null` for a stored analysis and the
        // strict decoder turned that into AppError.Parsing ("Data tidak dapat dibaca.").
        val raw = """{"monthId":"2026-11","inputRevision":4,"currentRevision":5,
            "freshness":"STALE","status":"AVAILABLE","promptVersion":"analysis-v1",
            "observations":[{"text":"Arus kas keluar bulan ini tercatat pada ringkasan.",
            "metricKeys":["summary.grossOutflow"]}],
            "limitations":["Analisis memakai proyeksi agregat."],"suggestions":null}"""
        val decoded = com.pam.recap.core.util.PamJson.decodeFromString(
            RemoteAnalysis.serializer(),
            raw,
        )

        val mapped = decoded.toServer()

        assertEquals(SummaryFreshness.STALE, mapped.freshness)
        assertEquals(emptyList<Any>(), mapped.suggestions)
        assertEquals(1, mapped.limitations.size)
    }

    @Test
    fun `observations and limitations survive the mapping`() {
        val mapped = RemoteAnalysis(
            monthId = "2026-11",
            freshness = "CURRENT",
            observations = listOf(RemoteObservation("Arus kas bersih negatif", listOf("NET_CASH_FLOW"))),
            limitations = listOf("LIMITATION_NOT_FINANCIAL_ADVICE"),
        ).toServer()

        assertEquals("Arus kas bersih negatif", mapped.observations.single().text)
        assertEquals(listOf("NET_CASH_FLOW"), mapped.observations.single().metricKeys)
        assertEquals(listOf("LIMITATION_NOT_FINANCIAL_ADVICE"), mapped.limitations)
    }
}
