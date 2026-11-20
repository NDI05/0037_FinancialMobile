package com.pam.recap.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.pam.recap.core.security.HmacKeyProvider
import com.pam.recap.core.security.KeystoreHmacKeyProvider
import com.pam.recap.data.ai.AiPayloadBuilder
import com.pam.recap.data.ai.AnalysisCopySource
import com.pam.recap.data.ai.AndroidAnalysisCopySource
import com.pam.recap.data.account.LocalAccountSource
import com.pam.recap.data.gmail.LivinQrisParser
import com.pam.recap.data.gmail.LivinQueryBuilder
import com.pam.recap.data.gmail.LivinTemplateRegistry
import com.pam.recap.data.prefs.SessionRepositoryImpl
import com.pam.recap.data.prefs.pamSessionDataStore
import com.pam.recap.data.repository.AnalysisRepositoryImpl
import com.pam.recap.data.repository.BudgetRepositoryImpl
import com.pam.recap.data.repository.ConsentRepositoryImpl
import com.pam.recap.data.repository.DeletionRepositoryImpl
import com.pam.recap.data.repository.GmailConnectionRepositoryImpl
import com.pam.recap.data.repository.ImportRepositoryImpl
import com.pam.recap.data.repository.SummaryRepositoryImpl
import com.pam.recap.data.repository.TransactionRepositoryImpl
import com.pam.recap.data.seed.DemoDataSeeder
import com.pam.recap.domain.repository.AccountRepository
import com.pam.recap.domain.repository.AiPayloadSource
import com.pam.recap.domain.repository.AnalysisRepository
import com.pam.recap.domain.repository.BudgetRepository
import com.pam.recap.domain.repository.ConsentRepository
import com.pam.recap.domain.repository.DeletionRepository
import com.pam.recap.domain.repository.DemoDataPort
import com.pam.recap.domain.repository.GmailConnectionRepository
import com.pam.recap.domain.repository.ImportRepository
import com.pam.recap.domain.repository.LivinNotificationParser
import com.pam.recap.domain.repository.LivinQueryFactory
import com.pam.recap.domain.repository.SessionRepository
import com.pam.recap.domain.repository.SummaryRepository
import com.pam.recap.domain.repository.TransactionRepository
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Port-to-adapter wiring: every domain port has exactly one implementation here. */
@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    abstract fun bindTransactionRepository(impl: TransactionRepositoryImpl): TransactionRepository

    @Binds
    abstract fun bindSummaryRepository(impl: SummaryRepositoryImpl): SummaryRepository

    @Binds
    abstract fun bindImportRepository(impl: ImportRepositoryImpl): ImportRepository

    @Binds
    abstract fun bindAnalysisRepository(impl: AnalysisRepositoryImpl): AnalysisRepository

    @Binds
    abstract fun bindBudgetRepository(impl: BudgetRepositoryImpl): BudgetRepository

    @Binds
    abstract fun bindConsentRepository(impl: ConsentRepositoryImpl): ConsentRepository

    @Binds
    abstract fun bindGmailConnectionRepository(impl: GmailConnectionRepositoryImpl): GmailConnectionRepository

    @Binds
    abstract fun bindSessionRepository(impl: SessionRepositoryImpl): SessionRepository

    @Binds
    abstract fun bindDeletionRepository(impl: DeletionRepositoryImpl): DeletionRepository

    @Binds
    abstract fun bindDemoData(impl: DemoDataSeeder): DemoDataPort

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
