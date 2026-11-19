package com.pam.recap.ui.feature.welcome

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pam.recap.core.result.AppError
import com.pam.recap.core.result.Outcome
import com.pam.recap.domain.repository.AccountIdentitySource
import com.pam.recap.domain.usecase.SignInUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** FR-01 sign-in surface state. */
data class WelcomeUiState(
    val isSigningIn: Boolean = false,
    val isDemoMode: Boolean = false,
    val error: AppError? = null,
)

/**
 * FR-01/FR-08: sign-in never asks for a Google password. The identity port returns an account and
 * [SignInUseCase] hashes it into an opaque owner id and, in demo mode, seeds the sample month.
 * Navigation is not performed here: the session gate reacts to the emitted session.
 */
@HiltViewModel
class WelcomeViewModel @Inject constructor(
    private val identitySource: AccountIdentitySource,
    private val signIn: SignInUseCase,
) : ViewModel() {

    private val _state = MutableStateFlow(WelcomeUiState(isDemoMode = identitySource.isDemoMode))
    val state: StateFlow<WelcomeUiState> = _state.asStateFlow()

    fun signInWithGoogle() {
        if (_state.value.isSigningIn) return
        _state.update { it.copy(isSigningIn = true, error = null) }
        viewModelScope.launch {
            when (val identity = identitySource.signIn()) {
                is Outcome.Failure -> _state.update { it.copy(isSigningIn = false, error = identity.error) }

                is Outcome.Success -> {
                    val result = signIn(
                        accountId = identity.value.accountId,
                        displayName = identity.value.displayName,
                        mailboxLabel = identity.value.mailboxLabel,
                        isDemo = identity.value.isDemo,
                    )
                    _state.update {
                        it.copy(
                            isSigningIn = false,
                            error = (result as? Outcome.Failure)?.error,
                        )
                    }
                }
            }
        }
    }

    fun clearError() {
        _state.update { it.copy(error = null) }
    }
}
