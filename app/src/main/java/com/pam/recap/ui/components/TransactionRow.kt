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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.pam.recap.core.money.Money
import com.pam.recap.domain.model.Category
import com.pam.recap.domain.model.Direction
import com.pam.recap.ui.theme.GlassBorder
import com.pam.recap.ui.theme.GlassFill
import com.pam.recap.ui.theme.IncomeMint
import com.pam.recap.ui.theme.LabelM
import com.pam.recap.ui.theme.OnInk
import com.pam.recap.ui.theme.OnInkFaint
import com.pam.recap.ui.theme.OnInkMuted
import com.pam.recap.ui.theme.PamRadius
import com.pam.recap.ui.theme.PamSpacing
import com.pam.recap.ui.theme.Surface
import com.pam.recap.ui.theme.SurfaceLow
import com.pam.recap.ui.theme.Violet
import com.pam.recap.ui.theme.TitleM
import com.pam.recap.ui.theme.TitleS
import com.pam.recap.ui.theme.WarningAmber

/**
 * One transaction row in the reference's shape: category glyph, description, a meta line that ends
 * in the visible source (Livin mail or manual entry), and a right-aligned *signed* amount — `+`
 * green for income, `−` ink for spending — so direction never depends on colour alone. A
 * low-confidence row carries an explicit marker (FR-24) instead of a silent guess, and an
 * unconfirmed model suggestion is labelled (FR-25).
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
    hasUnconfirmedSuggestion: Boolean = false,
    isManual: Boolean = false,
    currency: String = "IDR",
    onClick: (() -> Unit)? = null,
) {
    val isIncome = direction == Direction.CREDIT
    val shape = RoundedCornerShape(PamRadius.md)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Surface)
            .border(1.dp, GlassBorder, shape)
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

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = title,
                style = TitleS,
                color = OnInk,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(PamSpacing.xs),
            ) {
                Text(
                    text = meta,
                    style = LabelM,
                    color = OnInkFaint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                SourceTag(isManual = isManual)
            }
            if (isLowConfidence || hasUnconfirmedSuggestion ||
                !com.pam.recap.core.money.IdrFormatter.isIdr(currency)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(PamSpacing.xxs),
                ) {
                    if (isLowConfidence) {
                        Icon(
                            imageVector = Icons.Rounded.WarningAmber,
                            contentDescription = null,
                            tint = WarningAmber,
                            modifier = Modifier.size(12.dp),
                        )
                    }
                    if (hasUnconfirmedSuggestion) {
                        // A model-suggested category the user has not confirmed yet: a distinct
                        // label, not the low-confidence warning (which is about data quality).
                        Text(
                            text = stringResource(com.pam.recap.R.string.status_ai_suggestion),
                            style = LabelM,
                            color = Violet,
                            maxLines = 1,
                        )
                    }
                    if (!com.pam.recap.core.money.IdrFormatter.isIdr(currency)) {
                        Text(
                            text = stringResource(com.pam.recap.R.string.non_idr_marker),
                            style = LabelM,
                            color = WarningAmber,
                            maxLines = 1,
                        )
                    }
                }
            }
        }

        Text(
            text = amountWithSign(amount, currency, isIncome),
            style = TitleM.copy(fontFeatureSettings = "tnum"),
            color = if (isIncome) IncomeMint else OnInk,
            maxLines = 1,
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

/** `+Rp…` for income, `−Rp…` for spending; the sign is copy, the colour only reinforces it. */
private fun amountWithSign(amount: Money, currency: String, isIncome: Boolean): String =
    (if (isIncome) "+" else "-") + amountLabel(amount, currency)

/**
 * Where the row came from. Only Livin mail is ingested today, so the email source is named for the
 * mailbox instead of a generic "Email"; manual entries stay labelled as such (FR-26).
 */
@Composable
private fun SourceTag(isManual: Boolean) {
    Text(
        text = stringResource(
            if (isManual) com.pam.recap.R.string.status_manual else com.pam.recap.R.string.detail_source_email,
        ),
        style = LabelM,
        color = OnInkMuted,
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(PamRadius.pill))
            .background(SurfaceLow)
            .border(1.dp, GlassBorder, RoundedCornerShape(PamRadius.pill))
            .padding(horizontal = PamSpacing.xs, vertical = 1.dp),
    )
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

/** Selectable pill without a category glyph, used for the "all categories" filter. */
@Composable
fun FilterPill(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(PamRadius.pill)
    Box(
        modifier = modifier
            .clip(shape)
            .background(if (selected) Violet.copy(alpha = 0.18f) else GlassFill)
            .border(1.dp, if (selected) Violet.copy(alpha = 0.6f) else GlassBorder, shape)
            .clickable(onClick = onClick)
            .minimumInteractiveComponentSize()
            .padding(horizontal = PamSpacing.md, vertical = PamSpacing.xs),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = label, style = TitleM, color = if (selected) OnInk else OnInkMuted, maxLines = 1)
    }
}
