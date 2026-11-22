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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Instant

/**
 * Opaque backend session for a PAM account (contract §2, CR-001).
 *
 * Registration and login are the only places a password is held; it is never persisted or logged.
 * Access + refresh tokens are wrapped by [WrappedSecretStore] before they are written. An expired
 * access token is renewed with a single-flight refresh: parallel callers serialize on one refresh
 * and re-check the rotated pair, so the old refresh token is never replayed into family revocation.
 * A late refresh response is discarded when logout/account switch increments the generation.
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

    private val refreshMutex = Mutex()
    private val generation = AtomicLong(0)
    private val commitLock = Any()

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
        val attemptGeneration = generation.get()
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
                        } else if (!commitLogin(attemptGeneration, StoredSession(
                                ownerId = OwnerIdFactory.ownerIdFor(email.trim()),
                                serverOwnerId = session.user?.ownerId,
                                role = session.user?.role,
                                sessionId = null,
                                accessToken = session.accessToken,
                                refreshToken = session.refreshToken,
                                expiresAtEpochMs = expiresAt(session.accessExpiresAt ?: session.expiresAt),
                            ))) {
                            Outcome.Failure(AppError.Unauthorized("backend_session_superseded"))
                        } else {
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

    private fun commitLogin(attemptGeneration: Long, session: StoredSession): Boolean =
        synchronized(commitLock) {
            if (generation.get() != attemptGeneration) {
                false
            } else {
                generation.incrementAndGet()
                writeCached(session)
                true
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
        if (remaining > REFRESH_SKEW_MILLIS) {
            return Outcome.Success(cached.accessToken)
        }
        return withContext(ioDispatcher) {
            refreshMutex.withLock {
                // Another caller may have refreshed while this one waited.
                val current = readCached()
                    ?: return@withLock Outcome.Failure(AppError.Unauthorized("backend_no_session"))
                if (current.ownerId != ownerId) {
                    clear()
                    return@withLock Outcome.Failure(AppError.Unauthorized("backend_session_other_owner"))
                }
                val nowRemaining = current.expiresAtEpochMs - clock.now().toEpochMilliseconds()
                if (nowRemaining > REFRESH_SKEW_MILLIS) {
                    return@withLock Outcome.Success(current.accessToken)
                }
                refreshLocked(current)
            }
        }
    }

    override suspend fun renew(ownerId: String, rejectedToken: String?): Outcome<String> {
        if (!config.isEnabled) return Outcome.Failure(AppError.Configuration(BACKEND_NOT_CONFIGURED))
        return withContext(ioDispatcher) {
            refreshMutex.withLock {
                val current = readCached()
                    ?: return@withLock Outcome.Failure(AppError.Unauthorized("backend_no_session"))
                if (current.ownerId != ownerId) {
                    return@withLock Outcome.Failure(AppError.Unauthorized("backend_session_other_owner"))
                }
                // Another caller may already have replaced the rejected access token.
                if (rejectedToken != null && current.accessToken != rejectedToken) {
                    return@withLock Outcome.Success(current.accessToken)
                }
                refreshLocked(current)
            }
        }
    }

    private fun refreshLocked(current: StoredSession): Outcome<String> {
        val refreshToken = current.refreshToken
            ?: run {
                clear()
                return Outcome.Failure(AppError.Unauthorized("backend_session_expired"))
            }
        val endpoint = config.endpoint("/v1/sessions/refresh")
            ?: return Outcome.Failure(AppError.Configuration(BACKEND_NOT_CONFIGURED))
        val expectedGeneration = generation.get()
        val body = json.encodeToString(RefreshBody.serializer(), RefreshBody(refreshToken))
        val response = try {
            val request = Request.Builder()
                .url(endpoint)
                .post(body.toRequestBody(JSON_MEDIA_TYPE))
                .header("content-type", JSON_MEDIA_TYPE.toString())
                .header("accept", JSON_MEDIA_TYPE.toString())
                .build()
            client.newCall(request).execute().use { call ->
                val payload = call.body?.string().orEmpty()
                CallResult(call.code, payload, errorCode(payload))
            }
        } catch (error: IOException) {
            SafeLogger.failure(TAG, "session_refresh_failed")
            return Outcome.Failure(AppError.Network("backend_unreachable"))
        } catch (error: Exception) {
            SafeLogger.failure(TAG, "session_refresh_invalid")
            return Outcome.Failure(AppError.Network("backend_invalid_response"))
        }
        // A logout/login that happened while the network call was in flight wins.

        if (response.code == 401 || response.code == 403) {
            clear()
            return Outcome.Failure(AppError.Unauthorized("backend_refresh_rejected"))
        }
        if (response.code != 200) {
            return Outcome.Failure(AppError.Network("backend_${response.code}"))
        }
        val session = runCatching {
            json.decodeFromString(SessionResponse.serializer(), response.payload)
        }.getOrNull() ?: return Outcome.Failure(AppError.Network("backend_invalid_response"))
        val rotated = current.copy(
            accessToken = session.accessToken,
            refreshToken = session.refreshToken ?: current.refreshToken,
            expiresAtEpochMs = expiresAt(session.accessExpiresAt ?: session.expiresAt),
            serverOwnerId = session.user?.ownerId ?: current.serverOwnerId,
            role = session.user?.role ?: current.role,
        )
        synchronized(commitLock) {
            if (generation.get() != expectedGeneration) {
                return Outcome.Failure(AppError.Unauthorized("backend_session_superseded"))
            }
            writeCached(rotated)
        }
        return Outcome.Success(rotated.accessToken)
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
        synchronized(commitLock) {
            generation.incrementAndGet()
            prefs.edit(commit = true) { remove(KEY_SESSION) }
        }
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
        val accessExpiresAt: String? = null,
        val refreshToken: String? = null,
        val refreshExpiresAt: String? = null,
        val displayName: String? = null,
        val user: SessionUser? = null,
    )

    @Serializable
    private data class SessionUser(
        val ownerId: String? = null,
        val email: String? = null,
        val role: String? = null,
    )

    @Serializable
    private data class RefreshBody(val refreshToken: String)

    @Serializable
    private data class StoredSession(
        val ownerId: String,
        val accessToken: String,
        val expiresAtEpochMs: Long,
        val refreshToken: String? = null,
        val serverOwnerId: String? = null,
        val role: String? = null,
        val sessionId: String? = null,
    )

    private companion object {
        const val TAG = "BackendSession"
        const val PREFS_NAME = "pam_backend_session"
        const val KEY_SESSION = "wrapped_backend_session_v1"
        const val BACKEND_NOT_CONFIGURED = "backend_not_configured"
        const val INVALID_INVITE = "invalid_invite"
        const val FALLBACK_TTL_MILLIS = 30L * 24 * 60 * 60 * 1000
        const val REFRESH_SKEW_MILLIS = 60_000L
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
