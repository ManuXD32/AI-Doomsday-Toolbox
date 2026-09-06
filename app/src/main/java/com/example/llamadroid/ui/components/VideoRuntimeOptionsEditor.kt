package com.example.llamadroid.ui.components

import android.content.res.Resources

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.focus.onFocusChanged
import com.example.llamadroid.ui.walkthrough.LocalWalkthroughTargets
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.llamadroid.R
import com.example.llamadroid.data.db.ModelEntity
import com.example.llamadroid.sd.SdLoraApplyMode
import com.example.llamadroid.sd.SdLoraSpec
import com.example.llamadroid.sd.SdVideoAudioCodec
import com.example.llamadroid.sd.SdVideoComponentPaths
import com.example.llamadroid.sd.SdVideoComponentRole
import com.example.llamadroid.sd.SdVideoFamily
import com.example.llamadroid.sd.SdVideoFamilyProfile
import com.example.llamadroid.sd.SdVideoFamilyProfiles
import com.example.llamadroid.sd.SdVideoInputRole
import com.example.llamadroid.sd.SdVideoInputs
import com.example.llamadroid.sd.SdVideoNativeOutputFormat
import com.example.llamadroid.sd.SdVideoOutputFormat
import com.example.llamadroid.sd.SdVideoPromptFormat
import com.example.llamadroid.sd.SdVideoWorkflow
import com.example.llamadroid.sd.pathFor
import com.example.llamadroid.sd.requiredRolesFor
import com.example.llamadroid.service.VideoRuntimeOptions
import com.example.llamadroid.service.SdBinaryCapabilities
import com.example.llamadroid.ui.walkthrough.walkthroughTarget

/**
 * Shared editor for the typed video contract. It intentionally keeps the
 * profile/input controls above the disclosure footer so local and distributed
 * screens can place it inside their existing scroll containers safely.
 */
