package com.example.llamadroid.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavController
import com.example.llamadroid.R
import com.example.llamadroid.data.SettingsRepository
import com.example.llamadroid.onnx.OnnxBackendOverride
import com.example.llamadroid.onnx.OnnxExecutionMode
import com.example.llamadroid.onnx.OnnxGraphOptimizationLevel
import com.example.llamadroid.onnx.OnnxRuntimeBackend
import com.example.llamadroid.ui.components.AppScreenScaffold
import com.example.llamadroid.ui.components.DraftFloatTextField
import com.example.llamadroid.ui.components.DraftIntTextField
import com.example.llamadroid.ui.components.DraftNullableIntTextField

/**
 * Image Generation Settings - Thread controls and output folder
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImageGenSettingsScreen(navController: NavController) {
    val context = LocalContext.current
    val settingsRepo = remember { SettingsRepository(context) }

    AppScreenScaffold(
        title = stringResource(R.string.imagegen_settings_title),
        subtitle = stringResource(R.string.settings_imagegen_desc),
        onBack = { navController.popBackStack() }
    ) { _ ->
        ImageGenSettingsContent(
            settingsRepo = settingsRepo,
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp, vertical = 12.dp)
        )
    }
}

@Composable
fun ImageGenSettingsContent(
    settingsRepo: SettingsRepository,
    modifier: Modifier = Modifier
) {
    val sdTxt2imgThreads by settingsRepo.sdTxt2imgThreads.collectAsState()
    val sdImg2imgThreads by settingsRepo.sdImg2imgThreads.collectAsState()
    val sdUpscaleThreads by settingsRepo.sdUpscaleThreads.collectAsState()

    val sdVaeTiling by settingsRepo.sdVaeTiling.collectAsState()
    val sdVaeTileOverlap by settingsRepo.sdVaeTileOverlap.collectAsState()
    val sdVaeTileSize by settingsRepo.sdVaeTileSize.collectAsState()
    val sdVaeRelativeTileSize by settingsRepo.sdVaeRelativeTileSize.collectAsState()
    val sdTensorTypeRules by settingsRepo.sdTensorTypeRules.collectAsState()

    LazyColumn(
            modifier = modifier,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // txt2img Threads
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(stringResource(R.string.imagegen_txt2img_threads), fontWeight = FontWeight.Bold)
                            Text("$sdTxt2imgThreads", color = MaterialTheme.colorScheme.primary)
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Slider(
                            value = sdTxt2imgThreads.toFloat(),
                            onValueChange = { settingsRepo.setSdTxt2imgThreads(it.toInt()) },
                            valueRange = 1f..8f,
                            steps = 6
                        )
                        Text(
                            stringResource(R.string.imagegen_txt2img_desc),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            
            // img2img Threads
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(stringResource(R.string.imagegen_img2img_threads), fontWeight = FontWeight.Bold)
                            Text("$sdImg2imgThreads", color = MaterialTheme.colorScheme.primary)
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Slider(
                            value = sdImg2imgThreads.toFloat(),
                            onValueChange = { settingsRepo.setSdImg2imgThreads(it.toInt()) },
                            valueRange = 1f..8f,
                            steps = 6
                        )
                        Text(
                            stringResource(R.string.imagegen_img2img_desc),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            
            // Upscale Threads
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(stringResource(R.string.imagegen_upscale_threads), fontWeight = FontWeight.Bold)
                            Text("$sdUpscaleThreads", color = MaterialTheme.colorScheme.primary)
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Slider(
                            value = sdUpscaleThreads.toFloat(),
                            onValueChange = { settingsRepo.setSdUpscaleThreads(it.toInt()) },
                            valueRange = 1f..8f,
                            steps = 6
                        )
                        Text(
                            stringResource(R.string.imagegen_upscale_desc),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Memory Optimization
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(stringResource(R.string.imagegen_memory_opt), fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        // VAE Tiling
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(stringResource(R.string.imagegen_vae_tiling))
                            Switch(
                                checked = sdVaeTiling,
                                onCheckedChange = { settingsRepo.setSdVaeTiling(it) }
                            )
                        }
                        
                        if (sdVaeTiling) {
                            Spacer(modifier = Modifier.height(16.dp))
                            
                            // Tile Overlap
                            Text(
                                stringResource(R.string.imagegen_tile_overlap) + ": ${"%.2f".format(sdVaeTileOverlap)}",
                                style = MaterialTheme.typography.labelMedium
                            )
                            Slider(
                                value = sdVaeTileOverlap,
                                onValueChange = { settingsRepo.setSdVaeTileOverlap(it) },
                                valueRange = 0f..1f,
                                steps = 10
                            )
                            
                            // Tile Size
                            OutlinedTextField(
                                value = sdVaeTileSize,
                                onValueChange = { settingsRepo.setSdVaeTileSize(it) },
                                label = { Text(stringResource(R.string.imagegen_tile_size)) },
                                modifier = Modifier.fillMaxWidth(),
                                placeholder = { Text("32x32") },
                                singleLine = true
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            
                            // Relative Tile Size
                            OutlinedTextField(
                                value = sdVaeRelativeTileSize,
                                onValueChange = { settingsRepo.setSdVaeRelativeTileSize(it) },
                                label = { Text(stringResource(R.string.imagegen_relative_tile_size)) },
                                modifier = Modifier.fillMaxWidth(),
                                placeholder = { Text("e.g. 0.5") },
                                singleLine = true
                            )
                        }
                        
                        Spacer(modifier = Modifier.height(16.dp))
                        
                        // Tensor Type Rules
                        OutlinedTextField(
                            value = sdTensorTypeRules,
                            onValueChange = { settingsRepo.setSdTensorTypeRules(it) },
                            label = { Text(stringResource(R.string.imagegen_tensor_type_rules)) },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text("e.g. *:q8_0,attn*:f16") },
                            supportingText = {
                                Column {
                                    Text(stringResource(R.string.imagegen_vae_gguf_note))
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(stringResource(R.string.imagegen_presets), style = MaterialTheme.typography.labelSmall)
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        listOf("f16", "q8_0", "q4_0").forEach { quant ->
                                            AssistChip(
                                                onClick = { settingsRepo.setSdTensorTypeRules("*: $quant") },
                                                label = { Text(quant) }
                                            )
                                        }
                                        AssistChip(
                                            onClick = { settingsRepo.setSdTensorTypeRules("") },
                                            label = { Text(stringResource(R.string.imagegen_default)) }
                                        )
                                    }
                                }
                            }
                        )
                    }
                }
            }
        }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NativeChatOnnxToolSettingsCard(
    model: String,
    availableModels: List<String>,
    onModelChange: (String?) -> Unit,
    width: Int,
    onWidthChange: (Int) -> Unit,
    height: Int,
    onHeightChange: (Int) -> Unit,
    steps: Int,
    onStepsChange: (Int) -> Unit,
    cfg: Float,
    onCfgChange: (Float) -> Unit,
    seed: String,
    onSeedChange: (String) -> Unit,
    negativePrompt: String,
    onNegativePromptChange: (String) -> Unit,
    backend: OnnxRuntimeBackend,
    onBackendChange: (OnnxRuntimeBackend) -> Unit,
    runtimeThreads: Int?,
    onRuntimeThreadsChange: (Int?) -> Unit,
    graphOptimizationLevel: OnnxGraphOptimizationLevel,
    onGraphOptimizationLevelChange: (OnnxGraphOptimizationLevel) -> Unit,
    unetBackendOverride: OnnxBackendOverride,
    onUnetBackendOverrideChange: (OnnxBackendOverride) -> Unit,
    vaeDecoderBackendOverride: OnnxBackendOverride,
    onVaeDecoderBackendOverrideChange: (OnnxBackendOverride) -> Unit,
    vaeEncoderBackendOverride: OnnxBackendOverride,
    onVaeEncoderBackendOverrideChange: (OnnxBackendOverride) -> Unit,
    intraOpThreads: Int?,
    onIntraOpThreadsChange: (Int?) -> Unit,
    interOpThreads: Int?,
    onInterOpThreadsChange: (Int?) -> Unit,
    executionMode: OnnxExecutionMode,
    onExecutionModeChange: (OnnxExecutionMode) -> Unit,
    memoryPatternOptimization: Boolean,
    onMemoryPatternOptimizationChange: (Boolean) -> Unit,
    cpuArenaAllocator: Boolean,
    onCpuArenaAllocatorChange: (Boolean) -> Unit,
    nnapiCpuDisabled: Boolean,
    onNnapiCpuDisabledChange: (Boolean) -> Unit,
    nnapiUseFp16: Boolean,
    onNnapiUseFp16Change: (Boolean) -> Unit
) {
    var modelExpanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                stringResource(R.string.native_chat_image_generation_settings_title),
                fontWeight = FontWeight.Bold
            )
            Text(
                stringResource(R.string.native_chat_image_generation_settings_desc),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            ExposedDropdownMenuBox(
                expanded = modelExpanded,
                onExpandedChange = { modelExpanded = it }
            ) {
                OutlinedTextField(
                    value = model,
                    onValueChange = onModelChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor(),
                    label = { Text(stringResource(R.string.agent_image_generation_model_label)) },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = modelExpanded) },
                    singleLine = true
                )
                ExposedDropdownMenu(
                    expanded = modelExpanded,
                    onDismissRequest = { modelExpanded = false }
                ) {
                    availableModels.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(option) },
                            onClick = {
                                onModelChange(option)
                                modelExpanded = false
                            }
                        )
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NativeChatOnnxNumberField(
                    value = width,
                    onValueChange = onWidthChange,
                    label = stringResource(R.string.onnx_image_gen_width_label),
                    modifier = Modifier.weight(1f)
                )
                NativeChatOnnxNumberField(
                    value = height,
                    onValueChange = onHeightChange,
                    label = stringResource(R.string.onnx_image_gen_height_label),
                    modifier = Modifier.weight(1f)
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NativeChatOnnxNumberField(
                    value = steps,
                    onValueChange = onStepsChange,
                    label = stringResource(R.string.onnx_image_gen_steps_label),
                    modifier = Modifier.weight(1f)
                )
                NativeChatOnnxFloatField(
                    value = cfg,
                    onValueChange = onCfgChange,
                    label = stringResource(R.string.onnx_image_gen_cfg_label),
                    modifier = Modifier.weight(1f)
                )
            }

            OutlinedTextField(
                value = seed,
                onValueChange = onSeedChange,
                label = { Text(stringResource(R.string.onnx_image_gen_seed_label)) },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text(stringResource(R.string.onnx_image_gen_seed_placeholder)) },
                singleLine = true,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    keyboardType = androidx.compose.ui.text.input.KeyboardType.Number
                )
            )

            OutlinedTextField(
                value = negativePrompt,
                onValueChange = onNegativePromptChange,
                label = { Text(stringResource(R.string.native_chat_image_generation_negative_prompt_label)) },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
                maxLines = 4
            )

            Text(
                stringResource(R.string.native_chat_image_generation_runtime_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )

            NativeChatOnnxEnumDropdown(
                label = stringResource(R.string.onnx_image_gen_backend_label),
                selected = backend,
                values = OnnxRuntimeBackend.entries,
                labelFor = {
                    when (it) {
                        OnnxRuntimeBackend.CPU -> stringResource(R.string.onnx_image_gen_backend_cpu)
                        OnnxRuntimeBackend.NNAPI -> stringResource(R.string.onnx_image_gen_backend_nnapi)
                    }
                },
                onSelected = onBackendChange
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NativeChatOnnxOptionalNumberField(
                    value = runtimeThreads,
                    onValueChange = onRuntimeThreadsChange,
                    label = stringResource(R.string.onnx_image_gen_runtime_threads_label),
                    modifier = Modifier.weight(1f)
                )
                NativeChatOnnxEnumDropdown(
                    label = stringResource(R.string.onnx_image_gen_graph_opt_title),
                    selected = graphOptimizationLevel,
                    values = OnnxGraphOptimizationLevel.entries,
                    labelFor = { it.name },
                    onSelected = onGraphOptimizationLevelChange,
                    modifier = Modifier.weight(1f)
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NativeChatOnnxOptionalNumberField(
                    value = intraOpThreads,
                    onValueChange = onIntraOpThreadsChange,
                    label = stringResource(R.string.onnx_image_gen_intra_threads_label),
                    modifier = Modifier.weight(1f)
                )
                NativeChatOnnxOptionalNumberField(
                    value = interOpThreads,
                    onValueChange = onInterOpThreadsChange,
                    label = stringResource(R.string.onnx_image_gen_inter_threads_label),
                    modifier = Modifier.weight(1f)
                )
            }

            NativeChatOnnxEnumDropdown(
                label = stringResource(R.string.onnx_image_gen_execution_mode_title),
                selected = executionMode,
                values = OnnxExecutionMode.entries,
                labelFor = { it.name },
                onSelected = onExecutionModeChange
            )

            Text(
                stringResource(R.string.native_chat_image_generation_component_backends_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )

            NativeChatOnnxEnumDropdown(
                label = stringResource(R.string.onnx_image_gen_component_backend_unet),
                selected = unetBackendOverride,
                values = OnnxBackendOverride.entries,
                labelFor = { it.name },
                onSelected = onUnetBackendOverrideChange
            )
            NativeChatOnnxEnumDropdown(
                label = stringResource(R.string.onnx_image_gen_component_backend_vae_decoder),
                selected = vaeDecoderBackendOverride,
                values = OnnxBackendOverride.entries,
                labelFor = { it.name },
                onSelected = onVaeDecoderBackendOverrideChange
            )
            NativeChatOnnxEnumDropdown(
                label = stringResource(R.string.onnx_image_gen_component_backend_vae_encoder),
                selected = vaeEncoderBackendOverride,
                values = OnnxBackendOverride.entries,
                labelFor = { it.name },
                onSelected = onVaeEncoderBackendOverrideChange
            )

            NativeChatOnnxSwitchRow(
                title = stringResource(R.string.onnx_image_gen_memory_pattern_label),
                checked = memoryPatternOptimization,
                onCheckedChange = onMemoryPatternOptimizationChange
            )
            NativeChatOnnxSwitchRow(
                title = stringResource(R.string.onnx_image_gen_cpu_arena_label),
                checked = cpuArenaAllocator,
                onCheckedChange = onCpuArenaAllocatorChange
            )
            NativeChatOnnxSwitchRow(
                title = stringResource(R.string.onnx_image_gen_nnapi_cpu_disabled_label),
                checked = nnapiCpuDisabled,
                onCheckedChange = onNnapiCpuDisabledChange
            )
            NativeChatOnnxSwitchRow(
                title = stringResource(R.string.onnx_image_gen_nnapi_fp16_label),
                checked = nnapiUseFp16,
                onCheckedChange = onNnapiUseFp16Change
            )
        }
    }
}

@Composable
private fun NativeChatOnnxNumberField(
    value: Int,
    onValueChange: (Int) -> Unit,
    label: String,
    modifier: Modifier = Modifier
) {
    DraftIntTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        modifier = modifier
    )
}

@Composable
private fun NativeChatOnnxFloatField(
    value: Float,
    onValueChange: (Float) -> Unit,
    label: String,
    modifier: Modifier = Modifier
) {
    DraftFloatTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        modifier = modifier
    )
}

@Composable
private fun NativeChatOnnxOptionalNumberField(
    value: Int?,
    onValueChange: (Int?) -> Unit,
    label: String,
    modifier: Modifier = Modifier
) {
    DraftNullableIntTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        modifier = modifier
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T : Enum<T>> NativeChatOnnxEnumDropdown(
    label: String,
    selected: T,
    values: List<T>,
    labelFor: @Composable (T) -> String,
    onSelected: (T) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = modifier
    ) {
        OutlinedTextField(
            value = labelFor(selected),
            onValueChange = {},
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(),
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            singleLine = true
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            values.forEach { option ->
                DropdownMenuItem(
                    text = { Text(labelFor(option)) },
                    onClick = {
                        onSelected(option)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
private fun NativeChatOnnxSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            title,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f)
        )
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}
