package com.pam.recap.domain.usecase

import com.pam.recap.core.result.AppError
import com.pam.recap.core.result.Outcome
import com.pam.recap.core.result.SafeLogger
import com.pam.recap.core.time.JakartaMonth
import com.pam.recap.data.security.ImportUploadJournal
import com.pam.recap.domain.model.ImportJob
import com.pam.recap.domain.model.ImportState
import com.pam.recap.domain.model.PendingUploadMonth
import com.pam.recap.domain.model.PendingUploadRun
import com.pam.recap.domain.model.Transaction
import com.pam.recap.domain.model.TransactionSource
import com.pam.recap.domain.repository.ServerFinanceRepository
import com.pam.recap.domain.repository.ServerImportCandidate
import com.pam.recap.domain.repository.ServerImportJob
import com.pam.recap.domain.repository.TransactionRepository
import javax.inject.Inject

fun interface ImportUploader {
    suspend fun upload(ownerId: String, job: ImportJob): Outcome<String>

    /**
     * Finishes the durable pending run for [ownerId], if one exists, and clears it only after every
     * month was acknowledged. The default is a no-op so simple doubles/test lambdas stay valid; the
     * real uploader always overrides it.
     */
    suspend fun resumePending(ownerId: String): Outcome<String> = Outcome.Success("no_pending")
}

