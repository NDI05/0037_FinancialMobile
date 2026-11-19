package com.pam.recap.core.money

import kotlin.math.abs

/**
 * Prior-period delta (FR-31).
 *
 * A zero or missing denominator is never rendered as `0%` or `+100%`; it becomes
 * [NotApplicable] so the UI can say "Tidak berlaku" (design system §8, NFR-11).
 *
 * [Value.fraction] is a ratio, not a money amount: `0.125` == +12,5 %. The resulting percentage
 * is a derived display value, so a [Double] here does not violate the money-path rule (BR-04).
 */
sealed interface PercentChange {

    data class Value(val fraction: Double) : PercentChange {

        /** Same delta expressed in percentage points: 12.5 for +12,5 %. */
        val percent: Double get() = fraction * 100.0

        val isIncrease: Boolean get() = fraction > 0.0

        val isDecrease: Boolean get() = fraction < 0.0

        val isFlat: Boolean get() = fraction == 0.0
    }

    data object NotApplicable : PercentChange

    companion object {

        /**
         * Delta of [current] against [previous]. [previous] zero or absent means there is no
         * denominator, therefore [NotApplicable] (SRS B5: "percentage change = Not applicable").
         */
        fun of(current: Money, previous: Money?): PercentChange {
            if (previous == null || previous.isZero) return NotApplicable
            val delta = current.minor - previous.minor
            return Value(delta.toDouble() / abs(previous.minor).toDouble())
        }

        /** Null-safe ratio builder for values that are already normalised elsewhere. */
        fun ofFraction(fraction: Double?): PercentChange =
            if (fraction == null || !fraction.isFinite()) NotApplicable else Value(fraction)
    }
}
