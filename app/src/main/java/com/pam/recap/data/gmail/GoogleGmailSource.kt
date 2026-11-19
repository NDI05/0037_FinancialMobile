package com.pam.recap.data.gmail

import android.content.Context
import android.util.Base64
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Task
import com.pam.recap.BuildConfig
import com.pam.recap.core.di.IoDispatcher
import com.pam.recap.core.result.AppError
import com.pam.recap.core.result.Outcome
import com.pam.recap.core.result.SafeLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import com.pam.recap.core.util.ActivityHolder
import com.pam.recap.domain.model.GmailConnection
import com.pam.recap.domain.model.GmailConnectionState
import com.pam.recap.domain.repository.GmailMailboxLabelProvider
import com.pam.recap.domain.repository.GmailMessageRef
import com.pam.recap.domain.repository.GmailPage
import com.pam.recap.domain.repository.GmailSource
import com.pam.recap.domain.repository.LivinQuery
import com.pam.recap.domain.repository.RawMessage
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.time.Instant

/**
 * Real Gmail adapter (FR-06…FR-21, NFR-26).
 *
 * - Authorization uses Google Identity Services `AuthorizationClient` for exactly
 *   [GmailConnection.READONLY_SCOPE]; the app never sees a password (FR-08).
 * - Requests go to `https://gmail.googleapis.com/gmail/v1/users/me/...` over plain TLS with no
 *   custom `TrustManager` (NFR-03).
 * - The access token lives in memory only: it is never persisted, logged or placed in an error
 *   (FR-08, NFR-14). A revoke failure is surfaced rather than swallowed (FR-46).
 * - MIME walking collects `text/plain` parts only; attachment payloads are never decoded or
 *   stored (FR-14).
 * - Enabled only when `BuildConfig.PAM_GOOGLE_WEB_CLIENT_ID` is non-blank (OI-01).
 */
