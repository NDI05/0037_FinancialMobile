package com.pam.recap.ui.feature.overview

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Undo
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pam.recap.R
import com.pam.recap.core.money.Money
import com.pam.recap.domain.model.CategoryTotal
import com.pam.recap.domain.model.ClassifiedKind
import com.pam.recap.domain.model.CoverageStatus
import com.pam.recap.domain.model.Direction
import com.pam.recap.domain.model.MonthlySummary
import com.pam.recap.domain.model.Transaction
import com.pam.recap.domain.model.TransactionSource
import com.pam.recap.ui.components.CategoryGlyph
import com.pam.recap.ui.components.GlassCard
import com.pam.recap.ui.components.GlassTopBar
import com.pam.recap.ui.components.GroupedListCard
import com.pam.recap.ui.components.LoadingShimmer
import com.pam.recap.ui.components.MoneyText
import com.pam.recap.ui.components.MoneyTone
import com.pam.recap.ui.components.MonthSelector
import com.pam.recap.ui.components.NoticeBanner
import com.pam.recap.ui.components.PamScaffold
import com.pam.recap.ui.components.TransactionRow
import com.pam.recap.ui.components.categoryColor
import com.pam.recap.ui.components.categoryIcon
import com.pam.recap.ui.feature.status.UpdateStatusTopBarAction
import com.pam.recap.ui.theme.Accent
import com.pam.recap.ui.theme.BodyM
import com.pam.recap.ui.theme.BodyS
import com.pam.recap.ui.theme.DisplayL
import com.pam.recap.ui.theme.HeadlineM
import com.pam.recap.ui.theme.IncomeMint
import com.pam.recap.ui.theme.LabelEyebrow
import com.pam.recap.ui.theme.LabelL
import com.pam.recap.ui.theme.LabelM
import com.pam.recap.ui.theme.OnInk
import com.pam.recap.ui.theme.OnInkFaint
import com.pam.recap.ui.theme.OnInkMuted
import com.pam.recap.ui.theme.OutlineVariant
import com.pam.recap.ui.theme.PamRadius
import com.pam.recap.ui.theme.PamSpacing
import com.pam.recap.ui.theme.Surface
import com.pam.recap.ui.theme.SurfaceLow
import com.pam.recap.ui.theme.TitleL
import com.pam.recap.ui.theme.TitleM
import com.pam.recap.ui.util.PamDateFormat
import com.pam.recap.ui.util.appErrorRes
import com.pam.recap.ui.util.categoryLabelRes
import com.pam.recap.ui.util.coverageBannerRes
import com.pam.recap.ui.util.directionLabelRes

/**
 * Stitch Ringkasan: centred title with the update-status icon, a compact month pill, the Option B
 * bank card (expense hero + income drill-down), a six-column category chart and the three latest
 * rows inside one grouped list.
 *
 * [monthTransactions] is additive and optional: when the host already holds the month's rows it
 * yields exact per-direction counts. Without it the authoritative amounts still come from
 * [MonthlySummary], while Mandiri's EMAIL count uses the summary arithmetic (recorded minus manual).
 */
