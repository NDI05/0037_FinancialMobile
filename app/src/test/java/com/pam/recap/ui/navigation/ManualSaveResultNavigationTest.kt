package com.pam.recap.ui.navigation

import android.content.Context
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.ComposeNavigator
import androidx.navigation.compose.composable
import androidx.navigation.createGraph
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * FR-26 follow-up: saving a manual row must hand its month back to the list on the way out, so the
 * list can reload page one even when it missed the in-memory refresh event.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ManualSaveResultNavigationTest {

    private fun controller(): NavHostController {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return NavHostController(context).apply {
            setViewModelStore(ViewModelStore())
            navigatorProvider.addNavigator(ComposeNavigator())
            graph = createGraph(startDestination = PamDestination.Transactions()) {
                composable<PamDestination.Transactions> { }
                composable<PamDestination.ManualEntry> { }
            }
        }
    }

    @Test
    fun `saving publishes its month on the list entry and pops back to the list`() {
        val nav = controller()
        nav.navigate(PamDestination.ManualEntry())

        nav.publishManualSaveMonth("2026-11")
        assertTrue(nav.popBackStack())

        val entry = requireNotNull(nav.currentBackStackEntry)
        assertTrue(entry.destination.hasRoute<PamDestination.Transactions>())
        assertEquals("2026-11", entry.savedStateHandle.get<String>(MANUAL_SAVE_MONTH_KEY))
    }
}
