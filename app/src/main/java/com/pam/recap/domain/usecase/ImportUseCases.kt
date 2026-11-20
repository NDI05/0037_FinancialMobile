package com.pam.recap.domain.usecase

import com.pam.recap.core.result.AppError
import com.pam.recap.core.result.AppErrorRules
import com.pam.recap.core.result.Outcome
import com.pam.recap.core.result.SafeLogger
import com.pam.recap.core.security.FingerprintFactory
import com.pam.recap.core.security.FingerprintInput
import com.pam.recap.core.time.ClockProvider
import com.pam.recap.core.time.JakartaMonth
import com.pam.recap.core.util.IdGenerator
import com.pam.recap.domain.model.CategoryOrigin
import com.pam.recap.domain.model.ImportJob
import com.pam.recap.domain.model.ImportState
import com.pam.recap.domain.model.ParseResult
import com.pam.recap.domain.model.ParseStatus
import com.pam.recap.domain.model.RedactionReason
import com.pam.recap.domain.model.SkippedCandidate
import com.pam.recap.domain.model.Transaction
import com.pam.recap.domain.model.TransactionSource
import com.pam.recap.domain.repository.AnalysisRepository
import com.pam.recap.domain.repository.DeletionRepository
import com.pam.recap.domain.repository.GmailConnectionRepository
import com.pam.recap.domain.repository.GmailSource
import com.pam.recap.domain.repository.ImportRepository
import com.pam.recap.domain.repository.LivinNotificationParser
import com.pam.recap.domain.repository.LivinQueryFactory
import com.pam.recap.domain.repository.SummaryRepository
import com.pam.recap.domain.repository.TransactionRepository
import javax.inject.Inject
import kotlin.time.Instant

/**
 * FR-16…FR-22 import pipeline:
 * paginate the allow-listed query → parse in memory → fingerprint → dedupe → insert atomically
 * per batch → write the job and the redacted skipped candidates → mark dependents outdated.
 *
 * Guarantees:
 * - the message id never leaves memory: only the HMAC [FingerprintFactory] digest is stored;
 * - a batch is inserted in one transaction, so an interrupted page cannot half-write a month;
 * - a failed page 2 keeps page 1 and reports `PARTIAL` instead of losing data (FR-21);
 * - a revoked grant reports `PERMISSION_REQUIRED` so the UI can offer reconnect (FR-07);
 * - "no matching email" reports `EMPTY`, never a zero-spending claim (FR-20);
 * - a fingerprint collision is flagged for review instead of silently dropped (FR-18).
 */
