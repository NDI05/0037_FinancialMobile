package com.pam.recap.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import com.pam.recap.core.money.Money
import com.pam.recap.domain.model.Category

/** §4 `BudgetLimitEntity`: per-category envelope amount, keyed by `(planId, categoryCode)`. */
@Entity(
    tableName = "budget_limits",
    primaryKeys = ["planId", "categoryCode"],
    foreignKeys = [
        ForeignKey(
            entity = BudgetPlanEntity::class,
            parentColumns = ["id"],
            childColumns = ["planId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["planId"], name = "idx_budget_limits_plan")],
)
data class BudgetLimitEntity(
    val planId: String,
    @ColumnInfo(name = "categoryCode") val categoryCode: Category,
    val amount: Money,
)
