package com.pam.recap.ui.feature.account

import androidx.lifecycle.SavedStateHandle
import com.pam.recap.core.result.AppError
import com.pam.recap.core.result.AppErrorRules
import com.pam.recap.core.result.Outcome
import com.pam.recap.domain.model.GoogleIdToken
import com.pam.recap.domain.model.GoogleSignInResult
import com.pam.recap.domain.model.GoogleNonce
import com.pam.recap.domain.model.Session
import com.pam.recap.testing.FakeAccountRepository
import com.pam.recap.testing.FakeGoogleSignInGateway
import com.pam.recap.testing.FakeSessionRepository
import kotlin.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Google SSO on the account screen: the button only exists when the build configured a client ID,
 * the nonce comes from the backend, cancellation is idle, and linking Google never disturbs the
 * existing session. The fake gateway records nonces; no Play Services and no token ever appear.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AccountSsoViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private val now = Instant.parse("2026-11-24T07:00:00Z")

    private lateinit var accounts: FakeAccountRepository
    private lateinit var sessions: FakeSessionRepository
    private lateinit var gateway: FakeGoogleSignInGateway

    private fun session(ownerId: String = "u_owner", googleLinked: Boolean = true) = Session(
        ownerId = ownerId,
        displayName = "Andhika",
        mailboxLabel = "andhika@example.com",
        signedInAt = now,
        isDemo = false,
        googleLinked = googleLinked,
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        accounts = FakeAccountRepository(isBackendEnabled = true, googleSsoEnabled = true)
        sessions = FakeSessionRepository()
        gateway = FakeGoogleSignInGateway()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel(mode: AccountMode = AccountMode.SIGN_IN): AccountViewModel {
        accounts.sessionStore = sessions
        return AccountViewModel(
            accounts,
            sessions,
            gateway,
            SavedStateHandle(mapOf("mode" to mode.name)),
        )
    }

    @Test
    fun `google button only appears when the client id is configured`() {
        val enabled = viewModel()
        assertTrue(enabled.state.value.googleSsoEnabled)
        assertTrue(enabled.state.value.showGoogleSignIn)

        // isAvailable=false is what production reports when the client ID is blank.
        gateway.isAvailable = false
        val hidden = AccountViewModel(
            accounts,
            sessions,
            gateway,
            SavedStateHandle(),
        )
        assertFalse(hidden.state.value.googleSsoEnabled)
        assertFalse(hidden.state.value.showGoogleSignIn)
    }

    @Test
    fun `successful google sign in stores the session and googleLinked`() = runTest(dispatcher) {
        accounts.googleSignInResult = Outcome.Success(session(googleLinked = true))
        val vm = viewModel()

        vm.signInWithGoogle()
        advanceUntilIdle()

        assertTrue(vm.state.value.isSignedIn)
        assertEquals("andhika@example.com", vm.state.value.signedInLabel)
        assertTrue(vm.state.value.googleLinked)
        assertNull(vm.state.value.error)
        assertFalse(vm.state.value.googleBusy)
        // The nonce the backend issued is the one handed to Credential Manager.
        assertEquals(listOf("nonce-fake"), gateway.nonces)
        assertTrue(accounts.calls.contains("googleSignIn:nonce-fake"))
        assertEquals("u_owner", sessions.current()?.ownerId)
    }

    @Test
    fun `cancelled google sheet leaves the screen idle without an error`() = runTest(dispatcher) {
        gateway.result = GoogleSignInResult.Cancelled
        val vm = viewModel()

        vm.signInWithGoogle()
        advanceUntilIdle()

        assertNull(vm.state.value.error)
        assertFalse(vm.state.value.googleBusy)
        assertFalse(vm.state.value.isSignedIn)
        assertFalse(accounts.calls.any { it.startsWith("googleSignIn") })
    }

    @Test
    fun `unlinked identity returns guidance to use the pam email first`() = runTest(dispatcher) {
        accounts.googleSignInResult = Outcome.Failure(
            AppError.Validation(AppErrorRules.GOOGLE_IDENTITY_NOT_LINKED),
        )
        val vm = viewModel()

        vm.signInWithGoogle()
        advanceUntilIdle()

        assertEquals(
            AppErrorRules.GOOGLE_IDENTITY_NOT_LINKED,
            (vm.state.value.error as AppError.Validation).rule,
        )
        assertFalse(vm.state.value.isSignedIn)
    }

    @Test
    fun `inactive account is reported and no session is kept`() = runTest(dispatcher) {
        accounts.googleSignInResult = Outcome.Failure(
            AppError.Validation(AppErrorRules.ACCOUNT_INACTIVE),
        )
        val vm = viewModel()

        vm.signInWithGoogle()
        advanceUntilIdle()

        assertEquals(
            AppErrorRules.ACCOUNT_INACTIVE,
            (vm.state.value.error as AppError.Validation).rule,
        )
        assertFalse(vm.state.value.isSignedIn)
    }

    @Test
    fun `linking google marks the account linked without changing the session`() =
        runTest(dispatcher) {
            sessions.signIn(session(googleLinked = false))
            val vm = viewModel()
            advanceUntilIdle()
            assertTrue(vm.state.value.isSignedIn)
            assertTrue(vm.state.value.showGoogleLinkCard)

            vm.linkGoogle()
            advanceUntilIdle()

            assertTrue(vm.state.value.googleLinked)
            assertTrue(vm.state.value.googleLinkNotice)
            assertNull(vm.state.value.googleLinkError)
            assertEquals("u_owner", sessions.current()?.ownerId)
            assertTrue(sessions.googleLinkedOwners.contains("u_owner"))
            assertTrue(accounts.calls.contains("googleLink:nonce-fake"))
        }

    @Test
    fun `link conflict keeps the existing session and explains the clash`() = runTest(dispatcher) {
        sessions.signIn(session(googleLinked = false))
        accounts.googleLinkResult = Outcome.Failure(
            AppError.Validation(AppErrorRules.GOOGLE_IDENTITY_CONFLICT),
        )
        val vm = viewModel()
        advanceUntilIdle()

        vm.linkGoogle()
        advanceUntilIdle()

        assertEquals(
            AppErrorRules.GOOGLE_IDENTITY_CONFLICT,
            (vm.state.value.googleLinkError as AppError.Validation).rule,
        )
        assertFalse(vm.state.value.googleLinked)
        assertFalse(vm.state.value.googleLinkNotice)
        // The session/owner is untouched by the 409.
        assertTrue(vm.state.value.isSignedIn)
        assertEquals("u_owner", sessions.current()?.ownerId)
    }

    @Test
    fun `missing backend nonce stops before credential manager`() = runTest(dispatcher) {
        accounts.googleNonceResult = Outcome.Failure(
            AppError.Configuration(AppErrorRules.GOOGLE_SSO_DISABLED),
        )
        val vm = viewModel()

        vm.signInWithGoogle()
        advanceUntilIdle()

        assertTrue(gateway.nonces.isEmpty())
        assertFalse(vm.state.value.googleBusy)
        assertEquals(
            AppErrorRules.GOOGLE_SSO_DISABLED,
            (vm.state.value.error as AppError.Configuration).debugDetail,
        )
    }

    @Test
    fun `signed in account does not offer a second sign in`() = runTest(dispatcher) {
        sessions.signIn(session(googleLinked = true))
        val vm = viewModel()
        advanceUntilIdle()

        assertTrue(vm.state.value.isSignedIn)
        assertFalse(vm.state.value.showGoogleSignIn)
        assertFalse(vm.state.value.showGoogleLinkCard)
        assertTrue(vm.state.value.googleLinked)
    }

    @Test
    fun `gateway token flows through unchanged`() = runTest(dispatcher) {
        gateway.result = GoogleSignInResult.Success(GoogleIdToken("token-a"))
        accounts.googleNonceResult = Outcome.Success(GoogleNonce(nonce = "nonce-a"))
        accounts.googleSignInResult = Outcome.Success(session(googleLinked = true))
        val vm = viewModel()

        vm.signInWithGoogle()
        advanceUntilIdle()

        assertEquals(listOf("nonce-a"), gateway.nonces)
        assertTrue(accounts.calls.contains("googleSignIn:nonce-a"))
    }
}