@Composable
fun OverviewScreen(
    onOpenTransactions: (categoryCode: String?, monthId: String?, direction: Direction?) -> Unit,
    onOpenTransaction: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: OverviewViewModel = hiltViewModel(),
    monthTransactions: List<Transaction> = emptyList(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val month = state.month
    val summary = state.summary
    val rowsForBanks = monthTransactions.ifEmpty { state.monthTransactions }

    PamScaffold(
        modifier = modifier,
        showAurora = false,
        topBar = {
            GlassTopBar(
                title = stringResource(R.string.overview_title),
                status = { UpdateStatusTopBarAction() },
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
                contentPadding = PaddingValues(bottom = PamSpacing.md),
            ) {
                item {
                    // Arrows stop at the rolling-year boundaries; no month outside the window is reachable.
                    val window = viewModel.window()
                    Box(modifier = Modifier.padding(top = PamSpacing.xs)) {
                        MonthSelector(
                            label = month.label(),
                            onPrevious = viewModel::previousMonth,
                            onNext = viewModel::nextMonth,
                            canGoPrevious = window.canGoEarlier(month),
                            canGoNext = window.canGoLater(month),
                            partial = window.isPartial(month),
                        )
                    }
                }

                if (summary != null) {
                    item {
                        Spacer(modifier = Modifier.height(38.dp))
                        val counts = rowsForBanks
                            .takeIf { it.isNotEmpty() }
                            ?.let { monthlyDirectionCounts(it, month) }
                        val mandiriCount = if (rowsForBanks.isNotEmpty()) {
                            emailTransactionCount(rowsForBanks, month)
                        } else {
                            summaryEmailCount(summary)
                        }
                        BankCards(
                            cards = buildBankCards(month = month, mandiriEmailCount = mandiriCount),
                            expense = summary.grossOutflow,
                            income = summary.grossInflow,
                            expenseCount = counts?.expense,
                            incomeCount = counts?.income,
                            onOpenExpense = {
                                onOpenTransactions(null, month.id(), Direction.DEBIT)
                            },
                            onOpenIncome = {
                                onOpenTransactions(null, month.id(), Direction.CREDIT)
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }

                    if (summary.hasData) {
                        item {
                            Spacer(modifier = Modifier.height(2.dp))
                            CategoryBreakdown(
                                summary = summary,
                                onOpenCategory = { code -> onOpenTransactions(code, month.id(), null) },
                                onSeeAll = { onOpenTransactions(null, month.id(), null) },
                            )
                        }
                    } else {
                        item {
                            Spacer(modifier = Modifier.height(24.dp))
                            EmptyMonthCard(coverage = summary.coverage)
                        }
                    }
                }

                if (state.recent.isNotEmpty()) {
                    item {
                        Spacer(modifier = Modifier.height(28.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = stringResource(R.string.overview_recent_title),
                                style = TitleM,
                                color = OnInk,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                text = stringResource(R.string.action_see_all),
                                style = LabelL,
                                color = OnInkMuted,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(PamRadius.sm))
                                    .clickable(role = Role.Button) {
                                        onOpenTransactions(null, month.id(), null)
                                    }
                                    .padding(horizontal = PamSpacing.xs, vertical = PamSpacing.xs),
                            )
                        }
                    }
                    item {
                        GroupedListCard(radius = PamRadius.xl) {
                            state.recent.take(RECENT_ROWS).forEachIndexed { index, transaction ->
                                if (index > 0) {
                                    HorizontalDivider(
                                        color = OutlineVariant,
                                        modifier = Modifier.padding(start = PamSpacing.md),
                                    )
                                }
                                TransactionRow(
                                    category = transaction.category,
                                    title = transaction.description,
                                    meta = stringResource(categoryLabelRes(transaction.category)),
                                    amount = transaction.amount,
                                    direction = transaction.direction,
                                    isLowConfidence = transaction.isLowConfidence,
                                    hasUnconfirmedSuggestion = transaction.hasUnconfirmedSuggestion,
                                    isManual = transaction.source == TransactionSource.MANUAL,
                                    currency = transaction.currency,
                                    showCardWrapper = false,
                                    onClick = { onOpenTransaction(transaction.id) },
                                )
                            }
                        }
                    }
                } else if (state.error != null) {
                    item {
                        NoticeBanner(
                            text = stringResource(appErrorRes(requireNotNull(state.error))),
                        )
                    }
                }
            }
        }
    }
}

/**
 * Stitch category chart: six 32 dp columns, each a percentage label, a 96 dp pill track filled from
 * the bottom, and a category glyph. The strongest category is repeated as the legend row.
 */
@Composable
private fun CategoryBreakdown(
    summary: MonthlySummary,
    onOpenCategory: (String) -> Unit,
    onSeeAll: () -> Unit,
) {
    val expenseTotals = summary.categoryTotals
        .filter { it.category.kind == ClassifiedKind.EXPENSE && !it.amount.isZero }
        .sortedByDescending { it.amount.minor }
        .take(MAX_CATEGORIES)
    val totalExpense = summary.classifiedExpense.minor.toFloat().takeIf { it > 0f }

    val shape = RoundedCornerShape(PamRadius.xl)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 274.dp)
            .clip(shape)
            .background(Surface)
            .border(1.dp, OutlineVariant, shape)
            .padding(PamSpacing.md),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.overview_top_categories),
                style = TitleM,
                color = OnInk,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = stringResource(R.string.overview_analysis_link),
                style = LabelM,
                color = OnInkMuted,
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .clip(RoundedCornerShape(PamRadius.sm))
                    .clickable(role = Role.Button, onClick = onSeeAll)
                    .padding(horizontal = PamSpacing.xxs, vertical = PamSpacing.xxs),
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top,
        ) {
            expenseTotals.forEachIndexed { index, total ->
                val share = totalExpense
                    ?.let { (total.amount.minor.toFloat() / it).coerceIn(0f, 1f) }
                    ?: 0f
                CategoryColumn(
                    total = total,
                    share = share,
                    fill = columnFill(index),
                    highlighted = index == 0,
                    onClick = { onOpenCategory(total.category.code) },
                )
            }
            // Keep six slots so the chart never stretches to two wide columns on sparse months.
            repeat((MAX_CATEGORIES - expenseTotals.size).coerceAtLeast(0)) {
                Spacer(modifier = Modifier.width(COLUMN_WIDTH))
            }
        }
        if (expenseTotals.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            HorizontalDivider(color = OutlineVariant)
            Spacer(modifier = Modifier.height(8.dp))
            val top = expenseTotals.first()
            val percent = ((top.amount.minor.toFloat() / (totalExpense ?: 1f)) * 100).toInt()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .minimumInteractiveComponentSize()
                    .clip(RoundedCornerShape(PamRadius.sm))
                    .clickable(
                        role = Role.Button,
                        onClick = { onOpenCategory(top.category.code) },
                    )
                    .padding(vertical = PamSpacing.xxs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = top.serverLabel ?: stringResource(categoryLabelRes(top.category)),
                    style = BodyS,
                    color = OnInk,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "${moneyLabel(top.amount)} · $percent%",
                    style = LabelL,
                    color = OnInk,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun CategoryColumn(
    total: CategoryTotal,
    share: Float,
    fill: androidx.compose.ui.graphics.Color,
    highlighted: Boolean,
    onClick: () -> Unit,
) {
    val percent = (share * 100).toInt()
    Column(
        modifier = Modifier
            .width(COLUMN_WIDTH)
            .clip(RoundedCornerShape(PamRadius.sm))
            .clickable(role = Role.Button, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "$percent%",
            style = LabelEyebrow,
            color = OnInkMuted,
            maxLines = 1,
        )
        Spacer(modifier = Modifier.height(6.dp))
        Box(
            modifier = Modifier
                .width(COLUMN_WIDTH)
                .height(BAR_HEIGHT)
                .clip(RoundedCornerShape(PamRadius.lg))
                .background(SurfaceLow),
            contentAlignment = Alignment.BottomCenter,
        ) {
            Box(
                modifier = Modifier
                    .width(COLUMN_WIDTH - 4.dp)
                    .fillMaxHeight(share.coerceIn(0.02f, 1f))
                    .clip(RoundedCornerShape(PamRadius.lg))
                    .background(fill),
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(if (highlighted) OnInk else SurfaceLow),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = categoryIcon(total.category),
                contentDescription = null,
                tint = if (highlighted) Accent else OnInk,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

private fun columnFill(index: Int) = when (index) {
    0 -> Accent
    1 -> OnInk
    2 -> OnInkMuted
    3 -> OnInkFaint
    else -> OutlineVariant
}

private fun moneyLabel(amount: Money): String = com.pam.recap.core.money.IdrFormatter.format(amount)

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
    // Absence of matching email is not "no spending"; the import CTA lives in "Status pembaruan".
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.overview_empty_eyebrow),
            style = HeadlineM,
            color = OnInk,
        )
        Spacer(modifier = Modifier.height(PamSpacing.xs))
        Text(
            text = stringResource(coverageBannerRes(coverage)),
            style = BodyM,
            color = OnInkMuted,
        )
    }
}

private val COLUMN_WIDTH = 32.dp
private val BAR_HEIGHT = 88.dp
private const val MAX_CATEGORIES = 6
private const val RECENT_ROWS = 3
