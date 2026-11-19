package com.pam.recap.domain.model

import com.pam.recap.core.money.Money
import com.pam.recap.core.money.sumMoney
import com.pam.recap.core.time.JakartaMonth
import kotlin.time.Instant

/** FR-45: where a plan value came from, so AI-seeded numbers are labelled and reviewable. */
enum class ValueOrigin {
    USER,
    HISTORICAL_AVERAGE,
    AI_SUGGESTION,
}

/**
 * FR-43 gap = expected income − planned savings − Σ per-category limits.
 *
 * A negative gap is a **shortfall**, kept as a negative internal amount so the arithmetic stays
 * exact; the UI renders [shortfall] with explicit copy and never with a minus sign.
 */
data class PlanningGap(
    val expectedIncome: Money,
    val plannedSavings: Money,
    val totalLimits: Money,
    val gap: Money,
) {

    val isShortfall: Boolean get() = gap.isNegative

    /** Positive magnitude of the shortfall; zero when the plan is feasible. */
    val shortfall: Money get() = if (gap.isNegative) gap.absolute else Money.ZERO

    val isBalanced: Boolean get() = gap.isZero

    companion object {

        fun of(
            expectedIncome: Money,
            plannedSavings: Money,
            limits: Map<Category, Money>,
        ): PlanningGap {
            val totalLimits = limits.values.sumMoney()
            return PlanningGap(
                expectedIncome = expectedIncome,
                plannedSavings = plannedSavings,
                totalLimits = totalLimits,
                gap = expectedIncome - plannedSavings - totalLimits,
            )
        }

        fun of(plan: BudgetPlan): PlanningGap =
            of(plan.expectedIncome, plan.plannedSavings, plan.limits)
    }
}

/**
 * FR-42/FR-44 budget envelope for one month. Saving a plan performs no external action: PAM
 * never moves money.
 */
data class BudgetPlan(
    val id: String,
    val ownerId: String,
    val month: JakartaMonth,
    val expectedIncome: Money,
    val plannedSavings: Money,
    val limits: Map<Category, Money>,
    val gap: Money,
    val revision: Int,
    val valueOrigins: Map<Category, ValueOrigin>,
    val createdAt: Instant,
    val updatedAt: Instant,
) {

    val totalLimits: Money get() = limits.values.sumMoney()

    fun limitFor(category: Category): Money = limits[category] ?: Money.ZERO

    fun originFor(category: Category): ValueOrigin = valueOrigins[category] ?: ValueOrigin.USER

    val planningGap: PlanningGap get() = PlanningGap.of(this)

    /** True when any value still needs review before activation (FR-44). */
    val requiresReview: Boolean
        get() = valueOrigins.values.any { it == ValueOrigin.AI_SUGGESTION }

    companion object {
        /** Deterministic id per owner+month: one active plan per month (schema §4). */
        fun idFor(ownerId: String, month: JakartaMonth): String = "$ownerId:${month.id()}"
    }
}

/** FR-45 comparison of one category against the plan; transfers are excluded by the calculator. */
data class BudgetComparisonRow(
    val category: Category,
    val limit: Money,
    val actual: Money,
    val remaining: Money,
) {
    val isOverLimit: Boolean get() = actual > limit

    val utilisation: Double
        get() = if (limit.isZero) {
            if (actual.isZero) 0.0 else 1.0
        } else {
            actual.minor.toDouble() / limit.minor.toDouble()
        }
}

/** FR-45 result set: plan vs. actual for the same month. */
data class BudgetComparison(
    val month: JakartaMonth,
    val plan: BudgetPlan,
    val rows: List<BudgetComparisonRow>,
    val totalActualExpense: Money,
    val actualSavings: Money,
    val gap: PlanningGap,
)
