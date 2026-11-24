package com.pam.recap.ui.feature.gmail

import com.pam.recap.core.result.AppError
import com.pam.recap.core.result.Outcome
import com.pam.recap.domain.model.GmailAuthorization
import com.pam.recap.domain.model.GmailLinkResult
import com.pam.recap.domain.model.GmailLinkStatus
import com.pam.recap.domain.model.GmailSyncState
import com.pam.recap.domain.model.Session
import com.pam.recap.domain.repository.GmailLinkRepository
import com.pam.recap.testing.FakeSessionRepository
import kotlin.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Gmail OAuth link ViewModel: distinct loading/error/unlinked states, start (with reconnect when
 * already linked), deep-link success/error handling, and confirmation-gated disconnect.
 *
 * Nothing here touches Google: the fake records calls and the assertions never see a token.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GmailLinkViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private lateinit var gmail: FakeGmailLinkRepository
    private lateinit var sessions: FakeSessionRepository

    private val connectedStatus = GmailLinkStatus(
        connected = true,
        accountEmailMasked = "a***@gmail.com",
        syncState = GmailSyncState.IDLE,
        connectedAt = Instant.parse("2026-11-24T07:00:00Z"),
        lastSyncAt = Instant.parse("2026-11-24T07:15:00Z"),
        watchExpiresAt = Instant.parse("2026-11-30T07:00:00Z"),
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        gmail = FakeGmailLinkRepository()
        sessions = FakeSessionRepository(
            Session("own_gmail_ui", "PM UI", null, Instant.parse("2026-11-24T00:00:00Z"), false),
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel() = GmailLinkViewModel(sessions, gmail)

    @Test
    fun `not linked shows the unlinked status without pretending the backend failed`() {
        val vm = viewModel()
        val state = vm.state.value
        assertTrue(state.enabled)
        assertFalse(state.loading)
        assertNull(state.error)
        assertEquals(GmailLinkStatus.NotConnected, state.status)
    }

    @Test
    fun `linked status exposes only the masked address`() {
        gmail.statusResult = Outcome.Success(connectedStatus)
        val state = viewModel().state.value
        assertEquals(connectedStatus, state.status)
        assertEquals("a***@gmail.com", state.status?.accountEmailMasked)
    }

    @Test
    fun `status failure stays distinct from the unlinked empty state`() {
        gmail.statusResult = Outcome.Failure(AppError.Network("backend_unreachable"))
        val state = viewModel().state.value
        assertNull(state.status)
        assertFalse(state.loading)
        assertEquals(AppError.Network("backend_unreachable"), state.error)
    }

    @Test
    fun `start calls the API and exposes the authorization url to open`() {
        val vm = viewModel()
        vm.connect()
        assertEquals(1, gmail.startCalls)
        assertEquals(listOf(false), gmail.reconnectFlags)
        assertEquals("https://accounts.google.com/o/oauth2/v2/auth?state=x", vm.state.value.authorizationUrl)
        assertFalse(vm.state.value.busy)
        assertNull(vm.state.value.error)
    }

    @Test
    fun `start on an existing link asks the server to reconnect`() {
        gmail.statusResult = Outcome.Success(connectedStatus)
        val vm = viewModel()
        vm.connect()
        assertEquals(listOf(true), gmail.reconnectFlags)
    }

    @Test
    fun `start failure shows an error and no url`() {
        gmail.startResult = Outcome.Failure(AppError.Network("backend_unreachable"))
        val vm = viewModel()
        vm.connect()
        assertNull(vm.state.value.authorizationUrl)
        assertEquals(AppError.Network("backend_unreachable"), vm.state.value.error)
    }

    @Test
    fun `deep link success is shown and refreshes the status`() {
        val vm = viewModel()
        val readsBefore = gmail.statusCalls
        gmail.statusResult = Outcome.Success(connectedStatus)
        vm.onDeepLink(GmailLinkResult(success = true))
        assertEquals(GmailLinkResult(success = true), vm.state.value.linkResult)
        assertTrue(gmail.statusCalls > readsBefore)
        assertEquals(connectedStatus, vm.state.value.status)
        vm.dismissLinkResult()
        assertNull(vm.state.value.linkResult)
    }

    @Test
    fun `deep link error keeps the safe reason for the banner`() {
        val vm = viewModel()
        vm.onDeepLink(GmailLinkResult.fromWire("error", "state_expired"))
        assertEquals(GmailLinkResult(success = false, reason = "state_expired"), vm.state.value.linkResult)
        assertFalse(vm.state.value.linkResult!!.success)
    }

    @Test
    fun `disconnect needs confirmation before it calls the server`() {
        gmail.statusResult = Outcome.Success(connectedStatus)
        val vm = viewModel()
        vm.requestDisconnect()
        assertTrue(vm.state.value.disconnectRequested)
        assertEquals(0, gmail.disconnectCalls)

        vm.confirmDisconnect()
        assertEquals(1, gmail.disconnectCalls)
        assertFalse(vm.state.value.disconnectRequested)
        assertEquals(GmailLinkStatus.NotConnected, vm.state.value.status)
    }

    @Test
    fun `dismissing the dialog leaves the link untouched`() {
        gmail.statusResult = Outcome.Success(connectedStatus)
        val vm = viewModel()
        vm.requestDisconnect()
        vm.dismissDisconnect()
        assertFalse(vm.state.value.disconnectRequested)
        assertEquals(0, gmail.disconnectCalls)
        assertEquals(connectedStatus, vm.state.value.status)
    }

    @Test
    fun `disconnect failure keeps the linked state and shows the error`() {
        gmail.statusResult = Outcome.Success(connectedStatus)
        gmail.disconnectResult = Outcome.Failure(AppError.Network("backend_unreachable"))
        val vm = viewModel()
        vm.confirmDisconnect()
        assertEquals(connectedStatus, vm.state.value.status)
        assertEquals(AppError.Network("backend_unreachable"), vm.state.value.error)
    }

    @Test
    fun `no session never calls the API`() {
        sessions = FakeSessionRepository(null)
        val vm = GmailLinkViewModel(sessions, gmail)
        assertFalse(vm.state.value.signedIn)
        assertFalse(vm.state.value.loading)
        assertEquals(0, gmail.statusCalls)
    }

    @Test
    fun `a build without a backend disables the action honestly`() {
        gmail = FakeGmailLinkRepository(isEnabled = false)
        val vm = GmailLinkViewModel(sessions, gmail)
        assertFalse(vm.state.value.enabled)
        vm.connect()
        assertNull(vm.state.value.authorizationUrl)
        assertEquals(0, gmail.startCalls)
    }
}

private class FakeGmailLinkRepository(
    override val isEnabled: Boolean = true,
    var statusResult: Outcome<GmailLinkStatus> = Outcome.Success(GmailLinkStatus.NotConnected),
    var startResult: Outcome<GmailAuthorization> = Outcome.Success(
        GmailAuthorization("https://accounts.google.com/o/oauth2/v2/auth?state=x"),
    ),
    var disconnectResult: Outcome<Unit> = Outcome.Success(Unit),
) : GmailLinkRepository {

    var statusCalls: Int = 0
    var startCalls: Int = 0
    var disconnectCalls: Int = 0
    val reconnectFlags = mutableListOf<Boolean>()

    override suspend fun status(ownerId: String): Outcome<GmailLinkStatus> {
        statusCalls++
        return statusResult
    }

    override suspend fun startConnect(
        ownerId: String,
        reconnect: Boolean,
    ): Outcome<GmailAuthorization> {
        startCalls++
        reconnectFlags += reconnect
        return startResult
    }

    override suspend fun disconnect(ownerId: String): Outcome<Unit> {
        disconnectCalls++
        return disconnectResult
    }
}
