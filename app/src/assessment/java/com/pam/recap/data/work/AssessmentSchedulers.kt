package com.pam.recap.data.work

import com.pam.recap.domain.repository.AutoCategoryScheduler
import com.pam.recap.domain.repository.AutoSyncScheduler
import javax.inject.Inject
import javax.inject.Singleton

/**
 * S4.4 assessment-variant scheduling: foreground-only, no WorkManager.
 *
 * `androidx.work` links Room's `WorkDatabase`, so an assessment build that bundled WorkManager
 * could never be Room-free. The assessment variant therefore checks freshness on every app start /
 * foreground return ([com.pam.recap.MainActivity.onStart] runs the same delta + pending-journal
 * recovery pipeline), while the standard variant additionally gets OS-owned periodic work.
 *
 * `ensureScheduled` is a deliberate no-op rather than a fake success: there is no durable schedule
 * to keep, and reporting "scheduled" for work the platform will never run would be dishonest.
 */
@Singleton
class ForegroundAutoSyncScheduler @Inject constructor() : AutoSyncScheduler {

    override fun ensureScheduled(ownerId: String) = Unit

    override fun cancelAll() = Unit
}

/**
 * S4.4 assessment-variant category drain scheduling: the drain still runs on every foreground
 * visit ([com.pam.recap.MainActivity.onStart]); the server enforces its own two-hour window, so no
 * device-side periodic request is needed for correctness.
 */
@Singleton
class ForegroundAutoCategoryScheduler @Inject constructor() : AutoCategoryScheduler {

    override fun ensureScheduled(ownerId: String) = Unit

    override fun cancelAll() = Unit
}
