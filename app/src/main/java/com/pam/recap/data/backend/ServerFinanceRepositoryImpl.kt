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
        is Outcome.Success -> Outcome.Success(result.value.toDomain(ownerId))
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
        is Outcome.Success -> Outcome.Success(
            ServerTransactionPage(result.value.items.map { it.toDomain(ownerId) }, result.value.nextCursor),
        )
        is Outcome.Failure -> result
    }

    override suspend fun get(ownerId: String, transactionId: String): Outcome<Transaction> =
        when (val result = api.getTransaction(ownerId, transactionId)) {
            is Outcome.Success -> Outcome.Success(result.value.toDomain(ownerId))
            is Outcome.Failure -> result
        }

    override suspend fun summary(ownerId: String, month: JakartaMonth): Outcome<MonthlySummary> =
        when (val result = api.summary(ownerId, month.id())) {
            is Outcome.Success -> Outcome.Success(result.value.toDomain(ownerId, month))
            is Outcome.Failure -> result
        }

    private fun RemoteTransaction.toDomain(ownerId: String): Transaction {
        val event = runCatching { Instant.parse(eventAt) }
            .getOrElse { Instant.fromEpochMilliseconds(0) }
        val created = createdAt?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: event
        val updated = updatedAt?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: created
        val category = Category.fromCode(categoryCode) ?: Category.OTHER_EXPENSE
        return Transaction(
            id = id,
            ownerId = ownerId,
            direction = if (direction == "CREDIT") Direction.CREDIT else Direction.DEBIT,
            source = if (source == "EMAIL") TransactionSource.EMAIL else TransactionSource.MANUAL,
            amount = Money(amountMinor),
            currency = currency,
            eventAt = event,
            receivedAt = null,
            description = description.orEmpty(),
            merchantLabel = merchantLabel,
            acquirer = null,
            status = ParseStatus.POSTED,
            fingerprint = "",
            parserVersion = "server-v1",
            parserConfidence = 1f,
            category = category,
            categoryOrigin = if (categoryCode.isBlank()) CategoryOrigin.IMPORTED else CategoryOrigin.USER,
            categoryConfidence = null,
            categoryUpdatedAt = null,
            isInternalTransfer = isInternalTransfer,
            linkedRefundOf = null,
            note = null,
            importJobId = null,
            revision = revision,
            createdAt = created,
            updatedAt = updated,
            serverCategoryId = categoryId,
        )
    }

    private fun RemoteSummary.toDomain(ownerId: String, month: JakartaMonth): MonthlySummary {
        val coverage = runCatching { CoverageStatus.valueOf(coverage.uppercase()) }
            .getOrElse { CoverageStatus.PARTIAL }
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
            categoryTotals = categoryTotals.mapNotNull { total ->
                Category.fromCode(total.categoryCode)?.let {
                    CategoryTotal(it, Money(total.amountMinor), total.count)
                }
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
