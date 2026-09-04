package com.example.llamadroid.ui.ai

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.core.content.ContextCompat
import androidx.navigation.NavController
import com.example.llamadroid.R
import com.example.llamadroid.data.SettingsRepository
import com.example.llamadroid.data.binary.BinaryRepository
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.data.db.ModelType
import com.example.llamadroid.service.GenerationDiagnosticsStore
import com.example.llamadroid.service.SDGenerationState
import com.example.llamadroid.service.SDMode
import com.example.llamadroid.service.SDModeStateHolder
import com.example.llamadroid.service.SDUpscaleConfig
import com.example.llamadroid.service.StableDiffusionService
import com.example.llamadroid.service.sdLaunchIssueMessage
import com.example.llamadroid.service.validateSdLaunchInputs
import com.example.llamadroid.ui.components.IntSliderWithInput
import com.example.llamadroid.ui.components.AppScrollableTabRow
import com.example.llamadroid.ui.navigation.Screen
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max

private const val LEGACY_UPSCALE_UI_DIAGNOSTIC_SOURCE = "image_generation_ui"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LegacyUpscaleScreen(navController: NavController) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val db = remember { AppDatabase.getDatabase(context) }
    val binaryRepo = remember { BinaryRepository(context) }
    val settingsRepo = remember { SettingsRepository(context) }
    val batteryGateState = rememberBatteryOptimizationGateState()
    val keepScreenAwakeDuringGeneration by settingsRepo.keepScreenAwakeDuringGeneration.collectAsState()
    val sdMaxCpuRamEnabled by settingsRepo.sdMaxCpuRamEnabled.collectAsState()
    val sdMaxCpuRamGiB by settingsRepo.sdMaxCpuRamGiB.collectAsState()
    val sdUpscaleThreads by settingsRepo.sdUpscaleThreads.collectAsStateWithLifecycle()
    val upscalerModels by db.modelDao().getModelsByType(ModelType.SD_UPSCALER).collectAsState(initial = emptyList())

    var mainTab by remember { mutableIntStateOf(0) }
    var selectedUpscalerModelPath by remember { mutableStateOf<String?>(null) }
    val selectedUpscalerModel = upscalerModels.firstOrNull { it.path == selectedUpscalerModelPath }
    var selectedImageUri by remember { mutableStateOf<Uri?>(null) }
    var selectedImagePath by remember { mutableStateOf<String?>(null) }
    var imageResolution by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var upscaleFactor by remember { mutableIntStateOf(2) }
    var upscaleRepeats by remember { mutableIntStateOf(1) }
    var threadsText by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var fullscreenImage by remember { mutableStateOf<File?>(null) }

    val modeStateHolder = remember { SDModeStateHolder.upscale }
    val generationState by modeStateHolder.state.collectAsState()
    val progress by modeStateHolder.progress.collectAsState()
    val generationStatus by modeStateHolder.status.collectAsState()
    val generatedImages by modeStateHolder.generatedImages.collectAsState()
    val totalSteps by modeStateHolder.totalSteps.collectAsState()
    val currentStep by modeStateHolder.currentStep.collectAsState()
    val isGenerating = generationState is SDGenerationState.Generating
    GenerationKeepScreenAwakeEffect(enabled = keepScreenAwakeDuringGeneration && isGenerating)

    LaunchedEffect(Unit) {
        GenerationDiagnosticsStore.recordBreadcrumb(
            source = LEGACY_UPSCALE_UI_DIAGNOSTIC_SOURCE,
            mode = SDMode.UPSCALE.name,
            event = "mode_entered",
            details = "legacyScreen=true"
        )
        GenerationDiagnosticsStore.recordBreadcrumb(
            source = LEGACY_UPSCALE_UI_DIAGNOSTIC_SOURCE,
            mode = SDMode.UPSCALE.name,
            event = "pane_rendered",
            details = "pane=upscale_legacy"
        )
    }

    LaunchedEffect(upscalerModels) {
        selectedUpscalerModelPath = selectedUpscalerModelPath?.takeIf { selectedPath ->
            upscalerModels.any { it.path == selectedPath }
        } ?: upscalerModels.firstOrNull()?.path
    }

    LaunchedEffect(Unit) {
        val pendingFile = com.example.llamadroid.data.SharedFileHolder.consumeFor(
            com.example.llamadroid.data.SharedFileTarget.IMAGE_GENERATION
        ) ?: com.example.llamadroid.data.SharedFileHolder.consumeFor(
            com.example.llamadroid.data.SharedFileTarget.LEGACY_IMAGE_UPSCALER
        )
        if (pendingFile != null && pendingFile.mimeType.startsWith("image/")) {
            GenerationDiagnosticsStore.recordBreadcrumb(
                source = LEGACY_UPSCALE_UI_DIAGNOSTIC_SOURCE,
                mode = SDMode.UPSCALE.name,
                event = "shared_image_prepare_started",
                details = "targetScreen=${pendingFile.targetScreen}"
            )
            val prepared = prepareLegacyUpscaleImageInput(
                context = context,
                uri = pendingFile.uri,
                tempFileName = "legacy_upscale_shared_input.png"
            )
            prepared?.let {
                selectedImageUri = it.uri
                selectedImagePath = it.path
                imageResolution = it.resolution
            }
            GenerationDiagnosticsStore.recordBreadcrumb(
                source = LEGACY_UPSCALE_UI_DIAGNOSTIC_SOURCE,
                mode = SDMode.UPSCALE.name,
                event = "shared_image_prepare_finished",
                details = "prepared=${prepared != null}"
            )
        }
    }

    val imagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let { pickedUri ->
            val prepared = prepareLegacyUpscaleImageInputSync(
                context = context,
                uri = pickedUri,
                tempFileName = "legacy_upscale_input.png"
            )
            prepared?.let {
                selectedImageUri = it.uri
                selectedImagePath = it.path
                imageResolution = it.resolution
            }
        }
    }

    val outputDir = remember { File(context.filesDir, "sd_output").apply { mkdirs() } }
    val upscaledDir = remember(outputDir) { File(outputDir, "upscaled").apply { mkdirs() } }
    var diskImages by remember { mutableStateOf<List<File>>(emptyList()) }

    fun reloadDiskImages() {
        diskImages = upscaledDir.listFiles()
            ?.filter { it.extension.lowercase() in listOf("png", "jpg", "jpeg") }
            ?.sortedByDescending { it.lastModified() }
            .orEmpty()
    }

    LaunchedEffect(Unit) {
        reloadDiskImages()
    }

    LaunchedEffect(generationState) {
        when (val state = generationState) {
            is SDGenerationState.Error -> errorMessage = state.message
            is SDGenerationState.Complete -> {
                errorMessage = null
                reloadDiskImages()
            }
            else -> Unit
        }
    }

    val galleryImages = remember(generatedImages, diskImages) {
        (generatedImages + diskImages)
            .filter { it.parentFile?.name == "upscaled" }
            .distinctBy { it.absolutePath }
    }

    val generate = fun() {
        val modelPath = selectedUpscalerModelPath
        val inputImagePath = selectedImagePath
        val sdBinaryPath = binaryRepo.getSdBinary()?.absolutePath
        val launchIssue = validateSdLaunchInputs(
            mode = SDMode.UPSCALE,
            modelPath = modelPath,
            inputImagePath = inputImagePath,
            sdBinaryPath = sdBinaryPath
        )
        if (launchIssue != null) {
            errorMessage = sdLaunchIssueMessage(context, SDMode.UPSCALE, launchIssue)
            GenerationDiagnosticsStore.recordBreadcrumb(
                source = LEGACY_UPSCALE_UI_DIAGNOSTIC_SOURCE,
                mode = SDMode.UPSCALE.name,
                event = "ui_preflight_failed",
                details = "issue=${launchIssue.name}"
            )
            return
        }

        val threadCount = threadsText.toIntOrNull()?.takeIf { it > 0 }
            ?: sdUpscaleThreads
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val outputFile = File(upscaledDir, "sd_$timestamp.png")
        val config = SDUpscaleConfig(
            modelPath = modelPath ?: "",
            inputImagePath = inputImagePath ?: "",
            outputPath = outputFile.absolutePath,
            upscaleRepeats = upscaleRepeats,
            threads = threadCount,
            sdParamsBackendSpec = selectedUpscalerModel?.sdParamsBackendSpec ?: "auto",
            sdParamsBackendMode = selectedUpscalerModel?.sdParamsBackendMode ?: "auto",
            sdRuntimeBackendMode = selectedUpscalerModel?.sdRuntimeBackendMode ?: "auto",
            maxVramCpuGiB = if (sdMaxCpuRamEnabled) sdMaxCpuRamGiB else ""
        )

        batteryGateState.runAfterCheck {
            val launchDetails = buildString {
                append("legacy=true")
                append(" model=${File(config.modelPath).name}")
                append(" input=${File(config.inputImagePath).name}")
                append(" repeats=${config.upscaleRepeats}")
                append(" threads=${config.threads}")
            }
            GenerationDiagnosticsStore.recordBreadcrumb(
                source = LEGACY_UPSCALE_UI_DIAGNOSTIC_SOURCE,
                mode = SDMode.UPSCALE.name,
                event = "ui_launch_requested",
                details = launchDetails
            )
            runCatching {
                ContextCompat.startForegroundService(
                    context,
                    StableDiffusionService.createStartUpscaleIntent(context, config)
                )
                errorMessage = null
                GenerationDiagnosticsStore.recordBreadcrumb(
                    source = LEGACY_UPSCALE_UI_DIAGNOSTIC_SOURCE,
                    mode = SDMode.UPSCALE.name,
                    event = "ui_launch_dispatched",
                    details = launchDetails
                )
            }.onFailure { error ->
                errorMessage = error.message ?: resources.getString(R.string.error_generic)
                GenerationDiagnosticsStore.recordBreadcrumb(
                    source = LEGACY_UPSCALE_UI_DIAGNOSTIC_SOURCE,
                    mode = SDMode.UPSCALE.name,
                    event = "ui_launch_failed",
                    details = "$launchDetails error=${error.javaClass.simpleName}: ${error.message}"
                )
            }
        }
    }

    val cancelGeneration: () -> Unit = {
        context.startService(StableDiffusionService.createCancelModeIntent(context, SDMode.UPSCALE))
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
                Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back))
            }
            Text(
                "🎨 " + stringResource(R.string.imagegen_title),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        val mainTabs = listOf(
            "🎨 " + stringResource(R.string.imagegen_tab_generate),
            "📂 " + stringResource(R.string.imagegen_tab_gallery)
        )
        AppScrollableTabRow(
            selectedTabIndex = mainTab,
            modifier = Modifier.padding(horizontal = 16.dp),
            edgePadding = 12.dp,
            containerColor = androidx.compose.ui.graphics.Color.Transparent,
            contentColor = MaterialTheme.colorScheme.primary
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
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 16.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                val modes = listOf(
                    IMAGE_GEN_MODE_TXT2IMG to stringResource(R.string.imagegen_task_create),
                    IMAGE_GEN_MODE_IMG2IMG to stringResource(R.string.imagegen_task_transform),
                    IMAGE_GEN_MODE_INPAINT to stringResource(R.string.imagegen_task_repair),
                    IMAGE_GEN_MODE_ADETAILER to stringResource(R.string.imagegen_task_enhance),
                    IMAGE_GEN_MODE_UPSCALE to stringResource(R.string.imagegen_task_enlarge)
                )
                val modeRowState = rememberLazyListState(
                    initialFirstVisibleItemIndex = modes.lastIndex
                )
                LazyRow(
                    state = modeRowState,
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(end = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(modes, key = { it.first }) { (mode, label) ->
                        FilterChip(
                            selected = mode == IMAGE_GEN_MODE_UPSCALE,
                            onClick = {
                                if (mode != IMAGE_GEN_MODE_UPSCALE) {
                                    navController.navigate(Screen.ImageGen.createRoute(mode))
                                }
                            },
                            label = { Text(label, maxLines = 1) }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            stringResource(R.string.imagegen_mode_upscale),
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                        )
                        Spacer(modifier = Modifier.height(12.dp))

                        if (selectedImagePath != null && imageResolution != null) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                val bitmap by rememberLegacyUpscalePreviewBitmap(selectedImagePath)
                                bitmap?.let {
                                    androidx.compose.foundation.Image(
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
                                        "${imageResolution?.first ?: 0} × ${imageResolution?.second ?: 0}",
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

                        Spacer(modifier = Modifier.height(12.dp))

                        if (selectedUpscalerModelPath != null) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    stringResource(R.string.imagegen_upscale_factor_label),
                                    style = MaterialTheme.typography.bodyMedium
                                )
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

                            Spacer(modifier = Modifier.height(12.dp))

                            OutlinedTextField(
                                value = threadsText,
                                onValueChange = { threadsText = it.filter(Char::isDigit) },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text(stringResource(R.string.imagegen_threads_label)) },
                                placeholder = { Text(sdUpscaleThreads.toString()) },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                shape = RoundedCornerShape(12.dp)
                            )

                            val finalFactor = Math.pow(upscaleFactor.toDouble(), upscaleRepeats.toDouble()).toInt()
                            val baseSize = 512
                            val (outputW, outputH, fittedW, fittedH) = if (imageResolution != null) {
                                val (origW, origH) = imageResolution!!
                                val scale = baseSize.toFloat() / max(origW, origH)
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
                                        Text(stringResource(R.string.imagegen_final_factor))
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
                                        Text(stringResource(R.string.imagegen_output_res))
                                        Text(
                                            "${outputW} × ${outputH}",
                                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold)
                                        )
                                    }
                                    imageResolution?.let { (origW, origH) ->
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

                Spacer(modifier = Modifier.height(12.dp))

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            stringResource(R.string.imagegen_component_upscaler),
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        if (upscalerModels.isEmpty()) {
                            Text(
                                stringResource(R.string.imagegen_no_upscalers_installed),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedButton(onClick = { navController.navigate(Screen.SDModels.route) }) {
                                Icon(Icons.Default.Add, null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(stringResource(R.string.imagegen_get_upscaler_models))
                            }
                        } else {
                            LegacyUpscaleModelPicker(
                                selectedPath = selectedUpscalerModelPath,
                                models = upscalerModels,
                                onModelSelected = { modelPath, filename ->
                                    selectedUpscalerModelPath = modelPath
                                    Regex("(\\d+)[xX]|[xX](\\d+)").find(filename)?.let { match ->
                                        val detected = (match.groupValues[1].takeIf { it.isNotBlank() }
                                            ?: match.groupValues[2]).toIntOrNull()
                                        if (detected != null && detected in listOf(2, 4, 8)) {
                                            upscaleFactor = detected
                                        }
                                    }
                                }
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                if (isGenerating) {
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
                                Text(generationStatus, color = MaterialTheme.colorScheme.onPrimaryContainer)
                                Spacer(modifier = Modifier.height(8.dp))
                            }
                            val progressPercent = (progress * 100).toInt()
                            Text(
                                stringResource(R.string.imagegen_step_progress, currentStep, totalSteps, progressPercent)
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            LinearProgressIndicator(
                                progress = { progress },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(8.dp)
                                    .clip(RoundedCornerShape(4.dp))
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
                    Button(
                        onClick = generate,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp),
                        enabled = selectedUpscalerModelPath != null && selectedImagePath != null,
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Icon(Icons.Default.Create, null)
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            stringResource(R.string.imagegen_upscale_btn),
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                errorMessage?.let { error ->
                    Spacer(modifier = Modifier.height(8.dp))
                    Card(
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
                    }
                }

                if (generationState is SDGenerationState.Complete) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Card(
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
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
            }
        } else {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 16.dp)
            ) {
                Spacer(modifier = Modifier.height(12.dp))
                if (galleryImages.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("📷", style = MaterialTheme.typography.displayLarge)
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                stringResource(R.string.imagegen_gallery_empty_filter, stringResource(R.string.imagegen_mode_upscale)),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(2),
                        modifier = Modifier.fillMaxSize(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(items = galleryImages, key = { it.absolutePath }) { imageFile ->
                            val bitmap by rememberLegacyUpscalePreviewBitmap(imageFile.absolutePath)
                            Box(
                                modifier = Modifier
                                    .aspectRatio(1f)
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable { fullscreenImage = imageFile }
                            ) {
                                if (bitmap != null) {
                                    androidx.compose.foundation.Image(
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
                                Surface(
                                    modifier = Modifier
                                        .align(Alignment.TopStart)
                                        .padding(4.dp),
                                    shape = RoundedCornerShape(6.dp),
                                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.8f)
                                ) {
                                    Text(
                                        "⬆️",
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

    if (fullscreenImage != null) {
        val bitmap by rememberLegacyUpscalePreviewBitmap(fullscreenImage?.absolutePath, maxDimension = 1600)
        AlertDialog(
            onDismissRequest = { fullscreenImage = null },
            confirmButton = {
                Button(
                    onClick = {
                        fullscreenImage?.let { file ->
                            runCatching {
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
                                context.startActivity(
                                    Intent.createChooser(
                                        shareIntent,
                                        resources.getString(R.string.imagegen_share_chooser)
                                    )
                                )
                            }
                        }
                    }
                ) {
                    Icon(Icons.Default.Share, null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.action_share))
                }
            },
            dismissButton = {
                Row(modifier = Modifier.wrapContentWidth()) {
                    TextButton(
                        onClick = {
                            fullscreenImage?.let { file ->
                                if (file.delete()) {
                                    diskImages = diskImages.filter { it.absolutePath != file.absolutePath }
                                    modeStateHolder.removeImage(file)
                                    android.widget.Toast.makeText(
                                        context,
                                        resources.getString(R.string.imagegen_delete_confirm),
                                        android.widget.Toast.LENGTH_SHORT
                                    ).show()
                                } else {
                                    android.widget.Toast.makeText(
                                        context,
                                        resources.getString(R.string.imagegen_delete_fail),
                                        android.widget.Toast.LENGTH_SHORT
                                    ).show()
                                }
                            }
                            fullscreenImage = null
                        },
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    ) {
                        Icon(Icons.Default.Delete, null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(stringResource(R.string.action_delete))
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    TextButton(onClick = { fullscreenImage = null }) {
                        Text(stringResource(R.string.action_close))
                    }
                }
            },
            title = {
                Column {
                    Text(stringResource(R.string.imagegen_generated_title))
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
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                ) {
                    bitmap?.let {
                        androidx.compose.foundation.Image(
                            bitmap = it,
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Fit
                        )
                    }
                }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LegacyUpscaleModelPicker(
    selectedPath: String?,
    models: List<com.example.llamadroid.data.db.ModelEntity>,
    onModelSelected: (String, String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded }
    ) {
        OutlinedTextField(
            value = models.firstOrNull { it.path == selectedPath }?.filename
                ?: stringResource(R.string.imagegen_select_model),
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
            models.forEach { model ->
                DropdownMenuItem(
                    text = { Text(model.filename) },
                    onClick = {
                        expanded = false
                        onModelSelected(model.path, model.filename)
                    }
                )
            }
        }
    }
}

private data class LegacyUpscalePreparedImage(
    val uri: Uri,
    val path: String,
    val resolution: Pair<Int, Int>?
)

private suspend fun prepareLegacyUpscaleImageInput(
    context: Context,
    uri: Uri,
    tempFileName: String
): LegacyUpscalePreparedImage? {
    val tempFile = File(context.cacheDir, tempFileName)
    return runCatching {
        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(tempFile).use { output -> input.copyTo(output) }
        }
        LegacyUpscalePreparedImage(
            uri = uri,
            path = tempFile.absolutePath,
            resolution = readLegacyUpscaleImageResolution(context, uri)
        )
    }.getOrNull()
}

private fun prepareLegacyUpscaleImageInputSync(
    context: Context,
    uri: Uri,
    tempFileName: String
): LegacyUpscalePreparedImage? {
    val tempFile = File(context.cacheDir, tempFileName)
    return runCatching {
        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(tempFile).use { output -> input.copyTo(output) }
        }
        LegacyUpscalePreparedImage(
            uri = uri,
            path = tempFile.absolutePath,
            resolution = readLegacyUpscaleImageResolution(context, uri)
        )
    }.getOrNull()
}

private fun readLegacyUpscaleImageResolution(context: Context, uri: Uri): Pair<Int, Int>? {
    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(uri)?.use { input ->
        BitmapFactory.decodeStream(input, null, options)
    }
    return if (options.outWidth > 0 && options.outHeight > 0) {
        options.outWidth to options.outHeight
    } else {
        null
    }
}

@Composable
private fun rememberLegacyUpscalePreviewBitmap(
    path: String?,
    maxDimension: Int = 256
) = produceState<ImageBitmap?>(initialValue = null, key1 = path, key2 = maxDimension) {
    value = loadPreviewImageBitmap(path, maxDimension)
}
