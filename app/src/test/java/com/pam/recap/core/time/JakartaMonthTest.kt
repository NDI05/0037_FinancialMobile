package com.pam.recap.core.time

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Instant

/**
 * §9 test 3 — month boundaries in Asia/Jakarta (BR-01). Jakarta is UTC+7 all year, so
 * `2026-02-01T00:00+07:00` is `2026-01-31T17:00:00Z`.
 */
class JakartaMonthTest {

    private val february = JakartaMonth(2026, 2)

    @Test
    fun `range starts at jakarta midnight and ends at the next month start`() {
        val range = february.range()

        assertEquals(Instant.parse("2026-01-31T17:00:00Z"), range.startInclusive)
        assertEquals(Instant.parse("2026-02-28T17:00:00Z"), range.endExclusive)
    }

    @Test
    fun `last second of the month is inside`() {
        val lastSecond = Instant.parse("2026-02-28T16:59:59Z")

        assertTrue(february.contains(lastSecond))
        assertTrue(lastSecond in february.range())
    }

    @Test
    fun `first instant of the next month is outside`() {
        val nextMonthStart = Instant.parse("2026-02-28T17:00:00Z")

        assertFalse(february.contains(nextMonthStart))
        assertFalse(nextMonthStart in february.range())
        assertTrue(JakartaMonth(2026, 3).contains(nextMonthStart))
    }

    @Test
    fun `ids are canonical and round trip`() {
        assertEquals("2026-02", february.id())
        assertEquals("2026-12", JakartaMonth(2026, 12).id())
        assertEquals(february, JakartaMonth.parse("2026-02"))
        assertNull(JakartaMonth.parse("2026-13"))
        assertNull(JakartaMonth.parse("not-a-month"))
    }

    @Test
    fun `previous and next roll over the year`() {
        assertEquals(JakartaMonth(2026, 1), february.previous())
        assertEquals(JakartaMonth(2026, 3), february.next())
        assertEquals(JakartaMonth(2025, 12), JakartaMonth(2026, 1).previous())
        assertEquals(JakartaMonth(2027, 1), JakartaMonth(2026, 12).next())
        assertEquals(JakartaMonth(2025, 11), february.minusMonths(3))
    }

    @Test
    fun `search range is widened by the ingestion buffer only`() {
        val range = february.searchRange()

        assertEquals(Instant.parse("2026-01-28T17:00:00Z"), range.startInclusive)
        assertEquals(Instant.parse("2026-03-03T17:00:00Z"), range.endExclusive)
        assertEquals(JakartaMonth.INGESTION_BUFFER_DAYS, 3)
    }

    @Test
    fun `previous months are oldest first and exclude the current month`() {
        assertEquals(
            listOf(JakartaMonth(2026, 1), JakartaMonth(2025, 12)),
            february.previousMonths(2),
        )
    }

    @Test
    fun `an instant resolves to the jakarta month, not the utc month`() {
        // 2026-02-28T18:30Z is 2026-03-01 01:30 in Jakarta, so it belongs to March.
        val lateFebruaryUtc = Instant.parse("2026-02-28T18:30:00Z")

        assertEquals(JakartaMonth(2026, 3), JakartaMonth.of(lateFebruaryUtc))
    }

    @Test
    fun `month labels are locale aware and never hardcoded`() {
        assertEquals("Februari 2026", february.label(java.util.Locale.forLanguageTag("id-ID")))
        assertEquals("February 2026", february.label(java.util.Locale.ENGLISH))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `month zero is rejected`() {
        JakartaMonth(2026, 0)
    }
}
