package com.pam.recap.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/**
 * PAM Warm R1 is a light, warm-surface theme. The old dark names remain as compatibility
 * aliases in Color.kt so un-migrated screens stay readable while R1 rolls out.
 */
private val PamColorScheme = lightColorScheme(
    primary = Primary,
    onPrimary = OnPrimary,
    primaryContainer = PrimaryContainer,
    onPrimaryContainer = OnPrimaryContainer,
    secondary = Secondary,
    onSecondary = OnPrimary,
    secondaryContainer = SecondaryContainer,
    onSecondaryContainer = OnSecondaryContainer,
    tertiary = Tertiary,
    onTertiary = OnPrimary,
    tertiaryContainer = TertiaryContainer,
    onTertiaryContainer = OnTertiaryContainer,
    background = Canvas,
    onBackground = OnInk,
    surface = Surface,
    onSurface = OnInk,
    surfaceVariant = SurfaceLow,
    onSurfaceVariant = OnInkMuted,
    surfaceTint = Primary,
    outline = Outline,
    outlineVariant = OutlineVariant,
    error = DangerRed,
    onError = OnPrimary,
    errorContainer = ExpenseRoseSoft,
    onErrorContainer = DangerRed,
    inverseSurface = OnInk,
    inverseOnSurface = Surface,
    inversePrimary = PrimaryContainer,
    scrim = Color(0x661C1B1B),
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