@Composable
fun VideoRuntimeOptionsEditor(
    options: VideoRuntimeOptions,
    onOptionsChange: (VideoRuntimeOptions) -> Unit,
    modifier: Modifier = Modifier,
    componentModels: Map<SdVideoComponentRole, List<ModelEntity>> = emptyMap(),
    loraModels: List<ModelEntity> = emptyList(),
    loras: List<SdLoraSpec> = emptyList(),
    highNoiseLoras: List<SdLoraSpec> = emptyList(),
    onLorasChange: (List<SdLoraSpec>) -> Unit = {},
    onHighNoiseLorasChange: (List<SdLoraSpec>) -> Unit = {},
    loraApplyMode: SdLoraApplyMode? = null,
    onLoraApplyModeChange: (SdLoraApplyMode?) -> Unit = {},
    onApplyLingBot: (() -> Unit)? = null,
    lingBotReady: Boolean = false,
    binaryCapabilities: SdBinaryCapabilities? = null,
    binaryProbePending: Boolean = binaryCapabilities == null,
    binaryProbeUnavailable: Boolean = false,
    onRetryBinaryProbe: (() -> Unit)? = null,
    onOpenBinarySettings: (() -> Unit)? = null,
    uncondDiffusionModels: List<ModelEntity> = emptyList(),
    ipAdapterModels: List<ModelEntity> = emptyList()
) {
    val profile = options.videoFamily?.let { SdVideoFamilyProfiles.resolve(it, options.videoVariant) }
    val supportedWorkflows = profile?.supportedWorkflows?.toList()
        ?: SdVideoWorkflow.entries.toList()
    val workflow = options.workflow ?: supportedWorkflows.first()
    val componentRoles = videoEditorComponentRoles(profile, workflow, options.videoComponents)
    val availability = videoUiAvailability(
        profile = profile,
        binaryCapabilities = binaryCapabilities,
        binaryProbePending = binaryProbePending,
        binaryProbeUnavailable = binaryProbeUnavailable
    )
    val readiness = videoGenerationReadiness(options)
    val requestedTarget = LocalWalkthroughTargets.current?.requestedId
    val clearedUnavailable = options.clearUnavailableAdvancedSelections(availability)
    val hasUnavailableLoras = !availability.isFlagEnabled("--lora-model-dir") &&
        (loras + highNoiseLoras).any { it.enabled }
    val hasUnavailableHighNoiseLoras = !availability.highNoiseEnabled && highNoiseLoras.any { it.enabled }
    val hasUnavailableApplyMode = !availability.isFlagEnabled("--lora-apply-mode") && loraApplyMode != null
    val hasUnavailableSelections = options.hasUnavailableAdvancedSelections(availability) ||
        hasUnavailableLoras || hasUnavailableHighNoiseLoras || hasUnavailableApplyMode

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = stringResource(R.string.video_controls_profile_title),
            style = MaterialTheme.typography.titleMedium
        )
        when {
            availability.binaryProbePending -> Text(
                text = stringResource(R.string.video_controls_binary_checking),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            availability.binaryProbeUnavailable -> {
                Text(
                    text = stringResource(R.string.video_controls_binary_unavailable_selection),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
                if (onOpenBinarySettings != null || onRetryBinaryProbe != null) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        onOpenBinarySettings?.let { openSettings ->
                            OutlinedButton(
                                onClick = openSettings,
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                            ) {
                                Text(
                                    stringResource(R.string.video_controls_open_binary_settings)
                                )
                            }
                        }
                        onRetryBinaryProbe?.let { retryProbe ->
                            TextButton(
                                onClick = retryProbe,
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                            ) {
                                Text(
                                    stringResource(R.string.video_controls_binary_retry)
                                )
                            }
                        }
                    }
                }
            }
            availability.unavailableFlags.isNotEmpty() -> Text(
                text = stringResource(
                    R.string.video_controls_binary_unavailable,
                    availability.unavailableFlags.size
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
        VideoFamilyChips(
            selected = options.videoFamily,
            enabled = availability.workflowEnabled,
            onSelected = { family ->
                val nextProfile = family?.let { SdVideoFamilyProfiles.resolve(it, null) }
                val nextWorkflow = options.workflow?.takeIf { nextProfile?.supports(it) == true }
                    ?: nextProfile?.supportedWorkflows?.firstOrNull()
                onOptionsChange(options.copy(videoFamily = family, videoVariant = null, workflow = nextWorkflow,
                    promptFormat = nextProfile?.promptFormat ?: SdVideoPromptFormat.PLAIN))
            }
        )
        OutlinedTextField(
            value = options.videoVariant.orEmpty(),
            onValueChange = { value -> onOptionsChange(options.copy(videoVariant = value.trim().ifBlank { null })) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.video_controls_profile_variant)) },
            enabled = availability.workflowEnabled,
            singleLine = true,
            shape = RoundedCornerShape(12.dp)
        )
        Text(
            text = stringResource(R.string.video_controls_workflow),
            style = MaterialTheme.typography.labelLarge
        )
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            items(supportedWorkflows, key = { it.name }) { workflow ->
                FilterChip(
                    selected = options.workflow == workflow,
                    enabled = availability.workflowEnabled,
                    onClick = { onOptionsChange(options.copy(workflow = workflow)) },
                    label = { Text(videoWorkflowLabel(workflow), maxLines = 1) }
                )
            }
        }
        if (!readiness.isSatisfied) {
            if (readiness.unsupportedWorkflow) {
                Text(
                    text = stringResource(R.string.video_controls_profile_unsupported_workflow),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            if (readiness.missingComponents.isNotEmpty()) {
                    Text(
                        text = stringResource(
                            R.string.video_controls_profile_missing_components,
                            readiness.missingComponents.map { videoComponentLabel(it) }.joinToString()
                        ),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
            }
            if (readiness.missingInputs.isNotEmpty()) {
                    Text(
                        text = stringResource(
                            R.string.video_controls_profile_missing_inputs,
                            readiness.missingInputs.map { videoInputLabel(it) }.joinToString()
                        ),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
            }
        }
        if (onApplyLingBot != null) {
            OutlinedButton(
                onClick = onApplyLingBot,
                enabled = lingBotReady && availability.lingBotPromptEnabled,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.video_controls_lingbot_apply))
            }
            Text(
                text = stringResource(if (lingBotReady) R.string.video_controls_lingbot_ready else R.string.video_controls_lingbot_missing),
                style = MaterialTheme.typography.bodySmall,
                color = if (lingBotReady) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = stringResource(R.string.video_controls_lingbot_no_run),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (hasUnavailableSelections) {
            Text(
                text = stringResource(R.string.video_controls_unavailable_values_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
            OutlinedButton(
                onClick = {
                    onOptionsChange(clearedUnavailable)
                    if (!availability.isFlagEnabled("--lora-model-dir")) {
                        onLorasChange(loras.map { it.copy(enabled = false) })
                        onHighNoiseLorasChange(highNoiseLoras.map { it.copy(enabled = false) })
                    } else if (!availability.highNoiseEnabled) {
                        onHighNoiseLorasChange(highNoiseLoras.map { it.copy(enabled = false) })
                    }
                    if (!availability.isFlagEnabled("--lora-apply-mode")) onLoraApplyModeChange(null)
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.video_controls_clear_unavailable_values))
            }
        }

        ComponentPathsEditor(
            profile = profile,
            roles = componentRoles,
            paths = options.videoComponents,
            models = componentModels,
            availability = availability,
            uncondDiffusionModels = uncondDiffusionModels,
            ipAdapterModels = ipAdapterModels,
            onPathsChange = { onOptionsChange(options.copy(videoComponents = it)) }
        )
        VideoInputsEditor(
            profile = profile,
            workflow = workflow,
            inputs = options.videoInputs,
            availability = availability,
            showIpAdapterImage = ipAdapterModels.isNotEmpty() ||
                !options.videoComponents.ipAdapterPath.isNullOrBlank() ||
                !options.videoInputs.ipAdapterImagePath.isNullOrBlank(),
            onInputsChange = { onOptionsChange(options.copy(videoInputs = it)) }
        )

        AppAdvancedSection(
            title = stringResource(R.string.video_controls_advanced),
            modifier = Modifier.fillMaxWidth().walkthroughTarget("video.advanced"),
            revealContent = requestedTarget == "video.loras"
        ) {
            Text(
                text = stringResource(R.string.video_controls_advanced_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            DecoderAndPassEditor(
                options = options,
                availability = availability,
                onOptionsChange = onOptionsChange
            )
            VideoAdditionalOptionsEditor(
                options = options,
                onOptionsChange = onOptionsChange,
                availability = availability
            )
            OutputEditor(
                options = options,
                availability = availability,
                onOptionsChange = onOptionsChange
            )
            VideoLoraEditor(
                models = loraModels,
                loras = loras,
                highNoiseLoras = highNoiseLoras,
                onLorasChange = onLorasChange,
                onHighNoiseLorasChange = onHighNoiseLorasChange,
                applyMode = loraApplyMode,
                onApplyModeChange = onLoraApplyModeChange,
                availability = availability,
                profile = profile
            )
        }
    }
}

@Composable
private fun VideoFamilyChips(
    selected: SdVideoFamily?,
    enabled: Boolean,
    onSelected: (SdVideoFamily?) -> Unit
) {
    val walkthroughTargets = LocalWalkthroughTargets.current
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth().walkthroughTarget("video.profile")
    ) {
        item {
            FilterChip(
                selected = selected == null,
                enabled = enabled,
                onClick = {
                    onSelected(null)
                    walkthroughTargets?.recordEvent("video.profile")
                },
                label = { Text(stringResource(R.string.video_runtime_custom_profile)) }
            )
        }
        items(SdVideoFamilyProfiles.all, key = { it.family.name }) { profile ->
            FilterChip(
                selected = selected == profile.family,
                enabled = enabled,
                onClick = {
                    onSelected(profile.family)
                    walkthroughTargets?.recordEvent("video.profile")
                },
                label = { Text(videoFamilyLabel(profile.family), maxLines = 1) }
            )
        }
    }
}

@Composable
private fun VideoInputsEditor(
    profile: SdVideoFamilyProfile?,
    workflow: SdVideoWorkflow,
    inputs: SdVideoInputs,
    availability: VideoUiAvailability,
    showIpAdapterImage: Boolean,
    onInputsChange: (SdVideoInputs) -> Unit
) {
    val required = profile?.requiredInputRoles?.get(workflow).orEmpty() +
        profile?.requiredInputGroups?.get(workflow).orEmpty().flatten()
    val roles = when {
        profile == null -> SdVideoInputRole.entries.toSet()
        workflow == SdVideoWorkflow.TEXT_TO_VIDEO || workflow == SdVideoWorkflow.TEXT_TO_AUDIO_VIDEO -> required
        else -> required.ifEmpty { setOf(SdVideoInputRole.INIT_IMAGE) }
    }
    Column(Modifier.fillMaxWidth().walkthroughTarget("video.inputs"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (roles.isNotEmpty()) Text(stringResource(R.string.video_controls_inputs), style = MaterialTheme.typography.titleSmall)
        SdVideoInputRole.entries.forEach { role ->
            val paths = inputs.pathsFor(role)
            if (role in roles || paths.isNotEmpty()) {
                val multiple = role.name.startsWith("REFERENCE_")
                val mime = when (role) {
                    SdVideoInputRole.REFERENCE_AUDIO -> "audio/*"
                    SdVideoInputRole.CONTROL_VIDEO, SdVideoInputRole.REFERENCE_VIDEO -> "video/*"
                    // Native expects a WAV soundtrack paired by index with
                    // each reference-video frame directory.
                    SdVideoInputRole.REFERENCE_VIDEO_AUDIO -> "audio/*"
                    else -> "image/*"
                }
                VideoInputPicker(
                    label = videoInputLabel(role),
                    paths = paths,
                    mimeTypes = arrayOf(mime),
                    multiple = multiple,
                    directorySelection = role == SdVideoInputRole.CONTROL_VIDEO ||
                        role == SdVideoInputRole.REFERENCE_VIDEO,
                    enabled = availability.isFlagEnabled(videoInputFlag(role)),
                    onPathsChange = { onInputsChange(inputs.withPaths(role, it)) }
                )
            }
        }
        val ipAdapterPaths = listOfNotNull(inputs.ipAdapterImagePath)
        if (showIpAdapterImage || ipAdapterPaths.isNotEmpty()) {
            VideoInputPicker(
                label = stringResource(R.string.video_controls_ip_adapter_image),
                paths = ipAdapterPaths,
                mimeTypes = arrayOf("image/*"),
                multiple = false,
                enabled = availability.isFlagEnabled("--ip-adapter-image"),
                onPathsChange = { onInputsChange(inputs.copy(ipAdapterImagePath = it.firstOrNull())) }
            )
        }
    }
}

private fun videoInputFlag(role: SdVideoInputRole): String = when (role) {
    SdVideoInputRole.INIT_IMAGE -> "--init-img"
    SdVideoInputRole.END_IMAGE -> "--end-img"
    SdVideoInputRole.CONTROL_IMAGE -> "--control-image"
    SdVideoInputRole.CONTROL_VIDEO -> "--control-video"
    SdVideoInputRole.REFERENCE_IMAGE -> "--ref-image"
    SdVideoInputRole.REFERENCE_VIDEO -> "--ref-video"
    SdVideoInputRole.REFERENCE_VIDEO_AUDIO -> "--ref-video-audio"
    SdVideoInputRole.REFERENCE_AUDIO -> "--ref-audio"
}

private fun SdVideoInputs.pathsFor(role: SdVideoInputRole): List<String> = when (role) {
    SdVideoInputRole.INIT_IMAGE -> listOfNotNull(initImagePath)
    SdVideoInputRole.END_IMAGE -> listOfNotNull(endImagePath)
    SdVideoInputRole.CONTROL_IMAGE -> listOfNotNull(controlImagePath)
    SdVideoInputRole.CONTROL_VIDEO -> listOfNotNull(controlVideoPath)
    SdVideoInputRole.REFERENCE_IMAGE -> referenceImages
    SdVideoInputRole.REFERENCE_VIDEO -> referenceVideos
    SdVideoInputRole.REFERENCE_VIDEO_AUDIO -> referenceVideoAudios
    SdVideoInputRole.REFERENCE_AUDIO -> referenceAudios
}

private fun SdVideoInputs.withPaths(role: SdVideoInputRole, paths: List<String>): SdVideoInputs = when (role) {
    SdVideoInputRole.INIT_IMAGE -> copy(initImagePath = paths.firstOrNull())
    SdVideoInputRole.END_IMAGE -> copy(endImagePath = paths.firstOrNull())
    SdVideoInputRole.CONTROL_IMAGE -> copy(controlImagePath = paths.firstOrNull())
    SdVideoInputRole.CONTROL_VIDEO -> copy(controlVideoPath = paths.firstOrNull())
    SdVideoInputRole.REFERENCE_IMAGE -> copy(referenceImages = paths)
    SdVideoInputRole.REFERENCE_VIDEO -> copy(referenceVideos = paths)
    SdVideoInputRole.REFERENCE_VIDEO_AUDIO -> copy(referenceVideoAudios = paths)
    SdVideoInputRole.REFERENCE_AUDIO -> copy(referenceAudios = paths)
}

@Composable
private fun ComponentPathsEditor(
    profile: SdVideoFamilyProfile?,
    roles: List<SdVideoComponentRole>,
    paths: SdVideoComponentPaths,
    models: Map<SdVideoComponentRole, List<ModelEntity>>,
    availability: VideoUiAvailability,
    uncondDiffusionModels: List<ModelEntity>,
    ipAdapterModels: List<ModelEntity>,
    onPathsChange: (SdVideoComponentPaths) -> Unit
) {
    Text(stringResource(R.string.video_controls_components), style = MaterialTheme.typography.titleSmall)
    roles.forEach { role ->
        VideoModelPathField(
            label = videoComponentLabel(role),
            value = paths.pathFor(role).orEmpty(),
            models = models[role].orEmpty(),
            enabled = role.cliFlag?.let(availability::isFlagEnabled) ?: true,
            onValueChange = { onPathsChange(paths.withPath(role, it)) }
        )
    }
    if (uncondDiffusionModels.isNotEmpty() || !paths.uncondDiffusionModelPath.isNullOrBlank()) {
        VideoModelPathField(
            label = stringResource(R.string.video_runtime_component_uncond_diffusion_model),
            value = paths.uncondDiffusionModelPath.orEmpty(),
            models = uncondDiffusionModels,
            enabled = availability.isFlagEnabled("--uncond-diffusion-model"),
            onValueChange = { value ->
                onPathsChange(paths.copy(uncondDiffusionModelPath = value.trim().ifBlank { null }))
            }
        )
    }
    if (ipAdapterModels.isNotEmpty() || !paths.ipAdapterPath.isNullOrBlank()) {
        VideoModelPathField(
            label = stringResource(R.string.video_runtime_component_ip_adapter),
            value = paths.ipAdapterPath.orEmpty(),
            models = ipAdapterModels,
            enabled = availability.isFlagEnabled("--ip-adapter"),
            onValueChange = { value ->
                onPathsChange(paths.copy(ipAdapterPath = value.trim().ifBlank { null }))
            }
        )
    }
}

@Composable
private fun DecoderAndPassEditor(
    options: VideoRuntimeOptions,
    availability: VideoUiAvailability,
    onOptionsChange: (VideoRuntimeOptions) -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Switch(
            checked = options.useTae,
            enabled = availability.taeEnabled && availability.isFlagEnabled("--tae"),
            onCheckedChange = { onOptionsChange(options.copy(useTae = it)) }
        )
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.video_controls_decoder_tae))
            Text(
                stringResource(R.string.video_controls_decoder_tae_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
    VideoOptionalNumberField(
        stringResource(R.string.video_controls_seed),
        options.seed.takeIf { it != -1L }?.toString().orEmpty(),
        KeyboardType.Number,
        enabled = availability.isFlagEnabled("--seed")
    ) { onOptionsChange(options.copy(seed = it.toLongOrNull() ?: -1L)) }
    Text(stringResource(R.string.video_controls_high_noise), style = MaterialTheme.typography.titleSmall)
    VideoOptionalNumberField(
        stringResource(R.string.video_controls_high_noise_steps),
        options.highNoiseSteps?.toString().orEmpty(),
        KeyboardType.Number,
        enabled = availability.highNoiseEnabled
    ) { onOptionsChange(options.copy(highNoiseSteps = it.toIntOrNull())) }
    VideoOptionalNumberField(
        stringResource(R.string.video_controls_high_noise_cfg),
        options.highNoiseCfgScale?.toString().orEmpty(),
        KeyboardType.Decimal,
        enabled = availability.highNoiseEnabled && availability.isFlagEnabled("--high-noise-cfg-scale")
    ) { onOptionsChange(options.copy(highNoiseCfgScale = it.toFloatOrNull())) }
    VideoOptionalNumberField(
        stringResource(R.string.video_controls_control_strength),
        options.controlStrength?.toString().orEmpty(),
        KeyboardType.Decimal,
        enabled = availability.isFlagEnabled("--control-strength")
    ) { onOptionsChange(options.copy(controlStrength = it.toFloatOrNull())) }
    VideoOptionalTextField(
        stringResource(R.string.video_controls_tile_overlap),
        options.vaeTileOverlap.toString(),
        KeyboardType.Decimal,
        enabled = availability.vaeTilingEnabled && availability.isFlagEnabled("--vae-tile-overlap")
    ) { onOptionsChange(options.copy(vaeTileOverlap = it.toFloatOrNull() ?: options.vaeTileOverlap)) }
    VideoOptionalTextField(
        stringResource(R.string.video_controls_relative_tile_size),
        options.vaeRelativeTileSize,
        KeyboardType.Text,
        enabled = availability.vaeTilingEnabled && availability.isFlagEnabled("--vae-relative-tile-size"),
        onOptionsChange = { onOptionsChange(options.copy(vaeRelativeTileSize = it)) }
    )
    Text(stringResource(R.string.video_controls_hires), style = MaterialTheme.typography.titleSmall)
    val hires = options.hires
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Checkbox(
            checked = hires.enabled,
            enabled = availability.hiresEnabled,
            onCheckedChange = { onOptionsChange(options.copy(hires = hires.copy(enabled = it))) }
        )
        Text(stringResource(R.string.video_controls_hires))
    }
    if (hires.enabled) {
        VideoOptionalNumberField(stringResource(R.string.video_controls_hires_width), hires.width?.toString().orEmpty(), KeyboardType.Number,
            enabled = availability.hiresEnabled && availability.isFlagEnabled("--hires-width")) {
            onOptionsChange(options.copy(hires = hires.copy(width = it.toIntOrNull())))
        }
        VideoOptionalNumberField(stringResource(R.string.video_controls_hires_height), hires.height?.toString().orEmpty(), KeyboardType.Number,
            enabled = availability.hiresEnabled && availability.isFlagEnabled("--hires-height")) {
            onOptionsChange(options.copy(hires = hires.copy(height = it.toIntOrNull())))
        }
        VideoOptionalNumberField(stringResource(R.string.video_controls_hires_steps), hires.steps?.toString().orEmpty(), KeyboardType.Number,
            enabled = availability.hiresEnabled && availability.isFlagEnabled("--hires-steps")) {
            onOptionsChange(options.copy(hires = hires.copy(steps = it.toIntOrNull())))
        }
        VideoOptionalNumberField(stringResource(R.string.video_controls_hires_scale), hires.scale?.toString().orEmpty(), KeyboardType.Decimal,
            enabled = availability.hiresEnabled && availability.isFlagEnabled("--hires-scale")) {
            onOptionsChange(options.copy(hires = hires.copy(scale = it.toFloatOrNull())))
        }
        VideoOptionalNumberField(stringResource(R.string.video_controls_hires_denoise), hires.denoisingStrength?.toString().orEmpty(), KeyboardType.Decimal,
            enabled = availability.hiresEnabled && availability.isFlagEnabled("--hires-denoising-strength")) {
            onOptionsChange(options.copy(hires = hires.copy(denoisingStrength = it.toFloatOrNull())))
        }
        VideoOptionalNumberField(stringResource(R.string.video_controls_hires_tile), hires.upscaleTileSize?.toString().orEmpty(), KeyboardType.Number,
            enabled = availability.hiresEnabled && availability.isFlagEnabled("--hires-upscale-tile-size")) {
            onOptionsChange(options.copy(hires = hires.copy(upscaleTileSize = it.toIntOrNull())))
        }
        VideoOptionalTextField(stringResource(R.string.video_controls_hires_sigmas), hires.sigmas, KeyboardType.Text,
            enabled = availability.hiresEnabled && availability.isFlagEnabled("--hires-sigmas")) {
            onOptionsChange(options.copy(hires = hires.copy(sigmas = it)))
        }
    }
}

@Composable
private fun OutputEditor(
    options: VideoRuntimeOptions,
    availability: VideoUiAvailability,
    onOptionsChange: (VideoRuntimeOptions) -> Unit
) {
    Text(stringResource(R.string.video_controls_output), style = MaterialTheme.typography.titleSmall)
    Text(stringResource(R.string.video_controls_portable_format), style = MaterialTheme.typography.labelLarge)
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        items(SdVideoOutputFormat.entries, key = { it.name }) { format ->
            FilterChip(
                selected = options.outputFormat == format,
                enabled = availability.workflowEnabled,
                onClick = { onOptionsChange(options.copy(outputFormat = format)) },
                label = { Text(outputLabel(format)) }
            )
        }
    }
    Text(stringResource(R.string.video_controls_native_format), style = MaterialTheme.typography.labelLarge)
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        items(SdVideoNativeOutputFormat.entries, key = { it.name }) { format ->
            FilterChip(
                selected = options.nativeOutputFormat == format,
                enabled = availability.workflowEnabled,
                onClick = { onOptionsChange(options.copy(nativeOutputFormat = format)) },
                label = { Text(outputLabel(format)) }
            )
        }
    }
    Text(stringResource(R.string.video_controls_audio_codec), style = MaterialTheme.typography.labelLarge)
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        item {
            FilterChip(
                selected = options.audioCodec == null,
                enabled = availability.audioEnabled && availability.isFlagEnabled("--audio-vae"),
                onClick = { onOptionsChange(options.copy(audioCodec = null)) },
                label = { Text(stringResource(R.string.video_output_audio_auto)) }
            )
        }
        items(SdVideoAudioCodec.entries, key = { it.name }) { codec ->
            FilterChip(
                selected = options.audioCodec == codec,
                enabled = availability.audioEnabled && availability.isFlagEnabled("--audio-vae"),
                onClick = { onOptionsChange(options.copy(audioCodec = codec)) },
                label = { Text(audioCodecLabel(codec)) }
            )
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Switch(
            checked = options.conversionRecoveryEnabled,
            enabled = availability.workflowEnabled,
            onCheckedChange = { onOptionsChange(options.copy(conversionRecoveryEnabled = it)) }
        )
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.video_controls_conversion_recovery))
    }
    Text(stringResource(R.string.video_controls_prompt_format), style = MaterialTheme.typography.labelLarge)
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        items(SdVideoPromptFormat.entries, key = { it.name }) { format ->
            FilterChip(
                selected = options.promptFormat == format,
                enabled = format != SdVideoPromptFormat.LINGBOT_CAPTION_JSON || availability.lingBotPromptEnabled,
                onClick = { onOptionsChange(options.copy(promptFormat = format)) },
                label = {
                    Text(
                        if (format == SdVideoPromptFormat.LINGBOT_CAPTION_JSON) {
                            stringResource(R.string.video_controls_prompt_lingbot)
                        } else {
                            stringResource(R.string.video_controls_prompt_plain)
                        }
                    )
                }
            )
        }
    }
}

