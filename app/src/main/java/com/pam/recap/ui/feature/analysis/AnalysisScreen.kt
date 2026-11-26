package com.pam.recap.ui.feature.analysis

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.ReceiptLong
import androidx.compose.material.icons.rounded.Schedule
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pam.recap.R
import com.pam.recap.core.money.IdrFormatter
import com.pam.recap.core.money.Money
import com.pam.recap.domain.model.Analysis
import com.pam.recap.domain.model.AnalysisStatus
import com.pam.recap.domain.model.CategoryTotal
import com.pam.recap.domain.model.ClassifiedKind
import com.pam.recap.domain.model.MetricKeys
import com.pam.recap.domain.model.MonthlySummary
import com.pam.recap.domain.model.Observation
import com.pam.recap.domain.model.Suggestion
import com.pam.recap.domain.model.SummaryFreshness
import com.pam.recap.domain.model.displayStatus
import com.pam.recap.domain.model.freshnessAgainst
import com.pam.recap.ui.components.CircularIconWell
import com.pam.recap.ui.components.CompactMoneyText
import com.pam.recap.ui.components.EyebrowText
import com.pam.recap.ui.components.GlassCard
import com.pam.recap.ui.components.GlassTopBar
import com.pam.recap.ui.components.LoadingShimmer
import com.pam.recap.ui.components.NoticeBanner
import com.pam.recap.ui.components.PamScaffold
import com.pam.recap.ui.components.PillButton
import com.pam.recap.ui.components.PillButtonStyle
import com.pam.recap.ui.components.PillToggleGroup
import com.pam.recap.ui.components.PillToggleOption
import com.pam.recap.ui.components.StatusChip
import com.pam.recap.ui.components.StatusChipKind
import com.pam.recap.ui.theme.Accent
import com.pam.recap.ui.theme.BodyM
import com.pam.recap.ui.theme.BodyS
import com.pam.recap.ui.theme.HeadlineM
import com.pam.recap.ui.theme.LabelEyebrow
import com.pam.recap.ui.theme.LabelM
import com.pam.recap.ui.theme.OnAccent
import com.pam.recap.ui.theme.OnInk
import com.pam.recap.ui.theme.OnInkFaint
import com.pam.recap.ui.theme.OnInkMuted
import com.pam.recap.ui.theme.Outline
import com.pam.recap.ui.theme.OutlineVariant
import com.pam.recap.ui.theme.PamRadius
import com.pam.recap.ui.theme.PamSpacing
import com.pam.recap.ui.theme.Primary
import com.pam.recap.ui.theme.SurfaceContainer
import com.pam.recap.ui.theme.SurfaceLow
import com.pam.recap.ui.theme.TitleL
import com.pam.recap.ui.theme.TitleM
import com.pam.recap.ui.theme.TrackInset
import com.pam.recap.ui.theme.Violet
import com.pam.recap.ui.util.appErrorRes
import com.pam.recap.ui.util.categoryLabelRes
import com.pam.recap.ui.util.metricLabelRes

/** Stitch Analisis: 13 px medium row label (the prototype's `label` tier between BodyS and LabelL). */
private val CategoryRowLabel: TextStyle = BodyS.copy(fontWeight = FontWeight.Medium)

/** Stitch Analisis: 13 px semibold amount/percentage aligned to the right of a category row. */
private val CategoryRowValue: TextStyle = BodyS.copy(fontWeight = FontWeight.SemiBold)

/** Prototype card padding (section inner x/y inset is 21 px on a 393 px viewport). */
private val AnalysisCardPadding = PaddingValues(horizontal = PamSpacing.lg, vertical = PamSpacing.lg)

/**
 * §8 Analysis, re-cut against the Stitch prototype: a centred title with the sync status control,
 * a compact month pill, a lime-tagged "Insight utama" card, a "Pengeluaran per kategori" card with
 * four proportional bars and a "Temuan ringkas" icon-row card.
 *
 * The screens' earlier behaviour is preserved underneath the new visual language: the provider
 * disclosure is rendered before any request (FR-34), the consent gate never lets a request fire
 * without consent, stale/fresh data stays distinguishable (S2.5), observations still carry evidence
 * chips (FR-36) and limitations plus the unavailable fallback remain visible (FR-37).
 */
