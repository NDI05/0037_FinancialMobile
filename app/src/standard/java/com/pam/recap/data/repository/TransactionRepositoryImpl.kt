package com.pam.recap.data.repository

import androidx.room.withTransaction
import com.pam.recap.core.di.IoDispatcher
import com.pam.recap.core.money.Money
import com.pam.recap.core.time.ClockProvider
import com.pam.recap.core.time.JakartaMonth
import com.pam.recap.data.local.AppMetadataDao
import com.pam.recap.data.local.AppMetadataEntity
import com.pam.recap.data.local.PamDatabase
import com.pam.recap.data.local.TransactionDao
import com.pam.recap.data.local.TransactionEntity
import com.pam.recap.domain.model.Category
import com.pam.recap.domain.model.CategoryOrigin
import com.pam.recap.domain.model.InsertResult
import com.pam.recap.domain.model.MonthTotals
import com.pam.recap.domain.model.Transaction
import com.pam.recap.domain.model.TransactionFilter
import com.pam.recap.domain.model.TransactionPage
import com.pam.recap.domain.repository.TransactionRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Instant

/**
 * Room implementation of [TransactionRepository] (FR-02/FR-17/FR-18/FR-27).
 *
 * The month revision counter lives in `app_metadata` and is bumped inside the same transaction as
 * the mutation, so a summary can never be stamped with a revision that excludes its own inputs
 * (FR-33). Mutations are owner-checked even though the SQL already filters by owner, because a
 * revision bump must not leak across accounts (FR-02).
 */
