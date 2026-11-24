package com.pam.recap.ui.feature.overview

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.pam.recap.domain.model.Direction
import com.pam.recap.domain.model.Transaction
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
import com.pam.recap.ui.theme.PamTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * FR-32: the "Masuk"/"Keluar" cards on Ringkasan must open the rows that actually form those
 * totals, so the tap carries the direction (CREDIT/DEBIT) and the month — not an unfiltered list.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "id-rID-w411dp-h891dp-xxhdpi")
class OverviewScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun rows(): List<Transaction> = listOf(testTransaction("tx_credit"), testTransaction("tx_debit"))

    private fun viewModel(): OverviewViewModel {
        val transactions = FakeTransactionRepository(rows())
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
        )
    }

    private class Opened {
        var category: String? = "unset"
        var month: String? = null
        var direction: Direction? = null
    }

    private fun render(): Opened {
        val opened = Opened()
        val viewModel = viewModel()
        composeRule.setContent {
            PamTheme {
                OverviewScreen(
                    onOpenTransactions = { category, month, direction ->
                        opened.category = category
                        opened.month = month
                        opened.direction = direction
                    },
                    onOpenTransaction = {},
                    viewModel = viewModel,
                )
            }
        }
        composeRule.waitUntil(10_000) { viewModel.state.value.summary != null }
        composeRule.waitForIdle()
        return opened
    }

    @Test
    fun `the Masuk card opens the credit rows of the displayed month`() {
        val opened = render()

        composeRule.onNodeWithText("MASUK").performClick()
        composeRule.waitForIdle()

        assertEquals(Direction.CREDIT, opened.direction)
        assertEquals(testMonth.id(), opened.month)
        assertNull(opened.category)
    }

    @Test
    fun `the Keluar card opens the debit rows of the displayed month`() {
        val opened = render()

        composeRule.onNodeWithText("KELUAR").performClick()
        composeRule.waitForIdle()

        assertEquals(Direction.DEBIT, opened.direction)
        assertEquals(testMonth.id(), opened.month)
        assertNull(opened.category)
    }
}
