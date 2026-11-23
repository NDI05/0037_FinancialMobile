package com.pam.recap.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.pam.recap.core.money.Money
import kotlin.time.Instant

/** §4 `BudgetPlanEntity` (FR-42/FR-44): one active plan per `(ownerId, monthId)`. */
@Entity(
    tableName = "budget_plans",
    indices = [
        Index(
            value = ["ownerId", "monthId"],
            unique = true,
            name = "idx_budget_plans_owner_month",
        ),
    ],
)
data class BudgetPlanEntity(
    @PrimaryKey val id: String,
    val ownerId: String,
    val monthId: String,
    val expectedIncome: Money,
    val plannedSavings: Money,
    val gap: Money,
    val revision: Int,
    /** `Map<Category, ValueOrigin>` as JSON: origins are presentation metadata, not queried. */
    val valueOriginsJson: String,
    val createdAt: Instant,
    val updatedAt: Instant,
)
