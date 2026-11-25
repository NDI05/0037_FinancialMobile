package com.pam.recap.data.account

import android.util.Patterns
import com.pam.recap.BuildConfig
import com.pam.recap.core.result.AppError
import com.pam.recap.core.result.AppErrorRules
import com.pam.recap.core.security.OwnerIdFactory
import com.pam.recap.core.result.Outcome
import com.pam.recap.domain.model.GoogleNonce
import com.pam.recap.domain.model.GoogleSignInSession
import com.pam.recap.domain.model.Session
import com.pam.recap.domain.repository.AccountRepository
import com.pam.recap.domain.repository.AppPasswordVault
import com.pam.recap.domain.repository.BackendSessionRepository
import com.pam.recap.domain.repository.DemoDataMaintenance
import com.pam.recap.domain.repository.SessionRepository
import com.pam.recap.domain.usecase.SignInUseCase
import javax.inject.Inject
import javax.inject.Singleton

/**
 * FR-51/FR-52 identity.
 *
 * With a backend configured, `register`/`login` go through `/v1/accounts` and `/v1/sessions` and the
 * resulting opaque token is cached by [BackendSessionRepository]. Without one, a **local profile**
 * (email + display name, no password) signs in on this device only — nothing leaves the phone and
 * the account screen says so.
 *
 * Demo data belongs to the offline demo only (F1): `seedDemo` requires **offline mode and no stored
 * app password**. Once a credential exists the account can hold genuinely imported data, so sample
 * rows are never written again, in any mode; whatever an earlier build left behind is purged on
 * sign-in (F2).
 *
 * `isDemo` is now a display/labelling flag: true in offline mode, false for a server account,
 * independent of whether a Gmail app password is stored.
 */
@Singleton
class LocalAccountSource @Inject constructor(
    private val sessions: SessionRepository,
    private val signIn: SignInUseCase,
    private val backendSessions: BackendSessionRepository,
    private val demoData: DemoDataMaintenance,
    private val appPasswordVault: AppPasswordVault,
) : AccountRepository {

    override val isBackendEnabled: Boolean get() = backendSessions.isEnabled

    override suspend fun register(
        email: String,
        password: String,
        displayName: String?,
    ): Outcome<Session> {
        val normalized = normalize(email)
        if (!isValidEmail(normalized)) {
            return Outcome.Failure(AppError.Validation(AppErrorRules.INVALID_EMAIL))
        }
        val cleanName = displayName?.trim()?.takeIf { it.isNotEmpty() }
        if (!isBackendEnabled) {
            // FR-52: the offline profile has no password to check.
            return createProfile(normalized, cleanName)
        }
        if (password.length < MIN_PASSWORD_LENGTH) {
            return Outcome.Failure(AppError.Validation(AppErrorRules.PASSWORD_TOO_SHORT))
        }
        val registered = backendSessions.register(normalized, password, cleanName)
        if (registered is Outcome.Failure) return registered
        val loggedIn = backendSessions.login(normalized, password)
        if (loggedIn is Outcome.Failure) return loggedIn
        return createProfile(normalized, cleanName)
    }

    override suspend fun login(email: String, password: String): Outcome<Session> {
        val normalized = normalize(email)
        if (!isBackendEnabled) {
            return Outcome.Failure(AppError.Configuration("backend_not_configured"))
        }
        if (!isValidEmail(normalized) || password.isEmpty()) {
            return Outcome.Failure(AppError.Validation(AppErrorRules.INVALID_CREDENTIALS))
        }
        val loggedIn = backendSessions.login(normalized, password)
        if (loggedIn is Outcome.Failure) return loggedIn
        return createProfile(normalized, displayName = null)
    }

    override suspend fun loginOffline(email: String, displayName: String?): Outcome<Session> {
        val normalized = normalize(email)
        if (!isValidEmail(normalized)) {
            return Outcome.Failure(AppError.Validation(AppErrorRules.INVALID_EMAIL))
        }
        return createProfile(normalized, displayName?.trim()?.takeIf { it.isNotEmpty() })
    }

    override suspend fun signOut() {
        sessions.signOut()
    }

    // --- Google SSO (contract: /v1/auth/google*) ---------------------------------------------

    /** Hidden unless this build was compiled with a Google OAuth client ID. */
    override val isGoogleSsoEnabled: Boolean get() = BuildConfig.PAM_GOOGLE_SSO_ENABLED

    override suspend fun googleNonce(): Outcome<GoogleNonce> = backendSessions.googleNonce()

    override suspend fun googleSignIn(idToken: String, nonce: String): Outcome<Session> {
        val exchanged = backendSessions.googleSignIn(idToken, nonce)
        if (exchanged is Outcome.Failure) return exchanged
        val identity = (exchanged as Outcome.Success<GoogleSignInSession>).value
        // The backend session is already stored; from here it is the same profile path as login.
        return createProfile(
            email = normalize(identity.email),
            displayName = identity.displayName,
            googleLinked = identity.googleLinked,
        )
    }

    override suspend fun googleLink(idToken: String, nonce: String): Outcome<Unit> {
        val current = sessions.current()
            ?: return Outcome.Failure(AppError.Unauthorized(AppErrorRules.NOT_SIGNED_IN))
        val linked = backendSessions.googleLink(current.ownerId, idToken, nonce)
        if (linked is Outcome.Failure) return linked
        // Linking only flips the flag on the *existing* session: owner, role, data and Gmail stay.
        sessions.setGoogleLinked(current.ownerId, true)
        return Outcome.Success(Unit)
    }

    override suspend fun googleLinked(ownerId: String): Outcome<Boolean> =
        backendSessions.googleLinked(ownerId)

    private suspend fun createProfile(
        email: String,
        displayName: String?,
        googleLinked: Boolean = false,
    ): Outcome<Session> {
        val offline = !isBackendEnabled
        // A stored app password means real imports are possible: no sample rows, in any mode.
        val ownerId = OwnerIdFactory.ownerIdFor(email)
        val seedable = offline && appPasswordVault.read(ownerId) == null
        val result = signIn(
            accountId = email,
            displayName = displayName ?: email.substringBefore('@'),
            mailboxLabel = email,
            // The label still describes the mode; seeding has its own condition.
            isDemo = offline,
            seedDemo = seedable,
            googleLinked = googleLinked,
        )
        if (!seedable && result is Outcome.Success) {
            // F2 self-healing: an installation that seeded sample rows before this rule existed.
            runCatching { demoData.purge(result.value.ownerId) }
        }
        return result
    }

    private fun normalize(email: String): String = email.trim().lowercase()

    private fun isValidEmail(email: String): Boolean =
        email.isNotEmpty() && email.length <= MAX_EMAIL_LENGTH && Patterns.EMAIL_ADDRESS.matcher(email).matches()

    private companion object {
        const val MIN_PASSWORD_LENGTH = 8
        const val MAX_EMAIL_LENGTH = 254
    }
}
