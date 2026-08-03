package com.example.llamadroid.ui.ai

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.navigation.NavController
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.data.db.ModelEntity
import com.example.llamadroid.data.db.ModelType
import com.example.llamadroid.data.db.SD_CAPABILITY_IMG2IMG
import com.example.llamadroid.data.db.SD_CAPABILITY_TXT2IMG
import com.example.llamadroid.data.db.parseSdCapabilities
import com.example.llamadroid.data.SettingsRepository
import com.example.llamadroid.data.binary.BinaryRepository
import com.example.llamadroid.sd.SdCacheArchitecture
import com.example.llamadroid.sd.SdComponentRole
import com.example.llamadroid.sd.SdImageInputMode
import com.example.llamadroid.sd.SdLoraApplyMode
import com.example.llamadroid.sd.SdParamsBackendMode
import com.example.llamadroid.sd.SdRuntimeBackendMode
import com.example.llamadroid.sd.effectiveSdCompatProfiles
import com.example.llamadroid.sd.isSdImageMainModel
import com.example.llamadroid.sd.matchesSdFamily
import com.example.llamadroid.sd.resolveSdFamilySpec
import com.example.llamadroid.sd.resolvedSdFamily
import com.example.llamadroid.service.*
import com.example.llamadroid.ui.navigation.Screen
import androidx.compose.ui.res.stringResource
import com.example.llamadroid.R
import com.example.llamadroid.ui.components.DraftLongTextField
import com.example.llamadroid.ui.components.SliderWithInput
import com.example.llamadroid.ui.components.IntSliderWithInput
import com.example.llamadroid.ui.components.SdSchedulerPicker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*

