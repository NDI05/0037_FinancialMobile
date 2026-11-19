package com.pam.recap.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Internal key/value bookkeeping used for schema-side metadata and migration markers.
 * Not user facing.
 */
@Entity(tableName = "app_metadata")
data class AppMetadataEntity(
    @PrimaryKey val key: String,
    val value: String,
    val updatedAtEpochMillis: Long,
)
