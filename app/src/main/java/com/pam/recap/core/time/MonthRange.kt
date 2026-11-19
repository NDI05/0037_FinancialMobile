package com.pam.recap.core.time

import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/**
 * A half-open interval `[startInclusive, endExclusive)` of instants plus the [month] it belongs
 * to. Half-open is deliberate: `23:59:59` on the last day is inside the month, `00:00:00` on the
 * first day of the next month is outside, with no double counting (BR-01, test 3 of §9).
 */
data class MonthRange(
    val month: JakartaMonth,
    val startInclusive: Instant,
    val endExclusive: Instant,
) {

    init {
        require(startInclusive < endExclusive) { "empty month range" }
    }

    operator fun contains(instant: Instant): Boolean =
        instant >= startInclusive && instant < endExclusive

    /** Window used by the Gmail query (BR-01: ingestion buffer, not a membership change). */
    fun widenedBy(days: Int): MonthRange {
        require(days >= 0) { "days must be >= 0" }
        if (days == 0) return this
        return copy(
            startInclusive = startInclusive - days.days,
            endExclusive = endExclusive + days.days,
        )
    }

    /** Locale-aware label of the underlying month. */
    fun label(locale: java.util.Locale = JakartaMonth.INDONESIAN): String = month.label(locale)
}
