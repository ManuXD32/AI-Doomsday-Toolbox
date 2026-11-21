@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.example.llamadroid.ui.distributed.sd

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.llamadroid.R
import com.example.llamadroid.data.db.ModelEntity
import com.example.llamadroid.sd.SdComponentCandidate
import com.example.llamadroid.sd.SdComponentRole
import com.example.llamadroid.sd.SdLoraApplyMode
import com.example.llamadroid.sd.SdLoraSpec
import com.example.llamadroid.service.SamplingMethod
import com.example.llamadroid.service.SdCacheMode
import com.example.llamadroid.ui.components.ComponentCandidatePicker

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ModelPicker(
    label: String,
    models: List<ModelEntity>,
    selectedPath: String,
    onSelectedPath: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedName = models.firstOrNull { it.path == selectedPath }?.filename ?: stringResource(R.string.sd_dist_select_model)
    Column {
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = !expanded }) {
        OutlinedTextField(
            value = selectedName,
            onValueChange = {},
            readOnly = true,
            modifier = Modifier.fillMaxWidth().menuAnchor(),
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            singleLine = true
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            if (models.isEmpty()) {
                DropdownMenuItem(text = { Text(stringResource(R.string.sd_dist_no_models_available)) }, onClick = { expanded = false })
            } else {
                models.forEach { model ->
                    DropdownMenuItem(
                        text = { Text(model.filename, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        onClick = {
                            onSelectedPath(model.path)
                            expanded = false
                        }
                    )
                }
            }
        }
    }
    }
}

