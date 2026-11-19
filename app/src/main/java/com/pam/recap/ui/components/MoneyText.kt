package com.pam.recap.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import com.pam.recap.R
import com.pam.recap.core.money.IdrFormatter
import com.pam.recap.core.money.Money
import com.pam.recap.core.money.PercentChange
import com.pam.recap.ui.theme.ExpenseRose
import com.pam.recap.ui.theme.IncomeMint
import com.pam.recap.ui.theme.LabelM
import com.pam.recap.ui.theme.MoneyXl
import com.pam.recap.ui.theme.OnInk
import com.pam.recap.ui.theme.OnInkMuted
import com.pam.recap.ui.theme.PamRadius
import com.pam.recap.ui.theme.PamSpacing
import com.pam.recap.ui.theme.TitleM
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** Semantic tone of an amount; always paired with a word or glyph, never colour alone. */
enum class MoneyTone {
    Income,
    Expense,
    Neutral,
}

@Composable
fun moneyToneColor(tone: MoneyTone): Color = when (tone) {
    MoneyTone.Income -> IncomeMint
    MoneyTone.Expense -> ExpenseRose
    MoneyTone.Neutral -> OnInk
}

/**
 * Amount renderer: `IdrFormatter` output only (never a template with literals), tabular figures so
 * columns do not jitter, and no minus sign — the magnitude is rendered and the direction is carried
 * by [tone] plus the adjacent word or glyph (design system §2, hard rule 6).
 */
@Composable
fun MoneyText(
    amount: Money,
    modifier: Modifier = Modifier,
    tone: MoneyTone = MoneyTone.Neutral,
    style: TextStyle = MoneyXl,
    prefix: String? = null,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(PamSpacing.xxs),
    ) {
        if (prefix != null) {
            Text(text = prefix, style = TitleM, color = OnInkMuted)
        }
        Text(
            text = IdrFormatter.format(amount),
            style = style.copy(fontFeatureSettings = "tnum"),
            color = moneyToneColor(tone),
            maxLines = 1,
        )
    }
}

/**
 * Percentage delta chip (FR-31). A zero/missing denominator renders the "not applicable" copy
 * instead of a fabricated `0%`; the arrow keeps direction out of colour alone.
 */
@Composable
fun AmountPill(
    change: PercentChange,
    modifier: Modifier = Modifier,
) {
    val (text, color) = when (change) {
        is PercentChange.Value -> {
            val points = abs(change.percent).roundToInt()
            if (change.isDecrease) {
                stringResource(R.string.percent_decrease, points) to ExpenseRose
            } else {
                stringResource(R.string.percent_increase, points) to IncomeMint
            }
        }

        PercentChange.NotApplicable ->
            stringResource(R.string.percent_not_applicable) to OnInkMuted
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(PamRadius.pill))
            .background(color.copy(alpha = 0.16f))
            .padding(horizontal = PamSpacing.xs, vertical = 2.dp),
    ) {
        Text(text = text, style = LabelM, color = color)
    }
}

/** Compact amount used inside dense rows: same formatter, smaller style. */
@Composable
fun CompactMoneyText(
    amount: Money,
    modifier: Modifier = Modifier,
    tone: MoneyTone = MoneyTone.Neutral,
) {
    MoneyText(
        amount = amount,
        modifier = modifier,
        tone = tone,
        style = TitleM.copy(fontFeatureSettings = "tnum"),
    )
}

/** Formats a ratio (0..1) as a locale-aware percentage label. */
@Composable
fun sharePercentText(fraction: Float): String =
    String.format(Locale.getDefault(), "%d%%", (fraction.coerceIn(0f, 1f) * 100).roundToInt())
