package com.pam.recap.ui.feature.status

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.pam.recap.R
import com.pam.recap.ui.components.SheetScaffold
import com.pam.recap.ui.theme.BodyM
import com.pam.recap.ui.theme.BodyS
import com.pam.recap.ui.theme.DangerRed
import com.pam.recap.ui.theme.LabelEyebrow
import com.pam.recap.ui.theme.OnInk
import com.pam.recap.ui.theme.OnInkMuted
import com.pam.recap.ui.theme.PamSpacing
import com.pam.recap.ui.theme.TitleL
import com.pam.recap.ui.util.PamDateFormat
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
) {
    SheetScaffold(onDismissRequest = onDismiss) {
        Text(text = stringResource(R.string.update_status_title), style = TitleL, color = OnInk)
        Spacer(modifier = Modifier.height(PamSpacing.sm))

        Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
            StatusSection(title = stringResource(R.string.update_status_section_data)) {
                StatusLine(
                    label = stringResource(R.string.update_status_last_attempt),
                    value = relative(status.lastAttemptAt, now),
                )
                // Deliberately a different line: the last attempt is not the last successful sync.
                StatusLine(
                    label = stringResource(R.string.update_status_last_success),
                    value = relative(status.lastSuccessAt, now),
                )
                if (status.pendingUpload) {
                    BodyLine(stringResource(R.string.update_status_pending_upload))
                }
                BodyLine(
                    if (status.newTransactionsLastRun > 0) {
                        stringResource(R.string.update_status_new_transactions, status.newTransactionsLastRun)
                    } else {
                        stringResource(R.string.update_status_no_new_transactions)
                    },
                )
                if (status.hasRunCounts) {
                    BodyLine(
                        stringResource(
                            R.string.update_status_run_counts,
                            status.savedCount,
                            status.duplicateCount,
                            status.skippedCount,
                            status.unresolvedCount,
                        ),
                    )
                }
            }

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
                if (status.attention.isEmpty()) {
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
        "unresolved_items" -> R.string.update_status_reason_unresolved
        else -> R.string.update_status_reason_unknown
    },
)

private fun AttentionItem.Scope.labelRes(): Int = when (this) {
    AttentionItem.Scope.TRANSACTIONS -> R.string.update_status_attention_transactions
    AttentionItem.Scope.CATEGORIES -> R.string.update_status_attention_categories
}
