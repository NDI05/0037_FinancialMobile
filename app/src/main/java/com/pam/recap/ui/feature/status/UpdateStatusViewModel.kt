package com.pam.recap.ui.feature.status

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pam.recap.core.result.AppError
import com.pam.recap.core.result.AppErrorRules
import com.pam.recap.core.result.Outcome
import com.pam.recap.core.time.ClockProvider
import com.pam.recap.core.time.JakartaMonth
import com.pam.recap.core.time.RetentionWindow
import com.pam.recap.domain.model.GmailLinkStatus
import com.pam.recap.domain.model.GmailSyncState
import com.pam.recap.domain.repository.GmailLinkRepository
import com.pam.recap.domain.repository.NetworkMonitor
import com.pam.recap.domain.repository.SessionRepository
import com.pam.recap.domain.usecase.CategoryStatusHolder
import com.pam.recap.domain.usecase.ResolveInitialMonthUseCase
import com.pam.recap.domain.usecase.SyncSessionCoordinator
import com.pam.recap.domain.usecase.SyncStatusHolder
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.time.Duration.Companion.seconds

/**
 * Read-only projection of the two pipeline holders plus the app-level manual sync session.
 *
 * Opening the panel constructs this view model and nothing else: no sync, no drain, no provider
 * request. The only write it can cause is an explicit "Sinkronkan sekarang" tap, delegated to
 * [SyncSessionCoordinator] so the run survives the sheet being dismissed.
 *
 * When the server reports a linked Gmail account (contract §9) the sheet switches to the server
 * projection instead: [serverSync] is read while the sheet is visible and the tap only enqueues
 * `POST /v1/gmail/sync`. Polling re-reads `GET /v1/gmail/status`; it never starts a job by itself.
 */
