package com.pam.recap.data.seed

import com.pam.recap.core.money.Money
import com.pam.recap.core.security.FingerprintFactory
import com.pam.recap.core.time.JakartaMonth
import com.pam.recap.data.gmail.LivinQrisParser
import com.pam.recap.domain.model.Category
import com.pam.recap.domain.model.Direction
import com.pam.recap.domain.model.Transaction
import com.pam.recap.domain.usecase.CashFlowCalculator
import com.pam.recap.domain.usecase.CashFlowEntry
import com.pam.recap.domain.usecase.MerchantCategoryRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Instant

/**
 * A9 assertion: the demo dataset really contains the SRS B5 rows and produces the interesting
 * totals the Lead will look at on the emulator. Runs on the JVM without Room or Hilt because
 * [DemoDataset] is pure.
 */
class DemoDatasetTest {

    private val parser = LivinQrisParser()
    private val rules = MerchantCategoryRules()
    private val fingerprints = FingerprintFactory { ownerId -> "test-key::$ownerId".toByteArray() }
    private val now = Instant.parse("2026-03-01T02:00:00Z")

    @org.junit.Test
    fun `every seeded row id carries the demo prefix the purge relies on`() {
        val ids = rows().map { it.id }
        org.junit.Assert.assertEquals(ids.size, ids.count { it.startsWith(DemoDataset.ID_PREFIX) })
        org.junit.Assert.assertTrue(ids.isNotEmpty())
    }

    private fun rows(ownerId: String = "u_demo"): List<Transaction> = DemoDataset.build(
        ownerId = ownerId,
        now = now,
        parser = parser,
        rules = rules,
        fingerprints = fingerprints,
    )

    private fun totals(month: JakartaMonth = DemoDataset.REFERENCE_MONTH) =
        CashFlowCalculator.compute(
            rows().filter { JakartaMonth.of(it.eventAt) == month }.map(CashFlowEntry::from),
        )

    @Test
    fun `reference month is february 2026`() {
        assertEquals("2026-02", DemoDataset.REFERENCE_MONTH.id())
        assertEquals(2026, DemoDataset.REFERENCE_MONTH.year)
        assertEquals(2, DemoDataset.REFERENCE_MONTH.month)
    }

    @Test
    fun `dataset has at least fourteen transactions and every category`() {
        val februaryRows = rows().filter {
            JakartaMonth.of(it.eventAt) == DemoDataset.REFERENCE_MONTH
        }

        assertTrue("expected >= 14 rows, was ${februaryRows.size}", februaryRows.size >= 14)
        val categories = februaryRows.map { it.category }.toSet()
        Category.entries.forEach { category ->
            assertTrue("missing category ${category.code}", category in categories)
        }
    }

    @Test
    fun `srs b5 rows are present with the exact amounts`() {
        val februaryRows = rows().filter {
            JakartaMonth.of(it.eventAt) == DemoDataset.REFERENCE_MONTH
        }

        val salary = februaryRows.single { it.id == DemoDataset.seedId("manual-salary") }
        assertEquals(Direction.CREDIT, salary.direction)
        assertEquals(Money.ofMajor(5_000_000), salary.amount)
        assertEquals(Category.INCOME, salary.category)

        val housing = februaryRows.single { it.id == DemoDataset.seedId("seed-apartemen") }
        assertEquals(Direction.DEBIT, housing.direction)
        assertEquals(Money.ofMajor(1_000_000), housing.amount)
        assertEquals(Category.HOUSING, housing.category)

        val transfer = februaryRows.single { it.id == DemoDataset.seedId("seed-own-transfer") }
        assertEquals(Direction.DEBIT, transfer.direction)
        assertEquals(Money.ofMajor(200_000), transfer.amount)
        assertTrue(transfer.isInternalTransfer)

        val refund = februaryRows.single { it.id == DemoDataset.seedId("seed-refund-apartemen") }
        assertEquals(Direction.CREDIT, refund.direction)
        assertEquals(Money.ofMajor(100_000), refund.amount)
        assertEquals(DemoDataset.seedId("seed-apartemen"), refund.linkedRefundOf)
    }

    @Test
    fun `reference month totals are the designed ones`() {
        val totals = totals()

        assertEquals(Money.ofMajor(5_850_000), totals.grossInflow)
        assertEquals(Money.ofMajor(2_870_650), totals.grossOutflow)
        assertEquals(Money.ofMajor(2_979_350), totals.netCashFlow)
        assertEquals(Money.ofMajor(5_750_000), totals.classifiedIncome)
        // Housing nets the linked refund down to 900.000 (BR-03).
        assertEquals(Money.ofMajor(900_000), totals.totalFor(Category.HOUSING))
        assertEquals(Money.ofMajor(200_000), totals.totalFor(Category.TRANSFER))
        assertEquals(1, totals.uncategorizedCount)
        assertEquals(2, totals.manualEntryCount)
        assertEquals(1, totals.nonIdrExcludedCount)
    }

    @Test
    fun `non idr row never contributes to idr totals`() {
        val totals = totals()
        val nonIdr = rows().single { it.id == DemoDataset.seedId("non-idr") }

        assertEquals("USD", nonIdr.currency)
        assertEquals(1, totals.nonIdrExcludedCount)
        assertEquals(
            Money.ofMajor(2_870_650),
            totals.grossOutflow,
        )
    }

    @Test
    fun `the uncategorized row stays low confidence`() {
        val uncategorized = rows().single { it.id == DemoDataset.seedId("uncategorized") }

        assertEquals(Category.UNCATEGORIZED, uncategorized.category)
        assertTrue(uncategorized.isLowConfidence)
        assertEquals(0.30f, uncategorized.categoryConfidence)
    }

    @Test
    fun `january rows give the prior month a real denominator`() {
        val january = totals(DemoDataset.REFERENCE_MONTH.previous())

        assertEquals(Money.ofMajor(2_200_000), january.grossOutflow)
        assertTrue(january.transactionCount >= 4)
    }

    @Test
    fun `seed ids and fingerprints are deterministic`() {
        val first = rows()
        val second = rows()

        assertEquals(first.map { it.id }, second.map { it.id })
        assertEquals(first.map { it.fingerprint }, second.map { it.fingerprint })
        assertNotNull(first.first { it.source.name == "EMAIL" }.fingerprint)
        assertEquals(first.size, first.map { it.fingerprint }.distinct().size)
    }

    @Test
    fun `every seeded row belongs to the reference or previous month`() {
        val allowed = setOf(
            DemoDataset.REFERENCE_MONTH,
            DemoDataset.REFERENCE_MONTH.previous(),
        )

        assertTrue(rows().all { JakartaMonth.of(it.eventAt) in allowed })
    }
}
