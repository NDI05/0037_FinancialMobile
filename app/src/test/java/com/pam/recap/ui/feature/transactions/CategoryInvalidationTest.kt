package com.pam.recap.ui.feature.transactions

import androidx.lifecycle.SavedStateHandle
import com.pam.recap.core.money.Money
import com.pam.recap.core.result.AppError
import com.pam.recap.core.result.Outcome
import com.pam.recap.core.time.JakartaMonth
import com.pam.recap.domain.model.Category
import com.pam.recap.domain.model.CategoryOrigin
import com.pam.recap.domain.model.Direction
import com.pam.recap.domain.model.MonthlySummary
import com.pam.recap.domain.model.ParseStatus
import com.pam.recap.domain.model.Session
import com.pam.recap.domain.model.Transaction
import com.pam.recap.domain.model.TransactionSource
import com.pam.recap.domain.repository.FinanceRefreshBus
import com.pam.recap.domain.repository.FinanceRefreshEvent
import com.pam.recap.domain.repository.ManualTransactionDraft
import com.pam.recap.domain.repository.ServerCategory
import com.pam.recap.domain.repository.ServerCategoryJob
import com.pam.recap.domain.repository.ServerFinanceRepository
import com.pam.recap.domain.repository.ServerImportCandidate
import com.pam.recap.domain.repository.ServerImportCounts
import com.pam.recap.domain.repository.ServerImportJob
import com.pam.recap.domain.repository.ServerTransactionPage
import com.pam.recap.domain.usecase.LinkRefundUseCase
import com.pam.recap.domain.usecase.MarkInternalTransferUseCase
import com.pam.recap.domain.usecase.UpdateTransactionCategoryUseCase
import com.pam.recap.domain.usecase.UpdateTransactionNoteUseCase
import com.pam.recap.testing.FakeAnalysisRepository
import com.pam.recap.testing.FakeSessionRepository
import com.pam.recap.testing.FakeSummaryRepository
import com.pam.recap.testing.FakeTransactionRepository
import com.pam.recap.testing.fixedClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.time.Instant

