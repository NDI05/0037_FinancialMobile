package com.pam.recap.data.local

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface AppMetadataDao {

    @Query("SELECT value FROM app_metadata WHERE key = :key")
    suspend fun value(key: String): String?

    @Query("SELECT * FROM app_metadata")
    fun observeAll(): Flow<List<AppMetadataEntity>>

    @Upsert
    suspend fun upsert(entity: AppMetadataEntity)

    /** Used by sign-out/deletion to drop bookkeeping keys (e.g. month revisions). */
    @Query("DELETE FROM app_metadata WHERE key LIKE :prefix || '%'")
    suspend fun deleteByPrefix(prefix: String)
}
