package com.pam.recap.ui.feature.analysis

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pam.recap.R
import com.pam.recap.domain.model.Analysis
import com.pam.recap.domain.model.AnalysisStatus
import com.pam.recap.domain.model.Observation
import com.pam.recap.domain.model.Suggestion
import com.pam.recap.ui.components.CompactMoneyText
import com.pam.recap.ui.components.EyebrowText
import com.pam.recap.ui.components.GlassCard
import com.pam.recap.ui.components.GlassTopBar
import com.pam.recap.ui.components.LoadingShimmer
import com.pam.recap.ui.components.MonthSelector
import com.pam.recap.ui.components.NoticeBanner
import com.pam.recap.ui.components.PamScaffold
import com.pam.recap.ui.components.PillButton
import com.pam.recap.ui.components.PillButtonStyle
import com.pam.recap.ui.components.PillToggleGroup
import com.pam.recap.ui.components.PillToggleOption
import com.pam.recap.ui.components.StatusChip
import com.pam.recap.ui.components.StatusChipKind
import com.pam.recap.ui.theme.BodyM
import com.pam.recap.ui.theme.DisplayM
import com.pam.recap.ui.theme.HeadlineM
import com.pam.recap.ui.theme.LabelM
import com.pam.recap.ui.theme.OnInk
import com.pam.recap.ui.theme.OnInkFaint
import com.pam.recap.ui.theme.OnInkMuted
import com.pam.recap.ui.theme.PamRadius
import com.pam.recap.ui.theme.PamSpacing
import com.pam.recap.ui.theme.TitleM
import com.pam.recap.ui.theme.TrackInset
import com.pam.recap.ui.theme.Violet
import com.pam.recap.ui.util.appErrorRes
import com.pam.recap.ui.util.metricLabelRes

/**
 * §8 Analysis: provider disclosure before any request (FR-34), the segmented observations/suggestion
 * control from the reference's middle frame, evidence chips per observation (FR-36), an
 * `i dari N` progress line, limitations, status chips and an explicit unavailable fallback that
 * keeps the deterministic summary readable (FR-37).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AnalysisScreen(
    onOpenConsent: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AnalysisViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val month = state.month

    PamScaffold(
        modifier = modifier,
        showAurora = false,
        topBar = { GlassTopBar(title = stringResource(R.string.analysis_title)) },
    ) { padding ->
        if (month == null) {
            Column(modifier = Modifier.fillMaxSize().padding(padding)) {
                LoadingShimmer(rows = 3)
            }
            return@PamScaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(PamSpacing.sm),
        ) {
            MonthSelector(
                label = month.label(),
                onPrevious = viewModel::previousMonth,
                onNext = viewModel::nextMonth,
            )

            DisclosureCard(
                consentGranted = state.aiConsentGranted,
                onOpenConsent = onOpenConsent,
            )

            val analysis = state.analysis
            when {
                analysis == null -> AnalysisEmptyCard(
                    canGenerate = state.canGenerate,
                    isGenerating = state.isGenerating,
                    errorMessage = state.error?.let { stringResource(appErrorRes(it)) },
                    onGenerate = { viewModel.generate(regenerate = false) },
                )

                else -> {
                    AnalysisHeader(analysis = analysis)
                    PillToggleGroup(
                        options = listOf(
                            PillToggleOption(false, stringResource(R.string.analysis_tab_observations)),
                            PillToggleOption(true, stringResource(R.string.analysis_tab_suggestions)),
                        ),
                        selected = state.showSuggestions,
                        onSelect = { suggestions ->
                            if (suggestions) viewModel.showSuggestions() else viewModel.showObservations()
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
                    Row(horizontalArrangement = Arrangement.spacedBy(PamSpacing.xs)) {
                        PillButton(
                            text = stringResource(R.string.analysis_regenerate),
                            onClick = { viewModel.generate(regenerate = true) },
                            loading = state.isGenerating,
                            style = PillButtonStyle.Secondary,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DisclosureCard(consentGranted: Boolean, onOpenConsent: () -> Unit) {
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        EyebrowText(text = stringResource(R.string.analysis_disclosure_eyebrow))
        Spacer(modifier = Modifier.height(PamSpacing.xs))
        Text(
            text = stringResource(R.string.analysis_disclosure_body),
            style = BodyM,
            color = OnInkMuted,
        )
        Spacer(modifier = Modifier.height(PamSpacing.xxs))
        Text(
            text = stringResource(R.string.analysis_disclosure_provider),
            style = BodyM,
            color = OnInkMuted,
        )
        if (!consentGranted) {
            Spacer(modifier = Modifier.height(PamSpacing.xs))
            NoticeBanner(text = stringResource(R.string.analysis_consent_missing))
            Spacer(modifier = Modifier.height(PamSpacing.xxs))
            PillButton(
                text = stringResource(R.string.analysis_open_consent),
                onClick = onOpenConsent,
                style = PillButtonStyle.Ghost,
            )
        }
    }
}

@Composable
private fun AnalysisEmptyCard(
    canGenerate: Boolean,
    isGenerating: Boolean,
    errorMessage: String?,
    onGenerate: () -> Unit,
) {
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Rounded.AutoAwesome,
                contentDescription = null,
                tint = Violet,
                modifier = Modifier.padding(end = PamSpacing.xs),
            )
            Text(
                text = stringResource(R.string.analysis_empty_title),
                style = HeadlineM,
                color = OnInk,
            )
        }
        Spacer(modifier = Modifier.height(PamSpacing.xs))
        Text(
            text = stringResource(R.string.analysis_empty_body),
            style = BodyM,
            color = OnInkMuted,
        )
        if (errorMessage != null) {
            Spacer(modifier = Modifier.height(PamSpacing.xs))
            NoticeBanner(text = errorMessage)
        }
        Spacer(modifier = Modifier.height(PamSpacing.sm))
        PillButton(
            text = stringResource(R.string.analysis_generate),
            onClick = onGenerate,
            enabled = canGenerate,
            loading = isGenerating,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun AnalysisHeader(analysis: Analysis) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(PamSpacing.xs),
    ) {
        EyebrowText(text = stringResource(R.string.analysis_eyebrow))
        Spacer(modifier = Modifier.weight(1f))
        StatusChip(kind = analysis.status.chipKind())
    }
}

private fun AnalysisStatus.chipKind(): StatusChipKind = when (this) {
    AnalysisStatus.AVAILABLE -> StatusChipKind.Complete
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
            text = observation.text,
            style = DisplayM,
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
