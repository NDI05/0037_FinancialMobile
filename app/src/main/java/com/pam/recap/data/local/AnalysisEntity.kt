package com.pam.recap.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.pam.recap.domain.model.AnalysisStatus
import kotlin.time.Instant

/**
 * §4 `AnalysisEntity` (FR-39). Structured sections are stored as JSON strings via
 * kotlinx.serialization so the provider output can evolve without a schema migration, while the
 * columns that matter for queries and cache keys stay first-class.
 */
@Entity(
    tableName = "analyses",
    indices = [Index(value = ["ownerId", "monthId"], name = "idx_analyses_owner_month")],
)
data class AnalysisEntity(
    @PrimaryKey val id: String,
    val ownerId: String,
    val monthId: String,
    val summaryId: String,
    val inputRevision: Int,
    val consentId: String?,
    val providerId: String,
    val modelId: String,
    val promptVersion: String,
    val observationsJson: String,
    val suggestionsJson: String,
    val limitationsJson: String,
    val status: AnalysisStatus,
    val validationJson: String,
    val usageJson: String?,
    val createdAt: Instant,
)
