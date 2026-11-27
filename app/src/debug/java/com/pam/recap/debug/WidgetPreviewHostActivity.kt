package com.pam.recap.debug

import android.app.Activity
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import com.pam.recap.ui.widget.PamSummaryWidgetReceiver

/**
 * Debug-only host used to capture real Glance widget renders without a launcher.
 *
 * Not shipped in release. Requires `adb shell appwidget grantbind --package com.pam.recap.debug`
 * once per install. Usage:
 * `adb shell am start -n com.pam.recap.debug/.debug.WidgetPreviewHostActivity --ei width 250 --ei height 124`
 */
class WidgetPreviewHostActivity : Activity() {

    private var host: AppWidgetHost? = null
    private var appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val width = intent.getIntExtra("width", 250).coerceIn(120, 500)
        val height = intent.getIntExtra("height", 124).coerceIn(100, 400)
        val manager = AppWidgetManager.getInstance(this)
        val provider = ComponentName(this, PamSummaryWidgetReceiver::class.java)
        host = AppWidgetHost(this, HOST_ID).also { it.startListening() }
        appWidgetId = host?.allocateAppWidgetId() ?: AppWidgetManager.INVALID_APPWIDGET_ID
        val bound = manager.bindAppWidgetIdIfAllowed(appWidgetId, provider)
        Log.i(TAG, "appWidgetId=$appWidgetId bound=$bound")
        if (!bound) {
            setContentView(
                TextView(this).apply {
                    text = "grantbind needed: adb shell appwidget grantbind --package ${packageName}"
                    gravity = Gravity.CENTER
                    setTextColor(Color.BLACK)
                },
            )
            return
        }
        val info = manager.getAppWidgetInfo(appWidgetId)
        val view: AppWidgetHostView = host!!.createView(this, appWidgetId, info)
        view.setAppWidget(appWidgetId, info)
        val density = resources.displayMetrics.density
        setContentView(
            FrameLayout(this).apply {
                setBackgroundColor(Color.rgb(250, 250, 247))
                addView(
                    view,
                    FrameLayout.LayoutParams(
                        (width * density).toInt(),
                        (height * density).toInt(),
                        Gravity.CENTER,
                    ),
                )
            },
        )
        window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }

    override fun onDestroy() {
        host?.deleteAppWidgetId(appWidgetId)
        host?.stopListening()
        super.onDestroy()
    }

    private companion object {
        const val TAG = "PamWidgetPreview"
        const val HOST_ID = 0x50414D
    }
}
