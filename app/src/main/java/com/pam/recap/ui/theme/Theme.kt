package com.pam.recap.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/**
 * PAM is dark-only by design: the reference aesthetic depends on glass over deep ink, and a
 * light variant would break the gradient identities used for income/expense semantics.
 */
private val PamColorScheme = darkColorScheme(
    primary = Violet,
    onPrimary = Color(0xFF210E38),
    primaryContainer = VioletDeep,
    onPrimaryContainer = Color(0xFFF3E7FF),
    secondary = Pink,
    onSecondary = Color(0xFF3A0F26),
    secondaryContainer = Color(0xFF3A1C36),
    onSecondaryContainer = PinkSoft,
    tertiary = Amber,
    onTertiary = Color(0xFF3A1D00),
    tertiaryContainer = Color(0xFF3B2412),
    onTertiaryContainer = Amber,
    background = Ink,
    onBackground = OnInk,
    surface = InkSurface,
    onSurface = OnInk,
    surfaceVariant = InkHigh,
    onSurfaceVariant = OnInkMuted,
    surfaceTint = Violet,
    outline = Color(0xFF4A3D60),
    outlineVariant = Color(0xFF302546),
    error = DangerRed,
    onError = Color(0xFF3A0209),
    errorContainer = Color(0xFF43121A),
    onErrorContainer = Color(0xFFFFDADD),
    inverseSurface = OnInk,
    inverseOnSurface = Ink,
    inversePrimary = VioletDeep,
    scrim = Color(0xCC050208),
)

@Composable
fun PamTheme(
    @Suppress("UNUSED_PARAMETER") darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            WindowCompat.setDecorFitsSystemWindows(window, false)
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = false
                isAppearanceLightNavigationBars = false
            }
        }
    }

    MaterialTheme(
        colorScheme = PamColorScheme,
        typography = PamTypography,
        shapes = PamShapes,
        content = content,
    )
}
