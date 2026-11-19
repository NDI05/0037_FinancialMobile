package com.pam.recap.ui.util

import com.pam.recap.core.time.JakartaMonth
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.time.Instant

/**
 * Locale-aware date/time presentation for rows and headers.
 *
 * These helpers produce locale data (month names, day names, clock format), not copy: every
 * surrounding word stays in `strings.xml`. The zone is always Asia/Jakarta so a parsed event time
 * and its displayed time can never disagree (BR-01).
 */
object PamDateFormat {

    private val zone: ZoneId = ZoneId.of(JakartaMonth.ZONE.id)

    fun localDateTime(instant: Instant): LocalDateTime =
        LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(instant.toEpochMilliseconds()), zone)

    /** `08:12` */
    fun time(instant: Instant, locale: Locale = Locale.getDefault()): String =
        localDateTime(instant).format(DateTimeFormatter.ofPattern("HH:mm", locale))

    /** `Sabtu, 15 Feb` — used for the list's day headers. */
    fun dayHeader(instant: Instant, locale: Locale = Locale.getDefault()): String =
        localDateTime(instant).format(DateTimeFormatter.ofPattern("EEEE, d MMM", locale))

    /** `15 Feb 2026` */
    fun date(instant: Instant, locale: Locale = Locale.getDefault()): String =
        localDateTime(instant).format(DateTimeFormatter.ofPattern("d MMM uuuu", locale))

    /** `15 Feb 2026 · 08:12` */
    fun dateTime(instant: Instant, locale: Locale = Locale.getDefault()): String =
        localDateTime(instant).format(DateTimeFormatter.ofPattern("d MMM uuuu HH:mm", locale))

    /** `Feb` — the compact label above a MonthStrip cell. */
    fun shortMonth(instant: Instant, locale: Locale = Locale.getDefault()): String =
        localDateTime(instant).format(DateTimeFormatter.ofPattern("MMM", locale))

    /** Day-of-month number in the locale's digits, e.g. `15`. */
    fun dayOfMonth(instant: Instant, locale: Locale = Locale.getDefault()): String =
        localDateTime(instant).format(DateTimeFormatter.ofPattern("d", locale))

    /**
     * Relative freshness label parts. The caller owns the words: this returns the number and the
     * unit so the string resource can place them.
     */
    fun daysBetween(from: Instant, to: Instant): Long {
        val millis = to.toEpochMilliseconds() - from.toEpochMilliseconds()
        return millis / MILLIS_PER_DAY
    }

    /** `2026-02-15` for the provenance block. */
    fun isoDate(instant: Instant): String = localDateTime(instant).format(DateTimeFormatter.ISO_LOCAL_DATE)

    private const val MILLIS_PER_DAY = 24L * 60L * 60L * 1000L
}
