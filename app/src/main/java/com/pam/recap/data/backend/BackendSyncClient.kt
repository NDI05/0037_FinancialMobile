package com.pam.recap.data.backend

import com.pam.recap.core.di.ApplicationScope
import com.pam.recap.core.di.IoDispatcher
import com.pam.recap.core.result.AppError
import com.pam.recap.core.result.Outcome
import com.pam.recap.core.result.SafeLogger
import com.pam.recap.domain.model.ConsentDecision
import com.pam.recap.domain.model.ConsentPurpose
import com.pam.recap.domain.repository.AnalysisRequest
import com.pam.recap.domain.repository.BackendConsentSync
import com.pam.recap.domain.repository.BackendDeletionSync
import com.pam.recap.domain.repository.BackendSessionRepository
import com.pam.recap.domain.repository.BackendSummarySync
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Server-side copies of state the analysis gate needs (contract §4): the consent decision, the
 * frozen summary, and the deletion request.
 *
 * Every method is a no-op without a backend URL, and none of them throws: the device is the source
 * of truth for the UI, and a failed push only means the server has not caught up yet.
 */
@Singleton
class BackendSyncClient @Inject constructor(
    private val client: OkHttpClient,
    private val json: Json,
    private val config: BackendEndpoints,
    private val backendSessions: BackendSessionRepository,
    @param:ApplicationScope private val appScope: CoroutineScope,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : BackendConsentSync, BackendSummarySync, BackendDeletionSync {

    // ---------------------------------------------------------------------------------------
    // Consent (FR-03/FR-34)
    // ---------------------------------------------------------------------------------------

    override suspend fun record(
        ownerId: String,
        purpose: ConsentPurpose,
        decision: ConsentDecision,
        noticeVersion: String,
    ) {
        if (!config.isEnabled) return
        // Fire-and-forget: the local ledger write already happened and must never wait on HTTP.
        appScope.launch(ioDispatcher) {
            val body = json.encodeToString(
                ConsentBody.serializer(),
                ConsentBody(
                    purpose = purpose.name,
                    decision = decision.name,
                    noticeVersion = noticeVersion,
                ),
            )
            postWithRetry(ownerId, "/v1/consents", body, expectedStatus = 201)
        }
    }

    // ---------------------------------------------------------------------------------------
    // Summary (FR-33)
    // ---------------------------------------------------------------------------------------

    override suspend fun push(request: AnalysisRequest): Outcome<Unit> {
        if (!config.isEnabled) return Outcome.Success(Unit)
        val path = "/v1/summaries/${request.monthId}"
        val body = json.encodeToString(
            SummaryBody.serializer(),
            SummaryBody(inputRevision = request.inputRevision, payload = request.payload),
        )
        val status = putWithRetry(request.ownerId, path, body, expectedStatus = 200)
        return if (status != null) Outcome.Success(Unit) else Outcome.Failure(AppError.Network("summary_push_failed"))
    }

    // ---------------------------------------------------------------------------------------
    // Deletion (FR-47/FR-48)
    // ---------------------------------------------------------------------------------------

    override suspend fun request(ownerId: String): Outcome<Int> {
        if (!config.isEnabled) return Outcome.Failure(AppError.Configuration("backend_not_configured"))
        val endpoint = config.endpoint("/v1/deletion-requests")
            ?: return Outcome.Failure(AppError.Configuration("backend_not_configured"))
        val token = tokenFor(ownerId) ?: return Outcome.Failure(AppError.Unauthorized("backend_no_session"))
        return withContext(ioDispatcher) {
            try {
                val response = client.newCall(
                    Request.Builder()
                        .url(endpoint)
                        .post(EMPTY_BODY)
                        .header("authorization", "Bearer $token")
                        .header("accept", JSON_MEDIA_TYPE.toString())
                        .build(),
                ).execute()
                response.use {
                    val payload = it.body?.string().orEmpty()
                    if (it.code != 202) {
                        return@withContext Outcome.Failure(AppError.Network("deletion_request_failed"))
                    }
                    val decoded = json.decodeFromString(DeletionResponse.serializer(), payload)
                    Outcome.Success(decoded.slaDays)
                }
            } catch (error: IOException) {
                SafeLogger.failure(TAG, "deletion_push_failed")
                Outcome.Failure(AppError.Network("backend_unreachable"))
            } catch (error: Exception) {
                SafeLogger.failure(TAG, "deletion_push_invalid")
                Outcome.Failure(AppError.Network("backend_invalid_response"))
            }
        }
    }

    // ---------------------------------------------------------------------------------------
    // Internals
    // ---------------------------------------------------------------------------------------

    private suspend fun postWithRetry(
        ownerId: String,
        path: String,
        body: String,
        expectedStatus: Int,
    ): Int? = requestWithRetry(ownerId, path, body, expectedStatus) { url, token, requestBody ->
        Request.Builder()
            .url(url)
            .post(requestBody)
            .header("authorization", "Bearer $token")
            .header("content-type", JSON_MEDIA_TYPE.toString())
            .header("accept", JSON_MEDIA_TYPE.toString())
            .build()
    }

    private suspend fun putWithRetry(
        ownerId: String,
        path: String,
        body: String,
        expectedStatus: Int,
    ): Int? = requestWithRetry(ownerId, path, body, expectedStatus) { url, token, requestBody ->
        Request.Builder()
            .url(url)
            .put(requestBody)
            .header("authorization", "Bearer $token")
            .header("content-type", JSON_MEDIA_TYPE.toString())
            .header("accept", JSON_MEDIA_TYPE.toString())
            .build()
    }

    /** Bounded retry: the server push is best-effort, so two attempts and then give up. */
    private suspend fun requestWithRetry(
        ownerId: String,
        path: String,
        body: String,
        expectedStatus: Int,
        build: (url: String, token: String, body: RequestBody) -> Request,
    ): Int? {
        val endpoint = config.endpoint(path) ?: return null
        repeat(MAX_ATTEMPTS) { attempt ->
            val token = tokenFor(ownerId) ?: return null
            val status = withContext(ioDispatcher) {
                try {
                    client.newCall(build(endpoint, token, body.toRequestBody(JSON_MEDIA_TYPE)))
                        .execute()
                        .use { it.code }
                } catch (error: IOException) {
                    null
                }
            }
            if (status == expectedStatus) return status
            if (status == 401) backendSessions.clear()
            if (attempt < MAX_ATTEMPTS - 1) delay(RETRY_DELAY_MILLIS)
        }
        SafeLogger.warning(TAG, "sync_push_gave_up")
        return null
    }

    private suspend fun tokenFor(ownerId: String): String? {
        return when (val result = backendSessions.token(ownerId)) {
            is Outcome.Success -> result.value
            is Outcome.Failure -> null
        }
    }

    @Serializable
    private data class ConsentBody(
        val purpose: String,
        val decision: String,
        val noticeVersion: String,
    )

    @Serializable
    private data class SummaryBody(
        val inputRevision: Int,
        val payload: com.pam.recap.domain.model.AiAggregatePayload,
    )

    @Serializable
    private data class DeletionResponse(
        val id: String,
        val state: String,
        val slaDays: Int,
    )

    private companion object {
        const val TAG = "BackendSync"
        const val MAX_ATTEMPTS = 2
        const val RETRY_DELAY_MILLIS = 250L
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        val EMPTY_BODY = ByteArray(0).toRequestBody(null)
    }
}
