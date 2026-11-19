package com.pam.recap.domain.usecase

import com.pam.recap.core.money.Money
import com.pam.recap.core.money.PercentChange
import com.pam.recap.domain.model.Category
import com.pam.recap.domain.model.Direction
import com.pam.recap.domain.model.ParseStatus
import com.pam.recap.domain.model.TransactionSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §9 test 1 — the binding arithmetic of BR-01…BR-06, including the SRS B5 worked example
 * verbatim.
 */
class CashFlowCalculatorTest {

    private fun entry(
        id: String,
        direction: Direction,
        major: Long,
        category: Category,
        currency: String = "IDR",
        isInternalTransfer: Boolean = false,
        linkedRefundOf: String? = null,
        status: ParseStatus = ParseStatus.POSTED,
        source: TransactionSource = TransactionSource.EMAIL,
    ) = CashFlowEntry(
        id = id,
        direction = direction,
        category = category,
        amount = Money.ofMajor(major),
        currency = currency,
        status = status,
        source = source,
        isInternalTransfer = isInternalTransfer,
        linkedRefundOf = linkedRefundOf,
    )

    /** The exact SRS B5 table: salary, housing, own-account transfer and a linked refund. */
    private fun srsB5Entries(): List<CashFlowEntry> = listOf(
        entry("salary", Direction.CREDIT, 5_000_000, Category.INCOME),
        entry("housing", Direction.DEBIT, 1_000_000, Category.HOUSING),
        entry("own-transfer", Direction.DEBIT, 200_000, Category.TRANSFER, isInternalTransfer = true),
        entry(
            id = "housing-refund",
            direction = Direction.CREDIT,
            major = 100_000,
            category = Category.HOUSING,
            linkedRefundOf = "housing",
        ),
    )

    @Test
    fun `srs b5 worked example matches every asserted total`() {
        val totals = CashFlowCalculator.compute(srsB5Entries())

        assertEquals(Money.ofMajor(5_100_000), totals.grossInflow)
        assertEquals(Money.ofMajor(1_200_000), totals.grossOutflow)
        assertEquals(Money.ofMajor(3_900_000), totals.netCashFlow)
        assertEquals(Money.ofMajor(5_000_000), totals.classifiedIncome)
        // BR-02: classified expense is the gross of DEBIT expense rows…
        assertEquals(Money.ofMajor(1_000_000), totals.classifiedExpense)
        // …while BR-03 nets the linked refund down inside its own category.
        assertEquals(Money.ofMajor(900_000), totals.totalFor(Category.HOUSING))
        assertEquals(Money.ofMajor(100_000), totals.refundedAmount)
        assertEquals(4, totals.transactionCount)
    }

    @Test
    fun `previous month without expense makes the percentage change not applicable`() {
        val current = CashFlowCalculator.compute(srsB5Entries())
        val emptyPreviousMonth = CashFlowCalculator.compute(emptyList())

        assertEquals(PercentChange.NotApplicable, PercentChange.of(current.classifiedExpense, emptyPreviousMonth.classifiedExpense))
        assertEquals(PercentChange.NotApplicable, PercentChange.of(current.classifiedIncome, null))
        assertEquals(PercentChange.NotApplicable, PercentChange.of(Money.ofMajor(10), Money.ZERO))
    }

    @Test
    fun `percent change reports a real ratio when a denominator exists`() {
        val change = PercentChange.of(Money.ofMajor(1_500_000), Money.ofMajor(1_000_000))

        assertTrue(change is PercentChange.Value)
        assertEquals(0.5, (change as PercentChange.Value).fraction, 0.000_001)
        assertEquals(50.0, change.percent, 0.000_001)
        assertTrue(change.isIncrease)
    }

    @Test
    fun `internal transfers stay in gross inflow and outflow but leave classified totals`() {
        val totals = CashFlowCalculator.compute(srsB5Entries())

        // The transfer is part of grossOutflow…
        assertEquals(Money.ofMajor(1_200_000), totals.grossOutflow)
        // …but classified expense only sees the housing debit.
        assertEquals(Money.ofMajor(1_000_000), totals.classifiedExpense)
        assertEquals(Money.ofMajor(200_000), totals.totalFor(Category.TRANSFER))
    }

