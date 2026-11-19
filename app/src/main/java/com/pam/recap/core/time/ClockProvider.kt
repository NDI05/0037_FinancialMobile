package com.pam.recap.core.time

import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Time source port so every use case is deterministic in tests.
 *
 * Pure Kotlin: [SystemClockProvider] only delegates to the kotlin.time clock. Android callers
 * reach it through Hilt; nothing in `domain` or `core` touches `System.currentTimeMillis()`.
 */
interface ClockProvider {

    fun now(): Instant

    fun nowMillis(): Long = now().toEpochMilliseconds()

    fun currentMonth(): JakartaMonth = JakartaMonth.of(now())
}

/** Production clock. Injectable seam for [ClockProvider]. */
class SystemClockProvider : ClockProvider {
    override fun now(): Instant = Clock.System.now()
}
