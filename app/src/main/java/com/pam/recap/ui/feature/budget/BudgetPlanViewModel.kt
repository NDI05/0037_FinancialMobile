package com.pam.recap.ui.feature.budget

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pam.recap.core.money.Money
import com.pam.recap.core.result.AppError
import com.pam.recap.core.result.Outcome
import com.pam.recap.core.time.ClockProvider
import com.pam.recap.core.time.JakartaMonth
import com.pam.recap.domain.model.BudgetComparison
import com.pam.recap.domain.model.BudgetComparisonRow
import com.pam.recap.domain.model.BudgetPlan
import com.pam.recap.domain.model.Category
import com.pam.recap.domain.model.CoverageStatus
import com.pam.recap.domain.model.MonthlySummary
import com.pam.recap.domain.model.PlanningGap
import com.pam.recap.domain.model.SummaryFreshness
import com.pam.recap.domain.model.ValueOrigin
import com.pam.recap.domain.repository.BudgetRepository
import com.pam.recap.domain.repository.FinanceRefreshBus
import com.pam.recap.domain.repository.ServerBudgetPlan
import com.pam.recap.domain.repository.ServerCategory
import com.pam.recap.domain.repository.ServerFinanceRepository
import com.pam.recap.domain.repository.SessionRepository
import com.pam.recap.domain.usecase.ComparePlanToActualUseCase
import com.pam.recap.domain.usecase.ResolveInitialMonthUseCase
import com.pam.recap.domain.usecase.SaveBudgetPlanUseCase
import com.pam.recap.domain.usecase.SuggestBudgetPlanUseCase
import com.pam.recap.ui.util.MoneyInput
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Whether the editor shows values the server already stores, or unsaved/local ones. */
enum class PlanFreshness {
    /** Edited or seeded but not written yet. */
    UNSAVED,

    /** Loaded from the server and unchanged since. */
    CURRENT,

    /** The server rejected our revision, so its copy is newer than the draft. */
    STALE,
}

/** FR-42…FR-45 budget editor state. Values stay as text while editing and become exact Money on save. */
data class BudgetPlanUiState(
    val isLoading: Boolean = true,
    val month: JakartaMonth? = null,
    val expectedIncomeText: String = "",
    val plannedSavingsText: String = "",
    val limitTexts: Map<Category, String> = emptyMap(),
    val valueOrigins: Map<Category, ValueOrigin> = emptyMap(),
    val comparison: BudgetComparison? = null,
    val isSaving: Boolean = false,
    val error: AppError? = null,
    val savedAt: Long? = null,
    val revision: Int = 0,
    /** S2.5: the plan is read from / written to the server instead of Room. */
    val isServerMode: Boolean = false,
    val freshness: PlanFreshness = PlanFreshness.UNSAVED,
    /** 409 on save: the server's revision moved on; the draft is kept for review. */
    val conflict: Boolean = false,
    /** Coverage/freshness of the server monthly summary the comparison is computed from. */
    val summaryCoverage: CoverageStatus? = null,
    val summaryIsOutdated: Boolean = false,
    val summaryGeneratedAt: Long? = null,
    /** S2.5: CURRENT / STALE / HISTORICAL as reported for the summary behind the comparison. */
    val summaryFreshness: SummaryFreshness? = null,
    val isRefreshingSummary: Boolean = false,
    /** The last refresh attempt failed; the old numbers stay visible but are marked unknown. */
    val summaryRefreshFailed: Boolean = false,
    /** Explicit delete of the server plan: confirm first, then fall back to an unsaved suggestion. */
    val deleteConfirmation: Boolean = false,
    val isDeleting: Boolean = false,
    val planDeleted: Boolean = false,
) {
    val expectedIncome: Money get() = MoneyInput.parse(expectedIncomeText) ?: Money.ZERO
    val plannedSavings: Money get() = MoneyInput.parse(plannedSavingsText) ?: Money.ZERO
    val limits: Map<Category, Money>
        get() = limitTexts.mapValues { (_, text) -> MoneyInput.parse(text) ?: Money.ZERO }
            .filterValues { it.isPositive }

    /** Σ limits; the server DTO carries limits per category but not their total. */
    val totalLimitMinor: Long get() = limits.values.sumOf { it.minor }

    val planningGap: PlanningGap get() = PlanningGap.of(expectedIncome, plannedSavings, limits)

    val isSeededFromHistory: Boolean
        get() = valueOrigins.values.any { it == ValueOrigin.HISTORICAL_AVERAGE || it == ValueOrigin.AI_SUGGESTION }
}

