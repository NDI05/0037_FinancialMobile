package com.pam.recap.ui.feature.transactions

import androidx.lifecycle.SavedStateHandle
import com.pam.recap.core.result.Outcome
import com.pam.recap.core.time.JakartaMonth
import com.pam.recap.domain.repository.FinanceRefreshBus
import com.pam.recap.domain.repository.ServerTransactionPage
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
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.time.Instant

/**
 * FR-26 regression: a manual save publishes [FinanceRefreshBus] for its month, so the list must
 * re-read page 1 and show the new row without the owner switching months.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TransactionsManualRefreshTest {

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    private val month: JakartaMonth = testMonth

    private fun viewModel(server: RecordingServerFinanceRepository) =
        TransactionsViewModel(
            savedStateHandle = SavedStateHandle(mapOf("monthId" to month.id())),
            sessions = FakeSessionRepository(testSession()),
            transactions = FakeTransactionRepository(emptyList()),
            resolveInitialMonth = ResolveInitialMonthUseCase(
                transactions = FakeTransactionRepository(emptyList()),
                clock = fixedClock(testNow),
            ),
            clock = fixedClock(testNow),
            server = server,
        )

    @Test
    fun `manual save event reloads page one and shows the new row once`() = runTest {
        val server = RecordingServerFinanceRepository()
        val before = listOf("tx_a", "tx_b").map { testTransaction(it) }
        server.pageProvider = {
            Outcome.Success(ServerTransactionPage(before, nextCursor = null))
        }
        val vm = viewModel(server)
        vm.state.first { it.items.size == 2 }

        // What the server returns after the POST lands: the manual row sorts first by event time.
        val created = testTransaction("tx_manual", description = "UjiRefresh").copy(
            eventAt = Instant.parse("2026-11-24T05:00:00Z"),
        )
        server.pageProvider = {
            Outcome.Success(ServerTransactionPage(listOf(created) + before, nextCursor = null))
        }
        FinanceRefreshBus.notify("u_owner", month.id())

        val refreshed = vm.state.first { it.items.any { row -> row.id == "tx_manual" } }

        assertEquals(3, refreshed.items.size)
        assertEquals(1, refreshed.items.count { it.id == "tx_manual" })
        assertEquals("tx_manual", refreshed.items.first().id)
        // Two page-1 reads: the initial load and exactly one refresh, both without a cursor.
        assertEquals(listOf(null, null), server.searchCalls.map { it.cursor })
        assertTrue(server.searchCalls.all { it.limit == TRANSACTIONS_PAGE_SIZE })
    }

    @Test
    fun `the navigation result of a manual save reloads page one with the new row once`() = runTest {
        val server = RecordingServerFinanceRepository()
        val before = listOf("tx_a", "tx_b").map { testTransaction(it) }
        server.pageProvider = {
            Outcome.Success(ServerTransactionPage(before, nextCursor = null))
        }
        val vm = viewModel(server)
        vm.state.first { it.items.size == 2 }

        val created = testTransaction("tx_manual", description = "UjiRefresh").copy(
            eventAt = Instant.parse("2026-11-24T05:00:00Z"),
        )
        server.pageProvider = {
            Outcome.Success(ServerTransactionPage(listOf(created) + before, nextCursor = null))
        }
        // What the nav layer hands back after the form pops: the saved row's month.
        vm.refreshAfterSave(month.id())

        val refreshed = vm.state.first { it.items.any { row -> row.id == "tx_manual" } }

        assertEquals(3, refreshed.items.size)
        assertEquals(1, refreshed.items.count { it.id == "tx_manual" })
        assertEquals("tx_manual", refreshed.items.first().id)
        assertEquals(listOf(null, null), server.searchCalls.map { it.cursor })
    }

    @Test
    fun `a navigation result for another month is a no-op`() = runTest {
        val server = RecordingServerFinanceRepository()
        server.pageProvider = {
            Outcome.Success(ServerTransactionPage(listOf(testTransaction("tx_a")), nextCursor = null))
        }
        val vm = viewModel(server)
        vm.state.first { it.items.size == 1 }

        vm.refreshAfterSave("2026-10")
        kotlinx.coroutines.yield()

        assertEquals(1, server.searchCalls.size)
        assertEquals(1, vm.state.value.items.size)
    }

    @Test
    fun `a manual save for another month does not refetch this month`() = runTest {
        val server = RecordingServerFinanceRepository()
        server.pageProvider = {
            Outcome.Success(ServerTransactionPage(listOf(testTransaction("tx_a")), nextCursor = null))
        }
        val vm = viewModel(server)
        vm.state.first { it.items.size == 1 }

        FinanceRefreshBus.notify("u_owner", "2026-10")
        kotlinx.coroutines.yield()

        assertEquals(1, server.searchCalls.size)
        assertEquals(1, vm.state.value.items.size)
    }

    @Test
    fun `a manual save for another owner does not refetch this month`() = runTest {
        val server = RecordingServerFinanceRepository()
        server.pageProvider = {
            Outcome.Success(ServerTransactionPage(listOf(testTransaction("tx_a")), nextCursor = null))
        }
        val vm = viewModel(server)
        vm.state.first { it.items.size == 1 }

        FinanceRefreshBus.notify("u_other", month.id())
        kotlinx.coroutines.yield()

        assertEquals(1, server.searchCalls.size)
        assertEquals(1, vm.state.value.items.size)
    }
}
