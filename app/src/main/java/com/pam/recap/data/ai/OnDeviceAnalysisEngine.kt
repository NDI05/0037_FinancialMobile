package com.pam.recap.data.ai

import com.pam.recap.core.money.IdrFormatter
import com.pam.recap.core.money.Money
import com.pam.recap.core.result.AppError
import com.pam.recap.core.result.Outcome
import com.pam.recap.domain.model.AiAggregatePayload
import com.pam.recap.domain.model.AiCategoryTotal
import com.pam.recap.domain.model.AnalysisLimitation
import com.pam.recap.domain.model.AnalysisUsage
import com.pam.recap.domain.model.Category
import com.pam.recap.domain.model.MetricKeys
import com.pam.recap.domain.model.ObservationTemplate
import com.pam.recap.domain.model.RawAnalysis
import com.pam.recap.domain.model.RawObservation
import com.pam.recap.domain.model.RawSuggestion
import com.pam.recap.domain.model.SuggestionTemplate
import com.pam.recap.domain.repository.AnalysisEngine
import com.pam.recap.domain.repository.AnalysisRequest
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

/** Version stamp recorded with every stored analysis (FR-39). */
object AnalysisPrompt {
    const val ON_DEVICE_VERSION: String = "pam-ondevice-prompt-v1"
    const val REMOTE_VERSION: String = "pam-remote-prompt-v1"
}

/**
 * Deterministic, offline, evidence-bound engine (OI-07 default).
 *
 * Design rules that make FR-36 satisfiable without an LLM:
 * - every observation/suggestion cites at least one [MetricKeys] entry;
 * - every amount is taken from the payload, or is an exact integer fraction of one from the
 *   payload (90 % of the top category, 20 % of inflow) — no amount is ever invented;
 * - copy is resolved from `strings.xml`, so the sentences are translatable (hard rule 2);
 * - with no data it says so instead of inventing a trend (FR-38, BR-06).
 */
