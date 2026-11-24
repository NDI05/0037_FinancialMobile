package com.pam.recap.domain.repository

import com.pam.recap.core.result.AppError
import com.pam.recap.core.result.AppErrorRules
import com.pam.recap.core.result.Outcome
import com.pam.recap.domain.model.GoogleNonce
import com.pam.recap.domain.model.Session

/**
 * FR-51/FR-52 identity: a PAM account (email + password) when a backend is configured, or a local
 * profile kept only on this device when it is not.
 *
 * The password is never stored by the app: it is sent once to the backend in exchange for an
 * opaque session token, which [BackendSessionRepository] wraps with the Keystore.
 */
interface AccountRepository {

    /** True when a backend URL is configured; drives the copy and the fields on the account screen. */
    val isBackendEnabled: Boolean

    /** Invite-only registration (FR-51). Fails with [com.pam.recap.core.result.AppErrorRules.INVALID_INVITE]. */
    suspend fun register(
        email: String,
        password: String,
        displayName: String?,
        inviteCode: String,
    ): Outcome<Session>

    /** Email + password sign-in; a failure never says whether the account exists (FR-53). */
    suspend fun login(email: String, password: String): Outcome<Session>

    /** FR-52 offline profile: no password, nothing leaves the device. */
    suspend fun loginOffline(email: String, displayName: String?): Outcome<Session>

    /** Clears the local profile (the server session is revoked by `SignOutUseCase`). */
    suspend fun signOut()

    // --- Google SSO (contract: /v1/auth/google*). Defaults keep doubles compiling and make the
    // feature look unavailable unless a real implementation opts in. ---

    /** True only when `PAM_GOOGLE_SSO_CLIENT_ID` is configured in this build. */
    val isGoogleSsoEnabled: Boolean get() = false

    /** Single-use challenge for the Google ID token; issued by the PAM backend, never locally. */
    suspend fun googleNonce(): Outcome<GoogleNonce> =
        Outcome.Failure(AppError.Configuration(AppErrorRules.GOOGLE_SSO_DISABLED))

    /**
     * Exchanges a Google ID token for a PAM session. On success the session is stored through the
     * exact same path as [login] (wrapped backend token + local profile), so nothing downstream
     * needs a separate SSO branch.
     */
    suspend fun googleSignIn(idToken: String, nonce: String): Outcome<Session> =
        Outcome.Failure(AppError.Configuration(AppErrorRules.GOOGLE_SSO_DISABLED))

    /**
     * Attaches a Google identity to the *current* account. It must not change the owner, role,
     * transactions or Gmail link.
     */
    suspend fun googleLink(idToken: String, nonce: String): Outcome<Unit> =
        Outcome.Failure(AppError.Configuration(AppErrorRules.GOOGLE_SSO_DISABLED))

    suspend fun googleLinked(ownerId: String): Outcome<Boolean> =
        Outcome.Failure(AppError.Configuration(AppErrorRules.GOOGLE_SSO_DISABLED))
}
