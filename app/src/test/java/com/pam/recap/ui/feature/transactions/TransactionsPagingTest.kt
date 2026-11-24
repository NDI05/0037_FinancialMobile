package com.pam.recap.ui.feature.transactions

import androidx.lifecycle.SavedStateHandle
import com.pam.recap.core.result.AppError
import com.pam.recap.core.result.Outcome
import com.pam.recap.core.time.JakartaMonth
import com.pam.recap.domain.repository.ServerTransactionPage
import com.pam.recap.domain.usecase.ResolveInitialMonthUseCase
import com.pam.recap.testing.FakeSessionRepository
import com.pam.recap.testing.FakeTransactionRepository
import com.pam.recap.testing.fixedClock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * S2.4 server paging: a month is read page by page, the cursor chain is used exactly once per
 * generation, and a changed query/month must drop both the cursor and any late response.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TransactionsPagingTest {

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel(
        server: RecordingServerFinanceRepository,
        month: JakartaMonth = testMonth,
        sessions: FakeSessionRepository = FakeSessionRepository(testSession()),
    ) =
        TransactionsViewModel(
            savedStateHandle = SavedStateHandle(mapOf("monthId" to month.id())),
            sessions = sessions,
            transactions = FakeTransactionRepository(emptyList()),
            resolveInitialMonth = ResolveInitialMonthUseCase(
                transactions = FakeTransactionRepository(emptyList()),
                clock = fixedClock(testNow),
            ),
            clock = fixedClock(testNow),
            server = server,
        )

    private fun page(rows: List<com.pam.recap.domain.model.Transaction>, cursor: String?, scanned: Int = rows.size) =
        Outcome.Success(ServerTransactionPage(rows, nextCursor = cursor, scanned = scanned, truncated = false))

    private val pageOne = (0 until 50).map { testTransaction("tx_%03d".format(it)) }
    private val pageTwo = (50 until 100).map { testTransaction("tx_%03d".format(it)) }
    private val pageThree = (100 until 150).map { testTransaction("tx_%03d".format(it)) }

    @Test
    fun `three pages append in order without duplicates and stop when the cursor is null`() = runTest {
        val server = RecordingServerFinanceRepository()
        server.pageProvider = { call ->
            when (call.cursor) {
                null -> page(pageOne, "cursor_1")
                "cursor_1" -> page(pageTwo, "cursor_2")
                "cursor_2" -> page(pageThree, null)
                else -> Outcome.Failure(AppError.NotFound("cursor"))
            }
        }
        val vm = viewModel(server)

        vm.state.first { it.items.size == 50 && it.hasMore }
        vm.loadMore()
        vm.state.first { it.items.size == 100 && it.hasMore }
        vm.loadMore()
        val done = vm.state.first { it.items.size == 150 && !it.hasMore }

        // 150 rows is beyond the single-page / 100-row limit the old snapshot path had.
        assertEquals(150, done.items.map { it.id }.toSet().size)
        assertEquals(150, done.items.size)
        assertFalse(done.hasMore)
        assertEquals(listOf(null, "cursor_1", "cursor_2"), server.searchCalls.map { it.cursor })
        assertEquals(
            listOf(TRANSACTIONS_PAGE_SIZE, TRANSACTIONS_PAGE_SIZE, TRANSACTIONS_PAGE_SIZE),
            server.searchCalls.map { it.limit },
        )
        // The cursor response must not be mistaken for a full refresh: all 150 rows stay.
        assertEquals("tx_000", done.items.first().id)
    }

    @Test
    fun `a duplicate row on a later page is not appended twice`() = runTest {
        val server = RecordingServerFinanceRepository()
        server.pageProvider = { call ->
            when (call.cursor) {
                null -> page(pageOne, "cursor_1")
                // Overlap is possible when a row moves between pages; the id stays the identity.
                "cursor_1" -> page(listOf(pageOne.first()) + pageTwo.take(49), null)
                else -> Outcome.Failure(AppError.NotFound("cursor"))
            }
        }
        val vm = viewModel(server)

        vm.state.first { it.items.size == 50 }
        vm.loadMore()
        val done = vm.state.first { it.items.size == 99 && !it.hasMore }
        assertEquals(99, done.items.map { it.id }.toSet().size)
    }

    @Test
    fun `search uses the query and returns a match that only exists on page two`() = runTest {
        val pageTwoOnly = testTransaction("tx_075", description = "BAKSO PAK KUMIS")
        val server = RecordingServerFinanceRepository()
        server.pageProvider = { call ->
            if (call.query == "bakso") page(listOf(pageTwoOnly), null)
            else page(pageOne, "cursor_1")
        }
        val vm = viewModel(server)
        vm.state.first { it.items.size == 50 }

        vm.setQuery("bakso")
        val searched = vm.state.first { it.query == "bakso" && it.items.size == 1 && !it.isRefreshing }

        assertEquals("tx_075", searched.items.single().id)
        val call = server.searchCalls.last()
        assertEquals("bakso", call.query)
        assertEquals(TRANSACTIONS_PAGE_SIZE, call.limit)
        // A fresh search can never inherit the previous cursor: the hit lived past page one.
        assertEquals(null, call.cursor)
        assertTrue(searched.isFiltered)
    }

    @Test
    fun `changing the query mid-flight drops the previous cursor and the stale response`() = runTest {
        val firstArrived = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val server = RecordingServerFinanceRepository()
        server.suspendBeforeSearch = { call ->
            if (call.query == "first") {
                firstArrived.complete(Unit)
                releaseFirst.await()
            }
        }
        server.pageProvider = { call ->
            when (call.query) {
                "first" -> page(listOf(testTransaction("tx_first")), null)
                "second" -> page(listOf(testTransaction("tx_second")), null)
                else -> page(pageOne, "cursor_1")
            }
        }
        val vm = viewModel(server)
        vm.state.first { it.items.size == 50 }

        vm.setQuery("first")
        firstArrived.await()
        vm.setQuery("second")
        vm.state.first { it.query == "second" && it.items.singleOrNull()?.id == "tx_second" }

        // Let the abandoned generation answer; its result must not overwrite the new query.
        releaseFirst.complete(Unit)
        repeat(50) { delay(1) }

        assertEquals("second", vm.state.value.query)
        assertEquals(listOf("tx_second"), vm.state.value.items.map { it.id })
        // The new query restarted from the first page, not from the abandoned cursor.
        assertEquals(null, server.searchCalls.last().cursor)
    }

    @Test
    fun `changing the month resets the cursor and shows the new month's rows`() = runTest {
        // The retention window ends at the current month, so a shift back is the reachable move.
        val previous = JakartaMonth.parse("2026-10")
        val october = kotlin.time.Instant.parse("2026-10-05T03:00:00Z")
        val server = RecordingServerFinanceRepository()
        server.pageProvider = { call ->
            if (call.month == previous) {
                page(listOf(testTransaction("tx_oct").copy(eventAt = october)), null)
            } else {
                page(pageOne, "cursor_1")
            }
        }
        val vm = viewModel(server)
        vm.state.first { it.items.size == 50 }

        vm.previousMonth()
        val state = vm.state.first { it.month == previous && it.items.singleOrNull()?.id == "tx_oct" }

        assertEquals(null, server.searchCalls.last().cursor)
        assertEquals(listOf("tx_oct"), state.items.map { it.id })
        assertFalse(state.hasMore)
    }

    @Test
    fun `a refresh keeps the previous rows visible instead of blanking the list`() = runTest {
        val block = CompletableDeferred<Unit>()
        var allowBlock = false
        val server = RecordingServerFinanceRepository()
        server.pageProvider = { page(pageOne, null) }
        val vm = viewModel(server)
        vm.state.first { it.items.size == 50 }

        server.suspendBeforeSearch = { if (allowBlock) block.await() }
        allowBlock = true
        vm.retry()

        // Synchronously after the refresh trigger the old rows are still on screen.
        assertEquals(50, vm.state.value.items.size)
        assertFalse(vm.state.value.isLoading)
        assertTrue(vm.state.value.isRefreshing)

        block.complete(Unit)
        val settled = vm.state.first { !it.isRefreshing }
        assertEquals(50, settled.items.size)
        assertEquals(null, settled.error)
    }

    @Test
    fun `a failing next page keeps the loaded rows and retries the same cursor`() = runTest {
        var failOnce = true
        val server = RecordingServerFinanceRepository()
        server.pageProvider = { call ->
            when (call.cursor) {
                null -> page(pageOne, "cursor_1")
                "cursor_1" -> if (failOnce) {
                    failOnce = false
                    Outcome.Failure(AppError.Network("offline"))
                } else {
                    page(pageTwo, null)
                }
                else -> Outcome.Failure(AppError.NotFound("cursor"))
            }
        }
        val vm = viewModel(server)
        vm.state.first { it.items.size == 50 }

        vm.loadMore()
        val failed = vm.state.first { it.loadMoreError != null }
        assertTrue(failed.loadMoreError is AppError.Network)
        assertEquals(50, failed.items.size)
        assertFalse(failed.isLoadingMore)
        assertTrue(failed.hasMore)

        vm.retryLoadMore()
        val recovered = vm.state.first { it.items.size == 100 && it.loadMoreError == null }
        assertEquals(100, recovered.items.map { it.id }.toSet().size)
        assertEquals(
            listOf(null, "cursor_1", "cursor_1"),
            server.searchCalls.map { it.cursor },
        )
    }

    @Test
    fun `the scanned and truncated coverage fields are exposed to the screen`() = runTest {
        val server = RecordingServerFinanceRepository()
        server.pageProvider = {
            Outcome.Success(
                ServerTransactionPage(
                    items = pageOne,
                    nextCursor = null,
                    scanned = 4_000,
                    truncated = true,
                ),
            )
        }
        val vm = viewModel(server)

        val state = vm.state.first { it.items.size == 50 && !it.isLoading }
        assertEquals(4_000, state.scanned)
        assertTrue(state.truncated)
        assertNotNull(state.items)
    }

    @Test
    fun `switching owner drops the previous rows and restarts at page one`() = runTest {
        val sessions = FakeSessionRepository(testSession("u_owner"))
        val newOwnerArrived = CompletableDeferred<Unit>()
        val releaseNewOwner = CompletableDeferred<Unit>()
        val server = RecordingServerFinanceRepository()
        server.suspendBeforeSearch = { call ->
            if (call.ownerId == "u_other") {
                newOwnerArrived.complete(Unit)
                releaseNewOwner.await()
            }
        }
        server.pageProvider = { call ->
            if (call.ownerId == "u_other") {
                page(listOf(testTransaction("tx_other").copy(ownerId = "u_other")), null)
            } else {
                page(pageOne, "cursor_1")
            }
        }
        val vm = viewModel(server, sessions = sessions)
        vm.state.first { it.items.size == 50 }

        sessions.signIn(testSession("u_other"))
        newOwnerArrived.await()

        // While the new owner's first page is loading, no row from the previous owner is visible.
        assertTrue(vm.state.value.items.isEmpty())
        assertTrue(vm.state.value.isLoading)

        releaseNewOwner.complete(Unit)
        val state = vm.state.first { it.items.singleOrNull()?.id == "tx_other" }

        assertEquals(listOf("tx_other"), state.items.map { it.id })
        assertEquals("u_other", server.searchCalls.last().ownerId)
        // The old cursor belonged to the previous owner and must not be reused.
        assertEquals(null, server.searchCalls.last().cursor)
    }

    @Test
    fun `a search with no hits reports no-match instead of an empty month`() = runTest {
        val server = RecordingServerFinanceRepository()
        server.pageProvider = { call ->
            if (call.query.isBlank()) page(pageOne, "cursor_1")
            else Outcome.Success(ServerTransactionPage(emptyList(), nextCursor = null))
        }
        val vm = viewModel(server)
        vm.state.first { it.items.size == 50 }

        vm.setQuery("tidak-ada")
        val state = vm.state.first { it.query == "tidak-ada" && it.items.isEmpty() && !it.isLoading }

        assertTrue(state.isNoMatch)
        assertFalse(state.isEmptyMonth)
        assertTrue(state.isFiltered)
        assertEquals("tidak-ada", server.searchCalls.last().query)
    }
}
