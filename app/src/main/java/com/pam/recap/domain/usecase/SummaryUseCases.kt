package com.pam.recap.domain.usecase

import com.pam.recap.core.money.PercentChange
import com.pam.recap.core.time.ClockProvider
import com.pam.recap.core.time.JakartaMonth
import com.pam.recap.domain.model.CategoryTotal
import com.pam.recap.domain.model.CoverageStatus
import com.pam.recap.domain.model.ImportJob
import com.pam.recap.domain.model.ImportState
import com.pam.recap.domain.model.MonthTotals
import com.pam.recap.domain.model.MonthlySummary
import com.pam.recap.domain.model.SummaryComparison
import com.pam.recap.domain.repository.GmailConnectionRepository
import com.pam.recap.domain.repository.ImportRepository
import com.pam.recap.domain.repository.SummaryRepository
import com.pam.recap.domain.repository.TransactionRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import javax.inject.Inject

/**
 * FR-30 summary computation.
 *
 * The arithmetic itself lives in the pure [CashFlowCalculator]; this use case only decides what
 * goes into it, resolves the coverage state (FR-19/FR-20, NFR-11) and persists the versioned
 * result with the input revision (FR-33).
 */
class ComputeMonthlySummaryUseCase @Inject constructor(
    private val transactions: TransactionRepository,
    private val summaries: SummaryRepository,
    private val imports: ImportRepository,
    private val gmailConnection: GmailConnectionRepository,
    private val clock: ClockProvider,
) {

    suspend operator fun invoke(
        ownerId: String,
        month: JakartaMonth,
        persist: Boolean = true,
    ): MonthlySummary {
        val entries = transactions.listMonth(ownerId, month).map(CashFlowEntry::from)
        val totals = CashFlowCalculator.compute(entries)
        val job = imports.latestForMonth(ownerId, month)
        val revision = transactions.monthRevision(ownerId, month)
        val connection = gmailConnection.get(ownerId)

        val summary = MonthlySummary(
            id = summaryId(ownerId, month, revision),
            ownerId = ownerId,
            month = month,
            mailboxLabel = connection?.mailboxLabel,
            grossInflow = totals.grossInflow,
            grossOutflow = totals.grossOutflow,
            netCashFlow = totals.netCashFlow,
            classifiedIncome = totals.classifiedIncome,
            classifiedExpense = totals.classifiedExpense,
            categoryTotals = totals.categoryTotals,
            uncategorizedCount = totals.uncategorizedCount,
            manualEntryCount = totals.manualEntryCount,
            nonIdrExcludedCount = totals.nonIdrExcludedCount,
            transactionCount = totals.transactionCount,
            coverage = CoverageResolver.resolve(job, totals.transactionCount),
            inputRevision = revision,
            summaryVersion = MonthlySummary.SUMMARY_VERSION,
            generatedAt = clock.now(),
        )

        if (persist) summaries.save(summary)
        return summary
    }

    /** FR-31: current summary plus prior-month deltas, with `NotApplicable` when undefined. */
    suspend fun withComparison(ownerId: String, month: JakartaMonth): SummaryComparison {
        val current = invoke(ownerId, month)
        val previous = invoke(ownerId, month.previous())
        return SummaryComparison(
            summary = current,
            previousMonth = previous,
            incomeChange = PercentChange.of(current.classifiedIncome, previous.classifiedIncome),
            expenseChange = PercentChange.of(current.classifiedExpense, previous.classifiedExpense),
            netChange = PercentChange.of(current.netCashFlow, previous.netCashFlow),
        )
    }

    private fun summaryId(ownerId: String, month: JakartaMonth, revision: Int): String =
        "$ownerId:${month.id()}:$revision"
}

/**
 * NFR-11: the four coverage states stay distinguishable, and `EMPTY` never means "no spending".
 * A month that only has manual entries reports `PARTIAL`, because email coverage was never
 * established (BR-05).
 */
object CoverageResolver {

    fun resolve(job: ImportJob?, transactionCount: Int): CoverageStatus {
        val hasData = transactionCount > 0
        return when (job?.state) {
            ImportState.COMPLETE -> if (hasData) CoverageStatus.COMPLETE else CoverageStatus.EMPTY
            ImportState.EMPTY -> CoverageStatus.EMPTY
            ImportState.CREATED, ImportState.RUNNING, ImportState.PARTIAL -> CoverageStatus.PARTIAL
            ImportState.FAILED, ImportState.PERMISSION_REQUIRED ->
                if (hasData) CoverageStatus.PARTIAL else CoverageStatus.FAILED

            null -> if (hasData) CoverageStatus.PARTIAL else CoverageStatus.EMPTY
        }
    }
}

/** Cheap, reactive coverage read for the overview banner (FR-19, NFR-06). */
class ObserveCoverageUseCase @Inject constructor(
    private val transactions: TransactionRepository,
    private val imports: ImportRepository,
) {

    operator fun invoke(ownerId: String, month: JakartaMonth): Flow<CoverageStatus> =
        combine(
            transactions.observeMonthTotals(ownerId, month),
            imports.observeLatestForMonth(ownerId, month),
        ) { totals: MonthTotals, job: ImportJob? ->
            CoverageResolver.resolve(job, totals.transactionCount)
        }
}

/**
 * FR-32 drill-down: the category totals of a stored summary, used by the overview list and the
 * category detail screen.
 */
class ObserveCategoryTotalsUseCase @Inject constructor(
    private val summaries: SummaryRepository,
) {

    operator fun invoke(ownerId: String, month: JakartaMonth): Flow<List<CategoryTotal>> =
        summaries.observeSummary(ownerId, month).map { summary -> summary?.categoryTotals.orEmpty() }
}
