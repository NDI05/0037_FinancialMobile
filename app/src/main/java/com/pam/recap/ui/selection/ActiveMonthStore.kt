package com.pam.recap.ui.selection

import com.pam.recap.core.time.JakartaMonth
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The month the owner is currently looking at, shared by the top-level screens.
 *
 * Ringkasan and the Transaksi tab resolve their opening month with the same rule
 * ([com.pam.recap.domain.usecase.ResolveInitialMonthUseCase]): the newest month with data,
 * falling back to the clock month. Without a shared value the two screens could drift apart —
 * Ringkasan scrolled to an older month, Transaksi silently opening on the newest one — and the
 * owner would see two different "current" months in the same session (UAT 24 Nov, S2.P0 nav).
 *
 * The store is a plain in-memory holder: it is per-process UI state, never persisted, and it is
 * keyed by owner so a sign-out/sign-in cannot leak one account's month into another's list.
 */
@Singleton
class ActiveMonthStore @Inject constructor() {

    private val _selection = MutableStateFlow<Selection?>(null)

    /** Current selection for observation; `null` until a screen has resolved a month. */
    val selection: StateFlow<Selection?> = _selection.asStateFlow()

    /** Month the owner last saw, or `null` when nothing has been resolved yet or the owner differs. */
    fun current(ownerId: String): JakartaMonth? = _selection.value
        ?.takeIf { it.ownerId == ownerId }
        ?.month

    /** Records the month a top-level screen is showing for [ownerId]. */
    fun select(ownerId: String, month: JakartaMonth) {
        _selection.value = Selection(ownerId, month)
    }

    /** Drops the selection on sign-out so the next session resolves its own month. */
    fun clear() {
        _selection.value = null
    }

    data class Selection(val ownerId: String, val month: JakartaMonth)
}
