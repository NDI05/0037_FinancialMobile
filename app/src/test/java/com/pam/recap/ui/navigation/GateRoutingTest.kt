package com.pam.recap.ui.navigation

import android.content.Context
import androidx.navigation.NavHostController
import androidx.navigation.compose.ComposeNavigator
import androidx.navigation.compose.composable
import androidx.navigation.createGraph
import androidx.test.core.app.ApplicationProvider
import com.pam.recap.domain.model.Session
import com.pam.recap.ui.feature.account.AccountMode
import com.pam.recap.ui.session.GateState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.time.Instant

/**
 * FR-01/FR-03 gate routing (D.3). Regression for the onboarding bug: a profile created on the
 * Account screen leaves the gate in `NeedsConsent`, and the old branch only redirected from
 * Welcome/Overview, so the user stayed on Account until they pressed Back.
 *
 * These tests use the real Navigation Compose destinations, not string stand-ins, because
 * `hasRoute` is what the production predicate relies on.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GateRoutingTest {

    private val session = Session(
        ownerId = "u_owner",
        displayName = "Andhika",
        mailboxLabel = "andhika@example.test",
        signedInAt = Instant.parse("2026-03-01T02:00:00Z"),
        isDemo = false,
    )
    private val consentGate = GateState.NeedsConsent(session)
    private val readyGate = GateState.Ready(session)

    private fun controller(): NavHostController {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return NavHostController(context).apply {
            navigatorProvider.addNavigator(ComposeNavigator())
            graph = createGraph(startDestination = PamDestination.Welcome) {
                composable<PamDestination.Welcome> { }
                composable<PamDestination.Account> { }
                composable<PamDestination.Consent> { }
                composable<PamDestination.Overview> { }
                composable<PamDestination.Settings> { }
                composable<PamDestination.Transactions> { }
            }
        }
    }

    private fun NavHostController.at(destination: PamDestination): NavHostController = apply {
        navigate(destination)
    }

    private fun NavHostController.current(): androidx.navigation.NavDestination? =
        currentBackStackEntry?.destination

    @Test
    fun `a new account without a decision goes straight to consent`() {
        val nav = controller().at(PamDestination.Account(AccountMode.REGISTER))

        assertEquals(PamDestination.Consent, gateRedirect(consentGate, nav.current()))
    }

    @Test
    fun `consent does not redirect to itself`() {
        val nav = controller().at(PamDestination.Consent)

        assertNull(gateRedirect(consentGate, nav.current()))
    }

    @Test
    fun `a profile with a saved decision opens the overview`() {
        val nav = controller().at(PamDestination.Account(AccountMode.SIGN_IN))

        assertEquals(PamDestination.Overview(), gateRedirect(readyGate, nav.current()))
    }

    @Test
    fun `a saved decision leaves a working screen alone`() {
        val nav = controller().at(PamDestination.Settings)

        assertNull(gateRedirect(readyGate, nav.current()))
    }

    @Test
    fun `a saved decision can still reopen consent to change it`() {
        // The settings/Gmail/analysis surfaces link to Consent. Once a decision exists the gate
        // must not bounce the user back to Overview, or Gmail could never be granted again.
        val nav = controller().at(PamDestination.Consent)

        assertNull(gateRedirect(readyGate, nav.current()))
    }

    @Test
    fun `an ended session never keeps a protected screen`() {
        val nav = controller().at(PamDestination.Settings)

        assertEquals(PamDestination.Welcome, gateRedirect(GateState.NeedsSignIn, nav.current()))
    }

    @Test
    fun `an ended session keeps the account screen reachable`() {
        val nav = controller().at(PamDestination.Account(AccountMode.REGISTER))

        assertNull(gateRedirect(GateState.NeedsSignIn, nav.current()))
    }

    @Test
    fun `replacing the gate screen leaves nothing behind to go back to`() {
        val nav = controller()
            .at(PamDestination.Welcome)
            .at(PamDestination.Account(AccountMode.REGISTER))

        nav.replaceWith(PamDestination.Consent)

        assertEquals(PamDestination.Consent, nav.current()?.route?.let { route ->
            if (route.contains("Consent")) PamDestination.Consent else null
        })
        // Nothing under the gate screen: Back cannot restore Account or any other protected page.
        assertNull(nav.previousBackStackEntry)
        assertEquals(false, nav.popBackStack())
    }
}
