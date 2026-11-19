package com.pam.recap.domain.usecase

import com.pam.recap.core.di.OnDeviceEngine
import com.pam.recap.core.di.RemoteEngine
import com.pam.recap.core.money.Money
import com.pam.recap.core.result.AppError
import com.pam.recap.core.result.AppErrorRules
import com.pam.recap.core.result.Outcome
import com.pam.recap.core.time.ClockProvider
import com.pam.recap.core.time.JakartaMonth
import com.pam.recap.core.util.IdGenerator
import com.pam.recap.domain.model.Analysis
import com.pam.recap.domain.model.AiAggregatePayload
import com.pam.recap.domain.model.AnalysisStatus
import com.pam.recap.domain.model.Category
import com.pam.recap.domain.model.ConsentPurpose
import com.pam.recap.domain.model.CoverageStatus
import com.pam.recap.domain.model.Observation
import com.pam.recap.domain.model.RawAnalysis
import com.pam.recap.domain.model.Suggestion
import com.pam.recap.domain.repository.AiPayloadSource
import com.pam.recap.domain.repository.AnalysisEngine
import com.pam.recap.domain.repository.AnalysisEngineConfiguration
import com.pam.recap.domain.repository.AnalysisRepository
import com.pam.recap.domain.repository.AnalysisRequest
import com.pam.recap.domain.repository.BackendSummarySync
import com.pam.recap.domain.repository.BudgetRepository
import com.pam.recap.domain.repository.ConsentRepository
import com.pam.recap.domain.repository.GmailConnectionRepository
import javax.inject.Inject

/**
 * FR-34…FR-41 analysis request.
 *
 * Order of operations is the state machine from §7: consent → frozen summary → provider →
 * validation → publish. Notable guarantees:
 * - no provider call happens without a granted AI consent (FR-34), and a withdrawal observed
 *   after the call discards the result instead of publishing it (FR-41);
 * - no call happens for a month with no records (FR-38, BR-06);
 * - a saved analysis is reused when the input revision, the consent record and the provider/model
 *   are unchanged (FR-40); the stored prompt version is kept for provenance and audit;
 * - an invalid output gets at most one regeneration, then the request fails as `REJECTED`
 *   (FR-37);
 * - partial coverage publishes as `PROVISIONAL` (FR-38).
 */
class RequestAnalysisUseCase @Inject constructor(
    private val computeSummary: ComputeMonthlySummaryUseCase,
    private val validator: AnalysisValidator,
    private val consents: ConsentRepository,
    private val analyses: AnalysisRepository,
    private val budgets: BudgetRepository,
    private val payloadSource: AiPayloadSource,
    @param:OnDeviceEngine private val onDeviceEngine: AnalysisEngine,
    @param:RemoteEngine private val remoteEngine: AnalysisEngine,
    private val remoteConfiguration: AnalysisEngineConfiguration,
    private val gmailConnection: GmailConnectionRepository,
    private val clock: ClockProvider,
    private val idGenerator: IdGenerator,
    private val backendSummary: BackendSummarySync,
) {

    suspend operator fun invoke(
        ownerId: String,
        month: JakartaMonth,
        regenerate: Boolean = false,
    ): Outcome<Analysis> {
        val consent = consents.latest(ownerId, ConsentPurpose.AI_PROCESSING)
        if (consent?.isGranted != true) {
            val rule = if (consent?.isWithdrawn == true) {
                AppErrorRules.AI_CONSENT_WITHDRAWN
            } else {
                AppErrorRules.CONSENT_REQUIRED
            }
            return Outcome.Failure(AppError.Validation(rule))
        }

        val comparison = computeSummary.withComparison(ownerId, month)
        val summary = comparison.summary
        if (!summary.hasData || summary.coverage == CoverageStatus.FAILED) {
            // FR-38: no records means no call, never an invented trend.
            return Outcome.Failure(AppError.Validation(AppErrorRules.MONTH_HAS_NO_DATA))
        }

        val engine = if (remoteConfiguration.isConfigured) remoteEngine else onDeviceEngine
        val plan = budgets.active(ownerId, month)

        val cached = analyses.latest(ownerId, month.id())
        if (!regenerate && cached != null &&
            cached.isReusableFor(
                inputRevision = summary.inputRevision,
                consentId = consent.id,
                providerId = engine.providerId,
                modelId = engine.modelId,
                promptVersion = cached.promptVersion,
            )
        ) {
            return Outcome.Success(cached)
        }

        val payload = payloadSource.build(summary, comparison.previousMonth, plan)
        val request = AnalysisRequest(
            ownerId = ownerId,
            monthId = month.id(),
            inputRevision = summary.inputRevision,
            payload = payload,
        )

        // FR-33: the server freezes the same revision it analyses. A failed push is not fatal —
        // the analyses endpoint stores the payload it receives.
        runCatching { backendSummary.push(request) }

        var regeneration = 0
        var raw = analyzeWithFallback(engine, request)
        var validation = validator.validate(raw, payload)
        if (!validation.isValid) {
            // FR-37: exactly one bounded regeneration, never an unbounded retry loop.
            regeneration = 1
            raw = analyzeWithFallback(engine, request)
            validation = validator.validate(raw, payload)
        }
        if (raw == null || !validation.isValid) {
            return Outcome.Failure(AppError.Validation(AppErrorRules.ANALYSIS_REJECTED))
        }

        // FR-41: consent may have been withdrawn while the provider was working.
        val latestConsent = consents.latest(ownerId, ConsentPurpose.AI_PROCESSING)
        if (latestConsent?.isGranted != true) {
            return Outcome.Failure(AppError.Validation(AppErrorRules.AI_CONSENT_WITHDRAWN))
        }

        val publishedAt = clock.now()
        val analysis = Analysis(
            id = idGenerator.newId(),
            ownerId = ownerId,
            monthId = month.id(),
            summaryId = summary.id,
            inputRevision = summary.inputRevision,
            consentId = consent.id,
            providerId = raw.providerId,
            modelId = raw.modelId,
            promptVersion = raw.promptVersion,
            observations = raw.observations.map { Observation(it.text, it.metricKeys, it.templateKey) },
            suggestions = raw.suggestions.map { suggestion ->
                Suggestion(
                    text = suggestion.text,
                    metricKeys = suggestion.metricKeys,
                    category = suggestion.categoryCode?.let { Category.fromCode(it) },
                    suggestedAmount = suggestion.suggestedAmountMinor?.let(::Money),
                    templateKey = suggestion.templateKey,
                )
            },
            limitations = raw.limitations,
            status = if (summary.coverage == CoverageStatus.PARTIAL) {
                AnalysisStatus.PROVISIONAL
            } else {
                AnalysisStatus.AVAILABLE
            },
            validation = validation.copy(regenerationCount = regeneration, validatedAt = publishedAt),
            usage = raw.usage,
            createdAt = publishedAt,
        )
        analyses.save(analysis)
        runCatching { gmailConnection.touchLastAi(ownerId, publishedAt) }
        return Outcome.Success(analysis)
    }

    private suspend fun analyzeWithFallback(
        engine: AnalysisEngine,
        request: AnalysisRequest,
    ): RawAnalysis? {
        val first = engine.analyze(request)
        if (first is Outcome.Success) return first.value
        if (engine === remoteEngine) {
            // OI-07: a misconfigured or unavailable backend must not block the offline engine.
            val fallback = onDeviceEngine.analyze(request)
            return (fallback as? Outcome.Success)?.value
        }
        return null
    }


}
