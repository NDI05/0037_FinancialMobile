package com.pam.recap.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

/**
 * PAM's single Room database, schema version 1 (§4), SQLCipher-encrypted (NFR-16).
 *
 * `exportSchema = true` writes `app/schemas/…1.json`, which is committed: any later schema change
 * must ship a migration, and `fallbackToDestructiveMigration` is never used in release.
 */
@Database(
    entities = [
        AppMetadataEntity::class,
        TransactionEntity::class,
        ImportJobEntity::class,
        SkippedCandidateEntity::class,
        MonthlySummaryEntity::class,
        SummaryCategoryTotalEntity::class,
        AnalysisEntity::class,
        BudgetPlanEntity::class,
        BudgetLimitEntity::class,
        ConsentEntity::class,
        GmailConnectionEntity::class,
        DeletionRequestEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class PamDatabase : RoomDatabase() {

    abstract fun appMetadataDao(): AppMetadataDao

    abstract fun transactionDao(): TransactionDao

    abstract fun importJobDao(): ImportJobDao

    abstract fun skippedCandidateDao(): SkippedCandidateDao

    abstract fun monthlySummaryDao(): MonthlySummaryDao

    abstract fun analysisDao(): AnalysisDao

    abstract fun budgetDao(): BudgetDao

    abstract fun consentDao(): ConsentDao

    abstract fun gmailConnectionDao(): GmailConnectionDao

    abstract fun deletionDao(): DeletionDao

    companion object {
        const val DATABASE_NAME = "pam.db"
    }
}
