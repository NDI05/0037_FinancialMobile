package com.pam.recap.ui.feature.entry

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pam.recap.R
import com.pam.recap.domain.model.Category
import com.pam.recap.domain.model.Direction
import com.pam.recap.ui.components.CategoryChip
import com.pam.recap.ui.components.CircularIconWell
import com.pam.recap.ui.components.GlassCard
import com.pam.recap.ui.components.GlassTopBar
import com.pam.recap.ui.components.NoticeBanner
import com.pam.recap.ui.components.PamScaffold
import com.pam.recap.ui.components.PillButton
import com.pam.recap.ui.components.PillButtonStyle
import com.pam.recap.ui.components.SheetScaffold
import com.pam.recap.ui.theme.BodyM
import com.pam.recap.ui.theme.BodyS
import com.pam.recap.ui.theme.Canvas
import com.pam.recap.ui.theme.DisplayL
import com.pam.recap.ui.theme.LabelL
import com.pam.recap.ui.theme.LabelM
import com.pam.recap.ui.theme.OnInk
import com.pam.recap.ui.theme.OnInkFaint
import com.pam.recap.ui.theme.OnInkMuted
import com.pam.recap.ui.theme.OutlineVariant
import com.pam.recap.ui.theme.PamRadius
import com.pam.recap.ui.theme.PamSpacing
import com.pam.recap.ui.theme.Primary
import com.pam.recap.ui.theme.Surface
import com.pam.recap.ui.theme.SurfaceLow
import com.pam.recap.ui.theme.TitleL
import com.pam.recap.ui.util.PamDateFormat
import com.pam.recap.ui.util.appErrorRes
import com.pam.recap.ui.util.categoryLabelRes

