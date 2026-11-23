package com.pam.recap.data.backend

import com.pam.recap.core.di.RemoteEngine
import com.pam.recap.core.result.AppError
import com.pam.recap.core.result.AppErrorRules
import com.pam.recap.core.result.Outcome
import com.pam.recap.core.result.getOrNull
import com.pam.recap.core.time.JakartaMonth
import com.pam.recap.domain.model.AiAggregatePayload
import com.pam.recap.domain.model.AiCategoryTotal
import com.pam.recap.domain.model.AiPlanSummary
import com.pam.recap.domain.model.AiPriorMonthTotals
import com.pam.recap.domain.model.AnalysisStatus
import com.pam.recap.domain.model.Category
import com.pam.recap.domain.model.CoverageStatus
import com.pam.recap.domain.model.Observation
import com.pam.recap.domain.model.Suggestion
import com.pam.recap.domain.model.SummaryFreshness
import com.pam.recap.domain.repository.AnalysisEngine
import com.pam.recap.domain.repository.AnalysisEngineConfiguration
import com.pam.recap.domain.repository.AnalysisRequest
import com.pam.recap.domain.repository.ServerAnalysis
import com.pam.recap.domain.repository.ServerAnalysisRepository
import com.pam.recap.domain.repository.ServerFinanceRepository
import kotlin.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * S2.5 server-mode analysis source. The stored analysis and the monthly summary both come from the
 * backend, so the screen renders the server's canonical `freshness`/`currentRevision` instead of a
 * locally computed summary (which is empty in server mode). Regeneration builds the aggregate
 * payload from the same server summary + prior month + budget plan and posts it through the remote
 * engine, which owns the Idempotency-Key and the consent error mapping.
 */
@Singleton
class ServerAnalysisRepositoryImpl @Inject constructor(
    private val api: PamApiClient,
    private val finance: ServerFinanceRepository,
    @param:RemoteEngine private val remoteEngine: AnalysisEngine,
    private val remoteConfiguration: AnalysisEngineConfiguration,
) : ServerAnalysisRepository {

    override val isEnabled: Boolean
        get() = remoteConfiguration.isConfigured

    override suspend fun latest(
        ownerId: String,
        month: JakartaMonth,
    ): Outcome<ServerAnalysis?> = when (val result = api.latestAnalysis(ownerId, month.id())) {
        is Outcome.Success -> Outcome.Success(result.value.toServer())
        is Outcome.Failure ->
            // The backend answers 404 until an analysis exists for the month.
            if (result.error is AppError.NotFound) Outcome.Success(null) else result
    }

    override suspend fun generate(ownerId: String, month: JakartaMonth): Outcome<Unit> {
        if (!isEnabled) return Outcome.Failure(AppError.Configuration("analysis_backend_not_configured"))
        val summary = when (val result = finance.summary(ownerId, month)) {
            is Outcome.Success -> result.value
            is Outcome.Failure -> return result
        }
        if (!summary.hasData || summary.coverage == CoverageStatus.FAILED ||
            summary.coverage == CoverageStatus.EMPTY
        ) {
            return Outcome.Failure(AppError.Validation(AppErrorRules.MONTH_HAS_NO_DATA))
        }
        val previous = finance.summary(ownerId, month.previous()).getOrNull()
            ?.takeIf { it.hasData }
        val plan = runCatching { planSummary(ownerId, month) }.getOrNull()
        val payload = ServerAnalysisPayload.assemble(summary, previous, plan)
        val request = AnalysisRequest(
            ownerId = ownerId,
            monthId = month.id(),
            inputRevision = summary.currentRevision,
            payload = payload,
        )
        return when (val result = remoteEngine.analyze(request)) {
            is Outcome.Success -> Outcome.Success(Unit)
            is Outcome.Failure -> result
        }
    }

    /** Budget plan is optional: a missing plan, unknown category or failed read simply omits it. */
    private suspend fun planSummary(ownerId: String, month: JakartaMonth): AiPlanSummary? {
        val plan = when (val result = api.budget(ownerId, month.id())) {
            is Outcome.Success -> result.value
            is Outcome.Failure -> return null
        }
        if (plan.limits.isEmpty()) {
            return AiPlanSummary(
                expectedIncomeMinor = plan.expectedIncomeMinor,
                plannedSavingsMinor = plan.plannedSavingsMinor,
                categoryLimitTotals = emptyList(),
            )
        }
        val categories = when (val result = api.categories(ownerId, limit = 100)) {
            is Outcome.Success -> result.value.categories
            is Outcome.Failure -> return null
        }
        val codes = categories.associate { it.id to it.code }
        val limits = plan.limits.mapNotNull { limit ->
            val code = codes[limit.categoryId] ?: return@mapNotNull null
            AiCategoryTotal(categoryCode = code, amountMinor = limit.amountMinor, transactionCount = 0)
        }
        return AiPlanSummary(
            expectedIncomeMinor = plan.expectedIncomeMinor,
            plannedSavingsMinor = plan.plannedSavingsMinor,
            categoryLimitTotals = limits,
        )
    }
}

