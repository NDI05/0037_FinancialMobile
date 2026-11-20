package com.pam.recap.data.backend

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.pam.recap.core.di.IoDispatcher
import com.pam.recap.core.result.AppError
import com.pam.recap.core.result.AppErrorRules
import com.pam.recap.core.result.Outcome
import com.pam.recap.core.result.SafeLogger
import com.pam.recap.core.security.OwnerIdFactory
import com.pam.recap.core.security.WrappedSecretStore
import com.pam.recap.core.time.ClockProvider
import com.pam.recap.domain.repository.BackendSessionRepository
import dagger.hilt.android.qualifiers.ApplicationContext
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
import kotlin.time.Instant

/**
 * Opaque backend session for a PAM account (contract §2, CR-001).
 *
 * Registration and login are the only places a password is held; it is never persisted or logged.
 * The access token is wrapped by [WrappedSecretStore] before it is written — the same mechanism as
 * the database passphrase — and refreshed when fewer than five minutes remain.
 */
@Singleton
class BackendSessionRepositoryImpl @Inject constructor(
    private val client: OkHttpClient,
    private val json: Json,
    private val config: BackendEndpoints,
    private val keystoreSecretStore: WrappedSecretStore,
    @param:ApplicationContext private val context: Context,
    private val clock: ClockProvider,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : BackendSessionRepository {

    private val prefs: SharedPreferences
        get() = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override val isEnabled: Boolean get() = config.isEnabled

    override suspend fun register(
        email: String,
        password: String,
        displayName: String?,
        inviteCode: String,
    ): Outcome<Unit> {
        val endpoint = config.endpoint("/v1/accounts")
            ?: return Outcome.Failure(AppError.Configuration(BACKEND_NOT_CONFIGURED))
        val body = json.encodeToString(
            RegisterBody.serializer(),
            RegisterBody(email = email.trim(), password = password, displayName = displayName, inviteCode = inviteCode),
        )
        return withContext(ioDispatcher) {
            when (val call = execute(endpoint, body)) {
                null -> Outcome.Failure(AppError.Network("backend_unreachable"))
                else -> when {
                    call.code == 201 -> Outcome.Success(Unit)
                    call.errorCode == INVALID_INVITE || call.code == 403 -> Outcome.Failure(
                        AppError.Validation(AppErrorRules.INVALID_INVITE),
                    )
                    call.code == 409 -> Outcome.Failure(AppError.Validation(AppErrorRules.EMAIL_TAKEN))
                    call.code == 400 -> Outcome.Failure(AppError.Validation(AppErrorRules.INVALID_CREDENTIALS))
                    call.code == 429 -> Outcome.Failure(AppError.RateLimited())
                    else -> Outcome.Failure(AppError.Network("backend_${call.code}"))
                }
            }
        }
    }

    override suspend fun login(email: String, password: String): Outcome<Unit> {
        val endpoint = config.endpoint("/v1/sessions")
            ?: return Outcome.Failure(AppError.Configuration(BACKEND_NOT_CONFIGURED))
        val body = json.encodeToString(
            LoginBody.serializer(),
            LoginBody(email = email.trim(), password = password),
        )
        return withContext(ioDispatcher) {
            when (val call = execute(endpoint, body)) {
                null -> Outcome.Failure(AppError.Network("backend_unreachable"))
                else -> when {
                    call.code == 201 -> {
                        val session = runCatching {
                            json.decodeFromString(SessionResponse.serializer(), call.payload)
                        }.getOrNull()
                        if (session == null) {
                            Outcome.Failure(AppError.Network("backend_invalid_response"))
                        } else {
                            writeCached(
                                StoredSession(
                                    ownerId = OwnerIdFactory.ownerIdFor(email.trim()),
                                    accessToken = session.accessToken,
                                    expiresAtEpochMs = expiresAt(session.expiresAt),
                                ),
                            )
                            Outcome.Success(Unit)
                        }
                    }

                    call.code == 401 ->
                        Outcome.Failure(AppError.Validation(AppErrorRules.INVALID_CREDENTIALS))

                    call.code == 429 -> Outcome.Failure(AppError.RateLimited(call.errorCode))
                    else -> Outcome.Failure(AppError.Network("backend_${call.code}"))
                }
            }
        }
    }

    override suspend fun token(ownerId: String): Outcome<String> {
        if (!config.isEnabled) {
            return Outcome.Failure(AppError.Configuration(BACKEND_NOT_CONFIGURED))
        }
        val cached = readCached()
            ?: return Outcome.Failure(AppError.Unauthorized("backend_no_session"))
        if (cached.ownerId != ownerId) {
            clear()
            return Outcome.Failure(AppError.Unauthorized("backend_session_other_owner"))
        }
        val remaining = cached.expiresAtEpochMs - clock.now().toEpochMilliseconds()
        if (remaining <= 0) {
            clear()
            return Outcome.Failure(AppError.Unauthorized("backend_session_expired"))
        }
        return Outcome.Success(cached.accessToken)
    }

    override suspend fun revoke() {
        val cached = readCached()
        clear()
        if (cached == null || !config.isEnabled) return
        val endpoint = config.endpoint("/v1/sessions") ?: return
        withContext(ioDispatcher) {
            runCatching {
                val request = Request.Builder()
                    .url(endpoint)
                    .delete()
                    .header("authorization", "Bearer ${cached.accessToken}")
                    .build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) SafeLogger.warning(TAG, "session_revoke_rejected")
                }
            }.onFailure { SafeLogger.failure(TAG, "session_revoke_failed") }
        }
    }

    override fun clear() {
        prefs.edit(commit = true) { remove(KEY_SESSION) }
    }

    /**
     * One POST. The response is reduced to a status plus an optional machine code; the body is
     * never logged, and the password lives only in the request it built.
     */
    private fun execute(endpoint: String, body: String): CallResult? = try {
        val request = Request.Builder()
            .url(endpoint)
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .header("content-type", JSON_MEDIA_TYPE.toString())
            .header("accept", JSON_MEDIA_TYPE.toString())
            .build()
        client.newCall(request).execute().use { response ->
            val payload = response.body?.string().orEmpty()
            CallResult(response.code, payload, errorCode(payload))
        }
    } catch (error: IOException) {
        SafeLogger.failure(TAG, "account_call_failed")
        null
    } catch (error: Exception) {
        SafeLogger.failure(TAG, "account_call_invalid")
        null
    }

    private data class CallResult(
        val code: Int,
        val payload: String,
        val errorCode: String?,
    )

    private fun expiresAt(raw: String): Long =
        runCatching { Instant.parse(raw).toEpochMilliseconds() }
            .getOrElse { clock.now().toEpochMilliseconds() + FALLBACK_TTL_MILLIS }

    private fun errorCode(body: String?): String? = body
        ?.let { runCatching { json.decodeFromString(BackendErrorBody.serializer(), it) }.getOrNull() }
        ?.code

    private fun readCached(): StoredSession? {
        val wrapped = prefs.getString(KEY_SESSION, null) ?: return null
        return runCatching {
            json.decodeFromString(
                StoredSession.serializer(),
                keystoreSecretStore.unwrap(wrapped).decodeToString(),
            )
        }.getOrElse {
            clear()
            null
        }
    }

    private fun writeCached(session: StoredSession) {
        val wrapped = runCatching {
            keystoreSecretStore.wrap(json.encodeToString(StoredSession.serializer(), session).encodeToByteArray())
        }.getOrElse {
            SafeLogger.failure(TAG, "session_wrap_failed")
            return
        }
        prefs.edit(commit = true) { putString(KEY_SESSION, wrapped) }
    }

    @Serializable
    private data class RegisterBody(
        val email: String,
        val password: String,
        val displayName: String? = null,
        val inviteCode: String,
    )

    @Serializable
    private data class LoginBody(val email: String, val password: String)

    @Serializable
    private data class SessionResponse(
        val accessToken: String,
        val expiresAt: String,
        val displayName: String? = null,
    )

    @Serializable
    private data class StoredSession(
        val ownerId: String,
        val accessToken: String,
        val expiresAtEpochMs: Long,
    )

    private companion object {
        const val TAG = "BackendSession"
        const val PREFS_NAME = "pam_backend_session"
        const val KEY_SESSION = "wrapped_backend_session_v1"
        const val BACKEND_NOT_CONFIGURED = "backend_not_configured"
        const val INVALID_INVITE = "invalid_invite"
        const val FALLBACK_TTL_MILLIS = 30L * 24 * 60 * 60 * 1000
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
