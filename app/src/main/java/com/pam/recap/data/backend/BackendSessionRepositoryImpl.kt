package com.pam.recap.data.backend

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.pam.recap.BuildConfig
import com.pam.recap.core.di.IoDispatcher
import com.pam.recap.core.result.AppError
import com.pam.recap.core.result.Outcome
import com.pam.recap.core.result.SafeLogger
import com.pam.recap.core.security.WrappedSecretStore
import com.pam.recap.core.time.ClockProvider
import com.pam.recap.domain.repository.BackendSessionRepository
import com.pam.recap.domain.repository.IdentityTokenProvider
import com.pam.recap.domain.repository.SessionRepository
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

/** True only for a build without a real Google client id (OI-01 demo mode). */
internal fun demoTokenAllowed(googleClientId: String): Boolean = googleClientId.isBlank()

/**
 * Opaque backend session (contract §2.1/§2.2).
 *
 * The access token is wrapped by [KeystoreSecretStore] before it is written, exactly like the
 * database passphrase: the only persisted form is ciphertext, and the plaintext exists in memory
 * for the call that needs it. Nothing here logs a token, an endpoint or a body (NFR-18).
 */
@Singleton
class BackendSessionRepositoryImpl @Inject constructor(
    private val client: OkHttpClient,
    private val json: Json,
    private val config: BackendEndpoints,
    private val identityTokens: IdentityTokenProvider,
    private val sessions: SessionRepository,
    private val keystoreSecretStore: WrappedSecretStore,
    @param:ApplicationContext private val context: Context,
    private val clock: ClockProvider,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : BackendSessionRepository {

    private val prefs: SharedPreferences
        get() = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override val isEnabled: Boolean get() = config.isEnabled

    override suspend fun token(ownerId: String, isDemo: Boolean): Outcome<String> {
        if (!config.isEnabled) {
            return Outcome.Failure(AppError.Configuration(BACKEND_NOT_CONFIGURED))
        }
        readCached()?.let { cached ->
            val freshEnough = cached.ownerId == ownerId &&
                cached.expiresAtEpochMs - clock.now().toEpochMilliseconds() > REFRESH_WINDOW_MILLIS
            if (freshEnough) return Outcome.Success(cached.accessToken)
            if (cached.ownerId != ownerId) clear()
        }
        return exchange(ownerId, isDemo)
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
                    if (!response.isSuccessful) {
                        SafeLogger.warning(TAG, "session_revoke_rejected")
                    }
                }
            }.onFailure { SafeLogger.failure(TAG, "session_revoke_failed") }
        }
    }

    override fun clear() {
        prefs.edit(commit = true) { remove(KEY_SESSION) }
    }

    private suspend fun exchange(ownerId: String, isDemo: Boolean): Outcome<String> {
        val endpoint = config.endpoint("/v1/sessions")
            ?: return Outcome.Failure(AppError.Configuration(BACKEND_NOT_CONFIGURED))

        val idToken = when {
            isDemo -> {
                if (!demoTokenAllowed(BuildConfig.PAM_GOOGLE_WEB_CLIENT_ID)) {
                    // A real build never sends a development token, even if a demo session leaks in.
                    return Outcome.Failure(AppError.Configuration("demo_token_not_allowed"))
                }
                val displayName = sessions.current()?.displayName ?: DEMO_DISPLAY_NAME
                "dev:$ownerId:$displayName"
            }

            else -> when (val result = identityTokens.idToken()) {
                is Outcome.Success -> result.value
                is Outcome.Failure -> return Outcome.Failure(result.error)
            }
        }

        return withContext(ioDispatcher) {
            try {
                val body = json.encodeToString(
                    SessionRequestBody.serializer(),
                    SessionRequestBody(idToken = idToken),
                )
                val request = Request.Builder()
                    .url(endpoint)
                    .post(body.toRequestBody(JSON_MEDIA_TYPE))
                    .header("accept", JSON_MEDIA_TYPE.toString())
                    .build()
                client.newCall(request).execute().use { response ->
                    if (response.code != 201) {
                        return@withContext Outcome.Failure(
                            backendError(response.code, errorCode(response.body?.string())),
                        )
                    }
                    val payload = response.body?.string().orEmpty()
                    val decoded = json.decodeFromString(SessionResponse.serializer(), payload)
                    val expiresAt = runCatching { Instant.parse(decoded.expiresAt).toEpochMilliseconds() }
                        .getOrElse { clock.now().toEpochMilliseconds() + FALLBACK_TTL_MILLIS }
                    writeCached(
                        StoredSession(
                            ownerId = ownerId,
                            accessToken = decoded.accessToken,
                            expiresAtEpochMs = expiresAt,
                        ),
                    )
                    Outcome.Success(decoded.accessToken)
                }
            } catch (error: IOException) {
                SafeLogger.failure(TAG, "session_exchange_failed")
                Outcome.Failure(AppError.Network("backend_unreachable"))
            } catch (error: Exception) {
                SafeLogger.failure(TAG, "session_exchange_invalid")
                Outcome.Failure(AppError.Network("backend_invalid_response"))
            }
        }
    }

    private fun errorCode(body: String?): String? = body
        ?.let { runCatching { json.decodeFromString(BackendErrorBody.serializer(), it) }.getOrNull() }
        ?.code

    private fun readCached(): StoredSession? {
        val wrapped = prefs.getString(KEY_SESSION, null) ?: return null
        return runCatching {
            val plain = keystoreSecretStore.unwrap(wrapped).decodeToString()
            json.decodeFromString(StoredSession.serializer(), plain)
        }.getOrElse {
            // A wrapped value from another key (reinstall, cleared keystore) is simply dropped.
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
    private data class SessionRequestBody(val idToken: String)

    @Serializable
    private data class SessionResponse(val accessToken: String, val expiresAt: String)

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
        const val DEMO_DISPLAY_NAME = "Demo"
        const val REFRESH_WINDOW_MILLIS = 5 * 60 * 1000L
        const val FALLBACK_TTL_MILLIS = 30L * 24 * 60 * 60 * 1000
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
