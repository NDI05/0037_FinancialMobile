package com.pam.recap.domain.usecase

import com.pam.recap.core.money.Money
import com.pam.recap.core.result.AppError
import com.pam.recap.core.result.AppErrorRules
import com.pam.recap.core.result.Outcome
import com.pam.recap.core.security.FingerprintFactory
import com.pam.recap.core.security.FingerprintInput
import com.pam.recap.core.time.ClockProvider
import com.pam.recap.core.time.JakartaMonth
import com.pam.recap.core.util.IdGenerator
import com.pam.recap.domain.model.Category
import com.pam.recap.domain.model.CategoryOrigin
import com.pam.recap.domain.model.Direction
import com.pam.recap.domain.model.ParseStatus
import com.pam.recap.domain.model.Transaction
import com.pam.recap.domain.model.TransactionFilter
import com.pam.recap.domain.model.TransactionSource
import com.pam.recap.domain.repository.AnalysisRepository
import com.pam.recap.domain.repository.SummaryRepository
import com.pam.recap.domain.repository.TransactionRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import kotlin.time.Instant

/** FR-26: manual income/expense, always marked [TransactionSource.MANUAL]. */
class AddManualEntryUseCase @Inject constructor(
    private val transactions: TransactionRepository,
    private val summaries: SummaryRepository,
    private val analyses: AnalysisRepository,
    private val fingerprints: FingerprintFactory,
    private val clock: ClockProvider,
    private val idGenerator: IdGenerator,
) {

    suspend operator fun invoke(
        ownerId: String,
        direction: Direction,
        amount: Money,
        eventAt: Instant,
        description: String,
        category: Category? = null,
        note: String? = null,
    ): Outcome<Transaction> {
        if (!amount.isPositive) {
            return Outcome.Failure(AppError.Validation(AppErrorRules.INVALID_AMOUNT))
        }
        if (description.isBlank()) {
            return Outcome.Failure(AppError.Validation(AppErrorRules.DESCRIPTION_REQUIRED))
        }

        val resolvedCategory = category ?: if (direction == Direction.CREDIT) {
            Category.INCOME
        } else {
            Category.UNCATEGORIZED
        }
        val now = clock.now()
        val id = idGenerator.newId()
        val fingerprint = fingerprints.fingerprint(
            ownerId = ownerId,
            input = FingerprintInput(
                messageId = "manual:$id",
                amountMinor = amount.minor,
                eventAtEpochMilliseconds = eventAt.toEpochMilliseconds(),
                directionCode = direction.name,
                recipientLabel = description,
            ),
        )

        val transaction = Transaction(
            id = id,
            ownerId = ownerId,
            direction = direction,
            source = TransactionSource.MANUAL,
            amount = amount,
            currency = "IDR",
            eventAt = eventAt,
            receivedAt = null,
            description = description.trim(),
            merchantLabel = null,
            acquirer = null,
            status = ParseStatus.POSTED,
            fingerprint = fingerprint,
            parserVersion = MANUAL_PARSER_VERSION,
            parserConfidence = 1f,
            category = resolvedCategory,
            categoryOrigin = CategoryOrigin.USER,
            categoryConfidence = 1f,
            categoryUpdatedAt = now,
            isInternalTransfer = resolvedCategory.isTransfer,
            linkedRefundOf = null,
            note = note?.trim()?.takeIf { it.isNotEmpty() },
            importJobId = null,
            revision = 0,
            createdAt = now,
            updatedAt = now,
        )

        val result = transactions.insertIgnoringDuplicates(ownerId, listOf(transaction))
        if (result.inserted != 1) {
            return Outcome.Failure(AppError.Storage("manual_entry_not_inserted"))
        }
        summaries.markOutdated(ownerId, JakartaMonth.of(eventAt))
        analyses.markOutdated(ownerId, JakartaMonth.of(eventAt).id())
        return Outcome.Success(transaction)
    }

    companion object {
        const val MANUAL_PARSER_VERSION: String = "manual-v1"
    }
}

/** FR-25: change the category only — amount, direction and event time are read-only. */
class UpdateTransactionCategoryUseCase @Inject constructor(
    private val transactions: TransactionRepository,
    private val summaries: SummaryRepository,
    private val analyses: AnalysisRepository,
    private val clock: ClockProvider,
) {

    suspend operator fun invoke(
        ownerId: String,
        transactionId: String,
        category: Category,
        confidence: Float = 1f,
    ): Outcome<Unit> {
        val existing = transactions.get(ownerId, transactionId)
            ?: return Outcome.Failure(AppError.NotFound("transaction_$transactionId"))
        val updated = transactions.updateCategory(
            ownerId = ownerId,
            id = transactionId,
            category = category,
            origin = CategoryOrigin.USER,
            confidence = confidence,
            at = clock.now(),
        )
        if (!updated) return Outcome.Failure(AppError.Storage("category_update_failed"))
        invalidateDependents(ownerId, existing)
        return Outcome.Success(Unit)
    }

    private suspend fun invalidateDependents(ownerId: String, transaction: Transaction) {
        val month = JakartaMonth.of(transaction.eventAt)
        summaries.markOutdated(ownerId, month)
        analyses.markOutdated(ownerId, month.id())
    }
}

