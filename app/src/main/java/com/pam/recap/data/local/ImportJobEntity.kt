package com.pam.recap.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.pam.recap.domain.model.ImportState
import kotlin.time.Instant

/** §4 `ImportJobEntity` (FR-19): pages, counts and the state machine's current state. */
@Entity(
    tableName = "import_jobs",
    indices = [Index(value = ["ownerId", "monthId"], name = "idx_import_jobs_owner_month")],
)
data class ImportJobEntity(
    @PrimaryKey val id: String,
    val ownerId: String,
    val monthId: String,
    val state: ImportState,
    val pagesProcessed: Int,
    val pagesExpected: Int?,
    val matchedCount: Int,
    val importedCount: Int,
    val duplicateCount: Int,
    val skippedCount: Int,
    val unresolvedCount: Int,
    val templateVersion: String,
    val startedAt: Instant,
    val finishedAt: Instant?,
    /** Technical code only; never provider prose or email content (NFR-18). */
    val errorCode: String?,
)
