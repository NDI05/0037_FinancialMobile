package com.pam.recap.ui.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pam.recap.core.result.AppError
import com.pam.recap.core.result.Outcome
import com.pam.recap.domain.model.Consent
import com.pam.recap.domain.model.ConsentDecision
import com.pam.recap.domain.model.ConsentPurpose
import com.pam.recap.domain.model.NoticeVersion
import com.pam.recap.domain.model.DemoPurge
import com.pam.recap.domain.model.GmailConnection
import com.pam.recap.domain.repository.ConsentRepository
import com.pam.recap.domain.repository.DemoDataMaintenance
import com.pam.recap.domain.repository.GmailConnectionRepository
import com.pam.recap.domain.repository.SessionRepository
import com.pam.recap.domain.usecase.DisconnectGmailUseCase
import com.pam.recap.domain.usecase.RecordConsentUseCase
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
    /** Classifier consent is separate from analysis consent (Purposes.AI_CATEGORIZATION). */
    val categoryConsent: Consent? = null,
    /** Which purpose is awaiting the backend acknowledgement; all switches disable meanwhile. */
    val consentBusyPurpose: ConsentPurpose? = null,
    val isBusy: Boolean = false,
    val error: AppError? = null,
    val signedOut: Boolean = false,
    val revokeFailed: Boolean = false,
    /** F3: the demo cleanup row is shown only when sample rows actually exist. */
    val demoRowCount: Int = 0,
    val confirmingPurge: Boolean = false,
    val purgeResult: DemoPurge? = null,
    val purgeBusy: Boolean = false,
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
    private val demoData: DemoDataMaintenance,
    /** Optional so legacy preview/test constructions keep rendering without a ledger. */
    private val recordConsent: RecordConsentUseCase? = null,
) : ViewModel() {

    private val actions = MutableStateFlow(ActionState())

    private data class DemoState(
        val rowCount: Int = 0,
        val confirming: Boolean = false,
        val busy: Boolean = false,
        val result: DemoPurge? = null,
    )

    private val demo = MutableStateFlow(DemoState())

    private data class ActionState(
        val isBusy: Boolean = false,
        val error: AppError? = null,
        val signedOut: Boolean = false,
        val revokeFailed: Boolean = false,
        val consentBusyPurpose: ConsentPurpose? = null,
    )

    val state: StateFlow<SettingsUiState> = sessions.observe()
        .filterNotNull()
        .flatMapLatest { session ->
            demo.update { it.copy(rowCount = runCatching { demoData.countDemoRows(session.ownerId) }.getOrDefault(0)) }
            val consentFlow = combine(
                consents.observeLatest(session.ownerId, ConsentPurpose.GMAIL_READ),
                consents.observeLatest(session.ownerId, ConsentPurpose.AI_PROCESSING),
                consents.observeLatest(session.ownerId, ConsentPurpose.AI_CATEGORIZATION),
            ) { gmail, ai, category -> Triple(gmail, ai, category) }
            combine(
                connections.observe(session.ownerId),
                consentFlow,
                actions,
                demo,
            ) { connection, consent, action, demoState ->
                SettingsUiState(
                    connection = connection,
                    gmailConsent = consent.first,
                    aiConsent = consent.second,
                    categoryConsent = consent.third,
                    consentBusyPurpose = action.consentBusyPurpose,
                    isBusy = action.isBusy,
                    error = action.error,
                    signedOut = action.signedOut,
                    revokeFailed = action.revokeFailed,
                    demoRowCount = demoState.rowCount,
                    confirmingPurge = demoState.confirming,
                    purgeResult = demoState.result,
                    purgeBusy = demoState.busy,
                )
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())


    /**
     * Records an explicit decision for one AI purpose. The switches are the real control surface now,
     * so turning one off appends WITHDRAWN instead of silently leaving a stale GRANTED. A backend
     * acknowledgement failure leaves the local ledger append visible and surfaces the error banner.
     */
    fun setConsent(purpose: ConsentPurpose, granted: Boolean) {
        if (actions.value.isBusy || actions.value.consentBusyPurpose != null) return
        actions.update { it.copy(consentBusyPurpose = purpose, error = null) }
        viewModelScope.launch {
            val recorder = recordConsent
            val ownerId = sessions.current()?.ownerId
            if (ownerId == null || recorder == null) {
                actions.update { it.copy(consentBusyPurpose = null) }
                return@launch
            }
            val result = recorder(
                ownerId = ownerId,
                purpose = purpose,
                noticeVersion = NoticeVersion.CURRENT,
                decision = if (granted) ConsentDecision.GRANTED else ConsentDecision.WITHDRAWN,
            )
            actions.update {
                it.copy(
                    consentBusyPurpose = null,
                    error = (result as? Outcome.Failure)?.error,
                )
            }
        }
    }

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

    fun requestPurge() {
        if (demo.value.busy) return
        demo.update { it.copy(confirming = true, result = null) }
    }

    fun cancelPurge() {
        if (demo.value.busy) return
        demo.update { it.copy(confirming = false) }
    }

    /** F3: deletes the demo rows and reports how many were removed. */
    fun confirmPurge() {
        if (demo.value.busy) return
        demo.update { it.copy(busy = true) }
        viewModelScope.launch {
            val ownerId = sessions.current()?.ownerId ?: return@launch
            val result = runCatching { demoData.purge(ownerId) }.getOrNull()
            val remaining = runCatching { demoData.countDemoRows(ownerId) }.getOrDefault(0)
            demo.update {
                it.copy(
                    busy = false,
                    confirming = false,
                    rowCount = remaining,
                    result = result,
                )
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
