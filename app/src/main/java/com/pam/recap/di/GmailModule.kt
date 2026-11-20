package com.pam.recap.di

import com.pam.recap.data.gmail.AppPasswordVaultImpl
import com.pam.recap.data.gmail.ImapGmailSource
import com.pam.recap.data.gmail.RoutingGmailSource
import com.pam.recap.domain.repository.AppPasswordVault
import com.pam.recap.domain.repository.GmailCredentialValidator
import com.pam.recap.domain.repository.GmailMailboxLabelProvider
import com.pam.recap.domain.repository.GmailSource
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * CR-001/Fase 3: Gmail is read over IMAP when an app password is stored, and over the deterministic
 * fixture mailbox otherwise (demo + offline stay intact). The router decides per call, so pasting a
 * password takes effect without a restart.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class GmailModule {

    @Binds
    @Singleton
    abstract fun bindAppPasswordVault(impl: AppPasswordVaultImpl): AppPasswordVault

    @Binds
    @Singleton
    abstract fun bindCredentialValidator(impl: ImapGmailSource): GmailCredentialValidator

    companion object {
        @Provides
        @Singleton
        fun provideGmailSource(router: RoutingGmailSource): GmailSource = router

        @Provides
        @Singleton
        fun provideMailboxLabelProvider(source: GmailSource): GmailMailboxLabelProvider =
            source as? GmailMailboxLabelProvider
                ?: object : GmailMailboxLabelProvider {
                    override fun mailboxLabel(): String? = null
                }
    }
}
