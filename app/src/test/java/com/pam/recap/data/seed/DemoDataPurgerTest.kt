package com.pam.recap.data.seed

import com.pam.recap.core.money.Money
import com.pam.recap.core.security.FingerprintFactory
import com.pam.recap.core.time.JakartaMonth
import com.pam.recap.data.gmail.LivinQrisParser
import com.pam.recap.domain.model.Category
import com.pam.recap.domain.model.CategoryTotal
import com.pam.recap.domain.model.CoverageStatus
import com.pam.recap.domain.model.MonthlySummary
import com.pam.recap.domain.model.Transaction
import com.pam.recap.domain.model.TransactionSource
import com.pam.recap.domain.usecase.MerchantCategoryRules
import com.pam.recap.testing.FakeImportRepository
import com.pam.recap.testing.FakeSessionRepository
import com.pam.recap.testing.FakeSummaryRepository
import com.pam.recap.testing.FakeTransactionRepository
import com.pam.recap.testing.completeJob
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Instant

/**
 * F1/F2: the purge is structural (demo id prefix, seeded job ids, demo months) and owner-scoped.
 * Demo rows are email-shaped, so the source column cannot be used to tell them apart — the prefix is
 * the only discriminator, and this test pins that an imported or manual row survives.
 */
class DemoDataPurgerTest {

    private val owner = "u_owner"
    private val other = "u_other"
    private val february = JakartaMonth(2026, 2)
    private val now = Instant.parse("2026-02-20T03:00:00Z")
    private val fingerprints = FingerprintFactory { ownerId -> "test-key::$ownerId".toByteArray() }

    /** Real seed rows, then the first one re-labelled as an imported row with a non-demo id. */
    private fun demoRows(ownerId: String = owner): List<Transaction> = DemoDataset.build(
        ownerId = ownerId,
        now = now,
        parser = LivinQrisParser(),
        rules = MerchantCategoryRules(),
        fingerprints = fingerprints,
    )

    private fun importedRow(id: String, source: TransactionSource = TransactionSource.EMAIL): Transaction =
        demoRows().first().copy(
            id = id,
            fingerprint = "fp-$id",
            source = source,
        )

    private fun summary(ownerId: String, month: JakartaMonth): MonthlySummary = MonthlySummary(
        id = "summary-$ownerId-${month.id()}",
        ownerId = ownerId,
        month = month,
        mailboxLabel = null,
        grossInflow = Money.ofMajor(5_000_000),
        grossOutflow = Money.ofMajor(2_000_000),
        netCashFlow = Money.ofMajor(3_000_000),
        classifiedIncome = Money.ofMajor(4_500_000),
        classifiedExpense = Money.ofMajor(1_800_000),
        categoryTotals = listOf(CategoryTotal(Category.HOUSING, Money.ofMajor(900_000), 2)),
        uncategorizedCount = 0,
        manualEntryCount = 0,
        nonIdrExcludedCount = 0,
        transactionCount = 10,
        coverage = CoverageStatus.COMPLETE,
        inputRevision = 1,
        summaryVersion = MonthlySummary.SUMMARY_VERSION,
        generatedAt = now,
    )

    @Test
    fun `purge removes only demo rows, the demo jobs and the demo-month summaries`() = runTest {
        val transactions = FakeTransactionRepository(
            demoRows() + importedRow("imported-1") + importedRow("manual-1", TransactionSource.MANUAL) +
                demoRows(ownerId = other),
        )
        val jobs = FakeImportRepository().apply {
            create(completeJob(owner, february, now).copy(id = DemoDataset.DEMO_JOB_ID))
            create(completeJob(owner, february, now).copy(id = DemoDataset.PREVIOUS_JOB_ID))
            create(completeJob(owner, february, now).copy(id = "job-real"))
        }
        val summaries = FakeSummaryRepository().apply {
            save(summary(owner, february))
            save(summary(owner, JakartaMonth(2026, 3)))
        }
        val purger = DemoDataPurger(transactions, jobs, summaries, FakeSessionRepository())

        val result = purger.purge(owner)

        val demoCount = demoRows().size
        assertEquals(demoCount, result.transactions)
        assertEquals(2, result.importJobs)
        assertEquals(1, result.summaries)
        assertEquals(demoCount + 3, result.total)

        val remaining = transactions.all().map { it.id }.sorted()
        val expected = (listOf("imported-1", "manual-1") + demoRows(ownerId = other).map { it.id }).sorted()
        assertEquals(expected, remaining)
        assertNotNull(jobs.get("job-real"))
        assertNull(jobs.get(DemoDataset.DEMO_JOB_ID))
        assertNull(jobs.get(DemoDataset.PREVIOUS_JOB_ID))
        assertNotNull(summaries.get(owner, JakartaMonth(2026, 3)))
        assertNull(summaries.get(owner, february))
    }

    @Test
    fun `purge is idempotent and owner-scoped`() = runTest {
        val transactions = FakeTransactionRepository(demoRows())
        val purger = DemoDataPurger(
            transactions,
            FakeImportRepository(),
            FakeSummaryRepository(),
            FakeSessionRepository(),
        )

        val first = purger.purge(owner)
        val second = purger.purge(owner)

        assertEquals(demoRows().size, first.transactions)
        assertTrue(second.isEmpty)
        assertEquals(0, purger.countDemoRows(owner))
    }

    @Test
    fun `hasDemoRows is false once the rows are gone`() = runTest {
        val transactions = FakeTransactionRepository(demoRows())
        val purger = DemoDataPurger(
            transactions,
            FakeImportRepository(),
            FakeSummaryRepository(),
            FakeSessionRepository(),
        )

        assertTrue(purger.hasDemoRows(owner))
        purger.purge(owner)
        assertFalse(purger.hasDemoRows(owner))
    }
}
