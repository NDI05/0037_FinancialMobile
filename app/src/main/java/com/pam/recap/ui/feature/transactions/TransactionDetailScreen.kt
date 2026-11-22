package com.pam.recap.ui.feature.transactions

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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pam.recap.R
import com.pam.recap.domain.model.Category
import com.pam.recap.domain.model.Direction
import com.pam.recap.domain.model.Transaction
import com.pam.recap.ui.components.CategoryChip
import com.pam.recap.ui.components.CategoryGlyph
import com.pam.recap.ui.components.ConfidenceBar
import com.pam.recap.ui.components.amountLabel
import com.pam.recap.ui.components.moneyToneColor
import com.pam.recap.ui.components.ConsentSwitchRow
import com.pam.recap.ui.components.EyebrowText
import com.pam.recap.ui.components.GlassCard
import com.pam.recap.ui.components.GlassTopBar
import com.pam.recap.ui.components.KeyValueRow
import com.pam.recap.ui.components.LabelledField
import com.pam.recap.ui.components.LoadingShimmer
import com.pam.recap.ui.components.MoneyTone
import com.pam.recap.ui.components.NoticeBanner
import com.pam.recap.ui.components.PamScaffold
import com.pam.recap.ui.components.PillButton
import com.pam.recap.ui.components.PillButtonStyle
import com.pam.recap.ui.components.SheetScaffold
import com.pam.recap.ui.components.StatusChip
import com.pam.recap.ui.components.StatusChipKind
import com.pam.recap.ui.theme.BodyM
import com.pam.recap.ui.theme.DisplayL
import com.pam.recap.ui.theme.LabelM
import com.pam.recap.ui.theme.OnInk
import com.pam.recap.ui.theme.OnInkMuted
import com.pam.recap.ui.theme.PamSpacing
import com.pam.recap.ui.theme.TitleM
import com.pam.recap.ui.util.PamDateFormat
import com.pam.recap.ui.util.appErrorRes
import com.pam.recap.ui.util.categoryLabelRes
import com.pam.recap.ui.util.directionLabelRes

