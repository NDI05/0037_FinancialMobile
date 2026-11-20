package com.pam.recap.domain.repository

import com.pam.recap.core.result.Outcome
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
}
