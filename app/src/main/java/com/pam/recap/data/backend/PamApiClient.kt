package com.pam.recap.data.backend

import com.pam.recap.core.di.IoDispatcher
import com.pam.recap.core.result.AppError
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

    suspend fun categories(ownerId: String): Outcome<RemoteCategoriesResponse> =
        get(ownerId, "/v1/categories", RemoteCategoriesResponse.serializer())

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

    suspend fun budget(ownerId: String, monthId: String): Outcome<RemoteBudgetPlan> =
        get(ownerId, "/v1/budget-plans/$monthId", RemoteBudgetPlan.serializer())

    suspend fun saveBudget(ownerId: String, monthId: String, body: RemoteBudgetWrite):
        Outcome<RemoteBudgetPlan> {
        val encoded = json.encodeToString(RemoteBudgetWrite.serializer(), body)
        return send(ownerId, "PUT", "/v1/budget-plans/$monthId", encoded, null,
            RemoteBudgetPlan.serializer())
    }

    suspend fun latestAnalysis(ownerId: String, monthId: String): Outcome<RemoteAnalysis> =
        get(ownerId, "/v1/analyses/$monthId", RemoteAnalysis.serializer())

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

    private suspend fun <T> get(
        ownerId: String,
        path: String,
        deserializer: DeserializationStrategy<T>,
    ): Outcome<T> {
        val endpoint = config.endpoint(path)
            ?: return Outcome.Failure(AppError.Configuration("backend_not_configured"))
        return request(ownerId, Request.Builder().url(endpoint).get(), deserializer)
    }

    private suspend fun <T> send(
        ownerId: String,
        method: String,
        path: String,
        body: String,
        idempotencyKey: String?,
        deserializer: DeserializationStrategy<T>,
    ): Outcome<T> {
        val endpoint = config.endpoint(path)
            ?: return Outcome.Failure(AppError.Configuration("backend_not_configured"))
        val builder = Request.Builder()
            .url(endpoint)
            .method(method, body.toRequestBody(JSON_MEDIA_TYPE))
            .header("content-type", JSON_MEDIA_TYPE.toString())
            .header("accept", JSON_MEDIA_TYPE.toString())
        idempotencyKey?.let { builder.header("Idempotency-Key", it) }
        return request(ownerId, builder, deserializer)
    }

    private suspend fun <T> request(
        ownerId: String,
        builder: Request.Builder,
        deserializer: DeserializationStrategy<T>,
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
                return@withContext Outcome.Failure(backendError(response.code, machine))
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
)

@Serializable
data class RemoteCategoriesResponse(val categories: List<RemoteCategoryItem> = emptyList())

@Serializable
data class RemoteCategoryItem(
    val id: String,
    val code: String,
    val label: String? = null,
    val kind: String,
    val revision: Int = 0,
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
    val categoryOrigin: String? = null,
    val linkedRefundOf: String? = null,
    val fingerprint: String? = null,
    @SerialName("isInternalTransfer") val isInternalTransfer: Boolean = false,
    val revision: Int,
    val createdAt: String? = null,
    val updatedAt: String? = null,
)

@Serializable
data class RemoteTransactionPage(
    val items: List<RemoteTransaction> = emptyList(),
    val nextCursor: String? = null,
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
data class RemoteBudgetPlan(
    val monthId: String,
    val expectedIncomeMinor: Long,
    val plannedSavingsMinor: Long,
    val limits: List<RemoteBudgetLimit> = emptyList(),
    val revision: Int,
    val gapMinor: Long = 0,
)

@Serializable
data class RemoteBudgetLimit(val categoryId: String, val amountMinor: Long)

@Serializable
data class RemoteBudgetWrite(
    val expectedIncomeMinor: Long,
    val plannedSavingsMinor: Long,
    val limits: List<RemoteBudgetLimit> = emptyList(),
    val revision: Int,
)

@Serializable
data class RemoteAnalysis(
    val monthId: String = "",
    val inputRevision: Int = 0,
    val currentRevision: Int = 0,
    val freshness: String = "STALE",
    val status: String = "AVAILABLE",
    val promptVersion: String = "",
    val observations: List<RemoteObservation> = emptyList(),
    val suggestions: List<RemoteSuggestion> = emptyList(),
    val limitations: List<String> = emptyList(),
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
