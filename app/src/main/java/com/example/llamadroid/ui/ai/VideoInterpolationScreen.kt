package com.example.llamadroid.ui.ai

import androidx.compose.foundation.layout.consumeWindowInsets

import androidx.compose.foundation.layout.imePadding

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.IBinder
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.example.llamadroid.R
import com.example.llamadroid.data.SettingsRepository
import com.example.llamadroid.data.SharedFileHolder
import com.example.llamadroid.data.SharedFileTarget
import com.example.llamadroid.service.DownloadableMediaAsset
import com.example.llamadroid.service.MediaAssetDownloader
import com.example.llamadroid.service.MediaModelDownloadPhase
import com.example.llamadroid.service.MediaModelDownloadProgress
import com.example.llamadroid.service.MediaModelManager
import com.example.llamadroid.service.MediaModelRegistry
import com.example.llamadroid.service.VideoInterpolationBackend
import com.example.llamadroid.service.VideoInterpolationCodec
import com.example.llamadroid.service.VideoInterpolateUpscaleConfig
import com.example.llamadroid.service.VideoInterpolateUpscaleState
import com.example.llamadroid.service.VideoInterpolateUpscaleStateHolder
import com.example.llamadroid.service.VideoInterpolationConfig
import com.example.llamadroid.service.VideoInterpolationInfo
import com.example.llamadroid.service.VideoInterpolationMath
import com.example.llamadroid.service.VideoInterpolationGalleryItem
import com.example.llamadroid.service.VideoInterpolationGalleryStore
import com.example.llamadroid.service.VideoInterpolationService
import com.example.llamadroid.service.VideoInterpolationState
import com.example.llamadroid.service.VideoUpscalerConfig
import com.example.llamadroid.service.UpscalerEngine
import com.example.llamadroid.service.UpscalerModels
import com.example.llamadroid.service.UpscalerModelCapability
import com.example.llamadroid.service.UpscalerModelFiles
import com.example.llamadroid.util.UpscalerAssetPackSupport
import com.example.llamadroid.ui.components.AppScrollableTabRow
import com.example.llamadroid.ui.components.AppTaskActionFooter
import androidx.core.content.FileProvider
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideoInterpolationScreen(navController: NavController, embeddedWorkflow: Boolean = false) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val settingsRepo = remember { SettingsRepository(context) }
    val downloader = remember { MediaAssetDownloader(context) }

    var interpolationService by remember { mutableStateOf<VideoInterpolationService?>(null) }
    DisposableEffect(Unit) {
        val connection = object : android.content.ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                interpolationService = (binder as VideoInterpolationService.VideoInterpolationBinder).getService()
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                interpolationService = null
            }
        }
        val intent = Intent(context, VideoInterpolationService::class.java)
        context.startForegroundService(intent)
        context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        onDispose {
            context.unbindService(connection)
        }
    }

    val state by interpolationService?.state?.collectAsState() ?: remember {
        mutableStateOf(VideoInterpolationState.Idle)
    }
    val progress by interpolationService?.progress?.collectAsState() ?: remember { mutableStateOf(0f) }
    val eta by interpolationService?.eta?.collectAsState() ?: remember { mutableStateOf("") }
    val combinedState by VideoInterpolateUpscaleStateHolder.state.collectAsState()
    val combinedProgress by VideoInterpolateUpscaleStateHolder.progress.collectAsState()
    val combinedStatus by VideoInterpolateUpscaleStateHolder.status.collectAsState()
    val upscalerOutputFolder by settingsRepo.upscalerOutputFolder.collectAsState()
    val sharedOutputFolder by settingsRepo.outputFolderUri.collectAsState()
    val outputFolder = upscalerOutputFolder ?: sharedOutputFolder

    var selectedVideoPath by remember { mutableStateOf<String?>(null) }
    var pendingSharedVideoPath by remember { mutableStateOf<String?>(null) }
    var videoInfo by remember { mutableStateOf<VideoInterpolationInfo?>(null) }
    var selectedModel by remember { mutableStateOf(MediaModelRegistry.defaultRifeModel) }
    var installedVersion by remember { mutableIntStateOf(0) }
    // Let people compare and select the available RIFE models before asking for a download.
    var showDownloadDialog by remember { mutableStateOf(false) }
    var downloadProgress by remember { mutableStateOf<MediaModelDownloadProgress?>(null) }
    var downloadError by remember { mutableStateOf<String?>(null) }
    var downloadJob by remember { mutableStateOf<Job?>(null) }
    var selectedMultiplier by remember { mutableIntStateOf(2) }
    var selectedBackend by remember { mutableStateOf(VideoInterpolationBackend.AUTO) }
    var preserveAudio by remember { mutableStateOf(true) }
    var sceneCutProtection by remember { mutableStateOf(true) }
    var selectedCodec by remember { mutableStateOf(VideoInterpolationCodec.H264) }
    var crf by remember { mutableIntStateOf(20) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var selectedTab by remember { mutableIntStateOf(0) }
    val combinedWorkflow = embeddedWorkflow
    var combinedUpscaleEngine by remember { mutableStateOf(UpscalerEngine.REALSR) }
    var combinedUpscaleModel by remember {
        mutableStateOf<UpscalerModelCapability?>(UpscalerModels.getForEngine(UpscalerEngine.REALSR).firstOrNull())
    }
    var combinedUpscaleScale by remember { mutableIntStateOf(2) }
    var combinedUpscaleDenoise by remember { mutableIntStateOf(0) }
    var galleryItems by remember { mutableStateOf(VideoInterpolationGalleryStore.list(context)) }
    var pendingGalleryDelete by remember { mutableStateOf<VideoInterpolationGalleryItem?>(null) }
    var lastExportedCombinedGalleryId by remember { mutableStateOf<String?>(null) }
    val upscalerModelsRoot = remember {
        UpscalerAssetPackSupport.getModelsDir(context)
    }
    val combinedAvailableScales = remember(combinedUpscaleModel, combinedUpscaleDenoise, upscalerModelsRoot) {
        combinedUpscaleModel?.let { model ->
            UpscalerModelFiles.availableScales(
                modelsRoot = upscalerModelsRoot,
                model = model,
                denoise = if (model.engine == UpscalerEngine.REALCUGAN) combinedUpscaleDenoise else -1
            )
        }.orEmpty()
    }
    LaunchedEffect(combinedUpscaleEngine) {
        combinedUpscaleModel = UpscalerModels.getForEngine(combinedUpscaleEngine).firstOrNull()
        combinedUpscaleModel?.let { combinedUpscaleDenoise = UpscalerModelFiles.defaultDenoise(it) }
    }
    LaunchedEffect(combinedUpscaleModel, combinedUpscaleDenoise, combinedAvailableScales) {
        val fallback = combinedUpscaleModel?.scales?.firstOrNull() ?: 2
        if (combinedUpscaleScale !in combinedAvailableScales) {
            combinedUpscaleScale = combinedAvailableScales.firstOrNull() ?: fallback
        }
    }
    LaunchedEffect(combinedState, outputFolder) {
        val completed = combinedState as? VideoInterpolateUpscaleState.Completed ?: return@LaunchedEffect
        if (completed.galleryId == lastExportedCombinedGalleryId) return@LaunchedEffect
        lastExportedCombinedGalleryId = completed.galleryId
        galleryItems = VideoInterpolationGalleryStore.list(context)
        exportResult(
            context = context,
            sourcePath = completed.outputPath,
            fileName = File(completed.outputPath).name,
            outputFolder = outputFolder
        ).fold(
            onSuccess = { savedPath ->
                Toast.makeText(
                    context,
                    resources.getString(R.string.interpolation_success_toast, savedPath),
                    Toast.LENGTH_LONG
                ).show()
            },
            onFailure = { errorMessage = it.message }
        )
    }

    fun refreshInstalled() {
        installedVersion += 1
    }

    fun startDownload(asset: DownloadableMediaAsset) {
        downloadError = null
        downloadProgress = null
        downloadJob?.cancel()
        downloadJob = scope.launch {
            try {
                downloader.download(asset).collect { progressState ->
                    downloadProgress = progressState
                    if (progressState.phase == MediaModelDownloadPhase.COMPLETED) {
                        showDownloadDialog = false
                        refreshInstalled()
                    }
                }
            } catch (e: Exception) {
                downloadError = e.message ?: resources.getString(R.string.interpolation_download_failed)
            }
        }
    }

    fun cancelDownload(asset: DownloadableMediaAsset) {
        downloader.cancel(asset)
        downloadJob?.cancel()
        downloadJob = null
        downloadProgress = null
        MediaModelManager.cleanupIncomplete(context, asset)
    }

    fun copyUriToCache(uri: Uri, filename: String): String? = runCatching {
        val tempFile = File(context.cacheDir, filename)
        context.contentResolver.openInputStream(uri)?.use { input ->
            tempFile.outputStream().use { output -> input.copyTo(output) }
        } ?: return@runCatching null
        tempFile.absolutePath
    }.getOrNull()

    val videoPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        val path = copyUriToCache(uri, "interpolation_input.mp4")
        if (path == null) {
            errorMessage = resources.getString(R.string.interpolation_error_video_copy)
        } else {
            selectedVideoPath = path
            scope.launch {
                interpolationService?.getVideoInfo(path)?.fold(
                    onSuccess = { videoInfo = it },
                    onFailure = { errorMessage = it.message }
                )
            }
        }
    }

    LaunchedEffect(Unit) {
        val pending = SharedFileHolder.consumeFor(SharedFileTarget.VIDEO_INTERPOLATION)
        if (pending != null && pending.mimeType.startsWith("video/")) {
            val path = copyUriToCache(pending.uri, "interpolation_shared_input.mp4")
            if (path == null) {
                errorMessage = resources.getString(R.string.interpolation_error_video_copy)
            } else {
                selectedVideoPath = path
                pendingSharedVideoPath = path
            }
        }
    }

    LaunchedEffect(interpolationService, pendingSharedVideoPath) {
        val service = interpolationService
        val path = pendingSharedVideoPath
        if (service != null && path != null) {
            videoInfo = service.getVideoInfo(path).getOrNull()
            pendingSharedVideoPath = null
        }
    }

    val modelInstalled = remember(selectedModel, installedVersion) {
        MediaModelManager.isInstalled(context, selectedModel)
    }
    if (showDownloadDialog) {
        MediaModelDownloadDialog(
            asset = selectedModel,
            progress = downloadProgress,
            error = downloadError,
            isDownloading = downloadJob != null,
            onDownload = { startDownload(selectedModel) },
            onCancelDownload = { cancelDownload(selectedModel) },
            onDismiss = { showDownloadDialog = false }
        )
    }

    val footerState = if (combinedWorkflow) combinedState.asInterpolationCardState() else state
    val footerRunning = footerState !is VideoInterpolationState.Idle &&
        footerState !is VideoInterpolationState.Completed && footerState !is VideoInterpolationState.Error
    Scaffold(
        modifier = Modifier.imePadding(),
        bottomBar = {
            if (selectedTab == 0 || embeddedWorkflow || footerRunning) {
            StartInterpolationCard(
                state = if (combinedWorkflow) combinedState.asInterpolationCardState() else state,
                progress = if (combinedWorkflow) combinedProgress else progress,
                eta = if (combinedWorkflow) combinedStatus else eta,
                modelInstalled = modelInstalled,
                combinedWorkflow = combinedWorkflow,
                onStart = {
                    val path = selectedVideoPath
                    if (path == null) {
                        errorMessage = resources.getString(R.string.interpolation_error_no_video)
                        return@StartInterpolationCard
                    }
                    if (!MediaModelManager.isInstalled(context, selectedModel)) {
                        showDownloadDialog = true
                        return@StartInterpolationCard
                    }
                    val outputPath = File(context.cacheDir, "interpolated_${System.currentTimeMillis()}.mp4").absolutePath
                    val config = VideoInterpolationConfig(
                        inputPath = path,
                        outputPath = outputPath,
                        modelId = selectedModel.id,
                        multiplier = selectedMultiplier,
                        backend = selectedBackend,
                        preserveAudio = preserveAudio,
                        sceneCutProtection = sceneCutProtection,
                        codec = selectedCodec,
                        crf = crf
                    )
                    if (combinedWorkflow) {
                        val model = combinedUpscaleModel
                        if (model == null || combinedAvailableScales.isEmpty()) {
                            errorMessage = resources.getString(R.string.upscaler_model_variant_unavailable)
                            return@StartInterpolationCard
                        }
                        val upscaleOutput = File(context.cacheDir, "interpolated_upscaled_${System.currentTimeMillis()}.mp4")
                        val combinedConfig = VideoInterpolateUpscaleConfig(
                            interpolationConfig = config,
                            upscaleConfig = VideoUpscalerConfig(
                                inputPath = outputPath,
                                outputPath = upscaleOutput.absolutePath,
                                engine = combinedUpscaleEngine,
                                model = model.name,
                                scale = combinedUpscaleScale,
                                denoise = if (model.engine == UpscalerEngine.REALCUGAN) combinedUpscaleDenoise else -1
                            )
                        )
                        context.startForegroundService(
                            VideoInterpolationService.createStartInterpolateUpscaleIntent(context, combinedConfig)
                        )
                        return@StartInterpolationCard
                    }
                    scope.launch {
                        interpolationService?.interpolate(config)?.fold(
                            onSuccess = { generatedPath ->
                                val finalPath = generatedPath
                                val galleryItem = VideoInterpolationGalleryStore.save(
                                    context = context,
                                    source = File(finalPath),
                                    config = config,
                                    info = videoInfo,
                                    backendUsed = (state as? VideoInterpolationState.Completed)?.backendUsed,
                                    workflow = "INTERPOLATE_ONLY",
                                    upscaleModel = null,
                                    upscaleScale = null
                                )
                                galleryItems = VideoInterpolationGalleryStore.list(context)
                                exportResult(
                                    context = context,
                                    sourcePath = galleryItem.videoFile.absolutePath,
                                    fileName = galleryItem.videoFile.name,
                                    outputFolder = outputFolder
                                ).fold(
                                    onSuccess = { savedPath ->
                                        Toast.makeText(
                                            context,
                                            resources.getString(R.string.interpolation_success_toast, savedPath),
                                            Toast.LENGTH_LONG
                                        ).show()
                                    },
                                    onFailure = { errorMessage = it.message }
                                )
                            },
                            onFailure = { errorMessage = it.message }
                        )
                    }
                },
                onCancel = {
                    interpolationService?.cancel()
                    context.startService(VideoInterpolationService.createCancelIntent(context))
                }
            )
            }
        },
        topBar = {
            if (!embeddedWorkflow) {
                TopAppBar(
                    title = { Text(stringResource(R.string.interpolation_title)) },
                    navigationIcon = {
                        IconButton(onClick = { navController.popBackStack() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                        }
                    }
                )
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (!embeddedWorkflow) AppScrollableTabRow(selectedTabIndex = selectedTab) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text(stringResource(R.string.interpolation_tab_process)) }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = {
                        galleryItems = VideoInterpolationGalleryStore.list(context)
                        selectedTab = 1
                    },
                    text = { Text(stringResource(R.string.interpolation_tab_gallery)) }
                )
            }
            if (!embeddedWorkflow && selectedTab == 1) {
                InterpolationGallery(
                    items = galleryItems,
                    onShare = { item ->
                        val uri = FileProvider.getUriForFile(
                            context,
                            "${context.packageName}.fileprovider",
                            item.videoFile
                        )
                        context.startActivity(
                            Intent.createChooser(
                                Intent(Intent.ACTION_SEND).apply {
                                    type = "video/mp4"
                                    putExtra(Intent.EXTRA_STREAM, uri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                },
                                resources.getString(R.string.interpolation_share)
                            )
                        )
                    },
                    onDelete = { pendingGalleryDelete = it }
                )
            } else {
            if (combinedWorkflow) {
                Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            stringResource(R.string.interpolation_upscale_stage),
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(stringResource(R.string.upscaler_engine_label))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            UpscalerEngine.entries.forEach { engine ->
                                FilterChip(
                                    selected = combinedUpscaleEngine == engine,
                                    onClick = { combinedUpscaleEngine = engine },
                                    label = { Text(if (engine == UpscalerEngine.REALSR) "RealSR" else "RealCUGAN") }
                                )
                            }
                        }
                        Text(stringResource(R.string.upscaler_model_label))
                        UpscalerModels.getForEngine(combinedUpscaleEngine).forEach { model ->
                            FilterChip(
                                selected = combinedUpscaleModel == model,
                                onClick = { combinedUpscaleModel = model },
                                label = { Text(model.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                            )
                        }
                        Text(stringResource(R.string.upscaler_scale_label))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            combinedAvailableScales.forEach { scale ->
                                FilterChip(
                                    selected = combinedUpscaleScale == scale,
                                    onClick = { combinedUpscaleScale = scale },
                                    label = { Text("${scale}×") }
                                )
                            }
                        }
                        if (combinedAvailableScales.isEmpty()) {
                            Text(
                                stringResource(R.string.upscaler_model_variant_unavailable),
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        val combinedDenoiseLevels = combinedUpscaleModel?.let {
                            UpscalerModelFiles.availableDenoiseLevels(upscalerModelsRoot, it, combinedUpscaleScale)
                        }.orEmpty()
                        if (combinedDenoiseLevels.size > 1) {
                            Text(stringResource(R.string.upscaler_denoise_label))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                combinedDenoiseLevels.forEach { level ->
                                    FilterChip(
                                        selected = combinedUpscaleDenoise == level,
                                        onClick = { combinedUpscaleDenoise = level },
                                        label = {
                                            Text(if (level == -1) stringResource(R.string.upscaler_none) else level.toString())
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }
            VideoInputCard(
                selected = selectedVideoPath != null,
                info = videoInfo,
                multiplier = selectedMultiplier,
                onPick = { videoPicker.launch("video/*") }
            )

            InterpolationOptionsCard(
                multiplier = selectedMultiplier,
                onMultiplier = { selectedMultiplier = it },
                backend = selectedBackend,
                onBackend = { selectedBackend = it },
                preserveAudio = preserveAudio,
                onPreserveAudio = { preserveAudio = it },
                sceneCutProtection = sceneCutProtection,
                onSceneCutProtection = { sceneCutProtection = it },
                codec = selectedCodec,
                onCodec = { selectedCodec = it },
                crf = crf,
                onCrf = { crf = it },
                videoInfo = videoInfo
            )

            if (combinedWorkflow && videoInfo != null) {
                val info = videoInfo!!
                val finalFrames = VideoInterpolationMath.outputFrameCount(info.frameCount, selectedMultiplier)
                val finalFps = VideoInterpolationMath.outputFps(info.fps, selectedMultiplier)
                Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(stringResource(R.string.workflow_video_estimated_output), style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(
                            R.string.workflow_video_estimated_resolution,
                            info.width * combinedUpscaleScale,
                            info.height * combinedUpscaleScale
                        ))
                        Text(stringResource(R.string.workflow_video_estimated_fps, finalFps))
                        Text(stringResource(R.string.workflow_video_estimated_frames, finalFrames))
                        Text(stringResource(R.string.workflow_video_estimated_duration, info.durationFormatted))
                        val estimatedBytes = (
                            info.sizeBytes.toDouble() *
                                selectedMultiplier.toDouble() *
                                combinedUpscaleScale.toDouble() *
                                combinedUpscaleScale.toDouble()
                            ).toLong().coerceAtLeast(info.sizeBytes)
                        Text(
                            stringResource(
                                R.string.workflow_video_estimated_size,
                                com.example.llamadroid.util.FormatUtils.Technical.formatBytes(estimatedBytes)
                            )
                        )
                    }
                }
            }

            ModelSelectionCard(
                selectedModel = selectedModel,
                installedVersion = installedVersion,
                onSelect = { selectedModel = it },
                onDownload = {
                    selectedModel = it
                    showDownloadDialog = true
                },
                onDelete = {
                    MediaModelManager.delete(context, it)
                    refreshInstalled()
                },
                onVerify = {
                    val ok = MediaModelManager.isInstalled(context, it)
                    Toast.makeText(
                        context,
                        resources.getString(if (ok) R.string.interpolation_model_verify_ok else R.string.interpolation_model_verify_failed),
                        Toast.LENGTH_SHORT
                    ).show()
                    refreshInstalled()
                }
            )



            val visibleError = errorMessage
                ?: (combinedState as? VideoInterpolateUpscaleState.Error)
                    ?.message
                    ?.takeIf { combinedWorkflow }
            visibleError?.let {
                Text(it, color = MaterialTheme.colorScheme.error)
            }

            if ((!combinedWorkflow && state is VideoInterpolationState.Completed) ||
                (combinedWorkflow && combinedState is VideoInterpolateUpscaleState.Completed)
            ) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.interpolation_success_message), fontWeight = FontWeight.Bold)
                    }
                }
            }
            }
        }
    }
    pendingGalleryDelete?.let { item ->
        AlertDialog(
            onDismissRequest = { pendingGalleryDelete = null },
            text = { Text(stringResource(R.string.interpolation_delete_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    VideoInterpolationGalleryStore.delete(item)
                    galleryItems = VideoInterpolationGalleryStore.list(context)
                    pendingGalleryDelete = null
                }) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingGalleryDelete = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }
}

@Composable
private fun InterpolationGallery(
    items: List<VideoInterpolationGalleryItem>,
    onShare: (VideoInterpolationGalleryItem) -> Unit,
    onDelete: (VideoInterpolationGalleryItem) -> Unit
) {
    if (items.isEmpty()) {
        Text(
            stringResource(R.string.interpolation_gallery_empty),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        return
    }
    items.forEach { item ->
        Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    if (item.workflow == "INTERPOLATE_UPSCALE") {
                        stringResource(R.string.interpolation_workflow_combined)
                    } else {
                        stringResource(R.string.interpolation_workflow_only)
                    },
                    fontWeight = FontWeight.Bold
                )
                Text(
                    buildString {
                        append("${item.modelId} · ${item.multiplier}× · ${item.backendUsed ?: item.backendRequested} · ${item.codec} CRF ${item.crf}")
                        item.upscaleScale?.let { append(" · upscale ${it}×") }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                item.sourceResolution?.let {
                    Text(
                        "$it · ${com.example.llamadroid.util.FormatUtils.Technical.formatBytes(item.outputSizeBytes)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { onShare(item) }) {
                        Icon(Icons.Default.Share, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.interpolation_share))
                    }
                    TextButton(onClick = { onDelete(item) }) {
                        Icon(Icons.Default.Delete, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.action_delete))
                    }
                }
            }
        }
    }
}

