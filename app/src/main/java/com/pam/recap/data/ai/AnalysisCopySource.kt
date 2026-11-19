package com.pam.recap.data.ai

import android.content.Context
import com.pam.recap.R
import com.pam.recap.core.time.JakartaMonth
import com.pam.recap.domain.model.AnalysisLimitation
import com.pam.recap.domain.model.Category
import com.pam.recap.domain.model.ObservationTemplate
import com.pam.recap.domain.model.SuggestionTemplate
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Resolves engine-generated copy from resources.
 *
 * The on-device engine is deterministic, but its sentences are still user-visible: they must come
 * from `strings.xml` in both locales (hard rule 2), not from literals in Kotlin. This is the seam
 * that keeps the engine free of Android types while the copy stays translatable.
 */
interface AnalysisCopySource {

    fun observation(template: ObservationTemplate, vararg args: String): String

    fun suggestion(template: SuggestionTemplate, vararg args: String): String

    fun limitation(limitation: AnalysisLimitation): String

    fun categoryName(category: Category): String

    fun monthLabel(monthId: String): String
}

@Singleton
class AndroidAnalysisCopySource @Inject constructor(
    @ApplicationContext private val context: Context,
) : AnalysisCopySource {

    override fun observation(template: ObservationTemplate, vararg args: String): String =
        context.getString(observationRes(template), *args)

    override fun suggestion(template: SuggestionTemplate, vararg args: String): String =
        context.getString(suggestionRes(template), *args)

    override fun limitation(limitation: AnalysisLimitation): String =
        context.getString(limitationRes(limitation))

    override fun categoryName(category: Category): String = context.getString(categoryRes(category))

    /** Month names come from locale data, never from a literal table (hard rule 2). */
    override fun monthLabel(monthId: String): String =
        JakartaMonth.parse(monthId)?.label() ?: monthId

    private fun observationRes(template: ObservationTemplate): Int = when (template) {
        ObservationTemplate.MONTH_TOTALS -> R.string.analysis_obs_month_totals
        ObservationTemplate.NET_POSITIVE -> R.string.analysis_obs_net_positive
        ObservationTemplate.NET_NEGATIVE -> R.string.analysis_obs_net_negative
        ObservationTemplate.TOP_EXPENSE_CATEGORY -> R.string.analysis_obs_top_category
        ObservationTemplate.EXPENSE_DOMINANT_SHARE -> R.string.analysis_obs_expense_dominant_share
        ObservationTemplate.UNCATEGORIZED_ROWS -> R.string.analysis_obs_uncategorized
        ObservationTemplate.NON_IDR_ROWS -> R.string.analysis_obs_non_idr
        ObservationTemplate.MANUAL_ROWS -> R.string.analysis_obs_manual
        ObservationTemplate.PARTIAL_COVERAGE -> R.string.analysis_obs_partial_coverage
        ObservationTemplate.EMPTY_MONTH -> R.string.analysis_obs_empty_month
        ObservationTemplate.NO_PRIOR_MONTH -> R.string.analysis_obs_no_prior_month
        ObservationTemplate.EXPENSE_INCREASED -> R.string.analysis_obs_expense_increased
        ObservationTemplate.EXPENSE_DECREASED -> R.string.analysis_obs_expense_decreased
    }

    private fun suggestionRes(template: SuggestionTemplate): Int = when (template) {
        SuggestionTemplate.SET_TOP_CATEGORY_LIMIT -> R.string.analysis_sug_top_category_limit
        SuggestionTemplate.SET_SAVINGS_TARGET -> R.string.analysis_sug_savings_target
        SuggestionTemplate.REVIEW_UNCATEGORIZED -> R.string.analysis_sug_review_uncategorized
        SuggestionTemplate.KEEP_MONTH_ON_TRACK -> R.string.analysis_sug_keep_on_track
        SuggestionTemplate.NO_DATA -> R.string.analysis_sug_no_data
    }

    private fun limitationRes(limitation: AnalysisLimitation): Int = when (limitation) {
        AnalysisLimitation.AGGREGATE_ONLY -> R.string.analysis_limit_aggregate_only
        AnalysisLimitation.EMAIL_DERIVED -> R.string.analysis_limit_email_derived
        AnalysisLimitation.NO_ADVICE -> R.string.analysis_limit_no_advice
        AnalysisLimitation.DETERMINISTIC -> R.string.analysis_limit_deterministic
    }

    private fun categoryRes(category: Category): Int = when (category) {
        Category.INCOME -> R.string.category_income
        Category.HOUSING -> R.string.category_housing
        Category.FOOD -> R.string.category_food
        Category.TRANSPORT -> R.string.category_transport
        Category.UTILITIES -> R.string.category_utilities
        Category.SHOPPING -> R.string.category_shopping
        Category.HEALTH -> R.string.category_health
        Category.EDUCATION -> R.string.category_education
        Category.OTHER_EXPENSE -> R.string.category_other_expense
        Category.TRANSFER -> R.string.category_transfer
        Category.UNCATEGORIZED -> R.string.category_uncategorized
    }
}
