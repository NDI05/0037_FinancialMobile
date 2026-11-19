package com.pam.recap.core.util

import android.app.Activity
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Holds the current foreground [Activity] for platform flows that require one (Credential
 * Manager / Google authorization). Set by `MainActivity`, cleared in `onDestroy`.
 *
 * Only a weak, transient reference is kept: nothing about the activity is persisted, and the
 * holder is never used to touch UI state from a repository.
 */
@Singleton
class ActivityHolder @Inject constructor() {

    @Volatile
    var current: Activity? = null
        private set

    fun attach(activity: Activity) {
        current = activity
    }

    fun detach(activity: Activity) {
        if (current === activity) current = null
    }
}
