package com.pam.recap.ui.feature.status

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.pam.recap.R
import com.pam.recap.core.time.RetentionWindow
import com.pam.recap.domain.model.CategoryQueuePhase
import com.pam.recap.domain.model.GmailSyncState
import com.pam.recap.ui.components.CircularIconWell
import com.pam.recap.ui.components.PillButton
import com.pam.recap.ui.components.PillButtonStyle
import com.pam.recap.ui.components.SheetScaffold
import com.pam.recap.ui.theme.BodyM
import com.pam.recap.ui.theme.BodyS
import com.pam.recap.ui.theme.DangerRed
import com.pam.recap.ui.theme.GlassBorder
import com.pam.recap.ui.theme.LabelEyebrow
import com.pam.recap.ui.theme.OnInk
import com.pam.recap.ui.theme.OnInkMuted
import com.pam.recap.ui.theme.PamRadius
import com.pam.recap.ui.theme.PamSpacing
import com.pam.recap.ui.theme.SurfaceLow
import com.pam.recap.ui.theme.TitleL
import com.pam.recap.ui.theme.TitleM
import com.pam.recap.ui.theme.Violet
import com.pam.recap.ui.util.PamDateFormat
import com.pam.recap.ui.util.appErrorRes
import kotlin.time.Instant

/**
 * "Status pembaruan" sheet.
 *
 * Three sections, each answering a different question: what happened to the transactions, what is
 * happening to the categories, and what needs the user. It renders whatever the snapshot says and
 * never triggers work, so opening and closing it is free.
 */
@Composable
fun UpdateStatusSheet(
    status: UpdateStatusUi,
    now: Instant,
    onDismiss: () -> Unit,
    onSyncNow: () -> Unit = {},
    /** The rolling window the served period belongs to; null keeps the sheet silent about it. */
    retention: RetentionWindow? = null,
    /**
     * Server-side Gmail projection (contract §9). While Gmail is linked this replaces the legacy
     * device-sync trigger; when it is [GmailServerSyncUi.Hidden] the old behaviour is untouched.
     */
    serverSync: GmailServerSyncUi = GmailServerSyncUi(),
    onServerSyncNow: () -> Unit = {},
    /** "Tautkan ulang Gmail" goes to the existing Gmail link screen; the sheet owns no OAuth. */
    onReconnectGmail: () -> Unit = {},
) {
    SheetScaffold(onDismissRequest = onDismiss) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(PamSpacing.xs),
        ) {
            Text(
                text = stringResource(R.string.update_status_title),
                style = TitleL,
                color = OnInk,
                modifier = Modifier.weight(1f),
            )
            // Closing the sheet only hides it: the run belongs to the coordinator and keeps going.
            CircularIconWell(
                icon = Icons.Rounded.Close,
                contentDescription = stringResource(R.string.action_close),
                onClick = onDismiss,
            )
        }
        Spacer(modifier = Modifier.height(PamSpacing.sm))

        Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
            SyncStatusCard(
                status = status,
                serverSync = serverSync,
                now = now,
                retention = retention,
                onSyncNow = onSyncNow,
                onServerSyncNow = onServerSyncNow,
                onReconnectGmail = onReconnectGmail,
            )

            StatusSection(title = stringResource(R.string.update_status_section_categories)) {
                BodyLine(
                    if (status.categoryPending > 0) {
                        stringResource(R.string.update_status_category_pending, status.categoryPending)
                    } else {
                        stringResource(R.string.update_status_category_none)
                    },
                )
                status.categoryNextDueAt?.let {
                    BodyLine(stringResource(R.string.update_status_category_next_due, PamDateFormat.time(it)))
                }
                if (status.categoryLastDrained > 0) {
                    BodyLine(stringResource(R.string.update_status_category_drained, status.categoryLastDrained))
                }
            }

            StatusSection(title = stringResource(R.string.update_status_section_attention)) {
                if (!status.online) {
                    BodyLine(stringResource(R.string.update_status_offline_body))
                }
                if (status.attention.isEmpty() && status.syncError == null) {
                    BodyLine(stringResource(R.string.update_status_no_attention))
                } else {
                    status.attention.forEach { item ->
                        AttentionLine(item)
                    }
                }
            }

            Spacer(modifier = Modifier.height(PamSpacing.sm))
        }
    }
}

/**
 * The one status surface of the sheet: current phase, real progress, the last *attempt* versus the
 * last *successful* sync, the last run's outcome counters, and exactly one action.
 *
 * Progress is drawn as a determinate bar only when the source reported a total; otherwise the bar
 * is indeterminate and the copy states the phase. No percentage is ever invented.
 */
