package com.pam.recap.data.work

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.pam.recap.core.result.SafeLogger
import com.pam.recap.domain.repository.AutoCategoryScheduler
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * WorkManager implementation of [AutoCategoryScheduler].
 *
 * Two hours is the plan's drain target and also the server's window, so the device never asks more
 * often than the queue can legitimately move. The request needs a network but not Wi-Fi or charging:
 * a small enqueue+drain should not wait for an ideal charging window.
 */
@Singleton
class WorkManagerAutoCategoryScheduler @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : AutoCategoryScheduler {

    private val enqueued = AtomicBoolean(false)

    override fun ensureScheduled(ownerId: String) {
        // Once per process: re-issuing on every screen start would postpone the next run forever.
        if (!enqueued.compareAndSet(false, true)) return
        val request = PeriodicWorkRequestBuilder<AutoCategoryWorker>(INTERVAL_HOURS, TimeUnit.HOURS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, MIN_BACKOFF_MINUTES, TimeUnit.MINUTES)
            .addTag(TAG)
            .addTag("$OWNER_TAG$ownerId")
            .build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(UNIQUE_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
        SafeLogger.counts(TAG, "scheduled", "hours" to INTERVAL_HOURS.toInt())
    }

    override fun cancelAll() {
        enqueued.set(false)
        WorkManager.getInstance(context).cancelAllWorkByTag(TAG)
        SafeLogger.event(TAG, "cancelled")
    }

    private companion object {
        const val TAG = "pam-auto-category"
        const val UNIQUE_NAME = "pam-auto-category-periodic"
        const val OWNER_TAG = "pam-auto-category-owner:"
        const val INTERVAL_HOURS = 2L
        const val MIN_BACKOFF_MINUTES = 30L
    }
}
