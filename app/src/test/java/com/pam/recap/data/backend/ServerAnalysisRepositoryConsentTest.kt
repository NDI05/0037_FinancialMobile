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
import com.pam.recap.domain.repository.ConsentRepository
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
 * B-RE-3: the server-mode generate path must honour the same local AI-consent gate as the local
 * path (`RequestAnalysisUseCase`). The UI disables the button and the backend answers 403, but a
 * side-effecting POST must never leave the device before the local ledger says granted.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ServerAnalysisRepositoryConsentTest {
    private lateinit var server: MockWebServer
    private lateinit var finance: RecordingServerFinanceRepository
    private lateinit var engine: RecordingEngine

    private val month = JakartaMonth.of(Instant.parse("2026-11-19T02:15:20Z"))

    private class RecordingEngine : AnalysisEngine {
        val requests = mutableListOf<AnalysisRequest>()

        override val providerId = "pam-remote"
        override val modelId = "test"

        override suspend fun analyze(request: AnalysisRequest): Outcome<RawAnalysis> {
            requests += request
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
        val api = PamApiClient(OkHttpClient(), PamJson, endpoints, sessions, UnconfinedTestDispatcher())
        finance = RecordingServerFinanceRepository().apply { summaryOutcome = Outcome.Success(summary()) }
        engine = RecordingEngine()
        this.api = api
    }

    private lateinit var api: PamApiClient

    private fun summary(): MonthlySummary = MonthlySummary.empty(
        ownerId = "u_1",
        month = month,
        mailboxLabel = "synthetic@example.test",
        coverage = CoverageStatus.COMPLETE,
        inputRevision = 5,
        generatedAt = Instant.parse("2026-11-19T05:00:00Z"),
    ).copy(
        currentRevision = 5,
        transactionCount = 6,
    )

    private fun repo(consents: ConsentRepository) = ServerAnalysisRepositoryImpl(
        api = api,
        finance = finance,
        remoteEngine = engine,
        remoteConfiguration = object : AnalysisEngineConfiguration {
            override val isConfigured = true
        },
        consents = consents,
    )

    private fun grantedConsent() = FakeConsentRepository(
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
    )

    @Test
    fun `generate without granted AI consent fails locally and sends no request`() = runTest {
        val result = repo(FakeConsentRepository()).generate("u_1", month)

        val failure = result as Outcome.Failure
        assertEquals(
            AppErrorRules.CONSENT_REQUIRED,
            (failure.error as AppError.Validation).rule,
        )
        assertEquals(0, engine.requests.size)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `withdrawn AI consent maps to the withdrawn rule without sending`() = runTest {
        val consents = grantedConsent().apply { withdraw(ConsentPurpose.AI_PROCESSING) }

        val result = repo(consents).generate("u_1", month)

        val failure = result as Outcome.Failure
        assertEquals(
            AppErrorRules.AI_CONSENT_WITHDRAWN,
            (failure.error as AppError.Validation).rule,
        )
        assertEquals(0, engine.requests.size)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `granted AI consent still posts through the remote engine on the canonical revision`() = runTest {
        // Budget plan is optional in the payload: answer 500 so planSummary() omits it.
        server.enqueue(MockResponse().setResponseCode(500).setBody("""{"code":"internal_error"}"""))

        val result = repo(grantedConsent()).generate("u_1", month)

        assertTrue(result is Outcome.Success)
        assertEquals(1, engine.requests.size)
        assertEquals(5, engine.requests.single().inputRevision)
    }

    @After
    fun tearDown() = server.shutdown()
}
