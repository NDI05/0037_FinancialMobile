package com.pam.recap.ui.components

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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
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

/**
 * Compact automatic-update indicator for a glass top bar.
 *
 * Icon + word, never colour alone. The animated ring appears only for work that is actually running
 * and is replaced by a static icon when the OS asks for reduced motion. The whole pill is the touch
 * target (>= 48 dp) and the accessible name repeats the state, so TalkBack does not depend on the
 * visual label alone.
 */
@Composable
fun UpdateStatusPill(
    status: UpdateStatusUi,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val reducedMotion = LocalReducedMotion.current
    // At very large text the word no longer fits next to the screen title; the icon keeps the state
    // visible and the content description keeps it audible.
    val showLabel = LocalDensity.current.fontScale < 1.5f
    val label = stringResource(status.headline.labelRes())
    val spoken = stringResource(R.string.update_status_open) + ": " + label
    val tint = status.headline.tint()

    Row(
        modifier = modifier
            .sizeIn(minHeight = PamSize.minTouch)
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
        if (showLabel) {
            Text(
                text = label,
                style = LabelM,
                color = tint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private fun UpdateHeadline.labelRes(): Int = when (this) {
    UpdateHeadline.NEEDS_ATTENTION -> R.string.update_status_indicator_attention
    UpdateHeadline.CHECKING, UpdateHeadline.FETCHING -> R.string.update_status_indicator_checking
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
