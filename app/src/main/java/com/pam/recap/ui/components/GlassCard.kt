package com.pam.recap.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.pam.recap.ui.theme.GlassBorder
import com.pam.recap.ui.theme.InkElevated
import com.pam.recap.ui.theme.OnInk
import com.pam.recap.ui.theme.OutlineVariant
import com.pam.recap.ui.theme.PamRadius
import com.pam.recap.ui.theme.PamSpacing
import com.pam.recap.ui.theme.Surface
import com.pam.recap.ui.theme.TrackInset

/**
 * Stitch flat tonal card: crisp white `#FFFFFF` surface with a 1 dp `#E2E3DB` border and generous
 * 20 dp corners (no heavy shadows or decorative gradients).
 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    radius: Dp = PamRadius.lg,
    fill: Brush = SolidColor(Surface),
    containerColor: Color = Surface,
    borderColor: Color = OutlineVariant,
    contentPadding: PaddingValues = PaddingValues(PamSpacing.md),
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(radius)
    Box(
        modifier = modifier
            .clip(shape)
            .background(containerColor)
            .background(fill)
            .border(width = 1.dp, color = borderColor, shape = shape),
    ) {
        Column(modifier = Modifier.padding(contentPadding), content = content)
    }
}

/**
 * Grouped Stitch container (`rounded-xl`/`rounded-2xl` white card with `1dp` border). Callers
 * separate rows inside with `HorizontalDivider(color = OutlineVariant)`.
 */
@Composable
fun GroupedListCard(
    modifier: Modifier = Modifier,
    radius: Dp = PamRadius.md,
    borderColor: Color = OutlineVariant,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(radius)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Surface)
            .border(width = 1.dp, color = borderColor, shape = shape),
        content = content,
    )
}

/** Inset dark track used behind toggles, skeletons and progress bars. */
@Composable
fun InsetTrack(
    modifier: Modifier = Modifier,
    radius: Dp = PamRadius.pill,
    content: @Composable () -> Unit = {},
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(radius))
            .background(TrackInset),
    ) {
        content()
    }
}

/**
 * Stitch bottom sheet wrapper: crisp white `#FFFFFF` surface, 24 dp top radius, thin top border,
 * and centered drag handle.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SheetScaffold(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Surface,
        contentColor = OnInk,
        shape = RoundedCornerShape(topStart = PamRadius.sheet, topEnd = PamRadius.sheet),
        dragHandle = { SheetDragHandle() },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = PamSpacing.gutter)
                .padding(bottom = PamSpacing.xl),
            content = content,
        )
    }
}

/** Grab handle of the sheet. Decorative, so it carries no content description. */
@Composable
fun SheetDragHandle(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = PamSpacing.sm),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .width(40.dp)
                .height(4.dp)
                .clip(RoundedCornerShape(PamRadius.pill))
                .background(OutlineVariant),
        )
    }
}
