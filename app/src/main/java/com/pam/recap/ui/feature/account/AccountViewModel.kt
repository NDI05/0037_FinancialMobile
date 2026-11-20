package com.pam.recap.ui.feature.account

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pam.recap.core.result.AppError
import com.pam.recap.core.result.Outcome
import com.pam.recap.domain.repository.AccountRepository
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
) {
    /** Local validation mirrors the backend so the user gets feedback without a round trip. */
    val canSubmit: Boolean
        get() = when {
            isBusy -> false
            email.isBlank() -> false
            !isBackendEnabled -> true
            password.length < MIN_PASSWORD_LENGTH -> false
            mode == AccountMode.REGISTER && inviteCode.isBlank() -> false
            else -> true
        }

    companion object {
        const val MIN_PASSWORD_LENGTH = 8
    }
}

/**
 * FR-51/FR-52: registration and sign-in against the PAM backend, or a local profile when no
 * backend is configured. The password never leaves this ViewModel's state; it is handed to
 * [AccountRepository] once and then dropped.
 */
@HiltViewModel
class AccountViewModel @Inject constructor(
    private val accounts: AccountRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val initialMode: AccountMode = runCatching {
        AccountMode.valueOf(savedStateHandle.get<String>(MODE_ARGUMENT) ?: AccountMode.SIGN_IN.name)
    }.getOrDefault(AccountMode.SIGN_IN)

    private val _state = MutableStateFlow(
        AccountUiState(
            mode = initialMode,
            isBackendEnabled = accounts.isBackendEnabled,
        ),
    )
    val state: StateFlow<AccountUiState> = _state.asStateFlow()

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
}
