package com.pam.recap.domain.usecase

import com.pam.recap.core.money.IdrFormatter
import com.pam.recap.core.money.Money
import com.pam.recap.domain.model.Category
import com.pam.recap.domain.model.CategoryTotal
import com.pam.recap.domain.model.Direction
import com.pam.recap.domain.model.ParseStatus
import com.pam.recap.domain.model.Transaction
import com.pam.recap.domain.model.TransactionSource

/**
 * The minimal projection of a transaction that BR-02/BR-03 need. Keeping the calculator on this
 * shape (instead of the full [Transaction]) makes it trivially unit-testable and prevents UI or
 * persistence concerns from leaking into the arithmetic (NFR-12).
 */
data class CashFlowEntry(
    val id: String,
    val direction: Direction,
    val category: Category,
    val amount: Money,
    val currency: String,
    val status: ParseStatus = ParseStatus.POSTED,
    val source: TransactionSource = TransactionSource.EMAIL,
    val isInternalTransfer: Boolean = false,
    val linkedRefundOf: String? = null,
) {
    val isCredit: Boolean get() = direction == Direction.CREDIT

    val isDebit: Boolean get() = direction == Direction.DEBIT

    /** A refund is a CREDIT explicitly linked to an expense row (BR-03). */
    val isLinkedRefund: Boolean get() = isCredit && linkedRefundOf != null

    val isTransfer: Boolean get() = isInternalTransfer || category.isTransfer

    companion object {
        fun from(transaction: Transaction): CashFlowEntry = CashFlowEntry(
            id = transaction.id,
            direction = transaction.direction,
            category = transaction.category,
            amount = transaction.amount,
            currency = transaction.currency,
            status = transaction.status,
            source = transaction.source,
            isInternalTransfer = transaction.isInternalTransfer,
            linkedRefundOf = transaction.linkedRefundOf,
        )
    }
}

/** Result of one month's fold. All amounts are exact minor units. */
data class CashFlowTotals(
    val grossInflow: Money,
    val grossOutflow: Money,
    val netCashFlow: Money,
    val classifiedIncome: Money,
    val classifiedExpense: Money,
    val categoryTotals: List<CategoryTotal>,
    val uncategorizedCount: Int,
    val manualEntryCount: Int,
    val nonIdrExcludedCount: Int,
    val transactionCount: Int,
    val refundedAmount: Money,
) {
    fun totalFor(category: Category): Money =
        categoryTotals.firstOrNull { it.category == category }?.amount ?: Money.ZERO

    companion object {
        val EMPTY: CashFlowTotals = CashFlowTotals(
            grossInflow = Money.ZERO,
            grossOutflow = Money.ZERO,
            netCashFlow = Money.ZERO,
            classifiedIncome = Money.ZERO,
            classifiedExpense = Money.ZERO,
            categoryTotals = emptyList(),
            uncategorizedCount = 0,
            manualEntryCount = 0,
            nonIdrExcludedCount = 0,
            transactionCount = 0,
            refundedAmount = Money.ZERO,
        )
    }
}

/**
 * Pure implementation of BR-01…BR-06 (§6). Deliberately an `object` with no dependencies so the
 * same arithmetic runs in unit tests, in the app and in any future export (NFR-12).
 *
 * Rules applied here:
 * - **BR-02** `grossInflow` = Σ CREDIT, `grossOutflow` = Σ DEBIT (POSTED only, IDR only).
 *   `classifiedIncome` = CREDIT in [Category.INCOME]; `classifiedExpense` = DEBIT in an expense
 *   category and not an internal transfer. Internal transfers stay in gross and leave classified.
 * - **BR-03** a CREDIT linked to an expense reduces that expense category's **net** for the
 *   refund month. An unlinked credit is never netted against unrelated expenses.
 * - **BR-04** non-IDR rows are excluded from every IDR total and counted so the UI can flag them.
 * - **BR-06** no amount here is invented: `EMPTY` totals are explicit zeros with no data.
 */
object CashFlowCalculator {

