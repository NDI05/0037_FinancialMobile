package com.pam.recap.ui.feature.transactions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pam.recap.R
import com.pam.recap.domain.model.Category
import com.pam.recap.domain.model.Transaction
import com.pam.recap.ui.components.CategoryChip
import com.pam.recap.ui.components.EmptyState
import com.pam.recap.ui.components.EyebrowText
import com.pam.recap.ui.components.GlassTopBar
import com.pam.recap.ui.components.LoadingShimmer
import com.pam.recap.ui.components.MonthSelector
import com.pam.recap.ui.components.MonthStrip
import com.pam.recap.ui.components.PamScaffold
import com.pam.recap.ui.components.SearchField
import com.pam.recap.ui.components.TransactionRow
import com.pam.recap.ui.theme.LabelM
import com.pam.recap.ui.theme.OnInkMuted
import com.pam.recap.ui.theme.PamSize
import com.pam.recap.ui.theme.PamSpacing
import com.pam.recap.ui.theme.TitleM
import com.pam.recap.ui.util.PamDateFormat
import com.pam.recap.ui.util.directionLabelRes
import com.pam.recap.ui.util.categoryLabelRes

/**
 * §8 Transactions: month navigator, day strip with rings, category filters, search, a date-grouped
 * list with provenance markers, and two distinct empty states (FR-20/FR-27).
 */
@Composable
fun TransactionsScreen(
    onOpenTransaction: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: TransactionsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val month = state.month

    PamScaffold(
        modifier = modifier,
        showAurora = false,
        topBar = { GlassTopBar(title = stringResource(R.string.transactions_title)) },
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

            MonthSelector(
                label = month.label(),
                onPrevious = viewModel::previousMonth,
                onNext = viewModel::nextMonth,
            )

            if (state.days.isNotEmpty()) {
                MonthStrip(
                    days = state.days,
                    selectedDate = state.selectedDay,
                    onSelect = viewModel::selectDay,
                )
            }

            SearchField(
                value = state.query,
                onValueChange = viewModel::setQuery,
                placeholder = stringResource(R.string.transactions_search_placeholder),
                modifier = Modifier.padding(top = PamSpacing.xs),
            )

            CategoryFilterRow(
                selected = state.category,
                onSelect = viewModel::selectCategory,
                modifier = Modifier.padding(top = PamSpacing.xs),
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = PamSpacing.sm, bottom = PamSpacing.xxs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                EyebrowText(text = stringResource(R.string.transactions_eyebrow))
                Text(
                    text = stringResource(R.string.transactions_count, state.items.size),
                    style = LabelM,
                    color = OnInkMuted,
                    modifier = Modifier.padding(start = PamSpacing.xs),
                )
            }

            when {
                state.isLoading -> LoadingShimmer(rows = 4, showHero = false)

                state.isEmptyMonth -> EmptyState(
                    eyebrow = stringResource(R.string.transactions_empty_eyebrow),
                    title = stringResource(R.string.transactions_empty_title),
                    body = stringResource(R.string.transactions_empty_body),
                    icon = Icons.Rounded.Search,
                )

                state.isNoMatch -> EmptyState(
                    eyebrow = stringResource(R.string.transactions_nomatch_eyebrow),
                    title = stringResource(R.string.transactions_nomatch_title),
                    body = stringResource(R.string.transactions_nomatch_body),
                    icon = Icons.Rounded.Search,
                )

                else -> TransactionList(
                    items = state.items,
                    onOpenTransaction = onOpenTransaction,
                )
            }
        }
    }
}

@Composable
private fun TransactionList(
    items: List<Transaction>,
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
                Text(
                    text = PamDateFormat.dayHeader(rows.first().eventAt),
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
                    onClick = { onOpenTransaction(transaction.id) },
                )
            }
        }
    }
}

@Composable
private fun rowMeta(transaction: Transaction): String {
    val time = PamDateFormat.time(transaction.eventAt)
    val category = stringResource(categoryLabelRes(transaction.category))
    val direction = stringResource(directionLabelRes(transaction.direction))
    return "$time · $category · $direction"
}

@Composable
private fun CategoryFilterRow(
    selected: Category?,
    onSelect: (Category?) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(PamSpacing.xs),
        contentPadding = PaddingValues(horizontal = 0.dp),
    ) {
        item(key = "all") {
            CategoryChip(
                category = Category.UNCATEGORIZED,
                label = stringResource(R.string.transactions_filter_all),
                selected = selected == null,
                onClick = { onSelect(null) },
            )
        }
        items(items = Category.expenseCategories, key = { it.code }) { category ->
            CategoryChip(
                category = category,
                label = stringResource(categoryLabelRes(category)),
                selected = selected == category,
                onClick = { onSelect(category) },
            )
        }
    }
}
