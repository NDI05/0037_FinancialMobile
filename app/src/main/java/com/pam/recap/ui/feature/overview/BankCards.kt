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
import com.pam.recap.core.time.JakartaMonth
import com.pam.recap.domain.model.Direction
import com.pam.recap.domain.model.MonthlySummary
import com.pam.recap.domain.model.Transaction
import com.pam.recap.domain.model.TransactionSource
import com.pam.recap.ui.theme.BodyS
import com.pam.recap.ui.theme.DisplayL
import com.pam.recap.ui.theme.LabelM
import com.pam.recap.ui.theme.OnInk
import com.pam.recap.ui.theme.OnInkFaint
import com.pam.recap.ui.theme.OutlineVariant
import com.pam.recap.ui.theme.PamRadius
import com.pam.recap.ui.theme.PamSpacing
import com.pam.recap.ui.theme.Surface
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
 * Stitch Option B: one full-width card when only Mandiri exists, otherwise a snapping pager with a
 * position indicator and accessible previous/next controls. The carousel is presentational only —
 * swiping never changes the transaction filter.
 */
@Composable
fun BankCards(cards: List<BankCardData>, modifier: Modifier = Modifier) {
    if (cards.isEmpty()) return
    if (cards.size == 1) {
        BankCard(card = cards.first(), modifier = modifier.fillMaxWidth())
        return
    }
    BankCardPager(cards = cards, modifier = modifier)
}

@Composable
private fun BankCardPager(cards: List<BankCardData>, modifier: Modifier = Modifier) {
    val pagerState = rememberPagerState(pageCount = { cards.size })
    val scope = rememberCoroutineScope()
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(PamSpacing.xs)) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxWidth(),
            pageSpacing = PamSpacing.sm,
        ) { page ->
            BankCard(card = cards[page], modifier = Modifier.fillMaxWidth())
        }
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(
                onClick = {
                    scope.launch { pagerState.animateScrollToPage((pagerState.currentPage - 1).coerceAtLeast(0)) }
                },
                enabled = pagerState.currentPage > 0,
                colors = IconButtonDefaults.iconButtonColors(
                    contentColor = OnInk,
                    disabledContentColor = OnInkFaint.copy(alpha = 0.38f),
                ),
            ) {
                Icon(
                    imageVector = Icons.Rounded.ChevronLeft,
                    contentDescription = stringResource(R.string.bank_card_previous),
                    modifier = Modifier.size(20.dp),
                )
            }
            Row(
                modifier = Modifier.weight(1f),
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
                Spacer(modifier = Modifier.width(PamSpacing.xs))
                Text(
                    text = stringResource(
                        R.string.bank_card_position,
                        pagerState.currentPage + 1,
                        cards.size,
                    ),
                    style = LabelM,
                    color = OnInkFaint,
                )
            }
            IconButton(
                onClick = {
                    scope.launch {
                        pagerState.animateScrollToPage(
                            (pagerState.currentPage + 1).coerceAtMost(cards.lastIndex),
                        )
                    }
                },
                enabled = pagerState.currentPage < cards.lastIndex,
                colors = IconButtonDefaults.iconButtonColors(
                    contentColor = OnInk,
                    disabledContentColor = OnInkFaint.copy(alpha = 0.38f),
                ),
            ) {
                Icon(
                    imageVector = Icons.Rounded.ChevronRight,
                    contentDescription = stringResource(R.string.bank_card_next),
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

@Composable
private fun BankCardDot(selected: Boolean, label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .minimumInteractiveComponentSize()
            .size(32.dp)
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

@Composable
private fun BankCard(card: BankCardData, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(PamRadius.xl)
    Column(
        modifier = modifier
            .heightIn(min = 168.dp)
            .clip(shape)
            .background(Surface)
            .border(1.dp, OutlineVariant, shape)
            .padding(PamSpacing.lg),
        verticalArrangement = Arrangement.spacedBy(PamSpacing.md),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BankMonogram(card)
            Spacer(modifier = Modifier.width(PamSpacing.sm))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = card.bankName,
                    style = TitleL,
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
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = pluralStringResource(
                    R.plurals.overview_recorded_count,
                    card.transactionCount,
                    card.transactionCount,
                ),
                style = DisplayL,
                color = if (card.isPreview) OnInkFaint else OnInk,
            )
            Text(
                text = if (card.isPreview) {
                    stringResource(R.string.bank_card_preview_note)
                } else {
                    stringResource(R.string.bank_card_recorded)
                },
                style = BodyS,
                color = OnInkFaint,
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(PamSpacing.xxs)) {
            Text(
                text = stringResource(R.string.bank_card_period, card.periodLabel),
                style = BodyS,
                color = OnInkFaint,
            )
            if (card.isPreview) PreviewChip() else EmailDerivedChip()
        }
    }
}

@Composable
private fun BankMonogram(card: BankCardData) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(PamRadius.sm))
            .background(if (card.isPreview) Surface else OnInk),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = card.bankName.take(1),
            style = TitleM,
            color = if (card.isPreview) OnInk else Surface,
        )
    }
}

@Composable
private fun EmailDerivedChip() {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(PamRadius.pill))
            .background(com.pam.recap.ui.theme.IncomeMintSoft)
            .padding(horizontal = PamSpacing.xs, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(PamSpacing.xxs),
    ) {
        Icon(
            imageVector = Icons.Rounded.MailOutline,
            contentDescription = null,
            tint = com.pam.recap.ui.theme.IncomeMint,
            modifier = Modifier.size(12.dp),
        )
        Text(
            text = stringResource(R.string.bank_card_data_source),
            style = LabelM,
            color = com.pam.recap.ui.theme.IncomeMint,
        )
    }
}

@Composable
private fun PreviewChip() {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(PamRadius.pill))
            .background(WarningAmber.copy(alpha = 0.12f))
            .padding(horizontal = PamSpacing.xs, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(PamSpacing.xxs),
    ) {
        Icon(
            imageVector = Icons.Rounded.Visibility,
            contentDescription = null,
            tint = WarningAmber,
            modifier = Modifier.size(12.dp),
        )
        Text(
            text = stringResource(R.string.bank_card_preview),
            style = LabelM,
            color = WarningAmber,
        )
    }
}
