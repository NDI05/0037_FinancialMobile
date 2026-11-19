package com.pam.recap.core.security

import android.content.Context
import android.content.SharedPreferences
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton
import androidx.core.content.edit

/**
 * Supplies the 32-byte passphrase used by SQLCipher. The passphrase is generated once,
 * wrapped by [KeystoreSecretStore] and stored as ciphertext; it is never written in clear.
 */
@Singleton
class DatabasePassphraseProvider @Inject constructor(
    private val context: Context,
    private val keystoreSecretStore: KeystoreSecretStore,
) {

    private val prefs: SharedPreferences
        get() = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun passphrase(): ByteArray {
        val stored = prefs.getString(KEY_WRAPPED_PASSPHRASE, null)
        if (stored != null) {
            return runCatching { keystoreSecretStore.unwrap(stored) }
                .getOrElse { regenerate() }
        }
        return regenerate()
    }

    private fun regenerate(): ByteArray {
        val fresh = ByteArray(PASSPHRASE_LENGTH_BYTES).also { SecureRandom().nextBytes(it) }
        prefs.edit(commit = true) {
            putString(KEY_WRAPPED_PASSPHRASE, keystoreSecretStore.wrap(fresh))
        }
        return fresh
    }

    fun destroyMaterial() {
        prefs.edit(commit = true) { remove(KEY_WRAPPED_PASSPHRASE) }
        keystoreSecretStore.clearKey()
    }

    private companion object {
        const val PREFS_NAME = "pam_secure_store"
        const val KEY_WRAPPED_PASSPHRASE = "wrapped_db_passphrase_v1"
        const val PASSPHRASE_LENGTH_BYTES = 32
    }
}
