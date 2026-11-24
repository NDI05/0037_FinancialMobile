package com.pam.recap.core.time

import java.time.LocalDate

/**
 * NFR-15: the inclusive date range a manual row may use.
 *
 * The lower bound is the rolling-window floor (`today` in Asia/Jakarta minus one calendar year,
 * the same rule as [RetentionWindow]); the upper bound is today itself. The backend tolerates
 * future-dated rows, but the client never writes one: a future row would sit outside the recap
 * window, so allowing it would create data the owner cannot see.
 *
 * This type only describes dates; it does not read or write data and does not change the server
 * contract.
 */
data class ManualDateRange(
    val earliest: LocalDate,
    val latest: LocalDate,
) {

    init {
        require(!earliest.isAfter(latest)) { "range must not end before it starts: $earliest..$latest" }
    }

    /** True when [date] is a valid manual-entry date. */
    fun contains(date: LocalDate): Boolean = !date.isBefore(earliest) && !date.isAfter(latest)

    /** Moves an out-of-range date onto the nearest allowed day. */
    fun clamp(date: LocalDate): LocalDate = when {
        date.isBefore(earliest) -> earliest
        date.isAfter(latest) -> latest
        else -> date
    }

    /** The previous-day arrow: disabled once the window floor is displayed. */
    fun canGoEarlier(date: LocalDate): Boolean = date.isAfter(earliest)

    /** The next-day arrow: disabled on today, so the future is never reachable. */
    fun canGoLater(date: LocalDate): Boolean = date.isBefore(latest)

    companion object {
        /** Range for a Jakarta "today"; the caller resolves the clock, this stays deterministic. */
        fun of(today: LocalDate): ManualDateRange = ManualDateRange(today.minusYears(1), today)
    }
}
