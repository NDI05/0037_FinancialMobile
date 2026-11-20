package com.pam.recap.data.gmail

import com.pam.recap.core.result.AppError
import com.pam.recap.core.result.ErrorKind
import com.pam.recap.domain.model.GmailConnectionState
import com.sun.mail.util.MailConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.mail.AuthenticationFailedException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** G4/FR-07: the three failure families map to the state the UI already distinguishes. */
class ImapErrorsTest {

    @Test
    fun `an authentication failure asks for reauthorization and is never retried`() {
        val error = AuthenticationFailedException("invalid credentials")

        assertEquals(GmailConnectionState.REAUTHORIZATION_REQUIRED, stateForFailure(error))
        assertEquals(ErrorKind.PERMISSION_REQUIRED, mapImapFailure(error).kind)
        assertTrue(isAuthenticationFailure(error))
    }

    @Test
    fun `a connect failure is unavailable`() {
        val error = MailConnectException(
            com.sun.mail.util.SocketConnectException(
                "imap.gmail.com",
                java.net.ConnectException("refused"),
                "imaps",
                993,
                15_000,
            ),
        )

        assertEquals(GmailConnectionState.UNAVAILABLE, stateForFailure(error))
        assertEquals(ErrorKind.NETWORK, mapImapFailure(error).kind)
        assertTrue(!isAuthenticationFailure(error))
    }

    @Test
    fun `a timeout and a DNS failure are unavailable`() {
        for (error in listOf(SocketTimeoutException("timeout"), UnknownHostException("imap.gmail.com"))) {
            assertEquals(GmailConnectionState.UNAVAILABLE, stateForFailure(error))
            assertEquals(ErrorKind.NETWORK, mapImapFailure(error).kind)
        }
    }

    @Test
    fun `no detail leaks a server message`() {
        val mapped = mapImapFailure(AuthenticationFailedException("user=andhika@example.test"))
        assertTrue(!mapped.debugDetail.orEmpty().contains("andhika"))
    }
}