    @Test
    fun `a refund nets only its linked category`() {
        val entries = srsB5Entries() + entry("food", Direction.DEBIT, 300_000, Category.FOOD)

        val totals = CashFlowCalculator.compute(entries)

        assertEquals(Money.ofMajor(900_000), totals.totalFor(Category.HOUSING))
        assertEquals(Money.ofMajor(300_000), totals.totalFor(Category.FOOD))
    }

    @Test
    fun `an unlinked credit never nets against unrelated expenses`() {
        val entries = listOf(
            entry("housing", Direction.DEBIT, 1_000_000, Category.HOUSING),
            entry("bonus", Direction.CREDIT, 250_000, Category.HOUSING),
        )

        val totals = CashFlowCalculator.compute(entries)

        assertEquals(Money.ofMajor(1_000_000), totals.totalFor(Category.HOUSING))
        assertEquals(Money.ofMajor(250_000), totals.grossInflow)
        assertEquals(Money.ofMajor(0), totals.classifiedIncome)
        assertEquals(Money.ofMajor(0), totals.refundedAmount)
    }

    @Test
    fun `non idr rows are excluded from idr totals and counted for flagging`() {
        val entries = srsB5Entries() + entry(
            id = "usd",
            direction = Direction.DEBIT,
            major = 12,
            category = Category.SHOPPING,
            currency = "USD",
        )

        val totals = CashFlowCalculator.compute(entries)

        assertEquals(Money.ofMajor(1_200_000), totals.grossOutflow)
        assertEquals(Money.ofMajor(3_900_000), totals.netCashFlow)
        assertEquals(1, totals.nonIdrExcludedCount)
        assertEquals(4, totals.transactionCount)
        assertEquals(Money.ZERO, totals.totalFor(Category.SHOPPING))
    }

    @Test
    fun `failed and unresolved notifications never contribute money`() {
        val entries = listOf(
            entry("ok", Direction.DEBIT, 100_000, Category.FOOD),
            entry("failed", Direction.DEBIT, 999_000, Category.FOOD, status = ParseStatus.FAILED),
            entry("unresolved", Direction.DEBIT, 999_000, Category.FOOD, status = ParseStatus.UNRESOLVED),
        )

        val totals = CashFlowCalculator.compute(entries)

        assertEquals(Money.ofMajor(100_000), totals.grossOutflow)
        assertEquals(1, totals.transactionCount)
    }

    @Test
    fun `uncategorized and manual rows are counted separately`() {
        val entries = listOf(
            entry("mystery", Direction.DEBIT, 175_000, Category.UNCATEGORIZED),
            entry("salary", Direction.CREDIT, 750_000, Category.INCOME, source = TransactionSource.MANUAL),
        )

        val totals = CashFlowCalculator.compute(entries)

        assertEquals(1, totals.uncategorizedCount)
        assertEquals(1, totals.manualEntryCount)
        assertEquals(Money.ofMajor(750_000), totals.classifiedIncome)
        assertEquals(Money.ofMajor(750_000), totals.grossInflow)
        assertEquals(Money.ofMajor(175_000), totals.grossOutflow)
        assertEquals(Money.ofMajor(575_000), totals.netCashFlow)
    }

    @Test
    fun `empty month is an explicit zero with no data, never a crash`() {
        val totals = CashFlowCalculator.compute(emptyList())

        assertEquals(CashFlowTotals.EMPTY, totals)
        assertTrue(totals.categoryTotals.isEmpty())
        assertEquals(0, totals.transactionCount)
        assertFalse(totals.netCashFlow.isNegative)
    }

    @Test
    fun `category totals are deterministic and ordered by taxonomy`() {
        val totals = CashFlowCalculator.compute(srsB5Entries())
        val codes = totals.categoryTotals.map { it.category }

        assertEquals(listOf(Category.INCOME, Category.HOUSING, Category.TRANSFER), codes)
    }
}
