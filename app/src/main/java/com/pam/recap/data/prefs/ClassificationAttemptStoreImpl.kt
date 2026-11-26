package com.pam.recap.data.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.pam.recap.core.di.IoDispatcher
import com.pam.recap.domain.repository.ClassificationAttemptStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Ledger in the session DataStore, owner-scoped so switching accounts cannot silence another
 * owner's queue. Encoded as `id:revision,id:revision`; ids never contain a comma and the revision is
 * the trailing integer, so no JSON parser is needed for a small, append-mostly map.
 */
@Singleton
class ClassificationAttemptStoreImpl @Inject constructor(
    private val dataStore: DataStore<Preferences>,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : ClassificationAttemptStore {

    override suspend fun reviewed(ownerId: String): Map<String, Int> =
        withContext(ioDispatcher) {
            decode(dataStore.data.first()[key(ownerId)])
        }

    override suspend fun recordReviewed(ownerId: String, attempts: Map<String, Int>) {
        if (attempts.isEmpty()) return
        withContext(ioDispatcher) {
            dataStore.edit { prefs ->
                val merged = decode(prefs[key(ownerId)]).toMutableMap()
                merged.putAll(attempts)
                // Keep the ledger bounded even after years of imports; the oldest ids are dropped
                // first and simply become eligible again, which is safe (the server dedupes jobs).
                prefs[key(ownerId)] = encode(if (merged.size > MAX_ENTRIES) {
                    merged.entries.drop(merged.size - MAX_ENTRIES).associate { it.key to it.value }
                } else {
                    merged
                })
            }
        }
    }

    private fun key(ownerId: String) = stringPreferencesKey("category_reviewed_$ownerId")

    private fun decode(raw: String?): Map<String, Int> {
        if (raw.isNullOrBlank()) return emptyMap()
        return raw.split(STORE_SEPARATOR).mapNotNull { entry ->
            val split = entry.lastIndexOf(REVISION_SEPARATOR)
            if (split <= 0) return@mapNotNull null
            val revision = entry.substring(split + 1).toIntOrNull() ?: return@mapNotNull null
            entry.substring(0, split) to revision
        }.toMap()
    }

    private fun encode(attempts: Map<String, Int>): String =
        attempts.entries.joinToString(STORE_SEPARATOR.toString()) { (id, revision) ->
            "$id$REVISION_SEPARATOR$revision"
        }

    private companion object {
        const val STORE_SEPARATOR = ','
        const val REVISION_SEPARATOR = ':'
        const val MAX_ENTRIES = 2_000
    }
}
