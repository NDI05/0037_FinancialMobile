package com.pam.recap.domain.usecase

import com.pam.recap.core.money.Money
import com.pam.recap.core.result.AppError
import com.pam.recap.core.result.AppErrorRules
import com.pam.recap.core.result.Outcome
import com.pam.recap.core.time.JakartaMonth
import com.pam.recap.data.ai.AiPayloadBuilder
import com.pam.recap.domain.model.AiAggregatePayload
import com.pam.recap.domain.model.AnalysisStatus
import com.pam.recap.domain.model.Category
import com.pam.recap.domain.model.CategoryOrigin
import com.pam.recap.domain.model.Consent
import com.pam.recap.domain.model.ConsentDecision
import com.pam.recap.domain.model.ConsentPurpose
import com.pam.recap.domain.model.Direction
import com.pam.recap.domain.model.MetricKeys
import com.pam.recap.domain.model.ParseStatus
import com.pam.recap.domain.model.RawAnalysis
import com.pam.recap.domain.model.RawObservation
import com.pam.recap.domain.model.Transaction
import com.pam.recap.domain.model.TransactionSource
import com.pam.recap.domain.repository.AnalysisEngine
import com.pam.recap.domain.repository.AnalysisEngineConfiguration
import com.pam.recap.domain.repository.AnalysisRequest
import com.pam.recap.testing.FakeAnalysisRepository
import com.pam.recap.testing.FakeConsentRepository
import com.pam.recap.testing.FakeGmailConnectionRepository
import com.pam.recap.testing.FakeImportRepository
import com.pam.recap.testing.FakeSummaryRepository
import com.pam.recap.testing.FakeTransactionRepository
import com.pam.recap.testing.NoopBackendSummarySync
import com.pam.recap.testing.SequentialIdGenerator
import com.pam.recap.testing.completeJob
import com.pam.recap.testing.fixedClock
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Instant

/**
 * §9 test 7 (regeneration half) plus FR-34/FR-38/FR-41 gating: no call without consent, no call
 * for an empty month, at most one regeneration, and a withdrawal detected after the provider
 * returns must discard the result instead of publishing it.
 */
class RequestAnalysisUseCaseTest {

    private val owner = "u_demo"
    private val month = JakartaMonth(2026, 2)
    private val now = Instant.parse("2026-03-01T02:00:00Z")

    private val rows = listOf(
        transaction("salary", Direction.CREDIT, 5_000_000, Category.INCOME, "2026-02-01T01:00:00Z"),
        transaction("rent", Direction.DEBIT, 1_000_000, Category.HOUSING, "2026-02-05T02:00:00Z"),
    )

    private val transactions = FakeTransactionRepository(rows)
    private val summaries = FakeSummaryRepository()
    private val imports = FakeImportRepository(latest = completeJob(owner, month, now))
    private val connections = FakeGmailConnectionRepository()
    private val consents = FakeConsentRepository(
        listOf(
            Consent(
                id = "consent-ai",
                ownerId = owner,
                purpose = ConsentPurpose.AI_PROCESSING,
                noticeVersion = "v1",
                decision = ConsentDecision.GRANTED,
                decidedAt = now,
            ),
        ),
    )
    private val analyses = FakeAnalysisRepository()
    private val computeSummary = ComputeMonthlySummaryUseCase(
        transactions = transactions,
        summaries = summaries,
        imports = imports,
        gmailConnection = connections,
        clock = fixedClock(now),
    )

    private val remoteConfiguration = object : AnalysisEngineConfiguration {
        override val isConfigured: Boolean = false
    }

    private val validOutput = RawAnalysis(
        providerId = "test-engine",
        modelId = "test-model",
        promptVersion = "v1",
        observations = listOf(
            RawObservation(
                text = "Pemasukan Rp5.000.000 dan pengeluaran Rp1.000.000 bulan ini.",
                metricKeys = listOf(MetricKeys.GROSS_INFLOW, MetricKeys.GROSS_OUTFLOW),
            ),
        ),
        suggestions = emptyList(),
    )

    private val inventedOutput = validOutput.copy(
        observations = listOf(
            RawObservation(
                text = "Total pemasukan Anda Rp99.000.000 bulan ini.",
                metricKeys = listOf(MetricKeys.GROSS_INFLOW),
            ),
        ),
    )

