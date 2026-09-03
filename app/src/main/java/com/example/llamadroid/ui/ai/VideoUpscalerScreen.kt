package com.example.llamadroid.ui.ai

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import androidx.core.content.ContextCompat
import com.example.llamadroid.data.SettingsRepository
import com.example.llamadroid.service.*
import androidx.compose.ui.res.stringResource
import com.example.llamadroid.R
import android.widget.Toast
import kotlinx.coroutines.launch
import java.io.File
import com.example.llamadroid.util.UpscalerAssetPackSupport

/**
 * Video Upscaler Screen using realsr-ncnn/realcugan-ncnn
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideoUpscalerScreen(navController: NavController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settingsRepo = remember { SettingsRepository(context) }
    
    // Check for required upscaler runtime assets
    var hasRequiredAssets by remember {
        mutableStateOf(
            UpscalerAssetPackSupport.areModelsReady(context)
        )
    }
    
    var showDownloadDialog by remember { mutableStateOf(!hasRequiredAssets) }
    
    if (showDownloadDialog) {
        com.example.llamadroid.ui.components.AssetDownloadDialog(
            onDismiss = { 
                // If dismissed without downloading, go back
                if (!hasRequiredAssets) navController.popBackStack() 
                showDownloadDialog = false 
            },
            onDownloadAll = {
                hasRequiredAssets = true
                showDownloadDialog = false
            },
            onSkip = {
                navController.popBackStack()
                showDownloadDialog = false
            }
        )
        return
    }
    
    if (!hasRequiredAssets) {
        // Fallback if dialog is somehow bypassed but assets are missing
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text(stringResource(R.string.feature_upscaler_assets_missing))
        }
        return
    }
    
    // Service binding
    var upscalerService by remember { mutableStateOf<VideoUpscalerService?>(null) }
    
    DisposableEffect(Unit) {
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                upscalerService = (binder as VideoUpscalerService.VideoUpscalerBinder).getService()
            }
            override fun onServiceDisconnected(name: ComponentName?) {
                upscalerService = null
            }
        }
        val intent = Intent(context, VideoUpscalerService::class.java)
        context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        
        onDispose {
            context.unbindService(connection)
        }
    }
    
    // State. The job itself is service-owned; the screen only observes it.
    val boundUpscalerState by upscalerService?.state?.collectAsState() ?: remember { mutableStateOf(VideoUpscalerState.Idle) }
    val boundProgress by upscalerService?.progress?.collectAsState() ?: remember { mutableStateOf(0f) }
    val eta by upscalerService?.eta?.collectAsState() ?: remember { mutableStateOf("") }
    val holderProcessing by VideoUpscalerStateHolder.isProcessing.collectAsState()
    val holderProgress by VideoUpscalerStateHolder.progress.collectAsState()
    val holderCurrentFrame by VideoUpscalerStateHolder.currentFrame.collectAsState()
    val holderTotalFrames by VideoUpscalerStateHolder.totalFrames.collectAsState()
    val holderResultPath by VideoUpscalerStateHolder.resultPath.collectAsState()
    val holderError by VideoUpscalerStateHolder.error.collectAsState()
    val upscalerState = when {
        holderError != null -> VideoUpscalerState.Error(holderError ?: "")
        holderProcessing && holderTotalFrames > 0 -> VideoUpscalerState.Upscaling(holderCurrentFrame, holderTotalFrames)
        holderProcessing -> boundUpscalerState.takeUnless { it is VideoUpscalerState.Idle || it is VideoUpscalerState.Completed } ?: VideoUpscalerState.Analyzing
        holderResultPath != null -> VideoUpscalerState.Completed
        else -> boundUpscalerState
    }
    val progress = if (holderProcessing || holderResultPath != null || holderError != null) holderProgress else boundProgress
    
    var selectedVideoPath by remember { mutableStateOf<String?>(null) }
    var videoInfo by remember { mutableStateOf<VideoInfo?>(null) }
    var selectedEngine by remember { mutableStateOf(UpscalerEngine.REALSR) }
    var selectedModel by remember { mutableStateOf<UpscalerModelCapability?>(null) }
    var selectedScale by remember { mutableIntStateOf(2) }
    var selectedDenoise by remember { mutableIntStateOf(0) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var exportedResultPath by remember { mutableStateOf<String?>(null) }
    
    // Check for shared file (from share intent)
    var pendingSharedVideoPath by remember { mutableStateOf<String?>(null) }
    
    LaunchedEffect(Unit) {
        val pendingFile = com.example.llamadroid.data.SharedFileHolder.consumeFor(
            com.example.llamadroid.data.SharedFileTarget.VIDEO_UPSCALER
        )
        if (pendingFile != null && pendingFile.mimeType.startsWith("video/")) {
            try {
                val inputStream = context.contentResolver.openInputStream(pendingFile.uri)
                val tempFile = java.io.File(context.cacheDir, "upscaler_shared_input.mp4")
                tempFile.outputStream().use { out ->
                    inputStream?.copyTo(out)
                }
                inputStream?.close()
                selectedVideoPath = tempFile.absolutePath
                pendingSharedVideoPath = tempFile.absolutePath // Mark for video info retrieval
            } catch (e: Exception) {
                errorMessage = context.getString(R.string.upscaler_error_shared_video, e.message)
            }
        }
    }
    
    // Get video info when service is bound and we have a pending shared video
    LaunchedEffect(upscalerService, pendingSharedVideoPath) {
        val service = upscalerService
        val pendingPath = pendingSharedVideoPath
        if (service != null && pendingPath != null) {
            videoInfo = service.getVideoInfo(pendingPath).getOrNull()
            pendingSharedVideoPath = null  // Clear after processing
        }
    }
    
    // Settings - thread counts for load/proc/save
    val loadThreads by settingsRepo.upscalerLoadThreads.collectAsState()
    val procThreads by settingsRepo.upscalerProcThreads.collectAsState()
    val saveThreads by settingsRepo.upscalerSaveThreads.collectAsState()
    val upscalerOutputFolder by settingsRepo.upscalerOutputFolder.collectAsState()
    val sharedOutputFolder by settingsRepo.outputFolderUri.collectAsState()
    // Use upscaler-specific output folder, or fall back to shared output folder
    val outputFolder = upscalerOutputFolder ?: sharedOutputFolder
    val upscalerModelsRoot = remember(hasRequiredAssets) {
        if (hasRequiredAssets) UpscalerAssetPackSupport.getModelsDir(context) else null
    }
    
    // Video file picker
    val videoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            // Copy to internal storage
            val inputStream = context.contentResolver.openInputStream(it)
            val tempFile = File(context.cacheDir, "upscaler_input.mp4")
            tempFile.outputStream().use { out ->
                inputStream?.copyTo(out)
            }
            inputStream?.close()
            selectedVideoPath = tempFile.absolutePath
            
            // Get video info
            scope.launch {
                upscalerService?.getVideoInfo(tempFile.absolutePath)?.fold(
                    onSuccess = { info -> videoInfo = info },
                    onFailure = { errorMessage = context.getString(R.string.upscaler_error_video_info) }
                )
            }
        }
    }
    
    // Update model when engine changes
    LaunchedEffect(selectedEngine) {
        selectedModel = UpscalerModels.getForEngine(selectedEngine).firstOrNull()
        selectedModel?.let { selectedDenoise = UpscalerModelFiles.defaultDenoise(it) }
    }
    
    // Update scale when model changes
    val availableScales = remember(selectedModel, selectedDenoise, upscalerModelsRoot) {
        val model = selectedModel
        val modelsRoot = upscalerModelsRoot
        if (model == null || modelsRoot == null) {
            emptyList()
        } else {
            UpscalerModelFiles.availableScales(
                modelsRoot = modelsRoot,
                model = model,
                denoise = if (model.engine == UpscalerEngine.REALCUGAN) selectedDenoise else -1
            )
        }
    }

    LaunchedEffect(selectedModel, selectedDenoise, availableScales) {
        selectedModel?.let { model ->
            if (selectedScale !in availableScales) {
                selectedScale = availableScales.firstOrNull() ?: model.scales.firstOrNull() ?: 2
            }
        }
    }

    LaunchedEffect(upscalerState, holderResultPath, outputFolder) {
        val path = holderResultPath ?: return@LaunchedEffect
        if (upscalerState !is VideoUpscalerState.Completed || exportedResultPath == path) return@LaunchedEffect
        val fileName = File(path).name
        if (outputFolder.isNullOrEmpty()) {
            errorMessage = context.getString(R.string.upscaler_error_no_folder, fileName)
            exportedResultPath = path
            return@LaunchedEffect
        }
        runCatching {
            val sourceFile = File(path)
            require(sourceFile.exists()) { context.getString(R.string.upscaler_error_not_found) }
            val treeUri = android.net.Uri.parse(outputFolder)
            val rootDoc = androidx.documentfile.provider.DocumentFile.fromTreeUri(context, treeUri)
                ?: error(context.getString(R.string.upscaler_error_save_folder))
            val videosDoc = rootDoc.findFile("videos") ?: rootDoc.createDirectory("videos")
                ?: error(context.getString(R.string.upscaler_error_save_folder))
            val newFile = videosDoc.createFile("video/mp4", fileName)
                ?: error(context.getString(R.string.upscaler_error_save_folder))
            context.contentResolver.openOutputStream(newFile.uri)?.use { output ->
                sourceFile.inputStream().use { input -> input.copyTo(output) }
            } ?: error(context.getString(R.string.upscaler_error_save_folder))
            "videos/$fileName"
        }.fold(
            onSuccess = { savedPath ->
                exportedResultPath = path
                errorMessage = null
                Toast.makeText(context, context.getString(R.string.upscaler_success_toast, savedPath), Toast.LENGTH_LONG).show()
            },
            onFailure = {
                exportedResultPath = path
                errorMessage = context.getString(R.string.upscaler_error_save_generic, it.message)
            }
        )
    }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.upscaler_title)) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            // Video Selection
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(stringResource(R.string.upscaler_select), style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    OutlinedButton(
                        onClick = { videoPicker.launch("video/*") },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(if (selectedVideoPath != null) stringResource(R.string.action_change) else stringResource(R.string.action_select))
                    }
                    
                    // Video Info
                    videoInfo?.let { info ->
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            InfoChip("📐 ${info.resolution}")
                            InfoChip("⏱️ ${info.durationFormatted}")
                            InfoChip("📁 ${info.sizeFormatted}")
                        }
                        
                        // Estimated output
                        Spacer(modifier = Modifier.height(8.dp))
                        val outputWidth = info.width * selectedScale
                        val outputHeight = info.height * selectedScale
                        Text(
                            stringResource(R.string.upscaler_output_resolution, outputWidth, outputHeight),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // Engine Selection
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(stringResource(R.string.upscaler_engine_label), style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilterChip(
                            selected = selectedEngine == UpscalerEngine.REALSR,
                            onClick = { selectedEngine = UpscalerEngine.REALSR },
                            label = { Text("RealSR") },
                            modifier = Modifier.weight(1f)
                        )
                        FilterChip(
                            selected = selectedEngine == UpscalerEngine.REALCUGAN,
                            onClick = { selectedEngine = UpscalerEngine.REALCUGAN },
                            label = { Text("RealCUGAN") },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // Model Selection
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(stringResource(R.string.upscaler_model_label), style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    val modelsForEngine = UpscalerModels.getForEngine(selectedEngine)
                    
                    modelsForEngine.forEach { model ->
                        ModelOptionRow(
                            model = model,
                            selected = selectedModel == model,
                            onClick = { selectedModel = model }
                        )
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // Scale Selection
            selectedModel?.let { model ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(stringResource(R.string.upscaler_scale_label), style = MaterialTheme.typography.titleMedium)
                        Spacer(modifier = Modifier.height(8.dp))

                        if (availableScales.isEmpty()) {
                            Text(
                                stringResource(R.string.upscaler_model_variant_unavailable),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        } else {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                availableScales.forEach { scale ->
                                    FilterChip(
                                        selected = selectedScale == scale,
                                        onClick = { selectedScale = scale },
                                        label = { Text("${scale}x") }
                                    )
                                }
                            }
                        }
                        
                        // Denoise (only for RealCUGAN with denoise support)
                        val availableDenoiseLevels = upscalerModelsRoot?.let {
                            UpscalerModelFiles.availableDenoiseLevels(it, model, selectedScale)
                        }.orEmpty()
                        if (availableDenoiseLevels.size > 1) {
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(stringResource(R.string.upscaler_denoise_label), style = MaterialTheme.typography.titleMedium)
                            Spacer(modifier = Modifier.height(8.dp))
                            
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                availableDenoiseLevels.forEach { level ->
                                    FilterChip(
                                        selected = selectedDenoise == level,
                                        onClick = { selectedDenoise = level },
                                        label = { Text(if (level == -1) stringResource(R.string.upscaler_none) else level.toString()) }
                                    )
                                }
                            }
                        }
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(24.dp))
            
            // Progress (when running)
            when (val state = upscalerState) {
                is VideoUpscalerState.Upscaling -> {
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
                                stringResource(R.string.upscaler_status_frames, state.current, state.total),
                                style = MaterialTheme.typography.titleMedium
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            LinearProgressIndicator(
                                progress = { progress },
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(stringResource(R.string.upscaler_eta, eta), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                }
                is VideoUpscalerState.ExtractingFrames -> {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(stringResource(R.string.upscaler_status_extracting), style = MaterialTheme.typography.bodySmall)
                    Spacer(modifier = Modifier.height(16.dp))
                }
                is VideoUpscalerState.Rebuilding -> {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(stringResource(R.string.upscaler_status_rebuilding), style = MaterialTheme.typography.bodySmall)
                    Spacer(modifier = Modifier.height(16.dp))
                }
                else -> {}
            }
            
            // Upscale Button
            Button(
                onClick = {
                    if (selectedVideoPath == null) {
                        errorMessage = context.getString(R.string.upscaler_error_no_video)
                        return@Button
                    }
                    if (selectedModel == null) {
                        errorMessage = context.getString(R.string.upscaler_error_no_model)
                        return@Button
                    }
                    
                    errorMessage = null

                    // Save to app-owned storage so the foreground service can finish even if this screen leaves composition.
                    val fileName = "upscaled_${System.currentTimeMillis()}.mp4"
                    val outputPath = File(File(context.filesDir, "video_upscale_output").apply { mkdirs() }, fileName).absolutePath
                    
                    val config = VideoUpscalerConfig(
                        inputPath = selectedVideoPath!!,
                        outputPath = outputPath,
                        engine = selectedEngine,
                        model = selectedModel!!.name,
                        scale = selectedScale,
                        denoise = if (selectedModel!!.engine == UpscalerEngine.REALCUGAN) selectedDenoise else -1,
                        loadThreads = loadThreads,
                        procThreads = procThreads,
                        saveThreads = saveThreads
                    )
                    ContextCompat.startForegroundService(
                        context,
                        VideoUpscalerService.createStartIntent(context, config)
                    )
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = upscalerState == VideoUpscalerState.Idle || 
                          upscalerState == VideoUpscalerState.Completed ||
                          upscalerState is VideoUpscalerState.Error
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.upscaler_start_btn))
            }
            
            // Cancel Button (when running)
            if (upscalerState !is VideoUpscalerState.Idle && 
                upscalerState !is VideoUpscalerState.Completed &&
                upscalerState !is VideoUpscalerState.Error) {
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { context.startService(VideoUpscalerService.createCancelIntent(context)) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
            
            // Error
            (errorMessage ?: holderError)?.let {
                Spacer(modifier = Modifier.height(8.dp))
                Text(it, color = MaterialTheme.colorScheme.error)
            }
            
            // Success
            if (upscalerState == VideoUpscalerState.Completed) {
                Spacer(modifier = Modifier.height(16.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF4CAF50).copy(alpha = 0.2f))
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Check, contentDescription = null, tint = Color(0xFF4CAF50))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.upscaler_success_msg), fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
private fun InfoChip(text: String) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
private fun ModelOptionRow(
    model: UpscalerModelCapability,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Spacer(modifier = Modifier.width(8.dp))
        
        Column(modifier = Modifier.weight(1f)) {
            Text(model.displayName, fontWeight = FontWeight.Medium)
            Text(model.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        
        // Badges
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            model.scales.forEach { scale ->
                Badge { Text("${scale}x") }
            }
            if (model.isAnime) {
                Badge(containerColor = MaterialTheme.colorScheme.secondary) { Text(stringResource(R.string.upscaler_badge_anime)) }
            }
            if (model.supportsDenoise) {
                Badge(containerColor = MaterialTheme.colorScheme.tertiary) { Text(stringResource(R.string.upscaler_badge_denoise)) }
            }
        }
    }
}
