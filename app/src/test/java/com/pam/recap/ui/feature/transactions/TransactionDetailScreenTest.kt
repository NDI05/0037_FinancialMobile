package com.pam.recap.ui.feature.transactions

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.SavedStateHandle
import com.pam.recap.core.result.AppError
import com.pam.recap.core.result.Outcome
import com.pam.recap.domain.usecase.LinkRefundUseCase
import com.pam.recap.domain.usecase.MarkInternalTransferUseCase
import com.pam.recap.domain.usecase.UpdateTransactionCategoryUseCase
import com.pam.recap.domain.usecase.UpdateTransactionNoteUseCase
import com.pam.recap.testing.FakeAnalysisRepository
import com.pam.recap.testing.FakeSessionRepository
import com.pam.recap.testing.assertEveryClickableControlIsLabeled
import com.pam.recap.testing.FakeSummaryRepository
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
 * Screen-level S2.4 detail checks: the edit form and delete affordance exist in server mode, delete
 * waits for the confirmation sheet, and a 409 shows the "changed elsewhere" message.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "id-rID-w411dp-h891dp-xxhdpi")
class TransactionDetailScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val row = testTransaction("tx_1", description = "QRIS", merchantLabel = "BAKSO UJI")

    private fun viewModel(server: RecordingServerFinanceRepository?): TransactionDetailViewModel {
        val transactions = FakeTransactionRepository(listOf(row))
        return TransactionDetailViewModel(
            savedStateHandle = SavedStateHandle(mapOf("transactionId" to "tx_1")),
            sessions = FakeSessionRepository(testSession()),
            transactions = transactions,
            updateCategory = UpdateTransactionCategoryUseCase(
                transactions = transactions,
                summaries = FakeSummaryRepository(),
                analyses = FakeAnalysisRepository(),
                clock = fixedClock(testNow),
            ),
            markTransfer = MarkInternalTransferUseCase(
                transactions = transactions,
                summaries = FakeSummaryRepository(),
                analyses = FakeAnalysisRepository(),
                clock = fixedClock(testNow),
            ),
            linkRefund = LinkRefundUseCase(
                transactions = transactions,
                summaries = FakeSummaryRepository(),
                analyses = FakeAnalysisRepository(),
                clock = fixedClock(testNow),
            ),
            updateNote = UpdateTransactionNoteUseCase(transactions, fixedClock(testNow)),
            server = server,
        )
    }

    private fun setContent(vm: TransactionDetailViewModel) {
        composeRule.setContent {
            PamTheme {
                TransactionDetailScreen(onBack = {}, viewModel = vm, attachments = {})
            }
        }
    }

    /** Waits until the (animated) sheet content is composed and the clock has settled. */
    private fun awaitText(text: String) {
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.waitForIdle()
    }

    @Test
    fun `every clickable control exposes an accessible label`() {
        val server = RecordingServerFinanceRepository().apply { transactionRow = row }
        val vm = viewModel(server)
        setContent(vm)
        composeRule.waitUntil(5_000) { vm.state.value.metadataEditable }

        composeRule.assertEveryClickableControlIsLabeled("Detail transaksi")
    }

    @Test
    fun `delete asks for confirmation before anything is removed`() {
        val server = RecordingServerFinanceRepository().apply { transactionRow = row }
        val vm = viewModel(server)
        setContent(vm)
        composeRule.waitUntil(5_000) { vm.state.value.metadataEditable }

        // The dynamic-fields editor also has a "Hapus" remove button; the delete affordance is last.
        composeRule.onAllNodesWithText("Hapus").onLast().performScrollTo().performClick()

        awaitText("Hapus transaksi ini?")
        // Dialog content lives in its own window; assert the sheet text is in the semantics tree.
        composeRule.onNodeWithText("Hapus transaksi ini?").assertExists()
        // Still nothing sent: the sheet is the gate.
        org.junit.Assert.assertTrue(server.deleteCalls.isEmpty())

        composeRule.onNodeWithText("Batal").performClick()
        composeRule.waitUntil(5_000) { !vm.state.value.deleteConfirmation }
        org.junit.Assert.assertTrue(server.deleteCalls.isEmpty())
    }

    @Test
    fun `the conflict message appears and the draft is still on screen`() {
        val server = RecordingServerFinanceRepository().apply {
            transactionRow = row
            updateOutcome = Outcome.Failure(AppError.Conflict("revision_conflict"))
        }
        val vm = viewModel(server)
        setContent(vm)
        composeRule.waitUntil(5_000) { vm.state.value.metadataEditable }

        vm.setDescription("Draft saya")
        composeRule.waitUntil(5_000) { vm.state.value.hasUnsavedChanges }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Simpan").performScrollTo().performClick()

        composeRule.waitUntil(5_000) { vm.state.value.conflict }
        composeRule
            .onNodeWithText(
                "Transaksi ini sudah diubah di tempat lain. Data terbaru sudah dimuat; " +
                    "periksa lagi sebelum menyimpan.",
            )
            .performScrollTo()
            .assertIsDisplayed()
        // The typed value was not thrown away by the reload.
        composeRule.onNodeWithText("Draft saya").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `stored attributes are visible and an invalid row shows the rule copy`() {
        val server = RecordingServerFinanceRepository().apply {
            transactionRow = row.copy(attributes = mapOf("sumber" to "kamera"))
        }
        val vm = viewModel(server)
        setContent(vm)
        composeRule.waitUntil(5_000) { vm.state.value.metadataEditable }

        composeRule.onNodeWithText("kamera").performScrollTo().assertIsDisplayed()

        vm.setAttributeFields(
            listOf(com.pam.recap.ui.components.DynamicField("sama", "a"),
                com.pam.recap.ui.components.DynamicField("sama", "b")),
        )
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Simpan").performScrollTo().performClick()

        composeRule.waitUntil(5_000) { vm.state.value.attributeRule != null }
        composeRule.onNodeWithText("Nama kolom tidak boleh sama.")
            .performScrollTo()
            .assertIsDisplayed()
        org.junit.Assert.assertTrue(server.updateCalls.isEmpty())
    }
}
