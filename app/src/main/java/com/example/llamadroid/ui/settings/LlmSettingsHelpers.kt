package com.example.llamadroid.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.example.llamadroid.R
import com.example.llamadroid.data.SettingsRepository
import com.example.llamadroid.data.binary.BinaryRepository
import com.example.llamadroid.data.binary.EffectiveLlamaBinary
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.data.db.ModelType
import com.example.llamadroid.data.db.SavedCommand
import com.example.llamadroid.data.db.SavedCommandScopes
import com.example.llamadroid.data.db.launchProfile
import com.example.llamadroid.data.db.savedCommandFromLaunchProfile
import com.example.llamadroid.service.LlamaSpeculativeMode
import com.example.llamadroid.service.LlamaLoadMode
import com.example.llamadroid.service.LlamaLoraSpec
import com.example.llamadroid.service.LlamaServerLaunchProfile
import com.example.llamadroid.service.LlamaVideoProfileLimits
import com.example.llamadroid.service.effectiveSpeculativeDraftPath
import com.example.llamadroid.service.speculativeDraftModelsFor
import com.example.llamadroid.ui.components.AppChromeDefaults
import com.example.llamadroid.ui.components.AppScreenScaffold
import kotlinx.coroutines.launch
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LlmModeDropdown(
    label: String,
    selected: String,
    options: List<Pair<String, String>>,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onSelected: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val selectedLabel = options.firstOrNull { it.first == selected }?.second ?: options.firstOrNull()?.second.orEmpty()
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = onExpandedChange,
        modifier = modifier.fillMaxWidth()
    ) {
        OutlinedTextField(
            value = selectedLabel,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .menuAnchor()
                .fillMaxWidth(),
            singleLine = true
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { onExpandedChange(false) },
            modifier = Modifier.heightIn(max = 420.dp)
        ) {
            options.forEach { (value, text) ->
                DropdownMenuItem(
                    text = {
                        Text(
                            text,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    onClick = {
                        onSelected(value)
                        onExpandedChange(false)
                    }
                )
            }
        }
    }
}

@Composable
internal fun LlmAdvancedToggle(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Medium)
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

internal data class LlamaParallelContextBreakdown(
    val totalContext: Int,
    val parallel: Int,
    val contextPerSequence: Int,
    val remainder: Int
)

internal fun calculateLlamaParallelContext(
    totalContext: Int,
    configuredParallel: Int?
): LlamaParallelContextBreakdown {
    val safeContext = totalContext.coerceAtLeast(0)
    val effectiveParallel = (configuredParallel ?: 1).coerceAtLeast(1)
    return LlamaParallelContextBreakdown(
        totalContext = safeContext,
        parallel = effectiveParallel,
        contextPerSequence = safeContext / effectiveParallel,
        remainder = safeContext % effectiveParallel
    )
}

internal fun containsManagedLlamaFlag(customFlags: String): Boolean {
    val managed = setOf(
        "--parallel", "-np", "--cache-ram", "-cram", "--ctx-checkpoints", "-ctxcp",
        "--swa-checkpoints", "--checkpoint-min-step", "-cms", "--cache-prompt",
        "--no-cache-prompt", "--cache-idle-slots", "--no-cache-idle-slots",
        "--kv-unified", "-kvu", "--no-kv-unified", "-no-kvu", "--swa-full",
        "--no-swa-full", "--sleep-idle-seconds"
    )
    return customFlags
        .split(Regex("\\s+"))
        .asSequence()
        .map { it.substringBefore('=') }
        .any { it in managed }
}

@Composable
internal fun DraftIntTextField(
    value: Int,
    onValueChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    label: @Composable (() -> Unit)? = null,
    placeholder: @Composable (() -> Unit)? = null,
    valueRange: IntRange? = null,
    singleLine: Boolean = true
) {
    var draft by remember(value) { mutableStateOf(value.toString()) }
    OutlinedTextField(
        value = draft,
        onValueChange = {
            draft = it.filter(Char::isDigit)
            draft.toIntOrNull()?.let { parsed ->
                if (valueRange?.contains(parsed) != false) onValueChange(parsed)
            }
        },
        modifier = modifier,
        label = label,
        placeholder = placeholder,
        singleLine = singleLine
    )
}

@Composable
internal fun DraftNullableIntTextField(
    value: Int?,
    onValueChange: (Int?) -> Unit,
    modifier: Modifier = Modifier,
    label: @Composable (() -> Unit)? = null,
    placeholder: @Composable (() -> Unit)? = null,
    singleLine: Boolean = true,
    valueRange: IntRange? = null
) {
    var draft by remember(value) { mutableStateOf(value?.toString().orEmpty()) }
    OutlinedTextField(
        value = draft,
        onValueChange = {
            draft = it.filter(Char::isDigit)
            if (draft.isBlank()) {
                onValueChange(null)
            } else {
                draft.toIntOrNull()?.let { parsed ->
                    if (valueRange?.contains(parsed) != false) onValueChange(parsed)
                }
            }
        },
        modifier = modifier,
        label = label,
        placeholder = placeholder,
        singleLine = singleLine
    )
}

@Composable
internal fun DraftFloatTextField(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    label: @Composable (() -> Unit)? = null,
    singleLine: Boolean = true,
    valueRange: ClosedFloatingPointRange<Float>? = null,
    allowSigned: Boolean = false
) {
    var draft by remember(value) { mutableStateOf(value.toString()) }
    OutlinedTextField(
        value = draft,
        onValueChange = {
            draft = buildString {
                var seenDot = false
                it.forEachIndexed { index, ch ->
                    if (ch.isDigit()) append(ch)
                    if (ch == '-' && allowSigned && index == 0) append(ch)
                    if (ch == '.' && !seenDot) {
                        append(ch)
                        seenDot = true
                    }
                }
            }
            draft.toFloatOrNull()
                ?.takeIf { it.isFinite() }
                ?.let { parsed ->
                    if (valueRange?.contains(parsed) != false) onValueChange(parsed)
                }
        },
        modifier = modifier,
        label = label,
        singleLine = singleLine,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
    )
}

internal fun loraPathsMatch(first: String, second: String): Boolean =
    first.trim().equals(second.trim(), ignoreCase = true)

internal fun safeLoraStrength(value: Float): Float =
    if (value.isFinite()) value else 1f

internal fun LlamaLoadMode.localizedLabelRes(): Int = when (name.lowercase(Locale.ROOT)) {
    "auto" -> R.string.llm_load_mode_auto
    "none" -> R.string.llm_load_mode_none
    "mmap" -> R.string.llm_load_mode_mmap
    "mlock" -> R.string.llm_load_mode_mlock
    "mmap_mlock", "mmap+mlock" -> R.string.llm_load_mode_mmap_mlock
    "dio", "direct_io", "directio" -> R.string.llm_load_mode_dio
    else -> R.string.llm_load_mode_unknown
}

internal fun LlamaLoadMode.localizedDescriptionRes(): Int = when (name.lowercase(Locale.ROOT)) {
    "auto" -> R.string.llm_load_mode_auto_desc
    "none" -> R.string.llm_load_mode_none_desc
    "mmap" -> R.string.llm_load_mode_mmap_desc
    "mlock" -> R.string.llm_load_mode_mlock_desc
    "mmap_mlock", "mmap+mlock" -> R.string.llm_load_mode_mmap_mlock_desc
    "dio", "direct_io", "directio" -> R.string.llm_load_mode_dio_desc
    else -> R.string.llm_load_mode_unknown_desc
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LlamaLoadModePicker(
    selected: LlamaLoadMode,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onSelected: (LlamaLoadMode) -> Unit
) {
    val modes = remember { LlamaLoadMode.entries.toList() }
    val selectedLabel = stringResource(selected.localizedLabelRes())
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = onExpandedChange,
        modifier = Modifier.fillMaxWidth()
    ) {
        OutlinedTextField(
            value = selectedLabel,
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.llm_load_mode_label)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.menuAnchor().fillMaxWidth(),
            singleLine = true
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { onExpandedChange(false) }
        ) {
            modes.forEach { mode ->
                DropdownMenuItem(
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                stringResource(mode.localizedLabelRes()),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                stringResource(mode.localizedDescriptionRes()),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    },
                    onClick = {
                        onSelected(mode)
                        onExpandedChange(false)
                    }
                )
            }
        }
    }
    Spacer(modifier = Modifier.height(6.dp))
    Text(
        stringResource(selected.localizedDescriptionRes()),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
internal fun LlamaLoraStackRow(
    index: Int,
    spec: LlamaLoraSpec,
    onStrengthChange: (Float) -> Unit,
    onMoveUp: (() -> Unit)?,
    onMoveDown: (() -> Unit)?,
    onRemove: () -> Unit
) {
    val strength = safeLoraStrength(spec.strength)
    val sliderStrength = strength.coerceIn(0f, 2f)
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, top = 10.dp, end = 4.dp, bottom = 10.dp),
            verticalAlignment = Alignment.Top
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    stringResource(R.string.llm_lora_stack_item, index + 1),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    spec.path.substringAfterLast('/').ifBlank { spec.path },
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        stringResource(R.string.llm_lora_stack_strength),
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.widthIn(max = 78.dp),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Slider(
                        value = sliderStrength,
                        onValueChange = { onStrengthChange(it.coerceIn(0f, 2f)) },
                        valueRange = 0f..2f,
                        steps = 19,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        String.format(Locale.ROOT, "%.3g", strength.toDouble()),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.widthIn(min = 44.dp, max = 72.dp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                DraftFloatTextField(
                    value = strength,
                    onValueChange = { onStrengthChange(safeLoraStrength(it)) },
                    label = { Text(stringResource(R.string.llm_lora_stack_strength_input)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    allowSigned = true
                )
                Text(
                    stringResource(R.string.llm_lora_stack_strength_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                when {
                    ',' in spec.path -> R.string.llm_lora_stack_invalid_comma
                    strength != 1f && ':' in spec.path -> R.string.llm_lora_stack_invalid_scaled_colon
                    else -> null
                }?.let { errorRes ->
                    Text(
                        stringResource(errorRes),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(0.dp),
                modifier = Modifier.width(48.dp)
            ) {
                IconButton(
                    onClick = { onMoveUp?.invoke() },
                    enabled = onMoveUp != null,
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(
                        Icons.Default.KeyboardArrowUp,
                        contentDescription = stringResource(R.string.llm_lora_stack_move_up),
                    )
                }
                IconButton(
                    onClick = { onMoveDown?.invoke() },
                    enabled = onMoveDown != null,
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(
                        Icons.Default.KeyboardArrowDown,
                        contentDescription = stringResource(R.string.llm_lora_stack_move_down),
                    )
                }
                IconButton(
                    onClick = onRemove,
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = stringResource(R.string.llm_lora_stack_remove),
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}
