package com.pam.recap.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
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
import com.pam.recap.ui.theme.BodyS
import com.pam.recap.ui.theme.GlassBorder
import com.pam.recap.ui.theme.GlassFill
import com.pam.recap.ui.theme.IncomeMint
import com.pam.recap.ui.theme.LabelEyebrow
import com.pam.recap.ui.theme.LabelM
import com.pam.recap.ui.theme.OnInk
import com.pam.recap.ui.theme.OnInkMuted
import com.pam.recap.ui.theme.OnWarningContainer
import com.pam.recap.ui.theme.OutlineVariant
import com.pam.recap.ui.theme.PamRadius
import com.pam.recap.ui.theme.PamSpacing
import com.pam.recap.ui.theme.Surface
import com.pam.recap.ui.theme.SurfaceContainer
import com.pam.recap.ui.theme.TitleM
import com.pam.recap.ui.theme.WarningAmber
import com.pam.recap.ui.theme.WarningContainer
import com.pam.recap.ui.theme.Violet

/**
 * Stitch transaction row: clean merchant/description title, category + source badge (`Email Livin`
 * or `Manual`, plus `Saran AI` warm orange chip when unconfirmed) in a wrapping FlowRow, and a
 * right-aligned signed tabular amount (`+Rp…` green for income, `-Rp…` ink for spending).
 *
 * Pass [showCardWrapper] = false when placed inside a [GroupedListCard] with dividers.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TransactionRow(
    @Suppress("UNUSED_PARAMETER") category: Category,
    title: String,
    meta: String,
    amount: Money,
    direction: Direction,
    modifier: Modifier = Modifier,
    isLowConfidence: Boolean = false,
    hasUnconfirmedSuggestion: Boolean = false,
    isManual: Boolean = false,
    currency: String = "IDR",
    showCardWrapper: Boolean = true,
    onClick: (() -> Unit)? = null,
) {
    val isIncome = direction == Direction.CREDIT
    val shape = RoundedCornerShape(PamRadius.md)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (showCardWrapper) {
                    Modifier
                        .clip(shape)
                        .background(Surface)
                        .border(1.dp, OutlineVariant, shape)
                } else {
                    Modifier
                },
            )
            .then(
                if (onClick != null) {
                    Modifier.clickable(onClick = onClick)
                } else {
                    Modifier
                },
            )
            .minimumInteractiveComponentSize()
            .padding(horizontal = PamSpacing.md, vertical = PamSpacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(PamSpacing.sm),
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = title,
                style = TitleM,
                color = OnInk,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            FlowRow(
                verticalArrangement = Arrangement.spacedBy(4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                if (hasUnconfirmedSuggestion) {
                    Text(
                        text = stringResource(com.pam.recap.R.string.status_ai_suggestion),
                        style = LabelEyebrow,
                        color = OnWarningContainer,
                        maxLines = 1,
                        modifier = Modifier
                            .clip(RoundedCornerShape(PamRadius.pill))
                            .background(WarningContainer)
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                    )
                } else if (meta.isNotBlank()) {
                    Text(
                        text = meta,
                        style = BodyS,
                        color = OnInkMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    text = "·",
                    style = BodyS,
                    color = OnInkMuted,
                )
                SourceTag(isManual = isManual)
                if (isLowConfidence) {
                    Icon(
                        imageVector = Icons.Rounded.WarningAmber,
                        contentDescription = stringResource(com.pam.recap.R.string.status_low_confidence),
                        tint = WarningAmber,
                        modifier = Modifier.size(14.dp),
                    )
                }
                if (!com.pam.recap.core.money.IdrFormatter.isIdr(currency)) {
                    Text(
                        text = stringResource(com.pam.recap.R.string.non_idr_marker),
                        style = LabelEyebrow,
                        color = WarningAmber,
                        maxLines = 1,
                    )
                }
            }
        }

        Text(
            text = amountWithSign(amount, currency, isIncome),
            style = TitleM.copy(fontFeatureSettings = "tnum"),
            color = if (isIncome) IncomeMint else OnInk,
            maxLines = 1,
        )
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
        style = LabelEyebrow,
        color = OnInkMuted,
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(SurfaceContainer)
            .padding(horizontal = 8.dp, vertical = 2.dp),
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
