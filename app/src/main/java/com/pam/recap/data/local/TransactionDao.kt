package com.pam.recap.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Transaction persistence (FR-17/FR-18/FR-27).
 *
 * Query parameters are primitives; the enum/code conversion happens in the mapper layer. Time
 * bounds are epoch millis because the columns store millis (§4 Converters) and that is what the
 * month index is built for (NFR-07).
 */
@Dao
interface TransactionDao {

    /**
     * FR-18 dedupe: `INSERT OR IGNORE` against the unique `(ownerId, fingerprint)` index.
     * Returns the inserted row ids, with `-1` for rows that already existed.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(rows: List<TransactionEntity>): List<Long>

    @Query(
        """
        SELECT * FROM transactions
        WHERE ownerId = :ownerId AND eventAt >= :startMillis AND eventAt < :endMillis
        ORDER BY eventAt DESC
        """,
    )
    suspend fun listRange(ownerId: String, startMillis: Long, endMillis: Long): List<TransactionEntity>

    @Query(
        """
        SELECT * FROM transactions
        WHERE ownerId = :ownerId AND eventAt >= :startMillis AND eventAt < :endMillis
          AND (:categoryCode IS NULL OR category = :categoryCode)
          AND (:directionName IS NULL OR direction = :directionName)
          AND (:searchText IS NULL OR description LIKE '%' || :searchText || '%'
               OR merchantLabel LIKE '%' || :searchText || '%')
        ORDER BY eventAt DESC
        LIMIT :limit
        """,
    )
    fun observeFiltered(
        ownerId: String,
        startMillis: Long,
        endMillis: Long,
        categoryCode: String?,
        directionName: String?,
        searchText: String?,
        limit: Int,
    ): Flow<List<TransactionEntity>>

    @Query(
        """
        SELECT * FROM transactions
        WHERE ownerId = :ownerId AND eventAt >= :startMillis AND eventAt < :endMillis
          AND (:categoryCode IS NULL OR category = :categoryCode)
          AND (:directionName IS NULL OR direction = :directionName)
          AND (:searchText IS NULL OR description LIKE '%' || :searchText || '%'
               OR merchantLabel LIKE '%' || :searchText || '%')
        ORDER BY eventAt DESC
        LIMIT :limit OFFSET :offset
        """,
    )
    suspend fun pageFiltered(
        ownerId: String,
        startMillis: Long,
        endMillis: Long,
        categoryCode: String?,
        directionName: String?,
        searchText: String?,
        limit: Int,
        offset: Int,
    ): List<TransactionEntity>

    @Query(
        """
        SELECT COUNT(*) FROM transactions
        WHERE ownerId = :ownerId AND eventAt >= :startMillis AND eventAt < :endMillis
          AND (:categoryCode IS NULL OR category = :categoryCode)
          AND (:directionName IS NULL OR direction = :directionName)
          AND (:searchText IS NULL OR description LIKE '%' || :searchText || '%'
               OR merchantLabel LIKE '%' || :searchText || '%')
        """,
    )
    suspend fun countFiltered(
        ownerId: String,
        startMillis: Long,
        endMillis: Long,
        categoryCode: String?,
        directionName: String?,
        searchText: String?,
    ): Int

    @Query("SELECT * FROM transactions WHERE ownerId = :ownerId AND id = :id LIMIT 1")
    suspend fun findById(ownerId: String, id: String): TransactionEntity?

    @Query("SELECT * FROM transactions WHERE ownerId = :ownerId AND fingerprint = :fingerprint LIMIT 1")
    suspend fun findByFingerprint(ownerId: String, fingerprint: String): TransactionEntity?

    @Query("SELECT COUNT(*) FROM transactions WHERE ownerId = :ownerId")
    suspend fun countByOwner(ownerId: String): Int

    @Query(
        """
        UPDATE transactions
        SET category = :categoryCode,
            categoryOrigin = :categoryOrigin,
            categoryConfidence = :confidence,
            categoryUpdatedAt = :updatedAtMillis,
            updatedAt = :updatedAtMillis,
            revision = revision + 1
        WHERE ownerId = :ownerId AND id = :id
        """,
    )
    suspend fun updateCategory(
        ownerId: String,
        id: String,
        categoryCode: String,
        categoryOrigin: String,
        confidence: Float?,
        updatedAtMillis: Long,
    ): Int

    @Query(
        """
        UPDATE transactions
        SET isInternalTransfer = :isInternalTransfer,
            updatedAt = :updatedAtMillis,
            revision = revision + 1
        WHERE ownerId = :ownerId AND id = :id
        """,
    )
    suspend fun updateInternalTransfer(
        ownerId: String,
        id: String,
        isInternalTransfer: Boolean,
        updatedAtMillis: Long,
    ): Int

    @Query(
        """
        UPDATE transactions
        SET linkedRefundOf = :expenseId,
            updatedAt = :updatedAtMillis,
            revision = revision + 1
        WHERE ownerId = :ownerId AND id = :refundId
        """,
    )
    suspend fun updateRefundLink(ownerId: String, refundId: String, expenseId: String?, updatedAtMillis: Long): Int

    @Query(
        """
        UPDATE transactions
        SET note = :note, updatedAt = :updatedAtMillis, revision = revision + 1
        WHERE ownerId = :ownerId AND id = :id
        """,
    )
    suspend fun updateNote(ownerId: String, id: String, note: String?, updatedAtMillis: Long): Int

    /**
     * NFR-07: totals are folded in SQLite on minor units, so a 10k-row month never has to be
     * materialised just to draw the hero totals. Only POSTED IDR rows count (BR-02/BR-04).
     */
    @Query(
        """
        SELECT
            COALESCE(SUM(CASE WHEN direction = 'CREDIT' AND status = 'POSTED' AND currency = 'IDR'
                THEN amount ELSE 0 END), 0) AS grossInflowMinor,
            COALESCE(SUM(CASE WHEN direction = 'DEBIT' AND status = 'POSTED' AND currency = 'IDR'
                THEN amount ELSE 0 END), 0) AS grossOutflowMinor,
            COALESCE(SUM(CASE WHEN direction = 'CREDIT' AND status = 'POSTED' AND currency = 'IDR'
                THEN 1 ELSE 0 END), 0) AS creditCount,
            COALESCE(SUM(CASE WHEN direction = 'DEBIT' AND status = 'POSTED' AND currency = 'IDR'
                THEN 1 ELSE 0 END), 0) AS debitCount,
            COUNT(*) AS transactionCount,
            COALESCE(SUM(CASE WHEN currency <> 'IDR' THEN 1 ELSE 0 END), 0) AS nonIdrCount,
            COALESCE(SUM(CASE WHEN category = 'uncategorized' THEN 1 ELSE 0 END), 0) AS uncategorizedCount,
            COALESCE(SUM(CASE WHEN source = 'MANUAL' THEN 1 ELSE 0 END), 0) AS manualCount,
            MAX(eventAt) AS latestEventAt,
            MAX(updatedAt) AS latestUpdatedAt
        FROM transactions
        WHERE ownerId = :ownerId AND eventAt >= :startMillis AND eventAt < :endMillis
        """,
    )
    fun observeMonthTotals(ownerId: String, startMillis: Long, endMillis: Long): Flow<MonthTotalsRow>

