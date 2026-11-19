package com.pam.recap.domain.model

import com.pam.recap.core.money.Money
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** §9 test 8 — FR-43 planning gap arithmetic on exact minor units. */
class PlanningGapTest {

    @Test
    fun `income minus savings minus limits is a shortfall of 500 000`() {
        val gap = PlanningGap.of(
            expectedIncome = Money.ofMajor(5_000_000),
            plannedSavings = Money.ofMajor(1_000_000),
            limits = mapOf(
                Category.HOUSING to Money.ofMajor(2_000_000),
                Category.FOOD to Money.ofMajor(1_500_000),
                Category.TRANSPORT to Money.ofMajor(1_000_000),
            ),
        )

        assertEquals(Money.ofMajor(4_500_000), gap.totalLimits)
        assertEquals(Money.ofMajor(-500_000), gap.gap)
        assertEquals(Money.ofMajor(500_000), gap.shortfall)
        assertTrue(gap.isShortfall)
        assertFalse(gap.isBalanced)
    }

    @Test
    fun `a feasible plan has no shortfall`() {
        val gap = PlanningGap.of(
            expectedIncome = Money.ofMajor(5_000_000),
            plannedSavings = Money.ofMajor(1_000_000),
            limits = mapOf(Category.FOOD to Money.ofMajor(2_000_000)),
        )

        assertEquals(Money.ofMajor(2_000_000), gap.gap)
        assertFalse(gap.isShortfall)
        assertEquals(Money.ZERO, gap.shortfall)
        assertFalse(gap.isBalanced)
    }

    @Test
    fun `an exactly balanced plan reports a zero gap`() {
        val gap = PlanningGap.of(
            expectedIncome = Money.ofMajor(3_000_000),
            plannedSavings = Money.ofMajor(1_000_000),
            limits = mapOf(Category.FOOD to Money.ofMajor(2_000_000)),
        )

        assertTrue(gap.gap.isZero)
        assertTrue(gap.isBalanced)
        assertFalse(gap.isShortfall)
    }

    @Test
    fun `no limits sums to zero instead of failing`() {
        val gap = PlanningGap.of(
            expectedIncome = Money.ofMajor(5_000_000),
            plannedSavings = Money.ofMajor(1_000_000),
            limits = emptyMap(),
        )

        assertEquals(Money.ZERO, gap.totalLimits)
        assertEquals(Money.ofMajor(4_000_000), gap.gap)
    }

    @Test
    fun `gap is exact to the cent`() {
        val gap = PlanningGap.of(
            expectedIncome = Money(5_000_050),
            plannedSavings = Money(1_000_000),
            limits = mapOf(Category.FOOD to Money(2_000_050)),
        )

        assertEquals(Money(2_000_000), gap.gap)
        assertEquals(Money(3_000_050), gap.totalLimits + gap.gap - gap.plannedSavings + Money(0))
    }
}
