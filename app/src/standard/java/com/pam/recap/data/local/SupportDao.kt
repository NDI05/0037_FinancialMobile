package com.pam.recap.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** FR-39/FR-40 analysis persistence. */
@Dao
interface AnalysisDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(analysis: AnalysisEntity)

    @Query(
        """
        SELECT * FROM analyses
        WHERE ownerId = :ownerId AND monthId = :monthId
        ORDER BY createdAt DESC LIMIT 1
        """,
    )
    suspend fun latest(ownerId: String, monthId: String): AnalysisEntity?

    @Query(
        """
        SELECT * FROM analyses
        WHERE ownerId = :ownerId AND monthId = :monthId
        ORDER BY createdAt DESC LIMIT 1
        """,
    )
    fun observeLatest(ownerId: String, monthId: String): Flow<AnalysisEntity?>

    /** FR-22/FR-40: current results become outdated when the month's inputs change. */
    @Query(
        """
        UPDATE analyses SET status = 'OUTDATED'
        WHERE ownerId = :ownerId AND monthId = :monthId
          AND status IN ('AVAILABLE', 'PROVISIONAL')
        """,
    )
    suspend fun markOutdated(ownerId: String, monthId: String)

    /** FR-04: withdrawal keeps the report but marks it historical. */
    @Query(
        """
        UPDATE analyses SET status = 'HISTORICAL'
        WHERE ownerId = :ownerId AND status IN ('AVAILABLE', 'PROVISIONAL', 'OUTDATED')
        """,
    )
    suspend fun markAllHistorical(ownerId: String)

    @Query("DELETE FROM analyses WHERE ownerId = :ownerId")
    suspend fun deleteAll(ownerId: String)
}


/** FR-03/FR-49 consent ledger. */
@Dao
interface ConsentDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun append(consent: ConsentEntity)

    @Query("SELECT * FROM consents WHERE ownerId = :ownerId ORDER BY decidedAt DESC")
    fun observeLedger(ownerId: String): Flow<List<ConsentEntity>>

    @Query(
        """
        SELECT * FROM consents
        WHERE ownerId = :ownerId AND purpose = :purpose
        ORDER BY decidedAt DESC LIMIT 1
        """,
    )
    fun observeLatest(ownerId: String, purpose: String): Flow<ConsentEntity?>

    @Query(
        """
        SELECT * FROM consents
        WHERE ownerId = :ownerId AND purpose = :purpose
        ORDER BY decidedAt DESC LIMIT 1
        """,
    )
    suspend fun latest(ownerId: String, purpose: String): ConsentEntity?

    @Query("DELETE FROM consents WHERE ownerId = :ownerId")
    suspend fun deleteAll(ownerId: String)
}

/** FR-07/FR-49 connection facts. */
@Dao
interface GmailConnectionDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(connection: GmailConnectionEntity)

    @Query("SELECT * FROM gmail_connection WHERE ownerId = :ownerId LIMIT 1")
    fun observe(ownerId: String): Flow<GmailConnectionEntity?>

    @Query("SELECT * FROM gmail_connection WHERE ownerId = :ownerId LIMIT 1")
    suspend fun find(ownerId: String): GmailConnectionEntity?

    @Query("UPDATE gmail_connection SET lastImportAt = :atMillis, updatedAt = :atMillis WHERE ownerId = :ownerId")
    suspend fun touchLastImport(ownerId: String, atMillis: Long): Int

    @Query("UPDATE gmail_connection SET lastAiAt = :atMillis, updatedAt = :atMillis WHERE ownerId = :ownerId")
    suspend fun touchLastAi(ownerId: String, atMillis: Long): Int

    @Query("DELETE FROM gmail_connection WHERE ownerId = :ownerId")
    suspend fun deleteAll(ownerId: String)
}

/** FR-47/FR-48 deletion requests. */
@Dao
interface DeletionDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(request: DeletionRequestEntity)

    @Query("SELECT * FROM deletion_requests WHERE ownerId = :ownerId ORDER BY requestedAt DESC LIMIT 1")
    fun observeLatest(ownerId: String): Flow<DeletionRequestEntity?>

    @Query("SELECT * FROM deletion_requests WHERE ownerId = :ownerId ORDER BY requestedAt DESC LIMIT 1")
    suspend fun latest(ownerId: String): DeletionRequestEntity?

    @Query(
        """
        SELECT * FROM deletion_requests
        WHERE ownerId = :ownerId AND state IN ('REQUESTED', 'IN_PROGRESS')
        ORDER BY requestedAt DESC LIMIT 1
        """,
    )
    suspend fun active(ownerId: String): DeletionRequestEntity?

    @Query("DELETE FROM deletion_requests WHERE ownerId = :ownerId")
    suspend fun deleteAll(ownerId: String)
}
