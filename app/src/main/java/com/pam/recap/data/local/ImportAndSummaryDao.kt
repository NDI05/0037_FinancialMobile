package com.pam.recap.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** FR-19 job persistence. One row is inserted at creation and replaced on every update. */
@Dao
interface ImportJobDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(job: ImportJobEntity)

    @Query("SELECT * FROM import_jobs WHERE id = :jobId LIMIT 1")
    fun observe(jobId: String): Flow<ImportJobEntity?>

    @Query("SELECT * FROM import_jobs WHERE id = :jobId LIMIT 1")
    suspend fun findById(jobId: String): ImportJobEntity?

    @Query(
        """
        SELECT * FROM import_jobs
        WHERE ownerId = :ownerId AND monthId = :monthId
        ORDER BY startedAt DESC LIMIT 1
        """,
    )
    suspend fun latestForMonth(ownerId: String, monthId: String): ImportJobEntity?

    @Query("DELETE FROM import_jobs WHERE ownerId = :ownerId")
    suspend fun deleteAll(ownerId: String)
}

/** FR-15 redacted skipped candidates. */
@Dao
interface SkippedCandidateDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(rows: List<SkippedCandidateEntity>)

    @Query("SELECT * FROM skipped_candidates WHERE jobId = :jobId ORDER BY candidateOrdinal ASC")
    suspend fun forJob(jobId: String): List<SkippedCandidateEntity>

    @Query(
        """
        SELECT reasonCode AS reasonCode, COUNT(*) AS count FROM skipped_candidates
        WHERE jobId = :jobId GROUP BY reasonCode
        """,
    )
    suspend fun reasonCounts(jobId: String): List<ReasonCountRow>

    @Query("DELETE FROM skipped_candidates WHERE ownerId = :ownerId")
    suspend fun deleteAll(ownerId: String)
}

/** FR-30/FR-33 summaries. */
@Dao
interface MonthlySummaryDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(summary: MonthlySummaryEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCategoryTotals(rows: List<SummaryCategoryTotalEntity>)

    @Query("DELETE FROM summary_category_totals WHERE summaryId = :summaryId")
    suspend fun deleteCategoryTotals(summaryId: String)

    @Query(
        """
        SELECT * FROM monthly_summaries
        WHERE ownerId = :ownerId AND monthId = :monthId AND inputRevision = :inputRevision
        LIMIT 1
        """,
    )
    suspend fun forRevision(ownerId: String, monthId: String, inputRevision: Int): MonthlySummaryEntity?

    @Query(
        """
        SELECT * FROM monthly_summaries
        WHERE ownerId = :ownerId AND monthId = :monthId
        ORDER BY inputRevision DESC LIMIT 1
        """,
    )
    suspend fun latest(ownerId: String, monthId: String): MonthlySummaryEntity?

    @Query(
        """
        SELECT * FROM monthly_summaries
        WHERE ownerId = :ownerId AND monthId = :monthId
        ORDER BY inputRevision DESC LIMIT 1
        """,
    )
    fun observeLatest(ownerId: String, monthId: String): Flow<MonthlySummaryEntity?>

    @Query("SELECT * FROM summary_category_totals WHERE summaryId = :summaryId")
    suspend fun categoryTotals(summaryId: String): List<SummaryCategoryTotalEntity>

    @Query(
        """
        UPDATE monthly_summaries SET isOutdated = 1
        WHERE ownerId = :ownerId AND monthId = :monthId
        """,
    )
    suspend fun markOutdated(ownerId: String, monthId: String)

    @Query("DELETE FROM monthly_summaries WHERE ownerId = :ownerId")
    suspend fun deleteAll(ownerId: String)
}
