package com.pam.recap.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.pam.recap.core.money.Money
import com.pam.recap.domain.model.Category
import com.pam.recap.domain.model.Direction
import com.pam.recap.ui.theme.GlassBorder
import com.pam.recap.ui.theme.GlassFill
import com.pam.recap.ui.theme.LabelM
import com.pam.recap.ui.theme.OnInk
import com.pam.recap.ui.theme.OnInkFaint
import com.pam.recap.ui.theme.OnInkMuted
import com.pam.recap.ui.theme.PamRadius
import com.pam.recap.ui.theme.PamSpacing
import com.pam.recap.ui.theme.TitleM
import com.pam.recap.ui.theme.TitleS
import com.pam.recap.ui.theme.WarningAmber

/**
 * One transaction row: circular category glyph, description, meta line, right-aligned amount and a
 * chevron. Direction is expressed by the amount tone *and* the visible "Masuk"/"Keluar" meta, and a
 * low-confidence row carries an explicit marker (FR-24) instead of a silent guess.
 */
@Composable
fun TransactionRow(
    category: Category,
    title: String,
    meta: String,
    amount: Money,
    direction: Direction,
    modifier: Modifier = Modifier,
    isLowConfidence: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(PamRadius.md))
            .background(GlassFill)
            .then(
                if (onClick != null) {
                    Modifier.clickable(onClick = onClick)
                } else {
                    Modifier
                },
            )
            .minimumInteractiveComponentSize()
            .padding(horizontal = PamSpacing.sm, vertical = PamSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(PamSpacing.sm),
    ) {
        CategoryGlyph(category = category, size = 40.dp)

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = TitleS,
                color = OnInk,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(PamSpacing.xxs),
            ) {
                Text(
                    text = meta,
                    style = LabelM,
                    color = OnInkFaint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (isLowConfidence) {
                    Icon(
                        imageVector = Icons.Rounded.WarningAmber,
                        contentDescription = null,
                        tint = WarningAmber,
                        modifier = Modifier.size(12.dp),
                    )
                }
            }
        }

        MoneyText(
            amount = amount,
            tone = if (direction == Direction.CREDIT) MoneyTone.Income else MoneyTone.Expense,
            style = TitleM,
        )

        if (onClick != null) {
            Icon(
                imageVector = Icons.Rounded.ChevronRight,
                contentDescription = null,
                tint = OnInkFaint,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/**
 * Category legend/filter chip: glyph + label + optional amount. Selected state adds a border, so
 * selection is visible without relying on fill colour alone.
 */
@Composable
fun CategoryChip(
    category: Category,
    label: String,
    modifier: Modifier = Modifier,
    amount: Money? = null,
    selected: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val shape = RoundedCornerShape(PamRadius.pill)
    Row(
        modifier = modifier
            .clip(shape)
            .background(if (selected) categoryColor(category.colorRole).copy(alpha = 0.18f) else GlassFill)
            .then(
                if (selected) {
                    Modifier.border(1.dp, categoryColor(category.colorRole).copy(alpha = 0.6f), shape)
                } else {
                    Modifier.border(1.dp, GlassBorder, shape)
                },
            )
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .minimumInteractiveComponentSize()
            .padding(horizontal = PamSpacing.sm, vertical = PamSpacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(PamSpacing.xs),
    ) {
        CategoryGlyph(category = category, size = 24.dp)
        Column {
            Text(text = label, style = TitleM, color = OnInk, maxLines = 1)
            if (amount != null) {
                CompactMoneyText(amount = amount, tone = MoneyTone.Neutral)
            }
        }
    }
}

/** Overlapping avatars used for the mailbox/consent "who" chip (design system §6). */
@Composable
fun AvatarStack(
    initials: List<String>,
    modifier: Modifier = Modifier,
    size: Dp = 28.dp,
    overlap: Dp = 8.dp,
) {
    Row(modifier = modifier) {
        initials.forEachIndexed { index, value ->
            Box(
                modifier = Modifier
                    .padding(start = if (index == 0) 0.dp else -overlap)
                    .size(size)
                    .clip(CircleShape)
                    .background(com.pam.recap.ui.theme.InkHigh)
                    .border(1.dp, GlassBorder, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(text = value, style = LabelM, color = OnInkMuted)
            }
            Spacer(modifier = Modifier.width(0.dp))
        }
    }
}
