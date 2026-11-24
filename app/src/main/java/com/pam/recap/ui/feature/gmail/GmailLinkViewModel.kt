package com.pam.recap.ui.feature.gmail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pam.recap.core.result.AppError
import com.pam.recap.core.result.AppErrorRules
import com.pam.recap.core.result.Outcome
import com.pam.recap.domain.model.GmailLinkResult
import com.pam.recap.domain.model.GmailLinkStatus
import com.pam.recap.domain.repository.GmailLinkRepository
import com.pam.recap.domain.repository.SessionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * State for the "Tautkan Gmail" section (contract §1–§4): link status, start/disconnect actions,
 * and the outcome of the OAuth callback deep link.
 *
 * Loading, failed and empty (not-yet-linked) states stay distinct so the screen never shows an
 * empty card when the status read failed. No token or full address enters this state.
 */
data class GmailLinkUiState(
    val enabled: Boolean = true,
    val signedIn: Boolean = true,
    val loading: Boolean = true,
    val status: GmailLinkStatus? = null,
    val busy: Boolean = false,
    val error: AppError? = null,
    /** Set by the `pam://gmail/connected` deep link; shown once and refreshed away on close. */
    val linkResult: GmailLinkResult? = null,
    /** One-shot: the screen opens this URL in the browser/Custom Tab, then consumes it. */
    val authorizationUrl: String? = null,
    val disconnectRequested: Boolean = false,
) {

    /**
     * True when the server answered `503 gmail_disabled`: the deployment has not enabled the
     * feature at all, so a connect button would be a dead end. Distinct from [enabled], which only
     * describes this build's backend configuration.
     */
    val backendGmailDisabled: Boolean
        get() = (error as? AppError.Validation)?.rule == AppErrorRules.GMAIL_DISABLED
}

@HiltViewModel
class GmailLinkViewModel @Inject constructor(
    private val sessions: SessionRepository,
    private val gmail: GmailLinkRepository,
    private val savedState: SavedStateHandle,
) : ViewModel() {

    private val _state = MutableStateFlow(GmailLinkUiState(enabled = gmail.isEnabled))
    val state: StateFlow<GmailLinkUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    /** Reads the server projection; also called on resume and after the OAuth callback. */
    fun refresh() {
        viewModelScope.launch {
            val ownerId = sessions.current()?.ownerId
            if (ownerId == null) {
                _state.update { it.copy(signedIn = false, loading = false) }
                return@launch
            }
            when (val result = gmail.status(ownerId)) {
                is Outcome.Success -> _state.update { current ->
                    current.copy(loading = false, status = result.value, error = null)
                }
                is Outcome.Failure -> _state.update { current ->
                    current.copy(loading = false, error = result.error)
                }
            }
        }
    }

    /** Starts the OAuth flow; on success [GmailLinkUiState.authorizationUrl] must be opened. */
    fun connect() {
        val current = _state.value
        if (!current.enabled || current.busy) return
        _state.update { it.copy(busy = true, error = null, linkResult = null) }
        viewModelScope.launch {
            val ownerId = sessions.current()?.ownerId ?: run {
                _state.update {
                    it.copy(busy = false, error = AppError.Validation("not_signed_in"))
                }
                return@launch
            }
            val reconnect = current.status?.connected == true
            when (val result = gmail.startConnect(ownerId, reconnect = reconnect)) {
                is Outcome.Success -> _state.update {
                    it.copy(busy = false, authorizationUrl = result.value.authorizationUrl)
                }
                is Outcome.Failure -> _state.update { it.copy(busy = false, error = result.error) }
            }
        }
    }

    /** The screen opened the authorization URL; drop the one-shot event. */
    fun consumeAuthorizationUrl() {
        _state.update { it.copy(authorizationUrl = null) }
    }

    /** No browser/Custom Tab could take the URL; keep the screen honest instead of stuck. */
    fun onOpenUrlFailed() {
        _state.update {
            it.copy(
                authorizationUrl = null,
                busy = false,
                error = AppError.Validation("browser_unavailable"),
            )
        }
    }

    /**
     * Result of `pam://gmail/connected?status=...&reason=...`; always followed by a refresh.
     *
     * The nav route keeps the result in its arguments, so an activity recreate composes the screen
     * again with the same values. The handled key lives in [SavedStateHandle] so the banner is
     * shown once per callback: a replay (duplicate intent / recreate) must not re-banner nor
     * re-trigger the status refresh.
     */
    fun onDeepLink(result: GmailLinkResult) {
        val key = "${result.success}|${result.reason.orEmpty()}"
        if (savedState.get<String>(KEY_HANDLED_LINK) == key) return
        savedState[KEY_HANDLED_LINK] = key
        _state.update {
            it.copy(
                linkResult = result,
                error = if (result.success) null else it.error,
                loading = false,
            )
        }
        refresh()
    }

    /** The user acknowledged the deep-link outcome banner. */
    fun dismissLinkResult() {
        _state.update { it.copy(linkResult = null) }
    }

    /** Show the confirmation dialog — disconnect is never one-tap destructive. */
    fun requestDisconnect() {
        if (!_state.value.busy) _state.update { it.copy(disconnectRequested = true) }
    }

    fun dismissDisconnect() {
        _state.update { it.copy(disconnectRequested = false) }
    }

    /** Confirmed: revoke on the server, then show the unlinked card. */
    fun confirmDisconnect() {
        _state.update {
            it.copy(disconnectRequested = false, busy = true, error = null, linkResult = null)
        }
        viewModelScope.launch {
            val ownerId = sessions.current()?.ownerId ?: run {
                _state.update {
                    it.copy(busy = false, error = AppError.Validation("not_signed_in"))
                }
                return@launch
            }
            when (val result = gmail.disconnect(ownerId)) {
                is Outcome.Success -> _state.update {
                    it.copy(busy = false, status = GmailLinkStatus.NotConnected)
                }
                is Outcome.Failure -> _state.update { it.copy(busy = false, error = result.error) }
            }
        }
    }

    companion object {
        /** Saved-state key of the last deep link this view model already presented. */
        internal const val KEY_HANDLED_LINK = "gmail_link_handled"
    }
}
