package com.pam.recap.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * Stitch flat layering: 24 dp cards, 16–20 dp secondary containers and 8–12 dp controls.
 * The names are shared with un-migrated screens, so only the values change here.
 */
val PamShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(24.dp),
)

object PamRadius {
    val xs = 8.dp
    val sm = 12.dp
    val md = 16.dp
    val lg = 20.dp
    val xl = 24.dp
    val hero = 24.dp
    val sheet = 24.dp
    val pill = 100.dp
}

object PamSpacing {
    val xxs = 4.dp
    val xs = 8.dp
    val sm = 12.dp
    val md = 16.dp
    val lg = 20.dp
    val xl = 24.dp
    val xxl = 32.dp
    val xxxl = 40.dp
    /** Horizontal gutter of every screen. */
    val gutter = 20.dp
}

object PamSize {
    /** NFR-01: minimum touch target. */
    val minTouch = 48.dp
    val iconButton = 44.dp
    val fab = 56.dp
    val bottomBar = 76.dp
    val bottomBarGutter = 0.dp
    val monthStripCell = 52.dp

    /** Bottom content padding for a scroll surface that sits under the floating bottom bar. */
    val bottomBarContentPadding = 112.dp

    /** Raised import action diameter on the bottom bar (kept for compatibility). */
    val bottomBarAction = 52.dp
}
