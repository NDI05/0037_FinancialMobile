package com.pam.recap.domain.repository

import com.pam.recap.core.result.AppError
import com.pam.recap.core.result.Outcome
import com.pam.recap.core.time.JakartaMonth
import com.pam.recap.domain.model.MonthlySummary
import com.pam.recap.domain.model.Transaction
import kotlin.time.Instant

/** Owner-scoped category identity returned by the v0.4 server. */
data class ServerCategory(
    val id: String,
    val code: String,
    val label: String,
    val kind: String,
    val isBuiltin: Boolean = false,
    val color: String? = null,
    val attributes: Map<String, String> = emptyMap(),
    val revision: Int = 0,
)

/** A custom category write; built-ins reject edits/deletes on the server. */
data class CategoryDraft(
    val code: String,
    val label: String,
    val kind: String,
    val color: String? = null,
    val attributes: Map<String, String> = emptyMap(),
)

data class CategoryPatch(
    val revision: Int,
    val label: String? = null,
    val kind: String? = null,
    val color: String? = null,
    val attributes: Map<String, String>? = null,
)

/** Server budget envelope for one month; amounts are minor units. */
data class ServerBudgetPlan(
    val monthId: String,
    val expectedIncomeMinor: Long,
    val plannedSavingsMinor: Long,
    val limitsByCategoryId: Map<String, Long>,
    val gapMinor: Long,
    val revision: Int,
)

/** A manual action before the server assigns its final id/revision. */
data class ManualTransactionDraft(
    val direction: String,
    val amountMinor: Long,
    val eventAtEpochMilliseconds: Long,
    val categoryId: String?,
    val description: String,
    val note: String?,
)

/** Outcome of a queue enqueue: how many rows this call added and the total still waiting. */
data class CategoryEnqueueResult(val queued: Int, val pending: Int)

/** Outcome of one drain attempt: `state` is empty/not_due/drained/budget_exhausted. */
data class CategoryDrainResult(
    val state: String,
    val drained: Int,
    val pending: Int,
    val nextDueAt: Instant? = null,
)

data class ServerTransactionPage(
    val items: List<Transaction>,
    val nextCursor: String? = null,
    /** Rows the server examined for a search, and whether its scan cap cut the result. */
    val scanned: Int = 0,
    val truncated: Boolean = false,
)

/** Partial edit for a server row; omitted fields keep their current value. */
data class TransactionPatch(
    val revision: Int,
    val categoryId: String? = null,
    val description: String? = null,
    val merchantLabel: String? = null,
    val note: String? = null,
)

/**
 * C2A read/write boundary for the existing transaction and summary screens. Implementations call
 * Go; the legacy Room path is a separate, explicit implementation selected only when no server is
 * configured.
 */
data class ServerImportJob(
    val id: String,
    val monthId: String,
    val state: String,
    val matched: Int = 0,
    val imported: Int = 0,
    val duplicates: Int = 0,
    val skipped: Int = 0,
    val unresolved: Int = 0,
)

data class ServerImportCandidate(
    val fingerprint: String,
    val status: String,
    val reason: String? = null,
    val direction: String? = null,
    val amountMinor: Long? = null,
    val eventAtEpochMilliseconds: Long? = null,
    val categoryId: String? = null,
    val parserVersion: String? = null,
    val confidence: Double? = null,
    val description: String? = null,
    val merchantLabel: String? = null,
)

data class ServerImportCounts(
    val matched: Int,
    val imported: Int,
    val duplicates: Int,
    val skipped: Int,
    val unresolved: Int,
    val inputRevision: Int,
    val jobState: String,
)

data class ServerCategoryJob(
    val id: String,
    val transactionId: String,
    val state: String,
    val categoryCode: String? = null,
    val reasonCode: String? = null,
    val errorCode: String? = null,
    val provider: String? = null,
    val model: String? = null,
)

interface ServerFinanceRepository {
    val isEnabled: Boolean

    suspend fun categories(ownerId: String): Outcome<List<ServerCategory>>

    suspend fun createCategory(ownerId: String, draft: CategoryDraft): Outcome<ServerCategory> =
        Outcome.Failure(AppError.Configuration("backend_category_write_not_supported"))

