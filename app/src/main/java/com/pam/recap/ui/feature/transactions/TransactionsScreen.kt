package com.pam.recap.ui.feature.transactions

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
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
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
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
import com.pam.recap.ui.theme.BodyM
import com.pam.recap.ui.theme.Ink
import com.pam.recap.ui.theme.LabelM
import com.pam.recap.ui.theme.OnInk
import com.pam.recap.ui.theme.OnInkFaint
import com.pam.recap.ui.theme.Surface as PamSurface
import com.pam.recap.ui.theme.OnInkMuted
import com.pam.recap.ui.theme.SurfaceContainer
import com.pam.recap.ui.theme.OutlineVariant
import com.pam.recap.ui.theme.PamRadius
import com.pam.recap.ui.theme.PamSpacing
import com.pam.recap.ui.theme.TitleM
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
    /** Consent feedback links to the "Akses aplikasi" switches in Settings. */
    onOpenSettings: () -> Unit = {},
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
            var addMenuOpen by remember { mutableStateOf(false) }
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

                ClassificationFeedback(
                    isRunning = state.isClassifying,
                    processed = state.classificationProcessed,
                    total = state.classificationTotal,
                    status = state.classificationStatus,
                    error = state.classificationError,
                    consentRequired = state.classificationConsentRequired,
                    reviewReasons = state.classificationReviewReasons,
                    onRetry = viewModel::classifyUncategorized,
                    onOpenSettings = onOpenSettings,
                )

                when {
                    state.isLoading -> LoadingShimmer(rows = 4, showHero = false)

                    // Connection failure owns the viewport: the retry action is centred instead of
                    // sitting off to the left and the list never renders stale rows underneath.
                    state.error != null -> Column(
                        modifier = Modifier.fillMaxSize().padding(horizontal = PamSpacing.lg),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(
                            text = stringResource(R.string.transactions_retry_banner_title),
                            style = TitleM,
                            color = OnInk,
                        )
                        Spacer(modifier = Modifier.height(PamSpacing.xs))
                        Text(
                            text = stringResource(
                                com.pam.recap.ui.util.appErrorRes(requireNotNull(state.error)),
                            ),
                            style = BodyM,
                            color = OnInkMuted,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(modifier = Modifier.height(PamSpacing.md))
                        PillButton(
                            text = stringResource(R.string.action_retry),
                            onClick = viewModel::retry,
                            style = PillButtonStyle.Secondary,
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
                        isServerMode = state.isServerMode,
                        truncated = state.truncated,
                        scanned = state.scanned,
                        isFiltered = state.isFiltered,
                    )
                }
            }

            // One lime control for both creations the screen owns. Tapping it reveals the two
            // actions instead of hiding AI categorisation at the end of the list.
            Column(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(bottom = PamSpacing.md),
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(PamSpacing.sm),
            ) {
                AnimatedVisibility(
                    visible = addMenuOpen,
                    enter = fadeIn() + slideInVertically { it / 3 },
                    exit = fadeOut() + slideOutVertically { it / 3 },
                ) {
                    Column(
                        horizontalAlignment = Alignment.End,
                        verticalArrangement = Arrangement.spacedBy(PamSpacing.sm),
                    ) {
                        FabAction(
                            label = stringResource(R.string.transactions_classify_ai),
                            icon = Icons.Rounded.AutoAwesome,
                            onClick = {
                                addMenuOpen = false
                                viewModel.classifyUncategorized()
                            },
                        )
                        FabAction(
                            label = addLabel,
                            icon = Icons.Rounded.Add,
                            onClick = {
                                addMenuOpen = false
                                onOpenManualEntry()
                            },
                        )
                    }
                }
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(RoundedCornerShape(PamRadius.xl))
                        .background(Accent)
                        .clickable(role = Role.Button) { addMenuOpen = !addMenuOpen }
                        .semantics { contentDescription = addLabel },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = if (addMenuOpen) Icons.Rounded.Close else Icons.Rounded.Add,
                        contentDescription = null,
                        tint = OnInk,
                        modifier = Modifier.size(24.dp),
                    )
                }
            }
        }
    }
}

/**
 * Classification is a screen-level state, not a list footer: progress, the consent gate and the
 * result sit between the filters and the rows so the FAB and the last row stay reachable. At most two
 * review reasons are shown; the rest stay in the review flow instead of flooding the screen.
 */
@Composable
private fun ClassificationFeedback(
    isRunning: Boolean,
    processed: Int,
    total: Int,
    status: String?,
    error: com.pam.recap.core.result.AppError?,
    consentRequired: Boolean,
    reviewReasons: List<String>,
    onRetry: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val visible = isRunning || status != null || error != null || consentRequired
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(),
        exit = fadeOut(),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(top = PamSpacing.sm),
            verticalArrangement = Arrangement.spacedBy(PamSpacing.xs),
        ) {
            if (isRunning) {
                Text(
                    text = if (total > 0) {
                        stringResource(R.string.transactions_classify_progress, processed, total)
                    } else {
                        stringResource(R.string.transactions_classify_preparing)
                    },
                    style = LabelM,
                    color = OnInkMuted,
                )
                LinearProgressIndicator(
                    progress = {
                        if (total > 0) processed.toFloat() / total.toFloat() else 0f
                    },
                    modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(PamRadius.pill)),
                    color = Accent,
                    trackColor = SurfaceContainer,
                )
            }
            if (consentRequired) {
                NoticeBanner(
                    text = stringResource(R.string.transactions_classify_consent),
                    tone = MoneyTone.Neutral,
                )
                PillButton(
                    text = stringResource(R.string.transactions_classify_open_settings),
                    onClick = onOpenSettings,
                    style = PillButtonStyle.Ghost,
                )
            }
            status?.let { code ->
                NoticeBanner(
                    text = when (code) {
                        "no_eligible_transactions" -> stringResource(R.string.transactions_classify_none)
                        "none_manual_uncategorized" -> stringResource(R.string.transactions_classify_manual)
                        "local_pending_upload" -> stringResource(R.string.transactions_classify_local_pending)
                        "partial" -> stringResource(
                            R.string.transactions_classify_partial,
                            processed,
                            total,
                        )
                        "done" -> stringResource(R.string.transactions_classify_done, processed)
                        else -> stringResource(R.string.transactions_classify_result, code)
                    },
                    tone = if (error != null) MoneyTone.Expense else MoneyTone.Neutral,
                )
            }
            reviewReasons.take(2).forEach { reason ->
                NoticeBanner(
                    text = stringResource(classificationReasonRes(reason)),
                    tone = MoneyTone.Neutral,
                )
            }
            error?.let { failure ->
                NoticeBanner(
                    text = stringResource(com.pam.recap.ui.util.appErrorRes(failure)),
                    tone = MoneyTone.Expense,
                )
                PillButton(
                    text = stringResource(R.string.action_retry),
                    onClick = onRetry,
                    style = PillButtonStyle.Ghost,
                )
            }
        }
    }
}

/** White pill above the FAB: readable over any row, no shadow, 48 dp touch target. */
@Composable
private fun FabAction(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
) {
    Surface(
        color = PamSurface,
        contentColor = Ink,
        shape = RoundedCornerShape(PamRadius.lg),
        border = BorderStroke(1.dp, OutlineVariant),
        modifier = Modifier
            .height(48.dp)
            .clickable(role = Role.Button, onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = PamSpacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(PamSpacing.xs),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = Ink,
                modifier = Modifier.size(20.dp),
            )
            Text(text = label, style = LabelM, color = Ink)
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
            bottom = PamSpacing.md,
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
