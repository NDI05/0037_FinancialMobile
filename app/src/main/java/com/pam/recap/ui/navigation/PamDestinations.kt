package com.pam.recap.ui.navigation

import kotlinx.serialization.Serializable

/**
 * Type-safe navigation graph (Navigation Compose 2.9, `@Serializable` destinations).
 *
 * Thirteen destinations exactly as specified in `docs/03_ARCHITECTURE.md` §1: three gate screens,
 * four top-level tabs, three flows and three privacy surfaces. Optional parameters are query
 * arguments so a drill-down (FR-32) and a job deep-link can be expressed without stringly routes.
 */
sealed interface PamDestination {

    @Serializable
    data object Welcome : PamDestination

    @Serializable
    data object Consent : PamDestination

    @Serializable
    data object GmailConnect : PamDestination

    @Serializable
    data class Overview(val monthId: String? = null) : PamDestination

    /** FR-27/FR-32: the list can be opened pre-filtered by category, month or day. */
    @Serializable
    data class Transactions(
        val categoryCode: String? = null,
        val monthId: String? = null,
        val dayIso: String? = null,
    ) : PamDestination

    @Serializable
    data class TransactionDetail(val transactionId: String) : PamDestination

    @Serializable
    data class ManualEntry(val monthId: String? = null) : PamDestination

    @Serializable
    data class ImportJob(val jobId: String? = null, val monthId: String? = null) : PamDestination

    @Serializable
    data class Analysis(val monthId: String? = null) : PamDestination

    @Serializable
    data class BudgetPlan(val monthId: String? = null) : PamDestination

    @Serializable
    data object Settings : PamDestination

    @Serializable
    data object ConsentHistory : PamDestination

    @Serializable
    data object DataDeletion : PamDestination

    companion object {
        /** Tab keys used by the bottom bar; they are technical ids, not user copy. */
        const val TAB_OVERVIEW = "overview"
        const val TAB_TRANSACTIONS = "transactions"
        const val TAB_ANALYSIS = "analysis"
        const val TAB_SETTINGS = "settings"
    }
}
