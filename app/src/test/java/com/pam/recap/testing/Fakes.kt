package com.pam.recap.testing
import com.pam.recap.domain.model.DemoPurge
import com.pam.recap.domain.repository.DemoDataMaintenance
import com.pam.recap.domain.repository.DemoDataPort
import com.pam.recap.domain.model.GmailCredential
import com.pam.recap.domain.repository.AppPasswordVault
import com.pam.recap.domain.repository.GmailCredentialValidator
import com.pam.recap.domain.repository.SessionRepository
import com.pam.recap.core.result.AppErrorRules
import com.pam.recap.domain.model.Session
import com.pam.recap.core.result.AppError
import com.pam.recap.core.result.Outcome
import com.pam.recap.domain.model.ConsentDecision
import com.pam.recap.domain.repository.AccountRepository
import com.pam.recap.domain.repository.AnalysisRequest
import com.pam.recap.domain.repository.BackendConsentSync
import com.pam.recap.domain.repository.BackendDeletionSync
import com.pam.recap.domain.repository.BackendSessionRepository
import com.pam.recap.domain.repository.BackendSummarySync

import com.pam.recap.core.money.Money
import com.pam.recap.core.time.ClockProvider
import com.pam.recap.core.time.JakartaMonth
import com.pam.recap.core.util.IdGenerator
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
import kotlin.time.Instant

/**
 * In-memory fakes for the JVM tests. They implement the ports faithfully enough for the use-case
 * tests (no Room, no Hilt, deterministic time).
 */

fun fixedClock(instant: Instant): ClockProvider = object : ClockProvider {
    override fun now(): Instant = instant
}

class SequentialIdGenerator(private val prefix: String = "id") : IdGenerator {
    private var counter = 0
    override fun newId(): String = "$prefix-${counter++}"
}

class FakeTransactionRepository(initial: List<Transaction> = emptyList()) : TransactionRepository {

    private val rows = MutableStateFlow(initial)
    private val revisions = mutableMapOf<String, Int>()

    fun all(): List<Transaction> = rows.value

    fun replaceAll(values: List<Transaction>) {
        rows.value = values
    }

    override fun observeMonth(ownerId: String, month: JakartaMonth, filter: TransactionFilter?): Flow<List<Transaction>> =
        rows.map { list -> list.filter { it.ownerId == ownerId && month.contains(it.eventAt) } }

    override suspend fun listMonth(ownerId: String, month: JakartaMonth): List<Transaction> =
        rows.value.filter { it.ownerId == ownerId && month.contains(it.eventAt) }

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
    ): Boolean = update(id) { it.copy(category = category, categoryOrigin = origin, categoryConfidence = confidence) }

    override suspend fun setInternalTransfer(ownerId: String, id: String, isInternalTransfer: Boolean, at: Instant): Boolean =
        update(id) { it.copy(isInternalTransfer = isInternalTransfer) }

    override suspend fun linkRefund(ownerId: String, refundId: String, expenseId: String?, at: Instant): Boolean =
        update(refundId) { it.copy(linkedRefundOf = expenseId) }

    override suspend fun updateNote(ownerId: String, id: String, note: String?, at: Instant): Boolean =
        update(id) { it.copy(note = note) }

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

    override suspend fun monthTotals(ownerId: String, month: JakartaMonth): MonthTotals {
        val scoped = listMonth(ownerId, month).filter { it.isIdr }
        val inflow = scoped.filter { it.isCredit }.fold(Money.ZERO) { acc, row -> acc + row.amount }
        val outflow = scoped.filter { it.isDebit }.fold(Money.ZERO) { acc, row -> acc + row.amount }
        return MonthTotals(
            grossInflow = inflow,
            grossOutflow = outflow,
            creditCount = scoped.count { it.isCredit },
            debitCount = scoped.count { it.isDebit },
            transactionCount = scoped.size,
            nonIdrCount = listMonth(ownerId, month).count { !it.isIdr },
            uncategorizedCount = scoped.count { it.category.isUncategorized },
            manualCount = scoped.count { it.isManual },
            latestEventAt = scoped.maxOfOrNull { it.eventAt },
            latestUpdatedAt = scoped.maxOfOrNull { it.updatedAt },
        )
    }

    override suspend fun totalAmountForCategory(ownerId: String, month: JakartaMonth, category: Category): Money =
        listMonth(ownerId, month)
            .filter { it.category == category && it.isDebit && it.isIdr }
            .fold(Money.ZERO) { acc, row -> acc + row.amount }

    override suspend fun latestEventAt(ownerId: String): Instant? =
        rows.value.filter { it.ownerId == ownerId }.maxOfOrNull { it.eventAt }

    override suspend fun deleteAll(ownerId: String) {
        rows.value = rows.value.filterNot { it.ownerId == ownerId }
    }

    override suspend fun countByIdPrefix(ownerId: String, prefix: String): Int =
        rows.value.count { it.ownerId == ownerId && it.id.startsWith(prefix) }

    override suspend fun deleteByIdPrefix(ownerId: String, prefix: String): Int {
        val current = rows.value
        val kept = current.filterNot { it.ownerId == ownerId && it.id.startsWith(prefix) }
        rows.value = kept
        return current.size - kept.size
    }

    private fun update(id: String, transform: (Transaction) -> Transaction): Boolean {
        val current = rows.value
        val index = current.indexOfFirst { it.id == id }
        if (index < 0) return false
        rows.value = current.toMutableList().also { it[index] = transform(it[index]) }
        return true
    }
}

