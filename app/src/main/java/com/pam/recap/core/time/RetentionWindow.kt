package com.pam.recap.core.time

import kotlinx.datetime.number
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/**
 * The one-year rolling history window the product may show (DRAFT-RET-01 / DRAFT-RET-02).
 *
 * The floor is "today in Asia/Jakarta minus one calendar year", so the window starts at a start of
 * day and the first month can be only partly covered: with a floor of 24 November 2025 the window
 * includes 24 November onward, not the whole of November 2025. The ceiling is the end of today, so
 * the current month is always the newest reachable month (the month is in progress, never
 * "available in full").
 *
 * This type only describes reachable months. It does not read or write data and does not change the
 * server contract: the API already rejects out-of-window requests with `outside_retention`, and the
 * client must never construct one.
 */
data class RetentionWindow(
    val newest: JakartaMonth,
    val oldest: JakartaMonth,
    /** True when the one-year floor lands after the first day of [oldest]. */
    val oldestPartial: Boolean,
) {

    init {
        require(oldest <= newest) { "window must not end before it starts: $oldest..$newest" }
    }

    /** True when [month] belongs to the window. */
    fun contains(month: JakartaMonth): Boolean = month in oldest..newest

    /** The previous-month arrow: disabled once the boundary month is displayed. */
    fun canGoEarlier(month: JakartaMonth): Boolean = month > oldest

    /** The next-month arrow: disabled on the current (in-progress) month. */
    fun canGoLater(month: JakartaMonth): Boolean = month < newest

    /** True when [month] is the boundary month and that month is only partly covered. */
    fun isPartial(month: JakartaMonth): Boolean = oldestPartial && month == oldest

    /** Moves an out-of-window request onto the nearest reachable month. */
    fun clamp(month: JakartaMonth): JakartaMonth = when {
        month < oldest -> oldest
        month > newest -> newest
        else -> month
    }

    /** Inclusive list of selector labels, oldest first (13 entries for a full rolling year). */
    fun months(): List<JakartaMonth> = buildList {
        var cursor = oldest
        while (cursor <= newest) {
            add(cursor)
            cursor = cursor.next()
        }
    }

    companion object {
        /** Window for the owner's clock, evaluated in Asia/Jakarta. */
        fun of(clock: ClockProvider): RetentionWindow = of(clock.now())

        fun of(now: Instant): RetentionWindow {
            val today = now.toJakartaLocalDate()
            val floor = today.minusYears(1)
            return RetentionWindow(
                newest = JakartaMonth(today.year, today.monthValue),
                oldest = JakartaMonth(floor.year, floor.monthValue),
                // java.time already clamps 29 February to 28 February in a non-leap year.
                oldestPartial = floor.dayOfMonth != 1,
            )
        }

        /** Same calendar rule as [of], for callers that already hold a Jakarta date. */
        fun of(today: java.time.LocalDate): RetentionWindow {
            val floor = today.minusYears(1)
            return RetentionWindow(
                newest = JakartaMonth(today.year, today.monthValue),
                oldest = JakartaMonth(floor.year, floor.monthValue),
                oldestPartial = floor.dayOfMonth != 1,
            )
        }
    }
}

/** Today's wall date in Asia/Jakarta; the one-time conversion used by the boundary math. */
internal fun Instant.toJakartaLocalDate(): java.time.LocalDate {
    val dateTime = toLocalDateTime(JakartaMonth.ZONE)
    return java.time.LocalDate.of(dateTime.year, dateTime.month.number, dateTime.day)
}
