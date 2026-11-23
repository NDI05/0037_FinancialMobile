package com.pam.recap.domain.usecase

import com.pam.recap.core.result.AppError
import com.pam.recap.core.result.AppErrorRules
import com.pam.recap.core.result.Outcome
import com.pam.recap.core.result.SafeLogger
import com.pam.recap.core.time.ClockProvider
import com.pam.recap.core.time.JakartaMonth
import com.pam.recap.data.security.ImportUploadJournal
import com.pam.recap.domain.model.AutoSyncConfig
import com.pam.recap.domain.model.ImportState
import com.pam.recap.domain.model.DeltaWindow
import com.pam.recap.domain.model.MailboxWatermark
import com.pam.recap.domain.model.PendingUploadMonth
import com.pam.recap.domain.model.PendingUploadRun
import com.pam.recap.domain.model.SyncCursor
import com.pam.recap.domain.model.SyncPhase
import com.pam.recap.domain.model.SyncSnapshot
import com.pam.recap.domain.model.TransactionSource
import com.pam.recap.domain.repository.DeletionRepository
import com.pam.recap.domain.model.ImportJob
import com.pam.recap.domain.repository.GmailSource
import com.pam.recap.domain.repository.ImportRepository
import com.pam.recap.domain.repository.SessionRepository
import com.pam.recap.domain.repository.SyncCursorStore
import com.pam.recap.domain.repository.TransactionRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

/** Why a check started. Manual, foreground and background share one gate and one cursor. */
enum class SyncTrigger { PERIODIC, FOREGROUND, MANUAL }

/** Result of one attempt; the UI never needs more than this to be honest. */
sealed interface SyncOutcome {
    data class Completed(val imported: Int, val duplicates: Int, val state: String) : SyncOutcome

    /** Another run already holds the single-flight gate; nothing was started. */
    data object AlreadyRunning : SyncOutcome

    /** Skipped on purpose: no session, deletion in flight, or the cooldown has not elapsed. */
    data class Skipped(val reason: String) : SyncOutcome

    data class Failed(val code: String) : SyncOutcome

    /**
     * The server acknowledged what was imported, but the mailbox range is not finished
     * (PARTIAL/FAILED/PERMISSION_REQUIRED or a missing watermark). The watermark deliberately did
     * not move, so the next run retries the same range instead of skipping the rest.
     */
    data class Incomplete(
        val imported: Int,
        val duplicates: Int,
        val state: String,
        val unresolved: Int,
    ) : SyncOutcome
}

/**
 * Observable status of the transaction pipeline. Written only by [IncrementalSyncUseCase]; read by
 * the top bar and the status sheet, which must never cause a request by being opened.
 */
@Singleton
class SyncStatusHolder @Inject constructor() {
    private val _snapshot = MutableStateFlow(SyncSnapshot())
    val snapshot: StateFlow<SyncSnapshot> = _snapshot.asStateFlow()

    fun update(transform: (SyncSnapshot) -> SyncSnapshot) = _snapshot.update(transform)

    fun reset() {
        _snapshot.value = SyncSnapshot()
    }
}

/**
 * Incremental (delta) sync: durable cursor → IMAP delta → financial commit → server upload.
 *
 * Guarantees:
 * - single flight. One process-wide mutex; a second trigger returns [SyncOutcome.AlreadyRunning]
 *   instead of importing the same delta twice;
 * - the cursor advances only after the server acknowledged the upload, so a crash between commit
 *   and upload re-runs the same delta (fingerprints make that idempotent) instead of losing mail;
 * - the delta is bounded by a watermark captured *before* the run, so nothing arriving mid-run is
 *   half-processed;
 * - no full-month rescan: a healthy run reads only what is above the cursor;
 * - a late email whose transaction date belongs to the previous month is routed there instead of
 *   being skipped;
 * - results for an owner who signed out (or switched) mid-run are discarded.
 */
