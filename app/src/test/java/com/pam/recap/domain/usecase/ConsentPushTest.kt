package com.pam.recap.domain.usecase

import com.pam.recap.core.result.Outcome
import com.pam.recap.core.time.JakartaMonth
import com.pam.recap.domain.model.ConsentDecision
import com.pam.recap.domain.model.ConsentPurpose
import com.pam.recap.domain.repository.BackendConsentSync
import com.pam.recap.testing.FakeAnalysisRepository
import com.pam.recap.testing.FakeConsentRepository
import com.pam.recap.testing.SequentialIdGenerator
import com.pam.recap.testing.fixedClock
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Instant

/**
 * E5.5 / FR-03: the local ledger is the source of truth. A backend push that throws, hangs or
 * rejects must not change or fail the decision the user just made.
 */
class ConsentPushTest {

    private val owner = "u_demo"
    private val now = Instant.parse("2026-03-01T02:00:00Z")
    private val consents = FakeConsentRepository()
    private val analyses = FakeAnalysisRepository()

    private fun useCase(sync: BackendConsentSync) = RecordConsentUseCase(
        consents = consents,
        analyses = analyses,
        clock = fixedClock(now),
        idGenerator = SequentialIdGenerator("consent"),
        backendConsent = sync,
    )

    @Test
    fun `a throwing backend push does not fail the local consent`() = runTest {
        val exploding = object : BackendConsentSync {
            override suspend fun record(
                ownerId: String,
                purpose: ConsentPurpose,
                decision: ConsentDecision,
                noticeVersion: String,
            ): Unit = throw IllegalStateException("backend down")
        }

        val result = useCase(exploding)(
            ownerId = owner,
            purpose = ConsentPurpose.AI_PROCESSING,
            noticeVersion = "v1",
            decision = ConsentDecision.GRANTED,
        )

        assertTrue(result is Outcome.Success)
        assertEquals(1, consents.ledger.value.size)
        assertEquals(ConsentDecision.GRANTED, consents.ledger.value.single().decision)
    }

    @Test
    fun `the pushed copy carries the purpose, decision and notice version`() = runTest {
        var recorded: Triple<ConsentPurpose, ConsentDecision, String>? = null
        val recording = object : BackendConsentSync {
            override suspend fun record(
                ownerId: String,
                purpose: ConsentPurpose,
                decision: ConsentDecision,
                noticeVersion: String,
            ) {
                recorded = Triple(purpose, decision, noticeVersion)
            }
        }

        useCase(recording)(
            ownerId = owner,
            purpose = ConsentPurpose.GMAIL_READ,
            noticeVersion = "v2",
            decision = ConsentDecision.DECLINED,
        )

        assertEquals(
            Triple(ConsentPurpose.GMAIL_READ, ConsentDecision.DECLINED, "v2"),
            recorded,
        )
        assertEquals(1, consents.ledger.value.size)
    }

    @Test
    fun `withdrawing ai consent still records locally and pushes the withdrawal`() = runTest {
        var pushed: ConsentDecision? = null
        val recording = object : BackendConsentSync {
            override suspend fun record(
                ownerId: String,
                purpose: ConsentPurpose,
                decision: ConsentDecision,
                noticeVersion: String,
            ) {
                pushed = decision
            }
        }

        val result = useCase(recording)(
            ownerId = owner,
            purpose = ConsentPurpose.AI_PROCESSING,
            noticeVersion = "v1",
            decision = ConsentDecision.WITHDRAWN,
        )

        assertTrue(result is Outcome.Success)
        assertEquals(ConsentDecision.WITHDRAWN, pushed)
        assertEquals(ConsentDecision.WITHDRAWN, consents.ledger.value.single().decision)
    }

    @Test
    fun `the gmail consent does not touch the month being analysed`() = runTest {
        useCase(NoopSync)(
            ownerId = owner,
            purpose = ConsentPurpose.GMAIL_READ,
            noticeVersion = "v1",
            decision = ConsentDecision.GRANTED,
        )
        // Only the AI withdrawal re-classifies stored analyses (FR-04).
        assertTrue(analyses.saved.isEmpty())
        assertEquals(JakartaMonth(2026, 2).id(), "2026-02")
    }

    private object NoopSync : BackendConsentSync {
        override suspend fun record(
            ownerId: String,
            purpose: ConsentPurpose,
            decision: ConsentDecision,
            noticeVersion: String,
        ) = Unit
    }
}
