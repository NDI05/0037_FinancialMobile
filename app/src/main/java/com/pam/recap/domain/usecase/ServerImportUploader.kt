package com.pam.recap.domain.usecase

import com.pam.recap.core.result.AppError
import com.pam.recap.core.result.Outcome
import com.pam.recap.core.result.SafeLogger
import com.pam.recap.core.time.JakartaMonth
import com.pam.recap.data.security.ImportUploadJournal
import com.pam.recap.domain.model.ImportJob
import com.pam.recap.domain.model.ImportState
import com.pam.recap.domain.model.Transaction
import com.pam.recap.domain.model.TransactionSource
import com.pam.recap.domain.repository.ServerFinanceRepository
import com.pam.recap.domain.repository.ServerImportCandidate
import com.pam.recap.domain.repository.ServerImportJob
import com.pam.recap.domain.repository.TransactionRepository
import javax.inject.Inject

fun interface ImportUploader {
    suspend fun upload(ownerId: String, job: ImportJob): Outcome<String>
}

/**
 * Uploads one completed local parse to the Go import job/batch API.
 *
 * The candidate set is the immutable run snapshot ([TransactionRepository.listByImportJob]), not
 * "every EMAIL row of job.month": a routine sync must never re-upload history, and a late email
 * whose transaction date is in another month must land in that month's server job. Rows are grouped
 * by their own transaction month, each month gets its own server job, and every batch key/body is
 * deterministic per (run, month, page) so a retry is idempotent.
 *
 * Success means every month of the run was acknowledged. A failure on any month returns a failure,
 * so the caller keeps the cursor where it is.
 */
class ServerImportUploader @Inject constructor(
    private val server: ServerFinanceRepository,
    private val transactions: TransactionRepository,
    private val journal: ImportUploadJournal? = null,
) : ImportUploader {

    override suspend fun upload(ownerId: String, job: ImportJob): Outcome<String> = invoke(ownerId, job)

    suspend operator fun invoke(ownerId: String, job: ImportJob): Outcome<String> {
        SafeLogger.event("ServerImportUploader", "upload_start")
        if (!server.isEnabled) return Outcome.Success(job.id)

        val rows = transactions.listByImportJob(ownerId, job.id)
            .filter { it.source == TransactionSource.EMAIL }
            .sortedBy { it.fingerprint }
        val byMonth: Map<String, List<Transaction>> = rows.groupBy { JakartaMonth.of(it.eventAt).id() }
        // An empty run still closes its primary month so the server job does not stay RUNNING.
        val months = if (byMonth.isEmpty()) mapOf(job.month.id() to emptyList()) else byMonth

        val entry = journal?.read(ownerId, job.id)
            ?: ImportUploadJournal.Entry(ownerId, job.id, emptyMap())
        val state = entry.months.toMutableMap()

        val categories = (server.categories(ownerId) as? Outcome.Success)?.value.orEmpty()
        val categoryIds = categories.associate { it.code to it.id }

        var lastJobId = job.id
        for (monthId in months.keys.sorted()) {
            val monthRows = months.getValue(monthId)
            val month = JakartaMonth.parse(monthId) ?: job.month
            val known = state[monthId]
            val serverJob = if (known != null) {
                ServerImportJob(known.serverJobId, monthId, known.finalState)
            } else {
                val created = when (val result = server.createImportJob(ownerId, month, job.templateVersion)) {
                    is Outcome.Success -> result.value
                    is Outcome.Failure -> return result
                }
                state[monthId] = ImportUploadJournal.MonthJob(monthId, created.id, "RUNNING")
                if (journal != null && !journal.save(ImportUploadJournal.Entry(ownerId, job.id, state.toMap()))) {
                    return Outcome.Failure(AppError.Storage("import_journal_not_saved"))
                }
                created
            }
            lastJobId = serverJob.id
            monthRows.chunked(PAGE_SIZE).forEachIndexed { page, chunk ->
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
                // Deterministic per run *and* month, so a retry after a partial acknowledgement
                // replays the same key and the same body.
                val batchKey = "${job.id}-$monthId-page-$page"
                when (val result = server.importBatch(ownerId, serverJob.id, batchKey, page, candidates)) {
                    is Outcome.Success -> Unit
                    is Outcome.Failure -> return result
                }
            }
            val finalState = when {
                monthRows.isEmpty() -> "EMPTY"
                else -> when (job.state) {
                    ImportState.COMPLETE -> "COMPLETE"
                    ImportState.EMPTY -> "EMPTY"
                    ImportState.PARTIAL -> "PARTIAL"
                    ImportState.FAILED -> "FAILED"
                    ImportState.PERMISSION_REQUIRED -> "PERMISSION_REQUIRED"
                    else -> "PARTIAL"
                }
            }
            when (val finalized = server.finalizeImportJob(ownerId, serverJob.id, finalState, job.errorCode)) {
                is Outcome.Success -> {
                    state[monthId] = ImportUploadJournal.MonthJob(monthId, finalized.value.id, finalState)
                    if (journal != null && !journal.save(ImportUploadJournal.Entry(ownerId, job.id, state.toMap()))) {
                        return Outcome.Failure(AppError.Storage("import_journal_not_saved"))
                    }
                }
                is Outcome.Failure -> return finalized
            }
        }
        journal?.clear(ownerId, job.id)
        return Outcome.Success(lastJobId)
    }

    private companion object {
        const val PAGE_SIZE = 100
    }
}
