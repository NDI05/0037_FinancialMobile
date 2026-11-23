package com.pam.recap.ui.feature.status

import com.pam.recap.ui.util.PamDateFormat
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * Coarse age of a timestamp. Deliberately not a live countdown: the plan forbids pretending a
 * schedule is exact, so anything older than a few minutes is expressed in whole units.
 */
sealed interface RelativeTime {
    data object Never : RelativeTime
    data object JustNow : RelativeTime
    data class Minutes(val value: Int) : RelativeTime
    data class Hours(val value: Int) : RelativeTime
    data class Days(val value: Int) : RelativeTime

    /** Older than a week: show a real date instead of "12 hari lalu". */
    data class On(val label: String) : RelativeTime

    companion object {
        fun of(instant: Instant?, now: Instant): RelativeTime {
            if (instant == null) return Never
            val delta = now - instant
            return when {
                delta < 1.minutes -> JustNow
                delta < 1.hours -> Minutes((delta.inWholeMinutes).toInt())
                delta < 1.days -> Hours((delta.inWholeHours).toInt())
                delta < 7.days -> Days((delta.inWholeDays).toInt())
                else -> On(PamDateFormat.date(instant))
            }
        }
    }
}
