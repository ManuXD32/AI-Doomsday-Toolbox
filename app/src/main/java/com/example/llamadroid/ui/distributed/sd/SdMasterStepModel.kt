@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.example.llamadroid.ui.distributed.sd

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.llamadroid.R
import com.example.llamadroid.data.SettingsRepository
import com.example.llamadroid.data.binary.BinaryRepository
import com.example.llamadroid.data.db.ModelEntity
import com.example.llamadroid.data.db.ModelType
import com.example.llamadroid.data.db.SdDistributedMasterSettingsEntity
import com.example.llamadroid.sd.SdComponentRole
import com.example.llamadroid.sd.SdLoraApplyMode
import com.example.llamadroid.sd.SdVideoComponentRole
import com.example.llamadroid.sd.isSdImageMainModel
import com.example.llamadroid.sd.isSdVideoMainModel
import com.example.llamadroid.sd.matchesSdVideoFamily
import com.example.llamadroid.sd.toJsonArray
import com.example.llamadroid.service.SdBinaryCapabilities
import com.example.llamadroid.service.VideoRuntimeOptions
import com.example.llamadroid.service.imageLoras
import com.example.llamadroid.service.probeSdBinaryCapabilities
import com.example.llamadroid.service.videoHighNoiseLoras
import com.example.llamadroid.service.videoLoras
import com.example.llamadroid.service.withImageLoras
import com.example.llamadroid.ui.ai.VideoScalarDefaults
import com.example.llamadroid.ui.ai.videoScalarDefaultsFor
import com.example.llamadroid.ui.components.AppAdvancedSection
import com.example.llamadroid.ui.components.AppSectionCard
import com.example.llamadroid.ui.components.VideoModelPicker
import com.example.llamadroid.ui.components.VideoRuntimeOptionsEditor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal typealias SdMasterSettingsUpdate = ((SdDistributedMasterSettingsEntity) -> SdDistributedMasterSettingsEntity) -> Unit

private const val MODE_TXT2IMG = "TXT2IMG"
private const val MODE_IMG2IMG = "IMG2IMG"
private const val MODE_UPSCALE = "UPSCALE"

/**
 * Step 1 of the main-device flow: pick the model. One keyed item per card; video mode shows the
 * shared [VideoModelPicker], image mode the workflow choice and the image preset cards.
 */
internal fun LazyListScope.sdMasterModelStepItems(
    state: SdMasterUiState,
    vm: SdMasterViewModel,
    env: SdMasterEnv
) {
    when (state.mode) {
        SdMasterMode.VIDEO -> {
            item(key = "model_video_picker") {
                SdMasterVideoModelCard(state.videoOptions, env, vm::updateVideoOptions) { defaults ->
                    vm.update { it.withVideoScalarDefaults(defaults) }
                }
            }
            item(key = "model_video_options") {
                SdMasterVideoOptionsCard(state.settings, state.videoOptions, env, vm::update, vm::updateVideoOptions)
            }
        }
        SdMasterMode.IMAGES -> {
            item(key = "model_image_workflow") {
                SdMasterImageWorkflowCard(state.settings.imageWorkflowMode, vm::update)
            }
            if (state.settings.imageWorkflowMode == MODE_UPSCALE) {
                item(key = "model_image_upscaler") {
                    SdMasterUpscalerCard(state.settings, env, vm::update)
                }
            } else {
                item(key = "model_image_presets") {
                    SdMasterImageModelCard(state.settings, env.installedModels, env.onOpenModelManager, vm::update)
                }
                item(key = "model_image_files") {
                    SdMasterImageFilesSection(state.settings, env.installedModels, vm::update)
                }
            }
        }
    }
}

/** Scalar form defaults of a preset (LingBot phone) written into the legacy video columns. */
internal fun SdDistributedMasterSettingsEntity.withVideoScalarDefaults(
    defaults: VideoScalarDefaults
): SdDistributedMasterSettingsEntity = copy(
    frames = defaults.frames.toString(),
    fps = defaults.fps.toString(),
    videoWidth = defaults.width.toString(),
    videoHeight = defaults.height.toString(),
    videoSteps = defaults.steps.toString(),
    videoCfg = defaults.cfgScaleText,
    videoFlowShift = defaults.flowShiftText,
    runtimeThreads = defaults.threads.toString(),
    mmap = defaults.mmap,
    diffusionFa = defaults.diffusionFa,
    videoSampler = defaults.sampler.cliName,
    videoScheduler = defaults.scheduler?.cliName.orEmpty()
)