@Composable
private fun VideoLoraEditor(
    models: List<ModelEntity>,
    loras: List<SdLoraSpec>,
    highNoiseLoras: List<SdLoraSpec>,
    onLorasChange: (List<SdLoraSpec>) -> Unit,
    onHighNoiseLorasChange: (List<SdLoraSpec>) -> Unit,
    applyMode: SdLoraApplyMode?,
    onApplyModeChange: (SdLoraApplyMode?) -> Unit,
    availability: VideoUiAvailability,
    profile: SdVideoFamilyProfile?
) {
    Text(
        stringResource(R.string.video_controls_loras),
        modifier = Modifier.walkthroughTarget("video.loras"),
        style = MaterialTheme.typography.titleSmall
    )
    VideoLoraStack(
        stageLabel = stringResource(R.string.video_controls_lora_regular),
        models = models,
        stack = loras,
        highNoiseOnly = false,
        onStackChange = onLorasChange,
        availability = availability
    )
    VideoLoraStack(
        stageLabel = stringResource(R.string.video_controls_lora_high_noise),
        models = models,
        stack = highNoiseLoras,
        highNoiseOnly = true,
        onStackChange = onHighNoiseLorasChange,
        availability = availability,
        stackEnabled = availability.highNoiseEnabled && profile?.supportsHighNoisePass != false
    )
    Text(
        stringResource(R.string.video_controls_lora_low_noise_unavailable),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        item {
            FilterChip(
                selected = applyMode == null,
                enabled = availability.isFlagEnabled("--lora-apply-mode"),
                onClick = { onApplyModeChange(null) },
                label = { Text(stringResource(R.string.video_controls_lora_default)) }
            )
        }
        items(SdLoraApplyMode.entries, key = { it.name }) { mode ->
            FilterChip(
                selected = applyMode == mode,
                enabled = availability.isFlagEnabled("--lora-apply-mode"),
                onClick = { onApplyModeChange(mode) },
                label = { Text(mode.cliName) }
            )
        }
    }
}