/**
 * §8 Transaction detail: read-only amount hero and provenance (source, parser version, confidence,
 * opaque fingerprint, event vs received time) plus the only mutable fields — category, transfer
 * flag, refund link and private note (FR-17/FR-25).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TransactionDetailScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: TransactionDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val transaction = state.transaction
    var noteDraft by remember(transaction?.id, transaction?.note) {
        mutableStateOf(transaction?.note.orEmpty())
    }
    var showRefundSheet by remember { mutableStateOf(false) }

    PamScaffold(
        modifier = modifier,
        topBar = {
            GlassTopBar(
                title = stringResource(R.string.detail_title),
                onBack = onBack,
                actions = {
                    if (transaction?.isManual == true) StatusChip(kind = StatusChipKind.Manual)
                },
            )
        },
    ) { padding ->
        if (transaction == null) {
            Column(modifier = Modifier.fillMaxSize().padding(padding)) {
                LoadingShimmer(rows = 3, showHero = false)
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
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CategoryGlyph(category = transaction.category, size = 40.dp)
                    Spacer(modifier = Modifier.padding(horizontal = PamSpacing.xxs))
                    Column {
                        Text(
                            text = transaction.description,
                            style = TitleM,
                            color = OnInk,
                        )
                        Text(
                            text = stringResource(
                                R.string.detail_direction_and_time,
                                stringResource(directionLabelRes(transaction.direction)),
                                PamDateFormat.dateTime(transaction.eventAt),
                            ),
                            style = LabelM,
                            color = OnInkMuted,
                        )
                    }
                }
                Spacer(modifier = Modifier.height(PamSpacing.sm))
                Text(
                    text = amountLabel(transaction.amount, transaction.currency),
                    style = DisplayL.copy(fontFeatureSettings = "tnum"),
                    color = moneyToneColor(
                        if (transaction.direction == Direction.CREDIT) MoneyTone.Income else MoneyTone.Expense,
                    ),
                    maxLines = 1,
                )
                if (!transaction.isIdr) {
                    Spacer(modifier = Modifier.height(PamSpacing.xxs))
                    StatusChip(kind = StatusChipKind.Unavailable)
                }
                if (transaction.isLowConfidence) {
                    Spacer(modifier = Modifier.height(PamSpacing.xs))
                    StatusChip(kind = StatusChipKind.LowConfidence)
                }
            }

            if (state.error != null) {
                NoticeBanner(text = stringResource(appErrorRes(requireNotNull(state.error))))
            }
            if (!state.actionsEnabled) {
                NoticeBanner(text = stringResource(R.string.detail_actions_unavailable))
            }

            GlassCard(modifier = Modifier.fillMaxWidth()) {
                EyebrowText(text = stringResource(R.string.detail_provenance_eyebrow))
                Spacer(modifier = Modifier.height(PamSpacing.xs))
                KeyValueRow(
                    label = stringResource(R.string.detail_source),
                    value = if (transaction.isManual) {
                        stringResource(R.string.status_manual)
                    } else {
                        stringResource(R.string.detail_source_email)
                    },
                )
                KeyValueRow(
                    label = stringResource(R.string.detail_parser_version),
                    value = transaction.parserVersion,
                )
                KeyValueRow(
                    label = stringResource(R.string.detail_fingerprint),
                    value = transaction.fingerprint.take(FINGERPRINT_PREVIEW),
                )
                KeyValueRow(
                    label = stringResource(R.string.detail_event_time),
                    value = PamDateFormat.dateTime(transaction.eventAt),
                )
                transaction.receivedAt?.let { receivedAt ->
                    KeyValueRow(
                        label = stringResource(R.string.detail_received_time),
                        value = PamDateFormat.dateTime(receivedAt),
                    )
                }
                KeyValueRow(
                    label = stringResource(R.string.detail_category_origin),
                    value = stringResource(originLabelRes(transaction)),
                )
                Spacer(modifier = Modifier.height(PamSpacing.xs))
                Text(
                    text = stringResource(R.string.detail_confidence),
                    style = LabelM,
                    color = OnInkMuted,
                )
                ConfidenceBar(
                    confidence = transaction.categoryConfidence ?: transaction.parserConfidence,
                    modifier = Modifier.padding(top = PamSpacing.xxs),
                )
            }

            GlassCard(modifier = Modifier.fillMaxWidth()) {
                EyebrowText(text = stringResource(R.string.detail_category_eyebrow))
                Spacer(modifier = Modifier.height(PamSpacing.xs))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(PamSpacing.xs),
                    verticalArrangement = Arrangement.spacedBy(PamSpacing.xs),
                ) {
                    Category.entries.forEach { category ->
                        CategoryChip(
                            category = category,
                            label = stringResource(categoryLabelRes(category)),
                            selected = transaction.category == category,
                            onClick = { viewModel.setCategory(category) },
                        )
                    }
                }
            }

            ConsentSwitchRow(
                title = stringResource(R.string.detail_transfer_title),
                body = stringResource(R.string.detail_transfer_body),
                checked = transaction.isInternalTransfer,
                onCheckedChange = viewModel::setInternalTransfer,
            )

            if (transaction.direction == Direction.CREDIT) {
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    EyebrowText(text = stringResource(R.string.detail_refund_eyebrow))
                    Spacer(modifier = Modifier.height(PamSpacing.xs))
                    Text(
                        text = transaction.linkedRefundOf?.let { linkedId ->
                            state.refundCandidates.firstOrNull { it.id == linkedId }?.description
                                ?: stringResource(R.string.detail_refund_linked)
                        } ?: stringResource(R.string.detail_refund_unlinked),
                        style = BodyM,
                        color = OnInkMuted,
                    )
                    Spacer(modifier = Modifier.height(PamSpacing.xs))
                    PillButton(
                        text = stringResource(R.string.detail_refund_choose),
                        onClick = { showRefundSheet = true },
                        style = PillButtonStyle.Secondary,
                        leadingIcon = Icons.Rounded.Link,
                        enabled = state.refundCandidates.isNotEmpty(),
                    )
                }
            }

            GlassCard(modifier = Modifier.fillMaxWidth()) {
                EyebrowText(text = stringResource(R.string.detail_note_eyebrow))
                Spacer(modifier = Modifier.height(PamSpacing.xs))
                LabelledField(
                    label = stringResource(R.string.detail_note_label),
                    value = noteDraft,
                    onValueChange = { noteDraft = it },
                    placeholder = stringResource(R.string.detail_note_placeholder),
                )
                Spacer(modifier = Modifier.height(PamSpacing.xs))
                PillButton(
                    text = stringResource(R.string.action_save),
                    onClick = { viewModel.saveNote(noteDraft) },
                    style = PillButtonStyle.Secondary,
                    loading = state.isBusy,
                )
            }

            GlassCard(modifier = Modifier.fillMaxWidth()) {
                EyebrowText(text = stringResource(R.string.detail_audit_eyebrow))
                Spacer(modifier = Modifier.height(PamSpacing.xs))
                KeyValueRow(
                    label = stringResource(R.string.detail_created_at),
                    value = PamDateFormat.dateTime(transaction.createdAt),
                )
                KeyValueRow(
                    label = stringResource(R.string.detail_updated_at),
                    value = PamDateFormat.dateTime(transaction.updatedAt),
                )
                KeyValueRow(
                    label = stringResource(R.string.detail_revision),
                    value = transaction.revision.toString(),
                )
                KeyValueRow(
                    label = stringResource(R.string.detail_category_updated),
                    value = transaction.categoryUpdatedAt?.let { PamDateFormat.dateTime(it) }
                        ?: stringResource(R.string.detail_never_edited),
                )
            }
        }
    }

    if (showRefundSheet) {
        SheetScaffold(onDismissRequest = { showRefundSheet = false }) {
            Text(text = stringResource(R.string.detail_refund_sheet_title), style = com.pam.recap.ui.theme.HeadlineM, color = OnInk)
            Spacer(modifier = Modifier.height(PamSpacing.sm))
            state.refundCandidates.forEach { candidate ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = PamSpacing.xxs),
                ) {
                    PillButton(
                        text = candidate.description,
                        onClick = {
                            viewModel.setRefundLink(candidate.id)
                            showRefundSheet = false
                        },
                        style = PillButtonStyle.Secondary,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            Spacer(modifier = Modifier.height(PamSpacing.xs))
            PillButton(
                text = stringResource(R.string.detail_refund_clear),
                onClick = {
                    viewModel.setRefundLink(null)
                    showRefundSheet = false
                },
                style = PillButtonStyle.Ghost,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

private fun originLabelRes(transaction: Transaction): Int = when {
    transaction.isManual -> R.string.status_manual
    transaction.categoryOrigin == com.pam.recap.domain.model.CategoryOrigin.USER -> R.string.origin_user
    transaction.categoryOrigin == com.pam.recap.domain.model.CategoryOrigin.LLM -> R.string.origin_llm
    else -> R.string.origin_rule
}

private const val FINGERPRINT_PREVIEW = 10
