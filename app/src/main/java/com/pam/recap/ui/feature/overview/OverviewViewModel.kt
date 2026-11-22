package com.pam.recap.ui.feature.overview

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pam.recap.core.money.PercentChange
import com.pam.recap.core.result.AppError
import com.pam.recap.core.result.Outcome
import com.pam.recap.core.time.JakartaMonth
import com.pam.recap.domain.model.GmailConnection
import com.pam.recap.domain.model.MonthlySummary
import com.pam.recap.domain.model.SummaryComparison
import com.pam.recap.domain.model.Transaction
import com.pam.recap.domain.repository.GmailConnectionRepository
import com.pam.recap.domain.repository.ServerFinanceRepository
import com.pam.recap.domain.repository.SessionRepository
import com.pam.recap.domain.repository.TransactionRepository
import com.pam.recap.domain.usecase.ComputeMonthlySummaryUseCase
import com.pam.recap.domain.usecase.ResolveInitialMonthUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** FR-28…FR-33 overview state. */
data class OverviewUiState(
    val isLoading: Boolean = true,
    val month: JakartaMonth? = null,
    val summary: MonthlySummary? = null,
    val comparison: SummaryComparison? = null,
    val recent: List<Transaction> = emptyList(),
    val connection: GmailConnection? = null,
    val error: AppError? = null,
) {
    val hasData: Boolean get() = summary?.hasData == true
}

/**
 * Overview data source.
 *
 * The month's transaction flow is the trigger: every import, manual entry or category change
 * re-emits and the summary is recomputed from the database, so the overview can never show a stale
 * total. Coverage comes from the stored summary (FR-30), and the connection row supplies the
 * import freshness hint (FR-49).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class OverviewViewModel @Inject constructor(
    private val sessions: SessionRepository,
    private val transactions: TransactionRepository,
    private val connections: GmailConnectionRepository,
    private val computeSummary: ComputeMonthlySummaryUseCase,
    private val resolveInitialMonth: ResolveInitialMonthUseCase,
    private val server: ServerFinanceRepository? = null,
) : ViewModel() {

    private val month = MutableStateFlow<JakartaMonth?>(null)

    init {
        viewModelScope.launch {
            val ownerId = sessions.current()?.ownerId
            if (ownerId != null) {
                month.value = resolveInitialMonth(ownerId)
            }
        }
    }

    val state: StateFlow<OverviewUiState> = combine(
        sessions.observe().filterNotNull(),
        month.filterNotNull(),
    ) { session, selectedMonth -> session.ownerId to selectedMonth }
        .flatMapLatest { (ownerId, selectedMonth) ->
            if (server?.isEnabled == true) {
                combine(
                    kotlinx.coroutines.flow.flow {
                        emit(OverviewUiState(isLoading = true, month = selectedMonth))
                        val current = server.summary(ownerId, selectedMonth)
                        if (current is Outcome.Failure) {
                            emit(OverviewUiState(isLoading = false, month = selectedMonth, error = current.error))
                            return@flow
                        }
                        val summary = (current as Outcome.Success).value
                        val previous = server.summary(ownerId, selectedMonth.previous())
                        val rows = server.listMonth(ownerId, selectedMonth)
                        val recent = (rows as? Outcome.Success)?.value?.items
                            ?.sortedByDescending { it.eventAt }?.take(RECENT_COUNT).orEmpty()
                        val prior = (previous as? Outcome.Success)?.value
                        emit(
                            OverviewUiState(
                                isLoading = false, month = selectedMonth, summary = summary,
                                comparison = SummaryComparison(
                                    summary = summary,
                                    previousMonth = prior,
                                    incomeChange = PercentChange.of(
                                        summary.classifiedIncome, prior?.classifiedIncome,
                                    ),
                                    expenseChange = PercentChange.of(
                                        summary.classifiedExpense, prior?.classifiedExpense,
                                    ),
                                    netChange = PercentChange.of(summary.netCashFlow, prior?.netCashFlow),
                                ),
                                recent = recent,
                            ),
                        )
                    },
                    connections.observe(ownerId),
                ) { overview, connection -> overview.copy(connection = connection) }
            } else {
                combine(
                    transactions.observeMonth(ownerId, selectedMonth).map { rows ->
                        val comparison = computeSummary.withComparison(ownerId, selectedMonth)
                        OverviewUiState(
                            isLoading = false,
                            month = selectedMonth,
                            summary = comparison.summary,
                            comparison = comparison,
                            recent = rows.sortedByDescending { it.eventAt }.take(RECENT_COUNT),
                        )
                    },
                    connections.observe(ownerId),
                ) { overview, connection -> overview.copy(connection = connection) }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), OverviewUiState())

    fun previousMonth() = shiftMonth { it.previous() }

    fun nextMonth() = shiftMonth { it.next() }

    private fun shiftMonth(transform: (JakartaMonth) -> JakartaMonth) {
        month.update { current -> current?.let(transform) }
    }

    private companion object {
        const val RECENT_COUNT = 3
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
