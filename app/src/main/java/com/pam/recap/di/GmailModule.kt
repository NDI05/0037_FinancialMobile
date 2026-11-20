package com.pam.recap.di

import com.pam.recap.data.gmail.FixtureGmailSource
import com.pam.recap.domain.repository.GmailMailboxLabelProvider
import com.pam.recap.domain.repository.GmailSource
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * CR-001/Fase 2: every Google path is gone, so the deterministic fixture mailbox is the only
 * source. Fase 3 replaces it with `ImapGmailSource`; binding it here keeps the import pipeline
 * exercisable until then.
 */
@Module
@InstallIn(SingletonComponent::class)
object GmailModule {

    @Provides
    @Singleton
    fun provideGmailSource(fixture: FixtureGmailSource): GmailSource = fixture

    @Provides
    @Singleton
    fun provideMailboxLabelProvider(source: GmailSource): GmailMailboxLabelProvider =
        source as? GmailMailboxLabelProvider
            ?: object : GmailMailboxLabelProvider {
                override fun mailboxLabel(): String? = null
            }
}
