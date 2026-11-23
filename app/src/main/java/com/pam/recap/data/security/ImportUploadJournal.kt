package com.pam.recap.data.security

import android.content.Context
import com.pam.recap.core.security.WrappedSecretStore
import com.pam.recap.domain.model.MailboxWatermark
import com.pam.recap.domain.model.PendingUploadMonth
import com.pam.recap.domain.model.PendingUploadRun
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

    // ---------------------------------------------------------------- pending run

    /**
     * The committed-but-unacknowledged run for [ownerId], if any. This record — not the newest
     * local import job — is the identity the next tick must finish, because Room's dedupe keeps the
     * rows attached to the old `importJobId`.
     */
    fun readPending(ownerId: String): PendingUploadRun? {
        val wrapped = prefs.getString(pendingKey(ownerId), null) ?: return null
        val decoded = runCatching { wrappedSecrets.unwrap(wrapped).decodeToString() }.getOrNull() ?: return null
        val parts = decoded.split(FIELD_SEP)
        if (parts.size != PENDING_FIELDS) return null
        return runCatching {
            val values = parts.map { String(java.util.Base64.getUrlDecoder().decode(it)) }
            require(values[0] == PENDING_VERSION) { "unsupported pending journal version" }
            val months = values[9].takeIf { it.isNotEmpty() }?.split(RECORD_SEP)?.map { record ->
                val fields = record.split(FIELD_SEP)
                require(fields.size == 4) { "bad pending month record" }
                val decodedFields = fields.map { String(java.util.Base64.getUrlDecoder().decode(it)) }
                PendingUploadMonth(
                    monthId = decodedFields[0],
                    fingerprints = decodedFields[3].takeIf { it.isNotEmpty() }?.split(FINGERPRINT_SEP).orEmpty(),
                    serverJobId = decodedFields[1].takeIf { it.isNotEmpty() },
                    finalState = decodedFields[2].takeIf { it.isNotEmpty() },
                )
            }?.associateBy { it.monthId }.orEmpty()
            // Defense in depth: a record copied under another owner's key must never be replayed
            // for that owner. The value is key-scoped already, so this is a consistency check.
            require(values[1] == ownerId) { "pending journal owner mismatch" }
            PendingUploadRun(
                ownerId = values[1],
                localJobId = values[2],
                primaryMonthId = values[3],
                localState = values[4],
                templateVersion = values[5],
                errorCode = values[6].takeIf { it.isNotEmpty() },
                watermark = values[7].takeIf { it.isNotEmpty() }?.let { watermark ->
                    val fields = watermark.split(':')
                    require(fields.size == 2) { "bad pending watermark" }
                    MailboxWatermark(fields[0].toLong(), fields[1].toLong())
                },
                startedAtEpochMillis = values[8].toLong(),
                months = months,
            )
        }.getOrNull()
    }

    /**
     * Persists [run]. A second, DIFFERENT unacknowledged run is never allowed to overwrite the
     * first: that would lose the only pointer to work the server has not confirmed yet. Updating
     * the same run (month acknowledgement) is always allowed.
     */
    fun savePending(run: PendingUploadRun): Boolean {
        val existing = readPending(run.ownerId)
        if (existing != null && existing.localJobId != run.localJobId && !existing.fullyAcknowledged) {
            return false
        }
        val months = run.months.values.sortedBy { it.monthId }.joinToString(RECORD_SEP) { month ->
            listOf(
                month.monthId,
                month.serverJobId.orEmpty(),
                month.finalState.orEmpty(),
                month.fingerprints.joinToString(FINGERPRINT_SEP),
            ).joinToString(FIELD_SEP) { b64(it) }
        }
        val fields = listOf(
            PENDING_VERSION,
            run.ownerId,
            run.localJobId,
            run.primaryMonthId,
            run.localState,
            run.templateVersion,
            run.errorCode.orEmpty(),
            run.watermark?.let { "${it.uidValidity}:${it.highestUid}" }.orEmpty(),
            run.startedAtEpochMillis.toString(),
            months,
        )
        val value = fields.joinToString(FIELD_SEP) { b64(it) }
        val wrapped = runCatching { wrappedSecrets.wrap(value.encodeToByteArray()) }.getOrNull() ?: return false
        return prefs.edit().putString(pendingKey(run.ownerId), wrapped).commit()
    }

    fun clearPending(ownerId: String) {
        prefs.edit().remove(pendingKey(ownerId)).commit()
    }

    private fun key(ownerId: String, localJobId: String): String {
        return "import_upload_${b64(ownerId)}_${b64(localJobId)}"
    }

    private fun pendingKey(ownerId: String): String = "pending_upload_${b64(ownerId)}"

    private fun b64(value: String): String =
        java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(value.encodeToByteArray())

    private companion object {
        const val PREFS = "pam_backend_session"
        const val FIELD_SEP = "\u001f"
        const val RECORD_SEP = "\u001e"
        const val FINGERPRINT_SEP = ","
        const val PENDING_VERSION = "1"
        const val PENDING_FIELDS = 10
    }
}