/**
 * S2.5: aggregate payload assembled from the canonical server responses (summary, prior month,
 * optional plan). Kept as a pure function so the wire-to-payload mapping is unit tested without a
 * live backend. The server re-derives canonical numbers from its own rows; this only has to be a
 * faithful, allow-listed projection of what the device received.
 */
internal object ServerAnalysisPayload {
    fun assemble(
        summary: com.pam.recap.domain.model.MonthlySummary,
        previous: com.pam.recap.domain.model.MonthlySummary?,
        plan: AiPlanSummary?,
    ): AiAggregatePayload = AiAggregatePayload(
        schemaVersion = AiAggregatePayload.SCHEMA_VERSION,
        monthId = summary.month.id(),
        coverage = summary.coverage.name,
        grossInflowMinor = summary.grossInflow.minor,
        grossOutflowMinor = summary.grossOutflow.minor,
        netCashFlowMinor = summary.netCashFlow.minor,
        classifiedIncomeMinor = summary.classifiedIncome.minor,
        classifiedExpenseMinor = summary.classifiedExpense.minor,
        transactionCount = summary.transactionCount,
        uncategorizedCount = summary.uncategorizedCount,
        manualEntryCount = summary.manualEntryCount,
        nonIdrExcludedCount = summary.nonIdrExcludedCount,
        categoryTotals = summary.categoryTotals.map { total ->
            AiCategoryTotal(
                categoryCode = total.serverCode ?: total.category.code,
                amountMinor = total.amount.minor,
                transactionCount = total.transactionCount,
            )
        },
        priorMonth = previous?.let {
            AiPriorMonthTotals(
                monthId = it.month.id(),
                expenseMinor = it.classifiedExpense.minor,
                incomeMinor = it.classifiedIncome.minor,
            )
        },
        plan = plan,
    )
}

internal fun RemoteAnalysis.toServer(): ServerAnalysis {
    val canonical = when (freshness.uppercase()) {
        "CURRENT" -> SummaryFreshness.CURRENT
        "HISTORICAL" -> SummaryFreshness.HISTORICAL
        "STALE" -> SummaryFreshness.STALE
        else -> SummaryFreshness.UNKNOWN
    }
    val wireStatus = when (status.uppercase()) {
        "AVAILABLE" -> AnalysisStatus.AVAILABLE
        "PROVISIONAL" -> AnalysisStatus.PROVISIONAL
        "OUTDATED" -> AnalysisStatus.OUTDATED
        "HISTORICAL" -> AnalysisStatus.HISTORICAL
        "REJECTED" -> AnalysisStatus.REJECTED
        else -> AnalysisStatus.UNAVAILABLE
    }
    // The server's freshness is authoritative: a STALE stored report renders as OUTDATED even when
    // the wire status is still AVAILABLE, and a HISTORICAL one as HISTORICAL.
    val effective = when (canonical) {
        SummaryFreshness.STALE -> AnalysisStatus.OUTDATED
        SummaryFreshness.HISTORICAL -> AnalysisStatus.HISTORICAL
        SummaryFreshness.UNKNOWN -> AnalysisStatus.UNAVAILABLE
        SummaryFreshness.CURRENT -> wireStatus
    }
    return ServerAnalysis(
        monthId = monthId,
        inputRevision = inputRevision,
        currentRevision = currentRevision,
        freshness = canonical,
        status = effective,
        promptVersion = promptVersion,
        observations = observations.orEmpty().map { Observation(it.text, it.metricKeys) },
        suggestions = suggestions.orEmpty().map { suggestion ->
            Suggestion(
                text = suggestion.text,
                metricKeys = suggestion.metricKeys,
                category = suggestion.categoryCode?.let { Category.fromCode(it) },
                suggestedAmount = suggestion.suggestedAmountMinor?.let {
                    com.pam.recap.core.money.Money(it)
                },
            )
        },
        limitations = limitations.orEmpty(),
        createdAt = Instant.fromEpochMilliseconds(0),
    )
}
