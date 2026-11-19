package com.pam.recap.di

import com.pam.recap.BuildConfig
import com.pam.recap.data.gmail.FixtureGmailSource
import com.pam.recap.data.gmail.GoogleGmailSource
import com.pam.recap.domain.repository.GmailMailboxLabelProvider
import com.pam.recap.domain.repository.GmailSource
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Provider
import javax.inject.Singleton

/**
 * OI-01 gate: the real Gmail adapter is wired but only bound when
 * `PAM_GOOGLE_WEB_CLIENT_ID` is configured. Otherwise the deterministic fixture mailbox is bound,
 * so the app still exercises the full import pipeline without a Cloud project or SHA-1.
 */
@Module
@InstallIn(SingletonComponent::class)
object GmailModule {

    @Provides
    @Singleton
    fun provideGmailSource(
        google: Provider<GoogleGmailSource>,
        fixture: Provider<FixtureGmailSource>,
    ): GmailSource = if (BuildConfig.PAM_GOOGLE_WEB_CLIENT_ID.isNotBlank()) {
        google.get()
    } else {
        fixture.get()
    }

    /**
     * FR-49: both adapters can report a mailbox label. The binding reuses the *same* source
     * instance, so a label is only ever reported for the adapter that is actually bound.
     */
    @Provides
    @Singleton
    fun provideMailboxLabelProvider(source: GmailSource): GmailMailboxLabelProvider =
        source as? GmailMailboxLabelProvider
            ?: object : GmailMailboxLabelProvider {
                override fun mailboxLabel(): String? = null
            }
}
