package com.pam.recap.domain.usecase

import com.pam.recap.core.result.AppError
import com.pam.recap.core.result.Outcome
import com.pam.recap.core.time.ClockProvider
import com.pam.recap.core.time.JakartaMonth
import com.pam.recap.domain.model.Category
import com.pam.recap.domain.model.CategoryOrigin
import com.pam.recap.domain.model.CategoryQueuePhase
import com.pam.recap.domain.model.CategoryQueueSnapshot
import com.pam.recap.domain.model.ConsentPurpose
import com.pam.recap.domain.model.Transaction
import com.pam.recap.domain.repository.ConsentRepository
import com.pam.recap.domain.repository.ServerFinanceRepository
import com.pam.recap.domain.repository.TransactionRepository
import kotlinx.coroutines.yield
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Instant

/** The category-job endpoint accepts at most five ids per call (backend `categoryllm.MaxCandidates`). */
private const val CLASSIFY_BATCH = 5

/** The enqueue endpoint accepts at most fifty ids per call. */
private const val ENQUEUE_BATCH = 50

/** How many months back the client looks for uncategorized candidates (the rolling year). */
private const val SCAN_MONTHS = 12

/**
 * Outcome of one owner-triggered sweep. [processed] can be lower than [total] when the server budget
 * or the connection stops the run; the remaining rows stay eligible for the next sweep.
 */
data class CategorySweepResult(
    val total: Int,
    val processed: Int,
    val classified: Int,
    val needsReview: Int,
    val failed: Int,
    val reviewReasons: List<String> = emptyList(),
    val stoppedError: AppError? = null,
)

/**
 * Process-wide, owner-scoped status for the automatic category queue. The status panel reads it; the
 * worker writes it. It is deliberately in-memory: the durable truth is the server queue, and a
 * restart simply re-reads the pending count on the next drain instead of trusting a stale cache.
 */
@Singleton
class CategoryStatusHolder @Inject constructor() {
    private val state = MutableStateFlow(CategoryQueueSnapshot())
    val snapshot: StateFlow<CategoryQueueSnapshot> = state.asStateFlow()

    fun update(transform: (CategoryQueueSnapshot) -> CategoryQueueSnapshot) {
        state.value = transform(state.value)
    }

    fun reset() {
        state.value = CategoryQueueSnapshot()
    }
}

/**
 * Drives the automatic category queue: enqueue what became eligible after the financial commit, then
 * ask the server to drain its 2-hour window. The client never decides the batch size, the interval or
 * the budget — it only reports candidates and surfaces the server's answer, so a poll can never turn
 * into an unbounded provider bill.
 */
