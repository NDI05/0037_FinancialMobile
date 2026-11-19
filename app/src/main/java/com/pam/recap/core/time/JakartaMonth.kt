package com.pam.recap.core.time

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.number
import kotlinx.datetime.toLocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.time.Instant

/**
 * One calendar month in the single supported business time zone, `Asia/Jakarta` (OI-10).
 *
 * Month membership is decided by the **parsed transaction event time**, never by the Gmail
 * `internalDate` (BR-01 / NFR-04). [range] therefore produces half-open instants in Jakarta
 * local time, and [searchRange] widens that window so emails whose event time sits on either
 * edge are still fetched (ingestion buffer, OI-05).
 */
data class JakartaMonth(val year: Int, val month: Int) {

    init {
        require(month in 1..MONTHS_PER_YEAR) { "month out of range: $month" }
        require(year in MIN_YEAR..MAX_YEAR) { "year out of range: $year" }
    }

    /** Stable storage/route id, e.g. `"2026-02"`. */
    fun id(): String = "${year.toString().padStart(4, '0')}-${month.toString().padStart(2, '0')}"

    /**
     * Locale-aware display label (`"Februari 2026"` / `"February 2026"`).
     *
     * The label is produced by the platform's locale data rather than by literals in Kotlin, so
     * no user-visible month name is hardcoded (hard rule 2). Feature copy that needs a different
     * shape (e.g. an uppercase eyebrow) still comes from `strings.xml`.
     */
    fun label(locale: Locale = INDONESIAN): String =
        DateTimeFormatter.ofPattern(LABEL_PATTERN, locale)
            .format(java.time.YearMonth.of(year, month).atDay(1))

    /** Half-open month window `[start, nextStart)` in Asia/Jakarta (BR-01). */
    fun range(): MonthRange =
        MonthRange(
            month = this,
            startInclusive = firstDay().atStartOfDayIn(ZONE),
            endExclusive = next().firstDay().atStartOfDayIn(ZONE),
        )

    /**
     * Import search window: the month window widened by [bufferDays] on both sides so a
     * notification that arrives late or whose event time straddles the boundary is not missed
     * (BR-01, OI-05). Membership is re-checked against [contains] after parsing.
     */
    fun searchRange(bufferDays: Int = INGESTION_BUFFER_DAYS): MonthRange {
        require(bufferDays >= 0) { "bufferDays must be >= 0" }
        return range().widenedBy(bufferDays)
    }

    /** True when [instant] falls inside this month in Asia/Jakarta. */
    fun contains(instant: Instant): Boolean =
        instant >= range().startInclusive && instant < range().endExclusive

    fun previous(): JakartaMonth = if (month == 1) JakartaMonth(year - 1, MONTHS_PER_YEAR) else JakartaMonth(year, month - 1)

    fun next(): JakartaMonth = if (month == MONTHS_PER_YEAR) JakartaMonth(year + 1, 1) else JakartaMonth(year, month + 1)

    /** `month.minusMonths(1) == month.previous()`; used for historical averages (FR-42). */
    fun minusMonths(count: Int): JakartaMonth {
        require(count >= 0) { "count must be >= 0" }
        var result = this
        repeat(count) { result = result.previous() }
        return result
    }

    /** Inclusive sequence ending at [this], oldest first; used by budget historical averages. */
    fun previousMonths(count: Int): List<JakartaMonth> =
        (count downTo 1).map { minusMonths(it) }.reversed()

    /** First day of the month as a Jakarta local date. */
    fun firstDay(): LocalDate = LocalDate(year, month, 1)

    override fun toString(): String = id()

    companion object {
        const val MONTHS_PER_YEAR: Int = 12

        /** Ingestion buffer mandated by BR-01 / OI-05. */
        const val INGESTION_BUFFER_DAYS: Int = 3

        /** Historical ingestion range (NFR-15). */
        const val HISTORICAL_MONTHS: Int = 24

        private const val MIN_YEAR = 1970
        private const val MAX_YEAR = 2999
        private const val LABEL_PATTERN = "LLLL yyyy"

        /** Display-only fallback; UI strings still come from `strings.xml`. */
        val INDONESIAN: Locale = Locale.forLanguageTag("id-ID")

        val ZONE: TimeZone = TimeZone.of("Asia/Jakarta")

        /** Month of an instant, resolved in Asia/Jakarta. */
        fun of(instant: Instant, zone: TimeZone = ZONE): JakartaMonth {
            val dateTime: LocalDateTime = instant.toLocalDateTime(zone)
            return JakartaMonth(dateTime.year, dateTime.month.number)
        }

        /** Current month for a clock; kept here so callers do not re-derive the zone. */
        fun of(clock: ClockProvider): JakartaMonth = of(clock.now())

        fun todayInJakarta(clock: ClockProvider): LocalDate {
            val dateTime = clock.now().toLocalDateTime(ZONE)
            return LocalDate(dateTime.year, dateTime.month, dateTime.day)
        }

        /** Parses the canonical `"2026-02"` id, returning null for anything malformed. */
        fun parse(id: String): JakartaMonth? {
            val parts = id.split('-')
            if (parts.size != 2) return null
            val year = parts[0].toIntOrNull() ?: return null
            val month = parts[1].toIntOrNull() ?: return null
            if (month !in 1..MONTHS_PER_YEAR) return null
            return runCatching { JakartaMonth(year, month) }.getOrNull()
        }

        fun fromDate(date: LocalDate): JakartaMonth = JakartaMonth(date.year, date.month.number)
    }
}
