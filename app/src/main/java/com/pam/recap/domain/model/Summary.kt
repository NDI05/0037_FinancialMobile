package com.pam.recap.domain.model

import com.pam.recap.core.money.Money
import com.pam.recap.core.money.PercentChange
import com.pam.recap.core.time.JakartaMonth
import kotlin.time.Instant

/** FR-30 coverage of the month, kept distinct from "zero" (NFR-11). */
enum class CoverageStatus {
    COMPLETE,
    PARTIAL,
    EMPTY,
    FAILED,
}

/** One row of the category split; [amount] is the **net** amount for that category (BR-03). */
data class CategoryTotal(
    val category: Category,
    val amount: Money,
    val transactionCount: Int,
    /** Server identity/label for custom categories that have no built-in enum. */
    val serverCode: String? = null,
    val serverLabel: String? = null,
    val serverKind: String? = null,
)

/**
 * FR-30 monthly recap. Every field is derived from persisted transactions; nothing here is a
 * bank-balance claim, and every surface that renders it must also render `EMAIL-DERIVED`
 * (BR-05).
 */
/** Server-computed freshness of a month summary (S2.5). */
enum class SummaryFreshness { CURRENT, STALE, HISTORICAL }

data class MonthlySummary(
    val id: String,
    val ownerId: String,
    val month: JakartaMonth,
    val mailboxLabel: String?,
    val grossInflow: Money,
    val grossOutflow: Money,
    val netCashFlow: Money,
    val classifiedIncome: Money,
    val classifiedExpense: Money,
    val categoryTotals: List<CategoryTotal>,
    val uncategorizedCount: Int,
    val manualEntryCount: Int,
    val nonIdrExcludedCount: Int,
    val transactionCount: Int,
    val coverage: CoverageStatus,
    /** Input revision this summary was computed from; a newer revision marks it outdated (FR-33). */
    val inputRevision: Int,
    val summaryVersion: String,
    val generatedAt: Instant,
    /** FR-22: set when a row changed after this summary was computed. */
    val isOutdated: Boolean = false,
    /** S2.5: CURRENT when computed from the latest revision, STALE when older, HISTORICAL when newer. */
    val freshness: SummaryFreshness = if (isOutdated) SummaryFreshness.STALE else SummaryFreshness.CURRENT,
) {

    fun totalFor(category: Category): Money =
        categoryTotals.firstOrNull { it.category == category }?.amount ?: Money.ZERO

    fun countFor(category: Category): Int =
        categoryTotals.firstOrNull { it.category == category }?.transactionCount ?: 0

    val hasData: Boolean get() = transactionCount > 0

    val isProvisional: Boolean get() = coverage == CoverageStatus.PARTIAL

    val isComplete: Boolean get() = coverage == CoverageStatus.COMPLETE

    /** BR-06: an engine may only speak about a month with at least one complete month behind it. */
    val isEligibleForAnalysis: Boolean get() = coverage != CoverageStatus.EMPTY && coverage != CoverageStatus.FAILED

    companion object {
        const val SUMMARY_VERSION: String = "summary-v1"

        fun empty(
            ownerId: String,
            month: JakartaMonth,
            mailboxLabel: String?,
            coverage: CoverageStatus,
            inputRevision: Int,
            generatedAt: Instant,
        ): MonthlySummary = MonthlySummary(
            id = "$ownerId:${month.id()}:$inputRevision",
            ownerId = ownerId,
            month = month,
            mailboxLabel = mailboxLabel,
            grossInflow = Money.ZERO,
            grossOutflow = Money.ZERO,
            netCashFlow = Money.ZERO,
            classifiedIncome = Money.ZERO,
            classifiedExpense = Money.ZERO,
            categoryTotals = emptyList(),
            uncategorizedCount = 0,
            manualEntryCount = 0,
            nonIdrExcludedCount = 0,
            transactionCount = 0,
            coverage = coverage,
            inputRevision = inputRevision,
            summaryVersion = SUMMARY_VERSION,
            generatedAt = generatedAt,
            isOutdated = false,
        )
    }
}

/**
 * FR-31: summary plus its prior-month deltas. `NotApplicable` is a first-class outcome when the
 * previous month has no denominator, so the UI never prints a fake `0%`.
 */
data class SummaryComparison(
    val summary: MonthlySummary,
    val previousMonth: MonthlySummary?,
    val incomeChange: PercentChange,
    val expenseChange: PercentChange,
    val netChange: PercentChange,
)

/**
 * Cheap SQL aggregate over one month (NFR-07): totals are folded in SQLite, so the 10k-row
 * budget holds even before the full calculator runs. Used for coverage and freshness only —
 * BR-02 classification always goes through `CashFlowCalculator`.
 */
data class MonthTotals(
    val grossInflow: Money,
    val grossOutflow: Money,
    val creditCount: Int,
    val debitCount: Int,
    val transactionCount: Int,
    val nonIdrCount: Int,
    val uncategorizedCount: Int,
    val manualCount: Int,
    val latestEventAt: Instant?,
    val latestUpdatedAt: Instant?,
) {
    val isEmpty: Boolean get() = transactionCount == 0

    companion object {
        val EMPTY: MonthTotals = MonthTotals(
            grossInflow = Money.ZERO,
            grossOutflow = Money.ZERO,
            creditCount = 0,
            debitCount = 0,
            transactionCount = 0,
            nonIdrCount = 0,
            uncategorizedCount = 0,
            manualCount = 0,
            latestEventAt = null,
            latestUpdatedAt = null,
        )
    }
}