class FakeSummaryRepository : SummaryRepository {
    private val stored = mutableMapOf<String, MonthlySummary>()

    override fun observeSummary(ownerId: String, month: JakartaMonth): Flow<MonthlySummary?> =
        flowOf(stored["$ownerId:${month.id()}"])

    override suspend fun get(ownerId: String, month: JakartaMonth, inputRevision: Int?): MonthlySummary? =
        stored["$ownerId:${month.id()}"]

    override suspend fun save(summary: MonthlySummary) {
        stored["${summary.ownerId}:${summary.month.id()}"] = summary
    }

    override suspend fun markOutdated(ownerId: String, month: JakartaMonth) = Unit

    override suspend fun deleteAll(ownerId: String) {
        stored.keys.removeIf { it.startsWith("$ownerId:") }
    }

    override suspend fun delete(ownerId: String, month: JakartaMonth): Int =
        if (stored.remove("$ownerId:${month.id()}") != null) 1 else 0
}

class FakeImportRepository(
    var latest: ImportJob? = null,
    private val skipped: MutableList<SkippedCandidate> = mutableListOf(),
) : ImportRepository {

    private val jobs = mutableMapOf<String, ImportJob>()
    private val latestFlow = MutableStateFlow(latest)

    override suspend fun create(job: ImportJob) {
        jobs[job.id] = job
        latestFlow.value = job
    }

    override suspend fun update(job: ImportJob) {
        jobs[job.id] = job
        latestFlow.value = job
    }

    override fun observe(jobId: String): Flow<ImportJob?> = flowOf(jobs[jobId])

    override suspend fun get(jobId: String): ImportJob? = jobs[jobId]

    override suspend fun latestForMonth(ownerId: String, month: JakartaMonth): ImportJob? =
        latest ?: jobs.values.filter { it.ownerId == ownerId && it.month == month }.maxByOrNull { it.startedAt }

    override fun observeLatestForMonth(ownerId: String, month: JakartaMonth): Flow<ImportJob?> = latestFlow

    override suspend fun saveSkipped(candidates: List<SkippedCandidate>) {
        skipped += candidates
    }

    override suspend fun skippedFor(jobId: String): List<SkippedCandidate> = skipped.filter { it.jobId == jobId }

    override suspend fun deleteAll(ownerId: String) = Unit

    override suspend fun delete(ownerId: String, jobId: String): Boolean {
        val job = jobs[jobId] ?: return false
        if (job.ownerId != ownerId) return false
        jobs.remove(jobId)
        return true
    }
}

fun completeJob(ownerId: String, month: JakartaMonth, at: Instant): ImportJob = ImportJob(
    id = "job-$ownerId-${month.id()}",
    ownerId = ownerId,
    month = month,
    state = ImportState.COMPLETE,
    pagesProcessed = 1,
    pagesExpected = 1,
    matchedCount = 10,
    importedCount = 10,
    duplicateCount = 0,
    skippedCount = 0,
    unresolvedCount = 0,
    templateVersion = "v1",
    startedAt = at,
    finishedAt = at,
)

class FakeGmailConnectionRepository : GmailConnectionRepository {
    private val stored = MutableStateFlow<GmailConnection?>(null)

    override fun observe(ownerId: String): Flow<GmailConnection?> = stored

    override suspend fun get(ownerId: String): GmailConnection? = stored.value

    override suspend fun save(connection: GmailConnection) {
        stored.value = connection
    }

    override suspend fun touchLastImport(ownerId: String, at: Instant) = Unit

    override suspend fun touchLastAi(ownerId: String, at: Instant) = Unit

    override suspend fun deleteAll(ownerId: String) = Unit
}

class FakeConsentRepository(initial: List<Consent> = emptyList()) : ConsentRepository {
    val ledger = MutableStateFlow(initial)

