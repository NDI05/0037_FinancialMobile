package com.pam.recap.ui.feature.importjob

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Refresh
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
import com.pam.recap.domain.model.ImportJob
import com.pam.recap.domain.model.ImportState
import com.pam.recap.ui.components.EyebrowText
import com.pam.recap.ui.components.GlassCard
import com.pam.recap.ui.components.GlassTopBar
import com.pam.recap.ui.components.LoadingShimmer
import com.pam.recap.ui.components.MoneyTone
import com.pam.recap.ui.components.NoticeBanner
import com.pam.recap.ui.components.PamScaffold
import com.pam.recap.ui.components.PillButton
import com.pam.recap.ui.components.PillButtonStyle
import com.pam.recap.ui.components.StatTile
import com.pam.recap.ui.components.StatusChip
import com.pam.recap.ui.components.StatusChipKind
import com.pam.recap.ui.theme.BodyM
import com.pam.recap.ui.theme.DisplayM
import com.pam.recap.ui.theme.LabelM
import com.pam.recap.ui.theme.OnInk
import com.pam.recap.ui.theme.OnInkMuted
import com.pam.recap.ui.theme.PamSpacing
import com.pam.recap.ui.theme.TitleM
import com.pam.recap.ui.util.appErrorRes
import com.pam.recap.ui.util.redactionReasonRes

/**
 * §8 Import job: live page progress, matched/imported/skipped/unresolved counts, the redacted skip
 * reasons (FR-15 — reason codes only, never content), retry for a partial run and reconnect
 * guidance for a revoked grant (FR-21).
 */
@Composable
fun ImportJobScreen(
    onBack: () -> Unit,
    onOpenGmail: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ImportJobViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val job = state.job

    PamScaffold(
        modifier = modifier,
        topBar = {
            GlassTopBar(
                title = stringResource(R.string.import_title),
                onBack = onBack,
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(PamSpacing.sm),
        ) {
            EyebrowText(text = stringResource(R.string.import_eyebrow))
            Text(
                text = stringResource(R.string.import_headline),
                style = DisplayM,
                color = OnInk,
            )

            if (job == null || state.isLoading) {
                LoadingShimmer(rows = 2, showHero = false)
                return@PamScaffold
            }

            GlassCard(modifier = Modifier.fillMaxWidth()) {
                // F6: one state word only — the chip carries it, the banner below explains it.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.End,
                ) {
                    StatusChip(kind = importChipKind(job.state))
                }
                Spacer(modifier = Modifier.height(PamSpacing.xs))
                Text(
                    // Never claim "1 of 1" when the total is unknown: a partial run has no
                    // horizon yet, and "of 1" would read as complete (NFR-11).
                    text = if (job.pagesExpected != null) {
                        stringResource(R.string.import_pages, job.pagesProcessed, job.pagesExpected)
                    } else {
                        stringResource(R.string.import_pages_processed, job.pagesProcessed)
                    },
                    style = LabelM,
                    color = OnInkMuted,
                )
                if (job.finishedAt != null) {
                    Spacer(modifier = Modifier.height(PamSpacing.xxs))
                    Text(
                        text = stringResource(
                            R.string.import_finished_at,
                            com.pam.recap.ui.util.PamDateFormat.dateTime(job.finishedAt),
                        ),
                        style = LabelM,
                        color = OnInkMuted,
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(PamSpacing.sm)) {
                CountTile(
                    eyebrow = stringResource(R.string.import_matched),
                    value = job.matchedCount,
                    modifier = Modifier.weight(1f),
                )
                CountTile(
                    eyebrow = stringResource(R.string.import_imported),
                    value = job.importedCount,
                    modifier = Modifier.weight(1f),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(PamSpacing.sm)) {
                CountTile(
                    eyebrow = stringResource(R.string.import_skipped),
                    value = job.skippedCount,
                    modifier = Modifier.weight(1f),
                )
                CountTile(
                    eyebrow = stringResource(R.string.import_unresolved),
                    value = job.unresolvedCount,
                    modifier = Modifier.weight(1f),
                )
            }

            when (job.state) {
                ImportState.EMPTY -> NoticeBanner(
                    text = stringResource(R.string.import_empty),
                    tone = MoneyTone.Neutral,
                )

                ImportState.PARTIAL -> NoticeBanner(
                    text = stringResource(R.string.import_partial),
                    tone = MoneyTone.Expense,
                )

                ImportState.FAILED -> NoticeBanner(
                    text = stringResource(R.string.import_failed),
                    tone = MoneyTone.Expense,
                )

                ImportState.PERMISSION_REQUIRED -> {
                    NoticeBanner(
                        text = stringResource(R.string.import_permission),
                        tone = MoneyTone.Expense,
                    )
                    PillButton(
                        text = stringResource(R.string.import_reconnect),
                        onClick = onOpenGmail,
                        style = PillButtonStyle.Secondary,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                ImportState.COMPLETE -> NoticeBanner(
                    text = stringResource(R.string.import_complete),
                    tone = MoneyTone.Income,
                    icon = Icons.Rounded.CheckCircle,
                )

                ImportState.CREATED, ImportState.RUNNING -> NoticeBanner(
                    text = stringResource(R.string.import_running),
                    tone = MoneyTone.Neutral,
                    icon = Icons.Rounded.Refresh,
                )
            }

            if (state.skippedByReason.isNotEmpty()) {
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    EyebrowText(text = stringResource(R.string.import_skipped_eyebrow))
                    Spacer(modifier = Modifier.height(PamSpacing.xs))
                    Text(
                        text = stringResource(R.string.import_skipped_note),
                        style = BodyM,
                        color = OnInkMuted,
                    )
                    Spacer(modifier = Modifier.height(PamSpacing.xs))
                    state.skippedByReason.forEach { (reason, count) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = stringResource(redactionReasonRes(reason)),
                                style = TitleM,
                                color = OnInk,
                                modifier = Modifier.weight(1f),
                            )
                            Text(text = count.toString(), style = TitleM, color = OnInkMuted)
                        }
                    }
                }
            }

            if (state.error != null) {
                NoticeBanner(text = stringResource(appErrorRes(requireNotNull(state.error))))
            }

            if (state.canRetry) {
                PillButton(
                    text = stringResource(R.string.action_retry),
                    onClick = viewModel::retry,
                    loading = state.isRunning,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun CountTile(eyebrow: String, value: Int, modifier: Modifier = Modifier) {
    StatTile(
        eyebrow = eyebrow,
        modifier = modifier,
    ) {
        Text(text = value.toString(), style = DisplayM, color = OnInk)
    }
}



private fun importChipKind(state: ImportState): StatusChipKind = when (state) {
    ImportState.CREATED, ImportState.RUNNING -> StatusChipKind.Provisional
    ImportState.COMPLETE -> StatusChipKind.Complete
    ImportState.PARTIAL -> StatusChipKind.Partial
    ImportState.FAILED -> StatusChipKind.Failed
    ImportState.PERMISSION_REQUIRED -> StatusChipKind.Partial
    ImportState.EMPTY -> StatusChipKind.Empty
}
