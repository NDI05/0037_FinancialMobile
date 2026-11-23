package com.pam.recap.ui.feature.transactions

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.SavedStateHandle
import com.pam.recap.core.result.Outcome
import com.pam.recap.domain.repository.ServerTransactionPage
import com.pam.recap.domain.usecase.ResolveInitialMonthUseCase
import com.pam.recap.testing.FakeSessionRepository
import com.pam.recap.testing.FakeTransactionRepository
import com.pam.recap.testing.fixedClock
import com.pam.recap.ui.theme.PamTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Screen-level S2.4 checks: the load-more footer actually appends a page on click, and a truncated
 * server scan is stated on screen instead of being hidden.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "id-rID-w411dp-h891dp-xxhdpi")
class TransactionsPagingScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun viewModel(server: RecordingServerFinanceRepository) = TransactionsViewModel(
        savedStateHandle = SavedStateHandle(mapOf("monthId" to testMonth.id())),
        sessions = FakeSessionRepository(testSession()),
        transactions = FakeTransactionRepository(emptyList()),
        resolveInitialMonth = ResolveInitialMonthUseCase(
            transactions = FakeTransactionRepository(emptyList()),
            clock = fixedClock(testNow),
        ),
        server = server,
    )

    private fun setContent(vm: TransactionsViewModel) {
        composeRule.setContent {
            PamTheme {
                TransactionsScreen(
                    onOpenTransaction = {},
                    onOpenManualEntry = {},
                    viewModel = vm,
                )
            }
        }
    }

    @Test
    fun `the footer loads the next page and the appended row appears`() {
        val server = RecordingServerFinanceRepository()
        val firstPage = listOf(testTransaction("tx_a"), testTransaction("tx_b"))
        val secondPage = listOf(testTransaction("tx_c"))
        server.pageProvider = { call ->
            when (call.cursor) {
                null -> Outcome.Success(ServerTransactionPage(firstPage, nextCursor = "cursor_1"))
                else -> Outcome.Success(ServerTransactionPage(secondPage, nextCursor = null))
            }
        }
        val vm = viewModel(server)
        setContent(vm)

        composeRule.waitUntil(5_000) { vm.state.value.items.size == 2 }
        composeRule.onNodeWithText("Muat lebih banyak").assertIsDisplayed()
        composeRule.onNodeWithText("Muat lebih banyak").performClick()

        composeRule.waitUntil(5_000) { vm.state.value.items.size == 3 }
        composeRule.onNodeWithText("QRIS tx_c").assertIsDisplayed()
        composeRule.onNodeWithText("Muat lebih banyak").assertDoesNotExist()
    }

    @Test
    fun `a truncated server scan is stated on screen`() {
        val server = RecordingServerFinanceRepository()
        server.pageProvider = {
            Outcome.Success(
                ServerTransactionPage(
                    items = listOf(testTransaction("tx_a")),
                    nextCursor = null,
                    scanned = 999,
                    truncated = true,
                ),
            )
        }
        val vm = viewModel(server)
        setContent(vm)

        composeRule.waitUntil(5_000) { vm.state.value.truncated }
        composeRule
            .onNodeWithText("Server memeriksa 999 transaksi; hasil mungkin belum lengkap.")
            .assertIsDisplayed()
    }

    @Test
    fun `the searched coverage line states how many rows the server examined`() {
        val server = RecordingServerFinanceRepository()
        server.pageProvider = { call ->
            Outcome.Success(
                ServerTransactionPage(
                    items = listOf(testTransaction("tx_a", description = "KOPI")),
                    nextCursor = null,
                    scanned = 175,
                ),
            )
        }
        val vm = viewModel(server)
        setContent(vm)
        composeRule.waitUntil(5_000) { vm.state.value.items.size == 1 }

        vm.setQuery("kopi")
        composeRule.waitUntil(5_000) { vm.state.value.query == "kopi" && !vm.state.value.isRefreshing }
        composeRule.onNodeWithText("Server memeriksa 175 transaksi.").assertIsDisplayed()
    }
}
