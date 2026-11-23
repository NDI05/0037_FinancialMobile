package com.pam.recap.di

import com.pam.recap.data.network.AndroidNetworkMonitor
import com.pam.recap.data.security.SyncCursorStoreImpl
import com.pam.recap.domain.model.AutoSyncConfig
import com.pam.recap.domain.repository.NetworkMonitor
import com.pam.recap.domain.repository.SyncCursorStore
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Automatic checks: a durable cursor and the plan's default tuning.
 *
 * S4.4: the scheduler implementations are variant-specific (`StandardSyncModule` uses WorkManager,
 * which links Room; `AssessmentSyncModule` is foreground-only) so the assessment build never pulls
 * Room in through WorkManager.
 *
 * The tuning is a real binding instead of hard-coded constants so a later UAT decision (interval,
 * cooldown) is one module edit rather than a hunt through the codebase.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class SyncModule {

    @Binds
    @Singleton
    abstract fun bindSyncCursorStore(impl: SyncCursorStoreImpl): SyncCursorStore

    @Binds
    @Singleton
    abstract fun bindNetworkMonitor(impl: AndroidNetworkMonitor): NetworkMonitor

    companion object {
        @Provides
        @Singleton
        fun provideAutoSyncConfig(): AutoSyncConfig = AutoSyncConfig()
    }
}
