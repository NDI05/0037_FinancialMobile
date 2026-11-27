package com.pam.recap.data.widget

import com.pam.recap.core.di.ApplicationScope
import com.pam.recap.data.notification.TransactionNotificationPipeline
import com.pam.recap.domain.repository.FinanceRefreshBus
import com.pam.recap.domain.repository.SessionRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/** The only thing the data layer needs from the Glance presentation layer. */
interface WidgetRefresher {
    suspend fun refreshAll()
}

/**
 * CR-010 orchestration: keeps the widget aggregate and the notification watermark in sync with
 * the app lifecycle without touching navigation or Overview.
 *
 * Triggers:
 *  - every session emission (sign-in readiness, account switch, logout);
 *  - app foreground, called once from MainActivity;
 *  - `FinanceRefreshBus`, the existing post-sync/post-write invalidation signal.
 */
@Singleton
class HomeSurfaceCoordinator @Inject constructor(
    private val sessions: SessionRepository,
    private val snapshots: WidgetSnapshotRepository,
    private val notifications: TransactionNotificationPipeline,
    private val widgetRefresher: WidgetRefresher,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private val started = AtomicBoolean(false)
    private val refreshMutex = Mutex()

    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            sessions.observe().collect { session ->
                val ownerId = session?.ownerId
                if (ownerId == null) {
                    snapshotMutex {
                        snapshots.invalidateForSession(null)
                        notifications.onSessionCleared()
                        widgetRefresher.refreshAll()
                    }
                } else {
                    refresh(ownerId)
                }
            }
        }
        scope.launch {
            FinanceRefreshBus.events.collect { event ->
                val ownerId = sessions.current()?.ownerId
                if (ownerId == event.ownerId) refresh(ownerId)
            }
        }
    }

    /** Called from MainActivity.onStart after the session gate has resolved the active owner. */
    suspend fun onForeground() {
        val ownerId = sessions.current()?.ownerId
        if (ownerId == null) {
            snapshotMutex {
                snapshots.invalidateForSession(null)
                notifications.onSessionCleared()
                widgetRefresher.refreshAll()
            }
        } else {
            refresh(ownerId)
        }
    }

    private suspend fun refresh(ownerId: String) = snapshotMutex {
        runCatching { snapshots.refreshNow(ownerId) }
        runCatching { notifications.onSyncCycle(ownerId) }
        runCatching { widgetRefresher.refreshAll() }
    }

    private suspend fun snapshotMutex(block: suspend () -> Unit) {
        refreshMutex.withLock { block() }
    }
}
