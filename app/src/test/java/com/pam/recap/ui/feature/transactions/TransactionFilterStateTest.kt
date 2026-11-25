package com.pam.recap.ui.feature.transactions

import androidx.lifecycle.SavedStateHandle
import com.pam.recap.domain.model.Direction
import com.pam.recap.domain.model.TransactionSource
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The filter strip is a projection of the screen's own state, so these tests pin two things: which
 * predicates count as "active", and when clearing the last one must leave a Ringkasan drill-down
 * instead of leaving an unfiltered list underneath a filtered route (C2-2, C2-6).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TransactionFilterStateTest {

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `only the predicates that actually narrow the list are chips`() {
        assertEquals(
            emptySet<ActiveTransactionFilter>(),
            activeTransactionFilters(direction = null, source = null, category = null, day = null),
        )
        assertEquals(
            setOf(
                ActiveTransactionFilter.DIRECTION,
                ActiveTransactionFilter.SOURCE,
                ActiveTransactionFilter.CATEGORY,
                ActiveTransactionFilter.DAY,
            ),
            activeTransactionFilters(
                direction = Direction.CREDIT,
                source = TransactionSource.MANUAL,
                category = com.pam.recap.domain.model.Category.FOOD,
                day = java.time.LocalDate.of(2026, 11, 19),
            ),
        )
    }

    @Test
    fun `clearing the last carried filter returns to Ringkasan`() {
        assertTrue(
            returnsToOverviewAfterClear(
                carried = setOf(ActiveTransactionFilter.DIRECTION),
                remaining = emptySet(),
            ),
        )
        // One of the carried filters is gone but another still narrows the list: stay.
        assertFalse(
            returnsToOverviewAfterClear(
                carried = setOf(ActiveTransactionFilter.DIRECTION, ActiveTransactionFilter.DAY),
                remaining = setOf(ActiveTransactionFilter.DAY),
            ),
        )
        // A filter added on the tab is not a drill-down: clearing it never navigates.
        assertFalse(returnsToOverviewAfterClear(carried = emptySet(), remaining = emptySet()))
    }

    @Test
    fun `the source chip narrows the loaded rows without changing the period`() = runTest {
        val rows = listOf(
            testTransaction("mail"),
            testTransaction("manual").copy(source = TransactionSource.MANUAL),
        )
        val vm = viewModel(rows = rows)

        withTimeout(5_000) { vm.state.first { it.month == testMonth && !it.isLoading } }

        vm.selectSource(TransactionSource.MANUAL)
        val manualOnly = withTimeout(5_000) { vm.state.first { it.source == TransactionSource.MANUAL } }
        assertEquals(listOf("manual"), manualOnly.visibleItems.map { it.id })
        assertTrue(manualOnly.isFiltered)
        // The month itself still holds both rows: this is a view predicate, not a new query.
        assertEquals(2, manualOnly.monthTransactionCount)

        vm.selectSource(TransactionSource.MANUAL)
        val cleared = withTimeout(5_000) { vm.state.first { it.source == null } }
        assertEquals(2, cleared.visibleItems.size)
    }

    @Test
    fun `carried route filters are remembered for the return decision`() = runTest {
        val vm = viewModel(
            handle = SavedStateHandle(
                mapOf(
                    "monthId" to testMonth.id(),
                    "direction" to "CREDIT",
                    "categoryCode" to "food",
                ),
            ),
        )
        assertEquals(
            setOf(ActiveTransactionFilter.DIRECTION, ActiveTransactionFilter.CATEGORY),
            vm.carriedFilters,
        )

        val tab = viewModel()
        assertTrue(tab.carriedFilters.isEmpty())
    }

    private fun viewModel(
        handle: SavedStateHandle = SavedStateHandle(),
        rows: List<com.pam.recap.domain.model.Transaction> = listOf(testTransaction("t1")),
    ): TransactionsViewModel {
        val repo = FakeTransactionRepository(rows)
        return TransactionsViewModel(
            savedStateHandle = handle,
            sessions = FakeSessionRepository(testSession()),
            transactions = repo,
            resolveInitialMonth = ResolveInitialMonthUseCase(repo, fixedClock(testNow)),
            activeMonth = ActiveMonthStore(),
            clock = fixedClock(testNow),
        )
    }
}
