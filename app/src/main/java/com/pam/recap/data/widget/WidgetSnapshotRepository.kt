package com.pam.recap.data.widget

import com.pam.recap.core.time.ClockProvider
import com.pam.recap.core.time.JakartaMonth
import com.pam.recap.core.result.Outcome
import com.pam.recap.domain.repository.ServerFinanceRepository
import com.pam.recap.domain.repository.SessionRepository
import com.pam.recap.domain.repository.TransactionRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * CR-010: builds the single private aggregate that every home-screen widget for the active account
 * renders. It never records amounts, merchants or account numbers and never calls the AI layer.
 *
 * Refresh triggers are owned by [HomeSurfaceCoordinator]: app foreground, session readiness and
 * `FinanceRefreshBus` signals. A failure keeps the previous confirmed count and marks the snapshot
 * OFFLINE so the widget can show it with "Diperbarui HH.mm" instead of pretending it is fresh.
 */
@Singleton
class WidgetSnapshotRepository @Inject constructor(
    private val sessions: SessionRepository,
    private val transactions: TransactionRepository,
    private val server: ServerFinanceRepository,
    private val clock: ClockProvider,
    private val store: WidgetSnapshotStore,
) {

    /**
     * Refreshes the snapshot for [ownerId]. A signed-out state is handled by
     * [invalidateForSession] before this call; stale data must not survive logout/account switch.
     */
    suspend fun refreshNow(ownerId: String) {
        val month = JakartaMonth.of(clock)
        val existing = store.read()
        if (existing != null && (existing.ownerId != ownerId || existing.monthId != month.id())) {
            store.clear()
        }
        val retained = store.read()?.takeIf {
            it.ownerId == ownerId && it.monthId == month.id() && it.hasCount
        }

        val now = clock.now().toEpochMilliseconds()
        if (retained == null) {
            store.save(
                WidgetSnapshot(
                    ownerId = ownerId,
                    bank = WidgetBank.MANDIRI,
                    monthId = month.id(),
                    transactionCount = null,
                    status = WidgetSnapshotStatus.LOADING,
                    updatedAtEpochMs = null,
                    confirmed = false,
                ),
            )
        }

        val count = loadConfirmedCount(ownerId, month)
        if (count != null) {
            store.save(
                WidgetSnapshot(
                    ownerId = ownerId,
                    bank = WidgetBank.MANDIRI,
                    monthId = month.id(),
                    transactionCount = count,
                    status = if (count == 0) {
                        WidgetSnapshotStatus.EMPTY
                    } else {
                        WidgetSnapshotStatus.AVAILABLE
                    },
                    updatedAtEpochMs = now,
                    confirmed = true,
                ),
            )
        } else if (retained != null) {
            store.save(
                retained.copy(
                    status = WidgetSnapshotStatus.OFFLINE,
                    confirmed = true,
                ),
            )
        } else {
            store.save(
                WidgetSnapshot(
                    ownerId = ownerId,
                    bank = WidgetBank.MANDIRI,
                    monthId = month.id(),
                    transactionCount = null,
                    status = WidgetSnapshotStatus.ERROR,
                    updatedAtEpochMs = null,
                    confirmed = false,
                ),
            )
        }
    }

    /** Clears the aggregate as soon as the active owner no longer matches (logout/account switch). */
    fun invalidateForSession(ownerId: String?) {
        val existing = store.read() ?: return
        if (existing.ownerId != ownerId) store.clear()
    }

    private suspend fun loadConfirmedCount(ownerId: String, month: JakartaMonth): Int? {
        if (server.isEnabled) {
            // In backend mode the server is the source of truth. A failed call must not fall back to
            // an empty local cache and present "0 transaksi tercatat" as confirmed data.
            return when (val result = server.summary(ownerId, month)) {
                is Outcome.Success -> result.value.transactionCount
                is Outcome.Failure -> null
            }
        }
        return runCatching { transactions.monthTotals(ownerId, month).transactionCount }.getOrNull()
    }
}
