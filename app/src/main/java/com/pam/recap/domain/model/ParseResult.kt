package com.pam.recap.domain.model

import com.pam.recap.core.money.Money
import kotlin.time.Instant

/**
 * Outcome of parsing one source notification (FR-12/FR-13). [Unresolved] carries only a
 * [RedactionReason]: never the subject, body, sender, message id or merchant (FR-15, hard rule 7).
 */
sealed interface ParseResult {

    data class Parsed(
        val messageId: String,
        val templateId: String,
        val parserVersion: String,
        val eventAt: Instant,
        val receivedAt: Instant?,
        val amount: Money,
        val currency: String,
        val direction: Direction,
        val merchantLabel: String?,
        val acquirer: String?,
        val status: ParseStatus,
        /** Normalized description from the template, never a body line (FR-15). */
        val description: String,
        val confidence: Float,
    ) : ParseResult

    data class Unresolved(val reason: RedactionReason) : ParseResult
}
