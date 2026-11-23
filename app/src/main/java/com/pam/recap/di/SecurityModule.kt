package com.pam.recap.di

import com.pam.recap.core.security.KeystoreSecretStore
import com.pam.recap.core.security.WrappedSecretStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object SecurityModule {

    @Provides
    @Singleton
    fun provideKeystoreSecretStore(): KeystoreSecretStore = KeystoreSecretStore()

    @Provides
    @Singleton
    fun provideWrappedSecretStore(store: KeystoreSecretStore): WrappedSecretStore = store
}
