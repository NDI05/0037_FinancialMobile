package com.pam.recap.ui.feature.status

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pam.recap.core.time.ClockProvider
import com.pam.recap.domain.repository.NetworkMonitor
import com.pam.recap.domain.usecase.CategoryStatusHolder
import com.pam.recap.domain.usecase.SyncStatusHolder
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * Read-only projection of the two pipeline holders.
 *
 * Opening the panel constructs this view model and nothing else: no sync, no drain, no provider
 * request. It exists so the top bar and the sheet always show the same snapshot.
 */
@HiltViewModel
class UpdateStatusViewModel @Inject constructor(
    syncStatus: SyncStatusHolder,
    categoryStatus: CategoryStatusHolder,
    network: NetworkMonitor,
    private val clock: ClockProvider,
) : ViewModel() {

    /** Hosts the sheet's relative labels; a single read per open, never a ticking counter. */
    fun now(): kotlin.time.Instant = clock.now()

    val state: StateFlow<UpdateStatusUi> = combine(
        syncStatus.snapshot,
        categoryStatus.snapshot,
        network.observe(),
    ) { sync, category, online ->
        UpdateStatusMapper.map(sync = sync, category = category, online = online)
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