class ImportMonthUseCase @Inject constructor(
    private val gmailSource: GmailSource,
    private val queryBuilder: LivinQueryFactory,
    private val parser: LivinNotificationParser,
    private val fingerprints: FingerprintFactory,
    private val categoryRules: MerchantCategoryRules,
    private val transactions: TransactionRepository,
    private val imports: ImportRepository,
    private val summaries: SummaryRepository,
    private val analyses: AnalysisRepository,
    private val gmailConnection: GmailConnectionRepository,
    private val deletion: DeletionRepository,
    private val clock: ClockProvider,
    private val idGenerator: IdGenerator,
) {

    suspend operator fun invoke(
        ownerId: String,
        month: JakartaMonth,
        templateVersion: String? = null,
    ): Outcome<ImportJob> {
        if (deletion.active(ownerId) != null) {
            // FR-47: sync is blocked while a deletion request is in flight.
            return Outcome.Failure(AppError.Validation(AppErrorRules.DELETION_IN_PROGRESS))
        }

        val startedAt = clock.now()
        val jobId = idGenerator.newId()
        val window = month.searchRange()
        val query = queryBuilder.build(window.startInclusive, window.endExclusive, templateVersion)

        var job = ImportJob(
            id = jobId,
            ownerId = ownerId,
            month = month,
            state = ImportState.CREATED,
            pagesProcessed = 0,
            pagesExpected = null,
            matchedCount = 0,
            importedCount = 0,
            duplicateCount = 0,
            skippedCount = 0,
            unresolvedCount = 0,
            templateVersion = query.templateVersion,
            startedAt = startedAt,
            finishedAt = null,
            errorCode = null,
        )
        imports.create(job)
        job = job.copy(state = ImportState.RUNNING)
        imports.update(job)

        var pageToken: String? = null
        var pageOrdinal = 0
        var matched = 0
        var imported = 0
        var duplicates = 0
        var skipped = 0
        var unresolved = 0
        var failureState: ImportState? = null
        var failureCode: String? = null
        val skippedCandidates = mutableListOf<SkippedCandidate>()

        pageLoop@ while (true) {
            when (val page = gmailSource.search(query, pageToken)) {
                is Outcome.Failure -> {
                    failureState = if (page.error is AppError.PermissionRequired) {
                        ImportState.PERMISSION_REQUIRED
                    } else if (pageOrdinal == 0) {
                        ImportState.FAILED
                    } else {
                        // FR-21: keep what page 1 delivered and let the user retry.
                        ImportState.PARTIAL
                    }
                    failureCode = page.error.kind.name.lowercase()
                    break@pageLoop
                }

                is Outcome.Success -> {
                    val batch = mutableListOf<Transaction>()
                    for ((index, ref) in page.value.messages.withIndex()) {
                        when (val fetched = gmailSource.fetchMessage(ref.id)) {
                            is Outcome.Failure -> {
                                unresolved++
                            }

                            is Outcome.Success -> {
                                // Any allow-listed message that reached the parser counts as a
                                // match, even when it is then rejected: an all-rejected page is a
                                // complete import with skips, never "no matching email" (FR-20).
                                matched++
                                when (val parsed = parser.parse(fetched.value)) {
                                    is ParseResult.Unresolved -> {
                                        // The reason enum is safe to log: it names the failed rule,
                                        // never the message content.
                                        SafeLogger.counts(
                                            TAG,
                                            "import_skip_reason",
                                            parsed.reason.name.lowercase() to 1,
                                        )
                                        skipped++
                                        skippedCandidates += skippedCandidate(
                                            jobId = jobId,
                                            ownerId = ownerId,
                                            reason = parsed.reason,
                                            pageOrdinal = pageOrdinal,
                                            candidateOrdinal = index,
                                        )
                                    }

                                    is ParseResult.Parsed -> {
                                        if (!month.contains(parsed.eventAt)) {
                                            // The search window is widened; membership is not (BR-01).
                                            SafeLogger.counts(
                                                TAG,
                                                "import_skip_reason",
                                                "not_in_month" to 1,
                                            )
                                            skipped++
                                            skippedCandidates += skippedCandidate(
                                                jobId = jobId,
                                                ownerId = ownerId,
                                                reason = RedactionReason.NOT_IN_MONTH,
                                                pageOrdinal = pageOrdinal,
                                                candidateOrdinal = index,
                                            )
                                        } else {
                                            batch += parsed.toTransaction(
                                                ownerId = ownerId,
                                                jobId = jobId,
                                                now = clock.now(),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    val dedupedBatch = batch.distinctBy { it.fingerprint }
                    duplicates += batch.size - dedupedBatch.size

                    if (dedupedBatch.isNotEmpty()) {
                        val result = transactions.insertIgnoringDuplicates(ownerId, dedupedBatch)
                        imported += result.inserted
                        duplicates += result.ignored
                        if (result.ignored > 0) {
                            for (candidate in dedupedBatch) {
                                val existing = transactions.findByFingerprint(ownerId, candidate.fingerprint)
                                    ?: continue
                                if (!existing.hasSameContentAs(candidate)) {
                                    // Same digest, different content: keep both facts and flag it.
                                    skipped++
                                    skippedCandidates += skippedCandidate(
                                        jobId = jobId,
                                        ownerId = ownerId,
                                        reason = RedactionReason.FINGERPRINT_COLLISION,
                                        pageOrdinal = pageOrdinal,
                                        candidateOrdinal = 0,
                                    )
                                }
                            }
                        }
                    }

                    pageOrdinal++
                    SafeLogger.counts(
                        TAG,
                        "import_page",
                        "page" to pageOrdinal,
                        "fetched" to page.value.messages.size,
                        "saved" to imported,
                        "duplicate" to duplicates,
                        "skipped" to skipped,
                        "unresolved" to unresolved,
                    )
                    pageToken = page.value.nextPageToken
                    job = job.copy(
                        pagesProcessed = pageOrdinal,
                        matchedCount = matched,
                        importedCount = imported,
                        duplicateCount = duplicates,
                        skippedCount = skipped,
                        unresolvedCount = unresolved,
                    )
                    imports.update(job)

                    if (pageToken == null) break@pageLoop
                }
            }
        }

        if (skippedCandidates.isNotEmpty()) {
            imports.saveSkipped(skippedCandidates)
        }

        val finalState = failureState ?: if (matched == 0) ImportState.EMPTY else ImportState.COMPLETE
        val finishedAt = clock.now()
        job = job.copy(
            state = finalState,
            pagesProcessed = pageOrdinal,
            matchedCount = matched,
            importedCount = imported,
            duplicateCount = duplicates,
            skippedCount = skipped,
            unresolvedCount = unresolved,
            finishedAt = finishedAt,
            errorCode = failureCode,
        )
        imports.update(job)

        if (imported > 0) {
            // FR-22/FR-33: stored summaries and analyses no longer reflect the inputs.
            summaries.markOutdated(ownerId, month)
            analyses.markOutdated(ownerId, month.id())
        }
        if (finalState != ImportState.FAILED && finalState != ImportState.PERMISSION_REQUIRED) {
            runCatching { gmailConnection.touchLastImport(ownerId, finishedAt) }
        }

        SafeLogger.event(TAG, "import_finished_${finalState.name.lowercase()}")
        return Outcome.Success(job)
    }

    private fun ParseResult.Parsed.toTransaction(ownerId: String, jobId: String, now: Instant): Transaction {
        val assignment = categoryRules.classify(description, merchantLabel)
        val fingerprint = fingerprints.fingerprint(
            ownerId = ownerId,
            input = FingerprintInput(
                messageId = messageId,
                amountMinor = amount.minor,
                eventAtEpochMilliseconds = eventAt.toEpochMilliseconds(),
                directionCode = direction.name,
                recipientLabel = merchantLabel,
            ),
        )
        return Transaction(
            id = idGenerator.newId(),
            ownerId = ownerId,
            direction = direction,
            source = TransactionSource.EMAIL,
            amount = amount,
            currency = currency,
            eventAt = eventAt,
            receivedAt = receivedAt,
            description = description,
            merchantLabel = merchantLabel,
            acquirer = acquirer,
            status = ParseStatus.POSTED,
            fingerprint = fingerprint,
            parserVersion = parserVersion,
            parserConfidence = confidence,
            category = assignment.category,
            categoryOrigin = CategoryOrigin.RULE,
            categoryConfidence = assignment.confidence,
            categoryUpdatedAt = null,
            isInternalTransfer = assignment.category.isTransfer,
            linkedRefundOf = null,
            note = null,
            importJobId = jobId,
            revision = 0,
            createdAt = now,
            updatedAt = now,
        )
    }

    private fun skippedCandidate(
        jobId: String,
        ownerId: String,
        reason: RedactionReason,
        pageOrdinal: Int,
        candidateOrdinal: Int,
    ): SkippedCandidate = SkippedCandidate(
        id = idGenerator.newId(),
        jobId = jobId,
        ownerId = ownerId,
        reason = reason,
        pageOrdinal = pageOrdinal,
        candidateOrdinal = candidateOrdinal,
        createdAt = clock.now(),
    )

    /** FR-18 collision test: same digest but a different amount/time/direction/recipient. */
    private fun Transaction.hasSameContentAs(other: Transaction): Boolean =
        amount == other.amount &&
            eventAt == other.eventAt &&
            direction == other.direction &&
            merchantLabel == other.merchantLabel

    private companion object {
        const val TAG = "ImportMonth"
    }
}

/**
 * FR-21 retry. The pipeline is idempotent (fingerprint unique index), so a retry can simply run
 * again: rows from the previous attempt are kept and only new rows are inserted. The previous
 * job stays in the history as `PARTIAL` for auditability.
 */
class RetryImportUseCase @Inject constructor(
    private val importMonth: ImportMonthUseCase,
    private val imports: ImportRepository,
) {

    suspend operator fun invoke(ownerId: String, month: JakartaMonth): Outcome<ImportJob> {
        val previous = imports.latestForMonth(ownerId, month)
        if (previous != null && previous.state == ImportState.COMPLETE) {
            // Nothing to repair: a re-run would only produce duplicates.
            return Outcome.Success(previous)
        }
        return importMonth(ownerId, month, previous?.templateVersion)
    }
}
