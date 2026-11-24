package com.pam.recap.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
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
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = PamSize.minTouch),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        CircularIconWell(
            icon = Icons.Rounded.ChevronLeft,
            contentDescription = stringResource(R.string.action_previous),
            onClick = onPrevious,
            enabled = canGoPrevious,
        )
        Column(
            modifier = Modifier
                .padding(horizontal = PamSpacing.md)
                .weight(1f, fill = false),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = label,
                style = com.pam.recap.ui.theme.HeadlineM,
                color = OnInk,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            if (partial) {
                Text(
                    text = stringResource(R.string.month_selector_partial),
                    style = com.pam.recap.ui.theme.BodyS,
                    color = OnInkMuted,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        }
        CircularIconWell(
            icon = Icons.Rounded.ChevronRight,
            contentDescription = stringResource(R.string.action_next),
            onClick = onNext,
            enabled = canGoNext,
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

/** One destination of the floating bottom bar. */
data class BottomNavItem(
    val key: String,
    val label: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val selected: Boolean,
)

/**
 * Floating glass pill with four destinations and a raised import action in the middle, mirroring
 * the reference's bottom navigation with its centre circle.
 */
@Composable
fun BottomNavBar(
    items: List<BottomNavItem>,
    onSelect: (String) -> Unit,
    onOpenStatus: () -> Unit,
    statusLabel: String,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = PamSize.bottomBarGutter, vertical = PamSpacing.sm),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(PamSize.bottomBar)
                .clip(RoundedCornerShape(PamRadius.pill))
                .background(GlassFill)
                .border(1.dp, GlassBorder, RoundedCornerShape(PamRadius.pill))
                .padding(horizontal = PamSpacing.xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            val half = items.size / 2
            items.take(half).forEach { item ->
                BottomNavCell(item = item, onSelect = onSelect)
            }
            // Centre raised action: the single update-status sheet, which owns the sync trigger.
            Box(
                modifier = Modifier
                    .size(PamSize.minTouch)
                    .clip(CircleShape)
                    .background(PamGradients.brand)
                    .clickable(onClick = onOpenStatus)
                    .semantics { contentDescription = statusLabel },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Add,
                    contentDescription = null,
                    tint = Ink,
                    modifier = Modifier.size(22.dp),
                )
            }
            items.drop(half).forEach { item ->
                BottomNavCell(item = item, onSelect = onSelect)
            }
        }
    }
}

@Composable
private fun BottomNavCell(item: BottomNavItem, onSelect: (String) -> Unit) {
    val tint = if (item.selected) com.pam.recap.ui.theme.Violet else OnInkMuted
    Column(
        modifier = Modifier
            .minimumInteractiveComponentSize()
            .clip(RoundedCornerShape(PamRadius.md))
            .clickable(role = Role.Tab) { onSelect(item.key) }
            .semantics {
                contentDescription = item.label
                selected = item.selected
            }
            .padding(horizontal = PamSpacing.xs, vertical = PamSpacing.xxs),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Icon(imageVector = item.icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
        CircleUnder(tint = tint, visible = item.selected)
    }
}

@Composable
private fun CircleUnder(tint: androidx.compose.ui.graphics.Color, visible: Boolean) {
    Box(
        modifier = Modifier
            .size(4.dp)
            .clip(CircleShape)
            .background(if (visible) tint else androidx.compose.ui.graphics.Color.Transparent),
    )
}
