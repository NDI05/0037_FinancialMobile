package com.pam.recap.ui.feature.transactions

import androidx.lifecycle.SavedStateHandle
import com.pam.recap.domain.model.Direction
import com.pam.recap.domain.usecase.ResolveInitialMonthUseCase
import com.pam.recap.testing.FakeSessionRepository
import com.pam.recap.testing.FakeTransactionRepository
import com.pam.recap.testing.fixedClock
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * FR-32 drill-down: the direction carried by `PamDestination.Transactions` becomes a real filter,
 * so the Overview cards show exactly the rows that formed their totals and the state knows it is
 * filtered (the "no match" copy, not the "empty month" copy).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TransactionsDirectionFilterTest {

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel(handle: SavedStateHandle, rows: List<com.pam.recap.domain.model.Transaction>) =
        TransactionsViewModel(
            savedStateHandle = handle,
            sessions = FakeSessionRepository(testSession()),
            transactions = FakeTransactionRepository(rows),
            resolveInitialMonth = ResolveInitialMonthUseCase(
                transactions = FakeTransactionRepository(rows),
                clock = fixedClock(testNow),
            ),
            clock = fixedClock(testNow),
        )

    private val credit = testTransaction("tx_credit").copy(direction = Direction.CREDIT)
    private val debit = testTransaction("tx_debit").copy(direction = Direction.DEBIT)

    @Test
    fun `a credit drill-down lists only the month rows that form the Masuk total`() = runTest {
        val vm = viewModel(
            SavedStateHandle(mapOf("monthId" to testMonth.id(), "direction" to "CREDIT")),
            listOf(credit, debit),
        )

        val settled = withTimeout(5_000) {
            vm.state.first { !it.isLoading && it.items.isNotEmpty() }
        }

        assertEquals(listOf("tx_credit"), settled.items.map { it.id })
        assertEquals(Direction.CREDIT, settled.direction)
        assertEquals(testMonth, settled.month)
        assertTrue(settled.isFiltered)
    }

    @Test
    fun `a debit drill-down lists only the month rows that form the Keluar total`() = runTest {
        val vm = viewModel(
            SavedStateHandle(mapOf("monthId" to testMonth.id(), "direction" to "DEBIT")),
            listOf(credit, debit),
        )

        val settled = withTimeout(5_000) {
            vm.state.first { !it.isLoading && it.items.isNotEmpty() }
        }

        assertEquals(listOf("tx_debit"), settled.items.map { it.id })
        assertEquals(Direction.DEBIT, settled.direction)
    }

    @Test
    fun `an unknown direction argument is ignored instead of crashing the tab`() = runTest {
        val vm = viewModel(
            SavedStateHandle(mapOf("monthId" to testMonth.id(), "direction" to "SIDEWAYS")),
            listOf(credit, debit),
        )

        val settled = withTimeout(5_000) { vm.state.first { !it.isLoading && it.items.isNotEmpty() } }

        assertNull(settled.direction)
        assertEquals(listOf("tx_credit", "tx_debit"), settled.items.map { it.id })
    }

    @Test
    fun `the server call carries the direction when paging`() = runTest {
        val server = RecordingServerFinanceRepository()
        val vm = TransactionsViewModel(
            savedStateHandle = SavedStateHandle(mapOf("monthId" to testMonth.id(), "direction" to "CREDIT")),
            sessions = FakeSessionRepository(testSession()),
            transactions = FakeTransactionRepository(emptyList()),
            resolveInitialMonth = ResolveInitialMonthUseCase(
                transactions = FakeTransactionRepository(emptyList()),
                clock = fixedClock(testNow),
            ),
            clock = fixedClock(testNow),
            server = server,
        )

        withTimeout(5_000) { vm.state.first { it.month == testMonth } }

        assertEquals("CREDIT", server.searchCalls.first().direction)
    }
}
