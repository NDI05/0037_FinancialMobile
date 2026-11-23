package com.pam.recap.domain.usecase

import com.pam.recap.core.result.AppError
import com.pam.recap.core.result.AppErrorRules
import com.pam.recap.core.result.Outcome
import com.pam.recap.core.result.SafeLogger
import com.pam.recap.core.time.ClockProvider
import com.pam.recap.core.time.JakartaMonth
import com.pam.recap.domain.model.AutoSyncConfig
import com.pam.recap.domain.model.ImportState
import com.pam.recap.domain.model.DeltaWindow
import com.pam.recap.domain.model.SyncCursor
import com.pam.recap.domain.model.SyncPhase
import com.pam.recap.domain.model.SyncSnapshot
import com.pam.recap.domain.repository.DeletionRepository
import com.pam.recap.domain.model.ImportJob
import com.pam.recap.domain.repository.GmailSource
import com.pam.recap.domain.repository.ImportRepository
import com.pam.recap.domain.repository.SessionRepository
import com.pam.recap.domain.repository.SyncCursorStore
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
            val previous = imports?.latestForMonth(ownerId, month)
            if (previous != null && previous.state == ImportState.COMPLETE) {
                // Nothing to repair: a re-run would only re-read the same month.
                status.update { it.copy(phase = SyncPhase.SUCCEEDED, isRunning = false, lastSuccessAt = clock.now()) }
                return Outcome.Success(previous)
            }
            val job = when (val result = importMonth(ownerId, month, previous?.templateVersion)) {
                is Outcome.Failure -> {
                    recordFailure(ownerId, cursors.read(ownerId) ?: SyncCursor(ownerId), startedAt, result.error.kind.name.lowercase())
                    return result
                }
                is Outcome.Success -> result.value
            }
            if (sessions.current()?.ownerId != ownerId) {
                return Outcome.Failure(AppError.Conflict("owner_changed"))
            }
            when (val upload = uploader.upload(ownerId, job)) {
                is Outcome.Failure -> {
                    recordFailure(ownerId, cursors.read(ownerId) ?: SyncCursor(ownerId), startedAt,
                        upload.error.kind.name.lowercase(), uploadPending = true)
                    return Outcome.Failure(upload.error)
                }
                is Outcome.Success -> Unit
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
        try {
            val job = imports?.latestForMonth(ownerId, month)
                ?: return Outcome.Failure(AppError.NotFound("import_job"))
            return when (val upload = uploader.upload(ownerId, job)) {
                is Outcome.Failure -> {
                    recordFailure(ownerId, cursors.read(ownerId) ?: SyncCursor(ownerId), clock.now(),
                        upload.error.kind.name.lowercase(), uploadPending = true)
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
        if (tooSoon && cursor?.uploadPending != true) {
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
        val cursor = cursors.read(ownerId) ?: return
        if (status.snapshot.value.lastAttemptAt != null) return
        status.update {
            it.copy(
                ownerId = ownerId,
                phase = when {
                    cursor.lastErrorCode != null -> SyncPhase.PAUSED
                    cursor.uploadPending -> SyncPhase.SUCCEEDED
                    else -> SyncPhase.IDLE
                },
                lastAttemptAt = cursor.lastAttemptAt,
                lastSuccessAt = cursor.lastSuccessAt,
                lastErrorCode = cursor.lastErrorCode,
                pendingUpload = cursor.uploadPending,
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
            )
        ) {
            is Outcome.Failure -> {
                recordFailure(ownerId, cursor, startedAt, result.error.kind.name.lowercase())
                return SyncOutcome.Failed(result.error.kind.name.lowercase())
            }

            is Outcome.Success -> result.value
        }

        // Late result guard: the user may have signed out or switched owner during the run.
        if (sessions.current()?.ownerId != ownerId) {
            SafeLogger.event(TAG, "sync_discarded_owner_changed")
            return SyncOutcome.Skipped("owner_changed")
        }

        when (val upload = uploader.upload(ownerId, job)) {
            is Outcome.Failure -> {
                // The local commit stays; the cursor does not advance, so the next run retries the
                // same delta and the server dedupes by fingerprint/batch key.
                val code = upload.error.kind.name.lowercase()
                recordFailure(ownerId, cursor, startedAt, code, uploadPending = true)
                return SyncOutcome.Failed(code)
            }

            is Outcome.Success -> Unit
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
