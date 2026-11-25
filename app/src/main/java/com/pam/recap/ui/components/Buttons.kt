package com.pam.recap.ui.components

import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.pam.recap.R
import com.pam.recap.ui.theme.Accent
import com.pam.recap.ui.theme.ErrorContainer
import com.pam.recap.ui.theme.GlassBorder
import com.pam.recap.ui.theme.GlassFill
import com.pam.recap.ui.theme.Ink
import com.pam.recap.ui.theme.LabelL
import com.pam.recap.ui.theme.LabelM
import com.pam.recap.ui.theme.OnAccent
import com.pam.recap.ui.theme.OnErrorContainer
import com.pam.recap.ui.theme.OnInk
import com.pam.recap.ui.theme.OnInkFaint
import com.pam.recap.ui.theme.OnInkMuted
import com.pam.recap.ui.theme.OutlineVariant
import com.pam.recap.ui.theme.PamGradients
import com.pam.recap.ui.theme.PamRadius
import com.pam.recap.ui.theme.PamSize
import com.pam.recap.ui.theme.PamSpacing
import com.pam.recap.ui.theme.Surface
import com.pam.recap.ui.theme.TitleS
import com.pam.recap.ui.theme.TrackInset
import com.pam.recap.ui.theme.OnPrimary
import com.pam.recap.ui.theme.Primary
import com.pam.recap.ui.theme.Violet

/** Visual weight of a [PillButton]. */
enum class PillButtonStyle {
    /** Primary ink `#171915`, white label. */
    Primary,

    /** Functional lime `#D9F449`, dark ink `#171915` label. */
    Accent,

    /** White fill with a 1 dp `#E2E3DB` border. */
    Secondary,

    /** Soft error container `#FFEDEA`, dark red `#93000B` label. */
    DangerSoft,

    /** Text-only affordance. */
    Ghost,
}

/**
 * Stitch button with a 48 dp minimum height and explicit loading/disabled states (NFR-01).
 * While [loading] is true the control stays sized, announces itself as busy and ignores taps.
 */
@Composable
fun PillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: PillButtonStyle = PillButtonStyle.Primary,
    enabled: Boolean = true,
    loading: Boolean = false,
    leadingIcon: ImageVector? = null,
    trailingIcon: ImageVector? = null,
    size: androidx.compose.ui.unit.Dp = PamSize.minTouch,
    shape: Shape = RoundedCornerShape(PamRadius.sm),
) {
    val container: Brush = when (style) {
        PillButtonStyle.Primary -> SolidColor(Primary)
        PillButtonStyle.Accent -> SolidColor(Accent)
        PillButtonStyle.Secondary -> SolidColor(Surface)
        PillButtonStyle.DangerSoft -> SolidColor(ErrorContainer)
        PillButtonStyle.Ghost -> SolidColor(Color.Transparent)
    }
    val contentColor = when (style) {
        PillButtonStyle.Primary -> OnPrimary
        PillButtonStyle.Accent -> OnAccent
        PillButtonStyle.DangerSoft -> OnErrorContainer
        PillButtonStyle.Secondary, PillButtonStyle.Ghost -> OnInk
    }
    val border = when (style) {
        PillButtonStyle.Secondary -> BorderStroke(1.dp, OutlineVariant)
        PillButtonStyle.Primary, PillButtonStyle.Accent, PillButtonStyle.DangerSoft, PillButtonStyle.Ghost -> null
    }
    val alpha = if (enabled && !loading) 1f else 0.45f
    val interaction = remember { MutableInteractionSource() }
    val loadingDescription = stringResource(R.string.status_loading)

    Box(
        modifier = modifier
            .minimumInteractiveComponentSize()
            .heightIn(min = size)
            .clip(shape)
            .background(container)
            .then(if (border != null) Modifier.border(border, shape) else Modifier)
            .then(
                if (enabled && !loading) {
                    Modifier.clickable(
                        interactionSource = interaction,
                        indication = null,
                        role = Role.Button,
                        onClick = onClick,
                    )
                } else {
                    Modifier
                },
            )
            .semantics {
                if (loading) contentDescription = loadingDescription
            }
            .padding(horizontal = PamSpacing.lg, vertical = PamSpacing.xs),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            when {
                loading -> CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    color = contentColor.copy(alpha = 0.9f),
                    strokeWidth = 2.dp,
                )

                leadingIcon != null -> Icon(
                    imageVector = leadingIcon,
                    contentDescription = null,
                    tint = contentColor.copy(alpha = alpha),
                    modifier = Modifier.size(18.dp),
                )
            }
            if (loading || leadingIcon != null) Spacer(modifier = Modifier.width(PamSpacing.xs))
            Text(
                text = text,
                style = LabelL,
                color = contentColor.copy(alpha = alpha),
                textAlign = TextAlign.Center,
            )
            if (!loading && trailingIcon != null) {
                Spacer(modifier = Modifier.width(PamSpacing.xxs))
                Icon(
                    imageVector = trailingIcon,
                    contentDescription = null,
                    tint = contentColor.copy(alpha = alpha),
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

/**
 * Stitch search field: rounded 12 dp white box with 1 dp `#E2E3DB` border, a leading magnifying
 * glass on the left, and an optional clear button when non-empty.
 */
@Composable
fun SearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    onSearch: (() -> Unit)? = null,
) {
    val searchDescription = stringResource(R.string.action_search)
    val clearDescription = stringResource(R.string.action_close)
    val shape = RoundedCornerShape(PamRadius.sm)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = PamSize.minTouch)
            .clip(shape)
            .background(Surface)
            .border(1.dp, OutlineVariant, shape)
            .padding(start = PamSpacing.sm, end = PamSpacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(PamSpacing.xs),
    ) {
        Icon(
            imageVector = Icons.Rounded.Search,
            contentDescription = searchDescription,
            tint = OnInkFaint,
            modifier = Modifier
                .size(20.dp)
                .then(if (onSearch != null) Modifier.clickable(onClick = onSearch) else Modifier),
        )
        Box(modifier = Modifier.weight(1f).padding(vertical = PamSpacing.sm)) {
            if (value.isEmpty()) {
                Text(text = placeholder, style = com.pam.recap.ui.theme.BodyM, color = OnInkFaint)
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                textStyle = LocalTextStyle.current.merge(com.pam.recap.ui.theme.BodyM).copy(color = OnInk),
                cursorBrush = SolidColor(Primary),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (value.isNotEmpty()) {
            IconButton(
                onClick = { onValueChange("") },
                modifier = Modifier.size(36.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = clearDescription,
                    tint = OnInkFaint,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

/** Small circular progress shown inline while a value loads. */
@Composable
fun InlineSpinner(modifier: Modifier = Modifier, color: Color = Violet) {
    CircularProgressIndicator(
        modifier = modifier.size(16.dp),
        color = color,
        strokeWidth = 2.dp,
    )
}