@Composable
private fun VideoLoraStack(
    stageLabel: String,
    models: List<ModelEntity>,
    stack: List<SdLoraSpec>,
    highNoiseOnly: Boolean,
    onStackChange: (List<SdLoraSpec>) -> Unit,
    availability: VideoUiAvailability,
    stackEnabled: Boolean = true
) {
    Text(stageLabel, style = MaterialTheme.typography.labelLarge)
    stack.forEachIndexed { index, item ->
        key(index, item.path) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
            ) {
                Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                VideoModelPathField(
                    label = stringResource(R.string.video_controls_component_path),
                    value = item.path,
                    models = models,
                    enabled = availability.isFlagEnabled("--lora-model-dir") && stackEnabled,
                    onValueChange = { value ->
                        onStackChange(stack.replaceAt(index, item.copy(path = value, highNoiseOnly = highNoiseOnly)))
                    }
                )
                if (models.none { it.path == item.path }) {
                    Text(
                        text = stringResource(
                            R.string.video_controls_lora_missing_or_incompatible,
                            item.path.substringAfterLast('/').ifBlank { item.path }
                        ),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Checkbox(
                        checked = item.enabled,
                        enabled = true,
                        onCheckedChange = { enabled -> onStackChange(stack.replaceAt(index, item.copy(enabled = enabled))) }
                    )
                    Text(stringResource(R.string.video_controls_lora_enabled), modifier = Modifier.weight(1f))
                    VideoOptionalNumberField(
                        label = stringResource(R.string.video_controls_lora_strength),
                        value = item.strength.toString(),
                        keyboardType = KeyboardType.Decimal,
                        modifier = Modifier.weight(1f),
                        enabled = availability.isFlagEnabled("--lora-model-dir") && stackEnabled
                    ) { strength ->
                        onStackChange(stack.replaceAt(index, item.copy(strength = strength.toFloatOrNull() ?: item.strength)))
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(
                        onClick = { if (index > 0) onStackChange(stack.swap(index, index - 1)) },
                        enabled = index > 0,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(stringResource(R.string.video_controls_lora_up)) }
                    OutlinedButton(
                        onClick = { if (index < stack.lastIndex) onStackChange(stack.swap(index, index + 1)) },
                        enabled = index < stack.lastIndex,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(stringResource(R.string.video_controls_lora_down)) }
                    TextButton(
                        enabled = true,
                        onClick = { onStackChange(stack.filterIndexed { position, _ -> position != index }) }
                    ) {
                        Text(stringResource(R.string.video_controls_lora_remove))
                    }
                }
                }
            }
        }
    }
    OutlinedButton(
        onClick = {
            val candidate = models.firstOrNull { model -> stack.none { it.path == model.path } }
            if (candidate != null) {
                onStackChange(stack + SdLoraSpec(path = candidate.path, highNoiseOnly = highNoiseOnly))
            }
        },
        enabled = availability.isFlagEnabled("--lora-model-dir") && stackEnabled &&
            models.any { model -> stack.none { it.path == model.path } },
        modifier = Modifier.fillMaxWidth()
    ) { Text(stringResource(R.string.video_controls_lora_add)) }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun VideoModelPathField(
    label: String,
    value: String,
    models: List<ModelEntity>,
    enabled: Boolean = true,
    onValueChange: (String) -> Unit
) {
    var expanded by remember(label) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { if (enabled) expanded = !expanded }
        ) {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.fillMaxWidth().menuAnchor().semantics { contentDescription = label },
                placeholder = { Text(stringResource(R.string.video_controls_custom_path_hint)) },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                enabled = enabled,
                singleLine = true,
                shape = RoundedCornerShape(12.dp)
            )
            ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                models.forEach { model ->
                    DropdownMenuItem(
                        text = { Text(model.filename, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        onClick = {
                            onValueChange(model.path)
                            expanded = false
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun VideoPathTextField(label: String, value: String?, keyboardType: KeyboardType, onValueChange: (String) -> Unit) {
    OutlinedTextField(
        value = value.orEmpty(),
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label) },
        placeholder = { Text(stringResource(R.string.video_controls_custom_path_hint)) },
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        singleLine = true,
        shape = RoundedCornerShape(12.dp)
    )
}

@Composable
private fun VideoReferenceTextField(label: String, values: List<String>, onValuesChange: (List<String>) -> Unit) {
    OutlinedTextField(
        value = values.joinToString("\n"),
        onValueChange = { value -> onValuesChange(value.lines().map(String::trim).filter(String::isNotBlank)) },
        modifier = Modifier.fillMaxWidth().height(92.dp),
        label = { Text(label) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        shape = RoundedCornerShape(12.dp)
    )
}

@Composable
private fun VideoOptionalNumberField(
    label: String,
    value: String,
    keyboardType: KeyboardType,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onValueChange: (String) -> Unit
) = VideoOptionalTextField(label, value, keyboardType, modifier, enabled, onValueChange)

@Composable
private fun VideoOptionalTextField(
    label: String,
    value: String,
    keyboardType: KeyboardType,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onOptionsChange: (String) -> Unit
) {
    var draft by rememberSaveable(label) { mutableStateOf(value) }
    var focused by remember { mutableStateOf(false) }
    LaunchedEffect(value, focused) { if (!focused) draft = value }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it; onOptionsChange(it) },
            modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused }
                    .semantics { contentDescription = label },
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            enabled = enabled,
            singleLine = true,
            shape = RoundedCornerShape(12.dp)
        )
    }
}

