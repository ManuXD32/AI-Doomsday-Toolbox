package com.example.llamadroid.ui.ai

import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.example.llamadroid.R
import com.example.llamadroid.data.SharedFileHolder
import com.example.llamadroid.data.SharedFileTarget
import com.example.llamadroid.data.SettingsRepository
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.data.db.ModelType
import com.example.llamadroid.service.resolveVideoReuseSources
import com.example.llamadroid.service.VideoReuseDraftAdapter
import com.example.llamadroid.service.VideoReusePayload
import com.example.llamadroid.service.VideoReuseHandoffStore
import com.example.llamadroid.service.VideoReuseTarget
import com.example.llamadroid.service.GeneratedVideoMetadata
import com.example.llamadroid.service.GenerationQueueRepository
import com.example.llamadroid.service.GenerationQueueRuntime
import com.example.llamadroid.service.GenerationQueueSnapshot
import com.example.llamadroid.service.SamplingMethod
import com.example.llamadroid.service.SdCacheMode
import com.example.llamadroid.service.SdCacheScmPolicy
import com.example.llamadroid.service.SdScheduler
import com.example.llamadroid.service.VideoGenerationConfig
import com.example.llamadroid.service.VideoGenerationMode
import com.example.llamadroid.service.VideoGenerationService
import com.example.llamadroid.service.VideoGenerationState
import com.example.llamadroid.service.VideoGenerationStateHolder
import com.example.llamadroid.service.VideoRuntimeOptions
import com.example.llamadroid.service.parseVideoRuntimeOptions
import com.example.llamadroid.service.toJsonString
import com.example.llamadroid.service.loadGeneratedVideoMetadata
import com.example.llamadroid.sd.SdLoraApplyMode
import com.example.llamadroid.sd.SdVideoComponentRole
import com.example.llamadroid.sd.SdTaeCheck
import com.example.llamadroid.sd.SdVideoInputs
import com.example.llamadroid.sd.SdVideoWorkflow
import com.example.llamadroid.sd.isSdVideoMainModel
import com.example.llamadroid.sd.sdVideoComponentCandidates
import com.example.llamadroid.sd.resolvedSdVideoFamily
import com.example.llamadroid.sd.toJsonArray
import com.example.llamadroid.sd.toSdLoraSpecs
import com.example.llamadroid.sd.validateSdLoras
import com.example.llamadroid.ui.components.SdSchedulerPicker
import com.example.llamadroid.ui.components.AppAdvancedSection
import com.example.llamadroid.ui.components.AppScrollableTabRow
import com.example.llamadroid.ui.components.AppScreenScaffold
import com.example.llamadroid.ui.components.AppStateKind
import com.example.llamadroid.ui.components.AppStatePanel
import com.example.llamadroid.ui.components.AppTaskActionFooter
import com.example.llamadroid.ui.components.VideoRuntimeOptionsEditor
import com.example.llamadroid.ui.components.ImportedVideoImage
import com.example.llamadroid.ui.components.importVideoImage
import com.example.llamadroid.ui.components.videoComponentLabel
import com.example.llamadroid.ui.components.videoGenerationReadiness
import com.example.llamadroid.ui.components.videoInputLabel
import com.example.llamadroid.ui.components.videoLorasForValidation
import com.example.llamadroid.ui.walkthrough.WalkthroughScrollOwner
import com.example.llamadroid.ui.walkthrough.LocalWalkthroughTargets
import com.example.llamadroid.ui.walkthrough.walkthroughTarget
import com.example.llamadroid.ui.navigation.Screen
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideoGenScreen(navController: NavController, initialTab: String = "create") {
    val context = LocalContext.current
    val resources = LocalResources.current
    val walkthroughTargets = LocalWalkthroughTargets.current
    val scope = rememberCoroutineScope()
    val queueRepository = remember(context) { GenerationQueueRepository(context) }
    val queueRunning by GenerationQueueRuntime.active.collectAsState()
    val queuePendingCount by queueRepository.pendingCountFlow.collectAsState(initial = 0)
    val batteryGateState = rememberBatteryOptimizationGateState()
    val settingsRepo = remember { SettingsRepository(context) }
    val restoredDraft = remember { settingsRepo.videoGenerationDraft() }
    val externalVideoInputPending = remember {
        SharedFileHolder.pendingFile.value?.target == SharedFileTarget.VIDEO_GENERATION
    }
    var reuseBackendOverrides by remember { mutableStateOf(restoredDraft?.takeIf { it.has("reuseSdParamsBackendMode") }?.let { org.json.JSONObject(it.toString()) }) }
    val keepScreenAwakeDuringGeneration by settingsRepo.keepScreenAwakeDuringGeneration.collectAsState()
    val sdMaxCpuRamEnabled by settingsRepo.sdMaxCpuRamEnabled.collectAsState()
    val sdMaxCpuRamGiB by settingsRepo.sdMaxCpuRamGiB.collectAsState()
    val selectedSdNativeBinary by settingsRepo.stableDiffusionNativeBinarySelection.collectAsState()
    val videoBinaryRepository = remember { com.example.llamadroid.data.binary.BinaryRepository(context) }
    var videoBinaryCapabilities by remember { mutableStateOf<com.example.llamadroid.service.SdBinaryCapabilities?>(null) }
    var videoBinaryProbePending by remember { mutableStateOf(true) }
    var videoBinaryProbeUnavailable by remember { mutableStateOf(false) }
    var videoBinaryProbeRequest by remember { mutableIntStateOf(0) }
    LaunchedEffect(selectedSdNativeBinary, videoBinaryProbeRequest) {
        videoBinaryProbePending = true
        videoBinaryProbeUnavailable = false
        videoBinaryCapabilities = null
        val capabilities = withContext(Dispatchers.IO) {
            try {
                videoBinaryRepository.getSdBinary()
                    ?.takeIf { it.exists() && it.isFile }
                    ?.let {
                        com.example.llamadroid.service.probeSdBinaryCapabilities(
                            context,
                            it,
                            videoBinaryRepository
                        )
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

    val videoGenModels by db.modelDao().getModelsByTypes(
        listOf(ModelType.SD_DIFFUSION, ModelType.SD_CHECKPOINT)
    )
        .collectAsState(initial = emptyList())
    val vaeModels by db.modelDao().getModelsByType(ModelType.SD_VAE)
        .collectAsState(initial = emptyList())
    val t5xxlModels by db.modelDao().getModelsByType(ModelType.SD_T5XXL)
        .collectAsState(initial = emptyList())
    val taeModels by db.modelDao().getModelsByType(ModelType.SD_TAE)
        .collectAsState(initial = emptyList())
    val llmModels by db.modelDao().getModelsByType(ModelType.LLM)
        .collectAsState(initial = emptyList())
    val llmVisionModels by db.modelDao().getModelsByTypes(listOf(ModelType.VISION_PROJECTOR, ModelType.MMPROJ))
        .collectAsState(initial = emptyList())
    val audioVaeModels by db.modelDao().getModelsByType(ModelType.SD_AUDIO_VAE)
        .collectAsState(initial = emptyList())
    val embeddingsConnectorModels by db.modelDao().getModelsByType(ModelType.SD_EMBEDDINGS_CONNECTORS)
        .collectAsState(initial = emptyList())
    val motionModuleModels by db.modelDao().getModelsByType(ModelType.SD_MOTION_MODULE)
        .collectAsState(initial = emptyList())
    val controlNetModels by db.modelDao().getModelsByType(ModelType.SD_CONTROLNET)
        .collectAsState(initial = emptyList())
    val clipVisionModels by db.modelDao().getModelsByType(ModelType.SD_CLIP_VISION)
        .collectAsState(initial = emptyList())
    val ipAdapterModels by db.modelDao().getModelsByType(ModelType.SD_IP_ADAPTER)
        .collectAsState(initial = emptyList())
    val loraModels by db.modelDao().getModelsByType(ModelType.SD_LORA)
        .collectAsState(initial = emptyList())
    val upscalerModels by db.modelDao().getModelsByType(ModelType.SD_UPSCALER)
        .collectAsState(initial = emptyList())

    val availableVideoModels = remember(videoGenModels) {
        videoGenModels.filter { it.isSdVideoMainModel() }
    }
    val mainTabStateHolder = rememberSaveableStateHolder()
    var mainTab by rememberSaveable(initialTab) {
        mutableIntStateOf(if (initialTab.equals("gallery", ignoreCase = true)) 1 else 0)
    }
    var selectedMode by remember { mutableIntStateOf(restoredDraft?.optInt("mode", 0) ?: 0) }
    var galleryFilter by remember { mutableIntStateOf(0) }

    var selectedVideoModelPath by remember { mutableStateOf(restoredDraft?.optString("model").orEmpty().ifBlank { null }) }
    val selectedVideoModel = availableVideoModels.firstOrNull { it.path == selectedVideoModelPath }
    val installedVideoPool = remember(
        videoGenModels, vaeModels, taeModels, t5xxlModels, llmModels, llmVisionModels, audioVaeModels,
        embeddingsConnectorModels, motionModuleModels, upscalerModels, controlNetModels, clipVisionModels, loraModels
    ) {
        (videoGenModels + vaeModels + taeModels + t5xxlModels + llmModels + llmVisionModels + audioVaeModels +
            embeddingsConnectorModels + motionModuleModels + upscalerModels + controlNetModels +
            clipVisionModels + loraModels).distinctBy { it.path }
    }
    val videoComponentModels = remember(installedVideoPool, selectedVideoModel) {
        SdVideoComponentRole.entries.associateWith {
            sdVideoComponentCandidates(selectedVideoModel, installedVideoPool, it)
        }
    }
    val compatibleVideoLoraModels = remember(videoComponentModels) {
        videoComponentModels[SdVideoComponentRole.LORA].orEmpty().map { it.model }
    }
    val installedVideoSdModels = remember(
        videoGenModels, vaeModels, taeModels, t5xxlModels, llmModels, llmVisionModels,
        audioVaeModels, embeddingsConnectorModels, motionModuleModels, clipVisionModels
    ) {
        (videoGenModels + vaeModels + taeModels + t5xxlModels + llmModels + llmVisionModels +
            audioVaeModels + embeddingsConnectorModels + motionModuleModels + clipVisionModels)
            .distinctBy { it.path }
    }
    var prompt by remember { mutableStateOf(restoredDraft?.optString("prompt").orEmpty()) }
    var negativePrompt by remember { mutableStateOf(restoredDraft?.optString("negativePrompt").orEmpty()) }
    var selectedSampler by remember { mutableStateOf(SamplingMethod.entries.firstOrNull { it.name == restoredDraft?.optString("sampler") } ?: SamplingMethod.EULER) }
    var selectedScheduler by remember { mutableStateOf(SdScheduler.fromCliName(restoredDraft?.optString("scheduler"))) }

    var useVae by remember { mutableStateOf(restoredDraft?.optBoolean("useVae", false) ?: false) }
    var selectedVaePath by remember { mutableStateOf(restoredDraft?.optString("vae").orEmpty().ifBlank { null }) }
    var useT5xxl by remember { mutableStateOf(restoredDraft?.optBoolean("useT5", false) ?: false) }
    var selectedT5xxlPath by remember { mutableStateOf(restoredDraft?.optString("t5").orEmpty().ifBlank { null }) }
    val restoredVideoLoras = remember(restoredDraft) {
        restoredDraft?.optJSONArray("loras")?.toSdLoraSpecs().orEmpty()
    }
    val restoredHighNoiseLoras = remember(restoredDraft) {
        restoredDraft?.optJSONArray("highNoiseLoras")?.toSdLoraSpecs().orEmpty()
    }
    var videoLoras by remember(restoredDraft) { mutableStateOf(restoredVideoLoras) }
    var videoHighNoiseLoras by remember(restoredDraft) { mutableStateOf(restoredHighNoiseLoras) }
    var videoLoraApplyMode by remember(restoredDraft) {
        mutableStateOf(SdLoraApplyMode.fromStoredValue(restoredDraft?.optString("loraApplyMode")))
    }
    var videoRuntimeOptions by remember(restoredDraft) {
        mutableStateOf(
            parseVideoRuntimeOptions(restoredDraft?.optString("videoAdvancedJson").orEmpty())
                ?.takeIf {
                    val raw = restoredDraft?.optString("videoAdvancedJson").orEmpty().trim()
                    raw.isNotBlank() && raw != "{}"
                }
                ?: VideoRuntimeOptions(
                    workflow = if ((restoredDraft?.optInt("mode", 0) ?: 0) == 1) {
                        SdVideoWorkflow.IMAGE_TO_VIDEO
                    } else {
                        SdVideoWorkflow.TEXT_TO_VIDEO
                    },
                    videoComponents = com.example.llamadroid.sd.SdVideoComponentPaths(
                        diffusionModelPath = restoredDraft?.optString("model").orEmpty().ifBlank { null },
                        vaePath = restoredDraft?.optString("vae").orEmpty().ifBlank { null },
                        t5xxlPath = restoredDraft?.optString("t5").orEmpty().ifBlank { null }
                    ),
                    videoInputs = SdVideoInputs(
                        initImagePath = restoredDraft?.optString("input").orEmpty().ifBlank { null }
                    ),
                    seed = restoredDraft?.optLong("seed", -1L) ?: -1L,
                    useTae = false
                )
        )
    }

    var selectedImageUri by remember { mutableStateOf<Uri?>(null) }
    // Keep an unavailable saved path visible so the picker can explain or replace it; the
    // generation gate below performs the asynchronous readability check.
    var selectedImagePath by remember { mutableStateOf(restoredDraft?.optString("input").orEmpty().ifBlank { null }) }
    var imageResolution by remember { mutableStateOf<Pair<Int, Int>?>(null) }

    var videoFramesText by remember { mutableStateOf(restoredDraft?.optString("frames", "8") ?: "8") }
    var fpsText by remember { mutableStateOf(restoredDraft?.optString("fps", "5") ?: "5") }
    var widthText by remember { mutableStateOf(restoredDraft?.optString("width", "480") ?: "480") }
    var heightText by remember { mutableStateOf(restoredDraft?.optString("height", "832") ?: "832") }
    var stepsText by remember { mutableStateOf(restoredDraft?.optString("steps", "18") ?: "18") }
    var cfgScaleText by remember { mutableStateOf(restoredDraft?.optString("cfg", "6.0") ?: "6.0") }
    var threadsText by remember { mutableStateOf(restoredDraft?.optString("threads", "-1") ?: "-1") }
    var flowShiftEnabled by remember { mutableStateOf(restoredDraft?.optBoolean("flowShiftEnabled", false) ?: false) }
    var flowShiftText by remember { mutableStateOf(restoredDraft?.optString("flowShift").orEmpty()) }
    var vaeTileSize by remember { mutableStateOf(restoredDraft?.optString("vaeTileSize", "24x24") ?: "24x24") }
    var vaeTiling by remember { mutableStateOf(restoredDraft?.optBoolean("vaeTiling", true) ?: true) }
    var diffusionFa by remember { mutableStateOf(restoredDraft?.optBoolean("diffusionFa", true) ?: true) }
    var diffusionConvDirect by remember { mutableStateOf(restoredDraft?.optBoolean("diffConv", false) ?: false) }
    var vaeConvDirect by remember { mutableStateOf(restoredDraft?.optBoolean("vaeConv", false) ?: false) }
    var mmap by remember { mutableStateOf(restoredDraft?.optBoolean("mmap", true) ?: true) }
    val acceleratorPlacement = when (selectedSdNativeBinary) {
        SettingsRepository.NATIVE_BINARY_SD_SNAPDRAGON_VULKAN -> "vulkan0"
        SettingsRepository.NATIVE_BINARY_SD_SNAPDRAGON_OPENCL -> "opencl0"
        else -> null
    }
    var textEncoderPlacement by remember { mutableStateOf(restoredDraft?.optString("tePlacement").orEmpty().ifBlank { "cpu" }) }
    var diffusionPlacement by remember { mutableStateOf(restoredDraft?.optString("diffusionPlacement").orEmpty().ifBlank { acceleratorPlacement ?: "cpu" }) }
    var vaePlacement by remember { mutableStateOf(restoredDraft?.optString("vaePlacement").orEmpty().ifBlank { "cpu" }) }
    LaunchedEffect(acceleratorPlacement) {
        if (reuseBackendOverrides == null && acceleratorPlacement != null && diffusionPlacement !in listOf("vulkan0", "opencl0")) {
            diffusionPlacement = acceleratorPlacement
        }
    }

    var errorMessage by remember { mutableStateOf<String?>(null) }
    var warningMessage by remember { mutableStateOf<String?>(null) }
    var selectedGalleryVideo by remember { mutableStateOf<GeneratedVideoMetadata?>(null) }
    var showInfoDialog by remember { mutableStateOf(false) }
    var cacheMode by remember { mutableStateOf(SdCacheMode.fromStoredValue(restoredDraft?.optString("cacheMode").orEmpty().ifBlank { null })) }
    var cacheOption by remember { mutableStateOf(restoredDraft?.optString("cacheOption").orEmpty()) }
    var scmMask by remember { mutableStateOf(restoredDraft?.optString("scmMask").orEmpty()) }
    var scmPolicy by remember { mutableStateOf(SdCacheScmPolicy.fromStoredValue(restoredDraft?.optString("scmPolicy").orEmpty().ifBlank { null })) }
    var manualCommandFlags by remember { mutableStateOf(restoredDraft?.optString("flags").orEmpty()) }

    fun captureVideoDraft(): org.json.JSONObject = org.json.JSONObject().apply {
                put("mode", selectedMode); put("model", selectedVideoModelPath); put("prompt", prompt); put("negativePrompt", negativePrompt)
                put("useVae", useVae); put("vae", selectedVaePath); put("useT5", useT5xxl); put("t5", selectedT5xxlPath); put("input", selectedImagePath)
                put("frames", videoFramesText); put("fps", fpsText); put("width", widthText); put("height", heightText); put("steps", stepsText); put("cfg", cfgScaleText); put("threads", threadsText); put("sampler", selectedSampler.name); put("scheduler", selectedScheduler?.cliName)
                put("flowShiftEnabled", flowShiftEnabled); put("flowShift", flowShiftText); put("vaeTileSize", vaeTileSize); put("vaeTiling", vaeTiling); put("diffusionFa", diffusionFa); put("mmap", mmap); put("cacheMode", cacheMode?.cliName); put("cacheOption", cacheOption); put("scmMask", scmMask); put("scmPolicy", scmPolicy?.cliName); put("diffConv", diffusionConvDirect); put("vaeConv", vaeConvDirect); put("flags", manualCommandFlags)
                put("loras", videoLoras.toJsonArray()); put("highNoiseLoras", videoHighNoiseLoras.toJsonArray()); put("loraApplyMode", videoLoraApplyMode?.cliName)
                put("tePlacement", textEncoderPlacement); put("diffusionPlacement", diffusionPlacement); put("vaePlacement", vaePlacement)
                put("videoAdvancedJson", videoRuntimeOptions.toJsonString())
                reuseBackendOverrides?.let { overrides ->
                    listOf("reuseSdParamsBackendMode", "reuseSdParamsBackendSpec", "reuseSdRuntimeBackendMode", "reuseMaxVramCpuGiB").forEach { key ->
                        if (overrides.has(key)) put(key, overrides.optString(key))
                    }
                }
            }

    DisposableEffect(Unit) {
        onDispose { settingsRepo.setVideoGenerationDraft(captureVideoDraft()) }
    }

    // Persist edits while the screen remains open, while retaining the disposal write as a
    // final synchronous snapshot for navigation and process teardown.
    LaunchedEffect(settingsRepo) {
        snapshotFlow { captureVideoDraft().toString() }
            .distinctUntilChanged()
            .collectLatest { draftJson ->
                delay(VIDEO_DRAFT_PERSIST_DEBOUNCE_MS)
                withContext(Dispatchers.IO) {
                    settingsRepo.setVideoGenerationDraft(org.json.JSONObject(draftJson))
                }
            }
    }

    val outputDir = remember {
        File(context.filesDir, "video_gen_output").apply { mkdirs() }
    }
    var galleryVideos by remember { mutableStateOf<List<GeneratedVideoMetadata>>(emptyList()) }

    val modeStateHolder = remember(selectedMode) { VideoGenerationStateHolder.getForModeIndex(selectedMode) }
    val generationState by modeStateHolder.state.collectAsState()
    val progress by modeStateHolder.progress.collectAsState()
    val status by modeStateHolder.status.collectAsState()
    val persistedPrompt by modeStateHolder.currentPrompt.collectAsState()

    val isBusy = generationState is VideoGenerationState.Generating ||
        generationState is VideoGenerationState.Converting ||
        generationState is VideoGenerationState.Copying
    GenerationKeepScreenAwakeEffect(enabled = keepScreenAwakeDuringGeneration && isBusy)

    fun reloadGallery() {
        val loaded = loadGeneratedVideoMetadata(outputDir)
        galleryVideos = loaded
        VideoGenerationStateHolder.txt2vid.setVideos(loaded.filter { it.modeEnum == VideoGenerationMode.TXT2VID })
        VideoGenerationStateHolder.img2vid.setVideos(loaded.filter { it.modeEnum == VideoGenerationMode.IMG2VID })
    }

    fun loadImageInput(uri: Uri) {
        scope.launch {
            var imported: ImportedVideoImage? = null
            try {
                imported = importVideoImage(context, uri)
                selectedImageUri = uri
                imageResolution = imported.width to imported.height
                selectedImagePath = imported.file.absolutePath
                videoRuntimeOptions = videoRuntimeOptions.copy(
                    videoInputs = videoRuntimeOptions.videoInputs.copy(initImagePath = imported.file.absolutePath)
                )
            } catch (cancelled: CancellationException) {
                imported?.file?.delete()
                throw cancelled
            } catch (_: Exception) {
                imported?.file?.delete()
                errorMessage = resources.getString(R.string.video_input_import_failed)
            }
        }
    }

    LaunchedEffect(Unit) {
        reloadGallery()
        val pendingFile = SharedFileHolder.consumeFor(SharedFileTarget.VIDEO_GENERATION)
        if (pendingFile != null && pendingFile.mimeType.startsWith("image/")) {
            selectedMode = 1
            mainTab = 0
            loadImageInput(pendingFile.uri)
        }
    }

    LaunchedEffect(selectedMode) {
        if (persistedPrompt.isNotBlank()) {
            prompt = persistedPrompt
        }
    }

    LaunchedEffect(prompt, selectedMode) {
        modeStateHolder.updatePrompt(prompt)
    }

    LaunchedEffect(generationState) {
        when (val state = generationState) {
            is VideoGenerationState.Complete -> {
                warningMessage = state.warningMessage
                errorMessage = null
                reloadGallery()
            }
            is VideoGenerationState.Error -> {
                errorMessage = state.message
            }
            else -> Unit
        }
    }

    // Legacy scalar drafts and the typed editor share one effective prerequisite view. The
    // typed paths win, while old saved fields keep existing drafts launchable after migration.
    val videoEditorOptions = videoRuntimeOptions.copy(
        workflow = videoRuntimeOptions.workflow ?: if (selectedMode == 1) {
            SdVideoWorkflow.IMAGE_TO_VIDEO
        } else {
            SdVideoWorkflow.TEXT_TO_VIDEO
        },
        videoComponents = videoRuntimeOptions.videoComponents.copy(
            diffusionModelPath = videoRuntimeOptions.videoComponents.diffusionModelPath
                ?: selectedVideoModelPath.takeIf {
                    videoRuntimeOptions.videoComponents.fullModelPath == null && selectedVideoModel?.type != ModelType.SD_CHECKPOINT
                },
            fullModelPath = videoRuntimeOptions.videoComponents.fullModelPath
                ?: selectedVideoModelPath.takeIf {
                    videoRuntimeOptions.videoComponents.diffusionModelPath == null && selectedVideoModel?.type == ModelType.SD_CHECKPOINT
                },
            vaePath = videoRuntimeOptions.videoComponents.vaePath ?: selectedVaePath.takeIf { useVae },
            t5xxlPath = videoRuntimeOptions.videoComponents.t5xxlPath ?: selectedT5xxlPath.takeIf { useT5xxl }
        ),
        videoInputs = videoRuntimeOptions.videoInputs.copy(
            initImagePath = videoRuntimeOptions.videoInputs.initImagePath ?: selectedImagePath
        )
    )
    val videoReadiness = videoGenerationReadiness(videoEditorOptions)
    val effectiveVideoInputPath = videoEditorOptions.videoInputs.initImagePath
    val videoPathReferences = remember(
        videoEditorOptions,
        selectedVideoModelPath,
        videoLoras,
        videoHighNoiseLoras
    ) {
        videoGenerationPathReferences(
            options = videoEditorOptions,
            selectedModelPath = selectedVideoModelPath,
            loras = videoLoras,
            highNoiseLoras = videoHighNoiseLoras
        )
    }
    val videoPathsAvailable by produceState<Boolean?>(
        initialValue = null,
        videoPathReferences,
        availableVideoModels
    ) {
        value = null
        value = withContext(Dispatchers.IO) {
            videoPathReferences.all(::videoPathReferenceAvailable)
        }
    }

    val videoTaeCheck by produceState<SdTaeCheck?>(null, videoEditorOptions, availableVideoModels) {
        value = null
        value = withContext(Dispatchers.IO) { videoTaePreflight(videoEditorOptions) }
    }

    val generateVideo = generation@ fun(addToQueue: Boolean) {
        val mode = if (selectedMode == 1) VideoGenerationMode.IMG2VID else VideoGenerationMode.TXT2VID
        val frames = videoFramesText.toIntOrNull()
        val fps = fpsText.toIntOrNull()
        val width = widthText.toIntOrNull()
        val height = heightText.toIntOrNull()
        val steps = stepsText.toIntOrNull()
        val cfgScale = cfgScaleText.toFloatOrNull()
        val threads = threadsText.toIntOrNull()
        val flowShift = if (flowShiftEnabled) flowShiftText.toFloatOrNull() else null

        val loraError = runCatching { validateSdLoras(videoLorasForValidation(videoLoras, videoHighNoiseLoras)) }.exceptionOrNull()
        if (loraError != null) {
            errorMessage = loraError.message ?: resources.getString(R.string.sd_workflow_gate_missing)
            return@generation
        }

        when {
            selectedVideoModelPath == null -> {
                errorMessage = resources.getString(R.string.video_gen_error_model_required)
                return
            }
            prompt.isBlank() -> {
                errorMessage = resources.getString(R.string.video_gen_error_prompt_required)
                return
            }
            !videoReadiness.isSatisfied -> {
                errorMessage = when {
                    videoReadiness.unsupportedWorkflow -> resources.getString(
                        R.string.video_controls_profile_unsupported_workflow
                    )
                    videoReadiness.missingComponents.isNotEmpty() -> resources.getString(
                        R.string.video_controls_profile_missing_components,
                        videoReadiness.missingComponents.map { videoComponentLabel(resources, it) }.joinToString()
                    )
                    else -> resources.getString(
                        R.string.video_controls_profile_missing_inputs,
                        videoReadiness.missingInputs.map { videoInputLabel(resources, it) }.joinToString()
                    )
                }
                return
            }
            videoPathsAvailable != true -> {
                errorMessage = resources.getString(R.string.video_controls_unavailable_values_hint)
                return
            }
            videoTaeErrorMessage(resources, videoTaeCheck) != null -> {
                errorMessage = videoTaeErrorMessage(resources, videoTaeCheck)
                return
            }
            mode == VideoGenerationMode.IMG2VID && effectiveVideoInputPath == null -> {
                errorMessage = resources.getString(R.string.video_gen_error_input_image_required)
                return
            }
            useVae && selectedVaePath == null -> {
                errorMessage = resources.getString(R.string.video_gen_error_vae_required)
                return
            }
            useT5xxl && selectedT5xxlPath == null -> {
                errorMessage = resources.getString(R.string.video_gen_error_t5xxl_required)
                return
            }
            frames == null || frames < 2 -> {
                errorMessage = resources.getString(R.string.video_output_requires_two_frames)
                return
            }
            fps == null || fps <= 0 -> {
                errorMessage = resources.getString(R.string.video_gen_error_invalid_number, stringResourceSafe(context, R.string.video_gen_fps_label))
                return
            }
            width == null || width <= 0 -> {
                errorMessage = resources.getString(R.string.video_gen_error_invalid_number, stringResourceSafe(context, R.string.video_gen_width_label))
                return
            }
            height == null || height <= 0 -> {
                errorMessage = resources.getString(R.string.video_gen_error_invalid_number, stringResourceSafe(context, R.string.video_gen_height_label))
                return
            }
            steps == null || steps <= 0 -> {
                errorMessage = resources.getString(R.string.video_gen_error_invalid_number, stringResourceSafe(context, R.string.video_gen_steps_label))
                return
            }
            cfgScale == null || !cfgScale.isFinite() || cfgScale <= 0f -> {
                errorMessage = resources.getString(R.string.video_gen_error_invalid_number, stringResourceSafe(context, R.string.video_gen_cfg_scale_label))
                return
            }
            threads == null -> {
                errorMessage = resources.getString(R.string.video_gen_error_invalid_number, stringResourceSafe(context, R.string.video_gen_threads_label))
                return
            }
            flowShiftEnabled && (flowShift == null || !flowShift.isFinite()) -> {
                errorMessage = resources.getString(R.string.video_gen_error_invalid_number, stringResourceSafe(context, R.string.video_gen_flow_shift_label))
                return
            }
        }

        val selectedVideoFamily = selectedVideoModel?.resolvedSdVideoFamily()?.first
        if (selectedVideoFamily != null) {
            val incompatibleLoras = (videoLoras + videoHighNoiseLoras).filter { spec ->
                spec.enabled && compatibleVideoLoraModels.none { model -> model.path == spec.path }
            }
            if (incompatibleLoras.isNotEmpty()) {
                errorMessage = resources.getString(
                    R.string.video_controls_lora_missing_or_incompatible,
                    incompatibleLoras.joinToString { it.path.substringAfterLast('/').ifBlank { it.path } }
                )
                return@generation
            }
        }

        errorMessage = null
        warningMessage = null

        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val baseName = "video_$timestamp"
        val modeDir = File(outputDir, mode.folderName).apply { mkdirs() }

        val effectiveVideoOptions = videoEditorOptions

        val config = VideoGenerationConfig(
            mode = mode,
            prompt = prompt,
            negativePrompt = negativePrompt,
            diffusionModelPath = selectedVideoModelPath ?: "",
            outputAviPath = File(modeDir, "$baseName.avi").absolutePath,
            outputMp4Path = File(modeDir, "$baseName.mp4").absolutePath,
            metadataPath = File(modeDir, "$baseName.json").absolutePath,
            initImagePath = if (mode == VideoGenerationMode.IMG2VID) effectiveVideoInputPath else null,
            useVae = useVae,
            vaePath = if (useVae) selectedVaePath else null,
            useT5xxl = useT5xxl,
            t5xxlPath = if (useT5xxl) selectedT5xxlPath else null,
            videoFrames = frames ?: 8,
            fps = fps ?: 5,
            width = width ?: 480,
            height = height ?: 832,
            steps = steps ?: 18,
            cfgScale = cfgScale ?: 6.0f,
            flowShift = flowShift,
            samplingMethod = selectedSampler,
            scheduler = selectedScheduler,
            cacheMode = cacheMode,
            cacheOption = cacheOption,
            scmMask = scmMask,
            scmPolicy = scmPolicy,
            vaeTiling = vaeTiling,
            vaeTileSize = vaeTileSize,
            diffusionFa = diffusionFa,
            diffusionConvDirect = diffusionConvDirect,
            vaeConvDirect = vaeConvDirect,
            mmap = mmap,
            threads = threads ?: -1,
            loras = videoLoras,
            highNoiseLoras = videoHighNoiseLoras,
            loraApplyMode = videoLoraApplyMode,
            sdParamsBackendSpec = reuseBackendOverrides?.optString("reuseSdParamsBackendSpec") ?: selectedVideoModel?.sdParamsBackendSpec ?: "auto",
            sdParamsBackendMode = reuseBackendOverrides?.optString("reuseSdParamsBackendMode") ?: selectedVideoModel?.sdParamsBackendMode ?: "auto",
            sdRuntimeBackendMode = reuseBackendOverrides?.optString("reuseSdRuntimeBackendMode") ?: acceleratorPlacement?.let {
                "te=$textEncoderPlacement,diffusion=$diffusionPlacement,vae=$vaePlacement"
            } ?: selectedVideoModel?.sdRuntimeBackendMode ?: "auto",
            maxVramCpuGiB = reuseBackendOverrides?.optString("reuseMaxVramCpuGiB") ?: if (sdMaxCpuRamEnabled) sdMaxCpuRamGiB else "",
            customFlags = manualCommandFlags,
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

        if (addToQueue) {
            scope.launch {
                runCatching { queueRepository.add(GenerationQueueSnapshot.video(context, config)) }
                    .onSuccess {
                        android.widget.Toast.makeText(context, R.string.generation_queue_added,
                            android.widget.Toast.LENGTH_SHORT).show()
                    }
                    .onFailure { error ->
                        errorMessage = error.message ?: resources.getString(R.string.generation_queue_add_failed)
                    }
            }
        } else batteryGateState.runAfterCheck {
            context.startForegroundService(VideoGenerationService.createStartIntent(context, config))
        }
    }

    val cancelVideo: () -> Unit = {
        val mode = if (selectedMode == 1) VideoGenerationMode.IMG2VID else VideoGenerationMode.TXT2VID
        context.startService(VideoGenerationService.createCancelIntent(context, mode))
        warningMessage = null
    }

    BatteryOptimizationWarningDialog(state = batteryGateState)

    fun onVideoOptionsChange(next: VideoRuntimeOptions) {
        videoRuntimeOptions = next
        selectedVideoModelPath = videoMainModelPathForSelection(next)
        selectedVaePath = next.videoComponents.vaePath
        useVae = selectedVaePath != null && !next.useTae
        selectedT5xxlPath = next.videoComponents.t5xxlPath
        useT5xxl = selectedT5xxlPath != null
        selectedImagePath = next.videoInputs.initImagePath
        selectedMode = if (next.workflow in setOf(SdVideoWorkflow.IMAGE_TO_VIDEO,
            SdVideoWorkflow.FIRST_LAST_FRAME, SdVideoWorkflow.IMAGE_TO_AUDIO_VIDEO,
            SdVideoWorkflow.FIRST_LAST_TO_AUDIO_VIDEO)) 1 else 0
    }

    // Preset cards keep the user's prompt; only the scalar form defaults of the preset are applied.
    fun applyVideoScalarDefaults(defaults: VideoScalarDefaults) {
        reuseBackendOverrides = null
        useVae = defaults.useVae
        selectedVaePath = null
        useT5xxl = false
        selectedT5xxlPath = null
        videoFramesText = defaults.frames.toString()
        fpsText = defaults.fps.toString()
        widthText = defaults.width.toString()
        heightText = defaults.height.toString()
        stepsText = defaults.steps.toString()
        cfgScaleText = defaults.cfgScaleText
        flowShiftEnabled = true
        flowShiftText = defaults.flowShiftText
        threadsText = defaults.threads.toString()
        mmap = defaults.mmap
        diffusionFa = defaults.diffusionFa
        selectedSampler = defaults.sampler
        selectedScheduler = defaults.scheduler
        videoLoras = emptyList()
        videoHighNoiseLoras = emptyList()
        errorMessage = null
        warningMessage = null
    }

    fun applyVideoReuse(payload: VideoReusePayload, verifiedSources: Map<String, String> = emptyMap()) {
        val draft = VideoReuseDraftAdapter.toLocalDraftJson(payload, verifiedSources = verifiedSources)
        fun text(key: String): String = draft.optString(key).takeUnless { it == "null" }.orEmpty()
        fun path(key: String): String? = text(key).takeIf(String::isNotBlank)
        val nextMode = draft.optInt("mode", 0)
        VideoGenerationStateHolder.getForModeIndex(nextMode).updatePrompt(text("prompt"))
        selectedMode = nextMode
        prompt = text("prompt")
        negativePrompt = text("negativePrompt")
        selectedVideoModelPath = path("model")
        useVae = draft.optBoolean("useVae")
        selectedVaePath = path("vae")
        useT5xxl = draft.optBoolean("useT5")
        selectedT5xxlPath = path("t5")
        selectedImageUri = null
        selectedImagePath = path("input")
        imageResolution = null
        videoFramesText = text("frames")
        fpsText = text("fps")
        widthText = text("width")
        heightText = text("height")
        stepsText = text("steps")
        cfgScaleText = text("cfg")
        threadsText = text("threads")
        selectedSampler = SamplingMethod.entries.firstOrNull { it.name == text("sampler") } ?: SamplingMethod.EULER
        selectedScheduler = SdScheduler.fromCliName(text("scheduler"))
        flowShiftEnabled = draft.optBoolean("flowShiftEnabled")
        flowShiftText = text("flowShift")
        vaeTileSize = text("vaeTileSize")
        vaeTiling = draft.optBoolean("vaeTiling")
        diffusionFa = draft.optBoolean("diffusionFa")
        diffusionConvDirect = draft.optBoolean("diffConv")
        vaeConvDirect = draft.optBoolean("vaeConv")
        mmap = draft.optBoolean("mmap")
        cacheMode = SdCacheMode.fromStoredValue(path("cacheMode"))
        cacheOption = text("cacheOption")
        scmMask = text("scmMask")
        scmPolicy = SdCacheScmPolicy.fromStoredValue(path("scmPolicy"))
        manualCommandFlags = text("flags")
        videoLoras = draft.optJSONArray("loras")?.toSdLoraSpecs().orEmpty()
        videoHighNoiseLoras = draft.optJSONArray("highNoiseLoras")?.toSdLoraSpecs().orEmpty()
        videoLoraApplyMode = SdLoraApplyMode.fromStoredValue(path("loraApplyMode"))
        videoRuntimeOptions = parseVideoRuntimeOptions(text("videoAdvancedJson")) ?: VideoRuntimeOptions()
        textEncoderPlacement = text("tePlacement")
        diffusionPlacement = text("diffusionPlacement")
        vaePlacement = text("vaePlacement")
        reuseBackendOverrides = draft
        mainTab = 0
        errorMessage = null
        warningMessage = resources.getString(R.string.video_detail_reuse_warning)
        settingsRepo.setVideoGenerationDraft(captureVideoDraft())
    }

    LaunchedEffect(Unit) {
        try {
            val handoff = withContext(Dispatchers.IO) { VideoReuseHandoffStore.peek(context) }
            if (handoff?.target == VideoReuseTarget.LOCAL && externalVideoInputPending) {
                // A new share is a newer explicit intent than an undelivered gallery selection.
                withContext(Dispatchers.IO) { VideoReuseHandoffStore.complete(context, handoff) }
            } else if (handoff?.target == VideoReuseTarget.LOCAL) {
                val result = withContext(Dispatchers.IO) { VideoReuseHandoffStore.readPayload(context, handoff) }
                result?.payload?.let { payload ->
                    applyVideoReuse(payload, resolveVideoReuseSources(context, payload))
                    withContext(Dispatchers.IO) { VideoReuseHandoffStore.complete(context, handoff) }
                } ?: run { errorMessage = resources.getString(R.string.video_detail_reuse_failed) }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            errorMessage = resources.getString(R.string.video_detail_reuse_failed)
        }
    }

    val filteredGalleryVideos = remember(galleryVideos, galleryFilter) {
        when (galleryFilter) {
            1 -> galleryVideos.filter { it.modeEnum == VideoGenerationMode.TXT2VID }
            2 -> galleryVideos.filter { it.modeEnum == VideoGenerationMode.IMG2VID }
            else -> galleryVideos
        }
    }

    AppScreenScaffold(
        title = stringResource(R.string.video_gen_title),
        onBack = { navController.popBackStack() },
        actions = {
            GenerationQueueHeaderAction(navController, pendingCount = queuePendingCount)
            GenerationSettingsHeaderAction(navController)
            IconButton(onClick = { showInfoDialog = true }) {
                Icon(Icons.Default.Info, contentDescription = stringResource(R.string.gen_help_open))
            }
        }
    ) { _ ->
    Column(modifier = Modifier.fillMaxSize()) {

        AppScrollableTabRow(
            selectedTabIndex = mainTab,
            modifier = Modifier.padding(horizontal = 20.dp),
            edgePadding = 12.dp
        ) {
            listOf(
                stringResource(R.string.video_gen_tab_generate),
                stringResource(R.string.video_gen_tab_gallery)
            ).forEachIndexed { index, label ->
                Tab(
                    selected = mainTab == index,
                    modifier = Modifier.walkthroughTarget(
                        if (index == 0) "video.create_tab" else "video.gallery_tab"
                    ),
                    onClick = {
                        mainTab = index
                        if (index == 0) {
                            walkthroughTargets?.recordEvent("video.create_tab")
                        } else {
                            walkthroughTargets?.recordEvent("video.gallery_tab")
                            walkthroughTargets?.recordEvent("video.gallery")
                        }
                    },
                    text = { Text(label) }
                )
            }
        }

        mainTabStateHolder.SaveableStateProvider(mainTab) {
            if (mainTab == 0) {
                val formScroll = rememberLazyListState()
                WalkthroughScrollOwner(setOf("video.prompt", "video.models", "video.profile", "video.inputs", "video.advanced", "video.loras")) { target ->
                    val leadingItems = 1 + (if (reuseBackendOverrides != null) 1 else 0)
                    formScroll.animateScrollToItem(
                        when (target) {
                            "video.profile" -> 0 // model-picker card
                            "video.prompt" -> leadingItems + 1
                            else -> leadingItems
                        }
                    )
                }
                LazyColumn(
                    state = formScroll,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(bottom = 16.dp)
                ) {
                    item(key = "model-picker") {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                        ) {
                            VideoGenModelSection(
                                options = videoEditorOptions,
                                installedModels = installedVideoSdModels,
                                onOptionsChange = ::onVideoOptionsChange,
                                onOpenModelManager = { navController.navigate(Screen.SDModels.route) },
                                onScalarDefaults = ::applyVideoScalarDefaults,
                                modifier = Modifier.padding(16.dp)
                            )
                        }
                    }

                    reuseBackendOverrides?.let { overrides ->
                        item(key = "reused-device-settings") {
                            VideoReuseBackendCard(overrides) { next ->
                                reuseBackendOverrides = next
                                if (next == null) {
                                    textEncoderPlacement = "cpu"
                                    diffusionPlacement = acceleratorPlacement ?: "cpu"
                                    vaePlacement = "cpu"
                                }
                            }
                        }
                    }
                    item(key = "typed-video-options") {
                            Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                VideoRuntimeOptionsEditor(
                                    options = videoEditorOptions,
                                    modifier = Modifier.walkthroughTarget("video.models"),
                                    onOptionsChange = ::onVideoOptionsChange,
                                    componentModels = videoComponentModels,
                                    loraModels = compatibleVideoLoraModels,
                                    loras = videoLoras,
                                    highNoiseLoras = videoHighNoiseLoras,
                                    onLorasChange = { videoLoras = it },
                                    onHighNoiseLorasChange = { videoHighNoiseLoras = it },
                                    loraApplyMode = videoLoraApplyMode,
                                    onLoraApplyModeChange = { videoLoraApplyMode = it },
                                    binaryCapabilities = videoBinaryCapabilities,
                                    binaryProbePending = videoBinaryProbePending,
                                    binaryProbeUnavailable = videoBinaryProbeUnavailable,
                                    onRetryBinaryProbe = { videoBinaryProbeRequest++ },
                                    onOpenBinarySettings = {
                                        navController.navigate("settings_imagegen")
                                    },
                                    uncondDiffusionModels = availableVideoModels,
                                    ipAdapterModels = ipAdapterModels
                                )
                            }
                        }
                    }

                    item(key = "prompts") { Card(
                        modifier = Modifier.fillMaxWidth().walkthroughTarget("video.prompt"),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                stringResource(R.string.video_gen_prompt_label),
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedTextField(
                                value = prompt,
                                onValueChange = { prompt = it },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(120.dp),
                                placeholder = { Text(stringResource(R.string.video_gen_prompt_placeholder)) },
                                shape = RoundedCornerShape(12.dp)
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            AppAdvancedSection(title = stringResource(R.string.ui_redesign_gen_more_prompt), initiallyExpanded = negativePrompt.isNotBlank()) {
                                OutlinedTextField(
                                    value = negativePrompt,
                                    onValueChange = { negativePrompt = it },
                                    modifier = Modifier.fillMaxWidth(),
                                    label = { Text(stringResource(R.string.video_gen_negative_prompt_label)) },
                                    placeholder = { Text(stringResource(R.string.video_gen_negative_prompt_placeholder)) },
                                    shape = RoundedCornerShape(12.dp)
                                )
                            }
                        }
                    } }

                    item(key = "parameters") { Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                stringResource(R.string.video_gen_parameters_title),
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                VideoNumberField(
                                    modifier = Modifier.weight(1f),
                                    label = stringResource(R.string.video_gen_frames_label),
                                    value = videoFramesText,
                                    onValueChange = { videoFramesText = it }
                                )
                                VideoNumberField(
                                    modifier = Modifier.weight(1f),
                                    label = stringResource(R.string.video_gen_fps_label),
                                    value = fpsText,
                                    onValueChange = { fpsText = it }
                                )
                            }
                            Spacer(modifier = Modifier.height(12.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                VideoNumberField(
                                    modifier = Modifier.weight(1f),
                                    label = stringResource(R.string.video_gen_width_label),
                                    value = widthText,
                                    onValueChange = { widthText = it }
                                )
                                VideoNumberField(
                                    modifier = Modifier.weight(1f),
                                    label = stringResource(R.string.video_gen_height_label),
                                    value = heightText,
                                    onValueChange = { heightText = it }
                                )
                            }
                            Spacer(modifier = Modifier.height(12.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                VideoNumberField(
                                    modifier = Modifier.weight(1f),
                                    label = stringResource(R.string.video_gen_steps_label),
                                    value = stepsText,
                                    onValueChange = { stepsText = it }
                                )
                                VideoTextField(
                                    modifier = Modifier.weight(1f),
                                    label = stringResource(R.string.video_gen_cfg_scale_label),
                                    value = cfgScaleText,
                                    keyboardType = KeyboardType.Decimal,
                                    onValueChange = { cfgScaleText = it }
                                )
                            }
                            Spacer(modifier = Modifier.height(12.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                VideoTextField(
                                    modifier = Modifier.weight(1f),
                                    label = stringResource(R.string.video_gen_threads_label),
                                    value = threadsText,
                                    keyboardType = KeyboardType.Number,
                                    onValueChange = { threadsText = it }
                                )
                                Column(
                                    modifier = Modifier.weight(1f),
                                    verticalArrangement = Arrangement.Center
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Checkbox(
                                            checked = flowShiftEnabled,
                                            onCheckedChange = {
                                                flowShiftEnabled = it
                                                if (!it) {
                                                    flowShiftText = ""
                                                }
                                            }
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(stringResource(R.string.video_gen_flow_shift_toggle_label))
                                    }
                                }
                            }
                            if (flowShiftEnabled) {
                                Spacer(modifier = Modifier.height(12.dp))
                                VideoTextField(
                                    modifier = Modifier.fillMaxWidth(),
                                    label = stringResource(R.string.video_gen_flow_shift_label),
                                    value = flowShiftText,
                                    keyboardType = KeyboardType.Decimal,
                                    onValueChange = { flowShiftText = it }
                                )
                            }
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                stringResource(R.string.video_gen_sampler_label),
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            var samplerExpanded by remember { mutableStateOf(false) }
                            ExposedDropdownMenuBox(
                                expanded = samplerExpanded,
                                onExpandedChange = { samplerExpanded = !samplerExpanded }
                            ) {
                                OutlinedTextField(
                                    value = selectedSampler.cliName,
                                    onValueChange = {},
                                    readOnly = true,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .menuAnchor(),
                                    trailingIcon = {
                                        ExposedDropdownMenuDefaults.TrailingIcon(expanded = samplerExpanded)
                                    },
                                    shape = RoundedCornerShape(12.dp)
                                )
                                ExposedDropdownMenu(
                                    expanded = samplerExpanded,
                                    onDismissRequest = { samplerExpanded = false }
                                ) {
                                    SamplingMethod.entries.forEach { sampler ->
                                        DropdownMenuItem(
                                            text = { Text(sampler.cliName) },
                                            onClick = {
                                                selectedSampler = sampler
                                                samplerExpanded = false
                                            }
                                        )
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.height(12.dp))
                            SdSchedulerPicker(
                                value = selectedScheduler,
                                onValueChange = { selectedScheduler = it }
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(
                                    checked = vaeTiling,
                                    onCheckedChange = { vaeTiling = it }
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(stringResource(R.string.video_gen_vae_tiling_label))
                            }
                            if (vaeTiling) {
                                Spacer(modifier = Modifier.height(8.dp))
                                VideoTextField(
                                    modifier = Modifier.fillMaxWidth(),
                                    label = stringResource(R.string.video_gen_vae_tile_size_label),
                                    value = vaeTileSize,
                                    onValueChange = { vaeTileSize = it }
                                )
                            }
                            Spacer(modifier = Modifier.height(12.dp))
                            VideoBooleanOption(stringResource(R.string.video_gen_diffusion_fa_label), diffusionFa) { diffusionFa = it }
                            VideoBooleanOption(stringResource(R.string.video_gen_mmap_label), mmap) { mmap = it }
                            VideoBooleanOption(stringResource(R.string.imagegen_diffusion_conv_direct_label), diffusionConvDirect) { diffusionConvDirect = it }
                            VideoBooleanOption(stringResource(R.string.imagegen_vae_conv_direct_label), vaeConvDirect) { vaeConvDirect = it }
                            acceleratorPlacement?.let { accelerator ->
                                Spacer(modifier = Modifier.height(12.dp))
                                SdBackendPlacementControls(
                                    accelerator = accelerator,
                                    textEncoder = textEncoderPlacement,
                                    diffusion = diffusionPlacement,
                                    vae = vaePlacement,
                                    onTextEncoderChange = { textEncoderPlacement = it },
                                    onDiffusionChange = { diffusionPlacement = it },
                                    onVaeChange = { vaePlacement = it }
                                )
                            }
                            Spacer(modifier = Modifier.height(16.dp))
                            VideoLocalSdCliMemoryControls(
                                selectedModel = selectedVideoModel,
                                maxRamEnabled = sdMaxCpuRamEnabled,
                                maxRamGiB = sdMaxCpuRamGiB,
                                onParamsBackendChange = { mode ->
                                    selectedVideoModel?.let { model ->
                                        scope.launch {
                                            db.modelDao().insertModel(
                                                model.copy(sdParamsBackendMode = mode.storedValue)
                                            )
                                        }
                                    }
                                },
                                onRuntimeBackendChange = { mode ->
                                    selectedVideoModel?.let { model ->
                                        scope.launch {
                                            db.modelDao().insertModel(
                                                model.copy(sdRuntimeBackendMode = mode.storedValue)
                                            )
                                        }
                                    }
                                },
                                onMaxRamEnabledChange = { settingsRepo.setSdMaxCpuRamEnabled(it) },
                                onMaxRamGiBChange = { settingsRepo.setSdMaxCpuRamGiB(it) }
                            )
                        }
                    } }

                    item(key = "cache") { GenerationCachingCard(
                        title = stringResource(R.string.gen_cache_title),
                        cacheMode = cacheMode,
                        onCacheModeChange = { cacheMode = it },
                        cacheOption = cacheOption,
                        onCacheOptionChange = { cacheOption = it },
                        scmPolicy = scmPolicy,
                        onScmPolicyChange = { scmPolicy = it },
                        scmMask = scmMask,
                        onScmMaskChange = { scmMask = it },
                        guidanceFamily = GenerationCacheGuidanceFamily.VIDEO_DIT,
                        enabled = true,
                        disabledMessage = null
                    ) }

                    item(key = "manual-flags") {
                        AppAdvancedSection(title = stringResource(R.string.soft_studio_advanced)) {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(16.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                            ) {
                                Column(modifier = Modifier.padding(16.dp)) {
                                    Text(
                                        stringResource(R.string.sd_manual_flags_label),
                                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                                    )
                                    Text(
                                        stringResource(R.string.sd_manual_flags_desc),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Spacer(modifier = Modifier.height(12.dp))
                                    OutlinedTextField(
                                        value = manualCommandFlags,
                                        onValueChange = { manualCommandFlags = it },
                                        modifier = Modifier.fillMaxWidth(),
                                        label = { Text(stringResource(R.string.sd_manual_flags_label)) },
                                        placeholder = { Text(stringResource(R.string.sd_manual_flags_hint)) },
                                        minLines = 2,
                                        shape = RoundedCornerShape(12.dp)
                                    )
                                }
                            }
                        }
                    }

                    item(key = "run-state") { if (isBusy) {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    stringResource(R.string.video_gen_running_title),
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                Text(status.ifBlank { stringResource(R.string.video_gen_status_starting) })
                                Spacer(modifier = Modifier.height(8.dp))
                                androidx.compose.material3.LinearProgressIndicator(
                                    progress = { progress.coerceIn(0f, 1f) },
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    } else {
                    } }

                    warningMessage?.let { warning ->
                        item(key = "warning") { Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.75f)
                            ),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.Warning, contentDescription = null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(warning, color = MaterialTheme.colorScheme.onTertiaryContainer)
                            }
                        } }
                    }

                    errorMessage?.let { error ->
                        item(key = "error") { Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text(
                                error,
                                modifier = Modifier.padding(12.dp),
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        } }
                    }

                    if (generationState is VideoGenerationState.Complete) {
                        item(key = "complete") { Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    stringResource(R.string.video_gen_success),
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                        } }
                    }
                }
            } else {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp)
                ) {
                    val filters = listOf(
                        stringResource(R.string.video_gen_gallery_all),
                        stringResource(R.string.video_gen_mode_txt2vid),
                        stringResource(R.string.video_gen_mode_img2vid)
                    )
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(filters.size) { index ->
                            FilterChip(selected = galleryFilter == index,
                                onClick = { galleryFilter = index },
                                label = { Text(filters[index], maxLines = 1) },
                                modifier = Modifier.heightIn(min = 48.dp))
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    if (filteredGalleryVideos.isEmpty()) {
                        AppStatePanel(
                            kind = AppStateKind.Empty,
                            title = stringResource(R.string.soft_studio_empty_title),
                            message = if (galleryFilter == 0) {
                                stringResource(R.string.video_gen_gallery_empty)
                            } else {
                                stringResource(R.string.video_gen_gallery_empty_filter, filters[galleryFilter])
                            },
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        LazyColumn(
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.fillMaxSize().walkthroughTarget("video.gallery")
                        ) {
                            items(filteredGalleryVideos, key = { it.preferredArtifactPath }) { video ->
                                VideoGalleryCard(
                                    metadata = video,
                                    onClick = { selectedGalleryVideo = video }
                                )
                            }
                        }
                    }
                }
            }
        }
        if (mainTab == 0 || isBusy) {
            AppTaskActionFooter(
                modifier = Modifier
                    .fillMaxWidth()
            ) {
                if (isBusy) {
                    Text(
                        text = status.ifBlank { stringResource(R.string.video_gen_status_starting) },
                        modifier = Modifier.fillMaxWidth(),
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    LinearProgressIndicator(
                        progress = { progress.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedButton(
                        onClick = cancelVideo,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Icon(Icons.Default.Close, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.soft_studio_cancel))
                    }
                    OutlinedButton(
                        onClick = { generateVideo(true) },
                        enabled = selectedVideoModelPath != null && prompt.isNotBlank() &&
                            videoReadiness.isSatisfied && videoPathsAvailable == true && videoBinaryReady &&
                            (selectedMode == 0 || effectiveVideoInputPath != null),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    ) { Text(stringResource(R.string.generation_queue_add)) }
                } else {
                    val readyToGenerate = selectedVideoModelPath != null && prompt.isNotBlank() &&
                        videoReadiness.isSatisfied && videoPathsAvailable == true && videoBinaryReady &&
                        (selectedMode == 0 || effectiveVideoInputPath != null)
                    GenerationStartAndQueueButtons(
                        startLabel = stringResource(if (selectedMode == 0)
                            R.string.video_gen_generate_txt2vid else R.string.video_gen_generate_img2vid),
                        startEnabled = readyToGenerate && !queueRunning,
                        addEnabled = readyToGenerate,
                        onStart = { generateVideo(false) },
                        onAdd = { generateVideo(true) },
                        modifier = Modifier.walkthroughTarget("video.generate")
                    )
                }
            }
        }
    }
    }

    selectedGalleryVideo?.let { metadata ->
        VideoGalleryDetail(metadata, navController,
            onDismiss = { selectedGalleryVideo = null },
            onDeleted = { reloadGallery() },
            onReuseLocal = { payload, sources ->
                applyVideoReuse(payload, sources)
                scope.launch(Dispatchers.IO) { VideoReuseHandoffStore.peek(context)?.let { handoff ->
                    if (handoff.target == VideoReuseTarget.LOCAL && handoff.metadataPath == metadata.metadataPath) VideoReuseHandoffStore.complete(context, handoff)
                } }
            },
            localDraftEdited = VideoReuseDraftAdapter.hasMeaningfulLocalDraft(captureVideoDraft()))
    }

    if (showInfoDialog) {
        GenerationOptionsInfoDialog(
            title = stringResource(R.string.video_gen_help_title),
            sections = buildVideoGenerationHelpSections(selectedMode = selectedMode),
            onDismiss = { showInfoDialog = false }
        )
    }
}
