package com.pam.recap.domain.model

import kotlin.time.Instant

/**
 * FR-07: the six mailbox states the UI must distinguish. `UNAVAILABLE` is a provider outage,
 * `REAUTHORIZATION_REQUIRED` asks the user to reconnect, and the two are never conflated with
 * "no data".
 */
enum class GmailConnectionState {
    NOT_CONNECTED,
    AUTHORIZATION_PENDING,
    CONNECTED,
    REAUTHORIZATION_REQUIRED,
    UNAVAILABLE,
    DISCONNECTED,
    ;

    val grantsAccess: Boolean get() = this == CONNECTED

    val needsUserAction: Boolean
        get() = this == NOT_CONNECTED || this == REAUTHORIZATION_REQUIRED || this == DISCONNECTED
}

/**
 * FR-49 mailbox connection facts. Only the scope label is stored — never a token, refresh token
 * or Google account password (FR-08, NFR-14).
 */
data class GmailConnection(
    val ownerId: String,
    val state: GmailConnectionState,
    val mailboxLabel: String?,
    val scope: String,
    val connectedAt: Instant?,
    val lastImportAt: Instant?,
    val lastAiAt: Instant?,
    val updatedAt: Instant,
) {
    companion object {
        /** The only scope PAM ever requests (FR-08). */
        const val READONLY_SCOPE: String = "https://www.googleapis.com/auth/gmail.readonly"

        fun disconnected(ownerId: String, at: Instant): GmailConnection = GmailConnection(
            ownerId = ownerId,
            state = GmailConnectionState.NOT_CONNECTED,
            mailboxLabel = null,
            scope = READONLY_SCOPE,
            connectedAt = null,
            lastImportAt = null,
            lastAiAt = null,
            updatedAt = at,
        )
    }
}
