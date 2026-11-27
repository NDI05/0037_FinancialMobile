package com.pam.recap.debug

import android.app.Activity
import android.os.Bundle
import android.widget.TextView
import com.pam.recap.data.notification.TransactionNotificationCandidate
import com.pam.recap.data.notification.TransactionNotifications

/**
 * Debug-only fixture surface for CR-010 notification copy/lockscreen verification.
 * Posts the production notification builder with synthetic candidates; never packaged in release.
 *
 * `adb shell am start -n com.pam.recap.debug/com.pam.recap.debug.NotificationDemoActivity --ei count 1`
 */
class NotificationDemoActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val count = intent.getIntExtra("count", 1).coerceIn(1, 9)
        val now = System.currentTimeMillis()
        val candidates = (1..count).map { index ->
            TransactionNotificationCandidate(
                id = "debug-notification-$index",
                source = "EMAIL",
                eventAtEpochMs = now - index * 1_000L,
            )
        }
        TransactionNotifications.post(this, candidates)
        setContentView(
            TextView(this).apply {
                text = "posted=$count canPost=${TransactionNotifications.canPost(this@NotificationDemoActivity)}"
                textSize = 14f
            },
        )
    }
}
