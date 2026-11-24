package com.pam.recap.ui.feature.account

import androidx.annotation.Keep
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pam.recap.core.result.AppError
import com.pam.recap.core.result.Outcome
import com.pam.recap.domain.model.GoogleSignInResult
import com.pam.recap.domain.repository.AccountRepository
import com.pam.recap.domain.repository.GoogleSignInGateway
import com.pam.recap.domain.repository.SessionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import javax.inject.Inject

/** FR-51: the account screen has exactly two modes when a backend exists; offline has one form. */
@Serializable
@Keep
enum class AccountMode {
    SIGN_IN,
    REGISTER,
}

data class AccountUiState(
    val mode: AccountMode = AccountMode.SIGN_IN,
    val email: String = "",
    val password: String = "",
    val displayName: String = "",
    val inviteCode: String = "",
    val passwordVisible: Boolean = false,
    val isBackendEnabled: Boolean = true,
    val isBusy: Boolean = false,
    val error: AppError? = null,
    // --- Google SSO + signed-in account surface ---
    /** `PAM_GOOGLE_SSO_ENABLED`: false keeps every Google entry point hidden. */
    val googleSsoEnabled: Boolean = false,
    val googleBusy: Boolean = false,
    val googleLinked: Boolean = false,
    /** Set after `POST /v1/auth/google/link` succeeds; the card switches to "linked". */
    val googleLinkNotice: Boolean = false,
    val googleLinkError: AppError? = null,
    val isSignedIn: Boolean = false,
    val signedInLabel: String? = null,
) {
    /** Local validation mirrors the backend so the user gets feedback without a round trip. */
    val canSubmit: Boolean
        get() = when {
            isBusy || googleBusy -> false
            isSignedIn -> false
            email.isBlank() -> false
            !isBackendEnabled -> true
            password.length < MIN_PASSWORD_LENGTH -> false
            mode == AccountMode.REGISTER && inviteCode.isBlank() -> false
            else -> true
        }

    /** The Google button only exists when the build configured a client ID. */
    val showGoogleSignIn: Boolean get() = googleSsoEnabled && isBackendEnabled && !isSignedIn

    /** The email form stays reachable even when Google is offered (requirement: not hidden). */
    val showEmailForm: Boolean get() = !isSignedIn

    val showGoogleLinkCard: Boolean
        get() = googleSsoEnabled && isBackendEnabled && isSignedIn && !googleLinked

    companion object {
        const val MIN_PASSWORD_LENGTH = 8
    }
}

/**
 * FR-51/FR-52: registration and sign-in against the PAM backend, or a local profile when no
 * backend is configured. The password never leaves this ViewModel's state; it is handed to
 * [AccountRepository] once and then dropped.
 *
 * Google SSO is an *additional door*: the nonce comes from the backend, the ID token from
 * Credential Manager (via [GoogleSignInGateway]), and the resulting session is stored through the
 * same repository call email/password sign-in uses. Linking Google to an existing account never
 * re-issues a session, so the owner, transactions, role and Gmail link stay exactly as they were.
 */
