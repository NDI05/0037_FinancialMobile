package com.pam.recap.data.security

import android.content.Context
import com.pam.recap.core.security.WrappedSecretStore
import com.pam.recap.domain.model.MailboxWatermark
import com.pam.recap.domain.model.SyncCursor
import com.pam.recap.domain.repository.SyncCursorStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Instant

/**
 * Wrapped-preferences implementation of [SyncCursorStore].
 *
 * The record holds a folder generation and one integer high-water mark. No message id, subject,
 * address or amount is written here, so a stolen file reveals nothing about which emails were
 * already imported beyond a mailbox-wide counter.
 */
@Singleton
class SyncCursorStoreImpl @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val wrappedSecrets: WrappedSecretStore,
) : SyncCursorStore {

    private val prefs get() = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    override fun read(ownerId: String): SyncCursor? {
        val wrapped = prefs.getString(key(ownerId), null) ?: return null
        val decoded = runCatching { wrappedSecrets.unwrap(wrapped).decodeToString() }.getOrNull() ?: return null
        val parts = decoded.split(FIELD)
        if (parts.size != FIELD_COUNT) return null
        return runCatching {
            SyncCursor(
                ownerId = ownerId,
                mailboxKey = parts[0].ifBlank { null },
                watermark = parseWatermark(parts[1]),
                lastAttemptAt = parseInstant(parts[2]),
                lastSuccessAt = parseInstant(parts[3]),
                lastErrorCode = parts[4].ifBlank { null },
                consecutiveFailures = parts[5].toIntOrNull() ?: 0,
                uploadPending = parts[6] == "1",
            )
        }.getOrNull()
    }

    override fun save(cursor: SyncCursor): Boolean {
        val value = listOf(
            cursor.mailboxKey.orEmpty(),
            cursor.watermark?.let { "${it.uidValidity}:${it.highestUid}" }.orEmpty(),
            cursor.lastAttemptAt?.toEpochMilliseconds()?.toString().orEmpty(),
            cursor.lastSuccessAt?.toEpochMilliseconds()?.toString().orEmpty(),
            cursor.lastErrorCode.orEmpty(),
            cursor.consecutiveFailures.toString(),
            if (cursor.uploadPending) "1" else "0",
        ).joinToString(FIELD)
        val wrapped = runCatching { wrappedSecrets.wrap(value.encodeToByteArray()) }.getOrNull() ?: return false
        return prefs.edit().putString(key(cursor.ownerId), wrapped).commit()
    }

    override fun clear(ownerId: String) {
        prefs.edit().remove(key(ownerId)).commit()
    }

    private fun key(ownerId: String) = "sync_cursor_$ownerId"

    private fun parseWatermark(raw: String): MailboxWatermark? {
        if (raw.isBlank()) return null
        val parts = raw.split(':')
        if (parts.size != 2) return null
        val validity = parts[0].toLongOrNull() ?: return null
        val uid = parts[1].toLongOrNull() ?: return null
        return MailboxWatermark(validity, uid)
    }

    private fun parseInstant(raw: String): Instant? =
        raw.toLongOrNull()?.let { Instant.fromEpochMilliseconds(it) }

    private companion object {
        const val PREFS = "pam_backend_session"
        const val FIELD = "\u001f"
        const val FIELD_COUNT = 7
    }
}
