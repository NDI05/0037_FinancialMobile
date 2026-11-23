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

    suspend fun deleteBudget(ownerId: String, monthId: String): Outcome<Unit> {
        val endpoint = config.endpoint("/v1/budget-plans/$monthId")
            ?: return Outcome.Failure(AppError.Configuration("backend_not_configured"))
        return requestNoContent(ownerId, Request.Builder().url(endpoint).delete())
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

    suspend fun attachments(
        ownerId: String,
        transactionId: String,
        mimeType: String?,
        query: String,
        limit: Int,
        cursor: String?,
    ): Outcome<RemoteAttachmentPage> {
        val base = config.endpoint("/v1/attachments")
            ?: return Outcome.Failure(AppError.Configuration("backend_not_configured"))
        val url = base.toHttpUrl().newBuilder()
            .addQueryParameter("transactionId", transactionId)
            .addQueryParameter("limit", limit.coerceIn(1, 100).toString())
            .apply {
                mimeType?.takeIf { it.isNotBlank() }?.let { addQueryParameter("mime", it) }
                query.trim().takeIf { it.isNotEmpty() }?.let { addQueryParameter("q", it) }
                cursor?.takeIf { it.isNotBlank() }?.let { addQueryParameter("cursor", it) }
            }
            .build()
        return request(ownerId, Request.Builder().url(url).get(), RemoteAttachmentPage.serializer())
    }

    suspend fun createAttachment(ownerId: String, body: RemoteAttachmentWrite): Outcome<RemoteAttachment> {
        val encoded = json.encodeToString(RemoteAttachmentWrite.serializer(), body)
        return send(ownerId, "POST", "/v1/attachments", encoded, null, RemoteAttachment.serializer())
    }

    suspend fun patchAttachment(
        ownerId: String,
        attachmentId: String,
        body: RemoteAttachmentPatch,
    ): Outcome<RemoteAttachment> {
        val encoded = json.encodeToString(RemoteAttachmentPatch.serializer(), body)
        return send(ownerId, "PATCH", "/v1/attachments/$attachmentId", encoded, null,
            RemoteAttachment.serializer())
    }

    suspend fun deleteAttachment(
        ownerId: String,
        attachmentId: String,
        revision: Int?,
    ): Outcome<Unit> {
        val endpoint = config.endpoint("/v1/attachments/$attachmentId")
            ?: return Outcome.Failure(AppError.Configuration("backend_not_configured"))
        val builder = Request.Builder().url(endpoint).delete()
        revision?.let { builder.header("If-Match", "\"$it\"") }
        return requestNoContent(ownerId, builder)
    }

    suspend fun budget(ownerId: String, monthId: String): Outcome<RemoteBudgetPlan> =
        get(ownerId, "/v1/budget-plans/$monthId", RemoteBudgetPlan.serializer())

    suspend fun saveBudget(ownerId: String, monthId: String, body: RemoteBudgetWrite):
        Outcome<RemoteBudgetPlan> {
        val encoded = json.encodeToString(RemoteBudgetWrite.serializer(), body)
        return send(ownerId, "PUT", "/v1/budget-plans/$monthId", encoded, null,
            RemoteBudgetPlan.serializer())
    }

    /** C15 inclusive period search over stored plans, newest first. */
    suspend fun budgetPlans(
        ownerId: String,
        from: String,
        to: String,
        limit: Int = 50,
        cursor: String? = null,
    ): Outcome<RemoteBudgetPlanPage> {
        val base = config.endpoint("/v1/budget-plans")
            ?: return Outcome.Failure(AppError.Configuration("backend_not_configured"))
        val url = base.toHttpUrl().newBuilder()
            .addQueryParameter("from", from)
            .addQueryParameter("to", to)
            .addQueryParameter("limit", limit.coerceIn(1, 100).toString())
            .apply { cursor?.takeIf { it.isNotBlank() }?.let { addQueryParameter("cursor", it) } }
            .build()
        return request(ownerId, Request.Builder().url(url).get(), RemoteBudgetPlanPage.serializer())
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

    // --- S4.2 admin surface (contract §6). Every method here reuses the one-refresh replay of the
    // shared request helpers; only the HTTP → AppError mapping is admin-specific so the UI can tell
    // `invalid_cursor`, `revision_conflict`, `last_admin` and `invitation_expired` apart.

    suspend fun currentRole(ownerId: String): Outcome<RemoteAuthMe> =
        get(ownerId, "/v1/me", RemoteAuthMe.serializer(), ::adminBackendError)

    suspend fun adminAccounts(
        ownerId: String,
        q: String? = null,
        role: String? = null,
        status: String? = null,
        limit: Int = 50,
        cursor: String? = null,
    ): Outcome<RemoteAdminAccountPage> {
        val base = config.endpoint("/v1/admin/accounts")
            ?: return Outcome.Failure(AppError.Configuration("backend_not_configured"))
        val url = base.toHttpUrl().newBuilder()
            .addQueryParameter("limit", limit.coerceIn(1, 100).toString())
            .apply {
                q?.trim()?.takeIf { it.isNotEmpty() }?.let { addQueryParameter("q", it) }
                role?.takeIf { it.isNotBlank() }?.let { addQueryParameter("role", it.uppercase()) }
                status?.takeIf { it.isNotBlank() }?.let { addQueryParameter("status", it.uppercase()) }
                cursor?.takeIf { it.isNotBlank() }?.let { addQueryParameter("cursor", it) }
            }
            .build()
        return request(ownerId, Request.Builder().url(url).get(),
            RemoteAdminAccountPage.serializer(), ::adminBackendError)
    }

    suspend fun adminAccount(ownerId: String, targetId: String): Outcome<RemoteAdminAccount> =
        get(ownerId, "/v1/admin/accounts/$targetId", RemoteAdminAccount.serializer(),
            ::adminBackendError)

    suspend fun adminInvite(
        ownerId: String,
        email: String,
        role: String,
        expiresInHours: Int = 72,
    ): Outcome<RemoteAdminInvitationResult> {
        val encoded = json.encodeToString(
            RemoteAdminInvitationCreate.serializer(),
            RemoteAdminInvitationCreate(email = email.trim(), role = role.uppercase(),
                expiresInHours = expiresInHours),
        )
        return send(ownerId, "POST", "/v1/admin/invitations", encoded, null,
            RemoteAdminInvitationResult.serializer(), ::adminBackendError)
    }

    suspend fun adminUpdateAccount(
        ownerId: String,
        targetId: String,
        patch: RemoteAdminAccountPatch,
    ): Outcome<RemoteAdminAccount> {
        val encoded = json.encodeToString(RemoteAdminAccountPatch.serializer(), patch)
        return send(ownerId, "PATCH", "/v1/admin/accounts/$targetId", encoded, null,
            RemoteAdminAccount.serializer(), ::adminBackendError)
    }

    /**
     * Soft-delete: the backend reads the expected revision from `?revision=` and, when present,
     * from `If-Match`. Both are sent so a proxy that strips one header cannot turn the CAS into an
     * unconditional write.
     */
    suspend fun adminDeactivateAccount(
        ownerId: String,
        targetId: String,
        revision: Int,
    ): Outcome<RemoteAdminAccount> {
        val base = config.endpoint("/v1/admin/accounts/$targetId")
            ?: return Outcome.Failure(AppError.Configuration("backend_not_configured"))
        val url = base.toHttpUrl().newBuilder()
            .addQueryParameter("revision", revision.toString())
            .build()
        val builder = Request.Builder()
            .url(url)
            .delete()
            .header("If-Match", "\"$revision\"")
        return request(ownerId, builder, RemoteAdminAccount.serializer(), ::adminBackendError)
    }

    suspend fun adminAudit(
        ownerId: String,
        targetOwnerId: String? = null,
        action: String? = null,
        limit: Int = 50,
        cursor: String? = null,
    ): Outcome<RemoteAdminAuditPage> {
        val base = config.endpoint("/v1/admin/audit")
            ?: return Outcome.Failure(AppError.Configuration("backend_not_configured"))
        val url = base.toHttpUrl().newBuilder()
            .addQueryParameter("limit", limit.coerceIn(1, 100).toString())
            .apply {
                targetOwnerId?.trim()?.takeIf { it.isNotEmpty() }
                    ?.let { addQueryParameter("targetOwnerId", it) }
                action?.trim()?.takeIf { it.isNotEmpty() }?.let { addQueryParameter("action", it) }
                cursor?.takeIf { it.isNotBlank() }?.let { addQueryParameter("cursor", it) }
            }
            .build()
        return request(ownerId, Request.Builder().url(url).get(),
            RemoteAdminAuditPage.serializer(), ::adminBackendError)
    }

    /**
     * Admin-specific taxonomy (contract §6): the machine `code` is preserved so the UI can reset a
     * stale cursor, keep an edit draft on a revision conflict and name the last-admin guard. The
     * `debugDetail` stays a technical identifier; messages and tokens are never logged.
     */
    private fun adminBackendError(status: Int, code: String?): AppError = when {
        status == 401 -> AppError.Unauthorized("backend_401")
        status == 403 -> AppError.Unauthorized(
            code?.takeIf { it != "forbidden" } ?: "admin_required",
        )
        status == 404 -> AppError.NotFound("backend_404")
        status == 400 && code == "invalid_cursor" -> AppError.Validation("invalid_cursor")
        status == 400 -> AppError.Validation(code ?: "invalid_request")
        status == 409 -> AppError.Conflict(code ?: "backend_409")
        status == 410 -> AppError.Conflict(code ?: "invitation_expired")
        status >= 500 -> AppError.Network("backend_$status")
        else -> AppError.Network("backend_$status")
    }

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
    ): Outcome<T> {
        val endpoint = config.endpoint(path)
            ?: return Outcome.Failure(AppError.Configuration("backend_not_configured"))
        val builder = Request.Builder()
            .url(endpoint)
            .method(method, body.toRequestBody(JSON_MEDIA_TYPE))
            .header("content-type", JSON_MEDIA_TYPE.toString())
            .header("accept", JSON_MEDIA_TYPE.toString())
        idempotencyKey?.let { builder.header("Idempotency-Key", it) }
        return request(ownerId, builder, deserializer, mapError)
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
                return@withContext Outcome.Failure(mapError(response.code, machine))
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
    val attributes: Map<String, String> = emptyMap(),
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
data class RemoteAttachment(
    val id: String,
    val transactionId: String,
    val fileName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val sha256: String,
    val storageRef: String,
    val deviceRef: String? = null,
    val localFileId: String? = null,
    /** Older servers may serialize an empty metadata map as JSON null. */
    val attributes: Map<String, String>? = null,
    val revision: Int = 0,
    val createdAt: String? = null,
    val updatedAt: String? = null,
)

@Serializable
data class RemoteAttachmentWrite(
    val transactionId: String,
    val fileName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val sha256: String,
    val storageRef: String,
    val deviceRef: String? = null,
    val localFileId: String? = null,
    val attributes: Map<String, String> = emptyMap(),
)

@Serializable
data class RemoteAttachmentPatch(
    val revision: Int,
    val fileName: String? = null,
    val storageRef: String? = null,
    val attributes: Map<String, String>? = null,
)

@Serializable
data class RemoteAttachmentPage(
    val items: List<RemoteAttachment> = emptyList(),
    val nextCursor: String? = null,
)

@Serializable
data class RemoteBudgetPlan(
    val monthId: String,
    val expectedIncomeMinor: Long,
    val plannedSavingsMinor: Long,
    val limits: List<RemoteBudgetLimit> = emptyList(),
    val revision: Int,
    val gapMinor: Long = 0,
    /** Period-search rows carry the summed limits; single-plan reads may omit it. */
    val totalLimitMinor: Long = 0,
    /** Older servers omit the field; the domain treats absence as no attributes. */
    val attributes: Map<String, String>? = null,
)

@Serializable
data class RemoteBudgetLimit(val categoryId: String, val amountMinor: Long)

@Serializable
data class RemoteBudgetPlanPage(
    val plans: List<RemoteBudgetPlan> = emptyList(),
    val nextCursor: String? = null,
)

@Serializable
data class RemoteBudgetWrite(
    val expectedIncomeMinor: Long,
    val plannedSavingsMinor: Long,
    val limits: List<RemoteBudgetLimit> = emptyList(),
    /** The editor is authoritative: the sent map replaces the stored attribute set. */
    val attributes: Map<String, String> = emptyMap(),
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
