package com.pam.recap.ui.feature.transactions

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Link
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pam.recap.R
import com.pam.recap.domain.model.Category
import com.pam.recap.domain.model.Direction
import com.pam.recap.domain.model.Transaction
import com.pam.recap.ui.components.CategoryChip
import com.pam.recap.ui.components.ConfidenceBar
import com.pam.recap.ui.components.ConsentSwitchRow
import com.pam.recap.ui.components.DynamicFieldsEditor
import com.pam.recap.ui.components.EyebrowText
import com.pam.recap.ui.components.GlassCard
import com.pam.recap.ui.components.GlassTopBar
import com.pam.recap.ui.components.GroupedListCard
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
import com.pam.recap.ui.components.amountLabel
import com.pam.recap.ui.components.dynamicFieldRuleRes
import com.pam.recap.ui.theme.BodyM
import com.pam.recap.ui.theme.BodyS
import com.pam.recap.ui.theme.DisplayL
import com.pam.recap.ui.theme.LabelEyebrow
import com.pam.recap.ui.theme.LabelL
import com.pam.recap.ui.theme.LabelM
import com.pam.recap.ui.theme.OnInk
import com.pam.recap.ui.theme.OnInkFaint
import com.pam.recap.ui.theme.OnInkMuted
import com.pam.recap.ui.theme.OutlineVariant
import com.pam.recap.ui.theme.PamRadius
import com.pam.recap.ui.theme.PamSpacing
import com.pam.recap.ui.theme.Primary
import com.pam.recap.ui.theme.SurfaceContainer
import com.pam.recap.ui.theme.SurfaceLow
import com.pam.recap.ui.theme.TitleL
import com.pam.recap.ui.theme.TitleS
import com.pam.recap.ui.util.PamDateFormat
import com.pam.recap.ui.util.appErrorRes
import com.pam.recap.ui.util.categoryLabelRes

