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
}
