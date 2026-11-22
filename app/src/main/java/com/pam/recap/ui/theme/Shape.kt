package com.pam.recap.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * Large, soft radii are a defining trait of the reference design: cards sit close to 28 dp,
 * hero imagery at 24 dp and controls collapse to full pills.
 */
val PamShapes = Shapes(
    extraSmall = RoundedCornerShape(12.dp),
    small = RoundedCornerShape(16.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

object PamRadius {
    val xs = 10.dp
    val sm = 14.dp
    val md = 20.dp
    val lg = 24.dp
    val xl = 28.dp
    val hero = 24.dp
    val sheet = 28.dp
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
    val bottomBar = 68.dp
    val bottomBarGutter = 16.dp
    val monthStripCell = 52.dp

    /** Bottom content padding for a scroll surface that sits under the floating bottom bar. */
    val bottomBarContentPadding = 112.dp

    /** Raised import action diameter on the bottom bar. */
    val bottomBarAction = 52.dp
}
