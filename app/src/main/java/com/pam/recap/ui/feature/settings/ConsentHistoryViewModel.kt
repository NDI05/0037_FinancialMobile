package com.pam.recap.ui.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pam.recap.core.result.Outcome
import com.pam.recap.domain.model.Consent
import com.pam.recap.domain.model.ConsentPurpose
import com.pam.recap.domain.model.DeletionRequest
import com.pam.recap.domain.model.DeletionResult
import com.pam.recap.domain.repository.ConsentRepository
import com.pam.recap.domain.repository.DeletionRepository
import com.pam.recap.domain.repository.GmailSource
import com.pam.recap.domain.repository.SessionRepository
import com.pam.recap.domain.usecase.RequestDeletionUseCase
import com.pam.recap.domain.usecase.WithdrawAiConsentUseCase
import com.pam.recap.domain.model.NoticeVersion
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.time.Instant

/** FR-03/FR-49 consent ledger history. */
data class ConsentHistoryUiState(
    val isLoading: Boolean = true,
    val entries: List<Consent> = emptyList(),
)

/** FR-49: the append-only ledger, newest first, with purpose, notice version and decision. */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ConsentHistoryViewModel @Inject constructor(
    sessions: SessionRepository,
    consents: ConsentRepository,
) : ViewModel() {

    val state: StateFlow<ConsentHistoryUiState> = sessions.observe()
        .filterNotNull()
        .flatMapLatest { session ->
            consents.observeLedger(session.ownerId).let { ledger ->
                combine(ledger, flowOf(Unit)) { entries, _ ->
                    ConsentHistoryUiState(isLoading = false, entries = entries)
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ConsentHistoryUiState())
}

/** FR-47/FR-48 deletion flow: explain → re-authenticate → confirm → progress → done. */
data class DataDeletionUiState(
    val step: DeletionStep = DeletionStep.Explanation,
    val isBusy: Boolean = false,
    val reauthenticated: Boolean = false,
    val serverPassword: String = "",
    val request: DeletionRequest? = null,
    /** SLA the backend acknowledged (contract §4.3); null when the app runs offline. */
    val serverSlaDays: Int? = null,
    val error: Int? = null,
)

enum class DeletionStep {
    Explanation,
    Confirm,
    Done,
}

/**
 * FR-47/FR-48: deletion requires a recent re-authentication (the re-auth action re-runs the Google
 * authorization), states the retention rule before confirmation, blocks while a request is already
 * in flight, and reports the recorded outcome instead of a spinner that never resolves.
 */
@HiltViewModel
class DataDeletionViewModel @Inject constructor(
    private val sessions: SessionRepository,
    private val deletion: DeletionRepository,
    private val gmailSource: GmailSource,
    private val requestDeletion: RequestDeletionUseCase,
    private val withdrawAiConsent: WithdrawAiConsentUseCase,
) : ViewModel() {

    private val _state = MutableStateFlow(DataDeletionUiState())
    val state: StateFlow<DataDeletionUiState> = _state

    fun continueToConfirm() {
        _state.update { it.copy(step = DeletionStep.Confirm, error = null) }
    }

    fun reauthenticate() {
        if (_state.value.isBusy) return
        _state.update { it.copy(isBusy = true, error = null) }
        viewModelScope.launch {
            val result = gmailSource.authorize()
            _state.update {
                it.copy(
                    isBusy = false,
                    reauthenticated = result is Outcome.Success,
                    error = (result as? Outcome.Failure)?.error?.let { error ->
                        com.pam.recap.ui.util.appErrorRes(error)
                    },
                )
            }
        }
    }

    fun onServerPasswordChange(value: String) {
        _state.update { it.copy(serverPassword = value) }
    }

    fun confirmDeletion() {
        if (_state.value.isBusy) return
        if (!_state.value.reauthenticated) {
            _state.update { it.copy(error = com.pam.recap.R.string.error_reauth_required) }
            return
        }
        if (_state.value.serverPassword.isBlank()) {
            _state.update { it.copy(error = com.pam.recap.R.string.deletion_password_required) }
            return
        }
        _state.update { it.copy(isBusy = true, error = null) }
        viewModelScope.launch {
            val ownerId = sessions.current()?.ownerId
            if (ownerId == null) {
                _state.update { it.copy(isBusy = false, error = com.pam.recap.R.string.error_not_signed_in) }
                return@launch
            }
            val active = deletion.active(ownerId)
            if (active != null) {
                _state.update { it.copy(isBusy = false, error = com.pam.recap.R.string.error_deletion_in_progress) }
                return@launch
            }
            val result = requestDeletion(
                ownerId,
                Instant.fromEpochMilliseconds(System.currentTimeMillis()),
                _state.value.serverPassword,
            )
            when (result) {
                is Outcome.Failure -> _state.update {
                    it.copy(isBusy = false, error = com.pam.recap.ui.util.appErrorRes(result.error))
                }

                is Outcome.Success -> _state.update {
                    it.copy(
                        step = DeletionStep.Done,
                        isBusy = false,
                        request = result.value.request,
                        serverSlaDays = result.value.serverSlaDays,
                    )
                }
            }
        }
    }

    /** Offered from the ledger so the user can withdraw AI processing explicitly (FR-04). */
    fun withdrawAiConsent() {
        viewModelScope.launch {
            val ownerId = sessions.current()?.ownerId ?: return@launch
            withdrawAiConsent(ownerId, NoticeVersion.CURRENT)
        }
    }
}
