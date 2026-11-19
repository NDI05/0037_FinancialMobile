package com.pam.recap.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore

/**
 * Single DataStore instance for session-scoped preferences. The delegate is top-level by contract
 * (Android creates one store per name per process).
 */
val Context.pamSessionDataStore: DataStore<Preferences> by preferencesDataStore(name = "pam_session")

/**
 * Keys of the session store. Financial data never lives here: DataStore holds only identity and
 * the demo-seed marker, while the recap itself is in the encrypted Room database.
 */
internal object SessionKeys {
    val OWNER_ID = stringPreferencesKey("session_owner_id")
    val DISPLAY_NAME = stringPreferencesKey("session_display_name")
    val MAILBOX_LABEL = stringPreferencesKey("session_mailbox_label")
    val SIGNED_IN_AT = longPreferencesKey("session_signed_in_at")
    val IS_DEMO = booleanPreferencesKey("session_is_demo")

    fun demoSeeded(ownerId: String): Preferences.Key<Boolean> =
        booleanPreferencesKey("demo_seeded_$ownerId")
}
