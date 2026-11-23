package com.pam.recap.data.security

import android.content.Context
import com.pam.recap.core.security.WrappedSecretStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Durable identity for the server import jobs of ONE local run.
 *
 * A delta run can produce rows in more than one transaction month (a late email arrives this month
 * but its transaction date is last month). The server needs one import job per month, so the journal
 * keeps a month → serverJobId map instead of a single id. A retry reuses those ids and the same
 * deterministic batch keys, which is what makes "crash between two acknowledged months" recoverable
 * without re-creating jobs or re-sending different bytes.
 */
@Singleton
class ImportUploadJournal @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val wrappedSecrets: WrappedSecretStore,
) {
    data class MonthJob(val monthId: String, val serverJobId: String, val finalState: String)

    data class Entry(val ownerId: String, val localJobId: String, val months: Map<String, MonthJob>)

    private val prefs get() = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun save(entry: Entry): Boolean {
        val records = entry.months.values.sortedBy { it.monthId }.joinToString(RECORD_SEP) { month ->
            listOf(month.monthId, month.serverJobId, month.finalState).joinToString(FIELD_SEP) {
                java.util.Base64.getUrlEncoder().encodeToString(it.encodeToByteArray())
            }
        }
        val value = listOf(entry.ownerId, entry.localJobId, records).joinToString(FIELD_SEP) {
            java.util.Base64.getUrlEncoder().encodeToString(it.encodeToByteArray())
        }
        val wrapped = runCatching { wrappedSecrets.wrap(value.encodeToByteArray()) }.getOrNull() ?: return false
        return prefs.edit().putString(key(entry.ownerId, entry.localJobId), wrapped).commit()
    }

    fun read(ownerId: String, localJobId: String): Entry? {
        val wrapped = prefs.getString(key(ownerId, localJobId), null) ?: return null
        val decoded = runCatching { wrappedSecrets.unwrap(wrapped).decodeToString() }.getOrNull() ?: return null
        val parts = decoded.split(FIELD_SEP)
        if (parts.size != 3) return null
        return runCatching {
            val values = parts.map { String(java.util.Base64.getUrlDecoder().decode(it)) }
            val months = values[2].takeIf { it.isNotEmpty() }?.split(RECORD_SEP)?.map { record ->
                val fields = record.split(FIELD_SEP)
                require(fields.size == 3) { "bad journal month record" }
                val decodedFields = fields.map { String(java.util.Base64.getUrlDecoder().decode(it)) }
                MonthJob(decodedFields[0], decodedFields[1], decodedFields[2])
            }?.associateBy { it.monthId }.orEmpty()
            Entry(values[0], values[1], months)
        }.getOrNull()
    }

    fun clear(ownerId: String, localJobId: String) {
        prefs.edit().remove(key(ownerId, localJobId)).commit()
    }

    private fun key(ownerId: String, localJobId: String): String {
        fun b64(value: String) = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(value.encodeToByteArray())
        return "import_upload_${b64(ownerId)}_${b64(localJobId)}"
    }

    private companion object {
        const val PREFS = "pam_backend_session"
        const val FIELD_SEP = "\u001f"
        const val RECORD_SEP = "\u001e"
    }
}
