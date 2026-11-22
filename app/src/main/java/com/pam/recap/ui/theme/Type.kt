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
) = TextStyle(
    fontFamily = PamFontFamily,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = line.sp,
    letterSpacing = tracking.em,
    lineHeightStyle = Trim,
)

val DisplayXl = style(FontWeight.ExtraBold, 36, 44, -0.02)
val DisplayL = style(FontWeight.ExtraBold, 32, 40, -0.02)
val DisplayM = style(FontWeight.ExtraBold, 30, 38, -0.02)
val HeadlineL = style(FontWeight.Bold, 26, 34, -0.015)
val HeadlineM = style(FontWeight.Bold, 22, 28, -0.01)
val TitleL = style(FontWeight.SemiBold, 18, 24)
val TitleM = style(FontWeight.SemiBold, 16, 22)
val TitleS = style(FontWeight.Medium, 14, 20, 0.01)
val BodyL = style(FontWeight.Normal, 16, 24)
val BodyM = style(FontWeight.Normal, 14, 20)
val BodyS = style(FontWeight.Normal, 12, 16, 0.01)
val LabelL = style(FontWeight.SemiBold, 14, 20, 0.01)
val LabelM = style(FontWeight.SemiBold, 12, 16, 0.02)
val LabelEyebrow = style(FontWeight.SemiBold, 11, 14, 0.06)
val MoneyXl = DisplayL.copy(letterSpacing = (-0.02).em)