@Singleton
class OnDeviceAnalysisEngine @Inject constructor(
    private val copy: AnalysisCopySource,
) : AnalysisEngine {

    override val providerId: String = PROVIDER_ID

    override val modelId: String = MODEL_ID

    override suspend fun analyze(request: AnalysisRequest): Outcome<RawAnalysis> {
        val payload = request.payload
        if (payload.schemaVersion != AiAggregatePayload.SCHEMA_VERSION) {
            return Outcome.Failure(AppError.Validation("payload_schema_unsupported"))
        }
        return Outcome.Success(buildAnalysis(payload))
    }

    private fun buildAnalysis(payload: AiAggregatePayload): RawAnalysis {
        val monthLabel = copy.monthLabel(payload.monthId)
        val observations = mutableListOf<RawObservation>()
        val suggestions = mutableListOf<RawSuggestion>()

        observations += RawObservation(
            text = copy.observation(
                ObservationTemplate.MONTH_TOTALS,
                monthLabel,
                IdrFormatter.format(Money(payload.grossInflowMinor)),
                IdrFormatter.format(Money(payload.grossOutflowMinor)),
            ),
            metricKeys = listOf(MetricKeys.GROSS_INFLOW, MetricKeys.GROSS_OUTFLOW),
            templateKey = ObservationTemplate.MONTH_TOTALS.name,
        )

        if (payload.transactionCount == 0) {
            observations += RawObservation(
                text = copy.observation(ObservationTemplate.EMPTY_MONTH, monthLabel),
                metricKeys = listOf(MetricKeys.TRANSACTION_COUNT, MetricKeys.COVERAGE),
                templateKey = ObservationTemplate.EMPTY_MONTH.name,
            )
            suggestions += RawSuggestion(
                text = copy.suggestion(SuggestionTemplate.NO_DATA),
                metricKeys = listOf(MetricKeys.TRANSACTION_COUNT),
                templateKey = SuggestionTemplate.NO_DATA.name,
            )
            return RawAnalysis(
                providerId = providerId,
                modelId = modelId,
                promptVersion = AnalysisPrompt.ON_DEVICE_VERSION,
                observations = observations,
                suggestions = suggestions,
                limitations = limitations(),
                usage = AnalysisUsage(),
            )
        }

        val net = payload.netCashFlowMinor
        if (net > 0) {
            observations += RawObservation(
                text = copy.observation(ObservationTemplate.NET_POSITIVE, IdrFormatter.format(Money(net))),
                metricKeys = listOf(MetricKeys.NET_CASH_FLOW),
                templateKey = ObservationTemplate.NET_POSITIVE.name,
            )
        } else if (net < 0) {
            observations += RawObservation(
                text = copy.observation(ObservationTemplate.NET_NEGATIVE, IdrFormatter.format(Money(-net))),
                metricKeys = listOf(MetricKeys.NET_CASH_FLOW),
                templateKey = ObservationTemplate.NET_NEGATIVE.name,
            )
        }

        val topExpense = topExpenseCategory(payload)
        if (topExpense != null) {
            observations += RawObservation(
                text = copy.observation(
                    ObservationTemplate.TOP_EXPENSE_CATEGORY,
                    copy.categoryName(Category.fromCodeOrUncategorized(topExpense.categoryCode)),
                    IdrFormatter.format(Money(topExpense.amountMinor)),
                ),
                metricKeys = listOf(
                    MetricKeys.category(topExpense.categoryCode),
                    MetricKeys.CLASSIFIED_EXPENSE,
                ),
                templateKey = ObservationTemplate.TOP_EXPENSE_CATEGORY.name,
            )

            val share = shareOfClassifiedExpense(topExpense, payload)
            if (share != null) {
                observations += RawObservation(
                    text = copy.observation(
                        ObservationTemplate.EXPENSE_DOMINANT_SHARE,
                        copy.categoryName(Category.fromCodeOrUncategorized(topExpense.categoryCode)),
                        share.roundToInt().toString(),
                    ),
                    metricKeys = listOf(
                        MetricKeys.category(topExpense.categoryCode),
                        MetricKeys.CLASSIFIED_EXPENSE,
                    ),
                    templateKey = ObservationTemplate.EXPENSE_DOMINANT_SHARE.name,
                )
            }
        }

        if (payload.uncategorizedCount > 0) {
            observations += RawObservation(
                text = copy.observation(
                    ObservationTemplate.UNCATEGORIZED_ROWS,
                    payload.uncategorizedCount.toString(),
                ),
                metricKeys = listOf(MetricKeys.UNCATEGORIZED_COUNT),
                templateKey = ObservationTemplate.UNCATEGORIZED_ROWS.name,
            )
        }
        if (payload.nonIdrExcludedCount > 0) {
            observations += RawObservation(
                text = copy.observation(
                    ObservationTemplate.NON_IDR_ROWS,
                    payload.nonIdrExcludedCount.toString(),
                ),
                metricKeys = listOf(MetricKeys.NON_IDR_EXCLUDED_COUNT),
                templateKey = ObservationTemplate.NON_IDR_ROWS.name,
            )
        }
        if (payload.manualEntryCount > 0) {
            observations += RawObservation(
                text = copy.observation(
                    ObservationTemplate.MANUAL_ROWS,
                    payload.manualEntryCount.toString(),
                ),
                metricKeys = listOf(MetricKeys.MANUAL_ENTRY_COUNT),
                templateKey = ObservationTemplate.MANUAL_ROWS.name,
            )
        }
        if (payload.coverage == COVERAGE_PARTIAL) {
            observations += RawObservation(
                text = copy.observation(ObservationTemplate.PARTIAL_COVERAGE),
                metricKeys = listOf(MetricKeys.COVERAGE),
                templateKey = ObservationTemplate.PARTIAL_COVERAGE.name,
            )
        }

        val prior = payload.priorMonth
        if (prior == null || prior.expenseMinor == 0L) {
            observations += RawObservation(
                text = copy.observation(ObservationTemplate.NO_PRIOR_MONTH),
                metricKeys = listOf(MetricKeys.COVERAGE),
                templateKey = ObservationTemplate.NO_PRIOR_MONTH.name,
            )
        } else {
            val delta = payload.classifiedExpenseMinor - prior.expenseMinor
            val percent = (delta.toDouble() / prior.expenseMinor.toDouble() * 100.0).roundToInt()
            val template = if (delta >= 0) {
                ObservationTemplate.EXPENSE_INCREASED
            } else {
                ObservationTemplate.EXPENSE_DECREASED
            }
            observations += RawObservation(
                text = copy.observation(template, kotlin.math.abs(percent).toString()),
                metricKeys = listOf(MetricKeys.CLASSIFIED_EXPENSE, MetricKeys.COVERAGE),
                templateKey = template.name,
            )
        }

        // Suggestions are derived from payload values only; no external averages are invented.
        if (topExpense != null) {
            val limit = Money(topExpense.amountMinor * 9L / 10L)
            suggestions += RawSuggestion(
                text = copy.suggestion(
                    SuggestionTemplate.SET_TOP_CATEGORY_LIMIT,
                    copy.categoryName(Category.fromCodeOrUncategorized(topExpense.categoryCode)),
                    IdrFormatter.format(limit),
                ),
                metricKeys = listOf(
                    MetricKeys.category(topExpense.categoryCode),
                    MetricKeys.CLASSIFIED_EXPENSE,
                ),
                categoryCode = topExpense.categoryCode,
                suggestedAmountMinor = limit.minor,
                templateKey = SuggestionTemplate.SET_TOP_CATEGORY_LIMIT.name,
            )
        }
        if (payload.grossInflowMinor > 0L) {
            val target = Money(payload.grossInflowMinor * 2L / 10L)
            suggestions += RawSuggestion(
                text = copy.suggestion(
                    SuggestionTemplate.SET_SAVINGS_TARGET,
                    IdrFormatter.format(target),
                ),
                metricKeys = listOf(MetricKeys.GROSS_INFLOW, MetricKeys.NET_CASH_FLOW),
                suggestedAmountMinor = target.minor,
                templateKey = SuggestionTemplate.SET_SAVINGS_TARGET.name,
            )
        }
        if (payload.uncategorizedCount > 0) {
            suggestions += RawSuggestion(
                text = copy.suggestion(
                    SuggestionTemplate.REVIEW_UNCATEGORIZED,
                    payload.uncategorizedCount.toString(),
                ),
                metricKeys = listOf(MetricKeys.UNCATEGORIZED_COUNT),
                templateKey = SuggestionTemplate.REVIEW_UNCATEGORIZED.name,
            )
        }
        if (suggestions.isEmpty()) {
            suggestions += RawSuggestion(
                text = copy.suggestion(SuggestionTemplate.KEEP_MONTH_ON_TRACK),
                metricKeys = listOf(MetricKeys.NET_CASH_FLOW),
                templateKey = SuggestionTemplate.KEEP_MONTH_ON_TRACK.name,
            )
        }

        return RawAnalysis(
            providerId = providerId,
            modelId = modelId,
            promptVersion = AnalysisPrompt.ON_DEVICE_VERSION,
            observations = observations,
            suggestions = suggestions,
            limitations = limitations(),
            usage = AnalysisUsage(),
        )
    }

    private fun topExpenseCategory(payload: AiAggregatePayload): AiCategoryTotal? =
        payload.categoryTotals
            .asSequence()
            .filter { total ->
                Category.fromCode(total.categoryCode)?.isExpenseCategory == true && total.amountMinor > 0L
            }
            .maxByOrNull { it.amountMinor }

    private fun shareOfClassifiedExpense(
        top: AiCategoryTotal,
        payload: AiAggregatePayload,
    ): Double? {
        if (payload.classifiedExpenseMinor <= 0L) return null
        return top.amountMinor.toDouble() / payload.classifiedExpenseMinor.toDouble() * 100.0
    }

    private fun limitations(): List<String> = listOf(
        copy.limitation(AnalysisLimitation.AGGREGATE_ONLY),
        copy.limitation(AnalysisLimitation.EMAIL_DERIVED),
        copy.limitation(AnalysisLimitation.NO_ADVICE),
        copy.limitation(AnalysisLimitation.DETERMINISTIC),
    )

    private companion object {
        const val PROVIDER_ID = "pam-ondevice"
        const val MODEL_ID = "deterministic-v1"
        const val COVERAGE_PARTIAL = "PARTIAL"
    }
}
