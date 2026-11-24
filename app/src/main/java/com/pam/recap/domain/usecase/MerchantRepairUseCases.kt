package com.pam.recap.domain.usecase

import com.pam.recap.core.result.Outcome
import com.pam.recap.core.result.SafeLogger
import com.pam.recap.core.security.FingerprintFactory
import com.pam.recap.core.security.FingerprintInput
import com.pam.recap.core.time.ClockProvider
import com.pam.recap.core.time.JakartaMonth
import com.pam.recap.domain.model.ParseResult
import com.pam.recap.domain.model.Transaction
import com.pam.recap.domain.model.TransactionSource
import com.pam.recap.domain.repository.GmailMessageRef
import com.pam.recap.domain.repository.GmailSource
import com.pam.recap.domain.repository.LivinNotificationParser
import com.pam.recap.domain.repository.LivinQueryFactory
import com.pam.recap.domain.repository.ServerFinanceRepository
import com.pam.recap.domain.repository.TransactionPatch
import com.pam.recap.domain.repository.TransactionRepository
import javax.inject.Inject
import javax.inject.Singleton

/** Sanitised, counts-only result of one repair pass. */
data class MerchantRepairReport(
    val repaired: Int = 0,
    val ambiguous: Int = 0,
    val skipped: Int = 0,
    val failed: Int = 0,
    val alreadyDone: Boolean = false,
) {
    val touched: Boolean get() = repaired > 0
}

/**
 * P0 repair pass for rows imported before the Livin parser preferred `Penerima` over the Merchant
 * PAN. A 19-digit identifier was stored as the merchant label; this pass re-reads the month window
 * once (ignoring the incremental cursor), proves which notification produced each row, and asks the
 * server to replace only the label.
 *
 * Safety properties:
 * - once per owner+month through a durable marker, so ordinary syncs never re-fetch the month;
 * - identity is proven locally by recomputing the stored device fingerprint from the message ref
 *   and the row's own amount/event/direction/old label; zero or several matches mean "needs review"
 *   and no write happens;
 * - the server is the only writer of the replacement fingerprint: the PATCH carries the stored
 *   fingerprint as a CAS proof plus the opaque `UIDVALIDITY:UID`, and the server re-derives the new
 *   value from its own canonical fields;
 * - nothing destructive: no row is created or deleted, and category/note/attributes/status are not
 *   part of the request;
 * - bounded: at most [MAX_EMAIL_REFS] message references and [MAX_SERVER_PAGES] server pages.
 */
