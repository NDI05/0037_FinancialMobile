package com.pam.recap.ui.feature.transactions

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pam.recap.core.time.JakartaMonth
import com.pam.recap.domain.model.Category
import com.pam.recap.domain.model.Transaction
import com.pam.recap.domain.model.TransactionFilter
import com.pam.recap.domain.repository.SessionRepository
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
) : ViewModel() {

    private val month = MutableStateFlow<JakartaMonth?>(null)
    private val selectedDay = MutableStateFlow<LocalDate?>(savedStateHandle.dayArgument())
    private val category = MutableStateFlow(
        savedStateHandle.get<String>("categoryCode")?.let(Category::fromCode),
    )
    private val query = MutableStateFlow("")

    init {
        val requestedMonth = savedStateHandle.get<String>("monthId")?.let(JakartaMonth::parse)
        viewModelScope.launch {
            val ownerId = sessions.current()?.ownerId
            month.value = requestedMonth ?: ownerId?.let { resolveInitialMonth(it) }
        }
    }

    val state: StateFlow<TransactionsUiState> = combine(
        sessions.observe().filterNotNull(),
        combine(month.filterNotNull(), selectedDay, category, query) { selectedMonth, day, cat, text ->
            Filters(selectedMonth, day, cat, text)
        },
    ) { session, filters -> session.ownerId to filters }
        .flatMapLatest { (ownerId, filters) ->
            transactions.observeMonth(ownerId, filters.month, TransactionFilter(month = filters.month))
                .map { all -> filters.reduce(all) }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TransactionsUiState())

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
