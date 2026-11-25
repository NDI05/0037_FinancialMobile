package com.pam.recap.ui.feature.overview

import com.pam.recap.core.money.Money
import com.pam.recap.core.time.JakartaMonth
import com.pam.recap.domain.model.Category
import com.pam.recap.domain.model.CategoryOrigin
import com.pam.recap.domain.model.Direction
import com.pam.recap.domain.model.ParseStatus
import com.pam.recap.domain.model.Transaction
import com.pam.recap.domain.model.TransactionSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import kotlin.time.Instant
import org.junit.Test

class BankCardDataTest {

    private val month = JakartaMonth(2026, 11)
    private val inMonth = Instant.parse("2026-11-19T02:15:20Z")
    private val nextMonth = Instant.parse("2026-12-01T02:15:20Z")

    @Test
    fun `email count ignores manual failed non-idr and other-month rows`() {
        val rows = listOf(
            row("email-ok"),
            row("manual-ok", source = TransactionSource.MANUAL),
            row("email-failed", status = ParseStatus.FAILED),
            row("email-usd", currency = "USD"),
            row("email-december", at = nextMonth),
        )

        assertEquals(1, emailTransactionCount(rows, month))
    }

    @Test
    fun `mandiri count is summary transactions minus manual entries`() {
        assertEquals(9, recordedEmailCount(transactionCount = 12, manualEntryCount = 3))
        assertEquals(0, recordedEmailCount(transactionCount = 3, manualEntryCount = 7))
    }

    @Test
    fun `buildBankCards keeps Mandiri real and labels previews synthetic`() {
        val cards = buildBankCards(month = month, mandiriEmailCount = 7)

        assertEquals(3, cards.size)
        val mandiri = cards.first()
        assertEquals("mandiri", mandiri.key)
        assertEquals(7, mandiri.transactionCount)
        assertFalse(mandiri.isPreview)
        assertTrue(mandiri.isEmailDerived)
        assertEquals("November 2026", mandiri.periodLabel)

        val previews = cards.drop(1)
        assertEquals(listOf("jago", "bri"), previews.map { it.key })
        assertTrue(previews.all { it.isPreview && !it.isEmailDerived })
        assertEquals(listOf(JAGO_PREVIEW_COUNT, BRI_PREVIEW_COUNT), previews.map { it.transactionCount })
    }

    @Test
    fun `preview counts never react to month rows`() {
        val withRows = buildBankCards(month, mandiriEmailCount = 40)
        val withoutRows = buildBankCards(month, mandiriEmailCount = 0)

        assertEquals(
            withRows.filter { it.isPreview }.map { it.transactionCount },
            withoutRows.filter { it.isPreview }.map { it.transactionCount },
        )
    }

    @Test
    fun `single supported bank produces a single full-width card`() {
        val cards = buildBankCards(month = month, mandiriEmailCount = 4, includePreviews = false)

        assertEquals(1, cards.size)
        assertEquals("mandiri", cards.single().key)
    }

    @Test
    fun `direction counts include manual and credit rows but not failed ones`() {
        val rows = listOf(
            row("debit-email"),
            row("debit-manual", source = TransactionSource.MANUAL),
            row("credit-email", direction = Direction.CREDIT),
            row("failed", status = ParseStatus.FAILED),
        )

        val counts = monthlyDirectionCounts(rows, month)

        assertEquals(2, counts.expense)
        assertEquals(1, counts.income)
    }

    private fun row(
        id: String,
        source: TransactionSource = TransactionSource.EMAIL,
        direction: Direction = Direction.DEBIT,
        status: ParseStatus = ParseStatus.POSTED,
        currency: String = "IDR",
        at: Instant = inMonth,
    ): Transaction = Transaction(
        id = id,
        ownerId = "u_owner",
        direction = direction,
        source = source,
        amount = Money(16_000),
        currency = currency,
        eventAt = at,
        receivedAt = null,
        description = "QRIS $id",
        merchantLabel = null,
        acquirer = null,
        status = status,
        fingerprint = "a".repeat(32),
        parserVersion = "livin-v1",
        parserConfidence = 0.9f,
        category = Category.UNCATEGORIZED,
        categoryOrigin = CategoryOrigin.USER,
        categoryConfidence = null,
        categoryUpdatedAt = null,
        isInternalTransfer = false,
        linkedRefundOf = null,
        note = null,
        importJobId = null,
        revision = 1,
        createdAt = inMonth,
        updatedAt = inMonth,
    )
}