@Composable
private fun SyncStatusCard(
    status: UpdateStatusUi,
    serverSync: GmailServerSyncUi,
    now: Instant,
    retention: RetentionWindow?,
    onSyncNow: () -> Unit,
    onServerSyncNow: () -> Unit,
    onReconnectGmail: () -> Unit,
) {
    val shape = RoundedCornerShape(PamRadius.lg)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(SurfaceLow)
            .border(1.dp, GlassBorder, shape)
            .padding(PamSpacing.md),
        verticalArrangement = Arrangement.spacedBy(PamSpacing.xxs),
    ) {
        Text(
            text = stringResource(
                if (serverSync.available) {
                    R.string.update_status_server_section
                } else {
                    R.string.update_status_section_data
                },
            ),
            style = LabelEyebrow,
            color = OnInkMuted,
            modifier = Modifier.semantics { heading() },
        )

        status.month?.let {
            BodyLine(stringResource(R.string.update_status_sync_period, it.label()))
        }
        retention?.let { window ->
            BodyLine(
                stringResource(
                    R.string.update_status_retention_window,
                    window.oldest.label(),
                    window.newest.label(),
                ),
            )
            if (window.oldestPartial) {
                BodyLine(stringResource(R.string.update_status_retention_partial, window.oldest.label()))
            }
        }

        if (serverSync.available) {
            ServerSyncBody(
                state = serverSync,
                fallbackNow = now,
                onSyncNow = onServerSyncNow,
                onReconnectGmail = onReconnectGmail,
            )
        } else {
            DeviceSyncBody(status = status, now = now, onSyncNow = onSyncNow)
        }
    }
}

@Composable
private fun ServerSyncBody(
    state: GmailServerSyncUi,
    fallbackNow: Instant,
    onSyncNow: () -> Unit,
    onReconnectGmail: () -> Unit,
) {
    if (state.needsReauth) {
        BodyLine(stringResource(R.string.update_status_server_reauth))
        PillButton(
            text = stringResource(R.string.update_status_server_reconnect),
            onClick = onReconnectGmail,
            modifier = Modifier.fillMaxWidth().padding(top = PamSpacing.xs),
            leadingIcon = Icons.Rounded.Link,
            style = PillButtonStyle.Primary,
        )
        return
    }

    val now = state.observedAt ?: fallbackNow
    Text(
        text = serverStateCopy(state, now),
        style = TitleM,
        color = OnInk,
        modifier = Modifier.padding(top = PamSpacing.xxs),
    )

    // A bar only appears while something is actually happening.
    if (state.running || state.busy || state.connecting(now) || state.loading) {
        val monthsTotal = state.progress?.monthsTotal ?: 0
        if (monthsTotal > 0) {
            val fraction = (state.progress?.monthsDone ?: 0).toFloat() / monthsTotal.toFloat()
            LinearProgressIndicator(
                progress = { fraction.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().padding(vertical = PamSpacing.xxs),
                color = Violet,
            )
        } else {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth().padding(vertical = PamSpacing.xxs),
                color = Violet,
            )
        }
    }
    serverProgressLines(state)
    StatusLine(
        label = stringResource(R.string.update_status_last_attempt),
        value = relative(state.lastAttemptAt, now),
    )
    // Separate line on purpose: the last attempt is not the last successful sync.
    StatusLine(
        label = stringResource(R.string.update_status_server_last_success),
        value = relative(state.lastSuccessAt, now),
    )
    state.nextSyncAt?.let {
        BodyLine(stringResource(R.string.update_status_server_next_sync, PamDateFormat.time(it)))
    }
    when (state.notice) {
        GmailSyncNotice.QUEUED -> BodyLine(stringResource(R.string.update_status_server_queued))
        GmailSyncNotice.COALESCED -> BodyLine(stringResource(R.string.update_status_server_coalesced))
        GmailSyncNotice.COOLDOWN -> BodyLine(cooldownCopy(state, now))
        GmailSyncNotice.NOT_CONNECTED -> BodyLine(stringResource(R.string.update_status_server_not_connected))
        GmailSyncNotice.DISABLED -> BodyLine(stringResource(R.string.update_status_server_disabled))
        GmailSyncNotice.FAILED,
        GmailSyncNotice.NONE,
        -> state.error?.let { BodyLine(stringResource(appErrorRes(it))) }
    }

    val cooling = state.coolingDown(now)
    // One action for every state: queued/running disables it, a failure reuses the same button.
    PillButton(
        text = if (state.running || state.busy) {
            stringResource(R.string.update_status_sync_running)
        } else {
            stringResource(R.string.update_status_sync_now)
        },
        onClick = onSyncNow,
        enabled = !cooling && !state.running && !state.busy,
        loading = state.busy,
        modifier = Modifier.fillMaxWidth().padding(top = PamSpacing.sm),
        leadingIcon = if (state.running || state.busy) null else Icons.Rounded.Refresh,
        style = PillButtonStyle.Primary,
    )
}