@Composable
fun AnalysisScreen(
    onOpenConsent: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AnalysisViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val month = state.month

    // C2-5: with consent already granted and no cached analysis for the frozen revision, the first
    // open requests one so the walkthrough lands on content instead of a CTA. The disclosure card
    // is rendered above and a failed request never retries itself.
    var autoRequested by remember { mutableStateOf(false) }
    LaunchedEffect(state.aiConsentGranted, state.analysis, state.error, state.canGenerate) {
        if (!autoRequested && state.aiConsentGranted && state.analysis == null &&
            state.error == null && state.canGenerate
        ) {
            autoRequested = true
            viewModel.generate()
        }
    }

    PamScaffold(
        modifier = modifier,
        showAurora = false,
        topBar = {
            // Stitch: centred 18/700 title, sync standing alone at the top-right corner.
            GlassTopBar(
                title = stringResource(R.string.analysis_title),
                status = { com.pam.recap.ui.feature.status.UpdateStatusTopBarAction() },
            )
        },
    ) { padding ->
        if (month == null) {
            Column(modifier = Modifier.fillMaxSize().padding(padding)) {
                LoadingShimmer(rows = 3)
            }
            return@PamScaffold
        }

        // Arrows stop at the rolling-year boundaries; no month outside the window is reachable.
        val window = viewModel.window()
        val analysis = state.analysis

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            AnalysisMonthPill(
                label = month.label(),
                partial = window.isPartial(month),
                canGoPrevious = window.canGoEarlier(month),
                canGoNext = window.canGoLater(month),
                onPrevious = viewModel::previousMonth,
                onNext = viewModel::nextMonth,
            )
            // The arrow wells are 48 dp tall while the pill itself is 30 dp: this 6 dp spacer puts
            // the first card back on the prototype's y = 110 dp line.
            Spacer(modifier = Modifier.height(6.dp))

            Column(verticalArrangement = Arrangement.spacedBy(PamSpacing.md)) {
                when {
                    analysis == null && !state.aiConsentGranted -> ConsentGateCard(
                        usesServerAnalysis = viewModel.usesServerAnalysis,
                        onOpenConsent = onOpenConsent,
                    )

                    analysis == null -> GenerateCard(
                        canGenerate = state.canGenerate,
                        hasData = state.summary?.hasData == true,
                        isGenerating = state.isGenerating,
                        errorMessage = state.error?.let { stringResource(appErrorRes(it)) },
                        usesServerAnalysis = viewModel.usesServerAnalysis,
                        onGenerate = { viewModel.generate(regenerate = false) },
                    )

                    else -> {
                        // S2.5: the chip + stale notice are only loud when the report is not
                        // current, so the happy path stays as quiet as the prototype. A fresh
                        // analysis renders no status row at all.
                        if (analysis.displayStatus(state.summary) != AnalysisStatus.AVAILABLE) {
                            AnalysisFreshnessHeader(analysis = analysis, summary = state.summary)
                        }

                        InsightCard(summary = state.summary, analysis = analysis)

                        CategoryBreakdownCard(summary = state.summary)

                        FindingsCard(
                            observations = analysis.observations,
                            suggestions = analysis.suggestions,
                        )

                        PillToggleGroup(
                            options = listOf(
                                PillToggleOption(
                                    false,
                                    stringResource(R.string.analysis_tab_observations),
                                ),
                                PillToggleOption(
                                    true,
                                    stringResource(R.string.analysis_tab_suggestions),
                                ),
                            ),
                            selected = state.showSuggestions,
                            onSelect = { suggestions ->
                                if (suggestions) viewModel.showSuggestions()
                                else viewModel.showObservations()
                            },
                        )
                        if (state.showSuggestions) {
                            SuggestionList(analysis.suggestions)
                        } else {
                            ObservationPager(
                                observations = analysis.observations,
                                selectedIndex = state.selectedObservation.coerceIn(
                                    0,
                                    (analysis.observations.size - 1).coerceAtLeast(0),
                                ),
                                onSelect = viewModel::selectObservation,
                            )
                        }

                        if (analysis.limitations.isNotEmpty()) {
                            LimitationsCard(analysis.limitations)
                        }

                        if (state.error != null) {
                            NoticeBanner(text = stringResource(appErrorRes(requireNotNull(state.error))))
                        }

                        PillButton(
                            text = stringResource(R.string.analysis_regenerate),
                            onClick = { viewModel.generate(regenerate = true) },
                            loading = state.isGenerating,
                            style = PillButtonStyle.Secondary,
                            shape = RoundedCornerShape(PamRadius.pill),
                            modifier = Modifier.fillMaxWidth(),
                        )

                        // FR-34: the provider disclosure stays on screen even when the analysis is
                        // already visible, so the "what leaves the device" fact is never one scroll
                        // away.
                        DisclosureFootnote(usesServerAnalysis = viewModel.usesServerAnalysis)
                    }
                }
            }
        }
    }
}