/**
 * The cross-screen rule from the auto-sync mandate (target 1): a successful category correction on
 * the detail screen must invalidate the owner+month every other page reads, on the same navigation
 * instance, and a conflicting write must invalidate nothing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CategoryInvalidationTest {

    private val now = Instant.parse("2026-11-19T05:00:00Z")
    private val owner = "u_owner"
    private val month = JakartaMonth.of(Instant.parse("2026-11-19T02:15:20Z"))

    private val session = Session(
        ownerId = owner,
        displayName = "Andhika",
        mailboxLabel = "andhika@example.test",
        signedInAt = now,
        isDemo = false,
    )

    private val row = Transaction(
        id = "tx_1", ownerId = owner, direction = Direction.DEBIT,
        source = TransactionSource.EMAIL, amount = Money(16_000), currency = "IDR",
        eventAt = Instant.parse("2026-11-19T02:15:20Z"), receivedAt = null,
        description = "QRIS", merchantLabel = "BAKSO UJI", acquirer = null,
        status = ParseStatus.POSTED, fingerprint = "a".repeat(32),
        parserVersion = "livin-v1", parserConfidence = 0.9f,
        category = Category.UNCATEGORIZED, categoryOrigin = CategoryOrigin.LLM,
        categoryConfidence = null, categoryUpdatedAt = null,
        isInternalTransfer = false, linkedRefundOf = null, note = null, importJobId = null,
        revision = 3, createdAt = now, updatedAt = now,
    )

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel(server: ServerFinanceRepository?): TransactionDetailViewModel {
        val transactions = FakeTransactionRepository(listOf(row))
        return TransactionDetailViewModel(
            savedStateHandle = SavedStateHandle(mapOf("transactionId" to "tx_1")),
            sessions = FakeSessionRepository(session),
            transactions = transactions,
            updateCategory = UpdateTransactionCategoryUseCase(
                transactions = transactions,
                summaries = FakeSummaryRepository(),
                analyses = FakeAnalysisRepository(),
                clock = fixedClock(now),
            ),
            markTransfer = MarkInternalTransferUseCase(
                transactions = transactions,
                summaries = FakeSummaryRepository(),
                analyses = FakeAnalysisRepository(),
                clock = fixedClock(now),
            ),
            linkRefund = LinkRefundUseCase(
                transactions = transactions,
                summaries = FakeSummaryRepository(),
                analyses = FakeAnalysisRepository(),
                clock = fixedClock(now),
            ),
            updateNote = UpdateTransactionNoteUseCase(transactions, fixedClock(now)),
            server = server,
        )
    }

    /** Collects the shared bus while the action runs; the bus has no replay, so subscribe first. */
    private fun TestScope.recordEvents() = mutableListOf<FinanceRefreshEvent>().also { sink ->
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            FinanceRefreshBus.events.collect { sink += it }
        }
    }

    @Test
    fun `a server category change publishes the owner and month invalidation`() = runTest {
        val server = FakeServerFinanceRepository(conflict = false)
        val vm = viewModel(server)
        val events = recordEvents()
        vm.state.first { it.transaction != null }

        vm.setCategory(Category.FOOD)
        server.awaitWrites()
        vm.state.first { it.savedAt != null }

        assertEquals(1, events.size)
        assertEquals(owner, events.single().ownerId)
        assertEquals(month.id(), events.single().monthId)
        // The write went out with the revision the screen loaded, not a guessed one.
        assertEquals(3, server.lastRevision)
    }

    @Test
    fun `a conflicting write publishes no invalidation and reports the conflict`() = runTest {
        val server = FakeServerFinanceRepository(conflict = true)
        val vm = viewModel(server)
        val events = recordEvents()
        vm.state.first { it.transaction != null }

        vm.setCategory(Category.FOOD)
        server.awaitWrites()
        val failed = vm.state.first { it.error != null }

        assertTrue(failed.error is AppError.Conflict)
        assertTrue(events.isEmpty())
    }

    @Test
    fun `an owner or month mismatch is not broadcast`() = runTest {
        val vm = viewModel(server = null)
        val events = recordEvents()
        vm.state.first { it.transaction != null }

        vm.setCategory(Category.FOOD)
        vm.state.first { it.savedAt != null }

        val published = events.single()
        // A different owner must not act on somebody else's invalidation.
        assertTrue(published.ownerId == owner && published.monthId == month.id())
    }

    private val fixtureNow = Instant.parse("2026-11-19T05:00:00Z")

    private inner class FakeServerFinanceRepository(private val conflict: Boolean) : ServerFinanceRepository {
        override val isEnabled = true
        @Volatile var lastRevision: Int? = null
        private val writes = kotlinx.coroutines.CompletableDeferred<Unit>()

        suspend fun awaitWrites() = withTimeout(5_000) { writes.await() }

        override suspend fun categories(ownerId: String): Outcome<List<ServerCategory>> =
            Outcome.Success(listOf(ServerCategory("cat_food", "food", "Makanan", "expense")))

        override suspend fun get(ownerId: String, transactionId: String): Outcome<Transaction> =
            Outcome.Success(
                Transaction(
                    id = transactionId, ownerId = ownerId, direction = Direction.DEBIT,
                    source = TransactionSource.EMAIL, amount = Money(16_000), currency = "IDR",
                    eventAt = Instant.parse("2026-11-19T02:15:20Z"), receivedAt = null,
                    description = "QRIS", merchantLabel = "BAKSO UJI", acquirer = null,
                    status = ParseStatus.POSTED, fingerprint = "a".repeat(32),
                    parserVersion = "livin-v1", parserConfidence = 0.9f,
                    category = Category.UNCATEGORIZED, categoryOrigin = CategoryOrigin.LLM,
                    categoryConfidence = null, categoryUpdatedAt = null,
                    isInternalTransfer = false, linkedRefundOf = null, note = null,
                    importJobId = null, revision = 3, createdAt = fixtureNow, updatedAt = fixtureNow,
                ),
            )

        override suspend fun updateCategory(
            ownerId: String,
            transactionId: String,
            categoryId: String,
            revision: Int,
        ): Outcome<Transaction> {
            lastRevision = revision
            writes.complete(Unit)
            if (conflict) {
                return Outcome.Failure(AppError.Conflict("revision_conflict"))
            }
            return get(ownerId, transactionId)
        }

        override suspend fun listMonth(
            ownerId: String,
            month: JakartaMonth,
            limit: Int,
            cursor: String?,
        ): Outcome<ServerTransactionPage> = Outcome.Success(ServerTransactionPage(emptyList()))

        override suspend fun summary(ownerId: String, month: JakartaMonth): Outcome<MonthlySummary> =
            Outcome.Failure(AppError.NotFound("summary"))

        override suspend fun createManual(
            ownerId: String,
            draft: ManualTransactionDraft,
            idempotencyKey: String,
        ): Outcome<Transaction> = Outcome.Failure(AppError.NotFound("create"))

        override suspend fun classifyTransactions(
            ownerId: String,
            transactionIds: List<String>,
        ): Outcome<List<ServerCategoryJob>> = Outcome.Success(emptyList())

        override suspend fun createImportJob(
            ownerId: String,
            month: JakartaMonth,
            templateVersion: String,
        ): Outcome<ServerImportJob> = Outcome.Failure(AppError.NotFound("job"))

        override suspend fun importBatch(
            ownerId: String,
            jobId: String,
            batchKey: String,
            pageOrdinal: Int,
            candidates: List<ServerImportCandidate>,
        ): Outcome<ServerImportCounts> = Outcome.Failure(AppError.NotFound("batch"))

        override suspend fun finalizeImportJob(
            ownerId: String,
            jobId: String,
            state: String,
            errorCode: String?,
        ): Outcome<ServerImportJob> = Outcome.Failure(AppError.NotFound("job"))
    }
}
