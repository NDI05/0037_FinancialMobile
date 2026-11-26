package com.pam.recap.ui.feature.overview

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pam.recap.core.money.PercentChange
import com.pam.recap.core.result.AppError
import com.pam.recap.core.result.Outcome
import com.pam.recap.core.time.ClockProvider
import com.pam.recap.core.time.JakartaMonth
import com.pam.recap.core.time.RetentionWindow
import com.pam.recap.core.time.SystemClockProvider
import com.pam.recap.domain.model.GmailConnection
import com.pam.recap.domain.model.MonthlySummary
import com.pam.recap.domain.model.SummaryComparison
import com.pam.recap.domain.model.Transaction
import com.pam.recap.domain.repository.GmailConnectionRepository
import com.pam.recap.domain.repository.FinanceRefreshBus
import com.pam.recap.domain.repository.ServerFinanceRepository
import com.pam.recap.domain.repository.SessionRepository
import com.pam.recap.domain.repository.TransactionRepository
import com.pam.recap.domain.usecase.ComputeMonthlySummaryUseCase
import com.pam.recap.domain.usecase.ReconnectGmailUseCase
import com.pam.recap.domain.usecase.ResolveInitialMonthUseCase
import com.pam.recap.ui.selection.ActiveMonthStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
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
    /** Every row loaded for the selected month; feeds the bank cards' real Mandiri count. */
    val monthTransactions: List<Transaction> = emptyList(),
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
    private val reconnectGmail: ReconnectGmailUseCase? = null,
    private val clock: ClockProvider = SystemClockProvider(),
    private val activeMonth: ActiveMonthStore = ActiveMonthStore(),
) : ViewModel() {

    private val month = MutableStateFlow<JakartaMonth?>(null)
    private val refreshTick = MutableStateFlow(0L)

    init {
        viewModelScope.launch {
            FinanceRefreshBus.events.collect { event ->
                val owner = sessions.current()?.ownerId
                if (owner == event.ownerId && month.value?.id() == event.monthId) {
                    refreshTick.update { it + 1 }
                }
            }
        }
        viewModelScope.launch {
            // Wait for the signed-in owner instead of reading a snapshot: the shell can build this
            // view model before the session is restored, and a null read used to leave Ringkasan
            // stuck on an unresolved month (same race the Transaksi tab had).
            val ownerId = sessions.observe().filterNotNull().first().ownerId
            // The newest local data may sit outside the rolling year; it is not reachable, so the
            // screenshot/month state starts at the nearest month the product still serves.
            val resolved = window().clamp(resolveInitialMonth(ownerId))
            activeMonth.select(ownerId, resolved)
            month.value = resolved
            reconnectGmail?.let { runCatching { it(ownerId) } }
        }
        // One period across tabs: a month shifted on the Transaksi tab (or a drill-down month the
        // owner picked there) is followed here instead of Ringkasan keeping a private month.
        viewModelScope.launch {
            activeMonth.selection.collect { selection ->
                val picked = selection ?: return@collect
                val ownerId = sessions.current()?.ownerId ?: return@collect
                val pickedMonth = window().clamp(picked.month)
                if (picked.ownerId == ownerId && month.value != pickedMonth) {
                    month.value = pickedMonth
                }
            }
        }
    }

    val state: StateFlow<OverviewUiState> = combine(
        sessions.observe().filterNotNull(),
        month.filterNotNull(),
        refreshTick,
    ) { session, selectedMonth, _ -> session.ownerId to selectedMonth }
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
                        // Bank cards and direction counts must cover the whole month, not only the
                        // first 100-row page (audit P1: counts were silently capped at 100).
                        val rows = loadMonthTransactions(ownerId, selectedMonth)
                        val monthRows = (rows as? Outcome.Success)?.value.orEmpty()
                        val recent = monthRows.sortedByDescending { it.eventAt }.take(RECENT_COUNT)
                        val prior = (previous as? Outcome.Success)?.value
                        emit(
                            OverviewUiState(
                                isLoading = false, month = selectedMonth, summary = summary,
                                comparison = prior?.let {
                                    SummaryComparison(
                                        summary = summary,
                                        previousMonth = it,
                                        incomeChange = PercentChange.of(
                                            summary.classifiedIncome, it.classifiedIncome,
                                        ),
                                        expenseChange = PercentChange.of(
                                            summary.classifiedExpense, it.classifiedExpense,
                                        ),
                                        netChange = PercentChange.of(summary.netCashFlow, it.netCashFlow),
                                    )
                                },
                                recent = recent,
                                monthTransactions = monthRows,
                                error = if (rows is Outcome.Failure) rows.error else null,
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
                            monthTransactions = rows,
                        )
                    },
                    connections.observe(ownerId),
                ) { overview, connection -> overview.copy(connection = connection) }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), OverviewUiState())

    /** The rolling one-year window the arrows are bounded by (DRAFT-RET-01/02). */
    fun window(): RetentionWindow = RetentionWindow.of(clock)

    /**
     * Loads every page of the selected month for accurate per-bank and per-direction counts.
     * Bounded so a misbehaving cursor cannot loop forever; the UI still renders the rows fetched.
     */
    private suspend fun loadMonthTransactions(
        ownerId: String,
        month: JakartaMonth,
    ): Outcome<List<Transaction>> {
        val repo = server ?: return Outcome.Success(emptyList())
        val items = mutableListOf<Transaction>()
        var cursor: String? = null
        repeat(MONTH_PAGE_LIMIT) {
            when (val page = repo.listMonth(ownerId, month, limit = MONTH_PAGE_SIZE, cursor = cursor)) {
                is Outcome.Failure -> return page
                is Outcome.Success -> {
                    items += page.value.items
                    cursor = page.value.nextCursor
                    if (cursor == null || page.value.items.isEmpty()) return Outcome.Success(items)
                }
            }
        }
        return Outcome.Success(items)
    }

    /** Re-emits the month so a failed summary request can be retried without leaving the tab. */
    fun retry() {
        refreshTick.update { it + 1 }
    }

    fun previousMonth() {
        val current = month.value ?: return
        if (!window().canGoEarlier(current)) return
        shiftMonth { it.previous() }
    }

    fun nextMonth() {
        val current = month.value ?: return
        if (!window().canGoLater(current)) return
        shiftMonth { it.next() }
    }

    private fun shiftMonth(transform: (JakartaMonth) -> JakartaMonth) {
        val next = month.value?.let(transform) ?: return
        month.value = next
        viewModelScope.launch { sessions.current()?.ownerId?.let { activeMonth.select(it, next) } }
    }

    private companion object {
        const val MONTH_PAGE_SIZE = 200
        const val MONTH_PAGE_LIMIT = 10
        const val RECENT_COUNT = 3
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