@HiltViewModel
class AccountViewModel @Inject constructor(
    private val accounts: AccountRepository,
    private val sessions: SessionRepository,
    private val googleSignIn: GoogleSignInGateway,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val initialMode: AccountMode = runCatching {
        AccountMode.valueOf(savedStateHandle.get<String>(MODE_ARGUMENT) ?: AccountMode.SIGN_IN.name)
    }.getOrDefault(AccountMode.SIGN_IN)

    private val _state = MutableStateFlow(
        AccountUiState(
            mode = initialMode,
            isBackendEnabled = accounts.isBackendEnabled,
            googleSsoEnabled = accounts.isGoogleSsoEnabled && googleSignIn.isAvailable,
        ),
    )
    val state: StateFlow<AccountUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            sessions.observe().collect { session ->
                _state.update {
                    it.copy(
                        isSignedIn = session != null,
                        signedInLabel = session?.mailboxLabel ?: session?.displayName,
                        googleLinked = session?.googleLinked ?: it.googleLinked,
                    )
                }
                // The session store is the offline truth; `/v1/me` is the fresh one. A failure keeps
                // the cached value instead of turning the account screen into an error page.
                val ownerId = session?.ownerId ?: return@collect
                if (!_state.value.googleSsoEnabled) return@collect
                when (val remote = accounts.googleLinked(ownerId)) {
                    is Outcome.Success -> {
                        sessions.setGoogleLinked(ownerId, remote.value)
                        _state.update { it.copy(googleLinked = remote.value) }
                    }

                    is Outcome.Failure -> Unit
                }
            }
        }
    }

    private companion object {
        const val MODE_ARGUMENT = "mode"
    }

    fun setMode(mode: AccountMode) {
        _state.update { it.copy(mode = mode, error = null) }
    }

    fun setEmail(value: String) = _state.update { it.copy(email = value, error = null) }

    fun setPassword(value: String) = _state.update { it.copy(password = value, error = null) }

    fun setDisplayName(value: String) = _state.update { it.copy(displayName = value, error = null) }

    fun setInviteCode(value: String) = _state.update { it.copy(inviteCode = value, error = null) }

    fun togglePasswordVisibility() =
        _state.update { it.copy(passwordVisible = !it.passwordVisible) }

    fun clearError() = _state.update { it.copy(error = null) }

    fun clearGoogleLinkError() = _state.update { it.copy(googleLinkError = null) }

    fun clearGoogleLinkNotice() = _state.update { it.copy(googleLinkNotice = false) }

    fun submit() {
        val snapshot = _state.value
        if (!snapshot.canSubmit) return
        _state.update { it.copy(isBusy = true, error = null) }
        viewModelScope.launch {
            val result = when {
                !snapshot.isBackendEnabled ->
                    accounts.loginOffline(snapshot.email, snapshot.displayName)
                snapshot.mode == AccountMode.REGISTER -> accounts.register(
                    email = snapshot.email,
                    password = snapshot.password,
                    displayName = snapshot.displayName,
                    inviteCode = snapshot.inviteCode,
                )
                else -> accounts.login(snapshot.email, snapshot.password)
            }
            _state.update { current ->
                when (result) {
                    is Outcome.Success -> current.copy(isBusy = false, password = "", error = null)
                    is Outcome.Failure -> current.copy(isBusy = false, error = result.error)
                }
            }
        }
    }

    /**
     * Backend nonce → Credential Manager → `POST /v1/auth/google`. A cancelled Google sheet leaves
     * the screen idle (no error banner); backend refusals map to the messages in `AppErrorCopy`.
     */
    fun signInWithGoogle() {
        val snapshot = _state.value
        if (!snapshot.showGoogleSignIn || snapshot.googleBusy || snapshot.isBusy) return
        _state.update { it.copy(googleBusy = true, error = null) }
        viewModelScope.launch {
            val nonce = when (val result = accounts.googleNonce()) {
                is Outcome.Failure -> {
                    _state.update { it.copy(googleBusy = false, error = result.error) }
                    return@launch
                }
                is Outcome.Success -> result.value.nonce
            }
            when (val credential = googleSignIn.requestIdToken(nonce)) {
                is GoogleSignInResult.Cancelled ->
                    _state.update { it.copy(googleBusy = false) }

                is GoogleSignInResult.Failure ->
                    _state.update { it.copy(googleBusy = false, error = credential.error) }

                is GoogleSignInResult.Success -> {
                    when (val result = accounts.googleSignIn(credential.token.value, nonce)) {
                        is Outcome.Failure -> _state.update {
                            it.copy(googleBusy = false, error = result.error)
                        }

                        is Outcome.Success -> _state.update {
                            it.copy(
                                googleBusy = false,
                                error = null,
                                password = "",
                                googleLinked = result.value.googleLinked,
                            )
                        }
                    }
                }
            }
        }
    }

    /** `POST /v1/auth/google/link` for the signed-in owner; a 409 keeps the session untouched. */
    fun linkGoogle() {
        val snapshot = _state.value
        if (!snapshot.showGoogleLinkCard || snapshot.googleBusy) return
        _state.update { it.copy(googleBusy = true, googleLinkError = null, googleLinkNotice = false) }
        viewModelScope.launch {
            val nonce = when (val result = accounts.googleNonce()) {
                is Outcome.Failure -> {
                    _state.update { it.copy(googleBusy = false, googleLinkError = result.error) }
                    return@launch
                }
                is Outcome.Success -> result.value.nonce
            }
            when (val credential = googleSignIn.requestIdToken(nonce)) {
                is GoogleSignInResult.Cancelled ->
                    _state.update { it.copy(googleBusy = false) }

                is GoogleSignInResult.Failure ->
                    _state.update { it.copy(googleBusy = false, googleLinkError = credential.error) }

                is GoogleSignInResult.Success -> {
                    when (val result = accounts.googleLink(credential.token.value, nonce)) {
                        is Outcome.Failure -> _state.update {
                            it.copy(googleBusy = false, googleLinkError = result.error)
                        }

                        is Outcome.Success -> _state.update {
                            it.copy(googleBusy = false, googleLinked = true, googleLinkNotice = true)
                        }
                    }
                }
            }
        }
    }
}
