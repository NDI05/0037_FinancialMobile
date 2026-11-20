package com.pam.recap.data.gmail

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.pam.recap.core.result.SafeLogger
import com.pam.recap.core.security.WrappedSecretStore
import com.pam.recap.domain.model.GmailCredential
import com.pam.recap.domain.repository.AppPasswordVault
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * FR-54: the app password is written to SharedPreferences only after being wrapped by
 * [WrappedSecretStore] (Keystore AES-GCM). There is no code path that writes it in clear, and the
 * stored blob is a JSON document inside the ciphertext, so even the email is unreadable at rest.
 */
@Singleton
class AppPasswordVaultImpl @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val secretStore: WrappedSecretStore,
    private val json: Json,
) : AppPasswordVault {

    private val prefs: SharedPreferences
        get() = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override suspend fun save(email: String, appPassword: String) {
        val payload = json.encodeToString(
            StoredCredential.serializer(),
            StoredCredential(email = email.trim(), appPassword = appPassword.trim()),
        )
        val wrapped = runCatching { secretStore.wrap(payload.encodeToByteArray()) }
            .getOrElse {
                SafeLogger.failure(TAG, "credential_wrap_failed")
                return
            }
        prefs.edit(commit = true) { putString(KEY_CREDENTIAL, wrapped) }
        SafeLogger.event(TAG, "credential_stored")
    }

    override suspend fun read(): GmailCredential? {
        val wrapped = prefs.getString(KEY_CREDENTIAL, null) ?: return null
        val stored = runCatching {
            val plain = secretStore.unwrap(wrapped).decodeToString()
            json.decodeFromString(StoredCredential.serializer(), plain)
        }.getOrElse {
            // A blob from another key (reinstall, cleared keystore) is dropped, never trusted.
            clear()
            null
        } ?: return null
        if (stored.appPassword.isEmpty()) return null
        return GmailCredential(email = stored.email, appPassword = stored.appPassword)
    }

    override suspend fun clear() {
        prefs.edit(commit = true) { remove(KEY_CREDENTIAL) }
    }

    @Serializable
    private data class StoredCredential(
        val email: String,
        val appPassword: String,
    )

    private companion object {
        const val TAG = "AppPasswordVault"
        const val PREFS_NAME = "pam_app_password_vault"
        const val KEY_CREDENTIAL = "wrapped_app_password_v1"
    }
}