    fun compute(entries: List<CashFlowEntry>): CashFlowTotals {
        val byId: Map<String, CashFlowEntry> = entries.associateBy { it.id }

        var grossInflow = Money.ZERO
        var grossOutflow = Money.ZERO
        var classifiedIncome = Money.ZERO
        var classifiedExpense = Money.ZERO
        var refundedAmount = Money.ZERO
        var uncategorizedCount = 0
        var manualEntryCount = 0
        var nonIdrExcludedCount = 0
        var transactionCount = 0

        val categoryNet = LinkedHashMap<Category, Money>()
        val categoryRowCount = LinkedHashMap<Category, Int>()

        fun addToCategory(category: Category, delta: Money, rows: Int = 0) {
            categoryNet[category] = (categoryNet[category] ?: Money.ZERO) + delta
            if (rows != 0) {
                categoryRowCount[category] = (categoryRowCount[category] ?: 0) + rows
            }
        }

        for (entry in entries) {
            if (entry.status != ParseStatus.POSTED) {
                // FAILED / UNRESOLVED rows are never money: they are excluded from every total.
                continue
            }
            if (!IdrFormatter.isIdr(entry.currency)) {
                nonIdrExcludedCount++
                continue
            }

            transactionCount++
            if (entry.source == TransactionSource.MANUAL) manualEntryCount++

            if (entry.isCredit) {
                grossInflow += entry.amount
            } else {
                grossOutflow += entry.amount
            }

            when {
                entry.isTransfer -> {
                    // Transfers remain in gross and never enter classified income/expense.
                    val delta = if (entry.isDebit) entry.amount else -entry.amount
                    addToCategory(Category.TRANSFER, delta, rows = 1)
                }

                entry.category.isUncategorized -> {
                    uncategorizedCount++
                    val delta = if (entry.isDebit) entry.amount else -entry.amount
                    addToCategory(Category.UNCATEGORIZED, delta, rows = 1)
                }

                entry.isLinkedRefund -> {
                    refundedAmount += entry.amount
                    val linked = entry.linkedRefundOf?.let(byId::get)
                    val target = linked?.category
                        ?.takeIf { it.isExpenseCategory }
                        ?: entry.category.takeIf { it.isExpenseCategory }
                        ?: Category.UNCATEGORIZED
                    // A refund only ever nets down the category it belongs to (BR-03).
                    addToCategory(target, -entry.amount, rows = 1)
                }

                entry.isDebit && entry.category.isExpenseCategory -> {
                    classifiedExpense += entry.amount
                    addToCategory(entry.category, entry.amount, rows = 1)
                }

                entry.isCredit && entry.category.isIncome -> {
                    classifiedIncome += entry.amount
                    addToCategory(Category.INCOME, entry.amount, rows = 1)
                }

                entry.isCredit -> {
                    // Unlinked credit outside the income category: gross only, never netted
                    // against unrelated expenses (BR-03).
                    addToCategory(entry.category, Money.ZERO, rows = 1)
                }

                else -> {
                    // Debit that is neither an expense category nor a transfer.
                    addToCategory(Category.UNCATEGORIZED, entry.amount, rows = 1)
                }
            }
        }

        val categoryTotals = Category.entries.mapNotNull { category ->
            val net = categoryNet[category] ?: return@mapNotNull null
            val rows = categoryRowCount[category] ?: 0
            if (net.isZero && rows == 0) return@mapNotNull null
            CategoryTotal(category = category, amount = net, transactionCount = rows)
        }

        return CashFlowTotals(
            grossInflow = grossInflow,
            grossOutflow = grossOutflow,
            netCashFlow = grossInflow - grossOutflow,
            classifiedIncome = classifiedIncome,
            classifiedExpense = classifiedExpense,
            categoryTotals = categoryTotals,
            uncategorizedCount = uncategorizedCount,
            manualEntryCount = manualEntryCount,
            nonIdrExcludedCount = nonIdrExcludedCount,
            transactionCount = transactionCount,
            refundedAmount = refundedAmount,
        )
    }
}