/**
 * Image Generation Screen using stable-diffusion.cpp
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImageGenScreen(navController: NavController, initialMode: Int = 0) {
    val context = LocalContext.current
    val startupGuard = rememberAiJobStartupGuard()


    val db = remember { AppDatabase.getDatabase(context) }
    val binaryRepo = remember { BinaryRepository(context) }
    val settingsRepo = remember { SettingsRepository(context) }
    val restoredDraft = remember { settingsRepo.imageGenerationDraft() }
    val batteryGateState = rememberBatteryOptimizationGateState()
    val keepScreenAwakeDuringGeneration by settingsRepo.keepScreenAwakeDuringGeneration.collectAsState()
    val sdVaeTiling by settingsRepo.sdVaeTiling.collectAsState()
    val sdVaeTileOverlap by settingsRepo.sdVaeTileOverlap.collectAsState()
    val sdVaeTileSize by settingsRepo.sdVaeTileSize.collectAsState()
    val sdVaeRelativeTileSize by settingsRepo.sdVaeRelativeTileSize.collectAsState()
    val sdTensorTypeRules by settingsRepo.sdTensorTypeRules.collectAsState()
    val sdMaxCpuRamEnabled by settingsRepo.sdMaxCpuRamEnabled.collectAsState()
    val sdMaxCpuRamGiB by settingsRepo.sdMaxCpuRamGiB.collectAsState()
    val selectedSdNativeBinary by settingsRepo.stableDiffusionNativeBinarySelection.collectAsState()
    val scope = rememberCoroutineScope()

    // Available SD models - Classic checkpoints (SD1.5/SDXL)
    val sdCheckpoints by db.modelDao().getModelsByType(ModelType.SD_CHECKPOINT)
        .collectAsState(initial = emptyList())

    // Image family main/component models
    val fluxDiffusionModels by db.modelDao().getModelsByType(ModelType.SD_DIFFUSION)
        .collectAsState(initial = emptyList())
    val vaeModels by db.modelDao().getModelsByType(ModelType.SD_VAE)
        .collectAsState(initial = emptyList())
    val clipLModels by db.modelDao().getModelsByType(ModelType.SD_CLIP_L)
        .collectAsState(initial = emptyList())
    val clipGModels by db.modelDao().getModelsByType(ModelType.SD_CLIP_G)
        .collectAsState(initial = emptyList())
    val t5xxlModels by db.modelDao().getModelsByType(ModelType.SD_T5XXL)
        .collectAsState(initial = emptyList())
    val taeModels by db.modelDao().getModelsByType(ModelType.SD_TAE)
        .collectAsState(initial = emptyList())
    val controlNetModels by db.modelDao().getModelsByType(ModelType.SD_CONTROLNET)
        .collectAsState(initial = emptyList())
    val loraModels by db.modelDao().getModelsByType(ModelType.SD_LORA)
        .collectAsState(initial = emptyList())
    val textualInversionModels by db.modelDao()
        .getModelsByType(ModelType.SD_TEXTUAL_INVERSION)
        .collectAsState(initial = emptyList())
    val photoMakerModels by db.modelDao().getModelsByType(ModelType.SD_PHOTOMAKER)
        .collectAsState(initial = emptyList())
    val imageSupportModels by db.modelDao().getModelsByTypes(listOf(ModelType.LLM, ModelType.VISION_PROJECTOR))
        .collectAsState(initial = emptyList())

    // Available upscaler models
    val upscalerModels by db.modelDao().getModelsByType(ModelType.SD_UPSCALER)
        .collectAsState(initial = emptyList())

    // Mode selection: 0 = txt2img, 1 = img2img, 2 = upscale
    var selectedMode by remember(initialMode) { mutableIntStateOf(restoredDraft?.optInt("mode", if (initialMode == 1) 1 else 0) ?: if (initialMode == 1) 1 else 0) }

    // Combined model list for selection (checkpoints + FLUX diffusion)
    val allGenerationModels = (sdCheckpoints + fluxDiffusionModels)
        .filter { it.isSdImageMainModel() }
    // UI State
    var selectedGenerationModelPath by remember { mutableStateOf(restoredDraft?.optString("model").orEmpty().ifBlank { null }) }
    var selectedUpscalerModelPath by remember { mutableStateOf(restoredDraft?.optString("upscaler").orEmpty().ifBlank { null }) }
    var prompt by remember { mutableStateOf(restoredDraft?.optString("prompt").orEmpty()) }
    var negativePrompt by remember { mutableStateOf(restoredDraft?.optString("negativePrompt").orEmpty()) }
    var showAdvanced by remember { mutableStateOf(restoredDraft?.optBoolean("advanced", false) ?: false) }
    var showInfoDialog by remember { mutableStateOf(false) }

    // Family component selections
    var selectedVaePath by remember { mutableStateOf(restoredDraft?.optString("vae").orEmpty().ifBlank { null }) }
    var selectedTaePath by remember { mutableStateOf(restoredDraft?.optString("tae").orEmpty().ifBlank { null }) }
    var selectedClipLPath by remember { mutableStateOf(restoredDraft?.optString("clipL").orEmpty().ifBlank { null }) }
    var selectedClipGPath by remember { mutableStateOf(restoredDraft?.optString("clipG").orEmpty().ifBlank { null }) }
    var selectedT5xxlPath by remember { mutableStateOf(restoredDraft?.optString("t5").orEmpty().ifBlank { null }) }
    var selectedLlmPath by remember { mutableStateOf(restoredDraft?.optString("llm").orEmpty().ifBlank { null }) }
    var selectedLlmVisionPath by remember { mutableStateOf(restoredDraft?.optString("llmVision").orEmpty().ifBlank { null }) }
    var selectedPhotoMakerPath by remember { mutableStateOf(restoredDraft?.optString("photoMaker").orEmpty().ifBlank { null }) }

    // ControlNet settings (optional)
    var controlNetEnabled by remember { mutableStateOf(restoredDraft?.optBoolean("controlEnabled", false) ?: false) }
    var selectedControlNetPath by remember { mutableStateOf(restoredDraft?.optString("control").orEmpty().ifBlank { null }) }
    var controlStrength by remember { mutableFloatStateOf((restoredDraft?.optDouble("controlStrength", 0.9) ?: 0.9).toFloat()) }

    // LoRA settings (optional)
    var loraEnabled by remember { mutableStateOf(restoredDraft?.optBoolean("loraEnabled", false) ?: false) }
    var selectedLoraPath by remember { mutableStateOf(restoredDraft?.optString("lora").orEmpty().ifBlank { null }) }
    var loraStrength by remember { mutableFloatStateOf((restoredDraft?.optDouble("loraStrength", 1.0) ?: 1.0).toFloat()) }
    var selectedLoraApplyMode by remember { mutableStateOf(restoredDraft?.optString("loraApply").orEmpty().let { stored -> SdLoraApplyMode.entries.firstOrNull { it.cliName == stored } }) }
    var textualInversionEnabled by remember { mutableStateOf(restoredDraft?.optBoolean("textualEnabled", false) ?: false) }
    var selectedTextualInversionPath by remember {
        mutableStateOf(restoredDraft?.optString("textual").orEmpty().ifBlank { null })
    }
    var flowShiftText by remember { mutableStateOf(restoredDraft?.optString("flowShift").orEmpty()) }
    var selectedScheduler by remember { mutableStateOf(SdScheduler.fromCliName(restoredDraft?.optString("scheduler"))) }
    var diffusionFaEnabled by remember { mutableStateOf(restoredDraft?.optBoolean("diffusionFa", false) ?: false) }
    var diffusionConvDirectEnabled by remember { mutableStateOf(restoredDraft?.optBoolean("diffConv", false) ?: false) }
    var mmapEnabled by remember { mutableStateOf(restoredDraft?.optBoolean("mmap", false) ?: false) }
    var vaeConvDirectEnabled by remember { mutableStateOf(restoredDraft?.optBoolean("vaeConv", false) ?: false) }
    var qwenImageZeroCondTEnabled by remember { mutableStateOf(restoredDraft?.optBoolean("qwenZero", false) ?: false) }
    var chromaDisableDitMaskEnabled by remember { mutableStateOf(restoredDraft?.optBoolean("chromaMask", false) ?: false) }
    val acceleratorPlacement = when (selectedSdNativeBinary) {
        SettingsRepository.NATIVE_BINARY_SD_SNAPDRAGON_VULKAN -> "vulkan0"
        SettingsRepository.NATIVE_BINARY_SD_SNAPDRAGON_OPENCL -> "opencl0"
        else -> null
    }
    var textEncoderPlacement by remember { mutableStateOf(restoredDraft?.optString("tePlacement").orEmpty().ifBlank { "cpu" }) }
    var diffusionPlacement by remember { mutableStateOf(restoredDraft?.optString("diffusionPlacement").orEmpty().ifBlank { acceleratorPlacement ?: "cpu" }) }
    var vaePlacement by remember { mutableStateOf(restoredDraft?.optString("vaePlacement").orEmpty().ifBlank { "cpu" }) }
    LaunchedEffect(acceleratorPlacement) {
        if (acceleratorPlacement != null && diffusionPlacement !in listOf("vulkan0", "opencl0")) {
            diffusionPlacement = acceleratorPlacement
        }
    }

    // Main tab selection: 0 = Generate, 1 = Gallery
    var mainTab by remember { mutableIntStateOf(0) }

    // Gallery filter: 0 = All, 1 = txt2img, 2 = img2img, 3 = upscaled
    var galleryFilter by remember { mutableIntStateOf(0) }
    var gallerySourceFilter by remember { mutableIntStateOf(0) }
    val serverImagePaths by db.aiServerDao()
        .observeServerArtifactPathsByType(AiServerArtifactTypes.IMAGE)
        .collectAsState(initial = emptyList())
    val serverImagePathSet = remember(serverImagePaths) { serverImagePaths.toSet() }

    // Image input for img2img/upscale
    var selectedImageUri by remember { mutableStateOf<Uri?>(null) }
    var selectedImagePath by remember { mutableStateOf(restoredDraft?.optString("input").orEmpty().takeIf { it.isNotBlank() && File(it).canRead() }) }
    var imageResolution by remember { mutableStateOf<Pair<Int, Int>?>(null) }

    // Img2img strength
    var strength by remember { mutableFloatStateOf((restoredDraft?.optDouble("strength", 0.75) ?: 0.75).toFloat()) }

    // Quantization type for --type
    var selectedQuantType by remember { mutableStateOf(restoredDraft?.optString("quant").orEmpty()) }

    // Upscale factor and repeats
    var upscaleFactor by remember { mutableIntStateOf(restoredDraft?.optInt("upscaleFactor", 2) ?: 2) } // Default 2, will be auto-detected from model
    var upscaleRepeats by remember { mutableIntStateOf(restoredDraft?.optInt("upscaleRepeats", 1) ?: 1) } // User-controlled repeats (1-4)

    // Threads for generation (user-controlled, -1 = auto)
    var threads by remember { mutableIntStateOf(restoredDraft?.optInt("threads", -1) ?: -1) }

    // Keep the generation and upscale selections separate so mode switches do not
    // overwrite the last valid choice for the other mode.
    val selectedModelPath = if (selectedMode == 2) {
        selectedUpscalerModelPath
    } else {
        selectedGenerationModelPath
    }
    val modelsForSelectedMode = if (selectedMode == 2) upscalerModels else allGenerationModels
    val selectedMainModel = resolveImageGenSelectedMainModel(
        selectedMode = selectedMode,
        selectedModelPath = selectedGenerationModelPath,
        generationModels = allGenerationModels
    )
    val selectedActiveModel = modelsForSelectedMode.firstOrNull { it.path == selectedModelPath }
    val selectedFamilyInfo = selectedMainModel?.resolvedSdFamily()
    val selectedFamily = selectedFamilyInfo?.first
    val selectedVariant = selectedFamilyInfo?.second
    val selectedFamilySpec = selectedFamily?.let { resolveSdFamilySpec(it, selectedVariant) }
    val localMaxVramCpuGiB = if (sdMaxCpuRamEnabled) sdMaxCpuRamGiB else ""
    val effectiveCapabilities = run {
        val explicit = selectedMainModel?.sdCapabilities?.parseSdCapabilities().orEmpty()
        when {
            explicit.isNotEmpty() -> explicit
            selectedFamilySpec != null -> selectedFamilySpec.defaultCapabilities.parseSdCapabilities()
            else -> emptySet()
        }
    }
    val supportsTxt2Img = selectedMainModel == null || effectiveCapabilities.contains(SD_CAPABILITY_TXT2IMG)
    val supportsImg2Img = selectedMainModel == null || effectiveCapabilities.contains(SD_CAPABILITY_IMG2IMG)
    val componentRoles = selectedFamilySpec?.let { spec ->
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
    } ?: emptyList()
    val compatibleVaeModels = filterSdComponents(vaeModels, selectedFamily, selectedVariant)
    val compatibleTaeModels = filterSdComponents(taeModels, selectedFamily, selectedVariant)
    val compatibleClipLModels = filterSdComponents(clipLModels, selectedFamily, selectedVariant)
    val compatibleClipGModels = filterSdComponents(clipGModels, selectedFamily, selectedVariant)
    val compatibleT5xxlModels = filterSdComponents(t5xxlModels, selectedFamily, selectedVariant)
    val compatibleLlmModels = filterSdComponents(
        imageSupportModels.filter { it.type == ModelType.LLM && it.effectiveSdCompatProfiles().isNotEmpty() },
        selectedFamily,
        selectedVariant
    )
    val compatibleLlmVisionModels = filterSdComponents(
        imageSupportModels.filter { it.type == ModelType.VISION_PROJECTOR && it.effectiveSdCompatProfiles().isNotEmpty() },
        selectedFamily,
        selectedVariant
    )
    val compatibleControlNetModels = filterSdComponents(controlNetModels, selectedFamily, selectedVariant)
    val compatibleLoraModels = filterSdComponents(loraModels, selectedFamily, selectedVariant)
    val compatibleTextualInversionModels = filterSdComponents(
        textualInversionModels,
        selectedFamily,
        selectedVariant
    )
    val compatiblePhotoMakerModels = filterSdComponents(photoMakerModels, selectedFamily, selectedVariant)
    val missingRequiredComponents = selectedFamilySpec?.requiredRoles?.filter { role ->
        when (role) {
            SdComponentRole.VAE -> selectedVaePath.isNullOrBlank()
            SdComponentRole.TAE -> selectedTaePath.isNullOrBlank()
            SdComponentRole.CLIP_L -> selectedClipLPath.isNullOrBlank()
            SdComponentRole.CLIP_G -> selectedClipGPath.isNullOrBlank()
            SdComponentRole.T5XXL -> selectedT5xxlPath.isNullOrBlank()
            SdComponentRole.LLM -> selectedLlmPath.isNullOrBlank()
            SdComponentRole.LLM_VISION -> selectedLlmVisionPath.isNullOrBlank()
            SdComponentRole.PHOTOMAKER -> selectedPhotoMakerPath.isNullOrBlank()
            else -> false
        }
    } ?: emptyList()
    val imagePreparationScope = rememberCoroutineScope()
    val latestSelectedMode by rememberUpdatedState(selectedMode)

    // Check for shared file (from share intent)
    LaunchedEffect(Unit) {
        val pendingFile = com.example.llamadroid.data.SharedFileHolder.consumePendingFile()
        if (pendingFile != null && pendingFile.mimeType.startsWith("image/")) {
            try {
                val targetMode = when (resolveInitialImageGenMode(pendingFile.targetScreen)) {
                    1 -> 1
                    else -> 0
                }
                selectedMode = targetMode
                GenerationDiagnosticsStore.recordBreadcrumb(
                    source = IMAGE_GEN_UI_DIAGNOSTIC_SOURCE,
                    mode = when (targetMode) {
                        1 -> SDMode.IMG2IMG.name
                        2 -> SDMode.UPSCALE.name
                        else -> SDMode.TXT2IMG.name
                    },
                    event = "shared_image_prepare_started",
                    details = "targetScreen=${pendingFile.targetScreen}"
                )
                val preparedImage = prepareImageInputForMode(
                    context = context,
                    uri = pendingFile.uri,
                    targetMode = targetMode,
                    tempFileName = "shared_input_image.png"
                )
                preparedImage?.let {
                    imageResolution = it.resolution
                    selectedImagePath = it.path
                    selectedImageUri = pendingFile.uri
                }
                GenerationDiagnosticsStore.recordBreadcrumb(
                    source = IMAGE_GEN_UI_DIAGNOSTIC_SOURCE,
                    mode = when (targetMode) {
                        1 -> SDMode.IMG2IMG.name
                        2 -> SDMode.UPSCALE.name
                        else -> SDMode.TXT2IMG.name
                    },
                    event = "shared_image_prepare_finished",
                    details = "prepared=${preparedImage != null}"
                )
            } catch (e: Exception) {
                android.util.Log.e("ImageGenScreen", "Failed to load shared image: ${e.message}")
            }
        }
    }

    // Fullscreen gallery viewer
    var fullscreenImage by remember { mutableStateOf<File?>(null) }

    val imagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            imagePreparationScope.launch {
                val preparedImage = prepareImageInputForMode(
                    context = context,
                    uri = it,
                    targetMode = latestSelectedMode,
                    tempFileName = "input_image.png"
                )
                preparedImage?.let { prepared ->
                    imageResolution = prepared.resolution
                    selectedImagePath = prepared.path
                    selectedImageUri = prepared.uri
                }
            }
        }
    }

    // Generation parameters
    var width by remember { mutableIntStateOf(restoredDraft?.optInt("width", 512) ?: 512) }
    var height by remember { mutableIntStateOf(restoredDraft?.optInt("height", 512) ?: 512) }
    var steps by remember { mutableIntStateOf(restoredDraft?.optInt("steps", 20) ?: 20) }
    var cfgScale by remember { mutableFloatStateOf((restoredDraft?.optDouble("cfg", 7.0) ?: 7.0).toFloat()) }
    var seed by remember { mutableLongStateOf(restoredDraft?.optLong("seed", -1L) ?: -1L) }
    var selectedSampler by remember { mutableStateOf(SamplingMethod.entries.firstOrNull { it.name == restoredDraft?.optString("sampler") } ?: SamplingMethod.EULER_A) }
    var cacheMode by remember { mutableStateOf(SdCacheMode.fromStoredValue(restoredDraft?.optString("cacheMode").orEmpty().ifBlank { null })) }
    var cacheOption by remember { mutableStateOf(restoredDraft?.optString("cacheOption").orEmpty()) }
    var scmMask by remember { mutableStateOf(restoredDraft?.optString("scmMask").orEmpty()) }
    var scmPolicy by remember { mutableStateOf(SdCacheScmPolicy.fromStoredValue(restoredDraft?.optString("scmPolicy").orEmpty().ifBlank { null })) }
    var manualCommandFlags by remember { mutableStateOf(restoredDraft?.optString("flags").orEmpty()) }

    DisposableEffect(Unit) {
        onDispose {
            settingsRepo.setImageGenerationDraft(org.json.JSONObject().apply {
                put("mode", selectedMode); put("model", selectedGenerationModelPath); put("upscaler", selectedUpscalerModelPath)
                put("prompt", prompt); put("negativePrompt", negativePrompt); put("advanced", showAdvanced)
                put("vae", selectedVaePath); put("tae", selectedTaePath); put("clipL", selectedClipLPath); put("clipG", selectedClipGPath); put("t5", selectedT5xxlPath); put("llm", selectedLlmPath); put("llmVision", selectedLlmVisionPath); put("photoMaker", selectedPhotoMakerPath)
                put("controlEnabled", controlNetEnabled); put("control", selectedControlNetPath); put("controlStrength", controlStrength)
                put("loraEnabled", loraEnabled); put("lora", selectedLoraPath); put("loraStrength", loraStrength); put("loraApply", selectedLoraApplyMode?.cliName)
                put("textualEnabled", textualInversionEnabled); put("textual", selectedTextualInversionPath); put("flowShift", flowShiftText); put("diffusionFa", diffusionFaEnabled); put("diffConv", diffusionConvDirectEnabled); put("vaeConv", vaeConvDirectEnabled); put("mmap", mmapEnabled); put("qwenZero", qwenImageZeroCondTEnabled); put("chromaMask", chromaDisableDitMaskEnabled)
                put("input", selectedImagePath); put("strength", strength); put("quant", selectedQuantType); put("upscaleFactor", upscaleFactor); put("upscaleRepeats", upscaleRepeats); put("threads", threads)
                put("width", width); put("height", height); put("steps", steps); put("cfg", cfgScale); put("seed", seed); put("sampler", selectedSampler.name); put("scheduler", selectedScheduler?.cliName); put("cacheMode", cacheMode?.cliName); put("cacheOption", cacheOption); put("scmMask", scmMask); put("scmPolicy", scmPolicy?.cliName); put("flags", manualCommandFlags)
                put("tePlacement", textEncoderPlacement); put("diffusionPlacement", diffusionPlacement); put("vaePlacement", vaePlacement)
            })
        }
    }

    fun clearDiffusionModeState() {
        selectedVaePath = null
        selectedTaePath = null
        selectedClipLPath = null
        selectedClipGPath = null
        selectedT5xxlPath = null
        selectedLlmPath = null
        selectedLlmVisionPath = null
        selectedPhotoMakerPath = null
        selectedControlNetPath = null
        selectedLoraPath = null
        selectedLoraApplyMode = null
        controlNetEnabled = false
        loraEnabled = false
        cacheMode = null
        cacheOption = ""
        scmMask = ""
        scmPolicy = null
        flowShiftText = ""
        diffusionFaEnabled = false
        mmapEnabled = false
        vaeConvDirectEnabled = false
        qwenImageZeroCondTEnabled = false
        chromaDisableDitMaskEnabled = false
        selectedQuantType = ""
    }

    fun switchGenerationMode(targetMode: Int) {
        if (targetMode == selectedMode) return
        GenerationDiagnosticsStore.recordBreadcrumb(
            source = IMAGE_GEN_UI_DIAGNOSTIC_SOURCE,
            event = "mode_switch_requested",
            details = "from=$selectedMode to=$targetMode"
        )
        selectedMode = targetMode
    }

    val txt2imgModeHolder = remember { SDModeStateHolder.txt2img }
    val img2imgModeHolder = remember { SDModeStateHolder.img2img }
    val upscaleModeHolder = remember { SDModeStateHolder.upscale }

    val txt2imgGenerationState by txt2imgModeHolder.state.collectAsState()
    val img2imgGenerationState by img2imgModeHolder.state.collectAsState()
    val upscaleGenerationState by upscaleModeHolder.state.collectAsState()

    val txt2imgProgress by txt2imgModeHolder.progress.collectAsState()
    val img2imgProgress by img2imgModeHolder.progress.collectAsState()
    val upscaleProgress by upscaleModeHolder.progress.collectAsState()

    val txt2imgStatus by txt2imgModeHolder.status.collectAsState()
    val img2imgStatus by img2imgModeHolder.status.collectAsState()
    val upscaleStatus by upscaleModeHolder.status.collectAsState()

    val txt2imgGeneratedImages by txt2imgModeHolder.generatedImages.collectAsState()
    val img2imgGeneratedImages by img2imgModeHolder.generatedImages.collectAsState()
    val upscaleGeneratedImages by upscaleModeHolder.generatedImages.collectAsState()

    val txt2imgTotalSteps by txt2imgModeHolder.totalSteps.collectAsState()
    val img2imgTotalSteps by img2imgModeHolder.totalSteps.collectAsState()
    val upscaleTotalSteps by upscaleModeHolder.totalSteps.collectAsState()

    val txt2imgCurrentStep by txt2imgModeHolder.currentStep.collectAsState()
    val img2imgCurrentStep by img2imgModeHolder.currentStep.collectAsState()
    val upscaleCurrentStep by upscaleModeHolder.currentStep.collectAsState()

    val txt2imgPersistedPrompt by txt2imgModeHolder.currentPrompt.collectAsState()
    val img2imgPersistedPrompt by img2imgModeHolder.currentPrompt.collectAsState()
    val activeModeHolder = when (selectedMode) {
        1 -> img2imgModeHolder
        2 -> upscaleModeHolder
        else -> txt2imgModeHolder
    }
    val generationState = when (selectedMode) {
        1 -> img2imgGenerationState
        2 -> upscaleGenerationState
        else -> txt2imgGenerationState
    }
    val progress = when (selectedMode) {
        1 -> img2imgProgress
        2 -> upscaleProgress
        else -> txt2imgProgress
    }
    val generationStatus = when (selectedMode) {
        1 -> img2imgStatus
        2 -> upscaleStatus
        else -> txt2imgStatus
    }
    val generatedImages = when (selectedMode) {
        1 -> img2imgGeneratedImages
        2 -> upscaleGeneratedImages
        else -> txt2imgGeneratedImages
    }
    val totalStepsVal = when (selectedMode) {
        1 -> img2imgTotalSteps
        2 -> upscaleTotalSteps
        else -> txt2imgTotalSteps
    }
    val currentStepVal = when (selectedMode) {
        1 -> img2imgCurrentStep
        2 -> upscaleCurrentStep
        else -> txt2imgCurrentStep
    }
    LaunchedEffect(selectedMode) {
        val persistedPrompt = when (selectedMode) {
            1 -> img2imgPersistedPrompt
            else -> txt2imgPersistedPrompt
        }
        if (selectedMode != 2 && persistedPrompt.isNotBlank()) {
            prompt = persistedPrompt
        }
        GenerationDiagnosticsStore.recordBreadcrumb(
            source = IMAGE_GEN_UI_DIAGNOSTIC_SOURCE,
            mode = when (selectedMode) {
                1 -> SDMode.IMG2IMG.name
                2 -> SDMode.UPSCALE.name
                else -> SDMode.TXT2IMG.name
            },
            event = "mode_entered",
            details = "selectedMode=$selectedMode"
        )
        GenerationDiagnosticsStore.recordBreadcrumb(
            source = IMAGE_GEN_UI_DIAGNOSTIC_SOURCE,
            event = "mode_switch_committed",
            details = "selectedMode=$selectedMode"
        )
    }

    val isGenerating = generationState is SDGenerationState.Generating
    GenerationKeepScreenAwakeEffect(enabled = keepScreenAwakeDuringGeneration && isGenerating)
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // Update persisted prompt when user types
    LaunchedEffect(prompt, selectedMode) {
        if (selectedMode != 2) {
            activeModeHolder.updatePrompt(prompt)
        }
    }

    LaunchedEffect(allGenerationModels) {
        selectedGenerationModelPath = selectedGenerationModelPath?.takeIf { selectedPath ->
            allGenerationModels.any { it.path == selectedPath }
        }
    }

    LaunchedEffect(upscalerModels) {
        selectedUpscalerModelPath = selectedUpscalerModelPath?.takeIf { selectedPath ->
            upscalerModels.any { it.path == selectedPath }
        } ?: upscalerModels.firstOrNull()?.path
    }

    // Output directory for generated images
    val outputDir = remember { File(context.filesDir, "sd_output").apply { mkdirs() } }
    val galleryDirs = remember(outputDir) {
        listOf(
            outputDir,
            File(outputDir, "txt2img"),
            File(outputDir, "img2img"),
            File(outputDir, "upscaled"),
            File(outputDir, "workflow")
        )
    }

    // Local list of images from disk (for gallery)
    var diskImages by remember { mutableStateOf<List<File>>(emptyList()) }

    // Load existing images from disk (including subfolders)
    LaunchedEffect(Unit) {
        val allImages = mutableListOf<File>()
        galleryDirs.forEach { dir ->
                dir.listFiles()
                    ?.filter { it.extension.lowercase() in listOf("png", "jpg", "jpeg") }
                    ?.let { allImages.addAll(it) }
        }
        diskImages = allImages.sortedByDescending { it.lastModified() }
    }

    LaunchedEffect(txt2imgGenerationState, img2imgGenerationState, upscaleGenerationState, selectedMode) {
        val activeState = when (selectedMode) {
            1 -> img2imgGenerationState
            2 -> upscaleGenerationState
            else -> txt2imgGenerationState
        }
        when (val state = activeState) {
            is SDGenerationState.Error -> errorMessage = state.message
            is SDGenerationState.Complete -> {
                errorMessage = null
                val allImages = mutableListOf<File>()
                galleryDirs.forEach { dir ->
                    dir.listFiles()
                        ?.filter { it.extension.lowercase() in listOf("png", "jpg", "jpeg") }
                        ?.let { allImages.addAll(it) }
                }
                diskImages = allImages.sortedByDescending { it.lastModified() }
            }
            else -> Unit
        }
    }

    // Combine holder images with disk images for gallery
    val galleryImages = remember(generatedImages, diskImages) {
        (generatedImages + diskImages).distinctBy { it.absolutePath }
    }

    // Generate function - handles all modes
    val generate: () -> Unit = generate@{
        val modelPath = selectedModelPath
        val inputImagePath = selectedImagePath
        val mode = when (selectedMode) {
            1 -> SDMode.IMG2IMG
            2 -> SDMode.UPSCALE
            else -> SDMode.TXT2IMG
        }
        val sdBinaryPath = binaryRepo.getSdBinary()?.absolutePath
        val launchIssue = validateSdLaunchInputs(
            mode = mode,
            modelPath = modelPath,
            inputImagePath = inputImagePath,
            sdBinaryPath = sdBinaryPath
        )
        if (launchIssue != null) {
            val message = sdLaunchIssueMessage(context, mode, launchIssue)
            errorMessage = message
            GenerationDiagnosticsStore.recordBreadcrumb(
                source = IMAGE_GEN_UI_DIAGNOSTIC_SOURCE,
                mode = mode.name,
                event = "ui_preflight_failed",
                details = "issue=${launchIssue.name}"
            )
            return@generate
        }
        val modelFileExists = modelPath?.let { File(it).exists() } ?: false
        val inputImageExists = inputImagePath?.let { File(it).exists() } ?: false
        val canGenerate = when (selectedMode) {
            0 -> modelPath != null && modelFileExists && prompt.isNotBlank() && supportsTxt2Img && missingRequiredComponents.isEmpty()
            1 -> selectedModelPath != null &&
                modelFileExists &&
                prompt.isNotBlank() &&
                inputImagePath != null &&
                inputImageExists &&
                supportsImg2Img &&
                missingRequiredComponents.isEmpty()
            2 -> modelPath != null && modelFileExists && inputImagePath != null && inputImageExists
            else -> false
        }

        if (canGenerate) {
            errorMessage = null

            val subfolder = when (mode) {
                SDMode.TXT2IMG -> "txt2img"
                SDMode.IMG2IMG -> "img2img"
                SDMode.UPSCALE -> "upscaled"
            }

            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val filename = "sd_$timestamp.png"
            val modeOutputDir = File(outputDir, subfolder).apply { mkdirs() }
            val outputFile = File(modeOutputDir, filename)

            val threadCount = if (threads > 0) threads else when (mode) {
                SDMode.TXT2IMG -> settingsRepo.sdTxt2imgThreads.value
                SDMode.IMG2IMG -> settingsRepo.sdImg2imgThreads.value
                SDMode.UPSCALE -> settingsRepo.sdUpscaleThreads.value
            }

            if (mode == SDMode.UPSCALE) {
                val config = SDUpscaleConfig(
                    modelPath = modelPath ?: "",
                    outputPath = outputFile.absolutePath,
                    inputImagePath = inputImagePath ?: "",
                    upscaleRepeats = upscaleRepeats,
                    threads = threadCount,
                    sdParamsBackendMode = selectedActiveModel?.sdParamsBackendMode ?: "auto",
                    sdRuntimeBackendMode = acceleratorPlacement?.let {
                        "te=$textEncoderPlacement,diffusion=$diffusionPlacement,vae=$vaePlacement"
                    } ?: selectedActiveModel?.sdRuntimeBackendMode ?: "auto",
                    maxVramCpuGiB = localMaxVramCpuGiB,
                    customFlags = manualCommandFlags
                )

                batteryGateState.runAfterCheck {
                    val launchDetails = buildString {
                        append("model=${File(config.modelPath).name}")
                        append(" input=${File(config.inputImagePath).name}")
                        append(" repeats=${config.upscaleRepeats}")
                        append(" threads=${config.threads}")
                    }
                    GenerationDiagnosticsStore.recordBreadcrumb(
                        source = IMAGE_GEN_UI_DIAGNOSTIC_SOURCE,
                        mode = mode.name,
                        event = "ui_launch_requested",
                        details = launchDetails
                    )
                    runCatching {
                        startupGuard.run("sd_upscale_start") {
                            ContextCompat.startForegroundService(
                                context,
                                StableDiffusionService.createStartUpscaleIntent(context, config)
                            )
                        }
                        GenerationDiagnosticsStore.recordBreadcrumb(
                            source = IMAGE_GEN_UI_DIAGNOSTIC_SOURCE,
                            mode = mode.name,
                            event = "ui_launch_dispatched",
                            details = launchDetails
                        )
                    }.onFailure { error ->
                        GenerationDiagnosticsStore.recordBreadcrumb(
                            source = IMAGE_GEN_UI_DIAGNOSTIC_SOURCE,
                            mode = mode.name,
                            event = "ui_launch_failed",
                            details = "$launchDetails error=${error.javaClass.simpleName}: ${error.message}"
                        )
                        errorMessage = error.message ?: context.getString(R.string.error_generic)
                    }
                }
            } else {
                val config = SDConfig(
                    mode = mode,
                    modelPath = modelPath ?: "",
                    prompt = prompt,
                    negativePrompt = negativePrompt,
                    width = width,
                    height = height,
                    steps = steps,
                    cfgScale = cfgScale,
                    seed = seed,
                    samplingMethod = selectedSampler,
                    scheduler = selectedScheduler,
                    outputPath = outputFile.absolutePath,
                    initImage = inputImagePath,
                    strength = strength,
                    upscaleModel = null,
                    upscaleRepeats = upscaleRepeats,
                    threads = threadCount,
                    isFluxModel = selectedFamilySpec?.usesDiffusionModelFlag == true,
                    modelFamily = selectedFamily?.storedValue,
                    modelVariant = selectedVariant,
                    vaePath = selectedVaePath,
                    taePath = selectedTaePath,
                    clipLPath = selectedClipLPath,
                    clipGPath = selectedClipGPath,
                    t5xxlPath = selectedT5xxlPath,
                    llmPath = selectedLlmPath,
                    llmVisionPath = selectedLlmVisionPath,
                    controlNetPath = if (controlNetEnabled) selectedControlNetPath else null,
                    controlImagePath = if (controlNetEnabled) selectedImagePath else null,
                    controlStrength = controlStrength,
                    loraPath = if (loraEnabled) selectedLoraPath else null,
                    loraStrength = loraStrength,
                    loraApplyMode = if (loraEnabled) selectedLoraApplyMode else null,
                    textualInversionPath = if (textualInversionEnabled) {
                        selectedTextualInversionPath
                    } else {
                        null
                    },
                    photoMakerPath = selectedPhotoMakerPath,
                    flowShift = flowShiftText.toFloatOrNull(),
                    diffusionFa = diffusionFaEnabled,
                    diffusionConvDirect = diffusionConvDirectEnabled,
                    mmap = mmapEnabled,
                    vaeConvDirect = vaeConvDirectEnabled,
                    qwenImageZeroCondT = qwenImageZeroCondTEnabled,
                    chromaDisableDitMask = chromaDisableDitMaskEnabled,
                    sdParamsBackendMode = selectedMainModel?.sdParamsBackendMode ?: "auto",
                    sdRuntimeBackendMode = acceleratorPlacement?.let {
                        "te=$textEncoderPlacement,diffusion=$diffusionPlacement,vae=$vaePlacement"
                    } ?: selectedMainModel?.sdRuntimeBackendMode ?: "auto",
                    maxVramCpuGiB = localMaxVramCpuGiB,
                    vaeTiling = sdVaeTiling,
                    vaeTileOverlap = sdVaeTileOverlap,
                    vaeTileSize = sdVaeTileSize,
                    vaeRelativeTileSize = sdVaeRelativeTileSize,
                    tensorTypeRules = sdTensorTypeRules,
                    quantizationType = selectedQuantType,
                    cacheMode = cacheMode,
                    cacheOption = cacheOption,
                    scmMask = scmMask,
                    scmPolicy = scmPolicy,
                    customFlags = manualCommandFlags
                )

                batteryGateState.runAfterCheck {
                    val launchDetails = buildSdLaunchBreadcrumbDetails(config)
                    GenerationDiagnosticsStore.recordBreadcrumb(
                        source = IMAGE_GEN_UI_DIAGNOSTIC_SOURCE,
                        mode = mode.name,
                        event = "ui_launch_requested",
                        details = launchDetails
                    )
                    runCatching {
                        startupGuard.run("sd_${mode.name.lowercase()}_start") {
                            ContextCompat.startForegroundService(
                                context,
                                StableDiffusionService.createStartIntent(context, config)
                            )
                        }
                        GenerationDiagnosticsStore.recordBreadcrumb(
                            source = IMAGE_GEN_UI_DIAGNOSTIC_SOURCE,
                            mode = mode.name,
                            event = "ui_launch_dispatched",
                            details = launchDetails
                        )
                    }.onFailure { error ->
                        GenerationDiagnosticsStore.recordBreadcrumb(
                            source = IMAGE_GEN_UI_DIAGNOSTIC_SOURCE,
                            mode = mode.name,
                            event = "ui_launch_failed",
                            details = "$launchDetails error=${error.javaClass.simpleName}: ${error.message}"
                        )
                        errorMessage = error.message ?: context.getString(R.string.error_generic)
                    }
                }
            }
        }
    }

    // Cancel function - cancels only the current mode's generation
    val cancelGeneration: () -> Unit = {
        val mode = when (selectedMode) {
            0 -> SDMode.TXT2IMG
            1 -> SDMode.IMG2IMG
            else -> SDMode.UPSCALE
        }
        context.startService(StableDiffusionService.createCancelModeIntent(context, mode))
    }

    fun recordPaneRendered(paneName: String) {
        GenerationDiagnosticsStore.recordBreadcrumb(
            source = IMAGE_GEN_UI_DIAGNOSTIC_SOURCE,
            mode = when (selectedMode) {
                1 -> SDMode.IMG2IMG.name
                2 -> SDMode.UPSCALE.name
                else -> SDMode.TXT2IMG.name
            },
            event = "pane_rendered",
            details = "pane=$paneName"
        )
    }

    fun LazyListScope.generationModePaneContent() {
        // Image Input (for img2img and upscale modes)
        if (selectedMode > 0) item(key = "input") {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        when {
                            selectedMode == 2 -> stringResource(R.string.imagegen_mode_upscale)
                            selectedFamilySpec?.img2imgInputMode == SdImageInputMode.REFERENCE_IMAGE ->
                                stringResource(R.string.imagegen_reference_image_title)
                            else -> stringResource(R.string.imagegen_mode_img2img)
                        },
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    if (selectedImagePath != null && imageResolution != null) {
                        val resolution = imageResolution
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val bitmap by rememberPreviewImageBitmap(selectedImagePath)
                            bitmap?.let {
                                Image(
                                    bitmap = it,
                                    contentDescription = null,
                                    modifier = Modifier
                                        .size(80.dp)
                                        .clip(RoundedCornerShape(8.dp)),
                                    contentScale = ContentScale.Crop
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    "${resolution?.first ?: 0} × ${resolution?.second ?: 0}",
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                                )
                                Text(
                                    stringResource(R.string.imagegen_resolution),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            IconButton(onClick = { imagePicker.launch("image/*") }) {
                                Icon(Icons.Default.Edit, stringResource(R.string.action_change))
                            }
                        }
                    } else {
                        OutlinedButton(
                            onClick = { imagePicker.launch("image/*") },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Add, null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.imagegen_select_image))
                        }
                    }

                    if (selectedMode == 1 && selectedFamilySpec?.img2imgInputMode != SdImageInputMode.REFERENCE_IMAGE) {
                        Spacer(modifier = Modifier.height(12.dp))
                        SliderWithInput(
                            value = strength,
                            onValueChange = { strength = it },
                            valueRange = 0.1f..1.0f,
                            label = stringResource(R.string.imagegen_strength_label),
                            decimalPlaces = 2
                        )
                        Text(
                            stringResource(R.string.imagegen_strength_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    if (selectedMode == 2) {
                        Spacer(modifier = Modifier.height(12.dp))

                        if (selectedModelPath != null) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(stringResource(R.string.imagegen_upscale_factor_label), style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    "${upscaleFactor}x",
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            Text(
                                stringResource(R.string.imagegen_upscale_factor_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            Spacer(modifier = Modifier.height(12.dp))

                            IntSliderWithInput(
                                value = upscaleRepeats,
                                onValueChange = { upscaleRepeats = it },
                                valueRange = 1..4,
                                label = stringResource(R.string.imagegen_upscale_repeats),
                                steps = 2
                            )

                            val finalFactor = Math.pow(upscaleFactor.toDouble(), upscaleRepeats.toDouble()).toInt()
                            val baseSize = 512
                            val currentResolution = imageResolution
                            val (outputW, outputH, fittedW, fittedH) = if (currentResolution != null) {
                                val (origW, origH) = currentResolution
                                val scale = baseSize.toFloat() / maxOf(origW, origH)
                                val fitW = (origW * scale).toInt()
                                val fitH = (origH * scale).toInt()
                                listOf(fitW * finalFactor, fitH * finalFactor, fitW, fitH)
                            } else {
                                listOf(baseSize * finalFactor, baseSize * finalFactor, baseSize, baseSize)
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                                )
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(stringResource(R.string.imagegen_final_factor), style = MaterialTheme.typography.bodyMedium)
                                        Text(
                                            "${finalFactor}x",
                                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(stringResource(R.string.imagegen_output_res), style = MaterialTheme.typography.bodyMedium)
                                        Text(
                                            "${outputW} × ${outputH}",
                                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold)
                                        )
                                    }
                                    val resolution = imageResolution
                                    if (resolution != null) {
                                        val (origW, origH) = resolution
                                        Text(
                                            stringResource(R.string.imagegen_original_base, origW, origH, fittedW, fittedH),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }

                            if (upscaleRepeats > 1) {
                                Spacer(modifier = Modifier.height(8.dp))
                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(12.dp),
                                    colors = CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f)
                                    )
                                ) {
                                    Row(
                                        modifier = Modifier.padding(12.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            Icons.Default.Warning,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.tertiary,
                                            modifier = Modifier.size(20.dp)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            stringResource(R.string.imagegen_upscale_repeats_warn),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onTertiaryContainer
                                        )
                                    }
                                }
                            }
                        } else {
                            Text(
                                stringResource(R.string.imagegen_upscale_model_info),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
        }
        item(key = "model") { Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    if (selectedMode == 2) {
                        stringResource(R.string.imagegen_component_upscaler)
                    } else {
                        stringResource(R.string.sd_type_diffusion)
                    },
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                )
                Spacer(modifier = Modifier.height(8.dp))

                val modelsToShow = modelsForSelectedMode

                if (modelsToShow.isEmpty()) {
                    Text(
                        if (selectedMode == 2) stringResource(R.string.imagegen_no_upscalers_installed) else stringResource(R.string.imagegen_no_models_installed),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = { navController.navigate(Screen.SDModels.route) }
                    ) {
                        Icon(Icons.Default.Add, null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(if (selectedMode == 2) stringResource(R.string.imagegen_get_upscaler_models) else stringResource(R.string.imagegen_get_sd_models))
                    }
                } else {
                    var expanded by remember { mutableStateOf(false) }
                    ExposedDropdownMenuBox(
                        expanded = expanded,
                        onExpandedChange = { expanded = !expanded }
                    ) {
                        OutlinedTextField(
                            value = selectedActiveModel?.filename ?: stringResource(R.string.imagegen_select_model),
                            onValueChange = {},
                            readOnly = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .menuAnchor(),
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) }
                        )
                        ExposedDropdownMenu(
                            expanded = expanded,
                            onDismissRequest = { expanded = false }
                        ) {
                            modelsToShow.forEach { model ->
                                DropdownMenuItem(
                                    text = { Text(model.filename) },
                                    onClick = {
                                        expanded = false

                                        if (selectedMode == 2) {
                                            selectedUpscalerModelPath = model.path
                                            val factorRegex = Regex("(\\d+)[xX]|[xX](\\d+)")
                                            val match = factorRegex.find(model.filename)
                                            if (match != null) {
                                                val detected = (match.groupValues[1].takeIf { it.isNotBlank() }
                                                    ?: match.groupValues[2]).toIntOrNull()
                                                if (detected != null && detected in listOf(2, 4, 8)) {
                                                    upscaleFactor = detected
                                                }
                                            }
                                        } else {
                                            selectedGenerationModelPath = model.path
                                            if (model.type != ModelType.SD_DIFFUSION) {
                                                selectedVaePath = null
                                                selectedClipLPath = null
                                                selectedT5xxlPath = null
                                            }
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            }
        } }

        if (selectedModelPath != null && selectedMode != 2 && componentRoles.isNotEmpty()) item(key = "components") {
            Spacer(modifier = Modifier.height(12.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        stringResource(R.string.imagegen_components_title),
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                    )
                    Text(
                        stringResource(
                            R.string.imagegen_components_desc,
                            selectedFamily?.storedValue ?: stringResource(R.string.imagegen_select_model)
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    if (missingRequiredComponents.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f)
                            ),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text(
                                stringResource(
                                    R.string.imagegen_missing_components_message,
                                    missingRequiredComponents.joinToString(", ") {
                                        componentRoleLabel(context, it)
                                    }
                                ),
                                modifier = Modifier.padding(12.dp),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    componentRoles.forEachIndexed { index, role ->
                        when (role) {
                            SdComponentRole.VAE -> SdComponentPickerField(
                                label = componentRoleLabel(role),
                                models = compatibleVaeModels,
                                selectedPath = selectedVaePath,
                                onSelectionChange = { selectedVaePath = it },
                                allowNone = role !in selectedFamilySpec?.requiredRoles.orEmpty(),
                                emptyMessage = stringResource(R.string.imagegen_no_vae_installed)
                            )
                            SdComponentRole.TAE -> SdComponentPickerField(
                                label = componentRoleLabel(role),
                                models = compatibleTaeModels,
                                selectedPath = selectedTaePath,
                                onSelectionChange = { selectedTaePath = it },
                                allowNone = role !in selectedFamilySpec?.requiredRoles.orEmpty(),
                                emptyMessage = stringResource(R.string.imagegen_no_tae_installed)
                            )
                            SdComponentRole.CLIP_L -> SdComponentPickerField(
                                label = componentRoleLabel(role),
                                models = compatibleClipLModels,
                                selectedPath = selectedClipLPath,
                                onSelectionChange = { selectedClipLPath = it },
                                allowNone = role !in selectedFamilySpec?.requiredRoles.orEmpty(),
                                emptyMessage = stringResource(R.string.imagegen_no_clip_l)
                            )
                            SdComponentRole.CLIP_G -> SdComponentPickerField(
                                label = componentRoleLabel(role),
                                models = compatibleClipGModels,
                                selectedPath = selectedClipGPath,
                                onSelectionChange = { selectedClipGPath = it },
                                allowNone = role !in selectedFamilySpec?.requiredRoles.orEmpty(),
                                emptyMessage = stringResource(R.string.imagegen_no_clip_g)
                            )
                            SdComponentRole.T5XXL -> SdComponentPickerField(
                                label = componentRoleLabel(role),
                                models = compatibleT5xxlModels,
                                selectedPath = selectedT5xxlPath,
                                onSelectionChange = { selectedT5xxlPath = it },
                                allowNone = role !in selectedFamilySpec?.requiredRoles.orEmpty(),
                                emptyMessage = stringResource(R.string.imagegen_no_t5xxl)
                            )
                            SdComponentRole.LLM -> SdComponentPickerField(
                                label = componentRoleLabel(role),
                                models = compatibleLlmModels,
                                selectedPath = selectedLlmPath,
                                onSelectionChange = { selectedLlmPath = it },
                                allowNone = role !in selectedFamilySpec?.requiredRoles.orEmpty(),
                                emptyMessage = stringResource(R.string.imagegen_no_llm)
                            )
                            SdComponentRole.LLM_VISION -> SdComponentPickerField(
                                label = componentRoleLabel(role),
                                models = compatibleLlmVisionModels,
                                selectedPath = selectedLlmVisionPath,
                                onSelectionChange = { selectedLlmVisionPath = it },
                                allowNone = role !in selectedFamilySpec?.requiredRoles.orEmpty(),
                                emptyMessage = stringResource(R.string.imagegen_no_llm_vision)
                            )
                            SdComponentRole.PHOTOMAKER -> SdComponentPickerField(
                                label = componentRoleLabel(role),
                                models = compatiblePhotoMakerModels,
                                selectedPath = selectedPhotoMakerPath,
                                onSelectionChange = { selectedPhotoMakerPath = it },
                                allowNone = role !in selectedFamilySpec?.requiredRoles.orEmpty(),
                                emptyMessage = stringResource(R.string.imagegen_no_photomaker)
                            )
                            else -> Unit
                        }
                        if (index != componentRoles.lastIndex) {
                            Spacer(modifier = Modifier.height(12.dp))
                        }
                    }

                    val anyRequiredRoleMissingModel = selectedFamilySpec?.requiredRoles?.any { role ->
                        when (role) {
                            SdComponentRole.VAE -> compatibleVaeModels.isEmpty()
                            SdComponentRole.TAE -> compatibleTaeModels.isEmpty()
                            SdComponentRole.CLIP_L -> compatibleClipLModels.isEmpty()
                            SdComponentRole.CLIP_G -> compatibleClipGModels.isEmpty()
                            SdComponentRole.T5XXL -> compatibleT5xxlModels.isEmpty()
                            SdComponentRole.LLM -> compatibleLlmModels.isEmpty()
                            SdComponentRole.LLM_VISION -> compatibleLlmVisionModels.isEmpty()
                            SdComponentRole.PHOTOMAKER -> compatiblePhotoMakerModels.isEmpty()
                            else -> false
                        }
                    } == true
                    if (anyRequiredRoleMissingModel) {
                        Spacer(modifier = Modifier.height(12.dp))
                        OutlinedButton(onClick = { navController.navigate(Screen.SDModels.route) }) {
                            Icon(Icons.Default.Add, null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.imagegen_get_family_components))
                        }
                    }
                }
            }
        }

        if (selectedMode != 2 && (compatibleControlNetModels.isNotEmpty() || compatibleLoraModels.isNotEmpty())) item(key = "adapters") {
            Spacer(modifier = Modifier.height(12.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        stringResource(R.string.imagegen_adapters_title),
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                    )
                    Text(
                        stringResource(R.string.imagegen_adapters_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    if (selectedMode == 1 && compatibleControlNetModels.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(12.dp))
                        LabeledSwitchRow(
                            label = componentRoleLabel(SdComponentRole.CONTROLNET),
                            checked = controlNetEnabled,
                            onCheckedChange = {
                                controlNetEnabled = it
                                if (!it) {
                                    selectedControlNetPath = null
                                }
                            }
                        )
                        if (controlNetEnabled) {
                            Spacer(modifier = Modifier.height(8.dp))
                            SdComponentPickerField(
                                label = componentRoleLabel(SdComponentRole.CONTROLNET),
                                models = compatibleControlNetModels,
                                selectedPath = selectedControlNetPath,
                                onSelectionChange = { selectedControlNetPath = it },
                                allowNone = false,
                                emptyMessage = stringResource(R.string.imagegen_no_controlnet)
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            SliderWithInput(
                                value = controlStrength,
                                onValueChange = { controlStrength = it },
                                valueRange = 0f..1.5f,
                                label = stringResource(R.string.imagegen_control_strength_label),
                                decimalPlaces = 2
                            )
                        }
                    }

                    if (compatibleLoraModels.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(12.dp))
                        LabeledSwitchRow(
                            label = componentRoleLabel(SdComponentRole.LORA),
                            checked = loraEnabled,
                            onCheckedChange = {
                                loraEnabled = it
                                if (!it) {
                                    selectedLoraPath = null
                                    selectedLoraApplyMode = null
                                }
                            }
                        )
                        if (loraEnabled) {
                            Spacer(modifier = Modifier.height(8.dp))
                            SdComponentPickerField(
                                label = componentRoleLabel(SdComponentRole.LORA),
                                models = compatibleLoraModels,
                                selectedPath = selectedLoraPath,
                                onSelectionChange = { selectedLoraPath = it },
                                allowNone = false,
                                emptyMessage = stringResource(R.string.imagegen_no_lora)
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            SliderWithInput(
                                value = loraStrength,
                                onValueChange = { loraStrength = it },
                                valueRange = 0f..2f,
                                label = stringResource(R.string.imagegen_lora_strength_label),
                                decimalPlaces = 2
                            )
                        }
                    }

                    if (compatibleTextualInversionModels.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(12.dp))
                        LabeledSwitchRow(
                            label = stringResource(
                                R.string.imagegen_textual_inversion
                            ),
                            checked = textualInversionEnabled,
                            onCheckedChange = {
                                textualInversionEnabled = it
                                if (!it) {
                                    selectedTextualInversionPath = null
                                }
                            }
                        )
                        if (textualInversionEnabled) {
                            Spacer(modifier = Modifier.height(8.dp))
                            SdComponentPickerField(
                                label = stringResource(
                                    R.string.imagegen_textual_inversion
                                ),
                                models = compatibleTextualInversionModels,
                                selectedPath = selectedTextualInversionPath,
                                onSelectionChange = {
                                    selectedTextualInversionPath = it
                                },
                                allowNone = false,
                                emptyMessage = stringResource(
                                    R.string.imagegen_no_textual_inversion
                                )
                            )
                        }
                    }
                }
            }
        }

        if (selectedMode != 2 && selectedModelPath != null && selectedFamilySpec?.usesDiffusionModelFlag != true) item(key = "tensor-types") {
            Spacer(modifier = Modifier.height(12.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        stringResource(R.string.imagegen_quantization_title),
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf("none", "f16", "q8_0", "q4_0").forEach { type ->
                            FilterChip(
                                selected = selectedQuantType == (if (type == "none") "" else type),
                                onClick = { selectedQuantType = if (type == "none") "" else type },
                                label = { Text(type.uppercase()) }
                            )
                        }
                    }

                    if (selectedQuantType.isNotBlank()) {
                        Text(
                            stringResource(R.string.imagegen_quant_desc, selectedQuantType),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }

        if (selectedMode != 2) item(key = "prompts-and-parameters") {
            Spacer(modifier = Modifier.height(12.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        stringResource(R.string.imagegen_prompt_label),
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    if (missingRequiredComponents.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f)
                            ),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text(
                                stringResource(
                                    R.string.imagegen_missing_components_message,
                                    missingRequiredComponents.joinToString(", ") {
                                        componentRoleLabel(context, it)
                                    }
                                ),
                                modifier = Modifier.padding(8.dp),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                    }

                    OutlinedTextField(
                        value = prompt,
                        onValueChange = { prompt = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(100.dp),
                        placeholder = { Text(stringResource(R.string.imagegen_prompt_placeholder)) },
                        shape = RoundedCornerShape(12.dp)
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showAdvanced = !showAdvanced },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            stringResource(R.string.imagegen_advanced_options),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.weight(1f))
                        Icon(
                            if (showAdvanced) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                            null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }

                    if (showAdvanced) {
                        Spacer(modifier = Modifier.height(12.dp))

                        Text(stringResource(R.string.imagegen_negative_prompt_label), style = MaterialTheme.typography.bodyMedium)
                        Spacer(modifier = Modifier.height(4.dp))
                        OutlinedTextField(
                            value = negativePrompt,
                            onValueChange = { negativePrompt = it },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text(stringResource(R.string.imagegen_negative_prompt_placeholder)) },
                            shape = RoundedCornerShape(12.dp)
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            Column(modifier = Modifier.weight(1f)) {
                                IntSliderWithInput(
                                    value = width,
                                    onValueChange = { width = it },
                                    valueRange = 256..1024,
                                    label = stringResource(R.string.imagegen_width_label)
                                )
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                IntSliderWithInput(
                                    value = height,
                                    onValueChange = { height = it },
                                    valueRange = 256..1024,
                                    label = stringResource(R.string.imagegen_height_label)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            Column(modifier = Modifier.weight(1f)) {
                                IntSliderWithInput(
                                    value = steps,
                                    onValueChange = { steps = it },
                                    valueRange = 1..50,
                                    label = stringResource(R.string.imagegen_steps_label)
                                )
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                SliderWithInput(
                                    value = cfgScale,
                                    onValueChange = { cfgScale = it },
                                    valueRange = 1f..20f,
                                    label = stringResource(R.string.imagegen_cfg_label),
                                    decimalPlaces = 1
                                )
                            }
                        }

                        Text(stringResource(R.string.imagegen_sampler_label), style = MaterialTheme.typography.bodyMedium)
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
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = samplerExpanded) }
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

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            DraftLongTextField(
                                value = seed,
                                onValueChange = { seed = it },
                                blankValue = -1L,
                                modifier = Modifier.weight(1f),
                                label = { Text(stringResource(R.string.imagegen_seed_label)) },
                                shape = RoundedCornerShape(12.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            IconButton(onClick = { seed = (0..Int.MAX_VALUE).random().toLong() }) {
                                Icon(Icons.Default.Refresh, stringResource(R.string.imagegen_random_seed))
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        IntSliderWithInput(
                            value = if (threads <= 0) 4 else threads,
                            onValueChange = { threads = it },
                            valueRange = 1..16,
                            label = stringResource(R.string.imagegen_threads_label),
                            steps = 14
                        )
                        Text(
                            stringResource(R.string.imagegen_threads_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Spacer(modifier = Modifier.height(16.dp))
                        LocalSdCliMemoryControls(
                            selectedModel = selectedActiveModel,
                            maxRamEnabled = sdMaxCpuRamEnabled,
                            maxRamGiB = sdMaxCpuRamGiB,
                            onParamsBackendChange = { mode ->
                                selectedActiveModel?.let { model ->
                                    scope.launch {
                                        db.modelDao().insertModel(
                                            model.copy(sdParamsBackendMode = mode.storedValue)
                                        )
                                    }
                                }
                            },
                            onRuntimeBackendChange = { mode ->
                                selectedActiveModel?.let { model ->
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
                }
            }
        }

        if (selectedMode != 2 && selectedFamilySpec != null) item(key = "runtime") {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        stringResource(R.string.imagegen_runtime_title),
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                    )
                    Text(
                        stringResource(R.string.imagegen_runtime_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(modifier = Modifier.height(12.dp))
                    LabeledSwitchRow(
                        label = stringResource(R.string.imagegen_vae_tiling),
                        checked = sdVaeTiling,
                        onCheckedChange = { settingsRepo.setSdVaeTiling(it) }
                    )

                    if (sdVaeTiling) {
                        Spacer(modifier = Modifier.height(12.dp))
                        OutlinedTextField(
                            value = sdVaeTileSize,
                            onValueChange = { settingsRepo.setSdVaeTileSize(it) },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text(stringResource(R.string.imagegen_tile_size)) },
                            placeholder = { Text("32x32") },
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp)
                        )

                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            stringResource(
                                R.string.imagegen_tile_overlap_value,
                                String.format(Locale.US, "%.2f", sdVaeTileOverlap)
                            ),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Slider(
                            value = sdVaeTileOverlap,
                            onValueChange = { settingsRepo.setSdVaeTileOverlap(it) },
                            valueRange = 0f..1f,
                            steps = 10
                        )

                        Spacer(modifier = Modifier.height(12.dp))
                        OutlinedTextField(
                            value = sdVaeRelativeTileSize,
                            onValueChange = { settingsRepo.setSdVaeRelativeTileSize(it) },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text(stringResource(R.string.imagegen_relative_tile_size)) },
                            placeholder = { Text("0.5") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            shape = RoundedCornerShape(12.dp)
                        )
                    }

                    if (selectedFamilySpec.supportsFlowShift) {
                        Spacer(modifier = Modifier.height(12.dp))
                        OutlinedTextField(
                            value = flowShiftText,
                            onValueChange = { flowShiftText = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text(stringResource(R.string.imagegen_flow_shift_label)) },
                            placeholder = { Text("3.0") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            shape = RoundedCornerShape(12.dp)
                        )
                    }

                    if (selectedFamilySpec.supportsDiffusionFa) {
                        Spacer(modifier = Modifier.height(12.dp))
                        LabeledSwitchRow(
                            label = stringResource(R.string.imagegen_diffusion_fa_label),
                            checked = diffusionFaEnabled,
                            onCheckedChange = { diffusionFaEnabled = it }
                        )
                    }

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

                    Spacer(modifier = Modifier.height(12.dp))
                    LabeledSwitchRow(
                        label = stringResource(R.string.imagegen_diffusion_conv_direct_label),
                        checked = diffusionConvDirectEnabled,
                        onCheckedChange = { diffusionConvDirectEnabled = it }
                    )

                    if (selectedFamilySpec.supportsMmap) {
                        Spacer(modifier = Modifier.height(12.dp))
                        LabeledSwitchRow(
                            label = stringResource(R.string.imagegen_mmap_label),
                            checked = mmapEnabled,
                            onCheckedChange = { mmapEnabled = it }
                        )
                    }

                    if (selectedFamilySpec.supportsVaeConvDirect) {
                        Spacer(modifier = Modifier.height(12.dp))
                        LabeledSwitchRow(
                            label = stringResource(R.string.imagegen_vae_conv_direct_label),
                            checked = vaeConvDirectEnabled,
                            onCheckedChange = { vaeConvDirectEnabled = it }
                        )
                    }

                    if (loraEnabled && selectedFamilySpec.supportsLoraApplyMode) {
                        Spacer(modifier = Modifier.height(12.dp))
                        var loraApplyExpanded by remember { mutableStateOf(false) }
                        ExposedDropdownMenuBox(
                            expanded = loraApplyExpanded,
                            onExpandedChange = { loraApplyExpanded = !loraApplyExpanded }
                        ) {
                            OutlinedTextField(
                                value = selectedLoraApplyMode?.cliName
                                    ?: stringResource(R.string.imagegen_lora_apply_mode_default),
                                onValueChange = {},
                                readOnly = true,
                                modifier = Modifier.fillMaxWidth().menuAnchor(),
                                label = { Text(stringResource(R.string.imagegen_lora_apply_mode_label)) },
                                trailingIcon = {
                                    ExposedDropdownMenuDefaults.TrailingIcon(expanded = loraApplyExpanded)
                                },
                                shape = RoundedCornerShape(12.dp)
                            )
                            ExposedDropdownMenu(
                                expanded = loraApplyExpanded,
                                onDismissRequest = { loraApplyExpanded = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.imagegen_lora_apply_mode_default)) },
                                    onClick = {
                                        selectedLoraApplyMode = null
                                        loraApplyExpanded = false
                                    }
                                )
                                SdLoraApplyMode.entries.forEach { mode ->
                                    DropdownMenuItem(
                                        text = { Text(mode.cliName) },
                                        onClick = {
                                            selectedLoraApplyMode = mode
                                            loraApplyExpanded = false
                                        }
                                    )
                                }
                            }
                        }
                    }

                    if (selectedFamilySpec.supportsQwenImageZeroCondT) {
                        Spacer(modifier = Modifier.height(12.dp))
                        LabeledSwitchRow(
                            label = stringResource(R.string.imagegen_qwen_zero_cond_t_label),
                            checked = qwenImageZeroCondTEnabled,
                            onCheckedChange = { qwenImageZeroCondTEnabled = it }
                        )
                    }

                    if (selectedFamilySpec.supportsChromaDisableDitMask) {
                        Spacer(modifier = Modifier.height(12.dp))
                        LabeledSwitchRow(
                            label = stringResource(R.string.imagegen_chroma_disable_dit_mask_label),
                            checked = chromaDisableDitMaskEnabled,
                            onCheckedChange = { chromaDisableDitMaskEnabled = it }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
        }

        item(key = "cache") { GenerationCachingCard(
            title = stringResource(R.string.gen_cache_title),
            cacheMode = if (selectedMode == 2) null else cacheMode,
            onCacheModeChange = { cacheMode = it },
            cacheOption = if (selectedMode == 2) "" else cacheOption,
            onCacheOptionChange = { cacheOption = it },
            scmPolicy = if (selectedMode == 2) null else scmPolicy,
            onScmPolicyChange = { scmPolicy = it },
            scmMask = if (selectedMode == 2) "" else scmMask,
            onScmMaskChange = { scmMask = it },
            guidanceFamily = when {
                selectedMode == 2 -> null
                selectedFamilySpec?.cacheArchitecture == SdCacheArchitecture.DIT -> GenerationCacheGuidanceFamily.DIT
                else -> GenerationCacheGuidanceFamily.UNET
            },
            enabled = selectedMode != 2,
            disabledMessage = stringResource(R.string.gen_cache_disabled_for_upscale)
        ) }

        item(key = "manual-flags") { Card(
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
        } }

        item(key = "run-state") { if (isGenerating) {
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
                        stringResource(R.string.status_generating),
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    if (generationStatus.isNotBlank()) {
                        Text(
                            generationStatus,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }

                    val progressPercent = (progress * 100).toInt()
                    Text(
                        stringResource(R.string.imagegen_step_progress, currentStepVal, totalStepsVal, progressPercent),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .clip(RoundedCornerShape(4.dp)),
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    OutlinedButton(
                        onClick = cancelGeneration,
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Icon(Icons.Default.Close, null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.action_cancel))
                    }
                }
            }
        } else {
            val buttonEnabled = when (selectedMode) {
                0 -> selectedModelPath != null &&
                    prompt.isNotBlank() &&
                    supportsTxt2Img &&
                    missingRequiredComponents.isEmpty()
                1 -> selectedModelPath != null &&
                    prompt.isNotBlank() &&
                    selectedImagePath != null &&
                    supportsImg2Img &&
                    missingRequiredComponents.isEmpty()
                2 -> selectedModelPath != null && selectedImagePath != null
                else -> false
            }
            Button(
                onClick = generate,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                enabled = buttonEnabled,
                shape = RoundedCornerShape(16.dp)
            ) {
                Icon(Icons.Default.Create, null)
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    when (selectedMode) {
                        2 -> stringResource(R.string.imagegen_upscale_btn)
                        else -> stringResource(R.string.imagegen_generate_btn)
                    },
                    fontWeight = FontWeight.Bold
                )
            }
        } }

        errorMessage?.let { error ->
            item(key = "error") { Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(
                    error,
                    modifier = Modifier.padding(12.dp),
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            } }
        }

        if (generationState is SDGenerationState.Complete) {
            item(key = "complete") { Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        stringResource(R.string.imagegen_success),
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            } }
        }
    }

    @Composable
    fun GalleryPane() {
        LaunchedEffect(Unit) {
            recordPaneRendered("gallery")
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp)
        ) {
            val filterLabels = listOf(
                stringResource(R.string.imagegen_gallery_all),
                stringResource(R.string.imagegen_mode_txt2img),
                stringResource(R.string.imagegen_mode_img2img),
                stringResource(R.string.imagegen_mode_upscale),
                "⚙️"
            )
            SingleChoiceSegmentedButtonRow(
                modifier = Modifier.fillMaxWidth()
            ) {
                filterLabels.forEachIndexed { index, label ->
                    SegmentedButton(
                        selected = galleryFilter == index,
                        onClick = { galleryFilter = index },
                        shape = SegmentedButtonDefaults.itemShape(
                            index = index,
                            count = filterLabels.size
                        )
                    ) {
                        Text(label, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            SingleChoiceSegmentedButtonRow(
                modifier = Modifier.fillMaxWidth()
            ) {
                listOf(
                    stringResource(R.string.ai_servers_gallery_source_all),
                    stringResource(R.string.ai_servers_gallery_source_app),
                    stringResource(R.string.ai_servers_gallery_source_server)
                ).forEachIndexed { index, label ->
                    SegmentedButton(
                        selected = gallerySourceFilter == index,
                        onClick = { gallerySourceFilter = index },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = 3)
                    ) {
                        Text(label, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            val filteredImages = remember(galleryImages, galleryFilter, gallerySourceFilter, serverImagePathSet) {
                val modeFiltered = when (galleryFilter) {
                    1 -> galleryImages.filter { it.parentFile?.name == "txt2img" }
                    2 -> galleryImages.filter { it.parentFile?.name == "img2img" }
                    3 -> galleryImages.filter { it.parentFile?.name == "upscaled" }
                    4 -> galleryImages.filter { it.parentFile?.name == "workflow" }
                    else -> galleryImages
                }
                when (gallerySourceFilter) {
                    1 -> modeFiltered.filterNot { it.absolutePath in serverImagePathSet }
                    2 -> modeFiltered.filter { it.absolutePath in serverImagePathSet }
                    else -> modeFiltered
                }
            }

            if (filteredImages.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("📷", style = MaterialTheme.typography.displayLarge)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            if (galleryFilter == 0) {
                                stringResource(R.string.imagegen_gallery_empty)
                            } else {
                                stringResource(
                                    R.string.imagegen_gallery_empty_filter,
                                    filterLabels[galleryFilter]
                                )
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(
                        items = filteredImages,
                        key = { it.absolutePath }
                    ) { imageFile ->
                        val bitmap by rememberPreviewImageBitmap(imageFile.absolutePath)
                        val typeBadge = when (imageFile.parentFile?.name) {
                            "txt2img" -> "🎨"
                            "img2img" -> "🔄"
                            "upscaled" -> "⬆️"
                            "workflow" -> "⚙️"
                            else -> null
                        }

                        Box(
                            modifier = Modifier
                                .aspectRatio(1f)
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { fullscreenImage = imageFile }
                        ) {
                            if (bitmap != null) {
                                Image(
                                    bitmap = bitmap!!,
                                    contentDescription = null,
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Crop
                                )
                            } else {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(MaterialTheme.colorScheme.surfaceVariant),
                                    contentAlignment = Alignment.Center
                                ) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(24.dp),
                                        strokeWidth = 2.dp
                                    )
                                }
                            }
                            typeBadge?.let {
                                Surface(
                                    modifier = Modifier
                                        .align(Alignment.TopStart)
                                        .padding(4.dp),
                                    shape = RoundedCornerShape(6.dp),
                                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.8f)
                                ) {
                                    Text(
                                        it,
                                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    BatteryOptimizationWarningDialog(state = batteryGateState)

    LaunchedEffect(mainTab, selectedMode) {
        if (mainTab == 0) {
            recordPaneRendered(
                when (selectedMode) {
                    1 -> "img2img"
                    2 -> "upscale"
                    else -> "txt2img"
                }
            )
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        MaterialTheme.colorScheme.surface,
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                    )
                )
            )
    ) {
        // Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { navController.popBackStack() }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back))
            }
            Text(
                "🎨 " + stringResource(R.string.imagegen_title),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.headlineSmall.copy(
                    fontWeight = FontWeight.Bold
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            IconButton(onClick = { showInfoDialog = true }) {
                Icon(Icons.Default.Info, stringResource(R.string.gen_help_open))
            }
        }

        // Main Tab Selector: Generate vs Gallery
        val mainTabs = listOf("🎨 " + stringResource(R.string.imagegen_tab_generate), "📂 " + stringResource(R.string.imagegen_tab_gallery))
        TabRow(
            selectedTabIndex = mainTab,
            modifier = Modifier.padding(horizontal = 16.dp)
        ) {
            mainTabs.forEachIndexed { index, title ->
                Tab(
                    selected = mainTab == index,
                    onClick = { mainTab = index },
                    text = { Text(title) }
                )
            }
        }

        if (mainTab == 0) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(bottom = 16.dp)
            ) {
                item(key = "mode") {
                    val modes = listOf(
                        stringResource(R.string.imagegen_mode_txt2img),
                        stringResource(R.string.imagegen_mode_img2img),
                        stringResource(R.string.imagegen_mode_upscale)
                    )
                    SingleChoiceSegmentedButtonRow(
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        modes.forEachIndexed { index, mode ->
                            val modeEnabled = when (index) {
                                0 -> supportsTxt2Img
                                1 -> supportsImg2Img
                                else -> true
                            }
                            SegmentedButton(
                                selected = selectedMode == index,
                                onClick = {
                                    if (index == 2) {
                                        navController.navigate(Screen.ImageGenUpscale.route)
                                    } else {
                                        switchGenerationMode(index)
                                    }
                                },
                                enabled = modeEnabled,
                                shape = SegmentedButtonDefaults.itemShape(
                                    index = index,
                                    count = modes.size
                                )
                            ) {
                                Text(mode)
                            }
                        }
                    }
                }
                generationModePaneContent()
            }
        } else {
            key("gallery") {
                GalleryPane()
            }
        }
    }

    if (showInfoDialog) {
        GenerationOptionsInfoDialog(
            title = stringResource(R.string.imagegen_help_title),
            sections = buildImageGenerationHelpSections(
                selectedMode = selectedMode,
                cacheArchitecture = selectedFamilySpec?.cacheArchitecture,
                imageInputMode = selectedFamilySpec?.img2imgInputMode,
                hasComponents = componentRoles.isNotEmpty()
            ),
            subtitle = stringResource(R.string.gen_help_powered_by_sdcpp),
            onDismiss = { showInfoDialog = false }
        )
    }

    // Fullscreen image viewer dialog
    if (fullscreenImage != null) {
        val bitmap by rememberPreviewImageBitmap(fullscreenImage?.absolutePath, maxDimension = 1600)
        val imageMetadata = remember(fullscreenImage?.absolutePath) {
            fullscreenImage?.let { SdGeneratedImageMetadata.fromFile(SdGeneratedImageMetadata.metadataFileForImage(it)) }
        }

        AlertDialog(
            onDismissRequest = { fullscreenImage = null },
            confirmButton = {
                Button(
                    onClick = {
                        // Share the image with error handling
                        fullscreenImage?.let { file ->
                            try {
                                val uri = androidx.core.content.FileProvider.getUriForFile(
                                    context,
                                    "${context.packageName}.fileprovider",
                                    file
                                )
                                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                    type = "image/png"
                                    putExtra(Intent.EXTRA_STREAM, uri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                context.startActivity(Intent.createChooser(shareIntent, context.getString(R.string.imagegen_share_chooser)))
                            } catch (e: Exception) {
                                android.widget.Toast.makeText(
                                    context,
                                    "Failed to share: ${e.message}",
                                    android.widget.Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                    }
                ) {
                    Icon(Icons.Default.Share, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.action_share))
                }
            },
            dismissButton = {
                Row {
                    // Delete button
                    TextButton(
                        onClick = {
                            fullscreenImage?.let { file ->
                                // Show confirmation then delete
                                if (file.delete()) {
                                    // Remove from disk images list
                                    diskImages = diskImages.filter { it.absolutePath != file.absolutePath }
                                    // Remove from all mode state holders (to fix phantom image)
                                    SDModeStateHolder.txt2img.removeImage(file)
                                    SDModeStateHolder.img2img.removeImage(file)
                                    SDModeStateHolder.upscale.removeImage(file)
                                    android.widget.Toast.makeText(context, context.getString(R.string.imagegen_delete_confirm), android.widget.Toast.LENGTH_SHORT).show()
                                } else {
                                    android.widget.Toast.makeText(context, context.getString(R.string.imagegen_delete_fail), android.widget.Toast.LENGTH_SHORT).show()
                                }
                                fullscreenImage = null
                            }
                        },
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(context.getString(R.string.action_delete))
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    // Close button
                    TextButton(onClick = { fullscreenImage = null }) {
                        Text(context.getString(R.string.action_close))
                    }
                }
            },
            title = {
                Column {
                    Text(context.getString(R.string.imagegen_generated_title))
                    val actualResolution = fullscreenImage?.absolutePath?.let { readImageFileResolution(it) }
                    val displayResolution = actualResolution ?: bitmap?.let { it.width to it.height }
                    displayResolution?.let {
                        Text(
                            "${it.first} × ${it.second}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 520.dp).verticalScroll(rememberScrollState())
                ) {
                    Box(modifier = Modifier.fillMaxWidth().aspectRatio(1f)) {
                    bitmap?.let {
                        Image(
                            bitmap = it,
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Fit
                        )
                    }
                    }
                    imageMetadata?.let { metadata ->
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(metadata.prompt, style = MaterialTheme.typography.bodyMedium)
                        if (metadata.negativePrompt.isNotBlank()) Text(metadata.negativePrompt, style = MaterialTheme.typography.bodySmall)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(stringResource(R.string.sd_gallery_image_details, metadata.modelName, metadata.width, metadata.height, metadata.steps, metadata.samplingMethod.cliName), style = MaterialTheme.typography.bodySmall)
                        Text("${Date(metadata.createdAt)} • ${metadata.generationDurationMs?.let { formatGenerationDuration(it) } ?: stringResource(R.string.sd_gallery_unavailable)}", style = MaterialTheme.typography.bodySmall)
                        Text(stringResource(R.string.sd_gallery_image_runtime, metadata.sdRuntimeBackendMode, metadata.quantizationType.ifBlank { stringResource(R.string.sd_gallery_default) }), style = MaterialTheme.typography.bodySmall)
                        metadata.conditioningDurationMs?.let { Text(stringResource(R.string.sd_stage_conditioning, formatGenerationDuration(it)), style = MaterialTheme.typography.bodySmall) }
                        metadata.samplingDurationMs?.let { Text(stringResource(R.string.sd_stage_sampling, formatGenerationDuration(it)), style = MaterialTheme.typography.bodySmall) }
                        metadata.decodingDurationMs?.let { Text(stringResource(R.string.sd_stage_decoding, formatGenerationDuration(it)), style = MaterialTheme.typography.bodySmall) }
                        Text(stringResource(R.string.sd_gallery_image_components, metadata.vaeName ?: metadata.taeName ?: stringResource(R.string.sd_gallery_unavailable), metadata.cacheMode?.cliName ?: stringResource(R.string.gen_cache_mode_off), metadata.diffusionConvDirect, metadata.vaeConvDirect), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        )
    }
}

private data class PreparedImageInput(
    val uri: Uri,
    val path: String,
    val resolution: Pair<Int, Int>?
)

private const val IMAGE_GEN_UI_DIAGNOSTIC_SOURCE = "image_generation_ui"

private fun formatGenerationDuration(durationMs: Long): String = when {
    durationMs < 1_000L -> "${durationMs} ms"
    durationMs < 60_000L -> String.format(Locale.getDefault(), "%.1f s", durationMs / 1_000.0)
    else -> String.format(Locale.getDefault(), "%dm %02ds", durationMs / 60_000L, (durationMs / 1_000L) % 60L)
}

private suspend fun prepareImageInputForMode(
    context: Context,
    uri: Uri,
    targetMode: Int,
    tempFileName: String
): PreparedImageInput? = withContext(Dispatchers.IO) {
    val tempFile = File(context.cacheDir, tempFileName)
    if (targetMode == 2) {
        copyUriToFile(context, uri, tempFile)
        val resolution = readImageResolution(context, uri)
        return@withContext PreparedImageInput(
            uri = uri,
            path = tempFile.absolutePath,
            resolution = resolution
        )
    }

    context.contentResolver.openInputStream(uri)?.use { inputStream ->
        val originalBitmap = BitmapFactory.decodeStream(inputStream) ?: return@use null
        val processedBitmap = if (originalBitmap.width != originalBitmap.height) {
            val size = maxOf(originalBitmap.width, originalBitmap.height)
            val squareBitmap = android.graphics.Bitmap.createBitmap(
                size,
                size,
                android.graphics.Bitmap.Config.ARGB_8888
            )
            val canvas = android.graphics.Canvas(squareBitmap)
            canvas.drawColor(android.graphics.Color.BLACK)
            val left = (size - originalBitmap.width) / 2f
            val top = (size - originalBitmap.height) / 2f
            canvas.drawBitmap(originalBitmap, left, top, null)
            squareBitmap
        } else {
            originalBitmap
        }

        FileOutputStream(tempFile).use { out ->
            processedBitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
        }

        return@withContext PreparedImageInput(
            uri = uri,
            path = tempFile.absolutePath,
            resolution = originalBitmap.width to originalBitmap.height
        )
    }

    null
}

private fun filterSdComponents(
    models: List<ModelEntity>,
    family: com.example.llamadroid.sd.SdModelFamily?,
    variant: String?
): List<ModelEntity> {
    if (family == null) return emptyList()
    return models.filter { it.matchesSdFamily(family, variant) }
}

private fun componentRoleLabelRes(role: SdComponentRole): Int = when (role) {
    SdComponentRole.MAIN_MODEL -> R.string.imagegen_component_main_model
    SdComponentRole.VAE -> R.string.imagegen_component_vae
    SdComponentRole.TAE -> R.string.imagegen_component_tae
    SdComponentRole.CLIP_L -> R.string.imagegen_component_clip_l
    SdComponentRole.CLIP_G -> R.string.imagegen_component_clip_g
    SdComponentRole.T5XXL -> R.string.imagegen_component_t5xxl
    SdComponentRole.LLM -> R.string.imagegen_component_llm
    SdComponentRole.LLM_VISION -> R.string.imagegen_component_llm_vision
    SdComponentRole.CONTROLNET -> R.string.imagegen_component_controlnet
    SdComponentRole.LORA -> R.string.imagegen_component_lora
    SdComponentRole.PHOTOMAKER -> R.string.imagegen_component_photomaker
    SdComponentRole.UPSCALER -> R.string.imagegen_component_upscaler
}

private fun componentRoleLabel(context: Context, role: SdComponentRole): String =
    context.getString(componentRoleLabelRes(role))

@Composable
private fun componentRoleLabel(role: SdComponentRole): String = stringResource(componentRoleLabelRes(role))

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SdComponentPickerField(
    label: String,
    models: List<ModelEntity>,
    selectedPath: String?,
    onSelectionChange: (String?) -> Unit,
    allowNone: Boolean,
    emptyMessage: String
) {
    Text(label, style = MaterialTheme.typography.labelMedium)
    Spacer(modifier = Modifier.height(4.dp))
    if (models.isEmpty()) {
        Text(
            emptyMessage,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error
        )
        return
    }

    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded }
    ) {
        OutlinedTextField(
            value = selectedPath?.substringAfterLast("/")
                ?: if (allowNone) stringResource(R.string.imagegen_none_builtin) else "",
            onValueChange = {},
            readOnly = true,
            modifier = Modifier.fillMaxWidth().menuAnchor(),
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            shape = RoundedCornerShape(12.dp)
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            if (allowNone) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.imagegen_none_builtin)) },
                    onClick = {
                        onSelectionChange(null)
                        expanded = false
                    }
                )
            }
            models.forEach { model ->
                DropdownMenuItem(
                    text = { Text(model.filename) },
                    onClick = {
                        onSelectionChange(model.path)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
private fun LabeledSwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LocalSdCliMemoryControls(
    selectedModel: ModelEntity?,
    maxRamEnabled: Boolean,
    maxRamGiB: String,
    onParamsBackendChange: (SdParamsBackendMode) -> Unit,
    onRuntimeBackendChange: (SdRuntimeBackendMode) -> Unit,
    onMaxRamEnabledChange: (Boolean) -> Unit,
    onMaxRamGiBChange: (String) -> Unit
) {
    val paramsMode = SdParamsBackendMode.fromStoredValue(selectedModel?.sdParamsBackendMode)
    val runtimeMode = SdRuntimeBackendMode.fromStoredValue(selectedModel?.sdRuntimeBackendMode)
    Text(
        stringResource(R.string.sd_models_local_backend_title),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary
    )
    Text(
        stringResource(R.string.imagegen_local_sd_memory_help),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(modifier = Modifier.height(8.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        SdParamsBackendDropdown(
            modifier = Modifier.weight(1f),
            value = paramsMode,
            enabled = selectedModel != null,
            onValueChange = onParamsBackendChange
        )
        SdRuntimeBackendDropdown(
            modifier = Modifier.weight(1f),
            value = runtimeMode,
            enabled = selectedModel != null,
            onValueChange = onRuntimeBackendChange
        )
    }
    Spacer(modifier = Modifier.height(12.dp))
    LabeledSwitchRow(
        label = stringResource(R.string.imagegen_max_cpu_ram_toggle),
        checked = maxRamEnabled,
        onCheckedChange = onMaxRamEnabledChange
    )
    Text(
        stringResource(R.string.imagegen_max_cpu_ram_desc),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    if (maxRamEnabled) {
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(
            value = maxRamGiB,
            onValueChange = onMaxRamGiBChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.imagegen_max_cpu_ram_label)) },
            supportingText = { Text(stringResource(R.string.imagegen_max_cpu_ram_support)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            singleLine = true,
            shape = RoundedCornerShape(12.dp)
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SdParamsBackendDropdown(
    modifier: Modifier = Modifier,
    value: SdParamsBackendMode,
    enabled: Boolean,
    onValueChange: (SdParamsBackendMode) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { if (enabled) expanded = !expanded },
        modifier = modifier
    ) {
        OutlinedTextField(
            value = sdParamsBackendModeLabel(value),
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(),
            label = { Text(stringResource(R.string.sd_models_params_backend_label)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            shape = RoundedCornerShape(12.dp)
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            SdParamsBackendMode.entries.forEach { mode ->
                DropdownMenuItem(
                    text = { Text(sdParamsBackendModeLabel(mode)) },
                    onClick = {
                        onValueChange(mode)
                        expanded = false
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SdRuntimeBackendDropdown(
    modifier: Modifier = Modifier,
    value: SdRuntimeBackendMode,
    enabled: Boolean,
    onValueChange: (SdRuntimeBackendMode) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { if (enabled) expanded = !expanded },
        modifier = modifier
    ) {
        OutlinedTextField(
            value = sdRuntimeBackendModeLabel(value),
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(),
            label = { Text(stringResource(R.string.sd_models_runtime_backend_label)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            shape = RoundedCornerShape(12.dp)
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            SdRuntimeBackendMode.entries.forEach { mode ->
                DropdownMenuItem(
                    text = { Text(sdRuntimeBackendModeLabel(mode)) },
                    onClick = {
                        onValueChange(mode)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
private fun sdParamsBackendModeLabel(mode: SdParamsBackendMode): String = when (mode) {
    SdParamsBackendMode.AUTO -> stringResource(R.string.sd_models_backend_auto)
    SdParamsBackendMode.DISK -> stringResource(R.string.sd_models_params_backend_disk)
}

@Composable
private fun sdRuntimeBackendModeLabel(mode: SdRuntimeBackendMode): String = when (mode) {
    SdRuntimeBackendMode.AUTO -> stringResource(R.string.sd_models_backend_auto)
    SdRuntimeBackendMode.CPU -> stringResource(R.string.sd_models_runtime_backend_cpu)
}

@Composable
private fun buildImageGenerationHelpSections(
    selectedMode: Int,
    cacheArchitecture: SdCacheArchitecture?,
    imageInputMode: SdImageInputMode?,
    hasComponents: Boolean
): List<GenerationOptionHelpSection> {
    val currentModeTitle = when (selectedMode) {
        1 -> stringResource(R.string.imagegen_mode_img2img)
        2 -> stringResource(R.string.imagegen_mode_upscale)
        else -> stringResource(R.string.imagegen_mode_txt2img)
    }
    val currentModeBody = when (selectedMode) {
        1 -> stringResource(R.string.imagegen_help_img2img_body)
        2 -> stringResource(R.string.imagegen_help_upscale_body)
        else -> stringResource(R.string.imagegen_help_txt2img_body)
    }

    val sections = mutableListOf(
        GenerationOptionHelpSection(
            title = currentModeTitle,
            body = currentModeBody
        ),
        GenerationOptionHelpSection(
            title = stringResource(R.string.imagegen_help_models_title),
            items = listOf(
                GenerationOptionHelpItem(
                    stringResource(R.string.imagegen_help_model_item),
                    stringResource(R.string.imagegen_help_model_desc)
                ),
                GenerationOptionHelpItem(
                    stringResource(R.string.imagegen_vae_optional),
                    stringResource(R.string.imagegen_help_vae_desc)
                ),
                GenerationOptionHelpItem(
                    stringResource(R.string.imagegen_quantization_title),
                    stringResource(R.string.imagegen_help_quant_desc)
                )
            ) + if (hasComponents) {
                listOf(
                    GenerationOptionHelpItem(
                        stringResource(R.string.imagegen_components_title),
                        stringResource(R.string.imagegen_help_family_components_desc)
                    )
                )
            } else {
                emptyList()
            }
        )
    )

    sections += GenerationOptionHelpSection(
        title = stringResource(R.string.imagegen_help_families_title),
        items = listOf(
            GenerationOptionHelpItem(
                stringResource(R.string.imagegen_help_family_checkpoints_label),
                stringResource(R.string.imagegen_help_family_checkpoints_desc)
            ),
            GenerationOptionHelpItem(
                stringResource(R.string.imagegen_help_family_sd3_label),
                stringResource(R.string.imagegen_help_family_sd3_desc)
            ),
            GenerationOptionHelpItem(
                stringResource(R.string.imagegen_help_family_flux1_label),
                stringResource(R.string.imagegen_help_family_flux1_desc)
            ),
            GenerationOptionHelpItem(
                stringResource(R.string.imagegen_help_family_flux_kontext_label),
                stringResource(R.string.imagegen_help_family_flux_kontext_desc)
            ),
            GenerationOptionHelpItem(
                stringResource(R.string.imagegen_help_family_flux2_label),
                stringResource(R.string.imagegen_help_family_flux2_desc)
            ),
            GenerationOptionHelpItem(
                stringResource(R.string.imagegen_help_family_chroma_label),
                stringResource(R.string.imagegen_help_family_chroma_desc)
            ),
            GenerationOptionHelpItem(
                stringResource(R.string.imagegen_help_family_qwen_label),
                stringResource(R.string.imagegen_help_family_qwen_desc)
            ),
            GenerationOptionHelpItem(
                stringResource(R.string.imagegen_help_family_qwen_edit_label),
                stringResource(R.string.imagegen_help_family_qwen_edit_desc)
            ),
            GenerationOptionHelpItem(
                stringResource(R.string.imagegen_help_family_z_image_label),
                stringResource(R.string.imagegen_help_family_z_image_desc)
            ),
            GenerationOptionHelpItem(
                stringResource(R.string.imagegen_help_family_ovis_label),
                stringResource(R.string.imagegen_help_family_ovis_desc)
            ),
            GenerationOptionHelpItem(
                stringResource(R.string.imagegen_help_family_anima_label),
                stringResource(R.string.imagegen_help_family_anima_desc)
            ),
            GenerationOptionHelpItem(
                stringResource(R.string.imagegen_help_family_tae_label),
                stringResource(R.string.imagegen_help_family_tae_desc)
            ),
            GenerationOptionHelpItem(
                stringResource(R.string.imagegen_help_family_photomaker_label),
                stringResource(R.string.imagegen_help_family_photomaker_desc)
            ),
            GenerationOptionHelpItem(
                stringResource(R.string.imagegen_help_family_esrgan_label),
                stringResource(R.string.imagegen_help_family_esrgan_desc)
            )
        )
    )

    if (selectedMode != 2) {
        sections += GenerationOptionHelpSection(
            title = stringResource(R.string.imagegen_help_prompting_title),
            items = listOf(
                GenerationOptionHelpItem(
                    stringResource(R.string.imagegen_prompt_label),
                    stringResource(R.string.imagegen_help_prompt_desc)
                ),
                GenerationOptionHelpItem(
                    stringResource(R.string.imagegen_negative_prompt_label),
                    stringResource(R.string.imagegen_help_negative_desc)
                ),
                GenerationOptionHelpItem(
                    stringResource(R.string.imagegen_width_label) + " / " + stringResource(R.string.imagegen_height_label),
                    stringResource(R.string.imagegen_help_size_desc)
                ),
                GenerationOptionHelpItem(
                    stringResource(R.string.imagegen_steps_label) + " / " + stringResource(R.string.imagegen_cfg_label),
                    stringResource(R.string.imagegen_help_steps_cfg_desc)
                ),
                GenerationOptionHelpItem(
                    stringResource(R.string.imagegen_sampler_label),
                    stringResource(R.string.imagegen_help_sampler_desc)
                ),
                GenerationOptionHelpItem(
                    stringResource(R.string.imagegen_seed_label),
                    stringResource(R.string.imagegen_help_seed_desc)
                ),
                GenerationOptionHelpItem(
                    stringResource(R.string.imagegen_threads_label),
                    stringResource(R.string.imagegen_help_threads_desc)
                )
            )
        )
        sections += GenerationOptionHelpSection(
            title = stringResource(R.string.gen_cache_title),
            items = listOf(
                GenerationOptionHelpItem(
                    stringResource(R.string.gen_cache_mode_label),
                    if (cacheArchitecture == SdCacheArchitecture.DIT) {
                        stringResource(R.string.imagegen_help_cache_dit_desc)
                    } else {
                        stringResource(R.string.imagegen_help_cache_unet_desc)
                    }
                ),
                GenerationOptionHelpItem(
                    stringResource(R.string.gen_cache_option_label),
                    stringResource(R.string.imagegen_help_cache_option_desc)
                ),
                GenerationOptionHelpItem(
                    stringResource(R.string.gen_cache_scm_policy_label) + " / " + stringResource(R.string.gen_cache_scm_mask_label),
                    stringResource(R.string.imagegen_help_cache_scm_desc)
                )
            )
        )
        sections += GenerationOptionHelpSection(
            title = stringResource(R.string.imagegen_help_runtime_title),
            items = listOf(
                GenerationOptionHelpItem(
                    stringResource(R.string.imagegen_vae_tiling),
                    stringResource(R.string.imagegen_help_vae_tiling_desc)
                ),
                GenerationOptionHelpItem(
                    stringResource(R.string.imagegen_flow_shift_label),
                    stringResource(R.string.imagegen_help_flow_shift_desc)
                ),
                GenerationOptionHelpItem(
                    stringResource(R.string.imagegen_diffusion_fa_label),
                    stringResource(R.string.imagegen_help_diffusion_fa_desc)
                ),
                GenerationOptionHelpItem(
                    stringResource(R.string.imagegen_mmap_label),
                    stringResource(R.string.imagegen_help_mmap_desc)
                ),
                GenerationOptionHelpItem(
                    stringResource(R.string.imagegen_vae_conv_direct_label),
                    stringResource(R.string.imagegen_help_vae_conv_direct_desc)
                )
            )
        )
    } else {
        sections += GenerationOptionHelpSection(
            title = stringResource(R.string.gen_cache_title),
            body = stringResource(R.string.imagegen_help_cache_upscale_desc)
        )
    }

    if (selectedMode == 1) {
        sections += GenerationOptionHelpSection(
            title = stringResource(R.string.imagegen_mode_img2img),
            items = listOf(
                GenerationOptionHelpItem(
                    stringResource(R.string.imagegen_select_image),
                    if (imageInputMode == SdImageInputMode.REFERENCE_IMAGE) {
                        stringResource(R.string.imagegen_help_reference_image_desc)
                    } else {
                        stringResource(R.string.imagegen_help_input_image_desc)
                    }
                ),
                GenerationOptionHelpItem(
                    "ControlNet",
                    stringResource(R.string.imagegen_help_controlnet_desc)
                ),
                GenerationOptionHelpItem(
                    "LoRA",
                    stringResource(R.string.imagegen_help_lora_desc)
                )
            ) + if (imageInputMode == SdImageInputMode.INIT_IMAGE) {
                listOf(
                    GenerationOptionHelpItem(
                        stringResource(R.string.imagegen_strength_label),
                        stringResource(R.string.imagegen_help_strength_desc)
                    )
                )
            } else {
                emptyList()
            }
        )
    }

    if (selectedMode == 2) {
        sections += GenerationOptionHelpSection(
            title = stringResource(R.string.imagegen_mode_upscale),
            items = listOf(
                GenerationOptionHelpItem(
                    stringResource(R.string.imagegen_select_image),
                    stringResource(R.string.imagegen_help_upscale_input_desc)
                ),
                GenerationOptionHelpItem(
                    stringResource(R.string.imagegen_upscale_factor_label),
                    stringResource(R.string.imagegen_help_upscale_factor_desc)
                ),
                GenerationOptionHelpItem(
                    stringResource(R.string.imagegen_upscale_repeats),
                    stringResource(R.string.imagegen_help_upscale_repeats_desc)
                ),
                GenerationOptionHelpItem(
                    stringResource(R.string.imagegen_output_res),
                    stringResource(R.string.imagegen_help_output_res_desc)
                )
            )
        )
    }

    sections += GenerationOptionHelpSection(
        title = stringResource(R.string.imagegen_help_settings_title),
        body = stringResource(R.string.imagegen_help_settings_body)
    )

    sections += GenerationOptionHelpSection(
        title = stringResource(R.string.imagegen_help_low_ram_title),
        items = listOf(
            GenerationOptionHelpItem(
                stringResource(R.string.imagegen_help_low_ram_models_label),
                stringResource(R.string.imagegen_help_low_ram_models_desc)
            ),
            GenerationOptionHelpItem(
                stringResource(R.string.imagegen_help_low_ram_runtime_label),
                stringResource(R.string.imagegen_help_low_ram_runtime_desc)
            ),
            GenerationOptionHelpItem(
                stringResource(R.string.imagegen_help_low_ram_resolution_label),
                stringResource(R.string.imagegen_help_low_ram_resolution_desc)
            )
        )
    )

    return sections
}

private fun copyUriToFile(context: Context, uri: Uri, targetFile: File) {
    context.contentResolver.openInputStream(uri)?.use { input ->
        FileOutputStream(targetFile).use { output ->
            input.copyTo(output)
        }
    }
}

private fun readImageResolution(context: Context, uri: Uri): Pair<Int, Int>? {
    val options = BitmapFactory.Options().apply {
        inJustDecodeBounds = true
    }
    context.contentResolver.openInputStream(uri)?.use { input ->
        BitmapFactory.decodeStream(input, null, options)
    }
    return if (options.outWidth > 0 && options.outHeight > 0) {
        options.outWidth to options.outHeight
    } else {
        null
    }
}

internal fun decodePreviewImage(path: String?, maxDimension: Int = 256): ImageBitmap? {
    if (path.isNullOrBlank()) return null

    return runCatching {
        val bounds = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sampleSize = 1
        while (bounds.outWidth / sampleSize > maxDimension || bounds.outHeight / sampleSize > maxDimension) {
            sampleSize *= 2
        }

        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
        }
        BitmapFactory.decodeFile(path, options)?.asImageBitmap()
    }.getOrNull()
}

@Composable
private fun rememberPreviewImageBitmap(
    path: String?,
    maxDimension: Int = 256
): State<ImageBitmap?> = produceState<ImageBitmap?>(initialValue = null, key1 = path, key2 = maxDimension) {
    value = loadPreviewImageBitmap(path, maxDimension)
}

internal suspend fun loadPreviewImageBitmap(
    path: String?,
    maxDimension: Int = 256
): ImageBitmap? = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
    decodePreviewImage(path, maxDimension)
}

internal fun readImageFileResolution(path: String?): Pair<Int, Int>? {
    if (path.isNullOrBlank()) return null

    val bounds = BitmapFactory.Options().apply {
        inJustDecodeBounds = true
    }
    BitmapFactory.decodeFile(path, bounds)
    return if (bounds.outWidth > 0 && bounds.outHeight > 0) {
        bounds.outWidth to bounds.outHeight
    } else {
        null
    }
}
