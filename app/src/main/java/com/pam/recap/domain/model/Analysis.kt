package com.pam.recap.domain.model

import com.pam.recap.core.money.Money
import kotlin.time.Instant

/** FR-36/FR-39 lifecycle of a stored analysis (state machine in §7). */
enum class AnalysisStatus {
    AVAILABLE,
    PROVISIONAL,
    OUTDATED,
    HISTORICAL,
    UNAVAILABLE,
    REJECTED,
}

/**
 * Stable summary metric keys (FR-36). A suggestion or observation that cites no key is invalid,
 * and a key that is not in [all] can never be produced by the on-device engine.
 */
object MetricKeys {
    const val GROSS_INFLOW = "summary.grossInflow"
    const val GROSS_OUTFLOW = "summary.grossOutflow"
    const val NET_CASH_FLOW = "summary.netCashFlow"
    const val CLASSIFIED_INCOME = "summary.classifiedIncome"
    const val CLASSIFIED_EXPENSE = "summary.classifiedExpense"
    const val UNCATEGORIZED_COUNT = "summary.uncategorizedCount"
    const val MANUAL_ENTRY_COUNT = "summary.manualEntryCount"
    const val NON_IDR_EXCLUDED_COUNT = "summary.nonIdrExcludedCount"
    const val TRANSACTION_COUNT = "summary.transactionCount"
    const val COVERAGE = "summary.coverage"

    fun category(code: String): String = "category.$code"

    val all: Set<String> = setOf(
        GROSS_INFLOW,
        GROSS_OUTFLOW,
        NET_CASH_FLOW,
        CLASSIFIED_INCOME,
        CLASSIFIED_EXPENSE,
        UNCATEGORIZED_COUNT,
        MANUAL_ENTRY_COUNT,
        NON_IDR_EXCLUDED_COUNT,
        TRANSACTION_COUNT,
        COVERAGE,
    ) + Category.entries.map { category(it.code) }
}

/**
 * Technical copy keys for observation/suggestion text. The on-device engine resolves these
 * through an `AnalysisCopySource`, so the generated sentences still live in `strings.xml`
 * (hard rule 2) instead of being literals in a `.kt` file.
 */
enum class ObservationTemplate {
    MONTH_TOTALS,
    NET_POSITIVE,
    NET_NEGATIVE,
    TOP_EXPENSE_CATEGORY,
    EXPENSE_DOMINANT_SHARE,
    UNCATEGORIZED_ROWS,
    NON_IDR_ROWS,
    MANUAL_ROWS,
    PARTIAL_COVERAGE,
    EMPTY_MONTH,
    NO_PRIOR_MONTH,
    EXPENSE_INCREASED,
    EXPENSE_DECREASED,
}

enum class SuggestionTemplate {
    SET_TOP_CATEGORY_LIMIT,
    SET_SAVINGS_TARGET,
    REVIEW_UNCATEGORIZED,
    KEEP_MONTH_ON_TRACK,
    NO_DATA,
}

/** A single evidence-linked observation (FR-36). */
data class Observation(
    val text: String,
    val metricKeys: List<String>,
    /** Technical template id for observations produced on device; null for provider prose. */
    val templateKey: String? = null,
) {
    init {
        require(metricKeys.isNotEmpty()) { "observation must cite at least one metric key" }
    }
}

/** A suggestion that must be backed by summary evidence (BR-06). */
data class Suggestion(
    val text: String,
    val metricKeys: List<String>,
    val category: Category? = null,
    val suggestedAmount: Money? = null,
    val templateKey: String? = null,
) {
    init {
        require(metricKeys.isNotEmpty()) { "suggestion must cite at least one metric key" }
    }
}

/** FR-39 stored validation result. [issues] are technical error codes, never user copy. */
data class ValidationOutcome(
    val isValid: Boolean,
    val issues: List<String> = emptyList(),
    val regenerationCount: Int = 0,
    val validatedAt: Instant? = null,
) {
    companion object {
        fun valid(regenerationCount: Int, at: Instant): ValidationOutcome =
            ValidationOutcome(true, emptyList(), regenerationCount, at)

        fun invalid(issues: List<String>, regenerationCount: Int, at: Instant): ValidationOutcome =
            ValidationOutcome(false, issues, regenerationCount, at)
    }
}

/** FR-39/FR-50 usage accounting for one provider call. */
data class AnalysisUsage(
    val promptTokens: Int? = null,
    val completionTokens: Int? = null,
    val latencyMillis: Long? = null,
)

/**
 * FR-39 persisted analysis. `providerId`/`modelId`/`promptVersion` + [inputRevision] form the
 * cache key used by FR-40; consent withdrawal flips [status] to [AnalysisStatus.HISTORICAL].
 */
data class Analysis(
    val id: String,
    val ownerId: String,
    val monthId: String,
    val summaryId: String,
    val inputRevision: Int,
    val consentId: String?,
    val providerId: String,
    val modelId: String,
    val promptVersion: String,
    val observations: List<Observation>,
    val suggestions: List<Suggestion>,
    val limitations: List<String>,
    val status: AnalysisStatus,
    val validation: ValidationOutcome,
    val usage: AnalysisUsage?,
    val createdAt: Instant,
) {

    val isPublishable: Boolean
        get() = status == AnalysisStatus.AVAILABLE || status == AnalysisStatus.PROVISIONAL

    /** FR-40: reusable only when nothing that fed it changed. */
    fun isReusableFor(inputRevision: Int, consentId: String?, providerId: String, modelId: String, promptVersion: String): Boolean =
        status == AnalysisStatus.AVAILABLE &&
            this.inputRevision == inputRevision &&
            this.consentId == consentId &&
            this.providerId == providerId &&
            this.modelId == modelId &&
            this.promptVersion == promptVersion
}
