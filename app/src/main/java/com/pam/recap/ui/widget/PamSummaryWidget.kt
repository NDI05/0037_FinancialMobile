package com.pam.recap.ui.widget

import android.annotation.SuppressLint

import android.appwidget.AppWidgetManager
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.AppWidgetId
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.pam.recap.R
import com.pam.recap.core.time.JakartaMonth
import com.pam.recap.core.time.SystemClockProvider
import com.pam.recap.data.widget.WidgetBank
import com.pam.recap.data.widget.WidgetSnapshot
import com.pam.recap.data.widget.WidgetSnapshotStatus
import com.pam.recap.data.widget.WidgetSnapshotStore
import com.pam.recap.domain.repository.SessionRepository
import com.pam.recap.ui.theme.Canvas
import com.pam.recap.ui.theme.Lime
import com.pam.recap.ui.theme.OnInk
import com.pam.recap.ui.theme.OnInkMuted
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Instant
import kotlinx.datetime.toLocalDateTime

/**
 * CR-010 W-01..W-03: Glance summary widget, responsive between the launcher's 2x2 and 4x2 cells.
 *
 * One bank per widget. The snapshot itself is owner/month scoped and contains counts only, so a
 * different bank/period or a signed-out account is shown as loading rather than as stale data.
 */
@SuppressLint("RestrictedApi")
class PamSummaryWidget : GlanceAppWidget() {

    override val sizeMode: SizeMode = SizeMode.Responsive(
        setOf(
            DpSize(144.dp, 144.dp), // 2x2
            DpSize(250.dp, 124.dp), // 4x2
            DpSize(320.dp, 140.dp), // wider launchers
        ),
    )

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val entryPoint = EntryPointAccessors.fromApplication(
            context.applicationContext,
            WidgetEntryPoint::class.java,
        )
        val session = entryPoint.sessions().current()
        val currentMonth = SystemClockProvider().currentMonth()
        val appWidgetId = (id as? AppWidgetId)?.appWidgetId
            ?: AppWidgetManager.INVALID_APPWIDGET_ID
        val bank = entryPoint.store().widgetBank(appWidgetId)
        val snapshot = entryPoint.store().read()
        val state = resolve(session?.ownerId, bank, currentMonth.id(), snapshot)
        val strings = WidgetStrings(
            bank = bank.displayName,
            month = monthLabel(currentMonth),
            loggedOut = context.getString(R.string.widget_logged_out),
            loading = context.getString(R.string.widget_loading),
            empty = context.getString(R.string.widget_empty),
            error = context.getString(R.string.widget_error),
            transactions = context.getString(R.string.widget_transactions),
            updated = { context.getString(R.string.widget_updated, it) },
        )