@Composable
internal fun ImageComponentPickerSection(
    mode: String,
    components: SdDistributedImageComponentChoices,
    onComponentPathChange: (SdComponentRole, String) -> Unit,
    onControlNetEnabledChange: (Boolean) -> Unit,
    onLoraEnabledChange: (Boolean) -> Unit,
    loraStack: List<SdLoraSpec>,
    onLoraStackChange: (List<SdLoraSpec>) -> Unit,
    onLoraApplyModeChange: (String) -> Unit,
    onAddFile: (SdComponentRole) -> Unit
) {
    val img2img = mode == "IMG2IMG"
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = stringResource(R.string.imagegen_components_title),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            text = stringResource(R.string.imagegen_components_desc, components.familyLabel.ifBlank { "-" }),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (components.spec == null) {
            Text(
                text = stringResource(R.string.media_picker_unknown_family),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
        components.roles.forEach { role ->
            val embedded = role in components.embeddedRoles
            val required = role in components.requiredRoles && !embedded
            ComponentCandidatePicker(
                label = "${sdDistributedComponentRoleLabel(role)} (${
                    stringResource(
                        if (required) R.string.media_picker_role_required else R.string.media_picker_role_optional
                    )
                })",
                candidates = components.candidates[role].orEmpty(),
                selectedPath = components.selectedPaths[role].orEmpty(),
                onSelectedPath = { onComponentPathChange(role, it) },
                required = required,
                includedInMain = embedded,
                onAddFile = { onAddFile(role) }
            )
        }
    }

    // Always visible: adapters are optional and any installed file can be picked.
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = stringResource(R.string.imagegen_adapters_title),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            text = stringResource(R.string.imagegen_adapters_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (img2img) {
            OptionalComponentPicker(
                enabled = components.controlNetEnabled,
                onEnabledChange = onControlNetEnabledChange,
                label = sdDistributedComponentRoleLabel(SdComponentRole.CONTROLNET),
                candidates = components.candidates[SdComponentRole.CONTROLNET].orEmpty(),
                selectedPath = components.selectedPaths[SdComponentRole.CONTROLNET].orEmpty(),
                onSelectedPath = { onComponentPathChange(SdComponentRole.CONTROLNET, it) },
                onAddFile = { onAddFile(SdComponentRole.CONTROLNET) }
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Checkbox(
                checked = components.loraEnabled,
                onCheckedChange = onLoraEnabledChange
            )
            Text(
                text = sdDistributedComponentRoleLabel(SdComponentRole.LORA),
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (components.loraEnabled) {
            DistributedLoraStackEditor(
                label = stringResource(R.string.imagegen_lora_stack_label),
                candidates = components.candidates[SdComponentRole.LORA].orEmpty(),
                stack = loraStack,
                onStackChange = onLoraStackChange,
                onAddFile = { onAddFile(SdComponentRole.LORA) }
            )
            if (components.spec?.supportsLoraApplyMode == true) {
                SimpleStringPicker(
                    label = stringResource(R.string.imagegen_lora_apply_mode_label),
                    selected = components.loraApplyMode,
                    options = listOf("") + SdLoraApplyMode.entries.map { it.cliName },
                    offLabel = stringResource(R.string.imagegen_lora_apply_mode_default),
                    onSelected = onLoraApplyModeChange
                )
            }
        }
    }
}

@Composable
internal fun DistributedLoraStackEditor(
    label: String,
    candidates: List<SdComponentCandidate>,
    stack: List<SdLoraSpec>,
    onStackChange: (List<SdLoraSpec>) -> Unit,
    onAddFile: (() -> Unit)? = null
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        stack.forEachIndexed { index, item ->
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.imagegen_lora_item, index + 1),
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        IconButton(
                            onClick = {
                                onStackChange(stack.filterIndexed { itemIndex, _ -> itemIndex != index })
                            }
                        ) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = stringResource(R.string.imagegen_lora_remove)
                            )
                        }
                    }
                    ComponentCandidatePicker(
                        label = stringResource(R.string.imagegen_lora_item, index + 1),
                        candidates = candidates,
                        selectedPath = item.path,
                        onSelectedPath = { path ->
                            onStackChange(stack.mapIndexed { itemIndex, current ->
                                if (itemIndex == index) current.copy(path = path) else current
                            })
                        },
                        required = true,
                        includedInMain = false,
                        onAddFile = onAddFile
                    )
                    OutlinedTextField(
                        value = item.strength.toString(),
                        onValueChange = { raw ->
                            raw.toFloatOrNull()?.let { strength ->
                                onStackChange(stack.mapIndexed { itemIndex, current ->
                                    if (itemIndex == index) {
                                        current.copy(strength = strength.coerceIn(SdLoraSpec.MIN_STRENGTH, SdLoraSpec.MAX_STRENGTH))
                                    } else {
                                        current
                                    }
                                })
                            }
                        },
                        label = { Text(stringResource(R.string.imagegen_lora_strength_label)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
        val nextModel = candidates.firstOrNull { candidate -> stack.none { it.path == candidate.model.path } }
        OutlinedButton(
            onClick = {
                nextModel?.let { onStackChange(stack + SdLoraSpec(path = it.model.path)) }
            },
            enabled = nextModel != null,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.Add, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(
                stringResource(R.string.imagegen_lora_add),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (onAddFile != null && stack.isEmpty()) {
            OutlinedButton(onClick = onAddFile, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.media_picker_add_lora), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
internal fun OptionalComponentPicker(
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    label: String,
    candidates: List<SdComponentCandidate>,
    selectedPath: String,
    onSelectedPath: (String) -> Unit,
    onAddFile: (() -> Unit)? = null
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(
                checked = enabled,
                onCheckedChange = { checked ->
                    onEnabledChange(checked)
                    if (!checked) onSelectedPath("")
                }
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (enabled) {
            ComponentCandidatePicker(
                label = label,
                candidates = candidates,
                selectedPath = selectedPath,
                onSelectedPath = onSelectedPath,
                required = true,
                includedInMain = false,
                onAddFile = onAddFile
            )
        }
    }
}

@Composable
internal fun SamplingPicker(selected: String, onSelected: (String) -> Unit) {
    SimpleStringPicker(
        label = stringResource(R.string.sd_dist_sampler),
        selected = selected,
        options = SamplingMethod.entries.map { it.cliName },
        offLabel = SamplingMethod.EULER_A.cliName,
        onSelected = onSelected
    )
}

@Composable
internal fun BackendDevicePicker(
    label: String,
    selected: String,
    onSelected: (String) -> Unit,
    includeRemoteChoices: Boolean
) {
    val options = buildList {
        add("")
        add("cpu")
        if (includeRemoteChoices) {
            add("RPC0")
            add("RPC1")
            add("RPC2")
        }
    }
    SimpleStringPicker(
        label = label,
        selected = selected,
        options = options,
        offLabel = stringResource(R.string.sd_dist_picker_auto),
        onSelected = onSelected
    )
}

@Composable
internal fun CacheModePicker(selected: String, onSelected: (String) -> Unit) {
    SimpleStringPicker(
        label = stringResource(R.string.sd_dist_cache_mode),
        selected = selected,
        options = listOf("") + SdCacheMode.entries.map { it.cliName },
        offLabel = stringResource(R.string.sd_dist_cache_mode_off),
        onSelected = onSelected
    )
}

@Composable
internal fun CacheOptionPicker(cacheMode: String, value: String, onValueChange: (String) -> Unit) {
    val presets = cacheOptionPresets(cacheMode)
    SimpleStringPicker(
        label = stringResource(R.string.sd_dist_cache_option_preset),
        selected = value,
        options = listOf("") + presets,
        offLabel = stringResource(R.string.sd_dist_picker_custom),
        onSelected = onValueChange
    )
    LabeledTextField(R.string.sd_dist_cache_option, value, onValueChange, R.string.sd_dist_cache_option_hint)
}

@Composable
internal fun ScmMaskPicker(value: String, onValueChange: (String) -> Unit) {
    val presets = listOf("1,1,1,0,0,1,0,0,1,0", "1,0,1,0,1,0,1,0,1,0", "1")
    SimpleStringPicker(
        label = stringResource(R.string.sd_dist_scm_mask_preset),
        selected = value,
        options = listOf("") + presets,
        offLabel = stringResource(R.string.sd_dist_picker_custom),
        onSelected = onValueChange
    )
    LabeledTextField(R.string.sd_dist_scm_mask, value, onValueChange, R.string.sd_dist_scm_mask_hint)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SimpleStringPicker(
    label: String,
    selected: String,
    options: List<String>,
    offLabel: String,
    onSelected: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val displayValue = selected.ifBlank { offLabel }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = !expanded }) {
        OutlinedTextField(
            value = displayValue,
            onValueChange = {},
            readOnly = true,
            modifier = Modifier.fillMaxWidth().menuAnchor(),
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            singleLine = true
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.distinct().forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.ifBlank { offLabel }, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    onClick = {
                        onSelected(option)
                        expanded = false
                    }
                )
            }
        }
    }
}

internal fun cacheOptionPresets(cacheMode: String): List<String> = when (SdCacheMode.fromStoredValue(cacheMode)) {
    SdCacheMode.UCACHE -> listOf("threshold=1.5,reset=0")
    SdCacheMode.EASYCACHE -> listOf("threshold=0.3,start=0.15,end=0.95")
    SdCacheMode.DBCACHE,
    SdCacheMode.TAYLORSEER,
    SdCacheMode.CACHE_DIT -> listOf("threshold=0.25,warmup=4,Fn=8,Bn=0")
    SdCacheMode.SPECTRUM -> listOf("w=0.4,m=3,lam=1.0,window=2,flex=0.5,warmup=4,stop=0.9")
    null -> emptyList()
}

internal fun sdQuantizationOptions(): List<String> =
    listOf("", "f32", "f16", "q8_0", "q5_1", "q5_0", "q4_1", "q4_0")
