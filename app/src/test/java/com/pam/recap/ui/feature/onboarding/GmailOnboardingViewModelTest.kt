package com.pam.recap.ui.feature.onboarding

import com.pam.recap.core.result.Outcome
import com.pam.recap.domain.model.GmailAuthorization
import com.pam.recap.domain.model.GmailLinkStatus
import com.pam.recap.domain.model.GmailSyncRequest
import com.pam.recap.domain.model.GmailSyncState
import com.pam.recap.domain.model.Session
import com.pam.recap.domain.repository.GmailLinkRepository
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * One-time Gmail onboarding offer: visible only when a session exists and Gmail is not connected,
 * "Lewati" is remembered per owner (so another owner on the same device still sees it), and Gmail
 * being unlinked or disabled never blocks the app.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GmailOnboardingViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private val now = Instant.parse("2026-11-24T07:00:00Z")

    private lateinit var sessions: FakeSessionRepository
    private lateinit var gmail: FakeOnboardingGmailRepository

    private fun session(ownerId: String) = Session(
        ownerId = ownerId,
        displayName = "Andhika",
        mailboxLabel = "andhika@example.com",
        signedInAt = now,
        isDemo = false,
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        sessions = FakeSessionRepository()
        gmail = FakeOnboardingGmailRepository()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `offer is visible when a session exists and gmail is not connected`() =
        runTest(dispatcher) {
            sessions.signIn(session("u_owner"))
            val vm = GmailOnboardingViewModel(sessions, gmail)
            advanceUntilIdle()

            assertTrue(vm.state.value.visible)
            assertTrue(gmail.statusCalls >= 1)
        }

    @Test
    fun `offer is hidden when gmail is already connected`() = runTest(dispatcher) {
        gmail.statusResult = Outcome.Success(
            GmailLinkStatus(
                connected = true,
                accountEmailMasked = "a***@gmail.com",
                syncState = GmailSyncState.IDLE,
                connectedAt = now,
                lastSyncAt = now,
                watchExpiresAt = now,
            ),
        )
        sessions.signIn(session("u_owner"))
        val vm = GmailOnboardingViewModel(sessions, gmail)
        advanceUntilIdle()

        assertFalse(vm.state.value.visible)
    }

    @Test
    fun `offer is hidden when the server has gmail disabled`() = runTest(dispatcher) {
        gmail.enabled = false
        sessions.signIn(session("u_owner"))
        val vm = GmailOnboardingViewModel(sessions, gmail)
        advanceUntilIdle()

        assertFalse(vm.state.value.visible)
    }

    @Test
    fun `offer is hidden before any session exists`() = runTest(dispatcher) {
        val vm = GmailOnboardingViewModel(sessions, gmail)
        advanceUntilIdle()

        assertFalse(vm.state.value.visible)
        assertNull(vm.state.value.ownerId)
    }

    @Test
    fun `skip is remembered per owner and does not reappear`() = runTest(dispatcher) {
        sessions.signIn(session("u_owner"))
        val vm = GmailOnboardingViewModel(sessions, gmail)
        advanceUntilIdle()
        assertTrue(vm.state.value.visible)

        vm.skip()
        advanceUntilIdle()

        assertFalse(vm.state.value.visible)
        assertTrue(sessions.skippedGmailOwners.contains("u_owner"))

        // Recreating the ViewModel (next launch) must keep it hidden for that owner.
        val again = GmailOnboardingViewModel(sessions, gmail)
        advanceUntilIdle()
        assertFalse(again.state.value.visible)
    }

    @Test
    fun `another owner on the same device still sees the offer`() = runTest(dispatcher) {
        sessions.signIn(session("u_first"))
        GmailOnboardingViewModel(sessions, gmail).skip()
        advanceUntilIdle()

        sessions.signOut()
        sessions.signIn(session("u_second"))
        val vm = GmailOnboardingViewModel(sessions, gmail)
        advanceUntilIdle()

        assertTrue(vm.state.value.visible)
        assertTrue(sessions.skippedGmailOwners.contains("u_first"))
    }

    @Test
    fun `dismissing without an explicit skip keeps the offer for next launch`() =
        runTest(dispatcher) {
            sessions.signIn(session("u_owner"))
            val vm = GmailOnboardingViewModel(sessions, gmail)
            advanceUntilIdle()

            vm.dismissForNow()
            assertFalse(vm.state.value.visible)
            assertTrue(sessions.skippedGmailOwners.isEmpty())

            val again = GmailOnboardingViewModel(sessions, gmail)
            advanceUntilIdle()
            assertTrue(again.state.value.visible)
        }
}

/** Minimal Gmail link double for the onboarding gate. */
private class FakeOnboardingGmailRepository : GmailLinkRepository {
    var enabled: Boolean = true
    var statusCalls: Int = 0
    var statusResult: Outcome<GmailLinkStatus> =
        Outcome.Success(GmailLinkStatus.NotConnected)

    override val isEnabled: Boolean get() = enabled

    override suspend fun status(ownerId: String): Outcome<GmailLinkStatus> {
        statusCalls++
        return statusResult
    }

    override suspend fun startConnect(
        ownerId: String,
        reconnect: Boolean,
    ): Outcome<GmailAuthorization> = Outcome.Failure(
        com.pam.recap.core.result.AppError.Configuration("not_used"),
    )

    override suspend fun disconnect(ownerId: String): Outcome<Unit> =
        Outcome.Success(Unit)

    override suspend fun requestSync(ownerId: String): Outcome<GmailSyncRequest> =
        Outcome.Failure(com.pam.recap.core.result.AppError.Configuration("not_used"))
}
