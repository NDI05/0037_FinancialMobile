package com.pam.recap.core.money

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §9 test 2 — IDR presentation conventions (NFR-02): `.` grouping, decimal `,`, cents only when
 * non-zero, `Rp` with no space, and no minus sign ever.
 */
class IdrFormatterTest {

    @Test
    fun `groups thousands with dots`() {
        assertEquals("Rp1.200.000", IdrFormatter.format(Money.ofMajor(1_200_000)))
        assertEquals("Rp45.000", IdrFormatter.format(Money.ofMajor(45_000)))
        assertEquals("Rp999", IdrFormatter.format(Money.ofMajor(999)))
        assertEquals("Rp1.000", IdrFormatter.format(Money.ofMajor(1_000)))
        assertEquals("Rp1.234.567", IdrFormatter.format(Money.ofMajor(1_234_567)))
    }

    @Test
    fun `renders cents only when they are non zero`() {
        assertEquals("Rp5.100.000", IdrFormatter.format(Money(510_000_000)))
        assertEquals("Rp5.100.000,50", IdrFormatter.format(Money(510_000_050)))
        assertEquals("Rp1.234.567,89", IdrFormatter.format(Money(123_456_789)))
        assertEquals("Rp0,05", IdrFormatter.format(Money(5)))
    }

    @Test
    fun `renders zero without decimals`() {
        assertEquals("Rp0", IdrFormatter.format(Money.ZERO))
        assertEquals("Rp0", IdrFormatter.format(Money.ofMajor(0)))
    }

    @Test
    fun `never renders a minus sign for a negative amount`() {
        val rendered = IdrFormatter.format(Money.ofMajor(-1_200_000))

        assertEquals("Rp1.200.000", rendered)
        assertFalse(rendered.contains("-"))
        assertFalse(Money.ofMajor(-1_200_000).toDecimalString().contains("-"))
    }

    @Test
    fun `output is plain ASCII so it survives 200 percent font scale`() {
        val samples = listOf(
            Money.ofMajor(5_100_000),
            Money(510_000_050),
            Money(5),
            Money.ofMajor(-42),
        )

        samples.forEach { money ->
            val rendered = IdrFormatter.format(money)
            // No narrow/no-break spaces or other typographic characters that break wrapping.
            assertTrue("non-ascii in $rendered", rendered.all { it.code < 128 })
            assertFalse("space after the symbol in $rendered", rendered.startsWith("Rp "))
        }
    }

    @Test
    fun `decimal string excludes the currency symbol`() {
        assertEquals("1.200.000", Money.ofMajor(1_200_000).toDecimalString())
        assertEquals("0", Money.ZERO.toDecimalString())
    }

    @Test
    fun `currency check only accepts IDR`() {
        assertTrue(IdrFormatter.isIdr("IDR"))
        assertTrue(IdrFormatter.isIdr("idr"))
        assertFalse(IdrFormatter.isIdr("USD"))
        assertFalse(IdrFormatter.isIdr(null))
    }

    @Test
    fun `money arithmetic is exact on minor units`() {
        val salary = Money.ofMajor(5_000_000)
        val refund = Money.ofMajor(100_000)

        assertEquals(Money.ofMajor(5_100_000), salary + refund)
        assertEquals(Money.ofMajor(4_900_000), salary - refund)
        assertEquals(Money.ofMajor(15_000_000), salary * 3)
        assertTrue(salary > refund)
        assertEquals(Money.ofMajor(4_900_000), (refund - salary).absolute)
    }
}
