package com.pam.recap.ui.feature.gmail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pam.recap.core.result.AppError
import com.pam.recap.core.result.AppErrorRules
import com.pam.recap.core.result.Outcome
import com.pam.recap.domain.model.ConsentPurpose
import com.pam.recap.domain.model.GmailConnection
import com.pam.recap.domain.repository.AppPasswordVault
import com.pam.recap.domain.repository.ConsentRepository
import com.pam.recap.domain.repository.GmailConnectionRepository
import com.pam.recap.domain.repository.GmailCredentialValidator
import com.pam.recap.domain.repository.SessionRepository
import com.pam.recap.domain.usecase.ConnectGmailUseCase
import com.pam.recap.domain.usecase.DisconnectGmailUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** FR-07/FR-49: one card for six connection states, plus action feedback. */
data class GmailConnectUiState(
    val connection: GmailConnection? = null,
    val consentGranted: Boolean = false,
    val ownerId: String? = null,
    val isBusy: Boolean = false,
    val error: AppError? = null,
    /** FR-46: disconnect succeeded locally but the session could not be closed. */
    val revokeFailed: Boolean = false,
    val email: String = "",
    val appPassword: String = "",
    val passwordVisible: Boolean = false,
)

/**
 * Drives connect/disconnect (FR-06/FR-07/FR-46). The persisted connection row is the source of
 * truth for the state card; the transient action state only carries busy/error flags.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class GmailConnectViewModel @Inject constructor(
    sessions: SessionRepository,
    connections: GmailConnectionRepository,
    consents: ConsentRepository,
    private val validator: GmailCredentialValidator,
    private val vault: AppPasswordVault,
    private val connectGmail: ConnectGmailUseCase,
    private val disconnectGmail: DisconnectGmailUseCase,
) : ViewModel() {

    private data class ActionState(
        val isBusy: Boolean = false,
        val error: AppError? = null,
        val revokeFailed: Boolean = false,
        val email: String = "",
        val appPassword: String = "",
        val passwordVisible: Boolean = false,
    )

    private val actions = MutableStateFlow(ActionState())

    val state: StateFlow<GmailConnectUiState> = sessions.observe()
        .filterNotNull()
        .flatMapLatest { session ->
            combine(
                connections.observe(session.ownerId),
                consents.observeLatest(session.ownerId, ConsentPurpose.GMAIL_READ),
                actions,
            ) { connection, consent, action ->
                GmailConnectUiState(
                    connection = connection,
                    consentGranted = consent?.isGranted == true,
                    ownerId = session.ownerId,
                    isBusy = action.isBusy,
                    error = action.error,
                    revokeFailed = action.revokeFailed,
                    email = action.email.ifBlank { session.mailboxLabel.orEmpty() },
                    appPassword = action.appPassword,
                    passwordVisible = action.passwordVisible,
                )
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GmailConnectUiState())

    fun setEmail(value: String) = actions.update { it.copy(email = value, error = null) }

    fun setAppPassword(value: String) = actions.update { it.copy(appPassword = value, error = null) }

    fun togglePasswordVisibility() =
        actions.update { it.copy(passwordVisible = !it.passwordVisible) }

    /**
     * FR-54: the credential is validated with a real IMAP login **before** it is stored. A rejected
     * password is never written to the vault.
     */
    fun connect() {
        val ownerId = state.value.ownerId ?: return
        if (actions.value.isBusy) return
        val email = actions.value.email.trim()
        val appPassword = actions.value.appPassword.trim()
        if (email.isEmpty() || appPassword.isEmpty()) {
            actions.update {
                it.copy(error = AppError.Validation(AppErrorRules.IMAP_CREDENTIALS_REQUIRED))
            }
            return
        }
        actions.update { it.copy(isBusy = true, error = null, revokeFailed = false) }
        viewModelScope.launch {
            when (val validated = validator.validate(email, appPassword)) {
                is Outcome.Failure -> actions.update {
                    it.copy(isBusy = false, error = validated.error)
                }

                is Outcome.Success -> {
                    vault.save(email, appPassword)
                    val result = connectGmail(ownerId)
                    if (result is Outcome.Failure) {
                        // A credential the provider refused must not stay on the device.
                        if (result.error is AppError.PermissionRequired) vault.clear()
                        actions.update { it.copy(isBusy = false, error = result.error) }
                    } else {
                        actions.update { it.copy(isBusy = false, error = null, appPassword = "") }
                    }
                }
            }
        }
    }

    fun disconnect() {
        val ownerId = state.value.ownerId ?: return
        if (actions.value.isBusy) return
        actions.update { it.copy(isBusy = true, error = null, revokeFailed = false) }
        viewModelScope.launch {
            when (val result = disconnectGmail(ownerId)) {
                is Outcome.Failure -> actions.update {
                    it.copy(
                        isBusy = false,
                        error = result.error,
                        revokeFailed = true,
                    )
                }

                is Outcome.Success -> actions.update {
                    it.copy(isBusy = false, error = null, revokeFailed = false)
                }
            }
        }
    }

    /** Import requires a granted consent; the screen routes to the consent surface when needed. */
    fun requireConsentError(): AppError = AppError.Validation(AppErrorRules.CONSENT_REQUIRED)
}
