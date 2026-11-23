package com.pam.recap.di

import com.pam.recap.data.repository.AnalysisRepositoryImpl
import com.pam.recap.data.repository.BudgetRepositoryImpl
import com.pam.recap.data.repository.ConsentRepositoryImpl
import com.pam.recap.data.repository.DeletionRepositoryImpl
import com.pam.recap.data.repository.GmailConnectionRepositoryImpl
import com.pam.recap.data.repository.ImportRepositoryImpl
import com.pam.recap.data.repository.SummaryRepositoryImpl
import com.pam.recap.data.repository.TransactionRepositoryImpl
import com.pam.recap.domain.repository.AnalysisRepository
import com.pam.recap.domain.repository.BudgetRepository
import com.pam.recap.domain.repository.ConsentRepository
import com.pam.recap.domain.repository.DeletionRepository
import com.pam.recap.domain.repository.GmailConnectionRepository
import com.pam.recap.domain.repository.ImportRepository
import com.pam.recap.domain.repository.SummaryRepository
import com.pam.recap.domain.repository.TransactionRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Room/SQLCipher-backed bindings for the standard (debug/release) variants only.
 *
 * S4.4: the assessment variant must not bundle Room or SQLCipher, so every adapter that depends on
 * `data.local` lives in `src/standard/java` and is wired here instead of in the shared
 * [RepositoryModule]. Assessment binds its own server/outbox adapters in `src/assessment/java`.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class StandardRepositoryModule {

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
    abstract fun bindDeletionRepository(impl: DeletionRepositoryImpl): DeletionRepository
}
