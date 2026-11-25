package com.pam.recap.ui.feature.transactions

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CalendarToday
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pam.recap.R
import com.pam.recap.domain.model.Category
import com.pam.recap.domain.model.Direction
import com.pam.recap.domain.model.TransactionSource
import com.pam.recap.ui.theme.GlassBorder
import com.pam.recap.ui.theme.Ink
import com.pam.recap.ui.theme.LabelL
import com.pam.recap.ui.theme.OnInk
import com.pam.recap.ui.theme.OnInkFaint
import com.pam.recap.ui.theme.OnInkMuted
import com.pam.recap.ui.theme.PamRadius
import com.pam.recap.ui.theme.PamSpacing
import com.pam.recap.ui.theme.Surface
import com.pam.recap.ui.theme.SurfaceLow
import com.pam.recap.ui.util.categoryLabelRes
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Every predicate the transaction list can carry. The month is deliberately absent: a period is
 * always required, so it is navigation rather than something the user can remove.
 */
enum class ActiveTransactionFilter { DIRECTION, SOURCE, CATEGORY, DAY }

/** The filters that actually narrow the list right now, evaluated in one place for chips and tests. */
internal fun activeTransactionFilters(
    direction: Direction?,
    source: TransactionSource?,
    category: Category?,
    day: LocalDate?,
): Set<ActiveTransactionFilter> = buildSet {
    if (direction != null) add(ActiveTransactionFilter.DIRECTION)
    if (source != null) add(ActiveTransactionFilter.SOURCE)
    if (category != null) add(ActiveTransactionFilter.CATEGORY)
    if (day != null) add(ActiveTransactionFilter.DAY)
}

/**
 * A drill-down row was created for the filters it carried (`direction`/`categoryCode`/`dayIso`).
 * When the user clears the last of them the unfiltered month is the tab's job, so the screen leaves
 * through the same back route instead of silently turning a filtered drill-down into a second tab.
 * A tab (no carried filter) never auto-navigates.
 */
internal fun returnsToOverviewAfterClear(
    carried: Set<ActiveTransactionFilter>,
    remaining: Set<ActiveTransactionFilter>,
): Boolean = carried.isNotEmpty() && remaining.isEmpty()

/**
 * One horizontally scrollable strip of filter chips, mirroring the reference: the period control,
 * the direction segmented chips, a cycling source chip, removable category/day chips and a single
 * reset chip that appears only when something is narrowing the list.
 */
@Composable
fun TransactionFilterBar(
    monthLabel: String,
    direction: Direction?,
    source: TransactionSource?,
    category: Category?,
    day: LocalDate?,
    canGoPrevious: Boolean,
    canGoNext: Boolean,
    onPreviousMonth: () -> Unit,
    onNextMonth: () -> Unit,
    onDirectionChange: (Direction?) -> Unit,
    onSourceChange: (TransactionSource?) -> Unit,
    onCategoryCleared: () -> Unit,
    onDayCleared: () -> Unit,
    onClearAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val filters = activeTransactionFilters(direction, source, category, day)
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(PamSpacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        item(key = "period") {
            PeriodChip(
                monthLabel = monthLabel,
                canGoPrevious = canGoPrevious,
                canGoNext = canGoNext,
                onPrevious = onPreviousMonth,
                onNext = onNextMonth,
            )
        }
        item(key = "direction-all") {
            FilterChip(
                label = stringResource(R.string.transactions_filter_all),
                selected = direction == null,
                onClick = { onDirectionChange(null) },
            )
        }
        item(key = "direction-expense") {
            FilterChip(
                label = stringResource(R.string.metric_classified_expense),
                selected = direction == Direction.DEBIT,
                onClick = { onDirectionChange(Direction.DEBIT) },
            )
        }
        item(key = "direction-income") {
            FilterChip(
                label = stringResource(R.string.metric_classified_income),
                selected = direction == Direction.CREDIT,
                onClick = { onDirectionChange(Direction.CREDIT) },
            )
        }
        item(key = "source") {
            FilterChip(
                label = stringResource(R.string.detail_source) + ": " + sourceLabel(source),
                selected = source != null,
                onClick = { onSourceChange(nextSource(source)) },
            )
        }
        if (category != null) {
            item(key = "category", contentType = "removable") {
                RemovableChip(
                    label = stringResource(categoryLabelRes(category)),
                    onRemove = onCategoryCleared,
                )
            }
        }
        if (day != null) {
            item(key = "day", contentType = "removable") {
                RemovableChip(label = dayLabel(day), onRemove = onDayCleared)
            }
        }
        if (filters.isNotEmpty()) {
            item(key = "clear-all", contentType = "removable") {
                RemovableChip(
                    label = stringResource(R.string.action_remove),
                    onRemove = onClearAll,
                    leadingClose = true,
                )
            }
        }
    }
}

