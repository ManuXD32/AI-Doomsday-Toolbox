@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.example.llamadroid.ui.distributed.sd

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.llamadroid.R
import com.example.llamadroid.data.db.ModelEntity
import com.example.llamadroid.data.db.SdDistributedTemplateEntity
import com.example.llamadroid.sd.SdComponentRole
import com.example.llamadroid.sd.SdLoraApplyMode
import com.example.llamadroid.sd.SdLoraSpec
import com.example.llamadroid.sd.SdModelFamilySpec
import com.example.llamadroid.sd.SdVideoComponentRole
import com.example.llamadroid.service.SDGenerationState
import com.example.llamadroid.service.SdBinaryCapabilities
import com.example.llamadroid.service.SdDistributedModules
import com.example.llamadroid.service.SdDistributedPlacementPlan
import com.example.llamadroid.service.SdDistributedRuntimeConfig
import com.example.llamadroid.service.SdScheduler
import com.example.llamadroid.service.VideoGenerationState
import com.example.llamadroid.service.VideoRuntimeOptions
import com.example.llamadroid.ui.components.AppSectionCard
import com.example.llamadroid.ui.components.SdSchedulerPicker
import com.example.llamadroid.ui.components.VideoRuntimeOptionsEditor
import com.example.llamadroid.ui.walkthrough.walkthroughTarget

@Composable
internal fun RenderFarmHeader(
    runtimeConfig: SdDistributedRuntimeConfig,
    workers: List<com.example.llamadroid.service.SdDistributedPlanningWorker>,
    plan: SdDistributedPlacementPlan
) {
    AppSectionCard(containerColor = RenderFarmPalette.graphite, tonalAccent = RenderFarmPalette.lime.copy(alpha = 0.18f)) {
        Text(
            text = stringResource(R.string.sd_dist_render_farm_console),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = Color.White
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FarmStatusChip(stringResource(R.string.sd_dist_enabled), if (runtimeConfig.enabled) stringResource(R.string.sd_dist_live) else stringResource(R.string.sd_dist_disabled), RenderFarmPalette.lime)
            FarmStatusChip(stringResource(R.string.sd_dist_workers), workers.size.toString(), RenderFarmPalette.violet)
            FarmStatusChip(stringResource(R.string.sd_dist_ram_to_share), "${workers.sumOf { it.ramMB }} ${stringResource(R.string.sd_dist_mb_suffix)}", RenderFarmPalette.orange)
            FarmStatusChip(stringResource(R.string.sd_dist_backend_spec), plan.backendSpec.ifBlank { "-" }, RenderFarmPalette.coral)
        }
    }
}

@Composable
internal fun FarmStatusChip(label: String, value: String, color: Color) {
    ElevatedCard(
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = RenderFarmPalette.graphiteSoft)
    ) {
        Column(modifier = Modifier.widthIn(min = 150.dp, max = 260.dp).padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(value, style = MaterialTheme.typography.bodySmall, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
internal fun CollapsibleFarmCard(
    title: String,
    subtitle: String,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    accent: Color,
    content: @Composable () -> Unit
) {
    AppSectionCard(containerColor = MaterialTheme.colorScheme.surface, tonalAccent = accent) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = { onExpandedChange(!expanded) }) {
                Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, contentDescription = null)
            }
        }
        if (expanded) {
            HorizontalDivider()
            content()
        }
    }
}

internal enum class SdDistributedTemplateWorkflow { IMAGE, VIDEO }

internal data class SdDistributedImageComponentChoices(
    val familyLabel: String,
    val spec: SdModelFamilySpec?,
    val roles: List<SdComponentRole>,
    val compatibleModels: Map<SdComponentRole, List<ModelEntity>>,
    val selectedPaths: Map<SdComponentRole, String>,
    val controlNetEnabled: Boolean,
    val loraEnabled: Boolean,
    val loraApplyMode: String
)

