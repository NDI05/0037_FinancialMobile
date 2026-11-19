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
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.pam.recap.R
import com.pam.recap.ui.theme.GlassBorder
import com.pam.recap.ui.theme.GlassFill
import com.pam.recap.ui.theme.Ink
import com.pam.recap.ui.theme.LabelL
import com.pam.recap.ui.theme.LabelM
import com.pam.recap.ui.theme.OnInk
import com.pam.recap.ui.theme.OnInkFaint
import com.pam.recap.ui.theme.OnInkMuted
import com.pam.recap.ui.theme.PamGradients
import com.pam.recap.ui.theme.PamRadius
import com.pam.recap.ui.theme.PamSize
import com.pam.recap.ui.theme.PamSpacing
import com.pam.recap.ui.theme.TitleS
import com.pam.recap.ui.theme.TrackInset
import com.pam.recap.ui.theme.Violet

/** Visual weight of a [PillButton]. */
enum class PillButtonStyle {
    /** Brand gradient, dark ink label. */
    Primary,

    /** Glass fill with a light border. */
    Secondary,

    /** Text-only affordance. */
    Ghost,
}

/**
 * Fully rounded CTA with a 48 dp minimum height and explicit loading/disabled states (NFR-01).
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
    size: androidx.compose.ui.unit.Dp = PamSize.minTouch,
) {
    val container: Brush = when (style) {
        PillButtonStyle.Primary -> PamGradients.brand
        PillButtonStyle.Secondary -> SolidColor(GlassFill)
        PillButtonStyle.Ghost -> SolidColor(Color.Transparent)
    }
    val contentColor = when (style) {
        PillButtonStyle.Primary -> Ink
        PillButtonStyle.Secondary, PillButtonStyle.Ghost -> OnInk
    }
    val border = when (style) {
        PillButtonStyle.Primary -> null
        PillButtonStyle.Secondary -> BorderStroke(1.dp, GlassBorder)
        PillButtonStyle.Ghost -> null
    }
    val alpha = if (enabled && !loading) 1f else 0.45f
    val interaction = remember { MutableInteractionSource() }
    val loadingDescription = stringResource(R.string.status_loading)

    Box(
        modifier = modifier
            .minimumInteractiveComponentSize()
            .heightIn(min = size)
            .clip(RoundedCornerShape(PamRadius.pill))
            .background(container)
            .then(if (border != null) Modifier.border(border, RoundedCornerShape(PamRadius.pill)) else Modifier)
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
        }
    }
}

/**
 * Glass search field with a trailing circular search well, echoing the reference's bottom bar.
 * The trailing control is icon-only, so it carries a content description.
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
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = PamSize.minTouch)
            .clip(RoundedCornerShape(PamRadius.pill))
            .background(GlassFill)
            .border(1.dp, GlassBorder, RoundedCornerShape(PamRadius.pill))
            .padding(start = PamSpacing.md, end = PamSpacing.xxs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.weight(1f)) {
            if (value.isEmpty()) {
                Text(text = placeholder, style = TitleS, color = OnInkFaint)
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                textStyle = LocalTextStyle.current.merge(TitleS).copy(color = OnInk),
                cursorBrush = SolidColor(Violet),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        CircularIconWell(
            icon = Icons.Rounded.Search,
            contentDescription = searchDescription,
            onClick = { onSearch?.invoke() },
        )
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
