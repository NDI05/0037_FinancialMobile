package com.pam.recap.data.assessment

import com.pam.recap.core.di.IoDispatcher
import com.pam.recap.core.money.Money
import com.pam.recap.core.result.Outcome
import com.pam.recap.core.result.SafeLogger
import com.pam.recap.core.time.JakartaMonth
import com.pam.recap.domain.model.Analysis
import com.pam.recap.domain.model.AnalysisStatus
import com.pam.recap.domain.model.Category
import com.pam.recap.domain.model.CategoryOrigin
import com.pam.recap.domain.model.Consent
import com.pam.recap.domain.model.ConsentDecision
import com.pam.recap.domain.model.ConsentPurpose
import com.pam.recap.domain.model.DeletionRequest
import com.pam.recap.domain.model.GmailConnection
import com.pam.recap.domain.model.GmailConnectionState
import com.pam.recap.domain.model.ImportJob
import com.pam.recap.domain.model.ImportState
import com.pam.recap.domain.model.InsertResult
import com.pam.recap.domain.model.MonthTotals
import com.pam.recap.domain.model.MonthlySummary
import com.pam.recap.domain.model.SkippedCandidate
import com.pam.recap.domain.model.SummaryFreshness
import com.pam.recap.domain.model.Transaction
import com.pam.recap.domain.model.TransactionFilter
import com.pam.recap.domain.model.TransactionPage
import com.pam.recap.data.backend.BackendEndpoints
import com.pam.recap.domain.repository.AnalysisRepository
import com.pam.recap.domain.repository.ConsentRepository
import com.pam.recap.domain.repository.DeletionRepository
import com.pam.recap.domain.repository.GmailConnectionRepository
import com.pam.recap.domain.repository.ImportRepository
import com.pam.recap.domain.repository.SummaryRepository
import com.pam.recap.domain.repository.BackendSessionRepository
import com.pam.recap.domain.repository.TransactionRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Instant

