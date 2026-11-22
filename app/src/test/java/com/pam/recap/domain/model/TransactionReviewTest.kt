package com.pam.recap.domain.model

import com.pam.recap.core.money.Money
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Instant

/**
 * FR-24 review chip rules. A USER override is a human decision and must never carry the
 * "Perlu tinjauan" chip, even when the server has no classifier confidence for the row
 * (LLM results carry no confidence; a cleared override reports 0).
 */
class TransactionReviewTest {

    private fun row(
        origin: CategoryOrigin,
        categoryConfidence: Float?,
        parserConfidence: Float = 0.9f,
    ) = Transaction(
        id = "tx_1", ownerId = "u_1", direction = Direction.DEBIT,
        source = TransactionSource.EMAIL, amount = Money(16_000), currency = "IDR",
        eventAt = Instant.parse("2026-11-19T02:15:20Z"), receivedAt = null,
        description = "QRIS", merchantLabel = "BAKSO UJI", acquirer = null,
        status = ParseStatus.POSTED, fingerprint = "a".repeat(32),
        parserVersion = "livin-v1", parserConfidence = parserConfidence,
        category = Category.FOOD, categoryOrigin = origin,
        categoryConfidence = categoryConfidence, categoryUpdatedAt = null,
        isInternalTransfer = false, linkedRefundOf = null, note = null, importJobId = null,
        revision = 4, createdAt = Instant.parse("2026-11-19T03:00:00Z"),
        updatedAt = Instant.parse("2026-11-19T03:01:00Z"),
    )

    @Test
    fun `a USER override never carries the review chip even with zero confidence`() {
        // Server reports categoryConfidence 0 / null for USER overrides of LLM rows.
        assertFalse(row(CategoryOrigin.USER, categoryConfidence = 0f).isLowConfidence)
        assertFalse(row(CategoryOrigin.USER, categoryConfidence = null).isLowConfidence)
    }

    @Test
    fun `an LLM row is an unconfirmed suggestion, not a low-confidence data row`() {
        val llm = row(CategoryOrigin.LLM, categoryConfidence = null)
        assertTrue(llm.hasUnconfirmedSuggestion)
        assertFalse(llm.isLowConfidence)
        // A confirmed (user-edited) row is neither a suggestion nor low confidence.
        val confirmed = row(CategoryOrigin.USER, categoryConfidence = null)
        assertFalse(confirmed.hasUnconfirmedSuggestion)
        assertFalse(confirmed.isLowConfidence)
        // Non-LLM rows never report a pending suggestion.
        assertFalse(row(CategoryOrigin.IMPORTED, categoryConfidence = 0.3f).hasUnconfirmedSuggestion)
        assertFalse(row(CategoryOrigin.RULE, categoryConfidence = null).hasUnconfirmedSuggestion)
    }

    @Test
    fun `an IMPORTED row below threshold stays low confidence`() {
        assertTrue(row(CategoryOrigin.IMPORTED, categoryConfidence = 0.30f).isLowConfidence)
        assertFalse(row(CategoryOrigin.IMPORTED, categoryConfidence = 0.62f).isLowConfidence)
    }

    @Test
    fun `a low parser confidence still raises the chip for non-USER rows`() {
        assertTrue(row(CategoryOrigin.RULE, categoryConfidence = null, parserConfidence = 0.4f).isLowConfidence)
    }
}