// ---- Video ----

@Composable
private fun SdMasterVideoModelCard(
    options: VideoRuntimeOptions,
    env: SdMasterEnv,
    onOptionsChange: (VideoRuntimeOptions) -> Unit,
    onScalarDefaults: (VideoScalarDefaults) -> Unit
) {
    AppSectionCard {
        VideoModelPicker(
            options = options,
            installedModels = env.installedModels,
            onOptionsChange = onOptionsChange,
            onOpenModelManager = env.onOpenModelManager,
            onPresetApplied = { card -> videoScalarDefaultsFor(card)?.let(onScalarDefaults) }
        )
    }
}

/** Installed files grouped by the video component role the editor offers them for. */
internal fun videoEditorComponentModels(installed: List<ModelEntity>): Map<SdVideoComponentRole, List<ModelEntity>> {
    fun of(vararg types: ModelType) = installed.filter { it.type in types }
    val mains = of(ModelType.SD_DIFFUSION, ModelType.SD_CHECKPOINT).filter { it.isSdVideoMainModel() }
    return mapOf(
        SdVideoComponentRole.DIFFUSION_MODEL to mains,
        SdVideoComponentRole.FULL_MODEL to mains,
        SdVideoComponentRole.HIGH_NOISE_DIFFUSION_MODEL to mains,
        SdVideoComponentRole.VAE to of(ModelType.SD_VAE),
        SdVideoComponentRole.TAE to of(ModelType.SD_TAE),
        SdVideoComponentRole.T5XXL to of(ModelType.SD_T5XXL),
        SdVideoComponentRole.LLM to of(ModelType.LLM),
        SdVideoComponentRole.LLM_VISION to of(ModelType.VISION_PROJECTOR, ModelType.MMPROJ),
        SdVideoComponentRole.CONTROL_NET to of(ModelType.SD_CONTROLNET),
        SdVideoComponentRole.AUDIO_VAE to of(ModelType.SD_AUDIO_VAE),
        SdVideoComponentRole.EMBEDDINGS_CONNECTORS to of(ModelType.SD_EMBEDDINGS_CONNECTORS),
        SdVideoComponentRole.MOTION_MODULE to of(ModelType.SD_MOTION_MODULE),
        SdVideoComponentRole.HIRES_UPSCALER to of(ModelType.SD_UPSCALER),
        SdVideoComponentRole.CLIP_VISION to of(ModelType.SD_CLIP_VISION)
    )
}

private class SdBinaryProbeState(
    val capabilities: SdBinaryCapabilities?,
    val pending: Boolean,
    val unavailable: Boolean,
    val retry: () -> Unit
)