/**
 * S4.4 Room-free adapters for the assessment variant.
 *
 * The assessment build is the *server-backed* variant: finance data, categories and
 * imports are read/written through [com.pam.recap.domain.repository.ServerFinanceRepository].
 * Local persistence exists only where the app genuinely needs something durable without Room:
 * - the import outbox in [AssessmentOutbox] (upload-journal snapshot across restarts),
 * - the Keystore-wrapped [AssessmentDurableState] for the consent ledger, mailbox connection
 *   facts and import-job metadata, so a restart does not look like a fresh install,
 * - the keyset-encrypted journal in `data/security` (shared with the standard variant).
 *
 * Finance rows themselves are never cached locally. It is intentionally *not* a hidden Room
 * fallback: when no backend is configured, the server port reports `backend_not_configured` and
 * these adapters never invent rows from a database that is not in the build.
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

    override suspend fun applyMerchantRepair(
        ownerId: String,
        id: String,
        expectedFingerprint: String,
        merchantLabel: String,
        fingerprint: String,
        at: Instant,
    ): Boolean = mutate(ownerId, id) { row ->
        if (row.fingerprint != expectedFingerprint) return@mutate row
        row.copy(
            merchantLabel = merchantLabel,
            fingerprint = fingerprint,
            updatedAtMillis = at.toEpochMilliseconds(),
            revision = row.revision + 1,
        )
    }

    /** Assessment variant keeps no Room metadata, so the repair marker lives in this process only. */
    private val metadata = mutableMapOf<String, String>()

    override suspend fun metadataValue(ownerId: String, key: String): String? =
        metadata["$ownerId:$key"]

    override suspend fun putMetadata(ownerId: String, key: String, value: String) {
        metadata["$ownerId:$key"] = value
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
        return outbox.replace(ownerId, transform(row))
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

/**
 * Import-job metadata for the assessment variant.
 *
 * The durable recovery identity is the upload journal; this repository only mirrors the job
 * counters the UI shows. It is still persisted through [AssessmentDurableState] (counts and state
 * only, no finance rows) so the import screen does not look reset after a restart. Skipped
 * candidates keep their session cache because they carry message-derived text.
 */
@Singleton
class AssessmentImportRepository @Inject constructor(
    private val durable: AssessmentDurableState,
    private val json: Json,
) : ImportRepository {

    private val jobs = MutableStateFlow<Map<String, ImportJob>>(emptyMap())
    private val skipped = MutableStateFlow<List<SkippedCandidate>>(emptyList())
    private val loadedOwners = mutableSetOf<String>()

    @Synchronized
    private fun ensureLoaded(ownerId: String) {
        if (!loadedOwners.add(ownerId)) return
        val stored = durable.read(ownerId, JOBS_KEY) ?: return
        val decoded = runCatching { json.decodeFromString<List<StoredJob>>(stored) }.getOrDefault(emptyList())
        if (decoded.isEmpty()) return
        val loaded = decoded.mapNotNull { it.toDomain() }
        jobs.value = jobs.value + loaded.associateBy { it.id }
    }

    @Synchronized
    private fun persist(ownerId: String) {
        val owned = jobs.value.values.filter { it.ownerId == ownerId }.map { StoredJob.fromDomain(it) }
        val encoded = json.encodeToString(owned)
        if (!durable.write(ownerId, JOBS_KEY, encoded)) {
            SafeLogger.warning(TAG, "import_jobs_not_persisted")
        }
        owned.forEach { durable.write(null, "${JOB_OWNER_PREFIX}${it.id}", ownerId) }
    }

    @Synchronized
    private fun ownerOfJob(jobId: String): String? =
        jobs.value[jobId]?.ownerId ?: durable.read(null, "$JOB_OWNER_PREFIX$jobId")

    override suspend fun create(job: ImportJob) {
        ensureLoaded(job.ownerId)
        jobs.value = jobs.value + (job.id to job)
        persist(job.ownerId)
    }

    override suspend fun update(job: ImportJob) {
        ensureLoaded(job.ownerId)
        jobs.value = jobs.value + (job.id to job)
        persist(job.ownerId)
    }

    override fun observe(jobId: String): Flow<ImportJob?> = flow {
        ownerOfJob(jobId)?.let { ensureLoaded(it) }
        emitAll(jobs.map { it[jobId] })
    }

    override suspend fun get(jobId: String): ImportJob? {
        ownerOfJob(jobId)?.let { ensureLoaded(it) }
        return jobs.value[jobId]
    }

    override suspend fun latestForMonth(ownerId: String, month: JakartaMonth): ImportJob? {
        ensureLoaded(ownerId)
        return jobs.value.values
            .filter { it.ownerId == ownerId && it.month == month }
            .maxByOrNull { it.startedAt }
    }

    override fun observeLatestForMonth(ownerId: String, month: JakartaMonth): Flow<ImportJob?> = flow {
        ensureLoaded(ownerId)
        emitAll(
            jobs.map { all ->
                all.values.filter { it.ownerId == ownerId && it.month == month }.maxByOrNull { it.startedAt }
            },
        )
    }

    override suspend fun saveSkipped(candidates: List<SkippedCandidate>) {
        skipped.value = skipped.value + candidates
    }

    override suspend fun skippedFor(jobId: String): List<SkippedCandidate> =
        skipped.value.filter { it.jobId == jobId }

    override suspend fun deleteAll(ownerId: String) {
        ensureLoaded(ownerId)
        jobs.value = jobs.value.filterValues { it.ownerId != ownerId }
        skipped.value = skipped.value.filterNot { it.ownerId == ownerId }
        durable.clear(ownerId)
    }

    override suspend fun delete(ownerId: String, jobId: String): Boolean {
        ensureLoaded(ownerId)
        val job = jobs.value[jobId]?.takeIf { it.ownerId == ownerId } ?: return false
        jobs.value = jobs.value - job.id
        persist(ownerId)
        return true
    }

    /** Persisted shape: job counters only; no descriptions, notes or other finance text. */
    @Serializable
    private data class StoredJob(
        val id: String,
        val ownerId: String,
        val monthId: String,
        val state: String,
        val pagesProcessed: Int,
        val pagesExpected: Int? = null,
        val matchedCount: Int,
        val importedCount: Int,
        val duplicateCount: Int,
        val skippedCount: Int,
        val unresolvedCount: Int,
        val templateVersion: String,
        val startedAtMillis: Long,
        val finishedAtMillis: Long? = null,
        val errorCode: String? = null,
    ) {
        fun toDomain(): ImportJob? {
            val month = JakartaMonth.parse(monthId) ?: return null
            val importState = runCatching { ImportState.valueOf(state) }.getOrNull() ?: return null
            return ImportJob(
                id = id,
                ownerId = ownerId,
                month = month,
                state = importState,
                pagesProcessed = pagesProcessed,
                pagesExpected = pagesExpected,
                matchedCount = matchedCount,
                importedCount = importedCount,
                duplicateCount = duplicateCount,
                skippedCount = skippedCount,
                unresolvedCount = unresolvedCount,
                templateVersion = templateVersion,
                startedAt = Instant.fromEpochMilliseconds(startedAtMillis),
                finishedAt = finishedAtMillis?.let { Instant.fromEpochMilliseconds(it) },
                errorCode = errorCode,
            )
        }

        companion object {
            fun fromDomain(job: ImportJob): StoredJob = StoredJob(
                id = job.id,
                ownerId = job.ownerId,
                monthId = job.month.id(),
                state = job.state.name,
                pagesProcessed = job.pagesProcessed,
                pagesExpected = job.pagesExpected,
                matchedCount = job.matchedCount,
                importedCount = job.importedCount,
                duplicateCount = job.duplicateCount,
                skippedCount = job.skippedCount,
                unresolvedCount = job.unresolvedCount,
                templateVersion = job.templateVersion,
                startedAtMillis = job.startedAt.toEpochMilliseconds(),
                finishedAtMillis = job.finishedAt?.toEpochMilliseconds(),
                errorCode = job.errorCode,
            )
        }
    }

    private companion object {
        const val TAG = "AssessmentImportRepository"
        const val JOBS_KEY = "import_jobs"
        const val JOB_OWNER_PREFIX = "import_job_owner|"
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

/**
 * Consent ledger for the assessment variant: durable and server-hydrated.
 *
 * The standard variant reads this from Room. The assessment build keeps the append-only ledger in
 * the Keystore-wrapped [AssessmentDurableState] and, when a backend is configured, hydrates it from
 * `GET /v1/consents` (FR-49) before reporting "no decision". That is what stops
 * [com.pam.recap.ui.session.SessionGateViewModel] from showing the consent screen again on every
 * launch after a restart. Without a backend the durable ledger is the source of truth.
 */
@Singleton
class AssessmentConsentRepository @Inject constructor(
    private val durable: AssessmentDurableState,
    private val endpoints: BackendEndpoints,
    private val client: OkHttpClient,
    private val sessions: BackendSessionRepository,
    private val json: Json,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : ConsentRepository {

    private val ledger = MutableStateFlow<Map<String, List<Consent>>>(emptyMap())
    private val hydrateMutex = Mutex()
    private val serverChecked = mutableSetOf<String>()

    /** Loads the wrapped durable ledger once per process; safe to call from any thread. */
    @Synchronized
    private fun ensureLoaded(ownerId: String) {
        if (ledger.value.containsKey(ownerId)) return
        val stored = durable.read(ownerId, LEDGER_KEY) ?: return
        val decoded = runCatching { json.decodeFromString<List<StoredConsent>>(stored) }
            .getOrDefault(emptyList())
        ledger.value = ledger.value + (ownerId to decoded.map { it.toDomain() })
    }

    @Synchronized
    private fun persist(ownerId: String, entries: List<Consent>) {
        if (!durable.write(ownerId, LEDGER_KEY, json.encodeToString(entries.map { StoredConsent.fromDomain(it) }))) {
            // The in-memory decision stays visible for this session; it is simply not durable.
            SafeLogger.warning(TAG, "consent_ledger_not_persisted")
        }
    }

    /**
     * Pulls the server ledger once per owner per process. Best-effort: a network/401 failure keeps
     * whatever the durable ledger already has, so offline launches still show the last decision.
     */
    private suspend fun hydrate(ownerId: String) {
        if (!endpoints.isEnabled) return
        hydrateMutex.withLock {
            if (!serverChecked.add(ownerId)) return
            val url = endpoints.endpoint("/v1/consents") ?: return
            val token = (sessions.token(ownerId) as? Outcome.Success)?.value ?: return
            val response = withContext(ioDispatcher) {
                runCatching {
                    client.newCall(
                        Request.Builder()
                            .url(url)
                            .get()
                            .header("authorization", "Bearer $token")
                            .header("accept", "application/json")
                            .build(),
                    ).execute().use { call -> call.code to call.body?.string().orEmpty() }
                }.getOrNull()
            } ?: return
            if (response.first == 401) {
                sessions.clear()
                return
            }
            if (response.first != 200) return
            val wire = runCatching { json.decodeFromString<ConsentListBody>(response.second) }.getOrNull()
                ?: return
            val serverRows = wire.consents.mapNotNull { it.toDomain(ownerId) }
            if (serverRows.isEmpty()) return
            ensureLoaded(ownerId)
            val local = ledger.value[ownerId].orEmpty()
            // Both sides are append-only; the ledger keeps history and `lastOrNull` per purpose
            // is the current decision, so a newer local withdrawal is not overridden by a stale
            // GRANTED row that the server has not acknowledged yet.
            val merged = (local + serverRows)
                .associateBy { it.id }
                .values
                .sortedBy { it.decidedAt }
            ledger.value = ledger.value + (ownerId to merged)
            persist(ownerId, merged)
        }
    }

    private fun latestLoaded(ownerId: String, purpose: ConsentPurpose): Consent? =
        ledger.value[ownerId].orEmpty().lastOrNull { it.purpose == purpose }

    override fun observeLedger(ownerId: String): Flow<List<Consent>> = flow {
        ensureLoaded(ownerId)
        hydrate(ownerId)
        emitAll(ledger.map { it[ownerId].orEmpty() })
    }

    override fun observeLatest(ownerId: String, purpose: ConsentPurpose): Flow<Consent?> = flow {
        ensureLoaded(ownerId)
        hydrate(ownerId)
        emitAll(ledger.map { all -> all[ownerId].orEmpty().lastOrNull { it.purpose == purpose } })
    }

    override suspend fun latest(ownerId: String, purpose: ConsentPurpose): Consent? {
        ensureLoaded(ownerId)
        hydrate(ownerId)
        return latestLoaded(ownerId, purpose)
    }

    override suspend fun append(consent: Consent) {
        ensureLoaded(consent.ownerId)
        val entries = ledger.value[consent.ownerId].orEmpty() + consent
        ledger.value = ledger.value + (consent.ownerId to entries)
        persist(consent.ownerId, entries)
    }

    override suspend fun hasGranted(ownerId: String, purpose: ConsentPurpose): Boolean =
        latest(ownerId, purpose)?.isGranted == true

    override suspend fun deleteAll(ownerId: String) {
        ledger.value = ledger.value - ownerId
        durable.clear(ownerId)
    }

    @Serializable
    private data class StoredConsent(
        val id: String,
        val purpose: String,
        val decision: String,
        val noticeVersion: String,
        val decidedAtMillis: Long,
    ) {
        fun toDomain(): Consent = Consent(
            id = id,
            ownerId = "",
            purpose = runCatching { ConsentPurpose.valueOf(purpose) }.getOrDefault(ConsentPurpose.GMAIL_READ),
            noticeVersion = noticeVersion,
            decision = runCatching { ConsentDecision.valueOf(decision) }
                .getOrDefault(ConsentDecision.DECLINED),
            decidedAt = Instant.fromEpochMilliseconds(decidedAtMillis),
        )

        companion object {
            fun fromDomain(consent: Consent): StoredConsent = StoredConsent(
                id = consent.id,
                purpose = consent.purpose.name,
                decision = consent.decision.name,
                noticeVersion = consent.noticeVersion,
                decidedAtMillis = consent.decidedAt.toEpochMilliseconds(),
            )
        }
    }

    /** `GET /v1/consents` wire shape (backend contract §4, FR-49). */
    @Serializable
    private data class ConsentListBody(val consents: List<ConsentWire> = emptyList())

    @Serializable
    private data class ConsentWire(
        val id: String,
        val purpose: String,
        val decision: String,
        val noticeVersion: String,
        val decidedAt: String,
    ) {
        fun toDomain(ownerId: String): Consent? {
            val parsedPurpose = runCatching { ConsentPurpose.valueOf(purpose) }.getOrNull() ?: return null
            val parsedDecision = runCatching { ConsentDecision.valueOf(decision) }.getOrNull() ?: return null
            val parsedAt = runCatching { Instant.parse(decidedAt) }.getOrNull() ?: return null
            return Consent(
                id = id,
                ownerId = ownerId,
                purpose = parsedPurpose,
                noticeVersion = noticeVersion,
                decision = parsedDecision,
                decidedAt = parsedAt,
            )
        }
    }

    private companion object {
        const val TAG = "AssessmentConsentRepository"
        const val LEDGER_KEY = "consent_ledger"
    }
}

/**
 * Mailbox connection facts for the assessment variant: durable, never tokens (FR-49/NFR-14).
 *
 * The standard variant keeps these in Room. The assessment build persists only the non-secret
 * facts (state, label, scope, timestamps) in the Keystore-wrapped [AssessmentDurableState], so the
 * settings/import screens still show CONNECTED and the last-import time after a restart instead of
 * silently falling back to NOT_CONNECTED. There is no server endpoint for mailbox connection state
 * in the current contract, so hydration is from this durable store.
 */
@Singleton
class AssessmentGmailConnectionRepository @Inject constructor(
    private val durable: AssessmentDurableState,
    private val json: Json,
) : GmailConnectionRepository {

    private val connections = MutableStateFlow<Map<String, GmailConnection>>(emptyMap())

    @Synchronized
    private fun ensureLoaded(ownerId: String) {
        if (connections.value.containsKey(ownerId)) return
        val stored = durable.read(ownerId, CONNECTION_KEY) ?: return
        val decoded = runCatching { json.decodeFromString<StoredConnection>(stored) }.getOrNull() ?: return
        connections.value = connections.value + (ownerId to decoded.toDomain(ownerId))
    }

    @Synchronized
    private fun persist(connection: GmailConnection) {
        if (!durable.write(connection.ownerId, CONNECTION_KEY, json.encodeToString(StoredConnection.fromDomain(connection)))) {
            SafeLogger.warning(TAG, "gmail_connection_not_persisted")
        }
    }

    override fun observe(ownerId: String): Flow<GmailConnection?> = flow {
        ensureLoaded(ownerId)
        emitAll(connections.map { it[ownerId] })
    }

    override suspend fun get(ownerId: String): GmailConnection? {
        ensureLoaded(ownerId)
        return connections.value[ownerId]
    }

    override suspend fun save(connection: GmailConnection) {
        connections.value = connections.value + (connection.ownerId to connection)
        persist(connection)
    }

    override suspend fun touchLastImport(ownerId: String, at: Instant) {
        ensureLoaded(ownerId)
        val current = connections.value[ownerId] ?: return
        val updated = current.copy(lastImportAt = at)
        connections.value = connections.value + (ownerId to updated)
        persist(updated)
    }

    override suspend fun touchLastAi(ownerId: String, at: Instant) {
        ensureLoaded(ownerId)
        val current = connections.value[ownerId] ?: return
        val updated = current.copy(lastAiAt = at)
        connections.value = connections.value + (ownerId to updated)
        persist(updated)
    }

    override suspend fun deleteAll(ownerId: String) {
        connections.value = connections.value - ownerId
        durable.clear(ownerId)
    }

    /** Persisted shape: connection facts only, no credential or token (NFR-14). */
    @Serializable
    private data class StoredConnection(
        val state: String,
        val mailboxLabel: String? = null,
        val scope: String,
        val connectedAtMillis: Long? = null,
        val lastImportAtMillis: Long? = null,
        val lastAiAtMillis: Long? = null,
        val updatedAtMillis: Long,
    ) {
        fun toDomain(ownerId: String): GmailConnection = GmailConnection(
            ownerId = ownerId,
            state = runCatching { GmailConnectionState.valueOf(state) }
                .getOrDefault(GmailConnectionState.NOT_CONNECTED),
            mailboxLabel = mailboxLabel,
            scope = scope,
            connectedAt = connectedAtMillis?.let { Instant.fromEpochMilliseconds(it) },
            lastImportAt = lastImportAtMillis?.let { Instant.fromEpochMilliseconds(it) },
            lastAiAt = lastAiAtMillis?.let { Instant.fromEpochMilliseconds(it) },
            updatedAt = Instant.fromEpochMilliseconds(updatedAtMillis),
        )

        companion object {
            fun fromDomain(connection: GmailConnection): StoredConnection = StoredConnection(
                state = connection.state.name,
                mailboxLabel = connection.mailboxLabel,
                scope = connection.scope,
                connectedAtMillis = connection.connectedAt?.toEpochMilliseconds(),
                lastImportAtMillis = connection.lastImportAt?.toEpochMilliseconds(),
                lastAiAtMillis = connection.lastAiAt?.toEpochMilliseconds(),
                updatedAtMillis = connection.updatedAt.toEpochMilliseconds(),
            )
        }
    }

    private companion object {
        const val TAG = "AssessmentGmailConnectionRepository"
        const val CONNECTION_KEY = "gmail_connection"
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
