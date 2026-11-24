package com.pam.recap.ui.feature.transactions

import com.pam.recap.core.result.AppError
import com.pam.recap.core.result.Outcome
import com.pam.recap.core.time.JakartaMonth
import com.pam.recap.domain.model.Category
import com.pam.recap.domain.model.CategoryOrigin
import com.pam.recap.domain.model.Direction
import com.pam.recap.domain.model.MonthlySummary
import com.pam.recap.domain.model.ParseStatus
import com.pam.recap.domain.model.Session
import com.pam.recap.domain.model.Transaction
import com.pam.recap.domain.model.TransactionSource
import com.pam.recap.domain.repository.CategoryDrainResult
import com.pam.recap.domain.repository.CategoryEnqueueResult
import com.pam.recap.domain.repository.ManualTransactionDraft
import com.pam.recap.domain.repository.ServerCategory
import com.pam.recap.domain.repository.ServerCategoryJob
import com.pam.recap.domain.repository.ServerFinanceRepository
import com.pam.recap.domain.repository.ServerImportCandidate
import com.pam.recap.domain.repository.ServerImportCounts
import com.pam.recap.domain.repository.ServerImportJob
import com.pam.recap.domain.repository.ServerTransactionPage
import com.pam.recap.domain.repository.TransactionPatch
import kotlin.time.Instant
import kotlinx.coroutines.CompletableDeferred

/** One recorded `searchMonth` call, so tests can prove which cursor/query actually went out. */
internal data class SearchCall(
    val ownerId: String,
    val query: String,
    val month: JakartaMonth,
    val cursor: String?,
    val limit: Int,
    val categoryId: String?,
    val direction: String?,
)

/**
 * Server double for the S2.4 list/detail tests: records every call, lets each test script the page
 * response, and can suspend a search so stale-response races are reproducible.
 */
internal open class RecordingServerFinanceRepository : ServerFinanceRepository {
    override val isEnabled = true

    val searchCalls = mutableListOf<SearchCall>()
    val updateCalls = mutableListOf<TransactionPatch>()
    val deleteCalls = mutableListOf<String>()
    /** Revision sent as the delete compare-and-swap precondition, per call. */
    val deleteRevisions = mutableListOf<Int?>()
    var getCalls = 0

    var transactionRow: Transaction? = null

    /** Scripted response; returns an empty page by default so no test leaks fixtures. */
    var pageProvider: suspend (SearchCall) -> Outcome<ServerTransactionPage> =
        { Outcome.Success(ServerTransactionPage(emptyList())) }

    /** Optional hook before answering a search; used to hold one generation open. */
    var suspendBeforeSearch: (suspend (SearchCall) -> Unit)? = null

    var updateOutcome: Outcome<Transaction>? = null
    var deleteOutcome: Outcome<Unit>? = null

    var summaryOutcome: Outcome<MonthlySummary>? = null

    private val writeSignals = CompletableDeferred<Unit>()

    /** Resolves after the first mutation request reaches the fake. */
    suspend fun awaitWrites() = kotlinx.coroutines.withTimeout(5_000) { writeSignals.await() }

    val serverCategories = listOf(
        ServerCategory("cat_food", "food", "Makanan", "expense"),
        ServerCategory("cat_transport", "transport", "Transportasi", "expense"),
    )

    override suspend fun categories(ownerId: String): Outcome<List<ServerCategory>> =
        Outcome.Success(serverCategories)

    override suspend fun get(ownerId: String, transactionId: String): Outcome<Transaction> {
        getCalls += 1
        return transactionRow?.let { Outcome.Success(it) }
            ?: Outcome.Failure(AppError.NotFound("transaction_$transactionId"))
    }

    override suspend fun searchMonth(
        ownerId: String,
        month: JakartaMonth,
        query: String,
        categoryId: String?,
        direction: String?,
        limit: Int,
        cursor: String?,
    ): Outcome<ServerTransactionPage> {
        val call = SearchCall(ownerId, query, month, cursor, limit, categoryId, direction)
        searchCalls += call
        suspendBeforeSearch?.invoke(call)
        return pageProvider(call)
    }

    override suspend fun updateTransaction(
        ownerId: String,
        transactionId: String,
        patch: TransactionPatch,
    ): Outcome<Transaction> {
        updateCalls += patch
        writeSignals.complete(Unit)
        updateOutcome?.let { return it }
        val current = transactionRow ?: return Outcome.Failure(AppError.NotFound(transactionId))
        val updated = current.copy(
            description = patch.description ?: current.description,
            merchantLabel = patch.merchantLabel ?: current.merchantLabel,
            note = patch.note ?: current.note,
            // The S3 editor is authoritative, so the stored map is replaced by the patched one.
            attributes = patch.attributes,
            revision = current.revision + 1,
        )
        transactionRow = updated
        return Outcome.Success(updated)
    }

