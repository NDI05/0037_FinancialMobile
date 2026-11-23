package com.pam.recap.di

import com.pam.recap.data.work.WorkManagerAutoCategoryScheduler
import com.pam.recap.data.work.WorkManagerAutoSyncScheduler
import com.pam.recap.domain.repository.AutoCategoryScheduler
import com.pam.recap.domain.repository.AutoSyncScheduler
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * S4.4 standard-variant scheduling: the OS-owned periodic checks run through WorkManager.
 *
 * Kept out of the shared [SyncModule] because `androidx.work` links Room's `WorkDatabase`; the
 * assessment variant must not bundle Room at all.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class StandardSyncModule {

    @Binds
    @Singleton
    abstract fun bindAutoSyncScheduler(impl: WorkManagerAutoSyncScheduler): AutoSyncScheduler

    @Binds
    @Singleton
    abstract fun bindAutoCategoryScheduler(
        impl: WorkManagerAutoCategoryScheduler,
    ): AutoCategoryScheduler
}
