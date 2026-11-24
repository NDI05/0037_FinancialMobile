package com.pam.recap.data.backend

import com.pam.recap.core.di.IoDispatcher
import com.pam.recap.core.result.AppError
import com.pam.recap.core.result.AppErrorRules
import com.pam.recap.core.result.Outcome
import com.pam.recap.core.result.SafeLogger
import com.pam.recap.domain.repository.BackendSessionRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One authenticated REST boundary for C2 data calls.
 *
 * The access token is fetched from [BackendSessionRepository]; 401 triggers exactly one forced
 * refresh and one replay. Login/refresh never pass through this class, so an interceptor cannot
 * recurse. Bodies, tokens and monetary values are never logged.
 */
@Singleton
class PamApiClient @Inject constructor(
    private val client: OkHttpClient,
    private val json: Json,
    private val config: BackendEndpoints,
    private val sessions: BackendSessionRepository,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {

    suspend fun summary(ownerId: String, monthId: String): Outcome<RemoteSummary> =
        get(ownerId, "/v1/summaries/$monthId", RemoteSummary.serializer())

    /**
     * C10 owner-scoped category list/search. `q` matches code or label case-insensitively on the
     * server; `kind` narrows the taxonomy kind; the search envelope reports how many rows were
     * scanned before the page was cut.
     */
    suspend fun categories(
        ownerId: String,
        query: String = "",
        kind: String? = null,
        limit: Int = 50,
        cursor: String? = null,
    ): Outcome<RemoteCategoriesResponse> {
        val base = config.endpoint("/v1/categories")
            ?: return Outcome.Failure(AppError.Configuration("backend_not_configured"))
        val url = base.toHttpUrl().newBuilder()
            .addQueryParameter("limit", limit.coerceIn(1, 100).toString())
            .apply {
                query.trim().takeIf { it.isNotEmpty() }?.let { addQueryParameter("q", it) }
                kind?.takeIf { it.isNotBlank() }?.let { addQueryParameter("kind", it) }
                cursor?.takeIf { it.isNotBlank() }?.let { addQueryParameter("cursor", it) }
            }
            .build()
        return request(ownerId, Request.Builder().url(url).get(), RemoteCategoriesResponse.serializer())
    }

    suspend fun getTransaction(ownerId: String, transactionId: String): Outcome<RemoteTransaction> =
        get(ownerId, "/v1/transactions/$transactionId", RemoteTransaction.serializer())

    suspend fun listTransactions(
        ownerId: String,
        monthId: String,
        limit: Int = 50,
        cursor: String? = null,
    ): Outcome<RemoteTransactionPage> {
        val base = config.endpoint("/v1/transactions")
            ?: return Outcome.Failure(AppError.Configuration("backend_not_configured"))
        val url = base.toHttpUrl().newBuilder()
            .addQueryParameter("month", monthId)
            .addQueryParameter("limit", limit.coerceIn(1, 100).toString())
            .apply { cursor?.takeIf { it.isNotBlank() }?.let { addQueryParameter("cursor", it) } }
            .build()
        return request(ownerId, Request.Builder().url(url).get(), RemoteTransactionPage.serializer())
    }

    suspend fun createTransaction(
        ownerId: String,
        idempotencyKey: String,
        body: RemoteTransactionCreate,
    ): Outcome<RemoteTransaction> {
        if (idempotencyKey.isBlank()) {
            return Outcome.Failure(AppError.Validation("idempotency_key_required"))
        }
        val encoded = json.encodeToString(RemoteTransactionCreate.serializer(), body)
        return send(ownerId, "POST", "/v1/transactions", encoded, idempotencyKey,
            RemoteTransaction.serializer())
    }

    suspend fun patchTransactionCategory(
        ownerId: String,
        transactionId: String,
        categoryId: String,
        revision: Int,
    ): Outcome<RemoteTransaction> {
        val encoded = json.encodeToString(
            RemoteTransactionPatch.serializer(),
            RemoteTransactionPatch(categoryId = categoryId, revision = revision),
        )
        return send(ownerId, "PATCH", "/v1/transactions/$transactionId", encoded, null,
            RemoteTransaction.serializer())
    }

    suspend fun searchTransactions(
        ownerId: String,
        monthId: String,
        query: String,
        categoryId: String?,
        direction: String?,
        limit: Int,
        cursor: String?,
    ): Outcome<RemoteTransactionPage> {
        val base = config.endpoint("/v1/transactions")
            ?: return Outcome.Failure(AppError.Configuration("backend_not_configured"))
        val url = base.toHttpUrl().newBuilder()
            .addQueryParameter("month", monthId)
            .addQueryParameter("limit", limit.coerceIn(1, 100).toString())
            .apply {
                query.trim().takeIf { it.isNotEmpty() }?.let { addQueryParameter("q", it) }
                categoryId?.takeIf { it.isNotBlank() }?.let { addQueryParameter("categoryId", it) }
                direction?.takeIf { it.isNotBlank() }?.let { addQueryParameter("direction", it) }
                cursor?.takeIf { it.isNotBlank() }?.let { addQueryParameter("cursor", it) }
            }
            .build()
        return request(ownerId, Request.Builder().url(url).get(), RemoteTransactionPage.serializer())
    }

    suspend fun patchTransaction(
        ownerId: String,
        transactionId: String,
        body: RemoteTransactionPatch,
    ): Outcome<RemoteTransaction> {
        val encoded = json.encodeToString(RemoteTransactionPatch.serializer(), body)
        return send(ownerId, "PATCH", "/v1/transactions/$transactionId", encoded, null,
            RemoteTransaction.serializer())
    }

    suspend fun deleteTransaction(
        ownerId: String,
        transactionId: String,
        revision: Int? = null,
    ): Outcome<Unit> {
        val endpoint = config.endpoint("/v1/transactions/$transactionId")
            ?: return Outcome.Failure(AppError.Configuration("backend_not_configured"))
        val builder = Request.Builder().url(endpoint).delete()
        revision?.let { builder.header("If-Match", "\"$it\"") }
        return requestNoContent(ownerId, builder)
    }

    suspend fun createCategory(ownerId: String, body: RemoteCategoryWrite): Outcome<RemoteCategoryItem> {
        val encoded = json.encodeToString(RemoteCategoryWrite.serializer(), body)
        return send(ownerId, "POST", "/v1/categories", encoded, null, RemoteCategoryItem.serializer())
    }

    suspend fun patchCategory(
        ownerId: String,
        categoryId: String,
        body: RemoteCategoryPatch,
    ): Outcome<RemoteCategoryItem> {
        val encoded = json.encodeToString(RemoteCategoryPatch.serializer(), body)
        return send(ownerId, "PATCH", "/v1/categories/$categoryId", encoded, null,
            RemoteCategoryItem.serializer())
    }

    suspend fun deleteCategory(
        ownerId: String,
        categoryId: String,
        reassignTo: String?,
    ): Outcome<Unit> {
        val base = config.endpoint("/v1/categories/$categoryId")
            ?: return Outcome.Failure(AppError.Configuration("backend_not_configured"))
        val url = base.toHttpUrl().newBuilder()
            .apply { reassignTo?.takeIf { it.isNotBlank() }?.let { addQueryParameter("reassignTo", it) } }
            .build()
        return requestNoContent(ownerId, Request.Builder().url(url).delete())
    }

    suspend fun latestAnalysis(ownerId: String, monthId: String): Outcome<RemoteAnalysis> =
        get(ownerId, "/v1/analyses/$monthId", RemoteAnalysis.serializer())

    suspend fun createImportJob(
        ownerId: String,
        monthId: String,
        templateVersion: String,
    ): Outcome<RemoteImportJob> {
        val body = json.encodeToString(
            RemoteImportJobCreate.serializer(),
            RemoteImportJobCreate(monthId, templateVersion),
        )
        return send(ownerId, "POST", "/v1/import-jobs", body, null, RemoteImportJob.serializer())
    }

    suspend fun importBatch(
        ownerId: String,
        jobId: String,
        body: RemoteImportBatch,
    ): Outcome<RemoteImportCounts> {
        val encoded = json.encodeToString(RemoteImportBatch.serializer(), body)
        return send(ownerId, "POST", "/v1/import-jobs/$jobId/batches", encoded, null,
            RemoteImportCounts.serializer())
    }

    suspend fun finalizeImportJob(ownerId: String, jobId: String, state: String, errorCode: String? = null):
        Outcome<RemoteImportJob> {
        val body = json.encodeToString(
            RemoteImportFinalize.serializer(), RemoteImportFinalize(state, errorCode),
        )
        return send(ownerId, "PATCH", "/v1/import-jobs/$jobId", body, null, RemoteImportJob.serializer())
    }

    suspend fun createCategoryJobs(ownerId: String, transactionIds: List<String>):
        Outcome<RemoteCategoryJobs> {
        val body = json.encodeToString(RemoteCategoryJobsRequest.serializer(), RemoteCategoryJobsRequest(transactionIds))
        return send(ownerId, "POST", "/v1/category-jobs", body, null, RemoteCategoryJobs.serializer())
    }

    suspend fun enqueueCategoryJobs(ownerId: String, transactionIds: List<String>):
        Outcome<RemoteCategoryEnqueue> {
        val body = json.encodeToString(RemoteCategoryJobsRequest.serializer(), RemoteCategoryJobsRequest(transactionIds))
        return send(ownerId, "POST", "/v1/category-jobs/enqueue", body, null, RemoteCategoryEnqueue.serializer())
    }

    suspend fun drainCategoryQueue(ownerId: String): Outcome<RemoteCategoryDrain> =
        send(ownerId, "POST", "/v1/category-jobs/drain",
            json.encodeToString(RemoteEmptyBody.serializer(), RemoteEmptyBody()), null,
            RemoteCategoryDrain.serializer())

    suspend fun requestDeletion(
        ownerId: String,
        password: String,
        confirmation: String = "DELETE_WORKSPACE",
    ): Outcome<RemoteDeletion> {
        val encoded = json.encodeToString(
            RemoteDeletionCreate.serializer(),
            RemoteDeletionCreate(password = password, confirmation = confirmation),
        )
        return send(ownerId, "POST", "/v1/deletion-requests", encoded, null,
            RemoteDeletion.serializer())
    }

    suspend fun deletionReceipt(receipt: String): Outcome<RemoteDeletion> {
        val endpoint = config.endpoint("/v1/deletion-receipts/$receipt")
            ?: return Outcome.Failure(AppError.Configuration("backend_not_configured"))
        return withContext(ioDispatcher) {
            execute(Request.Builder().url(endpoint).get().build(), RemoteDeletion.serializer())
        }
    }

    // Gmail OAuth link (contract §1–§4, backend repo 0037-Backend-Financial). The Android client
    // only starts the flow and reads the projection; tokens never reach this device.
    suspend fun gmailStatus(ownerId: String): Outcome<RemoteGmailStatus> =
        get(ownerId, "/v1/gmail/status", RemoteGmailStatus.serializer())

    suspend fun gmailConnectStart(
        ownerId: String,
        reconnect: Boolean = false,
    ): Outcome<RemoteGmailConnectStart> {
        val encoded = json.encodeToString(
            RemoteGmailConnectRequest.serializer(),
            RemoteGmailConnectRequest(reconnect = reconnect),
        )
        return send(ownerId, "POST", "/v1/gmail/connect/start", encoded, null,
            RemoteGmailConnectStart.serializer())
    }

    suspend fun gmailDisconnect(ownerId: String): Outcome<RemoteGmailDisconnect> =
        send(ownerId, "POST", "/v1/gmail/disconnect", "{}", null,
            RemoteGmailDisconnect.serializer())

    /**
     * `POST /v1/gmail/sync` (contract §9.3). 202 answers both for a fresh enqueue and for a request
     * that joined an active job; only 429/409/503 are failures, and the 429 body is read for
     * `retryAfterSeconds` so the sheet can say how long the cooldown lasts.
     */
    suspend fun gmailSync(ownerId: String): Outcome<RemoteGmailSync> =
        send(
            ownerId = ownerId,
            method = "POST",
            path = "/v1/gmail/sync",
            body = "{}",
            idempotencyKey = null,
            deserializer = RemoteGmailSync.serializer(),
            mapErrorBody = ::gmailSyncError,
        )

    private suspend fun <T> get(
        ownerId: String,
        path: String,
        deserializer: DeserializationStrategy<T>,
        mapError: (Int, String?) -> AppError = ::backendError,
    ): Outcome<T> {
        val endpoint = config.endpoint(path)
            ?: return Outcome.Failure(AppError.Configuration("backend_not_configured"))
        return request(ownerId, Request.Builder().url(endpoint).get(), deserializer, mapError)
    }

    private suspend fun <T> send(
        ownerId: String,
        method: String,
        path: String,
        body: String,
        idempotencyKey: String?,
        deserializer: DeserializationStrategy<T>,
        mapError: (Int, String?) -> AppError = ::backendError,
        /** Sees the raw error body; only used where a field beyond `code` matters (cooldown). */
        mapErrorBody: ((Int, String) -> AppError)? = null,
    ): Outcome<T> {
        val endpoint = config.endpoint(path)
            ?: return Outcome.Failure(AppError.Configuration("backend_not_configured"))
        val builder = Request.Builder()
            .url(endpoint)
            .method(method, body.toRequestBody(JSON_MEDIA_TYPE))
            .header("content-type", JSON_MEDIA_TYPE.toString())
            .header("accept", JSON_MEDIA_TYPE.toString())
        idempotencyKey?.let { builder.header("Idempotency-Key", it) }
        return request(ownerId, builder, deserializer, mapError, mapErrorBody)
    }

    /** Same auth/refresh handling as [request], for endpoints that answer 204 without a body. */
    private suspend fun requestNoContent(
        ownerId: String,
        builder: Request.Builder,
        mapError: (Int, String?) -> AppError = ::backendError,
    ): Outcome<Unit> = withContext(ioDispatcher) {
        var refreshed = false
        var rejectedToken: String? = null
        while (true) {
            val tokenResult = if (!refreshed) sessions.token(ownerId) else sessions.renew(ownerId, rejectedToken)
            if (tokenResult is Outcome.Failure) {
                return@withContext Outcome.Failure(tokenResult.error)
            }
            val token = (tokenResult as Outcome.Success).value
            val request = builder.header("authorization", "Bearer $token").build()
            val response = try {
                client.newCall(request).execute().use { call ->
                    ApiResponse(call.code, call.body?.string().orEmpty())
                }
            } catch (error: IOException) {
                SafeLogger.failure(TAG, "api_network_failed")
                return@withContext Outcome.Failure(AppError.Network("backend_unreachable"))
            }
            if (response.code == 401 && !refreshed) {
                refreshed = true
                rejectedToken = token
                continue
            }
            if (response.code !in 200..299) {
                val machine = runCatching {
                    json.decodeFromString(BackendErrorBody.serializer(), response.body).code
                }.getOrNull()
                return@withContext Outcome.Failure(mapError(response.code, machine))
            }
            return@withContext Outcome.Success(Unit)
        }
        @Suppress("UNREACHABLE_CODE")
        Outcome.Success(Unit)
    }

    private suspend fun <T> request(
        ownerId: String,
        builder: Request.Builder,
        deserializer: DeserializationStrategy<T>,
        mapError: (Int, String?) -> AppError = ::backendError,
        mapErrorBody: ((Int, String) -> AppError)? = null,
    ): Outcome<T> = withContext(ioDispatcher) {
        var refreshed = false
        var rejectedToken: String? = null
        while (true) {
            val tokenResult = if (!refreshed) sessions.token(ownerId) else sessions.renew(ownerId, rejectedToken)
            if (tokenResult is Outcome.Failure) {
                return@withContext Outcome.Failure(tokenResult.error)
            }
            val token = (tokenResult as Outcome.Success).value
            val request = builder.header("authorization", "Bearer $token").build()
            val response = try {
                client.newCall(request).execute().use { call ->
                    ApiResponse(call.code, call.body?.string().orEmpty())
                }
            } catch (error: IOException) {
                SafeLogger.failure(TAG, "api_network_failed")
                return@withContext Outcome.Failure(AppError.Network("backend_unreachable"))
            } catch (error: Exception) {
                SafeLogger.failure(TAG, "api_invalid_response")
                return@withContext Outcome.Failure(AppError.Network("backend_invalid_response"))
            }
            if (response.code == 401 && !refreshed) {
                refreshed = true
                rejectedToken = token
                continue
            }
            if (response.code !in 200..299) {
                val machine = runCatching {
                    json.decodeFromString(BackendErrorBody.serializer(), response.body).code
                }.getOrNull()
                return@withContext Outcome.Failure(
                    mapErrorBody?.invoke(response.code, response.body)
                        ?: mapError(response.code, machine),
                )
            }
            val parsed = runCatching {
                json.decodeFromString(deserializer, response.body)
            }.getOrNull()
            return@withContext if (parsed == null) {
                Outcome.Failure(AppError.Parsing("backend_invalid_response"))
            } else {
                Outcome.Success(parsed)
            }
        }
        @Suppress("UNREACHABLE_CODE")
        return@withContext Outcome.Failure(AppError.Unknown("unreachable"))
    }

    private suspend fun <T> execute(request: Request, deserializer: DeserializationStrategy<T>): Outcome<T> {
        val response = try {
            client.newCall(request).execute().use { call ->
                ApiResponse(call.code, call.body?.string().orEmpty())
            }
        } catch (error: IOException) {
            return Outcome.Failure(AppError.Network("backend_unreachable"))
        }
        if (response.code !in 200..299) {
            val code = runCatching {
                json.decodeFromString(BackendErrorBody.serializer(), response.body).code
            }.getOrNull()
            return Outcome.Failure(backendError(response.code, code))
        }
        val parsed = runCatching { json.decodeFromString(deserializer, response.body) }.getOrNull()
        return if (parsed == null) Outcome.Failure(AppError.Parsing("backend_invalid_response"))
        else Outcome.Success(parsed)
    }

    /**
     * Gmail sync failures carry machine codes the sheet treats differently: a cooldown must show
     * its remaining seconds, a missing link must hide the server section, and a disabled backend
     * is configuration, not a transient outage.
     */
    private fun gmailSyncError(status: Int, body: String): AppError {
        val parsed = runCatching {
            json.decodeFromString(RemoteGmailSyncError.serializer(), body)
        }.getOrNull()
        return when {
            status == 429 && parsed?.code == AppErrorRules.SYNC_COOLDOWN ->
                AppError.RateLimited(
                    debugDetail = AppErrorRules.SYNC_COOLDOWN,
                    retryAfterSeconds = parsed.retryAfterSeconds?.takeIf { it > 0 },
                )
            status == 429 -> AppError.RateLimited(parsed?.code ?: "sync_cooldown")
            status == 409 && parsed?.code == AppErrorRules.GMAIL_NOT_CONNECTED ->
                AppError.Validation(AppErrorRules.GMAIL_NOT_CONNECTED)
            status == 503 && parsed?.code == AppErrorRules.GMAIL_DISABLED ->
                AppError.Validation(AppErrorRules.GMAIL_DISABLED)
            else -> backendError(status, parsed?.code)
        }
    }

    private data class ApiResponse(val code: Int, val body: String)

    private companion object {
        const val TAG = "PamApi"
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}

@Serializable
data class RemoteSummary(
    val monthId: String,
    val inputRevision: Int,
    val coverage: String,
    val grossInflowMinor: Long,
    val grossOutflowMinor: Long,
    val netMinor: Long,
    val classifiedIncomeMinor: Long,
    val classifiedExpenseMinor: Long,
    val transactionCount: Int,
    val manualEntryCount: Int,
    val uncategorizedCount: Int,
    val categoryTotals: List<RemoteCategoryTotal> = emptyList(),
    val generatedAt: String? = null,
    val emailDerived: Boolean = true,
    val currentRevision: Int = 0,
    val freshness: String = "",
)

@Serializable
data class RemoteCategoriesResponse(
    val categories: List<RemoteCategoryItem> = emptyList(),
    val nextCursor: String? = null,
    val search: RemoteSearchMeta? = null,
)

@Serializable
data class RemoteCategoryItem(
    val id: String,
    val code: String,
    val label: String? = null,
    val kind: String,
    val revision: Int = 0,
    val color: String? = null,
    val isBuiltin: Boolean = false,
    /** The server sends an explicit JSON null for categories without attributes. */
    val attributes: Map<String, String>? = null,
)

@Serializable
data class RemoteCategoryWrite(
    val code: String,
    val label: String,
    val kind: String,
    val color: String? = null,
    val attributes: Map<String, String> = emptyMap(),
)

@Serializable
data class RemoteCategoryPatch(
    val label: String? = null,
    val kind: String? = null,
    val color: String? = null,
    val attributes: Map<String, String>? = null,
    val revision: Int,
)

@Serializable
data class RemoteCategoryTotal(
    val categoryCode: String,
    val label: String? = null,
    val amountMinor: Long,
    val count: Int = 0,
)

@Serializable
data class RemoteTransaction(
    val id: String,
    val categoryId: String? = null,
    val categoryCode: String = "",
    val categoryKind: String? = null,
    val direction: String,
    val source: String = "MANUAL",
    val amountMinor: Long,
    val currency: String = "IDR",
    val eventAt: String,
    val monthId: String,
    val description: String? = null,
    val merchantLabel: String? = null,
    val note: String? = null,
    val attributes: JsonObject? = null,
    val parserVersion: String? = null,
    val parserConfidence: Double? = null,
    val categoryConfidence: Double? = null,
    val categoryOrigin: String? = null,
    val categoryRuleVersion: String? = null,
    val categoryReason: String? = null,
    val categoryTaxonomyVersion: String? = null,
    val linkedRefundOf: String? = null,
    val deletedAt: String? = null,
    val fingerprint: String? = null,
    @SerialName("isInternalTransfer") val isInternalTransfer: Boolean = false,
    val revision: Int,
    val createdAt: String? = null,
    val updatedAt: String? = null,
)

@Serializable
data class RemoteTransactionPatch(
    val categoryId: String? = null,
    val description: String? = null,
    val merchantLabel: String? = null,
    val note: String? = null,
    /** Null keeps the stored attributes; a map (including an empty one) replaces them. */
    val attributes: Map<String, String>? = null,
    val revision: Int,
    /** Merchant-repair identity proof; only sent by the repair path. */
    val expectedFingerprint: String? = null,
    /** Opaque `UIDVALIDITY:UID`; adopted once when the server row predates source references. */
    val sourceRef: String? = null,
)

@Serializable
data class RemoteTransactionPage(
    val items: List<RemoteTransaction> = emptyList(),
    val nextCursor: String? = null,
    val search: RemoteSearchMeta? = null,
)

@Serializable
data class RemoteSearchMeta(
    val scanned: Int = 0,
    val truncated: Boolean = false,
)

@Serializable
data class RemoteTransactionCreate(
    val direction: String,
    val amountMinor: Long,
    val currency: String = "IDR",
    val eventAt: String,
    val categoryId: String? = null,
    val description: String? = null,
    val merchantLabel: String? = null,
    val note: String? = null,
    val attributes: Map<String, String>? = null,
)

@Serializable
data class RemoteAnalysis(
    val monthId: String = "",
    val inputRevision: Int = 0,
    val currentRevision: Int = 0,
    val freshness: String = "STALE",
    val status: String = "AVAILABLE",
    val promptVersion: String = "",
    // The backend serialises absent GET fields as explicit JSON null (e.g. `"suggestions": null`
    // for an analysis with no suggestions); nullable here so decoding does not fail.
    val observations: List<RemoteObservation>? = null,
    val suggestions: List<RemoteSuggestion>? = null,
    val limitations: List<String>? = null,
)

@Serializable
data class RemoteObservation(val text: String, val metricKeys: List<String> = emptyList())

@Serializable
data class RemoteSuggestion(
    val text: String,
    val metricKeys: List<String> = emptyList(),
    val categoryCode: String? = null,
    val suggestedAmountMinor: Long? = null,
)

@Serializable
data class RemoteImportJobCreate(val monthId: String, val templateVersion: String)

@Serializable
data class RemoteImportCandidate(
    val fingerprint: String,
    val status: String,
    val reason: String? = null,
    val direction: String? = null,
    val amountMinor: Long? = null,
    val eventAt: String? = null,
    val categoryId: String? = null,
    val parserVersion: String? = null,
    val confidence: Double? = null,
    val description: String? = null,
    val merchantLabel: String? = null,
)

@Serializable
data class RemoteImportBatch(
    val batchKey: String,
    val pageOrdinal: Int,
    val candidates: List<RemoteImportCandidate>,
)

@Serializable
data class RemoteImportFinalize(val state: String, val errorCode: String? = null)

@Serializable
data class RemoteImportJob(
    val id: String,
    val monthId: String,
    val state: String,
    val matched: Int = 0,
    val imported: Int = 0,
    val duplicates: Int = 0,
    val skipped: Int = 0,
    val unresolved: Int = 0,
    val errorCode: String? = null,
)

@Serializable
data class RemoteImportCounts(
    val batchKey: String = "",
    val monthId: String = "",
    val inputRevision: Int = 0,
    val matched: Int = 0,
    val imported: Int = 0,
    val duplicates: Int = 0,
    val skipped: Int = 0,
    val unresolved: Int = 0,
    val jobState: String = "",
)

@Serializable
data class RemoteCategoryJobsRequest(val transactionIds: List<String>)

@Serializable
data class RemoteCategoryJob(
    val id: String,
    val transactionId: String,
    val expectedRevision: Int = 0,
    val state: String,
    val categoryCode: String? = null,
    val confidence: Double? = null,
    val provider: String? = null,
    val model: String? = null,
    val promptVersion: String? = null,
    val taxonomyVersion: String? = null,
    val reasonCode: String? = null,
    val errorCode: String? = null,
)

@Serializable
data class RemoteCategoryJobs(val jobs: List<RemoteCategoryJob> = emptyList())

@Serializable
data class RemoteCategoryEnqueue(val queued: Int = 0, val pending: Int = 0)

@Serializable
data class RemoteCategoryDrain(
    val state: String,
    val drained: Int = 0,
    val pending: Int = 0,
    val nextDueAt: String? = null,
)

/** The drain endpoint takes no arguments but still needs a JSON body to pass the decoder. */
@Serializable
class RemoteEmptyBody

@Serializable
data class RemoteDeletionCreate(val password: String, val confirmation: String)

@Serializable
data class RemoteDeletion(
    val id: String,
    val state: String,
    val slaDays: Int = 7,
    val receiptToken: String? = null,
    val requestedAt: String? = null,
    val completedAt: String? = null,
    val aggregatesDeleted: Int = 0,
)

// Kept for callers that need the raw JSON object shape (dynamic attributes).
typealias RemoteAttributes = JsonObject
