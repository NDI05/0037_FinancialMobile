package com.pam.recap.core.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Wraps a randomly generated database passphrase with an AES-256-GCM key that never
 * leaves the Android Keystore (NFR-16).
 *
 * The wrapped passphrase is the only value persisted; the unwrapped bytes exist in memory
 * for the lifetime of the process only.
 */
/**
 * Wraps a short secret with a key that never leaves the Keystore. The interface exists so the
 * backend session cache can be exercised on the JVM with an in-memory double; production always
 * binds [KeystoreSecretStore].
 */
interface WrappedSecretStore {
    fun wrap(plainSecret: ByteArray): String

    fun unwrap(wrapped: String): ByteArray
}

class KeystoreSecretStore(
    private val keyAlias: String = DEFAULT_KEY_ALIAS,
) : WrappedSecretStore {

    private val keyStore: KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private fun obtainKey(): SecretKey {
        (keyStore.getEntry(keyAlias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                keyAlias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_SIZE_BITS)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    override fun wrap(plainSecret: ByteArray): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, obtainKey())
        val cipherText = cipher.doFinal(plainSecret)
        val iv = cipher.iv
        val combined = ByteArray(1 + iv.size + cipherText.size).also { out ->
            out[0] = iv.size.toByte()
            iv.copyInto(out, 1)
            cipherText.copyInto(out, 1 + iv.size)
        }
        return Base64.encodeToString(combined, Base64.NO_WRAP)
    }

    override fun unwrap(wrapped: String): ByteArray {
        val combined = Base64.decode(wrapped, Base64.NO_WRAP)
        val ivSize = combined[0].toInt()
        val iv = combined.copyOfRange(1, 1 + ivSize)
        val cipherText = combined.copyOfRange(1 + ivSize, combined.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, obtainKey(), GCMParameterSpec(TAG_BITS, iv))
        return cipher.doFinal(cipherText)
    }

    fun clearKey() {
        runCatching { keyStore.deleteEntry(keyAlias) }
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val DEFAULT_KEY_ALIAS = "pam_database_wrap_key_v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val KEY_SIZE_BITS = 256
        const val TAG_BITS = 128
    }
}
