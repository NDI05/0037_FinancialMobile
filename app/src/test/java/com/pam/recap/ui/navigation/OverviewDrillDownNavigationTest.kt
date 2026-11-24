package com.pam.recap.ui.navigation

import android.content.Context
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.ComposeNavigator
import androidx.navigation.compose.composable
import androidx.navigation.createGraph
import androidx.navigation.toRoute
import androidx.test.core.app.ApplicationProvider
import com.pam.recap.domain.model.Direction
import com.pam.recap.ui.feature.transactions.testMonth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * FR-32 destination contract: Overview "Masuk"/"Keluar" open the single Transactions destination
 * with the direction (and month) as query arguments, and Back returns to Overview because such a
 * route counts as a drill-down.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OverviewDrillDownNavigationTest {

    private fun controller(): NavHostController {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return NavHostController(context).apply {
            navigatorProvider.addNavigator(ComposeNavigator())
            graph = createGraph(startDestination = PamDestination.Overview()) {
                composable<PamDestination.Overview> { }
                composable<PamDestination.Transactions> { }
            }
        }
    }

    private fun NavHostController.currentRoute(): PamDestination.Transactions? =
        currentBackStackEntry?.toRoute<PamDestination.Transactions>()

    @Test
    fun `the Masuk card route carries CREDIT and the month, and Back returns to Overview`() {
        val nav = controller()
        nav.navigate(
            PamDestination.Transactions(
                monthId = testMonth.id(),
                direction = Direction.CREDIT.name,
            ),
        )

        val route = requireNotNull(nav.currentRoute())
        assertEquals(testMonth.id(), route.monthId)
        assertEquals("CREDIT", route.direction)
        assertTrue(route.isDrillDown())

        nav.popBackStack()

        assertEquals(
            "back returns to Ringkasan",
            true,
            nav.currentBackStackEntry?.destination?.hasRoute<PamDestination.Overview>(),
        )
    }

    @Test
    fun `the Keluar card route carries DEBIT`() {
        val nav = controller()
        nav.navigate(
            PamDestination.Transactions(
                monthId = testMonth.id(),
                direction = Direction.DEBIT.name,
            ),
        )

        assertEquals("DEBIT", requireNotNull(nav.currentRoute()).direction)
    }

    @Test
    fun `the bare tab route is not a drill-down so it has no back well`() {
        assertFalse(PamDestination.Transactions().isDrillDown())
        // A category shortcut from the composition card is still a drill-down.
        assertTrue(PamDestination.Transactions(categoryCode = "food").isDrillDown())
    }
}
