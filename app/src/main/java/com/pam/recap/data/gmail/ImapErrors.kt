package com.pam.recap.data.gmail

import com.pam.recap.core.result.AppError
import com.pam.recap.domain.model.GmailConnectionState
import com.sun.mail.util.MailConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.mail.AuthenticationFailedException
import javax.mail.MessagingException

/**
 * FR-07: one mapping from an IMAP failure to the six-state vocabulary the UI already speaks.
 *
 * - wrong/revoked app password ⇒ `REAUTHORIZATION_REQUIRED` (the user must paste a new password)
 * - DNS/TLS/timeout/connect ⇒ `UNAVAILABLE` (the provider, not the credential, is the problem)
 *
 * Only a technical marker is logged; the exception message may carry a server greeting, so it never
 * reaches the log (NFR-14/18).
 */
internal fun mapImapFailure(error: Throwable): AppError = when (error) {
    is AuthenticationFailedException -> AppError.PermissionRequired(REASON_AUTH)
    is MailConnectException -> AppError.Network(REASON_CONNECT)
    is SocketTimeoutException -> AppError.Network(REASON_TIMEOUT)
    is UnknownHostException -> AppError.Network(REASON_DNS)
    is MessagingException -> AppError.Network(REASON_PROTOCOL)
    else -> AppError.Network(REASON_UNKNOWN)
}

internal fun stateForFailure(error: Throwable): GmailConnectionState = when (error) {
    is AuthenticationFailedException -> GmailConnectionState.REAUTHORIZATION_REQUIRED
    else -> GmailConnectionState.UNAVAILABLE
}

/** True when retrying the same credential cannot help (NFR-05: never retry an auth failure). */
internal fun isAuthenticationFailure(error: Throwable): Boolean =
    error is AuthenticationFailedException

private const val REASON_AUTH = "imap_authentication_failed"
private const val REASON_CONNECT = "imap_connect_failed"
private const val REASON_TIMEOUT = "imap_timeout"
private const val REASON_DNS = "imap_dns"
private const val REASON_PROTOCOL = "imap_protocol_error"
private const val REASON_UNKNOWN = "imap_unknown_error"