private fun videoEditorComponentRoles(
    profile: SdVideoFamilyProfile?, workflow: SdVideoWorkflow, paths: SdVideoComponentPaths
): List<SdVideoComponentRole> {
    val compatible = if (profile == null) SdVideoComponentRole.entries.toSet()
        else profile.requiredRolesFor(workflow).toSet() + profile.optionalComponents
    return SdVideoComponentRole.entries.filter {
        it != SdVideoComponentRole.LORA &&
            (it in compatible || !paths.pathFor(it).isNullOrBlank())
    }
}

private fun SdVideoComponentPaths.withPath(role: SdVideoComponentRole, value: String): SdVideoComponentPaths {
    val path = value.trim().ifBlank { null }
    return when (role) {
        SdVideoComponentRole.DIFFUSION_MODEL -> copy(diffusionModelPath = path)
        SdVideoComponentRole.FULL_MODEL -> copy(fullModelPath = path)
        SdVideoComponentRole.HIGH_NOISE_DIFFUSION_MODEL -> copy(highNoiseDiffusionModelPath = path)
        SdVideoComponentRole.VAE -> copy(vaePath = path)
        SdVideoComponentRole.TAE -> copy(taePath = path)
        SdVideoComponentRole.T5XXL -> copy(t5xxlPath = path)
        SdVideoComponentRole.LLM -> copy(llmPath = path)
        SdVideoComponentRole.LLM_VISION -> copy(llmVisionPath = path)
        SdVideoComponentRole.AUDIO_VAE -> copy(audioVaePath = path)
        SdVideoComponentRole.EMBEDDINGS_CONNECTORS -> copy(embeddingsConnectorsPath = path)
        SdVideoComponentRole.MOTION_MODULE -> copy(motionModulePath = path)
        SdVideoComponentRole.CLIP_VISION -> copy(clipVisionPath = path)
        SdVideoComponentRole.CONTROL_NET -> copy(controlNetPath = path)
        SdVideoComponentRole.LORA -> this
        SdVideoComponentRole.HIRES_UPSCALER -> copy(hiresUpscaler = path)
    }
}

