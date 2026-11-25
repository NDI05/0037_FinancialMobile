package com.pam.recap.data.notification

import android.content.Context
import androidx.annotation.VisibleForTesting
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Default-off notification preference plus the per-account watermark (CR-010 N-01).
 *
 * Nothing personal is stored: only the boolean opt-in and transaction ids/event timestamps already
 * persisted in the owner's finance store.
 */
@Singleton
class TransactionNotificationSettings @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isEnabled(): Boolean = prefs.getBoolean(KEY_ENABLED, false)

    fun setEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun watermark(ownerId: String): TransactionNotificationWatermark? {
        val raw = prefs.getString(watermarkKey(ownerId), null) ?: return null
        val parts = raw.split(SEPARATOR)
        if (parts.size !in 3..4) return null
        val established = parts[0].toBooleanStrictOrNull() ?: return null
        val lastEventAt = parts[1].toLongOrNull() ?: return null
        val seen = if (parts[2].isBlank()) emptyList() else parts[2].split(',')
        val monthId = parts.getOrNull(3)?.takeIf { it.isNotBlank() }
        return TransactionNotificationWatermark(established, lastEventAt, seen, monthId)
    }

    fun saveWatermark(ownerId: String, watermark: TransactionNotificationWatermark) {
        val raw = buildString {
            append(watermark.established)
            append(SEPARATOR)
            append(watermark.lastEventAtEpochMs)
            append(SEPARATOR)
            append(watermark.seenIds.joinToString(","))
            append(SEPARATOR)
            append(watermark.monthId.orEmpty())
        }
        prefs.edit().putString(watermarkKey(ownerId), raw).apply()
    }

    /** Logout/account switch: no watermark survives, so the next account starts with a baseline. */
    fun clearWatermarks() {
        val editor = prefs.edit()
        prefs.all.keys
            .filter { it.startsWith(KEY_WATERMARK_PREFIX) }
            .forEach { editor.remove(it) }
        editor.apply()
    }

    @VisibleForTesting
    internal fun clearAllForTest() {
        prefs.edit().clear().apply()
    }

    private fun watermarkKey(ownerId: String): String = "$KEY_WATERMARK_PREFIX$ownerId"

    private companion object {
        const val PREFS_NAME = "pam_transaction_notifications"
        const val KEY_ENABLED = "enabled"
        const val KEY_WATERMARK_PREFIX = "watermark_"
        const val SEPARATOR = "\u001F"
    }
}