@Singleton
class TransactionRepositoryImpl @Inject constructor(
    private val database: PamDatabase,
    private val transactionDao: TransactionDao,
    private val metadataDao: AppMetadataDao,
    private val clock: ClockProvider,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : TransactionRepository {

    override fun observeMonth(
        ownerId: String,
        month: JakartaMonth,
        filter: TransactionFilter?,
    ): Flow<List<Transaction>> {
        val range = month.range()
        return transactionDao.observeFiltered(
            ownerId = ownerId,
            startMillis = range.startInclusive.toEpochMilliseconds(),
            endMillis = range.endExclusive.toEpochMilliseconds(),
            categoryCode = filter?.category?.code,
            directionName = filter?.direction?.name,
            searchText = filter?.query?.takeIf { it.isNotBlank() },
            limit = filter?.limit ?: TransactionFilter.DEFAULT_LIMIT,
        ).map { rows -> rows.map { it.toDomain() } }
            .flowOn(ioDispatcher)
    }

    override suspend fun listMonth(ownerId: String, month: JakartaMonth): List<Transaction> =
        withContext(ioDispatcher) {
            val range = month.range()
            transactionDao.listRange(
                ownerId = ownerId,
                startMillis = range.startInclusive.toEpochMilliseconds(),
                endMillis = range.endExclusive.toEpochMilliseconds(),
            ).map { it.toDomain() }
        }

    override suspend fun listByImportJob(ownerId: String, importJobId: String): List<Transaction> =
        withContext(ioDispatcher) {
            transactionDao.listByImportJob(ownerId, importJobId).map { it.toDomain() }
        }

    override suspend fun page(
        ownerId: String,
        filter: TransactionFilter,
        offset: Int,
        limit: Int,
    ): TransactionPage = withContext(ioDispatcher) {
        require(offset >= 0) { "offset must be >= 0" }
        require(limit > 0) { "limit must be positive" }
        val range = filter.month.range()
        val startMillis = range.startInclusive.toEpochMilliseconds()
        val endMillis = range.endExclusive.toEpochMilliseconds()
        val rows = transactionDao.pageFiltered(
            ownerId = ownerId,
            startMillis = startMillis,
            endMillis = endMillis,
            categoryCode = filter.category?.code,
            directionName = filter.direction?.name,
            searchText = filter.query?.takeIf { it.isNotBlank() },
            limit = limit,
            offset = offset,
        )
        val total = transactionDao.countFiltered(
            ownerId = ownerId,
            startMillis = startMillis,
            endMillis = endMillis,
            categoryCode = filter.category?.code,
            directionName = filter.direction?.name,
            searchText = filter.query?.takeIf { it.isNotBlank() },
        )
        TransactionPage(
            items = rows.map { it.toDomain() },
            totalCount = total,
            offset = offset,
            hasMore = offset + rows.size < total,
        )
    }

    override suspend fun get(ownerId: String, id: String): Transaction? = withContext(ioDispatcher) {
        transactionDao.findById(ownerId, id)?.toDomain()
    }

    override suspend fun findByFingerprint(ownerId: String, fingerprint: String): Transaction? =
        withContext(ioDispatcher) {
            transactionDao.findByFingerprint(ownerId, fingerprint)?.toDomain()
        }

    override suspend fun insertIgnoringDuplicates(
        ownerId: String,
        transactions: List<Transaction>,
    ): InsertResult = withContext(ioDispatcher) {
        if (transactions.isEmpty()) return@withContext InsertResult.EMPTY
        database.withTransaction {
            val ids = transactionDao.insertAll(transactions.map { it.toEntity() })
            val inserted = ids.count { it != -1L }
            if (inserted > 0) {
                transactions.map { JakartaMonth.of(it.eventAt) }.distinct().forEach { month ->
                    bumpRevisionLocked(ownerId, month)
                }
            }
            InsertResult(inserted = inserted, ignored = ids.count { it == -1L })
        }
    }

    override suspend fun updateCategory(
        ownerId: String,
        id: String,
        category: Category,
        origin: CategoryOrigin,
        confidence: Float?,
        at: Instant,
    ): Boolean = mutateOwned(ownerId, id) { entity ->
        transactionDao.updateCategory(
            ownerId = ownerId,
            id = id,
            categoryCode = category.code,
            categoryOrigin = origin.name,
            confidence = confidence,
            updatedAtMillis = at.toEpochMilliseconds(),
        ) > 0
    }

    override suspend fun setInternalTransfer(
        ownerId: String,
        id: String,
        isInternalTransfer: Boolean,
        at: Instant,
    ): Boolean = mutateOwned(ownerId, id) {
        transactionDao.updateInternalTransfer(
            ownerId = ownerId,
            id = id,
            isInternalTransfer = isInternalTransfer,
            updatedAtMillis = at.toEpochMilliseconds(),
        ) > 0
    }

    override suspend fun linkRefund(ownerId: String, refundId: String, expenseId: String?, at: Instant): Boolean =
        mutateOwned(ownerId, refundId) {
            transactionDao.updateRefundLink(
                ownerId = ownerId,
                refundId = refundId,
                expenseId = expenseId,
                updatedAtMillis = at.toEpochMilliseconds(),
            ) > 0
        }

    override suspend fun updateNote(ownerId: String, id: String, note: String?, at: Instant): Boolean =
        mutateOwned(ownerId, id) {
            transactionDao.updateNote(
                ownerId = ownerId,
                id = id,
                note = note,
                updatedAtMillis = at.toEpochMilliseconds(),
            ) > 0
        }

    override suspend fun applyMerchantRepair(
        ownerId: String,
        id: String,
        expectedFingerprint: String,
        merchantLabel: String,
        fingerprint: String,
        at: Instant,
    ): Boolean = withContext(ioDispatcher) {
        database.withTransaction {
            val changed = transactionDao.updateMerchantRepair(
                ownerId = ownerId,
                id = id,
                expectedFingerprint = expectedFingerprint,
                merchantLabel = merchantLabel,
                fingerprint = fingerprint,
                updatedAtMillis = at.toEpochMilliseconds(),
            ) > 0
            if (changed) {
                val entity = transactionDao.findAnyById(id)
                if (entity != null) bumpRevisionLocked(ownerId, JakartaMonth.of(entity.eventAt))
            }
            changed
        }
    }

    override suspend fun metadataValue(ownerId: String, key: String): String? =
        withContext(ioDispatcher) { metadataDao.value(metadataKey(ownerId, key)) }

    override suspend fun putMetadata(ownerId: String, key: String, value: String): Unit =
        withContext(ioDispatcher) {
            metadataDao.upsert(
                AppMetadataEntity(
                    key = metadataKey(ownerId, key),
                    value = value,
                    updatedAtEpochMillis = clock.nowMillis(),
                ),
            )
        }

    override suspend fun monthRevision(ownerId: String, month: JakartaMonth): Int =
        withContext(ioDispatcher) { readRevision(ownerId, month) }

    override suspend fun bumpMonthRevision(ownerId: String, month: JakartaMonth): Int =
        withContext(ioDispatcher) {
            database.withTransaction { bumpRevisionLocked(ownerId, month) }
        }

    override fun observeMonthTotals(ownerId: String, month: JakartaMonth): Flow<MonthTotals> {
        val range = month.range()
        return transactionDao.observeMonthTotals(
            ownerId = ownerId,
            startMillis = range.startInclusive.toEpochMilliseconds(),
            endMillis = range.endExclusive.toEpochMilliseconds(),
        ).map { it.toDomain() }.flowOn(ioDispatcher)
    }

    override suspend fun monthTotals(ownerId: String, month: JakartaMonth): MonthTotals =
        withContext(ioDispatcher) {
            val range = month.range()
            transactionDao.monthTotals(
                ownerId = ownerId,
                startMillis = range.startInclusive.toEpochMilliseconds(),
                endMillis = range.endExclusive.toEpochMilliseconds(),
            ).toDomain()
        }

    override suspend fun totalAmountForCategory(
        ownerId: String,
        month: JakartaMonth,
        category: Category,
    ): Money = withContext(ioDispatcher) {
        val range = month.range()
        Money(
            transactionDao.totalDebitForCategory(
                ownerId = ownerId,
                categoryCode = category.code,
                startMillis = range.startInclusive.toEpochMilliseconds(),
                endMillis = range.endExclusive.toEpochMilliseconds(),
            ),
        )
    }

    override suspend fun latestEventAt(ownerId: String): Instant? = withContext(ioDispatcher) {
        transactionDao.latestEventAt(ownerId)?.let { Instant.fromEpochMilliseconds(it) }
    }

    override suspend fun countByIdPrefix(ownerId: String, prefix: String): Int =
        withContext(ioDispatcher) { transactionDao.countByIdPrefix(ownerId, prefix) }

    override suspend fun deleteByIdPrefix(ownerId: String, prefix: String): Int =
        withContext(ioDispatcher) {
            database.withTransaction {
                val removed = transactionDao.deleteByIdPrefix(ownerId, prefix)
                if (removed > 0) {
                    // Removing rows changes the month's inputs: bump every revision this owner has
                    // so a stale summary can never look current (FR-33).
                    metadataDao.deleteByPrefix(revisionPrefix(ownerId))
                }
                removed
            }
        }

    override suspend fun deleteAll(ownerId: String): Unit = withContext(ioDispatcher) {
        database.withTransaction {
            transactionDao.deleteAll(ownerId)
            metadataDao.deleteByPrefix(revisionPrefix(ownerId))
        }
    }

    // ---------------------------------------------------------------------------------------

    /** Applies a mutation only when the row belongs to [ownerId], then bumps its month revision. */
    private suspend fun mutateOwned(
        ownerId: String,
        id: String,
        block: suspend (TransactionEntity) -> Boolean,
    ): Boolean = withContext(ioDispatcher) {
        database.withTransaction {
            val entity = transactionDao.findAnyById(id) ?: return@withTransaction false
            if (entity.ownerId != ownerId) return@withTransaction false
            val changed = block(entity)
            if (changed) bumpRevisionLocked(ownerId, JakartaMonth.of(entity.eventAt))
            changed
        }
    }

    private suspend fun readRevision(ownerId: String, month: JakartaMonth): Int =
        metadataDao.value(revisionKey(ownerId, month))?.toIntOrNull() ?: 0

    private suspend fun bumpRevisionLocked(ownerId: String, month: JakartaMonth): Int {
        val next = readRevision(ownerId, month) + 1
        metadataDao.upsert(
            AppMetadataEntity(
                key = revisionKey(ownerId, month),
                value = next.toString(),
                updatedAtEpochMillis = clock.nowMillis(),
            ),
        )
        return next
    }

    private fun revisionKey(ownerId: String, month: JakartaMonth): String =
        "${revisionPrefix(ownerId)}${month.id()}"

    private fun revisionPrefix(ownerId: String): String = "revision:$ownerId:"

    private fun metadataKey(ownerId: String, key: String): String = "meta:$ownerId:$key"
}
