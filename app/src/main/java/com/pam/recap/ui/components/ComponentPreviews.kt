package com.pam.recap.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.pam.recap.R
import com.pam.recap.core.money.Money
import com.pam.recap.core.money.PercentChange
import com.pam.recap.domain.model.Category
import com.pam.recap.domain.model.Direction
import com.pam.recap.ui.theme.PamSpacing
import com.pam.recap.ui.theme.PamTheme

/**
 * Design-system previews. Text always comes from resources so the previews exercise the same copy
 * the app ships (hard rule 2).
 */
@Preview(name = "Primitives — light ink", backgroundColor = 0xFF0B0711, showBackground = true)
@Composable
private fun PrimitivesPreview() {
    var selectedTab by remember { mutableStateOf(0) }
    var selectedSwitch by remember { mutableStateOf(true) }
    PamTheme {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(PamSpacing.md),
            verticalArrangement = Arrangement.spacedBy(PamSpacing.sm),
        ) {
            GlassCard {
                EyebrowText(text = stringResource(R.string.category_food))
                MoneyText(amount = Money.ofMajor(1_200_000), tone = MoneyTone.Neutral)
                AmountPill(change = PercentChange.Value(0.125))
            }
            PillToggleGroup(
                options = listOf(
                    PillToggleOption(0, stringResource(R.string.category_income)),
                    PillToggleOption(1, stringResource(R.string.category_food)),
                ),
                selected = selectedTab,
                onSelect = { selectedTab = it },
            )
            PillButton(text = stringResource(R.string.action_continue), onClick = {})
            PillButton(
                text = stringResource(R.string.action_retry),
                onClick = {},
                style = PillButtonStyle.Secondary,
            )
            ConsentSwitchRow(
                title = stringResource(R.string.category_income),
                body = stringResource(R.string.status_email_derived),
                checked = selectedSwitch,
                onCheckedChange = { selectedSwitch = it },
            )
        }
    }
}

@Preview(name = "Rows and chips", backgroundColor = 0xFF0B0711, showBackground = true)
@Composable
private fun RowsPreview() {
    PamTheme {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(PamSpacing.md),
            verticalArrangement = Arrangement.spacedBy(PamSpacing.sm),
        ) {
            TransactionRow(
                category = Category.FOOD,
                title = stringResource(R.string.category_food),
                meta = stringResource(R.string.status_email_derived),
                amount = Money.ofMajor(45_000),
                direction = Direction.DEBIT,
                onClick = {},
            )
            TransactionRow(
                category = Category.INCOME,
                title = stringResource(R.string.category_income),
                meta = stringResource(R.string.status_manual),
                amount = Money.ofMajor(5_000_000),
                direction = Direction.CREDIT,
                isLowConfidence = true,
                onClick = {},
            )
            CategoryChip(category = Category.HOUSING, label = stringResource(R.string.category_housing))
            StatusChip(kind = StatusChipKind.EmailDerived)
            ProgressRing(
                progress = 0.63f,
                label = stringResource(R.string.category_food),
                center = {
                    MoneyText(amount = Money.ofMajor(900_000), style = com.pam.recap.ui.theme.TitleL)
                },
            )
        }
    }
}

@Preview(name = "Ink canvas", backgroundColor = 0xFF0B0711, showBackground = true, widthDp = 360)
@Composable
private fun CanvasPreview() {
    PamTheme {
        PamScaffold(showAurora = true) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(padding),
                verticalArrangement = Arrangement.spacedBy(PamSpacing.sm),
            ) {
                EyebrowText(text = stringResource(R.string.app_name))
                LoadingShimmer(rows = 2, showHero = false)
            }
        }
    }
}
