package com.pam.recap.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Bottom-bar navigation contract (S2.P0, UAT 24 Nov 2026).
 *
 * Regression under test: every tab tap used to navigate with
 * `popUpTo(Overview) { saveState = true }` + `restoreState = true`. The tab route and the
 * drill-down route share one destination pattern (`Transactions` with optional arguments), so the
 * saved state of a filtered drill-down could be restored for a bare tab tap: tapping "Ringkasan"
 * from that restored stack stayed on the Transaksi list instead of going home.
 *
 * A top-level tap must therefore be a fresh move: pop back to Overview (keeping it), reuse the
 * existing entry (single top), and never ask Navigation to restore saved drill-down state.
 */
class TabNavigationSpecTest {

    @Test
    fun `every tab pops back to Overview and reuses its entry`() {
        val keys = listOf(
            PamDestination.TAB_OVERVIEW,
            PamDestination.TAB_TRANSACTIONS,
            PamDestination.TAB_ANALYSIS,
            PamDestination.TAB_SETTINGS,
        )
        keys.forEach { key ->
            val target = requireNotNull(tabTargetFor(key)) { "no target for $key" }
            val spec = tabNavigationSpec(target)
            assertEquals("$key must pop back to the overview shell", PamDestination.Overview(), spec.popUpTo)
            assertFalse("$key must keep the Overview entry", spec.popUpToInclusive)
            assertTrue("$key must not push a duplicate top-level entry", spec.launchSingleTop)
        }
    }

    /**
     * The spec is deliberately the only thing navigateTab reads: it has no `saveState` /
     * `restoreState` fields, so a drill-down's saved back stack can never be restored by a tab tap.
     * This test pins that shape — adding such a field back would require changing this class, which
     * is the tripwire the original bug lacked.
     */
    @Test
    fun `tab navigation never carries saved drill-down state`() {
        val spec = tabNavigationSpec(PamDestination.Transactions())
        assertEquals(
            "tab navigation must stay stateless: no saveState/restoreState inputs exist",
            listOf(PamDestination.Overview(), false, true),
            listOf(spec.popUpTo, spec.popUpToInclusive, spec.launchSingleTop),
        )
    }

    @Test
    fun `tab keys map to their own top-level destinations`() {
        assertEquals(PamDestination.Overview(), tabTargetFor(PamDestination.TAB_OVERVIEW))
        assertEquals(PamDestination.Transactions(), tabTargetFor(PamDestination.TAB_TRANSACTIONS))
        assertEquals(PamDestination.Analysis(), tabTargetFor(PamDestination.TAB_ANALYSIS))
        assertEquals(PamDestination.Settings, tabTargetFor(PamDestination.TAB_SETTINGS))
        assertNull(tabTargetFor("unknown"))
    }
}
