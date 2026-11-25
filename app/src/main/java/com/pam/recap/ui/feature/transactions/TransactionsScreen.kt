package com.pam.recap.ui.feature.transactions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pam.recap.R
import com.pam.recap.domain.model.Transaction
import com.pam.recap.ui.components.EyebrowText
import com.pam.recap.ui.components.EmptyState
import com.pam.recap.ui.components.GlassTopBar
import com.pam.recap.ui.components.LoadingShimmer
import com.pam.recap.ui.components.MoneyTone
import com.pam.recap.ui.components.MonthStrip
import com.pam.recap.ui.components.NoticeBanner
import com.pam.recap.ui.components.PamScaffold
import com.pam.recap.ui.components.PillButton
import com.pam.recap.ui.components.PillButtonStyle
import com.pam.recap.ui.components.SearchField
import com.pam.recap.ui.components.TransactionRow
import com.pam.recap.ui.theme.LabelM
import com.pam.recap.ui.theme.OnInkFaint
import com.pam.recap.ui.theme.OnInkMuted
import com.pam.recap.ui.theme.PamSize
import com.pam.recap.ui.theme.PamSpacing
import com.pam.recap.ui.theme.TitleM
import com.pam.recap.ui.util.PamDateFormat
import com.pam.recap.ui.util.categoryLabelRes

/**
 * §8 Transactions, one route for both entry points: the bottom-nav tab and the Ringkasan
 * drill-down. The centred header carries the period (the back arrow exists only on a drill-down,
 * supplied by the host), the filter strip states every active predicate as a chip, and the list
 * keeps the date grouping, provenance markers and the two distinct empty states (FR-20/FR-27).
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
    // query, it is "show me the whole list again" — which is the Ringkasan route's job, so the
    // existing back behavior leaves instead of leaving an unfiltered list under a filtered route.
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
                actions = {
                    com.pam.recap.ui.feature.status.UpdateStatusTopBarAction()
                    com.pam.recap.ui.components.CircularIconWell(
                        icon = Icons.Rounded.Add,
                        contentDescription = stringResource(R.string.manual_title),
                        onClick = onOpenManualEntry,
                    )
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            if (month == null) {
                LoadingShimmer(rows = 3, showHero = false)
                return@PamScaffold
            }

            SearchField(
                value = state.query,
                onValueChange = viewModel::setQuery,
                placeholder = stringResource(R.string.transactions_search_placeholder),
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
                    // Tapping the selected segment clears it (ViewModel toggle), so the chip and the
                    // return decision must reason about the *resulting* value, not the tapped one.
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

            if (viewModel.window().isPartial(month)) {
                Text(
                    text = stringResource(R.string.month_selector_partial),
                    style = LabelM,
                    color = OnInkFaint,
                    modifier = Modifier.padding(top = PamSpacing.xxs),
                )
            }

            if (state.days.isNotEmpty()) {
                // C2-6: the strip lists only days that actually carry transactions, so it needs
                // the month in words to read as "days of February", not as a partial week.
                Text(
                    text = stringResource(R.string.transactions_days_caption, month.label()),
                    style = LabelM,
                    color = OnInkFaint,
                    modifier = Modifier.padding(top = PamSpacing.xs),
                )
                MonthStrip(
                    days = state.days,
                    selectedDate = state.selectedDay,
                    onSelect = viewModel::selectDay,
                )
            }

            PillButton(
                text = stringResource(R.string.transactions_classify_ai),
                onClick = viewModel::classifyUncategorized,
                style = PillButtonStyle.Secondary,
                loading = state.isClassifying,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = PamSpacing.xs),
            )
            state.classificationStatus?.let { status ->
                NoticeBanner(
                    text = when (status) {
                        "no_eligible_transactions" -> stringResource(R.string.transactions_classify_none)
                        else -> stringResource(R.string.transactions_classify_result, status)
                    },
                    tone = if (state.classificationError != null) MoneyTone.Expense else MoneyTone.Neutral,
                )
            }
            state.classificationReviewReasons.forEach { reason ->
                NoticeBanner(
                    text = stringResource(classificationReasonRes(reason)),
                    tone = MoneyTone.Neutral,
                )
            }
            state.classificationError?.let { error ->
                NoticeBanner(
                    text = stringResource(com.pam.recap.ui.util.appErrorRes(error)),
                    tone = MoneyTone.Expense,
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = PamSpacing.sm, bottom = PamSpacing.xxs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                EyebrowText(text = stringResource(R.string.transactions_eyebrow))
                Text(
                    text = pluralStringResource(R.plurals.transactions_count, items.size, items.size),
                    style = LabelM,
                    color = OnInkMuted,
                    modifier = Modifier.padding(start = PamSpacing.xs),
                )
            }
            if (state.isRefreshing) {
                Text(
                    text = stringResource(R.string.transactions_refreshing),
                    style = LabelM,
                    color = OnInkFaint,
                )
            }
            // S2.4: a paged server search must say how many rows it examined and whether its scan
            // cap could have cut matches. Only shown when the server is actually paging the month.
            if (state.isServerMode) {
                val coverage = when {
                    state.truncated -> stringResource(R.string.transactions_coverage_incomplete, state.scanned)
                    state.isFiltered && state.scanned > 0 ->
                        stringResource(R.string.transactions_coverage_scanned, state.scanned)
                    state.hasMore -> stringResource(R.string.transactions_coverage_partial, state.items.size)
                    else -> null
                }
                coverage?.let { text ->
                    Text(
                        text = text,
                        style = LabelM,
                        color = OnInkFaint,
                        modifier = Modifier.padding(bottom = PamSpacing.xxs),
                    )
                }
            }

            when {
                state.isLoading -> LoadingShimmer(rows = 4, showHero = false)

                state.error != null -> Column {
                    NoticeBanner(
                        text = stringResource(com.pam.recap.ui.util.appErrorRes(requireNotNull(state.error))),
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

                // With a next page still available, "no match" is not a conclusion: keep the list
                // surface (and its load-more footer) so the owner can keep looking.
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
) {
    val grouped = items.groupBy { PamDateFormat.localDateTime(it.eventAt).toLocalDate() }
        .toSortedMap(reverseOrder())

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = PamSize.bottomBarContentPadding),
        verticalArrangement = Arrangement.spacedBy(PamSpacing.xs),
    ) {
        grouped.forEach { (date, rows) ->
            item(key = "header-$date") {
                // Reference day header: the date in words plus how many rows it holds.
                Text(
                    text = PamDateFormat.dayHeader(rows.first().eventAt) + " · " +
                        pluralStringResource(R.plurals.transactions_count, rows.size, rows.size),
                    style = TitleM,
                    color = OnInkMuted,
                    modifier = Modifier.padding(top = PamSpacing.sm, bottom = PamSpacing.xxs),
                )
            }
            items(items = rows, key = { it.id }) { transaction ->
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
                    onClick = { onOpenTransaction(transaction.id) },
                )
            }
        }
        if (loadMoreError != null || hasMore) {
            item(key = "load-more") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = PamSpacing.sm),
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
    }
}

@Composable
private fun rowMeta(transaction: Transaction): String {
    // The day header carries the date; the row only needs the clock time and its category. The
    // direction is already expressed by the signed amount.
    val time = PamDateFormat.time(transaction.eventAt)
    val category = stringResource(categoryLabelRes(transaction.category))
    return "$time · $category"
}

/**
 * Localized copy for a category-job reason code. The API returns stable codes
 * ("merchant_not_identifiable", "merchant_missing", "unsafe_merchant", ...); the app explains them
 * in plain Indonesian and never shows the raw code. Unknown/new codes fall back to a generic
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
