package com.pam.recap.ui.util

import com.pam.recap.core.money.Money

/**
 * Exact parser for the amount field. Accepts Indonesian grouping (`1.250.000`), a decimal comma
 * (`1.250.000,50`) and a `Rp` prefix, and returns null for anything it cannot read so the caller
 * blocks the save instead of storing a zero (BR-04).
 */
object MoneyInput {

    /** Keeps only digits plus a single decimal comma while the user types. */
    fun sanitize(raw: String): String {
        val digits = raw.filter { it.isDigit() || it == '.' || it == ',' }
        val commaIndex = digits.indexOf(',')
        if (commaIndex < 0) return digits.filter { it.isDigit() || it == '.' }
        val major = digits.substring(0, commaIndex).filter { it.isDigit() || it == '.' }
        val cents = digits.substring(commaIndex + 1).filter { it.isDigit() }.take(2)
        return "$major,$cents"
    }

    fun parse(raw: String): Money? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        val commaIndex = trimmed.lastIndexOf(',')
        val majorPart = if (commaIndex >= 0) trimmed.substring(0, commaIndex) else trimmed
        val centsPart = if (commaIndex >= 0) trimmed.substring(commaIndex + 1) else "00"
        val majorDigits = majorPart.filter { it.isDigit() }
        if (majorDigits.isEmpty()) return null
        val centsDigits = centsPart.filter { it.isDigit() }.padEnd(2, '0').take(2)
        val major = majorDigits.toLongOrNull() ?: return null
        val cents = centsDigits.toIntOrNull() ?: return null
        if (cents !in 0..99) return null
        return Money.ofMajor(major, cents)
    }
}
