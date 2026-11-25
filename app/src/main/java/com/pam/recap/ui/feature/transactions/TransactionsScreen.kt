package com.pam.recap.ui.feature.transactions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pam.recap.R
import com.pam.recap.domain.model.Transaction
import com.pam.recap.ui.components.EmptyState
import com.pam.recap.ui.components.GlassTopBar
import com.pam.recap.ui.components.GroupedListCard
import com.pam.recap.ui.components.LoadingShimmer
import com.pam.recap.ui.components.MoneyTone
import com.pam.recap.ui.components.NoticeBanner
import com.pam.recap.ui.components.PamScaffold
import com.pam.recap.ui.components.PillButton
import com.pam.recap.ui.components.PillButtonStyle
import com.pam.recap.ui.components.SearchField
import com.pam.recap.ui.components.TransactionRow
import com.pam.recap.ui.theme.Accent
import com.pam.recap.ui.theme.LabelM
import com.pam.recap.ui.theme.OnInk
import com.pam.recap.ui.theme.OnInkFaint
import com.pam.recap.ui.theme.OnInkMuted
import com.pam.recap.ui.theme.OutlineVariant
import com.pam.recap.ui.theme.PamRadius
import com.pam.recap.ui.theme.PamSize
import com.pam.recap.ui.theme.PamSpacing
import com.pam.recap.ui.util.PamDateFormat
import com.pam.recap.ui.util.categoryLabelRes

/**
 * §8 Transactions, one route for both entry points: the bottom-nav tab and the Ringkasan
 * drill-down. Stitch structure: centred title with status icon, 48 dp search field, one horizontal
 * predicate chip strip, grouped white date cards and a lime add FAB. The AI categorisation action
 * lives at the end of the list (it is not part of the reference above-the-fold composition) and
 * every active predicate still appears as a chip (FR-20/FR-27).
 */
