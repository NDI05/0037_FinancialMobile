package com.pam.recap.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.pam.recap.domain.model.ConsentDecision
import com.pam.recap.domain.model.ConsentPurpose
import kotlin.time.Instant

/**
 * §4 `ConsentEntity` (FR-03/FR-49): append-only ledger. Rows are never updated or deleted by
 * normal flows, so the history shown on the consent-history screen is a real audit trail.
 */
@Entity(
    tableName = "consents",
    indices = [Index(value = ["ownerId", "purpose"], name = "idx_consents_owner_purpose")],
)
data class ConsentEntity(
    @PrimaryKey val id: String,
    val ownerId: String,
    val purpose: ConsentPurpose,
    val noticeVersion: String,
    val decision: ConsentDecision,
    val decidedAt: Instant,
)
