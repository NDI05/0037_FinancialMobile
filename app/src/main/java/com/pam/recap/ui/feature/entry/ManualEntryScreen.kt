package com.pam.recap.ui.feature.entry

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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pam.recap.R
import com.pam.recap.domain.model.Category
import com.pam.recap.domain.model.Direction
import com.pam.recap.ui.components.CategoryChip
import com.pam.recap.ui.components.CircularIconWell
import com.pam.recap.ui.components.EyebrowText
import com.pam.recap.ui.components.GlassCard
import com.pam.recap.ui.components.GlassTopBar
import com.pam.recap.ui.components.LabelledField
import com.pam.recap.ui.components.NoticeBanner
import com.pam.recap.ui.components.PamScaffold
import com.pam.recap.ui.components.PillButton
import com.pam.recap.ui.components.PillToggleGroup
import com.pam.recap.ui.components.PillToggleOption
import com.pam.recap.ui.theme.BodyM
import com.pam.recap.ui.theme.OnInkMuted
import com.pam.recap.ui.theme.PamSpacing
import com.pam.recap.ui.theme.TitleM
import com.pam.recap.ui.util.PamDateFormat
import com.pam.recap.ui.util.appErrorRes
import com.pam.recap.ui.util.categoryLabelRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight

/** §8 Manual entry: direction, amount, date, category and description, saved as a MANUAL row. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ManualEntryScreen(
    onBack: () -> Unit,
    onSaved: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ManualEntryViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(state.saved) {
        if (state.saved) onSaved()
    }

    PamScaffold(
        modifier = modifier,
        topBar = {
            GlassTopBar(
                title = stringResource(R.string.manual_title),
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
            EyebrowText(text = stringResource(R.string.manual_eyebrow))
            Text(
                text = stringResource(R.string.manual_headline),
                style = com.pam.recap.ui.theme.DisplayM,
                color = com.pam.recap.ui.theme.OnInk,
            )
            Text(
                text = stringResource(R.string.manual_body),
                style = BodyM,
                color = OnInkMuted,
            )

            PillToggleGroup(
                options = listOf(
                    PillToggleOption(Direction.CREDIT, stringResource(R.string.direction_credit)),
                    PillToggleOption(Direction.DEBIT, stringResource(R.string.direction_debit)),
                ),
                selected = state.direction,
                onSelect = viewModel::setDirection,
            )

            GlassCard(modifier = Modifier.fillMaxWidth()) {
                LabelledField(
                    label = stringResource(R.string.manual_amount_label),
                    value = state.amountText,
                    onValueChange = viewModel::setAmount,
                    placeholder = stringResource(R.string.manual_amount_placeholder),
                    suffix = stringResource(R.string.currency_code),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                Spacer(modifier = Modifier.height(PamSpacing.sm))
                LabelledField(
                    label = stringResource(R.string.manual_description_label),
                    value = state.description,
                    onValueChange = viewModel::setDescription,
                    placeholder = stringResource(R.string.manual_description_placeholder),
                )
                Spacer(modifier = Modifier.height(PamSpacing.sm))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.manual_date_label),
                        style = TitleM,
                        color = OnInkMuted,
                        modifier = Modifier.weight(1f),
                    )
                    CircularIconWell(
                        icon = Icons.Rounded.ChevronLeft,
                        contentDescription = stringResource(R.string.action_previous),
                        onClick = { viewModel.shiftDate(-1) },
                    )
                    Text(
                        text = PamDateFormat.date(
                            kotlin.time.Instant.fromEpochMilliseconds(
                                state.date.atTime(java.time.LocalTime.NOON)
                                    .atZone(java.time.ZoneId.of(com.pam.recap.core.time.JakartaMonth.ZONE.id))
                                    .toInstant().toEpochMilli(),
                            ),
                        ),
                        style = TitleM,
                        color = com.pam.recap.ui.theme.OnInk,
                    )
                    CircularIconWell(
                        icon = Icons.Rounded.ChevronRight,
                        contentDescription = stringResource(R.string.action_next),
                        onClick = { viewModel.shiftDate(1) },
                    )
                }
            }

            GlassCard(modifier = Modifier.fillMaxWidth()) {
                EyebrowText(text = stringResource(R.string.manual_category_eyebrow))
                Spacer(modifier = Modifier.height(PamSpacing.xs))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(PamSpacing.xs),
                    verticalArrangement = Arrangement.spacedBy(PamSpacing.xs),
                ) {
                    val categories = if (state.direction == Direction.CREDIT) {
                        listOf(Category.INCOME, Category.OTHER_EXPENSE)
                    } else {
                        Category.expenseCategories + Category.TRANSFER
                    }
                    categories.forEach { category ->
                        CategoryChip(
                            category = category,
                            label = stringResource(categoryLabelRes(category)),
                            selected = state.category == category,
                            onClick = { viewModel.setCategory(category) },
                        )
                    }
                }
            }

            if (state.error != null) {
                NoticeBanner(text = stringResource(appErrorRes(requireNotNull(state.error))))
            }

            PillButton(
                text = stringResource(R.string.manual_save),
                onClick = viewModel::save,
                enabled = state.canSave,
                loading = state.isSaving,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