    @Query(
        """
        SELECT
            COALESCE(SUM(CASE WHEN direction = 'CREDIT' AND status = 'POSTED' AND currency = 'IDR'
                THEN amount ELSE 0 END), 0) AS grossInflowMinor,
            COALESCE(SUM(CASE WHEN direction = 'DEBIT' AND status = 'POSTED' AND currency = 'IDR'
                THEN amount ELSE 0 END), 0) AS grossOutflowMinor,
            COALESCE(SUM(CASE WHEN direction = 'CREDIT' AND status = 'POSTED' AND currency = 'IDR'
                THEN 1 ELSE 0 END), 0) AS creditCount,
            COALESCE(SUM(CASE WHEN direction = 'DEBIT' AND status = 'POSTED' AND currency = 'IDR'
                THEN 1 ELSE 0 END), 0) AS debitCount,
            COUNT(*) AS transactionCount,
            COALESCE(SUM(CASE WHEN currency <> 'IDR' THEN 1 ELSE 0 END), 0) AS nonIdrCount,
            COALESCE(SUM(CASE WHEN category = 'uncategorized' THEN 1 ELSE 0 END), 0) AS uncategorizedCount,
            COALESCE(SUM(CASE WHEN source = 'MANUAL' THEN 1 ELSE 0 END), 0) AS manualCount,
            MAX(eventAt) AS latestEventAt,
            MAX(updatedAt) AS latestUpdatedAt
        FROM transactions
        WHERE ownerId = :ownerId AND eventAt >= :startMillis AND eventAt < :endMillis
        """,
    )
    suspend fun monthTotals(ownerId: String, startMillis: Long, endMillis: Long): MonthTotalsRow

    @Query(
        """
        SELECT COALESCE(SUM(amount), 0) FROM transactions
        WHERE ownerId = :ownerId AND category = :categoryCode
          AND eventAt >= :startMillis AND eventAt < :endMillis
          AND direction = 'DEBIT' AND status = 'POSTED' AND currency = 'IDR'
        """,
    )
    suspend fun totalDebitForCategory(
        ownerId: String,
        categoryCode: String,
        startMillis: Long,
        endMillis: Long,
    ): Long

    @Query("DELETE FROM transactions WHERE ownerId = :ownerId")
    suspend fun deleteAll(ownerId: String)
}
