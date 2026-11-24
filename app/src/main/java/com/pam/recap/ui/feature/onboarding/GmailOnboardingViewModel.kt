package com.pam.recap.ui.feature.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pam.recap.core.result.Outcome
import com.pam.recap.domain.repository.GmailLinkRepository
import com.pam.recap.domain.repository.SessionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class GmailOnboardingUi(
    val visible: Boolean = false,
    val ownerId: String? = null,
)

/**
 * One-time Gmail onboarding sheet (requirement: after login/SSO/initial refresh, offer the inbox
 * link when Gmail is not connected and the owner has not skipped it).
 *
 * "Skip" is remembered per owner in the session store, so a second account on the same device still
 * sees the offer. Gmail being unlinked or revoked never blocks the app — the sheet is only an
 * offer, and both actions close it.
 */
@HiltViewModel
class GmailOnboardingViewModel @Inject constructor(
    private val sessions: SessionRepository,
    private val gmail: GmailLinkRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(GmailOnboardingUi())
    val state: StateFlow<GmailOnboardingUi> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            sessions.observe().collect { session ->
                val ownerId = session?.ownerId
                if (ownerId == null) {
                    _state.value = GmailOnboardingUi()
                    return@collect
                }
                if (sessions.isGmailOnboardingSkipped(ownerId)) {
                    _state.value = GmailOnboardingUi(ownerId = ownerId)
                    return@collect
                }
                val connected = when (val status = gmail.status(ownerId)) {
                    is Outcome.Success -> status.value.connected
                    // A failed probe is not a reason to nag: the link screen owns the real error.
                    is Outcome.Failure -> true
                }
                _state.value = GmailOnboardingUi(
                    visible = !connected && gmail.isEnabled,
                    ownerId = ownerId,
                )
            }
        }
    }

    /** "Lewati": remembered per owner and never shown again for that owner on this device. */
    fun skip() {
        val ownerId = _state.value.ownerId ?: return
        _state.update { it.copy(visible = false) }
        viewModelScope.launch { sessions.skipGmailOnboarding(ownerId) }
    }

    /** Any dismissal other than an explicit tap keeps the offer available next launch. */
    fun dismissForNow() {
        _state.update { it.copy(visible = false) }
    }

    /** Called when the connect flow finished so the sheet does not reappear underneath it. */
    fun markConnected() {
        _state.update { it.copy(visible = false) }
    }
}
