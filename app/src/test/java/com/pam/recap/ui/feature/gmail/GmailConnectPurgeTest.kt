package com.pam.recap.ui.feature.gmail

import com.pam.recap.core.result.AppError
import com.pam.recap.core.result.Outcome
import com.pam.recap.core.time.ClockProvider
import com.pam.recap.data.gmail.FixtureGmailSource
import com.pam.recap.data.gmail.LivinTemplateRegistry
import com.pam.recap.domain.model.Consent
import com.pam.recap.domain.model.ConsentDecision
import com.pam.recap.domain.model.ConsentPurpose
import com.pam.recap.domain.model.Session
import com.pam.recap.domain.usecase.ConnectGmailUseCase
import com.pam.recap.domain.usecase.DisconnectGmailUseCase
import com.pam.recap.testing.FakeAppPasswordVault
import com.pam.recap.testing.FakeConsentRepository
import com.pam.recap.testing.FakeCredentialValidator
import com.pam.recap.testing.FakeDemoDataMaintenance
import com.pam.recap.testing.FakeGmailConnectionRepository
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
import org.junit.Before
import org.junit.Test
import kotlin.time.Instant

/** F2: connecting a real mailbox is the moment demo framing ends, so the purge must run. */
@OptIn(ExperimentalCoroutinesApi::class)
class GmailConnectPurgeTest {

    private val now = Instant.parse("2026-03-01T02:00:00Z")
    private val clock = object : ClockProvider {
        override fun now(): Instant = now
    }
    private val ownerId = "u_owner"

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun session() = Session(
        ownerId = ownerId,
        displayName = "Andhika",
        mailboxLabel = "andhika@example.test",
        signedInAt = now,
        isDemo = false,
    )

    private fun viewModel(
        sessions: FakeSessionRepository,
        connections: FakeGmailConnectionRepository,
        consents: FakeConsentRepository,
        validator: FakeCredentialValidator,
        demoData: FakeDemoDataMaintenance,
    ): GmailConnectViewModel {
        val gmail = FixtureGmailSource(LivinTemplateRegistry())
        return GmailConnectViewModel(
            sessions = sessions,
            connections = connections,
            consents = consents,
            validator = validator,
            vault = FakeAppPasswordVault(),
            demoData = demoData,
            connectGmail = ConnectGmailUseCase(gmail, gmail, consents, connections, sessions, clock),
            disconnectGmail = DisconnectGmailUseCase(
                gmail,
                connections,
                sessions,
                FakeAppPasswordVault(),
                clock,
            ),
        )
    }

    @Test
    fun `a successful connect purges the demo rows`() = runTest {
        val sessions = FakeSessionRepository(session())
        val demoData = FakeDemoDataMaintenance()
        val viewModel = viewModel(
            sessions = sessions,
            connections = FakeGmailConnectionRepository(),
            consents = grantedConsent(),
            validator = FakeCredentialValidator(),
            demoData = demoData,
        )

        viewModel.state.first { it.consentGranted }
        viewModel.setEmail("andhika@example.test")
        viewModel.setAppPassword("abcdefghijklmnop")
        viewModel.connect()

        val settled = viewModel.state.first { !it.isBusy }
        assertEquals(null, settled.error)
        assertEquals(listOf(ownerId), demoData.purgedOwners)
    }

    @Test
    fun `a rejected credential does not purge anything`() = runTest {
        val demoData = FakeDemoDataMaintenance()
        val viewModel = viewModel(
            sessions = FakeSessionRepository(session()),
            connections = FakeGmailConnectionRepository(),
            consents = grantedConsent(),
            validator = FakeCredentialValidator(Outcome.Failure(AppError.Network("imap_connect_failed"))),
            demoData = demoData,
        )

        viewModel.state.first { it.ownerId != null }
        viewModel.setEmail("andhika@example.test")
        viewModel.setAppPassword("wrong")
        viewModel.connect()

        viewModel.state.first { !it.isBusy }
        assertEquals(emptyList<String>(), demoData.purgedOwners)
    }

    private fun grantedConsent() = FakeConsentRepository(
        listOf(
            Consent(
                id = "c1",
                ownerId = ownerId,
                purpose = ConsentPurpose.GMAIL_READ,
                noticeVersion = "v1",
                decision = ConsentDecision.GRANTED,
                decidedAt = now,
            ),
        ),
    )
}
