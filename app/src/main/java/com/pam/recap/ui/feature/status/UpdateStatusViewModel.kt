package com.pam.recap.ui.feature.status

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pam.recap.core.time.ClockProvider
import com.pam.recap.core.time.JakartaMonth
import com.pam.recap.domain.repository.NetworkMonitor
import com.pam.recap.domain.repository.SessionRepository
import com.pam.recap.domain.usecase.CategoryStatusHolder
import com.pam.recap.domain.usecase.ResolveInitialMonthUseCase
import com.pam.recap.domain.usecase.SyncSessionCoordinator
import com.pam.recap.domain.usecase.SyncStatusHolder
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Read-only projection of the two pipeline holders plus the app-level manual sync session.
 *
 * Opening the panel constructs this view model and nothing else: no sync, no drain, no provider
 * request. The only write it can cause is an explicit "Sinkronkan sekarang" tap, delegated to
 * [SyncSessionCoordinator] so the run survives the sheet being dismissed.
 */
@HiltViewModel
class UpdateStatusViewModel @Inject constructor(
    syncStatus: SyncStatusHolder,
    categoryStatus: CategoryStatusHolder,
    network: NetworkMonitor,
    private val clock: ClockProvider,
    sessions: SessionRepository,
    resolveInitialMonth: ResolveInitialMonthUseCase,
    private val coordinator: SyncSessionCoordinator,
) : ViewModel() {

    /**
     * Follows the signed-in owner instead of reading the session once: the status provider is
     * activity-scoped, so a login that happens after the shell was built must still resolve the
     * month the sheet offers to sync.
     */
    private val month = MutableStateFlow<JakartaMonth?>(null)

    init {
        viewModelScope.launch {
            sessions.observe()
                .map { it?.ownerId }
                .distinctUntilChanged()
                .collect { ownerId ->
                    month.value = if (ownerId == null) null else {
                        runCatching { resolveInitialMonth(ownerId) }
                            .getOrElse { clock.currentMonth() }
                    }
                }
        }
    }

    /** Hosts the sheet's relative labels; a single read per open, never a ticking counter. */
    fun now(): kotlin.time.Instant = clock.now()

    /** Starts (or joins) the one manual sync flight. Idempotent while a run is active. */
    fun syncNow() {
        coordinator.requestSync(month.value)
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
}