@HiltViewModel
class UpdateStatusViewModel @Inject constructor(
    syncStatus: SyncStatusHolder,
    categoryStatus: CategoryStatusHolder,
    network: NetworkMonitor,
    private val clock: ClockProvider,
    private val sessions: SessionRepository,
    resolveInitialMonth: ResolveInitialMonthUseCase,
    private val coordinator: SyncSessionCoordinator,
    private val gmail: GmailLinkRepository,
) : ViewModel() {

    /**
     * Follows the signed-in owner instead of reading the session once: the status provider is
     * activity-scoped, so a login that happens after the shell was built must still resolve the
     * month the sheet offers to sync.
     */
    private val month = MutableStateFlow<JakartaMonth?>(null)

    private val _serverSync = MutableStateFlow(GmailServerSyncUi.Hidden)

    /** Server-side Gmail sync state; [GmailServerSyncUi.Hidden] keeps the legacy device-sync view. */
    val serverSync: StateFlow<GmailServerSyncUi> = _serverSync.asStateFlow()

    private var pollJob: Job? = null
    private var refreshing = false

    init {
        viewModelScope.launch {
            sessions.observe()
                .map { it?.ownerId }
                .distinctUntilChanged()
                .collect { ownerId ->
                    month.value = if (ownerId == null) null else {
                        // Never offer a period outside the rolling year the server serves; a stale
                        // local month would only come back as `outside_retention`.
                        window().clamp(
                            runCatching { resolveInitialMonth(ownerId) }
                                .getOrElse { clock.currentMonth() },
                        )
                    }
                }
        }
    }

    /** Hosts the sheet's relative labels; re-read on every render, never a ticking counter. */
    fun now(): kotlin.time.Instant = clock.now()

    /** The rolling one-year window the current period belongs to, evaluated on the owner's clock. */
    fun window(): RetentionWindow = RetentionWindow.of(clock)

    /** Starts (or joins) the one manual sync flight. Idempotent while a run is active. */
    fun syncNow() {
        coordinator.requestSync(month.value)
    }

    /**
     * Called when the sheet becomes visible: read the server projection once and keep polling while
     * it stays open. Reads only; the endpoint is a GET, so opening the sheet still starts nothing.
     */
    fun onSheetOpened() {
        refreshServerSync()
        if (pollJob?.isActive == true) return
        pollJob = viewModelScope.launch {
            while (isActive) {
                delay(SERVER_POLL_INTERVAL_MS)
                refreshServerSyncNow()
            }
        }
    }

    /** Called when the sheet leaves composition: stop polling, never touch the server job. */
    fun onSheetClosed() {
        pollJob?.cancel()
        pollJob = null
    }

    /** Re-reads the projection on demand (sheet open, activity resume, after a request). */
    fun refreshServerSync() {
        viewModelScope.launch { refreshServerSyncNow() }
    }

    /**
     * The server-sync tap. It only enqueues `POST /v1/gmail/sync`; no local import is started and
     * dismissing the sheet afterwards does not cancel anything. Local guards keep one tap from
     * turning into two requests while one is in flight or a cooldown is still running.
     */
    fun syncServerNow() {
        val current = _serverSync.value
        if (!current.available || current.busy || current.running || current.needsReauth) return
        if (current.coolingDown(clock.now())) return
        viewModelScope.launch {
            val ownerId = sessions.current()?.ownerId ?: return@launch
            _serverSync.update {
                it.copy(busy = true, error = null, notice = GmailSyncNotice.NONE)
            }
            when (val result = gmail.requestSync(ownerId)) {
                is Outcome.Success -> {
                    _serverSync.update {
                        it.copy(
                            busy = false,
                            notice = if (result.value.coalesced) {
                                GmailSyncNotice.COALESCED
                            } else {
                                GmailSyncNotice.QUEUED
                            },
                        )
                    }
                    refreshServerSyncNow()
                }
                is Outcome.Failure -> applySyncFailure(result.error)
            }
        }
    }

    private suspend fun refreshServerSyncNow() {
        if (refreshing) return
        refreshing = true
        try {
            if (!gmail.isEnabled) {
                _serverSync.value = GmailServerSyncUi.Hidden
                return
            }
            val ownerId = sessions.current()?.ownerId
            if (ownerId == null) {
                _serverSync.value = GmailServerSyncUi.Hidden
                return
            }
            if (!_serverSync.value.available) {
                _serverSync.update { it.copy(loading = true) }
            }
            when (val result = gmail.status(ownerId)) {
                is Outcome.Success -> {
                    val observedAt = clock.now()
                    _serverSync.update { previous -> previous.withStatus(result.value, observedAt) }
                }
                is Outcome.Failure -> {
                    _serverSync.update { it.copy(loading = false, error = result.error) }
                }
            }
        } finally {
            refreshing = false
        }
    }

    private suspend fun applySyncFailure(error: AppError) {
        val now = clock.now()
        when {
            error is AppError.RateLimited && error.debugDetail == AppErrorRules.SYNC_COOLDOWN -> {
                val seconds = error.retryAfterSeconds
                _serverSync.update {
                    it.copy(
                        busy = false,
                        notice = GmailSyncNotice.COOLDOWN,
                        cooldownUntil = seconds?.let { value -> now + value.seconds },
                        observedAt = now,
                    )
                }
            }
            error is AppError.Validation && error.rule == AppErrorRules.GMAIL_NOT_CONNECTED -> {
                _serverSync.update { it.copy(busy = false, notice = GmailSyncNotice.NOT_CONNECTED) }
                refreshServerSyncNow()
            }
            error is AppError.Validation && error.rule == AppErrorRules.GMAIL_DISABLED -> {
                _serverSync.update { it.copy(busy = false, notice = GmailSyncNotice.DISABLED) }
            }
            else -> {
                _serverSync.update {
                    it.copy(busy = false, notice = GmailSyncNotice.FAILED, error = error)
                }
            }
        }
    }

    /** Maps one status read onto the sheet state; a disconnected account hides the whole section. */
    private fun GmailServerSyncUi.withStatus(
        status: GmailLinkStatus,
        observedAt: kotlin.time.Instant,
    ): GmailServerSyncUi {
        if (!status.connected) return GmailServerSyncUi.Hidden
        val running = status.syncState == GmailSyncState.BACKFILL ||
            status.syncState == GmailSyncState.DELTA
        return copy(
            available = true,
            loading = false,
            needsReauth = status.needsReauth,
            syncState = status.syncState,
            requested = status.syncRequestedAt != null && !running,
            progress = status.progress,
            lastAttemptAt = status.lastAttemptAt,
            lastSuccessAt = status.lastSuccessAt,
            nextSyncAt = status.nextSyncAt,
            observedAt = observedAt,
            error = null,
            // A queued/coalesced notice is only useful until the worker reports its own phase.
            notice = when (notice) {
                GmailSyncNotice.QUEUED, GmailSyncNotice.COALESCED ->
                    if (running) GmailSyncNotice.NONE else notice
                GmailSyncNotice.COOLDOWN ->
                    if (cooldownUntil != null && cooldownUntil <= observedAt) {
                        GmailSyncNotice.NONE
                    } else {
                        notice
                    }
                else -> notice
            },
        )
    }

    val state: StateFlow<UpdateStatusUi> = combine(
        syncStatus.snapshot,
        categoryStatus.snapshot,
        network.observe(),
        coordinator.state,
        month,
    ) { sync, category, online, session, resolvedMonth ->
        UpdateStatusMapper.map(
            sync = sync,
            category = category,
            online = online,
            session = session.copy(month = session.month ?: resolvedMonth),
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = UpdateStatusMapper.map(
            sync = syncStatus.snapshot.value,
            category = categoryStatus.snapshot.value,
            online = network.isOnline(),
        ),
    )

    companion object {
        /** How often the open sheet refreshes `GET /v1/gmail/status`; reads only, no side effects. */
        internal const val SERVER_POLL_INTERVAL_MS: Long = 5_000
    }
}
