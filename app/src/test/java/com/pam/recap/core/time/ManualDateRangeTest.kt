package com.pam.recap.core.time

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** NFR-15: manual dates cannot be future-dated or fall before the rolling one-year window floor. */
class ManualDateRangeTest {

    private val today: LocalDate = LocalDate.of(2026, 11, 24)
    private val range = ManualDateRange.of(today)

    @Test
    fun `today is the newest allowed date`() {
        assertTrue(range.contains(today))
        assertEquals(today, range.latest)
    }

    @Test
    fun `tomorrow is rejected`() {
        assertFalse(range.contains(today.plusDays(1)))
        assertEquals(today, range.clamp(today.plusDays(1)))
    }

    @Test
    fun `floor is the same day one year earlier and is inclusive`() {
        val floor = LocalDate.of(2025, 11, 24)
        assertEquals(floor, range.earliest)
        assertTrue(range.contains(floor))
        assertTrue(range.contains(floor.plusDays(1)))
    }

    @Test
    fun `one day before the floor is rejected and clamped`() {
        val before = LocalDate.of(2025, 11, 23)
        assertFalse(range.contains(before))
        assertEquals(LocalDate.of(2025, 11, 24), range.clamp(before))
    }

    @Test
    fun `leap day floor follows java calendar clamping`() {
        val leapToday = LocalDate.of(2028, 2, 29)
        val leapRange = ManualDateRange.of(leapToday)

        assertEquals(LocalDate.of(2027, 2, 28), leapRange.earliest)
        assertTrue(leapRange.contains(LocalDate.of(2027, 2, 28)))
        assertFalse(leapRange.contains(LocalDate.of(2027, 2, 27)))
        assertEquals(LocalDate.of(2028, 2, 29), leapRange.clamp(LocalDate.of(2028, 3, 1)))
    }

    @Test
    fun `month end stays inside the window`() {
        val monthEnd = LocalDate.of(2026, 11, 30)
        assertFalse(range.contains(monthEnd))
        assertEquals(today, range.clamp(monthEnd))
    }
}
