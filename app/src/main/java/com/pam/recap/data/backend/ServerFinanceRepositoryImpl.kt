package com.pam.recap.data.backend

import com.pam.recap.core.money.Money
import com.pam.recap.core.result.AppError
import com.pam.recap.core.result.Outcome
import com.pam.recap.core.time.JakartaMonth
import com.pam.recap.domain.model.Category
import com.pam.recap.domain.model.CategoryOrigin
import com.pam.recap.domain.model.CategoryTotal
import com.pam.recap.domain.model.CoverageStatus
import com.pam.recap.domain.model.Direction
import com.pam.recap.domain.model.MonthlySummary
import com.pam.recap.domain.model.SummaryFreshness
import com.pam.recap.domain.model.ParseStatus
import com.pam.recap.domain.model.Transaction
import com.pam.recap.domain.model.TransactionSource
import com.pam.recap.domain.repository.CategoryDrainResult
import com.pam.recap.domain.repository.CategoryEnqueueResult
import com.pam.recap.domain.repository.ManualTransactionDraft
import com.pam.recap.domain.repository.CategoryDraft
import com.pam.recap.domain.repository.CategoryPatch
import com.pam.recap.domain.repository.ServerCategory
import com.pam.recap.domain.repository.ServerCategoryPage
import com.pam.recap.domain.repository.ServerImportCandidate
import com.pam.recap.domain.repository.ServerImportCounts
import com.pam.recap.domain.repository.ServerCategoryJob
import com.pam.recap.domain.repository.ServerImportJob
import com.pam.recap.domain.repository.ServerFinanceRepository
import com.pam.recap.domain.repository.ServerTransactionPage
import com.pam.recap.domain.repository.TransactionPatch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.math.roundToInt
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Instant