@Composable
private fun VideoInputCard(
    selected: Boolean,
    info: VideoInterpolationInfo?,
    multiplier: Int,
    onPick: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.interpolation_input_video), style = MaterialTheme.typography.titleMedium)
            OutlinedButton(onClick = onPick, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.FolderOpen, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(if (selected) stringResource(R.string.action_change) else stringResource(R.string.action_select))
            }
            info?.let {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    InterpolationInfoChip(it.resolution)
                    InterpolationInfoChip(stringResource(R.string.interpolation_info_fps, it.fps))
                    InterpolationInfoChip(it.durationFormatted)
                }
                val outputFrames = VideoInterpolationMath.outputFrameCount(it.frameCount, multiplier)
                val outputFps = VideoInterpolationMath.outputFps(it.fps, multiplier)
                Text(
                    stringResource(R.string.interpolation_output_estimate, outputFrames, outputFps, it.durationSeconds),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold
                )
                if (it.fps <= 6.0 && multiplier < 4) {
                    Text(
                        stringResource(R.string.interpolation_adt_recommendation, it.fps * 4.0, it.durationSeconds),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                }
                if (it.isLikelyVariableFrameRate) {
                    Text(
                        stringResource(R.string.interpolation_vfr_warning),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

@Composable
private fun InterpolationOptionsCard(
    multiplier: Int,
    onMultiplier: (Int) -> Unit,
    backend: VideoInterpolationBackend,
    onBackend: (VideoInterpolationBackend) -> Unit,
    preserveAudio: Boolean,
    onPreserveAudio: (Boolean) -> Unit,
    sceneCutProtection: Boolean,
    onSceneCutProtection: (Boolean) -> Unit,
    codec: VideoInterpolationCodec,
    onCodec: (VideoInterpolationCodec) -> Unit,
    crf: Int,
    onCrf: (Int) -> Unit,
    videoInfo: VideoInterpolationInfo?
) {
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(stringResource(R.string.interpolation_options), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.interpolation_multiplier_label), style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(2, 4).forEach { value ->
                    FilterChip(
                        selected = multiplier == value,
                        onClick = { onMultiplier(value) },
                        label = { Text(stringResource(R.string.interpolation_multiplier_value, value)) }
                    )
                }
            }
            videoInfo?.let {
                Text(
                    stringResource(R.string.interpolation_output_fps_label, VideoInterpolationMath.outputFps(it.fps, multiplier)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Text(stringResource(R.string.interpolation_backend_label), style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                VideoInterpolationBackend.entries.forEach { value ->
                    FilterChip(
                        selected = backend == value,
                        onClick = { onBackend(value) },
                        label = { Text(stringResource(value.labelRes())) }
                    )
                }
            }

            ToggleRow(stringResource(R.string.interpolation_preserve_audio), preserveAudio, onPreserveAudio)
            ToggleRow(stringResource(R.string.interpolation_scene_cut), sceneCutProtection, onSceneCutProtection)

            Text(stringResource(R.string.interpolation_codec_label), style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                VideoInterpolationCodec.entries.forEach { value ->
                    FilterChip(
                        selected = codec == value,
                        onClick = { onCodec(value) },
                        label = { Text(stringResource(value.labelRes())) }
                    )
                }
            }
            Text(stringResource(R.string.interpolation_quality, crf), style = MaterialTheme.typography.labelLarge)
            Slider(
                value = crf.toFloat(),
                onValueChange = { onCrf(it.toInt()) },
                valueRange = 14f..32f,
                steps = 17
            )
        }
    }
}

@Composable
private fun ModelSelectionCard(
    selectedModel: DownloadableMediaAsset,
    installedVersion: Int,
    onSelect: (DownloadableMediaAsset) -> Unit,
    onDownload: (DownloadableMediaAsset) -> Unit,
    onDelete: (DownloadableMediaAsset) -> Unit,
    onVerify: (DownloadableMediaAsset) -> Unit
) {
    val context = LocalContext.current
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.interpolation_model_label), style = MaterialTheme.typography.titleMedium)
            MediaModelRegistry.rifeModels.forEach { model ->
                val installed = remember(model, installedVersion) { MediaModelManager.isInstalled(context, model) }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(model) }
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(selected = selectedModel == model, onClick = { onSelect(model) })
                    Spacer(modifier = Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(model.displayName, fontWeight = FontWeight.Medium)
                        Text(
                            stringResource(model.descriptionRes()),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Badge(
                        containerColor = if (installed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        Text(stringResource(if (installed) R.string.interpolation_model_installed else R.string.interpolation_model_missing))
                    }
                }
                if (selectedModel == model) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { onDownload(model) }) {
                            Icon(if (installed) Icons.Default.Refresh else Icons.Default.Download, contentDescription = null)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(stringResource(if (installed) R.string.interpolation_model_redownload else R.string.interpolation_model_download))
                        }
                        OutlinedButton(onClick = { onVerify(model) }) {
                            Icon(Icons.Default.Info, contentDescription = null)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(stringResource(R.string.interpolation_model_verify))
                        }
                        if (installed) {
                            OutlinedButton(onClick = { onDelete(model) }) {
                                Icon(Icons.Default.Delete, contentDescription = null)
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(stringResource(R.string.action_delete))
                            }
                        }
                    }
                    val size = MediaModelManager.installedSizeBytes(context, model)
                    if (size > 0L) {
                        Text(
                            stringResource(
                                R.string.interpolation_model_disk_usage,
                                com.example.llamadroid.util.FormatUtils.Technical.formatBytes(size)
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

@Composable
private fun StartInterpolationCard(
    state: VideoInterpolationState,
    progress: Float,
    eta: String,
    modelInstalled: Boolean,
    combinedWorkflow: Boolean,
    onStart: () -> Unit,
    onCancel: () -> Unit
) {
    val isRunning = state !is VideoInterpolationState.Idle &&
        state !is VideoInterpolationState.Completed && state !is VideoInterpolationState.Error
    AppTaskActionFooter {
        if (isRunning) {
            val percent = (progress * 100).toInt().coerceIn(0, 100)
            Text(if (combinedWorkflow && eta.isNotBlank()) "$percent% · $eta"
                else stringResource(R.string.interpolation_progress_eta, percent, eta.ifBlank { "--" }),
                style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            Button(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.soft_studio_cancel))
            }
        } else {
            if (!modelInstalled) Text(stringResource(R.string.interpolation_start_model_required),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            Button(onClick = onStart, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.PlayArrow, null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(if (combinedWorkflow) R.string.interpolation_start_combined else R.string.interpolation_start))
            }
        }
    }
}

@Composable
private fun MediaModelDownloadDialog(
    asset: DownloadableMediaAsset,
    progress: MediaModelDownloadProgress?,
    error: String?,
    isDownloading: Boolean,
    onDownload: () -> Unit,
    onCancelDownload: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = { if (!isDownloading) onDismiss() },
        title = { Text(stringResource(R.string.interpolation_download_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.interpolation_download_desc, asset.displayName))
                Text(
                    stringResource(
                        R.string.interpolation_download_size,
                        com.example.llamadroid.util.FormatUtils.Technical.formatBytes(asset.estimatedBytes)
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                progress?.let {
                    LinearProgressIndicator(progress = { it.progress }, modifier = Modifier.fillMaxWidth())
                    Text(
                        stringResource(
                            R.string.interpolation_download_progress,
                            (it.progress * 100).toInt(),
                            it.downloadedFormatted,
                            it.totalFormatted,
                            it.speedFormatted
                        ),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            if (isDownloading) {
                TextButton(onClick = onCancelDownload) { Text(stringResource(R.string.action_cancel)) }
            } else {
                Button(onClick = onDownload) { Text(stringResource(R.string.interpolation_model_download)) }
            }
        },
        dismissButton = {
            if (!isDownloading) TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        }
    )
}

@Composable
private fun ToggleRow(label: String, value: Boolean, onValueChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = value, onCheckedChange = onValueChange)
    }
}

@Composable
private fun InterpolationInfoChip(text: String) {
    androidx.compose.material3.Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            style = MaterialTheme.typography.bodySmall
        )
    }
}

private fun VideoInterpolationBackend.labelRes(): Int = when (this) {
    VideoInterpolationBackend.AUTO -> R.string.interpolation_backend_auto
    VideoInterpolationBackend.VULKAN -> R.string.interpolation_backend_vulkan
    VideoInterpolationBackend.CPU -> R.string.interpolation_backend_cpu
}

private fun VideoInterpolateUpscaleState.asInterpolationCardState(): VideoInterpolationState = when (this) {
    VideoInterpolateUpscaleState.Idle -> VideoInterpolationState.Idle
    VideoInterpolateUpscaleState.Interpolating -> VideoInterpolationState.Interpolating(0, 0, null)
    VideoInterpolateUpscaleState.Upscaling -> VideoInterpolationState.Finalizing
    VideoInterpolateUpscaleState.Finalizing -> VideoInterpolationState.Finalizing
    is VideoInterpolateUpscaleState.Completed -> VideoInterpolationState.Completed(outputPath, null)
    is VideoInterpolateUpscaleState.Error -> VideoInterpolationState.Error(message)
}

private fun VideoInterpolationCodec.labelRes(): Int = when (this) {
    VideoInterpolationCodec.H264 -> R.string.interpolation_codec_h264
    VideoInterpolationCodec.HEVC -> R.string.interpolation_codec_hevc
}

private fun DownloadableMediaAsset.descriptionRes(): Int = when (id) {
    "rife-v4.6" -> R.string.interpolation_model_rife_v46_desc
    "rife-v4" -> R.string.interpolation_model_rife_v4_desc
    "rife-anime" -> R.string.interpolation_model_rife_anime_desc
    else -> R.string.interpolation_model_generic_desc
}

private fun exportResult(
    context: Context,
    sourcePath: String,
    fileName: String,
    outputFolder: String?
): Result<String> = runCatching {
    if (outputFolder.isNullOrEmpty()) {
        return@runCatching fileName
    }
    val sourceFile = File(sourcePath)
    val treeUri = Uri.parse(outputFolder)
    val rootDoc = androidx.documentfile.provider.DocumentFile.fromTreeUri(context, treeUri)
        ?: error(context.getString(R.string.interpolation_error_output_folder))
    val videosDoc = rootDoc.findFile("videos") ?: rootDoc.createDirectory("videos")
        ?: error(context.getString(R.string.interpolation_error_output_folder))
    val newFile = videosDoc.createFile("video/mp4", fileName)
        ?: error(context.getString(R.string.interpolation_error_output_folder))
    context.contentResolver.openOutputStream(newFile.uri)?.use { output ->
        sourceFile.inputStream().use { input -> input.copyTo(output) }
    } ?: error(context.getString(R.string.interpolation_error_output_folder))
    "videos/$fileName"
}
