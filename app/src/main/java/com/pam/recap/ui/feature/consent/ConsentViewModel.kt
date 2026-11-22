package com.pam.recap.ui.feature.consent

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pam.recap.core.result.AppError
import com.pam.recap.core.result.AppErrorRules
import com.pam.recap.domain.model.ConsentDecision
import com.pam.recap.domain.model.ConsentPurpose
import com.pam.recap.domain.model.NoticeVersion
import com.pam.recap.domain.repository.SessionRepository
import com.pam.recap.domain.usecase.RecordConsentUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** FR-03: two independent decisions, each recorded separately. */
data class ConsentUiState(
    val gmailGranted: Boolean? = null,
    val aiGranted: Boolean? = null,
    val categoryGranted: Boolean? = null,
    val isSaving: Boolean = false,
    val error: AppError? = null,
    val finished: Boolean = false,
) {
    val canContinue: Boolean get() = gmailGranted != null && !isSaving
}

/**
 * Records the Gmail and AI decisions independently (FR-03). Declining AI leaves the deterministic
 * summary fully usable (FR-04); declining Gmail keeps the app in manual mode and the import flow
 * asks for consent again when the user tries it.
 */
@HiltViewModel
class ConsentViewModel @Inject constructor(
    private val sessions: SessionRepository,
    private val recordConsent: RecordConsentUseCase,
) : ViewModel() {

    private val _state = MutableStateFlow(ConsentUiState())
    val state: StateFlow<ConsentUiState> = _state.asStateFlow()

    fun decideGmail(granted: Boolean) {
        _state.update { it.copy(gmailGranted = granted, error = null) }
    }

    fun decideAi(granted: Boolean) {
        _state.update { it.copy(aiGranted = granted, error = null) }
    }

    fun decideCategory(granted: Boolean) {
        _state.update { it.copy(categoryGranted = granted, error = null) }
    }

    /**
     * FR-03: an explicit "no Gmail" path. An untouched switch is not a decision, so a user who
     * wants the manual flow had to toggle the switch off to record one. This records a DECLINED
     * Gmail decision and finishes in one tap; the recap stays usable and Gmail can be granted
     * later from Settings. An AI choice the user already made is preserved: only Gmail is forced.
     */
    fun continueWithoutGmail() {
        if (_state.value.isSaving) return
        _state.update { it.copy(gmailGranted = false, error = null) }
        submit()
    }

    fun submit() {
        val current = _state.value
        val gmail = current.gmailGranted ?: return
        if (current.isSaving) return
        _state.update { it.copy(isSaving = true, error = null) }

        viewModelScope.launch {
            val ownerId = sessions.current()?.ownerId
            if (ownerId == null) {
                _state.update { it.copy(isSaving = false, error = AppError.Validation(AppErrorRules.NOT_SIGNED_IN)) }
                return@launch
            }
            recordConsent(
                ownerId = ownerId,
                purpose = ConsentPurpose.GMAIL_READ,
                noticeVersion = NoticeVersion.CURRENT,
                decision = if (gmail) ConsentDecision.GRANTED else ConsentDecision.DECLINED,
            )
            // FR-03: both decisions are recorded. An untouched AI switch means "not granted",
            // which the analysis surface then treats as a decision the user can revisit.
            val aiGranted = current.aiGranted == true
            recordConsent(
                ownerId = ownerId,
                purpose = ConsentPurpose.AI_PROCESSING,
                noticeVersion = NoticeVersion.CURRENT,
                decision = if (aiGranted) ConsentDecision.GRANTED else ConsentDecision.DECLINED,
            )
            // Category cloud consent is independent; untouched means declined, never implied.
            recordConsent(
                ownerId = ownerId,
                purpose = ConsentPurpose.AI_CATEGORIZATION,
                noticeVersion = NoticeVersion.CURRENT,
                decision = if (current.categoryGranted == true) ConsentDecision.GRANTED
                else ConsentDecision.DECLINED,
            )
            _state.update { it.copy(isSaving = false, finished = true) }
        }
    }
}
