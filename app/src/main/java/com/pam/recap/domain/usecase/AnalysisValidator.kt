package com.pam.recap.domain.usecase

import com.pam.recap.domain.model.AiAggregatePayload
import com.pam.recap.domain.model.MetricKeys
import com.pam.recap.domain.model.RawAnalysis
import com.pam.recap.domain.model.ValidationOutcome
import javax.inject.Inject
import javax.inject.Singleton

/**
 * FR-36/FR-37 output validation, extracted from the request use case so it is testable on its own
 * (§9 test 7).
 *
 * A provider output is publishable only when:
 * 1. it produced observations at all;
 * 2. every observation/suggestion cites at least one **known** summary metric key (FR-36 evidence);
 * 3. no text carries an amount that cannot be traced to the payload — an invented total is a
 *    hallucination, not a rounding difference (BR-04, FR-37);
 * 4. no text carries instruction-like phrasing; provider output is untrusted data, never
 *    instructions (NFR-13);
 * 5. a suggested amount is positive and does not exceed the month's inflow.
 *
 * Every issue is a technical code; the UI maps rejection to copy from `strings.xml`.
 */
@Singleton
class AnalysisValidator @Inject constructor() {

    fun validate(analysis: RawAnalysis?, payload: AiAggregatePayload): ValidationOutcome {
        if (analysis == null) {
            return ValidationOutcome(isValid = false, issues = listOf(ISSUE_NO_OUTPUT))
        }

        val issues = mutableListOf<String>()
        if (analysis.observations.isEmpty()) issues += ISSUE_NO_OBSERVATIONS

        val allowedAmounts = allowedAmounts(payload, analysis)

        analysis.observations.forEach { observation ->
            validateEvidence(observation.metricKeys, issues)
            val text = observation.text
            if (text.isBlank()) issues += ISSUE_BLANK_TEXT
            if (containsInstruction(text)) issues += ISSUE_UNTRUSTED_INSTRUCTION
            if (hasInventedAmount(text, allowedAmounts)) issues += ISSUE_INVENTED_AMOUNT
        }

        analysis.suggestions.forEach { suggestion ->
            validateEvidence(suggestion.metricKeys, issues)
            val text = suggestion.text
            if (text.isBlank()) issues += ISSUE_BLANK_TEXT
            if (containsInstruction(text)) issues += ISSUE_UNTRUSTED_INSTRUCTION
            if (hasInventedAmount(text, allowedAmounts)) issues += ISSUE_INVENTED_AMOUNT

            val amount = suggestion.suggestedAmountMinor
            if (amount != null) {
                val exceedsInflow = payload.grossInflowMinor > 0L && amount > payload.grossInflowMinor
                if (amount <= 0L || exceedsInflow) issues += ISSUE_SUGGESTION_AMOUNT
            }
        }

        return ValidationOutcome(isValid = issues.isEmpty(), issues = issues.distinct())
    }

    private fun validateEvidence(metricKeys: List<String>, issues: MutableList<String>) {
        when {
            metricKeys.isEmpty() -> issues += ISSUE_MISSING_EVIDENCE
            metricKeys.any { it !in MetricKeys.all } -> issues += ISSUE_UNKNOWN_METRIC
        }
    }

    /**
     * Every amount the provider may legitimately mention: the payload metrics, each category
     * total, the prior month, the plan and the provider's own *structured* suggestion amounts.
     */
    private fun allowedAmounts(payload: AiAggregatePayload, analysis: RawAnalysis): Set<Long> = buildSet {
        add(0L)
        add(payload.grossInflowMinor)
        add(payload.grossOutflowMinor)
        add(kotlin.math.abs(payload.netCashFlowMinor))
        add(payload.classifiedIncomeMinor)
        add(payload.classifiedExpenseMinor)
        payload.categoryTotals.forEach { add(kotlin.math.abs(it.amountMinor)) }
        payload.priorMonth?.let {
            add(kotlin.math.abs(it.expenseMinor))
            add(kotlin.math.abs(it.incomeMinor))
        }
        payload.plan?.let { plan ->
            add(plan.expectedIncomeMinor)
            add(plan.plannedSavingsMinor)
            plan.categoryLimitTotals.forEach { add(kotlin.math.abs(it.amountMinor)) }
        }
        analysis.suggestions.forEach { suggestion ->
            suggestion.suggestedAmountMinor?.let { add(kotlin.math.abs(it)) }
        }
    }

    /** True when a `Rp…` amount in [text] is not in [allowedAmounts] (FR-37). */
    private fun hasInventedAmount(text: String, allowedAmounts: Set<Long>): Boolean =
        extractRupiahAmounts(text).any { it !in allowedAmounts }

    /**
     * Reads the same shape [com.pam.recap.core.money.IdrFormatter] produces: `Rp` then `.`
     * grouping and an optional `,` cents group.
     */
    internal fun extractRupiahAmounts(text: String): List<Long> =
        RUPIAH_PATTERN.findAll(text).mapNotNull { match ->
            parseMinorUnits(match.groupValues[1])
        }.toList()

    private fun parseMinorUnits(raw: String): Long? {
        val compact = raw.trim()
        if (compact.isEmpty()) return null
        val commaIndex = compact.lastIndexOf(',')
        val majorPart = if (commaIndex >= 0) compact.substring(0, commaIndex) else compact
        val centsPart = if (commaIndex >= 0) compact.substring(commaIndex + 1) else "00"
        val majorDigits = majorPart.filter { it.isDigit() }
        if (majorDigits.isEmpty()) return null
        val centsDigits = centsPart.filter { it.isDigit() }.padEnd(2, '0').take(2)
        val major = majorDigits.toLongOrNull() ?: return null
        val cents = centsDigits.toIntOrNull() ?: return null
        return major * 100L + cents
    }

    /** NFR-13: provider prose must never be read as an instruction. */
    private fun containsInstruction(text: String): Boolean {
        val lower = text.lowercase()
        return INSTRUCTION_MARKERS.any { lower.contains(it) }
    }

    companion object {
        const val ISSUE_NO_OUTPUT = "no_output"
        const val ISSUE_NO_OBSERVATIONS = "no_observations"
        const val ISSUE_BLANK_TEXT = "blank_text"
        const val ISSUE_MISSING_EVIDENCE = "missing_evidence"
        const val ISSUE_UNKNOWN_METRIC = "unknown_metric_key"
        const val ISSUE_INVENTED_AMOUNT = "invented_amount"
        const val ISSUE_UNTRUSTED_INSTRUCTION = "untrusted_instruction"
        const val ISSUE_SUGGESTION_AMOUNT = "suggestion_amount_invalid"

        private val RUPIAH_PATTERN = Regex("""Rp\s?([0-9][0-9.,]*)""")

        /**
         * Instruction-like phrasing (NFR-13 prompt-injection corpus). Technical marker data, kept
         * multilingual because the provider may answer in any language.
         */
        private val INSTRUCTION_MARKERS = listOf(
            "abaikan",
            "instruksi sebelumnya",
            "ignore previous",
            "ignore all previous",
            "ignore the above",
            "disregard previous",
            "system:",
            "assistant:",
            "you are now",
            "kamu sekarang",
            "lupakan aturan",
            "override the",
        )
    }
}
