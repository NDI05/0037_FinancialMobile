package com.pam.recap.domain.usecase

import com.pam.recap.core.result.AppError
import com.pam.recap.core.result.Outcome
import com.pam.recap.core.time.ClockProvider
import com.pam.recap.core.time.JakartaMonth
import com.pam.recap.domain.model.Category
import com.pam.recap.domain.model.CategoryOrigin
import com.pam.recap.domain.model.CategoryQueuePhase
import com.pam.recap.domain.model.CategoryQueueSnapshot
import com.pam.recap.domain.model.Transaction
import com.pam.recap.domain.repository.ServerFinanceRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Instant

/** Bounded slice of the queue the client may offer in one round. */
private const val ENQUEUE_LIMIT = 50

/** How many months back the client looks for uncategorized candidates. */
private const val ENQUEUE_MONTHS = 2

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
                val candidates = eligibleCandidates(ownerId, clock.now())
                if (candidates.isEmpty()) return@withLock 0
                when (val enqueued = serverFinance.enqueueCategories(ownerId, candidates)) {
                    is Outcome.Failure -> null
                    is Outcome.Success -> {
                        status.update { it.copy(pending = enqueued.value.pending) }
                        enqueued.value.queued
                    }
                }
            }
        } finally {
            running.set(false)
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
    suspend fun eligibleCandidates(ownerId: String, now: Instant): List<String> {
        val month = JakartaMonth.of(now)
        // A row must be offered once even when the page cursor repeats it across months.
        val ids = LinkedHashSet<String>()
        for (offset in 0 until ENQUEUE_MONTHS) {
            val target = month.minusMonths(offset)
            var page: String? = null
            do {
                val result = serverFinance.listMonth(ownerId, target, limit = 100, cursor = page)
                if (result is Outcome.Failure) return ids.toList()
                val items = (result as Outcome.Success).value.items
                for (tx in items) {
                    if (isEligible(tx)) ids += tx.id
                    if (ids.size >= ENQUEUE_LIMIT) return ids.toList()
                }
                page = result.value.nextCursor
            } while (page != null)
        }
        return ids.toList()
    }

    private fun isEligible(tx: Transaction): Boolean {
        if (tx.categoryOrigin == CategoryOrigin.USER) return false
        if (tx.isInternalTransfer) return false
        if (tx.category != Category.UNCATEGORIZED) return false
        return true
    }
}