    private class ScriptedEngine(
        private val output: () -> RawAnalysis?,
        private val onAnalyze: () -> Unit = {},
    ) : AnalysisEngine {
        var calls = 0
            private set

        override val providerId: String = "test-engine"
        override val modelId: String = "test-model"

        override suspend fun analyze(request: AnalysisRequest): Outcome<RawAnalysis> {
            calls++
            onAnalyze()
            val raw = output() ?: return Outcome.Failure(AppError.Network("engine_down"))
            return Outcome.Success(raw)
        }
    }

    private fun useCase(
        onDevice: AnalysisEngine,
        remote: AnalysisEngine = ScriptedEngine({ validOutput }),
    ) = RequestAnalysisUseCase(
        computeSummary = computeSummary,
        validator = AnalysisValidator(),
        consents = consents,
        analyses = analyses,
                payloadSource = AiPayloadBuilder(),
        onDeviceEngine = onDevice,
        remoteEngine = remote,
        remoteConfiguration = remoteConfiguration,
        gmailConnection = connections,
        clock = fixedClock(now),
        idGenerator = SequentialIdGenerator("analysis"),
        backendSummary = NoopBackendSummarySync(),
    )

    @Test
    fun `a valid output is published on the first attempt`() = runTest {
        val engine = ScriptedEngine({ validOutput })

        val result = useCase(engine)(owner, month)

        assertTrue(result is Outcome.Success)
        assertEquals(1, engine.calls)
        assertEquals(1, analyses.saved.size)
        val stored = analyses.saved.single()
        assertEquals(AnalysisStatus.AVAILABLE, stored.status)
        assertEquals(owner, stored.ownerId)
        assertEquals("2026-02", stored.monthId)
        assertTrue(stored.validation.isValid)
        assertEquals(0, stored.validation.regenerationCount)
    }

    @Test
    fun `an invalid output is regenerated exactly once and then rejected`() = runTest {
        val engine = ScriptedEngine({ inventedOutput })

        val result = useCase(engine)(owner, month)

        // FR-37: initial attempt + exactly one regeneration, no unbounded retry.
        assertEquals(2, engine.calls)
        assertTrue(result is Outcome.Failure)
        assertEquals(AppErrorRules.ANALYSIS_REJECTED, (result as Outcome.Failure).error.debugDetail)
        assertTrue(analyses.saved.isEmpty())
    }

    @Test
    fun `no provider call happens without ai consent`() = runTest {
        consents.ledger.value = emptyList()
        val engine = ScriptedEngine({ validOutput })

        val result = useCase(engine)(owner, month)

        assertEquals(0, engine.calls)
        assertEquals(AppErrorRules.CONSENT_REQUIRED, (result as Outcome.Failure).error.debugDetail)
    }

    @Test
    fun `no provider call happens for a month without records`() = runTest {
        transactions.replaceAll(emptyList())
        val engine = ScriptedEngine({ validOutput })

        val result = useCase(engine)(owner, month)

        assertEquals(0, engine.calls)
        assertEquals(AppErrorRules.MONTH_HAS_NO_DATA, (result as Outcome.Failure).error.debugDetail)
    }

    @Test
    fun `a withdrawal during the provider call discards the result`() = runTest {
        val engine = ScriptedEngine(
            output = { validOutput },
            onAnalyze = { consents.withdraw(ConsentPurpose.AI_PROCESSING) },
        )

        val result = useCase(engine)(owner, month)

        assertEquals(1, engine.calls)
        assertEquals(AppErrorRules.AI_CONSENT_WITHDRAWN, (result as Outcome.Failure).error.debugDetail)
        assertTrue("result must not be published", analyses.saved.isEmpty())
    }

    private fun transaction(
        id: String,
        direction: Direction,
        major: Long,
        category: Category,
        eventAtIso: String,
    ): Transaction = Transaction(
        id = id,
        ownerId = owner,
        direction = direction,
        source = TransactionSource.EMAIL,
        amount = Money.ofMajor(major),
        currency = "IDR",
        eventAt = Instant.parse(eventAtIso),
        receivedAt = Instant.parse(eventAtIso),
        description = "row-$id",
        merchantLabel = null,
        acquirer = null,
        status = ParseStatus.POSTED,
        fingerprint = "fp-$id".padEnd(32, '0'),
        parserVersion = "livin-qris-payment/v1",
        parserConfidence = 1f,
        category = category,
        categoryOrigin = CategoryOrigin.RULE,
        categoryConfidence = 0.9f,
        categoryUpdatedAt = null,
        isInternalTransfer = false,
        linkedRefundOf = null,
        note = null,
        importJobId = null,
        revision = 0,
        createdAt = now,
        updatedAt = now,
    )
}
