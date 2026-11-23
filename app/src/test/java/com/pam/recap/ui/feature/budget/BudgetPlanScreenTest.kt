package com.pam.recap.ui.feature.budget

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
import com.pam.recap.domain.model.Category
import com.pam.recap.domain.model.CoverageStatus
import com.pam.recap.domain.model.MonthlySummary
import com.pam.recap.domain.model.SummaryFreshness
import com.pam.recap.domain.repository.ServerBudgetPlan
import com.pam.recap.domain.usecase.ComparePlanToActualUseCase
import com.pam.recap.domain.usecase.ComputeMonthlySummaryUseCase
import com.pam.recap.domain.usecase.ResolveInitialMonthUseCase
import com.pam.recap.domain.usecase.SaveBudgetPlanUseCase
import com.pam.recap.domain.usecase.SuggestBudgetPlanUseCase
import com.pam.recap.testing.FakeBudgetRepository
import com.pam.recap.testing.FakeGmailConnectionRepository
import com.pam.recap.testing.FakeImportRepository
import com.pam.recap.testing.FakeSessionRepository
import com.pam.recap.testing.FakeSummaryRepository
import com.pam.recap.testing.FakeTransactionRepository
import com.pam.recap.testing.fixedClock
import com.pam.recap.ui.feature.transactions.RecordingServerFinanceRepository
import com.pam.recap.ui.feature.transactions.testMonth
import com.pam.recap.ui.feature.transactions.testNow
import com.pam.recap.ui.feature.transactions.testSession
import com.pam.recap.ui.theme.PamTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * S2.5 screen checks: the plan says it came from the server, the summary coverage/freshness is
 * visible, and a conflict offers a reload instead of silently discarding the draft.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "id-rID-w411dp-h891dp-xxhdpi")
class BudgetPlanScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val transactions = FakeTransactionRepository(emptyList())
    private val summaries = FakeSummaryRepository()
    private val imports = FakeImportRepository()
    private val connections = FakeGmailConnectionRepository()
    private val budgets = FakeBudgetRepository()
    private val clock = fixedClock(testNow)
    private val computeSummary = ComputeMonthlySummaryUseCase(
        transactions,
        summaries,
        imports,
        connections,
        clock,
    )

    private val storedPlan = ServerBudgetPlan(
        monthId = testMonth.id(),
        expectedIncomeMinor = 5_000_000,
        plannedSavingsMinor = 1_000_000,
        limitsByCategoryId = mapOf("cat_food" to 800_000L),
        gapMinor = 3_200_000,
        revision = 2,
    )

    private fun viewModel(server: RecordingServerFinanceRepository) = BudgetPlanViewModel(
        savedStateHandle = SavedStateHandle(mapOf("monthId" to testMonth.id())),
        sessions = FakeSessionRepository(testSession()),
        budgets = budgets,
        suggestPlan = SuggestBudgetPlanUseCase(computeSummary, clock),
        savePlan = SaveBudgetPlanUseCase(budgets, clock),
        comparePlan = ComparePlanToActualUseCase(budgets, computeSummary),
        resolveInitialMonth = ResolveInitialMonthUseCase(transactions, clock),
        clock = clock,
        server = server,
    )

    private fun setContent(vm: BudgetPlanViewModel) {
        composeRule.setContent {
            PamTheme {
                BudgetPlanScreen(onBack = {}, viewModel = vm)
            }
        }
    }

    private fun summary() = MonthlySummary.empty(
        ownerId = "u_owner",
        month = testMonth,
        mailboxLabel = "andhika@example.test",
        coverage = CoverageStatus.PARTIAL,
        inputRevision = 4,
        generatedAt = testNow,
    ).copy(transactionCount = 1)

    @Test
    fun `the serverplan revision and summary coverage are shown`() {
        val server = RecordingServerFinanceRepository().apply {
            budgetPlanOutcome = Outcome.Success(storedPlan)
            summaryOutcome = Outcome.Success(summary())
        }
        val vm = viewModel(server)
        setContent(vm)

        composeRule.waitUntil(5_000) { vm.state.value.summaryCoverage != null }
        composeRule.onNodeWithText("Rencana server · revisi 2").assertIsDisplayed()
        composeRule.onNodeWithText("Ringkasan server: sebagian").assertIsDisplayed()
    }

    @Test
    fun `an outdated summary is flagged next to the comparison`() {
        val server = RecordingServerFinanceRepository().apply {
            budgetPlanOutcome = Outcome.Success(storedPlan)
            summaryOutcome = Outcome.Success(summary().copy(isOutdated = true))
        }
        val vm = viewModel(server)
        setContent(vm)

        composeRule.waitUntil(5_000) { vm.state.value.summaryIsOutdated }
        composeRule
            .onNodeWithText("Ringkasan dihitung sebelum perubahan terakhir; angka aktual mungkin tertinggal.")
            .assertIsDisplayed()
    }

    private fun awaitText(text: String) {
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.waitForIdle()
    }

    @Test
    fun `a conflict banner offers to reload the latest server plan`() {
        val server = RecordingServerFinanceRepository().apply {
            budgetPlanOutcome = Outcome.Success(storedPlan)
            savePlanOutcome = { Outcome.Failure(AppError.Conflict("revision_conflict")) }
        }
        val vm = viewModel(server)
        setContent(vm)
        composeRule.waitUntil(5_000) { !vm.state.value.isLoading }

        vm.setLimit(Category.FOOD, "950000")
        vm.save()
        composeRule.waitUntil(5_000) { vm.state.value.conflict }
        awaitText("Rencana ini sudah diubah di tempat lain. Muat data terbaru sebelum menyimpan lagi.")

        composeRule
            .onNodeWithText("Rencana ini sudah diubah di tempat lain. Muat data terbaru sebelum menyimpan lagi.")
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithText("Muat data terbaru").performScrollTo().performClick()

        // Reloading applies the server copy, so the draft is replaced only on explicit request.
        composeRule.waitUntil(5_000) { !vm.state.value.conflict && vm.state.value.freshness == PlanFreshness.CURRENT }
        composeRule.onNodeWithText("Rencana server · revisi 2").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `a stale summary is visibly marked and the refresh action makes it current`() {
        val stale = summary().copy(
            isOutdated = true,
            freshness = SummaryFreshness.STALE,
        )
        val current = summary().copy(freshness = SummaryFreshness.CURRENT)
        val server = RecordingServerFinanceRepository().apply {
            budgetPlanOutcome = Outcome.Success(storedPlan)
            summaryOutcome = Outcome.Success(stale)
        }
        val vm = viewModel(server)
        setContent(vm)

        composeRule.waitUntil(5_000) { vm.state.value.comparison != null }
        composeRule
            .onNodeWithText("Ringkasan server sudah usang; angka aktual mungkin belum termasuk perubahan terakhir.")
            .assertIsDisplayed()

        server.summaryOutcome = Outcome.Success(current)
        composeRule.onNodeWithText("Perbarui ringkasan").performClick()
        composeRule.waitUntil(5_000) { vm.state.value.summaryFreshness == SummaryFreshness.CURRENT }

        composeRule.onNodeWithText("Ringkasan server terkini.").assertIsDisplayed()
        composeRule
            .onNodeWithText("Ringkasan server sudah usang; angka aktual mungkin belum termasuk perubahan terakhir.")
            .assertDoesNotExist()
    }

    @Test
    fun `deleting the plan asks for confirmation and then resets to an unsaved suggestion`() {
        val server = RecordingServerFinanceRepository().apply {
            budgetPlanOutcome = Outcome.Success(storedPlan)
        }
        val vm = viewModel(server)
        setContent(vm)
        composeRule.waitUntil(5_000) { !vm.state.value.isLoading }

        composeRule.onNodeWithText("Hapus rencana").performScrollTo().performClick()
        awaitText("Hapus rencana anggaran ini?")
        // The confirmation lives in a dialog window; assert its text is in the semantics tree.
        composeRule.onNodeWithText("Hapus rencana anggaran ini?").assertExists()
        org.junit.Assert.assertTrue(vm.state.value.deleteConfirmation)
        // Cancelling must not delete anything.
        composeRule.onNodeWithText("Batal").performClick()
        composeRule.waitUntil(5_000) { !vm.state.value.deleteConfirmation }
        org.junit.Assert.assertTrue(server.deletedPlanMonths.isEmpty())

        composeRule.onNodeWithText("Hapus rencana").performScrollTo().performClick()
        awaitText("Hapus rencana anggaran ini?")
        // The dynamic-fields editor also has a "Hapus" remove button; the dialog confirm is last.
        composeRule.onAllNodesWithText("Hapus").onLast().performClick()
        composeRule.waitUntil(5_000) { vm.state.value.planDeleted }

        org.junit.Assert.assertEquals(listOf(testMonth.id()), server.deletedPlanMonths)
        composeRule
            .onNodeWithText("Rencana di server sudah dihapus. Nilai di bawah adalah usulan yang belum disimpan.")
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun `server attributes are prefilled and survive the save readback`() {
        val server = RecordingServerFinanceRepository().apply {
            budgetPlanOutcome = Outcome.Success(storedPlan.copy(attributes = mapOf("sumber" to "kamera")))
        }
        val vm = viewModel(server)
        setContent(vm)
        composeRule.waitUntil(5_000) { !vm.state.value.isLoading }

        composeRule.onNodeWithText("kamera").performScrollTo().assertIsDisplayed()

        vm.setAttributeFields(
            listOf(
                com.pam.recap.ui.components.DynamicField("sumber", "kamera"),
                com.pam.recap.ui.components.DynamicField("catatan", "bulanan"),
            ),
        )
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Simpan rencana").performScrollTo().performClick()
        composeRule.waitUntil(5_000) { vm.state.value.savedAt != null }
        composeRule.waitForIdle()

        // The PUT response is applied back into the editor, so the saved row is what is shown.
        org.junit.Assert.assertEquals(
            mapOf("sumber" to "kamera", "catatan" to "bulanan"),
            server.savedPlans.single().attributes,
        )
        composeRule.onNodeWithText("bulanan").performScrollTo().assertIsDisplayed()
    }
}
