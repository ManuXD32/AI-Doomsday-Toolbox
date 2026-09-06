package com.example.llamadroid.ui.ai

import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.core.content.FileProvider
import androidx.navigation.NavController
import coil.compose.AsyncImage
import com.example.llamadroid.R
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.data.db.ModelType
import com.example.llamadroid.onnx.OnnxBackgroundRemovalConfig
import com.example.llamadroid.onnx.OnnxBackgroundRemovalRiskClass
import com.example.llamadroid.onnx.OnnxBackgroundRemovalStorage
import com.example.llamadroid.onnx.OnnxExecutionMode
import com.example.llamadroid.onnx.OnnxGraphOptimizationLevel
import com.example.llamadroid.onnx.OnnxRuntimeBackend
import com.example.llamadroid.onnx.OnnxRuntimeOptions
import com.example.llamadroid.onnx.backgroundRemovalRiskClass
import com.example.llamadroid.onnx.isOnnxBackgroundRemovalModel
import com.example.llamadroid.service.GenerationDiagnosticsStore
import com.example.llamadroid.service.OnnxBackgroundRemovalService
import com.example.llamadroid.service.OnnxBackgroundRemovalState
import com.example.llamadroid.service.OnnxBackgroundRemovalStateStore
import com.example.llamadroid.ui.components.AppPageBackground
import com.example.llamadroid.ui.components.AppScrollableTabRow
import com.example.llamadroid.ui.components.AppAdvancedSection
import com.example.llamadroid.ui.components.AppStateKind
import com.example.llamadroid.ui.components.AppStatePanel
import com.example.llamadroid.ui.components.AppTaskActionFooter
import com.example.llamadroid.ui.navigation.Screen
import com.example.llamadroid.util.FormatUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private data class BgrInputImage(val path: String, val name: String)

