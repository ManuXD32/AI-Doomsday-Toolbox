package com.example.llamadroid.ui.ai

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
import com.example.llamadroid.service.RemoteSummaryClientFactory
import com.example.llamadroid.service.VideoSummaryStateHolder
import com.example.llamadroid.service.VideoSumupService
import com.example.llamadroid.service.WhisperLanguages
import com.example.llamadroid.service.WhisperVadAssetStore
import com.example.llamadroid.ui.components.IntInputField
import com.example.llamadroid.ui.components.IntSliderWithInput
import com.example.llamadroid.ui.components.WhisperVadInlineControl
import com.example.llamadroid.ui.components.RemoteSummaryBackendEditor
import com.example.llamadroid.ui.components.SliderWithInput
import com.example.llamadroid.ui.components.SummaryMarkdownCard
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideoSumupScreen(navController: NavController) {
    val context = LocalContext.current
    val settingsRepo = remember { SettingsRepository(context) }
    val db = remember { AppDatabase.getDatabase(context) }

    val whisperModels by db.modelDao().getModelsByType(ModelType.WHISPER).collectAsState(initial = emptyList())
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

    val videoPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            try {
                context.contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Exception) {
            }
            if (isRunning) return@let
            VideoSummaryStateHolder.reset()
            VideoSummaryStateHolder.setSelectedSourceUri(it.toString())
            VideoSummaryStateHolder.setSelectedSourceName(it.lastPathSegment ?: context.getString(R.string.video_sumup_video_placeholder))
        }
    }

    val backendReady = when (SettingsRepository.normalizeOllamaOrLlamaBackend(backend)) {
        SettingsRepository.PDF_BACKEND_LLAMA_SERVER -> llamaServerUrl.isNotBlank()
        SettingsRepository.PDF_BACKEND_LLAMA_SWAP -> llamaSwapUrl.isNotBlank() && !llamaSwapModel.isNullOrBlank()
        else -> ollamaUrl.isNotBlank() && !ollamaModel.isNullOrBlank()
    }

    fun persistMetadata(metadata: com.example.llamadroid.service.RemoteSummaryMetadata) {
        if (SettingsRepository.isLlamaServerBackend(metadata.backend)) {
            settingsRepo.setVideoSummaryLlamaServerModelLabel(metadata.serverModelLabel)
            settingsRepo.setVideoSummaryLlamaServerContextTokens(metadata.serverContextTokens)
            settingsRepo.setVideoSummaryLlamaServerContextLabel(metadata.serverContextLabel)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.video_sumup_title)) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
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
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
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
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isRunning
                ) {
                    Icon(Icons.Default.PlayArrow, null)
                    Spacer(modifier = Modifier.size(8.dp))
                    Text(stringResource(R.string.video_sumup_select_video))
                }
            } else {
                Card(modifier = Modifier.fillMaxWidth()) {
                    androidx.compose.foundation.layout.Row(modifier = Modifier.padding(16.dp)) {
                        Text(selectedVideoName ?: stringResource(R.string.video_sumup_video_placeholder), modifier = Modifier.weight(1f))
                        IconButton(
                            onClick = { if (!isRunning) VideoSummaryStateHolder.reset() },
                            enabled = !isRunning
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
                    OutlinedButton(
                        onClick = { VideoSumupService.cancel() },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.action_cancel))
                    }
                } else {
                    Button(
                        onClick = {
                            val selectedUri = Uri.parse(selectedVideoString)
                            val videoPath = context.contentResolver.openInputStream(selectedUri)?.use { input ->
                                val tempFile = File(context.cacheDir, "temp_video.mp4")
                                tempFile.outputStream().use { output -> input.copyTo(output) }
                                tempFile.absolutePath
                            }
                            if (videoPath != null && selectedWhisperPath != null && backendReady) {
                                VideoSumupService.startSummarization(
                                    context = context,
                                    videoPath = videoPath,
                                    videoFileName = selectedVideoName ?: context.getString(R.string.video_sumup_video_placeholder),
                                    whisperModelPath = selectedWhisperPath!!,
                                    language = whisperLanguage,
                                    threads = whisperThreads,
                                    vadConfig = settingsRepo.whisperVadConfigSnapshot(),
                                    saveToNotes = true,
                                    noteType = NoteType.VIDEO_SUMMARY
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = selectedWhisperPath != null && backendReady && (!whisperVad.enabled || effectiveWhisperVadPath != null)
                    ) {
                        Text(stringResource(R.string.video_sumup_btn))
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
                        "### ${context.getString(R.string.summary_partial_item_label, index + 1)}\n$part"
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
    }
}
