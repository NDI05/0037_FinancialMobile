package com.pam.recap.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.pam.recap.R
import com.pam.recap.ui.theme.GlassBorder
import com.pam.recap.ui.theme.GlassFill
import com.pam.recap.ui.theme.GlassFillStrong
import com.pam.recap.ui.theme.Ink
import com.pam.recap.ui.theme.OnInk
import com.pam.recap.ui.theme.OnInkFaint
import com.pam.recap.ui.theme.OnInkMuted
import com.pam.recap.ui.theme.PamGradients
import com.pam.recap.ui.theme.PamRadius
import com.pam.recap.ui.theme.PamSize
import com.pam.recap.ui.theme.PamSpacing
import com.pam.recap.ui.theme.TitleM
import java.time.LocalDate

/**
 * Centred month navigator with chevrons, matching the reference's `‹ January 4–10 ›` control.
 * Both chevrons are icon-only, so each carries its own description.
 *
 * The arrows are bounded by the rolling one-year window: [canGoPrevious] is false on the oldest
 * reachable month and [canGoNext] is false on the current month, so the selector can never point at
 * a period the product does not serve. [partial] marks the boundary month that is only partly
 * covered by the window.
 */
@Composable
fun MonthSelector(
    label: String,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
    canGoPrevious: Boolean = true,
    canGoNext: Boolean = true,
    partial: Boolean = false,
) {
    // Stitch: a compact bordered pill centred under the title, not a full-width control row.
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
    ) {
        Row(
            modifier = Modifier
                .widthIn(min = 249.dp)
                .height(58.dp)
                .clip(RoundedCornerShape(PamRadius.pill))
                .background(com.pam.recap.ui.theme.SurfaceLow)
                .border(1.dp, com.pam.recap.ui.theme.OutlineVariant, RoundedCornerShape(PamRadius.pill))
                .padding(horizontal = PamSpacing.xxs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MonthArrow(
                icon = Icons.Rounded.ChevronLeft,
                contentDescription = stringResource(R.string.action_previous),
                enabled = canGoPrevious,
                onClick = onPrevious,
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = PamSpacing.xxs),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = label,
                    style = com.pam.recap.ui.theme.TitleM,
                    color = OnInk,
                    maxLines = 1,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
                if (partial) {
                    Text(
                        text = stringResource(R.string.month_selector_partial),
                        style = com.pam.recap.ui.theme.BodyS,
                        color = OnInkMuted,
                        maxLines = 1,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
            }
            MonthArrow(
                icon = Icons.Rounded.ChevronRight,
                contentDescription = stringResource(R.string.action_next),
                enabled = canGoNext,
                onClick = onNext,
            )
        }
    }
}

@Composable
private fun MonthArrow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .minimumInteractiveComponentSize()
            .size(44.dp)
            .clip(CircleShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (enabled) OnInk else OnInkFaint.copy(alpha = 0.38f),
            modifier = Modifier.size(20.dp),
        )
    }
}

/** One day cell of a [MonthStrip]. */
data class DayStripCell(
    val date: LocalDate,
    val weekdayLabel: String,
    val dayLabel: String,
    val hasData: Boolean,
    val contentDescription: String,
    /** True only when "today" falls inside the month this strip belongs to (C2-6). */
    val isToday: Boolean = false,
)

/**
 * Horizontal day selector echoing the reference's Sun–Sat strip: weekday letter above the day
 * number, a gradient fill on the selected day, a thin ring on today (only when today belongs to the
 * selected month) and a dot when the day has transactions.
 */
