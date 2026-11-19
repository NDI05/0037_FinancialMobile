package com.pam.recap.domain.repository

import com.pam.recap.core.result.Outcome

/**
 * The app's session against the optional analysis backend (contract §2).
 *
 * The backend never sees a Google ID token twice: it is exchanged once for an opaque access token
 * that is wrapped by the Keystore before it is stored (NFR-14/16), like the database passphrase.
 * A blank `PAM_BACKEND_BASE_URL` makes every method a no-op so the on-device engine stays in use.
 */
interface BackendSessionRepository {

    /** True when a backend URL is configured; false keeps the whole app offline. */
    val isEnabled: Boolean

    /**
     * Returns a valid access token, exchanging a Google ID token when there is none, when it is
     * within five minutes of expiry, or when it belongs to another owner.
     *
     * @param ownerId opaque owner id, used to scope the cached token and build a demo token.
     * @param isDemo when true, sends the backend's development token instead of a Google token.
     */
    suspend fun token(ownerId: String, isDemo: Boolean): Outcome<String>

    /** FR-05: server-side invalidation on sign-out; clears the cache even when the call fails. */
    suspend fun revoke()

    /** Drops the cached token without a network call (used after a 401). */
    fun clear()
}

/**
 * Narrow identity port: the Google ID token itself, so the backend can verify who the user is.
 * [AccountIdentitySource] deliberately discards it; this reuses the same Credential Manager call
 * instead of starting a second sign-in flow.
 */
interface IdentityTokenProvider {
    suspend fun idToken(): Outcome<String>
}