/** Probes the installed SD binary's flags once per binary choice; same logic the old screen ran. */
@Composable
private fun rememberSdBinaryProbe(): SdBinaryProbeState {
    val context = LocalContext.current
    val settingsRepo = remember { SettingsRepository(context) }
    val selectedBinary by settingsRepo.stableDiffusionNativeBinarySelection.collectAsState()
    val repository = remember { BinaryRepository(context) }
    var capabilities by remember { mutableStateOf<SdBinaryCapabilities?>(null) }
    var pending by remember { mutableStateOf(true) }
    var unavailable by remember { mutableStateOf(false) }
    var request by remember { mutableIntStateOf(0) }
    LaunchedEffect(selectedBinary, request) {
        pending = true
        unavailable = false
        capabilities = null
        val probed = withContext(Dispatchers.IO) {
            try {
                repository.getSdBinary()
                    ?.takeIf { it.exists() && it.isFile }
                    ?.let { probeSdBinaryCapabilities(context, it, repository) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                null
            }
        }
        capabilities = probed
        unavailable = probed == null
        pending = false
    }
    return SdBinaryProbeState(capabilities, pending, unavailable) { request++ }
}

@Composable
private fun SdMasterVideoOptionsCard(
    settings: SdDistributedMasterSettingsEntity,
    options: VideoRuntimeOptions,
    env: SdMasterEnv,
    onUpdate: SdMasterSettingsUpdate,
    onOptionsChange: (VideoRuntimeOptions) -> Unit
) {
    val probe = rememberSdBinaryProbe()
    val installed = env.installedModels
    val componentModels = remember(installed) { videoEditorComponentModels(installed) }
    val loraModels = remember(installed, options.videoFamily, options.videoVariant) {
        val all = installed.filter { it.type == ModelType.SD_LORA }
        options.videoFamily?.let { family -> all.filter { it.matchesSdVideoFamily(family, options.videoVariant) } } ?: all
    }
    AppSectionCard {
        Text(
            text = stringResource(R.string.sd_r8_w07_video_options_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        VideoRuntimeOptionsEditor(
            options = options,
            onOptionsChange = onOptionsChange,
            componentModels = componentModels,
            loraModels = loraModels,
            loras = settings.videoLoras(),
            highNoiseLoras = settings.videoHighNoiseLoras(),
            onLorasChange = { next -> onUpdate { it.copy(videoLorasJson = next.toJsonArray().toString()) } },
            onHighNoiseLorasChange = { next ->
                onUpdate { it.copy(videoHighNoiseLorasJson = next.toJsonArray().toString()) }
            },
            loraApplyMode = SdLoraApplyMode.fromStoredValue(settings.videoLoraApplyMode),
            onLoraApplyModeChange = { mode -> onUpdate { it.copy(videoLoraApplyMode = mode?.cliName.orEmpty()) } },
            binaryCapabilities = probe.capabilities,
            binaryProbePending = probe.pending,
            binaryProbeUnavailable = probe.unavailable,
            onRetryBinaryProbe = probe.retry,
            onOpenBinarySettings = env.onOpenBinarySettings,
            uncondDiffusionModels = componentModels[SdVideoComponentRole.DIFFUSION_MODEL].orEmpty(),
            ipAdapterModels = installed.filter { it.type == ModelType.SD_IP_ADAPTER }
        )
    }
}

// ---- Images ----

@Composable
internal fun SdMasterImageWorkflowCard(workflow: String, onUpdate: SdMasterSettingsUpdate) {
    val choices = listOf(
        MODE_TXT2IMG to R.string.sd_r8_w07_workflow_generate,
        MODE_IMG2IMG to R.string.sd_r8_w07_workflow_img2img,
        MODE_UPSCALE to R.string.sd_r8_w07_workflow_upscale
    )
    AppSectionCard {
        Text(
            text = stringResource(R.string.sd_r8_w07_workflow_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            choices.forEach { (mode, label) ->
                FilterChip(
                    selected = workflow.uppercase() == mode,
                    onClick = { if (workflow != mode) onUpdate { it.copy(imageWorkflowMode = mode) } },
                    label = { Text(stringResource(label), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                )
            }
        }
    }
}

@Composable
private fun SdMasterUpscalerCard(
    settings: SdDistributedMasterSettingsEntity,
    env: SdMasterEnv,
    onUpdate: SdMasterSettingsUpdate
) {
    val upscalers = remember(env.installedModels) { env.installedModels.filter { it.type == ModelType.SD_UPSCALER } }
    AppSectionCard {
        Text(
            text = stringResource(R.string.sd_r8_w07_upscaler_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (upscalers.isEmpty()) {
            Text(
                text = stringResource(R.string.sd_r8_w07_empty_upscalers),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            ModelPicker(
                label = stringResource(R.string.sd_r8_w07_upscaler_title),
                models = upscalers,
                selectedPath = settings.imageUpscalerModelPath,
                onSelectedPath = { path -> onUpdate { it.copy(imageUpscalerModelPath = path) } }
            )
        }
        SdMasterGetModelsButton { env.onOpenModelManager(null) }
    }
}

@Composable
private fun SdMasterGetModelsButton(onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
        Text(stringResource(R.string.sd_r8_w07_get_models), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Image preset cards plus a summary of what the selection filled in; one "Get models" action. */
@Composable
internal fun SdMasterImageModelCard(
    settings: SdDistributedMasterSettingsEntity,
    installed: List<ModelEntity>,
    onOpenModelManager: (bundleId: String?) -> Unit,
    onUpdate: SdMasterSettingsUpdate,
    cards: List<SdMasterImagePresetCard> = remember(installed) { sdMasterImagePresetCards(installed) }
) {
    AppSectionCard {
        Text(
            text = stringResource(R.string.sd_r8_w07_image_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (cards.none { it.mainPath != null }) {
            Text(
                text = stringResource(R.string.sd_r8_w07_empty_images),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            SdMasterGetModelsButton { onOpenModelManager(null) }
        }
        cards.forEach { card ->
            SdMasterPresetTile(
                title = card.titleRes?.let { stringResource(it) } ?: card.title,
                selected = card.mainPath != null && card.mainPath == settings.imageModelPath,
                installed = card.installed,
                missingFiles = if (card.mainPath != null) card.missingFiles else 0,
                onClick = {
                    if (!card.installed || card.mainPath == null) {
                        onOpenModelManager(card.bundleId)
                    } else if (applyImagePreset(settings, card, installed) !== settings) {
                        onUpdate { applyImagePreset(it, card, installed) }
                    }
                }
            )
        }
        if (settings.imageModelPath.isNotBlank()) {
            SdMasterImageSummary(settings, installed, onGetModels = { onOpenModelManager(null) })
        }
    }
}

@Composable
private fun SdMasterPresetTile(
    title: String,
    selected: Boolean,
    installed: Boolean,
    missingFiles: Int,
    onClick: () -> Unit
) {
    val state = when {
        selected -> stringResource(R.string.sd_r8_w07_state_selected)
        installed -> stringResource(R.string.sd_r8_w07_state_ready)
        missingFiles > 0 -> pluralStringResource(R.plurals.sd_r8_w07_files_missing, missingFiles, missingFiles)
        else -> stringResource(R.string.sd_r8_w07_state_not_installed)
    }
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceVariant
        ),
        border = BorderStroke(
            width = if (selected) 2.dp else 1.dp,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
        )
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                text = state,
                style = MaterialTheme.typography.bodySmall,
                color = if (selected || installed) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

private val summaryRoles = listOf(
    SdComponentRole.VAE, SdComponentRole.TAE, SdComponentRole.CLIP_L, SdComponentRole.CLIP_G,
    SdComponentRole.T5XXL, SdComponentRole.LLM, SdComponentRole.LLM_VISION
)

@Composable
private fun SdMasterImageSummary(
    settings: SdDistributedMasterSettingsEntity,
    installed: List<ModelEntity>,
    onGetModels: () -> Unit
) {
    val missing = remember(settings.imageModelPath, settings.imageVaePath, settings.imageTaePath,
        settings.imageClipLPath, settings.imageClipGPath, settings.imageT5xxlPath, settings.imageLlmPath,
        settings.imageLlmVisionPath, installed) { sdMasterImageMissingRoles(settings, installed) }
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        summaryRoles.mapNotNull { role ->
            settings.imageComponentPath(role).takeIf { it.isNotBlank() }?.let { role to it }
        }.forEach { (role, path) ->
            Text(
                text = stringResource(
                    R.string.sd_r8_w07_component_line,
                    sdDistributedComponentRoleLabel(role),
                    path.substringAfterLast('/')
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (missing.isNotEmpty()) {
            Text(
                text = stringResource(
                    R.string.sd_r8_w07_missing_line,
                    missing.map { sdDistributedComponentRoleLabel(it) }.joinToString()
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            SdMasterGetModelsButton(onGetModels)
        }
    }
}

@Composable
private fun SdMasterImageFilesSection(
    settings: SdDistributedMasterSettingsEntity,
    installed: List<ModelEntity>,
    onUpdate: SdMasterSettingsUpdate
) {
    val mains = remember(installed) {
        installed.filter { it.isSdImageMainModel() }
    }
    val choices = remember(settings, installed) { sdMasterImageComponentChoices(settings, installed) }
    AppAdvancedSection(title = stringResource(R.string.sd_r8_w07_files_title)) {
        ModelPicker(
            label = stringResource(R.string.sd_r8_w07_image_title),
            models = mains,
            selectedPath = settings.imageModelPath,
            onSelectedPath = { path ->
                onUpdate { current ->
                    resolveSdMasterImageSelection(path, installed)?.let(current::withImageSelection)
                        ?: current.copy(imageModelPath = path)
                }
            }
        )
        ImageComponentPickerSection(
            mode = settings.imageWorkflowMode.uppercase(),
            components = choices,
            onComponentPathChange = { role, path -> onUpdate { it.withImageComponentPath(role, path) } },
            onControlNetEnabledChange = { enabled ->
                onUpdate { it.copy(imageControlNetEnabled = enabled, imageControlNetPath = if (enabled) it.imageControlNetPath else "") }
            },
            onLoraEnabledChange = { enabled ->
                onUpdate {
                    if (enabled) it.copy(imageLoraEnabled = true)
                    else it.withImageLoras(emptyList()).copy(imageLoraApplyMode = "")
                }
            },
            loraStack = settings.imageLoras(),
            onLoraStackChange = { stack -> onUpdate { it.withImageLoras(stack) } },
            onLoraApplyModeChange = { mode -> onUpdate { it.copy(imageLoraApplyMode = mode) } }
        )
    }
}
