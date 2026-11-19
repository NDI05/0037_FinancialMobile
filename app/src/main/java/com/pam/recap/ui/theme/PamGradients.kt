package com.pam.recap.ui.theme

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * Gradient vocabulary lifted from the reference design: violet→magenta→pink brand sweeps,
 * radial aurora glows behind dark glass, and a warm amber bloom used by the schedule card.
 */
object PamGradients {

    /** Signature brand sweep. */
    val brand = Brush.linearGradient(
        colors = listOf(VioletDeep, Magenta, Pink),
        start = Offset.Zero,
        end = Offset.Infinite,
    )

    val brandSoft = Brush.linearGradient(
        colors = listOf(Violet.copy(alpha = 0.85f), Pink.copy(alpha = 0.55f)),
        start = Offset.Zero,
        end = Offset.Infinite,
    )

    /** Hero card wash: violet at the top-left falling into deep ink. */
    val hero = Brush.linearGradient(
        colors = listOf(Color(0xFF4A2A7A), Color(0xFF2A1740), Ink),
        start = Offset(0f, 0f),
        end = Offset(1100f, 1400f),
    )

    /** Glass surface sheen laid over card fills to fake refraction. */
    val glassSheen = Brush.linearGradient(
        colors = listOf(Color(0x1FFFFFFF), Color(0x0AFFFFFF), Color(0x14FFFFFF)),
        start = Offset(0f, 0f),
        end = Offset(800f, 800f),
    )

    val glassEdge = Brush.verticalGradient(
        colors = listOf(Color(0x33FFFFFF), Color(0x0DFFFFFF)),
    )

    fun aurora(center: Offset = Offset(0.15f, 0f)) = Brush.radialGradient(
        colors = listOf(VioletDeep.copy(alpha = 0.55f), Magenta.copy(alpha = 0.18f), Color.Transparent),
        center = Offset(center.x, center.y),
        radius = 900f,
    )

    /**
     * Warm corner bloom. The radius and centre must be supplied by the caller so the gradient's
     * falloff ends exactly at the drawn shape's edge — a fixed radius produces a hard-edged disc.
     */
    fun warmBloom(
        radius: Float,
        center: Offset,
        coreAlpha: Float = 0.38f,
    ) = Brush.radialGradient(
        colors = listOf(
            AmberDeep.copy(alpha = coreAlpha),
            Amber.copy(alpha = coreAlpha * 0.28f),
            Color.Transparent,
        ),
        center = center,
        radius = radius.coerceAtLeast(1f),
    )

    fun ringTrack() = Brush.sweepGradient(
        colors = listOf(TrackInset, TrackInset),
    )

    /** Progress arc gradient used by circular indicators. */
    fun ringProgress() = Brush.sweepGradient(
        colors = listOf(Pink, Violet, VioletDeep, Pink),
    )

    fun income() = Brush.linearGradient(listOf(IncomeMint, Color(0xFF4FCFA0)))
    fun expense() = Brush.linearGradient(listOf(ExpenseRose, Color(0xFFFF7A88)))
}
