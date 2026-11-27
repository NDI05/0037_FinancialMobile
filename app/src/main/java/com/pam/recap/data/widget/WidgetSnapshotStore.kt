package com.pam.recap.data.widget

import android.content.Context
import androidx.annotation.VisibleForTesting
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Private snapshot storage shared by every widget instance for the same bank/period (CR-010).
 *
 * SharedPreferences is intentionally used instead of Room/DataStore: the launcher asks for the
 * snapshot from a non-UI process path where a synchronous, tiny read is the cheapest correct
 * option, and the values are aggregates only — no transaction rows, amounts or identities.
 */
@Singleton
class WidgetSnapshotStore @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun read(): WidgetSnapshot? {
        val ownerId = prefs.getString(KEY_OWNER, null) ?: return null
        val monthId = prefs.getString(KEY_MONTH, null) ?: return null
        val status = prefs.getString(KEY_STATUS, null)
            ?.let { runCatching { WidgetSnapshotStatus.valueOf(it) }.getOrNull() }
            ?: WidgetSnapshotStatus.LOADING
        val count = if (prefs.contains(KEY_COUNT)) prefs.getInt(KEY_COUNT, 0) else null
        val updatedAt = if (prefs.contains(KEY_UPDATED_AT)) {
            prefs.getLong(KEY_UPDATED_AT, 0L)
        } else {
            null
        }
        return WidgetSnapshot(
            ownerId = ownerId,
            bank = WidgetBank.fromId(prefs.getString(KEY_BANK, null)),
            monthId = monthId,
            transactionCount = count,
            status = status,
            updatedAtEpochMs = updatedAt,
            confirmed = prefs.getBoolean(KEY_CONFIRMED, false),
        )
    }

    fun save(snapshot: WidgetSnapshot) {
        prefs.edit()
            .putString(KEY_OWNER, snapshot.ownerId)
            .putString(KEY_BANK, snapshot.bank.id)
            .putString(KEY_MONTH, snapshot.monthId)
            .putString(KEY_STATUS, snapshot.status.name)
            .putBoolean(KEY_CONFIRMED, snapshot.confirmed)
            .apply {
                val count = snapshot.transactionCount
                if (count == null) remove(KEY_COUNT) else putInt(KEY_COUNT, count)
                val updatedAt = snapshot.updatedAtEpochMs
                if (updatedAt == null) remove(KEY_UPDATED_AT) else putLong(KEY_UPDATED_AT, updatedAt)
            }
            .apply()
    }

    /** Clears the aggregate only; per-instance bank choices survive logout/month changes. */
    fun clear() {
        prefs.edit()
            .remove(KEY_OWNER)
            .remove(KEY_BANK)
            .remove(KEY_MONTH)
            .remove(KEY_COUNT)
            .remove(KEY_STATUS)
            .remove(KEY_CONFIRMED)
            .remove(KEY_UPDATED_AT)
            .apply()
    }

    /**
     * Per-instance bank choice. The current release stores only Mandiri, but the mapping is kept
     * separate so two widgets can later hold different banks without touching the snapshot schema.
     */
    fun saveWidgetBank(appWidgetId: Int, bank: WidgetBank) {
        prefs.edit().putString(widgetBankKey(appWidgetId), bank.id).apply()
    }

    fun widgetBank(appWidgetId: Int): WidgetBank =
        WidgetBank.fromId(prefs.getString(widgetBankKey(appWidgetId), null))

    @VisibleForTesting
    internal fun clearAllForTest() {
        prefs.edit().clear().apply()
    }

    private fun widgetBankKey(appWidgetId: Int): String = "widget_bank_$appWidgetId"

    private companion object {
        const val PREFS_NAME = "pam_widget_snapshot"
        const val KEY_OWNER = "owner_id"
        const val KEY_BANK = "bank"
        const val KEY_MONTH = "month_id"
        const val KEY_COUNT = "transaction_count"
        const val KEY_STATUS = "status"
        const val KEY_CONFIRMED = "confirmed"
        const val KEY_UPDATED_AT = "updated_at"
    }
}
