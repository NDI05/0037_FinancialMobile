package com.pam.recap.domain.usecase

import com.pam.recap.core.result.Outcome
import com.pam.recap.core.time.JakartaMonth
import com.pam.recap.domain.model.ImportJob
import com.pam.recap.domain.model.ImportState
import com.pam.recap.domain.model.TransactionSource
import com.pam.recap.domain.repository.ServerFinanceRepository
import com.pam.recap.domain.repository.ServerImportCandidate
import com.pam.recap.domain.repository.TransactionRepository
import javax.inject.Inject

/**
 * Uploads one completed local parse to the Go import job/batch API. Room remains the source for the
 * local import journal; the transaction/summary UI reads Go. Batch keys are deterministic per local
 * job/page, so a retried upload is idempotent at the server.
 */
class ServerImportUploader @Inject constructor(
    private val server: ServerFinanceRepository,
    private val transactions: TransactionRepository,
) {
    suspend operator fun invoke(
        ownerId: String,
        job: ImportJob,
    ): Outcome<String> {
        if (!server.isEnabled) return Outcome.Success(job.id)
        val serverJob = when (val created = server.createImportJob(ownerId, job.month, job.templateVersion)) {
            is Outcome.Success -> created.value
            is Outcome.Failure -> return created
        }
        val categories = (server.categories(ownerId) as? Outcome.Success)?.value.orEmpty()
        val categoryIds = categories.associate { it.code to it.id }
        val rows = transactions.listMonth(ownerId, job.month)
            .filter { it.source == TransactionSource.EMAIL }
        var matched = 0
        var imported = 0
        var duplicates = 0
        var skipped = 0
        rows.chunked(100).forEachIndexed { page, chunk ->
            val candidates = chunk.map { transaction ->
                ServerImportCandidate(
                    fingerprint = transaction.fingerprint,
                    status = "PARSED",
                    direction = transaction.direction.name,
                    amountMinor = transaction.amount.minor,
                    eventAtEpochMilliseconds = transaction.eventAt.toEpochMilliseconds(),
                    categoryId = categoryIds[transaction.category.code],
                    parserVersion = transaction.parserVersion,
                    confidence = transaction.parserConfidence.toDouble(),
                    description = transaction.description,
                    merchantLabel = transaction.merchantLabel,
                )
            }
            when (val result = server.importBatch(
                ownerId, serverJob.id, "${job.id}-page-$page", page, candidates,
            )) {
                is Outcome.Success -> {
                    matched += result.value.matched
                    imported += result.value.imported
                    duplicates += result.value.duplicates
                    skipped += result.value.skipped
                }
                is Outcome.Failure -> return result
            }
        }
        val finalState = when (job.state) {
            ImportState.COMPLETE -> "COMPLETE"
            ImportState.EMPTY -> "EMPTY"
            ImportState.PARTIAL -> "PARTIAL"
            ImportState.FAILED -> "FAILED"
            ImportState.PERMISSION_REQUIRED -> "PERMISSION_REQUIRED"
            else -> "PARTIAL"
        }
        return when (val finalized = server.finalizeImportJob(ownerId, serverJob.id, finalState)) {
            is Outcome.Success -> Outcome.Success(finalized.value.id)
            is Outcome.Failure -> finalized
        }
    }
}
