package com.pam.recap.data.assessment

import com.pam.recap.core.money.Money
import com.pam.recap.core.time.JakartaMonth
import com.pam.recap.domain.model.Analysis
import com.pam.recap.domain.model.AnalysisStatus
import com.pam.recap.domain.model.BudgetPlan
import com.pam.recap.domain.model.Category
import com.pam.recap.domain.model.CategoryOrigin
import com.pam.recap.domain.model.Consent
import com.pam.recap.domain.model.ConsentDecision
import com.pam.recap.domain.model.ConsentPurpose
import com.pam.recap.domain.model.DeletionRequest
import com.pam.recap.domain.model.GmailConnection
import com.pam.recap.domain.model.ImportJob
import com.pam.recap.domain.model.InsertResult
import com.pam.recap.domain.model.MonthTotals
import com.pam.recap.domain.model.MonthlySummary
import com.pam.recap.domain.model.SkippedCandidate
import com.pam.recap.domain.model.SummaryFreshness
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
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Instant

/**
 * S4.4 Room-free adapters for the assessment variant.
 *
 * The assessment build is the *server-backed* variant: finance data, categories, budgets and
 * imports are read/written through [com.pam.recap.domain.repository.ServerFinanceRepository].
 * Local persistence exists only where the app genuinely needs something durable without Room:
 * - the import outbox in [AssessmentOutbox] (upload-journal snapshot across restarts),
 * - the keyset-encrypted journal in `data/security` (shared with the standard variant).
 *
 * Everything else here is a session-scoped cache. It is intentionally *not* a hidden Room fallback:
 * when no backend is configured, the server port reports `backend_not_configured` and these
 * adapters never invent rows from a database that is not in the build.
 */
