package com.pam.recap.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.MailOutline
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.pam.recap.R
import com.pam.recap.ui.theme.DangerRed
import com.pam.recap.ui.theme.IncomeMint
import com.pam.recap.ui.theme.LabelM
import com.pam.recap.ui.theme.OnInkMuted
import com.pam.recap.ui.theme.PamRadius
import com.pam.recap.ui.theme.PamSpacing
import com.pam.recap.ui.theme.Violet
import com.pam.recap.ui.theme.WarningAmber

/** Every status the product can show on a chip. */
enum class StatusChipKind {
    EmailDerived,
    Complete,
    Partial,
    Empty,
    Failed,
    Provisional,
    Outdated,
    Historical,
    Manual,
    LowConfidence,
    Suggestion,
    Unavailable,

    /** A mailbox that is currently connected (not a coverage outcome). */
    Connected,

    /** A consent purpose whose latest decision is GRANTED. */
    Granted,

    /** A consent purpose whose latest decision is DECLINED. */
    Declined,

    /** A published analysis that is neither provisional nor outdated. */
    Available,
}

/**
 * Status chip. Always icon + text, so the state never depends on colour (NFR-01). Chip copy is
 * resolved here from `strings.xml`, which keeps every screen free of literals.
 */
@Composable
fun StatusChip(
    kind: StatusChipKind,
    modifier: Modifier = Modifier,
    onDark: Boolean = false,
) {
    val labelRes: Int
    val icon: ImageVector
    val baseColor: Color
    when (kind) {
        StatusChipKind.EmailDerived -> {
            labelRes = R.string.status_email_derived
            icon = Icons.Rounded.MailOutline
            baseColor = OnInkMuted
        }

        StatusChipKind.Complete -> {
            labelRes = R.string.status_complete
            icon = Icons.Rounded.CheckCircle
            baseColor = IncomeMint
        }

        StatusChipKind.Partial -> {
            labelRes = R.string.status_partial
            icon = Icons.Rounded.WarningAmber
            baseColor = WarningAmber
        }

        StatusChipKind.Empty -> {
            labelRes = R.string.status_empty
            icon = Icons.Rounded.Info
            baseColor = OnInkMuted
        }

        StatusChipKind.Failed -> {
            labelRes = R.string.status_failed
            icon = Icons.Rounded.Error
            baseColor = DangerRed
        }

        StatusChipKind.Provisional -> {
            labelRes = R.string.status_provisional
            icon = Icons.Rounded.Schedule
            baseColor = WarningAmber
        }

        StatusChipKind.Outdated -> {
            labelRes = R.string.status_outdated
            icon = Icons.Rounded.History
            baseColor = WarningAmber
        }

        StatusChipKind.Historical -> {
            labelRes = R.string.status_historical
            icon = Icons.Rounded.History
            baseColor = OnInkMuted
        }

        StatusChipKind.Manual -> {
            labelRes = R.string.status_manual
            icon = Icons.Rounded.Visibility
            baseColor = Violet
        }

        StatusChipKind.LowConfidence -> {
            labelRes = R.string.status_low_confidence
            icon = Icons.Rounded.WarningAmber
            baseColor = WarningAmber
        }

        StatusChipKind.Suggestion -> {
            labelRes = R.string.status_ai_suggestion
            icon = Icons.Rounded.AutoAwesome
            baseColor = Violet
        }

        StatusChipKind.Unavailable -> {
            labelRes = R.string.status_unavailable
            icon = Icons.Rounded.CloudOff
            baseColor = OnInkMuted
        }

        StatusChipKind.Connected -> {
            labelRes = R.string.status_connected
            icon = Icons.Rounded.Link
            baseColor = IncomeMint
        }

        StatusChipKind.Granted -> {
            labelRes = R.string.status_granted
            icon = Icons.Rounded.CheckCircle
            baseColor = IncomeMint
        }

        StatusChipKind.Declined -> {
            labelRes = R.string.status_declined
            icon = Icons.Rounded.Block
            baseColor = OnInkMuted
        }

        StatusChipKind.Available -> {
            labelRes = R.string.status_available
            icon = Icons.Rounded.CheckCircle
            baseColor = IncomeMint
        }
    }

    val color = if (onDark) Color.White else baseColor
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(PamRadius.pill))
            .background(color.copy(alpha = 0.14f))
            .padding(horizontal = PamSpacing.xs, vertical = PamSpacing.xxs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(PamSpacing.xxs),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(12.dp),
        )
        Text(text = stringResource(labelRes), style = LabelM, color = color)
    }
}

/** Thin confidence bar used by the transaction detail provenance block. */
@Composable
fun ConfidenceBar(
    confidence: Float,
    modifier: Modifier = Modifier,
) {
    val clamped = confidence.coerceIn(0f, 1f)
    val color = if (clamped >= 0.8f) IncomeMint else WarningAmber
    val track = color.copy(alpha = 0.18f)
    androidx.compose.foundation.Canvas(
        modifier = modifier
            .height(5.dp)
            .fillMaxWidth(),
    ) {
        val radius = androidx.compose.ui.geometry.CornerRadius(size.height / 2f)
        drawRoundRect(color = track, cornerRadius = radius)
        drawRoundRect(
            color = color,
            size = androidx.compose.ui.geometry.Size(width = size.width * clamped, height = size.height),
            cornerRadius = radius,
        )
    }
}
