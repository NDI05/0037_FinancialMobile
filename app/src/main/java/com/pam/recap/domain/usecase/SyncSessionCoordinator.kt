package com.pam.recap.domain.usecase

import com.pam.recap.core.di.ApplicationScope
import com.pam.recap.core.result.AppError
import com.pam.recap.core.result.Outcome
import com.pam.recap.core.time.ClockProvider
import com.pam.recap.core.time.JakartaMonth
import com.pam.recap.domain.model.ImportJob
import com.pam.recap.domain.repository.ImportRepository
import com.pam.recap.domain.repository.SessionRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The one callable seam between the status panel and the import pipeline.
 *
 * It exists so the panel never talks to [IncrementalSyncUseCase] directly: the coordinator owns the
 * app-level identity of a manual run (which month, which attempt, how far it got) and keeps that
 * state alive when the sheet that started it is dismissed. The pipeline's own mutex still serialises
 * manual, foreground and WorkManager runs, so closing the sheet and tapping again cannot start a
 * second import: the extra tap reaches the same gate and is reported as "already running".
 */
interface SyncSessionRunner {
    suspend fun run(month: JakartaMonth): Outcome<ImportJob>
    suspend fun resumeUpload(month: JakartaMonth): Outcome<ImportJob>
}

@Module
@InstallIn(SingletonComponent::class)
abstract class SyncSessionModule {
    @Binds
    @Singleton
    abstract fun bindRunner(impl: IncrementalSyncSessionRunner): SyncSessionRunner
}

@Singleton
class IncrementalSyncSessionRunner @Inject constructor(
    private val sync: IncrementalSyncUseCase,
) : SyncSessionRunner {
    override suspend fun run(month: JakartaMonth): Outcome<ImportJob> = sync.runManualMonth(month)

    override suspend fun resumeUpload(month: JakartaMonth): Outcome<ImportJob> =
        sync.resumeUpload(month)
}

/**
 * App-level state of the current/last manual sync attempt. Read-only for the UI; the sheet renders
 * it without needing to stay composed for the job to finish.
 */
data class SyncSessionState(
    val running: Boolean = false,
    val month: JakartaMonth? = null,
    /** Monotonic token: the UI keys its fade animations to this, never to recomposition. */
    val attempt: Long = 0,
    /** Real page progress when the pipeline reports it; null means "total unknown". */
    val pagesProcessed: Int = 0,
    val pagesExpected: Int? = null,
    val error: AppError? = null,
    /** Another pipeline run owns the single-flight gate; nothing was started by this tap. */
    val conflict: Boolean = false,
    val lastJob: ImportJob? = null,
) {
    val hasDefiniteProgress: Boolean get() = pagesExpected != null && (pagesExpected ?: 0) > 0
}

@Singleton
class SyncSessionCoordinator @Inject constructor(
    private val runner: SyncSessionRunner,
    private val imports: ImportRepository,
    private val sessions: SessionRepository,
    private val status: SyncStatusHolder,
    private val clock: ClockProvider,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private val flight = Mutex()
    private val _state = MutableStateFlow(SyncSessionState())
    val state: StateFlow<SyncSessionState> = _state.asStateFlow()

    /**
     * Fire-and-forget trigger used by the sheet. It runs on the application scope, so dismissing the
     * sheet or navigating away neither cancels nor restarts the import.
     */
    fun requestSync(month: JakartaMonth? = null) {
        scope.launch { runNow(month) }
    }

    /** Suspend entry point, also used by tests to await one attempt deterministically. */
    suspend fun runNow(month: JakartaMonth? = null) {
        if (!flight.tryLock()) return
        var watcher: Job? = null
        try {
            val ownerId = sessions.current()?.ownerId
            if (ownerId == null) {
                _state.update {
                    it.copy(
                        running = false,
                        error = AppError.Configuration("not_signed_in"),
                        conflict = false,
                    )
                }
                return
            }
            val target = month ?: _state.value.month ?: clock.currentMonth()
            val startedAt = clock.now()
            _state.update {
                it.copy(
                    running = true,
                    month = target,
                    attempt = it.attempt + 1,
                    pagesProcessed = 0,
                    pagesExpected = null,
                    error = null,
                    conflict = false,
                )
            }
            // Real progress, not a pretend spinner: the pipeline writes pagesProcessed/expected while
            // the suspend call below is in flight. A stale job from an earlier run is ignored.
            watcher = scope.launch {
                imports.observeLatestForMonth(ownerId, target).collect { job ->
                    if (job != null && job.startedAt >= startedAt) {
                        _state.update {
                            it.copy(
                                pagesProcessed = job.pagesProcessed,
                                pagesExpected = job.pagesExpected,
                            )
                        }
                    }
                }
            }
            val resumeUpload = status.snapshot.value.pendingUpload
            val outcome = if (resumeUpload) {
                runner.resumeUpload(target)
            } else {
                runner.run(target)
            }
            val latest = runCatching { imports.latestForMonth(ownerId, target) }.getOrNull()
            when (outcome) {
                is Outcome.Success -> {
                    // The pipeline's return value is authoritative for this attempt; the month row is
                    // only a fallback (e.g. a resumed upload that returns an earlier job).
                    val finished: ImportJob? = outcome.value ?: latest
                    _state.update {
                        it.copy(
                            running = false,
                            error = null,
                            conflict = false,
                            lastJob = finished ?: it.lastJob,
                            pagesProcessed = finished?.pagesProcessed ?: it.pagesProcessed,
                            pagesExpected = finished?.pagesExpected ?: it.pagesExpected,
                        )
                    }
                }

                is Outcome.Failure -> {
                    // A conflict is not a failure of the user's request: another run already owns
                    // the gate and its progress is visible through the shared status snapshot.
                    val alreadyRunning = outcome.error is AppError.Conflict &&
                        outcome.error.debugDetail == "sync_in_progress"
                    _state.update {
                        it.copy(
                            running = false,
                            error = if (alreadyRunning) null else outcome.error,
                            conflict = alreadyRunning,
                            lastJob = latest ?: it.lastJob,
                        )
                    }
                }
            }
        } finally {
            watcher?.cancel()
            flight.unlock()
        }
    }
}