@Singleton
class AssessmentTransactionRepository @Inject constructor(
    private val outbox: AssessmentOutbox,
) : TransactionRepository {

    override fun observeMonth(
        ownerId: String,
        month: JakartaMonth,
        filter: TransactionFilter?,
    ): Flow<List<Transaction>> = outbox.changes.map { rowsOf(ownerId, month, filter) }

    override suspend fun listMonth(ownerId: String, month: JakartaMonth): List<Transaction> =
        rowsOf(ownerId, month, null)

    override suspend fun listByImportJob(ownerId: String, importJobId: String): List<Transaction> =
        outbox.rowsForJob(ownerId, importJobId).map { it.toDomain() }

    override suspend fun page(
        ownerId: String,
        filter: TransactionFilter,
        offset: Int,
        limit: Int,
    ): TransactionPage {
        require(offset >= 0) { "offset must be >= 0" }
        require(limit > 0) { "limit must be positive" }
        val all = rowsOf(ownerId, filter.month, filter)
        val items = all.drop(offset).take(limit)
        return TransactionPage(
            items = items,
            totalCount = all.size,
            offset = offset,
            hasMore = offset + items.size < all.size,
        )
    }

    override suspend fun get(ownerId: String, id: String): Transaction? =
        outbox.get(ownerId, id)?.toDomain()

    override suspend fun findByFingerprint(ownerId: String, fingerprint: String): Transaction? =
        outbox.find(ownerId, fingerprint)?.toDomain()

    override suspend fun insertIgnoringDuplicates(
        ownerId: String,
        transactions: List<Transaction>,
    ): InsertResult = outbox.insert(
        ownerId,
        transactions.map { AssessmentOutbox.StagedTransaction.fromDomain(it) },
    )

    override suspend fun updateCategory(
        ownerId: String,
        id: String,
        category: Category,
        origin: CategoryOrigin,
        confidence: Float?,
        at: Instant,
    ): Boolean = mutate(ownerId, id) { row ->
        row.copy(
            categoryCode = category.code,
            categoryOrigin = origin.name,
            categoryConfidence = confidence,
            categoryUpdatedAtMillis = at.toEpochMilliseconds(),
            updatedAtMillis = at.toEpochMilliseconds(),
            revision = row.revision + 1,
        )
    }

    override suspend fun setInternalTransfer(
        ownerId: String,
        id: String,
        isInternalTransfer: Boolean,
        at: Instant,
    ): Boolean = mutate(ownerId, id) { row ->
        row.copy(
            isInternalTransfer = isInternalTransfer,
            updatedAtMillis = at.toEpochMilliseconds(),
            revision = row.revision + 1,
        )
    }

    override suspend fun linkRefund(
        ownerId: String,
        refundId: String,
        expenseId: String?,
        at: Instant,
    ): Boolean = mutate(ownerId, refundId) { row ->
        row.copy(
            linkedRefundOf = expenseId,
            updatedAtMillis = at.toEpochMilliseconds(),
            revision = row.revision + 1,
        )
    }

    override suspend fun updateNote(
        ownerId: String,
        id: String,
        note: String?,
        at: Instant,
    ): Boolean = mutate(ownerId, id) { row ->
        row.copy(note = note, updatedAtMillis = at.toEpochMilliseconds(), revision = row.revision + 1)
    }

    override suspend fun monthRevision(ownerId: String, month: JakartaMonth): Int =
        outbox.monthRevision(ownerId, month)

    override suspend fun bumpMonthRevision(ownerId: String, month: JakartaMonth): Int =
        outbox.bumpMonthRevision(ownerId, month)

    override fun observeMonthTotals(ownerId: String, month: JakartaMonth): Flow<MonthTotals> =
        outbox.changes.map { totalsOf(ownerId, month) }

    override suspend fun monthTotals(ownerId: String, month: JakartaMonth): MonthTotals =
        totalsOf(ownerId, month)

    override suspend fun totalAmountForCategory(
        ownerId: String,
        month: JakartaMonth,
        category: Category,
    ): Money = rowsOf(ownerId, month, null)
        .filter { it.countsTowardsTotals && it.category == category }
        .sumOfMoney { it.amount }

    override suspend fun latestEventAt(ownerId: String): Instant? =
        outbox.rows(ownerId).maxOfOrNull { it.eventAtMillis }?.let { Instant.fromEpochMilliseconds(it) }

    override suspend fun deleteAll(ownerId: String) = outbox.deleteAll(ownerId)

    override suspend fun countByIdPrefix(ownerId: String, prefix: String): Int =
        outbox.countByIdPrefix(ownerId, prefix)

    override suspend fun deleteByIdPrefix(ownerId: String, prefix: String): Int =
        outbox.deleteByIdPrefix(ownerId, prefix)

    private fun rowsOf(
        ownerId: String,
        month: JakartaMonth,
        filter: TransactionFilter?,
    ): List<Transaction> {
        val range = month.range()
        return outbox.rows(ownerId)
            .asSequence()
            .filterRow(range.startInclusive, range.endExclusive, filter)
            .map { it.toDomain() }
            .sortedByDescending { it.eventAt }
            .toList()
    }

    private fun Sequence<AssessmentOutbox.StagedTransaction>.filterRow(
        start: Instant,
        end: Instant,
        filter: TransactionFilter?,
    ): Sequence<AssessmentOutbox.StagedTransaction> = filter { row ->
        val at = Instant.fromEpochMilliseconds(row.eventAtMillis)
        at >= start && at < end &&
            (filter?.category == null || row.categoryCode == filter.category?.code) &&
            (filter?.direction == null || row.direction == filter.direction?.name) &&
            (filter?.query.isNullOrBlank() || row.description.contains(filter?.query ?: "", true) ||
                row.merchantLabel?.contains(filter?.query ?: "", true) == true ||
                row.note?.contains(filter?.query ?: "", true) == true)
    }

    private fun mutate(
        ownerId: String,
        id: String,
        transform: (AssessmentOutbox.StagedTransaction) -> AssessmentOutbox.StagedTransaction,
    ): Boolean {
        val row = outbox.get(ownerId, id) ?: return false
        outbox.replace(ownerId, transform(row))
        return true
    }

    private fun totalsOf(ownerId: String, month: JakartaMonth): MonthTotals {
        val range = month.range()
        val rows = outbox.rows(ownerId).map { it.toDomain() }
            .filter { it.eventAt >= range.startInclusive && it.eventAt < range.endExclusive }
        val counted = rows.filter { it.countsTowardsTotals }
        return MonthTotals(
            grossInflow = counted.filter { it.isCredit }.sumOfMoney { it.amount },
            grossOutflow = counted.filter { it.isDebit }.sumOfMoney { it.amount },
            creditCount = counted.count { it.isCredit },
            debitCount = counted.count { it.isDebit },
            transactionCount = rows.size,
            nonIdrCount = rows.count { !it.isIdr },
            uncategorizedCount = rows.count { it.category.isUncategorized },
            manualCount = rows.count { it.isManual },
            latestEventAt = rows.maxOfOrNull { it.eventAt },
            latestUpdatedAt = rows.maxOfOrNull { it.updatedAt },
        )
    }

    private fun Iterable<Transaction>.sumOfMoney(selector: (Transaction) -> Money): Money =
        Money(sumOf { selector(it).minor })
}

/** Session-scoped import-job cache; the durable recovery identity lives in the upload journal. */
@Singleton
class AssessmentImportRepository @Inject constructor() : ImportRepository {

