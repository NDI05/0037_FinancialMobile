package com.pam.recap.domain.usecase

import com.pam.recap.core.money.Money
import com.pam.recap.core.result.Outcome
import com.pam.recap.core.time.JakartaMonth
import com.pam.recap.domain.model.Category
import com.pam.recap.domain.model.CategoryOrigin
import com.pam.recap.domain.model.Direction
import com.pam.recap.domain.model.ImportJob
import com.pam.recap.domain.model.ImportState
import com.pam.recap.domain.model.MonthlySummary
import com.pam.recap.domain.model.ParseStatus
import com.pam.recap.domain.model.Transaction
import com.pam.recap.domain.model.TransactionSource
import com.pam.recap.domain.repository.ManualTransactionDraft
import com.pam.recap.domain.repository.ServerCategory
import com.pam.recap.domain.repository.ServerFinanceRepository
import com.pam.recap.domain.repository.ServerImportCandidate
import com.pam.recap.domain.repository.ServerImportCounts
import com.pam.recap.domain.repository.ServerImportJob
import com.pam.recap.domain.repository.ServerTransactionPage
import com.pam.recap.testing.FakeTransactionRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Instant

class ServerImportUploaderTest {
    private val month = JakartaMonth(2026, 11)
    private val row = Transaction(
        id = "tx_local", ownerId = "u_1", direction = Direction.DEBIT,
        source = TransactionSource.EMAIL, amount = Money(17_500_000), currency = "IDR",
        eventAt = Instant.parse("2026-11-19T02:15:20Z"), receivedAt = null,
        description = "Pembayaran", merchantLabel = "Indomaret", acquirer = null,
        status = ParseStatus.POSTED, fingerprint = "a".repeat(32),
        parserVersion = "livin-bill/v2", parserConfidence = 0.62f,
        category = Category.FOOD, categoryOrigin = CategoryOrigin.IMPORTED,
        categoryConfidence = 0.62f, categoryUpdatedAt = null, isInternalTransfer = false,
        linkedRefundOf = null, note = null, importJobId = "job-1", revision = 0,
        createdAt = Instant.parse("2026-11-19T03:00:00Z"),
        updatedAt = Instant.parse("2026-11-19T03:00:00Z"),
    )
    private val job = ImportJob(
        id = "job-1", ownerId = "u_1", month = month, state = ImportState.COMPLETE,
        pagesProcessed = 1, pagesExpected = null, matchedCount = 1, importedCount = 1,
        duplicateCount = 0, skippedCount = 0, unresolvedCount = 0,
        templateVersion = "v1", startedAt = Instant.parse("2026-11-19T03:00:00Z"),
        finishedAt = Instant.parse("2026-11-19T03:01:00Z"), errorCode = null,
    )

    @Test
    fun `a completed local parse becomes one server job, batch and final state`() = runTest {
        val domain = FakeServer()
        val uploader = ServerImportUploader(domain, FakeTransactionRepository(listOf(row)))
        val result = uploader("u_1", job) as Outcome.Success
        assertEquals("server-job", result.value)
        assertEquals(1, domain.batches)
        assertEquals("COMPLETE", domain.finalState)
        assertEquals("a".repeat(32), domain.candidates.single().fingerprint)
        assertEquals(17_500_000L, domain.candidates.single().amountMinor)
    }

    private class FakeServer : ServerFinanceRepository {
        override val isEnabled = true
        var batches = 0
        var finalState = ""
        var candidates = emptyList<ServerImportCandidate>()
        override suspend fun categories(ownerId: String) = Outcome.Success(
            listOf(ServerCategory("cat_food", "food", "Makanan", "EXPENSE")),
        )
        override suspend fun createManual(ownerId: String, draft: ManualTransactionDraft, idempotencyKey: String) =
            Outcome.Failure(com.pam.recap.core.result.AppError.Configuration("unused"))
        override suspend fun listMonth(ownerId: String, month: JakartaMonth, limit: Int, cursor: String?) =
            Outcome.Success(ServerTransactionPage(emptyList()))
        override suspend fun get(ownerId: String, transactionId: String) =
            Outcome.Failure(com.pam.recap.core.result.AppError.NotFound("unused"))
        override suspend fun updateCategory(
            ownerId: String, transactionId: String, categoryId: String, revision: Int,
        ) = Outcome.Failure(com.pam.recap.core.result.AppError.Configuration("unused"))
        override suspend fun summary(ownerId: String, month: JakartaMonth) =
            Outcome.Failure(com.pam.recap.core.result.AppError.Configuration("unused"))
        override suspend fun classifyTransactions(ownerId: String, transactionIds: List<String>) =
            Outcome.Success(emptyList<com.pam.recap.domain.repository.ServerCategoryJob>())
        override suspend fun createImportJob(ownerId: String, month: JakartaMonth, templateVersion: String) =
            Outcome.Success(ServerImportJob("server-job", month.id(), "CREATED"))
        override suspend fun importBatch(
            ownerId: String, jobId: String, batchKey: String, pageOrdinal: Int,
            candidates: List<ServerImportCandidate>,
        ): Outcome<ServerImportCounts> {
            batches++
            this.candidates = candidates
            return Outcome.Success(ServerImportCounts(1, 1, 0, 0, 0, 1, "RUNNING"))
        }
        override suspend fun finalizeImportJob(ownerId: String, jobId: String, state: String, errorCode: String?) =
            Outcome.Success(ServerImportJob("server-job", "2026-11", state).also { finalState = state })
    }
}
