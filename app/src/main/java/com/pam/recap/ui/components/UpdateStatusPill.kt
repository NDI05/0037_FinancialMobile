package com.pam.recap.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Sync
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
import com.pam.recap.ui.theme.LabelEyebrow
import com.pam.recap.ui.theme.LocalReducedMotion
import com.pam.recap.ui.theme.OnInk
import com.pam.recap.ui.theme.PamRadius
import com.pam.recap.ui.theme.PamSize
import com.pam.recap.ui.theme.PamSpacing
import com.pam.recap.ui.theme.SurfaceContainer
import com.pam.recap.ui.theme.WarningAmber
import kotlinx.coroutines.delay

/** How long the transient status word stays on screen after a change (2.2 s, per Stitch). */
private const val LABEL_HOLD_MILLIS = 2_200L

/**
 * Stitch automatic-update indicator for the top bar:
 * - On normal/idle states (`IDLE`, `WAITING`), the `sync` icon stands alone with no word pushing
 *   or overlapping the centred screen title.
 * - When an important status occurs or transitions (`CHECKING`/`FETCHING`/`SAVING`/`CATEGORIZING`,
 *   `SUCCEEDED`, `NEEDS_ATTENTION`, `OFFLINE`), a compact pill fades in briefly next to the icon
 *   and fades back out without ever shifting the centred title.
 */
@Composable
fun UpdateStatusPill(
    status: UpdateStatusUi,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val reducedMotion = LocalReducedMotion.current
    val showLabel = LocalDensity.current.fontScale < 1.5f && !reducedMotion
    val label = stringResource(status.labelRes())
    val spoken = spokenStatus(status, label)
    val tint = status.headline.tint()
    val important = status.headline != UpdateHeadline.IDLE && status.headline != UpdateHeadline.WAITING
    val token = "${status.headline}:${status.syncAttempt}:${status.attemptToken()}"

    var labelVisible by remember { mutableStateOf(false) }
    LaunchedEffect(token) {
        if (!showLabel || !important) {
            labelVisible = false
            return@LaunchedEffect
        }
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
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        AnimatedVisibility(
            visible = labelVisible,
            enter = fadeIn(tween(200)),
            exit = fadeOut(tween(200)),
        ) {
            Text(
                text = label,
                style = LabelEyebrow,
                color = OnInk,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .widthIn(max = 104.dp)
                    .clip(RoundedCornerShape(PamRadius.pill))
                    .background(SurfaceContainer)
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
        if (status.isActive && !reducedMotion) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
                color = tint,
            )
        } else {
            Icon(
                imageVector = status.headline.staticIcon(),
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(22.dp),
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
    UpdateHeadline.CHECKING, UpdateHeadline.FETCHING, UpdateHeadline.SAVING,
    UpdateHeadline.CATEGORIZING, UpdateHeadline.WAITING, UpdateHeadline.IDLE,
    -> Icons.Rounded.Sync
    UpdateHeadline.OFFLINE -> Icons.Rounded.CloudOff
    UpdateHeadline.SUCCEEDED -> Icons.Rounded.CheckCircle
}

private fun UpdateHeadline.tint(): Color = when (this) {
    UpdateHeadline.NEEDS_ATTENTION -> DangerRed
    UpdateHeadline.OFFLINE -> WarningAmber
    UpdateHeadline.SUCCEEDED -> IncomeMint
    UpdateHeadline.CHECKING, UpdateHeadline.FETCHING, UpdateHeadline.SAVING,
    UpdateHeadline.CATEGORIZING, UpdateHeadline.WAITING, UpdateHeadline.IDLE,
    -> OnInk
}