/**
 * FR-42/FR-43/FR-44: the editor seeds from historical averages (explicitly labelled), recomputes the
 * gap on every keystroke, and only writes the plan when the user saves. Saving performs no external
 * action — PAM never moves money.
 *
 * S2.5: when the server is enabled the plan is fetched from and written to the v0.4 budget endpoint
 * instead of Room, and the actual-versus-limit comparison is computed from the server monthly
 * summary (so it carries that summary's coverage/freshness instead of silently mixing local data).
 */
@HiltViewModel
class BudgetPlanViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val sessions: SessionRepository,
    private val budgets: BudgetRepository,
    private val suggestPlan: SuggestBudgetPlanUseCase,
    private val savePlan: SaveBudgetPlanUseCase,
    private val comparePlan: ComparePlanToActualUseCase,
    private val resolveInitialMonth: ResolveInitialMonthUseCase,
    private val clock: ClockProvider,
    private val server: ServerFinanceRepository? = null,
) : ViewModel() {

    private val requestedMonth = savedStateHandle.get<String>("monthId")?.let(JakartaMonth::parse)
    private val _state = MutableStateFlow(BudgetPlanUiState())
    val state: StateFlow<BudgetPlanUiState> = _state.asStateFlow()

    private var planId: String = ""
    private var ownerId: String = ""
    private var createdAt: kotlin.time.Instant = clock.now()

    init {
        viewModelScope.launch {
            val owner = sessions.current()?.ownerId ?: return@launch
            ownerId = owner
            val month = requestedMonth ?: resolveInitialMonth(owner)
            _state.update { it.copy(month = month, isServerMode = server?.isEnabled == true) }
            if (server?.isEnabled == true) {
                loadServerPlan(owner, month)
            } else {
                loadLocalPlan(owner, month)
            }
        }
        // A confirmed transaction write changes the actuals behind the comparison. Rather than
        // keep showing numbers that are no longer current, mark them stale and offer a refresh.
        viewModelScope.launch {
            FinanceRefreshBus.events.collect { event ->
                val current = _state.value
                val month = current.month ?: return@collect
                if (!current.isServerMode || event.ownerId != ownerId || event.monthId != month.id()) {
                    return@collect
                }
                if (current.comparison != null) {
                    _state.update { it.copy(summaryFreshness = SummaryFreshness.STALE) }
                }
            }
        }
    }

    private suspend fun loadLocalPlan(ownerId: String, month: JakartaMonth) {
        val existing = budgets.active(ownerId, month)
        if (existing != null) {
            applyPlan(existing)
            // The local editor is a valid fallback, not a failure the user must clear.
            _state.update { it.copy(error = null) }
            refreshLocalComparison(ownerId, month)
        } else {
            seedFromSuggestion(ownerId, month, error = null)
        }
    }

    /**
     * Reads the server plan. A never-saved server plan (revision 0, all amounts zero) is treated as
     * "no plan yet", so the historical suggestion is offered as a draft instead of an empty form.
     */
    private suspend fun loadServerPlan(ownerId: String, month: JakartaMonth) {
        val remote = server ?: return
        when (val result = remote.budgetPlan(ownerId, month)) {
            is Outcome.Failure -> {
                if (result.error is AppError.Configuration) {
                    // Endpoint absent on this backend: keep the local experience, but say so.
                    _state.update { it.copy(isServerMode = false, error = result.error) }
                    loadLocalPlan(ownerId, month)
                } else {
                    // Keep editing possible, but the values are a local suggestion and unsaved.
                    seedFromSuggestion(ownerId, month, error = result.error)
                }
            }

            is Outcome.Success -> {
                val plan = result.value
                val isEmptyPlan = plan.revision == 0 &&
                    plan.expectedIncomeMinor == 0L &&
                    plan.plannedSavingsMinor == 0L &&
                    plan.limitsByCategoryId.isEmpty()
                if (isEmptyPlan) {
                    seedFromSuggestion(ownerId, month, error = null)
                } else {
                    applyServerPlan(ownerId, plan, month)
                    refreshServerComparison(ownerId, month)
                }
            }
        }
    }

    private suspend fun seedFromSuggestion(ownerId: String, month: JakartaMonth, error: AppError?) {
        when (val suggestion = suggestPlan(ownerId, month)) {
            is Outcome.Success -> {
                applyPlan(suggestion.value)
                _state.update { it.copy(freshness = PlanFreshness.UNSAVED, error = error) }
            }

            is Outcome.Failure -> _state.update {
                it.copy(isLoading = false, month = month, error = error ?: suggestion.error)
            }
        }
    }

    private suspend fun applyServerPlan(ownerId: String, plan: ServerBudgetPlan, month: JakartaMonth) {
        val categories = when (val result = server?.categories(ownerId)) {
            is Outcome.Success -> result.value
            else -> emptyList()
        }
        planId = plan.monthId
        createdAt = clock.now()
        val limits = plan.limitsByCategoryId.mapNotNull { (categoryId, amountMinor) ->
            categoryForServerId(categories, categoryId)?.let { it to amountMinor }
        }.toMap()
        _state.update {
            it.copy(
                isLoading = false,
                month = month,
                expectedIncomeText = Money(plan.expectedIncomeMinor).toInputText(),
                plannedSavingsText = Money(plan.plannedSavingsMinor).toInputText(),
                limitTexts = limits.mapValues { (_, minor) -> Money(minor).toInputText() },
                valueOrigins = emptyMap(),
                revision = plan.revision,
                freshness = PlanFreshness.CURRENT,
                conflict = false,
                error = null,
            )
        }
    }

    private fun categoryForServerId(categories: List<ServerCategory>, categoryId: String): Category? =
        categories.firstOrNull { it.id == categoryId }
            ?.code
            ?.let { code -> Category.entries.firstOrNull { it.code == code } }

    fun setExpectedIncome(text: String) = onDraftEdited { it.copy(expectedIncomeText = MoneyInput.sanitize(text)) }

    fun setPlannedSavings(text: String) = onDraftEdited { it.copy(plannedSavingsText = MoneyInput.sanitize(text)) }

    fun setLimit(category: Category, text: String) = onDraftEdited {
        it.copy(limitTexts = it.limitTexts + (category to MoneyInput.sanitize(text)))
    }

    /**
     * Any keystroke means the draft no longer matches the server copy. Keeping a "saved/current"
     * label after an edit would claim a durability that does not exist yet.
     */
    private fun onDraftEdited(transform: (BudgetPlanUiState) -> BudgetPlanUiState) = _state.update {
        transform(it).copy(
            freshness = if (it.freshness == PlanFreshness.STALE) PlanFreshness.STALE else PlanFreshness.UNSAVED,
            planDeleted = false,
        )
    }

    fun save() {
        val current = _state.value
        val month = current.month ?: return
        if (current.isSaving) return
        _state.update { it.copy(isSaving = true, error = null, conflict = false) }
        viewModelScope.launch {
            val owner = sessions.current()?.ownerId
            if (owner == null) {
                _state.update { it.copy(isSaving = false) }
                return@launch
            }
            if (current.isServerMode) {
                saveServerPlan(owner, month, current)
            } else {
                saveLocalPlan(owner, month, current)
            }
        }
    }

    private suspend fun saveLocalPlan(ownerId: String, month: JakartaMonth, current: BudgetPlanUiState) {
        val plan = BudgetPlan(
            id = planId,
            ownerId = ownerId,
            month = month,
            expectedIncome = current.expectedIncome,
            plannedSavings = current.plannedSavings,
            limits = current.limits,
            gap = current.planningGap.gap,
            revision = current.revision,
            valueOrigins = current.valueOrigins,
            createdAt = createdAt,
            updatedAt = clock.now(),
        )
        when (val result = savePlan(plan)) {
            is Outcome.Failure -> _state.update { it.copy(isSaving = false, error = result.error) }
            is Outcome.Success -> {
                applyPlan(result.value)
                _state.update { it.copy(isSaving = false, savedAt = System.currentTimeMillis()) }
                refreshLocalComparison(ownerId, month)
            }
        }
    }

    private suspend fun saveServerPlan(ownerId: String, month: JakartaMonth, current: BudgetPlanUiState) {
        val remote = server
        if (remote == null) {
            _state.update { it.copy(isSaving = false) }
            return
        }
        val categories = when (val result = remote.categories(ownerId)) {
            is Outcome.Success -> result.value
            is Outcome.Failure -> {
                _state.update { it.copy(isSaving = false, error = result.error) }
                return
            }
        }
        val byCode = categories.associateBy { it.code }
        val limitsByCategoryId = current.limits.mapNotNull { (category, amount) ->
            byCode[category.code]?.let { it.id to amount.minor }
        }.toMap()
        when (
            val result = remote.saveBudgetPlan(
                ownerId = ownerId,
                month = month,
                expectedIncomeMinor = current.expectedIncome.minor,
                plannedSavingsMinor = current.plannedSavings.minor,
                limitsByCategoryId = limitsByCategoryId,
                revision = current.revision,
            )
        ) {
            is Outcome.Failure -> {
                val conflict = result.error is AppError.Conflict
                _state.update {
                    it.copy(
                        isSaving = false,
                        // The draft stays in the fields; the banner explains why it was refused.
                        error = if (conflict) null else result.error,
                        conflict = conflict,
                        freshness = if (conflict) PlanFreshness.STALE else it.freshness,
                    )
                }
            }

            is Outcome.Success -> {
                applyServerPlan(ownerId, result.value, month)
                _state.update { it.copy(isSaving = false, savedAt = System.currentTimeMillis()) }
                refreshServerComparison(ownerId, month)
            }
        }
    }

    /**
     * Recalculates the comparison from the server summary. The server rebuilds the summary on GET,
     * so this is the explicit "make the numbers current" action after a stale marker.
     */
    fun refreshSummary() {
        val current = _state.value
        val month = current.month ?: return
        if (current.isRefreshingSummary) return
        _state.update { it.copy(isRefreshingSummary = true, summaryRefreshFailed = false) }
        viewModelScope.launch {
            val owner = sessions.current()?.ownerId
            if (owner == null) {
                _state.update { it.copy(isRefreshingSummary = false) }
                return@launch
            }
            val ok = if (server?.isEnabled == true) {
                refreshServerComparison(owner, month)
            } else {
                refreshLocalComparison(owner, month)
                true
            }
            _state.update { it.copy(isRefreshingSummary = false, summaryRefreshFailed = !ok) }
        }
    }

    /** Ask for confirmation before deleting the server plan (no silent destructive action). */
    fun requestDelete() {
        val current = _state.value
        if (current.isDeleting) return
        _state.update { it.copy(deleteConfirmation = true, planDeleted = false) }
    }

    fun cancelDelete() = _state.update { it.copy(deleteConfirmation = false) }

    /**
     * Deletes the server plan, then shows the suggestion as an unsaved draft again. Failure keeps the
     * draft and reports honestly; a deleted plan is never displayed as if it still existed.
     */
    fun confirmDelete() {
        val current = _state.value
        if (!current.deleteConfirmation || current.isDeleting) return
        val month = current.month ?: return
        val remote = server
        if (remote == null || !current.isServerMode) {
            _state.update {
                it.copy(
                    deleteConfirmation = false,
                    error = AppError.Configuration("server_delete_not_available"),
                )
            }
            return
        }
        _state.update { it.copy(deleteConfirmation = false, isDeleting = true, error = null, conflict = false) }
        viewModelScope.launch {
            val owner = sessions.current()?.ownerId
            if (owner == null) {
                _state.update { it.copy(isDeleting = false) }
                return@launch
            }
            when (val result = remote.deleteBudgetPlan(owner, month)) {
                is Outcome.Success -> {
                    _state.update {
                        it.copy(
                            isDeleting = false,
                            revision = 0,
                            savedAt = null,
                            planDeleted = true,
                            freshness = PlanFreshness.UNSAVED,
                        )
                    }
                    // The editor falls back to the historical suggestion, explicitly unsaved.
                    seedFromSuggestion(owner, month, error = null)
                    _state.update { it.copy(planDeleted = true) }
                    refreshServerComparison(owner, month)
                }

                is Outcome.Failure -> _state.update {
                    it.copy(isDeleting = false, error = result.error)
                }
            }
        }
    }

    /** Loads the latest server copy, discarding a conflicting draft only when the user asks. */
    fun reload() {
        viewModelScope.launch {
            val owner = sessions.current()?.ownerId ?: return@launch
            val month = _state.value.month ?: requestedMonth ?: resolveInitialMonth(owner)
            if (server?.isEnabled == true) {
                loadServerPlan(owner, month)
            } else {
                refreshLocalComparison(owner, month)
            }
        }
    }

    private suspend fun refreshLocalComparison(ownerId: String, month: JakartaMonth) {
        when (val result = comparePlan(ownerId, month)) {
            is Outcome.Success -> _state.update { it.copy(comparison = result.value) }
            is Outcome.Failure -> Unit
        }
    }

    /**
     * Server mode compares the plan against the server summary, so the coverage/freshness line on the
     * screen describes the same numbers as the comparison table.
     *
     * @return false when the summary could not be read, so an explicit refresh can say so instead of
     * silently keeping the old numbers.
     */
    private suspend fun refreshServerComparison(ownerId: String, month: JakartaMonth): Boolean {
        val remote = server ?: return false
        return when (val result = remote.summary(ownerId, month)) {
            is Outcome.Failure -> false
            is Outcome.Success -> {
                val summary = result.value
                _state.update {
                    it.copy(
                        summaryCoverage = summary.coverage,
                        summaryIsOutdated = summary.isOutdated,
                        summaryFreshness = summary.freshness,
                        summaryGeneratedAt = summary.generatedAt.toEpochMilliseconds(),
                        summaryRefreshFailed = false,
                    )
                }
                applyComparison(summary, ownerId)
                true
            }
        }
    }

    private fun applyComparison(summary: MonthlySummary, ownerId: String) {
        val current = _state.value
        if (current.month != summary.month) return
        val limits = current.limits
        val rows = limits.entries
            .sortedBy { it.key.ordinal }
            .map { (category, limit) ->
                val actual = summary.totalFor(category)
                BudgetComparisonRow(
                    category = category,
                    limit = limit,
                    actual = actual,
                    remaining = limit - actual,
                )
            }
        val plan = BudgetPlan(
            id = planId,
            ownerId = ownerId,
            month = summary.month,
            expectedIncome = current.expectedIncome,
            plannedSavings = current.plannedSavings,
            limits = limits,
            gap = current.planningGap.gap,
            revision = current.revision,
            valueOrigins = current.valueOrigins,
            createdAt = createdAt,
            updatedAt = clock.now(),
        )
        _state.update {
            it.copy(
                comparison = BudgetComparison(
                    month = summary.month,
                    plan = plan,
                    rows = rows,
                    totalActualExpense = Money(rows.sumOf { row -> row.actual.minor }),
                    actualSavings = summary.classifiedIncome - summary.classifiedExpense,
                    gap = current.planningGap,
                ),
            )
        }
    }

    private fun applyPlan(plan: BudgetPlan) {
        planId = plan.id
        createdAt = plan.createdAt
        _state.update {
            it.copy(
                isLoading = false,
                month = plan.month,
                expectedIncomeText = plan.expectedIncome.toInputText(),
                plannedSavingsText = plan.plannedSavings.toInputText(),
                limitTexts = plan.limits.mapValues { (_, amount) -> amount.toInputText() },
                valueOrigins = plan.valueOrigins,
                revision = plan.revision,
            )
        }
    }

    /** Grouped like the display (`2.875.000`) so an editable amount is never a wall of digits. */
    private fun Money.toInputText(): String =
        if (isZero) "" else Money(minor).toDecimalString()
}
