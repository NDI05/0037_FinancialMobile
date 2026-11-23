package com.pam.recap.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.pam.recap.domain.model.RedactionReason
import kotlin.time.Instant

/**
 * §4 `SkippedCandidateEntity` (FR-15). This table is proof of what PAM deliberately does *not*
 * keep: only the redacted [reasonCode] and positional ordinals, with no subject, body, sender,
 * message id or merchant label column at all.
 */
@Entity(
    tableName = "skipped_candidates",
    indices = [Index(value = ["jobId"], name = "idx_skipped_candidates_job")],
)
data class SkippedCandidateEntity(
    @PrimaryKey val id: String,
    val jobId: String,
    val ownerId: String,
    val reasonCode: RedactionReason,
    val pageOrdinal: Int,
    val candidateOrdinal: Int,
    val createdAt: Instant,
)
