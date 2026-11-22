package com.pam.recap.di

import com.pam.recap.data.backend.BackendConfig
import com.pam.recap.data.backend.BackendEndpoints
import com.pam.recap.data.backend.BackendSessionRepositoryImpl
import com.pam.recap.data.backend.ServerFinanceRepositoryImpl
import com.pam.recap.data.backend.BackendSyncClient
import com.pam.recap.domain.repository.BackendConsentSync
import com.pam.recap.domain.repository.BackendDeletionSync
import com.pam.recap.domain.repository.BackendSessionRepository
import com.pam.recap.domain.repository.BackendSummarySync
import com.pam.recap.domain.repository.ServerFinanceRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Backend port bindings (contract §2/§4). Every implementation is a no-op when no backend URL is
 * configured, so the offline build keeps working unchanged.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class BackendModule {

    @Binds
    @Singleton
    abstract fun bindBackendEndpoints(impl: BackendConfig): BackendEndpoints

    @Binds
    @Singleton
    abstract fun bindBackendSession(impl: BackendSessionRepositoryImpl): BackendSessionRepository

    @Binds
    @Singleton
    abstract fun bindBackendConsentSync(impl: BackendSyncClient): BackendConsentSync

    @Binds
    @Singleton
    abstract fun bindBackendSummarySync(impl: BackendSyncClient): BackendSummarySync

    @Binds
    @Singleton
    abstract fun bindBackendDeletionSync(impl: BackendSyncClient): BackendDeletionSync

    @Binds
    @Singleton
    abstract fun bindServerFinance(impl: ServerFinanceRepositoryImpl): ServerFinanceRepository
}