@Composable
internal fun MasterContributionCard(
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    displayName: String,
    onDisplayNameChange: (String) -> Unit,
    ramDraft: String,
    onRamDraftChange: (String) -> Unit,
    threadsDraft: String,
    onThreadsDraftChange: (String) -> Unit,
    backendDevice: String,
    onBackendDeviceChange: (String) -> Unit,
    allowedModules: Set<String>,
    onAllowedModulesChange: (Set<String>) -> Unit,
    diffusionShareDraft: String,
    onDiffusionShareDraftChange: (String) -> Unit
) {
    ElevatedCard(
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = RenderFarmPalette.graphiteSoft)
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            ToggleRow(
                title = stringResource(R.string.sd_dist_master_contribute),
                subtitle = stringResource(R.string.sd_dist_master_contribute_desc),
                checked = enabled,
                onCheckedChange = onEnabledChange,
                // This card intentionally keeps the Render Farm's dark console
                // palette. The generic row defaults to the app's light-surface
                // colors, which made this otherwise actionable control nearly
                // disappear on the light theme.
                titleColor = Color.White,
                subtitleColor = Color.White.copy(alpha = 0.84f),
                checkboxCheckedColor = RenderFarmPalette.lime,
                checkboxUncheckedColor = Color.White.copy(alpha = 0.90f),
                checkboxCheckmarkColor = RenderFarmPalette.graphite
            )
            if (enabled) {
                OutlinedTextField(
                    value = displayName,
                    onValueChange = onDisplayNameChange,
                    label = { Text(stringResource(R.string.sd_dist_master_display_name)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    colors = distributedOutlinedTextFieldColors(onDark = true)
                )
                BackendDevicePicker(
                    label = stringResource(R.string.sd_dist_backend_device_optional),
                    selected = backendDevice,
                    onSelected = onBackendDeviceChange,
                    includeRemoteChoices = false,
                    onDark = true
                )
                NumberSliderField(
                    label = stringResource(R.string.sd_dist_ram_to_share),
                    valueDraft = ramDraft,
                    onValueDraftChange = onRamDraftChange,
                    range = 512f..65536f,
                    suffix = stringResource(R.string.sd_dist_mb_suffix),
                    onDark = true
                )
                NumberSliderField(
                    label = stringResource(R.string.sd_dist_threads_to_share),
                    valueDraft = threadsDraft,
                    onValueDraftChange = onThreadsDraftChange,
                    range = 1f..32f,
                    onDark = true
                )
                Text(stringResource(R.string.sd_dist_master_allowed_modules), style = MaterialTheme.typography.labelLarge, color = Color.White)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SdDistributedModules.defaultModuleSet.forEach { module ->
                        val selected = module in allowedModules
                        AssistChip(
                            onClick = {
                                val next = if (selected) allowedModules - module else allowedModules + module
                                onAllowedModulesChange(next.ifEmpty { setOf(SdDistributedModules.DIFFUSION) })
                            },
                            label = { Text(moduleLabel(module), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            colors = AssistChipDefaults.assistChipColors(
                                labelColor = Color.White,
                                leadingIconContentColor = RenderFarmPalette.lime,
                                disabledLabelColor = Color.White.copy(alpha = 0.72f),
                                disabledLeadingIconContentColor = Color.White.copy(alpha = 0.72f)
                            ),
                            leadingIcon = if (selected) {
                                { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                            } else {
                                null
                            }
                        )
                    }
                }
                LabeledTextField(
                    R.string.sd_dist_master_diffusion_share,
                    diffusionShareDraft,
                    { onDiffusionShareDraftChange(it.filter(Char::isDigit).take(2)) },
                    R.string.sd_dist_master_diffusion_share_hint,
                    KeyboardType.Number,
                    onDark = true
                )
                Text(
                    stringResource(R.string.sd_dist_master_diffusion_share_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.84f)
                )
            }
        }
    }
}

/** Error shown beside the Start button: a blocked launch wins over a service-side run failure; blank counts as none. */
internal fun sdDistributedVisibleError(launchError: String?, runError: String?): String? =
    launchError?.takeIf { it.isNotBlank() } ?: runError?.takeIf { it.isNotBlank() }

@Composable
internal fun ImageRunCard(
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    mode: String,
    onModeChange: (String) -> Unit,
    models: List<ModelEntity>,
    upscalerModels: List<ModelEntity>,
    modelPath: String,
    onModelPathChange: (String) -> Unit,
    upscalerModelPath: String,
    onUpscalerModelPathChange: (String) -> Unit,
    inputPath: String,
    onInputPathChange: (String) -> Unit,
    onPickInputImage: () -> Unit,
    prompt: String,
    onPromptChange: (String) -> Unit,
    negativePrompt: String,
    onNegativePromptChange: (String) -> Unit,
    width: String,
    onWidthChange: (String) -> Unit,
    height: String,
    onHeightChange: (String) -> Unit,
    steps: String,
    onStepsChange: (String) -> Unit,
    cfg: String,
    onCfgChange: (String) -> Unit,
    seed: String,
    onSeedChange: (String) -> Unit,
    sampler: String,
    onSamplerChange: (String) -> Unit,
    scheduler: String,
    onSchedulerChange: (String) -> Unit,
    flowShift: String,
    onFlowShiftChange: (String) -> Unit,
    strength: String,
    onStrengthChange: (String) -> Unit,
    components: SdDistributedImageComponentChoices,
    onComponentPathChange: (SdComponentRole, String) -> Unit,
    onControlNetEnabledChange: (Boolean) -> Unit,
    onLoraEnabledChange: (Boolean) -> Unit,
    loraStack: List<SdLoraSpec>,
    onLoraStackChange: (List<SdLoraSpec>) -> Unit,
    onLoraApplyModeChange: (String) -> Unit,
    customFlags: String,
    onCustomFlagsChange: (String) -> Unit,
    templates: List<SdDistributedTemplateEntity>,
    templateNameDraft: String,
    onTemplateNameChange: (String) -> Unit,
    onSaveTemplate: () -> Unit,
    onLoadTemplate: (SdDistributedTemplateEntity) -> Unit,
    onDeleteTemplate: (SdDistributedTemplateEntity) -> Unit,
    isRunning: Boolean,
    generationState: SDGenerationState,
    startError: String?,
    onStart: () -> Unit,
    onCancel: () -> Unit
) {
    CollapsibleFarmCard(
        title = stringResource(R.string.sd_dist_image_card_title),
        subtitle = stringResource(R.string.sd_dist_image_card_desc),
        expanded = expanded,
        onExpandedChange = onExpandedChange,
        accent = RenderFarmPalette.coral.copy(alpha = 0.14f)
    ) {
        SimpleStringPicker(
            label = stringResource(R.string.sd_dist_image_mode),
            selected = mode,
            options = listOf("TXT2IMG", "IMG2IMG", "UPSCALE"),
            offLabel = "TXT2IMG",
            onSelected = onModeChange
        )
        if (mode == "UPSCALE") {
            ModelPicker(
                label = stringResource(R.string.sd_dist_upscaler_model),
                models = upscalerModels,
                selectedPath = upscalerModelPath,
                onSelectedPath = onUpscalerModelPathChange
            )
        } else {
            ModelPicker(
                label = stringResource(R.string.sd_dist_image_model),
                models = models,
                selectedPath = modelPath,
                onSelectedPath = onModelPathChange
            )
            ImageComponentPickerSection(
                mode = mode,
                components = components,
                onComponentPathChange = onComponentPathChange,
                onControlNetEnabledChange = onControlNetEnabledChange,
                onLoraEnabledChange = onLoraEnabledChange,
                loraStack = loraStack,
                onLoraStackChange = onLoraStackChange,
                onLoraApplyModeChange = onLoraApplyModeChange
            )
        }
        if (mode != "TXT2IMG") {
            InputImagePathField(
                inputPath = inputPath,
                onInputPathChange = onInputPathChange,
                onPickInputImage = onPickInputImage
            )
        }
        LabeledTextField(R.string.sd_dist_prompt, prompt, onPromptChange, R.string.sd_dist_prompt_hint)
        LabeledTextField(R.string.sd_dist_negative_prompt, negativePrompt, onNegativePromptChange, R.string.sd_dist_negative_prompt_hint)
        TwoColumnFields(
            first = { LabeledTextField(R.string.imagegen_width_label, width, onWidthChange, R.string.sd_dist_width_hint, KeyboardType.Number) },
            second = { LabeledTextField(R.string.imagegen_height_label, height, onHeightChange, R.string.sd_dist_height_hint, KeyboardType.Number) }
        )
        LabeledTextField(R.string.sd_dist_steps, steps, onStepsChange, R.string.sd_dist_steps_hint, KeyboardType.Number)
        TwoColumnFields(
            first = { LabeledTextField(R.string.sd_dist_cfg, cfg, onCfgChange, R.string.sd_dist_cfg_hint, KeyboardType.Decimal) },
            second = { LabeledTextField(R.string.sd_dist_seed, seed, onSeedChange, R.string.sd_dist_seed_hint, KeyboardType.Number) }
        )
        SamplingPicker(selected = sampler, onSelected = onSamplerChange)
        SdSchedulerPicker(
            value = SdScheduler.fromCliName(scheduler),
            onValueChange = { onSchedulerChange(it?.cliName.orEmpty()) }
        )
        LabeledTextField(
            R.string.sd_dist_flow_shift_optional,
            flowShift,
            onFlowShiftChange,
            R.string.sd_dist_flow_shift_optional_hint,
            KeyboardType.Decimal
        )
        if (mode == "IMG2IMG") {
            LabeledTextField(R.string.sd_dist_strength, strength, onStrengthChange, R.string.sd_dist_strength_hint, KeyboardType.Decimal)
        }
        LabeledTextField(
            R.string.sd_manual_flags_label,
            customFlags,
            onCustomFlagsChange,
            R.string.sd_manual_flags_hint
        )
        TemplateManagerCard(
            title = stringResource(R.string.sd_dist_image_templates),
            templates = templates,
            templateNameDraft = templateNameDraft,
            onTemplateNameChange = onTemplateNameChange,
            onSaveTemplate = onSaveTemplate,
            onLoadTemplate = onLoadTemplate,
            onDeleteTemplate = onDeleteTemplate
        )
        if (!isRunning) startError?.let { WarningBand(text = it) }
        RunButtons(isRunning = isRunning, onStart = onStart, onCancel = onCancel)
        if (isRunning) {
            SdDistributedImageProgressCard(generationState)
        }
    }
}

@Composable
internal fun VideoRunCard(
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    componentModels: Map<SdVideoComponentRole, List<ModelEntity>>,
    binaryCapabilities: SdBinaryCapabilities?,
    binaryProbePending: Boolean,
    binaryProbeUnavailable: Boolean,
    onRetryBinaryProbe: () -> Unit,
    onOpenBinarySettings: () -> Unit,
    uncondDiffusionModels: List<ModelEntity>,
    ipAdapterModels: List<ModelEntity>,
    videoOptions: VideoRuntimeOptions,
    onVideoOptionsChange: (VideoRuntimeOptions) -> Unit,
    lingBotReady: Boolean,
    onApplyLingBot: () -> Unit,
    loraModels: List<ModelEntity>,
    loras: List<SdLoraSpec>,
    onLorasChange: (List<SdLoraSpec>) -> Unit,
    highNoiseLoras: List<SdLoraSpec>,
    onHighNoiseLorasChange: (List<SdLoraSpec>) -> Unit,
    loraApplyMode: String,
    onLoraApplyModeChange: (String) -> Unit,
    prompt: String,
    onPromptChange: (String) -> Unit,
    negativePrompt: String,
    onNegativePromptChange: (String) -> Unit,
    width: String,
    onWidthChange: (String) -> Unit,
    height: String,
    onHeightChange: (String) -> Unit,
    steps: String,
    onStepsChange: (String) -> Unit,
    cfg: String,
    onCfgChange: (String) -> Unit,
    sampler: String,
    onSamplerChange: (String) -> Unit,
    scheduler: String,
    onSchedulerChange: (String) -> Unit,
    flowShift: String,
    onFlowShiftChange: (String) -> Unit,
    frames: String,
    onFramesChange: (String) -> Unit,
    fps: String,
    onFpsChange: (String) -> Unit,
    customFlags: String,
    onCustomFlagsChange: (String) -> Unit,
    templates: List<SdDistributedTemplateEntity>,
    templateNameDraft: String,
    onTemplateNameChange: (String) -> Unit,
    onSaveTemplate: () -> Unit,
    onLoadTemplate: (SdDistributedTemplateEntity) -> Unit,
    onDeleteTemplate: (SdDistributedTemplateEntity) -> Unit,
    isRunning: Boolean,
    generationState: VideoGenerationState,
    startError: String?,
    onStart: () -> Unit,
    onCancel: () -> Unit
) {
    CollapsibleFarmCard(
        title = stringResource(R.string.sd_dist_video_card_title),
        subtitle = stringResource(R.string.sd_dist_video_card_desc),
        expanded = expanded,
        onExpandedChange = onExpandedChange,
        accent = RenderFarmPalette.violet.copy(alpha = 0.14f)
    ) {
        VideoRuntimeOptionsEditor(
            options = videoOptions,
            modifier = Modifier.walkthroughTarget("video.models"),
            onOptionsChange = onVideoOptionsChange,
            componentModels = componentModels,
            loraModels = loraModels,
            loras = loras,
            highNoiseLoras = highNoiseLoras,
            onLorasChange = onLorasChange,
            onHighNoiseLorasChange = onHighNoiseLorasChange,
            loraApplyMode = SdLoraApplyMode.fromStoredValue(loraApplyMode),
            onLoraApplyModeChange = { onLoraApplyModeChange(it?.cliName.orEmpty()) },
            onApplyLingBot = onApplyLingBot,
            lingBotReady = lingBotReady,
            binaryCapabilities = binaryCapabilities,
            binaryProbePending = binaryProbePending,
            binaryProbeUnavailable = binaryProbeUnavailable,
            onRetryBinaryProbe = onRetryBinaryProbe,
            onOpenBinarySettings = onOpenBinarySettings,
            uncondDiffusionModels = uncondDiffusionModels,
            ipAdapterModels = ipAdapterModels
        )
        LabeledTextField(R.string.sd_dist_prompt, prompt, onPromptChange, R.string.sd_dist_prompt_hint)
        LabeledTextField(R.string.sd_dist_negative_prompt, negativePrompt, onNegativePromptChange, R.string.sd_dist_negative_prompt_hint)
        TwoColumnFields(
            first = { LabeledTextField(R.string.video_gen_width_label, width, onWidthChange, R.string.sd_dist_video_width_hint, KeyboardType.Number) },
            second = { LabeledTextField(R.string.video_gen_height_label, height, onHeightChange, R.string.sd_dist_video_height_hint, KeyboardType.Number) }
        )
        LabeledTextField(R.string.sd_dist_steps, steps, onStepsChange, R.string.sd_dist_video_steps_hint, KeyboardType.Number)
        LabeledTextField(R.string.sd_dist_cfg, cfg, onCfgChange, R.string.sd_dist_cfg_hint, KeyboardType.Decimal)
        TwoColumnFields(
            first = { LabeledTextField(R.string.sd_dist_frames, frames, onFramesChange, R.string.sd_dist_frames_hint, KeyboardType.Number) },
            second = { LabeledTextField(R.string.sd_dist_fps, fps, onFpsChange, R.string.sd_dist_fps_hint, KeyboardType.Number) }
        )
        SamplingPicker(selected = sampler, onSelected = onSamplerChange)
        SdSchedulerPicker(
            value = SdScheduler.fromCliName(scheduler),
            onValueChange = { onSchedulerChange(it?.cliName.orEmpty()) }
        )
        LabeledTextField(
            R.string.sd_dist_flow_shift_optional,
            flowShift,
            onFlowShiftChange,
            R.string.sd_dist_flow_shift_optional_hint,
            KeyboardType.Decimal
        )
        LabeledTextField(
            R.string.sd_manual_flags_label,
            customFlags,
            onCustomFlagsChange,
            R.string.sd_manual_flags_hint
        )
        TemplateManagerCard(
            title = stringResource(R.string.sd_dist_video_templates),
            templates = templates,
            templateNameDraft = templateNameDraft,
            onTemplateNameChange = onTemplateNameChange,
            onSaveTemplate = onSaveTemplate,
            onLoadTemplate = onLoadTemplate,
            onDeleteTemplate = onDeleteTemplate
        )
        if (!isRunning) startError?.let { WarningBand(text = it) }
        RunButtons(isRunning = isRunning, onStart = onStart, onCancel = onCancel)
        if (isRunning) {
            SdDistributedVideoProgressCard(generationState)
        }
    }
}

@Composable
internal fun RunButtons(
    isRunning: Boolean,
    onStart: () -> Unit,
    onCancel: () -> Unit
) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
        if (isRunning) {
            OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Stop, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.action_cancel), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        } else {
            Button(
                onClick = onStart,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.sd_dist_start_work), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
internal fun InputImagePathField(
    inputPath: String,
    onInputPathChange: (String) -> Unit,
    onPickInputImage: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        LabeledTextField(
            R.string.sd_dist_input_image_path,
            inputPath,
            onInputPathChange,
            R.string.sd_dist_input_image_path_hint
        )
        OutlinedButton(
            onClick = onPickInputImage,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.Image, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(
                text = stringResource(
                    if (inputPath.isBlank()) R.string.sd_dist_choose_input_image else R.string.sd_dist_change_input_image
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
internal fun SdDistributedImageProgressCard(state: SDGenerationState) {
    val generating = state as? SDGenerationState.Generating ?: return
    val snapshot = generating.snapshot
    SdDistributedProgressCard(
        title = stringResource(R.string.sd_dist_image_progress_title),
        status = snapshot.statusText.ifBlank { stringResource(R.string.gen_status_calculating_eta) },
        progress = snapshot.progress,
        currentStep = snapshot.currentStep,
        totalSteps = snapshot.totalSteps,
        etaSeconds = snapshot.etaSeconds
    )
}

@Composable
internal fun SdDistributedVideoProgressCard(state: VideoGenerationState) {
    when (state) {
        is VideoGenerationState.Generating -> SdDistributedProgressCard(
            title = stringResource(R.string.sd_dist_video_progress_title),
            status = state.status.ifBlank { stringResource(R.string.gen_status_calculating_eta) },
            progress = state.progress,
            currentStep = state.currentStep,
            totalSteps = state.totalSteps,
            etaSeconds = state.etaSeconds
        )
        is VideoGenerationState.Converting -> SdDistributedProgressCard(
            title = stringResource(R.string.sd_dist_video_progress_title),
            status = state.status,
            progress = state.progress,
            currentStep = 0,
            totalSteps = 0,
            etaSeconds = null
        )
        is VideoGenerationState.Copying -> SdDistributedProgressCard(
            title = stringResource(R.string.sd_dist_video_progress_title),
            status = state.status,
            progress = state.progress,
            currentStep = 0,
            totalSteps = 0,
            etaSeconds = null
        )
        else -> Unit
    }
}

@Composable
internal fun SdDistributedProgressCard(
    title: String,
    status: String,
    progress: Float,
    currentStep: Int,
    totalSteps: Int,
    etaSeconds: Double?
) {
    ElevatedCard(
        colors = CardDefaults.elevatedCardColors(
            containerColor = RenderFarmPalette.graphite.copy(alpha = 0.90f)
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.padding(14.dp)
        ) {
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = RenderFarmPalette.lime
                )
                Text(
                    text = stringResource(
                        R.string.sd_dist_progress_percent,
                        (progress.coerceIn(0f, 1f) * 100).toInt()
                    ),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            LinearProgressIndicator(
                progress = { progress.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
                color = RenderFarmPalette.lime,
                trackColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
            )
            Text(
                text = status,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val stepText = if (totalSteps > 0) {
                    stringResource(
                        R.string.sd_dist_progress_steps,
                        currentStep.coerceAtLeast(0),
                        totalSteps
                    )
                } else {
                    stringResource(R.string.sd_dist_progress_steps_unknown)
                }
                AssistChip(onClick = {}, label = { Text(stepText) })
                AssistChip(
                    onClick = {},
                    label = {
                        Text(
                            stringResource(
                                R.string.sd_dist_progress_eta,
                                etaSeconds?.let { sdDistributedEtaLabel(it) }
                                    ?: stringResource(R.string.sd_dist_progress_eta_calculating)
                            )
                        )
                    }
                )
            }
        }
    }
}

@Composable
internal fun sdDistributedEtaLabel(etaSeconds: Double): String {
    val roundedSeconds = etaSeconds.coerceAtLeast(0.0).toInt()
    return when {
        roundedSeconds < 60 -> stringResource(R.string.gen_eta_seconds_short, roundedSeconds)
        roundedSeconds < 3600 -> stringResource(
            R.string.gen_eta_minutes_seconds_short,
            roundedSeconds / 60,
            roundedSeconds % 60
        )
        else -> stringResource(
            R.string.gen_eta_hours_minutes_short,
            roundedSeconds / 3600,
            (roundedSeconds % 3600) / 60
        )
    }
}
