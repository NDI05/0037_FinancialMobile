package com.pam.recap.ui.feature.consent

import com.pam.recap.core.time.ClockProvider
import com.pam.recap.core.util.IdGenerator
import com.pam.recap.domain.model.ConsentDecision
import com.pam.recap.domain.model.ConsentPurpose
import com.pam.recap.domain.model.Session
import com.pam.recap.domain.usecase.RecordConsentUseCase
import com.pam.recap.testing.FakeAnalysisRepository
import com.pam.recap.testing.FakeConsentRepository
import com.pam.recap.testing.FakeSessionRepository
import com.pam.recap.testing.NoopBackendConsentSync
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
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
import kotlin.time.Instant

/**
 * FR-03 consent decisions. The onboarding review found that an off-looking switch was not a
 * decision, so the manual path now has an explicit decline that records DECLINED for Gmail without
 * silently granting AI.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConsentViewModelTest {

    private val now = Instant.parse("2026-03-01T02:00:00Z")
    private val session = Session(
        ownerId = "u_owner",
        displayName = "Andhika",
        mailboxLabel = "andhika@example.test",
        signedInAt = now,
        isDemo = false,
    )
    private val consents = FakeConsentRepository()

    private fun viewModel(): ConsentViewModel {
        var next = 0
        val record = RecordConsentUseCase(
            consents = consents,
            analyses = FakeAnalysisRepository(),
            clock = object : ClockProvider {
                override fun now(): Instant = now
            },
            idGenerator = IdGenerator { "consent-${next++}" },
            backendConsent = NoopBackendConsentSync(),
        )
        return ConsentViewModel(FakeSessionRepository(session), record)
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `an untouched switch is not a decision and cannot continue`() {
        val state = viewModel().state.value

        assertNull(state.gmailGranted)
        assertFalse(state.canContinue)
    }

    @Test
    fun `continuing without gmail records a declined decision and finishes`() = runTest {
        val viewModel = viewModel()

        viewModel.continueWithoutGmail()

        val finished = viewModel.state.first { it.finished }
        assertEquals(false, finished.gmailGranted)
        val ledger = consents.observeLedger(session.ownerId).first()
        assertEquals(
            ConsentDecision.DECLINED,
            ledger.first { it.purpose == ConsentPurpose.GMAIL_READ }.decision,
        )
        // The manual path must not grant AI on the user's behalf.
        assertEquals(
            ConsentDecision.DECLINED,
            ledger.first { it.purpose == ConsentPurpose.AI_PROCESSING }.decision,
        )
    }

    @Test
    fun `granting both switches records two independent grants`() = runTest {
        val viewModel = viewModel()

        viewModel.decideGmail(true)
        viewModel.decideAi(true)
        viewModel.submit()

        val ledger = consents.observeLedger(session.ownerId).first()
        assertTrue(ledger.all { it.decision == ConsentDecision.GRANTED })
        assertEquals(2, ledger.map { it.purpose }.toSet().size)
    }
}
