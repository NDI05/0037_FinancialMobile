package com.pam.recap.ui.feature.analysis

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pam.recap.core.result.AppError
import com.pam.recap.core.result.Outcome
import com.pam.recap.core.time.JakartaMonth
import com.pam.recap.domain.model.Analysis
import com.pam.recap.domain.model.ConsentPurpose
import com.pam.recap.domain.model.MonthlySummary
import com.pam.recap.domain.model.ValidationOutcome
import com.pam.recap.domain.repository.AnalysisRepository
import com.pam.recap.domain.repository.FinanceRefreshBus
import com.pam.recap.domain.repository.ConsentRepository
import com.pam.recap.domain.repository.ServerAnalysis
import com.pam.recap.domain.repository.ServerAnalysisRepository
import com.pam.recap.domain.repository.ServerFinanceRepository
import com.pam.recap.domain.repository.SessionRepository
import com.pam.recap.domain.usecase.ComputeMonthlySummaryUseCase
import com.pam.recap.domain.usecase.RequestAnalysisUseCase
import com.pam.recap.domain.usecase.ResolveInitialMonthUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** FR-34…FR-41 analysis surface state. */
data class AnalysisUiState(
    val isLoading: Boolean = true,
    val month: JakartaMonth? = null,
    val summary: MonthlySummary? = null,
    val analysis: Analysis? = null,
    val aiConsentGranted: Boolean = false,
    val showSuggestions: Boolean = false,
    val selectedObservation: Int = 0,
    val isGenerating: Boolean = false,
    val error: AppError? = null,
) {
    val canGenerate: Boolean get() = aiConsentGranted && !isGenerating && summary?.hasData == true

    val observationsCount: Int get() = analysis?.observations?.size ?: 0
}

