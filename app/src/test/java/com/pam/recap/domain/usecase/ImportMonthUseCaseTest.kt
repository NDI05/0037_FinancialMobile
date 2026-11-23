package com.pam.recap.domain.usecase

import com.pam.recap.core.result.Outcome
import com.pam.recap.domain.model.GmailConnectionState
import com.pam.recap.domain.repository.GmailMessageRef
import com.pam.recap.domain.repository.GmailPage
import com.pam.recap.domain.repository.GmailSource
import com.pam.recap.domain.repository.LivinQuery
import com.pam.recap.domain.repository.RawMessage
import com.pam.recap.core.money.Money
import com.pam.recap.core.security.FingerprintFactory
import com.pam.recap.core.time.ClockProvider
import com.pam.recap.core.time.JakartaMonth
import com.pam.recap.core.util.IdGenerator
import com.pam.recap.data.gmail.FixtureGmailSource
import com.pam.recap.data.gmail.FixtureScenario
import com.pam.recap.data.gmail.LivinQrisParser
import com.pam.recap.data.gmail.LivinQueryBuilder
import com.pam.recap.data.gmail.LivinTemplateRegistry
import com.pam.recap.data.seed.LivinFixtures
import com.pam.recap.domain.model.Analysis
import com.pam.recap.domain.model.BudgetPlan
import com.pam.recap.domain.model.Category
import com.pam.recap.domain.model.CategoryOrigin
import com.pam.recap.domain.model.Consent
import com.pam.recap.domain.model.ConsentPurpose
import com.pam.recap.domain.model.DeletionRequest
import com.pam.recap.domain.model.GmailConnection
import com.pam.recap.domain.model.ImportJob
import com.pam.recap.domain.model.ImportState
import com.pam.recap.domain.model.InsertResult
import com.pam.recap.domain.model.MonthTotals
import com.pam.recap.domain.model.MonthlySummary
import com.pam.recap.domain.model.RedactionReason
import com.pam.recap.domain.model.SkippedCandidate
import com.pam.recap.domain.model.Transaction
import com.pam.recap.domain.model.TransactionFilter
import com.pam.recap.domain.model.TransactionPage
import com.pam.recap.domain.repository.AnalysisRepository
import com.pam.recap.domain.repository.BudgetRepository
import com.pam.recap.domain.repository.ConsentRepository
import com.pam.recap.domain.repository.DeletionRepository
import com.pam.recap.domain.repository.GmailConnectionRepository
import com.pam.recap.domain.repository.ImportRepository
import com.pam.recap.domain.repository.SummaryRepository
import com.pam.recap.domain.repository.TransactionRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Instant

/**
 * FR-16…FR-22 end-to-end over the deterministic fixture mailbox: idempotent dedupe, partial page
 * failure, revoked grant, empty result and redacted skip reasons.
 *
 * Uses in-memory fakes for the repositories so the pipeline is exercised without Room.
 */
class ImportMonthUseCaseTest {

    private val month = JakartaMonth(2026, 2)
    private val registry = LivinTemplateRegistry()
    private val source = FixtureGmailSource(registry)
    private val now = Instant.parse("2026-03-02T03:00:00Z")
    private var idCounter = 0

    private val fixedClock = object : ClockProvider {
        override fun now(): Instant = now
    }

    private val transactions = FakeTransactionRepository()
    private val imports = FakeImportRepository()
    private val summaries = FakeSummaryRepository()
    private val analyses = FakeAnalysisRepository()
    private val connections = FakeGmailConnectionRepository()
    private val deletion = FakeDeletionRepository()

    private val useCase = ImportMonthUseCase(
        gmailSource = source,
        queryBuilder = LivinQueryBuilder(registry),
        parser = LivinQrisParser(registry),
        fingerprints = FingerprintFactory { ownerId -> "test-key::$ownerId".toByteArray() },
        categoryRules = MerchantCategoryRules(),
        transactions = transactions,
        imports = imports,
        summaries = summaries,
        analyses = analyses,
        gmailConnection = connections,
        deletion = deletion,
        clock = fixedClock,
        idGenerator = IdGenerator { "id-${idCounter++}" },
    )

    private suspend fun connect() {
        source.authorize()
    }

