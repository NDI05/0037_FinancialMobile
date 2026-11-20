package com.pam.recap.domain.model

/**
 * FR-54: the only mailbox credential PAM ever holds — the user's own Gmail app password.
 *
 * It lives in memory only for the length of an IMAP login. On disk it exists solely as ciphertext
 * wrapped by the Android Keystore key, never in logs, and never sent to the backend (NFR-14).
 */
data class GmailCredential(
    val email: String,
    val appPassword: String,
)
