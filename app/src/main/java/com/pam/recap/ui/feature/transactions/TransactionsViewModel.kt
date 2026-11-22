package com.pam.recap.ui.feature.transactions

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pam.recap.core.result.AppError
import com.pam.recap.core.result.Outcome
import com.pam.recap.core.time.JakartaMonth
import com.pam.recap.domain.model.Category
import com.pam.recap.domain.model.Transaction
import com.pam.recap.domain.model.TransactionFilter
import com.pam.recap.domain.repository.SessionRepository
import com.pam.recap.domain.repository.FinanceRefreshBus
import com.pam.recap.domain.repository.ServerFinanceRepository
import com.pam.recap.domain.repository.TransactionRepository
import com.pam.recap.domain.usecase.ResolveInitialMonthUseCase
import com.pam.recap.ui.components.DayStripCell
import com.pam.recap.ui.util.PamDateFormat
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
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject

/** Filters and results for the transaction list (FR-27). */
data class TransactionsUiState(
    val isLoading: Boolean = true,
    val month: JakartaMonth? = null,
    val selectedDay: LocalDate? = null,
    val category: Category? = null,
    val query: String = "",
    val items: List<Transaction> = emptyList(),
    val monthTransactionCount: Int = 0,
    val days: List<DayStripCell> = emptyList(),
    val error: com.pam.recap.core.result.AppError? = null,
    val isClassifying: Boolean = false,
    val classificationStatus: String? = null,
    val classificationError: AppError? = null,
) {
    /** FR-20: "no data for the month" and "nothing matches the filter" are different states. */
    val isEmptyMonth: Boolean get() = !isLoading && monthTransactionCount == 0

    val isNoMatch: Boolean get() = !isLoading && monthTransactionCount > 0 && items.isEmpty()
}

