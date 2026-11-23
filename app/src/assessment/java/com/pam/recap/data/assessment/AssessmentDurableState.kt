package com.pam.recap.data.assessment

import android.content.Context
import com.pam.recap.core.security.WrappedSecretStore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton

/**
 * S4.4 durable, Keystore-wrapped key/value store for the assessment variant.
 *
 * The assessment build has no Room/SQLCipher database, but two pieces of state must survive a
 * process restart for the UI to behave like the standard build:
 * - the consent ledger, so [com.pam.recap.ui.session.SessionGateViewModel] does not ask for the
 *   same Gmail consent again on every launch (FR-03/FR-49),
 * - the mailbox connection facts (state/scope/timestamps only, never tokens) and the import-job
 *   metadata, so the settings/import screens do not look "reset" after a restart.
 *
 * Values are JSON strings wrapped by [WrappedSecretStore] before they reach SharedPreferences; no
 * plaintext finance row, consent history or credential is written to disk. This is a small
 * owner-scoped store, not a database: reads that the UI serves from the server never depend on it.
 */
@Singleton
class AssessmentDurableState @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val wrappedSecrets: WrappedSecretStore,
) {

    private val prefs get() = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Reads one wrapped value; [ownerId] null reads a process-global slot (e.g. a job index). */
    @Synchronized
    fun read(ownerId: String?, name: String): String? {
        val wrapped = prefs.getString(key(ownerId, name), null) ?: return null
        return runCatching { wrappedSecrets.unwrap(wrapped).decodeToString() }.getOrNull()
    }

    /**
     * Writes one wrapped value and returns the *durable* outcome: `false` means the commit to
     * SharedPreferences failed and the value must not be treated as persisted.
     */
    @Synchronized
    fun write(ownerId: String?, name: String, value: String): Boolean {
        val wrapped = wrappedSecrets.wrap(value.encodeToByteArray())
        return prefs.edit().putString(key(ownerId, name), wrapped).commit()
    }

    /** Removes every owner-scoped value, keeping only the global job index (used on sign-out). */
    @Synchronized
    fun clear(ownerId: String) {
        val prefix = ownerPrefix(ownerId)
        prefs.edit().apply {
            prefs.all.keys.filter { it.startsWith(prefix) }.forEach { remove(it) }
        }.commit()
    }

    private fun key(ownerId: String?, name: String): String =
        if (ownerId == null) "global|$name" else ownerPrefix(ownerId) + name

    private fun ownerPrefix(ownerId: String): String =
        "owner|${Base64.getUrlEncoder().withoutPadding().encodeToString(ownerId.encodeToByteArray())}|"

    private companion object {
        const val PREFS = "pam_assessment_state"
    }
}
