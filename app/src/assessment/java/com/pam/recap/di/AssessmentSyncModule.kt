package com.pam.recap.di

import com.pam.recap.data.work.ForegroundAutoCategoryScheduler
import com.pam.recap.data.work.ForegroundAutoSyncScheduler
import com.pam.recap.domain.repository.AutoCategoryScheduler
import com.pam.recap.domain.repository.AutoSyncScheduler
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * S4.4 assessment-variant scheduling bindings: foreground-only, Room-free.
 *
 * The standard variant binds WorkManager schedulers in [StandardSyncModule] instead.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class AssessmentSyncModule {

    @Binds
    @Singleton
    abstract fun bindAutoSyncScheduler(impl: ForegroundAutoSyncScheduler): AutoSyncScheduler

    @Binds
    @Singleton
    abstract fun bindAutoCategoryScheduler(
        impl: ForegroundAutoCategoryScheduler,
    ): AutoCategoryScheduler
}
