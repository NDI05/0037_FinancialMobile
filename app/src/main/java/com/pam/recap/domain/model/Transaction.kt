package com.pam.recap.domain.model

import com.pam.recap.core.money.IdrFormatter
import com.pam.recap.core.money.Money
import com.pam.recap.core.time.JakartaMonth
import kotlin.time.Instant

/**
 * One normalized cash-flow row (§3).
 *
 * Raw email bodies, subjects, senders and Gmail message ids are **not** modelled here and are
 * never persisted (hard rule 7). [merchantLabel], [acquirer] and [note] stay local: they are
 * never sent to the analysis provider (FR-35).
 */
data class Transaction(
    val id: String,
    val ownerId: String,
    val direction: Direction,
    val source: TransactionSource,
    val amount: Money,
    val currency: String,
    /** Parsed transaction time; decides month membership (BR-01). */
    val eventAt: Instant,
    /** Gmail `internalDate`, kept separately for NFR-04. */
    val receivedAt: Instant?,
    /** Normalized, minimal description (template label, not the email body). */
    val description: String,
    val merchantLabel: String?,
    val acquirer: String?,
    val status: ParseStatus,
    /** Opaque per-user HMAC digest (FR-18); never the raw message id. */
    val fingerprint: String,
    val parserVersion: String,
    val parserConfidence: Float,
    val category: Category,
    val categoryOrigin: CategoryOrigin,
    val categoryConfidence: Float?,
    val categoryUpdatedAt: Instant?,
    val isInternalTransfer: Boolean,
    /** Set on a CREDIT refund row: the id of the expense it refunds (BR-03). */
    val linkedRefundOf: String?,
    val note: String?,
    val importJobId: String?,
    val revision: Int,
    val createdAt: Instant,
    val updatedAt: Instant,
    /** Server identity retained even when the enum must fall back to OTHER_EXPENSE. */
    val serverCategoryId: String? = null,
    val serverCategoryLabel: String? = null,
) {

    val isCredit: Boolean get() = direction == Direction.CREDIT

    val isDebit: Boolean get() = direction == Direction.DEBIT

    val isIdr: Boolean get() = IdrFormatter.isIdr(currency)

    val isRefund: Boolean get() = linkedRefundOf != null

    val isManual: Boolean get() = source == TransactionSource.MANUAL

    val isPosted: Boolean get() = status == ParseStatus.POSTED

    /** FR-24: an LLM category is a *suggestion* the user has not confirmed yet. It is shown as
     *  such, separate from data-quality confidence, because the model returns no calibrated
     *  probability — the category code is the only output. Any user edit flips the origin to USER
     *  and clears this flag. */
    val hasUnconfirmedSuggestion: Boolean
        get() = categoryOrigin == CategoryOrigin.LLM && !category.isUncategorized

    /** FR-24: low parser/import confidence marks a row whose *data* is uncertain, so it stays
     *  reviewable instead of being silently trusted. [LOW_CONFIDENCE_THRESHOLD] is a manual
     *  review cutoff, not a model accuracy probability. A USER override is a human decision and
     *  never carries the chip; an LLM row is reported through [hasUnconfirmedSuggestion] instead
     *  of pretending its missing confidence is a low score. */
    val isLowConfidence: Boolean
        get() = categoryOrigin != CategoryOrigin.USER &&
            categoryOrigin != CategoryOrigin.LLM &&
            (categoryConfidence ?: parserConfidence) < LOW_CONFIDENCE_THRESHOLD

    /** Whether the row contributes to IDR totals (BR-02/BR-04). */
    val countsTowardsTotals: Boolean get() = isPosted && isIdr

    companion object {
        const val LOW_CONFIDENCE_THRESHOLD: Float = 0.60f
    }
}

/**
 * Filter/sort request for the transaction list and search (FR-27). All criteria are optional
 * except the month, so the same query powers the month list, category drill-downs and search.
 */
data class TransactionFilter(
    val month: JakartaMonth,
    val category: Category? = null,
    val direction: Direction? = null,
    val query: String? = null,
    val onlyLowConfidence: Boolean = false,
    val includeNonIdr: Boolean = true,
    val limit: Int = DEFAULT_LIMIT,
) {
    init {
        require(limit > 0) { "limit must be positive" }
    }

    val hasSearchText: Boolean get() = !query.isNullOrBlank()

    companion object {
        const val DEFAULT_LIMIT: Int = 500
    }
}

/**
 * Inclusive coverage of the month's data quality (NFR-11). `EMPTY` means "no matching email was
 * found", never "no spending happened" (FR-20).
 */
data class TransactionPage(
    val items: List<Transaction>,
    val totalCount: Int,
    val offset: Int,
    val hasMore: Boolean,
)

/** Outcome of a batch insert: [ignored] rows already existed for the same fingerprint (FR-18). */
data class InsertResult(
    val inserted: Int,
    val ignored: Int,
) {
    companion object {
        val EMPTY: InsertResult = InsertResult(0, 0)
    }
}
