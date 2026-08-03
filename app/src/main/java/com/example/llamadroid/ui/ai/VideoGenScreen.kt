package com.example.llamadroid.ui.ai

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.widget.Toast
import android.widget.VideoView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile
import androidx.navigation.NavController
import com.example.llamadroid.R
import com.example.llamadroid.data.SharedFileHolder
import com.example.llamadroid.data.SettingsRepository
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.data.db.ModelEntity
import com.example.llamadroid.data.db.ModelType
import com.example.llamadroid.data.db.SD_CAPABILITY_VID_GEN
import com.example.llamadroid.data.db.hasSdCapability
import com.example.llamadroid.service.GeneratedVideoMetadata
import com.example.llamadroid.service.SamplingMethod
import com.example.llamadroid.service.SdCacheMode
import com.example.llamadroid.service.SdCacheScmPolicy
import com.example.llamadroid.service.SdScheduler
import com.example.llamadroid.service.VideoGenerationConfig
import com.example.llamadroid.service.VideoGenerationMode
import com.example.llamadroid.service.VideoGenerationService
import com.example.llamadroid.service.VideoGenerationState
import com.example.llamadroid.service.VideoGenerationStateHolder
import com.example.llamadroid.service.loadGeneratedVideoMetadata
import com.example.llamadroid.sd.SdParamsBackendMode
import com.example.llamadroid.sd.SdRuntimeBackendMode
import com.example.llamadroid.ui.components.SdSchedulerPicker
import com.example.llamadroid.ui.navigation.Screen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideoGenScreen(navController: NavController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val batteryGateState = rememberBatteryOptimizationGateState()
    val settingsRepo = remember { SettingsRepository(context) }
    val restoredDraft = remember { settingsRepo.videoGenerationDraft() }
    val keepScreenAwakeDuringGeneration by settingsRepo.keepScreenAwakeDuringGeneration.collectAsState()
    val sdMaxCpuRamEnabled by settingsRepo.sdMaxCpuRamEnabled.collectAsState()
    val sdMaxCpuRamGiB by settingsRepo.sdMaxCpuRamGiB.collectAsState()
    val selectedSdNativeBinary by settingsRepo.stableDiffusionNativeBinarySelection.collectAsState()
    val db = remember { AppDatabase.getDatabase(context) }

    val videoGenModels by db.modelDao().getModelsByType(ModelType.SD_DIFFUSION)
        .collectAsState(initial = emptyList())
    val vaeModels by db.modelDao().getModelsByType(ModelType.SD_VAE)
        .collectAsState(initial = emptyList())
    val t5xxlModels by db.modelDao().getModelsByType(ModelType.SD_T5XXL)
        .collectAsState(initial = emptyList())

    val availableVideoModels = remember(videoGenModels) {
        videoGenModels.filter { it.hasSdCapability(SD_CAPABILITY_VID_GEN) }
    }

    var mainTab by remember { mutableIntStateOf(0) }
    var selectedMode by remember { mutableIntStateOf(restoredDraft?.optInt("mode", 0) ?: 0) }
    var galleryFilter by remember { mutableIntStateOf(0) }

    var selectedVideoModelPath by remember { mutableStateOf(restoredDraft?.optString("model").orEmpty().ifBlank { null }) }
    val selectedVideoModel = availableVideoModels.firstOrNull { it.path == selectedVideoModelPath }
    var prompt by remember { mutableStateOf(restoredDraft?.optString("prompt").orEmpty()) }
    var negativePrompt by remember { mutableStateOf(restoredDraft?.optString("negativePrompt").orEmpty()) }
    var selectedSampler by remember { mutableStateOf(SamplingMethod.entries.firstOrNull { it.name == restoredDraft?.optString("sampler") } ?: SamplingMethod.EULER) }
    var selectedScheduler by remember { mutableStateOf(SdScheduler.fromCliName(restoredDraft?.optString("scheduler"))) }

    var useVae by remember { mutableStateOf(restoredDraft?.optBoolean("useVae", false) ?: false) }
    var selectedVaePath by remember { mutableStateOf(restoredDraft?.optString("vae").orEmpty().ifBlank { null }) }
    var useT5xxl by remember { mutableStateOf(restoredDraft?.optBoolean("useT5", false) ?: false) }
    var selectedT5xxlPath by remember { mutableStateOf(restoredDraft?.optString("t5").orEmpty().ifBlank { null }) }

    var selectedImageUri by remember { mutableStateOf<Uri?>(null) }
    var selectedImagePath by remember { mutableStateOf(restoredDraft?.optString("input").orEmpty().takeIf { it.isNotBlank() && File(it).canRead() }) }
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
        if (acceleratorPlacement != null && diffusionPlacement !in listOf("vulkan0", "opencl0")) {
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

    DisposableEffect(Unit) {
        onDispose {
            settingsRepo.setVideoGenerationDraft(org.json.JSONObject().apply {
                put("mode", selectedMode); put("model", selectedVideoModelPath); put("prompt", prompt); put("negativePrompt", negativePrompt)
                put("useVae", useVae); put("vae", selectedVaePath); put("useT5", useT5xxl); put("t5", selectedT5xxlPath); put("input", selectedImagePath)
                put("frames", videoFramesText); put("fps", fpsText); put("width", widthText); put("height", heightText); put("steps", stepsText); put("cfg", cfgScaleText); put("threads", threadsText); put("sampler", selectedSampler.name); put("scheduler", selectedScheduler?.cliName)
                put("flowShiftEnabled", flowShiftEnabled); put("flowShift", flowShiftText); put("vaeTileSize", vaeTileSize); put("vaeTiling", vaeTiling); put("diffusionFa", diffusionFa); put("mmap", mmap); put("cacheMode", cacheMode?.cliName); put("cacheOption", cacheOption); put("scmMask", scmMask); put("scmPolicy", scmPolicy?.cliName); put("diffConv", diffusionConvDirect); put("vaeConv", vaeConvDirect); put("flags", manualCommandFlags)
                put("tePlacement", textEncoderPlacement); put("diffusionPlacement", diffusionPlacement); put("vaePlacement", vaePlacement)
            })
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
        try {
            selectedImageUri = uri
            val inputStream = context.contentResolver.openInputStream(uri)
            val bitmap = android.graphics.BitmapFactory.decodeStream(inputStream)
            inputStream?.close()
            bitmap?.let { bmp ->
                imageResolution = Pair(bmp.width, bmp.height)
                val processedBitmap = if (bmp.width != bmp.height) {
                    val size = maxOf(bmp.width, bmp.height)
                    val squareBitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
                    val canvas = android.graphics.Canvas(squareBitmap)
                    canvas.drawColor(android.graphics.Color.BLACK)
                    val left = (size - bmp.width) / 2f
                    val top = (size - bmp.height) / 2f
                    canvas.drawBitmap(bmp, left, top, null)
                    squareBitmap
                } else {
                    bmp
                }
                val tempFile = File(context.cacheDir, "video_gen_input_image.png")
                FileOutputStream(tempFile).use { out ->
                    processedBitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                }
                selectedImagePath = tempFile.absolutePath
            }
        } catch (e: Exception) {
            errorMessage = context.getString(R.string.video_gen_error_shared_image, e.message ?: "")
        }
    }

    val imagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let { loadImageInput(it) }
    }

    LaunchedEffect(Unit) {
        reloadGallery()
        val pendingFile = SharedFileHolder.consumePendingFile()
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

    val generateVideo = fun() {
        val mode = if (selectedMode == 1) VideoGenerationMode.IMG2VID else VideoGenerationMode.TXT2VID
        val frames = videoFramesText.toIntOrNull()
        val fps = fpsText.toIntOrNull()
        val width = widthText.toIntOrNull()
        val height = heightText.toIntOrNull()
        val steps = stepsText.toIntOrNull()
        val cfgScale = cfgScaleText.toFloatOrNull()
        val threads = threadsText.toIntOrNull()
        val flowShift = if (flowShiftEnabled) flowShiftText.toFloatOrNull() else null

        when {
            selectedVideoModelPath == null -> {
                errorMessage = context.getString(R.string.video_gen_error_model_required)
                return
            }
            prompt.isBlank() -> {
                errorMessage = context.getString(R.string.video_gen_error_prompt_required)
                return
            }
            mode == VideoGenerationMode.IMG2VID && selectedImagePath == null -> {
                errorMessage = context.getString(R.string.video_gen_error_input_image_required)
                return
            }
            useVae && selectedVaePath == null -> {
                errorMessage = context.getString(R.string.video_gen_error_vae_required)
                return
            }
            useT5xxl && selectedT5xxlPath == null -> {
                errorMessage = context.getString(R.string.video_gen_error_t5xxl_required)
                return
            }
            frames == null || frames <= 0 -> {
                errorMessage = context.getString(R.string.video_gen_error_invalid_number, stringResourceSafe(context, R.string.video_gen_frames_label))
                return
            }
            fps == null || fps <= 0 -> {
                errorMessage = context.getString(R.string.video_gen_error_invalid_number, stringResourceSafe(context, R.string.video_gen_fps_label))
                return
            }
            width == null || width <= 0 -> {
                errorMessage = context.getString(R.string.video_gen_error_invalid_number, stringResourceSafe(context, R.string.video_gen_width_label))
                return
            }
            height == null || height <= 0 -> {
                errorMessage = context.getString(R.string.video_gen_error_invalid_number, stringResourceSafe(context, R.string.video_gen_height_label))
                return
            }
            steps == null || steps <= 0 -> {
                errorMessage = context.getString(R.string.video_gen_error_invalid_number, stringResourceSafe(context, R.string.video_gen_steps_label))
                return
            }
            cfgScale == null || cfgScale <= 0f -> {
                errorMessage = context.getString(R.string.video_gen_error_invalid_number, stringResourceSafe(context, R.string.video_gen_cfg_scale_label))
                return
            }
            threads == null -> {
                errorMessage = context.getString(R.string.video_gen_error_invalid_number, stringResourceSafe(context, R.string.video_gen_threads_label))
                return
            }
            flowShiftEnabled && flowShift == null -> {
                errorMessage = context.getString(R.string.video_gen_error_invalid_number, stringResourceSafe(context, R.string.video_gen_flow_shift_label))
                return
            }
        }

        errorMessage = null
        warningMessage = null

        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val baseName = "video_$timestamp"
        val modeDir = File(outputDir, mode.folderName).apply { mkdirs() }

        val config = VideoGenerationConfig(
            mode = mode,
            prompt = prompt,
            negativePrompt = negativePrompt,
            diffusionModelPath = selectedVideoModelPath ?: "",
            outputAviPath = File(modeDir, "$baseName.avi").absolutePath,
            outputMp4Path = File(modeDir, "$baseName.mp4").absolutePath,
            metadataPath = File(modeDir, "$baseName.json").absolutePath,
            initImagePath = if (mode == VideoGenerationMode.IMG2VID) selectedImagePath else null,
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
            sdParamsBackendMode = selectedVideoModel?.sdParamsBackendMode ?: "auto",
            sdRuntimeBackendMode = acceleratorPlacement?.let {
                "te=$textEncoderPlacement,diffusion=$diffusionPlacement,vae=$vaePlacement"
            } ?: selectedVideoModel?.sdRuntimeBackendMode ?: "auto",
            maxVramCpuGiB = if (sdMaxCpuRamEnabled) sdMaxCpuRamGiB else "",
            customFlags = manualCommandFlags
        )

        batteryGateState.runAfterCheck {
            context.startForegroundService(VideoGenerationService.createStartIntent(context, config))
        }
    }

    val cancelVideo: () -> Unit = {
        val mode = if (selectedMode == 1) VideoGenerationMode.IMG2VID else VideoGenerationMode.TXT2VID
        context.startService(VideoGenerationService.createCancelIntent(context, mode))
        warningMessage = null
    }

    BatteryOptimizationWarningDialog(state = batteryGateState)

    fun shareVideo(metadata: GeneratedVideoMetadata) {
        try {
            val file = File(metadata.mp4Path)
            if (!file.exists()) {
                Toast.makeText(context, context.getString(R.string.video_gen_share_failed_missing), Toast.LENGTH_SHORT).show()
                return
            }
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "video/mp4"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(shareIntent, context.getString(R.string.video_gen_share_chooser)))
        } catch (e: Exception) {
            Toast.makeText(
                context,
                context.getString(R.string.video_gen_share_failed, e.message ?: ""),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    fun copyGenerationInfo(metadata: GeneratedVideoMetadata) {
        try {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText(
                context.getString(R.string.video_gen_copy_info),
                buildVideoGenerationInfoText(context, metadata)
            )
            clipboard.setPrimaryClip(clip)
            Toast.makeText(context, context.getString(R.string.video_gen_copy_info_success), Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(
                context,
                context.getString(R.string.video_gen_copy_info_failed, e.message ?: ""),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    fun deleteVideo(metadata: GeneratedVideoMetadata) {
        scope.launch(Dispatchers.IO) {
            runCatching { metadata.exportedAviUri?.let { deleteDocumentUri(context, it) } }
            runCatching { metadata.exportedMp4Uri?.let { deleteDocumentUri(context, it) } }
            runCatching { metadata.exportedMetadataUri?.let { deleteDocumentUri(context, it) } }
            File(metadata.aviPath).delete()
            File(metadata.mp4Path).delete()
            File(metadata.metadataPath).delete()
            withContext(Dispatchers.Main) {
                selectedGalleryVideo = null
                reloadGallery()
                VideoGenerationStateHolder.txt2vid.removeVideo(metadata)
                VideoGenerationStateHolder.img2vid.removeVideo(metadata)
                Toast.makeText(context, context.getString(R.string.video_gen_delete_success), Toast.LENGTH_SHORT).show()
            }
        }
    }

    val filteredGalleryVideos = remember(galleryVideos, galleryFilter) {
        when (galleryFilter) {
            1 -> galleryVideos.filter { it.modeEnum == VideoGenerationMode.TXT2VID }
            2 -> galleryVideos.filter { it.modeEnum == VideoGenerationMode.IMG2VID }
            else -> galleryVideos
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
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { navController.popBackStack() }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
            }
            Text(
                "🎥 " + stringResource(R.string.video_gen_title),
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold)
            )
            Spacer(modifier = Modifier.weight(1f))
            IconButton(onClick = { showInfoDialog = true }) {
                Icon(Icons.Default.Info, contentDescription = stringResource(R.string.gen_help_open))
            }
        }

        TabRow(
            selectedTabIndex = mainTab,
            modifier = Modifier.padding(horizontal = 16.dp)
        ) {
            listOf(
                stringResource(R.string.video_gen_tab_generate),
                stringResource(R.string.video_gen_tab_gallery)
            ).forEachIndexed { index, label ->
                Tab(
                    selected = mainTab == index,
                    onClick = { mainTab = index },
                    text = { Text(label) }
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
                        stringResource(R.string.video_gen_mode_txt2vid),
                        stringResource(R.string.video_gen_mode_img2vid)
                    )
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        modes.forEachIndexed { index, label ->
                            SegmentedButton(
                                selected = selectedMode == index,
                                onClick = { selectedMode = index },
                                shape = SegmentedButtonDefaults.itemShape(index = index, count = modes.size)
                            ) {
                                Text(label)
                            }
                        }
                    }
                }

                if (selectedMode == 1) {
                    item(key = "input-image") {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                stringResource(R.string.video_gen_input_image_title),
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            if (selectedImagePath != null && imageResolution != null) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    val bitmap by rememberVideoPreviewBitmap(selectedImagePath)
                                    bitmap?.let {
                                        Image(
                                            bitmap = it,
                                            contentDescription = null,
                                            modifier = Modifier
                                                .size(80.dp)
                                                .clip(RoundedCornerShape(10.dp)),
                                            contentScale = ContentScale.Crop
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            "${imageResolution!!.first} × ${imageResolution!!.second}",
                                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                                        )
                                        Text(
                                            stringResource(R.string.video_gen_input_image_ready),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    IconButton(onClick = { imagePicker.launch("image/*") }) {
                                        Icon(Icons.Default.Image, contentDescription = stringResource(R.string.action_change))
                                    }
                                }
                            } else {
                                OutlinedButton(
                                    onClick = { imagePicker.launch("image/*") },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Icon(Icons.Default.Add, contentDescription = null)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(stringResource(R.string.video_gen_select_image))
                                }
                            }
                            }
                        }
                    }
                }

                item(key = "model") { Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            stringResource(R.string.video_gen_model_label),
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        if (availableVideoModels.isEmpty()) {
                            Text(
                                stringResource(R.string.video_gen_no_models_installed),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedButton(onClick = { navController.navigate(Screen.SDModels.route) }) {
                                Icon(Icons.Default.Add, contentDescription = null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(stringResource(R.string.video_gen_get_models))
                            }
                        } else {
                            ModelDropdown(
                                value = selectedVideoModelPath,
                                placeholder = stringResource(R.string.video_gen_select_model),
                                models = availableVideoModels,
                                onSelected = { selectedVideoModelPath = it.path }
                            )
                        }
                    }
                } }

                item(key = "vae") { OptionalModelCard(
                    title = stringResource(R.string.video_gen_vae_toggle_label),
                    enabled = useVae,
                    onEnabledChange = { enabled ->
                        useVae = enabled
                        if (!enabled) {
                            selectedVaePath = null
                        }
                    },
                    models = vaeModels,
                    selectedPath = selectedVaePath,
                    emptyText = stringResource(R.string.video_gen_no_vae_installed),
                    placeholder = stringResource(R.string.video_gen_select_vae),
                    onSelected = { selectedVaePath = it.path },
                    onGetModels = { navController.navigate(Screen.SDModels.route) }
                ) }

                item(key = "t5") { OptionalModelCard(
                    title = stringResource(R.string.video_gen_t5_toggle_label),
                    enabled = useT5xxl,
                    onEnabledChange = { enabled ->
                        useT5xxl = enabled
                        if (!enabled) {
                            selectedT5xxlPath = null
                        }
                    },
                    models = t5xxlModels,
                    selectedPath = selectedT5xxlPath,
                    emptyText = stringResource(R.string.video_gen_no_t5xxl_installed),
                    placeholder = stringResource(R.string.video_gen_select_t5xxl),
                    onSelected = { selectedT5xxlPath = it.path },
                    onGetModels = { navController.navigate(Screen.SDModels.route) }
                ) }

                item(key = "prompts") { Card(
                    modifier = Modifier.fillMaxWidth(),
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
                        OutlinedTextField(
                            value = negativePrompt,
                            onValueChange = { negativePrompt = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text(stringResource(R.string.video_gen_negative_prompt_label)) },
                            placeholder = { Text(stringResource(R.string.video_gen_negative_prompt_placeholder)) },
                            shape = RoundedCornerShape(12.dp)
                        )
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
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            FilterChip(
                                selected = diffusionFa,
                                onClick = { diffusionFa = !diffusionFa },
                                label = { Text(stringResource(R.string.video_gen_diffusion_fa_label)) }
                            )
                            FilterChip(
                                selected = mmap,
                                onClick = { mmap = !mmap },
                                label = { Text(stringResource(R.string.video_gen_mmap_label)) }
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            FilterChip(
                                selected = diffusionConvDirect,
                                onClick = { diffusionConvDirect = !diffusionConvDirect },
                                label = { Text(stringResource(R.string.imagegen_diffusion_conv_direct_label)) }
                            )
                            FilterChip(
                                selected = vaeConvDirect,
                                onClick = { vaeConvDirect = !vaeConvDirect },
                                label = { Text(stringResource(R.string.imagegen_vae_conv_direct_label)) }
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
                            Spacer(modifier = Modifier.height(16.dp))
                            OutlinedButton(
                                onClick = cancelVideo,
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                            ) {
                                Icon(Icons.Default.Close, contentDescription = null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(stringResource(R.string.action_cancel))
                            }
                        }
                    }
                } else {
                    Button(
                        onClick = generateVideo,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp),
                        shape = RoundedCornerShape(16.dp),
                        enabled = selectedVideoModelPath != null &&
                            prompt.isNotBlank() &&
                            (selectedMode == 0 || selectedImagePath != null)
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null)
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            if (selectedMode == 0) {
                                stringResource(R.string.video_gen_generate_txt2vid)
                            } else {
                                stringResource(R.string.video_gen_generate_img2vid)
                            },
                            fontWeight = FontWeight.Bold
                        )
                    }
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
                    .fillMaxSize()
                    .padding(horizontal = 16.dp)
            ) {
                val filters = listOf(
                    stringResource(R.string.video_gen_gallery_all),
                    stringResource(R.string.video_gen_mode_txt2vid),
                    stringResource(R.string.video_gen_mode_img2vid)
                )
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    filters.forEachIndexed { index, label ->
                        SegmentedButton(
                            selected = galleryFilter == index,
                            onClick = { galleryFilter = index },
                            shape = SegmentedButtonDefaults.itemShape(index = index, count = filters.size)
                        ) {
                            Text(label, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                if (filteredGalleryVideos.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("🎞️", style = MaterialTheme.typography.displayLarge)
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                if (galleryFilter == 0) {
                                    stringResource(R.string.video_gen_gallery_empty)
                                } else {
                                    stringResource(R.string.video_gen_gallery_empty_filter, filters[galleryFilter])
                                },
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                } else {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(filteredGalleryVideos, key = { it.mp4Path }) { video ->
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

    selectedGalleryVideo?.let { metadata ->
        VideoDetailDialog(
            metadata = metadata,
            onDismiss = { selectedGalleryVideo = null },
            onShare = { shareVideo(metadata) },
            onInterpolate = {
                SharedFileHolder.setPendingFile(Uri.fromFile(File(metadata.mp4Path)), "video/mp4", Screen.VideoInterpolation.route)
                selectedGalleryVideo = null
                navController.navigate(Screen.VideoInterpolation.route)
            },
            onUpscale = {
                SharedFileHolder.setPendingFile(Uri.fromFile(File(metadata.mp4Path)), "video/mp4", Screen.VideoUpscaler.route)
                selectedGalleryVideo = null
                navController.navigate(Screen.VideoUpscaler.route)
            },
            onInterpolateAndUpscale = {
                SharedFileHolder.setPendingFile(Uri.fromFile(File(metadata.mp4Path)), "video/mp4", "interpolate_then_upscale")
                selectedGalleryVideo = null
                navController.navigate(Screen.Workflows.route)
            },
            onCopyInfo = { copyGenerationInfo(metadata) },
            onDelete = { deleteVideo(metadata) }
        )
    }

    if (showInfoDialog) {
        GenerationOptionsInfoDialog(
            title = stringResource(R.string.video_gen_help_title),
            sections = buildVideoGenerationHelpSections(selectedMode = selectedMode),
            onDismiss = { showInfoDialog = false }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelDropdown(
    value: String?,
    placeholder: String,
    models: List<ModelEntity>,
    onSelected: (ModelEntity) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded }
    ) {
        OutlinedTextField(
            value = value?.substringAfterLast("/") ?: placeholder,
            onValueChange = {},
            readOnly = true,
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(),
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) }
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            models.forEach { model ->
                androidx.compose.material3.DropdownMenuItem(
                    text = { Text(model.filename) },
                    onClick = {
                        onSelected(model)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
private fun OptionalModelCard(
    title: String,
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    models: List<ModelEntity>,
    selectedPath: String?,
    emptyText: String,
    placeholder: String,
    onSelected: (ModelEntity) -> Unit,
    onGetModels: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = enabled,
                    onCheckedChange = onEnabledChange
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                )
            }

            if (enabled) {
                Spacer(modifier = Modifier.height(8.dp))
                if (models.isEmpty()) {
                    Text(
                        emptyText,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedButton(onClick = onGetModels) {
                        Icon(Icons.Default.Add, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.video_gen_get_models))
                    }
                } else {
                    ModelDropdown(
                        value = selectedPath,
                        placeholder = placeholder,
                        models = models,
                        onSelected = onSelected
                    )
                }
            }
        }
    }
}

@Composable
private fun VideoNumberField(
    modifier: Modifier = Modifier,
    label: String,
    value: String,
    onValueChange: (String) -> Unit
) {
    VideoTextField(
        modifier = modifier,
        label = label,
        value = value,
        keyboardType = KeyboardType.Number,
        onValueChange = onValueChange
    )
}

@Composable
private fun VideoTextField(
    modifier: Modifier = Modifier,
    label: String,
    value: String,
    keyboardType: KeyboardType = KeyboardType.Text,
    onValueChange: (String) -> Unit
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        label = { Text(label) },
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        singleLine = true,
        shape = RoundedCornerShape(12.dp)
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VideoLocalSdCliMemoryControls(
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
        VideoSdParamsBackendDropdown(
            modifier = Modifier.weight(1f),
            value = paramsMode,
            enabled = selectedModel != null,
            onValueChange = onParamsBackendChange
        )
        VideoSdRuntimeBackendDropdown(
            modifier = Modifier.weight(1f),
            value = runtimeMode,
            enabled = selectedModel != null,
            onValueChange = onRuntimeBackendChange
        )
    }
    Spacer(modifier = Modifier.height(12.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            stringResource(R.string.imagegen_max_cpu_ram_toggle),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f)
        )
        Switch(
            checked = maxRamEnabled,
            onCheckedChange = onMaxRamEnabledChange
        )
    }
    Text(
        stringResource(R.string.imagegen_max_cpu_ram_desc),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    if (maxRamEnabled) {
        Spacer(modifier = Modifier.height(8.dp))
        VideoTextField(
            modifier = Modifier.fillMaxWidth(),
            label = stringResource(R.string.imagegen_max_cpu_ram_label),
            value = maxRamGiB,
            keyboardType = KeyboardType.Decimal,
            onValueChange = onMaxRamGiBChange
        )
        Text(
            stringResource(R.string.imagegen_max_cpu_ram_support),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VideoSdParamsBackendDropdown(
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
            value = videoSdParamsBackendModeLabel(value),
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
                    text = { Text(videoSdParamsBackendModeLabel(mode)) },
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
private fun VideoSdRuntimeBackendDropdown(
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
            value = videoSdRuntimeBackendModeLabel(value),
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
                    text = { Text(videoSdRuntimeBackendModeLabel(mode)) },
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
private fun videoSdParamsBackendModeLabel(mode: SdParamsBackendMode): String = when (mode) {
    SdParamsBackendMode.AUTO -> stringResource(R.string.sd_models_backend_auto)
    SdParamsBackendMode.DISK -> stringResource(R.string.sd_models_params_backend_disk)
}

@Composable
private fun videoSdRuntimeBackendModeLabel(mode: SdRuntimeBackendMode): String = when (mode) {
    SdRuntimeBackendMode.AUTO -> stringResource(R.string.sd_models_backend_auto)
    SdRuntimeBackendMode.CPU -> stringResource(R.string.sd_models_runtime_backend_cpu)
}

@Composable
private fun rememberVideoPreviewBitmap(path: String?): androidx.compose.runtime.State<ImageBitmap?> =
    produceState<ImageBitmap?>(initialValue = null, key1 = path) {
        value = path?.let { previewPath ->
            withContext(Dispatchers.IO) {
                android.graphics.BitmapFactory.decodeFile(previewPath)?.asImageBitmap()
            }
        }
    }

@Composable
private fun VideoGalleryCard(
    metadata: GeneratedVideoMetadata,
    onClick: () -> Unit
) {
    val thumbnail by produceState<ImageBitmap?>(initialValue = null, metadata.mp4Path) {
        value = withContext(Dispatchers.IO) {
            createVideoThumbnail(metadata.mp4Path)
        }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(modifier = Modifier.padding(12.dp)) {
            Box(
                modifier = Modifier
                    .width(128.dp)
                    .aspectRatio(1.2f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                if (thumbnail != null) {
                    Image(
                        bitmap = thumbnail!!,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                }
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                VideoModeBadge(metadata.modeEnum)
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    metadata.promptSnippet(96),
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    "${metadata.width}×${metadata.height} • ${metadata.videoFrames}f • ${metadata.fps} fps • ${metadata.steps} steps",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    metadata.diffusionModelName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun VideoModeBadge(mode: VideoGenerationMode) {
    val color = if (mode == VideoGenerationMode.TXT2VID) Color(0xFF1976D2) else Color(0xFF2E7D32)
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = color.copy(alpha = 0.12f)
    ) {
        Text(
            text = if (mode == VideoGenerationMode.TXT2VID) {
                stringResource(R.string.video_gen_mode_txt2vid)
            } else {
                stringResource(R.string.video_gen_mode_img2vid)
            },
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelSmall,
            color = color
        )
    }
}

@Composable
private fun VideoDetailDialog(
    metadata: GeneratedVideoMetadata,
    onDismiss: () -> Unit,
    onShare: () -> Unit,
    onInterpolate: () -> Unit,
    onUpscale: () -> Unit,
    onInterpolateAndUpscale: () -> Unit,
    onCopyInfo: () -> Unit,
    onDelete: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                VideoModeBadge(metadata.modeEnum)
                Spacer(modifier = Modifier.height(8.dp))
                Text(stringResource(R.string.video_gen_generated_title))
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                AndroidView(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(220.dp)
                        .clip(RoundedCornerShape(12.dp)),
                    factory = { ctx ->
                        VideoView(ctx).apply {
                            setVideoURI(Uri.fromFile(File(metadata.mp4Path)))
                            setOnPreparedListener { player ->
                                player.isLooping = true
                                start()
                            }
                        }
                    },
                    update = { view ->
                        view.setVideoURI(Uri.fromFile(File(metadata.mp4Path)))
                    }
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    metadata.prompt,
                    style = MaterialTheme.typography.bodyMedium
                )
                if (metadata.negativePrompt.isNotBlank()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    ParameterLine(
                        stringResource(R.string.video_gen_negative_prompt_label),
                        metadata.negativePrompt
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(12.dp))
                ParameterLine(stringResource(R.string.video_gen_model_label), metadata.diffusionModelName)
                ParameterLine(stringResource(R.string.video_gen_frames_label), metadata.videoFrames.toString())
                ParameterLine(stringResource(R.string.video_gen_fps_label), metadata.fps.toString())
                ParameterLine(stringResource(R.string.video_gen_width_label), metadata.width.toString())
                ParameterLine(stringResource(R.string.video_gen_height_label), metadata.height.toString())
                ParameterLine(stringResource(R.string.video_gen_steps_label), metadata.steps.toString())
                ParameterLine(stringResource(R.string.video_gen_cfg_scale_label), metadata.cfgScale.toString())
                metadata.generationDurationMs?.let {
                    ParameterLine(
                        stringResource(R.string.sd_dist_gallery_total_time_label),
                        formatGenerationDuration(it)
                    )
                }
                ParameterLine(stringResource(R.string.sd_dist_gallery_created_label), formatVideoGalleryDate(metadata.createdAt))
                metadata.conditioningDurationMs?.let { ParameterLine(stringResource(R.string.sd_stage_conditioning_label), formatGenerationDuration(it)) }
                metadata.samplingDurationMs?.let { ParameterLine(stringResource(R.string.sd_stage_sampling_label), formatGenerationDuration(it)) }
                metadata.decodingDurationMs?.let { ParameterLine(stringResource(R.string.sd_stage_decoding_label), formatGenerationDuration(it)) }
                metadata.flowShift?.let {
                    ParameterLine(stringResource(R.string.video_gen_flow_shift_label), it.toString())
                }
                ParameterLine(stringResource(R.string.video_gen_sampler_label), metadata.samplingMethod.cliName)
                ParameterLine(
                    stringResource(R.string.gen_cache_mode_label),
                    metadata.cacheMode?.cliName ?: stringResource(R.string.gen_cache_mode_off)
                )
                if (metadata.cacheOption.isNotBlank()) {
                    ParameterLine(stringResource(R.string.gen_cache_option_label), metadata.cacheOption)
                }
                if (metadata.cacheMode == SdCacheMode.CACHE_DIT) {
                    metadata.scmPolicy?.let {
                        ParameterLine(stringResource(R.string.gen_cache_scm_policy_label), it.cliName)
                    }
                    if (metadata.scmMask.isNotBlank()) {
                        ParameterLine(stringResource(R.string.gen_cache_scm_mask_label), metadata.scmMask)
                    }
                }
                ParameterLine(stringResource(R.string.video_gen_threads_label), metadata.threads.toString())
                ParameterLine(stringResource(R.string.video_gen_vae_toggle_label), if (metadata.vaeEnabled) (metadata.vaeName ?: "-") else stringResource(R.string.video_gen_disabled))
                ParameterLine(stringResource(R.string.video_gen_t5_toggle_label), if (metadata.t5xxlEnabled) (metadata.t5xxlName ?: "-") else stringResource(R.string.video_gen_disabled))
                ParameterLine(stringResource(R.string.video_gen_vae_tiling_label), if (metadata.vaeTiling) stringResource(R.string.video_gen_enabled) else stringResource(R.string.video_gen_disabled))
                if (metadata.vaeTiling && !metadata.vaeTileSize.isNullOrBlank()) {
                    ParameterLine(stringResource(R.string.video_gen_vae_tile_size_label), metadata.vaeTileSize)
                }
                ParameterLine(stringResource(R.string.video_gen_diffusion_fa_label), if (metadata.diffusionFa) stringResource(R.string.video_gen_enabled) else stringResource(R.string.video_gen_disabled))
                ParameterLine(stringResource(R.string.imagegen_diffusion_conv_direct_label), if (metadata.diffusionConvDirect) stringResource(R.string.video_gen_enabled) else stringResource(R.string.video_gen_disabled))
                ParameterLine(stringResource(R.string.imagegen_vae_conv_direct_label), if (metadata.vaeConvDirect) stringResource(R.string.video_gen_enabled) else stringResource(R.string.video_gen_disabled))
                ParameterLine(stringResource(R.string.video_gen_mmap_label), if (metadata.mmap) stringResource(R.string.video_gen_enabled) else stringResource(R.string.video_gen_disabled))
                ParameterLine(stringResource(R.string.sd_models_params_backend_label), metadata.sdParamsBackendMode)
                ParameterLine(stringResource(R.string.sd_models_runtime_backend_label), metadata.sdRuntimeBackendMode)
                if (metadata.maxVramCpuGiB.isNotBlank()) {
                    ParameterLine(stringResource(R.string.imagegen_max_cpu_ram_label), metadata.maxVramCpuGiB)
                }
                metadata.initImagePath?.let {
                    ParameterLine(stringResource(R.string.video_gen_input_image_title), File(it).name)
                }
            }
        },
        confirmButton = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onInterpolate) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(stringResource(R.string.video_gen_action_interpolate))
                    }
                    OutlinedButton(onClick = onUpscale) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(stringResource(R.string.video_gen_action_upscale))
                    }
                }
                Button(onClick = onInterpolateAndUpscale, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.video_gen_action_interpolate_upscale))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onCopyInfo) {
                        Icon(Icons.Default.ContentCopy, contentDescription = null)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(stringResource(R.string.video_gen_copy_info))
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(onClick = onShare) {
                        Icon(Icons.Default.Share, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.action_share))
                    }
                }
            }
        },
        dismissButton = {
            Row {
                TextButton(
                    onClick = onDelete,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Icon(Icons.Default.Delete, contentDescription = null)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(stringResource(R.string.action_delete))
                }
                Spacer(modifier = Modifier.width(8.dp))
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.action_close))
                }
            }
        }
    )
}

@Composable
private fun ParameterLine(label: String, value: String) {
    Column(modifier = Modifier.padding(bottom = 8.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

private fun createVideoThumbnail(path: String): ImageBitmap? {
    val retriever = MediaMetadataRetriever()
    return try {
        retriever.setDataSource(path)
        retriever.getFrameAtTime(0)?.asImageBitmap()
    } catch (_: Exception) {
        null
    } finally {
        runCatching { retriever.release() }
    }
}

private fun deleteDocumentUri(context: Context, uriString: String) {
    val uri = Uri.parse(uriString)
    val document = DocumentFile.fromSingleUri(context, uri)
    if (document?.delete() != true) {
        context.contentResolver.delete(uri, null, null)
    }
}

private fun stringResourceSafe(context: Context, resId: Int): String = context.getString(resId)

private fun buildVideoGenerationInfoText(context: Context, metadata: GeneratedVideoMetadata): String {
    val lines = mutableListOf(
        "${context.getString(R.string.video_gen_mode_label)}: ${formatVideoModeLabel(context, metadata.modeEnum)}",
        "${context.getString(R.string.video_gen_prompt_label)}: ${metadata.prompt}",
        "${context.getString(R.string.video_gen_model_label)}: ${metadata.diffusionModelName}",
        "${context.getString(R.string.video_gen_frames_label)}: ${metadata.videoFrames}",
        "${context.getString(R.string.video_gen_fps_label)}: ${metadata.fps}",
        "${context.getString(R.string.video_gen_width_label)}: ${metadata.width}",
        "${context.getString(R.string.video_gen_height_label)}: ${metadata.height}",
        "${context.getString(R.string.video_gen_steps_label)}: ${metadata.steps}",
        "${context.getString(R.string.video_gen_cfg_scale_label)}: ${metadata.cfgScale}",
        "${context.getString(R.string.video_gen_sampler_label)}: ${metadata.samplingMethod.cliName}",
        "${context.getString(R.string.gen_cache_mode_label)}: ${metadata.cacheMode?.cliName ?: context.getString(R.string.gen_cache_mode_off)}",
        "${context.getString(R.string.video_gen_threads_label)}: ${metadata.threads}",
        "${context.getString(R.string.video_gen_vae_toggle_label)}: ${if (metadata.vaeEnabled) (metadata.vaeName ?: "-") else context.getString(R.string.video_gen_disabled)}",
        "${context.getString(R.string.video_gen_t5_toggle_label)}: ${if (metadata.t5xxlEnabled) (metadata.t5xxlName ?: "-") else context.getString(R.string.video_gen_disabled)}",
        "${context.getString(R.string.video_gen_vae_tiling_label)}: ${if (metadata.vaeTiling) context.getString(R.string.video_gen_enabled) else context.getString(R.string.video_gen_disabled)}",
        "${context.getString(R.string.video_gen_diffusion_fa_label)}: ${if (metadata.diffusionFa) context.getString(R.string.video_gen_enabled) else context.getString(R.string.video_gen_disabled)}",
        "${context.getString(R.string.imagegen_diffusion_conv_direct_label)}: ${if (metadata.diffusionConvDirect) context.getString(R.string.video_gen_enabled) else context.getString(R.string.video_gen_disabled)}",
        "${context.getString(R.string.imagegen_vae_conv_direct_label)}: ${if (metadata.vaeConvDirect) context.getString(R.string.video_gen_enabled) else context.getString(R.string.video_gen_disabled)}",
        "${context.getString(R.string.video_gen_mmap_label)}: ${if (metadata.mmap) context.getString(R.string.video_gen_enabled) else context.getString(R.string.video_gen_disabled)}",
        "${context.getString(R.string.sd_models_params_backend_label)}: ${metadata.sdParamsBackendMode}",
        "${context.getString(R.string.sd_models_runtime_backend_label)}: ${metadata.sdRuntimeBackendMode}"
    )
    lines.add("${context.getString(R.string.sd_dist_gallery_created_label)}: ${formatVideoGalleryDate(metadata.createdAt)}")

    if (metadata.negativePrompt.isNotBlank()) {
        lines.add(2, "${context.getString(R.string.video_gen_negative_prompt_label)}: ${metadata.negativePrompt}")
    }
    metadata.generationDurationMs?.let {
        lines.add("${context.getString(R.string.sd_dist_gallery_total_time_label)}: ${formatGenerationDuration(it)}")
    }
    metadata.conditioningDurationMs?.let { lines.add("${context.getString(R.string.sd_stage_conditioning, formatGenerationDuration(it))}") }
    metadata.samplingDurationMs?.let { lines.add("${context.getString(R.string.sd_stage_sampling, formatGenerationDuration(it))}") }
    metadata.decodingDurationMs?.let { lines.add("${context.getString(R.string.sd_stage_decoding, formatGenerationDuration(it))}") }
    if (metadata.vaeTiling && !metadata.vaeTileSize.isNullOrBlank()) {
        lines.add("${context.getString(R.string.video_gen_vae_tile_size_label)}: ${metadata.vaeTileSize}")
    }
    metadata.flowShift?.let {
        lines.add("${context.getString(R.string.video_gen_flow_shift_label)}: $it")
    }
    if (metadata.cacheOption.isNotBlank()) {
        lines.add("${context.getString(R.string.gen_cache_option_label)}: ${metadata.cacheOption}")
    }
    metadata.scmPolicy?.let {
        lines.add("${context.getString(R.string.gen_cache_scm_policy_label)}: ${it.cliName}")
    }
    if (metadata.scmMask.isNotBlank()) {
        lines.add("${context.getString(R.string.gen_cache_scm_mask_label)}: ${metadata.scmMask}")
    }
    if (metadata.maxVramCpuGiB.isNotBlank()) {
        lines.add("${context.getString(R.string.imagegen_max_cpu_ram_label)}: ${metadata.maxVramCpuGiB}")
    }
    metadata.initImagePath?.let {
        lines.add("${context.getString(R.string.video_gen_input_image_title)}: ${File(it).name}")
    }

    return lines.joinToString(separator = "\n")
}

private fun formatGenerationDuration(durationMs: Long): String {
    val totalSeconds = (durationMs / 1000L).coerceAtLeast(0L)
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L
    return when {
        hours > 0L -> "${hours}h ${minutes}m ${seconds}s"
        minutes > 0L -> "${minutes}m ${seconds}s"
        else -> "${seconds}s"
    }
}

private fun formatVideoGalleryDate(timestampMs: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(timestampMs))

private fun formatVideoModeLabel(context: Context, mode: VideoGenerationMode): String {
    return when (mode) {
        VideoGenerationMode.TXT2VID -> context.getString(R.string.video_gen_mode_txt2vid)
        VideoGenerationMode.IMG2VID -> context.getString(R.string.video_gen_mode_img2vid)
    }
}

@Composable
private fun buildVideoGenerationHelpSections(selectedMode: Int): List<GenerationOptionHelpSection> {
    val modeTitle = if (selectedMode == 1) {
        stringResource(R.string.video_gen_mode_img2vid)
    } else {
        stringResource(R.string.video_gen_mode_txt2vid)
    }
    val modeBody = if (selectedMode == 1) {
        stringResource(R.string.video_gen_help_img2vid_body)
    } else {
        stringResource(R.string.video_gen_help_txt2vid_body)
    }

    val sections = mutableListOf(
        GenerationOptionHelpSection(
            title = modeTitle,
            body = modeBody
        ),
        GenerationOptionHelpSection(
            title = stringResource(R.string.video_gen_help_models_title),
            items = listOf(
                GenerationOptionHelpItem(
                    stringResource(R.string.video_gen_model_label),
                    stringResource(R.string.video_gen_help_model_desc)
                ),
                GenerationOptionHelpItem(
                    stringResource(R.string.video_gen_vae_toggle_label),
                    stringResource(R.string.video_gen_help_vae_desc)
                ),
                GenerationOptionHelpItem(
                    stringResource(R.string.video_gen_t5_toggle_label),
                    stringResource(R.string.video_gen_help_t5_desc)
                )
            )
        ),
        GenerationOptionHelpSection(
            title = stringResource(R.string.video_gen_help_prompting_title),
            items = listOf(
                GenerationOptionHelpItem(
                    stringResource(R.string.video_gen_prompt_label),
                    stringResource(R.string.video_gen_help_prompt_desc)
                ),
                GenerationOptionHelpItem(
                    stringResource(R.string.video_gen_negative_prompt_label),
                    stringResource(R.string.video_gen_help_negative_desc)
                ),
                GenerationOptionHelpItem(
                    stringResource(R.string.video_gen_input_image_title),
                    stringResource(R.string.video_gen_help_input_image_desc)
                )
            )
        ),
        GenerationOptionHelpSection(
            title = stringResource(R.string.video_gen_parameters_title),
            items = listOf(
                GenerationOptionHelpItem(
                    stringResource(R.string.video_gen_frames_label) + " / " + stringResource(R.string.video_gen_fps_label),
                    stringResource(R.string.video_gen_help_timing_desc)
                ),
                GenerationOptionHelpItem(
                    stringResource(R.string.video_gen_width_label) + " / " + stringResource(R.string.video_gen_height_label),
                    stringResource(R.string.video_gen_help_size_desc)
                ),
                GenerationOptionHelpItem(
                    stringResource(R.string.video_gen_steps_label) + " / " + stringResource(R.string.video_gen_cfg_scale_label),
                    stringResource(R.string.video_gen_help_steps_cfg_desc)
                ),
                GenerationOptionHelpItem(
                    stringResource(R.string.video_gen_sampler_label),
                    stringResource(R.string.video_gen_help_sampler_desc)
                ),
                GenerationOptionHelpItem(
                    stringResource(R.string.video_gen_threads_label),
                    stringResource(R.string.video_gen_help_threads_desc)
                ),
                GenerationOptionHelpItem(
                    stringResource(R.string.video_gen_flow_shift_toggle_label) + " / " + stringResource(R.string.video_gen_flow_shift_label),
                    stringResource(R.string.video_gen_help_flow_shift_desc)
                ),
                GenerationOptionHelpItem(
                    stringResource(R.string.video_gen_vae_tiling_label) + " / " + stringResource(R.string.video_gen_vae_tile_size_label),
                    stringResource(R.string.video_gen_help_vae_tiling_desc)
                ),
                GenerationOptionHelpItem(
                    stringResource(R.string.video_gen_diffusion_fa_label) + " / " + stringResource(R.string.video_gen_mmap_label),
                    stringResource(R.string.video_gen_help_runtime_flags_desc)
                )
            )
        ),
        GenerationOptionHelpSection(
            title = stringResource(R.string.gen_cache_title),
            items = listOf(
                GenerationOptionHelpItem(
                    stringResource(R.string.gen_cache_mode_label),
                    stringResource(R.string.video_gen_help_cache_mode_desc)
                ),
                GenerationOptionHelpItem(
                    stringResource(R.string.gen_cache_option_label),
                    stringResource(R.string.video_gen_help_cache_option_desc)
                ),
                GenerationOptionHelpItem(
                    stringResource(R.string.gen_cache_scm_policy_label) + " / " + stringResource(R.string.gen_cache_scm_mask_label),
                    stringResource(R.string.video_gen_help_cache_scm_desc)
                )
            )
        )
    )

    return sections
}
