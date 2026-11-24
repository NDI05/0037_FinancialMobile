package com.pam.recap.ui.feature.overview

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Undo
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pam.recap.R
import com.pam.recap.core.money.Money
import com.pam.recap.core.money.PercentChange
import com.pam.recap.core.time.JakartaMonth
import com.pam.recap.domain.model.Category
import com.pam.recap.domain.model.CategoryTotal
import com.pam.recap.domain.model.ClassifiedKind
import com.pam.recap.domain.model.CoverageStatus
import com.pam.recap.domain.model.Direction
import com.pam.recap.domain.model.MonthlySummary
import com.pam.recap.domain.model.Transaction
import com.pam.recap.ui.components.AmountPill
import com.pam.recap.ui.components.CategoryGlyph
import com.pam.recap.ui.components.EyebrowText
import com.pam.recap.ui.components.GlassCard
import com.pam.recap.ui.components.GlassTopBar
import com.pam.recap.ui.components.LoadingShimmer
import com.pam.recap.ui.components.MoneyText
import com.pam.recap.ui.components.MoneyTone
import com.pam.recap.ui.components.MonthSelector
import com.pam.recap.ui.components.NoticeBanner
import com.pam.recap.ui.components.PamScaffold
import com.pam.recap.ui.components.PillButton
import com.pam.recap.ui.components.PillButtonStyle
import com.pam.recap.ui.components.ProgressRing
import com.pam.recap.ui.components.SectionHeader
import com.pam.recap.ui.components.StatTile
import com.pam.recap.ui.components.StatusChip
import com.pam.recap.ui.components.StatusChipKind
import com.pam.recap.ui.components.TransactionRow
import com.pam.recap.ui.components.categoryColor
import com.pam.recap.ui.components.sharePercentText
import com.pam.recap.ui.theme.BodyM
import com.pam.recap.ui.theme.DisplayXl
import com.pam.recap.ui.theme.GlassBorderStrong
import com.pam.recap.ui.theme.HeadlineL
import com.pam.recap.ui.theme.IncomeMint
import com.pam.recap.ui.theme.LabelM
import com.pam.recap.ui.theme.OnInk
import com.pam.recap.ui.theme.OnInkFaint
import com.pam.recap.ui.theme.OnInkMuted
import com.pam.recap.ui.theme.PamGradients
import com.pam.recap.ui.theme.PamRadius
import com.pam.recap.ui.theme.PamSize
import com.pam.recap.ui.theme.PamSpacing
import com.pam.recap.ui.theme.TitleL
import com.pam.recap.ui.theme.TitleM
import com.pam.recap.ui.theme.TitleS
import com.pam.recap.ui.theme.WarningAmber
import com.pam.recap.ui.util.PamDateFormat
import com.pam.recap.ui.util.categoryLabelRes
import com.pam.recap.ui.util.coverageBannerRes
import kotlin.math.roundToInt

/**
 * §8 Overview — the flagship surface.
 *
 * Reference translation: the left frame's full-bleed hero becomes a gradient glass card holding the
 * net cash flow, its overlay pills become the EMAIL-DERIVED and coverage chips, the author row
 * becomes the mailbox/freshness row, and the lessons list becomes the category breakdown. Every
 * total is a drill-down (FR-32).
 */