/**
 * Stitch month control: a 30 dp bordered pill (12/600) centred on the page, with the previous/next
 * touch targets placed outside the pill so its width never shifts when an arrow becomes disabled.
 */
@Composable
private fun AnalysisMonthPill(
    label: String,
    partial: Boolean,
    canGoPrevious: Boolean,
    canGoNext: Boolean,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
) {
    Box(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.align(Alignment.Center),
            horizontalArrangement = Arrangement.Center,
        ) {
            MonthPill(label = label, partial = partial)
        }
        // 48 dp touch targets overflow the 30 dp pill symmetrically; nothing is clipped.
        CircularIconWell(
            icon = Icons.Rounded.ChevronLeft,
            contentDescription = stringResource(R.string.action_previous),
            onClick = onPrevious,
            enabled = canGoPrevious,
            containerColor = Color.Transparent,
            tint = OnInkMuted,
            modifier = Modifier.align(Alignment.CenterStart),
        )
        CircularIconWell(
            icon = Icons.Rounded.ChevronRight,
            contentDescription = stringResource(R.string.action_next),
            onClick = onNext,
            enabled = canGoNext,
            containerColor = Color.Transparent,
            tint = OnInkMuted,
            modifier = Modifier.align(Alignment.CenterEnd),
        )
    }
}

@Composable
private fun MonthPill(label: String, partial: Boolean) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(PamRadius.pill))
            .background(SurfaceLow)
            .border(1.dp, OutlineVariant, RoundedCornerShape(PamRadius.pill))
            .padding(horizontal = PamSpacing.md, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = label,
                style = LabelM,
                color = OnInk,
                maxLines = 1,
            )
            if (partial) {
                Text(
                    text = stringResource(R.string.month_selector_partial),
                    style = LabelEyebrow,
                    color = OnInkFaint,
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * Stitch insight card: the month's top expense category as an 18/700 headline plus one real,
 * locally-generated observation as the 14/500 body. Nothing is composed from thin air: both values
 * come from the stored summary and the current analysis.
 */
@Composable
private fun InsightCard(summary: MonthlySummary?, analysis: Analysis) {
    val top = summary?.topExpenseTotals(limit = 1)?.firstOrNull()
    val observationText = analysis.observations.firstOrNull()?.text
    if (top == null && observationText == null) return

    val expenseTotal = summary?.classifiedExpense
    val percent = top?.sharePercent(expenseTotal)

    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        radius = PamRadius.xl,
        contentPadding = AnalysisCardPadding,
    ) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(PamRadius.pill))
                .background(Accent)
                .padding(horizontal = 10.dp, vertical = PamSpacing.xxs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.analysis_primary_insight),
                style = LabelEyebrow,
                color = OnAccent,
                maxLines = 1,
            )
        }
        Spacer(modifier = Modifier.height(PamSpacing.xs))
        if (top != null) {
            Text(
                text = "${expenseLabel(top)} ($percent%)",
                style = TitleL,
                color = OnInk,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.height(PamSpacing.xs))
        }
        Text(
            text = observationText ?: top?.let { fallbackInsightBody(it) }.orEmpty(),
            style = BodyM,
            color = OnInkMuted,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Real, already-localized sentence when the analysis has no observation to feature yet. */
@Composable
private fun fallbackInsightBody(total: CategoryTotal): String =
    "${IdrFormatter.format(total.amount)} · " +
        pluralStringResource(
            R.plurals.overview_transaction_count,
            total.transactionCount,
            total.transactionCount,
        )

/**
 * Stitch category card: four rows (`n. name` + `Rp… (p%)`) each followed by a 12 dp proportional
 * track. Bars are derived from [MonthlySummary.categoryTotals]; a month without classified expense
 * simply renders no card rather than a fabricated 0 % chart.
 */
@Composable
private fun CategoryBreakdownCard(summary: MonthlySummary?) {
    val totals = summary?.topExpenseTotals(limit = 4).orEmpty()
    val expenseTotal = summary?.classifiedExpense
    if (totals.isEmpty() || expenseTotal == null || expenseTotal.minor <= 0L) return

    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        radius = PamRadius.xl,
        contentPadding = AnalysisCardPadding,
    ) {
        Text(
            text = stringResource(R.string.analysis_spending_by_category),
            style = TitleM,
            color = OnInk,
            maxLines = 1,
        )
        Spacer(modifier = Modifier.height(PamSpacing.md))
        Column(verticalArrangement = Arrangement.spacedBy(13.dp)) {
            totals.forEachIndexed { index, total ->
                CategoryBarRow(
                    position = index + 1,
                    total = total,
                    expenseTotal = expenseTotal,
                    fill = barFill(index),
                )
            }
        }
    }
}

