package com.pam.recap.ui.theme

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.pam.recap.R

/**
 * Plus Jakarta Sans is shipped as a single variable font; each weight is exposed through a
 * `font-family` XML that pins the `wght` axis, so selection by [FontWeight] is exact.
 */
val PamFontFamily = FontFamily(
    Font(R.font.pjs_light, FontWeight.Light),
    Font(R.font.pjs_regular, FontWeight.Normal),
    Font(R.font.pjs_medium, FontWeight.Medium),
    Font(R.font.pjs_semibold, FontWeight.SemiBold),
    Font(R.font.pjs_bold, FontWeight.Bold),
    Font(R.font.pjs_extrabold, FontWeight.ExtraBold),
)

private val Trim = LineHeightStyle(
    alignment = LineHeightStyle.Alignment.Center,
    trim = LineHeightStyle.Trim.None,
)

/** Display type used for hero numbers and screen titles, matching the reference tracking. */
val DisplayXl = TextStyle(
    fontFamily = PamFontFamily,
    fontWeight = FontWeight.Bold,
    fontSize = 40.sp,
    lineHeight = 44.sp,
    letterSpacing = (-0.04).em,
    lineHeightStyle = Trim,
)

val DisplayL = TextStyle(
    fontFamily = PamFontFamily,
    fontWeight = FontWeight.Bold,
    fontSize = 32.sp,
    lineHeight = 38.sp,
    letterSpacing = (-0.03).em,
    lineHeightStyle = Trim,
)

val DisplayM = TextStyle(
    fontFamily = PamFontFamily,
    fontWeight = FontWeight.Bold,
    fontSize = 26.sp,
    lineHeight = 32.sp,
    letterSpacing = (-0.02).em,
    lineHeightStyle = Trim,
)

val HeadlineL = TextStyle(
    fontFamily = PamFontFamily,
    fontWeight = FontWeight.SemiBold,
    fontSize = 22.sp,
    lineHeight = 28.sp,
    letterSpacing = (-0.02).em,
    lineHeightStyle = Trim,
)

val HeadlineM = TextStyle(
    fontFamily = PamFontFamily,
    fontWeight = FontWeight.SemiBold,
    fontSize = 19.sp,
    lineHeight = 25.sp,
    letterSpacing = (-0.015).em,
    lineHeightStyle = Trim,
)

val TitleL = TextStyle(
    fontFamily = PamFontFamily,
    fontWeight = FontWeight.SemiBold,
    fontSize = 17.sp,
    lineHeight = 23.sp,
    letterSpacing = (-0.01).em,
    lineHeightStyle = Trim,
)

val TitleM = TextStyle(
    fontFamily = PamFontFamily,
    fontWeight = FontWeight.SemiBold,
    fontSize = 15.sp,
    lineHeight = 20.sp,
    letterSpacing = 0.em,
    lineHeightStyle = Trim,
)

val TitleS = TextStyle(
    fontFamily = PamFontFamily,
    fontWeight = FontWeight.Medium,
    fontSize = 13.sp,
    lineHeight = 18.sp,
    letterSpacing = 0.01.em,
    lineHeightStyle = Trim,
)

val BodyL = TextStyle(
    fontFamily = PamFontFamily,
    fontWeight = FontWeight.Normal,
    fontSize = 15.sp,
    lineHeight = 23.sp,
    letterSpacing = 0.005.em,
    lineHeightStyle = Trim,
)

val BodyM = TextStyle(
    fontFamily = PamFontFamily,
    fontWeight = FontWeight.Normal,
    fontSize = 13.5.sp,
    lineHeight = 20.sp,
    letterSpacing = 0.005.em,
    lineHeightStyle = Trim,
)

val BodyS = TextStyle(
    fontFamily = PamFontFamily,
    fontWeight = FontWeight.Normal,
    fontSize = 12.sp,
    lineHeight = 17.sp,
    letterSpacing = 0.01.em,
    lineHeightStyle = Trim,
)

val LabelL = TextStyle(
    fontFamily = PamFontFamily,
    fontWeight = FontWeight.SemiBold,
    fontSize = 14.sp,
    lineHeight = 18.sp,
    letterSpacing = 0.005.em,
    lineHeightStyle = Trim,
)

val LabelM = TextStyle(
    fontFamily = PamFontFamily,
    fontWeight = FontWeight.Medium,
    fontSize = 12.sp,
    lineHeight = 16.sp,
    letterSpacing = 0.02.em,
    lineHeightStyle = Trim,
)

/** Small uppercase eyebrow used above section titles ("SCHEUDLE", "LESSONS"). */
val LabelEyebrow = TextStyle(
    fontFamily = PamFontFamily,
    fontWeight = FontWeight.SemiBold,
    fontSize = 10.5.sp,
    lineHeight = 14.sp,
    letterSpacing = 0.16.em,
    lineHeightStyle = Trim,
)

/** Tabular figures for money so columns do not jitter. */
val MoneyXl = DisplayL.copy(letterSpacing = (-0.02).em)
