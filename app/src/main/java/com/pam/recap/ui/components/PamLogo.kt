package com.pam.recap.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Stitch brand mark derived from `logo_financialrecap/screen.svg`:
 * dark rounded square (`#171915`) with three ascending vertical capsule bars
 * (white 45%, white 100%, lime `#DEFF4C`).
 */
@Composable
fun PamLogoMark(
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
) {
    Canvas(modifier = modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val sx = w / 120f
        val sy = h / 120f
        val bgRadius = 28f * sx
        drawRoundRect(
            color = Color(0xFF171915),
            topLeft = Offset.Zero,
            size = Size(w, h),
            cornerRadius = CornerRadius(bgRadius, bgRadius),
        )
        val barRadius = 7f * sx
        drawRoundRect(
            color = Color.White.copy(alpha = 0.45f),
            topLeft = Offset(30f * sx, 54f * sy),
            size = Size(14f * sx, 36f * sy),
            cornerRadius = CornerRadius(barRadius, barRadius),
        )
        drawRoundRect(
            color = Color.White,
            topLeft = Offset(53f * sx, 38f * sy),
            size = Size(14f * sx, 52f * sy),
            cornerRadius = CornerRadius(barRadius, barRadius),
        )
        drawRoundRect(
            color = Color(0xFFDEFF4C),
            topLeft = Offset(76f * sx, 30f * sy),
            size = Size(14f * sx, 60f * sy),
            cornerRadius = CornerRadius(barRadius, barRadius),
        )
    }
}
