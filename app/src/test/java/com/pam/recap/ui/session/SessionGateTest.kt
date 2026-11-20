package com.pam.recap.ui.session

import com.pam.recap.core.time.ClockProvider
import com.pam.recap.domain.model.Consent
import com.pam.recap.domain.model.ConsentDecision
import com.pam.recap.domain.model.ConsentPurpose
import com.pam.recap.domain.model.Session
import com.pam.recap.testing.FakeConsentRepository
import com.pam.recap.testing.FakeSessionRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.time.Instant

/**
 * FR-01/FR-03 gate: no profile → Welcome; a profile without a recorded Gmail decision → Consent;
 * otherwise the recap. The account screen is reachable in the first state because that is where a
 * profile is created.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionGateTest {

    private val now = Instant.parse("2026-03-01T02:00:00Z")
    private val clock = object : ClockProvider {
        override fun now(): Instant = now
    }
    private val session = Session(
        ownerId = "u_owner",
        displayName = "Andhika",
        mailboxLabel = "andhika@example.test",
        signedInAt = now,
        isDemo = true,
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `no profile lands on welcome`() = runTest {
        val viewModel = SessionGateViewModel(FakeSessionRepository(null), FakeConsentRepository())
        assertEquals(GateState.NeedsSignIn, viewModel.state.first { it != GateState.Loading })
    }

    @Test
    fun `a profile without a gmail decision lands on consent`() = runTest {
        val viewModel = SessionGateViewModel(FakeSessionRepository(session), FakeConsentRepository())
        val state = viewModel.state.first { it != GateState.Loading }
        assertTrue(state is GateState.NeedsConsent)
    }

    @Test
    fun `a recorded gmail decision opens the recap`() = runTest {
        val consents = FakeConsentRepository(
            listOf(
                Consent(
                    id = "c1",
                    ownerId = session.ownerId,
                    purpose = ConsentPurpose.GMAIL_READ,
                    noticeVersion = "v1",
                    decision = ConsentDecision.GRANTED,
                    decidedAt = now,
                ),
            ),
        )
        val viewModel = SessionGateViewModel(FakeSessionRepository(session), consents)
        val state = viewModel.state.first { it != GateState.Loading }
        assertTrue(state is GateState.Ready)
    }

    @Test
    fun `signing in moves the gate from needs-sign-in to consent`() = runTest {
        val sessions = FakeSessionRepository(null)
        val viewModel = SessionGateViewModel(sessions, FakeConsentRepository())
        assertEquals(GateState.NeedsSignIn, viewModel.state.first { it != GateState.Loading })

        sessions.signIn(session)

        val next = viewModel.state.first { it is GateState.NeedsConsent }
        assertTrue(next is GateState.NeedsConsent)
    }
}