/**
 * Device-sync body: same card, same single action. A real page total yields a determinate bar; an
 * indeterminate pipeline gets the indeterminate bar plus the phase it actually reported.
 */
@Composable
private fun DeviceSyncBody(status: UpdateStatusUi, now: Instant, onSyncNow: () -> Unit) {
    Text(
        text = if (status.syncRunning || status.isActive || status.syncError != null) {
            stageLine(status)
        } else if (status.syncError != null) {
            stringResource(R.string.update_status_indicator_attention)
        } else if (status.hasEverSucceeded) {
            stringResource(R.string.update_status_indicator_succeeded)
        } else {
            stringResource(R.string.update_status_indicator_idle)
        },
        style = TitleM,
        color = OnInk,
        modifier = Modifier.padding(top = PamSpacing.xxs),
    )

    if (status.syncRunning || status.isActive) {
        if (status.hasDefiniteProgress) {
            val expected = status.pagesExpected ?: 0
            val fraction = (status.pagesProcessed.toFloat() / expected.toFloat()).coerceIn(0f, 1f)
            LinearProgressIndicator(
                progress = { fraction },
                modifier = Modifier.fillMaxWidth().padding(vertical = PamSpacing.xxs),
                color = Violet,
            )
            BodyLine(stringResource(R.string.update_status_sync_progress, status.pagesProcessed, expected))
        } else {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth().padding(vertical = PamSpacing.xxs),
                color = Violet,
            )
        }
    }

    // Deliberately different: the last attempt is not the last successful sync.
    StatusLine(
        label = stringResource(R.string.update_status_last_attempt),
        value = relative(status.lastAttemptAt, now),
    )
    StatusLine(
        label = stringResource(R.string.update_status_last_success),
        value = relative(status.lastSuccessAt, now),
    )

    if (status.pendingUpload) {
        BodyLine(stringResource(R.string.update_status_pending_upload))
    }
    if (status.partial) {
        BodyLine(stringResource(R.string.update_status_reason_partial))
    }
    if (status.newTransactionsLastRun > 0) {
        BodyLine(
            stringResource(R.string.update_status_new_transactions, status.newTransactionsLastRun),
        )
    } else if (status.hasEverSucceeded && !status.syncBusy) {
        BodyLine(stringResource(R.string.update_status_no_new_transactions))
    }
    status.lastJob?.let { job ->
        BodyLine(stringResource(R.string.update_status_matched, job.matchedCount))
        // Always print the outcome counters, even when all are zero: "nothing was saved" is a result
        // the owner must be able to read, not an empty state.
        BodyLine(
            stringResource(
                R.string.update_status_run_counts,
                job.importedCount,
                job.duplicateCount,
                job.skippedCount,
                job.unresolvedCount,
            ),
        )
    }
    if (status.syncConflict) {
        BodyLine(stringResource(R.string.update_status_sync_conflict))
    }
    status.syncError?.let { error ->
        BodyLine(stringResource(appErrorRes(error)))
    }

    PillButton(
        text = when {
            status.syncBusy -> stringResource(R.string.update_status_sync_running)
            status.syncError != null -> stringResource(R.string.update_status_sync_retry)
            else -> stringResource(R.string.update_status_sync_now)
        },
        onClick = onSyncNow,
        enabled = !status.syncBusy,
        loading = status.syncRunning || status.isActive,
        modifier = Modifier.fillMaxWidth().padding(top = PamSpacing.sm),
        leadingIcon = if (status.syncBusy) null else Icons.Rounded.Refresh,
        style = if (status.syncError != null) PillButtonStyle.Primary else PillButtonStyle.Secondary,
    )
}

/** One-line state copy: the worker's phase first, then the pending request, then error/idle. */
@Composable
private fun serverStateCopy(state: GmailServerSyncUi, now: Instant): String = when {
    state.running -> stringResource(R.string.update_status_server_state_fetching)
    state.connecting(now) -> stringResource(R.string.update_status_server_state_connecting)
    state.syncState == GmailSyncState.ERROR -> stringResource(R.string.update_status_server_state_error)
    state.loading -> stringResource(R.string.update_status_server_loading)
    else -> stringResource(R.string.update_status_server_state_waiting)
}