/**
 * §8 Transaction detail, restyled to the Stitch reference (2026-11-25): a 32 dp amount hero card
 * with the source chip above and the provenance caption below, then a grouped attribute card with
 * Tanggal / Kategori (correction pill) / Catatan. The read-only imported fields stay untouched: the
 * amount, direction and event time are never editable and the provenance/audit cards below still
 * expose them. Only category, transfer, refund, note and — in server mode — description/merchant
 * plus delete are mutable (FR-17/FR-25).
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

    // The server confirmed the delete; leaving the now-stale detail is the only sane next step.
    LaunchedEffect(state.isDeleted) {
        if (state.isDeleted) onBack()
    }
    var showRefundSheet by remember { mutableStateOf(false) }
    var showCategorySheet by remember { mutableStateOf(false) }

    PamScaffold(
        modifier = modifier,
        topBar = {
            GlassTopBar(
                title = stringResource(R.string.detail_title),
                onBack = onBack,
            )
        },
    ) { padding ->
        if (transaction == null) {
            Column(modifier = Modifier.fillMaxSize().padding(padding)) {
                LoadingShimmer(rows = 3, showHero = false)
            }
            return@PamScaffold
        }

        val sourceLabel = if (transaction.isManual) {
            stringResource(R.string.status_manual)
        } else {
            stringResource(R.string.detail_source_email)
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(top = PamSpacing.xs),
            verticalArrangement = Arrangement.spacedBy(PamSpacing.lg),
        ) {
            AmountHeroCard(
                sourceLabel = sourceLabel,
                amount = amountLabel(transaction.amount, transaction.currency),
                title = transaction.description,
                provenance = if (transaction.isManual) {
                    stringResource(R.string.transactions_source_chip, stringResource(R.string.status_manual))
                } else {
                    stringResource(R.string.detail_readonly_livin_note)
                },
                chips = {
                    if (!transaction.isIdr) StatusChip(kind = StatusChipKind.Unavailable)
                    if (transaction.hasUnconfirmedSuggestion) {
                        StatusChip(kind = StatusChipKind.Suggestion)
                    }
                    if (transaction.isLowConfidence) StatusChip(kind = StatusChipKind.LowConfidence)
                },
            )

            AttributeCard(
                dateValue = PamDateFormat.dateTime(transaction.eventAt),
                categoryLabel = stringResource(categoryLabelRes(transaction.category)),
                categoryEditable = state.categoryEditable,
                onEditCategory = { showCategorySheet = true },
                note = state.noteDraft,
                onNoteChange = viewModel::setNote,
            )

            if (transaction.hasUnconfirmedSuggestion) {
                NoticeBanner(text = stringResource(R.string.detail_suggestion_unconfirmed))
            }

            if (state.error != null && !state.conflict) {
                NoticeBanner(text = stringResource(appErrorRes(requireNotNull(state.error))))
            }
            if (state.conflict) {
                NoticeBanner(
                    text = stringResource(R.string.detail_conflict_message),
                    tone = MoneyTone.Expense,
                )
            }
            if (!state.actionsEnabled) {
                NoticeBanner(text = stringResource(R.string.detail_actions_server_partial))
            }

            GlassCard(modifier = Modifier.fillMaxWidth(), radius = PamRadius.xl) {
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
                Spacer(modifier = Modifier.height(PamSpacing.xxs))
                Text(
                    text = stringResource(R.string.detail_confidence_hint),
                    style = LabelM,
                    color = OnInkMuted,
                )
            }

            ConsentSwitchRow(
                title = stringResource(R.string.detail_transfer_title),
                body = stringResource(R.string.detail_transfer_body),
                checked = transaction.isInternalTransfer,
                onCheckedChange = viewModel::setInternalTransfer,
            )

            if (transaction.direction == Direction.CREDIT) {
                GlassCard(modifier = Modifier.fillMaxWidth(), radius = PamRadius.xl) {
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

            if (state.metadataEditable) {
                GlassCard(modifier = Modifier.fillMaxWidth(), radius = PamRadius.xl) {
                    EyebrowText(text = stringResource(R.string.detail_edit_eyebrow))
                    Spacer(modifier = Modifier.height(PamSpacing.xs))
                    LabelledField(
                        label = stringResource(R.string.detail_description_label),
                        value = state.descriptionDraft,
                        onValueChange = viewModel::setDescription,
                        placeholder = stringResource(R.string.detail_description_placeholder),
                    )
                    Spacer(modifier = Modifier.height(PamSpacing.xs))
                    LabelledField(
                        label = stringResource(R.string.detail_merchant_label),
                        value = state.merchantDraft,
                        onValueChange = viewModel::setMerchantLabel,
                        placeholder = stringResource(R.string.detail_merchant_placeholder),
                    )
                    if (state.merchantIsReference) {
                        Spacer(modifier = Modifier.height(PamSpacing.xxs))
                        NoticeBanner(text = stringResource(R.string.detail_merchant_reference_notice))
                    }
                    state.fieldError?.let { error ->
                        Spacer(modifier = Modifier.height(PamSpacing.xs))
                        NoticeBanner(text = stringResource(fieldErrorRes(error)))
                    }
                    Spacer(modifier = Modifier.height(PamSpacing.xs))
                    DynamicFieldsEditor(
                        fields = state.attributeFields,
                        onFieldsChange = viewModel::setAttributeFields,
                        enabled = !state.isBusy,
                    )
                    state.attributeRule?.let { rule ->
                        Spacer(modifier = Modifier.height(PamSpacing.xs))
                        NoticeBanner(text = stringResource(dynamicFieldRuleRes(rule)))
                    }
                    if (state.hasUnsavedChanges) {
                        Spacer(modifier = Modifier.height(PamSpacing.xs))
                        Text(
                            text = stringResource(R.string.detail_unsaved_hint),
                            style = LabelM,
                            color = OnInkMuted,
                        )
                    }
                    Spacer(modifier = Modifier.height(PamSpacing.xs))
                    PillButton(
                        text = stringResource(R.string.action_save),
                        onClick = viewModel::saveMetadata,
                        style = PillButtonStyle.Secondary,
                        loading = state.isBusy,
                    )
                    if (state.canRetry) {
                        Spacer(modifier = Modifier.height(PamSpacing.xs))
                        PillButton(
                            text = stringResource(R.string.action_retry),
                            onClick = viewModel::retrySave,
                            style = PillButtonStyle.Ghost,
                        )
                    }
                }

                GlassCard(modifier = Modifier.fillMaxWidth(), radius = PamRadius.xl) {
                    EyebrowText(text = stringResource(R.string.detail_delete_eyebrow))
                    Spacer(modifier = Modifier.height(PamSpacing.xs))
                    Text(
                        text = stringResource(R.string.detail_delete_body),
                        style = BodyM,
                        color = OnInkMuted,
                    )
                    Spacer(modifier = Modifier.height(PamSpacing.xs))
                    PillButton(
                        text = stringResource(R.string.action_delete),
                        onClick = viewModel::requestDelete,
                        style = PillButtonStyle.Ghost,
                        enabled = !state.isBusy,
                    )
                }
            } else {
                // Offline mode: the Room note stays editable, the server-only edit form is not offered.
                PillButton(
                    text = stringResource(R.string.action_save),
                    onClick = viewModel::saveMetadata,
                    style = PillButtonStyle.Secondary,
                    loading = state.isBusy,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            GlassCard(modifier = Modifier.fillMaxWidth(), radius = PamRadius.xl) {
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

    if (showCategorySheet) {
        SheetScaffold(onDismissRequest = { showCategorySheet = false }) {
            Text(
                text = stringResource(R.string.detail_choose_category),
                style = com.pam.recap.ui.theme.HeadlineM,
                color = OnInk,
            )
            Spacer(modifier = Modifier.height(PamSpacing.sm))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(PamSpacing.xs),
                verticalArrangement = Arrangement.spacedBy(PamSpacing.xs),
            ) {
                Category.entries.forEach { category ->
                    CategoryChip(
                        category = category,
                        label = stringResource(categoryLabelRes(category)),
                        selected = transaction?.category == category,
                        onClick = {
                            viewModel.setCategory(category)
                            showCategorySheet = false
                        },
                    )
                }
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

    // Deleting is destructive, so it always passes this explicit confirmation; cancelling keeps
    // the row exactly as it was and no request is sent.
    if (state.deleteConfirmation) {
        SheetScaffold(onDismissRequest = viewModel::cancelDelete) {
            Text(
                text = stringResource(R.string.detail_delete_title),
                style = com.pam.recap.ui.theme.HeadlineM,
                color = OnInk,
            )
            Spacer(modifier = Modifier.height(PamSpacing.sm))
            Text(
                text = stringResource(R.string.detail_delete_confirm_body),
                style = BodyM,
                color = OnInkMuted,
            )
            Spacer(modifier = Modifier.height(PamSpacing.sm))
            PillButton(
                text = stringResource(R.string.action_delete),
                onClick = viewModel::confirmDelete,
                style = PillButtonStyle.Primary,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(PamSpacing.xs))
            PillButton(
                text = stringResource(R.string.action_cancel),
                onClick = viewModel::cancelDelete,
                style = PillButtonStyle.Ghost,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * Stitch amount hero: white 24 dp card, source chip, 32/700 tabular amount, 18/700 title and the
 * small provenance caption. Extra status chips wrap under the source chip without moving the
 * amount baseline.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AmountHeroCard(
    sourceLabel: String,
    amount: String,
    title: String,
    provenance: String,
    chips: @Composable () -> Unit,
) {
    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        radius = PamRadius.xl,
        contentPadding = PaddingValues(PamSpacing.lg),
    ) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(PamSpacing.xs),
            verticalArrangement = Arrangement.spacedBy(PamSpacing.xs),
        ) {
            SourceChip(text = sourceLabel)
            chips()
        }
        Spacer(modifier = Modifier.height(PamSpacing.sm))
        Text(
            text = amount,
            style = DisplayL,
            color = OnInk,
            maxLines = 1,
        )
        Spacer(modifier = Modifier.height(PamSpacing.sm))
        Text(
            text = title,
            style = TitleL,
            color = OnInk,
        )
        Spacer(modifier = Modifier.height(PamSpacing.xxs))
        Text(
            text = provenance,
            style = BodyS,
            color = OnInkMuted,
        )
    }
}

/** `#EEEEEB` 12 dp-radius source chip from the reference (`label-sm` 11/600). */
@Composable
private fun SourceChip(text: String) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(PamRadius.sm))
            .background(SurfaceContainer)
            .padding(horizontal = PamSpacing.xs + 2.dp, vertical = PamSpacing.xxs),
    ) {
        Text(text = text, style = LabelEyebrow, color = OnInkMuted)
    }
}