/** `Semua` / `Manual` / `Email Livin`, the three values the source chip can show. */
@Composable
private fun sourceLabel(source: TransactionSource?): String = when (source) {
    null -> stringResource(R.string.transactions_filter_all)
    TransactionSource.MANUAL -> stringResource(R.string.status_manual)
    // Only Livin mail is parsed today; naming the mailbox is more honest than a generic "Email".
    TransactionSource.EMAIL -> stringResource(R.string.detail_source_email)
}

/** Source cycles through its three values, so one chip exposes the whole dimension. */
private fun nextSource(current: TransactionSource?): TransactionSource? = when (current) {
    null -> TransactionSource.MANUAL
    TransactionSource.MANUAL -> TransactionSource.EMAIL
    TransactionSource.EMAIL -> null
}

@Composable
private fun dayLabel(day: LocalDate): String =
    day.format(DateTimeFormatter.ofPattern("d MMM", Locale.getDefault()))

/**
 * Period control: the month is always applied, so it has no remove action. The chevrons keep the
 * rolling-window navigation that the old header control provided, in the same 48 dp target.
 */
@Composable
private fun PeriodChip(
    monthLabel: String,
    canGoPrevious: Boolean,
    canGoNext: Boolean,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
) {
    val shape = RoundedCornerShape(PamRadius.pill)
    Row(
        modifier = Modifier
            .clip(shape)
            .background(SurfaceLow)
            .border(1.dp, GlassBorder, shape),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MonthArrow(
            icon = Icons.Rounded.ChevronLeft,
            contentDescription = stringResource(R.string.action_previous),
            enabled = canGoPrevious,
            onClick = onPrevious,
        )
        Row(
            modifier = Modifier.padding(horizontal = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(PamSpacing.xxs),
        ) {
            Icon(
                imageVector = Icons.Rounded.CalendarToday,
                contentDescription = null,
                tint = OnInkMuted,
                modifier = Modifier.size(14.dp),
            )
            Text(
                text = monthLabel,
                style = LabelL,
                color = OnInk,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        MonthArrow(
            icon = Icons.Rounded.ChevronRight,
            contentDescription = stringResource(R.string.action_next),
            enabled = canGoNext,
            onClick = onNext,
        )
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
            .size(width = 36.dp, height = 48.dp)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = if (enabled) OnInk else OnInkFaint.copy(alpha = 0.5f),
            modifier = Modifier.size(18.dp),
        )
    }
}

/** A plain pill: filled ink when selected, outlined surface otherwise (reference chip styling). */
@Composable
private fun FilterChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(PamRadius.pill)
    Box(
        modifier = Modifier
            .minimumInteractiveComponentSize()
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .height(36.dp)
                .clip(shape)
                .background(if (selected) OnInk else Surface)
                .border(1.dp, if (selected) Color.Transparent else GlassBorder, shape)
                .padding(horizontal = PamSpacing.md),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = label,
                style = LabelL,
                color = if (selected) Ink else OnInkMuted,
                maxLines = 1,
            )
        }
    }
}

/**
 * An active filter that can be removed. The whole chip is the remove target so a 48 dp row still
 * holds one unambiguous action; the close glyph only makes the affordance visible.
 */
@Composable
private fun RemovableChip(
    label: String,
    onRemove: () -> Unit,
    leadingClose: Boolean = false,
) {
    val shape = RoundedCornerShape(PamRadius.pill)
    Row(
        modifier = Modifier
            .clip(shape)
            .background(OnInk)
            .clickable(role = Role.Button, onClick = onRemove)
            .minimumInteractiveComponentSize()
            .padding(horizontal = PamSpacing.sm, vertical = PamSpacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(PamSpacing.xxs),
    ) {
        if (leadingClose) {
            Icon(
                imageVector = Icons.Rounded.Close,
                contentDescription = null,
                tint = Ink,
                modifier = Modifier.size(14.dp),
            )
        }
        Text(text = label, style = LabelL, color = Ink, maxLines = 1)
        if (!leadingClose) {
            Icon(
                imageVector = Icons.Rounded.Close,
                contentDescription = stringResource(R.string.action_remove),
                tint = Ink,
                modifier = Modifier.size(14.dp),
            )
        }
    }
}
