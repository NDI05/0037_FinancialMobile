package com.pam.recap.data.gmail

import com.pam.recap.core.result.AppError
import com.pam.recap.core.result.Outcome
import com.pam.recap.core.time.JakartaMonth
import com.pam.recap.data.backend.BackendEndpoints
import com.pam.recap.data.gmail.LivinQueryBuilder
import com.pam.recap.testing.FakeAppPasswordVault
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F1 residual gap: the bundled fixture mailbox is demo data. A server-backed account without an app
 * password must not be able to "connect" it and import sample emails into a real database.
 */
class RoutingGmailSourceTest {

    private val imapSource: ImapGmailSource by lazy {
        ImapGmailSource(vault = FakeAppPasswordVault(), ioDispatcher = kotlinx.coroutines.Dispatchers.Unconfined)
    }

    private fun router(backendEnabled: Boolean, withCredential: Boolean = false): RoutingGmailSource {
        val vault = FakeAppPasswordVault(
            if (withCredential) com.pam.recap.domain.model.GmailCredential("a@b.test", "k") else null,
        )
        val endpoints = object : BackendEndpoints {
            override val isEnabled: Boolean = backendEnabled
            override val analysesUrl: String? = null
            override fun endpoint(path: String): String? = null
        }
        val registry = LivinTemplateRegistry()
        return RoutingGmailSource(
            vault = vault,
            imap = imapSource,
            fixture = FixtureGmailSource(registry),
            backend = endpoints,
        )
    }

    @Test
    fun `a server account without a credential has no mailbox at all`() = runTest {
        val router = router(backendEnabled = true)

        val authorization = router.authorize()

        assertTrue(authorization is Outcome.Failure)
        val error = (authorization as Outcome.Failure).error
        assertTrue(error is AppError.Configuration)
        assertEquals(null, router.mailboxLabel())

        val query = query()
        val search = router.search(query, null)
        assertTrue(search is Outcome.Failure)
    }

    @Test
    fun `the offline demo still reads the fixture mailbox`() = runTest {
        val router = router(backendEnabled = false)

        assertTrue(router.authorize() is Outcome.Success)

        val query = query()
        val page = router.search(query, null)
        assertTrue(page is Outcome.Success)
        assertTrue((page as Outcome.Success).value.messages.isNotEmpty())
    }

    private fun query() = LivinQueryBuilder(LivinTemplateRegistry()).build(
        fromInclusive = JakartaMonth(2026, 2).range().startInclusive,
        toExclusive = JakartaMonth(2026, 2).range().endExclusive,
        templateVersion = null,
    )
}
