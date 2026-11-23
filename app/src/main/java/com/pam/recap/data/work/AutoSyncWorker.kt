package com.pam.recap.data.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.pam.recap.domain.usecase.IncrementalSyncUseCase
import com.pam.recap.domain.usecase.SyncOutcome
import com.pam.recap.domain.usecase.SyncTrigger
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/** Hilt bridge for the worker: WorkManager instantiates it, Hilt supplies the use case. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface AutoSyncEntryPoint {
    fun incrementalSync(): IncrementalSyncUseCase
}

/**
 * One background check. It runs the same delta pipeline as the foreground check and the manual
 * import, through the same single-flight gate, so the OS waking it up can never double-import.
 *
 * Retry is bounded by WorkManager's own backoff: a failure returns `retry()` (network/transient), a
 * deliberate skip returns success so the next period is used instead of a tight loop.
 */
class AutoSyncWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val sync = EntryPointAccessors.fromApplication(applicationContext, AutoSyncEntryPoint::class.java)
            .incrementalSync()
        return when (sync.run(SyncTrigger.PERIODIC)) {
            is SyncOutcome.Completed -> Result.success()
            // Another run already holds the gate: this period is covered by that run.
            SyncOutcome.AlreadyRunning -> Result.success()
            is SyncOutcome.Skipped -> Result.success()
            is SyncOutcome.Failed -> if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.success()
        }
    }

    private companion object {
        /** Keep a broken mailbox from burning battery: after this many retries wait for the period. */
        const val MAX_ATTEMPTS = 3
    }
}
