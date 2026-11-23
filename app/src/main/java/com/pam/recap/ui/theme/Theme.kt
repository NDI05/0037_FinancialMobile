package com.pam.recap.ui.theme

import android.app.Activity
import android.provider.Settings
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
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

/**
 * True when the OS asks for reduced motion (animator duration scale 0). Components read this instead
 * of hard-coding infinite spinners: the status indicator swaps an animated ring for a static icon so
 * the meaning survives without motion.
 */
val LocalReducedMotion = staticCompositionLocalOf { false }

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

    val context = LocalContext.current
    val reducedMotion = runCatching {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }.getOrDefault(false)

    CompositionLocalProvider(LocalReducedMotion provides reducedMotion) {
        MaterialTheme(
            colorScheme = PamColorScheme,
            typography = PamTypography,
            shapes = PamShapes,
            content = content,
        )
    }
}
