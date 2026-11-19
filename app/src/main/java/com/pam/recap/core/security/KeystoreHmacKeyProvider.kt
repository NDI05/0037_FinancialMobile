package com.pam.recap.core.security

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Android [HmacKeyProvider]: one random 32-byte master secret, wrapped by the Android Keystore
 * and persisted as ciphertext, from which a stable per-user key is derived.
 *
 * The master secret never leaves the device and is never written in clear; a per-user key means
 * the same notification in two accounts yields different fingerprints (FR-09, NFR-16).
 */
@Singleton
class KeystoreHmacKeyProvider @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val keystoreSecretStore: KeystoreSecretStore,
) : HmacKeyProvider {

    private val prefs: SharedPreferences
        get() = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun keyFor(ownerId: String): ByteArray {
        val master = masterSecret()
        val mac = Mac.getInstance(DERIVATION_ALGORITHM)
        mac.init(SecretKeySpec(master, DERIVATION_ALGORITHM))
        return mac.doFinal(ownerId.toByteArray(Charsets.UTF_8))
    }

    private fun masterSecret(): ByteArray {
        val stored = prefs.getString(KEY_WRAPPED_MASTER, null)
        if (stored != null) {
            runCatching { return keystoreSecretStore.unwrap(stored) }
        }
        val fresh = ByteArray(MASTER_SECRET_BYTES).also { SecureRandom().nextBytes(it) }
        prefs.edit(commit = true) {
            putString(KEY_WRAPPED_MASTER, keystoreSecretStore.wrap(fresh))
        }
        return fresh
    }

    fun destroyMaterial() {
        prefs.edit(commit = true) { remove(KEY_WRAPPED_MASTER) }
    }

    private companion object {
        const val PREFS_NAME = "pam_secure_store"
        const val KEY_WRAPPED_MASTER = "wrapped_fingerprint_master_v1"
        const val DERIVATION_ALGORITHM = "HmacSHA256"
        const val MASTER_SECRET_BYTES = 32
    }
}
