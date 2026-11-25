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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Undo
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
import com.pam.recap.ui.components.CategoryGlyph
import com.pam.recap.ui.components.GlassCard
import com.pam.recap.ui.components.GlassTopBar
import com.pam.recap.ui.components.LoadingShimmer
import com.pam.recap.ui.components.MoneyText
import com.pam.recap.ui.components.MoneyTone
import com.pam.recap.ui.components.MonthSelector
import com.pam.recap.ui.components.NoticeBanner
import com.pam.recap.ui.components.PamScaffold
import com.pam.recap.ui.components.SectionHeader
import com.pam.recap.ui.components.categoryColor
import com.pam.recap.ui.components.TransactionRow
import com.pam.recap.ui.feature.status.UpdateStatusTopBarAction
import com.pam.recap.ui.theme.BodyM
import com.pam.recap.ui.theme.BodyS
import com.pam.recap.ui.theme.DisplayL
import com.pam.recap.ui.theme.HeadlineM
import com.pam.recap.ui.theme.IncomeMint
import com.pam.recap.ui.theme.LabelM
import com.pam.recap.ui.theme.OnInk
import com.pam.recap.ui.theme.OnInkFaint
import com.pam.recap.ui.theme.OnInkMuted
import com.pam.recap.ui.theme.OutlineVariant
import com.pam.recap.ui.theme.PamRadius
import com.pam.recap.ui.theme.PamSize
import com.pam.recap.ui.theme.PamSpacing
import com.pam.recap.ui.theme.Surface
import com.pam.recap.ui.theme.TitleL
import com.pam.recap.ui.theme.TitleM
import com.pam.recap.ui.util.PamDateFormat
import com.pam.recap.ui.util.categoryLabelRes
import com.pam.recap.ui.util.coverageBannerRes
import com.pam.recap.ui.util.appErrorRes
import com.pam.recap.ui.util.directionLabelRes

/**
 * Stitch Ringkasan: centred title with the update-status icon, a compact month pill, two summary
 * cards (expense first), the Option B bank carousel, the category breakdown and recent rows.
 *
 * [monthTransactions] is additive and optional: when the host already holds the month's rows it
 * yields exact per-direction counts on the summary cards. Without it the cards still show the
 * authoritative amounts from [MonthlySummary], while Mandiri's EMAIL count comes from the summary
 * arithmetic (transactionCount minus manualEntryCount), never from account totals.
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
                contentPadding = PaddingValues(
                    top = PamSpacing.xxs,
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
                        val counts = rowsForBanks
                            .takeIf { it.isNotEmpty() }
                            ?.let { monthlyDirectionCounts(it, month) }
                        Column(verticalArrangement = Arrangement.spacedBy(PamSpacing.xs)) {
                            SummaryCard(
                                label = stringResource(R.string.overview_expense_recorded),
                                amount = summary.grossOutflow,
                                tone = MoneyTone.Neutral,
                                count = counts?.expense,
                                onClick = { onOpenTransactions(null, month.id(), Direction.DEBIT) },
                            )
                            SummaryCard(
                                label = stringResource(R.string.overview_income_recorded),
                                amount = summary.grossInflow,
                                tone = MoneyTone.Income,
                                count = counts?.income,
                                onClick = { onOpenTransactions(null, month.id(), Direction.CREDIT) },
                            )
                        }
                    }

                    item {
                        val mandiriCount = if (rowsForBanks.isNotEmpty()) {
                            emailTransactionCount(rowsForBanks, month)
                        } else {
                            summaryEmailCount(summary)
                        }
                        BankCards(
                            cards = buildBankCards(month = month, mandiriEmailCount = mandiriCount),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }

                    if (summary.hasData) {
                        item { NetCashFlowNote(summary = summary) }
                        item {
                            CategoryBreakdown(
                                summary = summary,
                                onOpenCategory = { code -> onOpenTransactions(code, month.id(), null) },
                                onSeeAll = { onOpenTransactions(null, month.id(), null) },
                            )
                        }
                    } else {
                        item { EmptyMonthCard(coverage = summary.coverage) }
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
                                        .minimumInteractiveComponentSize()
                                        .clip(RoundedCornerShape(PamRadius.sm))
                                        .clickable(role = Role.Button) {
                                            onOpenTransactions(null, month.id(), null)
                                        }
                                        .padding(horizontal = PamSpacing.xs, vertical = PamSpacing.xs),
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

/** White bordered summary card; the whole card drills into the month list with a direction filter. */
@Composable
private fun SummaryCard(
    label: String,
    amount: Money,
    tone: MoneyTone,
    count: Int?,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(PamRadius.lg)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .minimumInteractiveComponentSize()
            .clip(shape)
            .background(Surface)
            .border(1.dp, OutlineVariant, shape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(PamSpacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(PamSpacing.sm),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = label, style = BodyM, color = OnInkMuted)
            Spacer(modifier = Modifier.height(PamSpacing.xxs))
            MoneyText(amount = amount, tone = tone, style = DisplayL)
            if (count != null) {
                Spacer(modifier = Modifier.height(PamSpacing.xxs))
                Text(
                    text = pluralStringResource(R.plurals.overview_recorded_count, count, count),
                    style = LabelM,
                    color = OnInkFaint,
                )
            }
        }
        Icon(
            imageVector = Icons.Rounded.ChevronRight,
            contentDescription = null,
            tint = OnInkFaint,
            modifier = Modifier.size(20.dp),
        )
    }
}

/**
 * One-line honesty note replacing the old dominant net-cash-flow hero. Net flow is still reported
 * to TalkBack and shown compactly; there is no duplicate data-source banner.
 */
@Composable
private fun NetCashFlowNote(summary: MonthlySummary) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(PamSpacing.xs),
    ) {
        Text(
            text = stringResource(R.string.overview_net_eyebrow),
            style = LabelM,
            color = OnInkFaint,
        )
        MoneyText(
            amount = summary.netCashFlow,
            tone = if (summary.netCashFlow.isNegative) MoneyTone.Expense else MoneyTone.Income,
            style = TitleM,
        )
    }
}

@Composable
private fun rowMeta(transaction: Transaction): String {
    val category = stringResource(categoryLabelRes(transaction.category))
    val time = PamDateFormat.time(transaction.eventAt)
    val direction = stringResource(directionLabelRes(transaction.direction))
    // Direction before category: an ellipsis may only ever drop the category name.
    return "$time · $direction · $category"
}

/** Category breakdown: glyph, label, transaction count, amount and a share bar (FR-30/FR-32). */
@Composable
private fun CategoryBreakdown(
    summary: MonthlySummary,
    onOpenCategory: (String) -> Unit,
    onSeeAll: () -> Unit,
) {
    // BR-02: this section is the *expense* breakdown, so income and transfers must not appear.
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
                        .minimumInteractiveComponentSize()
                        .clip(RoundedCornerShape(PamRadius.sm))
                        .clickable(role = Role.Button, onClick = onSeeAll)
                        .padding(horizontal = PamSpacing.xs, vertical = PamSpacing.xs),
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
                text = pluralStringResource(
                    R.plurals.overview_transaction_count,
                    total.transactionCount,
                    total.transactionCount,
                ),
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

private const val MAX_CATEGORIES = 6
