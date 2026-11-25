package com.pam.recap.ui.theme

import androidx.compose.ui.graphics.Color

// PAM Stitch — source of truth: docs/design/stitch_pam_finance_app_prototype/
// pam_design_system.md. Warm neutral canvas, white surfaces, ink primary and a single
// functional lime accent. Legacy names stay so un-migrated screens keep compiling.
val Canvas = Color(0xFFFAFAF7)
val Surface = Color(0xFFFFFFFF)
val SurfaceLow = Color(0xFFF4F4F1)
val SurfaceContainer = Color(0xFFEEEEEB)
val SurfaceHigh = Color(0xFFE8E8E5)
val Ink = Canvas
val InkElevated = SurfaceLow
val InkSurface = Surface
val InkHigh = SurfaceContainer

val Primary = Color(0xFF171915)
val PrimaryContainer = Color(0xFFEEEEEB)
val OnPrimary = Color(0xFFFFFFFF)
val OnPrimaryContainer = Color(0xFF171915)
val Secondary = Color(0xFF576500)
val SecondaryContainer = Color(0xFFD9F449)
/** Legacy lime accent kept for widgets and older surfaces; still always paired with dark text. */
val Lime = Color(0xFFD9F449)
val LimeSoft = Color(0xFFF4FAC8)
val OnSecondaryContainer = Color(0xFF171915)
val Tertiary = Color(0xFF576500)
val TertiaryContainer = Color(0xFFEEEEEB)
val OnTertiaryContainer = Color(0xFF171915)

// Compatibility names used by existing screens; values follow the Stitch palette.
val Violet = Primary
val VioletDeep = Color(0xFF454742)
val VioletSoft = SurfaceContainer
val Magenta = Color(0xFF576500)
val Pink = Color(0xFF9B4500)
val PinkSoft = Color(0xFFFFEDE3)
val Amber = Color(0xFF9B4500)
val AmberDeep = Color(0xFF6E3000)

val IncomeMint = Color(0xFF006D30)
val IncomeMintSoft = Color(0xFFE6F6EC)
val ExpenseRose = Color(0xFFB91C1C)
val ExpenseRoseSoft = Color(0xFFFDEBEB)
val WarningAmber = Color(0xFF9B4500)
val DangerRed = Color(0xFFB91C1C)

val OnInk = Color(0xFF171915)
val OnInkMuted = Color(0xFF454742)
val OnInkFaint = Color(0xFF767871)
val Outline = Color(0xFF767871)
val OutlineVariant = Color(0xFFE2E3DB)
val GlassBorder = Color(0xFFE2E3DB)
val GlassBorderStrong = Color(0xFFD3D4CC)
val GlassFill = Surface
val GlassFillStrong = SurfaceLow
val TrackInset = Color(0x14171915)