@Composable
fun OverviewScreen(
    onOpenTransactions: (categoryCode: String?, monthId: String?, direction: Direction?) -> Unit,
    onOpenTransaction: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: OverviewViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val month = state.month
    val summary = state.summary

    PamScaffold(
        modifier = modifier,
        showAurora = true,
        topBar = {
            GlassTopBar(
                title = stringResource(R.string.overview_title),
                actions = {
                    // Settings lives in the bottom navigation; the only chrome here is status.
                    com.pam.recap.ui.feature.status.UpdateStatusTopBarAction()
                },
            )
        },
    ) { padding ->
        if (month == null || (state.isLoading && summary == null)) {
            Box(modifier = Modifier.fillMaxSize().padding(padding)) {
                LoadingShimmer(rows = 3)
            }
            return@PamScaffold
        }

        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                top = PamSpacing.xs,
                bottom = PamSize.bottomBarContentPadding,
            ),
            verticalArrangement = Arrangement.spacedBy(PamSpacing.sm),
        ) {
            item {
                // Arrows stop at the rolling-year boundaries; no month outside the window is reachable.
                val window = viewModel.window()
                MonthSelector(
                    label = month.label(),
                    onPrevious = viewModel::previousMonth,
                    onNext = viewModel::nextMonth,
                    canGoPrevious = window.canGoEarlier(month),
                    canGoNext = window.canGoLater(month),
                    partial = window.isPartial(month),
                )
            }

            if (summary != null) {
                item {
                    NetCashFlowHero(
                        summary = summary,
                        comparison = state.comparison,
                        connectionLabel = state.connection?.mailboxLabel,
                        lastImportAt = state.connection?.lastImportAt,
                    )
                }

                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(PamSpacing.sm)) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clickable { onOpenTransactions(null, month.id(), Direction.CREDIT) },
                        ) {
                            StatTile(
                                eyebrow = stringResource(R.string.overview_gross_in),
                                caption = deltaCaption(state.comparison?.incomeChange),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                MoneyText(
                                    amount = summary.grossInflow,
                                    tone = MoneyTone.Income,
                                    style = com.pam.recap.ui.theme.HeadlineL,
                                )
                            }
                        }
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clickable { onOpenTransactions(null, month.id(), Direction.DEBIT) },
                        ) {
                            StatTile(
                                eyebrow = stringResource(R.string.overview_gross_out),
                                caption = deltaCaption(state.comparison?.expenseChange),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                MoneyText(
                                    amount = summary.grossOutflow,
                                    tone = MoneyTone.Expense,
                                    style = com.pam.recap.ui.theme.HeadlineL,
                                )
                            }
                        }
                    }
                }

                item { CompositionCard(summary = summary) }

                if (summary.hasData) {
                    item {
                        CategoryBreakdown(
                            summary = summary,
                            onOpenCategory = { code -> onOpenTransactions(code, month.id(), null) },
                            onSeeAll = { onOpenTransactions(null, month.id(), null) },
                        )
                    }
                } else {
                    item {
                        EmptyMonthCard(coverage = summary.coverage)
                    }
                }

                if (state.recent.isNotEmpty()) {
                    item {
                        SectionHeader(
                            eyebrow = stringResource(R.string.overview_recent_eyebrow),
                            trailing = {
                                Text(
                                    text = stringResource(R.string.action_see_all),
                                    style = LabelM,
                                    color = OnInkMuted,
                                    modifier = Modifier
                                        .clickable { onOpenTransactions(null, month.id(), null) }
                                        .padding(PamSpacing.xs),
                                )
                            },
                        )
                    }
                    items(state.recent) { transaction ->
                        TransactionRow(
                            category = transaction.category,
                            title = transaction.description,
                            meta = rowMeta(transaction),
                            amount = transaction.amount,
                            direction = transaction.direction,
                            isLowConfidence = transaction.isLowConfidence,
                            hasUnconfirmedSuggestion = transaction.hasUnconfirmedSuggestion,
                            currency = transaction.currency,
                            onClick = { onOpenTransaction(transaction.id) },
                            modifier = Modifier.padding(horizontal = 0.dp),
                        )
                    }
                }
            } else if (state.error != null) {
                item {
                    NoticeBanner(text = stringResource(com.pam.recap.ui.util.appErrorRes(requireNotNull(state.error))))
                }
            }
        }
        }
    }
}

@Composable
private fun deltaCaption(change: PercentChange?): String? = when (change) {
    null -> null
    PercentChange.NotApplicable -> stringResource(R.string.percent_not_applicable)
    is PercentChange.Value -> {
        val points = kotlin.math.abs(change.percent).roundToInt()
        val percentage = if (change.isDecrease) {
            stringResource(R.string.percent_decrease, points)
        } else {
            stringResource(R.string.percent_increase, points)
        }
        "$percentage ${stringResource(R.string.overview_vs_previous)}"
    }
}

@Composable
private fun rowMeta(transaction: Transaction): String {
    val category = stringResource(categoryLabelRes(transaction.category))
    val time = PamDateFormat.time(transaction.eventAt)
    val direction = stringResource(com.pam.recap.ui.util.directionLabelRes(transaction.direction))
    // Direction before category: an ellipsis may only ever drop the category name.
    return "$time · $direction · $category"
}

