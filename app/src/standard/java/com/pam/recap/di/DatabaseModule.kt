package com.pam.recap.di

import android.content.Context
import androidx.room.Room
import com.pam.recap.core.security.DatabasePassphraseProvider
import com.pam.recap.data.local.AnalysisDao
import com.pam.recap.data.local.AppMetadataDao
import com.pam.recap.data.local.BudgetDao
import com.pam.recap.data.local.ConsentDao
import com.pam.recap.data.local.DeletionDao
import com.pam.recap.data.local.GmailConnectionDao
import com.pam.recap.data.local.ImportJobDao
import com.pam.recap.data.local.MonthlySummaryDao
import com.pam.recap.data.local.PamDatabase
import com.pam.recap.data.local.SkippedCandidateDao
import com.pam.recap.data.local.TransactionDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabasePassphraseProvider(
        @ApplicationContext context: Context,
        keystoreSecretStore: com.pam.recap.core.security.KeystoreSecretStore,
    ): DatabasePassphraseProvider = DatabasePassphraseProvider(context, keystoreSecretStore)

    @Provides
    @Singleton
    fun provideDatabase(
        @ApplicationContext context: Context,
        passphraseProvider: DatabasePassphraseProvider,
    ): PamDatabase {
        System.loadLibrary("sqlcipher")
        val factory = SupportOpenHelperFactory(passphraseProvider.passphrase())
        return Room.databaseBuilder(context, PamDatabase::class.java, PamDatabase.DATABASE_NAME)
            .openHelperFactory(factory)
            .addMigrations()
            .build()
    }

    @Provides
    fun provideAppMetadataDao(database: PamDatabase): AppMetadataDao = database.appMetadataDao()

    @Provides
    fun provideTransactionDao(database: PamDatabase): TransactionDao = database.transactionDao()

    @Provides
    fun provideImportJobDao(database: PamDatabase): ImportJobDao = database.importJobDao()

    @Provides
    fun provideSkippedCandidateDao(database: PamDatabase): SkippedCandidateDao = database.skippedCandidateDao()

    @Provides
    fun provideMonthlySummaryDao(database: PamDatabase): MonthlySummaryDao = database.monthlySummaryDao()

    @Provides
    fun provideAnalysisDao(database: PamDatabase): AnalysisDao = database.analysisDao()

    @Provides
    fun provideBudgetDao(database: PamDatabase): BudgetDao = database.budgetDao()

    @Provides
    fun provideConsentDao(database: PamDatabase): ConsentDao = database.consentDao()

    @Provides
    fun provideGmailConnectionDao(database: PamDatabase): GmailConnectionDao = database.gmailConnectionDao()

    @Provides
    fun provideDeletionDao(database: PamDatabase): DeletionDao = database.deletionDao()
}
