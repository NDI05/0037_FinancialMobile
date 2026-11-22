package com.pam.recap.domain.repository

import com.pam.recap.core.result.Outcome
import com.pam.recap.domain.model.GmailCredential

/**
 * FR-54 credential vault: stores the Gmail app password wrapped by an Android Keystore AES-GCM key
 * (the same mechanism as the SQLCipher passphrase). The plaintext never touches disk, a log, or the
 * network beyond the IMAP login it authorizes.
 *
 * Lifecycle (CR-003): written after a successful IMAP validation, preserved on ordinary PAM sign
 * out, cleared only on explicit Gmail disconnect/withdrawal or owner/workspace deletion. Entries are
 * scoped to the authenticated PAM owner; the legacy global v1 slot is never auto-bound.
 */
interface AppPasswordVault {

    /** @return true only after a checked, durable write. */
    suspend fun save(ownerId: String, email: String, appPassword: String): Boolean

    suspend fun read(ownerId: String): GmailCredential?

    suspend fun clear(ownerId: String)

    suspend fun hasCredential(ownerId: String): Boolean = read(ownerId) != null
}

/**
 * FR-54/FR-06: proves an app password works with a real IMAP login **before** anything is stored.
 * A failed validation stores nothing and maps to the same [com.pam.recap.domain.model.GmailConnectionState]
 * the connection screen already understands.
 */
interface GmailCredentialValidator {
    suspend fun validate(email: String, appPassword: String): Outcome<Unit>
}
