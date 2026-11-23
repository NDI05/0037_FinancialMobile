package com.pam.recap.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.ReceiptLong
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.pam.recap.R
import com.pam.recap.ui.components.BottomNavBar
import com.pam.recap.ui.components.BottomNavItem
import com.pam.recap.ui.feature.analysis.AnalysisScreen
import com.pam.recap.ui.feature.budget.BudgetPlanScreen
import com.pam.recap.ui.feature.category.CategoryManagementScreen
import com.pam.recap.ui.feature.consent.ConsentScreen
import com.pam.recap.ui.feature.entry.ManualEntryScreen
import com.pam.recap.ui.feature.gmail.GmailConnectScreen
import com.pam.recap.ui.feature.importjob.ImportJobScreen
import com.pam.recap.ui.feature.overview.OverviewScreen
import com.pam.recap.ui.feature.settings.ConsentHistoryScreen
import com.pam.recap.ui.feature.settings.DataDeletionScreen
import com.pam.recap.ui.feature.settings.SettingsScreen
import com.pam.recap.ui.feature.admin.AdminScreenRoot
import com.pam.recap.ui.feature.transactions.TransactionDetailScreen
import com.pam.recap.ui.feature.transactions.TransactionsScreen
import com.pam.recap.ui.feature.account.AccountMode
import com.pam.recap.ui.feature.account.AccountScreen
import com.pam.recap.ui.feature.welcome.WelcomeScreen
import com.pam.recap.ui.feature.welcome.WelcomeViewModel
import com.pam.recap.ui.session.GateState
import com.pam.recap.ui.session.SessionGateViewModel

/**
 * The whole graph (13 destinations) plus the auth gate and the floating bottom bar.
 *
 * Gate rule (FR-01/FR-03): no session → welcome; a session without a recorded Gmail decision →
 * consent; otherwise the recap. The bar only appears on the four top-level tabs, never on detail or
 * flow screens, and the raised action always opens the import job.
 */
