package com.pam.recap.ui.util

import androidx.annotation.StringRes
import com.pam.recap.R
import com.pam.recap.domain.model.Category
import com.pam.recap.domain.model.CoverageStatus
import com.pam.recap.domain.model.Direction
import com.pam.recap.domain.model.MetricKeys
import com.pam.recap.domain.model.RedactionReason

/** Category display names. Codes stay technical; only the label is user-visible (FR-23). */
@StringRes
fun categoryLabelRes(category: Category): Int = when (category) {
    Category.INCOME -> R.string.category_income
    Category.HOUSING -> R.string.category_housing
    Category.FOOD -> R.string.category_food
    Category.TRANSPORT -> R.string.category_transport
    Category.UTILITIES -> R.string.category_utilities
    Category.SHOPPING -> R.string.category_shopping
    Category.HEALTH -> R.string.category_health
    Category.EDUCATION -> R.string.category_education
    Category.OTHER_EXPENSE -> R.string.category_other_expense
    Category.TRANSFER -> R.string.category_transfer
    Category.UNCATEGORIZED -> R.string.category_uncategorized
}

/** Direction words used next to an amount so colour is never the only signal (FR-27, NFR-01). */
@StringRes
fun directionLabelRes(direction: Direction): Int = when (direction) {
    Direction.CREDIT -> R.string.direction_credit
    Direction.DEBIT -> R.string.direction_debit
}

/** Coverage banner copy (NFR-11: complete, partial, empty and failed are all distinct). */
@StringRes
fun coverageBannerRes(coverage: CoverageStatus): Int = when (coverage) {
    CoverageStatus.COMPLETE -> R.string.coverage_complete
    CoverageStatus.PARTIAL -> R.string.coverage_partial
    CoverageStatus.EMPTY -> R.string.coverage_empty
    CoverageStatus.FAILED -> R.string.coverage_failed
}

/**
 * Technical metric key → evidence chip label (FR-36). Unknown keys fall back to the coverage chip
 * rather than rendering a raw identifier to the user.
 */
@StringRes
fun metricLabelRes(metricKey: String): Int = when (metricKey) {
    MetricKeys.GROSS_INFLOW -> R.string.metric_gross_inflow
    MetricKeys.GROSS_OUTFLOW -> R.string.metric_gross_outflow
    MetricKeys.NET_CASH_FLOW -> R.string.metric_net_cash_flow
    MetricKeys.CLASSIFIED_INCOME -> R.string.metric_classified_income
    MetricKeys.CLASSIFIED_EXPENSE -> R.string.metric_classified_expense
    MetricKeys.UNCATEGORIZED_COUNT -> R.string.metric_uncategorized_count
    MetricKeys.MANUAL_ENTRY_COUNT -> R.string.metric_manual_count
    MetricKeys.NON_IDR_EXCLUDED_COUNT -> R.string.metric_non_idr_count
    MetricKeys.TRANSACTION_COUNT -> R.string.metric_transaction_count
    MetricKeys.COVERAGE -> R.string.metric_coverage
    else -> {
        val categoryCode = metricKey.removePrefix("category.")
        val category = Category.fromCode(categoryCode)
        if (category != null) categoryLabelRes(category) else R.string.metric_coverage
    }
}

/** FR-15: redacted skip reasons shown to the user; they never carry message content. */
@StringRes
fun redactionReasonRes(reason: RedactionReason): Int = when (reason) {
    RedactionReason.MISSING_AMOUNT -> R.string.reason_missing_amount
    RedactionReason.MALFORMED_AMOUNT -> R.string.reason_malformed_amount
    RedactionReason.MISSING_EVENT_TIME -> R.string.reason_missing_event_time
    RedactionReason.MALFORMED_EVENT_TIME -> R.string.reason_malformed_event_time
    RedactionReason.MISSING_DIRECTION -> R.string.reason_missing_direction
    RedactionReason.MISSING_STATUS -> R.string.reason_missing_status
    RedactionReason.MISSING_RECIPIENT -> R.string.reason_missing_recipient
    RedactionReason.FAILED_PAYMENT -> R.string.reason_failed_payment
    RedactionReason.UNKNOWN_TEMPLATE -> R.string.reason_unknown_template
    RedactionReason.ATTACHMENT_ONLY -> R.string.reason_attachment_only
    RedactionReason.NOT_LIVIN_SOURCE -> R.string.reason_not_livin_source
    RedactionReason.NOT_IN_MONTH -> R.string.reason_not_in_month
    RedactionReason.DUPLICATE -> R.string.reason_duplicate
    RedactionReason.FINGERPRINT_COLLISION -> R.string.reason_collision
    RedactionReason.CURRENCY_UNSUPPORTED -> R.string.reason_currency_unsupported
    RedactionReason.MALFORMED_FIELD -> R.string.reason_malformed_field
}
