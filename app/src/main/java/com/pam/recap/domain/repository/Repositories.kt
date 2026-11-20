package com.pam.recap.domain.repository

import com.pam.recap.core.money.Money
import com.pam.recap.core.time.JakartaMonth
import com.pam.recap.domain.model.Analysis
import com.pam.recap.domain.model.BudgetPlan
import com.pam.recap.domain.model.Category
import com.pam.recap.domain.model.CategoryOrigin
import com.pam.recap.domain.model.Consent
import com.pam.recap.domain.model.ConsentPurpose
import com.pam.recap.domain.model.DeletionRequest
import com.pam.recap.domain.model.GmailConnection
import com.pam.recap.domain.model.ImportJob
import com.pam.recap.domain.model.InsertResult
import com.pam.recap.domain.model.MonthTotals
import com.pam.recap.domain.model.MonthlySummary
import com.pam.recap.domain.model.Session
import com.pam.recap.domain.model.SkippedCandidate
import com.pam.recap.domain.model.Transaction
import com.pam.recap.domain.model.TransactionFilter
import com.pam.recap.domain.model.TransactionPage
import kotlinx.coroutines.flow.Flow
import kotlin.time.Instant

/**
 * Owner-scoped transaction store (FR-02). Every read and write takes [ownerId] so one account's
 * rows can never be observed through another account's session (FR-05, FR-09).
 */
interface TransactionRepository {

    fun observeMonth(
        ownerId: String,
        month: JakartaMonth,
        filter: TransactionFilter? = null,
    ): Flow<List<Transaction>>

    suspend fun listMonth(ownerId: String, month: JakartaMonth): List<Transaction>

    /** Paged, filtered list used by the transactions screen and search (FR-27). */
    suspend fun page(ownerId: String, filter: TransactionFilter, offset: Int, limit: Int): TransactionPage

    suspend fun get(ownerId: String, id: String): Transaction?

    /** Dedupe lookup for FR-18; the fingerprint is opaque, nothing else is exposed. */
    suspend fun findByFingerprint(ownerId: String, fingerprint: String): Transaction?

    /**
     * Inserts a batch, ignoring rows whose `(ownerId, fingerprint)` already exists.
     * Callers run this inside one transaction so a batch is atomic (FR-21).
     */
    suspend fun insertIgnoringDuplicates(ownerId: String, transactions: List<Transaction>): InsertResult

    suspend fun updateCategory(
        ownerId: String,
        id: String,
        category: Category,
        origin: CategoryOrigin,
        confidence: Float?,
        at: Instant,
    ): Boolean

    suspend fun setInternalTransfer(ownerId: String, id: String, isInternalTransfer: Boolean, at: Instant): Boolean

    suspend fun linkRefund(ownerId: String, refundId: String, expenseId: String?, at: Instant): Boolean

    suspend fun updateNote(ownerId: String, id: String, note: String?, at: Instant): Boolean

    /** Monotonic revision of the month's inputs, stored with every summary (FR-33). */
    suspend fun monthRevision(ownerId: String, month: JakartaMonth): Int

    suspend fun bumpMonthRevision(ownerId: String, month: JakartaMonth): Int

    /** SQL aggregate, used for cheap coverage/freshness reads (NFR-07). */
    fun observeMonthTotals(ownerId: String, month: JakartaMonth): Flow<MonthTotals>

    suspend fun monthTotals(ownerId: String, month: JakartaMonth): MonthTotals

    suspend fun totalAmountForCategory(ownerId: String, month: JakartaMonth, category: Category): Money

    /** Local-only convenience: the newest event time, used to open the most useful month. */
    suspend fun latestEventAt(ownerId: String): Instant?

    /** Used by sign-out (FR-05) and deletion (FR-47). */
    suspend fun deleteAll(ownerId: String)

    /** F2: demo rows are identified by their id prefix, never by content. */
    suspend fun countByIdPrefix(ownerId: String, prefix: String): Int

    suspend fun deleteByIdPrefix(ownerId: String, prefix: String): Int
}

/** FR-30/FR-33 summary persistence. */
interface SummaryRepository {

    fun observeSummary(ownerId: String, month: JakartaMonth): Flow<MonthlySummary?>

    suspend fun get(ownerId: String, month: JakartaMonth, inputRevision: Int? = null): MonthlySummary?

