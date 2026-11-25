package com.pam.recap.ui.feature.overview

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.MailOutline
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pam.recap.R
import com.pam.recap.core.money.Money
import com.pam.recap.core.time.JakartaMonth
import com.pam.recap.domain.model.Direction
import com.pam.recap.domain.model.MonthlySummary
import com.pam.recap.domain.model.Transaction
import com.pam.recap.domain.model.TransactionSource
import com.pam.recap.ui.components.MoneyText
import com.pam.recap.ui.components.MoneyTone
import com.pam.recap.ui.theme.BodyS
import com.pam.recap.ui.theme.DisplayL
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
import com.pam.recap.ui.theme.WarningAmber
import kotlinx.coroutines.launch

/** Fixed, clearly-labelled sample counts for banks PAM cannot see yet (no bank dimension in DTO). */
const val JAGO_PREVIEW_COUNT: Int = 9
const val BRI_PREVIEW_COUNT: Int = 12

/**
 * One card in the Stitch bank carousel. Mandiri is fed by real EMAIL rows for the selected month;
 * previews are synthetic and say so — they never claim an account balance.
 */
data class BankCardData(
    val key: String,
    val bankName: String,
    val productName: String,
    val periodLabel: String,
    val transactionCount: Int,
    val isPreview: Boolean,
    val isEmailDerived: Boolean,
)

/** Direction split for the two summary cards (all posted IDR rows, manual included). */
data class OverviewDirectionCounts(
    val expense: Int,
    val income: Int,
)

/**
 * Counts EMAIL rows that actually contribute to the month totals. MANUAL rows, failed parses and
 * non-IDR rows are excluded, exactly like [com.pam.recap.domain.model.Transaction.countsTowardsTotals].
 */
fun emailTransactionCount(transactions: List<Transaction>, month: JakartaMonth): Int = transactions.count { transaction ->
    transaction.source == TransactionSource.EMAIL &&
        transaction.countsTowardsTotals &&
        month.contains(transaction.eventAt)
}

/** EMAIL-only month count derived from the summary (transactionCount includes MANUAL). */
fun recordedEmailCount(transactionCount: Int, manualEntryCount: Int): Int =
    (transactionCount - manualEntryCount).coerceAtLeast(0)

/** Same rule as [recordedEmailCount], reading the summary directly. */
fun summaryEmailCount(summary: MonthlySummary): Int =
    recordedEmailCount(summary.transactionCount, summary.manualEntryCount)

/** Expense/income counts for the summary cards; empty input means "unknown", not zero. */
fun monthlyDirectionCounts(transactions: List<Transaction>, month: JakartaMonth): OverviewDirectionCounts {
    var expense = 0
    var income = 0
    transactions.forEach { transaction ->
        if (transaction.countsTowardsTotals && month.contains(transaction.eventAt)) {
            if (transaction.direction == Direction.DEBIT) expense++ else income++
        }
    }
    return OverviewDirectionCounts(expense = expense, income = income)
}

/**
 * Pure carousel data. [mandiriEmailCount] must already be EMAIL-only; preview counts are fixed
 * placeholders and deliberately do not react to the selected month's rows.
 */
fun buildBankCards(
    month: JakartaMonth,
    mandiriEmailCount: Int,
    includePreviews: Boolean = true,
): List<BankCardData> {
    val period = month.label()
    return buildList {
        add(
            BankCardData(
                key = "mandiri",
                bankName = "Mandiri",
                productName = "Livin' by Mandiri",
                periodLabel = period,
                transactionCount = mandiriEmailCount.coerceAtLeast(0),
                isPreview = false,
                isEmailDerived = true,
            ),
        )
        if (includePreviews) {
            add(
                BankCardData(
                    key = "jago",
                    bankName = "Jago",
                    productName = "Kantong Utama",
                    periodLabel = period,
                    transactionCount = JAGO_PREVIEW_COUNT,
                    isPreview = true,
                    isEmailDerived = false,
                ),
            )
            add(
                BankCardData(
                    key = "bri",
                    bankName = "BRI",
                    productName = "BRImo",
                    periodLabel = period,
                    transactionCount = BRI_PREVIEW_COUNT,
                    isPreview = true,
                    isEmailDerived = false,
                ),
            )
        }
    }
}

/**
 * Stitch Option B: one full-width card when only Mandiri exists, otherwise a snapping pager with
 * a position indicator. The carousel is presentational only — swiping never changes the filter.
 */
@Composable
fun BankCards(
    cards: List<BankCardData>,
    expense: Money,
    income: Money,
    expenseCount: Int?,
    incomeCount: Int?,
    onOpenExpense: () -> Unit,
    onOpenIncome: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (cards.isEmpty()) return
    if (cards.size == 1) {
        BankCard(
            card = cards.first(),
            expense = expense,
            income = income,
            expenseCount = expenseCount,
            incomeCount = incomeCount,
            onOpenExpense = onOpenExpense,
            onOpenIncome = onOpenIncome,
            modifier = modifier.fillMaxWidth(),
        )
        return
    }
    BankCardPager(
        cards = cards,
        expense = expense,
        income = income,
        expenseCount = expenseCount,
        incomeCount = incomeCount,
        onOpenExpense = onOpenExpense,
        onOpenIncome = onOpenIncome,
        modifier = modifier,
    )
}