@Composable
private fun CategoryBarRow(
    position: Int,
    total: CategoryTotal,
    expenseTotal: Money,
    fill: Color,
) {
    val share = total.shareOf(expenseTotal)
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "$position. ${expenseLabel(total)}",
                style = CategoryRowLabel,
                color = OnInk,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(modifier = Modifier.width(PamSpacing.xs))
            Text(
                text = "${IdrFormatter.format(total.amount)} (${(share * 100).toInt()}%)",
                style = CategoryRowValue,
                color = OnInk,
                maxLines = 1,
            )
        }
        Spacer(modifier = Modifier.height(PamSpacing.xxs))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(12.dp)
                .clip(RoundedCornerShape(PamRadius.pill))
                .background(SurfaceContainer)
                .semantics { progressBarRangeInfo = ProgressBarRangeInfo(share, 0f..1f) },
        ) {
            if (share > 0f) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(share)
                        .height(12.dp)
                        .clip(RoundedCornerShape(PamRadius.pill))
                        .background(fill),
                )
            }
        }
    }
}

/**
 * Stitch "Temuan ringkas": up to two short, already-localized findings rendered as 14/500 icon rows.
 * The source list is the live analysis — observations first, suggestions as the fallback — so the
 * card can never invent a finding the engine did not produce.
 */
@Composable
private fun FindingsCard(observations: List<Observation>, suggestions: List<Suggestion>) {
    val findings: List<Pair<String, ImageVector>> =
        observations.take(2).map { it.text to findingIcon(it.metricKeys) }
            .ifEmpty { suggestions.take(2).map { it.text to Icons.Rounded.ReceiptLong } }
    if (findings.isEmpty()) return

    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        radius = PamRadius.xl,
        contentPadding = AnalysisCardPadding,
    ) {
        Text(
            text = stringResource(R.string.analysis_concise_findings),
            style = TitleM,
            color = OnInk,
            maxLines = 1,
        )
        Spacer(modifier = Modifier.height(PamSpacing.sm))
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            findings.forEach { (text, icon) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = OnInkMuted,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(PamSpacing.xs))
                    Text(
                        text = text,
                        style = BodyM,
                        color = OnInk,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

/** Icon chosen from the metric evidence a finding cites; decorative next to the visible sentence. */
private fun findingIcon(metricKeys: List<String>): ImageVector = when {
    metricKeys.any { it.startsWith("category.") } -> Icons.Rounded.BarChart
    metricKeys.contains(MetricKeys.COVERAGE) -> Icons.Rounded.Schedule
    else -> Icons.Rounded.ReceiptLong
}

/** Stitch consent card: the analysis cannot be requested until consent exists (FR-34). */
@Composable
private fun ConsentGateCard(
    usesServerAnalysis: Boolean,
    onOpenConsent: () -> Unit,
) {
    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        radius = PamRadius.xl,
        contentPadding = AnalysisCardPadding,
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(SurfaceLow),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Rounded.AutoAwesome,
                contentDescription = null,
                tint = OnInk,
                modifier = Modifier.size(22.dp),
            )
        }
        Spacer(modifier = Modifier.height(PamSpacing.md))
        Text(
            text = stringResource(R.string.analysis_consent_missing_title),
            style = TitleL,
            color = OnInk,
        )
        Spacer(modifier = Modifier.height(PamSpacing.xs))
        Text(
            text = stringResource(R.string.analysis_consent_missing_body),
            style = BodyM,
            color = OnInkMuted,
        )
        Spacer(modifier = Modifier.height(PamSpacing.md))
        DisclosureParagraphs(usesServerAnalysis = usesServerAnalysis)
        Spacer(modifier = Modifier.height(PamSpacing.md))
        PillButton(
            text = stringResource(R.string.analysis_open_settings),
            onClick = onOpenConsent,
            shape = RoundedCornerShape(PamRadius.pill),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * Stitch empty state: the month is readable without an analysis, and the generate action stays
 * disabled until consent + a non-empty summary allow it (state.canGenerate).
 */
@Composable
private fun GenerateCard(
    canGenerate: Boolean,
    hasData: Boolean,
    isGenerating: Boolean,
    errorMessage: String?,
    usesServerAnalysis: Boolean,
    onGenerate: () -> Unit,
) {
    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        radius = PamRadius.xl,
        contentPadding = AnalysisCardPadding,
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(SurfaceLow),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Rounded.AutoAwesome,
                contentDescription = null,
                tint = OnInk,
                modifier = Modifier.size(22.dp),
            )
        }
        Spacer(modifier = Modifier.height(PamSpacing.md))
        Text(
            text = stringResource(R.string.analysis_empty_title),
            style = TitleL,
            color = OnInk,
        )
        Spacer(modifier = Modifier.height(PamSpacing.xs))
        Text(
            text = stringResource(R.string.analysis_empty_body),
            style = BodyM,
            color = OnInkMuted,
        )
        if (!hasData) {
            // A non-empty consent state with an empty month used to leave the CTA disabled with no
            // explanation; name the actual blocker instead of looking broken.
            Spacer(modifier = Modifier.height(PamSpacing.sm))
            NoticeBanner(text = stringResource(R.string.analysis_no_data))
        }
        if (errorMessage != null) {
            Spacer(modifier = Modifier.height(PamSpacing.sm))
            NoticeBanner(text = errorMessage)
        }
        Spacer(modifier = Modifier.height(PamSpacing.md))
        PillButton(
            text = stringResource(R.string.analysis_generate),
            onClick = onGenerate,
            enabled = canGenerate,
            loading = isGenerating,
            shape = RoundedCornerShape(PamRadius.pill),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(PamSpacing.md))
        DisclosureParagraphs(usesServerAnalysis = usesServerAnalysis)
    }
}

