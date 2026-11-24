package com.pam.recap.ui.feature.gmail

import com.pam.recap.domain.model.GmailLinkResult

/**
 * Pure gate between `MainActivity`'s intents and the OAuth callback result (contract §2).
 *
 * Android re-delivers the launching intent on activity recreate and may deliver the same intent
 * twice; without deduplication the `pam://gmail/connected` banner would pop up again on every
 * rotation. The gate keeps the action+data key of the last accepted callback, is seeded from saved
 * state after a recreate, and exposes [handledKeySnapshot] so the activity can save it again.
 *
 * The activity passes the already-extracted URI fields, which keeps the checks testable without an
 * `android.net.Uri` stub.
 */
class GmailLinkIntentGate(handledKeySnapshot: String? = null) {

    private var handledKey: String? = handledKeySnapshot

    /** Key to persist in `onSaveInstanceState`; identifies the callback already consumed. */
    val handledKeySnapshot: String?
        get() = handledKey

    /**
     * Returns the first result for this callback, or null when the intent is not a Gmail callback
     * or is an exact replay of one already accepted.
     */
    fun accept(
        action: String?,
        data: String?,
        scheme: String?,
        host: String?,
        path: String?,
        status: String?,
        reason: String?,
    ): GmailLinkResult? {
        if (data == null) return null
        if (scheme != "pam" || host != "gmail") return null
        if (path?.startsWith("/connected") != true) return null
        val key = "${action.orEmpty()}|$data"
        if (key == handledKey) return null
        handledKey = key
        return GmailLinkResult.fromWire(status = status, reason = reason)
    }

    companion object {
        /** Saved-state key for [handledKeySnapshot]. */
        const val KEY_HANDLED_INTENT = "gmail_link_handled_intent"
    }
}