/**
 * Grouped attribute card: Tanggal is read-only, Kategori opens the picker sheet and Catatan stays
 * an inline editor (the note is the only metadata every mode can write locally).
 */
@Composable
private fun AttributeCard(
    dateValue: String,
    categoryLabel: String,
    categoryEditable: Boolean,
    onEditCategory: () -> Unit,
    note: String,
    onNoteChange: (String) -> Unit,
) {
    GroupedListCard(radius = PamRadius.xl) {
        AttributeRow(label = stringResource(R.string.detail_date_row), value = dateValue)
        HorizontalDivider(color = OutlineVariant)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(PamSpacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(PamSpacing.sm),
        ) {
            Text(
                text = stringResource(R.string.detail_category_row),
                style = BodyM,
                color = OnInkMuted,
                modifier = Modifier.weight(1f),
            )
            CategoryPill(
                label = categoryLabel,
                enabled = categoryEditable,
                onClick = onEditCategory,
            )
        }
        HorizontalDivider(color = OutlineVariant)
        Column(modifier = Modifier.padding(PamSpacing.md)) {
            Text(
                text = stringResource(R.string.detail_note_label),
                style = BodyM,
                color = OnInkMuted,
            )
            Spacer(modifier = Modifier.height(PamSpacing.xxs + 2.dp))
            NoteField(
                value = note,
                onValueChange = onNoteChange,
                placeholder = stringResource(R.string.detail_note_placeholder_edit),
            )
        }
    }
}

