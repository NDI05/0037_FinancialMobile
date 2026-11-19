package com.pam.recap.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [AppMetadataEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class PamDatabase : RoomDatabase() {
    abstract fun appMetadataDao(): AppMetadataDao

    companion object {
        const val DATABASE_NAME = "pam.db"
    }
}
