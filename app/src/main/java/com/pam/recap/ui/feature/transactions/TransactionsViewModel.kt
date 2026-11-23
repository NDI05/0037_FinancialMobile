package com.pam.recap.ui.feature.transactions

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pam.recap.core.result.AppError
import com.pam.recap.core.result.Outcome
import com.pam.recap.core.time.JakartaMonth
import com.pam.recap.domain.model.Category
import com.pam.recap.domain.model.Direction
import com.pam.recap.domain.model.Transaction
import com.pam.recap.domain.model.TransactionFilter
import com.pam.recap.domain.repository.FinanceRefreshBus
import com.pam.recap.domain.repository.ServerCategoryJob
import com.pam.recap.domain.repository.ServerFinanceRepository
import com.pam.recap.domain.repository.SessionRepository
import com.pam.recap.domain.repository.TransactionRepository
import com.pam.recap.domain.usecase.ResolveInitialMonthUseCase
import com.pam.recap.ui.components.DayStripCell
import com.pam.recap.ui.util.PamDateFormat
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** One server page is 50 rows, matching the S2.4 contract's page budget. */
internal const val TRANSACTIONS_PAGE_SIZE = 50

/** Filters and results for the transaction list (FR-27). */
data class TransactionsUiState(
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val month: JakartaMonth? = null,
    val selectedDay: LocalDate? = null,
    val category: Category? = null,
    val direction: Direction? = null,
    val query: String = "",
    val items: List<Transaction> = emptyList(),
    val monthTransactionCount: Int = 0,
    val days: List<DayStripCell> = emptyList(),
    val error: com.pam.recap.core.result.AppError? = null,
    val isClassifying: Boolean = false,
    val classificationStatus: String? = null,
    val classificationError: AppError? = null,
    /**
     * Stable reason codes returned by the category-job API for rows that ended NEEDS_REVIEW or
     * FAILED ("merchant_not_identifiable", "merchant_missing", "unsafe_merchant", ...). The screen
     * maps each code to localized copy; the codes themselves are never shown to the owner.
     */
    val classificationReviewReasons: List<String> = emptyList(),
    /** Server mode pages the month instead of reading one local snapshot (S2.4). */
    val isServerMode: Boolean = false,
    val isLoadingMore: Boolean = false,
    val hasMore: Boolean = false,
    val loadMoreError: AppError? = null,
    /** Rows the server examined for this page and whether its scan cap cut the matches. */
    val scanned: Int = 0,
    val truncated: Boolean = false,
) {
    /** FR-20: "no data for the month" and "nothing matches the filter" are different states. */
    val isEmptyMonth: Boolean get() = !isLoading && monthTransactionCount == 0 && !hasMore && !isFiltered

    val isNoMatch: Boolean get() = !isLoading && items.isEmpty() && (isFiltered || monthTransactionCount > 0)

    /** True when any predicate other than the month narrows the result, locally or server-side. */
    val isFiltered: Boolean
        get() = query.isNotBlank() || category != null || direction != null || selectedDay != null
}

