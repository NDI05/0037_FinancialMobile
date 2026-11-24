package com.pam.recap.ui.feature.overview

import com.pam.recap.core.time.RetentionWindow
import com.pam.recap.domain.usecase.ComputeMonthlySummaryUseCase
import com.pam.recap.domain.usecase.ResolveInitialMonthUseCase
import com.pam.recap.testing.FakeGmailConnectionRepository
import com.pam.recap.testing.FakeImportRepository
import com.pam.recap.testing.FakeSessionRepository
import com.pam.recap.testing.FakeSummaryRepository
import com.pam.recap.testing.FakeTransactionRepository
import com.pam.recap.testing.fixedClock
import com.pam.recap.ui.feature.transactions.testMonth
import com.pam.recap.ui.feature.transactions.testNow
import com.pam.recap.ui.feature.transactions.testSession
import com.pam.recap.ui.feature.transactions.testTransaction
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
 * NFR-15: the period picker on Ringkasan may not advance past the current month, and its backward
 * limit is the same rolling one-year window the rest of the app uses — never a second, looser rule.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OverviewMonthWindowTest {

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel(rows: List<com.pam.recap.domain.model.Transaction> = listOf(testTransaction("t1"))): OverviewViewModel {
        val transactions = FakeTransactionRepository(rows)
        val connections = FakeGmailConnectionRepository()
        return OverviewViewModel(
            sessions = FakeSessionRepository(testSession()),
            transactions = transactions,
            connections = connections,
            computeSummary = ComputeMonthlySummaryUseCase(
                transactions = transactions,
                summaries = FakeSummaryRepository(),
                imports = FakeImportRepository(),
                gmailConnection = connections,
                clock = fixedClock(testNow),
            ),
            resolveInitialMonth = ResolveInitialMonthUseCase(transactions, fixedClock(testNow)),
            clock = fixedClock(testNow),
        )
    }

    @Test
    fun `the forward arrow does not leave the current month`() = runTest {
        val vm = viewModel()
        withTimeout(5_000) { vm.state.first { it.month == testMonth } }

        vm.nextMonth()

        assertEquals(testMonth, vm.state.value.month)
        assertFalse(vm.window().canGoLater(testMonth))
    }

    @Test
    fun `the back arrow stops at the oldest month of the rolling year`() = runTest {
        val oldest = RetentionWindow.of(fixedClock(testNow).now()).oldest
        val vm = viewModel()
        withTimeout(5_000) { vm.state.first { it.month == testMonth } }

        repeat(24) { vm.previousMonth() }

        assertEquals(oldest, vm.state.value.month)
        assertFalse(vm.window().canGoEarlier(oldest))
        assertTrue(vm.window().canGoLater(oldest))
    }
}
