package com.pam.recap.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.pam.recap.ui.theme.GlassFill
import com.pam.recap.ui.theme.Ink
import com.pam.recap.ui.theme.LabelL
import com.pam.recap.ui.theme.OnInk
import com.pam.recap.ui.theme.OnInkMuted
import com.pam.recap.ui.theme.PamRadius
import com.pam.recap.ui.theme.PamSpacing
import com.pam.recap.ui.theme.TitleM
import com.pam.recap.ui.theme.TrackInset
import com.pam.recap.ui.theme.Violet

/**
 * Consent row: independent title/body plus a switch. The switch stays a real Material3 `Switch`
 * (so TalkBack announces the checked state as a toggle) while the track/thumb use theme tokens.
 */
@Composable
fun ConsentSwitchRow(
    title: String,
    body: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    switchDescription: String? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(PamRadius.md))
            .background(GlassFill)
            .padding(PamSpacing.md)
            .minimumInteractiveComponentSize(),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(PamSpacing.sm),
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(PamSpacing.xxs),
        ) {
            Text(text = title, style = LabelL, color = OnInk)
            Text(text = body, style = TitleM, color = OnInkMuted)
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
            modifier = if (switchDescription != null) {
                Modifier.semantics { contentDescription = switchDescription }
            } else {
                Modifier
            },
            colors = SwitchDefaults.colors(
                checkedThumbColor = Ink,
                checkedTrackColor = Violet,
                checkedBorderColor = Violet,
                uncheckedThumbColor = com.pam.recap.ui.theme.OnInkFaint,
                uncheckedTrackColor = TrackInset,
                uncheckedBorderColor = TrackInset,
            ),
        )
    }
}

/**
 * Labelled number field used by manual entry. Kept as a component so the
 * amortised glass styling and the 48 dp target are identical everywhere.
 */
@Composable
fun LabelledField(
    label: String?,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    suffix: String? = null,
    keyboardOptions: androidx.compose.foundation.text.KeyboardOptions =
        androidx.compose.foundation.text.KeyboardOptions.Default,
    visualTransformation: androidx.compose.ui.text.input.VisualTransformation =
        androidx.compose.ui.text.input.VisualTransformation.None,
    trailing: (@Composable () -> Unit)? = null,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        if (!label.isNullOrBlank()) {
            Text(text = label, style = com.pam.recap.ui.theme.LabelM, color = OnInkMuted)
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = PamSpacing.xxs)
                .clip(RoundedCornerShape(PamRadius.sm))
                .background(GlassFill)
                .padding(horizontal = PamSpacing.md, vertical = PamSpacing.sm)
                .minimumInteractiveComponentSize(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            androidx.compose.foundation.text.BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                keyboardOptions = keyboardOptions,
                visualTransformation = visualTransformation,
                textStyle = com.pam.recap.ui.theme.TitleL.copy(color = OnInk),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(Violet),
                modifier = Modifier.weight(1f),
                decorationBox = { inner ->
                    if (value.isEmpty() && placeholder != null) {
                        Text(text = placeholder, style = TitleM, color = OnInkMuted)
                    }
                    inner()
                },
            )
            if (suffix != null) {
                Text(text = suffix, style = TitleM, color = OnInkMuted)
            }
            if (trailing != null) trailing()
        }
    }
}

/** Small label/value line used by provenance and settings blocks. */
@Composable
fun KeyValueRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(PamSpacing.sm),
    ) {
        Text(
            text = label,
            style = TitleM,
            color = OnInkMuted,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = TitleM,
            color = OnInk,
            modifier = Modifier.weight(1.2f),
            textAlign = androidx.compose.ui.text.style.TextAlign.End,
        )
    }
}
