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
import com.pam.recap.domain.model.BudgetPlan
import com.pam.recap.domain.model.Category
import com.pam.recap.domain.model.PlanningGap
import com.pam.recap.domain.model.ValueOrigin
import com.pam.recap.domain.repository.BudgetRepository
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
) {
    val expectedIncome: Money get() = MoneyInput.parse(expectedIncomeText) ?: Money.ZERO
    val plannedSavings: Money get() = MoneyInput.parse(plannedSavingsText) ?: Money.ZERO
    val limits: Map<Category, Money>
        get() = limitTexts.mapValues { (_, text) -> MoneyInput.parse(text) ?: Money.ZERO }
            .filterValues { it.isPositive }

    val planningGap: PlanningGap get() = PlanningGap.of(expectedIncome, plannedSavings, limits)

    val isSeededFromHistory: Boolean
        get() = valueOrigins.values.any { it == ValueOrigin.HISTORICAL_AVERAGE || it == ValueOrigin.AI_SUGGESTION }
}

/**
 * FR-42/FR-43/FR-44: the editor seeds from historical averages (explicitly labelled), recomputes the
 * gap on every keystroke, and only writes the plan when the user saves. Saving performs no external
 * action — PAM never moves money.
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
) : ViewModel() {

    private val requestedMonth = savedStateHandle.get<String>("monthId")?.let(JakartaMonth::parse)
    private val _state = MutableStateFlow(BudgetPlanUiState())
    val state: StateFlow<BudgetPlanUiState> = _state.asStateFlow()

    private var planId: String = ""
    private var createdAt: kotlin.time.Instant = clock.now()

    init {
        viewModelScope.launch {
            val ownerId = sessions.current()?.ownerId ?: return@launch
            val month = requestedMonth ?: resolveInitialMonth(ownerId)
            val existing = budgets.active(ownerId, month)
            if (existing != null) {
                applyPlan(existing)
                refreshComparison(ownerId, month)
            } else {
                when (val suggestion = suggestPlan(ownerId, month)) {
                    is Outcome.Success -> applyPlan(suggestion.value)
                    is Outcome.Failure -> _state.update {
                        it.copy(isLoading = false, month = month, error = suggestion.error)
                    }
                }
            }
        }
    }

    fun setExpectedIncome(text: String) = _state.update { it.copy(expectedIncomeText = MoneyInput.sanitize(text)) }

    fun setPlannedSavings(text: String) = _state.update { it.copy(plannedSavingsText = MoneyInput.sanitize(text)) }

    fun setLimit(category: Category, text: String) = _state.update {
        it.copy(limitTexts = it.limitTexts + (category to MoneyInput.sanitize(text)))
    }

    fun save() {
        val current = _state.value
        val month = current.month ?: return
        if (current.isSaving) return
        _state.update { it.copy(isSaving = true, error = null) }
        viewModelScope.launch {
            val ownerId = sessions.current()?.ownerId
            if (ownerId == null) {
                _state.update { it.copy(isSaving = false) }
                return@launch
            }
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
                    refreshComparison(ownerId, month)
                }
            }
        }
    }

    fun reload() {
        viewModelScope.launch {
            val ownerId = sessions.current()?.ownerId ?: return@launch
            val month = _state.value.month ?: return@launch
            refreshComparison(ownerId, month)
        }
    }

    private suspend fun refreshComparison(ownerId: String, month: JakartaMonth) {
        when (val result = comparePlan(ownerId, month)) {
            is Outcome.Success -> _state.update { it.copy(comparison = result.value) }
            is Outcome.Failure -> Unit
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

    private fun Money.toInputText(): String =
        if (isZero) "" else minor.let { value ->
            val major = value / 100
            val cents = (value % 100).toInt()
            if (cents == 0) major.toString() else "$major,${cents.toString().padStart(2, '0')}"
        }
}
