package com.example.llamadroid.ui.ai.llama.chat

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.llamadroid.R
import com.example.llamadroid.ui.components.DraftIntTextField


/** Numeric sampler field plus an "Auto" chip; Auto disables the field and the request omits the key. */
@Composable
internal fun LlamaSamplerNumericField(
    label: String,
    value: String,
    rangeText: String,
    error: String?,
    auto: Boolean,
    integer: Boolean = false,
    onAutoChange: (Boolean) -> Unit,
    onValueChange: (String) -> Unit
) {
    val autoLabel = stringResource(R.string.chat_sampler_auto)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top
    ) {
        OutlinedTextField(
            value = if (auto) "" else value,
            onValueChange = onValueChange,
            modifier = Modifier.weight(1f),
            enabled = !auto,
            singleLine = true,
            label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            placeholder = { Text(autoLabel) },
            isError = error != null && !auto,
            supportingText = {
                Text(
                    if (auto) "" else error ?: stringResource(R.string.llama_sampler_range_hint, rangeText),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            },
            keyboardOptions = KeyboardOptions(
                keyboardType = if (integer) KeyboardType.Number else KeyboardType.Decimal
            )
        )
        FilterChip(
            selected = auto,
            onClick = { onAutoChange(!auto) },
            label = { Text(autoLabel, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            modifier = Modifier.padding(top = 8.dp)
        )
    }
}

@Composable
internal fun LlamaMaxOutputTokensControl(
    enabled: Boolean,
    value: Int,
    onEnabledChange: (Boolean) -> Unit,
    onValueChange: (Int) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        LlamaToolToggleRow(
            label = stringResource(R.string.llama_max_output_tokens_toggle),
            description = stringResource(R.string.llama_max_output_tokens_desc),
            checked = enabled,
            onCheckedChange = onEnabledChange
        )
        AnimatedVisibility(visible = enabled) {
            DraftIntTextField(
                value = value.coerceAtLeast(1),
                onValueChange = { onValueChange(it.coerceAtLeast(1)) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.llama_max_output_tokens_label)) },
                supportingText = { Text(stringResource(R.string.llama_max_output_tokens_supporting)) },
                valueRange = 1..Int.MAX_VALUE
            )
        }
    }
}

@Composable
internal fun LlamaSeedControl(
    value: String,
    onValueChange: (String) -> Unit
) {
    OutlinedTextField(
        value = value,
        onValueChange = { next ->
            if (next.isBlank() || next == "-" || next.toIntOrNull() != null) {
                onValueChange(next)
            }
        },
        modifier = Modifier.fillMaxWidth(),
        label = { Text(stringResource(R.string.llama_seed_label)) },
        supportingText = { Text(stringResource(R.string.llama_seed_supporting)) },
        singleLine = true,
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
            keyboardType = androidx.compose.ui.text.input.KeyboardType.Number
        )
    )
}

@Composable
internal fun LlamaToolToggleRow(
    label: String,
    description: String,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit
) {
    val labelColor = if (enabled) {
        MaterialTheme.colorScheme.onSurface
    } else {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    }
    val descriptionColor = if (enabled) {
        MaterialTheme.colorScheme.onSurfaceVariant
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                label,
                style = MaterialTheme.typography.bodyMedium,
                color = labelColor
            )
            Text(
                description,
                style = MaterialTheme.typography.labelSmall,
                color = descriptionColor,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        Switch(
            checked = checked,
            enabled = enabled,
            onCheckedChange = onCheckedChange
        )
    }
}

@Composable
internal fun LlamaToolNumberRow(
    label: String,
    value: Int,
    onValueChange: (Int) -> Unit,
    range: IntRange
) {
    var text by remember { mutableStateOf(value.toString()) }
    var focused by remember { mutableStateOf(false) }

    LaunchedEffect(value, focused) {
        if (!focused && text.toIntOrNull() != value) {
            text = value.toString()
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall
        )
        OutlinedTextField(
            value = text,
            onValueChange = { raw ->
                val filtered = raw.filter { it.isDigit() }
                text = filtered
                filtered.toIntOrNull()
                    ?.takeIf { it in range }
                    ?.let(onValueChange)
            },
            modifier = Modifier
                .width(120.dp)
                .onFocusChanged { focusState ->
                    if (focused && !focusState.isFocused) {
                        val coerced = text.toIntOrNull()?.coerceIn(range) ?: value
                        text = coerced.toString()
                        onValueChange(coerced)
                    }
                    focused = focusState.isFocused
                },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodySmall,
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                keyboardType = androidx.compose.ui.text.input.KeyboardType.Number
            )
        )
    }
}
