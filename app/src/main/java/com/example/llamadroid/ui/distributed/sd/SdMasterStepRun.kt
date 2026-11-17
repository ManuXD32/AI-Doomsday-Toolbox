@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.example.llamadroid.ui.distributed.sd

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Collections
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.example.llamadroid.R
import com.example.llamadroid.data.db.SdDistributedMasterSettingsEntity
import com.example.llamadroid.service.SDGenerationState
import com.example.llamadroid.service.SDMode
import com.example.llamadroid.service.SDModeStateHolder
import com.example.llamadroid.service.SdCacheMode
import com.example.llamadroid.service.SdCacheScmPolicy
import com.example.llamadroid.service.SdDistributedRunMonitor
import com.example.llamadroid.service.SdDistributedService
import com.example.llamadroid.service.SdScheduler
import com.example.llamadroid.service.VideoGenerationState
import com.example.llamadroid.service.VideoGenerationStateHolder
import com.example.llamadroid.service.buildSdDistributedPreviewArgs
import com.example.llamadroid.ui.components.AppAdvancedSection
import com.example.llamadroid.ui.components.AppSectionCard
import com.example.llamadroid.ui.components.ResponsiveAction
import com.example.llamadroid.ui.components.ResponsiveActionGroup
import com.example.llamadroid.ui.components.ResponsiveActionStyle
import com.example.llamadroid.ui.components.SdSchedulerPicker
import com.example.llamadroid.ui.components.SdTensorTypeRulesPicker
import com.example.llamadroid.ui.navigation.Screen

/** What the Run step needs to know about the active run of the current workflow. */
internal class SdMasterRunStatus(
    val running: Boolean,
    val error: String?,
    /** True once a run was begun in the monitor (the progress panel has something to show). */
    val hasSession: Boolean
)

/** Screen-owned hooks the Run step cannot create inside a lazy item (activity-result launchers, navigation). */
internal class SdMasterRunHooks(
    val status: SdMasterRunStatus,
    val navController: NavController,
    val onPickInputImage: () -> Unit
)

/** Error shown beside the Start button: a blocked launch wins over a service-side run failure; blank counts as none. */
internal fun sdDistributedVisibleError(launchError: String?, runError: String?): String? =
    launchError?.takeIf { it.isNotBlank() } ?: runError?.takeIf { it.isNotBlank() }

/** Live state of the workflow the screen is configuring, from the distributed state holders. */
@Composable
internal fun rememberSdMasterRunStatus(state: SdMasterUiState): SdMasterRunStatus {
    val imageMode = state.settings.imageWorkflowMode.toSdMasterImageMode()
    val imageState by remember(imageMode) {
        SDModeStateHolder.getDistributedForMode(imageMode).state
    }.collectAsState()
    val videoMode = state.settings.videoWorkflowMode.toSdMasterVideoMode()
    val videoState by remember(videoMode) {
        VideoGenerationStateHolder.getForMode(videoMode, useDistributedStateHolder = true).state
    }.collectAsState()
    val session by SdDistributedRunMonitor.session.collectAsState()
    val video = state.mode == SdMasterMode.VIDEO
    val running = if (video) {
        when (videoState) {
            is VideoGenerationState.Generating,
            is VideoGenerationState.Converting,
            is VideoGenerationState.Copying -> true
            else -> false
        }
    } else {
        imageState is SDGenerationState.Generating
    }
    // Service-side start failures (missing binary, preflight, process exit) land in the state holders.
    val error = if (video) {
        (videoState as? VideoGenerationState.Error)?.message
    } else {
        (imageState as? SDGenerationState.Error)?.message
    }
    return SdMasterRunStatus(running = running, error = error, hasSession = session != null)
}

/**
 * Step 3 of the main-device flow: prompt, size and the run itself. Rarely used controls sit in
 * collapsed Advanced sections, one keyed item each. Start / Stop live in the screen footer.
 */
