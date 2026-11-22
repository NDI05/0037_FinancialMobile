package com.pam.recap.domain.repository

import com.pam.recap.core.result.Outcome

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
    suspend fun renew(ownerId: String): Outcome<String> = token(ownerId)

    /** FR-05: server-side invalidation on sign-out; clears the cache even when the call fails. */
    suspend fun revoke()

    /** Drops the cached token without a network call (used after a 401). */
    fun clear()
}
