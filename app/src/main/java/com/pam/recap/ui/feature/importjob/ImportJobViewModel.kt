package com.pam.recap.ui.feature.importjob

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pam.recap.core.result.AppError
import com.pam.recap.core.result.Outcome
import com.pam.recap.core.time.JakartaMonth
import com.pam.recap.domain.model.ImportJob
import com.pam.recap.domain.model.ImportState
import com.pam.recap.domain.model.RedactionReason
import com.pam.recap.domain.repository.ImportRepository
import com.pam.recap.domain.repository.SessionRepository
import com.pam.recap.domain.usecase.IncrementalSyncUseCase
import com.pam.recap.domain.usecase.ResolveInitialMonthUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** FR-19/FR-21 import progress state. */
data class ImportJobUiState(
    val isLoading: Boolean = true,
    val month: JakartaMonth? = null,
    val job: ImportJob? = null,
    val skippedByReason: List<Pair<RedactionReason, Int>> = emptyList(),
    val isRunning: Boolean = false,
    val error: AppError? = null,
    val uploadPending: Boolean = false,
) {
    val canRetry: Boolean get() = uploadPending || job?.isRetryable == true

    val needsReconnect: Boolean get() = job?.state == ImportState.PERMISSION_REQUIRED
}

/**
 * Runs one import for a month and follows it live.
 *
 * The job row is written before the first page and updated after every page, so this screen reads
 * the repository stream instead of polling a suspending call — progress is real, not a spinner
 * pretending to be one.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ImportJobViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val sessions: SessionRepository,
    private val imports: ImportRepository,
    private val sync: IncrementalSyncUseCase,
    private val resolveInitialMonth: ResolveInitialMonthUseCase,
) : ViewModel() {

    private val month = MutableStateFlow<JakartaMonth?>(null)
    private val action = MutableStateFlow(false)
    private val error = MutableStateFlow<AppError?>(null)
    private val uploadPending = MutableStateFlow(false)
    private val requestedMonth = savedStateHandle.get<String>("monthId")?.let(JakartaMonth::parse)

    init {
        viewModelScope.launch {
            val ownerId = sessions.current()?.ownerId ?: return@launch
            val resolved = requestedMonth ?: resolveInitialMonth(ownerId)
            month.value = resolved
            // Opening the screen without a specific job means "import this month now".
            if (savedStateHandle.get<String>("jobId").isNullOrBlank()) {
                start(ownerId, resolved)
            }
        }
    }

    val state: StateFlow<ImportJobUiState> = combine(
        sessions.observe().filterNotNull(),
        month.filterNotNull(),
    ) { session, selectedMonth -> session.ownerId to selectedMonth }
        .flatMapLatest { (ownerId, selectedMonth) ->
            combine(
                imports.observeLatestForMonth(ownerId, selectedMonth).let { jobFlow ->
                    jobFlow.flatMapLatest { job ->
                        flow {
                            val counts = job?.let { imports.skippedFor(it.id) }
                                ?.groupingBy { it.reason }
                                ?.eachCount()
                                ?.toList()
                                ?.sortedByDescending { it.second }
                                .orEmpty()
                            emit(job to counts)
                        }
                    }
                },
                action,
                error,
                uploadPending,
            ) { (job, counts), running, errorState, pending ->
                ImportJobUiState(
                    isLoading = false,
                    month = selectedMonth,
                    job = job,
                    skippedByReason = counts,
                    isRunning = running,
                    error = errorState,
                    uploadPending = pending,
                )
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ImportJobUiState())

    fun retry() {
        viewModelScope.launch {
            val ownerId = sessions.current()?.ownerId ?: return@launch
            val selectedMonth = month.value ?: return@launch
            action.value = true
            error.value = null
            if (uploadPending.value) {
                val job = imports.latestForMonth(ownerId, selectedMonth)
                if (job == null) {
                    uploadPending.value = false
                    error.value = AppError.NotFound("import_job")
                } else {
                    val upload = sync.resumeUpload(selectedMonth)
                    error.value = (upload as? Outcome.Failure)?.error
                    uploadPending.value = upload is Outcome.Failure
                }
            } else {
                val result = sync.runManualMonth(selectedMonth)
                error.value = (result as? Outcome.Failure)?.error
            }
            action.value = false
        }
    }

    private suspend fun start(ownerId: String, selectedMonth: JakartaMonth) {
        action.value = true
        error.value = null
        // One coordinator for manual, foreground and WorkManager: never a second import path.
        val result = sync.runManualMonth(selectedMonth)
        error.value = (result as? Outcome.Failure)?.error
        uploadPending.value = false
        action.value = false
    }
}
