package com.pam.recap.domain.usecase

import com.pam.recap.core.money.Money
import com.pam.recap.core.result.AppError
import com.pam.recap.core.result.AppErrorRules
import com.pam.recap.core.result.Outcome
import com.pam.recap.core.time.ClockProvider
import com.pam.recap.core.time.JakartaMonth
import com.pam.recap.domain.model.BudgetComparison
import com.pam.recap.domain.model.BudgetComparisonRow
import com.pam.recap.domain.model.BudgetPlan
import com.pam.recap.domain.model.Category
import com.pam.recap.domain.model.MonthlySummary
import com.pam.recap.domain.model.PlanningGap
import com.pam.recap.domain.model.ValueOrigin
import com.pam.recap.domain.repository.BudgetRepository
import javax.inject.Inject

/**
 * FR-42/FR-43 suggestion seeded from historical averages.
 *
 * Every value is labelled [ValueOrigin.AI_SUGGESTION] because nothing is saved until the user
 * reviews and confirms it (FR-44). Averages are computed on minor units with integer division, so
 * no floating point enters a money value (BR-04).
 */
class SuggestBudgetPlanUseCase @Inject constructor(
    private val computeSummary: ComputeMonthlySummaryUseCase,
    private val clock: ClockProvider,
) {

    suspend operator fun invoke(
        ownerId: String,
        month: JakartaMonth,
        historyMonths: Int = DEFAULT_HISTORY_MONTHS,
    ): Outcome<BudgetPlan> {
        val history = month.previousMonths(historyMonths)
            .map { computeSummary(ownerId, it, persist = false) }
            .filter { it.hasData }
        if (history.isEmpty()) {
            // BR-06: no historical month means nothing to base a suggestion on.
            return Outcome.Failure(AppError.Validation(AppErrorRules.ANALYSIS_NOT_ELIGIBLE))
        }

        val expectedIncome = average(history.map { it.classifiedIncome })
        val limits = Category.expenseCategories.mapNotNull { category ->
            val averageSpend = average(history.map { it.totalFor(category) })
            if (averageSpend.isZero) null else category to averageSpend
        }.toMap()
        val plannedSavings = Money(expectedIncome.minor * SAVINGS_RATE_PERCENT / 100L)
        val now = clock.now()

        val plan = BudgetPlan(
            id = BudgetPlan.idFor(ownerId, month),
            ownerId = ownerId,
            month = month,
            expectedIncome = expectedIncome,
            plannedSavings = plannedSavings,
            limits = limits,
            gap = PlanningGap.of(expectedIncome, plannedSavings, limits).gap,
            revision = 0,
            valueOrigins = limits.keys.associateWith { ValueOrigin.AI_SUGGESTION },
            createdAt = now,
            updatedAt = now,
        )
        return Outcome.Success(plan)
    }

    private fun average(values: List<Money>): Money =
        if (values.isEmpty()) Money.ZERO else Money(values.sumOf { it.minor } / values.size)

    companion object {
        const val DEFAULT_HISTORY_MONTHS: Int = 3
        const val SAVINGS_RATE_PERCENT: Long = 10L
    }
}

/**
 * FR-44 save: explicit, local, and performs no external action. The revision increments so the
 * stored plan is auditable, and the gap is recomputed rather than trusted from the caller.
 */
class SaveBudgetPlanUseCase @Inject constructor(
    private val budgets: BudgetRepository,
    private val clock: ClockProvider,
) {

    suspend operator fun invoke(plan: BudgetPlan): Outcome<BudgetPlan> {
        val values = listOf(plan.expectedIncome, plan.plannedSavings) +
            plan.limits.values.toList()
        if (values.any { it.isNegative }) {
            return Outcome.Failure(AppError.Validation(AppErrorRules.PLAN_VALUE_NEGATIVE))
        }
        if (plan.plannedSavings > plan.expectedIncome) {
            // Savings cannot exceed the income the plan is built on.
            return Outcome.Failure(AppError.Validation(AppErrorRules.BUDGET_GAP_NEGATIVE))
        }

        val existing = budgets.active(plan.ownerId, plan.month)
        val gap = PlanningGap.of(plan.expectedIncome, plan.plannedSavings, plan.limits)
        val saved = plan.copy(
            revision = (existing?.revision ?: 0) + 1,
            gap = gap.gap,
            updatedAt = clock.now(),
        )
        budgets.save(saved)
        return Outcome.Success(saved)
    }
}

/**
 * FR-45 plan vs. actual. Only limit categories present in the plan are compared, and the summary's
 * [MonthlySummary.classifiedExpense]/category nets already exclude internal transfers (BR-02), so
 * a transfer can never look like an over-budget category.
 */
class ComparePlanToActualUseCase @Inject constructor(
    private val budgets: BudgetRepository,
    private val computeSummary: ComputeMonthlySummaryUseCase,
) {

    suspend operator fun invoke(ownerId: String, month: JakartaMonth): Outcome<BudgetComparison> {
        val plan = budgets.active(ownerId, month)
            ?: return Outcome.Failure(AppError.NotFound("budget_plan_${month.id()}"))
        val summary = computeSummary(ownerId, month)

        val rows = plan.limits.entries
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

        return Outcome.Success(
            BudgetComparison(
                month = month,
                plan = plan,
                rows = rows,
                totalActualExpense = Money(rows.sumOf { it.actual.minor }),
                actualSavings = summary.classifiedIncome - summary.classifiedExpense,
                gap = plan.planningGap,
            ),
        )
    }
}
