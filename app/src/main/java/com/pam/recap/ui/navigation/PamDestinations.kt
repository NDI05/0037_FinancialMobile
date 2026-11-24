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

    /** FR-51/FR-52: sign-in, registration, or the offline local profile. */
    @Serializable
    data class Account(
        val mode: com.pam.recap.ui.feature.account.AccountMode = com.pam.recap.ui.feature.account.AccountMode.SIGN_IN,
    ) : PamDestination

    @Serializable
    data object Consent : PamDestination

    @Serializable
    data object GmailConnect : PamDestination

    /**
     * Server-side Gmail OAuth link (contract `0037-Backend-Financial` §1–§4). The optional status/
     * reason come from the `pam://gmail/connected` deep link handled by MainActivity.
     */
    @Serializable
    data class GmailLink(
        val status: String? = null,
        val reason: String? = null,
    ) : PamDestination

    @Serializable
    data class Overview(val monthId: String? = null) : PamDestination

    /**
     * FR-27/FR-32: the list can be opened pre-filtered by category, month, day or direction.
     *
     * [direction] is `CREDIT`/`DEBIT` (the enum name) so the Overview "Masuk"/"Keluar" cards open
     * exactly the rows behind those totals. [bank] is carried for the future bank filter; no bank
     * field exists on transactions yet, so it is accepted but not applied.
     */
    @Serializable
    data class Transactions(
        val categoryCode: String? = null,
        val monthId: String? = null,
        val dayIso: String? = null,
        val direction: String? = null,
        val bank: String? = null,
    ) : PamDestination

    @Serializable
    data class TransactionDetail(val transactionId: String) : PamDestination

    @Serializable
    data class ManualEntry(val monthId: String? = null) : PamDestination

    @Serializable
    data class Analysis(val monthId: String? = null) : PamDestination

    @Serializable
    data object CategoryManagement : PamDestination

    @Serializable
    data object Settings : PamDestination

    /**
     * Signed-in "Akun" editor (profile, Google link state, Gmail shortcut). It is a separate route
     * from [Account] on purpose: [Account] is the pre-session entry screen and the gate redirects
     * it to Overview once a session exists, so the gate would otherwise make this unreachable.
     */
    @Serializable
    data object AccountSettings : PamDestination

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
