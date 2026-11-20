package com.pam.recap.domain.repository

import com.pam.recap.core.result.Outcome
import com.pam.recap.domain.model.GmailCredential

/**
 * FR-54 credential vault: stores the Gmail app password wrapped by an Android Keystore AES-GCM key
 * (the same mechanism as the SQLCipher passphrase). The plaintext never touches disk, a log, or the
 * network beyond the IMAP login it authorizes.
 *
 * Lifecycle: written after a successful IMAP validation, cleared on sign out, on disconnect, and on
 * workspace deletion.
 */
interface AppPasswordVault {

    suspend fun save(email: String, appPassword: String)

    suspend fun read(): GmailCredential?

    suspend fun clear()

    suspend fun hasCredential(): Boolean = read() != null
}

/**
 * FR-54/FR-06: proves an app password works with a real IMAP login **before** anything is stored.
 * A failed validation stores nothing and maps to the same [com.pam.recap.domain.model.GmailConnectionState]
 * the connection screen already understands.
 */
interface GmailCredentialValidator {
    suspend fun validate(email: String, appPassword: String): Outcome<Unit>
}
