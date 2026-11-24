package com.pam.recap.core.platform

import android.app.Activity
import java.lang.ref.WeakReference
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The one running [Activity], kept weakly so Credential Manager can show the Google account sheet.
 *
 * Credential Manager needs an Activity context for the picker (`PendingIntent` launch); Hilt only
 * hands adapters the application context. This holder is the minimal seam — it never keeps the
 * Activity alive after `onDestroy`, so no window leaks into a session.
 */
@Singleton
class ActivityHost @Inject constructor() {

    private var ref: WeakReference<Activity>? = null

    fun attach(activity: Activity) {
        ref = WeakReference(activity)
    }

    fun detach(activity: Activity) {
        if (ref?.get() === activity) ref = null
    }

    /** Null when no Activity is resumed — callers must degrade instead of crashing. */
    fun activity(): Activity? = ref?.get()?.takeIf { !it.isFinishing && !it.isDestroyed }
}