/** FR-34 copy: what leaves the device and which engine answers. */
@Composable
private fun DisclosureParagraphs(usesServerAnalysis: Boolean) {
    Text(
        text = stringResource(R.string.analysis_disclosure_body),
        style = BodyS,
        color = OnInkMuted,
    )
    Spacer(modifier = Modifier.height(PamSpacing.xxs))
    Text(
        text = stringResource(
            if (usesServerAnalysis) R.string.analysis_disclosure_server_provider
            else R.string.analysis_disclosure_provider,
        ),
        style = BodyS,
        color = OnInkMuted,
    )
}

@Composable
private fun DisclosureFootnote(usesServerAnalysis: Boolean) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = PamSpacing.xxs)) {
        EyebrowText(text = stringResource(R.string.analysis_disclosure_eyebrow))
        Spacer(modifier = Modifier.height(PamSpacing.xxs))
        DisclosureParagraphs(usesServerAnalysis = usesServerAnalysis)
    }
}

/**
 * S2.5: the header renders the *effective* status. A stored analysis whose frozen revision is
 * behind the canonical month revision shows the outdated chip and an explicit stale notice that
 * names both revisions, so "stale" can never be confused with a provider/network error (which the
 * screen renders separately from [AppError]) or with "no result yet" (the empty card).
 */
@Composable
internal fun AnalysisFreshnessHeader(analysis: Analysis, summary: MonthlySummary?) {
    AnalysisHeader(status = analysis.displayStatus(summary))
    if (analysis.freshnessAgainst(summary) == SummaryFreshness.STALE) {
        val currentRevision = summary?.currentRevision ?: analysis.inputRevision
        // B-2: the server can report STALE without a revision bump when the dependency hash moved
        // (prior month, categories or an import job). Say that instead of printing
        // two identical revision numbers.
        val text = if (analysis.inputRevision < currentRevision) {
            stringResource(R.string.analysis_stale_body, analysis.inputRevision, currentRevision)
        } else {
            stringResource(R.string.analysis_stale_dependency_body, analysis.inputRevision)
        }
        NoticeBanner(text = text)
    }
}

@Composable
private fun AnalysisHeader(status: AnalysisStatus) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(PamSpacing.xs),
    ) {
        EyebrowText(text = stringResource(R.string.analysis_eyebrow))
        Spacer(modifier = Modifier.weight(1f))
        StatusChip(kind = status.chipKind())
    }
}

