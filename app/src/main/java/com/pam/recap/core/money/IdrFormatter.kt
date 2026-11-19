package com.pam.recap.core.money

/**
 * Indonesian rupiah presentation (NFR-02, design system §3).
 *
 * The currency symbol is part of the IDR convention, not translatable copy: the UI language is
 * Indonesian and English alike renders `Rp1.200.000` — never `IDR 1.200.000` or `Rp 1.200.000`.
 * Sign is never part of the formatted amount; the UI adds the explicit direction sign or word.
 */
object IdrFormatter {

    /** ISO 4217 code kept separate from the display symbol for payloads and non-IDR rows. */
    const val CURRENCY_CODE: String = "IDR"

    const val SYMBOL: String = "Rp"

    /** `Rp1.200.000`, `Rp5.100.000,50`, `Rp0`; magnitude only, never a minus sign. */
    fun format(amount: Money): String = SYMBOL + amount.toDecimalString()

    /** `Rp` prefixed amount for a nullable value, used by optional summary fields. */
    fun formatOrNull(amount: Money?): String? = amount?.let(::format)

    /** True when [currencyCode] participates in IDR totals (BR-04). */
    fun isIdr(currencyCode: String?): Boolean = currencyCode.equals(CURRENCY_CODE, ignoreCase = true)
}
