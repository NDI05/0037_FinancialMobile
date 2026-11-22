package com.pam.recap.domain.repository

import com.pam.recap.core.result.Outcome
import com.pam.recap.core.time.JakartaMonth
import com.pam.recap.domain.model.MonthlySummary
import com.pam.recap.domain.model.Transaction

/** Owner-scoped category identity returned by the v0.4 server. */
data class ServerCategory(
    val id: String,
    val code: String,
    val label: String,
    val kind: String,
)

/** A manual action before the server assigns its final id/revision. */
data class ManualTransactionDraft(
    val direction: String,
    val amountMinor: Long,
    val eventAtEpochMilliseconds: Long,
    val categoryId: String?,
    val description: String,
    val note: String?,
)

data class ServerTransactionPage(
    val items: List<Transaction>,
    val nextCursor: String? = null,
)

/**
 * C2A read/write boundary for the existing transaction and summary screens. Implementations call
 * Go; the legacy Room path is a separate, explicit implementation selected only when no server is
 * configured.
 */
data class ServerImportJob(
    val id: String,
    val monthId: String,
    val state: String,
    val matched: Int = 0,
    val imported: Int = 0,
    val duplicates: Int = 0,
    val skipped: Int = 0,
    val unresolved: Int = 0,
)

data class ServerImportCandidate(
    val fingerprint: String,
    val status: String,
    val reason: String? = null,
    val direction: String? = null,
    val amountMinor: Long? = null,
    val eventAtEpochMilliseconds: Long? = null,
    val categoryId: String? = null,
    val parserVersion: String? = null,
    val confidence: Double? = null,
    val description: String? = null,
    val merchantLabel: String? = null,
)

data class ServerImportCounts(
    val matched: Int,
    val imported: Int,
    val duplicates: Int,
    val skipped: Int,
    val unresolved: Int,
    val inputRevision: Int,
    val jobState: String,
)

data class ServerCategoryJob(
    val id: String,
    val transactionId: String,
    val state: String,
    val categoryCode: String? = null,
    val reasonCode: String? = null,
    val errorCode: String? = null,
    val provider: String? = null,
    val model: String? = null,
)

interface ServerFinanceRepository {
    val isEnabled: Boolean

    suspend fun categories(ownerId: String): Outcome<List<ServerCategory>>

    suspend fun createManual(
        ownerId: String,
        draft: ManualTransactionDraft,
        idempotencyKey: String,
    ): Outcome<Transaction>

    suspend fun listMonth(
        ownerId: String,
        month: JakartaMonth,
        limit: Int = 100,
        cursor: String? = null,
    ): Outcome<ServerTransactionPage>

    suspend fun get(ownerId: String, transactionId: String): Outcome<Transaction>

    suspend fun summary(ownerId: String, month: JakartaMonth): Outcome<MonthlySummary>

    suspend fun classifyTransactions(
        ownerId: String,
        transactionIds: List<String>,
    ): Outcome<List<ServerCategoryJob>>

    suspend fun createImportJob(
        ownerId: String,
        month: JakartaMonth,
        templateVersion: String,
    ): Outcome<ServerImportJob>

    suspend fun importBatch(
        ownerId: String,
        jobId: String,
        batchKey: String,
        pageOrdinal: Int,
        candidates: List<ServerImportCandidate>,
    ): Outcome<ServerImportCounts>

    suspend fun finalizeImportJob(
        ownerId: String,
        jobId: String,
        state: String,
        errorCode: String? = null,
    ): Outcome<ServerImportJob>
}
