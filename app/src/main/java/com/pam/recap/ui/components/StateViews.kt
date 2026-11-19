package com.pam.recap.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.pam.recap.ui.theme.DangerRed
import com.pam.recap.ui.theme.DisplayM
import com.pam.recap.ui.theme.InkElevated
import com.pam.recap.ui.theme.OnInk
import com.pam.recap.ui.theme.OnInkFaint
import com.pam.recap.ui.theme.OnInkMuted
import com.pam.recap.ui.theme.PamRadius
import com.pam.recap.ui.theme.PamSpacing
import com.pam.recap.ui.theme.TitleM
import com.pam.recap.ui.theme.Violet

/**
 * Compact 2-up statistic: eyebrow, big value, caption. The value slot is a composable so a money
 * or percentage renderer can be dropped in without this component formatting anything itself.
 */
@Composable
fun StatTile(
    eyebrow: String,
    modifier: Modifier = Modifier,
    caption: String? = null,
    value: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(PamRadius.md))
            .background(com.pam.recap.ui.theme.GlassFill)
            .padding(PamSpacing.md),
    ) {
        Column {
            EyebrowText(text = eyebrow)
            Spacer(modifier = Modifier.height(PamSpacing.xs))
            value()
            if (caption != null) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(text = caption, style = TitleM, color = OnInkFaint, maxLines = 2)
            }
        }
    }
}

/** Empty state with eyebrow, title, body and an optional action. */
@Composable
fun EmptyState(
    eyebrow: String,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    action: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = PamSpacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(PamSpacing.xs),
    ) {
        if (icon != null) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(PamRadius.pill))
                    .background(Violet.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = Violet,
                    modifier = Modifier.size(24.dp),
                )
            }
        }
        EyebrowText(text = eyebrow)
        Text(text = title, style = DisplayM, color = OnInk, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        Text(
            text = body,
            style = TitleM,
            color = OnInkMuted,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        if (action != null) {
            Spacer(modifier = Modifier.height(PamSpacing.xs))
            action()
        }
    }
}

/**
 * Error state. Announced politely so TalkBack reads the failure when it appears (NFR-01), with a
 * retry action when the caller can offer one.
 */
@Composable
fun ErrorState(
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    onRetry: (() -> Unit)? = null,
    retryLabel: String? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = PamSpacing.xl)
            .semantics { liveRegion = LiveRegionMode.Polite },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(PamSpacing.xs),
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(PamRadius.pill))
                .background(DangerRed.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Rounded.Error,
                contentDescription = null,
                tint = DangerRed,
                modifier = Modifier.size(24.dp),
            )
        }
        Text(
            text = title,
            style = DisplayM,
            color = OnInk,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        Text(
            text = message,
            style = TitleM,
            color = OnInkMuted,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        if (onRetry != null && retryLabel != null) {
            Spacer(modifier = Modifier.height(PamSpacing.xs))
            PillButton(text = retryLabel, onClick = onRetry, style = PillButtonStyle.Secondary)
        }
    }
}

/** Skeleton block on `InkHigh`, used while a screen waits for its first emission. */
@Composable
fun SkeletonBlock(
    modifier: Modifier = Modifier,
    height: androidx.compose.ui.unit.Dp = 16.dp,
    radius: androidx.compose.ui.unit.Dp = PamRadius.sm,
) {
    Box(
        modifier = modifier
            .height(height)
            .clip(RoundedCornerShape(radius))
            .background(com.pam.recap.ui.theme.InkHigh),
    )
}

/**
 * Loading skeleton for a list/card screen. Shimmer alpha loops here so it only runs while the
 * screen is genuinely loading (design system §7: no looping decorative animation).
 */
@Composable
fun LoadingShimmer(
    modifier: Modifier = Modifier,
    rows: Int = 4,
    showHero: Boolean = true,
) {
    val transition = rememberInfiniteTransition(label = "shimmer")
    val alpha by transition.animateFloat(
        initialValue = 0.45f,
        targetValue = 0.9f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "shimmer-alpha",
    )
    Column(
        modifier = modifier
            .fillMaxWidth()
            .alpha(alpha),
        verticalArrangement = Arrangement.spacedBy(PamSpacing.sm),
    ) {
        if (showHero) {
            SkeletonBlock(modifier = Modifier.fillMaxWidth(), height = 168.dp, radius = PamRadius.hero)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(PamSpacing.sm)) {
            SkeletonBlock(modifier = Modifier.weight(1f), height = 84.dp, radius = PamRadius.md)
            SkeletonBlock(modifier = Modifier.weight(1f), height = 84.dp, radius = PamRadius.md)
        }
        repeat(rows) {
            SkeletonBlock(modifier = Modifier.fillMaxWidth(), height = 64.dp, radius = PamRadius.md)
        }
    }
}

/** Tinted banner used for coverage and shortfall notices. */
@Composable
fun NoticeBanner(
    text: String,
    modifier: Modifier = Modifier,
    tone: MoneyTone = MoneyTone.Neutral,
    icon: ImageVector? = null,
    action: (@Composable () -> Unit)? = null,
) {
    val color: Color = moneyToneColor(tone)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(PamRadius.md))
            .background(color.copy(alpha = 0.12f))
            .padding(PamSpacing.md)
            .semantics { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(PamSpacing.sm),
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(18.dp),
            )
        }
        Text(
            text = text,
            style = TitleM,
            color = OnInk,
            modifier = Modifier.weight(1f),
        )
        action?.invoke()
    }
}

/** Neutral divider used inside glass cards. */
@Composable
fun GlassDivider(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(com.pam.recap.ui.theme.GlassBorder),
    )
}

/** Surface used for inset content inside a card (e.g. the provenance block). */
@Composable
fun InsetPanel(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(PamRadius.md))
            .background(InkElevated)
            .padding(PamSpacing.md),
    ) {
        content()
    }
}