/**
 * FR-25: mark an own-account movement as an internal transfer.
 *
 * Marking also moves the row to [Category.TRANSFER] so it leaves the classified totals while
 * staying in gross inflow/outflow (BR-02).
 */
class MarkInternalTransferUseCase @Inject constructor(
    private val transactions: TransactionRepository,
    private val summaries: SummaryRepository,
    private val analyses: AnalysisRepository,
    private val clock: ClockProvider,
) {

    suspend operator fun invoke(
        ownerId: String,
        transactionId: String,
        isInternalTransfer: Boolean,
    ): Outcome<Unit> {
        val existing = transactions.get(ownerId, transactionId)
            ?: return Outcome.Failure(AppError.NotFound("transaction_$transactionId"))
        val now = clock.now()
        val changed = transactions.setInternalTransfer(ownerId, transactionId, isInternalTransfer, now)
        if (!changed) return Outcome.Failure(AppError.Storage("transfer_update_failed"))

        if (isInternalTransfer && existing.category != Category.TRANSFER) {
            transactions.updateCategory(
                ownerId = ownerId,
                id = transactionId,
                category = Category.TRANSFER,
                origin = CategoryOrigin.USER,
                confidence = 1f,
                at = now,
            )
        }
        if (!isInternalTransfer && existing.category == Category.TRANSFER) {
            transactions.updateCategory(
                ownerId = ownerId,
                id = transactionId,
                category = Category.UNCATEGORIZED,
                origin = CategoryOrigin.USER,
                confidence = null,
                at = now,
            )
        }

        val month = JakartaMonth.of(existing.eventAt)
        summaries.markOutdated(ownerId, month)
        analyses.markOutdated(ownerId, month.id())
        return Outcome.Success(Unit)
    }
}

/** FR-25/BR-03: link a CREDIT refund to the expense it refunds. */
class LinkRefundUseCase @Inject constructor(
    private val transactions: TransactionRepository,
    private val summaries: SummaryRepository,
    private val analyses: AnalysisRepository,
    private val clock: ClockProvider,
) {

    suspend operator fun invoke(
        ownerId: String,
        refundId: String,
        expenseId: String?,
    ): Outcome<Unit> {
        val refund = transactions.get(ownerId, refundId)
            ?: return Outcome.Failure(AppError.NotFound("transaction_$refundId"))
        if (refund.direction != Direction.CREDIT) {
            return Outcome.Failure(AppError.Validation(AppErrorRules.REFUND_MUST_BE_CREDIT))
        }
        if (expenseId != null) {
            val expense = transactions.get(ownerId, expenseId)
                ?: return Outcome.Failure(AppError.NotFound("transaction_$expenseId"))
            if (expense.direction != Direction.DEBIT || !expense.category.isExpenseCategory) {
                return Outcome.Failure(AppError.Validation(AppErrorRules.REFUND_TARGET_MUST_BE_EXPENSE))
            }
        }

        val changed = transactions.linkRefund(ownerId, refundId, expenseId, clock.now())
        if (!changed) return Outcome.Failure(AppError.Storage("refund_link_failed"))

        val month = JakartaMonth.of(refund.eventAt)
        summaries.markOutdated(ownerId, month)
        analyses.markOutdated(ownerId, month.id())
        return Outcome.Success(Unit)
    }
}

/** FR-25: a private note stays local and never reaches the analysis payload. */
class UpdateTransactionNoteUseCase @Inject constructor(
    private val transactions: TransactionRepository,
    private val clock: ClockProvider,
) {

    suspend operator fun invoke(ownerId: String, transactionId: String, note: String?): Outcome<Unit> {
        val changed = transactions.updateNote(
            ownerId = ownerId,
            id = transactionId,
            note = note?.trim()?.takeIf { it.isNotEmpty() },
            at = clock.now(),
        )
        return if (changed) Outcome.Success(Unit) else Outcome.Failure(AppError.NotFound("transaction_$transactionId"))
    }
}

/** FR-27: observe the month list with optional category/direction filter. */
class ObserveTransactionsUseCase @Inject constructor(
    private val transactions: TransactionRepository,
) {

    operator fun invoke(
        ownerId: String,
        month: JakartaMonth,
        category: Category? = null,
        direction: Direction? = null,
    ): Flow<List<Transaction>> = transactions.observeMonth(
        ownerId = ownerId,
        month = month,
        filter = TransactionFilter(month = month, category = category, direction = direction),
    )
}

/** FR-27: description/merchant search scoped to one month. */
class SearchTransactionsUseCase @Inject constructor(
    private val transactions: TransactionRepository,
) {

    operator fun invoke(
        ownerId: String,
        month: JakartaMonth,
        query: String,
        category: Category? = null,
        direction: Direction? = null,
    ): Flow<List<Transaction>> = transactions.observeMonth(
        ownerId = ownerId,
        month = month,
        filter = TransactionFilter(
            month = month,
            category = category,
            direction = direction,
            query = query.trim(),
        ),
    )
}
