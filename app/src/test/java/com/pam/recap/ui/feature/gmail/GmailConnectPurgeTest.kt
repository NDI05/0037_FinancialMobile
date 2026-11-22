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
import com.pam.recap.testing.FakeAccountRepository
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
        accounts: FakeAccountRepository = FakeAccountRepository(),
        vault: FakeAppPasswordVault = FakeAppPasswordVault(),
    ): GmailConnectViewModel {
        val gmail = FixtureGmailSource(LivinTemplateRegistry())
        return GmailConnectViewModel(
            sessions = sessions,
            connections = connections,
            consents = consents,
            validator = validator,
            vault = vault,
            demoData = demoData,
            connectGmail = ConnectGmailUseCase(gmail, gmail, consents, connections, sessions, clock),
            disconnectGmail = DisconnectGmailUseCase(
                gmail,
                connections,
                sessions,
                FakeAppPasswordVault(),
                clock,
            ),
            accounts = accounts,
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
    fun `validation completing after an account switch writes nothing to either owner`() = runTest {
        val sessions = FakeSessionRepository(session())
        val vault = FakeAppPasswordVault()
        val gate = kotlinx.coroutines.CompletableDeferred<Outcome<Unit>>()
        val validator = object : com.pam.recap.domain.repository.GmailCredentialValidator {
            override suspend fun validate(email: String, appPassword: String) = gate.await()
        }
        val connections = FakeGmailConnectionRepository()
        val gmail = FixtureGmailSource(LivinTemplateRegistry())
        val vm = GmailConnectViewModel(
            sessions = sessions,
            connections = connections,
            consents = grantedConsent(),
            validator = validator,
            vault = vault,
            demoData = FakeDemoDataMaintenance(),
            connectGmail = ConnectGmailUseCase(gmail, gmail, grantedConsent(), connections, sessions, clock),
            disconnectGmail = DisconnectGmailUseCase(gmail, connections, sessions, vault, clock),
            accounts = FakeAccountRepository(),
        )
        vm.state.first { it.consentGranted }
        vm.setEmail("a@example.test")
        vm.setAppPassword("abcdefghijklmnop")
        vm.connect()
        sessions.signIn(session().copy(ownerId = "u_other", displayName = "Other"))
        gate.complete(Outcome.Success(Unit))
        val settled = vm.state.first { !it.isBusy }
        assertEquals(emptyList<Any>(), vault.saved)
        assertEquals(null, settled.error)
        assertEquals(null, connections.get("u_other"))
    }

    @Test
    fun `the offline demo mailbox authorizes the fixture without a credential`() = runTest {
        val sessions = FakeSessionRepository(session())
        val demoData = FakeDemoDataMaintenance()
        val viewModel = viewModel(
            sessions = sessions,
            connections = FakeGmailConnectionRepository(),
            consents = grantedConsent(),
            validator = FakeCredentialValidator(),
            demoData = demoData,
            accounts = FakeAccountRepository(isBackendEnabled = false),
        )

        viewModel.state.first { it.demoMailboxAvailable }
        viewModel.connectDemoMailbox()

        val settled = viewModel.state.first { !it.isBusy && it.connection != null }
        assertEquals(null, settled.error)
        assertEquals("livin.demo@pam.local", settled.connection?.mailboxLabel)
        // The demo rows are purged so the following import is what populates the month.
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
