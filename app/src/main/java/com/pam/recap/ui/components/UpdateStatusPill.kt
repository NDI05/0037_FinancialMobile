package com.pam.recap.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.CloudDone
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pam.recap.R
import com.pam.recap.ui.feature.status.UpdateHeadline
import com.pam.recap.ui.feature.status.UpdateStatusUi
import com.pam.recap.ui.theme.DangerRed
import com.pam.recap.ui.theme.IncomeMint
import com.pam.recap.ui.theme.LabelM
import com.pam.recap.ui.theme.LocalReducedMotion
import com.pam.recap.ui.theme.OnInkMuted
import com.pam.recap.ui.theme.PamRadius
import com.pam.recap.ui.theme.PamSize
import com.pam.recap.ui.theme.PamSpacing
import com.pam.recap.ui.theme.Violet
import com.pam.recap.ui.theme.WarningAmber
import kotlinx.coroutines.delay

/** How long the transient status word stays on screen after a change (2.5 s, inside the 2–3 s spec). */
private const val LABEL_HOLD_MILLIS = 2_500L

/**
 * Compact automatic-update indicator for a glass top bar.
 *
 * The icon is always present and is the whole >= 48 dp touch target; a short word fades in next to
 * it only when the status actually changes, holds ~2.5 s, then fades out while the icon stays. The
 * animation is keyed to the status token, so recomposition (scroll, font change, sheet open) never
 * replays it. The accessible name always carries the current status, independent of the visual fade.
 */
@Composable
fun UpdateStatusPill(
    status: UpdateStatusUi,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val reducedMotion = LocalReducedMotion.current
    // At very large text the transient word no longer fits next to the screen title; the icon keeps
    // the state visible and the content description keeps it audible.
    val showLabel = LocalDensity.current.fontScale < 1.5f && !reducedMotion
    val label = stringResource(status.labelRes())
    val spoken = spokenStatus(status, label)
    val tint = status.headline.tint()
    val token = "${status.headline}:${status.syncAttempt}:${status.attemptToken()}"

    var labelVisible by remember { mutableStateOf(false) }
    LaunchedEffect(token) {
        if (!showLabel) return@LaunchedEffect
        labelVisible = true
        delay(LABEL_HOLD_MILLIS)
        labelVisible = false
    }

    Row(
        modifier = modifier
            .sizeIn(minHeight = PamSize.minTouch, minWidth = PamSize.minTouch)
            .clip(RoundedCornerShape(PamRadius.pill))
            .clickable(onClick = onClick)
            .semantics {
                contentDescription = spoken
                role = Role.Button
            }
            .padding(horizontal = PamSpacing.xs, vertical = PamSpacing.xxs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(PamSpacing.xxs),
    ) {
        AnimatedVisibility(
            visible = labelVisible,
            enter = fadeIn(tween(220)),
            exit = fadeOut(tween(420)),
        ) {
            Text(
                text = label,
                style = LabelM,
                color = tint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (status.isActive && !reducedMotion) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp,
                color = tint,
            )
        } else {
            Icon(
                imageVector = status.headline.staticIcon(),
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/** The accessible name is a full sentence even after the visual word has faded. */
@Composable
private fun spokenStatus(status: UpdateStatusUi, label: String): String {
    val open = stringResource(R.string.update_status_open)
    val details = buildList {
        if (!status.online) add(stringResource(R.string.update_status_indicator_offline))
        if (status.pendingUpload) add(stringResource(R.string.update_status_cd_pending))
        if (status.needsAttention) {
            add(
                stringResource(
                    if (status.needsReview) {
                        R.string.update_status_cd_review
                    } else {
                        R.string.update_status_cd_attention
                    },
                ),
            )
        }
    }
    return if (details.isEmpty()) "$open: $label" else "$open: $label. ${details.joinToString(". ")}"
}

/** One value that changes when the status meaningfully changes; used only as an animation key. */
private fun UpdateStatusUi.attemptToken(): Int =
    headline.ordinal * 31 + (if (pendingUpload) 1 else 0) + (if (online) 0 else 2)

/**
 * A review backlog is a different short label from a failed run, so the word above the icon never
 * claims an error when the data is merely waiting for a human.
 */
private fun UpdateStatusUi.labelRes(): Int = when {
    headline == UpdateHeadline.NEEDS_ATTENTION && needsReview -> R.string.update_status_indicator_review
    else -> headline.labelRes()
}

private fun UpdateHeadline.labelRes(): Int = when (this) {
    UpdateHeadline.NEEDS_ATTENTION -> R.string.update_status_indicator_attention
    UpdateHeadline.CHECKING -> R.string.update_status_indicator_checking
    UpdateHeadline.FETCHING -> R.string.update_status_indicator_fetching
    UpdateHeadline.SAVING -> R.string.update_status_indicator_saving
    UpdateHeadline.CATEGORIZING -> R.string.update_status_indicator_categorizing
    UpdateHeadline.WAITING -> R.string.update_status_indicator_waiting
    UpdateHeadline.OFFLINE -> R.string.update_status_indicator_offline
    UpdateHeadline.SUCCEEDED -> R.string.update_status_indicator_succeeded
    UpdateHeadline.IDLE -> R.string.update_status_indicator_idle
}

private fun UpdateHeadline.staticIcon(): ImageVector = when (this) {
    UpdateHeadline.NEEDS_ATTENTION -> Icons.Rounded.ErrorOutline
    UpdateHeadline.CHECKING, UpdateHeadline.FETCHING -> Icons.Rounded.Cloud
    UpdateHeadline.SAVING -> Icons.Rounded.Cloud
    UpdateHeadline.CATEGORIZING -> Icons.Rounded.Schedule
    UpdateHeadline.WAITING -> Icons.Rounded.Schedule
    UpdateHeadline.OFFLINE -> Icons.Rounded.CloudOff
    UpdateHeadline.SUCCEEDED -> Icons.Rounded.CloudDone
    UpdateHeadline.IDLE -> Icons.Rounded.Cloud
}

private fun UpdateHeadline.tint(): Color = when (this) {
    UpdateHeadline.NEEDS_ATTENTION -> DangerRed
    UpdateHeadline.OFFLINE -> WarningAmber
    UpdateHeadline.SUCCEEDED -> IncomeMint
    UpdateHeadline.CHECKING, UpdateHeadline.FETCHING, UpdateHeadline.SAVING,
    UpdateHeadline.CATEGORIZING, UpdateHeadline.WAITING,
    -> Violet
    UpdateHeadline.IDLE -> OnInkMuted
}