@Singleton
class IncrementalSyncUseCase @Inject constructor(
    private val sessions: SessionRepository,
    private val gmail: GmailSource,
    private val importMonth: ImportMonthUseCase,
    private val uploader: ImportUploader,
    /**
     * Optional in tests; production binds the Room repository. It lets the manual import path reuse
     * the "nothing to retry when COMPLETE" decision without duplicating RetryImportUseCase.
     */
    private val imports: ImportRepository? = null,
    private val cursors: SyncCursorStore,
    private val deletion: DeletionRepository,
    private val clock: ClockProvider,
    private val status: SyncStatusHolder,
    private val config: AutoSyncConfig = AutoSyncConfig(),
    /**
     * Optional so existing tests keep compiling. After a server-acknowledged commit the freshly
     * imported rows are offered to the category queue; the 2-hour drain window still belongs to the
     * server, so this only avoids waiting a whole period for a candidate to become visible.
     */
    private val autoCategory: AutoCategoryUseCase? = null,
    /**
     * Optional in tests; production binds the Room repository. It reads back the committed snapshot
     * of a run so the durable pending record names the exact candidate fingerprints.
     */
    private val transactions: TransactionRepository? = null,
    /**
     * Durable identity of a committed-but-unacknowledged run. Without it a failed upload would be
     * retried against a fresh (deduped-to-empty) local job and never reach the server.
     */
    private val journal: ImportUploadJournal? = null,
) {

    private val gate = Mutex()
    private val running = AtomicBoolean(false)

    val snapshot: StateFlow<SyncSnapshot> get() = status.snapshot

    /** True while a run holds the gate; the UI uses this to show a spinner only for real work. */
    val isRunning: Boolean get() = running.get()

    /**
     * Manual "Impor" / repair path. It deliberately runs through the same gate, status holder and
     * uploader as foreground/background, so tapping the button while a periodic run is active can
     * never import the same window twice or leave two writers on one cursor.
     */
    suspend fun runManualMonth(month: JakartaMonth): Outcome<ImportJob> {
        val ownerId = sessions.current()?.ownerId
            ?: return Outcome.Failure(AppError.Configuration("not_signed_in"))
        if (deletion.active(ownerId) != null) {
            return Outcome.Failure(AppError.Validation(AppErrorRules.DELETION_IN_PROGRESS))
        }
        if (!gate.tryLock()) return Outcome.Failure(AppError.Conflict("sync_in_progress"))
        val startedAt = clock.now()
        status.update {
            it.copy(ownerId = ownerId, phase = SyncPhase.SYNCING, isRunning = true, lastAttemptAt = startedAt)
        }
        try {
            // Same recovery contract as the automatic path: a previous unacknowledged run must be
            // finished before this month is imported/uploaded again.
            when (val recovery = recoverPendingRun(ownerId, startedAt)) {
                is PendingRecovery.Failed -> {
                    val code = syncCode(recovery.error)
                    recordFailure(
                        ownerId,
                        cursors.read(ownerId) ?: SyncCursor(ownerId),
                        startedAt,
                        code,
                        uploadPending = true,
                    )
                    return Outcome.Failure(recovery.error)
                }
                PendingRecovery.Done, PendingRecovery.None -> Unit
            }
            val previous = imports?.latestForMonth(ownerId, month)
            if (previous != null && previous.state == ImportState.COMPLETE) {
                // Nothing to repair: a re-run would only re-read the same month.
                status.update { it.copy(phase = SyncPhase.SUCCEEDED, isRunning = false, lastSuccessAt = clock.now()) }
                return Outcome.Success(previous)
            }
            val job = when (
                val result = importMonth(
                    ownerId,
                    month,
                    previous?.templateVersion,
                    onJobCreated = { created ->
                        persistPendingIntent(ownerId, created, upTo = null, startedAt = startedAt)
                    },
                )
            ) {
                is Outcome.Failure -> {
                    val code = syncCode(result.error)
                    recordFailure(ownerId, cursors.read(ownerId) ?: SyncCursor(ownerId), startedAt, code)
                    return result
                }
                is Outcome.Success -> result.value
            }
            if (sessions.current()?.ownerId != ownerId) {
                return Outcome.Failure(AppError.Conflict("owner_changed"))
            }
            if (!persistPendingRun(ownerId, job, upTo = null, startedAt = startedAt)) {
                recordFailure(
                    ownerId,
                    cursors.read(ownerId) ?: SyncCursor(ownerId),
                    startedAt,
                    "storage_unavailable",
                    uploadPending = true,
                )
                return Outcome.Failure(AppError.Storage("storage_unavailable"))
            }
            when (val upload = uploader.upload(ownerId, job)) {
                is Outcome.Failure -> {
                    recordFailure(ownerId, cursors.read(ownerId) ?: SyncCursor(ownerId), startedAt,
                        syncCode(upload.error), uploadPending = true)
                    return Outcome.Failure(upload.error)
                }
                is Outcome.Success -> clearPendingIfSettled(ownerId, job.id)
            }
            val complete = job.state == ImportState.COMPLETE || job.state == ImportState.EMPTY
            status.update {
                it.copy(
                    ownerId = ownerId,
                    phase = if (complete) SyncPhase.SUCCEEDED else SyncPhase.NEEDS_ATTENTION,
                    isRunning = false,
                    lastAttemptAt = startedAt,
                    lastSuccessAt = if (complete) clock.now() else it.lastSuccessAt,
                    lastErrorCode = if (complete) null else "partial_import",
                    pendingUpload = false,
                    partial = !complete,
                    savedCount = job.importedCount,
                    duplicateCount = job.duplicateCount,
                    skippedCount = job.skippedCount,
                    unresolvedCount = job.unresolvedCount,
                    storageFailure = false,
                )
            }
            return Outcome.Success(job)
        } finally {
            gate.unlock()
        }
    }

    /**
     * Re-uploads the latest local job after a storage/network failure without re-reading IMAP. Same
     * gate, same idempotent batch keys and journal as the automatic path.
     */
    suspend fun resumeUpload(month: JakartaMonth): Outcome<ImportJob> {
        val ownerId = sessions.current()?.ownerId
            ?: return Outcome.Failure(AppError.Configuration("not_signed_in"))
        if (!gate.tryLock()) return Outcome.Failure(AppError.Conflict("sync_in_progress"))
        val startedAt = clock.now()
        try {
            // A pending run is the run that must be finished; re-uploading the newest local job
            // instead would send the old rows again under a fresh server job.
            when (val recovery = recoverPendingRun(ownerId, startedAt)) {
                is PendingRecovery.Failed -> {
                    val code = syncCode(recovery.error)
                    recordFailure(
                        ownerId,
                        cursors.read(ownerId) ?: SyncCursor(ownerId),
                        startedAt,
                        code,
                        uploadPending = true,
                    )
                    return Outcome.Failure(recovery.error)
                }
                PendingRecovery.Done -> {
                    status.update {
                        it.copy(phase = SyncPhase.SUCCEEDED, isRunning = false, pendingUpload = false, lastErrorCode = null)
                    }
                    return imports?.latestForMonth(ownerId, month)
                        ?.let { Outcome.Success(it) }
                        ?: Outcome.Failure(AppError.NotFound("import_job"))
                }
                PendingRecovery.None -> Unit
            }
            val job = imports?.latestForMonth(ownerId, month)
                ?: return Outcome.Failure(AppError.NotFound("import_job"))
            return when (val upload = uploader.upload(ownerId, job)) {
                is Outcome.Failure -> {
                    recordFailure(ownerId, cursors.read(ownerId) ?: SyncCursor(ownerId), clock.now(),
                        syncCode(upload.error), uploadPending = true)
                    Outcome.Failure(upload.error)
                }
                is Outcome.Success -> {
                    status.update { it.copy(phase = SyncPhase.SUCCEEDED, pendingUpload = false, lastErrorCode = null) }
                    Outcome.Success(job)
                }
            }
        } finally {
            gate.unlock()
        }
    }

    /**
     * Foreground freshness check: run only when the last successful sync is older than the
     * cooldown, so returning to the app repeatedly cannot hammer IMAP.
     */
    suspend fun runIfStale(trigger: SyncTrigger = SyncTrigger.FOREGROUND): SyncOutcome {
        val ownerId = sessions.current()?.ownerId ?: return SyncOutcome.Skipped("not_signed_in")
        val cursor = cursors.read(ownerId)
        val lastActivity = listOfNotNull(cursor?.lastAttemptAt, cursor?.lastSuccessAt).maxOrNull()
        val tooSoon = lastActivity != null &&
            clock.now() - lastActivity < config.foregroundCooldown
        val pending = journal?.readPending(ownerId) != null
        if (tooSoon && cursor?.uploadPending != true && !pending) {
            return SyncOutcome.Skipped("cooldown")
        }
        return run(trigger)
    }

    /**
     * Seeds the observable status from the durable cursor after a process start.
     *
     * Without this the panel claims "never" even though the cursor remembers a successful sync, and
     * "last attempt" would look different from what actually happened on disk. It reads local state
     * only: no IMAP, no HTTP.
     */
    suspend fun hydrate(ownerId: String) {
        val cursor = cursors.read(ownerId)
        val pending = journal?.readPending(ownerId)
        if (cursor == null && pending == null) return
        if (status.snapshot.value.lastAttemptAt != null) return
        val uploadPending = cursor?.uploadPending == true || pending != null
        status.update {
            it.copy(
                ownerId = ownerId,
                phase = when {
                    cursor?.lastErrorCode != null -> SyncPhase.PAUSED
                    uploadPending -> SyncPhase.SUCCEEDED
                    else -> SyncPhase.IDLE
                },
                lastAttemptAt = cursor?.lastAttemptAt,
                lastSuccessAt = cursor?.lastSuccessAt,
                lastErrorCode = cursor?.lastErrorCode,
                pendingUpload = uploadPending,
            )
        }
    }

    suspend fun run(trigger: SyncTrigger): SyncOutcome {
        if (!gate.tryLock()) return SyncOutcome.AlreadyRunning
        running.set(true)
        try {
            return execute(trigger)
        } finally {
            running.set(false)
            gate.unlock()
        }
    }

    private suspend fun execute(trigger: SyncTrigger): SyncOutcome {
        val ownerId = sessions.current()?.ownerId ?: return SyncOutcome.Skipped("not_signed_in")
        if (deletion.active(ownerId) != null) {
            // FR-47: syncing during an in-flight deletion would re-create the data being removed.
            return SyncOutcome.Skipped("deletion_in_progress")
        }
        val startedAt = clock.now()
        status.update {
            it.copy(
                ownerId = ownerId,
                phase = SyncPhase.SYNCING,
                isRunning = true,
                lastAttemptAt = startedAt,
            )
        }
        // Recovery comes FIRST and before `upTo` is captured: a committed-but-unacknowledged run
        // must be finished before this range may close. The cursor may only move after every month
        // of that run is acknowledged, otherwise a failed upload would be skipped forever.
        when (val recovery = recoverPendingRun(ownerId, startedAt)) {
            is PendingRecovery.Failed -> {
                val code = syncCode(recovery.error)
                recordFailure(
                    ownerId,
                    cursors.read(ownerId) ?: SyncCursor(ownerId),
                    startedAt,
                    code,
                    uploadPending = true,
                )
                return SyncOutcome.Failed(code)
            }
            PendingRecovery.Done, PendingRecovery.None -> Unit
        }

        val cursor = cursors.read(ownerId) ?: SyncCursor(ownerId = ownerId)
        // The watermark is captured BEFORE reading so a mid-run arrival stays above the cursor.
        val upTo = (gmail.watermark() as? Outcome.Success)?.value
        val since = cursor.watermark?.takeIf { upTo == null || it.uidValidity == upTo.uidValidity }
        val lookback = if (since != null) DAY_LOOKBACK else config.recoveryOverlap
        val from = (cursor.lastSuccessAt ?: startedAt).minus(lookback)
        val to = startedAt
        val primaryMonth = JakartaMonth.of(to)
        val previousMonth = primaryMonth.previous()
        val extraMonths = if (from < previousMonth.range().endExclusive) listOf(previousMonth) else emptyList()

        SafeLogger.counts(
            TAG,
            "sync_run",
            "trigger" to trigger.ordinal,
            "delta" to if (since != null) 1 else 0,
        )

        val job = when (
            val result = importMonth(
                ownerId = ownerId,
                month = primaryMonth,
                delta = DeltaWindow(
                    fromInclusive = from,
                    toExclusive = to,
                    since = since,
                    upTo = upTo,
                    extraMonths = extraMonths,
                ),
                // The durable identity of the run is written BEFORE a single row is committed, so a
                // crash right after the financial commit cannot strand rows under a job id that no
                // later tick knows. A failed write aborts the import before it touches the mailbox.
                onJobCreated = { created ->
                    persistPendingIntent(ownerId, created, upTo, startedAt)
                },
            )
        ) {
            is Outcome.Failure -> {
                val code = syncCode(result.error)
                recordFailure(ownerId, cursor, startedAt, code)
                return SyncOutcome.Failed(code)
            }

            is Outcome.Success -> result.value
        }

        // Late result guard: the user may have signed out or switched owner during the run.
        if (sessions.current()?.ownerId != ownerId) {
            SafeLogger.event(TAG, "sync_discarded_owner_changed")
            return SyncOutcome.Skipped("owner_changed")
        }

        // The exact candidate snapshot and its watermark, still BEFORE the first upload attempt.
        // Room's INSERT OR IGNORE keeps these rows on THIS importJobId, so a later run's snapshot
        // would be empty; without this record the next tick could never find them again.
        if (!persistPendingRun(ownerId, job, upTo, startedAt)) {
            // The pre-commit intent is gone too: the run has no durable identity at all. Sending it
            // anyway would advance the cursor over rows no retry can find.
            recordFailure(ownerId, cursor, startedAt, "storage_unavailable", uploadPending = true)
            return SyncOutcome.Failed("storage_unavailable")
        }

        when (val upload = uploader.upload(ownerId, job)) {
            is Outcome.Failure -> {
                // The local commit stays; the cursor does not advance, so the next run retries the
                // same delta and the server dedupes by fingerprint/batch key.
                val code = syncCode(upload.error)
                recordFailure(ownerId, cursor, startedAt, code, uploadPending = true)
                return SyncOutcome.Failed(code)
            }

            is Outcome.Success -> clearPendingIfSettled(ownerId, job.id)
        }

        // Second late-result guard: the account may have changed while the upload was in flight, and
        // a cursor written here would then belong to nobody.
        if (sessions.current()?.ownerId != ownerId) {
            SafeLogger.event(TAG, "sync_discarded_after_upload")
            return SyncOutcome.Skipped("owner_changed")
        }

        // Outcome.Success(job) is NOT "every email in the window was handled": PARTIAL, FAILED and
        // PERMISSION_REQUIRED all come back as Success. The watermark may only move when the whole
        // captured range is safely processed, otherwise the next run would skip the missing tail.
        val completeRange = job.state == ImportState.COMPLETE || job.state == ImportState.EMPTY
        if (!completeRange || upTo == null) {
            val saved = cursors.save(
                cursor.copy(
                    lastAttemptAt = startedAt,
                    lastErrorCode = if (upTo == null) "watermark_unavailable" else "partial_import",
                    consecutiveFailures = cursor.consecutiveFailures + 1,
                    uploadPending = false,
                ),
            )
            status.update {
                it.copy(
                    ownerId = ownerId,
                    phase = SyncPhase.NEEDS_ATTENTION,
                    isRunning = false,
                    lastAttemptAt = startedAt,
                    lastErrorCode = if (!saved) "storage_unavailable" else "partial_import",
                    newTransactionsLastRun = job.importedCount,
                    pendingUpload = false,
                    partial = true,
                    savedCount = job.importedCount,
                    duplicateCount = job.duplicateCount,
                    skippedCount = job.skippedCount,
                    unresolvedCount = job.unresolvedCount,
                    storageFailure = !saved,
                )
            }
            SafeLogger.counts(
                TAG,
                "sync_incomplete",
                "state" to job.state.ordinal,
                "saved" to job.importedCount,
                "unresolved" to job.unresolvedCount,
                "cursorSaved" to if (saved) 1 else 0,
            )
            return SyncOutcome.Incomplete(
                imported = job.importedCount,
                duplicates = job.duplicateCount,
                state = job.state.name,
                unresolved = job.unresolvedCount,
            )
        }

        // Server acknowledged a complete range: only now is the watermark worth keeping.
        val saved = cursors.save(
            cursor.copy(
                watermark = upTo,
                lastAttemptAt = startedAt,
                lastSuccessAt = clock.now(),
                lastErrorCode = null,
                consecutiveFailures = 0,
                uploadPending = false,
            ),
        )
        if (!saved) {
            // The financial commit happened, but saying "terbaru" here would claim durable progress
            // that does not exist. Keep the old watermark and report the storage failure.
            recordFailure(ownerId, cursor, startedAt, "storage_unavailable")
            return SyncOutcome.Failed("storage_unavailable")
        }
        status.update {
            it.copy(
                ownerId = ownerId,
                phase = SyncPhase.SUCCEEDED,
                isRunning = false,
                lastAttemptAt = startedAt,
                lastSuccessAt = clock.now(),
                lastErrorCode = null,
                newTransactionsLastRun = job.importedCount,
                pendingUpload = false,
                partial = false,
                savedCount = job.importedCount,
                duplicateCount = job.duplicateCount,
                skippedCount = job.skippedCount,
                unresolvedCount = job.unresolvedCount,
                storageFailure = false,
            )
        }
        SafeLogger.counts(TAG, "sync_done", "imported" to job.importedCount, "duplicates" to job.duplicateCount)
        if (job.importedCount > 0) {
            // Best effort: a queue failure must not downgrade a successful financial sync, and the
            // worker retries it on its own schedule.
            runCatching { autoCategory?.enqueueOnly(ownerId) }
        }
        return SyncOutcome.Completed(job.importedCount, job.duplicateCount, job.state.name)
    }

    /** Result of the durable pending-run recovery, so each entry point can react honestly. */
    private sealed interface PendingRecovery {
        /** Nothing was pending; the caller proceeds with its own range/month. */
        data object None : PendingRecovery

        /** The pending run is fully acknowledged and the cursor was persisted. */
        data object Done : PendingRecovery

        data class Failed(val error: AppError) : PendingRecovery
    }

    /**
     * Finishes the committed-but-unacknowledged run for [ownerId] before a new delta range may
     * close. This is what makes recovery automatic: the next periodic/foreground tick finds the
     * durable pending record, finishes THAT run, and only then advances the cursor.
     */
    private suspend fun recoverPendingRun(ownerId: String, startedAt: Instant): PendingRecovery {
        val store = journal ?: return PendingRecovery.None
        var pending = store.readPending(ownerId) ?: return PendingRecovery.None
        SafeLogger.event(TAG, "pending_recovery_start")
        status.update { it.copy(pendingUpload = true) }

        // The pre-commit intent names the job but not the committed fingerprints. Rebuild them from
        // the rows Room keeps attached to that importJobId, so an EXACT snapshot reaches the server
        // and a deduped retry never looks empty. Only months whose rows actually exist locally are
        // derived; an unreadable local snapshot is a failure, never an EMPTY acknowledgement.
        if (pending.months.values.all { it.fingerprints.isEmpty() && !it.acknowledged }) {
            val rows = transactions?.listByImportJob(ownerId, pending.localJobId)
                ?.filter { it.source == TransactionSource.EMAIL }
                .orEmpty()
            if (rows.isNotEmpty()) {
                val byMonth = rows.groupBy { JakartaMonth.of(it.eventAt).id() }
                val rebuilt = pending.copy(
                    months = byMonth.mapValues { (monthId, monthRows) ->
                        PendingUploadMonth(monthId, monthRows.map { it.fingerprint }.sorted())
                    },
                )
                if (!store.savePending(rebuilt)) {
                    return PendingRecovery.Failed(AppError.Storage("pending_journal_not_saved"))
                }
                pending = rebuilt
            }
        }

        when (val resumed = uploader.resumePending(ownerId)) {
            is Outcome.Failure -> return PendingRecovery.Failed(resumed.error)
            is Outcome.Success -> Unit
        }
        if (sessions.current()?.ownerId != ownerId) {
            return PendingRecovery.Failed(AppError.Conflict("owner_changed"))
        }
        if (store.readPending(ownerId) != null) {
            // At least one month is still unacknowledged: the watermark must not move.
            return PendingRecovery.Failed(AppError.Storage("pending_upload_incomplete"))
        }
        val cursor = cursors.read(ownerId) ?: SyncCursor(ownerId = ownerId)
        // A PARTIAL/FAILED local run still has rows worth uploading, but its watermark would hide
        // the unread tail of the range; only a fully processed range may publish it.
        val advance = pending.rangeComplete && pending.watermark != null
        val saved = cursors.save(
            cursor.copy(
                watermark = if (advance) pending.watermark else cursor.watermark,
                lastAttemptAt = startedAt,
                lastSuccessAt = if (advance) clock.now() else cursor.lastSuccessAt,
                lastErrorCode = if (advance) null else cursor.lastErrorCode,
                consecutiveFailures = if (advance) 0 else cursor.consecutiveFailures,
                uploadPending = false,
            ),
        )
        if (!saved) return PendingRecovery.Failed(AppError.Storage("storage_unavailable"))
        SafeLogger.event(TAG, "pending_recovery_done")
        return PendingRecovery.Done
    }

    /**
     * Writes the durable identity of a run BEFORE a single row is committed. The record names the
     * local job id, its month and template so the uploader can resume it after process death; the
     * fingerprints are filled in by [persistPendingRun] once the rows exist.
     */
    private suspend fun persistPendingIntent(
        ownerId: String,
        job: ImportJob,
        upTo: MailboxWatermark?,
        startedAt: Instant,
    ): Boolean {
        val store = journal ?: return true
        val intent = PendingUploadRun(
            ownerId = ownerId,
            localJobId = job.id,
            primaryMonthId = job.month.id(),
            localState = ImportState.RUNNING.name,
            templateVersion = job.templateVersion,
            errorCode = null,
            watermark = upTo,
            startedAtEpochMillis = startedAt.toEpochMilliseconds(),
            months = mapOf(job.month.id() to PendingUploadMonth(job.month.id(), emptyList())),
        )
        return store.savePending(intent)
    }

    /**
     * Writes the durable identity of a just-committed local run BEFORE the first upload attempt.
     * The fingerprint list is the exact candidate set, so a later tick can send it even though Room
     * dedupe keeps the rows attached to this (older) `importJobId`.
     *
     * @return true when a durable record names [job]; false means the run has no recoverable
     *   identity and must not be uploaded/acknowledged.
     */
    private suspend fun persistPendingRun(
        ownerId: String,
        job: ImportJob,
        upTo: MailboxWatermark?,
        startedAt: Instant,
    ): Boolean {
        val store = journal ?: return true
        val repository = transactions ?: return true
        val rows = repository.listByImportJob(ownerId, job.id)
            .filter { it.source == TransactionSource.EMAIL }
        val byMonth = rows.groupBy { JakartaMonth.of(it.eventAt).id() }
        val months = if (byMonth.isEmpty()) {
            // An EMPTY run still has to close its primary month on the server.
            mapOf(job.month.id() to PendingUploadMonth(job.month.id(), emptyList()))
        } else {
            byMonth.mapValues { (monthId, monthRows) ->
                PendingUploadMonth(monthId, monthRows.map { it.fingerprint }.sorted())
            }
        }
        val pending = PendingUploadRun(
            ownerId = ownerId,
            localJobId = job.id,
            primaryMonthId = job.month.id(),
            localState = job.state.name,
            templateVersion = job.templateVersion,
            errorCode = job.errorCode,
            watermark = upTo,
            startedAtEpochMillis = startedAt.toEpochMilliseconds(),
            months = months,
        )
        // Two attempts: a transient keystore/write failure must not downgrade a committed run from
        // "recoverable snapshot" to "recoverable only by re-deriving rows". The fallback below is
        // still durable (the pre-commit intent), but the exact fingerprint set is stronger.
        repeat(2) { if (store.savePending(pending)) return true }
        SafeLogger.event(TAG, "pending_journal_update_failed")
        return store.readPending(ownerId)?.localJobId == job.id
    }

    /**
     * The uploader clears its own record after a full acknowledgement. This also settles the
     * local-only build (nothing to acknowledge), so a leftover record cannot keep bypassing the
     * foreground cooldown forever.
     */
    private fun clearPendingIfSettled(ownerId: String, localJobId: String) {
        val store = journal ?: return
        if (store.readPending(ownerId)?.localJobId == localJobId) store.clearPending(ownerId)
    }

    /**
     * Maps an [AppError] to the machine code the status sheet understands. Storage failures get
     * their storage code instead of the generic `"storage"` kind, and the incomplete-snapshot
     * guard keeps its own code so the UI can explain why the run was not acknowledged.
     */
    private fun syncCode(error: AppError): String = when {
        error !is AppError.Storage -> error.kind.name.lowercase()
        error.debugDetail == "pending_snapshot_incomplete" -> "pending_snapshot_incomplete"
        else -> "storage_unavailable"
    }

    private fun recordFailure(
        ownerId: String,
        cursor: SyncCursor,
        startedAt: Instant,
        code: String,
        uploadPending: Boolean = false,
    ) {
        val failures = cursor.consecutiveFailures + 1
        cursors.save(
            cursor.copy(
                lastAttemptAt = startedAt,
                lastErrorCode = code,
                consecutiveFailures = failures,
                uploadPending = uploadPending || cursor.uploadPending,
            ),
        )
        val phase = when (code) {
            "PERMISSION_REQUIRED", "CONFIGURATION" -> SyncPhase.NEEDS_ATTENTION
            "NETWORK", "TIMEOUT", "NETWORK_UNAVAILABLE" -> SyncPhase.PAUSED
            else -> if (failures >= ATTENTION_THRESHOLD) SyncPhase.NEEDS_ATTENTION else SyncPhase.PAUSED
        }
        status.update {
            it.copy(
                ownerId = ownerId,
                phase = phase,
                isRunning = false,
                lastAttemptAt = startedAt,
                lastErrorCode = code,
                pendingUpload = uploadPending || it.pendingUpload,
                storageFailure = code == "storage_unavailable",
            )
        }
    }

    private companion object {
        const val TAG = "IncrementalSync"
        const val ATTENTION_THRESHOLD = 3
        val DAY_LOOKBACK = 24.hours
    }
}