/**
 * §8 Manual entry, restyled to the Stitch reference (2026-11-25): a 20 dp segmented direction
 * toggle, the 32/700 amount card, one fields card (Deskripsi / Tanggal / Kategori / Catatan) and
 * the sticky accent save CTA. Behaviour is untouched: the amount parser still rejects malformed
 * input, the date stepper stays inside the rolling window (NFR-15), draft recovery and the
 * category list rules are the view model's, and the save CTA is disabled unless the row is valid.
 *
 * The reference keeps a custom in-app numeric keypad under the CTA; this screen keeps the system
 * keyboard instead, which the scaffold already insets with `imePadding()`.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ManualEntryScreen(
    onBack: () -> Unit,
    onSaved: (savedMonthId: String?) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ManualEntryViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(state.saved) {
        if (state.saved) onSaved(state.savedMonthId)
    }
    var showCategorySheet by remember { mutableStateOf(false) }

    PamScaffold(
        modifier = modifier,
        topBar = {
            GlassTopBar(
                title = stringResource(R.string.manual_title),
                onBack = onBack,
            )
        },
        bottomBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Canvas)
                    .padding(horizontal = PamSpacing.gutter, vertical = PamSpacing.sm),
            ) {
                // Kept from the previous build: the hint says which month the row lands in.
                Text(
                    text = stringResource(
                        R.string.manual_saved_hint,
                        state.date.format(
                            java.time.format.DateTimeFormatter.ofPattern(
                                "MMMM yyyy", java.util.Locale("id", "ID"),
                            ),
                        ),
                    ),
                    style = BodyS,
                    color = OnInkMuted,
                    modifier = Modifier.padding(bottom = PamSpacing.xs),
                )
                PillButton(
                    text = stringResource(R.string.manual_save),
                    onClick = viewModel::save,
                    enabled = state.canSave,
                    loading = state.isSaving,
                    style = PillButtonStyle.Accent,
                    shape = CircleShape,
                    trailingIcon = Icons.Rounded.ChevronRight,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(top = PamSpacing.xs),
            verticalArrangement = Arrangement.spacedBy(PamSpacing.md),
        ) {
            DirectionToggle(
                selected = state.direction,
                onSelect = viewModel::setDirection,
            )

            GlassCard(
                modifier = Modifier.fillMaxWidth(),
                radius = PamRadius.xl,
                contentPadding = PaddingValues(PamSpacing.md),
            ) {
                FieldLabel(text = stringResource(R.string.manual_nominal_label))
                Spacer(modifier = Modifier.height(PamSpacing.xxs + 2.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(PamSpacing.xs),
                ) {
                    Text(
                        text = "Rp",
                        style = TitleL,
                        color = OnInkMuted,
                    )
                    BasicTextField(
                        value = state.amountText,
                        onValueChange = viewModel::setAmount,
                        textStyle = DisplayL.copy(color = OnInk),
                        cursorBrush = SolidColor(Primary),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        visualTransformation = ThousandsSeparator,
                        modifier = Modifier
                            .weight(1f)
                            .padding(vertical = PamSpacing.xxs),
                        decorationBox = { innerTextField ->
                            Box {
                                if (state.amountText.isEmpty()) {
                                    Text(
                                        text = stringResource(R.string.manual_amount_placeholder),
                                        style = DisplayL,
                                        color = OnInkFaint,
                                    )
                                }
                                innerTextField()
                            }
                        },
                    )
                }
            }

            GlassCard(
                modifier = Modifier.fillMaxWidth(),
                radius = PamRadius.xl,
                contentPadding = PaddingValues(PamSpacing.md),
            ) {
                FieldLabel(text = stringResource(R.string.manual_desc_label))
                Spacer(modifier = Modifier.height(PamSpacing.xxs + 2.dp))
                PamInput(
                    value = state.description,
                    onValueChange = viewModel::setDescription,
                    placeholder = stringResource(R.string.manual_desc_placeholder),
                )

                Spacer(modifier = Modifier.height(PamSpacing.md))
                FieldLabel(text = stringResource(R.string.manual_date_stitch))
                Spacer(modifier = Modifier.height(PamSpacing.xxs + 2.dp))
                DateField(
                    date = state.date,
                    canGoEarlier = state.dateRange.canGoEarlier(state.date),
                    canGoLater = state.dateRange.canGoLater(state.date),
                    onEarlier = { viewModel.shiftDate(-1) },
                    onLater = { viewModel.shiftDate(1) },
                )
                if (state.dateOutOfWindow) {
                    Spacer(modifier = Modifier.height(PamSpacing.xs))
                    NoticeBanner(text = stringResource(R.string.manual_date_out_of_window))
                }

                Spacer(modifier = Modifier.height(PamSpacing.md))
                FieldLabel(text = stringResource(R.string.manual_category_stitch))
                Spacer(modifier = Modifier.height(PamSpacing.xxs + 2.dp))
                CategoryField(
                    label = stringResource(categoryLabelRes(state.category)),
                    onClick = { showCategorySheet = true },
                )

                Spacer(modifier = Modifier.height(PamSpacing.md))
                FieldLabel(text = stringResource(R.string.manual_note_stitch))
                Spacer(modifier = Modifier.height(PamSpacing.xxs + 2.dp))
                PamTextArea(
                    value = state.note,
                    onValueChange = viewModel::setNote,
                    placeholder = stringResource(R.string.manual_note_stitch_placeholder),
                )
            }

            if (state.error != null) {
                NoticeBanner(text = stringResource(appErrorRes(requireNotNull(state.error))))
            }
        }
    }

    if (showCategorySheet) {
        SheetScaffold(onDismissRequest = { showCategorySheet = false }) {
            Text(
                text = stringResource(R.string.manual_category_stitch),
                style = com.pam.recap.ui.theme.HeadlineM,
                color = OnInk,
            )
            Spacer(modifier = Modifier.height(PamSpacing.sm))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(PamSpacing.xs),
                verticalArrangement = Arrangement.spacedBy(PamSpacing.xs),
            ) {
                val categories = if (state.direction == Direction.CREDIT) {
                    listOf(Category.INCOME, Category.OTHER_EXPENSE)
                } else {
                    Category.expenseCategories + Category.TRANSFER
                }
                categories.forEach { category ->
                    CategoryChip(
                        category = category,
                        label = stringResource(categoryLabelRes(category)),
                        selected = state.category == category,
                        onClick = {
                            viewModel.setCategory(category)
                            showCategorySheet = false
                        },
                    )
                }
            }
        }
    }
}

/**
 * `bg-surface-container-low p-1 rounded-xl` segmented control with the white `rounded-lg` active
 * segment from the reference. Both segments stay readable controls for TalkBack (radio role).
 */
@Composable
private fun DirectionToggle(
    selected: Direction,
    onSelect: (Direction) -> Unit,
) {
    val trackShape = RoundedCornerShape(PamRadius.xl)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(trackShape)
            .background(SurfaceLow)
            .border(1.dp, OutlineVariant, trackShape)
            .padding(PamSpacing.xxs),
        horizontalArrangement = Arrangement.spacedBy(PamSpacing.xxs),
    ) {
        listOf(
            Direction.DEBIT to R.string.manual_expense_tab,
            Direction.CREDIT to R.string.manual_income_tab,
        ).forEach { (direction, labelRes) ->
            val isSelected = selected == direction
            val segmentShape = RoundedCornerShape(PamRadius.md)
            Box(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 44.dp)
                    .clip(segmentShape)
                    .background(if (isSelected) Surface else Color.Transparent)
                    .then(
                        if (isSelected) {
                            Modifier.border(1.dp, OutlineVariant, segmentShape)
                        } else {
                            Modifier
                        },
                    )
                    .selectable(
                        selected = isSelected,
                        role = Role.RadioButton,
                        onClick = { onSelect(direction) },
                    )
                    .padding(horizontal = PamSpacing.xs),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(labelRes),
                    style = LabelL,
                    color = if (isSelected) OnInk else OnInkMuted,
                )
            }
        }
    }
}

