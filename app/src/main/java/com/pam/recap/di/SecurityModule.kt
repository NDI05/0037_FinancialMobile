package com.pam.recap.di

import android.content.Context
import com.pam.recap.core.security.DatabasePassphraseProvider
import com.pam.recap.core.security.KeystoreSecretStore
import com.pam.recap.core.security.WrappedSecretStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
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

    @Provides
    @Singleton
    fun provideDatabasePassphraseProvider(
        @ApplicationContext context: Context,
        keystoreSecretStore: KeystoreSecretStore,
    ): DatabasePassphraseProvider = DatabasePassphraseProvider(context, keystoreSecretStore)
}
