package com.pam.recap.data.backend

import com.pam.recap.core.money.Money
import com.pam.recap.core.time.JakartaMonth
import com.pam.recap.domain.model.AiPlanSummary
import com.pam.recap.domain.model.Category
import com.pam.recap.domain.model.CategoryTotal
import com.pam.recap.domain.model.CoverageStatus
import com.pam.recap.domain.model.MonthlySummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.time.Instant

/** S2.5: regeneration payload is a faithful projection of the server summary + prior month + plan. */
class ServerAnalysisPayloadTest {

    private val month = JakartaMonth.of(Instant.parse("2026-11-19T02:15:20Z"))
    private val previousMonth = JakartaMonth.of(Instant.parse("2026-10-15T02:15:20Z"))
    private val now = Instant.parse("2026-11-19T05:00:00Z")

    private fun summary(
        month: JakartaMonth = this.month,
        revision: Int = 4,
        currentRevision: Int = revision,
        grossOutflow: Long = 2_500_000,
        classifiedExpense: Long = 2_000_000,
    ) = MonthlySummary.empty(
        ownerId = "u_owner",
        month = month,
        mailboxLabel = "andhika@example.test",
        coverage = CoverageStatus.COMPLETE,
        inputRevision = revision,
        generatedAt = now,
    ).copy(
        currentRevision = currentRevision,
        grossInflow = Money(5_000_000),
        grossOutflow = Money(grossOutflow),
        netCashFlow = Money(2_500_000),
        classifiedIncome = Money(5_000_000),
        classifiedExpense = Money(classifiedExpense),
        transactionCount = 12,
        uncategorizedCount = 1,
        manualEntryCount = 2,
        categoryTotals = listOf(
            CategoryTotal(Category.FOOD, Money(1_200_000), 5),
        ),
    )

    @Test
    fun `the payload copies the server summary numbers`() {
        val payload = ServerAnalysisPayload.assemble(summary(currentRevision = 4), null, null)

        assertEquals("2026-11", payload.monthId)
        assertEquals(1, payload.schemaVersion)
        assertEquals("COMPLETE", payload.coverage)
        assertEquals(5_000_000L, payload.grossInflowMinor)
        assertEquals(2_500_000L, payload.grossOutflowMinor)
        assertEquals(12, payload.transactionCount)
        assertEquals(1, payload.uncategorizedCount)
        assertEquals(2, payload.manualEntryCount)
        assertEquals("food", payload.categoryTotals.single().categoryCode)
        assertEquals(1_200_000L, payload.categoryTotals.single().amountMinor)
        assertNull(payload.priorMonth)
        assertNull(payload.plan)
    }

    @Test
    fun `the prior month carries the server classified income and expense`() {
        val payload = ServerAnalysisPayload.assemble(
            summary(),
            summary(month = previousMonth, revision = 9, grossOutflow = 1_500_000, classifiedExpense = 1_100_000),
            null,
        )

        assertEquals("2026-10", payload.priorMonth?.monthId)
        assertEquals(1_100_000L, payload.priorMonth?.expenseMinor)
        assertEquals(5_000_000L, payload.priorMonth?.incomeMinor)
    }

    @Test
    fun `the budget plan is included when the server supplied one`() {
        val plan = AiPlanSummary(
            expectedIncomeMinor = 5_000_000,
            plannedSavingsMinor = 1_000_000,
            categoryLimitTotals = emptyList(),
        )

        val payload = ServerAnalysisPayload.assemble(summary(), null, plan)

        assertEquals(5_000_000L, payload.plan?.expectedIncomeMinor)
        assertEquals(1_000_000L, payload.plan?.plannedSavingsMinor)
    }
}