/** Hero card: gradient wash + warm bloom, net cash flow as the hero number (design system §5). */
@Composable
private fun NetCashFlowHero(
    summary: MonthlySummary,
    comparison: com.pam.recap.domain.model.SummaryComparison?,
    connectionLabel: String?,
    lastImportAt: kotlin.time.Instant?,
) {
    val shape = RoundedCornerShape(PamRadius.hero)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(PamGradients.hero)
            .border(1.dp, GlassBorderStrong, shape),
    ) {
        // Amber bloom in the top-right corner, exactly like the reference's "Schedule" card.
        Canvas(modifier = Modifier.matchParentSize()) {
            // Radius and centre are shared with the brush so the bloom fades out inside the card
            // instead of ending on a visible circular edge.
            val bloomRadius = size.maxDimension * 0.92f
            val bloomCenter = Offset(size.width * 1.0f, size.height * 0.02f)
            drawCircle(
                brush = PamGradients.warmBloom(radius = bloomRadius, center = bloomCenter),
                radius = bloomRadius,
                center = bloomCenter,
            )
        }
        Column(modifier = Modifier.padding(PamSpacing.lg)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(PamSpacing.xs),
            ) {
                StatusChip(kind = StatusChipKind.EmailDerived, onDark = true)
                StatusChip(kind = summary.coverage.chipKind(), onDark = true)
            }
            Spacer(modifier = Modifier.height(PamSpacing.lg))
            EyebrowText(text = stringResource(R.string.overview_net_eyebrow), color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.8f))
            Spacer(modifier = Modifier.height(PamSpacing.xs))
            MoneyText(
                amount = summary.netCashFlow,
                tone = if (summary.netCashFlow.isNegative) MoneyTone.Expense else MoneyTone.Income,
                style = DisplayXl,
                color = androidx.compose.ui.graphics.Color.White,
            )
            Spacer(modifier = Modifier.height(PamSpacing.xs))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = summary.month.label(),
                    style = TitleM,
                    color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.8f),
                )
                Spacer(modifier = Modifier.weight(1f))
                if (comparison != null) {
                    AmountPill(change = comparison.netChange, onDark = true)
                }
            }
            if (connectionLabel != null) {
                Spacer(modifier = Modifier.height(PamSpacing.md))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(com.pam.recap.ui.theme.IncomeMint),
                    )
                    Spacer(modifier = Modifier.size(PamSpacing.xs))
                    Text(
                        text = connectionLabel,
                        style = TitleS,
                        color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.8f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    FreshnessPill(lastImportAt = lastImportAt)
                }
            }
        }
    }
}

@Composable
private fun FreshnessPill(lastImportAt: kotlin.time.Instant?, modifier: Modifier = Modifier) {
    if (lastImportAt == null) {
        Text(
            text = stringResource(R.string.overview_never_imported),
            style = LabelM,
            color = OnInkFaint,
            maxLines = 1,
            modifier = modifier,
        )
    } else {
        val date = PamDateFormat.date(lastImportAt)
        Row(
            modifier = modifier,
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(PamSpacing.xxs),
        ) {
            Icon(
                imageVector = Icons.Rounded.Schedule,
                // The full sentence is announced once, the visible text stays a bare date.
                contentDescription = stringResource(R.string.overview_last_import, date),
                tint = OnInkFaint,
                modifier = Modifier.size(12.dp),
            )
            Text(text = date, style = LabelM, color = OnInkFaint, maxLines = 1)
        }
    }
}

private fun CoverageStatus.chipKind(): StatusChipKind = when (this) {
    CoverageStatus.COMPLETE -> StatusChipKind.Complete
    CoverageStatus.PARTIAL -> StatusChipKind.Partial
    CoverageStatus.EMPTY -> StatusChipKind.Empty
    CoverageStatus.FAILED -> StatusChipKind.Failed
}

/**
 * Expense composition ring plus the classified income/expense pair. The ring is the share of the
 * largest expense category inside classified spending (FR-30), with the amount in its centre.
 */
@Composable
private fun CompositionCard(summary: MonthlySummary) {
    val expenseTotals = summary.categoryTotals
        .filter { it.category.isExpenseCategory && it.amount.isPositive }
        .sortedByDescending { it.amount.minor }
    val top = expenseTotals.firstOrNull()
    val share = if (top != null && summary.classifiedExpense.isPositive) {
        top.amount.minor.toFloat() / summary.classifiedExpense.minor.toFloat()
    } else {
        0f
    }

    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(PamSpacing.md),
        ) {
            ProgressRing(
                progress = share,
                label = stringResource(
                    R.string.overview_composition_label,
                    top?.let { total ->
                        total.serverLabel ?: stringResource(categoryLabelRes(total.category))
                    } ?: stringResource(R.string.category_uncategorized),
                    sharePercentText(share),
                ),
                size = 112.dp,
                strokeWidth = 10.dp,
                center = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = sharePercentText(share),
                            style = com.pam.recap.ui.theme.DisplayM,
                            color = OnInk,
                        )
                        Text(
                            text = stringResource(R.string.overview_composition_center),
                            style = LabelM,
                            color = OnInkMuted,
                        )
                    }
                },
            )
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(PamSpacing.xs)) {
                ClassifiedRow(
                    label = stringResource(R.string.overview_classified_income),
                    amount = summary.classifiedIncome,
                    tone = MoneyTone.Income,
                )
                ClassifiedRow(
                    label = stringResource(R.string.overview_classified_expense),
                    amount = summary.classifiedExpense,
                    tone = MoneyTone.Expense,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = pluralStringResource(R.plurals.overview_uncategorized_count, summary.uncategorizedCount, summary.uncategorizedCount),
                        style = TitleS,
                        color = OnInkFaint,
                    )
                }
            }
        }
    }
}

