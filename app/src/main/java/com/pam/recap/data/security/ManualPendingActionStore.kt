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

    fun save(ownerId: String, pending: Pending): Boolean {
        val encoded = listOf(
            pending.key, pending.identity, pending.amountText, pending.description,
            pending.dateIso, pending.direction, pending.categoryCode,
        ).joinToString("\u001f") { java.util.Base64.getUrlEncoder().encodeToString(it.encodeToByteArray()) }
        val wrapped = runCatching { wrappedSecrets.wrap(encoded.encodeToByteArray()) }.getOrNull()
            ?: return false
        val editor = prefs.edit().putString(key(ownerId), wrapped)
        return editor.commit()
    }

    fun read(ownerId: String): Pending? {
        val wrapped = prefs.getString(key(ownerId), null) ?: return null
        val decoded = runCatching { wrappedSecrets.unwrap(wrapped).decodeToString() }.getOrNull() ?: return null
        val parts = decoded.split("\u001f")
        if (parts.size != 7) return null
        return runCatching {
            val values = parts.map { String(java.util.Base64.getUrlDecoder().decode(it)) }
            Pending(values[0], values[1], values[2], values[3], values[4], values[5], values[6])
        }.getOrNull()
    }

    fun clear(ownerId: String) {
        prefs.edit(commit = true) { remove(key(ownerId)) }
    }

    private fun key(ownerId: String) = "manual_pending_$ownerId"

    private companion object {
        const val PREFS_NAME = "pam_backend_session"
    }
}
