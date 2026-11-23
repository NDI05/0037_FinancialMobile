package com.pam.recap.di

import com.pam.recap.data.assessment.AssessmentAnalysisRepository
import com.pam.recap.data.assessment.AssessmentBudgetRepository
import com.pam.recap.data.assessment.AssessmentConsentRepository
import com.pam.recap.data.assessment.AssessmentDeletionRepository
import com.pam.recap.data.assessment.AssessmentGmailConnectionRepository
import com.pam.recap.data.assessment.AssessmentImportRepository
import com.pam.recap.data.assessment.AssessmentSummaryRepository
import com.pam.recap.data.assessment.AssessmentTransactionRepository
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
 * S4.4 assessment-variant bindings: no Room, no SQLCipher.
 *
 * The owner-scoped finance/identity ports are served by the server-backed adapters in
 * `src/assessment/java`, plus the Keystore-encrypted import outbox that keeps the pending-upload
 * journal recoverable. The shared [RepositoryModule] and [BackendModule] stay variant-neutral.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class AssessmentRepositoryModule {

    @Binds
    abstract fun bindTransactionRepository(impl: AssessmentTransactionRepository): TransactionRepository

    @Binds
    abstract fun bindSummaryRepository(impl: AssessmentSummaryRepository): SummaryRepository

    @Binds
    abstract fun bindImportRepository(impl: AssessmentImportRepository): ImportRepository

    @Binds
    abstract fun bindAnalysisRepository(impl: AssessmentAnalysisRepository): AnalysisRepository

    @Binds
    abstract fun bindBudgetRepository(impl: AssessmentBudgetRepository): BudgetRepository

    @Binds
    abstract fun bindConsentRepository(impl: AssessmentConsentRepository): ConsentRepository

    @Binds
    abstract fun bindGmailConnectionRepository(
        impl: AssessmentGmailConnectionRepository,
    ): GmailConnectionRepository

    @Binds
    abstract fun bindDeletionRepository(impl: AssessmentDeletionRepository): DeletionRepository
}
