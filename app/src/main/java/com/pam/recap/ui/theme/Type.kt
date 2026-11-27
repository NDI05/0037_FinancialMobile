package com.pam.recap.ui.theme

import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.pam.recap.R

/** Manrope variable font, bundled offline with its OFL license. */
@OptIn(ExperimentalTextApi::class)
val PamFontFamily = FontFamily(
    Font(R.font.manrope, FontWeight.Normal,
        variationSettings = FontVariation.Settings(FontVariation.weight(400))),
    Font(R.font.manrope, FontWeight.Medium,
        variationSettings = FontVariation.Settings(FontVariation.weight(500))),
    Font(R.font.manrope, FontWeight.SemiBold,
        variationSettings = FontVariation.Settings(FontVariation.weight(600))),
    Font(R.font.manrope, FontWeight.Bold,
        variationSettings = FontVariation.Settings(FontVariation.weight(700))),
    Font(R.font.manrope, FontWeight.ExtraBold,
        variationSettings = FontVariation.Settings(FontVariation.weight(800))),
)

private val Trim = LineHeightStyle(
    alignment = LineHeightStyle.Alignment.Center,
    trim = LineHeightStyle.Trim.None,
)

private fun style(
    weight: FontWeight,
    size: Int,
    line: Int,
    tracking: Double = 0.0,
    tabular: Boolean = false,
) = TextStyle(
    fontFamily = PamFontFamily,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = line.sp,
    letterSpacing = tracking.em,
    lineHeightStyle = Trim,
    // Stitch requires tabular numerals for every amount, date and metric so columns scan cleanly.
    fontFeatureSettings = if (tabular) "tnum" else null,
)

val DisplayXl = style(FontWeight.Bold, 36, 44, -0.021, tabular = true)
val DisplayL = style(FontWeight.Bold, 32, 38, -0.016, tabular = true)
val DisplayM = style(FontWeight.Bold, 24, 30, -0.013, tabular = true)
val HeadlineL = style(FontWeight.Bold, 22, 28, -0.012, tabular = true)
val HeadlineM = style(FontWeight.Bold, 20, 26, -0.01, tabular = true)
val TitleL = style(FontWeight.Bold, 18, 24, -0.011, tabular = true)
val TitleM = style(FontWeight.SemiBold, 16, 22, -0.006, tabular = true)
val TitleS = style(FontWeight.SemiBold, 14, 20, 0.0, tabular = true)
val BodyL = style(FontWeight.Medium, 16, 24)
val BodyM = style(FontWeight.Medium, 14, 20, tabular = true)
val BodyS = style(FontWeight.Normal, 13, 18, tabular = true)
val LabelL = style(FontWeight.SemiBold, 14, 20, 0.007, tabular = true)
val LabelM = style(FontWeight.SemiBold, 12, 16, 0.013, tabular = true)
val LabelEyebrow = style(FontWeight.SemiBold, 11, 14, 0.018, tabular = true)
val MoneyXl = DisplayL.copy(letterSpacing = (-0.016).em, fontFeatureSettings = "tnum")
