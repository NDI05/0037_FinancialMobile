package com.pam.recap.data.seed

import com.pam.recap.core.security.FingerprintFactory
import com.pam.recap.core.time.ClockProvider
import com.pam.recap.domain.model.ImportJob
import com.pam.recap.domain.model.ImportState
import com.pam.recap.domain.repository.DemoDataPort
import com.pam.recap.domain.repository.ImportRepository
import com.pam.recap.domain.repository.LivinNotificationParser
import com.pam.recap.domain.repository.SessionRepository
import com.pam.recap.domain.repository.TransactionRepository
import com.pam.recap.domain.usecase.ComputeMonthlySummaryUseCase
import com.pam.recap.domain.usecase.MerchantCategoryRules
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A9 seeding, invoked once for a signed-in demo session (OI-01).
 *
 * The rows themselves are built by the pure [DemoDataset]; this class only decides whether a seed
 * is needed and records the surrounding facts: a COMPLETE import job (so coverage reads Complete
 * for the reference month), a job for the prior month, and precomputed summaries so the first
 * frame has data (NFR-06). Deterministic seed ids plus the fingerprint unique index make a re-run
 * a no-op.
 */
@Singleton
class DemoDataSeeder @Inject constructor(
    private val sessions: SessionRepository,
    private val transactions: TransactionRepository,
    private val imports: ImportRepository,
    private val parser: LivinNotificationParser,
    private val categoryRules: MerchantCategoryRules,
    private val fingerprints: FingerprintFactory,
    private val computeSummary: ComputeMonthlySummaryUseCase,
    private val clock: ClockProvider,
) : DemoDataPort {

    override suspend fun seedIfNeeded(ownerId: String): Boolean {
        if (sessions.isDemoSeeded(ownerId)) return false

        val referenceMonth = DemoDataset.REFERENCE_MONTH
        if (transactions.monthTotals(ownerId, referenceMonth).transactionCount > 0) {
            sessions.markDemoSeeded(ownerId)
            return false
        }

        val now = clock.now()
        val rows = DemoDataset.build(
            ownerId = ownerId,
            now = now,
            parser = parser,
            rules = categoryRules,
            fingerprints = fingerprints,
        )
        transactions.insertIgnoringDuplicates(ownerId, rows)

        val fixtureCount = LivinFixtures.demoSeedFixtures().size
        imports.create(
            ImportJob(
                id = DemoDataset.DEMO_JOB_ID,
                ownerId = ownerId,
                month = referenceMonth,
                state = ImportState.COMPLETE,
                pagesProcessed = 1,
                pagesExpected = 1,
                matchedCount = fixtureCount,
                importedCount = fixtureCount,
                duplicateCount = 0,
                skippedCount = 0,
                unresolvedCount = 0,
                templateVersion = "v1",
                startedAt = now,
                finishedAt = now,
            ),
        )
        imports.create(
            ImportJob(
                id = DemoDataset.PREVIOUS_JOB_ID,
                ownerId = ownerId,
                month = referenceMonth.previous(),
                state = ImportState.COMPLETE,
                pagesProcessed = 1,
                pagesExpected = 1,
                matchedCount = 0,
                importedCount = 0,
                duplicateCount = 0,
                skippedCount = 0,
                unresolvedCount = 0,
                templateVersion = "v1",
                startedAt = now,
                finishedAt = now,
            ),
        )

        computeSummary(ownerId, referenceMonth, persist = true)
        computeSummary(ownerId, referenceMonth.previous(), persist = true)

        sessions.markDemoSeeded(ownerId)
        return true
    }
}