/** 12/600 muted field label from the reference form. */
@Composable
private fun FieldLabel(text: String) {
    Text(text = text, style = LabelM, color = OnInkMuted)
}

/** `#F4F4F1` 16 dp inset input with a 48 dp minimum height (reference `px-3.5 min-h-[48px]`). */
@Composable
private fun PamInput(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(PamRadius.md)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(shape)
            .background(SurfaceLow)
            .border(1.dp, OutlineVariant, shape)
            .padding(horizontal = 14.dp, vertical = PamSpacing.sm),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (value.isEmpty()) {
            Text(text = placeholder, style = BodyM, color = OnInkFaint)
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            textStyle = BodyM.copy(color = OnInk),
            cursorBrush = SolidColor(Primary),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** Multi-line variant of [PamInput] for the optional note (reference uses a 2-row textarea). */
@Composable
private fun PamTextArea(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
) {
    val shape = RoundedCornerShape(PamRadius.md)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 70.dp)
            .clip(shape)
            .background(SurfaceLow)
            .border(1.dp, OutlineVariant, shape)
            .padding(horizontal = 14.dp, vertical = PamSpacing.sm),
    ) {
        if (value.isEmpty()) {
            Text(text = placeholder, style = BodyM, color = OnInkFaint)
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            textStyle = BodyM.copy(color = OnInk),
            cursorBrush = SolidColor(Primary),
            minLines = 2,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * Read-only date value inside the same inset field, flanked by the window-bounded steppers
 * (NFR-15). The date itself is still derived from the same Jakarta noon instant as before.
 */
@Composable
private fun DateField(
    date: java.time.LocalDate,
    canGoEarlier: Boolean,
    canGoLater: Boolean,
    onEarlier: () -> Unit,
    onLater: () -> Unit,
) {
    val shape = RoundedCornerShape(PamRadius.md)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(shape)
            .background(SurfaceLow)
            .border(1.dp, OutlineVariant, shape)
            .padding(horizontal = PamSpacing.xxs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularIconWell(
            icon = Icons.Rounded.ChevronLeft,
            contentDescription = stringResource(R.string.action_previous),
            enabled = canGoEarlier,
            onClick = onEarlier,
            size = 40.dp,
            containerColor = Color.Transparent,
        )
        Text(
            text = PamDateFormat.date(
                kotlin.time.Instant.fromEpochMilliseconds(
                    date.atTime(java.time.LocalTime.NOON)
                        .atZone(java.time.ZoneId.of(com.pam.recap.core.time.JakartaMonth.ZONE.id))
                        .toInstant().toEpochMilli(),
                ),
            ),
            style = BodyM,
            color = OnInk,
            textAlign = TextAlign.Center,
            modifier = Modifier.weight(1f),
        )
        CircularIconWell(
            icon = Icons.Rounded.ChevronRight,
            contentDescription = stringResource(R.string.action_next),
            enabled = canGoLater,
            onClick = onLater,
            size = 40.dp,
            containerColor = Color.Transparent,
        )
    }
}

/** Category picker trigger styled as the reference `expand_more` select input. */
@Composable
private fun CategoryField(
    label: String,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(PamRadius.md)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(shape)
            .background(SurfaceLow)
            .border(1.dp, OutlineVariant, shape)
            .clickable(
                role = Role.Button,
                onClickLabel = stringResource(R.string.manual_category_stitch),
                onClick = onClick,
            )
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(PamSpacing.xs),
    ) {
        Text(
            text = label,
            style = BodyM,
            color = OnInk,
            modifier = Modifier.weight(1f),
        )
        Icon(
            imageVector = Icons.Rounded.ExpandMore,
            contentDescription = null,
            tint = OnInkMuted,
            modifier = Modifier.height(20.dp),
        )
    }
}

/**
 * Groups the nominally plain digits with Indonesian thousand separators while keeping the cursor
 * anchored to the digit it was between. The stored value stays digit-only, so parsing is unchanged.
 */
private object ThousandsSeparator : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val raw = text.text
        if (raw.length <= 3) return TransformedText(text, OffsetMapping.Identity)
        val out = StringBuilder(raw.length + raw.length / 3)
        raw.forEachIndexed { index, char ->
            out.append(char)
            val remaining = raw.length - index - 1
            if (remaining > 0 && remaining % 3 == 0) out.append('.')
        }
        val transformed = AnnotatedString(out.toString())
        val forward = object : OffsetMapping {
            override fun originalToTransformed(offset: Int): Int {
                if (offset >= raw.length) return transformed.length
                var dots = 0
                for (index in offset until raw.length - 1) {
                    if ((raw.length - index - 1) % 3 == 0) dots++
                }
                return (offset + dots).coerceIn(0, transformed.length)
            }

            override fun transformedToOriginal(offset: Int): Int =
                transformed.text.take(offset).count { it.isDigit() }.coerceIn(0, raw.length)
        }
        return TransformedText(transformed, forward)
    }
}
