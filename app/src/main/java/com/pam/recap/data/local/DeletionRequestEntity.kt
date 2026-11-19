package com.pam.recap.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.pam.recap.domain.model.DeletionState
import kotlin.time.Instant

/** §4 `DeletionRequestEntity` (FR-47/FR-48): one row per request, newest wins. */
@Entity(
    tableName = "deletion_requests",
    indices = [Index(value = ["ownerId"], name = "idx_deletion_requests_owner")],
)
data class DeletionRequestEntity(
    @PrimaryKey val id: String,
    val ownerId: String,
    val state: DeletionState,
    val requestedAt: Instant,
    val reauthenticatedAt: Instant?,
    val completedAt: Instant?,
    val failureCode: String?,
)
