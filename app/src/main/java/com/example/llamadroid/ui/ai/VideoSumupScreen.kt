package com.example.llamadroid.ui.ai

import androidx.compose.foundation.layout.consumeWindowInsets

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.example.llamadroid.R
import com.example.llamadroid.data.SettingsRepository
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.data.db.ModelType
import com.example.llamadroid.data.db.NoteType
import com.example.llamadroid.data.model.VideoRecognitionBundleCatalog
import com.example.llamadroid.service.RemoteSummaryClientFactory
import com.example.llamadroid.service.VideoSummaryStateHolder
import com.example.llamadroid.service.VideoSumupService
import com.example.llamadroid.service.VideoRecognitionAudioFallbackRequest
import com.example.llamadroid.service.VideoRecognitionLimits
import com.example.llamadroid.service.VideoRecognitionSettingsRepository
import com.example.llamadroid.service.VideoRecognitionSummaryRequest
import com.example.llamadroid.service.VideoRecognitionSummaryService
import com.example.llamadroid.service.VideoRecognitionSummaryStatus
import com.example.llamadroid.service.VideoRecognitionTarget
import com.example.llamadroid.service.VideoRecognitionTargetSelector
import com.example.llamadroid.service.videoRecognitionTargetFromBundle
import com.example.llamadroid.service.WhisperLanguages
import com.example.llamadroid.service.WhisperVadAssetStore
import com.example.llamadroid.ui.components.IntInputField
import com.example.llamadroid.ui.components.IntSliderWithInput
import com.example.llamadroid.ui.components.WhisperVadInlineControl
import com.example.llamadroid.ui.components.RemoteSummaryBackendEditor
import com.example.llamadroid.ui.components.SliderWithInput
import com.example.llamadroid.ui.components.SummaryMarkdownCard
import com.example.llamadroid.ui.components.AppTaskActionFooter
import com.example.llamadroid.ui.walkthrough.LocalWalkthroughTargets
import com.example.llamadroid.ui.walkthrough.walkthroughTarget
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideoSumupScreen(navController: NavController) {
    val context = LocalContext.current
    val walkthroughTargets = LocalWalkthroughTargets.current
    val resources = context.resources
    val settingsRepo = remember { SettingsRepository(context) }
    val videoRecognitionSettings = remember { VideoRecognitionSettingsRepository(context) }
    val db = remember { AppDatabase.getDatabase(context) }

    val allModels by db.modelDao().getAllModels().collectAsState(initial = emptyList())
    val whisperModels by db.modelDao().getModelsByType(ModelType.WHISPER).collectAsState(initial = emptyList())
    val llamaServers by db.llamaServerDao().getAllServers().collectAsState(initial = emptyList())
    var selectedWhisperPath by rememberSaveable {
        mutableStateOf(settingsRepo.videoSumupWhisperModelPath.value)
    }

    val selectedVideoString by VideoSummaryStateHolder.selectedSourceUri.collectAsState()
    val selectedVideoName by VideoSummaryStateHolder.selectedSourceName.collectAsState()
    val transcript by VideoSummaryStateHolder.transcript.collectAsState()
    val summary by VideoSummaryStateHolder.summary.collectAsState()
    val partialSummaries by VideoSummaryStateHolder.partialSummaries.collectAsState()
    val currentChunk by VideoSummaryStateHolder.currentChunk.collectAsState()
    val totalChunks by VideoSummaryStateHolder.totalChunks.collectAsState()
    val errorMessage by VideoSummaryStateHolder.error.collectAsState()
    val isRunning by VideoSummaryStateHolder.isRunning.collectAsState()
    val progress by VideoSummaryStateHolder.progress.collectAsState()
    val progressFraction by VideoSummaryStateHolder.progressFraction.collectAsState()
    val projectedChunkCount by VideoSummaryStateHolder.projectedChunkCount.collectAsState()
    val cancelled by VideoSummaryStateHolder.cancelled.collectAsState()

    val visualState by VideoRecognitionSummaryService.state.collectAsState()
    // Keep the live UI bounded for long videos; the coordinator retains the full list for
    // hierarchical merging and only exposes the latest observations as a progress preview.
    val visualPartialSummaries = visualState.partialSummaries.takeLast(3)
    val videoSavedTargetId by videoRecognitionSettings.savedTargetId.collectAsState()
    val videoRemoteEnabled by videoRecognitionSettings.remoteEnabled.collectAsState()
    val videoAudioEnabled by videoRecognitionSettings.audioEnabled.collectAsState()
    val videoParallelWhisperEnabled by videoRecognitionSettings.parallelWhisperEnabled.collectAsState()
    val videoSegmentSeconds by videoRecognitionSettings.segmentSeconds.collectAsState()
    val videoMaxFrames by videoRecognitionSettings.maxFrames.collectAsState()
    val videoMaxFps by videoRecognitionSettings.maxFps.collectAsState()
    val videoTargetLanguage by videoRecognitionSettings.targetLanguage.collectAsState()
    val videoPrompt by videoRecognitionSettings.prompt.collectAsState()
    val videoContextSize by videoRecognitionSettings.contextSize.collectAsState()
    val videoMaxTokens by videoRecognitionSettings.maxTokens.collectAsState()
    val videoTemperature by videoRecognitionSettings.temperature.collectAsState()

    val installedVideoBundles = remember(allModels) {
        VideoRecognitionBundleCatalog.installedBundles(allModels)
    }
    val localVideoTargets = remember(installedVideoBundles, allModels) {
        installedVideoBundles.mapNotNull { bundle ->
            videoRecognitionTargetFromBundle(
                bundle = bundle,
                installedModels = VideoRecognitionBundleCatalog.installedModels(bundle, allModels)
            )
        }
    }
    val selectedLocalTarget = remember(videoSavedTargetId, localVideoTargets) {
        VideoRecognitionTargetSelector.select(videoSavedTargetId, localVideoTargets)
    }
    val selectedLocalBundle = remember(selectedLocalTarget, installedVideoBundles) {
        installedVideoBundles.firstOrNull { it.id == selectedLocalTarget?.id }
    }
    val remoteVideoTargets = remember(llamaServers) {
        llamaServers.mapNotNull { VideoRecognitionTarget.fromServer(it) }
    }
    val selectedRemoteTarget = remember(videoSavedTargetId, remoteVideoTargets) {
        VideoRecognitionTargetSelector.selectRemote(videoSavedTargetId, remoteVideoTargets).target
    }
    val selectedVisualTarget = remember(videoSavedTargetId, localVideoTargets, remoteVideoTargets) {
        VideoRecognitionTargetSelector.selectConfigured(
            savedTargetId = videoSavedTargetId,
            localTargets = localVideoTargets,
            remoteTargets = remoteVideoTargets
        ).target
    }
    // A remote row is shown only after it is explicitly persisted. With no saved target the
    // coordinator uses the verified local sole/recommended bundle and never auto-selects remote.
    val showRemoteTarget = videoRemoteEnabled ||
        videoSavedTargetId?.startsWith("remote-server:") == true

    val whisperLanguage by settingsRepo.videoSummaryWhisperLanguage.collectAsState()
    val whisperThreads by settingsRepo.videoSummaryWhisperThreads.collectAsState()
    val whisperVad by settingsRepo.whisperVadConfig.collectAsState()
    val effectiveWhisperVadPath = WhisperVadAssetStore.resolvePath(context, whisperVad.modelPath)
    val backend by settingsRepo.videoSummaryBackend.collectAsState()
    val ollamaUrl by settingsRepo.videoSummaryOllamaUrl.collectAsState()
    val llamaServerUrl by settingsRepo.videoSummaryLlamaServerUrl.collectAsState()
    val llamaSwapUrl by settingsRepo.videoSummaryLlamaSwapUrl.collectAsState()
    val ollamaModel by settingsRepo.videoSummaryOllamaModel.collectAsState()
    val llamaSwapModel by settingsRepo.videoSummaryLlamaSwapModel.collectAsState()
    val thinkingEnabled by settingsRepo.videoSummaryThinkingEnabled.collectAsState()
    val videoSummaryPrompt by settingsRepo.videoSummaryPrompt.collectAsState()
    val targetLanguage by settingsRepo.videoSummaryTargetLanguage.collectAsState()
    val chunkContext by settingsRepo.videoSummaryChunkContext.collectAsState()
    val chunkMaxTokens by settingsRepo.videoSummaryChunkMaxTokens.collectAsState()
    val mergeContext by settingsRepo.videoSummaryMergeContext.collectAsState()
    val mergeMaxTokens by settingsRepo.videoSummaryMergeMaxTokens.collectAsState()
    val temperature by settingsRepo.videoSummaryTemperature.collectAsState()
    val timeoutMinutes by settingsRepo.videoSummaryTimeoutMinutes.collectAsState()
    val serverModelLabel by settingsRepo.videoSummaryLlamaServerModelLabel.collectAsState()
    val serverContextLabel by settingsRepo.videoSummaryLlamaServerContextLabel.collectAsState()
    val serverContextTokens by settingsRepo.videoSummaryLlamaServerContextTokens.collectAsState()
    val liteRtModelId by settingsRepo.videoSummaryLiteRtModelId.collectAsState()
    val liteRtBackend by settingsRepo.videoSummaryLiteRtBackend.collectAsState()
    val liteRtMtpEnabled by settingsRepo.videoSummaryLiteRtMtpEnabled.collectAsState()

    LaunchedEffect(whisperModels) {
        val selectedStillExists = whisperModels.any { it.path == selectedWhisperPath }
        if (!selectedStillExists) {
            selectedWhisperPath = whisperModels.firstOrNull()?.path
            settingsRepo.setVideoSumupWhisperModelPath(selectedWhisperPath)
        }
    }

    LaunchedEffect(visualState.status) {
        if (!visualState.isRunning) {
            videoRecognitionSettings.refreshSavedTargetId()
        }
    }

    val videoPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            try {
                context.contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Exception) {
            }
            if (isRunning || visualState.isRunning) return@let
            VideoSummaryStateHolder.reset()
            VideoRecognitionSummaryService.clear()
            VideoSummaryStateHolder.setSelectedSourceUri(it.toString())
            VideoSummaryStateHolder.setSelectedSourceName(it.lastPathSegment ?: resources.getString(R.string.video_sumup_video_placeholder))
        }
    }

    val backendReady = when (SettingsRepository.normalizeOllamaOrLlamaBackend(backend)) {
        SettingsRepository.PDF_BACKEND_LLAMA_SERVER -> llamaServerUrl.isNotBlank()
        SettingsRepository.PDF_BACKEND_LLAMA_SWAP -> llamaSwapUrl.isNotBlank() && !llamaSwapModel.isNullOrBlank()
        SettingsRepository.PDF_BACKEND_LITERT -> liteRtModelId?.let { it > 0L } == true
        else -> ollamaUrl.isNotBlank() && !ollamaModel.isNullOrBlank()
    }

    val anySummaryRunning = isRunning || visualState.isRunning
    val visualTargetAvailable = selectedVisualTarget != null
    val audioFallbackAvailable = selectedWhisperPath != null && backendReady &&
        (!whisperVad.enabled || effectiveWhisperVadPath != null)

    fun persistMetadata(metadata: com.example.llamadroid.service.RemoteSummaryMetadata) {
        if (SettingsRepository.isLlamaServerBackend(metadata.backend)) {
            settingsRepo.setVideoSummaryLlamaServerModelLabel(metadata.serverModelLabel)
            settingsRepo.setVideoSummaryLlamaServerContextTokens(metadata.serverContextTokens)
            settingsRepo.setVideoSummaryLlamaServerContextLabel(metadata.serverContextLabel)
        }
    }

    fun startSummary(forceAudioFallback: Boolean = false) {
        val selectedUri = selectedVideoString?.let(Uri::parse) ?: return
        walkthroughTargets?.recordEvent("documents.summary.input")
        val legacySettings = settingsRepo.videoSummarySettings.snapshot()
        VideoRecognitionSummaryService.start(
            context = context,
            request = VideoRecognitionSummaryRequest(
                sourceUri = selectedUri,
                sourceName = selectedVideoName
                    ?: resources.getString(R.string.video_sumup_video_placeholder),
                localTargets = localVideoTargets,
                remoteTargets = remoteVideoTargets,
                settings = videoRecognitionSettings.snapshot(),
                audioFallback = VideoRecognitionAudioFallbackRequest(
                    whisperModelPath = selectedWhisperPath.takeIf { audioFallbackAvailable },
                    language = whisperLanguage,
                    threads = whisperThreads,
                    vadConfig = settingsRepo.whisperVadConfigSnapshot(),
                    settingsOverride = legacySettings,
                    saveToNotes = true,
                    noteType = NoteType.VIDEO_SUMMARY
                ),
                forceAudioFallback = forceAudioFallback,
                saveToNotes = true,
                noteType = NoteType.VIDEO_SUMMARY
            )
        )
    }

    Scaffold(
        modifier = Modifier.imePadding(),
        topBar = {
            TopAppBar(
                    actions = { com.example.llamadroid.ui.walkthrough.FeatureGuideAction() },
                title = { Text(stringResource(R.string.video_sumup_title)) },
                navigationIcon = {
                    IconButton(
                        onClick = { navController.popBackStack() },
                        modifier = Modifier.walkthroughTarget("back")
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back))
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
        ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.32f)
                )
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        stringResource(R.string.video_recognition_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        stringResource(R.string.video_recognition_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(stringResource(R.string.video_recognition_remote_target))
                        Switch(
                            checked = videoRemoteEnabled,
                            onCheckedChange = videoRecognitionSettings::setRemoteEnabled,
                            enabled = !anySummaryRunning
                        )
                    }

                    if (showRemoteTarget) {
                        var remoteTargetMenuExpanded by remember { mutableStateOf(false) }
                        ExposedDropdownMenuBox(
                            expanded = remoteTargetMenuExpanded,
                            onExpandedChange = { if (!anySummaryRunning) remoteTargetMenuExpanded = it }
                        ) {
                            OutlinedTextField(
                                value = selectedRemoteTarget?.label
                                    ?: stringResource(R.string.video_recognition_remote_server_placeholder),
                                onValueChange = {},
                                readOnly = true,
                                label = { Text(stringResource(R.string.video_recognition_remote_target)) },
                                trailingIcon = {
                                    ExposedDropdownMenuDefaults.TrailingIcon(expanded = remoteTargetMenuExpanded)
                                },
                                modifier = Modifier.menuAnchor().fillMaxWidth()
                            )
                            ExposedDropdownMenu(
                                expanded = remoteTargetMenuExpanded,
                                onDismissRequest = { remoteTargetMenuExpanded = false }
                            ) {
                                remoteVideoTargets.forEach { target ->
                                    DropdownMenuItem(
                                        text = { Text(target.label) },
                                        onClick = {
                                            videoRecognitionSettings.setSavedTargetId(target.id)
                                            remoteTargetMenuExpanded = false
                                        }
                                    )
                                }
                            }
                        }
                        if (remoteVideoTargets.isEmpty()) {
                            Text(
                                stringResource(R.string.video_recognition_no_remote_server),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            if (selectedRemoteTarget == null && selectedLocalTarget != null) {
                                Text(
                                    stringResource(R.string.video_recognition_remote_default_local),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Text(
                                stringResource(R.string.video_recognition_remote_video_help),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else {
                        var targetMenuExpanded by remember { mutableStateOf(false) }
                        val localTargetLabel = selectedLocalBundle?.let { resources.getString(it.titleRes) }
                            ?: stringResource(R.string.video_recognition_bundle_placeholder)
                        ExposedDropdownMenuBox(
                            expanded = targetMenuExpanded,
                            onExpandedChange = { if (!anySummaryRunning) targetMenuExpanded = it }
                        ) {
                            OutlinedTextField(
                                value = localTargetLabel,
                                onValueChange = {},
                                readOnly = true,
                                label = { Text(stringResource(R.string.video_recognition_local_target)) },
                                trailingIcon = {
                                    ExposedDropdownMenuDefaults.TrailingIcon(expanded = targetMenuExpanded)
                                },
                                modifier = Modifier.menuAnchor().fillMaxWidth()
                            )
                            ExposedDropdownMenu(
                                expanded = targetMenuExpanded,
                                onDismissRequest = { targetMenuExpanded = false }
                            ) {
                                localVideoTargets.forEach { target ->
                                    val bundle = installedVideoBundles.firstOrNull { it.id == target.id }
                                    DropdownMenuItem(
                                        text = {
                                            Text(bundle?.let { resources.getString(it.titleRes) } ?: target.label)
                                        },
                                        onClick = {
                                            videoRecognitionSettings.setSavedTargetId(target.id)
                                            targetMenuExpanded = false
                                        }
                                    )
                                }
                            }
                        }
                        if (installedVideoBundles.isEmpty()) {
                            Text(
                                stringResource(R.string.video_recognition_no_model),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Text(
                        stringResource(
                            R.string.video_recognition_policy,
                            videoSegmentSeconds,
                            videoMaxFrames,
                            videoMaxFps
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            stringResource(R.string.video_recognition_audio_enabled),
                            modifier = Modifier.weight(1f)
                        )
                        Switch(
                            checked = videoAudioEnabled,
                            onCheckedChange = videoRecognitionSettings::setAudioEnabled,
                            enabled = !anySummaryRunning && visualTargetAvailable
                        )
                    }
                    Text(
                        stringResource(R.string.video_recognition_audio_enabled_help),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (videoAudioEnabled && selectedVisualTarget?.supportsAudio != true) {
                        Text(
                            stringResource(R.string.video_recognition_audio_unsupported),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.tertiary
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            stringResource(R.string.video_recognition_parallel_whisper),
                            modifier = Modifier.weight(1f)
                        )
                        Switch(
                            checked = videoParallelWhisperEnabled,
                            onCheckedChange = videoRecognitionSettings::setParallelWhisperEnabled,
                            enabled = !anySummaryRunning && visualTargetAvailable && audioFallbackAvailable
                        )
                    }
                    Text(
                        stringResource(R.string.video_recognition_parallel_whisper_help),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    IntSliderWithInput(
                        value = videoSegmentSeconds,
                        onValueChange = videoRecognitionSettings::setSegmentSeconds,
                        valueRange = 5..VideoRecognitionLimits.DEFAULT_SEGMENT_SECONDS,
                        label = stringResource(R.string.video_recognition_segment_seconds)
                    )
                    IntSliderWithInput(
                        value = videoMaxFrames,
                        onValueChange = videoRecognitionSettings::setMaxFrames,
                        valueRange = 1..VideoRecognitionLimits.DEFAULT_MAX_FRAMES,
                        label = stringResource(R.string.video_recognition_max_frames)
                    )
                    SliderWithInput(
                        value = videoMaxFps,
                        onValueChange = videoRecognitionSettings::setMaxFps,
                        valueRange = 0.25f..VideoRecognitionLimits.DEFAULT_MAX_FPS,
                        label = stringResource(R.string.video_recognition_max_fps),
                        decimalPlaces = 2
                    )
                    OutlinedTextField(
                        value = videoTargetLanguage,
                        onValueChange = videoRecognitionSettings::setTargetLanguage,
                        label = { Text(stringResource(R.string.video_recognition_target_language)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = videoPrompt,
                        onValueChange = videoRecognitionSettings::setPrompt,
                        label = { Text(stringResource(R.string.video_recognition_prompt)) },
                        minLines = 3,
                        modifier = Modifier.fillMaxWidth()
                    )
                    IntInputField(
                        value = videoContextSize,
                        onValueChange = videoRecognitionSettings::setContextSize,
                        label = stringResource(R.string.video_recognition_context_size)
                    )
                    IntInputField(
                        value = videoMaxTokens,
                        onValueChange = videoRecognitionSettings::setMaxTokens,
                        label = stringResource(R.string.video_recognition_max_tokens)
                    )
                    SliderWithInput(
                        value = videoTemperature,
                        onValueChange = videoRecognitionSettings::setTemperature,
                        valueRange = 0f..2f,
                        label = stringResource(R.string.video_recognition_temperature),
                        decimalPlaces = 1
                    )
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(stringResource(R.string.video_sumup_whisper_label), fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(8.dp))
                    var whisperExpanded by remember { mutableStateOf(false) }
                    ExposedDropdownMenuBox(
                        expanded = whisperExpanded,
                        onExpandedChange = { whisperExpanded = it }
                    ) {
                        OutlinedTextField(
                            value = selectedWhisperPath?.let { File(it).name }
                                ?: stringResource(R.string.video_sumup_select_whisper),
                            onValueChange = {},
                            readOnly = true,
                            label = { Text(stringResource(R.string.video_sumup_whisper_label)) },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = whisperExpanded) },
                            modifier = Modifier.menuAnchor().fillMaxWidth()
                        )
                        ExposedDropdownMenu(
                            expanded = whisperExpanded,
                            onDismissRequest = { whisperExpanded = false }
                        ) {
                            whisperModels.forEach { model ->
                                DropdownMenuItem(
                                    text = { Text(model.filename) },
                                    onClick = {
                                        selectedWhisperPath = model.path
                                        settingsRepo.setVideoSumupWhisperModelPath(model.path)
                                        whisperExpanded = false
                                    }
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    var languageExpanded by remember { mutableStateOf(false) }
                    ExposedDropdownMenuBox(
                        expanded = languageExpanded,
                        onExpandedChange = { languageExpanded = it }
                    ) {
                        OutlinedTextField(
                            value = WhisperLanguages.languages.find { it.first == whisperLanguage }?.second
                                ?: stringResource(R.string.whisper_auto_detect),
                            onValueChange = {},
                            readOnly = true,
                            label = { Text(stringResource(R.string.workflow_language_label)) },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = languageExpanded) },
                            modifier = Modifier.menuAnchor().fillMaxWidth()
                        )
                        ExposedDropdownMenu(
                            expanded = languageExpanded,
                            onDismissRequest = { languageExpanded = false }
                        ) {
                            WhisperLanguages.languages.take(20).forEach { (code, name) ->
                                DropdownMenuItem(
                                    text = { Text(name) },
                                    onClick = {
                                        settingsRepo.setVideoSummaryWhisperLanguage(code)
                                        languageExpanded = false
                                    }
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    IntSliderWithInput(
                        value = whisperThreads,
                        onValueChange = settingsRepo::setVideoSummaryWhisperThreads,
                        valueRange = 1..16,
                        label = stringResource(R.string.label_threads)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    WhisperVadInlineControl(settingsRepo = settingsRepo)
                }
            }

            RemoteSummaryBackendEditor(
                title = stringResource(R.string.video_summary_remote_settings_title),
                backend = backend,
                onBackendChange = settingsRepo::setVideoSummaryBackend,
                ollamaUrl = ollamaUrl,
                onOllamaUrlChange = settingsRepo::setVideoSummaryOllamaUrl,
                llamaServerUrl = llamaServerUrl,
                onLlamaServerUrlChange = settingsRepo::setVideoSummaryLlamaServerUrl,
                llamaSwapUrl = llamaSwapUrl,
                onLlamaSwapUrlChange = settingsRepo::setVideoSummaryLlamaSwapUrl,
                ollamaModel = ollamaModel,
                onOllamaModelSelected = settingsRepo::setVideoSummaryOllamaModel,
                llamaSwapModel = llamaSwapModel,
                onLlamaSwapModelSelected = settingsRepo::setVideoSummaryLlamaSwapModel,
                llamaServerModelLabel = serverModelLabel,
                llamaServerContextLabel = serverContextLabel,
                llamaServerContextTokens = serverContextTokens,
                requestedContextForWarning = mergeContext,
                liteRtModelId = liteRtModelId.takeIf { it > 0L },
                onLiteRtModelSelected = settingsRepo::setVideoSummaryLiteRtModelId,
                liteRtBackend = liteRtBackend,
                onLiteRtBackendChange = settingsRepo::setVideoSummaryLiteRtBackend,
                liteRtMtpEnabled = liteRtMtpEnabled,
                onLiteRtMtpEnabledChange = settingsRepo::setVideoSummaryLiteRtMtpEnabled,
                liteRtThinkingEnabled = thinkingEnabled,
                onLiteRtThinkingEnabledChange = settingsRepo::setVideoSummaryThinkingEnabled,
                fetchMetadata = {
                    RemoteSummaryClientFactory.fromSnapshot(context, settingsRepo.videoSummarySettings.snapshot())
                        .fetchMetadata()
                },
                onMetadataLoaded = ::persistMetadata
            )

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(stringResource(R.string.workflow_step_summarize), fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = targetLanguage,
                        onValueChange = settingsRepo::setVideoSummaryTargetLanguage,
                        label = { Text(stringResource(R.string.pdf_target_language_label)) },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = videoSummaryPrompt ?: SettingsRepository.DEFAULT_TRANSCRIPT_SUMMARY_PROMPT,
                        onValueChange = settingsRepo::setVideoSummaryPrompt,
                        label = { Text(stringResource(R.string.workflow_system_prompt_label)) },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 3
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    IntInputField(
                        value = chunkContext,
                        onValueChange = settingsRepo::setVideoSummaryChunkContext,
                        label = stringResource(R.string.pdf_context_size_label)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    IntInputField(
                        value = chunkMaxTokens,
                        onValueChange = settingsRepo::setVideoSummaryChunkMaxTokens,
                        label = stringResource(R.string.pdf_max_tokens_label)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    IntInputField(
                        value = mergeContext,
                        onValueChange = settingsRepo::setVideoSummaryMergeContext,
                        label = stringResource(R.string.pdf_merge_context_label)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    IntInputField(
                        value = mergeMaxTokens,
                        onValueChange = settingsRepo::setVideoSummaryMergeMaxTokens,
                        label = stringResource(R.string.pdf_merge_max_tokens_label)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    SliderWithInput(
                        value = temperature,
                        onValueChange = settingsRepo::setVideoSummaryTemperature,
                        valueRange = SettingsRepository.PDF_TEMPERATURE_MIN..SettingsRepository.PDF_TEMPERATURE_MAX,
                        label = stringResource(R.string.pdf_temperature_label),
                        decimalPlaces = 1
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    IntSliderWithInput(
                        value = timeoutMinutes,
                        onValueChange = settingsRepo::setVideoSummaryTimeoutMinutes,
                        valueRange = SettingsRepository.PDF_TIMEOUT_MINUTES_RANGE,
                        label = stringResource(R.string.pdf_timeout_label),
                        suffix = stringResource(R.string.pdf_minutes_suffix)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    androidx.compose.foundation.layout.Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(stringResource(R.string.pdf_thinking_toggle_title))
                        Switch(
                            checked = thinkingEnabled,
                            onCheckedChange = settingsRepo::setVideoSummaryThinkingEnabled
                        )
                    }
                }
            }

            if (selectedVideoString == null) {
                Button(
                    onClick = { videoPicker.launch(arrayOf("video/*")) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .walkthroughTarget("documents.summary.input"),
                    enabled = !anySummaryRunning
                ) {
                    Icon(Icons.Default.PlayArrow, null)
                    Spacer(modifier = Modifier.size(8.dp))
                    Text(stringResource(R.string.video_sumup_select_video))
                }
            } else {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .walkthroughTarget("documents.summary.input")
                ) {
                    androidx.compose.foundation.layout.Row(modifier = Modifier.padding(16.dp)) {
                        Text(selectedVideoName ?: stringResource(R.string.video_sumup_video_placeholder), modifier = Modifier.weight(1f))
                        IconButton(
                            onClick = {
                                if (!anySummaryRunning) {
                                    VideoSummaryStateHolder.reset()
                                    VideoRecognitionSummaryService.clear()
                                }
                            },
                            enabled = !anySummaryRunning
                        ) {
                            Icon(Icons.Default.Close, stringResource(R.string.action_remove))
                        }
                    }
                }

                if (projectedChunkCount > 0) {
                    Text(
                        stringResource(R.string.video_summary_chunk_count, projectedChunkCount),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                if (isRunning) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.3f)
                        )
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(progress, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.height(8.dp))
                            LinearProgressIndicator(
                                progress = { progressFraction.coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxWidth()
                            )
                            if (totalChunks > 0) {
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    stringResource(R.string.summary_progress_chunk, currentChunk.coerceAtLeast(1), totalChunks),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                } else {
                }
            }

            if (visualState.status != VideoRecognitionSummaryStatus.IDLE &&
                visualState.status != VideoRecognitionSummaryStatus.CANCELLED
            ) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.3f)
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            visualState.message.ifBlank {
                                stringResource(R.string.video_recognition_progress_preparing)
                            },
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        if (visualState.isRunning) {
                            LinearProgressIndicator(
                                progress = { visualState.progress.coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        if (visualState.sourceDurationSeconds > 0.0) {
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                stringResource(
                                    R.string.video_recognition_processed_percentage,
                                    visualState.processedFraction * 100f,
                                    visualState.processedSeconds.toFloat(),
                                    visualState.sourceDurationSeconds.toFloat()
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (visualState.totalSegments > 0) {
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                stringResource(
                                    R.string.video_recognition_segment_count,
                                    visualState.currentSegment.coerceAtLeast(1),
                                    visualState.totalSegments
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            if (visualPartialSummaries.isNotEmpty()) {
                SummaryMarkdownCard(
                    title = stringResource(R.string.video_recognition_partial_label),
                    markdown = visualPartialSummaries.mapIndexed { index, part ->
                        "### ${resources.getString(R.string.summary_partial_item_label, visualState.partialSummaries.size - visualPartialSummaries.size + index + 1)}\n$part"
                    }.joinToString("\n\n")
                )
            }

            if (visualState.summary.isNotBlank()) {
                SummaryMarkdownCard(
                    title = stringResource(R.string.video_recognition_summary_label),
                    markdown = visualState.summary
                )
            }

            if (visualState.transcript.isNotBlank()) {
                SummaryMarkdownCard(
                    title = stringResource(R.string.video_sumup_transcript_label),
                    markdown = visualState.transcript
                )
            }

            if (visualState.status == VideoRecognitionSummaryStatus.ERROR) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            visualState.error ?: visualState.message,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (visualState.canRetry) {
                                OutlinedButton(
                                    onClick = { startSummary() },
                                    enabled = !anySummaryRunning,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text(stringResource(R.string.video_recognition_retry))
                                }
                            }
                            if (visualState.canUseAudioFallback) {
                                Button(
                                    onClick = { startSummary(forceAudioFallback = true) },
                                    enabled = !anySummaryRunning,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text(stringResource(R.string.video_recognition_use_audio_fallback))
                                }
                            }
                        }
                    }
                }
            }

            errorMessage?.let {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                ) {
                    Text(it, modifier = Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onErrorContainer)
                }
            }

            if (cancelled && !isRunning && errorMessage == null) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f)
                    )
                ) {
                    Text(
                        text = stringResource(R.string.summary_cancelled_message),
                        modifier = Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }

            if (partialSummaries.isNotEmpty()) {
                SummaryMarkdownCard(
                    title = stringResource(R.string.pdf_partial_results_title),
                    markdown = partialSummaries.mapIndexed { index, part ->
                        "### ${resources.getString(R.string.summary_partial_item_label, index + 1)}\n$part"
                    }.joinToString("\n\n")
                )
            }

            if (summary.isNotBlank()) {
                SummaryMarkdownCard(
                    title = stringResource(R.string.video_sumup_summary_label),
                    markdown = summary
                )
            }

            if (transcript.isNotBlank()) {
                SummaryMarkdownCard(
                    title = stringResource(R.string.video_sumup_transcript_label),
                    markdown = transcript
                )
            }
        }
        AppTaskActionFooter(
            modifier = Modifier
                .fillMaxWidth()
        ) {
            if (anySummaryRunning) {
                OutlinedButton(
                    onClick = {
                        VideoRecognitionSummaryService.cancel()
                        VideoSumupService.cancel()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Text(stringResource(R.string.soft_studio_cancel))
                }
            } else {
                Button(
                    onClick = ::startSummary,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = selectedVideoString != null && (visualTargetAvailable || audioFallbackAvailable)
                ) {
                    Text(
                        stringResource(
                            if (visualTargetAvailable) {
                                R.string.video_recognition_start
                            } else {
                                R.string.video_sumup_btn
                            }
                        )
                    )
                }
            }
        }
        }
    }
}
