package com.pam.recap.data.ai

import com.pam.recap.domain.model.AiAggregatePayload
import com.pam.recap.domain.model.AiCategoryTotal
import com.pam.recap.domain.model.AiPlanSummary
import com.pam.recap.domain.model.AiPriorMonthTotals
import com.pam.recap.domain.model.BudgetPlan
import com.pam.recap.domain.model.MonthlySummary
import com.pam.recap.domain.repository.AiPayloadSource
import javax.inject.Inject
import javax.inject.Singleton

/**
 * FR-35 building block: converts a [MonthlySummary] (and optional prior month/plan) into the
 * aggregate-only [AiAggregatePayload].
 *
 * The builder has no access to transactions, so it *cannot* emit a body, subject, sender,
 * message id, token, PAN, merchant label or note. The only strings it can produce are the
 * month id (`2026-02`), the coverage enum name and allow-listed category codes.
 */
@Singleton
class AiPayloadBuilder @Inject constructor() : AiPayloadSource {

    override fun build(
        summary: MonthlySummary,
        previousMonth: MonthlySummary?,
        plan: BudgetPlan?,
    ): AiAggregatePayload = AiAggregatePayload(
        schemaVersion = AiAggregatePayload.SCHEMA_VERSION,
        monthId = summary.month.id(),
        coverage = summary.coverage.name,
        grossInflowMinor = summary.grossInflow.minor,
        grossOutflowMinor = summary.grossOutflow.minor,
        netCashFlowMinor = summary.netCashFlow.minor,
        classifiedIncomeMinor = summary.classifiedIncome.minor,
        classifiedExpenseMinor = summary.classifiedExpense.minor,
        transactionCount = summary.transactionCount,
        uncategorizedCount = summary.uncategorizedCount,
        manualEntryCount = summary.manualEntryCount,
        nonIdrExcludedCount = summary.nonIdrExcludedCount,
        categoryTotals = summary.categoryTotals.map { total ->
            AiCategoryTotal(
                categoryCode = total.category.code,
                amountMinor = total.amount.minor,
                transactionCount = total.transactionCount,
            )
        },
        priorMonth = previousMonth?.let { previous ->
            AiPriorMonthTotals(
                monthId = previous.month.id(),
                expenseMinor = previous.classifiedExpense.minor,
                incomeMinor = previous.classifiedIncome.minor,
            )
        },
        plan = plan?.let { budget ->
            AiPlanSummary(
                expectedIncomeMinor = budget.expectedIncome.minor,
                plannedSavingsMinor = budget.plannedSavings.minor,
                categoryLimitTotals = budget.limits.entries
                    .sortedBy { it.key.ordinal }
                    .map { (category, amount) ->
                        AiCategoryTotal(
                            categoryCode = category.code,
                            amountMinor = amount.minor,
                            transactionCount = 0,
                        )
                    },
            )
        },
    )
}
