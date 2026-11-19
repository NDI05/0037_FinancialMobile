package com.pam.recap.ui.feature.budget

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pam.recap.R
import com.pam.recap.core.money.IdrFormatter
import com.pam.recap.domain.model.BudgetComparisonRow
import com.pam.recap.domain.model.Category
import com.pam.recap.ui.components.EyebrowText
import com.pam.recap.ui.components.GlassCard
import com.pam.recap.ui.components.GlassTopBar
import com.pam.recap.ui.components.LabelledField
import com.pam.recap.ui.components.MoneyText
import com.pam.recap.ui.components.MoneyTone
import com.pam.recap.ui.components.NoticeBanner
import com.pam.recap.ui.components.PamScaffold
import com.pam.recap.ui.components.PillButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.WarningAmber
import com.pam.recap.ui.components.StatusChip
import com.pam.recap.ui.components.StatusChipKind
import com.pam.recap.ui.theme.BodyM
import com.pam.recap.ui.theme.DisplayM
import com.pam.recap.ui.theme.LabelM
import com.pam.recap.ui.theme.OnInk
import com.pam.recap.ui.theme.OnInkFaint
import com.pam.recap.ui.theme.OnInkMuted
import com.pam.recap.ui.theme.PamRadius
import com.pam.recap.ui.theme.PamSpacing
import com.pam.recap.ui.theme.TitleM
import com.pam.recap.ui.theme.WarningAmber
import com.pam.recap.ui.util.appErrorRes
import com.pam.recap.ui.util.categoryLabelRes

/**
 * §8 Budget plan: expected income, planned savings and per-category limits seeded from historical
 * averages (labelled), a live feasibility gap that turns into an explicit shortfall banner (FR-43),
 * an explicit save that performs no external action (FR-44), and the actual-versus-limit comparison
 * (FR-45).
 */
@Composable
fun BudgetPlanScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: BudgetPlanViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val gap = state.planningGap

    PamScaffold(
        modifier = modifier,
        topBar = {
            GlassTopBar(
                title = stringResource(R.string.budget_title),
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
            EyebrowText(text = stringResource(R.string.budget_eyebrow))
            Text(
                text = state.month?.label() ?: stringResource(R.string.budget_title),
                style = DisplayM,
                color = OnInk,
            )
            if (state.isSeededFromHistory) {
                // F4: chip above the sentence so a 200 % font scale wraps instead of squeezing.
                Column(verticalArrangement = Arrangement.spacedBy(PamSpacing.xxs)) {
                    StatusChip(kind = StatusChipKind.Provisional)
                    Text(
                        text = stringResource(R.string.budget_seeded_label),
                        style = LabelM,
                        color = OnInkMuted,
                    )
                }
            }

            GlassCard(modifier = Modifier.fillMaxWidth()) {
                LabelledField(
                    label = stringResource(R.string.budget_income_label),
                    value = state.expectedIncomeText,
                    onValueChange = viewModel::setExpectedIncome,
                    placeholder = stringResource(R.string.manual_amount_placeholder),
                    suffix = stringResource(R.string.currency_code),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                Spacer(modifier = Modifier.height(PamSpacing.sm))
                LabelledField(
                    label = stringResource(R.string.budget_savings_label),
                    value = state.plannedSavingsText,
                    onValueChange = viewModel::setPlannedSavings,
                    placeholder = stringResource(R.string.manual_amount_placeholder),
                    suffix = stringResource(R.string.currency_code),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            }

            NoticeBanner(
                text = if (gap.isShortfall) {
                    stringResource(R.string.budget_shortfall, IdrFormatter.format(gap.shortfall))
                } else {
                    stringResource(R.string.budget_feasible, IdrFormatter.format(gap.gap))
                },
                tone = if (gap.isShortfall) MoneyTone.Expense else MoneyTone.Income,
                // F4: the icon must agree with the tone; a warning triangle next to a feasible
                // plan reads as an error.
                icon = if (gap.isShortfall) Icons.Rounded.WarningAmber else Icons.Rounded.CheckCircle,
            )

            GlassCard(modifier = Modifier.fillMaxWidth()) {
                EyebrowText(text = stringResource(R.string.budget_limits_eyebrow))
                Spacer(modifier = Modifier.height(PamSpacing.xs))
                Category.expenseCategories.forEach { category ->
                    LimitRow(
                        category = category,
                        value = state.limitTexts[category].orEmpty(),
                        onValueChange = { viewModel.setLimit(category, it) },
                    )
                }
            }

            if (state.error != null) {
                NoticeBanner(text = stringResource(appErrorRes(requireNotNull(state.error))))
            }

            PillButton(
                text = stringResource(R.string.budget_save),
                onClick = viewModel::save,
                loading = state.isSaving,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text = stringResource(R.string.budget_no_money_movement),
                style = LabelM,
                color = OnInkFaint,
            )

            val comparison = state.comparison
            if (comparison != null) {
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    EyebrowText(text = stringResource(R.string.budget_comparison_eyebrow))
                    Spacer(modifier = Modifier.height(PamSpacing.xs))
                    comparison.rows.forEach { row -> ComparisonRow(row) }
                    Spacer(modifier = Modifier.height(PamSpacing.xs))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = stringResource(R.string.budget_actual_savings),
                            style = TitleM,
                            color = OnInkMuted,
                            modifier = Modifier.weight(1f),
                        )
                        MoneyText(
                            amount = comparison.actualSavings,
                            tone = if (comparison.actualSavings.isNegative) MoneyTone.Expense else MoneyTone.Income,
                            style = TitleM,
                        )
                    }
                    Text(
                        text = stringResource(R.string.budget_transfers_excluded),
                        style = BodyM,
                        color = OnInkFaint,
                    )
                }
            }
        }
    }
}

