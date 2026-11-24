package com.pam.recap.domain.repository

import com.pam.recap.core.result.AppError
import com.pam.recap.core.result.AppErrorRules
import com.pam.recap.core.result.Outcome
import com.pam.recap.domain.model.GoogleNonce
import com.pam.recap.domain.model.GoogleSignInSession

/**
 * The app's session against the optional analysis backend (contract §2, CR-001).
 *
 * The backend never sees a password twice: it is exchanged once at `POST /v1/sessions` for an
 * opaque access token that is wrapped by the Keystore before storage (NFR-14/16). A blank
 * `PAM_BACKEND_BASE_URL` makes every method a no-op, so the app runs entirely offline (FR-52).
 */
interface BackendSessionRepository {

    /** True when a backend URL is configured. */
    val isEnabled: Boolean

    /** FR-51: invite-only registration; the token is not issued until [login] succeeds. */
    suspend fun register(
        email: String,
        password: String,
        displayName: String?,
        inviteCode: String,
    ): Outcome<Unit>

    /** Exchanges email + password for an access token and caches it. */
    suspend fun login(email: String, password: String): Outcome<Unit>

    /**
     * Returns the cached token for [ownerId]; fails with `Unauthorized` when there is none or it
     * has expired, so the UI can ask for sign-in again instead of storing a password on device.
     */
    suspend fun token(ownerId: String): Outcome<String>

    /**
     * Forces one refresh for a request that received 401 despite a locally unexpired access token.
     * The default keeps test doubles source-compatible; the real repository shares the same
     * single-flight mutex as [token].
     */
    suspend fun renew(ownerId: String, rejectedToken: String? = null): Outcome<String> = token(ownerId)

    /** FR-05: server-side invalidation on sign-out; clears the cache even when the call fails. */
    suspend fun revoke()

    /** Drops the cached token without a network call (used after a 401). */
    fun clear()

    // --- Google SSO (contract: /v1/auth/google*). Defaults keep existing test doubles compiling
    // and make "SSO unavailable" the safe answer unless a real implementation opts in. ---

    /** `GET /v1/auth/google/nonce`: a single-use challenge for the Google ID token. */
    suspend fun googleNonce(): Outcome<GoogleNonce> =
        Outcome.Failure(AppError.Configuration(AppErrorRules.GOOGLE_SSO_DISABLED))

    /**
     * `POST /v1/auth/google`: verifies the Google ID token + [nonce] and stores the same opaque
     * session email/password login stores. Returns the identity the local profile is built from.
     */
    suspend fun googleSignIn(idToken: String, nonce: String): Outcome<GoogleSignInSession> =
        Outcome.Failure(AppError.Configuration(AppErrorRules.GOOGLE_SSO_DISABLED))

    /**
     * `POST /v1/auth/google/link`: attaches a Google identity to the signed-in PAM account. The
     * session, owner, transactions, role and Gmail link are untouched.
     */
    suspend fun googleLink(ownerId: String, idToken: String, nonce: String): Outcome<Unit> =
        Outcome.Failure(AppError.Configuration(AppErrorRules.GOOGLE_SSO_DISABLED))

    /** `GET /v1/me` `googleLinked` for the signed-in account. */
    suspend fun googleLinked(ownerId: String): Outcome<Boolean> =
        Outcome.Failure(AppError.Configuration(AppErrorRules.GOOGLE_SSO_DISABLED))
}
