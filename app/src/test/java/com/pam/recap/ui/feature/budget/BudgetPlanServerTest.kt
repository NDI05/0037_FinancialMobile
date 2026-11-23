package com.pam.recap.ui.feature.budget

import androidx.lifecycle.SavedStateHandle
import com.pam.recap.core.money.Money
import com.pam.recap.core.result.AppError
import com.pam.recap.core.result.Outcome
import com.pam.recap.domain.model.Category
import com.pam.recap.domain.model.CategoryTotal
import com.pam.recap.domain.model.CoverageStatus
import com.pam.recap.domain.model.MonthlySummary
import com.pam.recap.domain.model.SummaryFreshness
import com.pam.recap.domain.repository.FinanceRefreshBus
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * S2.5: when the server is enabled the budget editor reads and writes the server plan, and a
 * revision conflict is surfaced instead of overwriting the other writer.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BudgetPlanServerTest {

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

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

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

    private val storedPlan = ServerBudgetPlan(
        monthId = testMonth.id(),
        expectedIncomeMinor = 5_000_000,
        plannedSavingsMinor = 1_000_000,
        limitsByCategoryId = mapOf("cat_food" to 800_000L, "cat_transport" to 300_000L),
        gapMinor = 2_900_000,
        revision = 2,
    )

    @Test
    fun `the server plan is loaded and its category ids map to the taxonomy`() = runTest {
        val server = RecordingServerFinanceRepository().apply { budgetPlanOutcome = Outcome.Success(storedPlan) }
        val vm = viewModel(server)

        val state = vm.state.first { !it.isLoading }

        assertTrue(state.isServerMode)
        assertEquals(PlanFreshness.CURRENT, state.freshness)
        assertEquals(2, state.revision)
        assertEquals(Money(5_000_000), state.expectedIncome)
        assertEquals(Money(1_000_000), state.plannedSavings)
        assertEquals(Money(800_000), state.limits[Category.FOOD])
        assertEquals(Money(300_000), state.limits[Category.TRANSPORT])
        // The server DTO has no total; the editor sums the per-category limits itself.
        assertEquals(1_100_000L, state.totalLimitMinor)
        // The server gap is recomputed from the field values, so the screen's hero stays consistent.
        assertEquals(Money(2_900_000), state.planningGap.gap)
    }

    @Test
    fun `saving writes the mapped category ids with the loaded revision`() = runTest {
        val server = RecordingServerFinanceRepository().apply { budgetPlanOutcome = Outcome.Success(storedPlan) }
        val vm = viewModel(server)
        vm.state.first { !it.isLoading }

        vm.setLimit(Category.FOOD, "900000")
        vm.save()
        val state = vm.state.first { it.savedAt != null }

        val submitted = server.savedPlans.single()
        assertEquals(2, submitted.revision)
        assertEquals(5_000_000, submitted.expectedIncomeMinor)
        assertEquals(
            // "900000" is typed rupiah, and Money.minor is the cent amount (900.000 -> 90.000.000).
            mapOf("cat_food" to 90_000_000L, "cat_transport" to 300_000L),
            submitted.limitsByCategoryId,
        )
        assertFalse(state.conflict)
        assertEquals(PlanFreshness.CURRENT, state.freshness)
        assertEquals(3, state.revision)
    }

    @Test
    fun `a revision conflict keeps the draft and marks the plan stale`() = runTest {
        val server = RecordingServerFinanceRepository().apply {
            budgetPlanOutcome = Outcome.Success(storedPlan)
            savePlanOutcome = { Outcome.Failure(AppError.Conflict("revision_conflict")) }
        }
        val vm = viewModel(server)
        vm.state.first { !it.isLoading }

        vm.setLimit(Category.FOOD, "950000")
        vm.save()
        val state = vm.state.first { it.conflict }

        assertTrue(state.conflict)
        assertNull(state.error)
        assertEquals(PlanFreshness.STALE, state.freshness)
        // The draft survives, so the user can compare it with the newer server copy.
        assertEquals("950000", state.limitTexts[Category.FOOD])
        assertEquals(2, state.revision)
        assertEquals(1, server.savedPlans.size)
    }

    @Test
    fun `a backend without the budget endpoint keeps the local editor`() = runTest {
        // Default fake answers Configuration, which is the "endpoint absent" signal.
        budgets.stored = com.pam.recap.domain.model.BudgetPlan(
            id = "local_plan",
            ownerId = "u_owner",
            month = testMonth,
            expectedIncome = Money(5_000_000),
            plannedSavings = Money(1_000_000),
            limits = mapOf(Category.FOOD to Money(800_000)),
            gap = Money(3_200_000),
            revision = 1,
            valueOrigins = emptyMap(),
            createdAt = testNow,
            updatedAt = testNow,
        )
        val server = RecordingServerFinanceRepository()
        val vm = viewModel(server)

        val state = vm.state.first { !it.isLoading }

        assertFalse(state.isServerMode)
        assertNull(state.error)
        // The local plan, not a server one, is what the editor shows.
        assertEquals(Money(800_000), state.limits[Category.FOOD])
        assertEquals(1, state.revision)
    }

    @Test
    fun `the comparison comes from the server summary and carries its coverage`() = runTest {
        val summary = MonthlySummary.empty(
            ownerId = "u_owner",
            month = testMonth,
            mailboxLabel = "andhika@example.test",
            coverage = CoverageStatus.PARTIAL,
            inputRevision = 7,
            generatedAt = testNow,
        ).copy(
            transactionCount = 1,
            classifiedExpense = Money(450_000),
            categoryTotals = listOf(CategoryTotal(Category.FOOD, Money(450_000), 1)),
            isOutdated = true,
        )
        val server = RecordingServerFinanceRepository().apply {
            budgetPlanOutcome = Outcome.Success(storedPlan)
            summaryOutcome = Outcome.Success(summary)
        }
        val vm = viewModel(server)

        val state = vm.state.first { it.comparison != null }

        assertEquals(CoverageStatus.PARTIAL, state.summaryCoverage)
        assertTrue(state.summaryIsOutdated)
        assertEquals(testNow.toEpochMilliseconds(), state.summaryGeneratedAt)
        val foodRow = state.comparison?.rows?.first { it.category == Category.FOOD }
        assertEquals(Money(450_000), foodRow?.actual)
        assertEquals(Money(350_000), foodRow?.remaining)
    }

    private fun summary(
        coverage: CoverageStatus,
        inputRevision: Int,
    ): MonthlySummary = MonthlySummary.empty(
        ownerId = "u_owner",
        month = testMonth,
        mailboxLabel = "andhika@example.test",
        coverage = coverage,
        inputRevision = inputRevision,
        generatedAt = testNow,
    ).copy(
        transactionCount = 1,
        classifiedExpense = Money(450_000),
        categoryTotals = listOf(CategoryTotal(Category.FOOD, Money(450_000), 1)),
    )

    @Test
    fun `the summary freshness is exposed and an explicit refresh replaces it`() = runTest {
        val stale = summary(CoverageStatus.PARTIAL, inputRevision = 7).copy(
            isOutdated = true,
            freshness = SummaryFreshness.STALE,
        )
        val current = summary(CoverageStatus.COMPLETE, inputRevision = 9)
            .copy(freshness = SummaryFreshness.CURRENT)
        val server = RecordingServerFinanceRepository().apply {
            budgetPlanOutcome = Outcome.Success(storedPlan)
            summaryOutcome = Outcome.Success(stale)
        }
        val vm = viewModel(server)

        val loaded = vm.state.first { it.comparison != null }
        assertEquals(SummaryFreshness.STALE, loaded.summaryFreshness)

        // The recalc action refetches the server summary; the new freshness is what is shown.
        server.summaryOutcome = Outcome.Success(current)
        vm.refreshSummary()
        val refreshed = vm.state.first { it.summaryFreshness == SummaryFreshness.CURRENT }

        assertFalse(refreshed.isRefreshingSummary)
        assertFalse(refreshed.summaryRefreshFailed)
        assertEquals(CoverageStatus.COMPLETE, refreshed.summaryCoverage)
    }

    @Test
    fun `a failed refresh keeps the stale marker and says the numbers could not be made current`() = runTest {
        val stale = summary(CoverageStatus.PARTIAL, inputRevision = 7).copy(
            isOutdated = true,
            freshness = SummaryFreshness.STALE,
        )
        val server = RecordingServerFinanceRepository().apply {
            budgetPlanOutcome = Outcome.Success(storedPlan)
            summaryOutcome = Outcome.Success(stale)
        }
        val vm = viewModel(server)
        vm.state.first { it.comparison != null }

        server.summaryOutcome = Outcome.Failure(AppError.Network("offline"))
        vm.refreshSummary()
        val state = vm.state.first { it.summaryRefreshFailed }

        assertFalse(state.isRefreshingSummary)
        // Never pretend the old numbers became current just because the refresh failed.
        assertEquals(SummaryFreshness.STALE, state.summaryFreshness)
    }

    @Test
    fun `a confirmed transaction write marks the loaded comparison stale`() = runTest {
        val current = summary(CoverageStatus.COMPLETE, inputRevision = 9)
            .copy(freshness = SummaryFreshness.CURRENT)
        val server = RecordingServerFinanceRepository().apply {
            budgetPlanOutcome = Outcome.Success(storedPlan)
            summaryOutcome = Outcome.Success(current)
        }
        val vm = viewModel(server)
        vm.state.first { it.comparison != null }

        FinanceRefreshBus.notify("u_owner", testMonth.id())
        val state = vm.state.first { it.summaryFreshness == SummaryFreshness.STALE }

        // Only a refresh may clear the marker; the invalidation itself does not fabricate new numbers.
        assertEquals(SummaryFreshness.STALE, state.summaryFreshness)
    }

    @Test
    fun `deleting the server plan requires confirmation and falls back to an unsaved draft`() = runTest {
        val server = RecordingServerFinanceRepository().apply { budgetPlanOutcome = Outcome.Success(storedPlan) }
        val vm = viewModel(server)
        vm.state.first { !it.isLoading }

        // Nothing is deleted before the user confirms.
        vm.confirmDelete()
        assertTrue(server.deletedPlanMonths.isEmpty())
        assertEquals(PlanFreshness.CURRENT, vm.state.value.freshness)

        vm.requestDelete()
        assertTrue(vm.state.value.deleteConfirmation)
        vm.confirmDelete()
        val state = vm.state.first { it.planDeleted }

        assertEquals(listOf(testMonth.id()), server.deletedPlanMonths)
        assertFalse(state.deleteConfirmation)
        assertFalse(state.isDeleting)
        assertEquals(0, state.revision)
        assertEquals(PlanFreshness.UNSAVED, state.freshness)
        assertNull(state.savedAt)
    }

    @Test
    fun `a failed delete keeps the plan and reports the error`() = runTest {
        val server = RecordingServerFinanceRepository().apply {
            budgetPlanOutcome = Outcome.Success(storedPlan)
            deletePlanOutcome = Outcome.Failure(AppError.Network("offline"))
        }
        val vm = viewModel(server)
        vm.state.first { !it.isLoading }

        vm.requestDelete()
        vm.confirmDelete()
        val state = vm.state.first { it.error != null }

        assertFalse(state.planDeleted)
        assertFalse(state.isDeleting)
        assertEquals(2, state.revision)
        assertEquals(PlanFreshness.CURRENT, state.freshness)
    }
}
