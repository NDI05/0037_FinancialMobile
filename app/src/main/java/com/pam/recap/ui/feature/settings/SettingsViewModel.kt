package com.pam.recap.ui.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pam.recap.core.result.AppError
import com.pam.recap.core.result.Outcome
import com.pam.recap.domain.model.Consent
import com.pam.recap.domain.model.ConsentPurpose
import com.pam.recap.domain.model.GmailConnection
import com.pam.recap.domain.repository.ConsentRepository
import com.pam.recap.domain.repository.GmailConnectionRepository
import com.pam.recap.domain.repository.SessionRepository
import com.pam.recap.domain.usecase.DisconnectGmailUseCase
import com.pam.recap.domain.usecase.SignOutUseCase
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

/** FR-46/FR-49 settings state. */
data class SettingsUiState(
    val connection: GmailConnection? = null,
    val gmailConsent: Consent? = null,
    val aiConsent: Consent? = null,
    val isBusy: Boolean = false,
    val error: AppError? = null,
    val signedOut: Boolean = false,
    val revokeFailed: Boolean = false,
)

/**
 * FR-46/FR-49: shows the connection facts and the consent decisions (never a token), disconnects
 * with revoke-failure surfacing, and signs out — which drops the owner's financial cache so nothing
 * leaks into the next session (FR-05).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val sessions: SessionRepository,
    connections: GmailConnectionRepository,
    consents: ConsentRepository,
    private val disconnectGmail: DisconnectGmailUseCase,
    private val signOut: SignOutUseCase,
) : ViewModel() {

    private val actions = MutableStateFlow(ActionState())

    private data class ActionState(
        val isBusy: Boolean = false,
        val error: AppError? = null,
        val signedOut: Boolean = false,
        val revokeFailed: Boolean = false,
    )

    val state: StateFlow<SettingsUiState> = sessions.observe()
        .filterNotNull()
        .flatMapLatest { session ->
            combine(
                connections.observe(session.ownerId),
                consents.observeLatest(session.ownerId, ConsentPurpose.GMAIL_READ),
                consents.observeLatest(session.ownerId, ConsentPurpose.AI_PROCESSING),
                actions,
            ) { connection, gmail, ai, action ->
                SettingsUiState(
                    connection = connection,
                    gmailConsent = gmail,
                    aiConsent = ai,
                    isBusy = action.isBusy,
                    error = action.error,
                    signedOut = action.signedOut,
                    revokeFailed = action.revokeFailed,
                )
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    fun disconnect() {
        if (actions.value.isBusy) return
        actions.update { it.copy(isBusy = true, error = null, revokeFailed = false) }
        viewModelScope.launch {
            val ownerId = sessions.current()?.ownerId ?: return@launch
            when (val result = disconnectGmail(ownerId)) {
                is Outcome.Failure -> actions.update {
                    it.copy(isBusy = false, error = result.error, revokeFailed = true)
                }

                is Outcome.Success -> actions.update { it.copy(isBusy = false) }
            }
        }
    }

    fun signOut() {
        if (actions.value.isBusy) return
        actions.update { it.copy(isBusy = true, error = null) }
        viewModelScope.launch {
            val ownerId = sessions.current()?.ownerId
            if (ownerId != null) {
                signOut(ownerId)
            }
            actions.update { it.copy(isBusy = false, signedOut = true) }
        }
    }
}
