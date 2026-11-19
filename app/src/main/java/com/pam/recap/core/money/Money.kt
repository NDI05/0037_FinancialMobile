package com.pam.recap.core.money

import kotlinx.serialization.Serializable

/**
 * Exact money in minor units (1/100 IDR) per BR-04.
 *
 * The value is a [Long] so every arithmetic step is exact base-10; no `Double`/`Float` ever
 * enters a money path. Rounding happens only at display time.
 *
 * Rendering rule: an amount is **never** rendered with a minus sign (design system §1,
 * NFR-02). [toDecimalString] therefore always renders the magnitude and callers express the
 * direction with an explicit sign, colour *and* the words "Masuk"/"Keluar".
 */
@Serializable
@JvmInline
value class Money(val minor: Long) : Comparable<Money> {

    val isZero: Boolean get() = minor == 0L

    val isNegative: Boolean get() = minor < 0L

    val isPositive: Boolean get() = minor > 0L

    /** Magnitude only; sign is a presentation concern, never a money value. */
    val absolute: Money get() = if (minor < 0L) Money(-minor) else this

    operator fun plus(other: Money): Money = Money(minor + other.minor)

    operator fun minus(other: Money): Money = Money(minor - other.minor)

    operator fun unaryMinus(): Money = Money(-minor)

    /** Scaling helper used by derived (non-authoritative) values such as averages. */
    operator fun times(factor: Int): Money = Money(minor * factor)

    operator fun times(factor: Long): Money = Money(minor * factor)

    override fun compareTo(other: Money): Int = minor.compareTo(other.minor)

    /**
     * Indonesian numeric rendering: `.` grouping, decimal `,`, and the cents group only when
     * non-zero. No currency symbol and no sign; [IdrFormatter] adds the symbol.
     *
     * Examples: `120000000` -> `"1.200.000"`, `510000050` -> `"5.100.000,50"`, `0` -> `"0"`.
     */
    fun toDecimalString(): String {
        // Long.MIN_VALUE would overflow on negation; no real amount reaches 9.2e16 IDR cents,
        // but the magnitude is clamped so a minus sign can never leak into the rendering.
        val magnitude = when {
            minor == Long.MIN_VALUE -> Long.MAX_VALUE
            minor < 0L -> -minor
            else -> minor
        }
        val majorUnits = magnitude / MINOR_UNITS_PER_UNIT
        val cents = (magnitude % MINOR_UNITS_PER_UNIT).toInt()
        val grouped = groupThousands(majorUnits)
        return if (cents == 0) grouped else "$grouped,${cents.toString().padStart(2, '0')}"
    }

    override fun toString(): String = toDecimalString()

    companion object {
        const val MINOR_UNITS_PER_UNIT: Long = 100L

        val ZERO: Money = Money(0L)

        fun zero(): Money = ZERO

        fun ofMinor(minor: Long): Money = Money(minor)

        /** Builds an amount from whole rupiah plus an optional cents part (0..99). */
        fun ofMajor(major: Long, cents: Int = 0): Money {
            require(cents in 0..99) { "cents out of range: $cents" }
            return Money(major * MINOR_UNITS_PER_UNIT + cents)
        }

        private fun groupThousands(value: Long): String {
            val digits = value.toString()
            if (digits.length <= 3) return digits
            val out = StringBuilder(digits.length + digits.length / 3)
            var digitsSeen = 0
            for (index in digits.indices.reversed()) {
                if (digitsSeen > 0 && digitsSeen % 3 == 0) out.append('.')
                out.append(digits[index])
                digitsSeen++
            }
            return out.reverse().toString()
        }
    }
}

/** Sum helper that keeps the fold on minor units (NFR-07). */
fun Iterable<Money>.sumMoney(): Money = fold(Money.ZERO) { acc, money -> acc + money }