private fun <T> List<T>.replaceAt(index: Int, value: T): List<T> = toMutableList().also { it[index] = value }

private fun <T> List<T>.swap(first: Int, second: Int): List<T> = toMutableList().also {
    val value = it[first]
    it[first] = it[second]
    it[second] = value
}

@Composable
private fun videoFamilyLabel(family: SdVideoFamily): String = stringResource(
    when (family) {
        SdVideoFamily.WAN -> R.string.video_runtime_family_wan
        SdVideoFamily.HUNYUAN_VIDEO -> R.string.video_runtime_family_hunyuan_video
        SdVideoFamily.LINGBOT_VIDEO -> R.string.video_runtime_family_lingbot_video
        SdVideoFamily.LTX_VIDEO -> R.string.video_runtime_family_ltx_video
        SdVideoFamily.MINIMAX_H3 -> R.string.video_runtime_family_minimax_h3
        SdVideoFamily.SVD -> R.string.video_runtime_family_svd
        SdVideoFamily.ANIMATEDIFF -> R.string.video_runtime_family_animatediff
    }
)

@Composable
private fun videoWorkflowLabel(workflow: SdVideoWorkflow): String = stringResource(
    when (workflow) {
        SdVideoWorkflow.TEXT_TO_VIDEO -> R.string.video_runtime_workflow_text_to_video
        SdVideoWorkflow.IMAGE_TO_VIDEO -> R.string.video_runtime_workflow_image_to_video
        SdVideoWorkflow.FIRST_LAST_FRAME -> R.string.video_runtime_workflow_first_last_frame
        SdVideoWorkflow.VIDEO_TO_VIDEO -> R.string.video_runtime_workflow_video_to_video
        SdVideoWorkflow.REFERENCE_TO_VIDEO -> R.string.video_runtime_workflow_reference_to_video
        SdVideoWorkflow.TEXT_TO_AUDIO_VIDEO -> R.string.video_runtime_workflow_text_to_audio_video
        SdVideoWorkflow.IMAGE_TO_AUDIO_VIDEO -> R.string.video_runtime_workflow_image_to_audio_video
        SdVideoWorkflow.FIRST_LAST_TO_AUDIO_VIDEO -> R.string.video_runtime_workflow_first_last_to_audio_video
        SdVideoWorkflow.REFERENCE_TO_AUDIO_VIDEO -> R.string.video_runtime_workflow_reference_to_audio_video
    }
)