/** One `p-4` label/value row of the attribute card (reference `body-md` label, `label-lg` value). */
@Composable
private fun AttributeRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(PamSpacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(PamSpacing.sm),
    ) {
        Text(
            text = label,
            style = BodyM,
            color = OnInkMuted,
            modifier = Modifier.weight(1f),
        )
        Text(text = value, style = TitleS, color = OnInk)
    }
}

/** `#F4F4F1` pill with a 16 dp radius and the edit glyph from the reference category row. */
@Composable
private fun CategoryPill(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(PamRadius.md)
    Row(
        modifier = Modifier
            .heightIn(min = 40.dp)
            .clip(shape)
            .background(SurfaceLow)
            .then(
                if (enabled) {
                    Modifier.clickable(
                        role = Role.Button,
                        onClickLabel = stringResource(R.string.detail_choose_category),
                        onClick = onClick,
                    )
                } else {
                    Modifier
                },
            )
            .padding(horizontal = PamSpacing.sm + PamSpacing.xxs, vertical = PamSpacing.xxs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(PamSpacing.xxs + 2.dp),
    ) {
        Text(text = label, style = LabelL, color = OnInk)
        Icon(
            imageVector = Icons.Rounded.Edit,
            contentDescription = null,
            tint = OnInkMuted,
            modifier = Modifier.height(18.dp),
        )
    }
}

/** Inset `#F4F4F1` note textarea (16 dp radius, 12 dp padding) from the reference. */
@Composable
private fun NoteField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
) {
    val shape = RoundedCornerShape(PamRadius.md)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 66.dp)
            .clip(shape)
            .background(SurfaceLow)
            .border(1.dp, OutlineVariant, shape)
            .padding(PamSpacing.sm),
    ) {
        if (value.isEmpty()) {
            Text(text = placeholder, style = BodyM, color = OnInkFaint)
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            textStyle = BodyM.copy(color = OnInk),
            cursorBrush = SolidColor(Primary),
            minLines = 2,
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentDescription = placeholder },
        )
    }
}

private fun fieldErrorRes(error: TransactionFieldError): Int = when (error) {
    TransactionFieldError.DESCRIPTION_REQUIRED -> R.string.detail_description_required
    TransactionFieldError.DESCRIPTION_TOO_LONG -> R.string.detail_description_too_long
    TransactionFieldError.MERCHANT_TOO_LONG -> R.string.detail_merchant_too_long
    TransactionFieldError.NOTE_TOO_LONG -> R.string.detail_note_too_long
}

private fun originLabelRes(transaction: Transaction): Int = when {
    transaction.isManual -> R.string.status_manual
    transaction.categoryOrigin == com.pam.recap.domain.model.CategoryOrigin.USER -> R.string.origin_user
    transaction.categoryOrigin == com.pam.recap.domain.model.CategoryOrigin.LLM -> R.string.origin_llm
    else -> R.string.origin_rule
}

private const val FINGERPRINT_PREVIEW = 10
