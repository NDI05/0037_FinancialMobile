package com.pam.recap.debug

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.os.Bundle
import android.widget.TextView
import com.pam.recap.ui.widget.PamSummaryWidgetReceiver

/**
 * Debug-only helper that asks the current launcher to pin the PAM widget, so a real launcher
 * instance can be added automatically during verification. Never packaged in release.
 *
 * `adb shell am start -n com.pam.recap.debug/com.pam.recap.debug.PinWidgetActivity`
 */
class PinWidgetActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val manager = AppWidgetManager.getInstance(this)
        val supported = manager.isRequestPinAppWidgetSupported
        val requested = if (supported) {
            manager.requestPinAppWidget(
                ComponentName(this, PamSummaryWidgetReceiver::class.java),
                null,
                null,
            )
        } else {
            false
        }
        setContentView(
            TextView(this).apply {
                text = "pinSupported=$supported requested=$requested"
                textSize = 14f
            },
        )
    }
}
