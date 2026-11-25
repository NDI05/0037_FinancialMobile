package com.pam.recap.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.pam.recap.ui.theme.GlassFill
import com.pam.recap.ui.theme.Ink
import com.pam.recap.ui.theme.LabelEyebrow
import com.pam.recap.ui.theme.OnInk
import com.pam.recap.ui.theme.OnInkFaint
import com.pam.recap.ui.theme.OnInkMuted
import com.pam.recap.ui.theme.PamGradients
import com.pam.recap.ui.theme.PamRadius
import com.pam.recap.ui.theme.PamSize
import com.pam.recap.ui.theme.PamSpacing
import com.pam.recap.ui.theme.TitleL
import com.pam.recap.ui.theme.Violet
import java.util.Locale

/**
 * Edge-to-edge page shell: ink canvas, optional aurora bloom, status-bar padding, a caller-owned
 * top bar and an optional floating bottom bar. Screens receive the horizontal gutter as
 * [PaddingValues] so their scroll containers can opt into it.
 */
@Composable
fun PamScaffold(
    modifier: Modifier = Modifier,
    showAurora: Boolean = false,
    topBar: (@Composable () -> Unit)? = null,
    bottomBar: (@Composable () -> Unit)? = null,
    content: @Composable (PaddingValues) -> Unit,
) {
    Box(modifier = modifier.fillMaxSize().background(Ink)) {
        if (showAurora) {
            AuroraBackdrop()
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.statusBars.only(WindowInsetsSides.Top))
                // Keyboard (IME) is a bottom inset: shrink the shell instead of letting the
                // keyboard cover the fields and the save button on manual-entry forms.
                .imePadding(),
        ) {
            if (topBar != null) {
                topBar()
            }
            Box(modifier = Modifier.weight(1f)) {
                content(
                    PaddingValues(
                        start = PamSpacing.gutter,
                        end = PamSpacing.gutter,
                        bottom = PamSpacing.lg,
                    ),
                )
            }
            if (bottomBar != null) {
                Box(
                    modifier = Modifier.windowInsetsPadding(
                        WindowInsets.navigationBars.only(WindowInsetsSides.Bottom),
                    ),
                ) {
                    bottomBar()
                }
            }
        }
    }
}

/** Radial violet→magenta bloom behind the top of the screen (design system §5). */
@Composable
fun AuroraBackdrop(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(420.dp)
            .background(PamGradients.aurora()),
    )
}

/**
 * Title centred against the *screen* width, with circular wells overlaid on either side.
 *
 * Using an overlay Box instead of a weighted Row is deliberate: a Row centres the title in the
 * space left over after the back well and the trailing actions, so the title drifts whenever a
 * status icon appears, a back button is added, or a long title at 200 % font grows. Here the text
 * always measures the full screen width and is centred on it; the side wells are drawn on top and
 * never move it.
 */
@Composable
fun GlassTopBar(
    title: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    subtitle: String? = null,
    actions: @Composable RowScope.() -> Unit = {},
    // Dedicated top-right slot for the sync/update status control. Kept separate from [actions] so
    // screens cannot accidentally move it, and additive so every existing caller keeps compiling.
    status: (@Composable () -> Unit)? = null,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .padding(horizontal = PamSpacing.md),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = title,
                style = TitleL,
                color = OnInk,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 56.dp),
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = OnInkMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 56.dp),
                )
            }
        }
        if (onBack != null) {
            CircularIconWell(
                icon = Icons.AutoMirrored.Rounded.ArrowBack,
                contentDescription = stringResource(com.pam.recap.R.string.action_back),
                onClick = onBack,
                containerColor = Color.Transparent,
                modifier = Modifier.align(Alignment.CenterStart),
            )
        }
        Row(
            modifier = Modifier.align(Alignment.CenterEnd),
            horizontalArrangement = Arrangement.spacedBy(PamSpacing.xxs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            status?.invoke()
            actions()
        }
    }
}

/**
 * 48 dp minimum interactive icon well. [contentDescription] is mandatory for icon-only controls
 * (NFR-01); pass `null` only when the icon is decorative next to a visible label.
 */
@Composable
fun CircularIconWell(
    icon: ImageVector,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    size: Dp = PamSize.minTouch,
    selected: Boolean = false,
    enabled: Boolean = true,
    containerColor: Color = GlassFill,
    tint: Color = if (selected) Violet else OnInk,
) {
    val wellModifier = modifier
        .minimumInteractiveComponentSize()
        .size(size)
        .clip(CircleShape)
        .background(if (selected) Violet.copy(alpha = 0.12f) else containerColor)

    val withClick = if (onClick != null) {
        wellModifier
            .semantics {
                if (contentDescription != null) this.contentDescription = contentDescription
            }
            .clickable(enabled = enabled, onClick = onClick)
    } else {
        wellModifier
    }

    Box(modifier = withClick, contentAlignment = Alignment.Center) {
        Icon(
            imageVector = icon,
            contentDescription = if (onClick != null) null else contentDescription,
            tint = tint.copy(alpha = if (enabled) tint.alpha else tint.alpha * 0.4f),
            modifier = Modifier.size(22.dp),
        )
    }
}

/** Uppercase eyebrow label; the case transform is locale-aware, not a literal. */
@Composable
fun EyebrowText(
    text: String,
    modifier: Modifier = Modifier,
    color: androidx.compose.ui.graphics.Color = OnInkFaint,
) {
    Text(
        text = text.uppercase(Locale.getDefault()),
        style = LabelEyebrow,
        color = color,
        modifier = modifier,
    )
}

/** Eyebrow + title pair with an optional trailing action. */
@Composable
fun SectionHeader(
    eyebrow: String,
    modifier: Modifier = Modifier,
    title: String? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            EyebrowText(text = eyebrow)
            if (title != null) {
                Spacer(modifier = Modifier.height(PamSpacing.xxs))
                Text(text = title, style = MaterialTheme.typography.titleLarge, color = OnInk)
            }
        }
        trailing?.invoke()
    }
}

/** Small rounded label used for the persistent EMAIL-DERIVED indicator (BR-05). */
@Composable
fun DerivedBadge(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(PamRadius.pill))
            .background(OnInk.copy(alpha = 0.06f))
            .padding(horizontal = PamSpacing.sm, vertical = PamSpacing.xxs),
    ) {
        Text(text = text, style = LabelEyebrow, color = OnInkMuted)
    }
}