        provideContent {
            val size = LocalSize.current
            val content = WidgetContent(
                context = context,
                state = state,
                strings = strings,
                bank = bank,
                monthId = currentMonth.id(),
                compact = size.width < 220.dp,
            )
            content
        }
    }

    private fun resolve(
        ownerId: String?,
        bank: WidgetBank,
        currentMonthId: String,
        snapshot: WidgetSnapshot?,
    ): SummaryState {
        if (ownerId == null) return SummaryState.LoggedOut
        if (!bank.isAvailable) return SummaryState.Loading
        if (snapshot == null || snapshot.ownerId != ownerId) return SummaryState.Loading
        if (snapshot.monthId != currentMonthId) return SummaryState.Loading
        return when (snapshot.status) {
            WidgetSnapshotStatus.LOADING -> SummaryState.Loading
            WidgetSnapshotStatus.ERROR -> SummaryState.Error
            WidgetSnapshotStatus.EMPTY -> SummaryState.Empty
            WidgetSnapshotStatus.OFFLINE -> {
                val count = snapshot.transactionCount
                if (count == null) {
                    SummaryState.Error
                } else {
                    SummaryState.Offline(
                        count = count,
                        monthLabel = monthLabel(snapshot.monthId),
                        updatedLabel = snapshot.updatedAtEpochMs?.let(::updatedAtLabel),
                    )
                }
            }
            WidgetSnapshotStatus.AVAILABLE -> {
                val count = snapshot.transactionCount
                if (count == null) {
                    SummaryState.Error
                } else {
                    SummaryState.Available(count = count, monthLabel = monthLabel(snapshot.monthId))
                }
            }
        }
    }

    private fun monthLabel(month: JakartaMonth): String = month.label(Locale.forLanguageTag("id-ID"))

    private fun monthLabel(monthId: String): String =
        JakartaMonth.parse(monthId)?.let(::monthLabel) ?: monthId

    private fun updatedAtLabel(epochMs: Long): String {
        val local = Instant.fromEpochMilliseconds(epochMs)
            .toLocalDateTime(JakartaMonth.ZONE)
        return "%02d.%02d".format(local.hour, local.minute)
    }

    private data class WidgetStrings(
        val bank: String,
        val month: String,
        val loggedOut: String,
        val loading: String,
        val empty: String,
        val error: String,
        val transactions: String,
        val updated: (String) -> String,
    )

    private sealed interface SummaryState {
        data object LoggedOut : SummaryState
        data object Loading : SummaryState
        data object Empty : SummaryState
        data object Error : SummaryState
        data class Available(val count: Int, val monthLabel: String) : SummaryState
        data class Offline(
            val count: Int,
            val monthLabel: String,
            val updatedLabel: String?,
        ) : SummaryState
    }

    @Composable
    private fun WidgetContent(
        context: Context,
        state: SummaryState,
        strings: WidgetStrings,
        bank: WidgetBank,
        monthId: String,
        compact: Boolean,
    ) {
        val openIntent = android.content.Intent(context, com.pam.recap.MainActivity::class.java).apply {
            action = WidgetIntents.ACTION_OPEN_OVERVIEW
            putExtra(WidgetIntents.EXTRA_BANK, bank.id)
            putExtra(WidgetIntents.EXTRA_MONTH, monthId)
            flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val description = when (state) {
            SummaryState.LoggedOut -> strings.loggedOut
            SummaryState.Loading -> strings.loading
            SummaryState.Empty -> "${strings.bank} · ${strings.month} · ${strings.empty}"
            SummaryState.Error -> strings.error
            is SummaryState.Available ->
                "${strings.bank} · ${state.monthLabel} · ${state.count} ${strings.transactions}"
            is SummaryState.Offline ->
                "${strings.bank} · ${state.monthLabel} · ${state.count} ${strings.transactions}"
        }
        Box(
            modifier = GlanceModifier.fillMaxSize()
                .background(ColorProvider(Canvas))
                .cornerRadius(22.dp)
                .padding(if (compact) 14.dp else 16.dp)
                .semantics { contentDescription = description }
                .clickable(actionStartActivity(openIntent)),
        ) {
            Column(modifier = GlanceModifier.fillMaxSize()) {
                Box(modifier = GlanceModifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.Vertical.CenterVertically) {
                        Box(
                            modifier = GlanceModifier.size(8.dp)
                                .background(ColorProvider(Lime))
                                .cornerRadius(4.dp),
                        ) {}
                        Spacer(modifier = GlanceModifier.width(7.dp))
                        Text(
                            text = strings.bank,
                            style = TextStyle(
                                color = ColorProvider(OnInk),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                            ),
                            maxLines = 1,
                        )
                    }
                    if (!compact) {
                        Row(
                            modifier = GlanceModifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.Horizontal.End,
                        ) {
                            Text(
                                text = (state as? SummaryState.Available)?.monthLabel
                                    ?: (state as? SummaryState.Offline)?.monthLabel
                                    ?: strings.month,
                                style = TextStyle(
                                    color = ColorProvider(OnInkMuted),
                                    fontSize = 11.sp,
                                ),
                                maxLines = 1,
                            )
                        }
                    }
                }
                Spacer(modifier = GlanceModifier.height(6.dp))
                if (compact && (state is SummaryState.Available || state is SummaryState.Offline)) {
                    Text(
                        text = (state as? SummaryState.Available)?.monthLabel
                            ?: (state as? SummaryState.Offline)?.monthLabel.orEmpty(),
                        style = TextStyle(color = ColorProvider(OnInkMuted), fontSize = 10.sp),
                        maxLines = 1,
                    )
                    Spacer(modifier = GlanceModifier.height(2.dp))
                }
                Box(modifier = GlanceModifier.fillMaxWidth().height(if (compact) 64.dp else 54.dp)) {
                    Column(
                        modifier = GlanceModifier.fillMaxSize(),
                        verticalAlignment = Alignment.Vertical.CenterVertically,
                    ) {
                        when (state) {
                            SummaryState.LoggedOut -> StateCopy(strings.loggedOut)
                            SummaryState.Loading -> StateCopy(strings.loading)
                            SummaryState.Empty -> StateCopy(strings.empty)
                            SummaryState.Error -> StateCopy(strings.error, emphasis = true)
                            is SummaryState.Available -> CountCopy(
                                count = state.count,
                                label = strings.transactions,
                            )
                            is SummaryState.Offline -> {
                                CountCopy(count = state.count, label = strings.transactions)
                                if (state.updatedLabel != null) {
                                    Spacer(modifier = GlanceModifier.height(3.dp))
                                    Text(
                                        text = strings.updated(state.updatedLabel),
                                        style = TextStyle(
                                            color = ColorProvider(OnInkMuted),
                                            fontSize = 10.sp,
                                        ),
                                        maxLines = 1,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun StateCopy(text: String, emphasis: Boolean = false) {
        Text(
            text = text,
            style = TextStyle(
                color = ColorProvider(if (emphasis) OnInk else OnInkMuted),
                fontSize = 13.sp,
                fontWeight = if (emphasis) FontWeight.Medium else FontWeight.Normal,
            ),
            maxLines = 2,
        )
    }

    @Composable
    private fun CountCopy(count: Int, label: String) {
        Row(verticalAlignment = Alignment.Vertical.Bottom) {
            Text(
                text = count.toString(),
                style = TextStyle(
                    color = ColorProvider(OnInk),
                    fontSize = 30.sp,
                    fontWeight = FontWeight.Bold,
                ),
                maxLines = 1,
            )
            Spacer(modifier = GlanceModifier.width(6.dp))
            Text(
                text = label,
                style = TextStyle(color = ColorProvider(OnInkMuted), fontSize = 12.sp),
                maxLines = 1,
            )
        }
    }

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface WidgetEntryPoint {
        fun sessions(): SessionRepository
        fun store(): WidgetSnapshotStore
    }
}

/** Small indirection so the receiver/updater do not duplicate manager wiring. */
@Singleton
class PamWidgetRefresher @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: Context,
) : com.pam.recap.data.widget.WidgetRefresher {
    override suspend fun refreshAll() {
        val manager = GlanceAppWidgetManager(context)
        val ids = manager.getGlanceIds(PamSummaryWidget::class.java)
        ids.forEach { id -> PamSummaryWidget().update(context, id) }
    }
}

/** Widget tap contract; MainActivity validates the session before emitting a nav target. */
object WidgetIntents {
    const val ACTION_OPEN_OVERVIEW = "com.pam.recap.action.OPEN_WIDGET_OVERVIEW"
    const val EXTRA_BANK = "com.pam.recap.extra.WIDGET_BANK"
    const val EXTRA_MONTH = "com.pam.recap.extra.WIDGET_MONTH"
}
