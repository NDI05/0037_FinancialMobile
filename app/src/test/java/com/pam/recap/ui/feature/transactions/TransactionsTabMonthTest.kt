package com.pam.recap.ui.feature.transactions

import androidx.lifecycle.SavedStateHandle
import com.pam.recap.domain.model.Category
import com.pam.recap.domain.usecase.ResolveInitialMonthUseCase
import com.pam.recap.testing.FakeSessionRepository
import com.pam.recap.testing.FakeTransactionRepository
import com.pam.recap.testing.fixedClock
import com.pam.recap.ui.selection.ActiveMonthStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * Period and filter state for the Transaksi tab (S2.P0, UAT 24 Nov 2026).
 *
 * The owner reported an empty list when opening the tab and wondered which month it represented.
 * Two causes are pinned here: the tab used to read a session snapshot before sign-in had finished
 * (month stayed null, so no list was ever queried), and it had no shared month with Ringkasan.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TransactionsTabMonthTest {

    private lateinit var store: ActiveMonthStore

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        store = ActiveMonthStore()
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private val previousMonth = testMonth.previous()

    private fun viewModel(
        handle: SavedStateHandle = SavedStateHandle(),
        sessions: FakeSessionRepository = FakeSessionRepository(testSession()),
        rows: List<com.pam.recap.domain.model.Transaction> = listOf(testTransaction("t1")),
    ): TransactionsViewModel {
        val repo = FakeTransactionRepository(rows)
        return TransactionsViewModel(
            savedStateHandle = handle,
            sessions = sessions,
            transactions = repo,
            resolveInitialMonth = ResolveInitialMonthUseCase(repo, fixedClock(testNow)),
            activeMonth = store,
        )
    }

    @Test
    fun `the tab opens on the month the owner was already viewing on Ringkasan`() = runTest {
        store.select("u_owner", previousMonth)

        val vm = viewModel()

        val settled = withTimeout(5_000) { vm.state.first { it.month == previousMonth && !it.isLoading } }
        assertEquals(previousMonth, settled.month)
    }

    @Test
    fun `a drill-down keeps its explicit month instead of the remembered one`() = runTest {
        store.select("u_owner", previousMonth)

        val vm = viewModel(SavedStateHandle(mapOf("monthId" to testMonth.id())))

        val settled = withTimeout(5_000) { vm.state.first { it.month == testMonth } }
        assertEquals(testMonth, settled.month)
    }

    @Test
    fun `a cold start waits for the session before resolving the month`() = runTest {
        val sessions = FakeSessionRepository(null)
        val vm = viewModel(sessions = sessions, rows = emptyList())

        // Before sign-in there is no owner, so no month may be invented.
        assertNull(vm.state.value.month)

        sessions.signIn(testSession())

        val settled = withTimeout(5_000) { vm.state.first { it.month != null } }
        assertEquals(testMonth, settled.month)
    }

    @Test
    fun `a month shifted on this screen is published for the other tabs`() = runTest {
        val vm = viewModel()
        withTimeout(5_000) { vm.state.first { it.month != null } }

        vm.previousMonth()

        assertEquals(previousMonth, store.current("u_owner"))
        val settled = withTimeout(5_000) { vm.state.first { it.month == previousMonth } }
        assertEquals(previousMonth, settled.month)
    }

    @Test
    fun `a category shortcut stays an explicit removable filter and never leaks into the tab`() = runTest {
        val drillDown = viewModel(
            SavedStateHandle(mapOf("monthId" to testMonth.id(), "categoryCode" to Category.FOOD.code)),
        )
        val filtered = withTimeout(5_000) { drillDown.state.first { it.category != null } }
        assertEquals(Category.FOOD, filtered.category)
        assertEquals(testMonth, filtered.month)

        // The bare tab is a different entry: no category arrives unless the owner picks one.
        val tab = viewModel()
        val plain = withTimeout(5_000) { tab.state.first { it.month != null && !it.isLoading } }
        assertNull(plain.category)
        assertNull(plain.selectedDay)
        assertEquals("", plain.query)
    }
}