@Composable
private fun ComparisonRow(row: BudgetComparisonRow) {
    val share = if (row.limit.isZero) 0f else (row.actual.minor.toFloat() / row.limit.minor.toFloat())
    Column(modifier = Modifier.padding(vertical = PamSpacing.xxs)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(categoryLabelRes(row.category)),
                style = TitleM,
                color = OnInk,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = stringResource(
                    R.string.budget_limit_of,
                    IdrFormatter.format(row.actual),
                    IdrFormatter.format(row.limit),
                ),
                style = LabelM,
                color = if (row.isOverLimit) WarningAmber else OnInkMuted,
            )
        }
        Spacer(modifier = Modifier.height(PamSpacing.xxs))
        androidx.compose.foundation.layout.Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp)
                .clip(RoundedCornerShape(PamRadius.pill))
                .background(OnInk.copy(alpha = 0.08f)),
        ) {
            androidx.compose.foundation.layout.Box(
                modifier = Modifier
                    .fillMaxWidth(share.coerceIn(0f, 1f))
                    .height(4.dp)
                    .clip(RoundedCornerShape(PamRadius.pill))
                    .background(if (row.isOverLimit) WarningAmber else com.pam.recap.ui.theme.Violet),
            )
        }
    }
}

/**
 * D2: label left / amount right on one line at default scale (the reference's compact row shape),
 * stacking only when the font scale leaves no room for both — never clipping the amount.
 */
@Composable
private fun LimitRow(
    category: Category,
    value: String,
    onValueChange: (String) -> Unit,
) {
    val stacked = LocalDensity.current.fontScale > STACK_ABOVE_FONT_SCALE
    if (stacked) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = PamSpacing.xxs),
        ) {
            Text(
                text = stringResource(categoryLabelRes(category)),
                style = TitleM,
                color = OnInk,
            )
            LabelledField(
                label = null,
                value = value,
                onValueChange = onValueChange,
                placeholder = stringResource(R.string.manual_amount_placeholder),
                suffix = stringResource(R.string.currency_code),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
        }
    } else {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = PamSpacing.xxs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(categoryLabelRes(category)),
                style = TitleM,
                color = OnInk,
                modifier = Modifier.weight(0.8f),
            )
            LabelledField(
                label = null,
                value = value,
                onValueChange = onValueChange,
                placeholder = stringResource(R.string.manual_amount_placeholder),
                suffix = stringResource(R.string.currency_code),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1.2f),
            )
        }
    }
}

private const val STACK_ABOVE_FONT_SCALE = 1.3f