@Composable
fun PamNavHost(modifier: Modifier = Modifier) {
    val navController = rememberNavController()
    val gateViewModel: SessionGateViewModel = hiltViewModel()
    val gate by gateViewModel.state.collectAsStateWithLifecycle()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val destination = backStackEntry?.destination

    LaunchedEffect(gate, destination) {
        gateRedirect(gate, destination)?.let(navController::replaceWith)
    }

    Box(modifier = modifier.fillMaxSize()) {
        NavHost(
            navController = navController,
            startDestination = PamDestination.Welcome,
            modifier = Modifier.fillMaxSize(),
            enterTransition = {
                fadeIn(tween(durationMillis = 260)) +
                    slideInVertically(tween(durationMillis = 260)) { height -> height / 40 }
            },
            exitTransition = { fadeOut(tween(durationMillis = 160)) },
            popEnterTransition = {
                fadeIn(tween(durationMillis = 200)) +
                    slideInVertically(tween(durationMillis = 200)) { height -> -(height / 40) }
            },
            popExitTransition = { fadeOut(tween(durationMillis = 160)) },
        ) {
            composable<PamDestination.Welcome> {
                val welcome: WelcomeViewModel = hiltViewModel()
                WelcomeScreen(
                    onSignIn = { navController.navigate(PamDestination.Account(AccountMode.SIGN_IN)) },
                    onCreateAccount = { navController.navigate(PamDestination.Account(AccountMode.REGISTER)) },
                    onOpenPrivacy = { navController.navigate(PamDestination.Consent) },
                    showDemoNotice = welcome.showDemoNotice,
                )
            }

            composable<PamDestination.Account> {
                AccountScreen(
                    onBack = { if (!navController.popBackStack()) navController.replaceWith(PamDestination.Welcome) },
                )
            }

            composable<PamDestination.Consent> {
                ConsentScreen(
                    onFinished = {
                        if (!navController.popBackStack()) {
                            navController.replaceWith(PamDestination.Overview())
                        }
                    },
                )
            }

            composable<PamDestination.GmailConnect> {
                GmailConnectScreen(
                    onBack = { navController.popBackStack() },
                    onOpenConsent = { navController.navigate(PamDestination.Consent) },
                )
            }

            composable<PamDestination.Overview> {
                OverviewScreen(
                    onOpenSettings = { navController.navigateTab(PamDestination.Settings) },
                    onOpenTransactions = { categoryCode, monthId ->
                        navController.navigate(
                            PamDestination.Transactions(
                                categoryCode = categoryCode,
                                monthId = monthId,
                            ),
                        )
                    },
                    onOpenTransaction = { id ->
                        navController.navigate(PamDestination.TransactionDetail(id))
                    },
                    onOpenImport = { monthId -> navController.navigate(PamDestination.ImportJob(monthId = monthId)) },
                )
            }

            composable<PamDestination.Transactions> { entry ->
                val args = entry.toRoute<PamDestination.Transactions>()
                // A tab tap lands on the bare route; a drill-down carries a filter, and only the
                // drill-down gets a back well (C2-2).
                val openedAsDrillDown = args.monthId != null ||
                    args.categoryCode != null ||
                    args.dayIso != null
                TransactionsScreen(
                    onOpenTransaction = { id ->
                        navController.navigate(PamDestination.TransactionDetail(id))
                    },
                    onOpenManualEntry = { navController.navigate(PamDestination.ManualEntry()) },
                    onBack = if (openedAsDrillDown) {
                        { navController.popBackStack() }
                    } else {
                        null
                    },
                )
            }

            composable<PamDestination.TransactionDetail> {
                TransactionDetailScreen(onBack = { navController.popBackStack() })
            }

            composable<PamDestination.ManualEntry> {
                ManualEntryScreen(
                    onBack = { navController.popBackStack() },
                    onSaved = { navController.popBackStack() },
                )
            }

            composable<PamDestination.ImportJob> {
                ImportJobScreen(
                    onBack = { navController.popBackStack() },
                    onOpenGmail = { navController.navigate(PamDestination.GmailConnect) },
                )
            }

            composable<PamDestination.Analysis> {
                AnalysisScreen(
                    onOpenConsent = { navController.navigate(PamDestination.Consent) },
                )
            }

            composable<PamDestination.BudgetPlan> {
                BudgetPlanScreen(onBack = { navController.popBackStack() })
            }

            composable<PamDestination.Settings> {
                SettingsScreen(
                    onOpenGmail = { navController.navigate(PamDestination.GmailConnect) },
                    onOpenConsentHistory = { navController.navigate(PamDestination.ConsentHistory) },
                    onOpenBudget = { navController.navigate(PamDestination.BudgetPlan()) },
                    onOpenCategories = { navController.navigate(PamDestination.CategoryManagement) },
                    onOpenDeletion = { navController.navigate(PamDestination.DataDeletion) },
                    onOpenAdmin = { navController.navigate(PamDestination.Admin) },
                    onSignedOut = { navController.replaceWith(PamDestination.Welcome) },
                )
            }

            composable<PamDestination.CategoryManagement> {
                CategoryManagementScreen(onBack = { navController.popBackStack() })
            }

            composable<PamDestination.Admin> {
                AdminScreenRoot(onBack = { navController.popBackStack() })
            }

            composable<PamDestination.ConsentHistory> {
                ConsentHistoryScreen(onBack = { navController.popBackStack() })
            }

            composable<PamDestination.DataDeletion> {
                DataDeletionScreen(
                    onBack = { navController.popBackStack() },
                    onDeleted = { navController.replaceWith(PamDestination.Welcome) },
                )
            }
        }

        val showBottomBar = gate is GateState.Ready && destination.isTopLevel()
        if (showBottomBar) {
            BottomNavBar(
                items = bottomItems(destination),
                onSelect = { key -> navController.navigateTabFor(key) },
                onImport = { navController.navigate(PamDestination.ImportJob()) },
                importLabel = stringResource(R.string.overview_import_now),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .windowInsetsPadding(androidx.compose.foundation.layout.WindowInsets.navigationBars),
            )
        }
    }
}