@Singleton
class GoogleGmailSource @Inject constructor(
    @ApplicationContext private val context: Context,
    private val httpClient: OkHttpClient,
    private val json: Json,
    private val activityHolder: ActivityHolder,
    private val authBridge: GoogleAuthBridge,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : GmailSource, GmailMailboxLabelProvider {

    private val connection = MutableStateFlow(GmailConnectionState.NOT_CONNECTED)

    /** In-memory only. Cleared on disconnect, never written anywhere (NFR-14). */
    @Volatile
    private var accessToken: String? = null

    @Volatile
    private var mailbox: String? = null

    private val clientId: String = BuildConfig.PAM_GOOGLE_WEB_CLIENT_ID

    val isConfigured: Boolean get() = clientId.isNotBlank()

    override suspend fun authorize(): Outcome<Unit> {
        if (!isConfigured) {
            connection.value = GmailConnectionState.UNAVAILABLE
            return Outcome.Failure(AppError.Configuration("google_client_id_missing"))
        }
        val activity = activityHolder.current
            ?: return Outcome.Failure(AppError.PermissionRequired("no_foreground_activity"))

        connection.value = GmailConnectionState.AUTHORIZATION_PENDING
        return try {
            val request = AuthorizationRequest.Builder()
                .setRequestedScopes(listOf(Scope(GmailConnection.READONLY_SCOPE)))
                .build()
            val client = Identity.getAuthorizationClient(activity)
            val initial = client.authorize(request).awaitTask()

            val resolved: AuthorizationResult? = if (initial.hasResolution()) {
                val intentSender = initial.pendingIntent?.intentSender
                if (intentSender == null) {
                    null
                } else {
                    val data = authBridge.launch(activity, intentSender)
                    if (data == null) null else client.getAuthorizationResultFromIntent(data)
                }
            } else {
                initial
            }

            val token = resolved?.accessToken
            if (token.isNullOrBlank()) {
                connection.value = GmailConnectionState.REAUTHORIZATION_REQUIRED
                Outcome.Failure(AppError.PermissionRequired("google_authorization_cancelled"))
            } else {
                accessToken = token
                connection.value = GmailConnectionState.CONNECTED
                SafeLogger.event(TAG, "google_authorized")
                Outcome.Success(Unit)
            }
        } catch (error: ApiException) {
            connection.value = when (error.statusCode) {
                401, 403 -> GmailConnectionState.REAUTHORIZATION_REQUIRED
                else -> GmailConnectionState.UNAVAILABLE
            }
            SafeLogger.failure(TAG, "google_authorization_failed")
            Outcome.Failure(AppError.PermissionRequired("google_api_${error.statusCode}"))
        } catch (error: Throwable) {
            connection.value = GmailConnectionState.UNAVAILABLE
            SafeLogger.failure(TAG, "google_authorization_error")
            Outcome.Failure(AppError.Unknown(error::class.simpleName))
        }
    }

    override fun connectionState(): Flow<GmailConnectionState> = connection.asStateFlow()

    override suspend fun search(query: LivinQuery, pageToken: String?): Outcome<GmailPage> {
        val token = accessToken
            ?: return notAuthorized()

        val url = buildString {
            append(BASE_URL)
            append("/messages?maxResults=").append(query.pageSize)
            append("&q=").append(urlEncode(buildGmailQuery(query)))
            if (!pageToken.isNullOrBlank()) append("&pageToken=").append(urlEncode(pageToken))
        }

        return withContext(ioDispatcher) {
            val result = executeWithRetry(url, token)
            when (result) {
                is Outcome.Failure -> {
                    if (result.error is AppError.Unauthorized) {
                        accessToken = null
                        connection.value = GmailConnectionState.REAUTHORIZATION_REQUIRED
                    }
                    Outcome.Failure(result.error)
                }

                is Outcome.Success -> {
                    val page = runCatching { parsePage(result.value) }.getOrNull()
                    if (page == null) {
                        Outcome.Failure(AppError.Parsing("gmail_list_malformed"))
                    } else {
                        Outcome.Success(page)
                    }
                }
            }
        }
    }

    override suspend fun fetchMessage(id: String): Outcome<RawMessage> {
        val token = accessToken ?: return notAuthorized()
        val url = "$BASE_URL/messages/${urlEncode(id)}?format=full"

        return withContext(ioDispatcher) {
            when (val result = executeWithRetry(url, token)) {
                is Outcome.Failure -> {
                    if (result.error is AppError.Unauthorized) {
                        accessToken = null
                        connection.value = GmailConnectionState.REAUTHORIZATION_REQUIRED
                    }
                    Outcome.Failure(result.error)
                }

                is Outcome.Success -> {
                    val message = runCatching { parseMessage(id, result.value) }.getOrNull()
                    if (message == null) {
                        Outcome.Failure(AppError.Parsing("gmail_message_malformed"))
                    } else {
                        Outcome.Success(message)
                    }
                }
            }
        }
    }

    override suspend fun disconnect(): Outcome<Unit> = withContext(ioDispatcher) {
        val token = accessToken
        accessToken = null
        mailbox = null
        connection.value = GmailConnectionState.DISCONNECTED
        if (token.isNullOrBlank()) return@withContext Outcome.Success(Unit)

        // FR-46: revoke where supported and surface the failure instead of silently succeeding.
        val request = Request.Builder()
            .url("$REVOKE_URL?token=${urlEncode(token)}")
            .post(EMPTY_BODY)
            .build()
        runCatching {
            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    SafeLogger.event(TAG, "google_token_revoked")
                    Outcome.Success(Unit)
                } else {
                    Outcome.Failure(AppError.Network("google_revoke_${response.code}"))
                }
            }
        }.getOrElse { error ->
            SafeLogger.failure(TAG, "google_revoke_failed")
            Outcome.Failure(AppError.Network(error::class.simpleName))
        }
    }

    override fun mailboxLabel(): String? = mailbox

    // ---------------------------------------------------------------------------------------
    // HTTP
    // ---------------------------------------------------------------------------------------

    private fun notAuthorized(): Outcome.Failure {
        connection.value = GmailConnectionState.REAUTHORIZATION_REQUIRED
        return Outcome.Failure(AppError.PermissionRequired("gmail_not_authorized"))
    }

    private fun executeWithRetry(url: String, token: String): Outcome<String> {
        var attempt = 0
        var delayMillis = INITIAL_BACKOFF_MILLIS
        while (true) {
            val request = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $token")
                .header("Accept", "application/json")
                .build()

            val outcome = runCatching {
                httpClient.newCall(request).execute().use { response ->
                    when {
                        response.code == 401 || response.code == 403 ->
                            Outcome.Failure(AppError.Unauthorized("gmail_${response.code}"))

                        response.isSuccessful -> Outcome.Success(response.body?.string().orEmpty())

                        response.code == 429 || response.code >= 500 -> {
                            val retryAfter = response.header("Retry-After")?.toLongOrNull()
                            Outcome.Failure(
                                AppError.Network(
                                    "gmail_${response.code}" +
                                        (retryAfter?.let { ";retry_after=${it}s" } ?: ""),
                                ),
                            )
                        }

                        else -> Outcome.Failure(AppError.Network("gmail_${response.code}"))
                    }
                }
            }.getOrElse { error ->
                Outcome.Failure(AppError.Network(error::class.simpleName))
            }

            val failure = (outcome as? Outcome.Failure)?.error
            val retryable = failure is AppError.Network
            if (!retryable || attempt >= MAX_RETRIES) return outcome

            // NFR-05: at most two retries, exponential backoff, honour Retry-After.
            val retryAfterSeconds = failure.debugDetail
                ?.substringAfter(";retry_after=", "")
                ?.removeSuffix("s")
                ?.toLongOrNull()
            val waitMillis = retryAfterSeconds
                ?.takeIf { it > 0 }
                ?.times(TimeUnit.SECONDS.toMillis(1))
                ?: delayMillis
            Thread.sleep(waitMillis.coerceAtMost(MAX_BACKOFF_MILLIS))
            delayMillis *= 2
            attempt++
        }
    }

    private fun parseMessage(id: String, body: String): RawMessage {
        val root = json.parseToJsonElement(body).jsonObject
        val payload = root["payload"]?.jsonObject
        val extraction = MimeExtractor.collect(payload)
        return RawMessage(
            messageId = id,
            sender = headerValue(payload, "From"),
            subject = headerValue(payload, "Subject"),
            internalDate = root["internalDate"]?.jsonPrimitive?.content
                ?.toLongOrNull()
                ?.let { Instant.fromEpochMilliseconds(it) },
            textParts = extraction.textParts,
            attachmentCount = extraction.attachmentCount,
        )
    }

    private fun headerValue(payload: JsonObject?, name: String): String? =
        payload?.get("headers")?.jsonArray?.firstOrNull { header ->
            header.jsonObject["name"]?.jsonPrimitive?.content.equals(name, ignoreCase = true)
        }?.jsonObject?.get("value")?.jsonPrimitive?.content

    private fun parsePage(body: String): GmailPage {
        val root = json.parseToJsonElement(body).jsonObject
        val messages = root["messages"]?.jsonArray.orEmpty().mapNotNull { element ->
            val id = element.jsonObject["id"]?.jsonPrimitive?.content ?: return@mapNotNull null
            GmailMessageRef(id = id, internalDate = null)
        }
        return GmailPage(
            messages = messages,
            nextPageToken = root["nextPageToken"]?.jsonPrimitive?.content,
            resultSizeEstimate = root["resultSizeEstimate"]?.jsonPrimitive?.content?.toIntOrNull(),
        )
    }

    private fun buildGmailQuery(query: LivinQuery): String {
        val from = query.senderPatterns.joinToString(" OR ")
        val subject = query.subjectPatterns.joinToString(" OR ") { "\"$it\"" }
        val after = query.fromInclusive.epochSeconds
        val before = query.toExclusive.epochSeconds
        return "from:($from) subject:($subject) after:$after before:$before"
    }

    private fun urlEncode(value: String): String =
        java.net.URLEncoder.encode(value, Charsets.UTF_8.name())

    /** Recursively collects `text/plain` parts and counts attachments without decoding them. */
    private object MimeExtractor {

        data class Extraction(val textParts: List<String>, val attachmentCount: Int)

        fun collect(payload: JsonObject?): Extraction {
            if (payload == null) return Extraction(emptyList(), 0)
            val parts = mutableListOf<String>()
            var attachments = 0

            fun visit(part: JsonObject) {
                val mimeType = part["mimeType"]?.jsonPrimitive?.content.orEmpty()
                val filename = part["filename"]?.jsonPrimitive?.content.orEmpty()
                val hasBodyData = part["body"]?.jsonObject?.get("data") != null
                when {
                    filename.isNotEmpty() || part["body"]?.jsonObject?.get("attachmentId") != null -> {
                        attachments++
                    }

                    mimeType.startsWith("text/plain") && hasBodyData -> {
                        val data = part["body"]!!.jsonObject["data"]!!.jsonPrimitive.content
                        decodeBase64Url(data)?.let(parts::add)
                    }

                    else -> Unit
                }
                part["parts"]?.jsonArray?.forEach { child ->
                    (child as? JsonObject)?.let(::visit)
                }
            }

            visit(payload)
            return Extraction(parts, attachments)
        }

        private fun decodeBase64Url(data: String): String? = runCatching {
            String(Base64.decode(data, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING), Charsets.UTF_8)
        }.getOrNull()
    }

    private companion object {
        const val TAG = "GoogleGmail"
        const val BASE_URL = "https://gmail.googleapis.com/gmail/v1/users/me"
        const val REVOKE_URL = "https://oauth2.googleapis.com/revoke"
        const val MAX_RETRIES = 2
        const val INITIAL_BACKOFF_MILLIS = 400L
        const val MAX_BACKOFF_MILLIS = 4_000L
        val EMPTY_BODY = ByteArray(0).toRequestBody(null)
    }
}

/** Minimal `Task` -> coroutine bridge so no extra dependency is needed. */
internal suspend fun <T> Task<T>.awaitTask(): T = suspendCancellableCoroutine { continuation ->
    addOnSuccessListener { value -> if (continuation.isActive) continuation.resume(value) }
    addOnFailureListener { error -> if (continuation.isActive) continuation.resumeWithException(error) }
}
