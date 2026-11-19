package com.pam.recap.data.ai

import com.pam.recap.BuildConfig
import com.pam.recap.core.di.IoDispatcher
import com.pam.recap.core.result.AppError
import com.pam.recap.core.result.Outcome
import com.pam.recap.core.result.SafeLogger
import com.pam.recap.domain.model.AiAggregatePayload
import com.pam.recap.domain.model.AnalysisUsage
import com.pam.recap.domain.model.RawAnalysis
import com.pam.recap.domain.model.RawObservation
import com.pam.recap.domain.model.RawSuggestion
import com.pam.recap.domain.repository.AnalysisEngine
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Optional backend-backed engine (OI-07, NFR-26). Enabled only when
 * `PAM_ANALYSIS_BACKEND_URL` is configured; otherwise it fails fast with a configuration error
 * so the caller can fall back to the on-device engine.
 *
 * It posts exactly the [AiAggregatePayload] (aggregate numbers only, FR-35) and never sends a
 * credential: the backend URL is the only configuration and no token is stored or logged
 * (NFR-14).
 */
@Singleton
class RemoteAnalysisEngine @Inject constructor(
    private val httpClient: OkHttpClient,
    private val json: Json,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : AnalysisEngine {

    override val providerId: String = PROVIDER_ID

    override val modelId: String = MODEL_ID

    private val endpoint: String = BuildConfig.PAM_ANALYSIS_BACKEND_URL

    val isConfigured: Boolean get() = endpoint.isNotBlank()

    override suspend fun analyze(payload: AiAggregatePayload): Outcome<RawAnalysis> {
        if (!isConfigured) {
            return Outcome.Failure(AppError.Configuration("analysis_backend_not_configured"))
        }
        return withContext(ioDispatcher) {
            try {
                val body = json.encodeToString(AiAggregatePayload.serializer(), payload)
                val request = Request.Builder()
                    .url(endpoint)
                    .post(body.toRequestBody(JSON_MEDIA_TYPE))
                    .header("Accept", JSON_MEDIA_TYPE.toString())
                    .build()
                httpClient.newCall(request).execute().use { response ->
                    when {
                        response.code == 401 || response.code == 403 -> Outcome.Failure(
                            AppError.Unauthorized("analysis_backend_${response.code}"),
                        )

                        !response.isSuccessful -> Outcome.Failure(
                            AppError.Network("analysis_backend_${response.code}"),
                        )

                        else -> {
                            val text = response.body?.string().orEmpty()
                            val decoded = json.decodeFromString(RemoteAnalysisResponse.serializer(), text)
                            Outcome.Success(decoded.toRaw())
                        }
                    }
                }
            } catch (error: Throwable) {
                // Only an error class name is logged: never the payload or the endpoint (NFR-18).
                SafeLogger.failure(TAG, "analysis_backend_call_failed")
                Outcome.Failure(AppError.Network(error::class.simpleName))
            }
        }
    }

    @Serializable
    private data class RemoteAnalysisResponse(
        val promptVersion: String? = null,
        val observations: List<RemoteObservation> = emptyList(),
        val suggestions: List<RemoteSuggestion> = emptyList(),
        val limitations: List<String> = emptyList(),
        val promptTokens: Int? = null,
        val completionTokens: Int? = null,
    ) {
        fun toRaw(): RawAnalysis = RawAnalysis(
            providerId = PROVIDER_ID,
            modelId = MODEL_ID,
            promptVersion = promptVersion ?: AnalysisPrompt.REMOTE_VERSION,
            observations = observations.map { observation ->
                RawObservation(text = observation.text, metricKeys = observation.metricKeys)
            },
            suggestions = suggestions.map { suggestion ->
                RawSuggestion(
                    text = suggestion.text,
                    metricKeys = suggestion.metricKeys,
                    categoryCode = suggestion.categoryCode,
                    suggestedAmountMinor = suggestion.suggestedAmountMinor,
                )
            },
            limitations = limitations,
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
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