    private val jobs = MutableStateFlow<Map<String, ImportJob>>(emptyMap())
    private val skipped = MutableStateFlow<List<SkippedCandidate>>(emptyList())

    override suspend fun create(job: ImportJob) {
        jobs.value = jobs.value + (job.id to job)
    }

    override suspend fun update(job: ImportJob) {
        jobs.value = jobs.value + (job.id to job)
    }

    override fun observe(jobId: String): Flow<ImportJob?> = jobs.map { it[jobId] }

    override suspend fun get(jobId: String): ImportJob? = jobs.value[jobId]

    override suspend fun latestForMonth(ownerId: String, month: JakartaMonth): ImportJob? =
        jobs.value.values
            .filter { it.ownerId == ownerId && it.month == month }
            .maxByOrNull { it.startedAt }

    override fun observeLatestForMonth(ownerId: String, month: JakartaMonth): Flow<ImportJob?> =
        jobs.map { all ->
            all.values.filter { it.ownerId == ownerId && it.month == month }.maxByOrNull { it.startedAt }
        }

    override suspend fun saveSkipped(candidates: List<SkippedCandidate>) {
        skipped.value = skipped.value + candidates
    }

    override suspend fun skippedFor(jobId: String): List<SkippedCandidate> =
        skipped.value.filter { it.jobId == jobId }

    override suspend fun deleteAll(ownerId: String) {
        jobs.value = jobs.value.filterValues { it.ownerId != ownerId }
        skipped.value = skipped.value.filterNot { it.ownerId == ownerId }
    }

    override suspend fun delete(ownerId: String, jobId: String): Boolean {
        val job = jobs.value[jobId]?.takeIf { it.ownerId == ownerId } ?: return false
        jobs.value = jobs.value - job.id
        return true
    }
}

/** Session-scoped summary cache; reads in server mode go through the server port. */
@Singleton
class AssessmentSummaryRepository @Inject constructor() : SummaryRepository {

    private val summaries = MutableStateFlow<Map<String, MonthlySummary>>(emptyMap())

    override fun observeSummary(ownerId: String, month: JakartaMonth): Flow<MonthlySummary?> =
        summaries.map { it[key(ownerId, month)] }

    override suspend fun get(
        ownerId: String,
        month: JakartaMonth,
        inputRevision: Int?,
    ): MonthlySummary? = summaries.value[key(ownerId, month)]
        ?.takeIf { inputRevision == null || it.inputRevision == inputRevision }

    override suspend fun save(summary: MonthlySummary) {
        summaries.value = summaries.value + (key(summary.ownerId, summary.month) to summary)
    }

    override suspend fun markOutdated(ownerId: String, month: JakartaMonth) {
        val current = summaries.value[key(ownerId, month)] ?: return
        summaries.value = summaries.value + (
            key(ownerId, month) to current.copy(
                isOutdated = true,
                freshness = SummaryFreshness.STALE,
            )
            )
    }

    override suspend fun deleteAll(ownerId: String) {
        summaries.value = summaries.value.filterKeys { !it.startsWith(ownerPrefix(ownerId)) }
    }

    override suspend fun delete(ownerId: String, month: JakartaMonth): Int {
        val key = key(ownerId, month)
        if (!summaries.value.containsKey(key)) return 0
        summaries.value = summaries.value - key
        return 1
    }

    private fun key(ownerId: String, month: JakartaMonth): String = "${ownerPrefix(ownerId)}${month.id()}"

    private fun ownerPrefix(ownerId: String): String = "$ownerId|"
}

/** Session-scoped analysis cache. */
@Singleton
class AssessmentAnalysisRepository @Inject constructor() : AnalysisRepository {

    private val analyses = MutableStateFlow<Map<String, Analysis>>(emptyMap())

    override fun observeLatest(ownerId: String, monthId: String): Flow<Analysis?> =
        analyses.map { it[key(ownerId, monthId)] }

    override suspend fun latest(ownerId: String, monthId: String): Analysis? =
        analyses.value[key(ownerId, monthId)]

    override suspend fun save(analysis: Analysis) {
        analyses.value = analyses.value + (key(analysis.ownerId, analysis.monthId) to analysis)
    }

    override suspend fun markOutdated(ownerId: String, monthId: String) {
        val current = analyses.value[key(ownerId, monthId)] ?: return
        analyses.value = analyses.value + (key(ownerId, monthId) to current.copy(status = AnalysisStatus.OUTDATED))
    }

    override suspend fun markAllHistorical(ownerId: String) {
        analyses.value = analyses.value.mapValues { (key, analysis) ->
            if (key.startsWith("$ownerId|")) analysis.copy(status = AnalysisStatus.HISTORICAL) else analysis
        }
    }