private fun videoComponentLabelRes(role: SdVideoComponentRole): Int = when (role) {
    SdVideoComponentRole.DIFFUSION_MODEL -> R.string.video_runtime_component_diffusion_model
    SdVideoComponentRole.FULL_MODEL -> R.string.video_runtime_component_full_model
    SdVideoComponentRole.HIGH_NOISE_DIFFUSION_MODEL -> R.string.video_runtime_component_high_noise_diffusion_model
    SdVideoComponentRole.VAE -> R.string.video_runtime_component_vae
    SdVideoComponentRole.TAE -> R.string.video_runtime_component_tae
    SdVideoComponentRole.T5XXL -> R.string.video_runtime_component_t5xxl
    SdVideoComponentRole.LLM -> R.string.video_runtime_component_llm
    SdVideoComponentRole.LLM_VISION -> R.string.video_runtime_component_llm_vision
    SdVideoComponentRole.AUDIO_VAE -> R.string.video_runtime_component_audio_vae
    SdVideoComponentRole.EMBEDDINGS_CONNECTORS -> R.string.video_runtime_component_embeddings_connectors
    SdVideoComponentRole.MOTION_MODULE -> R.string.video_runtime_component_motion_module
    SdVideoComponentRole.CLIP_VISION -> R.string.video_runtime_component_clip_vision
    SdVideoComponentRole.CONTROL_NET -> R.string.video_runtime_component_control_net
    SdVideoComponentRole.LORA -> R.string.video_controls_loras
    SdVideoComponentRole.HIRES_UPSCALER -> R.string.video_runtime_component_hires_upscaler
}

