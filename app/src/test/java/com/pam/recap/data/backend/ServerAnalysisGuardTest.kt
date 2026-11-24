package com.pam.recap.data.backend

import com.pam.recap.core.result.AppError
import com.pam.recap.core.result.AppErrorRules
import com.pam.recap.core.result.Outcome
import com.pam.recap.core.time.JakartaMonth
import com.pam.recap.core.util.PamJson
import com.pam.recap.domain.model.Consent
import com.pam.recap.domain.model.ConsentDecision
import com.pam.recap.domain.model.ConsentPurpose
import com.pam.recap.domain.model.CoverageStatus
import com.pam.recap.domain.model.MonthlySummary
import com.pam.recap.domain.model.RawAnalysis
import com.pam.recap.domain.model.RawObservation
import com.pam.recap.domain.repository.AnalysisEngine
import com.pam.recap.domain.repository.AnalysisEngineConfiguration
import com.pam.recap.domain.repository.AnalysisRequest
import com.pam.recap.domain.repository.BackendSessionRepository
import com.pam.recap.testing.FakeConsentRepository
import com.pam.recap.ui.feature.transactions.RecordingServerFinanceRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.time.Instant

/**
 * S2.D UAT 24 Nov 2026: the server-mode generate guard must only refuse a month that has no
 * records at all. A month with manual transactions but EMPTY (or FAILED) email coverage is still
 * analysable — its canonical totals exist and the aggregate payload carries the coverage status —
 * so the screen must not answer "Belum ada data untuk bulan ini." for it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ServerAnalysisGuardTest {
    private lateinit var server: MockWebServer
    private lateinit var finance: RecordingServerFinanceRepository
    private lateinit var engine: RecordingEngine
    private lateinit var api: PamApiClient

    private val month = JakartaMonth.of(Instant.parse("2026-11-19T02:15:20Z"))

    private class RecordingEngine : AnalysisEngine {
        val requests = mutableListOf<AnalysisRequest>()

        override val providerId = "pam-remote"
        override val modelId = "test"

        override suspend fun analyze(request: AnalysisRequest): Outcome<RawAnalysis> {
            requests += request
            // The remote engine owns the POST; returning success here proves the guard let the
            // request through to the provider boundary.
            return Outcome.Success(
                RawAnalysis(
                    providerId = providerId,
                    modelId = modelId,
                    promptVersion = "analysis-v1",
                    observations = listOf(RawObservation("Arus kas tercatat.", listOf("summary.grossOutflow"))),
                    suggestions = emptyList(),
                ),
            )
        }
    }

    @Before
    fun setUp() {
        server = MockWebServer(); server.start()
        val endpoints = object : BackendEndpoints {
            override val isEnabled = true
            override val analysesUrl: String? = server.url("/v1/analyses").toString()
            override fun endpoint(path: String) = server.url(path).toString()
        }
        val sessions = object : BackendSessionRepository {
            override val isEnabled = true
            override suspend fun register(email: String, password: String, displayName: String?, inviteCode: String) = Outcome.Success(Unit)
            override suspend fun login(email: String, password: String) = Outcome.Success(Unit)
            override suspend fun token(ownerId: String) = Outcome.Success("token")
            override suspend fun revoke() = Unit
            override fun clear() = Unit
        }
        api = PamApiClient(OkHttpClient(), PamJson, endpoints, sessions, UnconfinedTestDispatcher())
        finance = RecordingServerFinanceRepository()
        engine = RecordingEngine()
    }

    private fun summary(coverage: CoverageStatus, transactionCount: Int): MonthlySummary =
        MonthlySummary.empty(
            ownerId = "u_1",
            month = month,
            mailboxLabel = "synthetic@example.test",
            coverage = coverage,
            inputRevision = 5,
            generatedAt = Instant.parse("2026-11-19T05:00:00Z"),
        ).copy(
            currentRevision = 5,
            transactionCount = transactionCount,
        )

    private fun repo() = ServerAnalysisRepositoryImpl(
        api = api,
        finance = finance,
        remoteEngine = engine,
        remoteConfiguration = object : AnalysisEngineConfiguration {
            override val isConfigured = true
        },
        consents = FakeConsentRepository(
            listOf(
                Consent(
                    id = "consent-1",
                    ownerId = "u_1",
                    purpose = ConsentPurpose.AI_PROCESSING,
                    noticeVersion = "v1",
                    decision = ConsentDecision.GRANTED,
                    decidedAt = Instant.fromEpochMilliseconds(0),
                ),
            ),
        ),
    )

    @Test
    fun `EMPTY email coverage with manual transactions still generates`() = runTest {
        // Budget plan read: answer 500 so planSummary() omits the optional plan.
        server.enqueue(MockResponse().setResponseCode(500).setBody("""{"code":"internal_error"}"""))
        finance.summaryOutcome = Outcome.Success(summary(CoverageStatus.EMPTY, transactionCount = 90))

        val result = repo().generate("u_1", month)

        assertTrue("EMPTY coverage + transactions must reach the provider", result is Outcome.Success)
        assertEquals(1, engine.requests.size)
        assertEquals(5, engine.requests.single().inputRevision)
    }

    @Test
    fun `FAILED email coverage with manual transactions still generates`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500).setBody("""{"code":"internal_error"}"""))
        finance.summaryOutcome = Outcome.Success(summary(CoverageStatus.FAILED, transactionCount = 3))

        val result = repo().generate("u_1", month)

        assertTrue(result is Outcome.Success)
        assertEquals(1, engine.requests.size)
    }

    @Test
    fun `month without transactions is still rejected honestly`() = runTest {
        finance.summaryOutcome = Outcome.Success(summary(CoverageStatus.EMPTY, transactionCount = 0))

        val result = repo().generate("u_1", month)

        val failure = result as Outcome.Failure
        assertEquals(
            AppErrorRules.MONTH_HAS_NO_DATA,
            (failure.error as AppError.Validation).rule,
        )
        assertEquals("no provider call for a month with no records", 0, engine.requests.size)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `COMPLETE coverage with data still generates`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500).setBody("""{"code":"internal_error"}"""))
        finance.summaryOutcome = Outcome.Success(summary(CoverageStatus.COMPLETE, transactionCount = 12))

        val result = repo().generate("u_1", month)

        assertTrue(result is Outcome.Success)
        assertEquals(1, engine.requests.size)
    }

    @After
    fun tearDown() = server.shutdown()
}