private fun AnalysisStatus.chipKind(): StatusChipKind = when (this) {
    AnalysisStatus.AVAILABLE -> StatusChipKind.Available
    AnalysisStatus.PROVISIONAL -> StatusChipKind.Provisional
    AnalysisStatus.OUTDATED -> StatusChipKind.Outdated
    AnalysisStatus.HISTORICAL -> StatusChipKind.Historical
    AnalysisStatus.UNAVAILABLE -> StatusChipKind.Unavailable
    AnalysisStatus.REJECTED -> StatusChipKind.Failed
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ObservationPager(
    observations: List<Observation>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
) {
    if (observations.isEmpty()) return
    val observation = observations[selectedIndex]
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.analysis_observation_of, selectedIndex + 1, observations.size),
                style = LabelM,
                color = OnInkFaint,
                modifier = Modifier.weight(1f),
            )
            if (selectedIndex < observations.lastIndex) {
                Icon(
                    imageVector = Icons.Rounded.ChevronRight,
                    contentDescription = stringResource(R.string.action_next),
                    tint = OnInkMuted,
                    modifier = Modifier
                        .padding(PamSpacing.xxs)
                        .clickable { onSelect(selectedIndex + 1) },
                )
            }
        }
        Spacer(modifier = Modifier.height(PamSpacing.xs))
        Text(
            // A full sentence at DisplayM (26 sp bold) becomes a wall of text; the featured
            // card keeps the size hierarchy in its progress row and evidence chips instead.
            text = observation.text,
            style = HeadlineM,
            color = OnInk,
        )
        Spacer(modifier = Modifier.height(PamSpacing.sm))
        EvidenceChips(metricKeys = observation.metricKeys)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EvidenceChips(metricKeys: List<String>) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(PamSpacing.xxs),
        verticalArrangement = Arrangement.spacedBy(PamSpacing.xxs),
    ) {
        metricKeys.forEach { key ->
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(PamRadius.pill))
                    .background(TrackInset)
                    .padding(horizontal = PamSpacing.xs, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(metricLabelRes(key)),
                    style = LabelM,
                    color = OnInkMuted,
                )
            }
        }
    }
}

@Composable
private fun SuggestionList(suggestions: List<Suggestion>) {
    Column(verticalArrangement = Arrangement.spacedBy(PamSpacing.xs)) {
        suggestions.forEach { suggestion ->
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Rounded.Lightbulb,
                        contentDescription = null,
                        tint = Violet,
                        modifier = Modifier.padding(end = PamSpacing.xs),
                    )
                    Text(
                        text = suggestion.text,
                        style = TitleM,
                        color = OnInk,
                        modifier = Modifier.weight(1f),
                    )
                }
                if (suggestion.suggestedAmount != null) {
                    Spacer(modifier = Modifier.height(PamSpacing.xs))
                    CompactMoneyText(amount = suggestion.suggestedAmount)
                }
                Spacer(modifier = Modifier.height(PamSpacing.xs))
                EvidenceChips(metricKeys = suggestion.metricKeys)
            }
        }
    }
}

@Composable
private fun LimitationsCard(limitations: List<String>) {
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        EyebrowText(text = stringResource(R.string.analysis_limitations_eyebrow))
        Spacer(modifier = Modifier.height(PamSpacing.xs))
        limitations.forEach { limitation ->
            Text(
                text = limitation,
                style = TitleM,
                color = OnInkMuted,
                modifier = Modifier.padding(vertical = 1.dp),
            )
        }
    }
}

/**
 * Top N net expense rows, identical mapping to the overview chart: refunds may net a category down,
 * so non-positive rows are not spending and are dropped instead of being rendered as a 0 % bar.
 */
private fun MonthlySummary.topExpenseTotals(limit: Int): List<CategoryTotal> = categoryTotals
    .filter { it.category.kind == ClassifiedKind.EXPENSE && it.amount.isPositive }
    .sortedByDescending { it.amount.minor }
    .take(limit)

/** Bar/label font colour from the prototype's four-step ink ramp, by row index. */
private fun barFill(index: Int): Color = when (index) {
    0 -> Primary
    1 -> OnInkMuted
    2 -> Outline
    else -> OutlineVariant
}

/** Fraction of the month's classified expense this row represents, clamped to a 0..1 bar. */
private fun CategoryTotal.shareOf(expenseTotal: Money): Float =
    (amount.minor.toFloat() / expenseTotal.minor.toFloat()).coerceIn(0f, 1f)

private fun CategoryTotal.sharePercent(expenseTotal: Money?): Int {
    if (expenseTotal == null || expenseTotal.minor <= 0L) return 0
    return ((amount.minor * 100L) / expenseTotal.minor).toInt().coerceIn(0, 100)
}

@Composable
private fun expenseLabel(total: CategoryTotal): String =
    total.serverLabel ?: stringResource(categoryLabelRes(total.category))
