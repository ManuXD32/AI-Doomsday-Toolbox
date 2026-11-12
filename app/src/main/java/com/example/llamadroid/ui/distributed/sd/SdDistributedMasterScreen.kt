@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.example.llamadroid.ui.distributed.sd

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import android.net.TrafficStats
import android.os.Process
import androidx.navigation.NavController
import com.example.llamadroid.R
import com.example.llamadroid.data.SettingsRepository
import com.example.llamadroid.data.binary.BinaryRepository
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.data.db.ModelType
import com.example.llamadroid.data.db.SdDistributedMasterSettingsEntity
import com.example.llamadroid.data.db.SdDistributedTemplateEntity
import com.example.llamadroid.data.db.SdDistributedWorkerEntity
import com.example.llamadroid.data.model.SdCuratedBundleCatalog
import com.example.llamadroid.data.model.installedSdCuratedModel
import com.example.llamadroid.sd.SdComponentRole
import com.example.llamadroid.sd.SdCuratedPresets
import com.example.llamadroid.sd.SdLoraApplyMode
import com.example.llamadroid.sd.SdLoraSpec
import com.example.llamadroid.sd.SdVideoComponentPaths
import com.example.llamadroid.sd.SdVideoComponentRole
import com.example.llamadroid.sd.SdVideoInputs
import com.example.llamadroid.sd.SdVideoWorkflow
import com.example.llamadroid.sd.effectiveSdCompatProfiles
import com.example.llamadroid.sd.isSdImageMainModel
import com.example.llamadroid.sd.isSdVideoMainModel
import com.example.llamadroid.sd.matchesSdVideoFamily
import com.example.llamadroid.sd.resolveSdFamilySpec
import com.example.llamadroid.sd.resolvedSdFamily
import com.example.llamadroid.sd.resolvedSdVideoFamily
import com.example.llamadroid.sd.toJsonArray
import com.example.llamadroid.service.SDConfig
import com.example.llamadroid.service.SDGenerationState
import com.example.llamadroid.service.SDMode
import com.example.llamadroid.service.SDModeStateHolder
import com.example.llamadroid.service.SDUpscaleConfig
import com.example.llamadroid.service.SamplingMethod
import com.example.llamadroid.service.SdBinaryCapabilities
import com.example.llamadroid.service.SdCacheMode
import com.example.llamadroid.service.SdCacheScmPolicy
import android.widget.Toast
import com.example.llamadroid.service.SdDistributedAutoRamScope
import com.example.llamadroid.service.SdDistributedModules
import com.example.llamadroid.service.SdDistributedPlacementMode
import com.example.llamadroid.service.SdDistributedService
import com.example.llamadroid.service.SdDistributedWorkerRuntime
import com.example.llamadroid.service.SdScheduler
import com.example.llamadroid.service.StableDiffusionService
import com.example.llamadroid.service.VideoGenerationConfig
import com.example.llamadroid.service.VideoGenerationMode
import com.example.llamadroid.service.VideoGenerationService
import com.example.llamadroid.service.VideoGenerationState
import com.example.llamadroid.service.VideoGenerationStateHolder
import com.example.llamadroid.service.VideoReuseDraftAdapter
import com.example.llamadroid.service.VideoReuseHandoffStore
import com.example.llamadroid.service.VideoReuseTarget
import com.example.llamadroid.service.VideoRuntimeOptions
import com.example.llamadroid.service.assignmentsByRpc
import com.example.llamadroid.service.SdDistributedRunMonitor
import com.example.llamadroid.service.SdRunKind
import com.example.llamadroid.service.SdTransferExpectation
import com.example.llamadroid.service.buildRamWeightedSdPlacementPlan
import com.example.llamadroid.service.buildSdDistributedPreviewArgs
import com.example.llamadroid.service.imageLoras
import com.example.llamadroid.service.probeSdBinaryCapabilities
import com.example.llamadroid.service.resolveVideoReuseSources
import com.example.llamadroid.service.settingsFromJson
import com.example.llamadroid.service.settingsToJson
import com.example.llamadroid.service.toJsonString
import com.example.llamadroid.service.toPlanningWorkers
import com.example.llamadroid.service.toRamPlannerOptions
import com.example.llamadroid.service.toRuntimeConfig
import com.example.llamadroid.service.toVideoReuseWorkers
import com.example.llamadroid.service.videoHighNoiseLoras
import com.example.llamadroid.service.videoLoras
import com.example.llamadroid.service.videoRuntimeOptionsOrNull
import com.example.llamadroid.ui.components.ResponsiveActionGroup
import com.example.llamadroid.ui.components.SdTensorTypeRulesPicker
import com.example.llamadroid.ui.distributed.common.DistributedNoHelpersState
import com.example.llamadroid.ui.navigation.Screen
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Which workflow a main-device screen configures; the other workflow's settings stay hidden. */
enum class SdMasterMode { IMAGES, VIDEO }