internal fun LazyListScope.sdMasterRunStepItems(
    state: SdMasterUiState,
    vm: SdMasterViewModel,
    hooks: SdMasterRunHooks
) {
    val settings = state.settings
    val video = state.mode == SdMasterMode.VIDEO
    val imageMode = settings.imageWorkflowMode.toSdMasterImageMode()
    val upscale = !video && imageMode == SDMode.UPSCALE
    val helperCount = state.workers.count { it.isEnabled }
    val status = hooks.status

    if (status.running || status.error != null || helperCount > 0) {
        item(key = "run_status") {
            val logs by SdDistributedService.logs.collectAsState()
            SdMasterStatusPanel(
                helperCount = helperCount,
                running = status.running,
                error = status.error,
                onAddHelper = { vm.goTo(SdMasterStep.DEVICES) },
                logLines = logs
            )
        }
    }
    if (status.hasSession) {
        item(key = "run_progress") {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { SdRunProgressSection(hooks.navController) }
        }
    }
    if (!video && imageMode != SDMode.TXT2IMG) {
        item(key = "run_input_image") {
            SdMasterInputImageCard(settings, vm::update, hooks.onPickInputImage)
        }
    }
    if (!upscale) {
        item(key = "run_prompt") { SdMasterPromptCard(settings, video, vm::update) }
        item(key = "run_size") { SdMasterSizeCard(settings, video, vm::update) }
        item(key = "run_sampling") { SdMasterSamplingSection(settings, video, imageMode, vm::update) }
    }
    item(key = "run_runtime") { SdMasterRuntimeSection(settings, video, vm::update) }
    if (!upscale) {
        item(key = "run_cache") { SdMasterCacheSection(settings, vm::update) }
    }
    item(key = "run_templates") { SdMasterTemplatesSection(vm, video) }
    item(key = "run_command") {
        val runtime = buildSdMasterRuntime(settings, state.workers, video = video)
        SdMasterCommandSection(settings, video, buildSdDistributedPreviewArgs(runtime.runtimeConfig).joinToString(" "), vm::update)
    }
    item(key = "run_links") { SdMasterLinksCard(hooks.navController) }
}

@Composable
private fun SdMasterCardTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis
    )
}

// ---- Inputs ----