/** Month/page progress; an idle projection without counters says so instead of showing zeroes. */
@Composable
private fun serverProgressLines(state: GmailServerSyncUi) {
    val progress = state.progress ?: return
    if (!progress.hasMonths && progress.pagesDone == 0) return
    if (progress.hasMonths) {
        BodyLine(
            stringResource(
                R.string.update_status_server_progress_months,
                progress.monthsDone.coerceIn(0, progress.monthsTotal),
                progress.monthsTotal,
            ),
        )
    }
    progress.month?.let {
        BodyLine(stringResource(R.string.update_status_server_progress_month, it.label()))
    }
    if (progress.pagesDone > 0) {
        BodyLine(stringResource(R.string.update_status_server_progress_pages, progress.pagesDone))
    }
}

@Composable
private fun cooldownCopy(state: GmailServerSyncUi, now: Instant): String {
    val seconds = state.cooldownSeconds(now)
    return if (seconds != null && seconds > 0) {
        stringResource(R.string.update_status_server_cooldown, seconds)
    } else {
        stringResource(R.string.update_status_server_cooldown_generic)
    }
}

/** Stage copy comes from the pipeline's own phase, not from a timer or an optimistic guess. */
@Composable
private fun stageLine(status: UpdateStatusUi): String = when {
    !status.online -> stringResource(R.string.update_status_stage_offline)
    status.pendingUpload -> stringResource(R.string.update_status_stage_upload)
    status.categoryPhase == CategoryQueuePhase.RUNNING ->
        stringResource(R.string.update_status_stage_categorizing)
    else -> stringResource(R.string.update_status_stage_checking)
}

@Composable
private fun StatusSection(title: String, content: @Composable () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = PamSpacing.sm)) {
        Text(
            text = title,
            style = LabelEyebrow,
            color = OnInkMuted,
            modifier = Modifier.semantics { heading() },
        )
        Spacer(modifier = Modifier.height(PamSpacing.xs))
        content()
        Spacer(modifier = Modifier.height(PamSpacing.xs))
    }
}

@Composable
private fun StatusLine(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = label, style = BodyM, color = OnInkMuted)
        Text(text = value, style = BodyM, color = OnInk)
    }
}

@Composable
private fun BodyLine(text: String) {
    Text(
        text = text,
        style = BodyS,
        color = OnInkMuted,
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
    )
}

@Composable
private fun AttentionLine(item: AttentionItem) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(PamSpacing.xs),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            imageVector = Icons.Rounded.ErrorOutline,
            contentDescription = null,
            tint = DangerRed,
            modifier = Modifier.size(16.dp),
        )
        Text(
            text = stringResource(item.scope.labelRes(), reasonFor(item.code)),
            style = BodyS,
            color = OnInk,
        )
    }
}

@Composable
private fun relative(instant: Instant?, now: Instant): String = when (val age = RelativeTime.of(instant, now)) {
    RelativeTime.Never -> stringResource(R.string.update_status_never)
    RelativeTime.JustNow -> stringResource(R.string.update_status_now)
    is RelativeTime.Minutes -> stringResource(R.string.update_status_minutes_ago, age.value)
    is RelativeTime.Hours -> stringResource(R.string.update_status_hours_ago, age.value)
    is RelativeTime.Days -> stringResource(R.string.update_status_days_ago, age.value)
    is RelativeTime.On -> age.label
}

/** Maps a machine code to safe copy; an unknown code still gets an honest generic sentence. */
@Composable
private fun reasonFor(code: String): String = stringResource(
    when (code) {
        "unauthorized" -> R.string.update_status_reason_unauthorized
        "permission_required" -> R.string.update_status_reason_permission
        "configuration" -> R.string.update_status_reason_configuration
        "network", "network_unavailable" -> R.string.update_status_reason_network
        "timeout" -> R.string.update_status_reason_timeout
        "budget_exhausted" -> R.string.update_status_reason_budget
        "rate_limited" -> R.string.update_status_reason_rate_limited
        "partial_import" -> R.string.update_status_reason_partial
        "storage_unavailable" -> R.string.update_status_reason_storage
        "pending_snapshot_incomplete" -> R.string.update_status_reason_pending_snapshot
        "unresolved_items" -> R.string.update_status_reason_unresolved
        else -> R.string.update_status_reason_unknown
    },
)

private fun AttentionItem.Scope.labelRes(): Int = when (this) {
    AttentionItem.Scope.TRANSACTIONS -> R.string.update_status_attention_transactions
    AttentionItem.Scope.CATEGORIES -> R.string.update_status_attention_categories
}