@Singleton
class ServerFinanceRepositoryImpl @Inject constructor(
    private val api: PamApiClient,
    private val endpoints: BackendEndpoints,
) : ServerFinanceRepository {

    override val isEnabled: Boolean get() = endpoints.isEnabled

    override suspend fun categories(ownerId: String): Outcome<List<ServerCategory>> =
        api.categories(ownerId).let { result ->
            when (result) {
                is Outcome.Success -> Outcome.Success(result.value.categories.map { it.toServer() })
                is Outcome.Failure -> result
            }
        }

    override suspend fun searchCategories(
        ownerId: String,
        query: String,
        kind: String?,
        limit: Int,
        cursor: String?,
    ): Outcome<ServerCategoryPage> = when (
        val result = api.categories(ownerId, query, kind, limit, cursor)
    ) {
        is Outcome.Success -> Outcome.Success(
            ServerCategoryPage(
                items = result.value.categories.map { it.toServer() },
                nextCursor = result.value.nextCursor,
                scanned = result.value.search?.scanned ?: result.value.categories.size,
                truncated = result.value.search?.truncated ?: false,
            ),
        )
        is Outcome.Failure -> result
    }

    override suspend fun createCategory(
        ownerId: String,
        draft: CategoryDraft,
    ): Outcome<ServerCategory> = when (
        val result = api.createCategory(
            ownerId,
            RemoteCategoryWrite(draft.code, draft.label, draft.kind, draft.color, draft.attributes),
        )
    ) {
        is Outcome.Success -> Outcome.Success(result.value.toServer())
        is Outcome.Failure -> result
    }

    override suspend fun updateCategoryMeta(
        ownerId: String,
        categoryId: String,
        patch: CategoryPatch,
    ): Outcome<ServerCategory> = when (
        val result = api.patchCategory(
            ownerId,
            categoryId,
            RemoteCategoryPatch(patch.label, patch.kind, patch.color, patch.attributes, patch.revision),
        )
    ) {
        is Outcome.Success -> Outcome.Success(result.value.toServer())
        is Outcome.Failure -> result
    }

    override suspend fun deleteCategory(
        ownerId: String,
        categoryId: String,
        reassignTo: String?,
    ): Outcome<Unit> = api.deleteCategory(ownerId, categoryId, reassignTo)

    private fun RemoteCategoryItem.toServer() = ServerCategory(
        id = id,
        code = code,
        label = label.orEmpty(),
        kind = kind,
        isBuiltin = isBuiltin,
        color = color,
        attributes = attributes.orEmpty(),
        revision = revision,
    )

    override suspend fun createManual(
        ownerId: String,
        draft: ManualTransactionDraft,
        idempotencyKey: String,
    ): Outcome<Transaction> = when (val result = api.createTransaction(
        ownerId,
        idempotencyKey,
        RemoteTransactionCreate(
            direction = draft.direction,
            amountMinor = draft.amountMinor,
            eventAt = Instant.fromEpochMilliseconds(draft.eventAtEpochMilliseconds).toString(),
            categoryId = draft.categoryId,
            description = draft.description,
            note = draft.note,
        ),
    )) {
        is Outcome.Success -> wireMap { result.value.toDomain(ownerId) }
        is Outcome.Failure -> result
    }

    override suspend fun listMonth(
        ownerId: String,
        month: JakartaMonth,
        limit: Int,
        cursor: String?,
    ): Outcome<ServerTransactionPage> = when (val result = api.listTransactions(
        ownerId, month.id(), limit, cursor,
    )) {
        is Outcome.Success -> wireMap {
            ServerTransactionPage(result.value.items.map { it.toDomain(ownerId) }, result.value.nextCursor)
        }
        is Outcome.Failure -> result
    }

    override suspend fun get(ownerId: String, transactionId: String): Outcome<Transaction> =
        when (val result = api.getTransaction(ownerId, transactionId)) {
            is Outcome.Success -> wireMap { result.value.toDomain(ownerId) }
            is Outcome.Failure -> result
        }

    override suspend fun updateCategory(
        ownerId: String,
        transactionId: String,
        categoryId: String,
        revision: Int,
    ): Outcome<Transaction> = when (val result = api.patchTransactionCategory(
        ownerId, transactionId, categoryId, revision,
    )) {
        is Outcome.Success -> wireMap { result.value.toDomain(ownerId) }
        is Outcome.Failure -> result
    }

    override suspend fun searchMonth(
        ownerId: String,
        month: JakartaMonth,
        query: String,
        categoryId: String?,
        direction: String?,
        limit: Int,
        cursor: String?,
    ): Outcome<ServerTransactionPage> = when (
        val result = api.searchTransactions(ownerId, month.id(), query, categoryId, direction, limit, cursor)
    ) {
        is Outcome.Success -> wireMap {
            ServerTransactionPage(
                items = result.value.items.map { it.toDomain(ownerId) },
                nextCursor = result.value.nextCursor,
                scanned = result.value.search?.scanned ?: 0,
                truncated = result.value.search?.truncated ?: false,
            )
        }
        is Outcome.Failure -> result
    }

    override suspend fun updateTransaction(
        ownerId: String,
        transactionId: String,
        patch: TransactionPatch,
    ): Outcome<Transaction> = when (
        val result = api.patchTransaction(
            ownerId,
            transactionId,
            com.pam.recap.data.backend.RemoteTransactionPatch(
                categoryId = patch.categoryId,
                description = patch.description,
                merchantLabel = patch.merchantLabel,
                note = patch.note,
                attributes = patch.attributes,
                revision = patch.revision,
                expectedFingerprint = patch.expectedFingerprint,
                sourceRef = patch.sourceRef,
            ),
        )
    ) {
        is Outcome.Success -> wireMap { result.value.toDomain(ownerId) }
        is Outcome.Failure -> result
    }

    override suspend fun deleteTransaction(
        ownerId: String,
        transactionId: String,
        revision: Int?,
    ): Outcome<Unit> = api.deleteTransaction(ownerId, transactionId, revision)

    override suspend fun summary(ownerId: String, month: JakartaMonth): Outcome<MonthlySummary> =
        when (val result = api.summary(ownerId, month.id())) {
            is Outcome.Success -> wireMap { result.value.toDomain(ownerId, month) }
            is Outcome.Failure -> result
        }

    override suspend fun classifyTransactions(
        ownerId: String,
        transactionIds: List<String>,
    ): Outcome<List<ServerCategoryJob>> = when (val result = api.createCategoryJobs(ownerId, transactionIds)) {
        is Outcome.Success -> Outcome.Success(result.value.jobs.map {
            ServerCategoryJob(it.id, it.transactionId, it.state, it.categoryCode,
                it.reasonCode, it.errorCode, it.provider, it.model)
        })
        is Outcome.Failure -> result
    }

    override suspend fun enqueueCategories(
        ownerId: String,
        transactionIds: List<String>,
    ): Outcome<CategoryEnqueueResult> = when (val result = api.enqueueCategoryJobs(ownerId, transactionIds)) {
        is Outcome.Success -> Outcome.Success(CategoryEnqueueResult(result.value.queued, result.value.pending))
        is Outcome.Failure -> result
    }

    override suspend fun drainCategories(ownerId: String): Outcome<CategoryDrainResult> =
        when (val result = api.drainCategoryQueue(ownerId)) {
            is Outcome.Success -> Outcome.Success(CategoryDrainResult(
                state = result.value.state,
                drained = result.value.drained,
                pending = result.value.pending,
                nextDueAt = result.value.nextDueAt?.let { runCatching { Instant.parse(it) }.getOrNull() },
            ))
            is Outcome.Failure -> result
        }

    override suspend fun createImportJob(
        ownerId: String,
        month: JakartaMonth,
        templateVersion: String,
    ): Outcome<ServerImportJob> = when (val result = api.createImportJob(ownerId, month.id(), templateVersion)) {
        is Outcome.Success -> Outcome.Success(result.value.toDomain())
        is Outcome.Failure -> result
    }

    override suspend fun importBatch(
        ownerId: String,
        jobId: String,
        batchKey: String,
        pageOrdinal: Int,
        candidates: List<ServerImportCandidate>,
    ): Outcome<ServerImportCounts> {
        val body = RemoteImportBatch(
            batchKey = batchKey,
            pageOrdinal = pageOrdinal,
            candidates = candidates.map { candidate ->
                RemoteImportCandidate(
                    fingerprint = candidate.fingerprint,
                    status = candidate.status,
                    reason = candidate.reason,
                    direction = candidate.direction,
                    amountMinor = candidate.amountMinor,
                    eventAt = candidate.eventAtEpochMilliseconds?.let {
                        Instant.fromEpochMilliseconds(it).toString()
                    },
                    categoryId = candidate.categoryId,
                    parserVersion = candidate.parserVersion,
                    confidence = candidate.confidence,
                    description = candidate.description,
                    merchantLabel = candidate.merchantLabel,
                )
            },
        )
        return when (val result = api.importBatch(ownerId, jobId, body)) {
            is Outcome.Success -> Outcome.Success(
                ServerImportCounts(
                    result.value.matched, result.value.imported, result.value.duplicates,
                    result.value.skipped, result.value.unresolved,
                    result.value.inputRevision, result.value.jobState,
                ),
            )
            is Outcome.Failure -> result
        }
    }

    override suspend fun finalizeImportJob(
        ownerId: String,
        jobId: String,
        state: String,
        errorCode: String?,
    ): Outcome<ServerImportJob> = when (val result = api.finalizeImportJob(ownerId, jobId, state, errorCode)) {
        is Outcome.Success -> Outcome.Success(result.value.toDomain())
        is Outcome.Failure -> result
    }

    private fun RemoteImportJob.toDomain() = ServerImportJob(
        id, monthId, state, matched, imported, duplicates, skipped, unresolved,
    )

    private fun RemoteTransaction.toDomainUnchecked(ownerId: String): Transaction {
        val event = Instant.parse(eventAt)
        val created = createdAt?.let { Instant.parse(it) } ?: event
        val updated = updatedAt?.let { Instant.parse(it) } ?: created
        val category = Category.fromCode(categoryCode)
            ?: if (categoryCode.isBlank()) Category.UNCATEGORIZED else Category.UNCATEGORIZED
        val origin = categoryOrigin?.let { CategoryOrigin.valueOf(it) } ?: CategoryOrigin.IMPORTED
        val confidence = parserConfidence?.toFloat()
        val categoryConfidenceValue = categoryConfidence?.toFloat()
        return Transaction(
            id = id,
            ownerId = ownerId,
            direction = Direction.valueOf(direction),
            source = TransactionSource.valueOf(source),
            amount = Money(amountMinor),
            currency = currency,
            eventAt = event,
            receivedAt = null,
            description = description.orEmpty(),
            merchantLabel = merchantLabel,
            acquirer = null,
            status = ParseStatus.POSTED,
            fingerprint = fingerprint.orEmpty(),
            parserVersion = parserVersion.orEmpty(),
            parserConfidence = confidence ?: 0f,
            category = category,
            categoryOrigin = origin,
            categoryConfidence = categoryConfidenceValue,
            categoryUpdatedAt = null,
            isInternalTransfer = isInternalTransfer,
            linkedRefundOf = linkedRefundOf,
            note = note,
            importJobId = null,
            revision = revision,
            createdAt = created,
            updatedAt = updated,
            serverCategoryId = categoryId,
            serverCategoryLabel = null,
            attributes = attributes.toStringAttributes(),
        )
    }

    /**
     * S3 attributes are a flat string map on the Android side, while the server stores a generic
     * JSON object. A non-string value cannot be edited by the dynamic-field editor, so it is a
     * protocol failure for this client rather than being silently dropped: the row is surfaced as
     * `backend_invalid_response` and never written back with the unknown value erased.
     */
    private fun JsonObject?.toStringAttributes(): Map<String, String> {
        if (this == null) return emptyMap()
        return entries.associate { (key, value) ->
            val text = value as? JsonPrimitive
            require(text != null && text.isString) { "attribute $key is not a string" }
            key to text.content
        }
    }

    private fun RemoteTransaction.toDomain(ownerId: String): Transaction {
        validateWire()
        return toDomainUnchecked(ownerId)
    }

    private fun RemoteTransaction.validateWire() {
        require(id.isNotBlank()) { "transaction id is missing" }
        require(amountMinor > 0 && amountMinor <= 1_000_000_000_000_000) { "invalid amount" }
        require(currency == "IDR") { "unsupported currency" }
        require(revision >= 0) { "invalid revision" }
        require(direction == "CREDIT" || direction == "DEBIT") { "invalid direction" }
        require(source == "EMAIL" || source == "MANUAL") { "invalid source" }
        Instant.parse(eventAt)
        createdAt?.let { Instant.parse(it) }
        updatedAt?.let { Instant.parse(it) }
        require(parserConfidence == null || parserConfidence in 0.0..1.0) { "invalid confidence" }
        require(categoryOrigin == null || categoryOrigin in setOf("IMPORTED", "RULE", "USER", "LLM")) { "invalid category origin" }
        // Empty category is the legitimate UNCATEGORIZED state for imported rows; an unknown
        // non-empty code is handled by the mapper and must not turn into Other Expense.
    }

    private inline fun <T> wireMap(block: () -> T): Outcome<T> = try {
        Outcome.Success(block())
    } catch (error: Exception) {
        Outcome.Failure(AppError.Parsing("backend_invalid_response"))
    }

    private fun RemoteSummary.toDomain(ownerId: String, month: JakartaMonth): MonthlySummary {
        require(monthId == month.id()) { "summary month mismatch" }
        require(inputRevision >= 0) { "invalid revision" }
        require(transactionCount >= 0) { "invalid count" }
        require(grossInflowMinor >= 0 && grossOutflowMinor >= 0 &&
            classifiedIncomeMinor >= 0 && classifiedExpenseMinor >= 0) { "invalid summary amount" }
        val coverage = CoverageStatus.valueOf(coverage.uppercase())
        return MonthlySummary(
            id = "server:${ownerId}:${month.id()}:$inputRevision",
            ownerId = ownerId,
            month = month,
            mailboxLabel = null,
            grossInflow = Money(grossInflowMinor),
            grossOutflow = Money(grossOutflowMinor),
            netCashFlow = Money(netMinor),
            classifiedIncome = Money(classifiedIncomeMinor),
            classifiedExpense = Money(classifiedExpenseMinor),
            categoryTotals = categoryTotals.map { total ->
                val mapped = Category.fromCode(total.categoryCode) ?: Category.OTHER_EXPENSE
                CategoryTotal(
                    category = mapped,
                    amount = Money(total.amountMinor),
                    transactionCount = total.count,
                    serverCode = total.categoryCode,
                    serverLabel = total.label,
                    serverKind = mapped.kind.name,
                )
            },
            freshness = when (freshness.uppercase()) {
                "STALE" -> SummaryFreshness.STALE
                "HISTORICAL" -> SummaryFreshness.HISTORICAL
                else -> if (currentRevision > inputRevision) SummaryFreshness.STALE else SummaryFreshness.CURRENT
            },
            uncategorizedCount = uncategorizedCount,
            manualEntryCount = manualEntryCount,
            nonIdrExcludedCount = 0,
            transactionCount = transactionCount,
            coverage = coverage,
            inputRevision = inputRevision,
            // S2.5: the canonical live revision, independent of the stored payload revision.
            currentRevision = maxOf(currentRevision, inputRevision),
            summaryVersion = "server-v1",
            generatedAt = generatedAt?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: Instant.fromEpochMilliseconds(0),
            isOutdated = false,
        )
    }
}
