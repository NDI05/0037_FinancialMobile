package com.pam.recap.domain.model

import kotlinx.serialization.Serializable

/**
 * FR-35: the **only** shape that may leave the device for analysis. It is aggregate-only:
 * no email body, subject, sender, Gmail message id, token, PAN, merchant name, note or
 * free-text description field exists on this class, and [AiPayloadBuilder] cannot add one.
 *
 * Amounts are minor units ([Long]) so serialization never introduces floating point (BR-04).
 */
@Serializable
data class AiAggregatePayload(
    val schemaVersion: Int,
    val monthId: String,
    val coverage: String,
    val grossInflowMinor: Long,
    val grossOutflowMinor: Long,
    val netCashFlowMinor: Long,
    val classifiedIncomeMinor: Long,
    val classifiedExpenseMinor: Long,
    val transactionCount: Int,
    val uncategorizedCount: Int,
    val manualEntryCount: Int,
    val nonIdrExcludedCount: Int,
    val categoryTotals: List<AiCategoryTotal>,
    val priorMonth: AiPriorMonthTotals? = null,
) {
    companion object {
        const val SCHEMA_VERSION: Int = 1

        /** Every key the payload may contain; the unit test asserts nothing else is present. */
        val ALLOWED_FIELDS: Set<String> = setOf(
            "schemaVersion",
            "monthId",
            "coverage",
            "grossInflowMinor",
            "grossOutflowMinor",
            "netCashFlowMinor",
            "classifiedIncomeMinor",
            "classifiedExpenseMinor",
            "transactionCount",
            "uncategorizedCount",
            "manualEntryCount",
            "nonIdrExcludedCount",
            "categoryTotals",
            "priorMonth",
            "categoryCode",
            "amountMinor",
            "limitMinor",
            "expenseMinor",
            "incomeMinor",
            "expectedIncomeMinor",
            "plannedSavingsMinor",
            "categoryLimitTotals",
        )
    }
}

@Serializable
data class AiCategoryTotal(
    val categoryCode: String,
    val amountMinor: Long,
    val transactionCount: Int,
)

@Serializable
data class AiPriorMonthTotals(
    val monthId: String,
    val expenseMinor: Long,
    val incomeMinor: Long,
)

/** Provider-agnostic raw output of an [AnalysisEngine]; validated before it is published. */
data class RawAnalysis(
    val providerId: String,
    val modelId: String,
    val promptVersion: String,
    val observations: List<RawObservation>,
    val suggestions: List<RawSuggestion>,
    val limitations: List<String> = emptyList(),
    val usage: AnalysisUsage? = null,
    /** Unstructured provider prose, kept only for provenance/debug, never rendered raw. */
    val rawText: String? = null,
)

data class RawObservation(
    val text: String,
    val metricKeys: List<String>,
    val templateKey: String? = null,
)

data class RawSuggestion(
    val text: String,
    val metricKeys: List<String>,
    val categoryCode: String? = null,
    val suggestedAmountMinor: Long? = null,
    val templateKey: String? = null,
)