@Composable
private fun BankCardPager(
    cards: List<BankCardData>,
    expense: Money,
    income: Money,
    expenseCount: Int?,
    incomeCount: Int?,
    onOpenExpense: () -> Unit,
    onOpenIncome: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val pagerState = rememberPagerState(pageCount = { cards.size })
    val scope = rememberCoroutineScope()
    Column(modifier = modifier.fillMaxWidth()) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxWidth(),
            pageSpacing = PamSpacing.sm,
        ) { page ->
            BankCard(
                card = cards[page],
                expense = expense,
                income = income,
                expenseCount = expenseCount,
                incomeCount = incomeCount,
                onOpenExpense = onOpenExpense,
                onOpenIncome = onOpenIncome,
                hasPrevious = page > 0,
                hasNext = page < cards.lastIndex,
                onPrevious = {
                    scope.launch {
                        pagerState.animateScrollToPage((pagerState.currentPage - 1).coerceAtLeast(0))
                    }
                },
                onNext = {
                    scope.launch {
                        pagerState.animateScrollToPage(
                            (pagerState.currentPage + 1).coerceAtMost(cards.lastIndex),
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(24.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            cards.forEachIndexed { index, card ->
                BankCardDot(
                    selected = pagerState.currentPage == index,
                    label = stringResource(R.string.bank_card_select, card.bankName),
                    onClick = { scope.launch { pagerState.animateScrollToPage(index) } },
                )
            }
        }
    }
}

/** Visibility-sensitive 6 dp dot inside a 32 dp touch target. */
@Composable
private fun BankCardDot(selected: Boolean, label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(24.dp)
            .clip(CircleShape)
            .clickable(role = Role.Tab, onClick = onClick)
            .semantics {
                contentDescription = label
                this.selected = selected
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .width(if (selected) 20.dp else 6.dp)
                .height(6.dp)
                .clip(RoundedCornerShape(PamRadius.pill))
                .background(if (selected) OnInk else OutlineVariant),
        )
    }
}

/**
 * Stitch bank card: header with bank mark and carousel arrows, a dominant expense amount with the
 * month's recorded count, then an income row that drills into the credit-filtered list.
 */
@Composable
private fun BankCard(
    card: BankCardData,
    expense: Money,
    income: Money,
    expenseCount: Int?,
    incomeCount: Int?,
    onOpenExpense: () -> Unit,
    onOpenIncome: () -> Unit,
    modifier: Modifier = Modifier,
    hasPrevious: Boolean = false,
    hasNext: Boolean = false,
    onPrevious: (() -> Unit)? = null,
    onNext: (() -> Unit)? = null,
) {
    val shape = RoundedCornerShape(PamRadius.xl)
    Column(
        modifier = modifier
            .heightIn(min = 245.dp)
            .clip(shape)
            .background(Surface)
            .border(1.dp, OutlineVariant, shape)
            .padding(PamSpacing.lg),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BankMonogram(card)
            Spacer(modifier = Modifier.width(PamSpacing.sm))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = card.bankName,
                    style = TitleM,
                    color = OnInk,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = card.productName,
                    style = BodyS,
                    color = OnInkFaint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (onPrevious != null && onNext != null) {
                BankArrow(
                    icon = Icons.Rounded.ChevronLeft,
                    contentDescription = stringResource(R.string.bank_card_previous),
                    enabled = hasPrevious,
                    onClick = onPrevious,
                )
                BankArrow(
                    icon = Icons.Rounded.ChevronRight,
                    contentDescription = stringResource(R.string.bank_card_next),
                    enabled = hasNext,
                    onClick = onNext,
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        if (card.isPreview) {
            Text(
                text = stringResource(R.string.bank_card_recorded),
                style = LabelM,
                color = OnInkMuted,
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = pluralStringResource(
                    R.plurals.overview_recorded_count,
                    card.transactionCount,
                    card.transactionCount,
                ),
                style = DisplayL,
                color = OnInkFaint,
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = stringResource(R.string.bank_card_preview_note),
                style = BodyS,
                color = OnInkFaint,
            )
        } else {
            Text(
                text = stringResource(R.string.overview_expense_recorded),
                style = LabelM,
                color = OnInkMuted,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(role = Role.Button, onClick = onOpenExpense),
            )
            Spacer(modifier = Modifier.height(2.dp))
            MoneyText(
                amount = expense,
                tone = MoneyTone.Neutral,
                style = DisplayL,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(role = Role.Button, onClick = onOpenExpense),
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = expenseCount?.let {
                    pluralStringResource(R.plurals.overview_recorded_count, it, it)
                } ?: stringResource(R.string.bank_card_recorded),
                style = BodyS,
                color = OnInkFaint,
            )
        }

        Spacer(modifier = Modifier.height(26.dp))
        HorizontalDivider(color = OutlineVariant)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 44.dp)
                .clickable(role = Role.Button, onClick = onOpenIncome)
                .padding(vertical = PamSpacing.xxs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.overview_income_recorded),
                style = LabelM,
                color = OnInkMuted,
                modifier = Modifier.weight(1f),
            )
            MoneyText(amount = income, tone = MoneyTone.Income, style = TitleL)
            Icon(
                imageVector = Icons.Rounded.ChevronRight,
                contentDescription = null,
                tint = OnInkFaint,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun BankArrow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(32.dp),
        colors = IconButtonDefaults.iconButtonColors(
            contentColor = OnInk,
            disabledContentColor = OnInkFaint.copy(alpha = 0.38f),
        ),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
private fun BankMonogram(card: BankCardData) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(RoundedCornerShape(PamRadius.md))
            .background(com.pam.recap.ui.theme.SurfaceLow),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = card.bankName.take(1),
            style = TitleM,
            color = OnInk,
        )
    }
}