    override suspend fun deleteAll(ownerId: String) {
        analyses.value = analyses.value.filterKeys { !it.startsWith("$ownerId|") }
    }

    private fun key(ownerId: String, monthId: String): String = "$ownerId|$monthId"
}

/** Session-scoped budget cache; the server plan is authoritative when configured. */
@Singleton
class AssessmentBudgetRepository @Inject constructor() : BudgetRepository {

    private val plans = MutableStateFlow<Map<String, BudgetPlan>>(emptyMap())

    override fun observeActive(ownerId: String, month: JakartaMonth): Flow<BudgetPlan?> =
        plans.map { it[key(ownerId, month)] }

    override suspend fun active(ownerId: String, month: JakartaMonth): BudgetPlan? =
        plans.value[key(ownerId, month)]

    override suspend fun save(plan: BudgetPlan) {
        plans.value = plans.value + (key(plan.ownerId, plan.month) to plan)
    }

    override suspend fun deleteAll(ownerId: String) {
        plans.value = plans.value.filterKeys { !it.startsWith("$ownerId|") }
    }

    private fun key(ownerId: String, month: JakartaMonth): String = "$ownerId|${month.id()}"
}

/** Session-scoped consent ledger copy; the server consent state is the shared source of truth. */
@Singleton
class AssessmentConsentRepository @Inject constructor() : ConsentRepository {

    private val ledger = MutableStateFlow<Map<String, List<Consent>>>(emptyMap())

    override fun observeLedger(ownerId: String): Flow<List<Consent>> = ledger.map { it[ownerId].orEmpty() }

    override fun observeLatest(ownerId: String, purpose: ConsentPurpose): Flow<Consent?> =
        ledger.map { all -> all[ownerId].orEmpty().lastOrNull { it.purpose == purpose } }

    override suspend fun latest(ownerId: String, purpose: ConsentPurpose): Consent? =
        ledger.value[ownerId].orEmpty().lastOrNull { it.purpose == purpose }

    override suspend fun append(consent: Consent) {
        ledger.value = ledger.value + (
            consent.ownerId to (ledger.value[consent.ownerId].orEmpty() + consent)
            )
    }

    override suspend fun hasGranted(ownerId: String, purpose: ConsentPurpose): Boolean =
        latest(ownerId, purpose)?.decision == ConsentDecision.GRANTED

    override suspend fun deleteAll(ownerId: String) {
        ledger.value = ledger.value - ownerId
    }
}

/** Session-scoped mailbox connection facts (never tokens). */
@Singleton
class AssessmentGmailConnectionRepository @Inject constructor() : GmailConnectionRepository {

    private val connections = MutableStateFlow<Map<String, GmailConnection>>(emptyMap())

    override fun observe(ownerId: String): Flow<GmailConnection?> = connections.map { it[ownerId] }

    override suspend fun get(ownerId: String): GmailConnection? = connections.value[ownerId]

    override suspend fun save(connection: GmailConnection) {
        connections.value = connections.value + (connection.ownerId to connection)
    }

    override suspend fun touchLastImport(ownerId: String, at: Instant) {
        val current = connections.value[ownerId] ?: return
        connections.value = connections.value + (ownerId to current.copy(lastImportAt = at))
    }

    override suspend fun touchLastAi(ownerId: String, at: Instant) {
        val current = connections.value[ownerId] ?: return
        connections.value = connections.value + (ownerId to current.copy(lastAiAt = at))
    }

    override suspend fun deleteAll(ownerId: String) {
        connections.value = connections.value - ownerId
    }
}

/** Session-scoped deletion lifecycle; the server receipt remains the durable record. */
@Singleton
class AssessmentDeletionRepository @Inject constructor() : DeletionRepository {

    private val requests = MutableStateFlow<Map<String, List<DeletionRequest>>>(emptyMap())

    override fun observeLatest(ownerId: String): Flow<DeletionRequest?> =
        requests.map { it[ownerId].orEmpty().maxByOrNull { req -> req.requestedAt } }

    override suspend fun latest(ownerId: String): DeletionRequest? =
        requests.value[ownerId].orEmpty().maxByOrNull { it.requestedAt }

    override suspend fun active(ownerId: String): DeletionRequest? =
        requests.value[ownerId].orEmpty().lastOrNull { it.isInProgress }

    override suspend fun save(request: DeletionRequest) {
        requests.value = requests.value + (
            request.ownerId to (requests.value[request.ownerId].orEmpty() + request)
            )
    }

    override suspend fun deleteAll(ownerId: String) {
        requests.value = requests.value - ownerId
    }
}