@Singleton
class MerchantRepairUseCase @Inject constructor(
    private val gmail: GmailSource,
    private val queryFactory: LivinQueryFactory,
    private val parser: LivinNotificationParser,
    private val fingerprints: FingerprintFactory,
    private val serverFinance: ServerFinanceRepository,
    private val transactions: TransactionRepository,
    private val clock: ClockProvider,
) {

    suspend fun runIfNeeded(ownerId: String, month: JakartaMonth): Outcome<MerchantRepairReport> {
        if (!serverFinance.isEnabled) {
            return Outcome.Success(MerchantRepairReport(alreadyDone = true))
        }
        val markerKey = MARKER_PREFIX + month.id()
        if (transactions.metadataValue(ownerId, markerKey) == MARKER_DONE) {
            return Outcome.Success(MerchantRepairReport(alreadyDone = true))
        }
        val candidates = transactions.listMonth(ownerId, month).filter(::isRepairCandidate)
        if (candidates.isEmpty()) {
            transactions.putMetadata(ownerId, markerKey, MARKER_DONE)
            return Outcome.Success(MerchantRepairReport())
        }
        val serverRows = when (val page = serverMonth(ownerId, month)) {
            is Outcome.Failure -> return page
            is Outcome.Success -> page.value
        }
        val refs = when (val page = monthRefs(month)) {
            is Outcome.Failure -> return page
            is Outcome.Success -> page.value
        }
        var repaired = 0
        var ambiguous = 0
        var skipped = 0
        var failed = 0
        val repairedIds = mutableListOf<String>()
        for (row in candidates) {
            val matches = refs.filter { ref ->
                fingerprints.fingerprint(
                    ownerId,
                    FingerprintInput(
                        messageId = ref.id,
                        amountMinor = row.amount.minor,
                        eventAtEpochMilliseconds = row.eventAt.toEpochMilliseconds(),
                        directionCode = row.direction.name,
                        recipientLabel = row.merchantLabel,
                    ),
                ) == row.fingerprint
            }
            if (matches.size != 1) {
                // Zero matches means the row was produced by another mailbox generation; several
                // means the identity is not unique. Both fail closed into review, never a write.
                ambiguous++
                continue
            }
            val ref = matches.single()
            val newLabel = parseLabel(ref)
            if (newLabel == null || isIdentifierLabel(newLabel) || newLabel == row.merchantLabel) {
                skipped++
                continue
            }
            val serverRow = serverRows.singleOrNull { sameRow(it, row) }
            val expected = serverRow?.fingerprint
            if (serverRow == null || expected.isNullOrBlank()) {
                ambiguous++
                continue
            }
            val patch = TransactionPatch(
                revision = serverRow.revision,
                merchantLabel = newLabel,
                expectedFingerprint = expected,
                sourceRef = ref.id,
            )
            when (val patched = serverFinance.updateTransaction(ownerId, serverRow.id, patch)) {
                is Outcome.Failure -> failed++
                is Outcome.Success -> {
                    val corrected = patched.value.merchantLabel ?: newLabel
                    val localFingerprint = fingerprints.fingerprint(
                        ownerId,
                        FingerprintInput(
                            messageId = ref.id,
                            amountMinor = row.amount.minor,
                            eventAtEpochMilliseconds = row.eventAt.toEpochMilliseconds(),
                            directionCode = row.direction.name,
                            recipientLabel = corrected,
                        ),
                    )
                    val applied = transactions.applyMerchantRepair(
                        ownerId = ownerId,
                        id = row.id,
                        expectedFingerprint = row.fingerprint,
                        merchantLabel = corrected,
                        fingerprint = localFingerprint,
                        at = clock.now(),
                    )
                    if (applied) repaired++ else failed++
                    repairedIds += serverRow.id
                }
            }
        }
        if (repairedIds.isNotEmpty()) {
            // Best effort: a queue refusal must not undo or re-run the repair.
            runCatching { serverFinance.enqueueCategories(ownerId, repairedIds) }
        }
        SafeLogger.counts(
            TAG,
            "merchant_repair",
            "candidates" to candidates.size,
            "repaired" to repaired,
            "ambiguous" to ambiguous,
            "skipped" to skipped,
            "failed" to failed,
        )
        // A transient server failure must be retried, so the marker is only written for a clean
        // pass; ambiguity is terminal because repeating the same read cannot resolve it.
        if (failed == 0) transactions.putMetadata(ownerId, markerKey, MARKER_DONE)
        return Outcome.Success(
            MerchantRepairReport(
                repaired = repaired,
                ambiguous = ambiguous,
                skipped = skipped,
                failed = failed,
            ),
        )
    }

    private suspend fun serverMonth(ownerId: String, month: JakartaMonth): Outcome<List<Transaction>> {
        val rows = mutableListOf<Transaction>()
        var cursor: String? = null
        repeat(MAX_SERVER_PAGES) {
            when (val page = serverFinance.listMonth(ownerId, month, limit = MAX_SERVER_PAGE_SIZE, cursor = cursor)) {
                is Outcome.Failure -> return page
                is Outcome.Success -> {
                    rows += page.value.items
                    cursor = page.value.nextCursor?.takeIf { it.isNotBlank() }
                    if (cursor == null) return Outcome.Success(rows)
                }
            }
        }
        return Outcome.Success(rows)
    }

    private suspend fun monthRefs(month: JakartaMonth): Outcome<List<GmailMessageRef>> {
        val range = month.range()
        val query = queryFactory.build(range.startInclusive, range.endExclusive, null)
        val refs = mutableListOf<GmailMessageRef>()
        var token: String? = null
        repeat(MAX_REF_PAGES) {
            when (val page = gmail.search(query.copy(pageSize = MAX_SERVER_PAGE_SIZE), token)) {
                is Outcome.Failure -> return page
                is Outcome.Success -> {
                    refs += page.value.messages
                    token = page.value.nextPageToken?.takeIf { it.isNotBlank() }
                    if (token == null || refs.size >= MAX_EMAIL_REFS) {
                        return Outcome.Success(refs.take(MAX_EMAIL_REFS))
                    }
                }
            }
        }
        return Outcome.Success(refs.take(MAX_EMAIL_REFS))
    }

    private suspend fun parseLabel(ref: GmailMessageRef): String? {
        val message = when (val fetched = gmail.fetchMessage(ref.id)) {
            is Outcome.Failure -> return null
            is Outcome.Success -> fetched.value
        }
        val parsed = parser.parse(message) as? ParseResult.Parsed ?: return null
        return parsed.merchantLabel?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun sameRow(server: Transaction, local: Transaction): Boolean =
        server.source == TransactionSource.EMAIL &&
            server.amount == local.amount &&
            server.eventAt == local.eventAt &&
            server.direction == local.direction &&
            server.merchantLabel == local.merchantLabel

    companion object {
        private const val TAG = "MerchantRepair"
        const val MARKER_PREFIX: String = "merchant_repair_v1_"
        const val MARKER_DONE: String = "DONE"
        private const val MAX_EMAIL_REFS = 100
        private const val MAX_REF_PAGES = 5
        private const val MAX_SERVER_PAGES = 3
        private const val MAX_SERVER_PAGE_SIZE = 100
        private val IDENTIFIER = Regex("^[0-9][0-9 .,/\\-]*$")
        private const val MIN_IDENTIFIER_DIGITS = 8

        /**
         * A merchant label is "an identifier, not a business" when it is only digits/separators and
         * long enough to be a PAN/reference. Only such rows are repair targets; a real name never
         * matches, so a healthy account does no work.
         */
        fun isIdentifierLabel(label: String?): Boolean {
            val value = label?.trim().orEmpty()
            if (value.isEmpty() || !IDENTIFIER.matches(value)) return false
            return value.count(Char::isDigit) >= MIN_IDENTIFIER_DIGITS
        }

        fun isRepairCandidate(row: Transaction): Boolean =
            row.source == TransactionSource.EMAIL && isIdentifierLabel(row.merchantLabel)
    }
}
