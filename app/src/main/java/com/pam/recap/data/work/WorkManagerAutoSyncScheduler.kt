package com.pam.recap.data.work

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.pam.recap.core.result.SafeLogger
import com.pam.recap.domain.model.AutoSyncConfig
import com.pam.recap.domain.repository.AutoSyncScheduler
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * WorkManager implementation of [AutoSyncScheduler].
 *
 * A 30-minute periodic request with a network constraint and exponential backoff. WorkManager
 * batches and defers it — the contract is "roughly every 30 minutes while the device cooperates",
 * which is exactly what the status surface is allowed to promise.
 */
@Singleton
class WorkManagerAutoSyncScheduler @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val config: AutoSyncConfig = AutoSyncConfig(),
) : AutoSyncScheduler {

    private val enqueued = java.util.concurrent.atomic.AtomicBoolean(false)

    override fun ensureScheduled(ownerId: String) {
        // Once per process: re-issuing the request on every foreground would push the next run back.
        if (!enqueued.compareAndSet(false, true)) return
        val minutes = config.interval.inWholeMinutes.coerceAtLeast(MIN_PERIOD_MINUTES)
        val request = PeriodicWorkRequestBuilder<AutoSyncWorker>(minutes, TimeUnit.MINUTES)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, MIN_BACKOFF_MINUTES, TimeUnit.MINUTES)
            .addTag(TAG)
            .addTag(ownerTag(ownerId))
            .build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(UNIQUE_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
        SafeLogger.counts(TAG, "scheduled", "minutes" to minutes.toInt())
    }

    override fun cancelAll() {
        enqueued.set(false)
        WorkManager.getInstance(context).cancelAllWorkByTag(TAG)
        SafeLogger.event(TAG, "cancelled")
    }

    private fun ownerTag(ownerId: String) = "$OWNER_TAG$ownerId"

    private companion object {
        const val TAG = "pam-auto-sync"
        const val UNIQUE_NAME = "pam-auto-sync-periodic"
        const val OWNER_TAG = "pam-auto-sync-owner:"

        /** Platform floor for periodic work is 15 minutes; the plan targets 30. */
        const val MIN_PERIOD_MINUTES = 15L
        const val MIN_BACKOFF_MINUTES = 15L
    }
}
