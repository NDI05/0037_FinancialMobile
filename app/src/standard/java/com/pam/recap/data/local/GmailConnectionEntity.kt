package com.pam.recap.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.pam.recap.domain.model.GmailConnectionState
import kotlin.time.Instant

/**
 * §4 `GmailConnectionEntity` (FR-49). There is deliberately **no** token column: tokens live in
 * Credential Manager / the Google account, never in PAM's database (FR-08, NFR-14).
 */
@Entity(tableName = "gmail_connection")
data class GmailConnectionEntity(
    @PrimaryKey val ownerId: String,
    val state: GmailConnectionState,
    val mailboxLabel: String?,
    val scope: String,
    val connectedAt: Instant?,
    val lastImportAt: Instant?,
    val lastAiAt: Instant?,
    val updatedAt: Instant,
)