@Singleton
class AutoCategoryUseCase @Inject constructor(
    private val serverFinance: ServerFinanceRepository,
    private val status: CategoryStatusHolder,
    private val clock: ClockProvider,
    /** Optional in legacy tests; production binds the ledger used to gate the classifier call. */
    private val consents: ConsentRepository? = null,
    /** Optional in legacy tests; production binds the local store used for per-import sweeps. */
    private val transactions: TransactionRepository? = null,
) {
    private val gate = Mutex()
    private val running = AtomicBoolean(false)

    suspend fun runIfConnected(ownerId: String): CategoryQueueSnapshot? {
        if (!serverFinance.isEnabled) return null
        if (!running.compareAndSet(false, true)) return status.snapshot.value
        return try {
            gate.withLock { execute(ownerId) }
        } finally {
            running.set(false)
        }
    }

    /**
     * Offers current candidates without asking for a drain. Used right after a sync commit so the
     * new rows join the backlog immediately; the server still decides when the batch runs.
     */
    suspend fun enqueueOnly(ownerId: String): Int? {
        if (!serverFinance.isEnabled) return null
        if (!running.compareAndSet(false, true)) return null
        return try {
            gate.withLock {
                val candidates = when (val scan = scanEligible(ownerId, clock.now())) {
                    is Outcome.Failure -> return@withLock null
                    is Outcome.Success -> scan.value
                }
                if (candidates.isEmpty()) return@withLock 0
                // The contract caps one enqueue request at 50 ids; send every remaining candidate
                // instead of silently leaving the tail of the month unqueued.
                var queued = 0
                for (chunk in candidates.chunked(ENQUEUE_BATCH)) {
                    when (val enqueued = serverFinance.enqueueCategories(ownerId, chunk)) {
                        is Outcome.Failure -> return@withLock null
                        is Outcome.Success -> {
                            queued += enqueued.value.queued
                            status.update { it.copy(pending = enqueued.value.pending) }
                        }
                    }
                }
                queued
            }
        } finally {
            running.set(false)
        }
    }

    /**
     * Owner-triggered sweep: classify **every** eligible row, five ids per request, using the manual
     * job endpoint that bypasses the two-hour drain window. The server still batches unique merchants
     * and fans cached labels out to duplicate rows, so the provider cost is per merchant, not per row.
     * Budget or transport failures stop the sweep honestly and leave the tail queued.
     */
    suspend fun classifyAll(
        ownerId: String,
        onProgress: (processed: Int, total: Int) -> Unit = { _, _ -> },
    ): Outcome<CategorySweepResult> {
        if (!serverFinance.isEnabled) return Outcome.Failure(AppError.Configuration("backend_disabled"))
        // Classification has its own consent purpose; AI analysis consent does not imply it.
        val consent = consents
        if (consent != null && !consent.hasGranted(ownerId, ConsentPurpose.AI_CATEGORIZATION)) {
            return Outcome.Failure(AppError.Validation("ai_category_consent_required"))
        }
        return gate.withLock {
            val ids = when (val scan = scanEligible(ownerId, clock.now())) {
                is Outcome.Failure -> return@withLock Outcome.Failure(scan.error)
                is Outcome.Success -> scan.value
            }
            var processed = 0
            var classified = 0
            var needsReview = 0
            var failed = 0
            val reviewReasons = mutableListOf<String>()
            onProgress(0, ids.size)
            for (batch in ids.chunked(CLASSIFY_BATCH)) {
                when (val result = serverFinance.classifyTransactions(ownerId, batch)) {
                    is Outcome.Failure -> {
                        return@withLock Outcome.Success(
                            CategorySweepResult(
                                total = ids.size,
                                processed = processed,
                                classified = classified,
                                needsReview = needsReview,
                                failed = failed,
                                reviewReasons = reviewReasons.distinct(),
                                stoppedError = result.error,
                            ),
                        )
                    }
                    is Outcome.Success -> {
                        processed += batch.size
                        for (job in result.value) {
                            when (job.state) {
                                "CLASSIFIED" -> classified++
                                "NEEDS_REVIEW" -> needsReview++
                                "FAILED" -> failed++
                            }
                            job.reasonCode?.let { reviewReasons += it }
                        }
                        onProgress(processed, ids.size)
                    }
                }
                yield()
            }
            Outcome.Success(
                CategorySweepResult(
                    total = ids.size,
                    processed = processed,
                    classified = classified,
                    needsReview = needsReview,
                    failed = failed,
                    reviewReasons = reviewReasons.distinct(),
                ),
            )
        }
    }

    /**
     * Categorize the rows a just-acknowledged import produced, without waiting for the two-hour
     * queue window. Ids are read from the local run snapshot, so only fresh rows are sent.
     */
    suspend fun classifyImportJob(ownerId: String, importJobId: String): Outcome<CategorySweepResult>? {
        val local = transactions ?: return null
        if (!serverFinance.isEnabled) return null
        val consent = consents
        if (consent != null && !consent.hasGranted(ownerId, ConsentPurpose.AI_CATEGORIZATION)) {
            return Outcome.Failure(AppError.Validation("ai_category_consent_required"))
        }
        val ids = runCatching { local.listByImportJob(ownerId, importJobId) }
            .getOrDefault(emptyList())
            .filter(::isEligible)
            .map { it.id }
            .distinct()
        if (ids.isEmpty()) return Outcome.Success(CategorySweepResult(0, 0, 0, 0, 0))
        return gate.withLock {
            var processed = 0
            var classified = 0
            var needsReview = 0
            var failed = 0
            val reviewReasons = mutableListOf<String>()
            for (batch in ids.chunked(CLASSIFY_BATCH)) {
                when (val result = serverFinance.classifyTransactions(ownerId, batch)) {
                    is Outcome.Failure -> return@withLock Outcome.Success(
                        CategorySweepResult(
                            total = ids.size,
                            processed = processed,
                            classified = classified,
                            needsReview = needsReview,
                            failed = failed,
                            reviewReasons = reviewReasons.distinct(),
                            stoppedError = result.error,
                        ),
                    )
                    is Outcome.Success -> {
                        processed += batch.size
                        for (job in result.value) {
                            when (job.state) {
                                "CLASSIFIED" -> classified++
                                "NEEDS_REVIEW" -> needsReview++
                                "FAILED" -> failed++
                            }
                            job.reasonCode?.let { reviewReasons += it }
                        }
                    }
                }
                yield()
            }
            Outcome.Success(
                CategorySweepResult(ids.size, processed, classified, needsReview, failed, reviewReasons.distinct()),
            )
        }
    }

    private suspend fun execute(ownerId: String): CategoryQueueSnapshot {
        val startedAt = clock.now()
        status.update { it.copy(phase = CategoryQueuePhase.RUNNING, errorCode = null) }
        val candidates = eligibleCandidates(ownerId, startedAt)
        if (candidates.isNotEmpty()) {
            when (val enqueued = serverFinance.enqueueCategories(ownerId, candidates)) {
                is Outcome.Failure -> return fail(enqueued.error)
                is Outcome.Success -> status.update { it.copy(pending = enqueued.value.pending) }
            }
        }
        return when (val drained = serverFinance.drainCategories(ownerId)) {
            is Outcome.Failure -> fail(drained.error)
            is Outcome.Success -> {
                val result = drained.value
                val phase = when (result.state) {
                    "budget_exhausted" -> CategoryQueuePhase.PAUSED
                    "not_due" -> CategoryQueuePhase.WAITING
                    "empty" -> CategoryQueuePhase.IDLE
                    else -> if (result.pending > 0) CategoryQueuePhase.WAITING else CategoryQueuePhase.IDLE
                }
                val snapshot = CategoryQueueSnapshot(
                    phase = phase,
                    pending = result.pending,
                    nextDueAt = result.nextDueAt,
                    lastCheckedAt = clock.now(),
                    lastDrained = result.drained,
                    errorCode = if (result.state == "budget_exhausted") "budget_exhausted" else null,
                )
                status.update { snapshot }
                snapshot
            }
        }
    }

    private fun fail(error: AppError): CategoryQueueSnapshot {
        val snapshot = status.snapshot.value.copy(
            phase = CategoryQueuePhase.NEEDS_ATTENTION,
            lastCheckedAt = clock.now(),
            errorCode = error.kind.name.lowercase(),
        )
        status.update { snapshot }
        return snapshot
    }

    /**
     * Eligible means: uncategorized (or still the fallback code), not a USER decision, not an internal
     * transfer, and a debit/credit row the server may classify. Personal counterparties are filtered
     * again server-side; the client only narrows the request.
     */
    suspend fun eligibleCandidates(ownerId: String, now: Instant): List<String> =
        when (val scan = scanEligible(ownerId, now)) {
            is Outcome.Success -> scan.value
            is Outcome.Failure -> emptyList()
        }

    /**
     * Every eligible row in the rolling year, deduplicated across month/page boundaries. There is no
     * client-side cap: leaving rows out was why only the first five (previously) or fifty were
     * categorized. The caller batches to the endpoint limits.
     */
    private suspend fun scanEligible(
        ownerId: String,
        now: Instant,
    ): Outcome<List<String>> {
        val month = JakartaMonth.of(now)
        val ids = LinkedHashSet<String>()
        for (offset in 0 until SCAN_MONTHS) {
            val target = month.minusMonths(offset)
            var page: String? = null
            do {
                val result = serverFinance.listMonth(ownerId, target, limit = 100, cursor = page)
                if (result is Outcome.Failure) return Outcome.Failure(result.error)
                val pageResult = result as Outcome.Success
                for (tx in pageResult.value.items) {
                    if (isEligible(tx)) ids += tx.id
                }
                page = pageResult.value.nextCursor
            } while (page != null)
        }
        return Outcome.Success(ids.toList())
    }

    private fun isEligible(tx: Transaction): Boolean {
        if (tx.categoryOrigin == CategoryOrigin.USER) return false
        if (tx.isInternalTransfer) return false
        if (tx.category != Category.UNCATEGORIZED) return false
        return true
    }
}
