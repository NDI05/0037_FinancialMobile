package com.pam.recap.ui.widget

import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver

/** Launcher receiver for the 2x2/4x2 PAM summary widget. */
class PamSummaryWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = PamSummaryWidget()
}
