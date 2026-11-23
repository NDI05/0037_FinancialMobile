package com.pam.recap.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.pam.recap.core.security.HmacKeyProvider
import com.pam.recap.core.security.KeystoreHmacKeyProvider
import com.pam.recap.domain.usecase.ImportUploader
import com.pam.recap.domain.usecase.ServerImportUploader
import com.pam.recap.data.ai.AiPayloadBuilder
import com.pam.recap.data.ai.AnalysisCopySource
import com.pam.recap.data.ai.AndroidAnalysisCopySource
import com.pam.recap.data.account.LocalAccountSource
import com.pam.recap.data.gmail.LivinQrisParser
import com.pam.recap.data.gmail.LivinQueryBuilder
import com.pam.recap.data.gmail.LivinTemplateRegistry
import com.pam.recap.data.prefs.SessionRepositoryImpl
import com.pam.recap.data.prefs.pamSessionDataStore
import com.pam.recap.data.seed.DemoDataPurger
import com.pam.recap.data.seed.DemoDataSeeder
import com.pam.recap.domain.repository.AccountRepository
import com.pam.recap.domain.repository.AiPayloadSource
import com.pam.recap.domain.repository.DemoDataMaintenance
import com.pam.recap.domain.repository.DemoDataPort
import com.pam.recap.domain.repository.LivinNotificationParser
import com.pam.recap.domain.repository.LivinQueryFactory
import com.pam.recap.domain.repository.SessionRepository
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Port-to-adapter wiring shared by every variant.
 *
 * S4.4: the owner-scoped finance/identity ports that were Room-backed moved to the variant-specific
 * modules — [StandardRepositoryModule] (debug/release, Room/SQLCipher) and
 * [AssessmentRepositoryModule] (assessment, server/outbox) — so the shared module never names a
 * Room type.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    abstract fun bindSessionRepository(impl: SessionRepositoryImpl): SessionRepository

    @Binds
    abstract fun bindDemoData(impl: DemoDataSeeder): DemoDataPort

    @Binds
    abstract fun bindDemoDataMaintenance(impl: DemoDataPurger): DemoDataMaintenance

    @Binds
    abstract fun bindLivinParser(impl: LivinQrisParser): LivinNotificationParser

    @Binds
    abstract fun bindLivinQueryFactory(impl: LivinQueryBuilder): LivinQueryFactory

    @Binds
    abstract fun bindAiPayloadSource(impl: AiPayloadBuilder): AiPayloadSource

    @Binds
    abstract fun bindHmacKeyProvider(impl: KeystoreHmacKeyProvider): HmacKeyProvider

    @Binds
    abstract fun bindAccountRepository(impl: LocalAccountSource): AccountRepository

    @Binds
    abstract fun bindAnalysisCopySource(impl: AndroidAnalysisCopySource): AnalysisCopySource

    @Binds
    abstract fun bindImportUploader(impl: ServerImportUploader): ImportUploader

    companion object {

        @Provides
        @Singleton
        fun provideDataStore(@ApplicationContext context: Context): DataStore<Preferences> =
            context.pamSessionDataStore

        @Provides
        @Singleton
        fun provideTemplateRegistry(): LivinTemplateRegistry = LivinTemplateRegistry()

        @Provides
        @Singleton
        fun provideQueryBuilder(registry: LivinTemplateRegistry): LivinQueryBuilder =
            LivinQueryBuilder(registry)

        @Provides
        @Singleton
        fun provideParser(registry: LivinTemplateRegistry): LivinQrisParser = LivinQrisParser(registry)
    }
}