private const val BGR_WORKER_PROCESS_SUFFIX = ":onnx_bgr"
private const val BGR_WORKER_EXIT_STALE_MS = 8_000L
private const val BGR_WORKER_STALE_MS = 30_000L
private val BGR_RESIZE_PRESETS = listOf(256, 384, 512, 768, 1024, 1536, 2048)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnnxBackgroundRemovalScreen(navController: NavController) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val db = remember { AppDatabase.getDatabase(context) }
    val installedModels by db.modelDao().getModelsByType(ModelType.ONNX_BACKGROUND_REMOVAL).collectAsState(initial = emptyList())
    val models = remember(installedModels) { installedModels.filter { it.isOnnxBackgroundRemovalModel() } }
    val state by OnnxBackgroundRemovalStateStore.state.collectAsState()
    val generatedOutputs by OnnxBackgroundRemovalStateStore.outputs.collectAsState()

    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    var selectedModelId by rememberSaveable { mutableStateOf("") }
    var inputs by remember { mutableStateOf<List<BgrInputImage>>(emptyList()) }
    var backend by rememberSaveable { mutableStateOf(OnnxRuntimeBackend.CPU) }
    var graphOptimization by rememberSaveable { mutableStateOf(OnnxGraphOptimizationLevel.ALL) }
    var threadsText by rememberSaveable { mutableStateOf("") }
    var alphaThreshold by rememberSaveable { mutableFloatStateOf(0.5f) }
    var featherRadius by rememberSaveable { mutableFloatStateOf(1f) }
    var maskSoftness by rememberSaveable { mutableFloatStateOf(1f) }
    var maskContrast by rememberSaveable { mutableFloatStateOf(1f) }
    var exportMask by rememberSaveable { mutableStateOf(false) }
    var resizeBeforeProcessing by rememberSaveable { mutableStateOf(true) }
    var resizeMaxEdge by rememberSaveable { mutableIntStateOf(512) }
    var preserveNames by rememberSaveable { mutableStateOf(true) }
    var graphMenuExpanded by remember { mutableStateOf(false) }
    var modelMenuExpanded by remember { mutableStateOf(false) }
    var resizeMenuExpanded by remember { mutableStateOf(false) }
    var galleryFiles by remember { mutableStateOf<List<File>>(emptyList()) }
    var fullscreenImage by remember { mutableStateOf<File?>(null) }
    var pendingDelete by remember { mutableStateOf<File?>(null) }

    val selectedModel = models.firstOrNull { it.filename == selectedModelId } ?: models.firstOrNull()
    val isRunning = state is OnnxBackgroundRemovalState.Running
    val galleryImages = remember(generatedOutputs, galleryFiles) {
        (generatedOutputs + galleryFiles).distinctBy { it.absolutePath }.sortedByDescending { it.lastModified() }
    }

    fun refreshGallery() {
        val files = OnnxBackgroundRemovalStorage.listOutputs(context)
        galleryFiles = files
        OnnxBackgroundRemovalStateStore.setOutputs(files)
    }

    LaunchedEffect(Unit) {
        refreshGallery()
        var lastCompleteAt = 0L
        while (true) {
            val runtimeState = withContext(Dispatchers.IO) {
                OnnxBackgroundRemovalStorage.readRuntimeState(context)
            }
            if (runtimeState != null) {
                val now = System.currentTimeMillis()
                when (runtimeState.state) {
                    "RUNNING" -> {
                        val staleMs = now - runtimeState.updatedAtEpochMs
                        val exitSummary = if (staleMs >= BGR_WORKER_EXIT_STALE_MS && runtimeState.startedAtEpochMs > 0L) {
                            GenerationDiagnosticsStore.describeRecentProcessExit(
                                processNameSuffix = BGR_WORKER_PROCESS_SUFFIX,
                                sinceTimestamp = runtimeState.startedAtEpochMs
                            )
                        } else {
                            null
                        }
                        when {
                            exitSummary != null -> {
                                val message = resources.getString(R.string.bgr_worker_stopped, exitSummary)
                                OnnxBackgroundRemovalStateStore.updateState(OnnxBackgroundRemovalState.Error(message))
                                withContext(Dispatchers.IO) {
                                    OnnxBackgroundRemovalStorage.writeRuntimeState(
                                        context,
                                        runtimeState.copy(
                                            state = "ERROR",
                                            status = message,
                                            message = message
                                        )
                                    )
                                }
                            }
                            staleMs >= BGR_WORKER_STALE_MS -> {
                                val message = resources.getString(R.string.bgr_worker_stale)
                                OnnxBackgroundRemovalStateStore.updateState(OnnxBackgroundRemovalState.Error(message))
                                withContext(Dispatchers.IO) {
                                    OnnxBackgroundRemovalStorage.writeRuntimeState(
                                        context,
                                        runtimeState.copy(
                                            state = "ERROR",
                                            status = message,
                                            message = message
                                        )
                                    )
                                }
                            }
                            else -> {
                                OnnxBackgroundRemovalStateStore.updateState(
                                    OnnxBackgroundRemovalState.Running(
                                        progress = runtimeState.progress,
                                        status = runtimeState.status,
                                        completed = runtimeState.completed,
                                        total = runtimeState.total
                                    )
                                )
                            }
                        }
                    }
                    "COMPLETE" -> if (runtimeState.updatedAtEpochMs != lastCompleteAt) {
                        lastCompleteAt = runtimeState.updatedAtEpochMs
                        OnnxBackgroundRemovalStateStore.updateState(
                            OnnxBackgroundRemovalState.Complete(
                                outputPaths = runtimeState.outputPaths,
                                failed = runtimeState.failed,
                                durationMs = runtimeState.durationMs
                            )
                        )
                        refreshGallery()
                    }
                    "ERROR" -> {
                        OnnxBackgroundRemovalStateStore.updateState(
                            OnnxBackgroundRemovalState.Error(runtimeState.message ?: runtimeState.status)
                        )
                    }
                }
            } else if (OnnxBackgroundRemovalStateStore.state.value is OnnxBackgroundRemovalState.Running) {
                OnnxBackgroundRemovalStateStore.reset()
            }
            delay(750)
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            val imported = withContext(Dispatchers.IO) {
                uris.mapNotNull { uri -> copyBgrInputToCache(context, uri).getOrNull() }
            }
            if (imported.isNotEmpty()) inputs = (inputs + imported).distinctBy { it.path }
        }
    }

    fun shareImage(imageFile: File) {
        runCatching {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", imageFile)
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(shareIntent, resources.getString(R.string.imagegen_share_chooser)))
        }.onFailure {
            Toast.makeText(context, resources.getString(R.string.bgr_share_failed, it.message ?: ""), Toast.LENGTH_LONG).show()
        }
    }

    fun startRemoval() {
        val model = selectedModel ?: return
        val validationError = OnnxBackgroundRemovalService.validateManagedModelPath(
            context,
            File(model.path),
            model.filename
        )
        if (validationError != null) {
            OnnxBackgroundRemovalStateStore.updateState(OnnxBackgroundRemovalState.Error(validationError))
            Toast.makeText(context, validationError, Toast.LENGTH_LONG).show()
            return
        }
        OnnxBackgroundRemovalService.start(
            context,
            OnnxBackgroundRemovalConfig(
                modelPath = model.path,
                modelName = model.filename,
                inputPaths = inputs.map { it.path },
                inputNames = inputs.map { it.name },
                backend = backend,
                runtimeOptions = OnnxRuntimeOptions(
                    runtimeThreadCount = threadsText.toIntOrNull(),
                    graphOptimizationLevel = graphOptimization,
                    executionMode = OnnxExecutionMode.SEQUENTIAL
                ),
                alphaThreshold = alphaThreshold,
                featherRadius = featherRadius.toInt(),
                maskSoftness = maskSoftness,
                maskContrast = maskContrast,
                exportMask = exportMask,
                resizeBeforeProcessing = resizeBeforeProcessing,
                resizeMaxEdge = resizeMaxEdge,
                preserveSourceNames = preserveNames
            )
        )
    }

    AppPageBackground {
        Scaffold(
            containerColor = androidx.compose.ui.graphics.Color.Transparent,
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(R.string.bgr_title)) },
                    navigationIcon = {
                        IconButton(onClick = { navController.popBackStack() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                        }
                    }
                )
            }
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                    AppScrollableTabRow(selectedTabIndex = selectedTab) {
                    Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }, text = { Text(stringResource(R.string.bgr_tab_remove)) })
                    Tab(selected = selectedTab == 1, onClick = { selectedTab = 1; refreshGallery() }, text = { Text(stringResource(R.string.bgr_tab_gallery)) })
                }
                if (selectedTab == 0) {
                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(20.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        item {
                            Text(
                                stringResource(R.string.bgr_subtitle),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        item {
                            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Text(stringResource(R.string.bgr_model_section), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                    if (models.isEmpty()) {
                                        Text(stringResource(R.string.bgr_no_models), color = MaterialTheme.colorScheme.error)
                                        Button(onClick = { navController.navigate(Screen.OnnxModels.route) }) {
                                            Icon(Icons.Default.Download, contentDescription = null)
                                            Spacer(Modifier.width(8.dp))
                                            Text(stringResource(R.string.onnx_image_gen_open_models))
                                        }
                                    } else {
                                        ExposedDropdownMenuBox(expanded = modelMenuExpanded, onExpandedChange = { modelMenuExpanded = it }) {
                                            OutlinedTextField(
                                                value = selectedModel?.filename.orEmpty(),
                                                onValueChange = {},
                                                readOnly = true,
                                                label = { Text(stringResource(R.string.bgr_model_label)) },
                                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = modelMenuExpanded) },
                                                modifier = Modifier.menuAnchor().fillMaxWidth()
                                            )
                                            ExposedDropdownMenu(expanded = modelMenuExpanded, onDismissRequest = { modelMenuExpanded = false }) {
                                                models.forEach { model ->
                                                    DropdownMenuItem(
                                                        text = { Text(model.filename, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                                        onClick = {
                                                            selectedModelId = model.filename
                                                            modelMenuExpanded = false
                                                        }
                                                    )
                                                }
                                            }
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        stringResource(R.string.bgr_catalog_play_safe_hint),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        stringResource(R.string.bgr_catalog_recommended_hint),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    val selectedRisk = selectedModel?.backgroundRemovalRiskClass()
                                    when (selectedRisk) {
                                        OnnxBackgroundRemovalRiskClass.HEAVY -> Text(
                                            stringResource(R.string.bgr_catalog_heavy_warning),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.tertiary
                                        )
                                        OnnxBackgroundRemovalRiskClass.UNSUPPORTED_LEGACY -> Text(
                                            stringResource(R.string.bgr_catalog_legacy_unsupported_hint),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.error
                                        )
                                        else -> Unit
                                    }
                                }
                            }
                        }
                        item {
                            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(stringResource(R.string.bgr_inputs_section), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                                        Button(onClick = { picker.launch(arrayOf("image/*")) }, enabled = !isRunning) {
                                            Icon(Icons.Default.FolderOpen, contentDescription = null)
                                            Spacer(Modifier.width(8.dp))
                                            Text(stringResource(R.string.bgr_pick_images))
                                        }
                                    }
                                    if (inputs.isEmpty()) {
                                        Text(stringResource(R.string.bgr_inputs_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    } else {
                                        inputs.forEach { image ->
                                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                                Text(image.name, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                IconButton(onClick = { inputs = inputs.filterNot { it.path == image.path } }, enabled = !isRunning) {
                                                    Icon(Icons.Default.Close, contentDescription = stringResource(R.string.action_delete))
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        item {
                            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Text(stringResource(R.string.bgr_runtime_section), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                                        OnnxRuntimeBackend.entries.forEachIndexed { index, option ->
                                            SegmentedButton(
                                                selected = backend == option,
                                                onClick = { backend = option },
                                                shape = androidx.compose.material3.SegmentedButtonDefaults.itemShape(index, OnnxRuntimeBackend.entries.size)
                                            ) { Text(option.name) }
                                        }
                                    }
                                    OutlinedTextField(
                                        value = threadsText,
                                        onValueChange = { threadsText = it.filter(Char::isDigit) },
                                        label = { Text(stringResource(R.string.bgr_threads_label)) },
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                    ExposedDropdownMenuBox(expanded = graphMenuExpanded, onExpandedChange = { graphMenuExpanded = it }) {
                                        OutlinedTextField(
                                            value = graphOptimization.name,
                                            onValueChange = {},
                                            readOnly = true,
                                            label = { Text(stringResource(R.string.bgr_graph_opt_label)) },
                                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = graphMenuExpanded) },
                                            modifier = Modifier.menuAnchor().fillMaxWidth()
                                        )
                                        ExposedDropdownMenu(expanded = graphMenuExpanded, onDismissRequest = { graphMenuExpanded = false }) {
                                            OnnxGraphOptimizationLevel.entries.forEach { level ->
                                                DropdownMenuItem(text = { Text(level.name) }, onClick = {
                                                    graphOptimization = level
                                                    graphMenuExpanded = false
                                                })
                                            }
                                        }
                                    }
                                    BgrSwitchRow(stringResource(R.string.bgr_resize_before_processing), resizeBeforeProcessing) {
                                        resizeBeforeProcessing = it
                                    }
                                    Text(
                                        stringResource(R.string.bgr_resize_before_processing_desc),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    ExposedDropdownMenuBox(
                                        expanded = resizeMenuExpanded && resizeBeforeProcessing,
                                        onExpandedChange = { if (resizeBeforeProcessing) resizeMenuExpanded = it }
                                    ) {
                                        OutlinedTextField(
                                            value = stringResource(R.string.bgr_resize_resolution_value, resizeMaxEdge),
                                            onValueChange = {},
                                            readOnly = true,
                                            enabled = resizeBeforeProcessing,
                                            label = { Text(stringResource(R.string.bgr_resize_resolution_label)) },
                                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = resizeMenuExpanded) },
                                            modifier = Modifier.menuAnchor().fillMaxWidth()
                                        )
                                        ExposedDropdownMenu(
                                            expanded = resizeMenuExpanded && resizeBeforeProcessing,
                                            onDismissRequest = { resizeMenuExpanded = false }
                                        ) {
                                            BGR_RESIZE_PRESETS.forEach { preset ->
                                                DropdownMenuItem(
                                                    text = { Text(stringResource(R.string.bgr_resize_resolution_value, preset)) },
                                                    onClick = {
                                                        resizeMaxEdge = preset
                                                        resizeMenuExpanded = false
                                                    }
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        item {
                            AppAdvancedSection(title = stringResource(R.string.soft_studio_advanced)) {
                            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Text(stringResource(R.string.bgr_mask_section), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                    BgrSlider(stringResource(R.string.bgr_alpha_threshold, alphaThreshold), alphaThreshold, 0.05f..0.95f) { alphaThreshold = it }
                                    BgrSlider(stringResource(R.string.bgr_feather_radius, featherRadius.toInt()), featherRadius, 0f..8f, steps = 7) { featherRadius = it }
                                    BgrSlider(stringResource(R.string.bgr_mask_softness, maskSoftness), maskSoftness, 0f..1f) { maskSoftness = it }
                                    BgrSlider(stringResource(R.string.bgr_mask_contrast, maskContrast), maskContrast, 0.25f..4f) { maskContrast = it }
                                    BgrSwitchRow(stringResource(R.string.bgr_export_mask), exportMask) { exportMask = it }
                                    BgrSwitchRow(stringResource(R.string.bgr_preserve_names), preserveNames) { preserveNames = it }
                                }
                            }
                            }
                        }
                        item {
                            when (val runningState = state) {
                                is OnnxBackgroundRemovalState.Running -> {
                                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                            Text(
                                                stringResource(R.string.bgr_progress_title),
                                                style = MaterialTheme.typography.titleSmall,
                                                fontWeight = FontWeight.SemiBold
                                            )
                                            LinearProgressIndicator(progress = { runningState.progress }, modifier = Modifier.fillMaxWidth())
                                            Text(runningState.status, style = MaterialTheme.typography.bodySmall)
                                            Text(
                                                stringResource(
                                                    R.string.bgr_progress_summary,
                                                    runningState.completed,
                                                    runningState.total,
                                                    (runningState.progress * 100f).toInt()
                                                ),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                                is OnnxBackgroundRemovalState.Error -> Text(runningState.message, color = MaterialTheme.colorScheme.error)
                                is OnnxBackgroundRemovalState.Complete -> Text(stringResource(R.string.bgr_complete, runningState.outputPaths.size, runningState.failed))
                                OnnxBackgroundRemovalState.Idle -> Unit
                            }
                            Spacer(Modifier.height(8.dp))
                        }
                    }
                    AppTaskActionFooter(
                        modifier = Modifier
                            .fillMaxWidth()
                    ) {
                        if (isRunning) {
                            (state as? OnnxBackgroundRemovalState.Running)?.let { runningState ->
                                Text(
                                    text = runningState.status,
                                    modifier = Modifier.fillMaxWidth(),
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                LinearProgressIndicator(
                                    progress = { runningState.progress.coerceIn(0f, 1f) },
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                            OutlinedButton(
                                onClick = { OnnxBackgroundRemovalService.cancel(context) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 48.dp),
                                colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(
                                    contentColor = MaterialTheme.colorScheme.error
                                )
                            ) {
                                Icon(Icons.Default.Close, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.soft_studio_cancel))
                            }
                        } else {
                            Button(
                                onClick = ::startRemoval,
                                enabled = selectedModel != null && inputs.isNotEmpty(),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 52.dp)
                            ) {
                                Text(stringResource(R.string.soft_studio_start))
                            }
                        }
                    }
                } else {
                    if (galleryImages.isEmpty()) {
                        AppStatePanel(
                            kind = AppStateKind.Empty,
                            title = stringResource(R.string.soft_studio_empty_title),
                            message = stringResource(R.string.bgr_gallery_empty),
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        LazyVerticalGrid(
                            columns = GridCells.Adaptive(140.dp),
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(16.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            items(galleryImages, key = { it.absolutePath }) { image ->
                                Card(modifier = Modifier.clickable { fullscreenImage = image }) {
                                    Column {
                                        AsyncImage(
                                            model = image,
                                            contentDescription = image.name,
                                            modifier = Modifier.fillMaxWidth().aspectRatio(1f),
                                            contentScale = ContentScale.Crop
                                        )
                                        Text(
                                            image.name,
                                            modifier = Modifier.padding(8.dp),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            style = MaterialTheme.typography.bodySmall
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

    fullscreenImage?.let { imageFile ->
        Dialog(onDismissRequest = { fullscreenImage = null }) {
            Card {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    AsyncImage(
                        model = imageFile,
                        contentDescription = imageFile.name,
                        modifier = Modifier.fillMaxWidth().aspectRatio(1f),
                        contentScale = ContentScale.Fit
                    )
                    Text(imageFile.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    OnnxBackgroundRemovalStorage.readMetadata(imageFile)?.let { metadata ->
                        Text(
                            stringResource(
                                R.string.bgr_metadata_details,
                                metadata.modelName,
                                metadata.width,
                                metadata.height,
                                FormatUtils.Display.formatDuration(metadata.durationMs / 1000.0)
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(onClick = { shareImage(imageFile) }, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Default.Share, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.action_share))
                        }
                        OutlinedButton(onClick = { pendingDelete = imageFile }, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Default.Delete, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.action_delete))
                        }
                    }
                }
            }
        }
    }

    pendingDelete?.let { imageFile ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.bgr_delete_title)) },
            text = { Text(stringResource(R.string.bgr_delete_desc, imageFile.name)) },
            confirmButton = {
                TextButton(onClick = {
                    OnnxBackgroundRemovalStorage.deleteImageWithMetadata(imageFile)
                    OnnxBackgroundRemovalStateStore.removeOutput(imageFile)
                    refreshGallery()
                    fullscreenImage = null
                    pendingDelete = null
                }) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text(stringResource(R.string.action_cancel)) }
            }
        )
    }
}

@Composable
private fun BgrSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, steps: Int = 0, onValueChange: (Float) -> Unit) {
    Column {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Slider(value = value, onValueChange = onValueChange, valueRange = range, steps = steps)
    }
}

@Composable
private fun BgrSwitchRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

private fun copyBgrInputToCache(context: android.content.Context, uri: Uri): Result<BgrInputImage> = runCatching {
    val name = queryBgrDisplayName(context, uri)
    val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
    val extension = name.substringAfterLast('.', "png").ifBlank { "png" }
    val cacheDir = File(context.cacheDir, "bgr_inputs").apply { mkdirs() }
    val outputFile = File(cacheDir, "input_${timestamp}.${extension}")
    context.contentResolver.openInputStream(uri)?.use { input ->
        outputFile.outputStream().use { output -> input.copyTo(output) }
    } ?: error("Could not open image")
    BgrInputImage(outputFile.absolutePath, name)
}

private fun queryBgrDisplayName(context: android.content.Context, uri: Uri): String {
    return context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
    } ?: File(uri.path.orEmpty()).name.ifBlank { "image.png" }
}
