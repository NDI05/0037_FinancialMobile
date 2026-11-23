package com.pam.recap.data.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.pam.recap.core.result.SafeLogger
import com.pam.recap.domain.repository.SessionRepository
import com.pam.recap.domain.usecase.AutoCategoryUseCase
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/** Hilt bridge for the category worker. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface AutoCategoryEntryPoint {
    fun autoCategory(): AutoCategoryUseCase
    fun sessions(): SessionRepository
}

/**
 * One periodic category drain. The worker holds no queue of its own: the server owns the backlog and
 * the 2-hour window, so a wake-up after a restart, a missed period or a manual trigger is always safe
 * and never duplicates spend.
 */
class AutoCategoryWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val entry = EntryPointAccessors.fromApplication(applicationContext, AutoCategoryEntryPoint::class.java)
        val ownerId = entry.sessions().current()?.ownerId ?: return Result.success()
        val snapshot = entry.autoCategory().runIfConnected(ownerId) ?: return Result.success()
        // Never retry tight: a spent budget or a missing consent needs a user action, and a transient
        // error is answered by the next period. The status panel explains the state in the meantime.
        SafeLogger.counts(TAG, snapshot.phase.name.lowercase(),
            "pending" to snapshot.pending, "drained" to snapshot.lastDrained)
        return Result.success()
    }
}

private const val TAG = "pam-auto-category"