@Composable
fun TransactionsScreen(
    onOpenTransaction: (String) -> Unit,
    onOpenManualEntry: () -> Unit,
    modifier: Modifier = Modifier,
    /** Present only when the screen was opened as a drill-down (C2-2). */
    onBack: (() -> Unit)? = null,
    /**
     * One-shot navigation result: the month of the row the manual-entry form just saved. The list
     * reloads page one so the new row is visible without a month switch (FR-26 follow-up).
     */
    savedMonthId: String? = null,
    onSavedMonthHandled: () -> Unit = {},
    viewModel: TransactionsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val month = state.month
    val items = state.visibleItems

    LaunchedEffect(savedMonthId) {
        if (savedMonthId != null) {
            viewModel.refreshAfterSave(savedMonthId)
            onSavedMonthHandled()
        }
    }

    val filters = activeTransactionFilters(
        direction = state.direction,
        source = state.source,
        category = state.category,
        day = state.selectedDay,
    )
    // A drill-down only exists to show the filters it carried. Clearing the last one is not a new
    // query, it is "show me the whole list again" — which is the Ringkasan route's job.
    fun clearOrReturn(afterClear: Set<ActiveTransactionFilter>) {
        if (onBack != null && returnsToOverviewAfterClear(viewModel.carriedFilters, afterClear)) {
            onBack()
        }
    }

    PamScaffold(
        modifier = modifier,
        showAurora = false,
        topBar = {
            GlassTopBar(
                title = stringResource(R.string.transactions_title),
                onBack = onBack,
                status = { com.pam.recap.ui.feature.status.UpdateStatusTopBarAction() },
            )
        },
    ) { padding ->
        val addLabel = stringResource(R.string.manual_title)
        if (month == null) {
            LoadingShimmer(rows = 3, showHero = false)
            return@PamScaffold
        }
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            Column(modifier = Modifier.fillMaxSize()) {
                SearchField(
                    value = state.query,
                    onValueChange = viewModel::setQuery,
                    placeholder = stringResource(R.string.transactions_search_placeholder),
                    modifier = Modifier.padding(top = PamSpacing.xs),
                )

                TransactionFilterBar(
                    monthLabel = month.label(),
                    direction = state.direction,
                    source = state.source,
                    category = state.category,
                    day = state.selectedDay,
                    canGoPrevious = viewModel.window().canGoEarlier(month),
                    canGoNext = viewModel.window().canGoLater(month),
                    onPreviousMonth = viewModel::previousMonth,
                    onNextMonth = viewModel::nextMonth,
                    onDirectionChange = { next ->
                        val effective = if (next == state.direction) null else next
                        viewModel.selectDirection(next)
                        if (effective == null) {
                            clearOrReturn(filters - ActiveTransactionFilter.DIRECTION)
                        }
                    },
                    onSourceChange = viewModel::selectSource,
                    onCategoryCleared = {
                        viewModel.selectCategory(null)
                        clearOrReturn(filters - ActiveTransactionFilter.CATEGORY)
                    },
                    onDayCleared = {
                        viewModel.selectDay(null)
                        clearOrReturn(filters - ActiveTransactionFilter.DAY)
                    },
                    onClearAll = {
                        viewModel.clearFilters()
                        clearOrReturn(emptySet())
                    },
                    modifier = Modifier.padding(top = PamSpacing.xs),
                )

                when {
                    state.isLoading -> LoadingShimmer(rows = 4, showHero = false)

                    state.error != null -> Column(modifier = Modifier.padding(top = PamSpacing.sm)) {
                        NoticeBanner(
                            text = stringResource(
                                com.pam.recap.ui.util.appErrorRes(requireNotNull(state.error)),
                            ),
                        )
                        PillButton(
                            text = stringResource(R.string.action_retry),
                            onClick = viewModel::retry,
                            style = PillButtonStyle.Secondary,
                            modifier = Modifier.padding(top = PamSpacing.xs),
                        )
                    }

                    state.isEmptyMonth -> EmptyState(
                        eyebrow = stringResource(R.string.transactions_empty_eyebrow),
                        title = stringResource(R.string.transactions_empty_title),
                        body = stringResource(R.string.transactions_empty_body),
                        icon = Icons.Rounded.Search,
                    )

                    // With a next page still available, "no match" is not a conclusion.
                    state.isNoMatch && !state.hasMore -> EmptyState(
                        eyebrow = stringResource(R.string.transactions_nomatch_eyebrow),
                        title = stringResource(R.string.transactions_nomatch_title),
                        body = stringResource(R.string.transactions_nomatch_body),
                        icon = Icons.Rounded.Search,
                        action = {
                            PillButton(
                                text = stringResource(R.string.action_remove),
                                onClick = {
                                    viewModel.clearFilters()
                                    clearOrReturn(emptySet())
                                },
                                style = PillButtonStyle.Ghost,
                            )
                        },
                    )

                    else -> TransactionList(
                        items = items,
                        hasMore = state.hasMore,
                        isLoadingMore = state.isLoadingMore,
                        loadMoreError = state.loadMoreError,
                        onLoadMore = viewModel::loadMore,
                        onRetryLoadMore = viewModel::retryLoadMore,
                        onOpenTransaction = onOpenTransaction,
                        isClassifying = state.isClassifying,
                        classificationStatus = state.classificationStatus,
                        classificationReviewReasons = state.classificationReviewReasons,
                        classificationError = state.classificationError,
                        onClassify = viewModel::classifyUncategorized,
                        isServerMode = state.isServerMode,
                        truncated = state.truncated,
                        scanned = state.scanned,
                        isFiltered = state.isFiltered,
                    )
                }
            }

            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(bottom = PamSize.bottomBarContentPadding - 4.dp)
                    .size(56.dp)
                    .clip(RoundedCornerShape(PamRadius.xl))
                    .background(Accent)
                    .clickable(role = Role.Button, onClick = onOpenManualEntry)
                    .semantics { contentDescription = addLabel },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Add,
                    contentDescription = null,
                    tint = OnInk,
                    modifier = Modifier.size(24.dp),
                )
            }
        }
    }
}

