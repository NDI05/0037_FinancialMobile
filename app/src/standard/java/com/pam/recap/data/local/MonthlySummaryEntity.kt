package com.pam.recap.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.pam.recap.core.money.Money
import com.pam.recap.domain.model.CoverageStatus
import kotlin.time.Instant

/**
 * §4 `MonthlySummaryEntity` (FR-30/FR-33). Unique per `(ownerId, monthId, inputRevision)` so a
 * recompute after an input change never overwrites the previous, auditable summary.
 */
@Entity(
    tableName = "monthly_summaries",
    indices = [
        Index(
            value = ["ownerId", "monthId", "inputRevision"],
            unique = true,
            name = "idx_monthly_summaries_owner_month_revision",
        ),
        Index(value = ["ownerId", "monthId"], name = "idx_monthly_summaries_owner_month"),
    ],
)
data class MonthlySummaryEntity(
    @PrimaryKey val id: String,
    val ownerId: String,
    val monthId: String,
    val mailboxLabel: String?,
    val grossInflow: Money,
    val grossOutflow: Money,
    val netCashFlow: Money,
    val classifiedIncome: Money,
    val classifiedExpense: Money,
    val uncategorizedCount: Int,
    val manualEntryCount: Int,
    val nonIdrExcludedCount: Int,
    val transactionCount: Int,
    val coverage: CoverageStatus,
    val inputRevision: Int,
    val summaryVersion: String,
    val generatedAt: Instant,
    val isOutdated: Boolean,
)