/**
 * One monthly collection drives the whole screen: the day strip, the count that distinguishes
 * "empty month" from "no match", and the filtered list. Filtering in memory keeps the day/day and
 * category predicates consistent with the strips (FR-27) at the scale of a single month.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class TransactionsViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val sessions: SessionRepository,
    private val transactions: TransactionRepository,
    private val resolveInitialMonth: ResolveInitialMonthUseCase,
    private val server: ServerFinanceRepository? = null,
) : ViewModel() {

    private val month = MutableStateFlow<JakartaMonth?>(null)
    private val selectedDay = MutableStateFlow<LocalDate?>(savedStateHandle.dayArgument())
    private val category = MutableStateFlow(
        savedStateHandle.get<String>("categoryCode")?.let(Category::fromCode),
    )
    private val query = MutableStateFlow("")
    private val refreshTick = MutableStateFlow(0L)
    private val classification = MutableStateFlow(ClassificationState())

    init {
        viewModelScope.launch {
            FinanceRefreshBus.events.collect { event ->
                val owner = sessions.current()?.ownerId
                if (owner == event.ownerId && month.value?.id() == event.monthId) {
                    refreshTick.update { it + 1 }
                }
            }
        }
        val requestedMonth = savedStateHandle.get<String>("monthId")?.let(JakartaMonth::parse)
        viewModelScope.launch {
            val ownerId = sessions.current()?.ownerId
            month.value = requestedMonth ?: ownerId?.let { resolveInitialMonth(it) }
        }
    }

    val state: StateFlow<TransactionsUiState> = combine(
        sessions.observe().filterNotNull(),
        combine(
            month.filterNotNull(), selectedDay, category, query, refreshTick,
        ) { selectedMonth, day, cat, text, _ ->
            Filters(selectedMonth, day, cat, text)
        },
        classification,
    ) { session, filters, classifier -> Triple(session.ownerId, filters, classifier) }
        .flatMapLatest { (ownerId, filters, classifier) ->
            if (server?.isEnabled == true) {
                kotlinx.coroutines.flow.flow {
                    emit(TransactionsUiState(isLoading = true, month = filters.month,
                        isClassifying = classifier.isRunning, classificationStatus = classifier.status,
                        classificationError = classifier.error))
                    when (val page = server.listMonth(ownerId, filters.month)) {
                        is com.pam.recap.core.result.Outcome.Success -> emit(
                            filters.reduce(page.value.items).copy(
                                isClassifying = classifier.isRunning,
                                classificationStatus = classifier.status,
                                classificationError = classifier.error,
                            ),
                        )
                        is com.pam.recap.core.result.Outcome.Failure -> emit(
                            TransactionsUiState(
                                isLoading = false, month = filters.month, error = page.error,
                                isClassifying = classifier.isRunning,
                                classificationStatus = classifier.status,
                                classificationError = classifier.error,
                            ),
                        )
                    }
                }
            } else {
                transactions.observeMonth(ownerId, filters.month, TransactionFilter(month = filters.month))
                    .map { all -> filters.reduce(all).copy(
                        isClassifying = classifier.isRunning,
                        classificationStatus = classifier.status,
                        classificationError = classifier.error,
                    ) }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TransactionsUiState())

    /** Requests Azure for at most five visible, eligible uncategorized rows. */
    fun classifyUncategorized() {
        if (classification.value.isRunning || server?.isEnabled != true) return
        viewModelScope.launch {
            val owner = sessions.current()?.ownerId ?: return@launch
            val ids = state.value.items
                .filter { it.category == Category.UNCATEGORIZED && it.categoryOrigin != com.pam.recap.domain.model.CategoryOrigin.USER }
                .map { it.id }
                .distinct()
                .take(5)
            if (ids.isEmpty()) {
                classification.value = ClassificationState(status = "no_eligible_transactions")
                return@launch
            }
            classification.value = ClassificationState(isRunning = true)
            classification.value = when (val result = server.classifyTransactions(owner, ids)) {
                is Outcome.Failure -> ClassificationState(error = result.error)
                is Outcome.Success -> {
                    refreshTick.update { it + 1 }
                    val needsReview = result.value.count { it.state == "NEEDS_REVIEW" }
                    val failed = result.value.count { it.state == "FAILED" }
                    val classified = result.value.count { it.state == "CLASSIFIED" }
                    ClassificationState(status = "classified=$classified needsReview=$needsReview failed=$failed")
                }
            }
        }
    }

    private data class ClassificationState(
        val isRunning: Boolean = false,
        val status: String? = null,
        val error: AppError? = null,
    )

    fun previousMonth() = month.updateValue { it?.previous() }

    fun nextMonth() = month.updateValue { it?.next() }

    fun selectDay(day: LocalDate?) {
        selectedDay.value = if (selectedDay.value == day) null else day
    }

    fun selectCategory(category: Category?) {
        this.category.value = category
    }

    fun setQuery(value: String) {
        query.value = value
    }

    fun clearFilters() {
        selectedDay.value = null
        category.value = null
        query.value = ""
    }

    private data class Filters(
        val month: JakartaMonth,
        val day: LocalDate?,
        val category: Category?,
        val query: String,
    ) {
        fun reduce(all: List<Transaction>): TransactionsUiState {
            val filtered = all.filter { transaction -> matches(transaction) }
            val days = all.groupBy { PamDateFormat.localDateTime(it.eventAt).toLocalDate() }
                .toSortedMap(reverseOrder())
                .map { (date, rows) -> date.toCell(rows.size) }
            return TransactionsUiState(
                isLoading = false,
                month = month,
                selectedDay = day,
                category = category,
                query = query,
                items = filtered,
                monthTransactionCount = all.size,
                days = days,
            )
        }

        private fun matches(transaction: Transaction): Boolean {
            if (category != null && transaction.category != category) return false
            if (day != null && PamDateFormat.localDateTime(transaction.eventAt).toLocalDate() != day) return false
            if (query.isBlank()) return true
            val needle = query.trim().lowercase()
            return transaction.description.lowercase().contains(needle) ||
                transaction.merchantLabel?.lowercase()?.contains(needle) == true
        }

        private fun LocalDate.toCell(count: Int): DayStripCell {
            val today = LocalDate.now(ZoneId.of(JakartaMonth.ZONE.id))
            return DayStripCell(
                date = this,
                weekdayLabel = format(DateTimeFormatter.ofPattern("EEE", Locale.getDefault())),
                dayLabel = format(DateTimeFormatter.ofPattern("d", Locale.getDefault())),
                hasData = count > 0,
                contentDescription = format(DateTimeFormatter.ofPattern("EEEE, d MMMM", Locale.getDefault())),
                // Cells are built from the selected month, so this can only be true when today
                // actually belongs to that month (C2-6).
                isToday = this == today,
            )
        }
    }

    private fun MutableStateFlow<JakartaMonth?>.updateValue(transform: (JakartaMonth?) -> JakartaMonth?) {
        value = transform(value)
    }

    private fun SavedStateHandle.dayArgument(): LocalDate? =
        get<String>("dayIso")?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
}
