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
import androidx.compose.runtime.saveable.rememberSaveable
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
import com.example.llamadroid.sd.SdComponentCandidate
import com.example.llamadroid.sd.SdComponentFit
import com.example.llamadroid.sd.SdComponentRole
import com.example.llamadroid.sd.SdImg2ImgBlock
import com.example.llamadroid.sd.SdLoraSpec
import com.example.llamadroid.sd.sdMainEmbeddedRoles
import com.example.llamadroid.sd.SdLoraApplyMode
import com.example.llamadroid.sd.SdVideoComponentRole
import com.example.llamadroid.sd.isSdImageMainModel
import com.example.llamadroid.sd.sdVideoComponentCandidates
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
import com.example.llamadroid.ui.components.ComponentCandidatePicker
import com.example.llamadroid.ui.components.rememberSdComponentImporter
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
                SdMasterImageWorkflowCard(
                    state.settings.imageWorkflowMode,
                    remember(state.settings.imageModelPath, state.settings.imageLlmVisionPath, env.installedModels) {
                        sdMasterImageImg2ImgBlock(state.settings, env.installedModels)
                    },
                    vm::update
                )
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

/** Installed files per video component role: family matches first, every other file kept as unverified. */
internal fun videoEditorComponentModels(
    main: ModelEntity?,
    installed: List<ModelEntity>
): Map<SdVideoComponentRole, List<SdComponentCandidate>> =
    SdVideoComponentRole.entries.associateWith { sdVideoComponentCandidates(main, installed, it) }

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
    val mainModel = remember(installed, options.videoComponents) {
        val path = options.videoComponents.diffusionModelPath ?: options.videoComponents.fullModelPath
        installed.firstOrNull { it.path == path }
    }
    val componentModels = remember(installed, mainModel) { videoEditorComponentModels(mainModel, installed) }
    val loraModels = remember(componentModels) {
        componentModels[SdVideoComponentRole.LORA].orEmpty().map { it.model }
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
            uncondDiffusionModels = componentModels[SdVideoComponentRole.DIFFUSION_MODEL].orEmpty().map { it.model },
            ipAdapterModels = installed.filter { it.type == ModelType.SD_IP_ADAPTER }
        )
    }
}

// ---- Images ----

@Composable
internal fun SdMasterImageWorkflowCard(
    workflow: String,
    img2imgBlock: SdImg2ImgBlock?,
    onUpdate: SdMasterSettingsUpdate
) {
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
                    enabled = !(mode == MODE_IMG2IMG && img2imgBlock == SdImg2ImgBlock.NOT_SUPPORTED),
                    label = { Text(stringResource(label), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                )
            }
        }
        img2imgBlock?.let { block ->
            Text(
                text = stringResource(
                    if (block == SdImg2ImgBlock.NOT_SUPPORTED) R.string.media_picker_img2img_not_supported
                    else R.string.media_picker_img2img_needs_projector
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
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
    // Cards that share one main file (SD 1.5 low-end / LCM) are told apart by the last tapped card.
    var appliedBundleId by rememberSaveable { mutableStateOf<String?>(null) }
    val importMain = rememberSdComponentImporter { saved ->
        onUpdate { it.withImageMainModel(saved.path, null, installed + saved) }
    }
    val selectedIndex = remember(cards, settings.imageModelPath, appliedBundleId) {
        val sameMain = cards.indices.filter { cards[it].mainPath != null && cards[it].mainPath == settings.imageModelPath }
        sameMain.firstOrNull { cards[it].bundleId != null && cards[it].bundleId == appliedBundleId } ?: sameMain.firstOrNull()
    }
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
        cards.forEachIndexed { index, card ->
            SdMasterPresetTile(
                title = card.titleRes?.let { stringResource(it) } ?: card.title,
                selected = index == selectedIndex,
                installed = card.installed,
                missingFiles = if (card.mainPath != null) card.missingFiles else 0,
                onClick = {
                    if (!card.installed || card.mainPath == null) {
                        onOpenModelManager(card.bundleId)
                    } else if (index != selectedIndex) {
                        appliedBundleId = card.bundleId
                        onUpdate { applyImagePreset(it, card, installed) }
                    }
                }
            )
        }
        OutlinedButton(
            onClick = { importMain(ModelType.SD_DIFFUSION, null, null) },
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
        ) {
            Text(stringResource(R.string.media_picker_add_main_file), maxLines = 1, overflow = TextOverflow.Ellipsis)
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
        settings.imageLlmVisionPath, settings.imageWorkflowMode, installed) { sdMasterImageMissingRoles(settings, installed) }
    val embedded = remember(settings.imageModelPath, installed) {
        sdMainEmbeddedRoles(installed.firstOrNull { it.path == settings.imageModelPath })
    }
    val context = LocalContext.current
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = stringResource(
                R.string.media_picker_main_contains,
                embedded.takeIf { it.isNotEmpty() }
                    ?.joinToString { sdDistributedComponentRoleLabel(context, it) }
                    ?: stringResource(R.string.media_picker_main_contains_nothing)
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
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
    var importRole by remember { mutableStateOf<SdComponentRole?>(null) }
    val importFile = rememberSdComponentImporter { saved ->
        val role = importRole
        importRole = null
        onUpdate { current ->
            when (role) {
                SdComponentRole.MAIN_MODEL -> current.withImageMainModel(saved.path, null, installed + saved)
                SdComponentRole.LORA -> current.withImageLoras(current.imageLoras() + SdLoraSpec(path = saved.path))
                null -> current
                else -> current.withImageComponentPath(role, saved.path)
            }
        }
    }
    AppAdvancedSection(title = stringResource(R.string.sd_r8_w07_files_title)) {
        ComponentCandidatePicker(
            label = stringResource(R.string.sd_r8_w07_image_title),
            candidates = mains.map { SdComponentCandidate(it, SdComponentFit.MATCH) },
            selectedPath = settings.imageModelPath,
            onSelectedPath = { path ->
                if (path.isNotBlank() && path != settings.imageModelPath) {
                    onUpdate { current -> current.withImageMainModel(path, null, installed) }
                }
            },
            required = true,
            includedInMain = false,
            onAddFile = {
                importRole = SdComponentRole.MAIN_MODEL
                importFile(ModelType.SD_DIFFUSION, null, null)
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
            onLoraApplyModeChange = { mode -> onUpdate { it.copy(imageLoraApplyMode = mode) } },
            onAddFile = { role ->
                importRole = role
                importFile(
                    sdImportTypeFor(role),
                    choices.mainFamily,
                    choices.mainVariant
                )
            }
        )
    }
}