    suspend fun save(summary: MonthlySummary)

    /** Called after any row mutation so stale reports stop looking current (FR-22). */
    suspend fun markOutdated(ownerId: String, month: JakartaMonth)

    suspend fun deleteAll(ownerId: String)

    /** F2: drops the summaries for one month, including its category split. */
    suspend fun delete(ownerId: String, month: JakartaMonth): Int
}

/** FR-19/FR-21 import job and redacted skipped-candidate persistence. */
interface ImportRepository {

    suspend fun create(job: ImportJob)

    suspend fun update(job: ImportJob)

    fun observe(jobId: String): Flow<ImportJob?>

    suspend fun get(jobId: String): ImportJob?

    suspend fun latestForMonth(ownerId: String, month: JakartaMonth): ImportJob?

    fun observeLatestForMonth(ownerId: String, month: JakartaMonth): Flow<ImportJob?>

    suspend fun saveSkipped(candidates: List<SkippedCandidate>)

    suspend fun skippedFor(jobId: String): List<SkippedCandidate>

    suspend fun deleteAll(ownerId: String)

    /** F2: removes one seeded job for [ownerId]; false when it did not exist. */
    suspend fun delete(ownerId: String, jobId: String): Boolean
}

/** FR-39/FR-40 analysis persistence, including the cache key and historical marking. */
interface AnalysisRepository {

    fun observeLatest(ownerId: String, monthId: String): Flow<Analysis?>

    suspend fun latest(ownerId: String, monthId: String): Analysis?

    suspend fun save(analysis: Analysis)

    /** FR-22/FR-40: the inputs changed, so the stored result is no longer current. */
    suspend fun markOutdated(ownerId: String, monthId: String)

    /** FR-04: consent withdrawn ⇒ older reports become historical, never deleted silently. */
    suspend fun markAllHistorical(ownerId: String)

    suspend fun deleteAll(ownerId: String)
}

/** FR-42/FR-44 budget plan storage. */
interface BudgetRepository {

    fun observeActive(ownerId: String, month: JakartaMonth): Flow<BudgetPlan?>

    suspend fun active(ownerId: String, month: JakartaMonth): BudgetPlan?

    suspend fun save(plan: BudgetPlan)

    suspend fun deleteAll(ownerId: String)
}

/** FR-03/FR-49 append-only consent ledger. */
interface ConsentRepository {

    fun observeLedger(ownerId: String): Flow<List<Consent>>

    fun observeLatest(ownerId: String, purpose: ConsentPurpose): Flow<Consent?>

    suspend fun latest(ownerId: String, purpose: ConsentPurpose): Consent?

    suspend fun append(consent: Consent)

    suspend fun hasGranted(ownerId: String, purpose: ConsentPurpose): Boolean

    suspend fun deleteAll(ownerId: String)
}

/** FR-07/FR-49 mailbox connection facts (no secret material). */
interface GmailConnectionRepository {

    fun observe(ownerId: String): Flow<GmailConnection?>

    suspend fun get(ownerId: String): GmailConnection?

    suspend fun save(connection: GmailConnection)

    suspend fun touchLastImport(ownerId: String, at: Instant)

    suspend fun touchLastAi(ownerId: String, at: Instant)

    suspend fun deleteAll(ownerId: String)
}

/** FR-01/FR-05 session state, backed by DataStore. */
interface SessionRepository {

    fun observe(): Flow<Session?>

    suspend fun current(): Session?

    suspend fun signIn(session: Session)

    suspend fun updateMailbox(ownerId: String, mailboxLabel: String?)

    suspend fun signOut()

    /** Demo data is seeded once per signed-in demo session (A9). */
    suspend fun isDemoSeeded(ownerId: String): Boolean

    /** F2: after a purge the owner may seed again if it ever runs in offline mode. */
    suspend fun clearDemoSeeded(ownerId: String)

    suspend fun markDemoSeeded(ownerId: String)
}

/** FR-47/FR-48 deletion lifecycle. */
interface DeletionRepository {

    fun observeLatest(ownerId: String): Flow<DeletionRequest?>

    suspend fun latest(ownerId: String): DeletionRequest?

    suspend fun active(ownerId: String): DeletionRequest?

    suspend fun save(request: DeletionRequest)

    suspend fun deleteAll(ownerId: String)
}
