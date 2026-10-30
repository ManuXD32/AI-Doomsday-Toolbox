package com.example.llamadroid.ui.ai.llama.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.llamadroid.R
import com.example.llamadroid.data.db.ModelEntity
import com.example.llamadroid.data.db.ModelType
import com.example.llamadroid.onnx.OnnxBackendOverride
import com.example.llamadroid.onnx.OnnxExecutionMode
import com.example.llamadroid.onnx.OnnxGraphOptimizationLevel
import com.example.llamadroid.onnx.OnnxRuntimeBackend
import com.example.llamadroid.sd.SdComponentRole
import com.example.llamadroid.sd.matchesSdFamily
import com.example.llamadroid.sd.resolvedSdFamily
import com.example.llamadroid.service.SamplingMethod
import com.example.llamadroid.ui.components.DraftFloatTextField
import com.example.llamadroid.ui.components.DraftIntTextField
import com.example.llamadroid.ui.components.DraftNullableIntTextField

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LlamaNativeBackgroundRemovalToolSettings(
    model: String,
    availableModels: List<String>,
    onModelChange: (String) -> Unit,
    backend: OnnxRuntimeBackend,
    onBackendChange: (OnnxRuntimeBackend) -> Unit,
    runtimeThreads: Int?,
    onRuntimeThreadsChange: (Int?) -> Unit,
    graphOptimizationLevel: OnnxGraphOptimizationLevel,
    onGraphOptimizationLevelChange: (OnnxGraphOptimizationLevel) -> Unit,
    alphaThreshold: Float,
    onAlphaThresholdChange: (Float) -> Unit,
    featherRadius: Int,
    onFeatherRadiusChange: (Int) -> Unit,
    maskSoftness: Float,
    onMaskSoftnessChange: (Float) -> Unit,
    maskContrast: Float,
    onMaskContrastChange: (Float) -> Unit,
    exportMask: Boolean,
    onExportMaskChange: (Boolean) -> Unit,
    resizeBeforeProcessing: Boolean,
    onResizeBeforeProcessingChange: (Boolean) -> Unit,
    resizeMaxEdge: Int,
    onResizeMaxEdgeChange: (Int) -> Unit
) {
    var modelExpanded by remember { mutableStateOf(false) }
    var settingsExpanded by remember { mutableStateOf(false) }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.65f)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { settingsExpanded = !settingsExpanded },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.native_chat_bgr_settings_title),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        stringResource(R.string.native_chat_bgr_collapsed_summary, resizeMaxEdge, alphaThreshold, featherRadius),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Icon(
                    imageVector = if (settingsExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = stringResource(R.string.native_chat_bgr_settings_title),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            AnimatedVisibility(visible = settingsExpanded) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        stringResource(R.string.native_chat_bgr_settings_desc),
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

                    LlamaImageToolEnumDropdown(
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
                        LlamaImageToolOptionalNumberField(
                            value = runtimeThreads,
                            onValueChange = onRuntimeThreadsChange,
                            label = stringResource(R.string.onnx_image_gen_runtime_threads_label),
                            modifier = Modifier.weight(1f)
                        )
                        LlamaImageToolEnumDropdown(
                            label = stringResource(R.string.onnx_image_gen_graph_opt_title),
                            selected = graphOptimizationLevel,
                            values = OnnxGraphOptimizationLevel.entries,
                            labelFor = { it.name },
                            onSelected = onGraphOptimizationLevelChange,
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        LlamaImageToolFloatField(
                            value = alphaThreshold,
                            onValueChange = onAlphaThresholdChange,
                            label = stringResource(R.string.agent_bgr_alpha_threshold_label),
                            modifier = Modifier.weight(1f)
                        )
                        LlamaImageToolNumberField(
                            value = featherRadius,
                            onValueChange = onFeatherRadiusChange,
                            label = stringResource(R.string.agent_bgr_feather_label),
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        LlamaImageToolFloatField(
                            value = maskSoftness,
                            onValueChange = onMaskSoftnessChange,
                            label = stringResource(R.string.agent_bgr_mask_softness_label),
                            modifier = Modifier.weight(1f)
                        )
                        LlamaImageToolFloatField(
                            value = maskContrast,
                            onValueChange = onMaskContrastChange,
                            label = stringResource(R.string.agent_bgr_mask_contrast_label),
                            modifier = Modifier.weight(1f)
                        )
                    }

                    LlamaImageToolSwitchRow(
                        title = stringResource(R.string.agent_bgr_resize_label),
                        checked = resizeBeforeProcessing,
                        onCheckedChange = onResizeBeforeProcessingChange
                    )
                    if (resizeBeforeProcessing) {
                        LlamaImageToolNumberField(
                            value = resizeMaxEdge,
                            onValueChange = onResizeMaxEdgeChange,
                            label = stringResource(R.string.agent_bgr_resize_max_edge_label)
                        )
                    }
                    LlamaImageToolSwitchRow(
                        title = stringResource(R.string.agent_bgr_export_mask_label),
                        checked = exportMask,
                        onCheckedChange = onExportMaskChange
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LlamaNativeImageToolSettings(
    model: String,
    availableModels: List<String>,
    onModelChange: (String) -> Unit,
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
    var settingsExpanded by remember { mutableStateOf(false) }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.65f)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { settingsExpanded = !settingsExpanded },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.native_chat_image_generation_settings_title),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        stringResource(
                            R.string.native_chat_image_generation_collapsed_summary,
                            width,
                            height,
                            steps,
                            cfg
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Icon(
                    imageVector = if (settingsExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = stringResource(R.string.native_chat_image_generation_settings_title),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            AnimatedVisibility(visible = settingsExpanded) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
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
                LlamaImageToolNumberField(
                    value = width,
                    onValueChange = onWidthChange,
                    label = stringResource(R.string.onnx_image_gen_width_label),
                    modifier = Modifier.weight(1f)
                )
                LlamaImageToolNumberField(
                    value = height,
                    onValueChange = onHeightChange,
                    label = stringResource(R.string.onnx_image_gen_height_label),
                    modifier = Modifier.weight(1f)
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LlamaImageToolNumberField(
                    value = steps,
                    onValueChange = onStepsChange,
                    label = stringResource(R.string.onnx_image_gen_steps_label),
                    modifier = Modifier.weight(1f)
                )
                LlamaImageToolFloatField(
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

            LlamaImageToolEnumDropdown(
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
                LlamaImageToolOptionalNumberField(
                    value = runtimeThreads,
                    onValueChange = onRuntimeThreadsChange,
                    label = stringResource(R.string.onnx_image_gen_runtime_threads_label),
                    modifier = Modifier.weight(1f)
                )
                LlamaImageToolEnumDropdown(
                    label = stringResource(R.string.onnx_image_gen_graph_opt_title),
                    selected = graphOptimizationLevel,
                    values = OnnxGraphOptimizationLevel.entries,
                    labelFor = { it.name },
                    onSelected = onGraphOptimizationLevelChange,
                    modifier = Modifier.weight(1f)
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LlamaImageToolOptionalNumberField(
                    value = intraOpThreads,
                    onValueChange = onIntraOpThreadsChange,
                    label = stringResource(R.string.onnx_image_gen_intra_threads_label),
                    modifier = Modifier.weight(1f)
                )
                LlamaImageToolOptionalNumberField(
                    value = interOpThreads,
                    onValueChange = onInterOpThreadsChange,
                    label = stringResource(R.string.onnx_image_gen_inter_threads_label),
                    modifier = Modifier.weight(1f)
                )
            }

            LlamaImageToolEnumDropdown(
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
            LlamaImageToolEnumDropdown(
                label = stringResource(R.string.onnx_image_gen_component_backend_unet),
                selected = unetBackendOverride,
                values = OnnxBackendOverride.entries,
                labelFor = { it.name },
                onSelected = onUnetBackendOverrideChange
            )
            LlamaImageToolEnumDropdown(
                label = stringResource(R.string.onnx_image_gen_component_backend_vae_decoder),
                selected = vaeDecoderBackendOverride,
                values = OnnxBackendOverride.entries,
                labelFor = { it.name },
                onSelected = onVaeDecoderBackendOverrideChange
            )
            LlamaImageToolEnumDropdown(
                label = stringResource(R.string.onnx_image_gen_component_backend_vae_encoder),
                selected = vaeEncoderBackendOverride,
                values = OnnxBackendOverride.entries,
                labelFor = { it.name },
                onSelected = onVaeEncoderBackendOverrideChange
            )

            LlamaImageToolSwitchRow(
                title = stringResource(R.string.onnx_image_gen_memory_pattern_label),
                checked = memoryPatternOptimization,
                onCheckedChange = onMemoryPatternOptimizationChange
            )
            LlamaImageToolSwitchRow(
                title = stringResource(R.string.onnx_image_gen_cpu_arena_label),
                checked = cpuArenaAllocator,
                onCheckedChange = onCpuArenaAllocatorChange
            )
            LlamaImageToolSwitchRow(
                title = stringResource(R.string.onnx_image_gen_nnapi_cpu_disabled_label),
                checked = nnapiCpuDisabled,
                onCheckedChange = onNnapiCpuDisabledChange
            )
            LlamaImageToolSwitchRow(
                title = stringResource(R.string.onnx_image_gen_nnapi_fp16_label),
                checked = nnapiUseFp16,
                onCheckedChange = onNnapiUseFp16Change
            )
                }
            }
        }
    }
}

@Composable
internal fun LlamaNativeSdImageToolSettings(
    model: String,
    availableModels: List<String>,
    onModelChange: (String) -> Unit,
    componentRoles: List<SdComponentRole>,
    requiredRoles: Set<SdComponentRole>,
    componentOptions: (SdComponentRole) -> List<String>,
    selectedComponent: (SdComponentRole) -> String,
    onComponentChange: (SdComponentRole, String) -> Unit,
    width: Int,
    onWidthChange: (Int) -> Unit,
    height: Int,
    onHeightChange: (Int) -> Unit,
    steps: Int,
    onStepsChange: (Int) -> Unit,
    cfg: Float,
    onCfgChange: (Float) -> Unit,
    sampler: SamplingMethod,
    onSamplerChange: (SamplingMethod) -> Unit,
    seed: String,
    onSeedChange: (String) -> Unit,
    negativePrompt: String,
    onNegativePromptChange: (String) -> Unit,
    threads: Int,
    onThreadsChange: (Int) -> Unit,
    flowShift: String,
    onFlowShiftChange: (String) -> Unit,
    showFlowShift: Boolean,
    diffusionFa: Boolean,
    onDiffusionFaChange: (Boolean) -> Unit,
    showDiffusionFa: Boolean,
    mmap: Boolean,
    onMmapChange: (Boolean) -> Unit,
    showMmap: Boolean,
    vaeConvDirect: Boolean,
    onVaeConvDirectChange: (Boolean) -> Unit,
    showVaeConvDirect: Boolean,
    qwenZeroCondT: Boolean,
    onQwenZeroCondTChange: (Boolean) -> Unit,
    showQwenZeroCondT: Boolean,
    chromaDisableDitMask: Boolean,
    onChromaDisableDitMaskChange: (Boolean) -> Unit,
    showChromaDisableDitMask: Boolean
) {
    var settingsExpanded by remember { mutableStateOf(false) }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.65f)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { settingsExpanded = !settingsExpanded },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.native_chat_sd_image_generation_settings_title),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        stringResource(
                            R.string.native_chat_image_generation_collapsed_summary,
                            width,
                            height,
                            steps,
                            cfg
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Icon(
                    imageVector = if (settingsExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = stringResource(R.string.native_chat_sd_image_generation_settings_title),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            AnimatedVisibility(visible = settingsExpanded) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    LlamaStringDropdown(
                        label = stringResource(R.string.agent_sd_image_generation_model_label),
                        selected = model,
                        values = availableModels,
                        onSelected = onModelChange,
                        enabled = availableModels.isNotEmpty()
                    )

                    componentRoles.forEach { role ->
                        LlamaSdComponentDropdown(
                            label = stringResource(llamaSdComponentLabelRes(role)) +
                                if (role in requiredRoles) " *" else "",
                            selected = selectedComponent(role),
                            values = componentOptions(role),
                            allowNone = role !in requiredRoles,
                            onSelected = { onComponentChange(role, it) }
                        )
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        LlamaImageToolNumberField(
                            value = width,
                            onValueChange = onWidthChange,
                            label = stringResource(R.string.onnx_image_gen_width_label),
                            modifier = Modifier.weight(1f)
                        )
                        LlamaImageToolNumberField(
                            value = height,
                            onValueChange = onHeightChange,
                            label = stringResource(R.string.onnx_image_gen_height_label),
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        LlamaImageToolNumberField(
                            value = steps,
                            onValueChange = onStepsChange,
                            label = stringResource(R.string.onnx_image_gen_steps_label),
                            modifier = Modifier.weight(1f)
                        )
                        LlamaImageToolFloatField(
                            value = cfg,
                            onValueChange = onCfgChange,
                            label = stringResource(R.string.onnx_image_gen_cfg_label),
                            modifier = Modifier.weight(1f)
                        )
                    }

                    LlamaImageToolEnumDropdown(
                        label = stringResource(R.string.imagegen_sampler_label),
                        selected = sampler,
                        values = SamplingMethod.entries,
                        labelFor = { it.cliName },
                        onSelected = onSamplerChange
                    )

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

                    LlamaImageToolNumberField(
                        value = threads,
                        onValueChange = onThreadsChange,
                        label = stringResource(R.string.imagegen_threads_label)
                    )

                    if (showFlowShift) {
                        OutlinedTextField(
                            value = flowShift,
                            onValueChange = onFlowShiftChange,
                            label = { Text(stringResource(R.string.imagegen_flow_shift_label)) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                                keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal
                            )
                        )
                    }
                    if (showDiffusionFa) {
                        LlamaImageToolSwitchRow(
                            title = stringResource(R.string.imagegen_diffusion_fa_label),
                            checked = diffusionFa,
                            onCheckedChange = onDiffusionFaChange
                        )
                    }
                    if (showMmap) {
                        LlamaImageToolSwitchRow(
                            title = stringResource(R.string.imagegen_mmap_label),
                            checked = mmap,
                            onCheckedChange = onMmapChange
                        )
                    }
                    if (showVaeConvDirect) {
                        LlamaImageToolSwitchRow(
                            title = stringResource(R.string.imagegen_vae_conv_direct_label),
                            checked = vaeConvDirect,
                            onCheckedChange = onVaeConvDirectChange
                        )
                    }
                    if (showQwenZeroCondT) {
                        LlamaImageToolSwitchRow(
                            title = stringResource(R.string.imagegen_qwen_zero_cond_t_label),
                            checked = qwenZeroCondT,
                            onCheckedChange = onQwenZeroCondTChange
                        )
                    }
                    if (showChromaDisableDitMask) {
                        LlamaImageToolSwitchRow(
                            title = stringResource(R.string.imagegen_chroma_disable_dit_mask_label),
                            checked = chromaDisableDitMask,
                            onCheckedChange = onChromaDisableDitMaskChange
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun LlamaImageToolNumberField(
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
internal fun LlamaImageToolFloatField(
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
internal fun LlamaImageToolOptionalNumberField(
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
internal fun LlamaStringDropdown(
    label: String,
    selected: String,
    values: List<String>,
    onSelected: (String) -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { if (enabled && values.isNotEmpty()) expanded = it },
        modifier = modifier
    ) {
        OutlinedTextField(
            value = selected,
            onValueChange = {},
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(),
            readOnly = true,
            enabled = enabled,
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
                    text = { Text(option) },
                    onClick = {
                        onSelected(option)
                        expanded = false
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LlamaSdComponentDropdown(
    label: String,
    selected: String,
    values: List<String>,
    allowNone: Boolean,
    onSelected: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val options = remember(values, allowNone) {
        (if (allowNone) listOf("") else emptyList()) + values
    }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { if (options.isNotEmpty()) expanded = it }
    ) {
        OutlinedTextField(
            value = selected.ifBlank { stringResource(R.string.image_tool_component_none) },
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
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.ifBlank { stringResource(R.string.image_tool_component_none) }) },
                    onClick = {
                        onSelected(option)
                        expanded = false
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun <T : Enum<T>> LlamaImageToolEnumDropdown(
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
internal fun LlamaImageToolSwitchRow(
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
            modifier = Modifier.weight(1f),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

internal fun llamaSdComponentOptions(
    models: List<ModelEntity>,
    selectedModel: ModelEntity?,
    role: SdComponentRole
): List<String> {
    val (family, variant) = selectedModel?.resolvedSdFamily() ?: return emptyList()
    val resolvedFamily = family ?: return emptyList()
    val modelType = role.toModelType() ?: return emptyList()
    return models
        .filter { model ->
            model.type == modelType && model.matchesSdFamily(resolvedFamily, variant)
        }
        .map { it.filename }
        .distinct()
}

internal fun SdComponentRole.toModelType(): ModelType? = when (this) {
    SdComponentRole.VAE -> ModelType.SD_VAE
    SdComponentRole.TAE -> ModelType.SD_TAE
    SdComponentRole.CLIP_L -> ModelType.SD_CLIP_L
    SdComponentRole.CLIP_G -> ModelType.SD_CLIP_G
    SdComponentRole.T5XXL -> ModelType.SD_T5XXL
    SdComponentRole.LLM -> ModelType.LLM
    SdComponentRole.LLM_VISION -> ModelType.VISION_PROJECTOR
    SdComponentRole.PHOTOMAKER -> ModelType.SD_PHOTOMAKER
    else -> null
}

internal fun llamaSdComponentLabelRes(role: SdComponentRole): Int = when (role) {
    SdComponentRole.VAE -> R.string.imagegen_component_vae
    SdComponentRole.TAE -> R.string.imagegen_component_tae
    SdComponentRole.CLIP_L -> R.string.imagegen_component_clip_l
    SdComponentRole.CLIP_G -> R.string.imagegen_component_clip_g
    SdComponentRole.T5XXL -> R.string.imagegen_component_t5xxl
    SdComponentRole.LLM -> R.string.imagegen_component_llm
    SdComponentRole.LLM_VISION -> R.string.imagegen_component_llm_vision
    SdComponentRole.PHOTOMAKER -> R.string.imagegen_component_photomaker
    else -> R.string.imagegen_component_main_model
}
