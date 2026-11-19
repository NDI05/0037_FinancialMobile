package com.pam.recap.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.pam.recap.core.money.Money
import com.pam.recap.domain.model.Category
import com.pam.recap.domain.model.CategoryOrigin
import com.pam.recap.domain.model.Direction
import com.pam.recap.domain.model.ParseStatus
import com.pam.recap.domain.model.TransactionSource
import kotlin.time.Instant

/**
 * §4 `TransactionEntity`. Stores only the normalized fields of §3 — never a message id, body,
 * subject or sender (hard rule 7, FR-17). The message id lives only in memory long enough to
 * compute [fingerprint] (FR-18).
 *
 * Indices match the architecture: unique `(ownerId, fingerprint)` for dedupe, `(ownerId, eventAt)`
 * for month ranges, `(ownerId, category)` for drill-downs.
 */
@Entity(
    tableName = "transactions",
    indices = [
        Index(value = ["ownerId", "fingerprint"], unique = true, name = "idx_transactions_owner_fingerprint"),
        Index(value = ["ownerId", "eventAt"], name = "idx_transactions_owner_event_at"),
        Index(value = ["ownerId", "category"], name = "idx_transactions_owner_category"),
    ],
)
data class TransactionEntity(
    @PrimaryKey val id: String,
    val ownerId: String,
    val direction: Direction,
    val source: TransactionSource,
    /** Minor units (1/100 IDR); SQL `SUM(amount)` stays exact (BR-04, NFR-07). */
    val amount: Money,
    val currency: String,
    /** Parsed event time in epoch millis; month membership key (BR-01/NFR-04). */
    val eventAt: Instant,
    /** Gmail internalDate, kept separately (NFR-04). */
    val receivedAt: Instant?,
    val description: String,
    val merchantLabel: String?,
    val acquirer: String?,
    val status: ParseStatus,
    val fingerprint: String,
    val parserVersion: String,
    val parserConfidence: Float,
    /** Persisted as the stable `Category.code` under the column name `category` (§4). */
    @ColumnInfo(name = "category") val categoryCode: Category,
    val categoryOrigin: CategoryOrigin,
    val categoryConfidence: Float?,
    val categoryUpdatedAt: Instant?,
    val isInternalTransfer: Boolean,
    val linkedRefundOf: String?,
    val note: String?,
    val importJobId: String?,
    val revision: Int,
    val createdAt: Instant,
    val updatedAt: Instant,
)
