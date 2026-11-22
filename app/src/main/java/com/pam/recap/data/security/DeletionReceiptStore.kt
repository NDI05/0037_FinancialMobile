package com.pam.recap.data.security

import android.content.Context
import androidx.core.content.edit
import com.pam.recap.core.security.WrappedSecretStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The deletion receipt is a bearer status token. It is wrapped by the same Keystore-backed store as
 * the session, keyed per owner, so logout and app restart do not destroy the ability to read server
 * status while no financial access is granted.
 */
@Singleton
class DeletionReceiptStore @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val wrappedSecrets: WrappedSecretStore,
) {
    private val prefs get() = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun save(ownerId: String, receipt: String) {
        val wrapped = runCatching {
            wrappedSecrets.wrap(receipt.encodeToByteArray()).also {
                prefs.edit(commit = true) { putString(key(ownerId), it) }
            }
        }.isSuccess
        if (!wrapped) clear(ownerId)
    }

    fun read(ownerId: String): String? {
        val wrapped = prefs.getString(key(ownerId), null) ?: return null
        return runCatching { wrappedSecrets.unwrap(wrapped).decodeToString() }.getOrNull()
    }

    fun clear(ownerId: String) {
        prefs.edit(commit = true) { remove(key(ownerId)) }
    }

    private fun key(ownerId: String) = "deletion_receipt_$ownerId"

    private companion object {
        const val PREFS_NAME = "pam_backend_session"
    }
}
