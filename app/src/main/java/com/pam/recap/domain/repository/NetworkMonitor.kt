package com.pam.recap.domain.repository

/**
 * Connectivity as the status surface needs it: a single boolean, no request. The panel and the top
 * bar must be able to say "Offline" without performing any network work of their own.
 */
interface NetworkMonitor {

    /** Latest known connectivity; true when the OS reports a validated-capable default network. */
    fun isOnline(): Boolean

    /**
     * Long-lived observation for the visible screen. Callers collect while the screen is started and
     * stop collecting when it leaves; nothing polls in the background.
     */
    fun observe(): kotlinx.coroutines.flow.Flow<Boolean>
}
