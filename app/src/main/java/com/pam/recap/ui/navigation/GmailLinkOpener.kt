package com.pam.recap.ui.navigation

import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * One-shot bridge from the app-level "Status pembaruan" sheet to the navigation graph.
 *
 * The sheet is hosted by [com.pam.recap.ui.feature.status.UpdateStatusActionProvider] above the
 * `NavHost`, so it cannot reach the controller directly. The provider owns one instance, publishes
 * it through [LocalGmailLinkOpener], and the graph collects [requests] to navigate. Keeping it out
 * of the view model preserves the rule that opening the sheet performs no work.
 */
class GmailLinkOpener {

    private val _requests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** Emits once per "Tautkan ulang Gmail" tap; buffered so a resume cannot lose the tap. */
    val requests: SharedFlow<Unit> = _requests

    fun open() {
        _requests.tryEmit(Unit)
    }
}

val LocalGmailLinkOpener = staticCompositionLocalOf<GmailLinkOpener?> { null }
