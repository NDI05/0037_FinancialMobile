package com.pam.recap.data.ai

import com.pam.recap.core.result.AppError
import com.pam.recap.core.result.ErrorKind
import com.pam.recap.core.result.AppErrorRules
import com.pam.recap.core.result.Outcome
import com.pam.recap.core.util.PamJson
import com.pam.recap.data.backend.BackendConfig
import com.pam.recap.data.backend.BackendEndpoints
import com.pam.recap.domain.model.AiAggregatePayload
import com.pam.recap.domain.model.AiCategoryTotal
import com.pam.recap.domain.model.MetricKeys
import com.pam.recap.domain.repository.AnalysisRequest
import com.pam.recap.domain.repository.BackendSessionRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * E5.1–E5.4: the engine speaks the backend contract — exact body, Bearer session, stable
 * idempotency key, and a precise [AppError] for every documented status.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RemoteAnalysisEngineTest {

    private lateinit var server: MockWebServer
    private lateinit var endpoints: BackendEndpoints

    private val request = AnalysisRequest(
        ownerId = "u_owner_hash_source",
        monthId = "2026-02",
        inputRevision = 7,
        payload = payload(),
    )

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        endpoints = object : BackendEndpoints {
            override val isEnabled: Boolean = true
            override val analysesUrl: String = server.url("/v1/analyses").toString()
            override fun endpoint(path: String): String = server.url(path).toString()
        }
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    // ---- E5.1: exact contract request -----------------------------------------------------
    @Test
    fun `sends the contract body, bearer token and a stable idempotency key`() = runTest {
        server.enqueue(jsonResponse(200, validResponseJson()))

        val engine = engine()
        val result = engine.analyze(request)

        assertTrue(result is Outcome.Success)

        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/v1/analyses", recorded.path)
        assertEquals("Bearer test-access-token", recorded.getHeader("authorization"))
        assertEquals(request.idempotencyKey(), recorded.getHeader("idempotency-key"))
        assertTrue(recorded.getHeader("idempotency-key")!!.startsWith("analysis:"))
        assertTrue(recorded.getHeader("content-type")!!.startsWith("application/json"))

        val body = Json.parseToJsonElement(recorded.body.readUtf8()).jsonObject
        assertEquals(setOf("monthId", "inputRevision", "payload"), body.keys)
        assertEquals("2026-02", body["monthId"]?.jsonPrimitive?.content)
        assertEquals("7", body["inputRevision"]?.jsonPrimitive?.content)
        // Nested, not flattened: the payload keeps its own keys.
        val payloadObject = body["payload"]!!.jsonObject
        assertEquals("2026-02", payloadObject["monthId"]?.jsonPrimitive?.content)
        assertEquals("585000000", payloadObject["grossInflowMinor"]?.jsonPrimitive?.content)
        assertNull(body["grossInflowMinor"])
        assertNull(payloadObject["subject"])
        assertNull(payloadObject["body"])
    }

    @Test
    fun `the idempotency key is stable per owner, month and revision`() {
        val first = request.idempotencyKey()
        val second = request.copy(payload = request.payload).idempotencyKey()
        assertEquals(first, second)
        assertFalse(first == request.copy(inputRevision = 8).idempotencyKey())
        assertFalse(first == request.copy(monthId = "2026-03").idempotencyKey())
        assertFalse(first == request.copy(ownerId = "u_other").idempotencyKey())
        assertFalse(first.contains("u_owner_hash_source"))
    }

    // ---- E5.2: parsed output --------------------------------------------------------------
    @Test
    fun `a valid response becomes a RawAnalysis with metric keys preserved`() = runTest {
        server.enqueue(jsonResponse(200, validResponseJson()))

        val result = engine().analyze(request) as Outcome.Success

        assertEquals("pam-remote", result.value.providerId)
        assertEquals("analysis-v1", result.value.promptVersion)
        assertEquals(1, result.value.observations.size)
        assertEquals(
            listOf(MetricKeys.GROSS_OUTFLOW, MetricKeys.category("housing")),
            result.value.observations.single().metricKeys,
        )
        assertEquals("housing", result.value.suggestions.single().categoryCode)
        assertEquals(90_000_000L, result.value.suggestions.single().suggestedAmountMinor)
        assertEquals(3, result.value.limitations.size)
        assertEquals(320, result.value.usage?.promptTokens)
        assertEquals(180, result.value.usage?.completionTokens)
    }

    @Test
    fun `explicitly null response lists still decode as a successful analysis`() = runTest {
        // The live backend serialises absent lists as JSON null; a strict decode used to turn the
        // successful POST into AppError.Network("backend_invalid_response") and the UI showed
        // "Tidak dapat terhubung" right after a regeneration that actually succeeded.
        server.enqueue(
            jsonResponse(
                200,
                """{"promptVersion":"analysis-v1","promptTokens":null,"completionTokens":null,
                    "observations":[{"text":"Arus kas keluar tercatat.","metricKeys":["summary.grossOutflow"]}],
                    "suggestions":null,"limitations":null}""",
            ),
        )

        val result = engine().analyze(request) as Outcome.Success

        assertEquals(1, result.value.observations.size)
        assertEquals(emptyList<Any>(), result.value.suggestions)
        assertEquals(emptyList<Any>(), result.value.limitations)
    }

    // ---- E5.3: 401 handling ---------------------------------------------------------------
    @Test
    fun `a 401 clears the session and retries once, then succeeds`() = runTest {
        server.enqueue(jsonResponse(401, errorJson("unauthorized")))
        server.enqueue(jsonResponse(200, validResponseJson()))
        val sessions = StubBackendSessions()

        val result = engine(sessions = sessions).analyze(request)

        assertTrue(result is Outcome.Success)
        assertEquals(1, sessions.clearCalls)
        assertEquals(2, sessions.tokenCalls)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `a second 401 fails as Unauthorized`() = runTest {
        server.enqueue(jsonResponse(401, errorJson("unauthorized")))
        server.enqueue(jsonResponse(401, errorJson("unauthorized")))
        val sessions = StubBackendSessions()

        val result = engine(sessions = sessions).analyze(request)

        assertTrue(result is Outcome.Failure)
        assertEquals(ErrorKind.UNAUTHORIZED, (result as Outcome.Failure).error.kind)
        assertEquals(1, sessions.clearCalls)
        assertEquals(2, server.requestCount)
    }

    // ---- E5.4: status mapping -------------------------------------------------------------
    @Test
    fun `403 consent_required maps to PermissionRequired`() = runTest {
        server.enqueue(jsonResponse(403, errorJson("consent_required")))
        assertError(ErrorKind.PERMISSION_REQUIRED) { error ->
            assertEquals("consent_required", error.debugDetail)
        }
    }

    @Test
    fun `422 maps to a validation rejection`() = runTest {
        server.enqueue(jsonResponse(422, errorJson("invalid_analysis_output")))
        assertError(ErrorKind.VALIDATION) { error ->
            assertEquals(AppErrorRules.ANALYSIS_REJECTED, (error as AppError.Validation).rule)
        }
    }

    @Test
    fun `429 maps to RateLimited with the server code`() = runTest {
        server.enqueue(jsonResponse(429, errorJson("daily_ceiling_reached")))
        assertError(ErrorKind.RATE_LIMITED) { error ->
            assertEquals("daily_ceiling_reached", error.debugDetail)
        }
    }

    @Test
    fun `500 maps to Network`() = runTest {
        server.enqueue(jsonResponse(500, errorJson("internal_error")))
        assertError(ErrorKind.NETWORK) { }
    }

    @Test
    fun `a timeout maps to Network`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(validResponseJson())
                .setBodyDelay(500, TimeUnit.MILLISECONDS),
        )
        val engine = engine(client = OkHttpClient.Builder().readTimeout(80, TimeUnit.MILLISECONDS).build())
        val result = engine.analyze(request)
        assertTrue(result is Outcome.Failure)
        assertEquals(ErrorKind.NETWORK, (result as Outcome.Failure).error.kind)
    }

    // ---- E5.6: no backend configured ------------------------------------------------------
    @Test
    fun `a blank base URL leaves the engine unconfigured and fails fast`() = runTest {
        // Explicit blanks: the blank-URL contract must not depend on how this build was invoked.
        val blank = BackendConfig(rawBaseUrl = "", rawLegacyAnalysisUrl = "")
        assertFalse(blank.isEnabled)
        assertNull(blank.analysesUrl)
        assertFalse(blank.endpoint("/v1/sessions") != null && blank.endpoint("/v1/sessions")!!.isNotBlank())

        val engine = RemoteAnalysisEngine(
            httpClient = OkHttpClient(),
            json = PamJson,
            ioDispatcher = UnconfinedTestDispatcher(),
            config = blank,
            backendSessions = StubBackendSessions(),
        )
        assertFalse(engine.isConfigured)
        val result = engine.analyze(request)
        assertTrue(result is Outcome.Failure)
        assertEquals(ErrorKind.CONFIGURATION, (result as Outcome.Failure).error.kind)
        assertEquals(0, server.requestCount)
    }

    // ---- helpers -------------------------------------------------------------------------

    private suspend fun assertError(kind: ErrorKind, verify: (AppError) -> Unit) {
        val result = engine().analyze(request)
        assertTrue(result is Outcome.Failure)
        val error = (result as Outcome.Failure).error
        assertEquals(kind, error.kind)
        verify(error)
    }

    private fun engine(
        sessions: BackendSessionRepository = StubBackendSessions(),
        client: OkHttpClient = OkHttpClient(),
    ) = RemoteAnalysisEngine(
        httpClient = client,
        json = PamJson,
        ioDispatcher = UnconfinedTestDispatcher(),
        config = endpoints,
        backendSessions = sessions,
    )

    private fun jsonResponse(code: Int, body: String) = MockResponse()
        .setResponseCode(code)
        .setHeader("content-type", "application/json")
        .setBody(body)

    private fun errorJson(code: String) =
        """{"code":"$code","message":"teknisi","retryable":false,"correlationId":"c-1"}"""

    private fun validResponseJson() = """
        {
          "promptVersion": "analysis-v1",
          "observations": [
            {
              "text": "Pengeluaran terbesar ada di kategori housing.",
              "metricKeys": ["summary.grossOutflow", "category.housing"]
            }
          ],
          "suggestions": [
            {
              "text": "Pertimbangkan batas untuk housing.",
              "metricKeys": ["category.housing"],
              "categoryCode": "housing",
              "suggestedAmountMinor": 90000000
            }
          ],
          "limitations": ["Agregat saja.", "Berbasis email.", "Bukan saran keuangan."],
          "promptTokens": 320,
          "completionTokens": 180
        }
    """.trimIndent()

    private fun payload() = AiAggregatePayload(
        schemaVersion = AiAggregatePayload.SCHEMA_VERSION,
        monthId = "2026-02",
        coverage = "COMPLETE",
        grossInflowMinor = 585_000_000,
        grossOutflowMinor = 287_065_000,
        netCashFlowMinor = 297_935_000,
        classifiedIncomeMinor = 575_000_000,
        classifiedExpenseMinor = 249_565_000,
        transactionCount = 18,
        uncategorizedCount = 1,
        manualEntryCount = 2,
        nonIdrExcludedCount = 1,
        categoryTotals = listOf(
            AiCategoryTotal(categoryCode = "housing", amountMinor = 90_000_000, transactionCount = 2),
        ),
        priorMonth = null,
        plan = null,
    )

    private class StubBackendSessions : BackendSessionRepository {
        var tokenCalls = 0
        var clearCalls = 0

        override val isEnabled: Boolean = true

        override suspend fun register(
            email: String,
            password: String,
            displayName: String?,
            inviteCode: String,
        ): Outcome<Unit> = Outcome.Success(Unit)

        override suspend fun login(email: String, password: String): Outcome<Unit> =
            Outcome.Success(Unit)

        override suspend fun token(ownerId: String): Outcome<String> {
            tokenCalls++
            return Outcome.Success("test-access-token")
        }

        override suspend fun revoke() = Unit

        override fun clear() {
            clearCalls++
        }
    }

}