@Composable
private fun ClassifiedRow(label: String, amount: Money, tone: MoneyTone) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(text = label, style = TitleS, color = OnInkMuted, modifier = Modifier.weight(1f))
        MoneyText(amount = amount, tone = tone, style = TitleM)
    }
}

/** Category breakdown: glyph, label, transaction count, amount and a share bar (FR-30/FR-32). */
@Composable
private fun CategoryBreakdown(
    summary: MonthlySummary,
    onOpenCategory: (String) -> Unit,
    onSeeAll: () -> Unit,
) {
    // BR-02: this section is the *expense* breakdown, so income and transfers must not appear.
    // C2-4: a category whose linked refund exceeded its spending has a negative net; it still
    // belongs here (with an explicit refund marker) instead of vanishing or drawing an empty bar.
    val expenseTotals = summary.categoryTotals
        .filter { it.category.kind == ClassifiedKind.EXPENSE && !it.amount.isZero }
        .sortedByDescending { it.amount.minor }
        .take(MAX_CATEGORIES)

    Column(verticalArrangement = Arrangement.spacedBy(PamSpacing.xs)) {
        SectionHeader(
            eyebrow = stringResource(R.string.overview_categories_eyebrow),
            trailing = {
                Text(
                    text = stringResource(R.string.action_see_all),
                    style = LabelM,
                    color = OnInkMuted,
                    modifier = Modifier
                        .clickable(onClick = onSeeAll)
                        .padding(PamSpacing.xs),
                )
            },
        )
        expenseTotals.forEach { total ->
            CategoryRow(
                total = total,
                share = if (summary.classifiedExpense.isPositive) {
                    total.amount.minor.toFloat() / summary.classifiedExpense.minor.toFloat()
                } else {
                    0f
                },
                onClick = { onOpenCategory(total.category.code) },
            )
        }
    }
}

@Composable
private fun CategoryRow(total: CategoryTotal, share: Float, onClick: () -> Unit) {
    val color = categoryColor(total.category.colorRole)
    val isNetRefund = total.amount.isNegative
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(PamRadius.md))
            .clickable(onClick = onClick)
            .padding(vertical = PamSpacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(PamSpacing.sm),
    ) {
        CategoryGlyph(category = total.category, size = 36.dp)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = total.serverLabel ?: stringResource(categoryLabelRes(total.category)),
                style = TitleL,
                color = OnInk,
                maxLines = 1,
            )
            Text(
                text = pluralStringResource(R.plurals.overview_transaction_count, total.transactionCount, total.transactionCount),
                style = LabelM,
                color = OnInkFaint,
            )
            Spacer(modifier = Modifier.height(PamSpacing.xxs))
            if (isNetRefund) {
                RefundBadge()
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(PamRadius.pill))
                        .background(color.copy(alpha = 0.16f)),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(share.coerceIn(0f, 1f))
                            .height(4.dp)
                            .clip(RoundedCornerShape(PamRadius.pill))
                            .background(color),
                    )
                }
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            MoneyText(amount = total.amount, tone = MoneyTone.Neutral, style = TitleM)
            if (isNetRefund) {
                Text(
                    text = stringResource(R.string.overview_after_refund),
                    style = LabelM,
                    color = OnInkMuted,
                )
            }
        }
    }
}

/** Explicit marker for a category whose refunds exceeded its spending in this month (C2-4). */
@Composable
private fun RefundBadge() {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(PamRadius.pill))
            .background(IncomeMint.copy(alpha = 0.14f))
            .padding(horizontal = PamSpacing.xs, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(PamSpacing.xxs),
    ) {
        Icon(
            imageVector = Icons.Rounded.Undo,
            contentDescription = null,
            tint = IncomeMint,
            modifier = Modifier.size(12.dp),
        )
        Text(
            text = stringResource(R.string.overview_refund_badge),
            style = LabelM,
            color = IncomeMint,
        )
    }
}

@Composable
private fun EmptyMonthCard(coverage: CoverageStatus) {
    // The month-empty state stays honest (absence of matching email is not "no spending"); the
    // import CTA that used to live here moved into "Status pembaruan".
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        EyebrowText(text = stringResource(R.string.overview_empty_eyebrow))
        Spacer(modifier = Modifier.height(PamSpacing.xs))
        Text(
            text = stringResource(coverageBannerRes(coverage)),
            style = BodyM,
            color = OnInkMuted,
        )
    }
}

private const val MAX_CATEGORIES = 6