    @Test
    fun `normal import paginates at twenty and imports the february rows`() = runTest {
        connect()

        val job = useCase("u_demo", month).let { (it as com.pam.recap.core.result.Outcome.Success).value }

        assertEquals(ImportState.COMPLETE, job.state)
        // The fixture mailbox has more than one page of February messages.
        assertEquals(2, job.pagesProcessed)
        assertTrue("expected at least 20 imports, was ${job.importedCount}", job.importedCount >= 20)
        assertEquals(0, job.unresolvedCount)
        assertTrue(job.matchedCount >= job.importedCount)
        assertNotNull(job.finishedAt)
    }

    @Test
    fun `re-importing the same month inserts nothing and counts duplicates`() = runTest {
        connect()
        val first = (useCase("u_demo", month) as com.pam.recap.core.result.Outcome.Success).value
        val second = (useCase("u_demo", month) as com.pam.recap.core.result.Outcome.Success).value

        assertEquals(first.importedCount, transactionCount())
        assertEquals(0, second.importedCount)
        assertEquals(first.importedCount, second.duplicateCount)
    }

    @Test
    fun `a failure on page two keeps page one and reports partial`() = runTest {
        connect()
        source.setScenario(FixtureScenario.PARTIAL_FAILURE)

        val job = (useCase("u_demo", month) as com.pam.recap.core.result.Outcome.Success).value

        assertEquals(ImportState.PARTIAL, job.state)
        assertEquals(1, job.pagesProcessed)
        // Page 1 is persisted; nothing is lost by the failed second page.
        assertTrue(job.importedCount > 0)
        assertEquals(job.importedCount, transactionCount())
        assertNotNull(job.errorCode)
    }

    @Test
    fun `a revoked grant reports permission required and imports nothing`() = runTest {
        source.setScenario(FixtureScenario.REVOKED)

        val job = (useCase("u_demo", month) as com.pam.recap.core.result.Outcome.Success).value

        assertEquals(ImportState.PERMISSION_REQUIRED, job.state)
        assertEquals(0, job.importedCount)
        assertEquals(0, transactionCount())
    }

    @Test
    fun `an empty mailbox reports empty rather than zero spending`() = runTest {
        connect()
        source.setScenario(FixtureScenario.EMPTY)

        val job = (useCase("u_demo", month) as com.pam.recap.core.result.Outcome.Success).value

        assertEquals(ImportState.EMPTY, job.state)
        assertTrue(job.isEmptyResult)
        assertEquals(0, job.matchedCount)
    }

    @Test
    fun `rejected candidates are recorded with redacted reasons only`() = runTest {
        connect()

        val job = (useCase("u_demo", month) as com.pam.recap.core.result.Outcome.Success).value
        val skipped = imports.skippedFor(job.id)

        assertTrue("expected skipped candidates", skipped.isNotEmpty())
        val reasons = skipped.map { it.reason }.toSet()
        assertTrue(RedactionReason.FAILED_PAYMENT in reasons)
        assertTrue(RedactionReason.MISSING_AMOUNT in reasons)
        assertTrue(RedactionReason.ATTACHMENT_ONLY in reasons)
        // FR-15: nothing beyond the reason and positional ordinals is retained.
        skipped.forEach { candidate ->
            assertTrue(candidate.reason.name.isNotBlank())
            assertTrue(candidate.pageOrdinal >= 0)
        }
    }

    @Test
    fun `imported rows carry fingerprint, parser version and a category`() = runTest {
        connect()
        useCase("u_demo", month)

        val rows = transactions.all()
        assertTrue(rows.isNotEmpty())
        rows.forEach { row ->
            assertEquals("u_demo", row.ownerId)
            assertEquals("IDR", row.currency)
            assertEquals(32, row.fingerprint.length)
            assertTrue(row.parserVersion.startsWith("livin-"))
            assertEquals(CategoryOrigin.RULE, row.categoryOrigin)
            assertNotNull(row.importJobId)
        }
        // The own-account transfer is recognised as an internal transfer and stays out of classified.
        assertTrue(rows.any { it.isInternalTransfer })
    }

