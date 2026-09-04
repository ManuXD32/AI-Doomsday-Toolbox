package com.example.llamadroid.ui.ai

import android.content.Intent
import android.graphics.BitmapFactory
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile
import androidx.navigation.NavController
import coil.compose.AsyncImage
import com.example.llamadroid.R
import com.example.llamadroid.data.SettingsRepository
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.data.db.ModelType
import com.example.llamadroid.data.db.ONNX_CAPABILITY_IMG2IMG
import com.example.llamadroid.data.db.onnxCapabilityTokens
import com.example.llamadroid.onnx.OnnxGeneratedImageMetadata
import com.example.llamadroid.onnx.OnnxBackendOverride
import com.example.llamadroid.onnx.OnnxExecutionMode
import com.example.llamadroid.onnx.OnnxGraphOptimizationLevel
import com.example.llamadroid.onnx.OnnxImageGenConfig
import com.example.llamadroid.onnx.OnnxImageGenMode
import com.example.llamadroid.onnx.OnnxRamProfile
import com.example.llamadroid.onnx.OnnxRuntimeBackend
import com.example.llamadroid.onnx.OnnxRuntimeOptions
import com.example.llamadroid.onnx.ONNX_IMAGE_GEN_DEFAULT_STRENGTH
import com.example.llamadroid.onnx.ONNX_IMG2IMG_CANVAS_SIZE
import com.example.llamadroid.onnx.OnnxStorage
import com.example.llamadroid.onnx.computeOnnxImg2ImgEffectiveSteps
import com.example.llamadroid.onnx.detectOnnxRuntimeFeatureSupport
import com.example.llamadroid.onnx.estimateOnnxRamProfile
import com.example.llamadroid.onnx.isOnnxTxt2ImgBundle
import com.example.llamadroid.onnx.normalizeOnnxCanvasSize
import com.example.llamadroid.onnx.resolveOnnxCatalogEntry
import com.example.llamadroid.onnx.toDisplayLines
import com.example.llamadroid.service.AiServerArtifactTypes
import com.example.llamadroid.service.OnnxImageGenerationService
import com.example.llamadroid.service.OnnxImageGenerationState
import com.example.llamadroid.service.OnnxImageGenerationStateStore
import com.example.llamadroid.ui.navigation.Screen
import com.example.llamadroid.util.FormatUtils
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun OnnxImageGenScreen(navController: NavController) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val startupGuard = rememberAiJobStartupGuard()
    val db = remember { AppDatabase.getDatabase(context) }
    val settingsRepo = remember { SettingsRepository(context) }
    val keepScreenAwakeDuringGeneration by settingsRepo.keepScreenAwakeDuringGeneration.collectAsState()
    val installedModels by db.modelDao().getModelsByType(ModelType.ONNX_IMAGE_GEN).collectAsState(initial = emptyList())
    val serverImagePaths by db.aiServerDao()
        .observeServerArtifactPathsByType(AiServerArtifactTypes.IMAGE)
        .collectAsState(initial = emptyList())
    val serverImagePathSet = remember(serverImagePaths) { serverImagePaths.toSet() }
    val onnxModels = remember(installedModels) { installedModels.filter { it.isOnnxTxt2ImgBundle() } }
    val holder = remember { OnnxImageGenerationStateStore.txt2img }
    val generationState by holder.state.collectAsState()
    val generatedImages by holder.generatedImages.collectAsState()
    val scope = rememberCoroutineScope()

    var mainTab by rememberSaveable { mutableStateOf(0) }
    var selectedModeIndex by rememberSaveable { mutableStateOf(0) }
    var selectedModelId by rememberSaveable { mutableStateOf("") }
    var prompt by rememberSaveable { mutableStateOf("") }
    var negativePrompt by rememberSaveable { mutableStateOf("") }
    var widthText by rememberSaveable { mutableStateOf("512") }
    var heightText by rememberSaveable { mutableStateOf("512") }
    var stepsText by rememberSaveable { mutableStateOf("20") }
    var cfgScaleText by rememberSaveable { mutableStateOf("7.5") }
    var seedText by rememberSaveable { mutableStateOf("") }
    var initImagePath by rememberSaveable { mutableStateOf<String?>(null) }
    var initImageName by rememberSaveable { mutableStateOf("") }
    var strength by rememberSaveable { mutableStateOf(ONNX_IMAGE_GEN_DEFAULT_STRENGTH) }
    var backend by rememberSaveable { mutableStateOf(OnnxRuntimeBackend.CPU) }
    var showAdvancedEssentials by rememberSaveable { mutableStateOf(false) }
    var showExpertPanel by rememberSaveable { mutableStateOf(false) }
    var runtimeThreadCountText by rememberSaveable { mutableStateOf("") }
    var graphOptimizationLevel by rememberSaveable { mutableStateOf(OnnxGraphOptimizationLevel.ALL) }
    var unetBackendOverride by rememberSaveable { mutableStateOf(OnnxBackendOverride.DEFAULT) }
    var vaeDecoderBackendOverride by rememberSaveable { mutableStateOf(OnnxBackendOverride.DEFAULT) }
    var vaeEncoderBackendOverride by rememberSaveable { mutableStateOf(OnnxBackendOverride.DEFAULT) }
    var intraOpThreadsText by rememberSaveable { mutableStateOf("") }
    var interOpThreadsText by rememberSaveable { mutableStateOf("") }
    var executionMode by rememberSaveable { mutableStateOf(OnnxExecutionMode.SEQUENTIAL) }
    var memoryPatternOptimization by rememberSaveable { mutableStateOf(true) }
    var cpuArenaAllocator by rememberSaveable { mutableStateOf(true) }
    var nnapiCpuDisabled by rememberSaveable { mutableStateOf(true) }
    var nnapiUseFp16 by rememberSaveable { mutableStateOf(false) }
    var diskImages by remember { mutableStateOf<List<File>>(emptyList()) }
    var showModelMenu by remember { mutableStateOf(false) }
    var showInfoDialog by remember { mutableStateOf(false) }
    var formError by remember { mutableStateOf<String?>(null) }
    var fullscreenImage by remember { mutableStateOf<File?>(null) }
    var pendingDeleteImage by remember { mutableStateOf<File?>(null) }
    var galleryFilterIndex by rememberSaveable { mutableStateOf(0) }
    var gallerySourceFilterIndex by rememberSaveable { mutableStateOf(0) }

    val metadataCache = remember { mutableStateMapOf<String, OnnxGeneratedImageMetadata?>() }
    val selectedMode = OnnxImageGenMode.entries[selectedModeIndex.coerceIn(0, OnnxImageGenMode.entries.lastIndex)]
    val selectedModel = onnxModels.firstOrNull { it.filename == selectedModelId } ?: onnxModels.firstOrNull()
    val selectedCatalogEntry = selectedModel?.let(::resolveOnnxCatalogEntry)
    val selectedModelSupportsImg2Img = selectedModel?.onnxCapabilityTokens()?.contains(ONNX_CAPABILITY_IMG2IMG) == true
    val selectedModelDisplayName = remember(selectedModel, selectedCatalogEntry) {
        selectedCatalogEntry?.title ?: selectedModel?.filename
    }
    val selectedModelDisplayLabel = remember(selectedCatalogEntry, selectedModelDisplayName, context) {
        selectedCatalogEntry?.let { entry ->
            "${entry.title} · ${onnxProviderLabel(entry.provider, context)}"
        } ?: selectedModelDisplayName
    }
    val runtimeFeatureSupport = remember { detectOnnxRuntimeFeatureSupport() }
    val galleryImages = remember(generatedImages, diskImages) {
        (generatedImages + diskImages)
            .distinctBy { it.absolutePath }
            .sortedByDescending { it.lastModified() }
    }
    val filteredGalleryImages = remember(
        galleryImages,
        metadataCache,
        galleryFilterIndex,
        gallerySourceFilterIndex,
        serverImagePathSet
    ) {
        val modeFiltered = galleryImages.filter { imageFile ->
            val mode = metadataCache[imageFile.absolutePath]?.mode?.let(OnnxImageGenMode::fromMetadataValue)
                ?: OnnxImageGenMode.TXT2IMG
            when (galleryFilterIndex) {
                1 -> mode == OnnxImageGenMode.TXT2IMG
                2 -> mode == OnnxImageGenMode.IMG2IMG
                else -> true
            }
        }
        when (gallerySourceFilterIndex) {
            1 -> modeFiltered.filterNot { it.absolutePath in serverImagePathSet }
            2 -> modeFiltered.filter { it.absolutePath in serverImagePathSet }
            else -> modeFiltered
        }
    }
    val isBusy = generationState is OnnxImageGenerationState.Preparing ||
        generationState is OnnxImageGenerationState.Generating
    val backendLabel = when (backend) {
        OnnxRuntimeBackend.CPU -> resources.getString(R.string.onnx_image_gen_backend_cpu)
        OnnxRuntimeBackend.NNAPI -> resources.getString(R.string.onnx_image_gen_backend_nnapi)
    }
    val normalizedCanvas = remember(selectedMode, widthText, heightText) {
        if (selectedMode == OnnxImageGenMode.IMG2IMG) {
            normalizeOnnxCanvasSize(ONNX_IMG2IMG_CANVAS_SIZE, ONNX_IMG2IMG_CANVAS_SIZE)
        } else {
            val width = widthText.toIntOrNull()
            val height = heightText.toIntOrNull()
            if (width != null && height != null && width >= 1 && height >= 1) {
                normalizeOnnxCanvasSize(width, height)
            } else {
                null
            }
        }
    }
    val ramProfile = normalizedCanvas?.let {
        estimateOnnxRamProfile(it.normalizedWidth, it.normalizedHeight)
    }
    val effectiveImg2ImgSteps = remember(selectedMode, stepsText, strength) {
        val steps = stepsText.toIntOrNull()
        if (selectedMode == OnnxImageGenMode.IMG2IMG && steps != null && steps > 0) {
            computeOnnxImg2ImgEffectiveSteps(steps, strength)
        } else {
            null
        }
    }

    GenerationKeepScreenAwakeEffect(enabled = keepScreenAwakeDuringGeneration && isBusy)

    val initImagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { imageUri ->
        imageUri ?: return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                imageUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
        scope.launch {
            val previousPath = initImagePath
            val result = runCatching {
                copyOnnxInitImageToCache(context, imageUri)
            }
            result.onSuccess { imported ->
                deleteManagedOnnxInitImage(previousPath)
                initImagePath = imported.absolutePath
                initImageName = imported.name
            }.onFailure { error ->
                Toast.makeText(
                    context,
                    resources.getString(
                        R.string.onnx_image_gen_init_image_import_failed,
                        error.message ?: resources.getString(R.string.error_generic)
                    ),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    fun refreshGallery() {
        val outputDir = OnnxStorage.txt2ImgOutputDir(context).apply { mkdirs() }
        val files = outputDir.listFiles()
            ?.filter { it.isFile && it.extension.lowercase(Locale.US) in setOf("png", "jpg", "jpeg") }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()
        diskImages = files
        holder.setImages(files)
        files.forEach { imageFile ->
            metadataCache[imageFile.absolutePath] = OnnxStorage.readMetadata(imageFile)
        }
    }

    fun parseAndValidateConfig(): OnnxImageGenConfig? {
        formError = null
        val model = selectedModel ?: return null
        val isImg2Img = selectedMode == OnnxImageGenMode.IMG2IMG
        val width = if (isImg2Img) ONNX_IMG2IMG_CANVAS_SIZE else widthText.toIntOrNull()
        val height = if (isImg2Img) ONNX_IMG2IMG_CANVAS_SIZE else heightText.toIntOrNull()
        val steps = stepsText.toIntOrNull()
        val cfgScale = cfgScaleText.toFloatOrNull()
        val normalized = if (width != null && height != null) {
            normalizeOnnxCanvasSize(width, height)
        } else {
            null
        }
        when {
            !isImg2Img && (width == null || width < 64) -> {
                formError = resources.getString(R.string.onnx_image_gen_error_invalid_width)
                return null
            }
            !isImg2Img && (height == null || height < 64) -> {
                formError = resources.getString(R.string.onnx_image_gen_error_invalid_height)
                return null
            }
            steps == null || steps !in 1..150 -> {
                formError = resources.getString(R.string.onnx_image_gen_error_invalid_steps)
                return null
            }
            cfgScale == null || cfgScale <= 0f || cfgScale > 30f -> {
                formError = resources.getString(R.string.onnx_image_gen_error_invalid_cfg)
                return null
            }
            selectedMode == OnnxImageGenMode.IMG2IMG && !selectedModelSupportsImg2Img -> {
                formError = resources.getString(R.string.onnx_image_gen_error_model_no_img2img)
                return null
            }
            selectedMode == OnnxImageGenMode.IMG2IMG && initImagePath.isNullOrBlank() -> {
                formError = resources.getString(R.string.onnx_image_gen_error_missing_init_image)
                return null
            }
        }
        val widthValue = width ?: return null
        val heightValue = height ?: return null
        val stepsValue = steps ?: return null
        val cfgScaleValue = cfgScale ?: return null
        val normalizedCanvasValue = normalized ?: return null
        val outputFile = OnnxStorage.buildOutputFile(context, prefix = selectedMode.storageToken)
        return OnnxImageGenConfig(
            modelPath = model.path,
            modelName = selectedCatalogEntry?.let {
                "${it.title} (${onnxProviderLabel(it.provider, context)})"
            } ?: model.filename,
            mode = selectedMode,
            prompt = prompt.trim(),
            negativePrompt = negativePrompt.trim(),
            width = normalizedCanvasValue.normalizedWidth,
            height = normalizedCanvasValue.normalizedHeight,
            steps = stepsValue,
            cfgScale = cfgScaleValue,
            seed = seedText.toLongOrNull() ?: -1L,
            requestedWidth = widthValue,
            requestedHeight = heightValue,
            initImagePath = initImagePath,
            strength = strength,
            backend = backend,
            runtimeOptions = OnnxRuntimeOptions(
                runtimeThreadCount = runtimeThreadCountText.toIntOrNull(),
                graphOptimizationLevel = graphOptimizationLevel,
                unetBackendOverride = unetBackendOverride,
                vaeDecoderBackendOverride = vaeDecoderBackendOverride,
                vaeEncoderBackendOverride = vaeEncoderBackendOverride,
                intraOpThreads = intraOpThreadsText.toIntOrNull(),
                interOpThreads = interOpThreadsText.toIntOrNull(),
                executionMode = executionMode,
                memoryPatternOptimization = memoryPatternOptimization,
                cpuArenaAllocator = cpuArenaAllocator,
                nnapiCpuDisabled = nnapiCpuDisabled,
                nnapiUseFp16 = nnapiUseFp16
            ),
            outputPath = outputFile.absolutePath
        )
    }

    fun startGeneration() {
        if (isBusy) return
        val config = parseAndValidateConfig() ?: return
        if (config.prompt.isBlank()) {
            formError = resources.getString(R.string.onnx_image_gen_error_empty_prompt)
            return
        }
        holder.updateState(OnnxImageGenerationState.Preparing(resources.getString(R.string.onnx_image_gen_status_preparing)))
        startupGuard.run("onnx_image_generation_start") {
            context.startForegroundService(OnnxImageGenerationService.createStartIntent(context, config))
        }
    }

    fun cancelGeneration() {
        context.startService(OnnxImageGenerationService.createCancelIntent(context))
    }

    fun shareImage(imageFile: File) {
        runCatching {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                imageFile
            )
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(shareIntent, resources.getString(R.string.imagegen_share_chooser)))
        }.onFailure { error ->
            Toast.makeText(
                context,
                resources.getString(R.string.onnx_image_gen_share_failed, error.message ?: resources.getString(R.string.error_generic)),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    fun deleteImage(imageFile: File) {
        scope.launch {
            val deleted = OnnxStorage.deleteImageWithMetadata(imageFile)
            if (deleted) {
                metadataCache.remove(imageFile.absolutePath)
                holder.removeImage(imageFile)
                refreshGallery()
            }
            pendingDeleteImage = null
            if (fullscreenImage?.absolutePath == imageFile.absolutePath) {
                fullscreenImage = null
            }
            Toast.makeText(
                context,
                resources.getString(
                    if (deleted) R.string.imagegen_delete_confirm else R.string.imagegen_delete_fail
                ),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    LaunchedEffect(onnxModels) {
        if (selectedModelId.isBlank() || onnxModels.none { it.filename == selectedModelId }) {
            selectedModelId = onnxModels.firstOrNull()?.filename.orEmpty()
        }
    }

    LaunchedEffect(Unit) {
        refreshGallery()
    }

    LaunchedEffect(generationState) {
        if (generationState is OnnxImageGenerationState.Complete) {
            refreshGallery()
            mainTab = 1
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        MaterialTheme.colorScheme.surface,
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f)
                    )
                )
            )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { navController.popBackStack() }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.onnx_image_gen_title),
                    style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    stringResource(R.string.onnx_image_gen_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            IconButton(onClick = { showInfoDialog = true }) {
                Icon(Icons.Default.Info, contentDescription = stringResource(R.string.gen_help_open))
            }
        }

        TabRow(selectedTabIndex = mainTab, modifier = Modifier.padding(horizontal = 16.dp)) {
            Tab(
                selected = mainTab == 0,
                onClick = { mainTab = 0 },
                text = { Text(stringResource(R.string.onnx_image_gen_tab_generate)) }
            )
            Tab(
                selected = mainTab == 1,
                onClick = { mainTab = 1 },
                text = { Text(stringResource(R.string.onnx_image_gen_tab_gallery)) }
            )
        }

        if (mainTab == 0) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (onnxModels.isEmpty()) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                        shape = RoundedCornerShape(18.dp)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                stringResource(R.string.onnx_image_gen_empty_title),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(stringResource(R.string.onnx_image_gen_empty_desc))
                            Spacer(modifier = Modifier.height(12.dp))
                            FilledTonalButton(onClick = { navController.navigate(Screen.OnnxModels.route) }) {
                                Text(stringResource(R.string.onnx_image_gen_open_models))
                            }
                        }
                    }
                } else {
                    OnnxHeroCard(
                        modeLabel = when (selectedMode) {
                            OnnxImageGenMode.TXT2IMG -> stringResource(R.string.imagegen_mode_txt2img)
                            OnnxImageGenMode.IMG2IMG -> stringResource(R.string.imagegen_mode_img2img)
                        },
                        modelName = selectedModelDisplayName ?: stringResource(R.string.onnx_image_gen_model_label),
                        backend = backendLabel,
                        width = normalizedCanvas?.normalizedWidth?.toString() ?: widthText.ifBlank { "512" },
                        height = normalizedCanvas?.normalizedHeight?.toString() ?: heightText.ifBlank { "512" },
                        steps = stepsText.ifBlank { "20" }
                    )

                    OnnxSectionCard(
                        title = stringResource(R.string.onnx_image_gen_mode_section_title),
                        description = stringResource(R.string.onnx_image_gen_mode_section_desc)
                    ) {
                        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                            OnnxImageGenMode.entries.forEachIndexed { index, mode ->
                                SegmentedButton(
                                    selected = selectedMode == mode,
                                    onClick = { selectedModeIndex = index },
                                    shape = androidx.compose.material3.SegmentedButtonDefaults.itemShape(
                                        index = index,
                                        count = OnnxImageGenMode.entries.size
                                    )
                                ) {
                                    Text(
                                        when (mode) {
                                            OnnxImageGenMode.TXT2IMG -> stringResource(R.string.imagegen_mode_txt2img)
                                            OnnxImageGenMode.IMG2IMG -> stringResource(R.string.imagegen_mode_img2img)
                                        }
                                    )
                                }
                            }
                        }
                    }

                    OnnxSectionCard(
                        title = stringResource(R.string.onnx_image_gen_model_section_title),
                        description = if (selectedMode == OnnxImageGenMode.IMG2IMG) {
                            stringResource(R.string.onnx_image_gen_model_section_desc_img2img)
                        } else {
                            stringResource(R.string.onnx_image_gen_model_section_desc)
                        }
                    ) {
                        FilledTonalButton(
                            onClick = { showModelMenu = true },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                selectedModelDisplayLabel ?: stringResource(R.string.onnx_image_gen_model_label),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        DropdownMenu(
                            expanded = showModelMenu,
                            onDismissRequest = { showModelMenu = false }
                        ) {
                            onnxModels
                                .sortedBy { (resolveOnnxCatalogEntry(it)?.title ?: it.filename).lowercase(Locale.getDefault()) }
                                .forEach { model ->
                                val catalogEntry = resolveOnnxCatalogEntry(model)
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            catalogEntry?.let {
                                                "${it.title} · ${onnxProviderLabel(it.provider, context)}"
                                            } ?: model.filename
                                        )
                                    },
                                    onClick = {
                                        selectedModelId = model.filename
                                        showModelMenu = false
                                    }
                                )
                            }
                        }
                    }

                    if (selectedMode == OnnxImageGenMode.IMG2IMG) {
                        OnnxSectionCard(
                            title = stringResource(R.string.onnx_image_gen_img2img_section_title),
                            description = stringResource(R.string.onnx_image_gen_img2img_section_desc)
                        ) {
                            if (!selectedModelSupportsImg2Img && selectedModel != null) {
                                Card(
                                    colors = CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.secondaryContainer
                                    ),
                                    shape = RoundedCornerShape(14.dp)
                                ) {
                                    Text(
                                        stringResource(R.string.onnx_image_gen_img2img_unsupported_desc),
                                        modifier = Modifier.padding(14.dp),
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                }
                                Spacer(modifier = Modifier.height(12.dp))
                            }
                            Card(
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
                                ),
                                shape = RoundedCornerShape(14.dp)
                            ) {
                                Column(modifier = Modifier.padding(14.dp)) {
                                    Text(
                                        stringResource(R.string.onnx_image_gen_img2img_fixed_canvas_title),
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        stringResource(R.string.onnx_image_gen_img2img_fixed_canvas_desc),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(12.dp))
                            FilledTonalButton(
                                onClick = { initImagePicker.launch(arrayOf("image/*")) },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.Image, contentDescription = null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    if (initImagePath.isNullOrBlank()) {
                                        stringResource(R.string.imagegen_select_image)
                                    } else {
                                        initImageName.ifBlank { File(initImagePath ?: "").name }
                                    }
                                )
                            }
                            if (!initImagePath.isNullOrBlank()) {
                                Spacer(modifier = Modifier.height(12.dp))
                                Card(shape = RoundedCornerShape(14.dp)) {
                                    Column(modifier = Modifier.padding(12.dp)) {
                                        AsyncImage(
                                            model = File(initImagePath!!),
                                            contentDescription = initImageName,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .aspectRatio(1.15f)
                                                .clip(RoundedCornerShape(12.dp))
                                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)),
                                            contentScale = ContentScale.Fit
                                        )
                                        Spacer(modifier = Modifier.height(8.dp))
                                        Text(
                                            initImageName.ifBlank { File(initImagePath!!).name },
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Medium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.height(14.dp))
                            Text(
                                stringResource(R.string.onnx_image_gen_strength_title),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Slider(
                                value = strength,
                                onValueChange = { strength = it },
                                valueRange = 0.1f..0.95f
                            )
                            Text(
                                stringResource(
                                    R.string.onnx_image_gen_strength_value,
                                    FormatUtils.Technical.formatDecimal(strength.toDouble(), 2)
                                ),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                stringResource(R.string.imagegen_strength_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            effectiveImg2ImgSteps?.let { effectiveSteps ->
                                Spacer(modifier = Modifier.height(12.dp))
                                Card(
                                    colors = CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f)
                                    ),
                                    shape = RoundedCornerShape(14.dp)
                                ) {
                                    Column(modifier = Modifier.padding(12.dp)) {
                                        Text(
                                            stringResource(
                                                R.string.onnx_image_gen_effective_steps_value,
                                                effectiveSteps,
                                                stepsText.ifBlank { "20" }
                                            ),
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            stringResource(R.string.onnx_image_gen_effective_steps_desc),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onPrimaryContainer
                                        )
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                stringResource(R.string.onnx_image_gen_strength_guidance_title),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                stringResource(R.string.onnx_image_gen_strength_guidance_body),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    OnnxSectionCard(
                        title = stringResource(R.string.onnx_image_gen_prompt_section_title),
                        description = stringResource(R.string.onnx_image_gen_prompt_section_desc)
                    ) {
                        OutlinedTextField(
                            value = prompt,
                            onValueChange = {
                                prompt = it
                                holder.updatePrompt(it)
                            },
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 4,
                            label = { Text(stringResource(R.string.onnx_image_gen_prompt_label)) },
                            shape = RoundedCornerShape(14.dp)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        OutlinedTextField(
                            value = negativePrompt,
                            onValueChange = { negativePrompt = it },
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 3,
                            label = { Text(stringResource(R.string.onnx_image_gen_negative_prompt_label)) },
                            shape = RoundedCornerShape(14.dp)
                        )
                    }

                    OnnxCollapsibleSectionCard(
                        title = stringResource(R.string.onnx_image_gen_advanced_essentials_title),
                        description = stringResource(R.string.onnx_image_gen_advanced_essentials_desc),
                        expanded = showAdvancedEssentials,
                        onToggle = { showAdvancedEssentials = !showAdvancedEssentials }
                    ) {
                        Text(
                            stringResource(R.string.onnx_image_gen_sdai_defaults_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        if (selectedMode == OnnxImageGenMode.IMG2IMG) {
                            Card(
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.75f)
                                ),
                                shape = RoundedCornerShape(14.dp)
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Text(
                                        stringResource(
                                            R.string.onnx_image_gen_normalized_size_value,
                                            ONNX_IMG2IMG_CANVAS_SIZE,
                                            ONNX_IMG2IMG_CANVAS_SIZE
                                        ),
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        stringResource(R.string.onnx_image_gen_img2img_size_locked_desc),
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                            }
                        } else {
                            Text(
                                stringResource(R.string.onnx_image_gen_quick_size_title),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf(64, 256, 512, 768, 1024).forEach { size ->
                                    OnnxQuickValueChip(
                                        label = "${size} x ${size}",
                                        selected = widthText == size.toString() && heightText == size.toString(),
                                        onClick = {
                                            widthText = size.toString()
                                            heightText = size.toString()
                                        }
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(14.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                OnnxNumericField(
                                    value = widthText,
                                    onValueChange = { widthText = it.filter { ch -> ch.isDigit() } },
                                    label = stringResource(R.string.onnx_image_gen_width_label),
                                    modifier = Modifier.weight(1f)
                                )
                                OnnxNumericField(
                                    value = heightText,
                                    onValueChange = { heightText = it.filter { ch -> ch.isDigit() } },
                                    label = stringResource(R.string.onnx_image_gen_height_label),
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            normalizedCanvas?.let { normalized ->
                                Spacer(modifier = Modifier.height(12.dp))
                                Card(
                                    colors = CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.75f)
                                    ),
                                    shape = RoundedCornerShape(14.dp)
                                ) {
                                    Column(modifier = Modifier.padding(12.dp)) {
                                        Text(
                                            stringResource(
                                                R.string.onnx_image_gen_normalized_size_value,
                                                normalized.normalizedWidth,
                                                normalized.normalizedHeight
                                            ),
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            if (normalized.wasAdjusted) {
                                                stringResource(
                                                    R.string.onnx_image_gen_normalized_size_adjusted_desc,
                                                    normalized.requestedWidth,
                                                    normalized.requestedHeight
                                                )
                                            } else {
                                                stringResource(R.string.onnx_image_gen_normalized_size_desc)
                                            },
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    }
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            stringResource(R.string.onnx_image_gen_quick_sampling_title),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(15, 20, 28, 35, 50).forEach { stepPreset ->
                                OnnxQuickValueChip(
                                    label = stepPreset.toString(),
                                    selected = stepsText == stepPreset.toString(),
                                    onClick = { stepsText = stepPreset.toString() }
                                )
                            }
                            listOf("6.5", "7.5", "9", "12").forEach { cfgPreset ->
                                OnnxQuickValueChip(
                                    label = "CFG $cfgPreset",
                                    selected = cfgScaleText == cfgPreset,
                                    onClick = { cfgScaleText = cfgPreset }
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(14.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            OnnxNumericField(
                                value = stepsText,
                                onValueChange = { stepsText = it.filter { ch -> ch.isDigit() } },
                                label = stringResource(R.string.onnx_image_gen_steps_label),
                                modifier = Modifier.weight(1f)
                            )
                            OutlinedTextField(
                                value = cfgScaleText,
                                onValueChange = { value ->
                                    cfgScaleText = value.filter { ch -> ch.isDigit() || ch == '.' }
                                },
                                modifier = Modifier.weight(1f),
                                label = { Text(stringResource(R.string.onnx_image_gen_cfg_label)) },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                singleLine = true,
                                shape = RoundedCornerShape(14.dp)
                            )
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        OutlinedTextField(
                            value = seedText,
                            onValueChange = { value ->
                                seedText = value.filter { ch -> ch.isDigit() || ch == '-' }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text(stringResource(R.string.onnx_image_gen_seed_label)) },
                            placeholder = { Text(stringResource(R.string.onnx_image_gen_seed_placeholder)) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            shape = RoundedCornerShape(14.dp)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            stringResource(R.string.onnx_image_gen_backend_section_title),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                            OnnxRuntimeBackend.entries.forEachIndexed { index, option ->
                                SegmentedButton(
                                    selected = backend == option,
                                    onClick = { backend = option },
                                    shape = androidx.compose.material3.SegmentedButtonDefaults.itemShape(index, OnnxRuntimeBackend.entries.size)
                                ) {
                                    Text(
                                        when (option) {
                                            OnnxRuntimeBackend.CPU -> stringResource(R.string.onnx_image_gen_backend_cpu)
                                            OnnxRuntimeBackend.NNAPI -> stringResource(R.string.onnx_image_gen_backend_nnapi)
                                        }
                                    )
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        OnnxNumericField(
                            value = runtimeThreadCountText,
                            onValueChange = { runtimeThreadCountText = it.filter { ch -> ch.isDigit() } },
                            label = stringResource(R.string.onnx_image_gen_runtime_threads_label),
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            stringResource(R.string.onnx_image_gen_graph_opt_title),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            OnnxGraphOptimizationLevel.entries.forEach { option ->
                                OnnxQuickValueChip(
                                    label = option.name,
                                    selected = graphOptimizationLevel == option,
                                    onClick = { graphOptimizationLevel = option }
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        OnnxRamProfileCard(
                            ramProfile = ramProfile,
                            normalizedCanvas = normalizedCanvas,
                            backendHint = stringResource(R.string.onnx_image_gen_backend_hint)
                        )
                    }

                    OnnxCollapsibleSectionCard(
                        title = stringResource(R.string.onnx_image_gen_expert_panel_title),
                        description = stringResource(R.string.onnx_image_gen_expert_panel_desc),
                        expanded = showExpertPanel,
                        onToggle = { showExpertPanel = !showExpertPanel }
                    ) {
                        Text(
                            stringResource(R.string.onnx_image_gen_expert_parity_warning),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.tertiary
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            stringResource(R.string.onnx_image_gen_component_backends_title),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        OnnxBackendOverrideSelector(
                            title = stringResource(R.string.onnx_image_gen_component_backend_unet),
                            selected = unetBackendOverride,
                            onSelected = { unetBackendOverride = it }
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        OnnxBackendOverrideSelector(
                            title = stringResource(R.string.onnx_image_gen_component_backend_vae_decoder),
                            selected = vaeDecoderBackendOverride,
                            onSelected = { vaeDecoderBackendOverride = it }
                        )
                        if (selectedMode == OnnxImageGenMode.IMG2IMG) {
                            Spacer(modifier = Modifier.height(12.dp))
                            OnnxBackendOverrideSelector(
                                title = stringResource(R.string.onnx_image_gen_component_backend_vae_encoder),
                                selected = vaeEncoderBackendOverride,
                                onSelected = { vaeEncoderBackendOverride = it }
                            )
                        }
                        if (runtimeFeatureSupport.intraOpThreads || runtimeFeatureSupport.interOpThreads) {
                            Spacer(modifier = Modifier.height(16.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                if (runtimeFeatureSupport.intraOpThreads) {
                                    OnnxNumericField(
                                        value = intraOpThreadsText,
                                        onValueChange = { intraOpThreadsText = it.filter { ch -> ch.isDigit() } },
                                        label = stringResource(R.string.onnx_image_gen_intra_threads_label),
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                                if (runtimeFeatureSupport.interOpThreads) {
                                    OnnxNumericField(
                                        value = interOpThreadsText,
                                        onValueChange = { interOpThreadsText = it.filter { ch -> ch.isDigit() } },
                                        label = stringResource(R.string.onnx_image_gen_inter_threads_label),
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }
                        }
                        if (runtimeFeatureSupport.executionMode) {
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                stringResource(R.string.onnx_image_gen_execution_mode_title),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                OnnxExecutionMode.entries.forEach { option ->
                                    OnnxQuickValueChip(
                                        label = option.name,
                                        selected = executionMode == option,
                                        onClick = { executionMode = option }
                                    )
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                        OnnxSwitchRow(
                            label = stringResource(R.string.onnx_image_gen_memory_pattern_label),
                            checked = memoryPatternOptimization,
                            onCheckedChange = { memoryPatternOptimization = it },
                            enabled = runtimeFeatureSupport.memoryPatternOptimization
                        )
                        OnnxSwitchRow(
                            label = stringResource(R.string.onnx_image_gen_cpu_arena_label),
                            checked = cpuArenaAllocator,
                            onCheckedChange = { cpuArenaAllocator = it },
                            enabled = runtimeFeatureSupport.cpuArenaAllocator
                        )
                        if (runtimeFeatureSupport.nnapi) {
                            OnnxSwitchRow(
                                label = stringResource(R.string.onnx_image_gen_nnapi_cpu_disabled_label),
                                checked = nnapiCpuDisabled,
                                onCheckedChange = { nnapiCpuDisabled = it },
                                enabled = runtimeFeatureSupport.nnapiCpuDisabled
                            )
                            OnnxSwitchRow(
                                label = stringResource(R.string.onnx_image_gen_nnapi_fp16_label),
                                checked = nnapiUseFp16,
                                onCheckedChange = { nnapiUseFp16 = it },
                                enabled = runtimeFeatureSupport.nnapiUseFp16
                            )
                        }
                    }

                    when (val state = generationState) {
                        is OnnxImageGenerationState.Preparing -> {
                            OnnxProgressCard(
                                status = state.status,
                                progress = 0f,
                                elapsedMs = state.elapsedMs,
                                etaMs = state.etaMs
                            )
                        }
                        is OnnxImageGenerationState.Generating -> {
                            OnnxProgressCard(
                                status = state.status,
                                progress = state.progress,
                                elapsedMs = state.elapsedMs,
                                etaMs = state.etaMs
                            )
                        }
                        is OnnxImageGenerationState.Complete -> {
                            Card(
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
                                shape = RoundedCornerShape(18.dp)
                            ) {
                                Column(modifier = Modifier.padding(16.dp)) {
                                    Text(
                                        stringResource(R.string.onnx_image_gen_complete),
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(File(state.outputPath).name)
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        stringResource(
                                            R.string.onnx_image_gen_mode_value,
                                            metadataLabelForMode(
                                                OnnxStorage.readMetadata(File(state.outputPath))?.mode,
                                                context
                                            )
                                        ),
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                    state.durationMs?.let { durationMs ->
                                        Spacer(modifier = Modifier.height(8.dp))
                                        Text(
                                            stringResource(
                                                R.string.onnx_image_gen_total_time_value,
                                                formatOnnxDuration(durationMs)
                                            ),
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Medium
                                        )
                                    }
                                    if (!state.warningMessage.isNullOrBlank()) {
                                        Spacer(modifier = Modifier.height(12.dp))
                                        Text(
                                            state.warningMessage,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onTertiaryContainer
                                        )
                                    }
                                }
                            }
                        }
                        is OnnxImageGenerationState.Error -> {
                            Card(
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                                shape = RoundedCornerShape(18.dp)
                            ) {
                                Text(
                                    state.message,
                                    modifier = Modifier.padding(16.dp),
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                            }
                        }
                        OnnxImageGenerationState.Idle -> Unit
                    }

                    formError?.let { message ->
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                            shape = RoundedCornerShape(18.dp)
                        ) {
                            Text(
                                message,
                                modifier = Modifier.padding(16.dp),
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(
                            onClick = { startGeneration() },
                            enabled = !isBusy &&
                                selectedModel != null &&
                                prompt.isNotBlank() &&
                                (selectedMode != OnnxImageGenMode.IMG2IMG ||
                                    (selectedModelSupportsImg2Img && !initImagePath.isNullOrBlank())),
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(16.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.imagegen_generate_btn))
                        }
                        FilledTonalButton(
                            onClick = { cancelGeneration() },
                            enabled = isBusy,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Text(stringResource(R.string.action_cancel))
                        }
                    }
                }
            }
        } else {
            if (galleryImages.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        stringResource(R.string.onnx_image_gen_gallery_empty),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp)
                ) {
                    SingleChoiceSegmentedButtonRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 12.dp)
                    ) {
                        listOf(
                            stringResource(R.string.onnx_image_gen_gallery_filter_all),
                            stringResource(R.string.imagegen_mode_txt2img),
                            stringResource(R.string.imagegen_mode_img2img)
                        ).forEachIndexed { index, label ->
                            SegmentedButton(
                                selected = galleryFilterIndex == index,
                                onClick = { galleryFilterIndex = index },
                                shape = androidx.compose.material3.SegmentedButtonDefaults.itemShape(
                                    index = index,
                                    count = 3
                                )
                            ) {
                                Text(label)
                            }
                        }
                    }
                    SingleChoiceSegmentedButtonRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 12.dp)
                    ) {
                        listOf(
                            stringResource(R.string.ai_servers_gallery_source_all),
                            stringResource(R.string.ai_servers_gallery_source_app),
                            stringResource(R.string.ai_servers_gallery_source_server)
                        ).forEachIndexed { index, label ->
                            SegmentedButton(
                                selected = gallerySourceFilterIndex == index,
                                onClick = { gallerySourceFilterIndex = index },
                                shape = androidx.compose.material3.SegmentedButtonDefaults.itemShape(
                                    index = index,
                                    count = 3
                                )
                            ) {
                                Text(label)
                            }
                        }
                    }
                    if (filteredGalleryImages.isEmpty()) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(
                                stringResource(R.string.onnx_image_gen_gallery_empty_filter),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else {
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(2),
                            modifier = Modifier.fillMaxSize(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            items(filteredGalleryImages, key = { it.absolutePath }) { imageFile ->
                                val metadata = metadataCache[imageFile.absolutePath]
                                Card(
                                    modifier = Modifier.clickable { fullscreenImage = imageFile },
                                    shape = RoundedCornerShape(16.dp)
                                ) {
                                    Column(modifier = Modifier.padding(10.dp)) {
                                        AsyncImage(
                                            model = imageFile,
                                            contentDescription = imageFile.name,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .aspectRatio(1f)
                                                .clip(RoundedCornerShape(12.dp)),
                                            contentScale = ContentScale.Crop
                                        )
                                        Spacer(modifier = Modifier.height(8.dp))
                                        Text(
                                            metadata?.modelName ?: imageFile.name,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.SemiBold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            metadataLabelForMode(metadata?.mode, context),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                        Text(
                                            formatOnnxGalleryTimestamp(metadata?.createdAtEpochMs ?: imageFile.lastModified()),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        metadata?.totalTimeMs?.let { totalTimeMs ->
                                            Text(
                                                stringResource(
                                                    R.string.onnx_image_gen_gallery_card_details,
                                                    metadata.steps,
                                                    formatOnnxDuration(totalTimeMs)
                                                ),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showInfoDialog) {
        GenerationOptionsInfoDialog(
            title = stringResource(R.string.onnx_image_gen_info_title),
            subtitle = stringResource(R.string.onnx_image_gen_info_subtitle),
            sections = buildOnnxImageGenerationHelpSections(),
            onDismiss = { showInfoDialog = false }
        )
    }

    if (fullscreenImage != null) {
        val imageFile = fullscreenImage!!
        val metadata = metadataCache[imageFile.absolutePath] ?: OnnxStorage.readMetadata(imageFile)
        LaunchedEffect(imageFile.absolutePath, metadata) {
            metadataCache[imageFile.absolutePath] = metadata
        }
        OnnxFullscreenImageDialog(
            imageFile = imageFile,
            metadata = metadata,
            onDismiss = { fullscreenImage = null },
            onShare = { shareImage(imageFile) },
            onDelete = { pendingDeleteImage = imageFile }
        )
    }

    pendingDeleteImage?.let { imageFile ->
        AlertDialog(
            onDismissRequest = { pendingDeleteImage = null },
            title = { Text(stringResource(R.string.onnx_image_gen_delete_title)) },
            text = { Text(stringResource(R.string.onnx_image_gen_delete_desc, imageFile.name)) },
            confirmButton = {
                TextButton(onClick = { deleteImage(imageFile) }) {
                    Text(stringResource(R.string.action_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteImage = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }
}

@Composable
private fun OnnxSectionCard(
    title: String,
    description: String,
    content: @Composable () -> Unit
) {
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.34f)),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(14.dp))
            content()
        }
    }
}

@Composable
private fun OnnxCollapsibleSectionCard(
    title: String,
    description: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable () -> Unit
) {
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.34f)),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggle),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Icon(
                    imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = null
                )
            }
            if (expanded) {
                Spacer(modifier = Modifier.height(14.dp))
                content()
            }
        }
    }
}

@Composable
private fun OnnxNumericField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        label = { Text(label) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        singleLine = true,
        shape = RoundedCornerShape(14.dp)
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OnnxBackendOverrideSelector(
    title: String,
    selected: OnnxBackendOverride,
    onSelected: (OnnxBackendOverride) -> Unit
) {
    Column {
        Text(
            title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OnnxBackendOverride.entries.forEach { option ->
                OnnxQuickValueChip(
                    label = option.name,
                    selected = selected == option,
                    onClick = { onSelected(option) }
                )
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            stringResource(R.string.onnx_image_gen_backend_override_selected, selected.name),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun OnnxSwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium
        )
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled
        )
    }
}

@Composable
private fun OnnxRamProfileCard(
    ramProfile: OnnxRamProfile?,
    normalizedCanvas: com.example.llamadroid.onnx.OnnxNormalizedCanvasSize?,
    backendHint: String
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)),
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                stringResource(R.string.onnx_image_gen_ram_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                stringResource(
                    R.string.onnx_image_gen_ram_profile_value,
                    when (ramProfile ?: OnnxRamProfile.LOW) {
                        OnnxRamProfile.LOW -> stringResource(R.string.onnx_image_gen_ram_profile_low)
                        OnnxRamProfile.MEDIUM -> stringResource(R.string.onnx_image_gen_ram_profile_medium)
                        OnnxRamProfile.HIGH -> stringResource(R.string.onnx_image_gen_ram_profile_high)
                        OnnxRamProfile.EXTREME -> stringResource(R.string.onnx_image_gen_ram_profile_extreme)
                    }
                ),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )
            normalizedCanvas?.let {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    stringResource(
                        R.string.onnx_image_gen_ram_canvas_value,
                        it.normalizedWidth,
                        it.normalizedHeight
                    ),
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                backendHint,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OnnxHeroCard(
    modeLabel: String,
    modelName: String,
    backend: String,
    width: String,
    height: String,
    steps: String
) {
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.linearGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.primaryContainer,
                            MaterialTheme.colorScheme.secondaryContainer,
                            MaterialTheme.colorScheme.tertiaryContainer
                        )
                    )
                )
                .padding(18.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    stringResource(R.string.onnx_image_gen_hero_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Text(
                    modelName,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OnnxHeroPill(label = modeLabel)
                    OnnxHeroPill(label = backend)
                    OnnxHeroPill(label = "${width} x ${height}")
                    OnnxHeroPill(label = stringResource(R.string.onnx_image_gen_steps_value, steps))
                }
            }
        }
    }
}

@Composable
private fun OnnxHeroPill(label: String) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f)
    ) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun OnnxQuickValueChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        colors = FilterChipDefaults.filterChipColors(
            containerColor = MaterialTheme.colorScheme.surface,
            labelColor = MaterialTheme.colorScheme.onSurface,
            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
        ),
        border = BorderStroke(
            1.dp,
            if (selected) {
                MaterialTheme.colorScheme.primary.copy(alpha = 0.72f)
            } else {
                MaterialTheme.colorScheme.outlineVariant
            }
        ),
        leadingIcon = if (selected) {
            {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(MaterialTheme.colorScheme.primary)
                )
            }
        } else {
            null
        }
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OnnxProgressCard(
    status: String,
    progress: Float,
    elapsedMs: Long,
    etaMs: Long?
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        shape = RoundedCornerShape(18.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                status,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OnnxHeroPill(
                    label = stringResource(
                        R.string.onnx_image_gen_elapsed_value,
                        formatOnnxDuration(elapsedMs)
                    )
                )
                etaMs?.let {
                    OnnxHeroPill(
                        label = stringResource(
                            R.string.onnx_image_gen_eta_value,
                            formatOnnxDuration(it)
                        )
                    )
                }
                OnnxHeroPill(
                    label = stringResource(
                        R.string.onnx_image_gen_progress_value,
                        (progress.coerceIn(0f, 1f) * 100f).toInt()
                    )
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            LinearProgressIndicator(
                progress = { progress.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun OnnxFullscreenImageDialog(
    imageFile: File,
    metadata: OnnxGeneratedImageMetadata?,
    onDismiss: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit
) {
    val bitmap = remember(imageFile.absolutePath) {
        runCatching { BitmapFactory.decodeFile(imageFile.absolutePath)?.asImageBitmap() }.getOrNull()
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.94f)
                .padding(12.dp),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            metadata?.modelName ?: imageFile.name,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            formatOnnxGalleryTimestamp(metadata?.createdAtEpochMs ?: imageFile.lastModified()),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Row {
                        IconButton(onClick = onShare) {
                            Icon(Icons.Default.Share, contentDescription = stringResource(R.string.action_share))
                        }
                        IconButton(onClick = onDelete) {
                            Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.action_delete))
                        }
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.action_close))
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                Card(shape = RoundedCornerShape(18.dp)) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f)
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)),
                        contentAlignment = Alignment.Center
                    ) {
                        bitmap?.let {
                            Image(
                                bitmap = it,
                                contentDescription = null,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Fit
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Card(
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            stringResource(R.string.onnx_image_gen_metadata_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        OnnxMetadataRow(
                            stringResource(R.string.onnx_image_gen_metadata_mode),
                            metadataLabelForMode(metadata?.mode, LocalContext.current)
                        )
                        OnnxMetadataRow(stringResource(R.string.onnx_image_gen_metadata_model), metadata?.modelName ?: imageFile.name)
                        OnnxMetadataRow(
                            stringResource(R.string.onnx_image_gen_metadata_prompt),
                            metadata?.prompt?.ifBlank { "-" } ?: "-"
                        )
                        OnnxMetadataRow(
                            stringResource(R.string.onnx_image_gen_metadata_negative_prompt),
                            metadata?.negativePrompt?.ifBlank { "-" } ?: "-"
                        )
                        OnnxMetadataRow(
                            stringResource(R.string.onnx_image_gen_metadata_requested_size),
                            metadata?.let { "${it.requestedWidth} x ${it.requestedHeight}" } ?: "-"
                        )
                        OnnxMetadataRow(
                            stringResource(R.string.onnx_image_gen_metadata_size),
                            metadata?.let { "${it.width} x ${it.height}" } ?: "-"
                        )
                        OnnxMetadataRow(
                            stringResource(R.string.onnx_image_gen_metadata_steps),
                            metadata?.steps?.toString() ?: "-"
                        )
                        metadata?.effectiveSteps?.let { effectiveSteps ->
                            OnnxMetadataRow(
                                stringResource(R.string.onnx_image_gen_metadata_effective_steps),
                                effectiveSteps.toString()
                            )
                        }
                        OnnxMetadataRow(
                            stringResource(R.string.onnx_image_gen_metadata_cfg),
                            metadata?.cfgScale?.toString() ?: "-"
                        )
                        OnnxMetadataRow(
                            stringResource(R.string.onnx_image_gen_metadata_seed),
                            metadata?.seed?.toString() ?: "-"
                        )
                        metadata?.initImagePath?.let { initImagePath ->
                            OnnxMetadataRow(
                                stringResource(R.string.onnx_image_gen_metadata_init_image),
                                File(initImagePath).name
                            )
                        }
                        if (metadata?.initImageOriginalWidth != null && metadata.initImageOriginalHeight != null) {
                            OnnxMetadataRow(
                                stringResource(R.string.onnx_image_gen_metadata_source_size),
                                "${metadata.initImageOriginalWidth} x ${metadata.initImageOriginalHeight}"
                            )
                        }
                        if (metadata?.initImageCanvasWidth != null && metadata.initImageCanvasHeight != null) {
                            OnnxMetadataRow(
                                stringResource(R.string.onnx_image_gen_metadata_padded_canvas),
                                "${metadata.initImageCanvasWidth} x ${metadata.initImageCanvasHeight}"
                            )
                        }
                        if (metadata?.initImageFittedWidth != null && metadata.initImageFittedHeight != null) {
                            OnnxMetadataRow(
                                stringResource(R.string.onnx_image_gen_metadata_fitted_source),
                                "${metadata.initImageFittedWidth} x ${metadata.initImageFittedHeight}"
                            )
                        }
                        if (metadata?.initImagePaddingLeft != null && metadata.initImagePaddingTop != null) {
                            OnnxMetadataRow(
                                stringResource(R.string.onnx_image_gen_metadata_padding),
                                "${metadata.initImagePaddingLeft}, ${metadata.initImagePaddingTop}"
                            )
                        }
                        metadata?.strength?.let { strengthValue ->
                            OnnxMetadataRow(
                                stringResource(R.string.onnx_image_gen_metadata_strength),
                                FormatUtils.Technical.formatDecimal(strengthValue.toDouble(), 2)
                            )
                        }
                        OnnxMetadataRow(
                            stringResource(R.string.onnx_image_gen_metadata_backend),
                            metadata?.backend ?: "-"
                        )
                        metadata?.resolvedBackendSummary?.let { summary ->
                            OnnxMetadataRow(
                                stringResource(R.string.onnx_image_gen_metadata_component_backends),
                                buildList {
                                    add("text_encoder=${summary.textEncoder.resolvedBackend}")
                                    add("unet=${summary.unet.resolvedBackend}")
                                    add("vae_decoder=${summary.vaeDecoder.resolvedBackend}")
                                    summary.vaeEncoder?.let { add("vae_encoder=${it.resolvedBackend}") }
                                }.joinToString("\n")
                            )
                        }
                        metadata?.runtimeOptions?.toDisplayLines()?.takeIf { it.isNotEmpty() }?.let { lines ->
                            OnnxMetadataRow(
                                stringResource(R.string.onnx_image_gen_metadata_runtime_options),
                                lines.joinToString("\n")
                            )
                        }
                        metadata?.totalTimeMs?.let { totalTimeMs ->
                            OnnxMetadataRow(
                                stringResource(R.string.onnx_image_gen_metadata_total_time),
                                formatOnnxDuration(totalTimeMs)
                            )
                        }
                        metadata?.warningMessage?.takeIf { it.isNotBlank() }?.let { warning ->
                            OnnxMetadataRow(
                                stringResource(R.string.onnx_image_gen_metadata_warning),
                                warning
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun OnnxMetadataRow(label: String, value: String) {
    Column {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun buildOnnxImageGenerationHelpSections(): List<GenerationOptionHelpSection> {
    return listOf(
        GenerationOptionHelpSection(
            title = stringResource(R.string.onnx_image_gen_info_bundles_title),
            body = stringResource(R.string.onnx_image_gen_info_bundles_body),
            items = listOf(
                GenerationOptionHelpItem(
                    label = stringResource(R.string.onnx_image_gen_info_bundle_layout_label),
                    description = stringResource(R.string.onnx_image_gen_info_bundle_layout_desc)
                ),
                GenerationOptionHelpItem(
                    label = stringResource(R.string.onnx_image_gen_info_bundle_runtime_label),
                    description = stringResource(R.string.onnx_image_gen_info_bundle_runtime_desc)
                )
            )
        ),
        GenerationOptionHelpSection(
            title = stringResource(R.string.onnx_image_gen_info_prompts_title),
            body = stringResource(R.string.onnx_image_gen_info_prompts_body),
            items = listOf(
                GenerationOptionHelpItem(
                    label = stringResource(R.string.onnx_image_gen_prompt_label),
                    description = stringResource(R.string.onnx_image_gen_info_prompt_item_desc)
                ),
                GenerationOptionHelpItem(
                    label = stringResource(R.string.onnx_image_gen_negative_prompt_label),
                    description = stringResource(R.string.onnx_image_gen_info_negative_prompt_item_desc)
                )
            )
        ),
        GenerationOptionHelpSection(
            title = stringResource(R.string.onnx_image_gen_info_canvas_title),
            body = stringResource(R.string.onnx_image_gen_info_canvas_body),
            items = listOf(
                GenerationOptionHelpItem(
                    label = stringResource(R.string.onnx_image_gen_width_label),
                    description = stringResource(R.string.onnx_image_gen_info_width_item_desc)
                ),
                GenerationOptionHelpItem(
                    label = stringResource(R.string.onnx_image_gen_height_label),
                    description = stringResource(R.string.onnx_image_gen_info_height_item_desc)
                ),
                GenerationOptionHelpItem(
                    label = stringResource(R.string.onnx_image_gen_info_normalized_size_label),
                    description = stringResource(R.string.onnx_image_gen_info_normalized_size_item_desc)
                )
            )
        ),
        GenerationOptionHelpSection(
            title = stringResource(R.string.onnx_image_gen_info_sampling_title),
            body = stringResource(R.string.onnx_image_gen_info_sampling_body),
            items = listOf(
                GenerationOptionHelpItem(
                    label = stringResource(R.string.onnx_image_gen_steps_label),
                    description = stringResource(R.string.onnx_image_gen_info_steps_item_desc)
                ),
                GenerationOptionHelpItem(
                    label = stringResource(R.string.onnx_image_gen_effective_steps_label),
                    description = stringResource(R.string.onnx_image_gen_info_effective_steps_item_desc)
                ),
                GenerationOptionHelpItem(
                    label = stringResource(R.string.onnx_image_gen_cfg_label),
                    description = stringResource(R.string.onnx_image_gen_info_cfg_item_desc)
                ),
                GenerationOptionHelpItem(
                    label = stringResource(R.string.onnx_image_gen_seed_label),
                    description = stringResource(R.string.onnx_image_gen_info_seed_item_desc)
                )
            )
        ),
        GenerationOptionHelpSection(
            title = stringResource(R.string.onnx_image_gen_info_img2img_title),
            body = stringResource(R.string.onnx_image_gen_info_img2img_body),
            items = listOf(
                GenerationOptionHelpItem(
                    label = stringResource(R.string.onnx_image_gen_init_image_label),
                    description = stringResource(R.string.onnx_image_gen_info_img2img_source_item_desc)
                ),
                GenerationOptionHelpItem(
                    label = stringResource(R.string.onnx_image_gen_img2img_fixed_canvas_title),
                    description = stringResource(R.string.onnx_image_gen_info_img2img_canvas_item_desc)
                ),
                GenerationOptionHelpItem(
                    label = stringResource(R.string.imagegen_strength),
                    description = stringResource(R.string.onnx_image_gen_info_img2img_strength_item_desc)
                ),
                GenerationOptionHelpItem(
                    label = stringResource(R.string.onnx_image_gen_strength_guidance_title),
                    description = stringResource(R.string.onnx_image_gen_info_img2img_editing_item_desc)
                )
            )
        ),
        GenerationOptionHelpSection(
            title = stringResource(R.string.onnx_image_gen_info_backend_title),
            body = stringResource(R.string.onnx_image_gen_info_backend_body),
            items = listOf(
                GenerationOptionHelpItem(
                    label = stringResource(R.string.onnx_image_gen_backend_label),
                    description = stringResource(R.string.onnx_image_gen_info_backend_profile_item_desc)
                ),
                GenerationOptionHelpItem(
                    label = stringResource(R.string.onnx_image_gen_runtime_threads_label),
                    description = stringResource(R.string.onnx_image_gen_info_runtime_threads_item_desc)
                )
            )
        ),
        GenerationOptionHelpSection(
            title = stringResource(R.string.onnx_image_gen_info_runtime_title),
            body = stringResource(R.string.onnx_image_gen_info_runtime_body),
            items = listOf(
                GenerationOptionHelpItem(
                    label = stringResource(R.string.onnx_image_gen_graph_opt_title),
                    description = stringResource(R.string.onnx_image_gen_info_graph_opt_item_desc)
                ),
                GenerationOptionHelpItem(
                    label = stringResource(R.string.onnx_image_gen_intra_threads_label),
                    description = stringResource(R.string.onnx_image_gen_info_intra_threads_item_desc)
                ),
                GenerationOptionHelpItem(
                    label = stringResource(R.string.onnx_image_gen_inter_threads_label),
                    description = stringResource(R.string.onnx_image_gen_info_inter_threads_item_desc)
                ),
                GenerationOptionHelpItem(
                    label = stringResource(R.string.onnx_image_gen_execution_mode_title),
                    description = stringResource(R.string.onnx_image_gen_info_execution_mode_item_desc)
                )
            )
        ),
        GenerationOptionHelpSection(
            title = stringResource(R.string.onnx_image_gen_info_expert_title),
            body = stringResource(R.string.onnx_image_gen_info_expert_body),
            items = listOf(
                GenerationOptionHelpItem(
                    label = stringResource(R.string.onnx_image_gen_component_backend_unet),
                    description = stringResource(R.string.onnx_image_gen_info_unet_override_item_desc)
                ),
                GenerationOptionHelpItem(
                    label = stringResource(R.string.onnx_image_gen_component_backend_vae_decoder),
                    description = stringResource(R.string.onnx_image_gen_info_vae_decoder_override_item_desc)
                ),
                GenerationOptionHelpItem(
                    label = stringResource(R.string.onnx_image_gen_component_backend_vae_encoder),
                    description = stringResource(R.string.onnx_image_gen_info_vae_encoder_override_item_desc)
                ),
                GenerationOptionHelpItem(
                    label = stringResource(R.string.onnx_image_gen_memory_pattern_label),
                    description = stringResource(R.string.onnx_image_gen_info_memory_pattern_item_desc)
                ),
                GenerationOptionHelpItem(
                    label = stringResource(R.string.onnx_image_gen_cpu_arena_label),
                    description = stringResource(R.string.onnx_image_gen_info_cpu_arena_item_desc)
                )
            )
        ),
        GenerationOptionHelpSection(
            title = stringResource(R.string.onnx_image_gen_info_nnapi_title),
            body = stringResource(R.string.onnx_image_gen_info_nnapi_body),
            items = listOf(
                GenerationOptionHelpItem(
                    label = stringResource(R.string.onnx_image_gen_nnapi_cpu_disabled_label),
                    description = stringResource(R.string.onnx_image_gen_info_nnapi_cpu_disabled_item_desc)
                ),
                GenerationOptionHelpItem(
                    label = stringResource(R.string.onnx_image_gen_nnapi_fp16_label),
                    description = stringResource(R.string.onnx_image_gen_info_nnapi_fp16_item_desc)
                )
            )
        ),
        GenerationOptionHelpSection(
            title = stringResource(R.string.onnx_image_gen_info_ram_title),
            body = stringResource(R.string.onnx_image_gen_info_ram_body),
            items = listOf(
                GenerationOptionHelpItem(
                    label = stringResource(R.string.onnx_image_gen_info_recommended_defaults_label),
                    description = stringResource(R.string.onnx_image_gen_info_recommended_defaults_desc)
                ),
                GenerationOptionHelpItem(
                    label = stringResource(R.string.onnx_image_gen_info_stay_close_label),
                    description = stringResource(R.string.onnx_image_gen_info_stay_close_desc)
                )
            )
        ),
        GenerationOptionHelpSection(
            title = stringResource(R.string.onnx_image_gen_info_storage_title),
            body = stringResource(R.string.onnx_image_gen_info_storage_body),
            items = listOf(
                GenerationOptionHelpItem(
                    label = stringResource(R.string.onnx_image_gen_info_storage_outputs_label),
                    description = stringResource(R.string.onnx_image_gen_info_storage_outputs_desc)
                )
            )
        )
    )
}

private fun formatOnnxGalleryTimestamp(epochMs: Long): String {
    return SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(epochMs))
}

private fun formatOnnxDuration(durationMs: Long): String {
    return FormatUtils.Display.formatDuration(durationMs / 1000.0)
}

private fun metadataLabelForMode(modeValue: String?, context: android.content.Context): String {
    return when (OnnxImageGenMode.fromMetadataValue(modeValue) ?: OnnxImageGenMode.TXT2IMG) {
        OnnxImageGenMode.TXT2IMG -> context.getString(R.string.imagegen_mode_txt2img)
        OnnxImageGenMode.IMG2IMG -> context.getString(R.string.imagegen_mode_img2img)
    }
}

private fun onnxProviderLabel(
    provider: com.example.llamadroid.onnx.OnnxCatalogProvider,
    context: android.content.Context
): String {
    return when (provider) {
        com.example.llamadroid.onnx.OnnxCatalogProvider.SDAI ->
            context.getString(R.string.onnx_models_provider_sdai)
        com.example.llamadroid.onnx.OnnxCatalogProvider.MANUXD32 ->
            context.getString(R.string.onnx_models_provider_manuxd32)
        com.example.llamadroid.onnx.OnnxCatalogProvider.SUPERTONIC ->
            context.getString(R.string.onnx_models_provider_supertonic)
        com.example.llamadroid.onnx.OnnxCatalogProvider.BACKGROUND_REMOVAL ->
            context.getString(R.string.onnx_models_provider_bgr)
    }
}

private suspend fun copyOnnxInitImageToCache(
    context: android.content.Context,
    uri: android.net.Uri
): File = withContext(Dispatchers.IO) {
    val inputDir = File(context.cacheDir, "onnx_input_images").apply { mkdirs() }
    val extension = DocumentFile.fromSingleUri(context, uri)
        ?.name
        ?.substringAfterLast('.', "")
        ?.let { candidate -> candidate.takeIf(String::isNotBlank) }
        ?: "png"
    val target = File(inputDir, "init_${System.currentTimeMillis()}.$extension")
    context.contentResolver.openInputStream(uri)?.use { input ->
        FileOutputStream(target).use { output ->
            input.copyTo(output)
        }
    } ?: error("Unable to open the selected image")
    target
}

private fun deleteManagedOnnxInitImage(path: String?) {
    val file = path?.let(::File) ?: return
    if (file.exists() && file.parentFile?.name == "onnx_input_images") {
        file.delete()
    }
}
