package com.pam.recap.data.seed

import com.pam.recap.core.result.SafeLogger
import com.pam.recap.domain.model.DemoPurge
import com.pam.recap.domain.repository.DemoDataMaintenance
import com.pam.recap.domain.repository.ImportRepository
import com.pam.recap.domain.repository.SessionRepository
import com.pam.recap.domain.repository.SummaryRepository
import com.pam.recap.domain.repository.TransactionRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * F1/F2: demo rows belong to the pure offline demo. Once an owner is server-backed, any sample
 * rows written by an earlier build are removed.
 *
 * The purge is deliberately structural, never content-based:
 * - transactions: id starts with [DemoDataset.ID_PREFIX] (the seed id scheme),
 * - import jobs: exactly the two seeded job ids,
 * - summaries: the reference month and the month before it (recomputed on next render).
 *
 * Owner-scoped at every step, so imported (EMAIL) and manual rows are untouchable.
 */
@Singleton
class DemoDataPurger @Inject constructor(
    private val transactions: TransactionRepository,
    private val imports: ImportRepository,
    private val summaries: SummaryRepository,
    private val sessions: SessionRepository,
) : DemoDataMaintenance {

    override suspend fun countDemoRows(ownerId: String): Int =
        transactions.countByIdPrefix(ownerId, DemoDataset.ID_PREFIX)

    override suspend fun purge(ownerId: String): DemoPurge {
        val removedTransactions = transactions.deleteByIdPrefix(ownerId, DemoDataset.ID_PREFIX)
        val removedJobs = listOf(DemoDataset.DEMO_JOB_ID, DemoDataset.PREVIOUS_JOB_ID)
            .count { jobId -> imports.delete(ownerId, jobId) }
        val months = listOf(DemoDataset.REFERENCE_MONTH, DemoDataset.REFERENCE_MONTH.previous())
        val removedSummaries = months.sumOf { month -> summaries.delete(ownerId, month) }

        val result = DemoPurge(
            transactions = removedTransactions,
            importJobs = removedJobs,
            summaries = removedSummaries,
        )
        if (!result.isEmpty) {
            // The owner may seed again if this device is ever used in offline demo mode.
            runCatching { sessions.clearDemoSeeded(ownerId) }
            SafeLogger.event(TAG, "demo_rows_purged")
        }
        return result
    }

    private companion object {
        const val TAG = "DemoDataPurger"
    }
}