    @Test
    fun `a stalled mailbox ends the job at the deadline instead of leaving it running`() = runTest {
        val stalling = StallingGmailSource(now)
        val stalledCase = ImportMonthUseCase(
            gmailSource = stalling,
            queryBuilder = LivinQueryBuilder(registry),
            parser = LivinQrisParser(registry),
            fingerprints = FingerprintFactory { ownerId -> "test-key::$ownerId".toByteArray() },
            categoryRules = MerchantCategoryRules(),
            transactions = transactions,
            imports = imports,
            summaries = summaries,
            analyses = analyses,
            gmailConnection = connections,
            deletion = deletion,
            clock = fixedClock,
            idGenerator = IdGenerator { "id-stall" },
        )

        val job = (stalledCase("u_demo", month) as com.pam.recap.core.result.Outcome.Success).value

        // NFR-08: never RUNNING after the deadline; the page that never completed is not claimed.
        assertEquals(ImportState.FAILED, job.state)
        assertEquals("deadline_exceeded", job.errorCode)
        assertEquals(0, job.pagesProcessed)
        assertNotNull(job.finishedAt)
    }

    @Test
    fun `an active deletion blocks the import`() = runTest {
        connect()
        deletion.active = DeletionRequest(
            id = "del",
            ownerId = "u_demo",
            state = com.pam.recap.domain.model.DeletionState.IN_PROGRESS,
            requestedAt = now,
            reauthenticatedAt = now,
            completedAt = null,
        )

        val result = useCase("u_demo", month)

        assertTrue(result is com.pam.recap.core.result.Outcome.Failure)
        assertEquals(0, transactionCount())
    }

    @Test
    fun `fixtures outside the month are skipped by membership, not by the query window`() = runTest {
        connect()
        useCase("u_demo", month)

        // The March fixture is outside the widened February window entirely, so it is never seen.
        assertTrue(transactions.all().none { it.eventAt >= JakartaMonth(2026, 3).range().startInclusive })
        // Every stored row is inside February, which is what BR-01 requires.
        assertTrue(transactions.all().all { month.contains(it.eventAt) })
    }

    @Test
    fun `retrying a partial import completes it without duplicating page one`() = runTest {
        connect()
        source.setScenario(FixtureScenario.PARTIAL_FAILURE)
        val first = (useCase("u_demo", month) as com.pam.recap.core.result.Outcome.Success).value
        assertEquals(ImportState.PARTIAL, first.state)
        assertTrue(first.importedCount > 0)

        source.setScenario(FixtureScenario.NORMAL)
        val retry = RetryImportUseCase(useCase, imports)("u_demo", month)
            .let { (it as com.pam.recap.core.result.Outcome.Success).value }

        assertEquals(ImportState.COMPLETE, retry.state)
        // Page one is seen again but deduped by fingerprint; only the missing rows are inserted.
        assertTrue("expected dedupe of page one, was ${retry.duplicateCount}", retry.duplicateCount >= first.importedCount)
        assertEquals(first.importedCount + retry.importedCount, transactionCount())
    }

    @Test
    fun `retrying a complete import returns the stored job and inserts nothing`() = runTest {
        connect()
        val complete = (useCase("u_demo", month) as com.pam.recap.core.result.Outcome.Success).value
        assertEquals(ImportState.COMPLETE, complete.state)
        val before = transactionCount()

        val retry = RetryImportUseCase(useCase, imports)("u_demo", month)
            .let { (it as com.pam.recap.core.result.Outcome.Success).value }

        assertEquals(complete.id, retry.id)
        assertEquals(before, transactionCount())
    }

    private fun transactionCount(): Int = transactions.all().size

    // ---------------------------------------------------------------------------------------
    // Fakes
    // ---------------------------------------------------------------------------------------

    private class FakeTransactionRepository : TransactionRepository {
        private val rows = MutableStateFlow<List<Transaction>>(emptyList())
        private val revisions = mutableMapOf<String, Int>()

        fun all(): List<Transaction> = rows.value

        override fun observeMonth(ownerId: String, month: JakartaMonth, filter: TransactionFilter?): Flow<List<Transaction>> =
            rows.map { list -> list.filter { it.ownerId == ownerId && month.contains(it.eventAt) } }

