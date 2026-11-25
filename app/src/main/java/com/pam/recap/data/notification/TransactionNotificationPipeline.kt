package com.pam.recap.data.notification

import android.content.Context
import com.pam.recap.core.time.ClockProvider
import com.pam.recap.core.time.JakartaMonth
import com.pam.recap.domain.model.Transaction
import com.pam.recap.core.result.Outcome
import com.pam.recap.domain.repository.ServerFinanceRepository
import com.pam.recap.domain.repository.SessionRepository
import com.pam.recap.domain.repository.TransactionRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One local pipeline for CR-010 N-01..N-03, invoked after app start and after a sync/refresh
 * signal. It does not require a live FCM receiver: it compares the persisted transaction store
 * against the private per-account watermark and posts at most one local notification per cycle.
 *
 * The watermark advances even when the toggle is off, the permission is denied or the channel is
 * disabled. That keeps "enable later" from replaying history.
 */
@Singleton
class TransactionNotificationPipeline @Inject constructor(
    @ApplicationContext private val context: Context,
    private val sessions: SessionRepository,
    private val settings: TransactionNotificationSettings,
    private val transactions: TransactionRepository,
    private val server: ServerFinanceRepository,
    private val clock: ClockProvider,
) {

    suspend fun onSyncCycle(ownerId: String) {
        if (sessions.current()?.ownerId != ownerId) return
        val now = clock.now()
        val month = JakartaMonth.of(now)
        val candidates = loadCandidates(ownerId, month)
        val plan = TransactionNotificationPlanner.plan(
            previous = settings.watermark(ownerId),
            candidates = candidates,
            nowEpochMs = now.toEpochMilliseconds(),
            monthId = month.id(),
        )
        settings.saveWatermark(ownerId, plan.watermark)
        if (!plan.shouldNotify) return
        if (!settings.isEnabled()) return
        // Re-check the session after the query: a logout during a slow sync must not surface data.
        if (sessions.current()?.ownerId != ownerId) return
        runCatching { TransactionNotifications.post(context, plan.newTransactions) }
    }

    /** First app start after sign-in establishes the baseline without notifying. */
    suspend fun establishBaseline(ownerId: String) = onSyncCycle(ownerId)

    fun onSessionCleared() {
        settings.clearWatermarks()
        TransactionNotifications.cancel(context)
    }

    /**
     * Server mode lists the current month; local mode reads the persisted Room rows. Both paths
     * return already-persisted transactions only — the pipeline never derives rows from email in
     * memory, never calls AI and never retries a failed import.
     */
    private suspend fun loadCandidates(
        ownerId: String,
        month: JakartaMonth,
    ): List<TransactionNotificationCandidate> {
        val rows: List<Transaction> = runCatching {
            if (server.isEnabled) {
                when (val page = server.listMonth(ownerId, month, limit = CANDIDATE_LIMIT)) {
                    is Outcome.Success -> page.value.items
                    is Outcome.Failure -> transactions.listMonth(ownerId, month)
                }
            } else {
                transactions.listMonth(ownerId, month)
            }
        }.getOrDefault(emptyList())
        return rows.map { row ->
            TransactionNotificationCandidate(
                id = row.id,
                source = row.source.name,
                eventAtEpochMs = row.eventAt.toEpochMilliseconds(),
            )
        }
    }

    private companion object {
        const val CANDIDATE_LIMIT = 100
    }
}