@Composable
fun SdDistributedMasterContent(navController: NavController, mode: SdMasterMode = SdMasterMode.IMAGES) {
    val videoMode = mode == SdMasterMode.VIDEO
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val settingsRepo = remember { SettingsRepository(context) }
    val selectedSdNativeBinary by settingsRepo.stableDiffusionNativeBinarySelection.collectAsState()
    val videoBinaryRepository = remember { BinaryRepository(context) }
    var videoBinaryCapabilities by remember { mutableStateOf<SdBinaryCapabilities?>(null) }
    var videoBinaryProbePending by remember { mutableStateOf(true) }
    var videoBinaryProbeUnavailable by remember { mutableStateOf(false) }
    var videoBinaryProbeRequest by remember { mutableIntStateOf(0) }
    LaunchedEffect(selectedSdNativeBinary, videoBinaryProbeRequest, videoMode) {
        if (!videoMode) return@LaunchedEffect
        videoBinaryProbePending = true
        videoBinaryProbeUnavailable = false
        videoBinaryCapabilities = null
        val capabilities = withContext(Dispatchers.IO) {
            try {
                videoBinaryRepository.getSdBinary()
                    ?.takeIf { it.exists() && it.isFile }
                    ?.let {
                        probeSdBinaryCapabilities(context, it, videoBinaryRepository)
                    }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                null
            }
        }
        videoBinaryCapabilities = capabilities
        videoBinaryProbeUnavailable = capabilities == null
        videoBinaryProbePending = false
    }
    val videoBinaryReady = !videoBinaryProbePending && !videoBinaryProbeUnavailable
    val db = remember { AppDatabase.getDatabase(context) }
    val dao = db.sdDistributedDao()
    val workers by dao.observeWorkersOrdered().collectAsState(initial = emptyList())
    val settingsEntity by dao.observeMasterSettings().collectAsState(initial = null)
    val templates by dao.observeTemplates().collectAsState(initial = emptyList())
    val imageModels by db.modelDao().getModelsByTypes(listOf(ModelType.SD_CHECKPOINT, ModelType.SD_DIFFUSION)).collectAsState(initial = emptyList())
    val upscalerModels by db.modelDao().getModelsByType(ModelType.SD_UPSCALER).collectAsState(initial = emptyList())
    val videoModelsRaw by db.modelDao().getModelsByTypes(
        listOf(ModelType.SD_DIFFUSION, ModelType.SD_CHECKPOINT)
    ).collectAsState(initial = emptyList())
    val vaeModels by db.modelDao().getModelsByType(ModelType.SD_VAE).collectAsState(initial = emptyList())
    val taeModels by db.modelDao().getModelsByType(ModelType.SD_TAE).collectAsState(initial = emptyList())
    val clipLModels by db.modelDao().getModelsByType(ModelType.SD_CLIP_L).collectAsState(initial = emptyList())
    val clipGModels by db.modelDao().getModelsByType(ModelType.SD_CLIP_G).collectAsState(initial = emptyList())
    val clipVisionModels by db.modelDao().getModelsByType(ModelType.SD_CLIP_VISION).collectAsState(initial = emptyList())
    val t5xxlModels by db.modelDao().getModelsByType(ModelType.SD_T5XXL).collectAsState(initial = emptyList())
    val controlNetModels by db.modelDao().getModelsByType(ModelType.SD_CONTROLNET).collectAsState(initial = emptyList())
    val audioVaeModels by db.modelDao().getModelsByType(ModelType.SD_AUDIO_VAE).collectAsState(initial = emptyList())
    val embeddingsConnectorModels by db.modelDao().getModelsByType(ModelType.SD_EMBEDDINGS_CONNECTORS).collectAsState(initial = emptyList())
    val motionModuleModels by db.modelDao().getModelsByType(ModelType.SD_MOTION_MODULE).collectAsState(initial = emptyList())
    val ipAdapterModels by db.modelDao().getModelsByType(ModelType.SD_IP_ADAPTER).collectAsState(initial = emptyList())
    val loraModels by db.modelDao().getModelsByType(ModelType.SD_LORA).collectAsState(initial = emptyList())
    val photoMakerModels by db.modelDao().getModelsByType(ModelType.SD_PHOTOMAKER).collectAsState(initial = emptyList())
    val imageSupportModels by db.modelDao().getModelsByTypes(listOf(ModelType.LLM, ModelType.VISION_PROJECTOR, ModelType.MMPROJ)).collectAsState(initial = emptyList())

    val settings = settingsEntity ?: SdDistributedMasterSettingsEntity()
    val enabledWorkers = workers.filter { it.isEnabled }
    val imageMainModels = remember(imageModels) { imageModels.filter { it.isSdImageMainModel() } }
    val videoModels = remember(videoModelsRaw) { videoModelsRaw.filter { it.isSdVideoMainModel() } }
    val videoComponentModels = remember(
        videoModels,
        vaeModels,
        taeModels,
        t5xxlModels,
        imageSupportModels,
        controlNetModels,
        audioVaeModels,
        embeddingsConnectorModels,
        motionModuleModels,
        clipVisionModels
    ) {
        mapOf(
            SdVideoComponentRole.DIFFUSION_MODEL to videoModels,
            SdVideoComponentRole.FULL_MODEL to videoModels,
            SdVideoComponentRole.HIGH_NOISE_DIFFUSION_MODEL to videoModels,
            SdVideoComponentRole.VAE to vaeModels,
            SdVideoComponentRole.TAE to taeModels,
            SdVideoComponentRole.T5XXL to t5xxlModels,
            SdVideoComponentRole.LLM to imageSupportModels.filter { it.type == ModelType.LLM },
            SdVideoComponentRole.LLM_VISION to imageSupportModels.filter {
                it.type == ModelType.VISION_PROJECTOR || it.type == ModelType.MMPROJ
            },
            SdVideoComponentRole.CONTROL_NET to controlNetModels,
            SdVideoComponentRole.AUDIO_VAE to audioVaeModels,
            SdVideoComponentRole.EMBEDDINGS_CONNECTORS to embeddingsConnectorModels,
            SdVideoComponentRole.MOTION_MODULE to motionModuleModels,
            SdVideoComponentRole.HIRES_UPSCALER to upscalerModels,
            SdVideoComponentRole.CLIP_VISION to clipVisionModels
        )
    }
    val lingBotBundle = remember { SdCuratedBundleCatalog.byId("lingbot-phone") }
    val lingBotInstalledModels = remember(videoModels, taeModels, imageSupportModels) {
        lingBotBundle?.let { bundle ->
            val installed = videoModels + taeModels + imageSupportModels
            bundle.files.mapNotNull { file -> file.installedSdCuratedModel(bundle, installed) }
        }.orEmpty()
    }
    val lingBotReady = lingBotBundle != null && lingBotInstalledModels.size == lingBotBundle.files.size

    var hostDraft by remember { mutableStateOf("") }
    var portDraft by remember { mutableStateOf(SdDistributedService.RPC_DEFAULT_PORT.toString()) }
    var nameDraft by remember { mutableStateOf("") }
    var ramDraft by remember { mutableStateOf("4096") }
    var threadsDraft by remember { mutableStateOf("4") }
    var backendDraft by remember { mutableStateOf("") }
    var editingWorkerId by remember { mutableStateOf<Long?>(null) }
    var showAddForm by rememberSaveable { mutableStateOf(false) }
    var templateNameDraft by remember { mutableStateOf("") }

    var enabled by remember { mutableStateOf(settings.enabled) }
    var placementMode by remember { mutableStateOf(settings.placementMode.toSdPlacementMode()) }
    var backendSpec by remember { mutableStateOf(settings.backendSpec) }
    var paramsBackend by remember { mutableStateOf(settings.paramsBackendSpec) }
    var autoFit by remember { mutableStateOf(settings.autoFit) }
    var autoRamScope by remember { mutableStateOf(SdDistributedAutoRamScope.fromStoredValue(settings.autoRamScope)) }
    var maxVramEnabled by remember { mutableStateOf(settings.maxVramEnabled) }
    var maxVram by remember { mutableStateOf(settings.maxVramSpec) }
    var customFlags by remember { mutableStateOf(settings.customFlags) }

    var imagePrompt by remember { mutableStateOf(settings.imagePrompt.ifBlank { settings.prompt }) }
    var imageNegativePrompt by remember { mutableStateOf(settings.imageNegativePrompt.ifBlank { settings.negativePrompt }) }
    var imageWidth by remember { mutableStateOf(settings.imageWidth) }
    var imageHeight by remember { mutableStateOf(settings.imageHeight) }
    var imageSteps by remember { mutableStateOf(settings.imageSteps) }
    var imageCfg by remember { mutableStateOf(settings.imageCfg) }
    var imageSeed by remember { mutableStateOf(settings.imageSeed) }
    var imageSampler by remember { mutableStateOf(settings.imageSampler) }
    var imageScheduler by remember { mutableStateOf(settings.imageScheduler) }
    var imageFlowShift by remember { mutableStateOf(settings.imageFlowShift) }
    var videoPrompt by remember { mutableStateOf(settings.videoPrompt.ifBlank { settings.prompt }) }
    var videoNegativePrompt by remember { mutableStateOf(settings.videoNegativePrompt.ifBlank { settings.negativePrompt }) }
    var videoWidth by remember { mutableStateOf(settings.videoWidth) }
    var videoHeight by remember { mutableStateOf(settings.videoHeight) }
    var videoSteps by remember { mutableStateOf(settings.videoSteps) }
    var videoCfg by remember { mutableStateOf(settings.videoCfg) }
    var videoSeed by remember { mutableStateOf(settings.videoSeed) }
    var videoSampler by remember { mutableStateOf(settings.videoSampler) }
    var videoScheduler by remember { mutableStateOf(settings.videoScheduler) }
    var videoFlowShift by remember { mutableStateOf(settings.videoFlowShift) }
    var batch by remember { mutableStateOf(settings.batchCount) }
    var clipSkip by remember { mutableStateOf(settings.clipSkip) }
    var strength by remember { mutableStateOf(settings.strength) }
    var frames by remember { mutableStateOf(settings.frames) }
    var fps by remember { mutableStateOf(settings.fps) }
    var runtimeThreads by remember { mutableStateOf(settings.runtimeThreads) }
    var mmap by remember { mutableStateOf(settings.mmap) }
    var diffusionFa by remember { mutableStateOf(settings.diffusionFa) }
    var vaeTiling by remember { mutableStateOf(settings.vaeTiling) }
    var vaeTileSize by remember { mutableStateOf(settings.vaeTileSize) }
    var vaeTileOverlap by remember { mutableStateOf(settings.vaeTileOverlap) }
    var quantization by remember { mutableStateOf(settings.quantization) }
    var tensorRules by remember { mutableStateOf(settings.tensorRules) }
    var loraStrength by remember { mutableStateOf(settings.loraStrength) }
    var controlStrength by remember { mutableStateOf(settings.controlStrength) }
    var cacheMode by remember { mutableStateOf(settings.cacheMode) }
    var cacheOption by remember { mutableStateOf(settings.cacheOption) }
    var scmMask by remember { mutableStateOf(settings.scmMask) }
    var scmPolicy by remember { mutableStateOf(settings.scmPolicy) }
    var masterContributes by remember { mutableStateOf(settings.masterContributes) }
    var masterDisplayName by remember { mutableStateOf(settings.masterDisplayName) }
    var masterRamDraft by remember { mutableStateOf(settings.masterRamMB.toString()) }
    var masterThreadsDraft by remember { mutableStateOf(settings.masterThreads.toString()) }
    var masterBackendDevice by remember { mutableStateOf(settings.masterBackendDevice) }
    var masterAllowedModules by remember { mutableStateOf(SdDistributedModules.normalizeCsv(settings.masterAllowedModules)) }
    var masterDiffusionShareDraft by remember { mutableStateOf(settings.masterDiffusionSharePercent) }
    var imageWorkflowMode by remember { mutableStateOf(settings.imageWorkflowMode) }
    var imageModelPath by remember { mutableStateOf(settings.imageModelPath) }
    var imageUpscalerModelPath by remember { mutableStateOf(settings.imageUpscalerModelPath) }
    var imageInputPath by remember { mutableStateOf(settings.imageInputPath) }
    var imageVaePath by remember { mutableStateOf(settings.imageVaePath) }
    var imageTaePath by remember { mutableStateOf(settings.imageTaePath) }
    var imageClipLPath by remember { mutableStateOf(settings.imageClipLPath) }
    var imageClipGPath by remember { mutableStateOf(settings.imageClipGPath) }
    var imageT5xxlPath by remember { mutableStateOf(settings.imageT5xxlPath) }
    var imageLlmPath by remember { mutableStateOf(settings.imageLlmPath) }
    var imageLlmVisionPath by remember { mutableStateOf(settings.imageLlmVisionPath) }
    var imagePhotoMakerPath by remember { mutableStateOf(settings.imagePhotoMakerPath) }
    var imageControlNetEnabled by remember { mutableStateOf(settings.imageControlNetEnabled) }
    var imageControlNetPath by remember { mutableStateOf(settings.imageControlNetPath) }
    var imageLoraEnabled by remember { mutableStateOf(settings.imageLoraEnabled) }
    var imageLoraPath by remember { mutableStateOf(settings.imageLoraPath) }
    var imageLoraStack by remember { mutableStateOf(settings.imageLoras()) }
    var imageLoraApplyMode by remember { mutableStateOf(settings.imageLoraApplyMode) }
    var imageCustomFlags by remember { mutableStateOf(settings.imageCustomFlags) }
    var videoWorkflowMode by remember { mutableStateOf(settings.videoWorkflowMode) }
    var videoModelPath by remember { mutableStateOf(settings.videoModelPath) }
    var videoInputPath by remember { mutableStateOf(settings.videoInputPath) }
    var videoUseVae by remember { mutableStateOf(settings.videoUseVae) }
    var videoVaePath by remember { mutableStateOf(settings.videoVaePath) }
    var videoUseT5xxl by remember { mutableStateOf(settings.videoUseT5xxl) }
    var videoT5xxlPath by remember { mutableStateOf(settings.videoT5xxlPath) }
    var videoLoraStack by remember { mutableStateOf(settings.videoLoras()) }
    var videoHighNoiseLoraStack by remember { mutableStateOf(settings.videoHighNoiseLoras()) }
    var videoLoraApplyMode by remember { mutableStateOf(settings.videoLoraApplyMode) }
    var videoCustomFlags by remember { mutableStateOf(settings.videoCustomFlags) }
    var videoRuntimeOptions by remember(settings.videoAdvancedJson) {
        mutableStateOf(
            settings.videoRuntimeOptionsOrNull()
                ?: VideoRuntimeOptions(
                    workflow = if (settings.videoWorkflowMode == "IMG2VID") {
                        SdVideoWorkflow.IMAGE_TO_VIDEO
                    } else {
                        SdVideoWorkflow.TEXT_TO_VIDEO
                    },
                    videoComponents = SdVideoComponentPaths(
                        diffusionModelPath = settings.videoModelPath.ifBlank { null },
                        vaePath = settings.videoVaePath.ifBlank { null },
                        t5xxlPath = settings.videoT5xxlPath.ifBlank { null }
                    ),
                    videoInputs = SdVideoInputs(settings.videoInputPath.ifBlank { null }),
                    seed = settings.videoSeed.toLongOrNull() ?: -1L,
                    useTae = false
                )
        )
    }
    val selectedVideoFamilyInfo = videoModels
        .firstOrNull { it.path == videoModelPath }
        ?.resolvedSdVideoFamily()
    val videoLoraModels = remember(loraModels, selectedVideoFamilyInfo) {
        val (family, variant) = selectedVideoFamilyInfo ?: (null to null)
        family?.let { selectedFamily ->
            loraModels.filter { it.matchesSdVideoFamily(selectedFamily, variant) }
        } ?: loraModels
    }
    var launchError by remember { mutableStateOf<String?>(null) }
    val imageInputPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            copyDistributedInputImageToCache(context, uri, "sd_dist_image_input").fold(
                onSuccess = {
                    imageInputPath = it
                    launchError = null
                },
                onFailure = { launchError = it.message ?: resources.getString(R.string.sd_dist_input_image_import_failed) }
            )
        }
    }

    val activeImageMode = imageWorkflowMode.toSdMasterImageMode()
    val imageGenerationState by remember(activeImageMode) {
        SDModeStateHolder.getDistributedForMode(activeImageMode).state
    }.collectAsState()
    val imageWorkRunning = imageGenerationState is SDGenerationState.Generating
    val activeVideoMode = videoWorkflowMode.toSdMasterVideoMode()
    val videoGenerationState by remember(activeVideoMode) {
        VideoGenerationStateHolder.getForMode(activeVideoMode, useDistributedStateHolder = true).state
    }.collectAsState()
    val videoWorkRunning = when (videoGenerationState) {
        is VideoGenerationState.Generating,
        is VideoGenerationState.Converting,
        is VideoGenerationState.Copying -> true
        else -> false
    }

    var devicesExpanded by remember { mutableStateOf(settings.devicesExpanded) }
    var plannerExpanded by remember { mutableStateOf(settings.plannerExpanded) }
    var generationExpanded by remember { mutableStateOf(settings.generationExpanded) }
    var imageExpanded by remember { mutableStateOf(settings.imageExpanded) }
    var videoExpanded by remember { mutableStateOf(settings.videoExpanded) }
    var runtimeExpanded by remember { mutableStateOf(settings.runtimeExpanded) }
    var adaptersExpanded by remember { mutableStateOf(settings.adaptersExpanded) }
    var expertExpanded by remember { mutableStateOf(settings.expertExpanded) }
    var videoReuseWarning by remember { mutableStateOf<String?>(null) }

    fun localizedVideoReuseWarnings(warnings: List<com.example.llamadroid.service.VideoReuseWarning>): String? {
        if (warnings.isEmpty()) return null
        val spanish = resources.configuration.locales[0]?.language.equals("es", ignoreCase = true)
        return warnings.joinToString(separator = "\n") { it.message(spanish) }
    }

    fun applyDistributedVideoReuse(
        restored: SdDistributedMasterSettingsEntity,
        warnings: List<com.example.llamadroid.service.VideoReuseWarning>
    ) {
        enabled = restored.enabled
        placementMode = restored.placementMode.toSdPlacementMode()
        backendSpec = restored.backendSpec
        paramsBackend = restored.paramsBackendSpec
        autoFit = restored.autoFit
        autoRamScope = SdDistributedAutoRamScope.fromStoredValue(restored.autoRamScope)
        maxVramEnabled = restored.maxVramEnabled
        maxVram = restored.maxVramSpec
        customFlags = restored.customFlags
        videoPrompt = restored.videoPrompt
        videoNegativePrompt = restored.videoNegativePrompt
        videoWidth = restored.videoWidth
        videoHeight = restored.videoHeight
        videoSteps = restored.videoSteps
        videoCfg = restored.videoCfg
        videoSeed = restored.videoSeed
        videoSampler = restored.videoSampler
        videoScheduler = restored.videoScheduler
        videoFlowShift = restored.videoFlowShift
        frames = restored.frames
        fps = restored.fps
        runtimeThreads = restored.runtimeThreads
        mmap = restored.mmap
        diffusionFa = restored.diffusionFa
        vaeTiling = restored.vaeTiling
        vaeTileSize = restored.vaeTileSize
        vaeTileOverlap = restored.vaeTileOverlap
        loraStrength = restored.loraStrength
        controlStrength = restored.controlStrength
        cacheMode = restored.cacheMode
        cacheOption = restored.cacheOption
        scmMask = restored.scmMask
        scmPolicy = restored.scmPolicy
        videoWorkflowMode = restored.videoWorkflowMode
        videoModelPath = restored.videoModelPath
        videoInputPath = restored.videoInputPath
        videoUseVae = restored.videoUseVae
        videoVaePath = restored.videoVaePath
        videoUseT5xxl = restored.videoUseT5xxl
        videoT5xxlPath = restored.videoT5xxlPath
        videoLoraStack = restored.videoLoras()
        videoHighNoiseLoraStack = restored.videoHighNoiseLoras()
        videoLoraApplyMode = restored.videoLoraApplyMode
        videoCustomFlags = restored.videoCustomFlags
        videoRuntimeOptions = restored.videoRuntimeOptionsOrNull() ?: VideoRuntimeOptions(
            workflow = if (restored.videoWorkflowMode == "IMG2VID") {
                SdVideoWorkflow.IMAGE_TO_VIDEO
            } else {
                SdVideoWorkflow.TEXT_TO_VIDEO
            },
            videoComponents = SdVideoComponentPaths(
                diffusionModelPath = restored.videoModelPath.ifBlank { null },
                vaePath = restored.videoVaePath.ifBlank { null },
                t5xxlPath = restored.videoT5xxlPath.ifBlank { null }
            ),
            videoInputs = SdVideoInputs(restored.videoInputPath.ifBlank { null }),
            seed = restored.videoSeed.toLongOrNull() ?: -1L,
            useTae = false
        )
        generationExpanded = true
        videoExpanded = true
        runtimeExpanded = true
        adaptersExpanded = true
        launchError = null
        videoReuseWarning = localizedVideoReuseWarnings(warnings)
    }

    LaunchedEffect(settingsEntity?.updatedAt) {
        val fresh = settingsEntity ?: return@LaunchedEffect
        enabled = fresh.enabled
        placementMode = fresh.placementMode.toSdPlacementMode()
        backendSpec = fresh.backendSpec
        paramsBackend = fresh.paramsBackendSpec
        autoFit = fresh.autoFit
        autoRamScope = SdDistributedAutoRamScope.fromStoredValue(fresh.autoRamScope)
        maxVramEnabled = fresh.maxVramEnabled
        maxVram = fresh.maxVramSpec
        customFlags = fresh.customFlags
        imagePrompt = fresh.imagePrompt.ifBlank { fresh.prompt }
        imageNegativePrompt = fresh.imageNegativePrompt.ifBlank { fresh.negativePrompt }
        imageWidth = fresh.imageWidth
        imageHeight = fresh.imageHeight
        imageSteps = fresh.imageSteps
        imageCfg = fresh.imageCfg
        imageSeed = fresh.imageSeed
        imageSampler = fresh.imageSampler
        imageScheduler = fresh.imageScheduler
        imageFlowShift = fresh.imageFlowShift
        videoPrompt = fresh.videoPrompt.ifBlank { fresh.prompt }
        videoNegativePrompt = fresh.videoNegativePrompt.ifBlank { fresh.negativePrompt }
        videoWidth = fresh.videoWidth
        videoHeight = fresh.videoHeight
        videoSteps = fresh.videoSteps
        videoCfg = fresh.videoCfg
        videoSeed = fresh.videoSeed
        videoSampler = fresh.videoSampler
        videoScheduler = fresh.videoScheduler
        videoFlowShift = fresh.videoFlowShift
        batch = fresh.batchCount
        clipSkip = fresh.clipSkip
        strength = fresh.strength
        frames = fresh.frames
        fps = fresh.fps
        runtimeThreads = fresh.runtimeThreads
        mmap = fresh.mmap
        diffusionFa = fresh.diffusionFa
        vaeTiling = fresh.vaeTiling
        vaeTileSize = fresh.vaeTileSize
        vaeTileOverlap = fresh.vaeTileOverlap
        quantization = fresh.quantization
        tensorRules = fresh.tensorRules
        loraStrength = fresh.loraStrength
        controlStrength = fresh.controlStrength
        cacheMode = fresh.cacheMode
        cacheOption = fresh.cacheOption
        scmMask = fresh.scmMask
        scmPolicy = fresh.scmPolicy
        masterContributes = fresh.masterContributes
        masterDisplayName = fresh.masterDisplayName
        masterRamDraft = fresh.masterRamMB.toString()
        masterThreadsDraft = fresh.masterThreads.toString()
        masterBackendDevice = fresh.masterBackendDevice
        masterAllowedModules = SdDistributedModules.normalizeCsv(fresh.masterAllowedModules)
        masterDiffusionShareDraft = fresh.masterDiffusionSharePercent
        imageWorkflowMode = fresh.imageWorkflowMode
        imageModelPath = fresh.imageModelPath
        imageUpscalerModelPath = fresh.imageUpscalerModelPath
        imageInputPath = fresh.imageInputPath
        imageVaePath = fresh.imageVaePath
        imageTaePath = fresh.imageTaePath
        imageClipLPath = fresh.imageClipLPath
        imageClipGPath = fresh.imageClipGPath
        imageT5xxlPath = fresh.imageT5xxlPath
        imageLlmPath = fresh.imageLlmPath
        imageLlmVisionPath = fresh.imageLlmVisionPath
        imagePhotoMakerPath = fresh.imagePhotoMakerPath
        imageControlNetEnabled = fresh.imageControlNetEnabled
        imageControlNetPath = fresh.imageControlNetPath
        imageLoraEnabled = fresh.imageLoraEnabled
        imageLoraPath = fresh.imageLoraPath
        imageLoraStack = fresh.imageLoras()
        imageLoraApplyMode = fresh.imageLoraApplyMode
        imageCustomFlags = fresh.imageCustomFlags
        videoWorkflowMode = fresh.videoWorkflowMode
        videoModelPath = fresh.videoModelPath
        videoInputPath = fresh.videoInputPath
        videoUseVae = fresh.videoUseVae
        videoVaePath = fresh.videoVaePath
        videoUseT5xxl = fresh.videoUseT5xxl
        videoT5xxlPath = fresh.videoT5xxlPath
        videoLoraStack = fresh.videoLoras()
        videoHighNoiseLoraStack = fresh.videoHighNoiseLoras()
        videoLoraApplyMode = fresh.videoLoraApplyMode
        videoCustomFlags = fresh.videoCustomFlags
        videoRuntimeOptions = fresh.videoRuntimeOptionsOrNull()
            ?: VideoRuntimeOptions(
                workflow = if (fresh.videoWorkflowMode == "IMG2VID") {
                    SdVideoWorkflow.IMAGE_TO_VIDEO
                } else {
                    SdVideoWorkflow.TEXT_TO_VIDEO
                },
                videoComponents = SdVideoComponentPaths(
                    diffusionModelPath = fresh.videoModelPath.ifBlank { null },
                    vaePath = fresh.videoVaePath.ifBlank { null },
                    t5xxlPath = fresh.videoT5xxlPath.ifBlank { null }
                ),
                    videoInputs = SdVideoInputs(fresh.videoInputPath.ifBlank { null }),
                    seed = fresh.videoSeed.toLongOrNull() ?: -1L,
                    useTae = false
                )
        devicesExpanded = fresh.devicesExpanded
        plannerExpanded = fresh.plannerExpanded
        generationExpanded = fresh.generationExpanded
        imageExpanded = fresh.imageExpanded
        videoExpanded = fresh.videoExpanded
        runtimeExpanded = fresh.runtimeExpanded
        adaptersExpanded = fresh.adaptersExpanded
        expertExpanded = fresh.expertExpanded
    }

    // Consume the durable handoff independently of the settings observer. A
    // first visit has no row yet, so waiting for settingsEntity would leave a
    // distributed reuse request unapplied. The handoff remains on disk until
    // the restored settings have been persisted successfully.
    LaunchedEffect(Unit) {
        val handoff = withContext(Dispatchers.IO) { VideoReuseHandoffStore.peek(context) }
        if (!videoMode || handoff?.target != VideoReuseTarget.DISTRIBUTED) return@LaunchedEffect

        try {
            val (baseSettings, currentWorkers) = withContext(Dispatchers.IO) {
                (dao.getMasterSettings() ?: SdDistributedMasterSettingsEntity()) to
                    dao.getEnabledWorkersOnce()
            }
            val readResult = withContext(Dispatchers.IO) {
                VideoReuseHandoffStore.readPayload(context, handoff)
            }
            val payload = readResult?.payload
            if (payload == null) {
                videoReuseWarning = localizedVideoReuseWarnings(
                    readResult?.warnings.orEmpty()
                ) ?: resources.getString(R.string.video_detail_reuse_failed)
                return@LaunchedEffect
            }

            val verifiedSources = resolveVideoReuseSources(context, payload)
            val restored = VideoReuseDraftAdapter.toDistributedSettings(
                payload = payload,
                baseSettings = baseSettings,
                currentWorkers = currentWorkers.toVideoReuseWorkers(),
                verifiedSources = verifiedSources
            )
            // Persist only the editable distributed draft. This does not start
            // or reconfigure a running service, and it avoids saveDraftSettings
            // mutating live runtime/worker state during navigation.
            val persistedId = withContext(Dispatchers.IO) {
                dao.upsertMasterSettings(restored.settings)
            }
            check(persistedId > 0L) { "distributed video reuse draft was not persisted" }
            applyDistributedVideoReuse(restored.settings, restored.warnings)
            withContext(Dispatchers.IO) { VideoReuseHandoffStore.complete(context, handoff) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            videoReuseWarning = resources.getString(R.string.video_detail_reuse_failed)
            // Keep the handoff so a transient resolver/DAO failure can retry on
            // the next visit instead of silently losing the user's selection.
        }
    }

    val draftSettings = settings.copy(
        updatedAt = System.currentTimeMillis(),
        enabled = enabled,
        placementMode = placementMode.name,
        backendSpec = backendSpec,
        paramsBackendSpec = paramsBackend,
        autoFit = autoFit,
        autoRamScope = autoRamScope.name,
        maxVramEnabled = maxVramEnabled,
        maxVramSpec = maxVram,
        splitMode = "layer",
        customFlags = customFlags,
        prompt = imagePrompt,
        negativePrompt = imageNegativePrompt,
        dimensions = "${imageWidth.ifBlank { "512" }} x ${imageHeight.ifBlank { "512" }}",
        steps = imageSteps,
        cfg = imageCfg,
        seed = imageSeed,
        sampler = imageSampler,
        scheduler = imageScheduler,
        imagePrompt = imagePrompt,
        imageNegativePrompt = imageNegativePrompt,
        imageWidth = imageWidth,
        imageHeight = imageHeight,
        imageSteps = imageSteps,
        imageCfg = imageCfg,
        imageSeed = imageSeed,
        imageSampler = imageSampler,
        imageScheduler = imageScheduler,
        imageFlowShift = imageFlowShift,
        videoPrompt = videoPrompt,
        videoNegativePrompt = videoNegativePrompt,
        videoWidth = videoWidth,
        videoHeight = videoHeight,
        videoSteps = videoSteps,
        videoCfg = videoCfg,
        videoSeed = videoSeed,
        videoSampler = videoSampler,
        videoScheduler = videoScheduler,
        videoFlowShift = videoFlowShift,
        batchCount = batch,
        clipSkip = clipSkip,
        strength = strength,
        frames = frames,
        fps = fps,
        runtimeThreads = runtimeThreads,
        mmap = mmap,
        diffusionFa = diffusionFa,
        vaeTiling = vaeTiling,
        vaeTileSize = vaeTileSize,
        vaeTileOverlap = vaeTileOverlap,
        flowShift = imageFlowShift,
        quantization = quantization,
        tensorRules = tensorRules,
        loraStrength = loraStrength,
        controlStrength = controlStrength,
        cacheMode = cacheMode,
        cacheOption = cacheOption,
        scmMask = scmMask,
        scmPolicy = scmPolicy,
        masterContributes = masterContributes,
        masterDisplayName = masterDisplayName.ifBlank { stringResource(R.string.sd_dist_this_device) },
        masterRamMB = masterRamDraft.toIntOrNull() ?: 4096,
        masterThreads = masterThreadsDraft.toIntOrNull() ?: 4,
        masterBackendDevice = masterBackendDevice.ifBlank { "cpu" },
        masterAllowedModules = SdDistributedModules.toCsv(masterAllowedModules),
        masterDiffusionSharePercent = masterDiffusionShareDraft.filter(Char::isDigit).take(2),
        imageWorkflowMode = imageWorkflowMode,
        imageModelPath = imageModelPath,
        imageUpscalerModelPath = imageUpscalerModelPath,
        imageInputPath = imageInputPath,
        imageVaePath = imageVaePath,
        imageTaePath = imageTaePath,
        imageClipLPath = imageClipLPath,
        imageClipGPath = imageClipGPath,
        imageT5xxlPath = imageT5xxlPath,
        imageLlmPath = imageLlmPath,
        imageLlmVisionPath = imageLlmVisionPath,
        imagePhotoMakerPath = imagePhotoMakerPath,
        imageControlNetEnabled = imageControlNetEnabled,
        imageControlNetPath = imageControlNetPath,
        imageLoraEnabled = imageLoraEnabled,
        imageLoraPath = imageLoraStack.firstOrNull()?.path.orEmpty(),
        imageLoraApplyMode = imageLoraApplyMode,
        imageLorasJson = if (imageLoraEnabled) imageLoraStack.toJsonArray().toString() else "[]",
        imageCustomFlags = imageCustomFlags,
        videoWorkflowMode = videoWorkflowMode,
        videoModelPath = videoModelPath,
        videoInputPath = videoInputPath,
        videoUseVae = videoUseVae,
        videoVaePath = videoVaePath,
        videoUseT5xxl = videoUseT5xxl,
        videoT5xxlPath = videoT5xxlPath,
        videoLorasJson = videoLoraStack.toJsonArray().toString(),
        videoHighNoiseLorasJson = videoHighNoiseLoraStack.toJsonArray().toString(),
        videoLoraApplyMode = videoLoraApplyMode,
        videoCustomFlags = videoCustomFlags,
        videoAdvancedJson = videoRuntimeOptions.toJsonString(),
        devicesExpanded = devicesExpanded,
        plannerExpanded = plannerExpanded,
        generationExpanded = generationExpanded,
        imageExpanded = imageExpanded,
        videoExpanded = videoExpanded,
        runtimeExpanded = runtimeExpanded,
        adaptersExpanded = adaptersExpanded,
        expertExpanded = expertExpanded
    )

    val planningWorkers = draftSettings.toPlanningWorkers(enabledWorkers)
    val autoRamPlan = buildRamWeightedSdPlacementPlan(planningWorkers, draftSettings.toRamPlannerOptions())
    val assignmentByRpc = assignmentsByRpc(autoRamPlan)
    val runtimeConfig = draftSettings.toRuntimeConfig(autoRamPlan)
    val preview = buildSdDistributedPreviewArgs(runtimeConfig).joinToString(" ")
    val imageTemplates = remember(templates) { templates.filter { it.workflowType == SdDistributedTemplateWorkflow.IMAGE.name } }
    val videoTemplates = remember(templates) { templates.filter { it.workflowType == SdDistributedTemplateWorkflow.VIDEO.name } }
    val imageSelectedModel = imageMainModels.firstOrNull { it.path == imageModelPath }
    val imageSelectedUpscaler = upscalerModels.firstOrNull { it.path == imageUpscalerModelPath }
    val videoSelectedModel = videoModels.firstOrNull { it.path == videoModelPath }
    val imageFamilyInfo = imageSelectedModel?.resolvedSdFamily()
    val imageFamily = imageFamilyInfo?.first
    val imageVariant = imageFamilyInfo?.second
    val imageFamilySpec = imageSelectedModel?.resolveSdFamilySpec()
    val imageComponentRoles = imageFamilySpec?.let { spec ->
        listOf(
            SdComponentRole.VAE,
            SdComponentRole.TAE,
            SdComponentRole.CLIP_L,
            SdComponentRole.CLIP_G,
            SdComponentRole.T5XXL,
            SdComponentRole.LLM,
            SdComponentRole.LLM_VISION,
            SdComponentRole.PHOTOMAKER
        ).filter { it in spec.requiredRoles || it in spec.optionalRoles }
    }.orEmpty()
    val imageCompatibleModels = mapOf(
        SdComponentRole.VAE to filterSdDistributedComponents(vaeModels, imageFamily, imageVariant),
        SdComponentRole.TAE to filterSdDistributedComponents(taeModels, imageFamily, imageVariant),
        SdComponentRole.CLIP_L to filterSdDistributedComponents(clipLModels, imageFamily, imageVariant),
        SdComponentRole.CLIP_G to filterSdDistributedComponents(clipGModels, imageFamily, imageVariant),
        SdComponentRole.T5XXL to filterSdDistributedComponents(t5xxlModels, imageFamily, imageVariant),
        SdComponentRole.LLM to filterSdDistributedComponents(
            imageSupportModels.filter { it.type == ModelType.LLM && it.effectiveSdCompatProfiles().isNotEmpty() },
            imageFamily,
            imageVariant
        ),
        SdComponentRole.LLM_VISION to filterSdDistributedComponents(
            imageSupportModels.filter { it.type == ModelType.VISION_PROJECTOR && it.effectiveSdCompatProfiles().isNotEmpty() },
            imageFamily,
            imageVariant
        ),
        SdComponentRole.PHOTOMAKER to filterSdDistributedComponents(photoMakerModels, imageFamily, imageVariant),
        SdComponentRole.CONTROLNET to filterSdDistributedComponents(controlNetModels, imageFamily, imageVariant),
        SdComponentRole.LORA to filterSdDistributedComponents(loraModels, imageFamily, imageVariant)
    )
    val imageSelectedComponentPaths = mapOf(
        SdComponentRole.VAE to imageVaePath,
        SdComponentRole.TAE to imageTaePath,
        SdComponentRole.CLIP_L to imageClipLPath,
        SdComponentRole.CLIP_G to imageClipGPath,
        SdComponentRole.T5XXL to imageT5xxlPath,
        SdComponentRole.LLM to imageLlmPath,
        SdComponentRole.LLM_VISION to imageLlmVisionPath,
        SdComponentRole.PHOTOMAKER to imagePhotoMakerPath,
        SdComponentRole.CONTROLNET to imageControlNetPath,
        SdComponentRole.LORA to imageLoraStack.firstOrNull()?.path.orEmpty()
    )
    val imageMissingRequiredRoles = imageFamilySpec?.requiredRoles?.filter { role ->
        imageSelectedComponentPaths[role].isNullOrBlank()
    }.orEmpty()
    val imageComponentChoices = SdDistributedImageComponentChoices(
        familyLabel = imageFamily?.storedValue.orEmpty(),
        spec = imageFamilySpec,
        roles = imageComponentRoles,
        compatibleModels = imageCompatibleModels,
        selectedPaths = imageSelectedComponentPaths,
        controlNetEnabled = imageControlNetEnabled,
        loraEnabled = imageLoraEnabled,
        loraApplyMode = imageLoraApplyMode
    )

    fun saveDraftSettings(announce: Boolean = false) {
        scope.launch(Dispatchers.IO) { dao.upsertMasterSettings(draftSettings) }
        SdDistributedService.setRuntimeConfig(runtimeConfig)
        SdDistributedService.setActiveWorkers(
            planningWorkers.map {
                SdDistributedWorkerRuntime(
                    host = it.host,
                    port = it.port,
                    deviceName = it.displayName,
                    ramMB = it.ramMB,
                    threads = it.threads,
                    backendDevice = it.backendDevice,
                    rpcName = it.rpcName,
                    isLocalMaster = it.isLocalMaster,
                    assignedModules = assignmentByRpc[it.rpcName].orEmpty().map { assignment -> assignment.module },
                    plannedAssignments = assignmentByRpc[it.rpcName].orEmpty()
                )
            }
        )
        if (announce) {
            val saved = resources.getString(R.string.sd_dist_settings_saved_applied)
            SdDistributedService.appendLog(saved)
            Toast.makeText(context, saved, Toast.LENGTH_SHORT).show()
        }
    }

    fun applyLingBotProfile() {
        val bundle = lingBotBundle ?: return
        // Distributed difference: start from the current component/runtime state instead of a fresh one.
        val components = SdCuratedPresets.resolveLingBotComponents(
            bundle, lingBotInstalledModels, videoRuntimeOptions.videoComponents
        ) ?: return
        val preset = SdCuratedPresets.LingBotPhone
        videoWorkflowMode = "TXT2VID"
        videoPrompt = resources.getString(R.string.video_lingbot_example_prompt)
        videoNegativePrompt = resources.getString(R.string.video_lingbot_example_negative)
        videoModelPath = components.diffusionModelPath.orEmpty()
        videoInputPath = ""
        videoUseVae = preset.USE_VAE
        videoVaePath = ""
        videoUseT5xxl = false
        videoT5xxlPath = ""
        videoWidth = preset.WIDTH.toString()
        videoHeight = preset.HEIGHT.toString()
        videoSteps = preset.STEPS.toString()
        videoCfg = preset.CFG_SCALE_TEXT
        videoSeed = preset.SEED.toString()
        videoFlowShift = preset.FLOW_SHIFT_TEXT
        frames = preset.FRAMES.toString()
        fps = preset.FPS.toString()
        runtimeThreads = preset.THREADS.toString()
        mmap = preset.MMAP
        diffusionFa = preset.DIFFUSION_FA
        videoRuntimeOptions = SdCuratedPresets.lingBotRuntimeOptions(components, videoRuntimeOptions)
    }

    /** Records the run for the Red multimedia screen and takes the user there. */
    fun beginRunMonitor(kind: SdRunKind, mode: String, modulePaths: Map<String, List<String?>>) {
        SdDistributedRunMonitor.begin(
            kind = kind,
            mode = mode,
            nowMs = System.currentTimeMillis(),
            txBytes = TrafficStats.getUidTxBytes(Process.myUid()),
            expected = SdTransferExpectation.expected(
                SdDistributedService.activeWorkers.value,
                SdTransferExpectation.moduleBytes(modulePaths)
            )
        )
        navController.navigate(Screen.SdDistributedNetwork.route)
    }

    fun startImageRun() {
        val mode = imageWorkflowMode.toSdMasterImageMode()
        val width = parseSdDistributedDimension(imageWidth)
        val height = parseSdDistributedDimension(imageHeight)
        val outputDir = File(context.filesDir, "sd_output").apply { mkdirs() }
        val subfolder = when (mode) {
            SDMode.TXT2IMG -> "txt2img"
            SDMode.IMG2IMG -> "img2img"
            SDMode.ADETAILER -> "adetailer"
            SDMode.UPSCALE -> "upscaled"
        }
        val outputFile = File(File(outputDir, subfolder).apply { mkdirs() }, "sd_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.png")
        val samplerMethod = SamplingMethod.entries.firstOrNull { it.cliName == imageSampler || it.name == imageSampler } ?: SamplingMethod.EULER_A
        val parsedCacheMode = SdCacheMode.fromStoredValue(cacheMode)
        val parsedScmPolicy = SdCacheScmPolicy.fromStoredValue(scmPolicy)

        launchError = sdDistributedImageLaunchIssue(
            mode = mode,
            modelPath = imageModelPath,
            prompt = imagePrompt,
            width = width,
            height = height,
            missingRequiredRoles = imageMissingRequiredRoles,
            inputPath = imageInputPath,
            upscalerModelPath = imageUpscalerModelPath,
            controlNetEnabled = imageControlNetEnabled,
            controlNetPath = imageControlNetPath,
            loraEnabled = imageLoraEnabled,
            loraPath = imageLoraPath
        )?.let { sdDistributedLaunchIssueMessage(resources, context, it) }
        launchError?.let {
            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
            return
        }
        saveDraftSettings()
        SdDistributedService.appendLog(resources.getString(R.string.sd_dist_log_run_requested, mode.name))
        beginRunMonitor(
            SdRunKind.IMAGE,
            mode.name,
            mapOf(
                SdDistributedModules.DIFFUSION to listOf(imageModelPath.takeIf { mode != SDMode.UPSCALE }),
                SdDistributedModules.TEXT_ENCODER to listOf(imageClipLPath, imageClipGPath, imageT5xxlPath, imageLlmPath, imageLlmVisionPath),
                SdDistributedModules.VAE to listOf(imageVaePath, imageTaePath),
                SdDistributedModules.CONTROLNET to listOf(imageControlNetPath.takeIf { imageControlNetEnabled }),
                SdDistributedModules.UPSCALER to listOf(imageUpscalerModelPath.takeIf { mode == SDMode.UPSCALE })
            )
        )

        if (mode == SDMode.UPSCALE) {
            val config = SDUpscaleConfig(
                modelPath = imageUpscalerModelPath,
                inputImagePath = imageInputPath,
                outputPath = outputFile.absolutePath,
                threads = runtimeThreads.toIntOrNull() ?: -1,
                // Distributed placement is selected by the remote runtime;
                // never inherit the local model's remembered residency.
                sdParamsBackendSpec = "auto",
                sdParamsBackendMode = "auto",
                sdRuntimeBackendMode = "auto",
                maxVramCpuGiB = "",
                distributedRuntime = runtimeConfig,
                customFlags = imageCustomFlags
            )
            ContextCompat.startForegroundService(
                context,
                StableDiffusionService.createStartUpscaleIntent(
                    context,
                    config,
                    useDistributedStateHolder = true
                )
            )
        } else {
            val config = SDConfig(
                mode = mode,
                modelPath = imageModelPath,
                prompt = imagePrompt,
                negativePrompt = imageNegativePrompt,
                width = requireNotNull(width),
                height = requireNotNull(height),
                steps = imageSteps.toIntOrNull() ?: 20,
                cfgScale = imageCfg.toFloatOrNull() ?: 7.0f,
                seed = imageSeed.toLongOrNull() ?: -1L,
                samplingMethod = samplerMethod,
                scheduler = SdScheduler.fromCliName(imageScheduler),
                outputPath = outputFile.absolutePath,
                initImage = imageInputPath.takeIf { mode == SDMode.IMG2IMG },
                strength = strength.toFloatOrNull() ?: 0.75f,
                threads = runtimeThreads.toIntOrNull() ?: -1,
                vaeTiling = vaeTiling,
                vaeTileOverlap = vaeTileOverlap.toFloatOrNull() ?: 0f,
                vaeTileSize = vaeTileSize.ifBlank { "32x32" },
                tensorTypeRules = tensorRules,
                modelFamily = imageFamily?.storedValue,
                modelVariant = imageVariant,
                vaePath = imageVaePath.ifBlank { null },
                taePath = imageTaePath.ifBlank { null },
                clipLPath = imageClipLPath.ifBlank { null },
                clipGPath = imageClipGPath.ifBlank { null },
                t5xxlPath = imageT5xxlPath.ifBlank { null },
                llmPath = imageLlmPath.ifBlank { null },
                llmVisionPath = imageLlmVisionPath.ifBlank { null },
                photoMakerPath = imagePhotoMakerPath.ifBlank { null },
                controlNetPath = if (imageControlNetEnabled) imageControlNetPath.ifBlank { null } else null,
                controlImagePath = if (imageControlNetEnabled && mode == SDMode.IMG2IMG) imageInputPath.ifBlank { null } else null,
                controlStrength = controlStrength.toFloatOrNull() ?: 0.9f,
                loraPath = if (imageLoraEnabled) imageLoraPath.ifBlank { null } else null,
                loraStrength = loraStrength.toFloatOrNull() ?: 1.0f,
                loraApplyMode = if (imageLoraEnabled) SdLoraApplyMode.fromStoredValue(imageLoraApplyMode) else null,
                loras = if (imageLoraEnabled) draftSettings.imageLoras() else emptyList(),
                cacheMode = parsedCacheMode,
                cacheOption = if (parsedCacheMode != null) cacheOption else "",
                scmMask = if (parsedCacheMode == SdCacheMode.CACHE_DIT) scmMask else "",
                scmPolicy = if (parsedCacheMode == SdCacheMode.CACHE_DIT) parsedScmPolicy else null,
                flowShift = imageFlowShift.toFloatOrNull(),
                diffusionFa = diffusionFa,
                mmap = mmap,
                // Keep local parameter residency out of distributed commands.
                sdParamsBackendSpec = "auto",
                sdParamsBackendMode = "auto",
                sdRuntimeBackendMode = "auto",
                quantizationType = quantization,
                distributedRuntime = runtimeConfig,
                customFlags = imageCustomFlags
            )
            ContextCompat.startForegroundService(
                context,
                StableDiffusionService.createStartIntent(
                    context,
                    config,
                    useDistributedStateHolder = true
                )
            )
        }
    }

    fun cancelImageRun() {
        context.startService(
            StableDiffusionService.createCancelModeIntent(
                context,
                imageWorkflowMode.toSdMasterImageMode(),
                useDistributedStateHolder = true
            )
        )
    }

    fun startVideoRun() {
        if (!videoBinaryReady) {
            val reason = resources.getString(
                if (videoBinaryProbePending) R.string.video_controls_binary_checking
                else R.string.video_controls_binary_unavailable_selection
            )
            launchError = reason
            Toast.makeText(context, reason, Toast.LENGTH_LONG).show()
            return
        }
        val mode = videoWorkflowMode.toSdMasterVideoMode()
        val width = parseSdDistributedDimension(videoWidth)
        val height = parseSdDistributedDimension(videoHeight)
        val outputDir = File(context.filesDir, "video_gen_output").apply { mkdirs() }
        val modeDir = File(outputDir, mode.folderName).apply { mkdirs() }
        val baseName = "video_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}"
        val samplerMethod = SamplingMethod.entries.firstOrNull { it.cliName == videoSampler || it.name == videoSampler } ?: SamplingMethod.EULER
        val parsedCacheMode = SdCacheMode.fromStoredValue(cacheMode)
        val parsedScmPolicy = SdCacheScmPolicy.fromStoredValue(scmPolicy)
        val effectiveVideoOptions = videoRuntimeOptions.copy(
            workflow = videoRuntimeOptions.workflow ?: if (mode == VideoGenerationMode.IMG2VID) {
                SdVideoWorkflow.IMAGE_TO_VIDEO
            } else {
                SdVideoWorkflow.TEXT_TO_VIDEO
            },
            videoComponents = videoRuntimeOptions.videoComponents.copy(
                diffusionModelPath = videoRuntimeOptions.videoComponents.diffusionModelPath ?: videoModelPath.ifBlank { null },
                fullModelPath = videoRuntimeOptions.videoComponents.fullModelPath ?: videoModelPath.ifBlank { null },
                vaePath = videoRuntimeOptions.videoComponents.vaePath ?: videoVaePath.takeIf { videoUseVae },
                t5xxlPath = videoRuntimeOptions.videoComponents.t5xxlPath ?: videoT5xxlPath.takeIf { videoUseT5xxl }
            ),
            videoInputs = videoRuntimeOptions.videoInputs.copy(
                initImagePath = videoRuntimeOptions.videoInputs.initImagePath ?: videoInputPath.ifBlank { null }
            ),
            seed = videoRuntimeOptions.seed.takeIf { it != -1L } ?: videoSeed.toLongOrNull() ?: -1L
        )

        val selectedVideoComponentPath = effectiveVideoOptions.videoComponents.diffusionModelPath
            ?: effectiveVideoOptions.videoComponents.fullModelPath

        launchError = sdDistributedVideoLaunchIssue(
            mode = mode,
            effectiveOptions = effectiveVideoOptions,
            prompt = videoPrompt,
            framesText = frames,
            width = width,
            height = height,
            loras = videoLoraStack + videoHighNoiseLoraStack,
            compatibleLoraPaths = videoLoraModels.map { it.path }.toSet()
        )?.let { sdDistributedLaunchIssueMessage(resources, context, it) }
        launchError?.let {
            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
            return
        }
        saveDraftSettings()
        SdDistributedService.appendLog(resources.getString(R.string.sd_dist_log_run_requested, mode.name))
        beginRunMonitor(
            SdRunKind.VIDEO,
            mode.name,
            mapOf(
                SdDistributedModules.DIFFUSION to listOf(
                    selectedVideoComponentPath,
                    effectiveVideoOptions.videoComponents.highNoiseDiffusionModelPath
                ),
                SdDistributedModules.TEXT_ENCODER to listOf(
                    effectiveVideoOptions.videoComponents.t5xxlPath,
                    effectiveVideoOptions.videoComponents.llmPath,
                    effectiveVideoOptions.videoComponents.llmVisionPath
                ),
                SdDistributedModules.VAE to listOf(
                    effectiveVideoOptions.videoComponents.vaePath,
                    effectiveVideoOptions.videoComponents.taePath
                ),
                SdDistributedModules.CONTROLNET to listOf(effectiveVideoOptions.videoComponents.controlNetPath)
            )
        )

        // VAE is replaced by the TAE decoder when the editor enables it.
        val effectiveVideoVaePath = effectiveVideoOptions.videoComponents.vaePath
            ?.takeIf { it.isNotBlank() && !effectiveVideoOptions.useTae }
        val config = VideoGenerationConfig(
            mode = mode,
            prompt = videoPrompt,
            negativePrompt = videoNegativePrompt,
            diffusionModelPath = selectedVideoComponentPath.orEmpty(),
            outputAviPath = File(modeDir, "$baseName.avi").absolutePath,
            outputMp4Path = File(modeDir, "$baseName.mp4").absolutePath,
            metadataPath = File(modeDir, "$baseName.json").absolutePath,
            initImagePath = effectiveVideoOptions.videoInputs.initImagePath
                ?.takeIf { mode == VideoGenerationMode.IMG2VID },
            useVae = effectiveVideoVaePath != null,
            vaePath = effectiveVideoVaePath,
            useT5xxl = effectiveVideoOptions.videoComponents.t5xxlPath != null,
            t5xxlPath = effectiveVideoOptions.videoComponents.t5xxlPath,
            videoFrames = frames.toIntOrNull() ?: 8,
            fps = fps.toIntOrNull() ?: 5,
            width = requireNotNull(width),
            height = requireNotNull(height),
            steps = videoSteps.toIntOrNull() ?: 18,
            cfgScale = videoCfg.toFloatOrNull() ?: 6.0f,
            flowShift = videoFlowShift.toFloatOrNull(),
            samplingMethod = samplerMethod,
            scheduler = SdScheduler.fromCliName(videoScheduler),
            loras = videoLoraStack,
            highNoiseLoras = videoHighNoiseLoraStack,
            loraApplyMode = SdLoraApplyMode.fromStoredValue(videoLoraApplyMode),
            cacheMode = parsedCacheMode,
            cacheOption = if (parsedCacheMode != null) cacheOption else "",
            scmMask = if (parsedCacheMode == SdCacheMode.CACHE_DIT) scmMask else "",
            scmPolicy = if (parsedCacheMode == SdCacheMode.CACHE_DIT) parsedScmPolicy else null,
            vaeTiling = vaeTiling,
            vaeTileSize = vaeTileSize.ifBlank { "24x24" },
            diffusionFa = diffusionFa,
            mmap = mmap,
            threads = runtimeThreads.toIntOrNull() ?: -1,
            // Distributed placement is negotiated independently of local
            // model-row preferences.
            sdParamsBackendSpec = "auto",
            sdParamsBackendMode = "auto",
            sdRuntimeBackendMode = "auto",
            maxVramCpuGiB = "",
            distributedRuntime = runtimeConfig,
            customFlags = videoCustomFlags,
            videoFamily = effectiveVideoOptions.videoFamily,
            videoVariant = effectiveVideoOptions.videoVariant,
            workflow = effectiveVideoOptions.workflow,
            videoComponents = effectiveVideoOptions.videoComponents,
            videoInputs = effectiveVideoOptions.videoInputs,
            useTae = effectiveVideoOptions.useTae,
            seed = effectiveVideoOptions.seed,
            highNoiseSteps = effectiveVideoOptions.highNoiseSteps,
            highNoiseCfgScale = effectiveVideoOptions.highNoiseCfgScale,
            highNoiseSamplingMethod = effectiveVideoOptions.highNoiseSamplingMethod,
            controlStrength = effectiveVideoOptions.controlStrength,
            vaeTileOverlap = effectiveVideoOptions.vaeTileOverlap,
            vaeRelativeTileSize = effectiveVideoOptions.vaeRelativeTileSize,
            hires = effectiveVideoOptions.hires,
            outputFormat = effectiveVideoOptions.outputFormat,
            nativeOutputFormat = effectiveVideoOptions.nativeOutputFormat,
            nativeOutputPath = File(modeDir, "$baseName.${effectiveVideoOptions.nativeOutputFormat.extension}").absolutePath,
            audioCodec = effectiveVideoOptions.audioCodec,
            conversionRecoveryEnabled = effectiveVideoOptions.conversionRecoveryEnabled,
            imgCfgScale = effectiveVideoOptions.imgCfgScale,
            guidance = effectiveVideoOptions.guidance,
            slgScale = effectiveVideoOptions.slgScale,
            skipLayerStart = effectiveVideoOptions.skipLayerStart,
            skipLayerEnd = effectiveVideoOptions.skipLayerEnd,
            skipLayers = effectiveVideoOptions.skipLayers,
            eta = effectiveVideoOptions.eta,
            strength = effectiveVideoOptions.strength,
            highNoiseImgCfgScale = effectiveVideoOptions.highNoiseImgCfgScale,
            highNoiseGuidance = effectiveVideoOptions.highNoiseGuidance,
            highNoiseSlgScale = effectiveVideoOptions.highNoiseSlgScale,
            highNoiseSkipLayerStart = effectiveVideoOptions.highNoiseSkipLayerStart,
            highNoiseSkipLayerEnd = effectiveVideoOptions.highNoiseSkipLayerEnd,
            highNoiseSkipLayers = effectiveVideoOptions.highNoiseSkipLayers,
            highNoiseEta = effectiveVideoOptions.highNoiseEta,
            moeBoundary = effectiveVideoOptions.moeBoundary,
            vaceStrength = effectiveVideoOptions.vaceStrength,
            ipAdapterStrength = effectiveVideoOptions.ipAdapterStrength,
            vaeFormat = effectiveVideoOptions.vaeFormat,
            sigmas = effectiveVideoOptions.sigmas,
            refImageArgs = effectiveVideoOptions.refImageArgs,
            extraSampleArgs = effectiveVideoOptions.extraSampleArgs,
            extraTilingArgs = effectiveVideoOptions.extraTilingArgs,
            increaseRefIndex = effectiveVideoOptions.increaseRefIndex,
            disableAutoResizeRefImage = effectiveVideoOptions.disableAutoResizeRefImage,
            circular = effectiveVideoOptions.circular,
            circularX = effectiveVideoOptions.circularX,
            circularY = effectiveVideoOptions.circularY,
            temporalTiling = effectiveVideoOptions.temporalTiling,
            promptFormat = effectiveVideoOptions.promptFormat,
            lingBotPromptJson = effectiveVideoOptions.lingBotPromptJson
        )
        ContextCompat.startForegroundService(
            context,
            VideoGenerationService.createStartIntent(
                context,
                config,
                useDistributedStateHolder = true
            )
        )
    }

    fun cancelVideoRun() {
        context.startService(
            VideoGenerationService.createCancelIntent(
                context,
                videoWorkflowMode.toSdMasterVideoMode(),
                useDistributedStateHolder = true
            )
        )
    }

    LaunchedEffect(planningWorkers, autoRamPlan) {
        SdDistributedService.setActiveWorkers(
            planningWorkers.map {
                SdDistributedWorkerRuntime(
                    host = it.host,
                    port = it.port,
                    deviceName = it.displayName,
                    ramMB = it.ramMB,
                    threads = it.threads,
                    backendDevice = it.backendDevice,
                    rpcName = it.rpcName,
                    isLocalMaster = it.isLocalMaster,
                    assignedModules = assignmentByRpc[it.rpcName].orEmpty().map { assignment -> assignment.module },
                    plannedAssignments = assignmentByRpc[it.rpcName].orEmpty()
                )
            }
        )
    }

    val sdWorkerLogs by SdDistributedService.logs.collectAsState()
    // Service-side start failures (missing binary, preflight, process exit) land in the generation
    // state holders; they were never rendered, so a failed Start looked like "nothing happens".
    val imageRunError = (imageGenerationState as? SDGenerationState.Error)?.message
    val videoRunError = (videoGenerationState as? VideoGenerationState.Error)?.message
    val visibleError = sdDistributedVisibleError(launchError, if (videoMode) videoRunError else imageRunError)
    LaunchedEffect(launchError) {
        launchError?.let { SdDistributedService.appendLog(resources.getString(R.string.sd_dist_log_start_blocked, it)) }
    }
    LaunchedEffect(imageRunError) {
        imageRunError?.let { SdDistributedService.appendLog(resources.getString(R.string.sd_dist_log_run_failed, it)) }
    }
    LaunchedEffect(videoRunError) {
        videoRunError?.let { SdDistributedService.appendLog(resources.getString(R.string.sd_dist_log_run_failed, it)) }
    }
    val sdLogLines = sdWorkerLogs

    SdDistributedPage(
        navController = navController,
        title = stringResource(if (videoMode) R.string.sd_r4_video_title else R.string.sd_r4_images_title),
        subtitle = stringResource(if (videoMode) R.string.sd_r4_video_subtitle else R.string.sd_r4_images_subtitle)
    ) {
        SdHowItWorks(SdHowToScreen.MASTER)
        SdMasterStatusPanel(
            helperCount = enabledWorkers.size,
            running = imageWorkRunning || videoWorkRunning,
            error = visibleError,
            logLines = sdLogLines,
            onAddHelper = {
                devicesExpanded = true
                showAddForm = true
            }
        )
        RenderFarmHeader(runtimeConfig = runtimeConfig, workers = planningWorkers, plan = autoRamPlan)

        CollapsibleFarmCard(
            title = stringResource(R.string.sd_dist_card_devices),
            subtitle = stringResource(R.string.sd_dist_card_devices_desc),
            expanded = devicesExpanded,
            onExpandedChange = { devicesExpanded = it },
            accent = RenderFarmPalette.lime.copy(alpha = 0.14f)
        ) {
            MasterContributionCard(
                enabled = masterContributes,
                onEnabledChange = { masterContributes = it },
                displayName = masterDisplayName,
                onDisplayNameChange = { masterDisplayName = it },
                ramDraft = masterRamDraft,
                onRamDraftChange = { masterRamDraft = it },
                threadsDraft = masterThreadsDraft,
                onThreadsDraftChange = { masterThreadsDraft = it },
                backendDevice = masterBackendDevice,
                onBackendDeviceChange = { masterBackendDevice = it },
                allowedModules = masterAllowedModules,
                onAllowedModulesChange = { masterAllowedModules = it },
                diffusionShareDraft = masterDiffusionShareDraft,
                onDiffusionShareDraftChange = { masterDiffusionShareDraft = it }
            )

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = stringResource(R.string.sd_dist_remote_workers),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                OutlinedButton(
                    onClick = {
                        hostDraft = ""
                        portDraft = SdDistributedService.RPC_DEFAULT_PORT.toString()
                        nameDraft = ""
                        ramDraft = "4096"
                        threadsDraft = "4"
                        backendDraft = ""
                        editingWorkerId = null
                        showAddForm = true
                    }
                ) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.sd_dist_new_worker), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            SdDiscoveredWorkersSection(
                saved = workers,
                onAdd = { found ->
                    scope.launch(Dispatchers.IO) {
                        dao.upsertWorker(found.toEntity(dao.getMaxWorkerSortOrder() + 1))
                    }
                }
            )
            if (workers.isEmpty()) {
                DistributedNoHelpersState(
                    onAdd = { showAddForm = true },
                    title = stringResource(R.string.sd_ui2_no_helpers_title),
                    message = stringResource(R.string.sd_ui2_no_helpers_message)
                )
            } else {
                val rpcNameByWorkerId = planningWorkers
                    .filterNot { it.isLocalMaster }
                    .associate { it.id to it.rpcName }
                workers.forEachIndexed { index, worker ->
                    WorkerRow(
                        worker = worker,
                        rpcName = if (worker.isEnabled) rpcNameByWorkerId[worker.id] ?: "-" else "-",
                        onToggle = {
                            scope.launch(Dispatchers.IO) { dao.setWorkerEnabled(worker.id, !worker.isEnabled) }
                        },
                        onEdit = {
                            editingWorkerId = worker.id
                            hostDraft = worker.host
                            portDraft = worker.port.toString()
                            nameDraft = worker.deviceName
                            ramDraft = worker.ramMB.toString()
                            threadsDraft = worker.threads.toString()
                            backendDraft = worker.backendDevice
                        },
                        onDelete = {
                            scope.launch(Dispatchers.IO) { dao.deleteWorker(worker.id) }
                        }
                    )
                    if (index < workers.lastIndex) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
                }
            }

            if (showAddForm || editingWorkerId != null) {
                HorizontalDivider()
                Text(
                    text = if (editingWorkerId == null) stringResource(R.string.sd_dist_manual_worker) else stringResource(R.string.sd_dist_edit_worker),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                OutlinedTextField(
                    value = hostDraft,
                    onValueChange = { hostDraft = it },
                    label = { Text(stringResource(R.string.sd_dist_worker_host)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = portDraft,
                        onValueChange = { portDraft = it.filter(Char::isDigit).take(5) },
                        label = { Text(stringResource(R.string.sd_dist_port)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = threadsDraft,
                        onValueChange = { threadsDraft = it.filter(Char::isDigit).take(2) },
                        label = { Text(stringResource(R.string.sd_dist_threads_short)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = nameDraft,
                        onValueChange = { nameDraft = it },
                        label = { Text(stringResource(R.string.sd_dist_device_name)) },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = ramDraft,
                        onValueChange = { ramDraft = it.filter(Char::isDigit).take(6) },
                        label = { Text(stringResource(R.string.sd_dist_ram_mb_short)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                }
                BackendDevicePicker(
                    label = stringResource(R.string.sd_dist_backend_device_optional),
                    selected = backendDraft,
                    onSelected = { backendDraft = it },
                    includeRemoteChoices = false
                )
                Button(
                    onClick = {
                        val host = hostDraft.trim()
                        if (host.isBlank()) return@Button
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                val sortOrder = if (editingWorkerId == null) dao.getMaxWorkerSortOrder() + 1 else {
                                    workers.firstOrNull { it.id == editingWorkerId }?.sortOrder ?: dao.getMaxWorkerSortOrder() + 1
                                }
                                dao.upsertWorker(
                                    SdDistributedWorkerEntity(
                                        id = editingWorkerId ?: 0L,
                                        host = host,
                                        port = portDraft.toIntOrNull() ?: SdDistributedService.RPC_DEFAULT_PORT,
                                        deviceName = nameDraft.ifBlank { host },
                                        ramMB = ramDraft.toIntOrNull() ?: 4096,
                                        threads = threadsDraft.toIntOrNull() ?: 4,
                                        backendDevice = backendDraft,
                                        isEnabled = workers.firstOrNull { it.id == editingWorkerId }?.isEnabled ?: true,
                                        sortOrder = sortOrder
                                    )
                                )
                            }
                            hostDraft = ""
                            nameDraft = ""
                            backendDraft = ""
                            editingWorkerId = null
                            showAddForm = false
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (editingWorkerId == null) stringResource(R.string.sd_dist_add_worker) else stringResource(R.string.sd_dist_save_worker))
                }
            }
            HorizontalDivider()
            SummaryLine(stringResource(R.string.sd_dist_rpc_servers_preview), autoRamPlan.rpcServers.ifBlank { "-" })
        }

        CollapsibleFarmCard(
            title = stringResource(R.string.sd_dist_card_planner),
            subtitle = stringResource(R.string.sd_dist_card_planner_desc),
            expanded = plannerExpanded,
            onExpandedChange = { plannerExpanded = it },
            accent = RenderFarmPalette.violet.copy(alpha = 0.16f)
        ) {
            ToggleRow(
                title = stringResource(R.string.sd_dist_enable_distributed),
                subtitle = stringResource(R.string.sd_dist_enable_distributed_desc),
                checked = enabled,
                onCheckedChange = { enabled = it }
            )
            PlacementModeRow(placementMode) { placementMode = it }
            WarningBand(text = stringResource(R.string.sd_dist_layer_split_max_vram_warning))
            if (placementMode == SdDistributedPlacementMode.AUTO_RAM) {
                AutoRamScopePicker(autoRamScope) { autoRamScope = it }
                RamPlanPreview(plan = autoRamPlan)
                Text(
                    text = stringResource(R.string.sd_dist_auto_ram_help),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = stringResource(R.string.sd_dist_first_rpc_overhead_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else if (placementMode == SdDistributedPlacementMode.AUTO_FIT) {
                Text(
                    text = stringResource(R.string.sd_dist_auto_fit_help),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else if (placementMode == SdDistributedPlacementMode.COMPONENTS) {
                LabeledTextField(R.string.sd_dist_backend_spec, backendSpec, { backendSpec = it }, R.string.sd_dist_backend_spec_hint)
                LabeledTextField(R.string.sd_dist_params_backend_spec, paramsBackend, { paramsBackend = it }, R.string.sd_dist_params_backend_hint)
            } else {
                Text(
                    text = stringResource(R.string.sd_dist_manual_expert_flags_help),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        visibleError?.let {
            WarningBand(text = it)
        }
        if (videoMode) {
            videoReuseWarning?.let {
                WarningBand(text = it)
            }
        }

        if (!videoMode) ImageRunCard(
            expanded = imageExpanded,
            onExpandedChange = { imageExpanded = it },
            mode = imageWorkflowMode,
            onModeChange = { imageWorkflowMode = it },
            models = imageMainModels,
            upscalerModels = upscalerModels,
            modelPath = imageModelPath,
            onModelPathChange = { imageModelPath = it },
            upscalerModelPath = imageUpscalerModelPath,
            onUpscalerModelPathChange = { imageUpscalerModelPath = it },
            inputPath = imageInputPath,
            onInputPathChange = { imageInputPath = it },
            onPickInputImage = { imageInputPicker.launch(arrayOf("image/*")) },
            prompt = imagePrompt,
            onPromptChange = { imagePrompt = it },
            negativePrompt = imageNegativePrompt,
            onNegativePromptChange = { imageNegativePrompt = it },
            width = imageWidth,
            onWidthChange = { imageWidth = it.filter(Char::isDigit).take(5) },
            height = imageHeight,
            onHeightChange = { imageHeight = it.filter(Char::isDigit).take(5) },
            steps = imageSteps,
            onStepsChange = { imageSteps = it },
            cfg = imageCfg,
            onCfgChange = { imageCfg = it },
            seed = imageSeed,
            onSeedChange = { imageSeed = it },
            sampler = imageSampler,
            onSamplerChange = { imageSampler = it },
            scheduler = imageScheduler,
            onSchedulerChange = { imageScheduler = it },
            flowShift = imageFlowShift,
            onFlowShiftChange = { imageFlowShift = it },
            strength = strength,
            onStrengthChange = { strength = it },
            components = imageComponentChoices,
            onComponentPathChange = { role, path ->
                when (role) {
                    SdComponentRole.VAE -> imageVaePath = path
                    SdComponentRole.TAE -> imageTaePath = path
                    SdComponentRole.CLIP_L -> imageClipLPath = path
                    SdComponentRole.CLIP_G -> imageClipGPath = path
                    SdComponentRole.T5XXL -> imageT5xxlPath = path
                    SdComponentRole.LLM -> imageLlmPath = path
                    SdComponentRole.LLM_VISION -> imageLlmVisionPath = path
                    SdComponentRole.PHOTOMAKER -> imagePhotoMakerPath = path
                    SdComponentRole.CONTROLNET -> imageControlNetPath = path
                    SdComponentRole.LORA -> {
                        imageLoraPath = path
                        imageLoraStack = if (imageLoraStack.isEmpty()) {
                            listOf(SdLoraSpec(path = path))
                        } else {
                            imageLoraStack.mapIndexed { index, item ->
                                if (index == 0) item.copy(path = path) else item
                            }
                        }
                    }
                    else -> Unit
                }
            },
            onControlNetEnabledChange = {
                imageControlNetEnabled = it
                if (!it) imageControlNetPath = ""
            },
            onLoraEnabledChange = {
                imageLoraEnabled = it
                if (!it) {
                    imageLoraPath = ""
                    imageLoraStack = emptyList()
                    imageLoraApplyMode = ""
                }
            },
            loraStack = imageLoraStack,
            onLoraStackChange = {
                imageLoraStack = it
                imageLoraEnabled = it.isNotEmpty()
                imageLoraPath = it.firstOrNull()?.path.orEmpty()
            },
            onLoraApplyModeChange = { imageLoraApplyMode = it },
            customFlags = imageCustomFlags,
            onCustomFlagsChange = { imageCustomFlags = it },
            templates = imageTemplates,
            templateNameDraft = templateNameDraft,
            onTemplateNameChange = { templateNameDraft = it },
            onSaveTemplate = {
                val name = templateNameDraft.trim()
                if (name.isNotBlank()) {
                    scope.launch(Dispatchers.IO) {
                        dao.upsertTemplate(
                            SdDistributedTemplateEntity(
                                name = name,
                                workflowType = SdDistributedTemplateWorkflow.IMAGE.name,
                                settingsJson = settingsToJson(draftSettings),
                                updatedAt = System.currentTimeMillis()
                            )
                        )
                    }
                    templateNameDraft = ""
                }
            },
            onLoadTemplate = { template ->
                scope.launch(Dispatchers.IO) {
                    dao.upsertMasterSettings(settingsFromJson(template.settingsJson, draftSettings).copy(id = 1))
                }
            },
            onDeleteTemplate = { template -> scope.launch(Dispatchers.IO) { dao.deleteTemplate(template.id) } },
            isRunning = imageWorkRunning,
            generationState = imageGenerationState,
            startError = visibleError,
            onStart = { startImageRun() },
            onCancel = { cancelImageRun() }
        )

        if (videoMode) VideoRunCard(
            expanded = videoExpanded,
            onExpandedChange = { videoExpanded = it },
            componentModels = videoComponentModels,
            binaryCapabilities = videoBinaryCapabilities,
            binaryProbePending = videoBinaryProbePending,
            binaryProbeUnavailable = videoBinaryProbeUnavailable,
            onRetryBinaryProbe = { videoBinaryProbeRequest++ },
            onOpenBinarySettings = { navController.navigate("settings_imagegen") },
            uncondDiffusionModels = videoModels,
            ipAdapterModels = ipAdapterModels,
            videoOptions = videoRuntimeOptions,
            onVideoOptionsChange = { next ->
                videoRuntimeOptions = next
                videoModelPath = (
                    next.videoComponents.diffusionModelPath
                        ?: next.videoComponents.fullModelPath
                ).orEmpty()
                videoVaePath = next.videoComponents.vaePath.orEmpty()
                videoUseVae = videoVaePath.isNotBlank() && !next.useTae
                videoT5xxlPath = next.videoComponents.t5xxlPath.orEmpty()
                videoUseT5xxl = videoT5xxlPath.isNotBlank()
                videoInputPath = next.videoInputs.initImagePath.orEmpty()
                videoSeed = next.seed.takeIf { it != -1L }?.toString().orEmpty()
                videoWorkflowMode = if (next.workflow == SdVideoWorkflow.IMAGE_TO_VIDEO) "IMG2VID" else "TXT2VID"
            },
            lingBotReady = lingBotReady,
            onApplyLingBot = { if (lingBotReady) applyLingBotProfile() },
            loraModels = videoLoraModels,
            loras = videoLoraStack,
            onLorasChange = { videoLoraStack = it },
            highNoiseLoras = videoHighNoiseLoraStack,
            onHighNoiseLorasChange = { videoHighNoiseLoraStack = it },
            loraApplyMode = videoLoraApplyMode,
            onLoraApplyModeChange = { videoLoraApplyMode = it },
            prompt = videoPrompt,
            onPromptChange = { videoPrompt = it },
            negativePrompt = videoNegativePrompt,
            onNegativePromptChange = { videoNegativePrompt = it },
            width = videoWidth,
            onWidthChange = { videoWidth = it.filter(Char::isDigit).take(5) },
            height = videoHeight,
            onHeightChange = { videoHeight = it.filter(Char::isDigit).take(5) },
            steps = videoSteps,
            onStepsChange = { videoSteps = it },
            cfg = videoCfg,
            onCfgChange = { videoCfg = it },
            sampler = videoSampler,
            onSamplerChange = { videoSampler = it },
            scheduler = videoScheduler,
            onSchedulerChange = { videoScheduler = it },
            flowShift = videoFlowShift,
            onFlowShiftChange = { videoFlowShift = it },
            frames = frames,
            onFramesChange = { frames = it },
            fps = fps,
            onFpsChange = { fps = it },
            customFlags = videoCustomFlags,
            onCustomFlagsChange = { videoCustomFlags = it },
            templates = videoTemplates,
            templateNameDraft = templateNameDraft,
            onTemplateNameChange = { templateNameDraft = it },
            onSaveTemplate = {
                val name = templateNameDraft.trim()
                if (name.isNotBlank()) {
                    scope.launch(Dispatchers.IO) {
                        dao.upsertTemplate(
                            SdDistributedTemplateEntity(
                                name = name,
                                workflowType = SdDistributedTemplateWorkflow.VIDEO.name,
                                settingsJson = settingsToJson(draftSettings),
                                updatedAt = System.currentTimeMillis()
                            )
                        )
                    }
                    templateNameDraft = ""
                }
            },
            onLoadTemplate = { template ->
                scope.launch(Dispatchers.IO) {
                    dao.upsertMasterSettings(settingsFromJson(template.settingsJson, draftSettings).copy(id = 1))
                }
            },
            onDeleteTemplate = { template -> scope.launch(Dispatchers.IO) { dao.deleteTemplate(template.id) } },
            isRunning = videoWorkRunning,
            generationState = videoGenerationState,
            startError = visibleError,
            onStart = { startVideoRun() },
            onCancel = { cancelVideoRun() }
        )

        CollapsibleFarmCard(
            title = stringResource(R.string.sd_dist_runtime_controls),
            subtitle = stringResource(R.string.sd_dist_card_runtime_desc),
            expanded = runtimeExpanded,
            onExpandedChange = { runtimeExpanded = it },
            accent = RenderFarmPalette.orange.copy(alpha = 0.14f)
        ) {
            ToggleRow(stringResource(R.string.sd_dist_mmap), stringResource(R.string.sd_dist_mmap_desc), mmap) { mmap = it }
            ToggleRow(stringResource(R.string.sd_dist_diffusion_fa), stringResource(R.string.sd_dist_diffusion_fa_desc), diffusionFa) { diffusionFa = it }
            ToggleRow(stringResource(R.string.sd_dist_vae_tiling), stringResource(R.string.sd_dist_vae_tiling_desc), vaeTiling) { vaeTiling = it }
            ToggleRow(stringResource(R.string.sd_dist_max_vram_enabled), stringResource(R.string.sd_dist_max_vram_enabled_desc), maxVramEnabled) { maxVramEnabled = it }
            if (maxVramEnabled) {
                LabeledTextField(R.string.sd_dist_max_vram, maxVram, { maxVram = it }, R.string.sd_dist_max_vram_hint)
            }
            TwoColumnFields(
                first = { LabeledTextField(R.string.sd_dist_vae_tile_size, vaeTileSize, { vaeTileSize = it }, R.string.sd_dist_vae_tile_size_hint) },
                second = { LabeledTextField(R.string.sd_dist_vae_tile_overlap, vaeTileOverlap, { vaeTileOverlap = it }, R.string.sd_dist_vae_tile_overlap_hint, KeyboardType.Number) }
            )
            SimpleStringPicker(
                label = stringResource(R.string.sd_dist_quantization),
                selected = quantization,
                options = sdQuantizationOptions(),
                offLabel = stringResource(R.string.sd_dist_picker_auto),
                onSelected = { quantization = it }
            )
            SdTensorTypeRulesPicker(
                value = tensorRules,
                onValueChange = { tensorRules = it }
            )
        }

        CollapsibleFarmCard(
            title = stringResource(R.string.sd_dist_adapter_cache_controls),
            subtitle = stringResource(R.string.sd_dist_card_adapters_desc),
            expanded = adaptersExpanded,
            onExpandedChange = { adaptersExpanded = it },
            accent = MaterialTheme.colorScheme.secondary.copy(alpha = 0.10f)
        ) {
            TwoColumnFields(
                first = { LabeledTextField(R.string.sd_dist_lora_strength, loraStrength, { loraStrength = it }, R.string.sd_dist_lora_strength_hint, KeyboardType.Decimal) },
                second = { LabeledTextField(R.string.sd_dist_control_strength, controlStrength, { controlStrength = it }, R.string.sd_dist_control_strength_hint, KeyboardType.Decimal) }
            )
            CacheModePicker(
                selected = cacheMode,
                onSelected = {
                    cacheMode = it
                    if (it.isBlank()) {
                        cacheOption = ""
                        scmMask = ""
                        scmPolicy = ""
                    } else if (it != SdCacheMode.CACHE_DIT.cliName) {
                        scmMask = ""
                        scmPolicy = ""
                    }
                }
            )
            if (cacheMode.isNotBlank()) {
                CacheOptionPicker(cacheMode = cacheMode, value = cacheOption, onValueChange = { cacheOption = it })
                if (cacheMode == SdCacheMode.CACHE_DIT.cliName) {
                    TwoColumnFields(
                        first = { ScmMaskPicker(value = scmMask, onValueChange = { scmMask = it }) },
                        second = {
                            SimpleStringPicker(
                                label = stringResource(R.string.sd_dist_scm_policy),
                                selected = scmPolicy,
                                options = SdCacheScmPolicy.entries.map { it.cliName },
                                offLabel = stringResource(R.string.sd_dist_picker_off),
                                onSelected = { scmPolicy = it }
                            )
                        }
                    )
                }
            }
        }

        CollapsibleFarmCard(
            title = stringResource(R.string.sd_dist_command_preview),
            subtitle = stringResource(R.string.sd_dist_card_expert_desc),
            expanded = expertExpanded,
            onExpandedChange = { expertExpanded = it },
            accent = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
        ) {
            LabeledTextField(R.string.sd_dist_custom_flags, customFlags, { customFlags = it }, R.string.sd_dist_custom_flags_hint)
            CodeBlock(text = preview.ifBlank { "-" })
            ResponsiveActionGroup {
                Button(onClick = { saveDraftSettings(announce = true) }) {
                    Icon(Icons.Default.Save, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.sd_dist_save_apply))
                }
                OutlinedButton(onClick = { navController.navigate(Screen.SdDistributedNetwork.route) }) {
                    Icon(Icons.Default.Share, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.sd_dist_view_network))
                }
            }
        }
    }
}
