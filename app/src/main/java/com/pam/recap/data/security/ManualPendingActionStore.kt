package com.pam.recap.data.security

import android.content.Context
import androidx.core.content.edit
import com.pam.recap.core.security.WrappedSecretStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Private, owner-scoped recovery metadata for one in-flight manual action. */
@Singleton
class ManualPendingActionStore @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val wrappedSecrets: WrappedSecretStore,
) {
    data class Pending(
        val key: String,
        val identity: String,
        val amountText: String,
        val description: String,
        val dateIso: String,
        val direction: String,
        val categoryCode: String,
    )

    private val prefs get() = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun save(ownerId: String, pending: Pending) {
        val encoded = listOf(
            pending.key, pending.identity, pending.amountText, pending.description,
            pending.dateIso, pending.direction, pending.categoryCode,
        ).joinToString("\u001f")
        val wrapped = runCatching { wrappedSecrets.wrap(encoded.encodeToByteArray()) }.getOrNull() ?: return
        prefs.edit(commit = true) { putString(key(ownerId), wrapped) }
    }

    fun read(ownerId: String): Pending? {
        val wrapped = prefs.getString(key(ownerId), null) ?: return null
        val decoded = runCatching { wrappedSecrets.unwrap(wrapped).decodeToString() }.getOrNull() ?: return null
        val parts = decoded.split("\u001f")
        if (parts.size != 7) return null
        return Pending(parts[0], parts[1], parts[2], parts[3], parts[4], parts[5], parts[6])
    }

    fun clear(ownerId: String) {
        prefs.edit(commit = true) { remove(key(ownerId)) }
    }

    private fun key(ownerId: String) = "manual_pending_$ownerId"

    private companion object {
        const val PREFS_NAME = "pam_backend_session"
    }
}
