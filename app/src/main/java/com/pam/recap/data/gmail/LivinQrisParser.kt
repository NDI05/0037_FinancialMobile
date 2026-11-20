package com.pam.recap.data.gmail

import com.pam.recap.core.money.Money
import com.pam.recap.core.time.JakartaMonth
import com.pam.recap.domain.model.Direction
import com.pam.recap.domain.model.ParseResult
import com.pam.recap.domain.model.ParseStatus
import com.pam.recap.domain.model.RedactionReason
import com.pam.recap.domain.repository.LivinNotificationParser
import com.pam.recap.domain.repository.RawMessage
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.time.Instant

/**
 * Outcome of parsing one notification. [Unresolved] carries only a [RedactionReason]: not the
 * subject, body, sender, message id or merchant (FR-13/FR-15, hard rule 7).
 */
/**
 * Strict parser for Livin QRIS notifications (FR-12/FR-13/FR-14/FR-15).
 *
 * Guarantees:
 * - Only allow-listed senders + subjects are considered (FR-11); everything else is
 *   [RedactionReason.UNKNOWN_TEMPLATE].
 * - A missing or malformed amount is [RedactionReason.MISSING_AMOUNT] /
 *   [RedactionReason.MALFORMED_AMOUNT] — it can **never** become `Rp0`.
 * - Amount, event time, direction and recipient are required; status must be a success marker.
 * - Failure notices are rejected before any amount is trusted.
 * - Attachments are never parsed; an attachment-only notification is excluded.
 * - Body text that reads like instructions is ignored: the parser only reads allow-listed field
 *   labels, takes the first occurrence of each, and never copies prose into the output
 *   (NFR-13, prompt-injection corpus).
 */