/**
 * Uploads one committed local parse to the Go import job/batch API.
 *
 * The candidate set is the durable run snapshot, not "every EMAIL row of job.month": a routine sync
 * must never re-upload history, and a late email whose transaction date is in another month must
 * land in that month's server job. Rows are grouped by their own transaction month, each month gets
 * its own server job, and every batch key/body is deterministic per (run, month, page) so a retry
 * is idempotent.
 *
 * Two things make a failed upload recoverable without user action:
 * - the run identity is read from [ImportUploadJournal]'s pending record, not only from `job.id`,
 *   so the next tick finishes the OLD run instead of a fresh (deduped-to-empty) local job;
 * - a month already acknowledged by the server is never sent again, and a month still running keeps
 *   its server job id and batch keys, so the server dedupes the replay.
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
        if (!server.isEnabled) {
            // A build without a backend cannot acknowledge anything. Reporting success here used to
            // let the caller clear the pending record and advance the watermark over rows that never
            // reached a server; an honest configuration failure keeps the work recoverable.
            return if (journal == null) {
                Outcome.Success(job.id)
            } else {
                Outcome.Failure(AppError.Configuration("backend_not_configured"))
            }
        }

        // Never acknowledge an older unreconciled run through a newer (possibly EMPTY) job: Room
        // dedupe keeps the old rows on the old importJobId, so an empty new snapshot says nothing
        // about them. Drain the old run first; only then may this job be uploaded.
        val prior = journal?.readPending(ownerId)
        if (prior != null && prior.localJobId != job.id) {
            SafeLogger.event("ServerImportUploader", "pending_drain_before_new_job")
            val priorRun = prior.toRunOrNull()
                ?: return Outcome.Failure(AppError.Storage("pending_journal_corrupt"))
            when (val drained = uploadRun(ownerId, priorRun)) {
                is Outcome.Success -> Unit
                is Outcome.Failure -> return drained
            }
        }
        val own = journal?.readPending(ownerId)?.takeIf { it.localJobId == job.id }
        return uploadRun(ownerId, Run(job.id, job.month, job.state, job.templateVersion, job.errorCode, own))
    }

    override suspend fun resumePending(ownerId: String): Outcome<String> {
        val pending = journal?.readPending(ownerId) ?: return Outcome.Success("no_pending")
        SafeLogger.event("ServerImportUploader", "pending_resume")
        if (!server.isEnabled) {
            // Never drop recorded work just because this build has no backend address. The record
            // stays durable and the caller reports a configuration failure instead of advancing.
            return Outcome.Failure(AppError.Configuration("backend_not_configured"))
        }
        val run = pending.toRunOrNull()
            ?: return Outcome.Failure(AppError.Storage("pending_journal_corrupt"))
        return uploadRun(ownerId, run)
    }

    private suspend fun uploadRun(ownerId: String, run: Run): Outcome<String> {
        if (!server.isEnabled) {
            return if (journal == null) {
                Outcome.Success(run.localJobId)
            } else {
                Outcome.Failure(AppError.Configuration("backend_not_configured"))
            }
        }
        val snapshot = transactions.listByImportJob(ownerId, run.localJobId)
            .filter { it.source == TransactionSource.EMAIL }
            .sortedBy { it.fingerprint }
        val byMonth: Map<String, List<Transaction>> = snapshot.groupBy { JakartaMonth.of(it.eventAt).id() }
        var pending = run.pending

        // The durable pending record is authoritative for "which months exist", because an EMPTY
        // run has no rows but still has a primary month to close. A pre-commit intent records only
        // the primary month, so the snapshot's own months (late emails routed backwards) are added;
        // otherwise their rows would be acked by association and never sent. Without any pending
        // record, fall back to the snapshot's months (legacy path used by ServerImportUploaderTest).
        val monthIds = when {
            pending != null && pending.months.isNotEmpty() ->
                (pending.months.keys + byMonth.keys).sorted()
            byMonth.isNotEmpty() -> byMonth.keys.sorted()
            else -> listOf(run.month.id())
        }

        val entry = journal?.read(ownerId, run.localJobId)
            ?: ImportUploadJournal.Entry(ownerId, run.localJobId, emptyMap())
        val state = entry.months.toMutableMap()

        val categories = (server.categories(ownerId) as? Outcome.Success)?.value.orEmpty()
        val categoryIds = categories.associate { it.code to it.id }

        var lastJobId = run.localJobId
        for (monthId in monthIds) {
            val pendingMonth = pending?.months?.get(monthId)
            val known = state[monthId]

            // A month the server already acknowledged is never re-sent. Its id is still carried so
            // a later failure cannot make the caller report a different month.
            val acknowledged = (pendingMonth?.acknowledged == true) ||
                (known != null && known.finalState != RUNNING_STATE)
            if (acknowledged) {
                lastJobId = known?.serverJobId ?: pendingMonth?.serverJobId ?: lastJobId
                continue
            }

            val month = JakartaMonth.parse(monthId) ?: run.month
            // A pending record promises an exact candidate set. If those rows are no longer in the
            // local snapshot, uploading whatever remains (or nothing at all) would let the ack clear
            // the record and the cursor skip candidates that never reached the server. Refuse, keep
            // the record, and let a retry rebuild the snapshot instead of inventing an EMPTY success.
            val promised = pendingMonth?.fingerprints.orEmpty().toSet()
            if (promised.isNotEmpty()) {
                val actual = byMonth[monthId].orEmpty().map { it.fingerprint }.toSet()
                if (!actual.containsAll(promised)) {
                    SafeLogger.event("ServerImportUploader", "pending_snapshot_incomplete")
                    return Outcome.Failure(AppError.Storage("pending_snapshot_incomplete"))
                }
            }
            val monthRows = byMonth[monthId].orEmpty().filter { transaction ->
                pendingMonth == null ||
                    pendingMonth.fingerprints.isEmpty() ||
                    transaction.fingerprint in pendingMonth.fingerprints
            }

            val serverJob = if (known != null && known.finalState == RUNNING_STATE) {
                ServerImportJob(known.serverJobId, monthId, known.finalState)
            } else {
                val created = when (val result = server.createImportJob(ownerId, month, run.templateVersion)) {
                    is Outcome.Success -> result.value
                    is Outcome.Failure -> return result
                }
                state[monthId] = ImportUploadJournal.MonthJob(monthId, created.id, RUNNING_STATE)
                if (journal != null && !journal.save(ImportUploadJournal.Entry(ownerId, run.localJobId, state.toMap()))) {
                    return Outcome.Failure(AppError.Storage("import_journal_not_saved"))
                }
                // Remember the server job in the pending record too: a crash before the first batch
                // must not create a second server job for the same month.
                val currentPending = pending
                if (currentPending != null) {
                    pending = persistPendingServerJob(currentPending, monthId, created.id, monthRows)
                        ?: return Outcome.Failure(AppError.Storage("import_journal_not_saved"))
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
                val batchKey = "${run.localJobId}-$monthId-page-$page"
                when (val result = server.importBatch(ownerId, serverJob.id, batchKey, page, candidates)) {
                    is Outcome.Success -> Unit
                    is Outcome.Failure -> return result
                }
            }

            val finalState = when {
                monthRows.isEmpty() -> "EMPTY"
                else -> when (run.state) {
                    ImportState.COMPLETE -> "COMPLETE"
                    ImportState.EMPTY -> "EMPTY"
                    ImportState.PARTIAL -> "PARTIAL"
                    ImportState.FAILED -> "FAILED"
                    ImportState.PERMISSION_REQUIRED -> "PERMISSION_REQUIRED"
                    else -> "PARTIAL"
                }
            }
            when (val finalized = server.finalizeImportJob(ownerId, serverJob.id, finalState, run.errorCode)) {
                is Outcome.Success -> {
                    // Durable acknowledgement of this month BEFORE the cursor may move.
                    state[monthId] = ImportUploadJournal.MonthJob(monthId, finalized.value.id, finalState)
                    if (journal != null) {
                        if (!journal.save(ImportUploadJournal.Entry(ownerId, run.localJobId, state.toMap()))) {
                            return Outcome.Failure(AppError.Storage("import_journal_not_saved"))
                        }
                        val acked = pending?.acknowledged(monthId, finalized.value.id, finalState)
                        if (acked != null && !journal.savePending(acked)) {
                            return Outcome.Failure(AppError.Storage("import_journal_not_saved"))
                        }
                        pending = acked
                    }
                    lastJobId = finalized.value.id
                }
                is Outcome.Failure -> return finalized
            }
        }

        // The run is only finished when every planned month is acknowledged. Re-derive that from the
        // durable journal as well, so a failed pending-record update cannot strand it forever.
        val allAcknowledged = monthIds.all { monthId ->
            pending?.months?.get(monthId)?.acknowledged == true ||
                state[monthId]?.finalState?.let { it != RUNNING_STATE } == true
        }
        if (allAcknowledged) {
            journal?.clearPending(ownerId)
            journal?.clear(ownerId, run.localJobId)
        }
        return Outcome.Success(lastJobId)
    }

    /** Returns the updated pending record, or null when the durable write failed. */
    private fun persistPendingServerJob(
        pending: PendingUploadRun,
        monthId: String,
        serverJobId: String,
        monthRows: List<Transaction>,
    ): PendingUploadRun? {
        val current = pending.months[monthId]
            ?: PendingUploadMonth(monthId, monthRows.map { it.fingerprint }.sorted())
        val updated = pending.copy(
            months = pending.months + (monthId to current.copy(serverJobId = serverJobId)),
        )
        if (journal != null && !journal.savePending(updated)) return null
        return updated
    }

    private data class Run(
        val localJobId: String,
        val month: JakartaMonth,
        val state: ImportState,
        val templateVersion: String,
        val errorCode: String?,
        val pending: PendingUploadRun?,
    )

    private fun PendingUploadRun.toRunOrNull(): Run? {
        val month = JakartaMonth.parse(primaryMonthId) ?: return null
        val state = runCatching { ImportState.valueOf(localState) }.getOrDefault(ImportState.COMPLETE)
        return Run(localJobId, month, state, templateVersion, errorCode, this)
    }

    private companion object {
        const val PAGE_SIZE = 100
        const val RUNNING_STATE = "RUNNING"
    }
}