/**
 * One monthly collection drives the whole screen: the day strip, the count that distinguishes
 * "empty month" from "no match", and the filtered list.
 *
 * Two rendering paths share that contract:
 *  - legacy/Room: one local monthly snapshot filtered in memory (kept exactly as before);
 *  - server (S2.4): cursor pages from `searchMonth`, appended on demand, with query/month/
 *    category/direction changes starting a new generation so a slow response can never
 *    overwrite a newer result.
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
    private val direction = MutableStateFlow<Direction?>(null)
    private val query = MutableStateFlow("")
    private val refreshTick = MutableStateFlow(0L)
    private val classification = MutableStateFlow(ClassificationState())

    private val _state = MutableStateFlow(TransactionsUiState())
    val state: StateFlow<TransactionsUiState> = _state.asStateFlow()

    /** Bumped for every month/query/category/direction/day/owner change. */
    private val generation = AtomicLong(0)

    private var loadJob: Job? = null

    /** The cursor returned with the last page, valid only for [serverPageKey]. */
    private var serverCursor: String? = null

    /** Exact server query the cursor belongs to; a mismatch drops the cursor (S2.4). */
    private var serverPageKey: ServerPageKey? = null

    /** code -> server category id, so the UI enum can drive the server filter. */
    private var categoryIdsByCode: Map<String, String>? = null
    private var categoryIdsOwner: String? = null

    /** Owner whose rows are currently rendered; a different owner must never reuse them. */
    private var renderedOwnerId: String? = null

    private data class ServerPageKey(
        val ownerId: String,
        val monthId: String,
        val query: String,
        val categoryId: String?,
        val direction: String?,
    )

    private data class ScreenFilters(
        val month: JakartaMonth,
        val day: LocalDate?,
        val category: Category?,
        val direction: Direction?,
        val query: String,
    )

    private data class QueryInput(
        val ownerId: String,
        val month: JakartaMonth,
        val day: LocalDate?,
        val category: Category?,
        val direction: Direction?,
        val query: String,
    )

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
            classification.collect { classifier ->
                _state.update {
                    it.copy(
                        isClassifying = classifier.isRunning,
                        classificationStatus = classifier.status,
                        classificationError = classifier.error,
                        classificationReviewReasons = classifier.reviewReasons,
                    )
                }
            }
        }
        viewModelScope.launch {
            val filters = combine(
                month.filterNotNull(),
                selectedDay,
                category,
                direction,
                query,
            ) { selectedMonth, day, cat, dir, text -> ScreenFilters(selectedMonth, day, cat, dir, text) }
            combine(sessions.observe().filterNotNull(), filters, refreshTick) { session, screen, _ ->
                QueryInput(
                    ownerId = session.ownerId,
                    month = screen.month,
                    day = screen.day,
                    category = screen.category,
                    direction = screen.direction,
                    query = screen.query,
                )
            }.collect { input -> requestLoad(input) }
        }
        val requestedMonth = savedStateHandle.get<String>("monthId")?.let(JakartaMonth::parse)
        viewModelScope.launch {
            val ownerId = sessions.current()?.ownerId
            month.value = requestedMonth ?: ownerId?.let { resolveInitialMonth(it) }
        }
    }

    private fun requestLoad(input: QueryInput) {
        val requestGeneration = generation.incrementAndGet()
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            if (server?.isEnabled == true) {
                loadServerPage(input, requestGeneration)
            } else {
                loadLocalSnapshot(input)
            }
        }
    }

    /**
     * Legacy path: one Room snapshot, filtered in memory exactly as before. Collecting here (not
     * in a `flatMapLatest`) keeps the live monthly observation and its local filter semantics.
     */
    private suspend fun loadLocalSnapshot(input: QueryInput) {
        serverCursor = null
        serverPageKey = null
        renderedOwnerId = input.ownerId
        _state.update {
            it.copy(
                isServerMode = false,
                month = input.month,
                selectedDay = input.day,
                category = input.category,
                direction = input.direction,
                query = input.query,
                error = null,
                loadMoreError = null,
                hasMore = false,
                isLoadingMore = false,
                isRefreshing = false,
            )
        }
        transactions.observeMonth(input.ownerId, input.month, TransactionFilter(month = input.month))
            .collect { all -> _state.value = renderLocal(input, all) }
    }

    private fun renderLocal(input: QueryInput, all: List<Transaction>): TransactionsUiState {
        val filtered = all.filter { matches(it, input) }
        val days = all.groupBy { PamDateFormat.localDateTime(it.eventAt).toLocalDate() }
            .toSortedMap(reverseOrder())
            .map { (date, rows) -> date.toCell(rows.size) }
        val classifier = classification.value
        return TransactionsUiState(
            isLoading = false,
            month = input.month,
            selectedDay = input.day,
            category = input.category,
            direction = input.direction,
            query = input.query,
            items = filtered,
            monthTransactionCount = all.size,
            days = days,
            isClassifying = classifier.isRunning,
            classificationStatus = classifier.status,
            classificationError = classifier.error,
            classificationReviewReasons = classifier.reviewReasons,
        )
    }

    /**
     * Server path: page 1 of the exact query, keeping the previous rows visible meanwhile. The
     * generation check after every suspension drops a response whose query is already stale.
     */
    private suspend fun loadServerPage(input: QueryInput, requestGeneration: Long) {
        val repo = server ?: return
        serverCursor = null
        serverPageKey = null
        val previous = _state.value
        // An owner switch is a hard reset: the previous owner's rows must never stay on screen.
        val sameOwner = renderedOwnerId == input.ownerId
        val keepPrevious =
            sameOwner && previous.month == input.month && previous.items.isNotEmpty()
        renderedOwnerId = input.ownerId
        _state.value = previous.copy(
            isLoading = !keepPrevious,
            isRefreshing = keepPrevious,
            isServerMode = true,
            month = input.month,
            selectedDay = input.day,
            category = input.category,
            direction = input.direction,
            query = input.query,
            error = null,
            loadMoreError = null,
            hasMore = false,
            isLoadingMore = false,
            // Same-month owner switch: nothing from the previous owner may stay on screen.
            items = if (keepPrevious) previous.items else emptyList(),
            monthTransactionCount = if (keepPrevious) previous.monthTransactionCount else 0,
            days = if (keepPrevious) previous.days else emptyList(),
        )

        val categoryId = if (input.category == null) {
            null
        } else {
            val resolved = resolveCategoryId(repo, input.ownerId, input.category)
            if (requestGeneration != generation.get()) return
            if (resolved == null) {
                _state.update {
                    it.copy(
                        isLoading = false,
                        isRefreshing = false,
                        error = AppError.NotFound("server_category_filter_unavailable"),
                        items = if (keepPrevious) it.items else emptyList(),
                        hasMore = false,
                    )
                }
                return
            }
            resolved
        }

        val page = repo.searchMonth(
            ownerId = input.ownerId,
            month = input.month,
            query = input.query.trim(),
            categoryId = categoryId,
            direction = input.direction?.name,
            limit = TRANSACTIONS_PAGE_SIZE,
            cursor = null,
        )
        if (requestGeneration != generation.get()) return

        when (page) {
            is Outcome.Failure -> _state.update {
                it.copy(
                    isLoading = false,
                    isRefreshing = false,
                    error = page.error,
                    items = if (keepPrevious) it.items else emptyList(),
                    hasMore = false,
                    scanned = 0,
                    truncated = false,
                )
            }

            is Outcome.Success -> {
                val key = ServerPageKey(
                    ownerId = input.ownerId,
                    monthId = input.month.id(),
                    query = input.query.trim(),
                    categoryId = categoryId,
                    direction = input.direction?.name,
                )
                serverPageKey = key
                serverCursor = page.value.nextCursor
                _state.value = renderServer(
                    input = input,
                    allItems = page.value.items.distinctBy { it.id },
                    hasMore = page.value.nextCursor != null,
                    isLoadingMore = false,
                    loadMoreError = null,
                    scanned = page.value.scanned,
                    truncated = page.value.truncated,
                )
            }
        }
    }

    /** Appends the next server page once; a new query generation makes any in-flight page inert. */
    fun loadMore() {
        val repo = server ?: return
        val current = _state.value
        val key = serverPageKey ?: return
        val cursor = serverCursor ?: return
        val month = current.month ?: return
        if (key.monthId != month.id()) return
        if (!current.isServerMode || !current.hasMore || current.isLoadingMore || current.isLoading) return
        if (current.isRefreshing) return

        val requestGeneration = generation.get()
        _state.update { it.copy(isLoadingMore = true, loadMoreError = null) }
        viewModelScope.launch {
            val ownerId = sessions.current()?.ownerId ?: return@launch
            if (key.ownerId != ownerId) return@launch
            val page = repo.searchMonth(
                ownerId = ownerId,
                month = month,
                query = key.query,
                categoryId = key.categoryId,
                direction = key.direction,
                limit = TRANSACTIONS_PAGE_SIZE,
                cursor = cursor,
            )
            if (requestGeneration != generation.get()) return@launch
            when (page) {
                is Outcome.Failure -> _state.update {
                    it.copy(isLoadingMore = false, loadMoreError = page.error)
                }

                is Outcome.Success -> {
                    if (requestGeneration != generation.get()) return@launch
                    serverCursor = page.value.nextCursor
                    val merged = (_state.value.items + page.value.items).distinctBy { it.id }
                    _state.value = renderServer(
                        input = QueryInput(
                            ownerId = key.ownerId,
                            month = current.month ?: return@launch,
                            day = current.selectedDay,
                            category = current.category,
                            direction = current.direction,
                            query = current.query,
                        ),
                        allItems = merged,
                        hasMore = page.value.nextCursor != null,
                        isLoadingMore = false,
                        loadMoreError = null,
                        scanned = current.scanned + page.value.scanned,
                        truncated = current.truncated || page.value.truncated,
                    )
                }
            }
        }
    }

    /** Retries the page that failed; the query generation is unchanged, so the cursor stays valid. */
    fun retry() {
        refreshTick.update { it + 1 }
    }

    /** Clears the footer error and retries the same page (the cursor is unchanged). */
    fun retryLoadMore() {
        if (_state.value.loadMoreError == null) return
        _state.update { it.copy(loadMoreError = null) }
        loadMore()
    }

    private fun renderServer(
        input: QueryInput,
        allItems: List<Transaction>,
        hasMore: Boolean,
        isLoadingMore: Boolean,
        loadMoreError: AppError?,
        scanned: Int,
        truncated: Boolean,
    ): TransactionsUiState {
        val visible = if (input.day == null) {
            allItems
        } else {
            allItems.filter { PamDateFormat.localDateTime(it.eventAt).toLocalDate() == input.day }
        }
        val days = allItems.groupBy { PamDateFormat.localDateTime(it.eventAt).toLocalDate() }
            .toSortedMap(reverseOrder())
            .map { (date, rows) -> date.toCell(rows.size) }
        val classifier = classification.value
        return TransactionsUiState(
            isLoading = false,
            isRefreshing = false,
            month = input.month,
            selectedDay = input.day,
            category = input.category,
            direction = input.direction,
            query = input.query,
            items = visible,
            monthTransactionCount = allItems.size,
            days = days,
            isClassifying = classifier.isRunning,
            classificationStatus = classifier.status,
            classificationError = classifier.error,
            classificationReviewReasons = classifier.reviewReasons,
            isServerMode = true,
            isLoadingMore = isLoadingMore,
            hasMore = hasMore,
            loadMoreError = loadMoreError,
            scanned = scanned,
            truncated = truncated,
        )
    }

    private suspend fun resolveCategoryId(
        repo: ServerFinanceRepository,
        ownerId: String,
        category: Category,
    ): String? {
        val cached = categoryIdsByCode
        if (cached != null && categoryIdsOwner == ownerId) return cached[category.code]
        val categories = when (val result = repo.categories(ownerId)) {
            is Outcome.Success -> result.value
            is Outcome.Failure -> return null
        }
        val map = categories.associate { it.code to it.id }
        categoryIdsByCode = map
        categoryIdsOwner = ownerId
        return map[category.code]
    }

    /** Requests Azure for at most five visible, eligible uncategorized rows. */
    fun classifyUncategorized() {
        if (classification.value.isRunning || server?.isEnabled != true) return
        viewModelScope.launch {
            val owner = sessions.current()?.ownerId ?: return@launch
            val ids = state.value.items
                .filter {
                    it.category == Category.UNCATEGORIZED &&
                        it.categoryOrigin != com.pam.recap.domain.model.CategoryOrigin.USER
                }
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
                    ClassificationState(
                        status = "classified=$classified needsReview=$needsReview failed=$failed",
                        reviewReasons = reviewReasonCodes(result.value),
                    )
                }
            }
        }
    }

    private data class ClassificationState(
        val isRunning: Boolean = false,
        val status: String? = null,
        val error: AppError? = null,
        val reviewReasons: List<String> = emptyList(),
    )

    fun previousMonth() = month.updateValue { it?.previous() }

    fun nextMonth() = month.updateValue { it?.next() }

    fun selectDay(day: LocalDate?) {
        selectedDay.value = if (selectedDay.value == day) null else day
    }

    fun selectCategory(category: Category?) {
        this.category.value = category
    }

    fun selectDirection(direction: Direction?) {
        this.direction.value = if (this.direction.value == direction) null else direction
    }

    fun setQuery(value: String) {
        query.value = value
    }

    fun clearFilters() {
        selectedDay.value = null
        category.value = null
        direction.value = null
        query.value = ""
    }

    private fun matches(transaction: Transaction, input: QueryInput): Boolean {
        if (input.category != null && transaction.category != input.category) return false
        if (input.direction != null && transaction.direction != input.direction) return false
        if (input.day != null &&
            PamDateFormat.localDateTime(transaction.eventAt).toLocalDate() != input.day
        ) {
            return false
        }
        if (input.query.isBlank()) return true
        val needle = input.query.trim().lowercase()
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

    private fun MutableStateFlow<JakartaMonth?>.updateValue(transform: (JakartaMonth?) -> JakartaMonth?) {
        value = transform(value)
    }

    private fun SavedStateHandle.dayArgument(): LocalDate? =
        get<String>("dayIso")?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
}

