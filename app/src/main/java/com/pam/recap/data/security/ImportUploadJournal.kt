package com.pam.recap.data.security

import android.content.Context
import com.pam.recap.core.security.WrappedSecretStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Durable identity for a server import job, so a retry does not create a new job blindly. */
@Singleton
class ImportUploadJournal @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val wrappedSecrets: WrappedSecretStore,
) {
    data class Entry(
        val ownerId: String,
        val localJobId: String,
        val serverJobId: String,
        val finalState: String,
    )

    private val prefs get() = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun save(entry: Entry): Boolean {
        val value = listOf(entry.ownerId, entry.localJobId, entry.serverJobId, entry.finalState)
            .joinToString("\u001f") { java.util.Base64.getUrlEncoder().encodeToString(it.encodeToByteArray()) }
        val wrapped = runCatching { wrappedSecrets.wrap(value.encodeToByteArray()) }.getOrNull() ?: return false
        return prefs.edit().putString(key(entry.ownerId), wrapped).commit()
    }

    fun read(ownerId: String): Entry? {
        val wrapped = prefs.getString(key(ownerId), null) ?: return null
        val decoded = runCatching { wrappedSecrets.unwrap(wrapped).decodeToString() }.getOrNull() ?: return null
        val parts = decoded.split("\u001f")
        if (parts.size != 4) return null
        return runCatching {
            val values = parts.map { String(java.util.Base64.getUrlDecoder().decode(it)) }
            Entry(values[0], values[1], values[2], values[3])
        }.getOrNull()
    }

    fun clear(ownerId: String) {
        prefs.edit().remove(key(ownerId)).commit()
    }

    private fun key(ownerId: String) = "import_upload_$ownerId"

    private companion object { const val PREFS = "pam_backend_session" }
}
