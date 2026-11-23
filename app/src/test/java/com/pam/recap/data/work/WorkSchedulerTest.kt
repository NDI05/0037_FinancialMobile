package com.pam.recap.data.work

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

/**
 * Scheduler contract, not pipeline behaviour: the periods exist, are unique, are network-gated and
 * disappear on cancel. Run under Robolectric because WorkManager needs a real Application context.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WorkSchedulerTest {

    private lateinit var context: Context
    private lateinit var workManager: WorkManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        val config = Configuration.Builder()
            .setExecutor(SynchronousExecutor())
            .setMinimumLoggingLevel(android.util.Log.ERROR)
            .build()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, config)
        workManager = WorkManager.getInstance(context)
    }

    private fun infoFor(tag: String): List<WorkInfo> =
        workManager.getWorkInfosByTag(tag).get()

    @Test
    fun `sync schedules one unique 30 minute periodic check`() {
        val scheduler = WorkManagerAutoSyncScheduler(context)
        scheduler.ensureScheduled("u_1")
        // A second foreground visit must not create a second periodic request.
        scheduler.ensureScheduled("u_1")

        val infos = infoFor("pam-auto-sync")
        assertEquals(1, infos.size)
        assertEquals(WorkInfo.State.ENQUEUED, infos.single().state)
        assertTrue(infos.single().tags.contains("pam-auto-sync-owner:u_1"))
        // 30 minutes expressed in millis by the periodic request builder.
        assertEquals(TimeUnit.MINUTES.toMillis(30), infos.single().periodicityInfo?.repeatIntervalMillis)
        assertEquals(NetworkType.CONNECTED, infos.single().constraints.requiredNetworkType)
    }

    @Test
    fun `category drain is scheduled every two hours`() {
        val scheduler = WorkManagerAutoCategoryScheduler(context)
        scheduler.ensureScheduled("u_1")

        val infos = infoFor("pam-auto-category")
        assertEquals(1, infos.size)
        assertEquals(TimeUnit.HOURS.toMillis(2), infos.single().periodicityInfo?.repeatIntervalMillis)
        assertEquals(NetworkType.CONNECTED, infos.single().constraints.requiredNetworkType)
    }

    @Test
    fun `cancelling removes both periods and allows a fresh schedule`() {
        WorkManagerAutoSyncScheduler(context).ensureScheduled("u_1")
        WorkManagerAutoCategoryScheduler(context).ensureScheduled("u_1")

        WorkManagerAutoSyncScheduler(context).cancelAll()
        WorkManagerAutoCategoryScheduler(context).cancelAll()

        assertTrue(infoFor("pam-auto-sync").all { it.state == WorkInfo.State.CANCELLED })
        assertTrue(infoFor("pam-auto-category").all { it.state == WorkInfo.State.CANCELLED })

        // Signing in again after a sign-out must be able to schedule a new period.
        WorkManagerAutoSyncScheduler(context).ensureScheduled("u_1")
        assertEquals(
            1,
            infoFor("pam-auto-sync").count { it.state == WorkInfo.State.ENQUEUED },
        )
    }
}
