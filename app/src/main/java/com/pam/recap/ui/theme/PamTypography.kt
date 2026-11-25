package com.pam.recap.ui.theme

import androidx.compose.material3.Typography

/**
 * Material slot mapping so stock M3 components inherit the Stitch Manrope scale.
 * Feature code should prefer the named styles in Type.kt for anything beyond defaults.
 */
val PamTypography = Typography(
    displayLarge = DisplayXl,
    displayMedium = DisplayL,
    displaySmall = DisplayM,
    headlineLarge = HeadlineL,
    headlineMedium = HeadlineM,
    headlineSmall = TitleL,
    titleLarge = TitleL,
    titleMedium = TitleM,
    titleSmall = TitleS,
    bodyLarge = BodyL,
    bodyMedium = BodyM,
    bodySmall = BodyS,
    labelLarge = LabelL,
    labelMedium = LabelM,
    labelSmall = LabelEyebrow,
)