/**
 * Distinct API reason codes that explain why category jobs could not become a category. The list
 * is sorted so the same result always renders the same way; blank codes (older servers) are
 * dropped and the screen falls back to the plain classified/needsReview/failed counter.
 */
internal fun reviewReasonCodes(jobs: List<ServerCategoryJob>): List<String> =
    jobs.asSequence()
        .filter { it.state == "NEEDS_REVIEW" || it.state == "FAILED" }
        .mapNotNull { job -> job.reasonCode?.takeIf { it.isNotBlank() } ?: job.errorCode?.takeIf { it.isNotBlank() } }
        .distinct()
        .sorted()
        .toList()

/**
 * True when a stored merchant label only carries a reference/account number (digits plus
 * separators) instead of a merchant name. Livin bill-payment emails put a reference number in
 * their "Merchant" slot, so the app must not present that value as if it were a merchant name.
 */
internal fun isReferenceMerchantLabel(raw: String?): Boolean {
    val trimmed = raw?.trim().orEmpty()
    if (trimmed.isEmpty()) return false
    var digits = 0
    for (c in trimmed) {
        when {
            c in '0'..'9' -> digits++
            c == ' ' || c == '-' || c == '.' || c == '/' -> Unit
            else -> return false
        }
    }
    return digits > 0
}