@Composable
fun MonthStrip(
    days: List<DayStripCell>,
    selectedDate: LocalDate?,
    onSelect: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(PamSpacing.xs),
    ) {
        items(items = days, key = { it.date.toEpochDay() }) { day ->
            val isSelected = day.date == selectedDate
            Column(
                modifier = Modifier
                    .width(PamSize.monthStripCell)
                    .minimumInteractiveComponentSize()
                    .clip(RoundedCornerShape(PamRadius.md))
                    .background(if (isSelected) Brush.linearGradient(listOf(GlassFillStrong, GlassFill)) else SolidColor(androidx.compose.ui.graphics.Color.Transparent))
                    .then(
                        if (isSelected) {
                            Modifier.border(
                                width = 1.dp,
                                brush = PamGradients.brandSoft,
                                shape = RoundedCornerShape(PamRadius.md),
                            )
                        } else {
                            Modifier
                        },
                    )
                    .clickable(role = Role.Tab) { onSelect(day.date) }
                    .semantics {
                        contentDescription = day.contentDescription
                        selected = isSelected
                    }
                    .padding(vertical = PamSpacing.xs),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(text = day.weekdayLabel, style = com.pam.recap.ui.theme.LabelM, color = OnInkFaint)
                Box(
                    modifier = Modifier
                        .size(30.dp)
                        .clip(CircleShape)
                        .then(
                            if (day.isToday && !isSelected) {
                                Modifier.border(1.dp, com.pam.recap.ui.theme.Violet, CircleShape)
                            } else {
                                Modifier
                            },
                        )
                        .background(if (isSelected) PamGradients.brand else SolidColor(androidx.compose.ui.graphics.Color.Transparent)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = day.dayLabel,
                        style = TitleM,
                        color = if (isSelected) Ink else OnInk,
                    )
                }
                Box(
                    modifier = Modifier
                        .size(4.dp)
                        .clip(CircleShape)
                        .background(if (day.hasData) OnInkMuted else androidx.compose.ui.graphics.Color.Transparent),
                )
            }
        }
    }
}

/** One destination of the Stitch bottom navigation. */
data class BottomNavItem(
    val key: String,
    val label: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val selected: Boolean,
)

/**
 * Flat Stitch bottom navigation: four equal-width labelled destinations with a lime pill behind
 * the active icon. Sync status lives in the top bar on every top-level tab, never as a 5th icon
 * squeezed into the bottom navigation bar.
 */
@Composable
fun BottomNavBar(
    items: List<BottomNavItem>,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    @Suppress("UNUSED_PARAMETER") onOpenStatus: (() -> Unit)? = null,
    @Suppress("UNUSED_PARAMETER") statusLabel: String = "",
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(com.pam.recap.ui.theme.Surface),
    ) {
        androidx.compose.material3.HorizontalDivider(
            color = com.pam.recap.ui.theme.OutlineVariant,
            thickness = 1.dp,
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .padding(horizontal = PamSpacing.xs, vertical = PamSpacing.xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(PamSpacing.xxs),
        ) {
            items.forEach { item ->
                BottomNavCell(
                    item = item,
                    onSelect = onSelect,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun BottomNavCell(
    item: BottomNavItem,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val active = item.selected
    Column(
        modifier = modifier
            .minimumInteractiveComponentSize()
            .clip(RoundedCornerShape(PamRadius.sm))
            .clickable(role = Role.Tab) { onSelect(item.key) }
            .semantics {
                contentDescription = item.label
                selected = item.selected
            }
            .padding(vertical = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Box(
            modifier = Modifier
                .height(28.dp)
                .widthIn(min = 52.dp)
                .clip(RoundedCornerShape(PamRadius.pill))
                .background(
                    if (active) {
                        com.pam.recap.ui.theme.Accent
                    } else {
                        androidx.compose.ui.graphics.Color.Transparent
                    },
                )
                .padding(horizontal = PamSpacing.md),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = item.icon,
                contentDescription = null,
                tint = if (active) com.pam.recap.ui.theme.OnAccent else OnInkMuted,
                modifier = Modifier.size(20.dp),
            )
        }
        Text(
            text = item.label,
            style = com.pam.recap.ui.theme.LabelEyebrow,
            color = if (active) OnInk else OnInkMuted,
            maxLines = 1,
        )
    }
}