    fun withdraw(purpose: ConsentPurpose) {
        ledger.value = ledger.value + Consent(
            id = "consent-withdraw-${ledger.value.size}",
            ownerId = ledger.value.firstOrNull()?.ownerId ?: "u_demo",
            purpose = purpose,
            noticeVersion = "v1",
            decision = com.pam.recap.domain.model.ConsentDecision.WITHDRAWN,
            decidedAt = Instant.fromEpochMilliseconds(0),
        )
    }

    override fun observeLedger(ownerId: String): Flow<List<Consent>> = ledger

    override fun observeLatest(ownerId: String, purpose: ConsentPurpose): Flow<Consent?> =
        ledger.map { list -> list.lastOrNull { it.purpose == purpose } }

    override suspend fun latest(ownerId: String, purpose: ConsentPurpose): Consent? =
        ledger.value.lastOrNull { it.purpose == purpose }

    override suspend fun append(consent: Consent) {
        ledger.value = ledger.value + consent
    }

    override suspend fun hasGranted(ownerId: String, purpose: ConsentPurpose): Boolean =
        latest(ownerId, purpose)?.isGranted == true

    override suspend fun deleteAll(ownerId: String) = Unit
}

class FakeAnalysisRepository : AnalysisRepository {
    private val stored = MutableStateFlow<List<Analysis>>(emptyList())
    var outdated = false

    /** Every analysis written so far, in order. */
    val saved: List<Analysis> get() = stored.value

    override fun observeLatest(ownerId: String, monthId: String): Flow<Analysis?> =
        stored.map { list -> list.lastOrNull { it.ownerId == ownerId && it.monthId == monthId } }

    override suspend fun latest(ownerId: String, monthId: String): Analysis? =
        stored.value.lastOrNull { it.ownerId == ownerId && it.monthId == monthId }

    override suspend fun save(analysis: Analysis) {
        stored.value = stored.value + analysis
    }

    override suspend fun markOutdated(ownerId: String, monthId: String) {
        outdated = true
    }

    override suspend fun markAllHistorical(ownerId: String) = Unit

    override suspend fun deleteAll(ownerId: String) {
        stored.value = stored.value.filterNot { it.ownerId == ownerId }
    }
}

class FakeBudgetRepository : BudgetRepository {
    var stored: BudgetPlan? = null

    override fun observeActive(ownerId: String, month: JakartaMonth): Flow<BudgetPlan?> = flowOf(stored)

    override suspend fun active(ownerId: String, month: JakartaMonth): BudgetPlan? = stored

    override suspend fun save(plan: BudgetPlan) {
        stored = plan
    }

    override suspend fun deleteAll(ownerId: String) = Unit
}

class FakeDeletionRepository : DeletionRepository {
    var activeRequest: DeletionRequest? = null

    override fun observeLatest(ownerId: String): Flow<DeletionRequest?> = flowOf(activeRequest)

    override suspend fun latest(ownerId: String): DeletionRequest? = activeRequest

    override suspend fun active(ownerId: String): DeletionRequest? = activeRequest

    override suspend fun save(request: DeletionRequest) {
        activeRequest = request
    }

    override suspend fun deleteAll(ownerId: String) = Unit
}

/**
 * Backend ports with no backend configured: the shape every offline test needs so the new
 * constructor parameters never force a real HTTP client into a JVM test.
 */
class NoopBackendSessionRepository : BackendSessionRepository {
    override val isEnabled: Boolean = false

    override suspend fun register(
        email: String,
        password: String,
        displayName: String?,
        inviteCode: String,
    ): Outcome<Unit> = Outcome.Failure(AppError.Configuration("backend_not_configured"))

    override suspend fun login(email: String, password: String): Outcome<Unit> =
        Outcome.Failure(AppError.Configuration("backend_not_configured"))

    override suspend fun token(ownerId: String): Outcome<String> =
        Outcome.Failure(AppError.Configuration("backend_not_configured"))

    override suspend fun revoke() = Unit

    override fun clear() = Unit
}

/**
 * Account source double: records the call and delegates to a fixed outcome, so the account screen
 * and the gate can be exercised without HTTP.
 */