    override suspend fun deleteTransaction(
        ownerId: String,
        transactionId: String,
        revision: Int?,
    ): Outcome<Unit> {
        deleteCalls += transactionId
        deleteRevisions += revision
        writeSignals.complete(Unit)
        deleteOutcome?.let { return it }
        transactionRow = null
        return Outcome.Success(Unit)
    }

    override suspend fun updateCategory(
        ownerId: String,
        transactionId: String,
        categoryId: String,
        revision: Int,
    ): Outcome<Transaction> = Outcome.Success(
        requireNotNull(transactionRow).copy(
            revision = revision + 1,
            category = serverCategories.firstOrNull { it.id == categoryId }
                ?.code?.let(Category::fromCode) ?: Category.UNCATEGORIZED,
        ),
    )

    override suspend fun listMonth(
        ownerId: String,
        month: JakartaMonth,
        limit: Int,
        cursor: String?,
    ): Outcome<ServerTransactionPage> = searchMonth(ownerId, month, "", null, null, limit, cursor)

    override suspend fun summary(ownerId: String, month: JakartaMonth): Outcome<MonthlySummary> =
        summaryOutcome ?: Outcome.Failure(AppError.NotFound("summary"))

    override suspend fun createManual(
        ownerId: String,
        draft: ManualTransactionDraft,
        idempotencyKey: String,
    ): Outcome<Transaction> = Outcome.Failure(AppError.NotFound("create"))

    override suspend fun enqueueCategories(
        ownerId: String,
        transactionIds: List<String>,
    ): Outcome<CategoryEnqueueResult> = Outcome.Success(CategoryEnqueueResult(0, 0))

    override suspend fun drainCategories(ownerId: String): Outcome<CategoryDrainResult> =
        Outcome.Success(CategoryDrainResult("empty", 0, 0))

    override suspend fun classifyTransactions(
        ownerId: String,
        transactionIds: List<String>,
    ): Outcome<List<ServerCategoryJob>> = Outcome.Success(emptyList())

    override suspend fun createImportJob(
        ownerId: String,
        month: JakartaMonth,
        templateVersion: String,
    ): Outcome<ServerImportJob> = Outcome.Failure(AppError.NotFound("job"))

    override suspend fun importBatch(
        ownerId: String,
        jobId: String,
        batchKey: String,
        pageOrdinal: Int,
        candidates: List<ServerImportCandidate>,
    ): Outcome<ServerImportCounts> = Outcome.Failure(AppError.NotFound("batch"))

    override suspend fun finalizeImportJob(
        ownerId: String,
        jobId: String,
        state: String,
        errorCode: String?,
    ): Outcome<ServerImportJob> = Outcome.Failure(AppError.NotFound("job"))
}

internal val testNow: Instant = Instant.parse("2026-11-19T05:00:00Z")
internal val testMonth: JakartaMonth = JakartaMonth.of(Instant.parse("2026-11-19T02:15:20Z"))

internal fun testSession(ownerId: String = "u_owner") = Session(
    ownerId = ownerId,
    displayName = "Andhika",
    mailboxLabel = "andhika@example.test",
    signedInAt = testNow,
    isDemo = false,
)

/** A posted QRIS row whose event time always falls inside [testMonth]. */
internal fun testTransaction(
    id: String,
    description: String = "QRIS $id",
    merchantLabel: String? = null,
    revision: Int = 3,
    attributes: Map<String, String> = emptyMap(),
): Transaction = Transaction(
    id = id,
    ownerId = "u_owner",
    direction = Direction.DEBIT,
    source = TransactionSource.EMAIL,
    amount = com.pam.recap.core.money.Money(16_000),
    currency = "IDR",
    eventAt = Instant.parse("2026-11-19T02:15:20Z"),
    receivedAt = null,
    description = description,
    merchantLabel = merchantLabel,
    acquirer = null,
    status = ParseStatus.POSTED,
    fingerprint = "a".repeat(32),
    parserVersion = "livin-v1",
    parserConfidence = 0.9f,
    category = Category.UNCATEGORIZED,
    categoryOrigin = CategoryOrigin.LLM,
    categoryConfidence = null,
    categoryUpdatedAt = null,
    isInternalTransfer = false,
    linkedRefundOf = null,
    note = null,
    importJobId = null,
    revision = revision,
    createdAt = testNow,
    updatedAt = testNow,
    attributes = attributes,
)
