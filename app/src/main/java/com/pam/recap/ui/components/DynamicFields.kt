package com.pam.recap.ui.components

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.pam.recap.R
import com.pam.recap.ui.theme.OnInkMuted

/** One user-defined attribute pair. The key is a machine label, the value is free text. */
data class DynamicField(val key: String = "", val value: String = "")

/**
 * Limits from the S3 gate: at most 10 pairs, key ≤ 40 characters, value ≤ 200 characters and the
 * encoded payload ≤ 2 KB. Everything is sent as JSON strings to the server and is never part of a
 * classifier prompt.
 */
object DynamicFieldRules {
    const val MAX_FIELDS = 10
    const val MAX_KEY = 40
    const val MAX_VALUE = 200
    const val MAX_ENCODED_BYTES = 2048

    const val RULE_TOO_MANY = "dynamic_fields_too_many"
    const val RULE_KEY_TOO_LONG = "dynamic_field_key_too_long"
    const val RULE_VALUE_TOO_LONG = "dynamic_field_value_too_long"
    const val RULE_DUPLICATE = "dynamic_field_duplicate"
    const val RULE_TOO_LARGE = "dynamic_fields_too_large"

    /** UTF-8 encoded size of the JSON object this list becomes. */
    fun encodedBytes(fields: List<DynamicField>): Int {
        val used = fields.filter { it.key.isNotBlank() }
        val payload = used.joinToString(separator = ",", prefix = "{", postfix = "}") {
            "\"${it.key}\":\"${it.value}\""
        }
        return payload.toByteArray(Charsets.UTF_8).size
    }

    /** Returns the first violated rule id, or null when the list is valid. */
    fun validate(fields: List<DynamicField>): String? {
        val used = fields.filter { it.key.isNotBlank() || it.value.isNotBlank() }
        if (used.size > MAX_FIELDS) return RULE_TOO_MANY
        if (used.any { it.key.length > MAX_KEY }) return RULE_KEY_TOO_LONG
        if (used.any { it.value.length > MAX_VALUE }) return RULE_VALUE_TOO_LONG
        if (used.map { it.key.trim() }.distinct().size != used.size) return RULE_DUPLICATE
        if (encodedBytes(used) > MAX_ENCODED_BYTES) return RULE_TOO_LARGE
        return null
    }

    /** Only pairs with a key are persisted; blank editor rows disappear on save. */
    fun toMap(fields: List<DynamicField>): Map<String, String> =
        fields.filter { it.key.isNotBlank() }.associate { it.key.trim() to it.value }

    fun fromMap(attributes: Map<String, String>): List<DynamicField> =
        attributes.entries.map { DynamicField(it.key, it.value) }.ifEmpty { listOf(DynamicField()) }
}

/** Localized copy for a [DynamicFieldRules] rule id; every attributes editor shares this mapping. */
@StringRes
fun dynamicFieldRuleRes(rule: String): Int = when (rule) {
    DynamicFieldRules.RULE_TOO_MANY -> R.string.dynamic_fields_too_many
    DynamicFieldRules.RULE_KEY_TOO_LONG -> R.string.dynamic_field_key_too_long
    DynamicFieldRules.RULE_VALUE_TOO_LONG -> R.string.dynamic_field_value_too_long
    DynamicFieldRules.RULE_DUPLICATE -> R.string.dynamic_field_duplicate
    DynamicFieldRules.RULE_TOO_LARGE -> R.string.dynamic_fields_too_large
    else -> R.string.dynamic_fields_invalid
}

/**
 * Reusable attribute editor for categories, transactions, budgets and attachments. It only edits
 * text pairs; validation happens in the owning ViewModel via [DynamicFieldRules].
 */
@Composable
fun DynamicFieldsEditor(
    fields: List<DynamicField>,
    onFieldsChange: (List<DynamicField>) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = stringResource(R.string.dynamic_fields_label),
            color = OnInkMuted,
        )
        fields.forEachIndexed { index, field ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = field.key,
                    onValueChange = {
                        if (it.length <= DynamicFieldRules.MAX_KEY) {
                            onFieldsChange(fields.toMutableList().also { list -> list[index] = field.copy(key = it) })
                        }
                    },
                    modifier = Modifier.weight(1f),
                    label = { Text(stringResource(R.string.dynamic_field_key)) },
                    singleLine = true,
                    enabled = enabled,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None),
                )
                OutlinedTextField(
                    value = field.value,
                    onValueChange = {
                        if (it.length <= DynamicFieldRules.MAX_VALUE) {
                            onFieldsChange(fields.toMutableList().also { list -> list[index] = field.copy(value = it) })
                        }
                    },
                    modifier = Modifier.weight(1.4f),
                    label = { Text(stringResource(R.string.dynamic_field_value)) },
                    singleLine = true,
                    enabled = enabled,
                )
                TextButton(
                    onClick = {
                        val next = fields.toMutableList().also { it.removeAt(index) }
                        onFieldsChange(next.ifEmpty { listOf(DynamicField()) })
                    },
                    enabled = enabled,
                ) {
                    Text(stringResource(R.string.action_remove))
                }
            }
        }
        TextButton(
            onClick = { onFieldsChange(fields + DynamicField()) },
            enabled = enabled && fields.size < DynamicFieldRules.MAX_FIELDS,
        ) {
            Text(stringResource(R.string.dynamic_field_add))
        }
        Text(
            text = stringResource(R.string.dynamic_fields_hint),
            color = OnInkMuted,
            modifier = Modifier.padding(start = 4.dp),
        )
    }
}