@Composable
private fun SdMasterInputImageCard(
    settings: SdDistributedMasterSettingsEntity,
    onUpdate: SdMasterSettingsUpdate,
    onPick: () -> Unit
) {
    AppSectionCard {
        SdMasterCardTitle(stringResource(R.string.sd_r8_w08_input_title))
        InputImagePathField(
            inputPath = settings.imageInputPath,
            onInputPathChange = { path -> onUpdate { it.copy(imageInputPath = path) } },
            onPickInputImage = onPick
        )
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
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
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
private fun SdMasterPromptCard(
    settings: SdDistributedMasterSettingsEntity,
    video: Boolean,
    onUpdate: SdMasterSettingsUpdate
) {
    AppSectionCard {
        SdMasterCardTitle(stringResource(R.string.sd_r8_w08_prompt_title))
        LabeledTextField(
            R.string.sd_dist_prompt,
            if (video) settings.videoPrompt else settings.imagePrompt,
            { value ->
                onUpdate { if (video) it.copy(videoPrompt = value) else it.copy(imagePrompt = value) }
            },
            R.string.sd_dist_prompt_hint
        )
        LabeledTextField(
            R.string.sd_dist_negative_prompt,
            if (video) settings.videoNegativePrompt else settings.imageNegativePrompt,
            { value ->
                onUpdate {
                    if (video) it.copy(videoNegativePrompt = value) else it.copy(imageNegativePrompt = value)
                }
            },
            R.string.sd_dist_negative_prompt_hint
        )
    }
}

// ---- Size / steps ----

@Composable
private fun SdMasterSizeCard(
    settings: SdDistributedMasterSettingsEntity,
    video: Boolean,
    onUpdate: SdMasterSettingsUpdate
) {
    AppSectionCard {
        SdMasterCardTitle(stringResource(R.string.sd_r8_w08_size_title))
        if (video) {
            TwoColumnFields(
                first = {
                    LabeledTextField(
                        R.string.video_gen_width_label, settings.videoWidth,
                        { v -> onUpdate { it.copy(videoWidth = v.filter(Char::isDigit).take(5)) } },
                        R.string.sd_dist_video_width_hint, KeyboardType.Number
                    )
                },
                second = {
                    LabeledTextField(
                        R.string.video_gen_height_label, settings.videoHeight,
                        { v -> onUpdate { it.copy(videoHeight = v.filter(Char::isDigit).take(5)) } },
                        R.string.sd_dist_video_height_hint, KeyboardType.Number
                    )
                }
            )
            TwoColumnFields(
                first = {
                    LabeledTextField(
                        R.string.sd_dist_steps, settings.videoSteps,
                        { v -> onUpdate { it.copy(videoSteps = v) } },
                        R.string.sd_dist_video_steps_hint, KeyboardType.Number
                    )
                },
                second = {
                    LabeledTextField(
                        R.string.sd_dist_cfg, settings.videoCfg,
                        { v -> onUpdate { it.copy(videoCfg = v) } },
                        R.string.sd_dist_cfg_hint, KeyboardType.Decimal
                    )
                }
            )
            TwoColumnFields(
                first = {
                    LabeledTextField(
                        R.string.sd_dist_frames, settings.frames,
                        { v -> onUpdate { it.copy(frames = v) } },
                        R.string.sd_dist_frames_hint, KeyboardType.Number
                    )
                },
                second = {
                    LabeledTextField(
                        R.string.sd_dist_fps, settings.fps,
                        { v -> onUpdate { it.copy(fps = v) } },
                        R.string.sd_dist_fps_hint, KeyboardType.Number
                    )
                }
            )
        } else {
            TwoColumnFields(
                first = {
                    LabeledTextField(
                        R.string.imagegen_width_label, settings.imageWidth,
                        { v -> onUpdate { it.copy(imageWidth = v.filter(Char::isDigit).take(5)) } },
                        R.string.sd_dist_width_hint, KeyboardType.Number
                    )
                },
                second = {
                    LabeledTextField(
                        R.string.imagegen_height_label, settings.imageHeight,
                        { v -> onUpdate { it.copy(imageHeight = v.filter(Char::isDigit).take(5)) } },
                        R.string.sd_dist_height_hint, KeyboardType.Number
                    )
                }
            )
            LabeledTextField(
                R.string.sd_dist_steps, settings.imageSteps,
                { v -> onUpdate { it.copy(imageSteps = v) } },
                R.string.sd_dist_steps_hint, KeyboardType.Number
            )
            TwoColumnFields(
                first = {
                    LabeledTextField(
                        R.string.sd_dist_cfg, settings.imageCfg,
                        { v -> onUpdate { it.copy(imageCfg = v) } },
                        R.string.sd_dist_cfg_hint, KeyboardType.Decimal
                    )
                },
                second = {
                    LabeledTextField(
                        R.string.sd_dist_seed, settings.imageSeed,
                        { v -> onUpdate { it.copy(imageSeed = v) } },
                        R.string.sd_dist_seed_hint, KeyboardType.Number
                    )
                }
            )
        }
    }
}

// ---- Advanced ----

@Composable
private fun SdMasterSamplingSection(
    settings: SdDistributedMasterSettingsEntity,
    video: Boolean,
    imageMode: SDMode,
    onUpdate: SdMasterSettingsUpdate
) {
    AppAdvancedSection(title = stringResource(R.string.sd_r8_w08_adv_sampling)) {
        SamplingPicker(
            selected = if (video) settings.videoSampler else settings.imageSampler,
            onSelected = { value ->
                onUpdate { if (video) it.copy(videoSampler = value) else it.copy(imageSampler = value) }
            }
        )
        SdSchedulerPicker(
            value = SdScheduler.fromCliName(if (video) settings.videoScheduler else settings.imageScheduler),
            onValueChange = { scheduler ->
                val name = scheduler?.cliName.orEmpty()
                onUpdate { if (video) it.copy(videoScheduler = name) else it.copy(imageScheduler = name) }
            }
        )
        LabeledTextField(
            R.string.sd_dist_flow_shift_optional,
            if (video) settings.videoFlowShift else settings.imageFlowShift,
            { value ->
                onUpdate { if (video) it.copy(videoFlowShift = value) else it.copy(imageFlowShift = value) }
            },
            R.string.sd_dist_flow_shift_optional_hint,
            KeyboardType.Decimal
        )
        if (!video && imageMode == SDMode.IMG2IMG) {
            LabeledTextField(
                R.string.sd_dist_strength, settings.strength,
                { value -> onUpdate { it.copy(strength = value) } },
                R.string.sd_dist_strength_hint, KeyboardType.Decimal
            )
            if (settings.imageControlNetEnabled) {
                // The video workflow's control strength lives in the shared video options editor.
                LabeledTextField(
                    R.string.sd_dist_control_strength, settings.controlStrength,
                    { value -> onUpdate { it.copy(controlStrength = value) } },
                    R.string.sd_dist_control_strength_hint, KeyboardType.Decimal
                )
            }
        }
    }
}

@Composable
private fun SdMasterRuntimeSection(
    settings: SdDistributedMasterSettingsEntity,
    video: Boolean,
    onUpdate: SdMasterSettingsUpdate
) {
    AppAdvancedSection(title = stringResource(R.string.sd_r8_w08_adv_runtime)) {
        ToggleRow(
            stringResource(R.string.sd_dist_mmap), stringResource(R.string.sd_dist_mmap_desc), settings.mmap
        ) { checked -> onUpdate { it.copy(mmap = checked) } }
        ToggleRow(
            stringResource(R.string.sd_dist_diffusion_fa), stringResource(R.string.sd_dist_diffusion_fa_desc),
            settings.diffusionFa
        ) { checked -> onUpdate { it.copy(diffusionFa = checked) } }
        ToggleRow(
            stringResource(R.string.sd_dist_vae_tiling), stringResource(R.string.sd_dist_vae_tiling_desc),
            settings.vaeTiling
        ) { checked -> onUpdate { it.copy(vaeTiling = checked) } }
        if (video) {
            // The video editor owns tile overlap for video runs; only the tile size is shared.
            LabeledTextField(
                R.string.sd_dist_vae_tile_size, settings.vaeTileSize,
                { value -> onUpdate { it.copy(vaeTileSize = value) } },
                R.string.sd_dist_vae_tile_size_hint
            )
        } else {
            TwoColumnFields(
                first = {
                    LabeledTextField(
                        R.string.sd_dist_vae_tile_size, settings.vaeTileSize,
                        { value -> onUpdate { it.copy(vaeTileSize = value) } },
                        R.string.sd_dist_vae_tile_size_hint
                    )
                },
                second = {
                    LabeledTextField(
                        R.string.sd_dist_vae_tile_overlap, settings.vaeTileOverlap,
                        { value -> onUpdate { it.copy(vaeTileOverlap = value) } },
                        R.string.sd_dist_vae_tile_overlap_hint, KeyboardType.Number
                    )
                }
            )
        }
        SimpleStringPicker(
            label = stringResource(R.string.sd_dist_quantization),
            selected = settings.quantization,
            options = sdQuantizationOptions(),
            offLabel = stringResource(R.string.sd_dist_picker_auto),
            onSelected = { value -> onUpdate { it.copy(quantization = value) } }
        )
        SdTensorTypeRulesPicker(
            value = settings.tensorRules,
            onValueChange = { value -> onUpdate { it.copy(tensorRules = value) } }
        )
    }
}

@Composable
private fun SdMasterCacheSection(settings: SdDistributedMasterSettingsEntity, onUpdate: SdMasterSettingsUpdate) {
    AppAdvancedSection(title = stringResource(R.string.sd_r8_w08_adv_cache)) {
        CacheModePicker(
            selected = settings.cacheMode,
            onSelected = { mode ->
                onUpdate {
                    when {
                        mode.isBlank() -> it.copy(cacheMode = mode, cacheOption = "", scmMask = "", scmPolicy = "")
                        mode != SdCacheMode.CACHE_DIT.cliName -> it.copy(cacheMode = mode, scmMask = "", scmPolicy = "")
                        else -> it.copy(cacheMode = mode)
                    }
                }
            }
        )
        if (settings.cacheMode.isNotBlank()) {
            CacheOptionPicker(
                cacheMode = settings.cacheMode,
                value = settings.cacheOption,
                onValueChange = { value -> onUpdate { it.copy(cacheOption = value) } }
            )
            if (settings.cacheMode == SdCacheMode.CACHE_DIT.cliName) {
                ScmMaskPicker(
                    value = settings.scmMask,
                    onValueChange = { value -> onUpdate { it.copy(scmMask = value) } }
                )
                SimpleStringPicker(
                    label = stringResource(R.string.sd_dist_scm_policy),
                    selected = settings.scmPolicy,
                    options = SdCacheScmPolicy.entries.map { it.cliName },
                    offLabel = stringResource(R.string.sd_dist_picker_off),
                    onSelected = { value -> onUpdate { it.copy(scmPolicy = value) } }
                )
            }
        }
    }
}

@Composable
private fun SdMasterTemplatesSection(vm: SdMasterViewModel, video: Boolean) {
    val templates by vm.templates.collectAsState()
    val workflow = if (video) SdDistributedTemplateWorkflow.VIDEO else SdDistributedTemplateWorkflow.IMAGE
    val shown = remember(templates, workflow) { templates.filter { it.workflowType == workflow.name } }
    var nameDraft by rememberSaveable { mutableStateOf("") }
    val title = stringResource(if (video) R.string.sd_dist_video_templates else R.string.sd_dist_image_templates)
    AppAdvancedSection(title = title) {
        TemplateManagerCard(
            title = title,
            templates = shown,
            templateNameDraft = nameDraft,
            onTemplateNameChange = { nameDraft = it },
            onSaveTemplate = { vm.saveTemplate(nameDraft) { saved -> if (saved) nameDraft = "" } },
            onLoadTemplate = vm::loadTemplate,
            onDeleteTemplate = { template -> vm.deleteTemplate(template.id) }
        )
    }
}

@Composable
private fun SdMasterCommandSection(
    settings: SdDistributedMasterSettingsEntity,
    video: Boolean,
    preview: String,
    onUpdate: SdMasterSettingsUpdate
) {
    AppAdvancedSection(title = stringResource(R.string.sd_dist_command_preview)) {
        LabeledTextField(
            R.string.sd_manual_flags_label,
            if (video) settings.videoCustomFlags else settings.imageCustomFlags,
            { value ->
                onUpdate { if (video) it.copy(videoCustomFlags = value) else it.copy(imageCustomFlags = value) }
            },
            R.string.sd_manual_flags_hint
        )
        LabeledTextField(
            R.string.sd_dist_custom_flags, settings.customFlags,
            { value -> onUpdate { it.copy(customFlags = value) } },
            R.string.sd_dist_custom_flags_hint
        )
        CodeBlock(text = preview.ifBlank { "-" })
    }
}

// ---- Results ----

@Composable
private fun SdMasterLinksCard(navController: NavController) {
    AppSectionCard {
        SdMasterCardTitle(stringResource(R.string.sd_r8_w08_links_title))
        ResponsiveActionGroup(
            actions = listOf(
                ResponsiveAction(
                    label = stringResource(R.string.sd_dist_view_network),
                    onClick = { navController.navigate(Screen.SdDistributedNetwork.route) },
                    icon = Icons.Default.Share,
                    style = ResponsiveActionStyle.Secondary
                ),
                ResponsiveAction(
                    label = stringResource(R.string.sd_dist_open_gallery),
                    onClick = { navController.navigate(Screen.SdDistributedGallery.route) },
                    icon = Icons.Default.Collections,
                    style = ResponsiveActionStyle.Secondary
                )
            )
        )
    }
}
