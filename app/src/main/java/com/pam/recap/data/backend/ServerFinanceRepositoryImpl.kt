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
import com.pam.recap.domain.model.ParseStatus
import com.pam.recap.domain.model.Transaction
import com.pam.recap.domain.model.TransactionSource
import com.pam.recap.domain.repository.ManualTransactionDraft
import com.pam.recap.domain.repository.ServerCategory
import com.pam.recap.domain.repository.ServerImportCandidate
import com.pam.recap.domain.repository.ServerImportCounts
import com.pam.recap.domain.repository.ServerImportJob
import com.pam.recap.domain.repository.ServerFinanceRepository
import com.pam.recap.domain.repository.ServerTransactionPage
import kotlinx.serialization.Serializable
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
                is Outcome.Success -> Outcome.Success(result.value.categories.map {
                    ServerCategory(it.id, it.code, it.label.orEmpty(), it.kind)
                })
                is Outcome.Failure -> result
            }
        }

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

    override suspend fun summary(ownerId: String, month: JakartaMonth): Outcome<MonthlySummary> =
        when (val result = api.summary(ownerId, month.id())) {
            is Outcome.Success -> wireMap { result.value.toDomain(ownerId, month) }
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
        val category = Category.fromCode(categoryCode) ?: Category.OTHER_EXPENSE
        val origin = categoryOrigin?.let { CategoryOrigin.valueOf(it) } ?: CategoryOrigin.IMPORTED
        val confidence = parserConfidence?.toFloat()
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
            categoryConfidence = confidence,
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
        )
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
        require(categoryOrigin == null || categoryOrigin in setOf("IMPORTED", "RULE", "USER")) { "invalid category origin" }
        require(categoryCode.isNotBlank() || source == "MANUAL") { "missing category" }
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
            uncategorizedCount = uncategorizedCount,
            manualEntryCount = manualEntryCount,
            nonIdrExcludedCount = 0,
            transactionCount = transactionCount,
            coverage = coverage,
            inputRevision = inputRevision,
            summaryVersion = "server-v1",
            generatedAt = generatedAt?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: Instant.fromEpochMilliseconds(0),
            isOutdated = false,
        )
    }
}