@Composable
internal fun videoComponentLabel(role: SdVideoComponentRole): String = stringResource(videoComponentLabelRes(role))

internal fun videoComponentLabel(resources: Resources, role: SdVideoComponentRole): String =
    resources.getString(videoComponentLabelRes(role))

private fun videoInputLabelRes(role: SdVideoInputRole): Int = when (role) {
    SdVideoInputRole.INIT_IMAGE -> R.string.video_input_first_image
    SdVideoInputRole.END_IMAGE -> R.string.video_input_last_image
    SdVideoInputRole.CONTROL_IMAGE -> R.string.video_input_control_image
    SdVideoInputRole.CONTROL_VIDEO -> R.string.video_input_control_video
    SdVideoInputRole.REFERENCE_IMAGE -> R.string.video_input_reference_images
    SdVideoInputRole.REFERENCE_VIDEO -> R.string.video_input_reference_videos
    SdVideoInputRole.REFERENCE_VIDEO_AUDIO -> R.string.video_input_reference_video_audio
    SdVideoInputRole.REFERENCE_AUDIO -> R.string.video_input_reference_audio
}

@Composable
internal fun videoInputLabel(role: SdVideoInputRole): String = stringResource(videoInputLabelRes(role))

internal fun videoInputLabel(resources: Resources, role: SdVideoInputRole): String =
    resources.getString(videoInputLabelRes(role))

@Composable
private fun outputLabel(format: SdVideoOutputFormat): String = when (format) {
    SdVideoOutputFormat.MP4 -> stringResource(R.string.video_runtime_output_mp4)
    SdVideoOutputFormat.WEBM -> stringResource(R.string.video_runtime_output_webm)
    SdVideoOutputFormat.AVI -> stringResource(R.string.video_runtime_output_avi)
}

@Composable
private fun outputLabel(format: SdVideoNativeOutputFormat): String = when (format) {
    SdVideoNativeOutputFormat.WEBP -> stringResource(R.string.video_output_animated_webp)
    SdVideoNativeOutputFormat.WEBM -> stringResource(R.string.video_runtime_output_webm)
    SdVideoNativeOutputFormat.AVI -> stringResource(R.string.video_runtime_output_avi)
}

@Composable
private fun audioCodecLabel(codec: SdVideoAudioCodec): String = when (codec) {
    SdVideoAudioCodec.AAC -> stringResource(R.string.video_runtime_audio_aac)
    SdVideoAudioCodec.OPUS -> stringResource(R.string.video_runtime_audio_opus)
    SdVideoAudioCodec.COPY -> stringResource(R.string.video_runtime_audio_copy)
    SdVideoAudioCodec.NONE -> stringResource(R.string.video_runtime_audio_none)
}
