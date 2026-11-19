package com.pam.recap.ui.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pam.recap.ui.theme.Ink
import com.pam.recap.ui.theme.LabelL
import com.pam.recap.ui.theme.OnInkMuted
import com.pam.recap.ui.theme.PamRadius
import com.pam.recap.ui.theme.PamSize
import com.pam.recap.ui.theme.PamSpacing
import com.pam.recap.ui.theme.TrackInset

/**
 * Segmented control matching the reference: an inset dark track with a sliding white pill.
 *
 * C2-1: the pill's width is **content driven** — each option is measured, so a 200 % font scale
 * widens its cell instead of clipping the label. When the labels together exceed the available
 * width the cells shrink proportionally and the labels ellipsize rather than overflow.
 */
@Composable
fun <T> PillToggleGroup(
    options: List<PillToggleOption<T>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    require(options.isNotEmpty()) { "toggle group needs at least one option" }
    val selectedIndex = options.indexOfFirst { it.value == selected }.coerceAtLeast(0)
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current

    val naturalWidths = remember(options, density) {
        options.map { option ->
            measurer.measure(
                text = AnnotatedString(option.label),
                style = LabelL,
                maxLines = 1,
            ).size.width
        }
    }
    val horizontalPadding = with(density) { (PamSpacing.lg + PamSpacing.xs).toPx() }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = PamSize.minTouch)
            .clip(RoundedCornerShape(PamRadius.pill))
            .background(TrackInset)
            .padding(4.dp),
    ) {
        val available = with(density) { maxWidth.toPx() }
        // The pill has no content of its own, so `fillMaxHeight` collapses under an unbounded
        // height constraint. Track the measured row height instead.
        var pillHeight by remember { mutableStateOf(PamSize.minTouch - 8.dp) }
        val wanted = naturalWidths.map { it + horizontalPadding }
        val total = wanted.sum().coerceAtLeast(1f)
        val shrink = if (total > available) available / total else 1f
        // Fill the track when the labels leave slack (a two-option control must not huddle in the
        // left corner), and shrink proportionally when they do not fit. A label never clips: it is
        // ellipsized inside its own measured cell.
        val slackPerCell = ((available - total * shrink) / options.size).coerceAtLeast(0f)
        val cellWidths = wanted.map { width ->
            with(density) { (width * shrink + slackPerCell).toDp() }
        }

        val pillWidth = cellWidths.getOrElse(selectedIndex) { cellWidths.first() }
        val pillOffset = cellWidths.take(selectedIndex).fold(0.dp) { acc, width -> acc + width }
        val animatedWidth by animateDpAsState(pillWidth, tween(durationMillis = 220), label = "toggle-width")
        val animatedOffset by animateDpAsState(pillOffset, tween(durationMillis = 220), label = "toggle-offset")

        Box(
            modifier = Modifier
                .offset(x = animatedOffset)
                .width(animatedWidth)
                .height(pillHeight)
                .align(Alignment.CenterStart)
                .clip(RoundedCornerShape(PamRadius.pill))
                .background(Color.White),
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .onSizeChanged { size ->
                    pillHeight = with(density) { size.height.toDp() }
                },
        ) {
            options.forEachIndexed { index, option ->
                val isSelected = option.value == selected
                Box(
                    modifier = Modifier
                        .width(cellWidths[index])
                        .heightIn(min = PamSize.minTouch - 8.dp)
                        .minimumInteractiveComponentSize()
                        .clip(RoundedCornerShape(PamRadius.pill))
                        .clickable(role = Role.Tab) { onSelect(option.value) }
                        .semantics {
                            contentDescription = option.label
                            this.selected = isSelected
                        }
                        .padding(horizontal = PamSpacing.xs),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = option.label,
                        style = LabelL,
                        color = if (isSelected) Ink else OnInkMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

/** One option of a [PillToggleGroup]. */
data class PillToggleOption<T>(
    val value: T,
    val label: String,
)