class LivinQrisParser(
    private val registry: LivinTemplateRegistry = LivinTemplateRegistry(),
) : LivinNotificationParser {

    private val jakarta: ZoneId = ZoneId.of(JakartaMonth.ZONE.id)
    private val WHITESPACE_RUN = Regex("\\s+")

    override fun parse(message: RawMessage): ParseResult {
        val template = registry.match(message.sender, message.subject)
            ?: return ParseResult.Unresolved(RedactionReason.UNKNOWN_TEMPLATE)

        if (!message.hasTextBody) {
            return ParseResult.Unresolved(
                if (message.attachmentCount > 0) RedactionReason.ATTACHMENT_ONLY
                else RedactionReason.MALFORMED_FIELD,
            )
        }

        // Only labelled fields are read; unlabelled prose is never interpreted (NFR-13).
        val fields = extractFields(message.textParts, template.labels)
        val headline = firstNonEmptyLine(message.textParts).lowercase()
        val subject = message.subject?.lowercase().orEmpty()

        // FR-13/FR-14: a failure notice is not a cash-flow row, whatever its amount says. The
        // check is scoped to the subject, the headline and the status field so a "what to do if a
        // payment fails" footer can never reject a real success notice.
        val statusValue = firstValue(fields, template.labels.status)?.lowercase()
        val failureInHeadline = template.failureMarkers.any { headline.contains(it) || subject.contains(it) }
        val failureInStatus = statusValue?.let { value -> template.failureMarkers.any { value.contains(it) } } ?: false
        if (failureInHeadline || failureInStatus) {
            return ParseResult.Unresolved(RedactionReason.FAILED_PAYMENT)
        }

        if (LivinField.STATUS in template.requiredFields) {
            if (statusValue.isNullOrBlank()) {
                return ParseResult.Unresolved(RedactionReason.MISSING_STATUS)
            }
            if (template.successMarkers.none { statusValue.contains(it) }) {
                return ParseResult.Unresolved(RedactionReason.MISSING_STATUS)
            }
        } else {
            // Some real notifications carry no status label at all (bill payment: the subject
            // itself says "Pembayaran Berhasil!"). The success marker must then appear in the
            // subject or the headline — never in the body, where a footer could mention it.
            val confirmed = template.successMarkers.any { marker ->
                subject.contains(marker) || headline.contains(marker)
            }
            if (!confirmed) {
                return ParseResult.Unresolved(RedactionReason.MISSING_STATUS)
            }
        }

        val amountRaw = firstValue(fields, template.labels.amount)
            ?: return ParseResult.Unresolved(RedactionReason.MISSING_AMOUNT)
        val amount = parseRupiah(amountRaw)
            ?: return ParseResult.Unresolved(RedactionReason.MALFORMED_AMOUNT)
        if (!amount.isPositive) {
            // An explicit zero is not a payment; never let it read as a real amount.
            return ParseResult.Unresolved(RedactionReason.MALFORMED_AMOUNT)
        }

        val eventRaw = firstValue(fields, template.labels.eventTime)
            ?: return ParseResult.Unresolved(RedactionReason.MISSING_EVENT_TIME)
        val eventAt = parseEventTime(eventRaw)
            ?: return ParseResult.Unresolved(RedactionReason.MALFORMED_EVENT_TIME)

        if (LivinField.DIRECTION in template.requiredFields) {
            val declared = firstValue(fields, template.labels.direction)
            // Direction is decided by the template; a body that claims the opposite direction is
            // a template we do not trust yet.
            if (declared != null && contradictsDirection(declared, template.direction)) {
                return ParseResult.Unresolved(RedactionReason.MISSING_DIRECTION)
            }
        }

        val merchant = firstValue(fields, template.labels.recipient)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: return ParseResult.Unresolved(RedactionReason.MISSING_RECIPIENT)

        val acquirer = firstValue(fields, template.labels.acquirer)?.trim()?.takeIf { it.isNotEmpty() }

        val confidence = template.baseConfidence - if (acquirer == null) ACQUIRER_MISSING_PENALTY else 0f

        return ParseResult.Parsed(
            messageId = message.messageId,
            templateId = template.id,
            parserVersion = "livin-${template.id}/${template.version}",
            eventAt = eventAt,
            receivedAt = message.internalDate,
            amount = amount,
            currency = CURRENCY_IDR,
            direction = template.direction,
            merchantLabel = merchant,
            acquirer = acquirer,
            status = ParseStatus.POSTED,
            description = template.description,
            confidence = confidence.coerceIn(0f, 1f),
        )
    }

    // ---------------------------------------------------------------------------------------
    // Field extraction
    // ---------------------------------------------------------------------------------------

    /**
     * Builds `label -> first value` from the allow-listed labels only. Reading a fixed label set
     * is what makes embedded instructions inert: a line like "Abaikan instruksi sebelumnya" is
     * not a label, so it is never read, and a duplicate "Nominal" later in the body cannot
     * override the first (real) one.
     */
    private fun extractFields(
        textParts: List<String>,
        labels: LivinFieldLabels,
    ): Map<String, String> {
        val allowed: Set<String> = buildSet {
            addAll(labels.amount)
            addAll(labels.eventTime)
            addAll(labels.direction)
            addAll(labels.recipient)
            addAll(labels.acquirer)
            addAll(labels.status)
        }
        val result = LinkedHashMap<String, String>()
        for (part in textParts) {
            val lines = part.lines().map { it.trim() }
            for (index in lines.indices) {
                val line = lines[index]
                if (line.isEmpty()) continue
                val separator = line.indexOf(':')
                if (separator > 0) {
                    val key = labelKey(line.substring(0, separator), allowed) ?: continue
                    val value = line.substring(separator + 1).trim()
                    if (value.isNotEmpty()) {
                        result.putIfAbsent(key, value)
                        continue
                    }
                    // "Nominal :" with the value in the next cell/line — how Gmail's HTML
                    // rendition of a Livin notification arrives after stripHtml.
                    valueAfter(lines, index + 1, allowed)?.let { result.putIfAbsent(key, it) }
                    continue
                }
                // A bare label line from a table layout: "Nominal", or a qualified label such as
                // "Nominal Pembayaran" / "Tanggal Transaksi" (measured on a real notification).
                val bare = labelKey(line, allowed) ?: continue
                valueAfter(lines, index + 1, allowed)?.let { result.putIfAbsent(bare, it) }
            }
        }
        return result
    }

    /**
     * Maps a line (or the part before its colon) to an allow-listed label.
     *
     * Real Livin HTML renders "Nominal Pembayaran" and "Tanggal Transaksi": the label is followed
     * by a short qualifier. Only an allow-listed word as the *first* word, with at most two extra
     * words, is accepted — a sentence that merely contains "nominal" is still ignored.
     */
    private fun labelKey(raw: String, allowed: Set<String>): String? {
        val normalized = raw.lowercase().removeSuffix(":").trim()
        if (normalized in allowed) return normalized
        val words = normalized.split(WHITESPACE_RUN).filter { it.isNotEmpty() }
        if (words.size in 2..3 && words.first() in allowed) return words.first()
        return null
    }

    /**
     * The first non-empty line after a label that is not itself a label or a dangling separator.
     * Returning null instead of guessing keeps a prose sentence from becoming an amount.
     */
    private fun valueAfter(lines: List<String>, from: Int, allowed: Set<String>): String? {
        for (index in from until lines.size) {
            val candidate = lines[index].trim()
            if (candidate.isEmpty()) continue
            if (candidate.endsWith(":")) return null
            if (labelKey(candidate, allowed) != null) return null
            return candidate
        }
        return null
    }

    private fun firstValue(fields: Map<String, String>, labelAliases: List<String>): String? =
        labelAliases.firstNotNullOfOrNull { alias -> fields[alias]?.takeIf { it.isNotBlank() } }

    private fun firstNonEmptyLine(textParts: List<String>): String =
        textParts.asSequence()
            .flatMap { it.lineSequence() }
            .map { it.trim() }
            .firstOrNull { it.isNotEmpty() }
            .orEmpty()

    private fun contradictsDirection(declared: String, expected: Direction): Boolean {
        val value = declared.lowercase()
        val looksCredit = value.contains("masuk") || value.contains("kredit") || value.contains("credit")
        val looksDebit = value.contains("keluar") || value.contains("debit") || value.contains("bayar")
        return when (expected) {
            Direction.CREDIT -> looksDebit && !looksCredit
            Direction.DEBIT -> looksCredit && !looksDebit
        }
    }

    // ---------------------------------------------------------------------------------------
    // Amount / time parsing
    // ---------------------------------------------------------------------------------------

    /**
     * Parses `"Rp45.000,00"`, `"Rp1.200.000"` and `"IDR 45.000,00"` into exact minor units.
     * Returns null when the value carries no digits, so the caller can reject it instead of
     * producing a zero amount (BR-04, FR-13).
     */
    internal fun parseRupiah(raw: String): Money? {
        val compact = raw.trim()
        val digitsOrSeparators = compact.filter { it.isDigit() || it == '.' || it == ',' }
        if (digitsOrSeparators.none { it.isDigit() }) return null

        val commaIndex = digitsOrSeparators.lastIndexOf(',')
        val majorPart: String
        val centsPart: String?
        if (commaIndex >= 0) {
            majorPart = digitsOrSeparators.substring(0, commaIndex)
            centsPart = digitsOrSeparators.substring(commaIndex + 1)
        } else {
            majorPart = digitsOrSeparators
            centsPart = null
        }
        val majorDigits = majorPart.filter { it.isDigit() }
        if (majorDigits.isEmpty()) return null
        val centsDigits = centsPart
            ?.filter { it.isDigit() }
            ?.padEnd(2, '0')
            ?.take(2)
            ?: "00"

        val major = majorDigits.toLongOrNull() ?: return null
        val cents = centsDigits.toIntOrNull() ?: return null
        if (cents !in 0..99) return null
        return Money.ofMajor(major, cents)
    }

    /**
     * Parses the observed date formats into an instant in Asia/Jakarta (BR-01). Returns null for
     * anything unrecognized so the row becomes `MALFORMED_EVENT_TIME`, never a guessed time.
     */
    internal fun parseEventTime(raw: String): Instant? {
        val value = raw.trim()
            .replace("WIB", "", ignoreCase = true)
            .replace("Waktu Indonesia Barat", "", ignoreCase = true)
            .trim()
        for (formatter in DATE_TIME_FORMATS) {
            val parsed = runCatching { LocalDateTime.parse(value, formatter) }.getOrNull()
            if (parsed != null) {
                return Instant.fromEpochMilliseconds(parsed.atZone(jakarta).toInstant().toEpochMilli())
            }
        }
        return null
    }

    private companion object {
        const val CURRENCY_IDR = "IDR"
        const val ACQUIRER_MISSING_PENALTY = 0.1f

        /** Observed shapes: `03 Feb 2026 14:22:07`, `03/02/2026 14:22`, `2026-02-03 14:22:07`. */
        val DATE_TIME_PATTERNS: List<String> = listOf(
            "dd MMM uuuu HH:mm:ss",
            "dd MMM uuuu HH:mm",
            "dd MMMM uuuu HH:mm:ss",
            "dd MMMM uuuu HH:mm",
            "dd/MM/uuuu HH:mm:ss",
            "dd/MM/uuuu HH:mm",
            "uuuu-MM-dd HH:mm:ss",
            "uuuu-MM-dd HH:mm",
        )

        val DATE_TIME_FORMATS: List<DateTimeFormatter> = DATE_TIME_PATTERNS.flatMap { pattern ->
            listOf(Locale.forLanguageTag("id-ID"), Locale.ENGLISH).map { locale ->
                DateTimeFormatter.ofPattern(pattern, locale)
            }
        }
    }
}
