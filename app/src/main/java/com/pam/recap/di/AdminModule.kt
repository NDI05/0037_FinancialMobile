package com.pam.recap.di

import com.pam.recap.data.backend.AdminRepositoryImpl
import com.pam.recap.domain.repository.AdminRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** S4.2 admin port binding. The implementation is a no-op when no backend URL is configured. */
@Module
@InstallIn(SingletonComponent::class)
abstract class AdminModule {

    @Binds
    @Singleton
    abstract fun bindAdminRepository(impl: AdminRepositoryImpl): AdminRepository
}