class FakeAccountRepository(
    override val isBackendEnabled: Boolean = true,
    var result: Outcome<Session>? = null,
    private val session: Session? = null,
) : AccountRepository {
    val calls = mutableListOf<String>()

    override suspend fun register(
        email: String,
        password: String,
        displayName: String?,
        inviteCode: String,
    ): Outcome<Session> {
        calls += "register:$email"
        return result ?: Outcome.Failure(AppError.Validation(AppErrorRules.INVALID_INVITE))
    }

    override suspend fun login(email: String, password: String): Outcome<Session> {
        calls += "login:$email"
        return result ?: Outcome.Failure(AppError.Validation(AppErrorRules.INVALID_CREDENTIALS))
    }

    override suspend fun loginOffline(email: String, displayName: String?): Outcome<Session> {
        calls += "offline:$email"
        return result ?: Outcome.Failure(AppError.Configuration("offline"))
    }

    override suspend fun signOut() {
        calls += "signOut"
    }
}

class NoopBackendConsentSync : BackendConsentSync {
    override suspend fun record(
        ownerId: String,
        purpose: ConsentPurpose,
        decision: ConsentDecision,
        noticeVersion: String,
    ) = Unit
}

class NoopBackendSummarySync : BackendSummarySync {
    override suspend fun push(request: AnalysisRequest): Outcome<Unit> = Outcome.Success(Unit)
}

class NoopBackendDeletionSync : BackendDeletionSync {
    override suspend fun request(ownerId: String): Outcome<Int> =
        Outcome.Failure(AppError.Configuration("backend_not_configured"))
}

/** In-memory session/profile store for the account and gate tests. */
class FakeSessionRepository(initial: Session? = null) : SessionRepository {
    private val flow = MutableStateFlow(initial)
    val seededOwners = mutableSetOf<String>()

    override fun observe(): Flow<Session?> = flow
    override suspend fun current(): Session? = flow.value
    override suspend fun signIn(session: Session) {
        flow.value = session
    }

    override suspend fun updateMailbox(ownerId: String, mailboxLabel: String?) = Unit
    override suspend fun signOut() {
        flow.value = null
    }

    override suspend fun isDemoSeeded(ownerId: String): Boolean = ownerId in seededOwners
    override suspend fun markDemoSeeded(ownerId: String) {
        seededOwners += ownerId
    }

    override suspend fun clearDemoSeeded(ownerId: String) {
        seededOwners -= ownerId
    }
}

/** In-memory credential vault (no Keystore) for JVM tests. */
class FakeAppPasswordVault(
    initial: GmailCredential? = null,
) : AppPasswordVault {
    val saved = mutableListOf<GmailCredential>()
    var cleared = 0

    private var current: GmailCredential? = initial

    override suspend fun save(email: String, appPassword: String) {
        val credential = GmailCredential(email = email, appPassword = appPassword)
        saved += credential
        current = credential
    }

    override suspend fun read(): GmailCredential? = current

    override suspend fun clear() {
        cleared += 1
        current = null
    }
}

/** Credential validator double: fixed outcome, records the attempt. */
class FakeCredentialValidator(
    var result: Outcome<Unit> = Outcome.Success(Unit),
) : GmailCredentialValidator {
    val attempts = mutableListOf<String>()

    override suspend fun validate(email: String, appPassword: String): Outcome<Unit> {
        attempts += email
        return result
    }
}

/**
 * Demo maintenance double: records purge calls and, by default, behaves like the real purger over a
 * [FakeTransactionRepository] (prefix delete) so a test can assert real row removal.
 */
class FakeDemoDataMaintenance(
    private val transactions: FakeTransactionRepository? = null,
    private val summaries: FakeSummaryRepository? = null,
    private val jobs: FakeImportRepository? = null,
    private val prefix: String = "demo-",
) : DemoDataMaintenance {
    val purgedOwners = mutableListOf<String>()
    val seededCalledFor = mutableListOf<String>()

    override suspend fun countDemoRows(ownerId: String): Int =
        transactions?.countByIdPrefix(ownerId, prefix) ?: 0

    override suspend fun purge(ownerId: String): DemoPurge {
        purgedOwners += ownerId
        val removedTransactions = transactions?.deleteByIdPrefix(ownerId, prefix) ?: 0
        val removedJobs = listOf("demo-job-2026-02", "demo-job-2026-01")
            .count { jobs?.delete(ownerId, it) == true }
        val removedSummaries = listOf(
            com.pam.recap.core.time.JakartaMonth(2026, 2),
            com.pam.recap.core.time.JakartaMonth(2026, 1),
        ).sumOf { summaries?.delete(ownerId, it) ?: 0 }
        return DemoPurge(removedTransactions, removedJobs, removedSummaries)
    }
}

/** Seed port double: records which owners were seeded, never writes rows. */
class FakeDemoDataPort : DemoDataPort {
    val seededOwners = mutableListOf<String>()

    override suspend fun seedIfNeeded(ownerId: String): Boolean {
        seededOwners += ownerId
        return true
    }
}
