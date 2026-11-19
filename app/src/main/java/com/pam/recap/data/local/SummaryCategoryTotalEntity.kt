package com.pam.recap.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import com.pam.recap.core.money.Money
import com.pam.recap.domain.model.Category

/**
 * §4 `SummaryCategoryTotalEntity`: the per-category split of a stored summary, keyed by
 * `(summaryId, categoryCode)`. Cascades with its summary so a recompute cannot leave orphans.
 */
@Entity(
    tableName = "summary_category_totals",
    primaryKeys = ["summaryId", "categoryCode"],
    foreignKeys = [
        ForeignKey(
            entity = MonthlySummaryEntity::class,
            parentColumns = ["id"],
            childColumns = ["summaryId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["summaryId"], name = "idx_summary_category_totals_summary")],
)
data class SummaryCategoryTotalEntity(
    val summaryId: String,
    @ColumnInfo(name = "categoryCode") val categoryCode: Category,
    val amount: Money,
    val transactionCount: Int,
)
