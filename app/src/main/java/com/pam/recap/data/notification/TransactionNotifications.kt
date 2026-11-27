package com.pam.recap.data.notification

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.pam.recap.R

/**
 * Local, privacy-safe notification surface for CR-010 N-02/N-03.
 *
 * The lockscreen copy is deliberately generic: no nominal, merchant, email or account number.
 * A single cycle posts one notification; a retry that produces no NEW ids posts nothing (dedupe
 * lives in the planner).
 */
object TransactionNotifications {
    const val CHANNEL_ID = "pam_transaction_new"
    const val NOTIFICATION_ID = 4201
    private const val SINGLE_REQUEST_CODE = 4201
    private const val GROUP_REQUEST_CODE = 4202

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = context.getString(R.string.notification_channel_description)
            lockscreenVisibility = android.app.Notification.VISIBILITY_PRIVATE
            setShowBadge(true)
        }
        manager.createNotificationChannel(channel)
    }

    fun hasPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED

    /** True when both the runtime permission and the user's app/channel switches allow posting. */
    fun canPost(context: Context): Boolean {
        if (!hasPermission(context)) return false
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(NotificationManager::class.java)
            val channel = manager?.getNotificationChannel(CHANNEL_ID)
            if (channel != null && channel.importance == NotificationManager.IMPORTANCE_NONE) {
                return false
            }
        }
        return true
    }

    fun post(context: Context, transactions: List<TransactionNotificationCandidate>) {
        if (transactions.isEmpty()) return
        ensureChannel(context)
        val manager = NotificationManagerCompat.from(context)
        if (!canPost(context)) return

        val single = transactions.size == 1
        val contentIntent = if (single) {
            TransactionNotificationIntents.pendingIntent(
                context,
                TransactionNotificationIntents.singleIntent(context, transactions.first().id),
                SINGLE_REQUEST_CODE,
            )
        } else {
            TransactionNotificationIntents.pendingIntent(
                context,
                TransactionNotificationIntents.groupIntent(
                    context,
                    transactions.map { it.id }.take(MAX_GROUP_IDS),
                ),
                GROUP_REQUEST_CODE,
            )
        }
        val title = if (single) {
            context.getString(R.string.notification_single_title)
        } else {
            context.getString(R.string.notification_group_title, transactions.size)
        }
        val body = if (single) {
            context.getString(R.string.notification_single_body)
        } else {
            context.getString(R.string.notification_group_body)
        }
        val publicVersion = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_pam)
            .setContentTitle(title)
            .setContentText(body)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_pam)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion)
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .build()

        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        runCatching { manager.notify(NOTIFICATION_ID, notification) }
    }

    fun cancel(context: Context) {
        runCatching { NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID) }
    }

    /** "Buka pengaturan sistem" target after a denied permission or a disabled channel. */
    fun systemSettingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

    private const val MAX_GROUP_IDS = 100
}
