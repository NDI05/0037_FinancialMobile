package com.pam.recap.ui.session

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pam.recap.domain.model.ConsentPurpose
import com.pam.recap.domain.model.Session
import com.pam.recap.domain.repository.ConsentRepository
import com.pam.recap.domain.repository.SessionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** FR-01 auth gate state. `NeedsConsent` is distinct from `NeedsSignIn` on purpose. */
sealed interface GateState {
    data object Loading : GateState

    data object NeedsSignIn : GateState

    data class NeedsConsent(val session: Session) : GateState

    data class Ready(val session: Session) : GateState
}

/**
 * Resolves which surface the user may see: no session → welcome, session without a Gmail decision
 * → consent, otherwise the recap. Only a granted Gmail consent opens the mailbox-dependent tabs
 * (FR-03); a declined or withdrawn AI consent never blocks the summaries.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class SessionGateViewModel @Inject constructor(
    sessions: SessionRepository,
    consents: ConsentRepository,
) : ViewModel() {

    val state: StateFlow<GateState> = sessions.observe()
        .flatMapLatest { session ->
            if (session == null) {
                flowOf(GateState.NeedsSignIn)
            } else {
                consents.observeLatest(session.ownerId, ConsentPurpose.GMAIL_READ).map { consent ->
                    // FR-03: a *recorded* decision (granted or declined) opens the app; only an
                    // undecided purpose sends the user to the consent screen. Declining Gmail
                    // keeps the recap usable in manual mode, and import asks for consent again.
                    if (consent == null) GateState.NeedsConsent(session) else GateState.Ready(session)
                }
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = GateState.Loading,
        )

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
