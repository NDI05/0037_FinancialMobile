package com.pam.recap.data.gmail

import android.app.Activity
import android.content.Intent
import android.content.IntentSender
import kotlinx.coroutines.CompletableDeferred
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Bridges the Activity-result needed by the Google authorization flow into a suspend call.
 *
 * `MainActivity` forwards `onActivityResult` here; keeping the hop in one small class lets
 * [GoogleGmailSource] stay a plain suspend API and keeps the token out of the Activity.
 */
@Singleton
class GoogleAuthBridge @Inject constructor() {

    private val lock = Any()
    private var pending: CompletableDeferred<Intent?>? = null

    /** True when the result belonged to this bridge and was consumed. */
    fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        if (requestCode != REQUEST_CODE) return false
        val deferred = synchronized(lock) {
            pending.also { pending = null }
        } ?: return true
        if (resultCode == Activity.RESULT_OK) deferred.complete(data) else deferred.complete(null)
        return true
    }

    /** Shows the provider consent screen and returns its intent, or null when cancelled. */
    suspend fun launch(activity: Activity, intentSender: IntentSender): Intent? {
        val deferred = CompletableDeferred<Intent?>()
        synchronized(lock) { pending = deferred }
        return try {
            activity.startIntentSenderForResult(intentSender, REQUEST_CODE, null, 0, 0, 0)
            deferred.await()
        } catch (error: IntentSender.SendIntentException) {
            synchronized(lock) { pending = null }
            null
        }
    }

    companion object {
        const val REQUEST_CODE: Int = 0x5041
    }
}