        override suspend fun listMonth(ownerId: String, month: JakartaMonth): List<Transaction> =
            rows.value.filter { it.ownerId == ownerId && month.contains(it.eventAt) }

        override suspend fun listByImportJob(ownerId: String, importJobId: String): List<Transaction> =
            rows.value.filter { it.ownerId == ownerId && it.importJobId == importJobId }

        override suspend fun page(
            ownerId: String,
            filter: TransactionFilter,
            offset: Int,
            limit: Int,
        ): TransactionPage {
            val items = listMonth(ownerId, filter.month).drop(offset).take(limit)
            return TransactionPage(items, items.size, offset, hasMore = false)
        }

        override suspend fun get(ownerId: String, id: String): Transaction? =
            rows.value.firstOrNull { it.ownerId == ownerId && it.id == id }

        override suspend fun findByFingerprint(ownerId: String, fingerprint: String): Transaction? =
            rows.value.firstOrNull { it.ownerId == ownerId && it.fingerprint == fingerprint }

        override suspend fun insertIgnoringDuplicates(ownerId: String, transactions: List<Transaction>): InsertResult {
            val existing = rows.value.map { it.fingerprint }.toSet()
            val fresh = transactions.filter { it.ownerId == ownerId && it.fingerprint !in existing }
            rows.value = rows.value + fresh
            return InsertResult(inserted = fresh.size, ignored = transactions.size - fresh.size)
        }

        override suspend fun updateCategory(
            ownerId: String,
            id: String,
            category: Category,
            origin: CategoryOrigin,
            confidence: Float?,
            at: Instant,
        ): Boolean = replace(id) { it.copy(category = category, categoryOrigin = origin, categoryConfidence = confidence) }

        override suspend fun setInternalTransfer(ownerId: String, id: String, isInternalTransfer: Boolean, at: Instant): Boolean =
            replace(id) { it.copy(isInternalTransfer = isInternalTransfer) }

        override suspend fun linkRefund(ownerId: String, refundId: String, expenseId: String?, at: Instant): Boolean =
            replace(refundId) { it.copy(linkedRefundOf = expenseId) }

        override suspend fun updateNote(ownerId: String, id: String, note: String?, at: Instant): Boolean =
            replace(id) { it.copy(note = note) }

        override suspend fun monthRevision(ownerId: String, month: JakartaMonth): Int =
            revisions["$ownerId:${month.id()}"] ?: 0

        override suspend fun bumpMonthRevision(ownerId: String, month: JakartaMonth): Int {
            val key = "$ownerId:${month.id()}"
            val next = (revisions[key] ?: 0) + 1
            revisions[key] = next
            return next
        }

        override fun observeMonthTotals(ownerId: String, month: JakartaMonth): Flow<MonthTotals> =
            flowOf(MonthTotals.EMPTY)

        override suspend fun monthTotals(ownerId: String, month: JakartaMonth): MonthTotals = MonthTotals.EMPTY

        override suspend fun totalAmountForCategory(ownerId: String, month: JakartaMonth, category: Category): Money =
            Money.ZERO

        override suspend fun latestEventAt(ownerId: String): Instant? =
            rows.value.filter { it.ownerId == ownerId }.maxOfOrNull { it.eventAt }

        override suspend fun deleteAll(ownerId: String) {
            rows.value = rows.value.filterNot { it.ownerId == ownerId }
        }

        override suspend fun countByIdPrefix(ownerId: String, prefix: String): Int = 0

        override suspend fun deleteByIdPrefix(ownerId: String, prefix: String): Int = 0

        private fun replace(id: String, transform: (Transaction) -> Transaction): Boolean {
            val current = rows.value
            val index = current.indexOfFirst { it.id == id }
            if (index < 0) return false
            rows.value = current.toMutableList().also { it[index] = transform(it[index]) }
            return true
        }
    }

    private class FakeImportRepository : ImportRepository {
        private val jobs = mutableMapOf<String, ImportJob>()
        private val skipped = mutableListOf<SkippedCandidate>()
        private val latest = MutableStateFlow<ImportJob?>(null)

        override suspend fun create(job: ImportJob) {
            jobs[job.id] = job
            latest.value = job
        }

        override suspend fun update(job: ImportJob) {
            jobs[job.id] = job
            latest.value = job
        }