/**
 * Analysis state (FR-34…FR-41): the screen shows the disclosure before anything is requested, the
 * stored analysis for the month's current revision, and a fallback that keeps the deterministic
 * summary readable when the provider cannot deliver (FR-37).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class AnalysisViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val sessions: SessionRepository,
    private val analyses: AnalysisRepository,
    private val consents: ConsentRepository,
    private val computeSummary: ComputeMonthlySummaryUseCase,
    private val requestAnalysis: RequestAnalysisUseCase,
    private val resolveInitialMonth: ResolveInitialMonthUseCase,
    private val serverAnalysis: ServerAnalysisRepository,
    private val serverFinance: ServerFinanceRepository,
) : ViewModel() {

    private val month = MutableStateFlow<JakartaMonth?>(null)
    private val refreshTick = MutableStateFlow(0L)
    private val ui = MutableStateFlow(UiToggles())
    private val requestedMonth = savedStateHandle.get<String>("monthId")?.let(JakartaMonth::parse)

    private data class UiToggles(
        val showSuggestions: Boolean = false,
        val selectedObservation: Int = 0,
        val isGenerating: Boolean = false,
        val error: AppError? = null,
    )

    init {
        viewModelScope.launch {
            // A category correction changes the classified totals the analysis reads, so the same
            // owner+month invalidation that refreshes Overview also refreshes this page.
            FinanceRefreshBus.events.collect { event ->
                val owner = sessions.current()?.ownerId
                if (owner == event.ownerId && month.value?.id() == event.monthId) {
                    refreshTick.update { it + 1 }
                }
            }
        }
        viewModelScope.launch {
            val ownerId = sessions.current()?.ownerId
            month.value = requestedMonth ?: ownerId?.let { resolveInitialMonth(it) }
        }
    }

    val state: StateFlow<AnalysisUiState> = combine(
        sessions.observe().filterNotNull(),
        combine(month.filterNotNull(), refreshTick) { selected, _ -> selected },
    ) { session, selectedMonth -> session.ownerId to selectedMonth }
        .flatMapLatest { (ownerId, selectedMonth) ->
            // B-1: with a backend configured the canonical month summary and stored analysis come
            // from the server; the local Room pipeline (empty on a fresh server-mode install) must
            // not gate the screen or the generate button.
            if (serverAnalysis.isEnabled) {
                serverState(ownerId, selectedMonth)
            } else {
                localState(ownerId, selectedMonth)
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AnalysisUiState())

    private fun localState(ownerId: String, selectedMonth: JakartaMonth): Flow<AnalysisUiState> =
        combine(
            analyses.observeLatest(ownerId, selectedMonth.id()),
            consents.observeLatest(ownerId, ConsentPurpose.AI_PROCESSING),
            ui,
        ) { analysis, consent, toggles ->
            Triple(analysis, consent?.isGranted == true, toggles)
        }.flatMapLatest { (analysis, granted, toggles) ->
            flow {
                val summary = computeSummary(ownerId, selectedMonth, persist = false)
                emit(
                    AnalysisUiState(
                        isLoading = false,
                        month = selectedMonth,
                        summary = summary,
                        analysis = analysis,
                        aiConsentGranted = granted,
                        showSuggestions = toggles.showSuggestions,
                        selectedObservation = toggles.selectedObservation,
                        isGenerating = toggles.isGenerating,
                        error = toggles.error,
                    ),
                )
            }
        }

    private fun serverState(ownerId: String, selectedMonth: JakartaMonth): Flow<AnalysisUiState> =
        combine(
            consents.observeLatest(ownerId, ConsentPurpose.AI_PROCESSING),
            ui,
        ) { consent, toggles ->
            (consent?.isGranted == true) to toggles
        }.flatMapLatest { (granted, toggles) ->
            flow {
                val summaryOutcome = serverFinance.summary(ownerId, selectedMonth)
                val analysisOutcome = serverAnalysis.latest(ownerId, selectedMonth)
                val summary = (summaryOutcome as? Outcome.Success)?.value
                emit(
                    AnalysisUiState(
                        isLoading = false,
                        month = selectedMonth,
                        summary = summary,
                        analysis = (analysisOutcome as? Outcome.Success)?.value
                            ?.toAnalysis(ownerId, selectedMonth),
                        aiConsentGranted = granted,
                        showSuggestions = toggles.showSuggestions,
                        selectedObservation = toggles.selectedObservation,
                        isGenerating = toggles.isGenerating,
                        error = (summaryOutcome as? Outcome.Failure)?.error
                            ?: (analysisOutcome as? Outcome.Failure)?.error
                            ?: toggles.error,
                    ),
                )
            }
        }

    fun previousMonth() = month.update { it?.previous() }

    fun nextMonth() = month.update { it?.next() }

    fun showObservations() = ui.update { it.copy(showSuggestions = false) }

    fun showSuggestions() = ui.update { it.copy(showSuggestions = true) }

    fun selectObservation(index: Int) = ui.update { it.copy(selectedObservation = index) }

    fun generate(regenerate: Boolean = false) {
        val selectedMonth = month.value ?: return
        if (ui.value.isGenerating) return
        ui.update { it.copy(isGenerating = true, error = null) }
        viewModelScope.launch {
            val ownerId = sessions.current()?.ownerId
            if (ownerId == null) {
                ui.update { it.copy(isGenerating = false) }
                return@launch
            }
            val result = if (serverAnalysis.isEnabled) {
                serverAnalysis.generate(ownerId, selectedMonth)
            } else {
                requestAnalysis(ownerId, selectedMonth, regenerate)
            }
            ui.update {
                it.copy(
                    isGenerating = false,
                    error = (result as? Outcome.Failure)?.error,
                    selectedObservation = 0,
                )
            }
            if (result is Outcome.Success && serverAnalysis.isEnabled) {
                // The response itself carries the fresh snapshot; the next collection re-reads it.
                refreshTick.update { it + 1 }
            }
        }
    }
}

/**
 * B-1/B-2: the domain view of a server-stored analysis. The status is the effective status mapped
 * from the server's canonical freshness, so the revision comparison in `displayStatus` is only a
 * fallback and never overrides a dependency-hash STALE the backend already reported.
 */
private fun ServerAnalysis.toAnalysis(ownerId: String, month: JakartaMonth): Analysis = Analysis(
    id = "server:${month.id()}:$inputRevision",
    ownerId = ownerId,
    monthId = month.id(),
    summaryId = "server:${month.id()}:$inputRevision",
    inputRevision = inputRevision,
    consentId = null,
    providerId = "backend-go",
    modelId = "server",
    promptVersion = promptVersion,
    observations = observations,
    suggestions = suggestions,
    limitations = limitations,
    status = status,
    validation = ValidationOutcome.valid(regenerationCount = 0, at = createdAt),
    usage = null,
    createdAt = createdAt,
)