@Composable
private fun bottomItems(destination: NavDestination?): List<BottomNavItem> = listOf(
    BottomNavItem(
        key = PamDestination.TAB_OVERVIEW,
        label = stringResource(R.string.nav_overview),
        icon = Icons.Rounded.Home,
        selected = destination.isOverview(),
    ),
    BottomNavItem(
        key = PamDestination.TAB_TRANSACTIONS,
        label = stringResource(R.string.nav_transactions),
        icon = Icons.Rounded.ReceiptLong,
        selected = destination.isTransactions(),
    ),
    BottomNavItem(
        key = PamDestination.TAB_ANALYSIS,
        label = stringResource(R.string.nav_analysis),
        icon = Icons.Rounded.AutoAwesome,
        selected = destination.isAnalysis(),
    ),
    BottomNavItem(
        key = PamDestination.TAB_SETTINGS,
        label = stringResource(R.string.nav_settings),
        icon = Icons.Rounded.Settings,
        selected = destination.isSettings(),
    ),
)

/**
 * The single gate decision: given the resolved gate and the destination currently on top, where
 * (if anywhere) the graph must move. Keeping it pure makes the "new profile stuck on Account" bug
 * testable without a Hilt graph (D.3).
 *
 * The rules are a closed set, not a list of exceptions per screen: a session without a recorded
 * Gmail decision may only be on Consent, and no session may only be on Welcome or Account. That is
 * why a screen added later is gated automatically instead of leaking through.
 */
internal fun gateRedirect(gate: GateState, destination: NavDestination?): PamDestination? = when (gate) {
    GateState.Loading -> null

    GateState.NeedsSignIn -> when {
        destination == null -> PamDestination.Welcome
        // The account screen is reachable before a profile exists: that is its purpose.
        destination.isAccount() -> null
        destination.isWelcome() -> null
        // Any other page is protected. This also covers a sign-out or session expiry that happens
        // while a protected screen is on top, so Back can never restore it.
        else -> PamDestination.Welcome
    }

    is GateState.NeedsConsent ->
        // A session exists but the Gmail decision does not: Consent is the only allowed page, so a
        // profile created on Account moves immediately instead of waiting for a Back press.
        if (destination == null || !destination.isConsent()) {
            PamDestination.Consent
        } else {
            null
        }

    is GateState.Ready ->
        // Sign-in/registration is done: leave the entry screens. Consent is deliberately NOT
        // included — with a saved decision it is still a reachable editor (grant/withdraw Gmail),
        // and bouncing it to Overview would make "change it later in Settings" impossible.
        if (destination == null || destination.isWelcome() || destination.isAccount()) {
            PamDestination.Overview()
        } else {
            null
        }
}

private fun NavDestination?.isTopLevel(): Boolean =
    isOverview() || isTransactions() || isAnalysis() || isSettings()


private fun NavDestination?.isAccount(): Boolean = this?.hasRoute<PamDestination.Account>() == true

private fun NavDestination?.isConsent(): Boolean = this?.hasRoute<PamDestination.Consent>() == true

private fun NavDestination?.isWelcome(): Boolean = this?.hasRoute<PamDestination.Welcome>() == true


private fun NavDestination?.isOverview(): Boolean = this?.hasRoute<PamDestination.Overview>() == true

private fun NavDestination?.isTransactions(): Boolean = this?.hasRoute<PamDestination.Transactions>() == true

private fun NavDestination?.isAnalysis(): Boolean = this?.hasRoute<PamDestination.Analysis>() == true

private fun NavDestination?.isSettings(): Boolean = this?.hasRoute<PamDestination.Settings>() == true

internal fun NavHostController.replaceWith(destination: PamDestination) {
    navigate(destination) {
        popUpTo(0) { inclusive = true }
        launchSingleTop = true
    }
}

private fun NavHostController.navigateTab(destination: PamDestination) {
    navigate(destination) {
        popUpTo(PamDestination.Overview()) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

private fun NavHostController.navigateTabFor(key: String) {
    when (key) {
        PamDestination.TAB_OVERVIEW -> navigateTab(PamDestination.Overview())
        PamDestination.TAB_TRANSACTIONS -> navigateTab(PamDestination.Transactions())
        PamDestination.TAB_ANALYSIS -> navigateTab(PamDestination.Analysis())
        PamDestination.TAB_SETTINGS -> navigateTab(PamDestination.Settings)
    }
}
