package com.pam.recap.domain.repository

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/** One owner/month invalidation after a server acknowledgement; no polling. */
data class FinanceRefreshEvent(val ownerId: String, val monthId: String)

object FinanceRefreshBus {
    private val _events = MutableSharedFlow<FinanceRefreshEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<FinanceRefreshEvent> = _events

    fun notify(ownerId: String, monthId: String) {
        _events.tryEmit(FinanceRefreshEvent(ownerId, monthId))
    }
}