@Composable
private fun TransactionList(
    items: List<Transaction>,
    hasMore: Boolean,
    isLoadingMore: Boolean,
    loadMoreError: com.pam.recap.core.result.AppError?,
    onLoadMore: () -> Unit,
    onRetryLoadMore: () -> Unit,
    onOpenTransaction: (String) -> Unit,
    isClassifying: Boolean,
    classificationStatus: String?,
    classificationReviewReasons: List<String>,
    classificationError: com.pam.recap.core.result.AppError?,
    onClassify: () -> Unit,
    isServerMode: Boolean,
    truncated: Boolean,
    scanned: Int,
    isFiltered: Boolean,
) {
    val grouped = items.groupBy { PamDateFormat.localDateTime(it.eventAt).toLocalDate() }
        .toSortedMap(reverseOrder())

    val coverage = if (isServerMode) {
        when {
            truncated -> stringResource(R.string.transactions_coverage_incomplete, scanned)
            isFiltered && scanned > 0 -> stringResource(R.string.transactions_coverage_scanned, scanned)
            hasMore -> stringResource(R.string.transactions_coverage_partial, items.size)
            else -> null
        }
    } else {
        null
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            top = PamSpacing.sm,
            bottom = PamSize.bottomBarContentPadding,
        ),
        verticalArrangement = Arrangement.spacedBy(PamSpacing.sm),
    ) {
        grouped.forEach { (date, rows) ->
            item(key = "group-$date") {
                Column {
                    Text(
                        text = PamDateFormat.dayHeader(rows.first().eventAt),
                        style = LabelM,
                        color = OnInkMuted,
                        modifier = Modifier.padding(start = PamSpacing.xxs, bottom = PamSpacing.xs),
                    )
                    GroupedListCard(radius = PamRadius.xl) {
                        rows.forEachIndexed { index, transaction ->
                            if (index > 0) {
                                HorizontalDivider(
                                    color = OutlineVariant,
                                    modifier = Modifier.padding(start = PamSpacing.md),
                                )
                            }
                            TransactionRow(
                                category = transaction.category,
                                title = transaction.description,
                                meta = rowMeta(transaction),
                                amount = transaction.amount,
                                direction = transaction.direction,
                                isLowConfidence = transaction.isLowConfidence,
                                hasUnconfirmedSuggestion = transaction.hasUnconfirmedSuggestion,
                                isManual = transaction.isManual,
                                currency = transaction.currency,
                                showCardWrapper = false,
                                onClick = { onOpenTransaction(transaction.id) },
                            )
                        }
                    }
                }
            }
        }
        if (loadMoreError != null || hasMore) {
            item(key = "load-more") {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(vertical = PamSpacing.xs),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    if (loadMoreError != null) {
                        NoticeBanner(
                            text = stringResource(com.pam.recap.ui.util.appErrorRes(loadMoreError)),
                        )
                        PillButton(
                            text = stringResource(R.string.action_retry),
                            onClick = onRetryLoadMore,
                            style = PillButtonStyle.Secondary,
                            modifier = Modifier.padding(top = PamSpacing.xs),
                        )
                    } else {
                        PillButton(
                            text = stringResource(R.string.transactions_load_more),
                            onClick = onLoadMore,
                            style = PillButtonStyle.Secondary,
                            loading = isLoadingMore,
                        )
                    }
                }
            }
        }
        // Moved out of the reference's above-the-fold composition but still reachable.
        item(key = "classify") {
            Column(modifier = Modifier.fillMaxWidth().padding(top = PamSpacing.md)) {
                if (isClassifying) {
                    Text(
                        text = stringResource(R.string.transactions_refreshing),
                        style = LabelM,
                        color = OnInkFaint,
                    )
                }
                classificationStatus?.let { status ->
                    NoticeBanner(
                        text = when (status) {
                            "no_eligible_transactions" -> stringResource(R.string.transactions_classify_none)
                            else -> stringResource(R.string.transactions_classify_result, status)
                        },
                        tone = if (classificationError != null) MoneyTone.Expense else MoneyTone.Neutral,
                    )
                }
                classificationReviewReasons.forEach { reason ->
                    NoticeBanner(
                        text = stringResource(classificationReasonRes(reason)),
                        tone = MoneyTone.Neutral,
                    )
                }
                classificationError?.let { error ->
                    NoticeBanner(
                        text = stringResource(com.pam.recap.ui.util.appErrorRes(error)),
                        tone = MoneyTone.Expense,
                    )
                }
                PillButton(
                    text = stringResource(R.string.transactions_classify_ai),
                    onClick = onClassify,
                    style = PillButtonStyle.Ghost,
                    loading = isClassifying,
                )
            }
        }

        coverage?.let { text ->
            item(key = "coverage") {
                Text(
                    text = text,
                    style = LabelM,
                    color = OnInkFaint,
                    modifier = Modifier.padding(bottom = PamSpacing.xxs),
                )
            }
        }
    }
}

@Composable
private fun rowMeta(transaction: Transaction): String {
    val time = PamDateFormat.time(transaction.eventAt)
    val category = stringResource(categoryLabelRes(transaction.category))
    return "$time · $category"
}

/**
 * Localized copy for a category-job reason code. Unknown/new codes fall back to a generic
 * "needs review" message so a contract addition can never render a technical token or crash.
 */
@androidx.annotation.StringRes
internal fun classificationReasonRes(code: String): Int = when (code) {
    "merchant_not_identifiable" -> R.string.transactions_classify_review_merchant_not_identifiable
    "merchant_missing" -> R.string.transactions_classify_review_merchant_missing
    "unsafe_merchant" -> R.string.transactions_classify_review_unsafe_merchant
    "not_eligible" -> R.string.transactions_classify_review_not_eligible
    "not_eligible_personal_transfer" -> R.string.transactions_classify_review_not_eligible_personal_transfer
    else -> R.string.transactions_classify_review_generic
}
