package com.pam.recap.core.time

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Instant

/**
 * The one-year rolling window (DRAFT-RET-01 / DRAFT-RET-02): the selector must never offer a month
 * older than "today in Jakarta minus one calendar year" and never one after the current month.
 */
class RetentionWindowTest {

    /** 24 November 2026, 13:26 Jakarta time. */
    private val now = Instant.parse("2026-11-24T06:26:59Z")

    private val window = RetentionWindow.of(now)

    @Test
    fun `the window ends at the current month and starts a calendar year back`() {
        assertEquals(JakartaMonth(2026, 11), window.newest)
        assertEquals(JakartaMonth(2025, 11), window.oldest)
    }

    @Test
    fun `a month boundary inside the oldest month makes it partial`() {
        assertTrue(window.oldestPartial)
        assertTrue(window.isPartial(JakartaMonth(2025, 11)))
        assertFalse(window.isPartial(JakartaMonth(2025, 12)))
    }

    @Test
    fun `a window that starts on the first of a month is not partial`() {
        // 1 November 2026, 00:00 Jakarta time.
        val firstOfMonth = RetentionWindow.of(Instant.parse("2026-10-31T17:00:00Z"))

        assertEquals(JakartaMonth(2025, 11), firstOfMonth.oldest)
        assertFalse(firstOfMonth.oldestPartial)
        assertFalse(firstOfMonth.isPartial(JakartaMonth(2025, 11)))
    }

    @Test
    fun `29 February rolls back to 28 February in a non-leap year`() {
        // 29 February 2028, 10:00 Jakarta time; the year before has no 29 February.
        val leapDay = RetentionWindow.of(Instant.parse("2028-02-29T03:00:00Z"))

        assertEquals(JakartaMonth(2028, 2), leapDay.newest)
        assertEquals(JakartaMonth(2027, 2), leapDay.oldest)
        assertTrue(leapDay.oldestPartial)
    }

    @Test
    fun `the forward arrow stops at the current month`() {
        assertFalse(window.canGoLater(JakartaMonth(2026, 11)))
        assertTrue(window.canGoLater(JakartaMonth(2026, 10)))
    }

    @Test
    fun `the back arrow stops at the oldest month`() {
        assertFalse(window.canGoEarlier(JakartaMonth(2025, 11)))
        assertTrue(window.canGoEarlier(JakartaMonth(2025, 12)))
    }

    @Test
    fun `only months inside the window are reachable`() {
        assertTrue(window.contains(JakartaMonth(2025, 11)))
        assertTrue(window.contains(JakartaMonth(2026, 11)))
        assertFalse(window.contains(JakartaMonth(2025, 10)))
        assertFalse(window.contains(JakartaMonth(2026, 12)))
    }

    @Test
    fun `an out-of-window month is clamped to the nearest edge`() {
        assertEquals(JakartaMonth(2025, 11), window.clamp(JakartaMonth(2024, 2)))
        assertEquals(JakartaMonth(2026, 11), window.clamp(JakartaMonth(2027, 1)))
        assertEquals(JakartaMonth(2026, 3), window.clamp(JakartaMonth(2026, 3)))
    }

    @Test
    fun `a full rolling year exposes the current month plus twelve earlier labels`() {
        val months = window.months()

        assertEquals(13, months.size)
        assertEquals(JakartaMonth(2025, 11), months.first())
        assertEquals(JakartaMonth(2026, 11), months.last())
        assertEquals(months.sorted(), months)
    }

    @Test
    fun `the window crosses a year boundary without skipping a month`() {
        // 15 January 2027: the window starts in January 2026.
        val january = RetentionWindow.of(Instant.parse("2027-01-15T03:00:00Z"))

        assertEquals(JakartaMonth(2027, 1), january.newest)
        assertEquals(JakartaMonth(2026, 1), january.oldest)
        assertTrue(january.oldestPartial)
        assertEquals(13, january.months().size)
    }
}
