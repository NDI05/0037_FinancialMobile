package com.pam.recap.data.notification

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.pam.recap.MainActivity

/**
 * Intent contract between a local transaction notification and [MainActivity]. Kept small and
 * explicit so a future FCM receiver can reuse the same routing without copying navigation logic.
 */
object TransactionNotificationIntents {
    const val ACTION_OPEN_TRANSACTION = "com.pam.recap.action.OPEN_TRANSACTION"
    const val ACTION_OPEN_TRANSACTIONS = "com.pam.recap.action.OPEN_TRANSACTIONS"

    const val EXTRA_TRANSACTION_ID = "com.pam.recap.extra.TRANSACTION_ID"
    const val EXTRA_TRANSACTION_IDS = "com.pam.recap.extra.TRANSACTION_IDS"
    const val EXTRA_NOTIFICATION_GROUP = "com.pam.recap.extra.NOTIFICATION_GROUP"

    fun singleIntent(context: Context, transactionId: String): Intent =
        Intent(context, MainActivity::class.java).apply {
            action = ACTION_OPEN_TRANSACTION
            putExtra(EXTRA_TRANSACTION_ID, transactionId)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

    fun groupIntent(context: Context, transactionIds: List<String>): Intent =
        Intent(context, MainActivity::class.java).apply {
            action = ACTION_OPEN_TRANSACTIONS
            putExtra(EXTRA_TRANSACTION_IDS, transactionIds.joinToString(","))
            putExtra(EXTRA_NOTIFICATION_GROUP, true)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

    fun pendingIntent(context: Context, intent: Intent, requestCode: Int): PendingIntent =
        PendingIntent.getActivity(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
}

/**
 * Presentation-neutral target emitted once an intent has passed session validation. The Lead can
 * collect this from `PamNavHost` without this change touching the navigation graph itself.
 */
sealed interface PamPendingTarget {
    data class Overview(val bank: String?, val monthId: String?) : PamPendingTarget

    data class TransactionDetail(val transactionId: String) : PamPendingTarget

    data class Transactions(val transactionIds: List<String>) : PamPendingTarget

    /** Signed-out deep link: the normal auth gate still decides the screen. */
    data object SignIn : PamPendingTarget
}

object PamPendingNavigation {
    @Volatile
    private var pending: PamPendingTarget? = null

    fun emit(target: PamPendingTarget) {
        pending = target
    }

    fun consume(): PamPendingTarget? {
        val value = pending
        pending = null
        return value
    }

    fun clear() {
        pending = null
    }
}