    suspend fun updateCategoryMeta(
        ownerId: String,
        categoryId: String,
        patch: CategoryPatch,
    ): Outcome<ServerCategory> =
        Outcome.Failure(AppError.Configuration("backend_category_write_not_supported"))

    /** Deletes a custom category; [reassignTo] moves rows first, empty means reject when in use. */
    suspend fun deleteCategory(
        ownerId: String,
        categoryId: String,
        reassignTo: String? = null,
    ): Outcome<Unit> = Outcome.Failure(AppError.Configuration("backend_category_write_not_supported"))

    suspend fun budgetPlan(ownerId: String, month: JakartaMonth): Outcome<ServerBudgetPlan> =
        Outcome.Failure(AppError.Configuration("backend_budget_not_supported"))

    suspend fun saveBudgetPlan(
        ownerId: String,
        month: JakartaMonth,
        expectedIncomeMinor: Long,
        plannedSavingsMinor: Long,
        limitsByCategoryId: Map<String, Long>,
        revision: Int,
    ): Outcome<ServerBudgetPlan> =
        Outcome.Failure(AppError.Configuration("backend_budget_not_supported"))

    suspend fun deleteBudgetPlan(ownerId: String, month: JakartaMonth): Outcome<Unit> =
        Outcome.Failure(AppError.Configuration("backend_budget_not_supported"))

    suspend fun createManual(
        ownerId: String,
        draft: ManualTransactionDraft,
        idempotencyKey: String,
    ): Outcome<Transaction>

    suspend fun listMonth(
        ownerId: String,
        month: JakartaMonth,
        limit: Int = 100,
        cursor: String? = null,
    ): Outcome<ServerTransactionPage>

    /**
     * One server-side page of a filtered search. The cursor belongs to the exact query/month: the
     * caller must drop it whenever any filter changes.
     */
    suspend fun searchMonth(
        ownerId: String,
        month: JakartaMonth,
        query: String = "",
        categoryId: String? = null,
        direction: String? = null,
        limit: Int = 50,
        cursor: String? = null,
    ): Outcome<ServerTransactionPage> = listMonth(ownerId, month, limit, cursor)

    suspend fun updateTransaction(
        ownerId: String,
        transactionId: String,
        patch: TransactionPatch,
    ): Outcome<Transaction> = Outcome.Failure(AppError.Configuration("backend_update_not_supported"))

    suspend fun deleteTransaction(
        ownerId: String,
        transactionId: String,
    ): Outcome<Unit> = Outcome.Failure(AppError.Configuration("backend_delete_not_supported"))

    suspend fun get(ownerId: String, transactionId: String): Outcome<Transaction>

    /** Server-mode category correction: PATCH the row with the revision the client loaded so a
     *  concurrent write is refused as a conflict instead of silently overwriting someone else. */
    suspend fun updateCategory(
        ownerId: String,
        transactionId: String,
        categoryId: String,
        revision: Int,
    ): Outcome<Transaction>

    suspend fun summary(ownerId: String, month: JakartaMonth): Outcome<MonthlySummary>

    suspend fun classifyTransactions(
        ownerId: String,
        transactionIds: List<String>,
    ): Outcome<List<ServerCategoryJob>>

    /**
     * Records eligible candidates for the automatic queue without spending classifier budget. Safe to
     * repeat: the server is idempotent per transaction revision.
     */
    suspend fun enqueueCategories(
        ownerId: String,
        transactionIds: List<String>,
    ): Outcome<CategoryEnqueueResult>

    /**
     * Asks the server to drain its queue. An early or empty drain returns without calling the
     * provider, so this is the only call needed to learn the backlog and the next due time.
     */
    suspend fun drainCategories(ownerId: String): Outcome<CategoryDrainResult>

    suspend fun createImportJob(
        ownerId: String,
        month: JakartaMonth,
        templateVersion: String,
    ): Outcome<ServerImportJob>

    suspend fun importBatch(
        ownerId: String,
        jobId: String,
        batchKey: String,
        pageOrdinal: Int,
        candidates: List<ServerImportCandidate>,
    ): Outcome<ServerImportCounts>

    suspend fun finalizeImportJob(
        ownerId: String,
        jobId: String,
        state: String,
        errorCode: String? = null,
    ): Outcome<ServerImportJob>
}
