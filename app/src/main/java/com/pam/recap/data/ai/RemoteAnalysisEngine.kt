package com.pam.recap.data.ai

import com.pam.recap.core.di.IoDispatcher
import com.pam.recap.core.result.AppError
import com.pam.recap.core.result.Outcome
import com.pam.recap.core.result.SafeLogger
import com.pam.recap.data.backend.BackendEndpoints
import com.pam.recap.data.backend.BackendErrorBody
import com.pam.recap.data.backend.backendError
import com.pam.recap.domain.model.AnalysisUsage
import com.pam.recap.domain.model.RawAnalysis
import com.pam.recap.domain.model.RawObservation
import com.pam.recap.domain.model.RawSuggestion
import com.pam.recap.domain.repository.AnalysisEngine
import com.pam.recap.domain.repository.AnalysisEngineConfiguration
import com.pam.recap.domain.repository.AnalysisRequest
import com.pam.recap.domain.repository.BackendSessionRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Backend-backed engine (OI-07, NFR-26, contract §4.1).
 *
 * Enabled only when a backend URL is configured; otherwise it fails fast with a configuration error
 * so `RequestAnalysisUseCase` keeps the deterministic on-device engine. The request is the exact
 * contract body `{monthId, inputRevision, payload}` with a Bearer session and a stable
 * `Idempotency-Key`, so a retry of the same frozen revision is a replay rather than a second
 * provider call.
 *
 * Nothing in this class logs the payload, the endpoint, the id token or the access token (NFR-18).
 */
@Singleton
class RemoteAnalysisEngine @Inject constructor(
    private val httpClient: OkHttpClient,
    private val json: Json,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    private val config: BackendEndpoints,
    private val backendSessions: BackendSessionRepository,
) : AnalysisEngine, AnalysisEngineConfiguration {

    override val providerId: String = PROVIDER_ID

    override val modelId: String = MODEL_ID

    override val isConfigured: Boolean
        get() = config.isEnabled && config.analysesUrl != null

    override suspend fun analyze(request: AnalysisRequest): Outcome<RawAnalysis> {
        val url = config.analysesUrl
        if (!isConfigured || url == null) {
            return Outcome.Failure(AppError.Configuration("analysis_backend_not_configured"))
        }

        return withContext(ioDispatcher) {
            val body = json.encodeToString(AnalysisRequestBody.serializer(), request.toBody())
            val idempotencyKey = request.idempotencyKey()

            for (attempt in 0 until MAX_ATTEMPTS) {
                val token = when (val result = backendSessions.token(request.ownerId)) {
                    is Outcome.Success -> result.value
                    is Outcome.Failure -> return@withContext Outcome.Failure(result.error)
                }
                val outcome = call(url, body, idempotencyKey, token)
                when {
                    outcome.value != null -> return@withContext Outcome.Success(outcome.value)
                    // Expired/revoked session: drop it and retry exactly once with a fresh token.
                    outcome.unauthorized && attempt == 0 -> backendSessions.clear()
                    else -> return@withContext Outcome.Failure(
                        outcome.error ?: AppError.Unknown("backend_empty"),
                    )
                }
            }
            Outcome.Failure(AppError.Unauthorized("backend_401"))
        }
    }

    private fun call(
        url: String,
        body: String,
        idempotencyKey: String,
        token: String,
    ): CallOutcome = try {
        val request = Request.Builder()
            .url(url)
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .header("authorization", "Bearer $token")
            .header("idempotency-key", idempotencyKey)
            .header("content-type", JSON_MEDIA_TYPE.toString())
            .header("accept", JSON_MEDIA_TYPE.toString())
            .build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val code = runCatching {
                    json.decodeFromString(BackendErrorBody.serializer(), response.body?.string().orEmpty())
                }.getOrNull()?.code
                CallOutcome(
                    unauthorized = response.code == 401,
                    error = backendError(response.code, code),
                    value = null,
                )
            } else {
                val text = response.body?.string().orEmpty()
                val decoded = json.decodeFromString(RemoteAnalysisResponse.serializer(), text)
                CallOutcome(unauthorized = false, error = null, value = decoded.toRaw())
            }
        }
    } catch (error: IOException) {
        // Only a class/technical marker is recorded: never the payload or the endpoint.
        SafeLogger.failure(TAG, "analysis_backend_call_failed")
        CallOutcome(unauthorized = false, error = AppError.Network("backend_unreachable"), value = null)
    } catch (error: Exception) {
        SafeLogger.failure(TAG, "analysis_backend_invalid_response")
        CallOutcome(unauthorized = false, error = AppError.Network("backend_invalid_response"), value = null)
    }

    private data class CallOutcome(
        val unauthorized: Boolean,
        val error: AppError?,
        val value: RawAnalysis?,
    ) {
        fun result(): Outcome<RawAnalysis> = when {
            value != null -> Outcome.Success(value)
            error != null -> Outcome.Failure(error)
            else -> Outcome.Failure(AppError.Unknown("backend_empty"))
        }
    }

    @Serializable
    private data class RemoteAnalysisResponse(
        val promptVersion: String? = null,
        // The backend writes explicit JSON null for empty GET fields (e.g. `"suggestions": null`);
        // nullable here so a successful POST is not misreported as an invalid response.
        val observations: List<RemoteObservation>? = null,
        val suggestions: List<RemoteSuggestion>? = null,
        val limitations: List<String>? = null,
        val promptTokens: Int? = null,
        val completionTokens: Int? = null,
    ) {
        fun toRaw(): RawAnalysis = RawAnalysis(
            providerId = PROVIDER_ID,
            modelId = MODEL_ID,
            promptVersion = promptVersion ?: AnalysisPrompt.REMOTE_VERSION,
            observations = observations.orEmpty().map { observation ->
                RawObservation(text = observation.text, metricKeys = observation.metricKeys)
            },
            suggestions = suggestions.orEmpty().map { suggestion ->
                RawSuggestion(
                    text = suggestion.text,
                    metricKeys = suggestion.metricKeys,
                    categoryCode = suggestion.categoryCode,
                    suggestedAmountMinor = suggestion.suggestedAmountMinor,
                )
            },
            limitations = limitations.orEmpty(),
            usage = AnalysisUsage(promptTokens = promptTokens, completionTokens = completionTokens),
        )
    }

    @Serializable
    private data class RemoteObservation(
        val text: String,
        val metricKeys: List<String> = emptyList(),
    )

    @Serializable
    private data class RemoteSuggestion(
        val text: String,
        val metricKeys: List<String> = emptyList(),
        val categoryCode: String? = null,
        val suggestedAmountMinor: Long? = null,
    )

    private companion object {
        const val TAG = "RemoteAnalysis"
        const val PROVIDER_ID = "pam-remote"
        const val MODEL_ID = "remote-v1"
        const val MAX_ATTEMPTS = 2
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