        override fun observe(jobId: String): Flow<ImportJob?> = flowOf(jobs[jobId])

        override suspend fun get(jobId: String): ImportJob? = jobs[jobId]

        override suspend fun latestForMonth(ownerId: String, month: JakartaMonth): ImportJob? =
            jobs.values.filter { it.ownerId == ownerId && it.month == month }.maxByOrNull { it.startedAt }

        override fun observeLatestForMonth(ownerId: String, month: JakartaMonth): Flow<ImportJob?> = latest

        override suspend fun saveSkipped(candidates: List<SkippedCandidate>) {
            skipped += candidates
        }

        override suspend fun skippedFor(jobId: String): List<SkippedCandidate> = skipped.filter { it.jobId == jobId }

        override suspend fun deleteAll(ownerId: String) = Unit

        override suspend fun delete(ownerId: String, jobId: String): Boolean = false
    }

    private class FakeSummaryRepository : SummaryRepository {
        override fun observeSummary(ownerId: String, month: JakartaMonth): Flow<MonthlySummary?> = flowOf(null)
        override suspend fun get(ownerId: String, month: JakartaMonth, inputRevision: Int?): MonthlySummary? = null
        override suspend fun save(summary: MonthlySummary) = Unit
        override suspend fun markOutdated(ownerId: String, month: JakartaMonth) = Unit
        override suspend fun deleteAll(ownerId: String) = Unit

        override suspend fun delete(ownerId: String, month: JakartaMonth): Int = 0
    }

    private class FakeAnalysisRepository : AnalysisRepository {
        override fun observeLatest(ownerId: String, monthId: String): Flow<Analysis?> = flowOf(null)
        override suspend fun latest(ownerId: String, monthId: String): Analysis? = null
        override suspend fun save(analysis: Analysis) = Unit
        override suspend fun markOutdated(ownerId: String, monthId: String) = Unit
        override suspend fun markAllHistorical(ownerId: String) = Unit
        override suspend fun deleteAll(ownerId: String) = Unit
    }

    private class FakeGmailConnectionRepository : GmailConnectionRepository {
        private var stored: GmailConnection? = null
        override fun observe(ownerId: String): Flow<GmailConnection?> = flowOf(stored)
        override suspend fun get(ownerId: String): GmailConnection? = stored
        override suspend fun save(connection: GmailConnection) {
            stored = connection
        }

        override suspend fun touchLastImport(ownerId: String, at: Instant) = Unit
        override suspend fun touchLastAi(ownerId: String, at: Instant) = Unit
        override suspend fun deleteAll(ownerId: String) = Unit
    }

    private class FakeDeletionRepository : DeletionRepository {
        var active: DeletionRequest? = null
        override fun observeLatest(ownerId: String): Flow<DeletionRequest?> = flowOf(active)
        override suspend fun latest(ownerId: String): DeletionRequest? = active
        override suspend fun active(ownerId: String): DeletionRequest? = active
        override suspend fun save(request: DeletionRequest) {
            active = request
        }

        override suspend fun deleteAll(ownerId: String) = Unit
    }
}

/** A mailbox that answers the search but never returns a message body. */
private class StallingGmailSource(private val at: Instant) : GmailSource {
    private val state = MutableStateFlow(GmailConnectionState.CONNECTED)

    override suspend fun authorize(): Outcome<Unit> = Outcome.Success(Unit)

    override fun connectionState(): Flow<GmailConnectionState> = state

    override suspend fun search(query: LivinQuery, pageToken: String?): Outcome<GmailPage> =
        Outcome.Success(
            GmailPage(
                messages = listOf(GmailMessageRef(id = "1:1", internalDate = at)),
                nextPageToken = null,
                resultSizeEstimate = 1,
            ),
        )

    override suspend fun fetchMessage(id: String): Outcome<RawMessage> {
        // Longer than the job deadline: the use case must cut the wait and finish.
        kotlinx.coroutines.delay(10 * 60 * 60 * 1000L)
        return Outcome.Failure(com.pam.recap.core.result.AppError.Network("never"))
    }

    override suspend fun disconnect(): Outcome<Unit> = Outcome.Success(Unit)
}
