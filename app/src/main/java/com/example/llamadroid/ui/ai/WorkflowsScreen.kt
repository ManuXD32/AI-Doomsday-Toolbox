package com.example.llamadroid.ui.ai

import android.Manifest
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.media.MediaRecorder
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.res.stringResource
import com.example.llamadroid.R
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.data.db.ModelEntity
import com.example.llamadroid.data.db.ModelType
import com.example.llamadroid.sd.SdComponentRole
import com.example.llamadroid.sd.isSdImageMainModel
import com.example.llamadroid.sd.matchesSdFamily
import com.example.llamadroid.sd.resolveSdFamilySpec
import com.example.llamadroid.sd.resolvedSdFamily
import com.example.llamadroid.data.RemoteSummarySettingsSnapshot
import com.example.llamadroid.data.SettingsRepository
import com.example.llamadroid.service.*
import com.example.llamadroid.ui.components.DraftIntTextField
import com.example.llamadroid.ui.components.DraftLongTextField
import com.example.llamadroid.ui.components.IntInputField
import com.example.llamadroid.ui.components.RemoteSummaryBackendEditor
import com.example.llamadroid.ui.components.SliderWithInput
import com.example.llamadroid.ui.components.IntSliderWithInput
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import com.example.llamadroid.util.UpscalerAssetPackSupport
import java.util.*
import kotlin.math.pow

/**
 * Workflows Screen - Sequential AI operations
 * State is preserved when switching between workflows
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkflowsScreen(navController: NavController) {
    val context = LocalContext.current
    val db = remember { AppDatabase.getDatabase(context) }
    val settingsRepo = remember { SettingsRepository(context) }
    val batteryGateState = rememberBatteryOptimizationGateState()
    val keepScreenAwakeDuringGeneration by settingsRepo.keepScreenAwakeDuringGeneration.collectAsState()
    val pdfTranslationJobState by PDFTranslationJobService.state.collectAsState()
    
    // Selected workflow: 0 = none, 1 = transcribe+summary, 2 = txt2img+upscale, 3 = manga translation
    val scope = rememberCoroutineScope()
    var selectedWorkflow by remember { mutableIntStateOf(0) }
    var mangaCbzUris by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var mangaIsRunning by remember { mutableStateOf(false) }
    var mangaStep by remember { mutableStateOf("") }
    var mangaProgress by remember { mutableFloatStateOf(0f) }
    var mangaResults by remember { mutableStateOf<List<MangaTranslationFileResult>>(emptyList()) }
    var mangaError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(
        pdfTranslationJobState.isRunning,
        pdfTranslationJobState.kind,
        pdfTranslationJobState.progressMessage,
        pdfTranslationJobState.progressFraction,
        pdfTranslationJobState.mangaResults,
        pdfTranslationJobState.errorMessage
    ) {
        if (pdfTranslationJobState.kind == PdfTranslationJobKind.MANGA_BATCH) {
            mangaIsRunning = pdfTranslationJobState.isRunning
            if (pdfTranslationJobState.isRunning) {
                mangaStep = pdfTranslationJobState.progressMessage
                mangaProgress = pdfTranslationJobState.progressFraction
                mangaError = null
            } else if (pdfTranslationJobState.mangaResults.isNotEmpty()) {
                mangaResults = pdfTranslationJobState.mangaResults
                mangaStep = context.getString(R.string.workflow_complete)
                mangaProgress = 1f
                mangaError = null
            } else if (pdfTranslationJobState.errorMessage != null) {
                mangaError = pdfTranslationJobState.errorMessage
                mangaStep = ""
                mangaProgress = 0f
            }
        }
    }
    
    // Asset pack check state
    var showDownloadDialog by remember { mutableStateOf(false) }
    
    if (showDownloadDialog) {
        com.example.llamadroid.ui.components.AssetDownloadDialog(
            onDismiss = { showDownloadDialog = false },
            onDownloadAll = {
                showDownloadDialog = false
                selectedWorkflow = 2
            },
            onSkip = { showDownloadDialog = false }
        )
    }
    
    // Workflow output directory
    val workflowOutputDir = remember { 
        File(context.filesDir, "sd_output/workflow").apply { mkdirs() } 
    }
    
    // ===== txt2img+Upscale workflow state (persisted across tab changes) =====
    var txt2imgModelPath by remember { mutableStateOf<String?>(null) }
    var txt2imgPrompt by remember { mutableStateOf("") }
    var txt2imgNegativePrompt by remember { mutableStateOf("") }
    var txt2imgWidth by remember { mutableIntStateOf(512) }
    var txt2imgHeight by remember { mutableIntStateOf(512) }
    var txt2imgSteps by remember { mutableIntStateOf(20) }
    var txt2imgCfgScale by remember { mutableFloatStateOf(7.0f) }
    var txt2imgSeed by remember { mutableLongStateOf(-1L) }
    var txt2imgSampler by remember { mutableStateOf(SamplingMethod.EULER_A) }
    var txt2imgThreads by remember { mutableIntStateOf(4) }
    var txt2imgVaePath by remember { mutableStateOf<String?>(null) }
    var txt2imgTaePath by remember { mutableStateOf<String?>(null) }
    var txt2imgClipLPath by remember { mutableStateOf<String?>(null) }
    var txt2imgClipGPath by remember { mutableStateOf<String?>(null) }
    var txt2imgT5xxlPath by remember { mutableStateOf<String?>(null) }
    var txt2imgLlmPath by remember { mutableStateOf<String?>(null) }
    var txt2imgLlmVisionPath by remember { mutableStateOf<String?>(null) }
    var txt2imgPhotoMakerPath by remember { mutableStateOf<String?>(null) }
    var upscalerPath by remember { mutableStateOf<String?>(null) }
    var upscaleFactor by remember { mutableIntStateOf(2) }
    var upscaleRepeats by remember { mutableIntStateOf(1) }
    var upscaleThreads by remember { mutableIntStateOf(4) }
    var txt2imgIsRunning by remember { mutableStateOf(false) }
    var txt2imgStep by remember { mutableStateOf("") }
    var txt2imgProgress by remember { mutableFloatStateOf(0f) }
    var txt2imgResultPath by remember { mutableStateOf<String?>(null) }
    var txt2imgError by remember { mutableStateOf<String?>(null) }
    
    // Observe workflow state holders at top level (persists across tab changes)
    val workflowTxt2imgState by SDModeStateHolder.workflowTxt2img.state.collectAsState()
    val workflowTxt2imgProgress by SDModeStateHolder.workflowTxt2img.progress.collectAsState()
    val workflowUpscaleState by SDModeStateHolder.workflowUpscale.state.collectAsState()
    val workflowUpscaleProgress by SDModeStateHolder.workflowUpscale.progress.collectAsState()
    val sdWorkflowGenerating =
        txt2imgIsRunning ||
            workflowTxt2imgState is SDGenerationState.Generating ||
            workflowUpscaleState is SDGenerationState.Generating
    GenerationKeepScreenAwakeEffect(
        enabled = keepScreenAwakeDuringGeneration && sdWorkflowGenerating
    )
    
    // Update txt2img workflow progress based on state holders (runs at top level, survives tab changes)
    LaunchedEffect(workflowTxt2imgState, workflowTxt2imgProgress, workflowUpscaleState, workflowUpscaleProgress) {
        if (txt2imgIsRunning) {
            when {
                workflowTxt2imgState is SDGenerationState.Generating -> {
                    txt2imgStep = context.getString(R.string.workflow_step_generating)
                    txt2imgProgress = workflowTxt2imgProgress * 0.5f  // 0-50% for txt2img
                }
                workflowUpscaleState is SDGenerationState.Generating -> {
                    txt2imgStep = context.getString(R.string.workflow_step_upscaling)
                    txt2imgProgress = 0.5f + workflowUpscaleProgress * 0.5f  // 50-100% for upscale
                }
                workflowUpscaleState is SDGenerationState.Complete -> {
                    txt2imgIsRunning = false
                    txt2imgStep = context.getString(R.string.workflow_complete)
                    txt2imgProgress = 1f
                    txt2imgResultPath = (workflowUpscaleState as SDGenerationState.Complete).outputPath
                    txt2imgError = null
                }
                workflowTxt2imgState is SDGenerationState.Error -> {
                    txt2imgIsRunning = false
                    txt2imgError = (workflowTxt2imgState as SDGenerationState.Error).message
                }
                workflowUpscaleState is SDGenerationState.Error -> {
                    txt2imgIsRunning = false
                    txt2imgError = (workflowUpscaleState as SDGenerationState.Error).message
                }
            }
        }
    }
    
    // ===== Transcribe+Summary workflow state (persisted via SettingsRepository) =====
    val persistedWhisperModel by settingsRepo.workflowWhisperModelPath.collectAsState()
    val persistedWhisperThreads by settingsRepo.workflowWhisperThreads.collectAsState()
    val persistedLanguage by settingsRepo.workflowWhisperLanguage.collectAsState()
    val persistedSummaryBackend by settingsRepo.workflowSummaryBackend.collectAsState()
    val persistedSummaryOllamaUrl by settingsRepo.workflowSummaryOllamaUrl.collectAsState()
    val persistedSummaryLlamaUrl by settingsRepo.workflowSummaryLlamaServerUrl.collectAsState()
    val persistedSummaryLlamaSwapUrl by settingsRepo.workflowSummaryLlamaSwapUrl.collectAsState()
    val persistedSummaryOllamaModel by settingsRepo.workflowSummaryOllamaModel.collectAsState()
    val persistedSummaryLlamaSwapModel by settingsRepo.workflowSummaryLlamaSwapModel.collectAsState()
    val persistedSummaryTargetLanguage by settingsRepo.workflowSummaryTargetLanguage.collectAsState()
    val persistedSummaryContext by settingsRepo.workflowContext.collectAsState()
    val persistedSummaryMaxTokens by settingsRepo.workflowMaxTokens.collectAsState()
    val persistedSummaryMergeContext by settingsRepo.workflowMergeContext.collectAsState()
    val persistedSummaryMergeMaxTokens by settingsRepo.workflowMergeMaxTokens.collectAsState()
    val persistedSummaryTemperature by settingsRepo.workflowTemperature.collectAsState()
    val persistedSummaryTimeout by settingsRepo.workflowSummaryTimeoutMinutes.collectAsState()
    val persistedSummaryThinking by settingsRepo.workflowSummaryThinkingEnabled.collectAsState()
    val persistedWorkflowSummaryPrompt by settingsRepo.workflowSummaryPrompt.collectAsState()
    val persistedWorkflowLlamaServerModelLabel by settingsRepo.workflowSummaryLlamaServerModelLabel.collectAsState()
    val persistedWorkflowLlamaServerContextLabel by settingsRepo.workflowSummaryLlamaServerContextLabel.collectAsState()
    val persistedWorkflowLlamaServerContextTokens by settingsRepo.workflowSummaryLlamaServerContextTokens.collectAsState()
    
    // ===== Transcribe+Summary workflow state (persisted via StateHolder) =====
    var whisperModelPath by remember(persistedWhisperModel) { mutableStateOf(persistedWhisperModel) }
    val audioUri by WorkflowStateHolder.audioUri.collectAsState()
    val audioPath by WorkflowStateHolder.audioPath.collectAsState()
    var summaryBackend by remember(persistedSummaryBackend) { mutableStateOf(persistedSummaryBackend) }
    var summaryOllamaUrl by remember(persistedSummaryOllamaUrl) { mutableStateOf(persistedSummaryOllamaUrl) }
    var summaryLlamaUrl by remember(persistedSummaryLlamaUrl) { mutableStateOf(persistedSummaryLlamaUrl) }
    var summaryLlamaSwapUrl by remember(persistedSummaryLlamaSwapUrl) { mutableStateOf(persistedSummaryLlamaSwapUrl) }
    var summaryOllamaModel by remember(persistedSummaryOllamaModel) { mutableStateOf(persistedSummaryOllamaModel) }
    var summaryLlamaSwapModel by remember(persistedSummaryLlamaSwapModel) { mutableStateOf(persistedSummaryLlamaSwapModel) }
    var summarySystemPrompt by remember(persistedWorkflowSummaryPrompt) {
        mutableStateOf(persistedWorkflowSummaryPrompt ?: SettingsRepository.DEFAULT_TRANSCRIPT_SUMMARY_PROMPT)
    }
    var summaryTemperature by remember(persistedSummaryTemperature) { mutableFloatStateOf(persistedSummaryTemperature) }
    var whisperThreads by remember(persistedWhisperThreads) { mutableIntStateOf(persistedWhisperThreads) }
    var whisperLanguage by remember(persistedLanguage) { mutableStateOf(persistedLanguage) }
    var summaryContext by remember(persistedSummaryContext) { mutableIntStateOf(persistedSummaryContext) }
    var summaryMaxTokens by remember(persistedSummaryMaxTokens) { mutableIntStateOf(persistedSummaryMaxTokens) }
    var summaryMergeContext by remember(persistedSummaryMergeContext) { mutableIntStateOf(persistedSummaryMergeContext) }
    var summaryMergeMaxTokens by remember(persistedSummaryMergeMaxTokens) { mutableIntStateOf(persistedSummaryMergeMaxTokens) }
    var summaryTargetLanguage by remember(persistedSummaryTargetLanguage) { mutableStateOf(persistedSummaryTargetLanguage) }
    var summaryTimeoutMinutes by remember(persistedSummaryTimeout) { mutableIntStateOf(persistedSummaryTimeout) }
    var summaryThinkingEnabled by remember(persistedSummaryThinking) { mutableStateOf(persistedSummaryThinking) }
    
    // Key progress state - persisted via StateHolder
    val transcribeIsRunning by WorkflowStateHolder.isRunning.collectAsState()
    val transcribeStep by WorkflowStateHolder.step.collectAsState()
    val transcribeProgress by WorkflowStateHolder.progress.collectAsState()
    val transcriptionText by WorkflowStateHolder.transcriptionText.collectAsState()
    val summaryText by WorkflowStateHolder.summaryText.collectAsState()
    val workflowPartialSummaries by WorkflowStateHolder.partialSummaries.collectAsState()
    val workflowCurrentChunk by WorkflowStateHolder.currentChunk.collectAsState()
    val workflowTotalChunks by WorkflowStateHolder.totalChunks.collectAsState()
    val workflowProjectedChunkCount by WorkflowStateHolder.projectedChunkCount.collectAsState()
    val workflowCancelled by WorkflowStateHolder.cancelled.collectAsState()
    val transcribeError by WorkflowStateHolder.error.collectAsState()
    
    // ===== Recording state for workflow (persisted via StateHolder) =====
    val showRecordingDialog by WorkflowStateHolder.showRecordingDialog.collectAsState()
    val isRecording by WorkflowStateHolder.isRecording.collectAsState()
    val recordingSeconds by WorkflowStateHolder.recordingSeconds.collectAsState()
    var mediaRecorder by remember { mutableStateOf<MediaRecorder?>(null) }
    var hasRecordPermission by remember { mutableStateOf(false) }
    val savedRecordingPath by WorkflowStateHolder.savedRecordingPath.collectAsState()
    
    // CRITICAL: Use rememberUpdatedState to prevent stale closure capture in LaunchedEffect
    val currentAudioUri by rememberUpdatedState(audioUri)
    val currentAudioPath by rememberUpdatedState(audioPath)
    val currentSavedRecordingPath by rememberUpdatedState(savedRecordingPath)
    val currentWhisperLanguage by rememberUpdatedState(whisperLanguage)

    LaunchedEffect(transcribeIsRunning, audioUri, transcriptionText, summaryText) {
        if (transcribeIsRunning || audioUri != null || transcriptionText.isNotBlank() || summaryText.isNotBlank()) {
            selectedWorkflow = 1
        }
    }
    
    // Permission launcher for recording
    val recordPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasRecordPermission = granted
        if (granted) {
            WorkflowStateHolder.setShowRecordingDialog(true)
        } else {
            WorkflowStateHolder.setError(context.getString(R.string.workflow_error_perm_denied))
        }
    }

    val mangaCbzPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            uris.forEach { uri ->
                try {
                    context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } catch (_: Exception) {
                }
            }
            mangaCbzUris = uris
            selectedWorkflow = 3
        }
    }
    
    // Recording timer
    LaunchedEffect(isRecording) {
        if (isRecording) {
            WorkflowStateHolder.setRecordingSeconds(0)
            while (isRecording) {
                kotlinx.coroutines.delay(1000)
                WorkflowStateHolder.setRecordingSeconds(recordingSeconds + 1)
            }
        }
    }
    
    // Save settings when they change
    LaunchedEffect(whisperModelPath) { settingsRepo.setWorkflowWhisperModelPath(whisperModelPath) }
    LaunchedEffect(whisperThreads) { settingsRepo.setWorkflowWhisperThreads(whisperThreads) }
    LaunchedEffect(whisperLanguage) { settingsRepo.setWorkflowWhisperLanguage(whisperLanguage) }
    LaunchedEffect(summaryBackend) { settingsRepo.setWorkflowSummaryBackend(summaryBackend) }
    LaunchedEffect(summaryOllamaUrl) { settingsRepo.setWorkflowSummaryOllamaUrl(summaryOllamaUrl) }
    LaunchedEffect(summaryLlamaUrl) { settingsRepo.setWorkflowSummaryLlamaServerUrl(summaryLlamaUrl) }
    LaunchedEffect(summaryLlamaSwapUrl) { settingsRepo.setWorkflowSummaryLlamaSwapUrl(summaryLlamaSwapUrl) }
    LaunchedEffect(summaryOllamaModel) { settingsRepo.setWorkflowSummaryOllamaModel(summaryOllamaModel) }
    LaunchedEffect(summaryLlamaSwapModel) { settingsRepo.setWorkflowSummaryLlamaSwapModel(summaryLlamaSwapModel) }
    LaunchedEffect(summaryContext) { settingsRepo.setWorkflowContext(summaryContext) }
    LaunchedEffect(summaryMaxTokens) { settingsRepo.setWorkflowMaxTokens(summaryMaxTokens) }
    LaunchedEffect(summaryMergeContext) { settingsRepo.setWorkflowMergeContext(summaryMergeContext) }
    LaunchedEffect(summaryMergeMaxTokens) { settingsRepo.setWorkflowMergeMaxTokens(summaryMergeMaxTokens) }
    LaunchedEffect(summaryTemperature) { settingsRepo.setWorkflowTemperature(summaryTemperature) }
    LaunchedEffect(summaryTargetLanguage) { settingsRepo.setWorkflowSummaryTargetLanguage(summaryTargetLanguage) }
    LaunchedEffect(summaryTimeoutMinutes) { settingsRepo.setWorkflowSummaryTimeoutMinutes(summaryTimeoutMinutes) }
    LaunchedEffect(summaryThinkingEnabled) { settingsRepo.setWorkflowSummaryThinkingEnabled(summaryThinkingEnabled) }
    LaunchedEffect(summarySystemPrompt) { settingsRepo.setWorkflowSummaryPrompt(summarySystemPrompt) }
    
    // ===== Consume shared audio/video files =====
    LaunchedEffect(Unit) {
        val pendingFile = com.example.llamadroid.data.SharedFileHolder.consumePendingFile()
        if (pendingFile != null && pendingFile.targetScreen == "workflows") {
            val mimeType = pendingFile.mimeType
            if (mimeType.startsWith("audio/") || mimeType.startsWith("video/")) {
                // Copy to cache for native access
                try {
                    val inputStream = context.contentResolver.openInputStream(pendingFile.uri)
                    val extension = if (mimeType.startsWith("video/")) "mp4" else "audio"
                    val tempFile = File(context.cacheDir, "workflow_shared_${System.currentTimeMillis()}.$extension")
                    inputStream?.use { input -> tempFile.outputStream().use { output -> input.copyTo(output) } }
                    WorkflowStateHolder.setAudioUri(pendingFile.uri)
                    WorkflowStateHolder.setAudioPath(tempFile.absolutePath)
                    selectedWorkflow = 1  // Auto-select transcribe workflow
                } catch (e: Exception) {
                    android.util.Log.e("WorkflowsScreen", "Failed to load shared file: ${e.message}")
                }
            }
        }
    }
    
    // Observe VideoSumupService for transcribe workflow results
    val videoSumupState by VideoSumupService.state.collectAsState()
    val videoSumupProgress by VideoSumupService.progress.collectAsState()
    val videoSumupResult by VideoSumupService.result.collectAsState()
    
    // Handle VideoSumupService results
    LaunchedEffect(videoSumupState) {
        when (videoSumupState) {
            is VideoSumupState.ExtractingAudio -> {
                WorkflowStateHolder.setStep(context.getString(R.string.workflow_step_extracting))
                WorkflowStateHolder.setProgress(0.1f)
            }
            is VideoSumupState.Transcribing -> {
                WorkflowStateHolder.setStep(context.getString(R.string.workflow_step_transcribing))
                WorkflowStateHolder.setProgress(0.4f)
            }
            is VideoSumupState.Summarizing -> {
                WorkflowStateHolder.setStep(context.getString(R.string.workflow_step_summarizing))
                WorkflowStateHolder.setProgress(0.7f)
            }
            is VideoSumupState.Idle -> {
                if (transcribeIsRunning && transcribeProgress > 0f) {
                    // Don't reset if just started
                }
            }
            is VideoSumupState.Error -> {
                WorkflowStateHolder.setIsRunning(false)
                WorkflowStateHolder.setError((videoSumupState as VideoSumupState.Error).message)
            }
        }
    }
    
    LaunchedEffect(videoSumupResult) {
        videoSumupResult?.fold(
            onSuccess = { result ->
                WorkflowStateHolder.onWorkflowComplete(result.transcript, result.summary)
                // Note saving is now handled by VideoSumupService with saveToNotes=true
                android.util.Log.d("WorkflowsScreen", "Workflow complete - note saved by service")
                
                VideoSumupService.clearResult()
            },
            onFailure = { e ->
                WorkflowStateHolder.setIsRunning(false)
                if (e.message != "Cancelled") {
                    WorkflowStateHolder.setError(e.message)
                }
                VideoSumupService.clearResult()
            }
        )
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
            IconButton(onClick = { 
                if (selectedWorkflow == 0) navController.popBackStack()
                else selectedWorkflow = 0
            }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back))
            }
            Text(
                when (selectedWorkflow) {
                    1 -> stringResource(R.string.workflow_transcribe_summary)
                    2 -> stringResource(R.string.workflow_txt2img_upscale)
                    3 -> stringResource(R.string.workflow_manga_translation)
                    else -> stringResource(R.string.workflow_title)
                },
                style = MaterialTheme.typography.headlineSmall.copy(
                    fontWeight = FontWeight.Bold
                )
            )
        }
        
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            // Show running workflow indicator (visible on all tabs)
            if (transcribeIsRunning || txt2imgIsRunning) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { 
                            // Switch to the running workflow
                            if (transcribeIsRunning) selectedWorkflow = 1
                            else if (txt2imgIsRunning) selectedWorkflow = 2
                        },
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                if (transcribeIsRunning) stringResource(R.string.workflow_running_transcribe) 
                                else stringResource(R.string.workflow_running_txt2img),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                if (transcribeIsRunning) transcribeStep else txt2imgStep,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                            )
                        }
                        Text(
                            "${((if (transcribeIsRunning) transcribeProgress else txt2imgProgress) * 100).toInt()}%",
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
            }
            
            when (selectedWorkflow) {
                0 -> {
                    Text(
                        stringResource(R.string.workflow_subtitle),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    WorkflowCard(
                        emoji = "🎙️→📝",
                        title = "Transcribe + Summary",
                        description = "Transcribe audio/video, then summarize with LLM",
                        gradientColors = listOf(
                            Color(0xFF00BCD4).copy(alpha = 0.15f),
                            Color(0xFF4CAF50).copy(alpha = 0.3f)
                        ),
                        onClick = { 
                            // Binaries are now in base, no need to download
                            selectedWorkflow = 1 
                        }
                    )
                    
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    WorkflowCard(
                        emoji = "🎨→⬆️",
                        title = "txt2img + Upscale",
                        description = "Generate image, then upscale it",
                        gradientColors = listOf(
                            Color(0xFF2196F3).copy(alpha = 0.15f),
                            Color(0xFF9C27B0).copy(alpha = 0.3f)
                        ),
                        onClick = { 
                            if (UpscalerAssetPackSupport.areModelsReady(context)) {
                                selectedWorkflow = 2 
                            } else {
                                showDownloadDialog = true
                            }
                        }
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    WorkflowCard(
                        emoji = "📚→🌐",
                        title = stringResource(R.string.workflow_manga_translation),
                        description = stringResource(R.string.workflow_manga_translation_desc),
                        gradientColors = listOf(
                            Color(0xFFE91E63).copy(alpha = 0.15f),
                            Color(0xFF3F51B5).copy(alpha = 0.3f)
                        ),
                        onClick = { selectedWorkflow = 3 }
                    )
                }
                
                1 -> {
                    TranscribeSummaryWorkflowContent(
                        db = db,
                        settingsRepo = settingsRepo,
                        whisperModelPath = whisperModelPath,
                        onWhisperModelChange = { whisperModelPath = it },
                        audioUri = audioUri,
                        onAudioUriChange = { WorkflowStateHolder.setAudioUri(it) },
                        audioPath = audioPath,
                        onAudioPathChange = { WorkflowStateHolder.setAudioPath(it) },
                        summaryBackend = summaryBackend,
                        onSummaryBackendChange = { summaryBackend = it },
                        summaryOllamaUrl = summaryOllamaUrl,
                        onSummaryOllamaUrlChange = { summaryOllamaUrl = it },
                        summaryLlamaUrl = summaryLlamaUrl,
                        onSummaryLlamaUrlChange = { summaryLlamaUrl = it },
                        summaryLlamaSwapUrl = summaryLlamaSwapUrl,
                        onSummaryLlamaSwapUrlChange = { summaryLlamaSwapUrl = it },
                        summaryOllamaModel = summaryOllamaModel,
                        onSummaryOllamaModelChange = { summaryOllamaModel = it },
                        summaryLlamaSwapModel = summaryLlamaSwapModel,
                        onSummaryLlamaSwapModelChange = { summaryLlamaSwapModel = it },
                        summaryLlamaServerModelLabel = persistedWorkflowLlamaServerModelLabel,
                        summaryLlamaServerContextLabel = persistedWorkflowLlamaServerContextLabel,
                        summaryLlamaServerContextTokens = persistedWorkflowLlamaServerContextTokens,
                        summaryTargetLanguage = summaryTargetLanguage,
                        onSummaryTargetLanguageChange = { summaryTargetLanguage = it },
                        systemPrompt = summarySystemPrompt,
                        onSystemPromptChange = { summarySystemPrompt = it },
                        temperature = summaryTemperature,
                        onTemperatureChange = { summaryTemperature = it },
                        whisperThreads = whisperThreads,
                        onWhisperThreadsChange = { whisperThreads = it },
                        whisperLanguage = whisperLanguage,
                        onWhisperLanguageChange = { whisperLanguage = it },
                        contextSize = summaryContext,
                        onContextChange = { summaryContext = it },
                        maxTokens = summaryMaxTokens,
                        onMaxTokensChange = { summaryMaxTokens = it },
                        mergeContext = summaryMergeContext,
                        onMergeContextChange = { summaryMergeContext = it },
                        mergeMaxTokens = summaryMergeMaxTokens,
                        onMergeMaxTokensChange = { summaryMergeMaxTokens = it },
                        timeoutMinutes = summaryTimeoutMinutes,
                        onTimeoutMinutesChange = { summaryTimeoutMinutes = it },
                        thinkingEnabled = summaryThinkingEnabled,
                        onThinkingEnabledChange = { summaryThinkingEnabled = it },
                        isRunning = transcribeIsRunning,
                        currentStep = transcribeStep,
                        progress = transcribeProgress,
                        transcriptionText = transcriptionText,
                        summaryText = summaryText,
                        partialSummaries = workflowPartialSummaries,
                        currentChunk = workflowCurrentChunk,
                        totalChunks = workflowTotalChunks,
                        projectedChunkCount = workflowProjectedChunkCount,
                        cancelled = workflowCancelled,
                        errorMessage = transcribeError,
                        onRun = {
                            val backendReady = when (SettingsRepository.normalizeOllamaOrLlamaBackend(summaryBackend)) {
                                SettingsRepository.PDF_BACKEND_LLAMA_SERVER -> summaryLlamaUrl.isNotBlank()
                                SettingsRepository.PDF_BACKEND_LLAMA_SWAP -> summaryLlamaSwapUrl.isNotBlank() && !summaryLlamaSwapModel.isNullOrBlank()
                                else -> summaryOllamaUrl.isNotBlank() && !summaryOllamaModel.isNullOrBlank()
                            }
                            if (audioPath != null && whisperModelPath != null && backendReady) {
                                WorkflowStateHolder.setIsRunning(true)
                                WorkflowStateHolder.setError(null)
                                WorkflowStateHolder.setStep(context.getString(R.string.workflow_step_starting))
                                WorkflowStateHolder.setProgress(0f)
                                VideoSumupService.startSummarization(
                                    context = context,
                                    videoPath = audioPath!!,
                                    videoFileName = audioUri?.lastPathSegment ?: context.getString(R.string.workflow_audio_video_placeholder),
                                    whisperModelPath = whisperModelPath!!,
                                    language = whisperLanguage,
                                    threads = whisperThreads,
                                    saveToNotes = true,  // Service handles note saving now
                                    noteType = com.example.llamadroid.data.db.NoteType.WORKFLOW,
                                    audioSourcePath = savedRecordingPath ?: audioPath  // Use saved recording if available
                                )
                            }
                        },
                        onComplete = { transcript, summary ->
                            WorkflowStateHolder.onWorkflowComplete(transcript, summary)
                        },
                        onError = { error ->
                            WorkflowStateHolder.setIsRunning(false)
                            WorkflowStateHolder.setError(error)
                        },
                        onCancel = {
                            VideoSumupService.cancel()
                            WorkflowStateHolder.setIsRunning(false)
                            WorkflowStateHolder.setStep("")
                            WorkflowStateHolder.setProgress(0f)
                        },
                        onRecord = {
                            if (hasRecordPermission) {
                                WorkflowStateHolder.setShowRecordingDialog(true)
                            } else {
                                recordPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        }
                    )
                }
                
                2 -> {
                    Txt2ImgUpscaleWorkflowContent(
                        db = db,
                        batteryGateState = batteryGateState,
                        outputDir = workflowOutputDir,
                        modelPath = txt2imgModelPath,
                        onModelChange = { txt2imgModelPath = it },
                        prompt = txt2imgPrompt,
                        onPromptChange = { txt2imgPrompt = it },
                        negativePrompt = txt2imgNegativePrompt,
                        onNegativePromptChange = { txt2imgNegativePrompt = it },
                        width = txt2imgWidth,
                        onWidthChange = { txt2imgWidth = it },
                        height = txt2imgHeight,
                        onHeightChange = { txt2imgHeight = it },
                        steps = txt2imgSteps,
                        onStepsChange = { txt2imgSteps = it },
                        cfgScale = txt2imgCfgScale,
                        onCfgScaleChange = { txt2imgCfgScale = it },
                        seed = txt2imgSeed,
                        onSeedChange = { txt2imgSeed = it },
                        sampler = txt2imgSampler,
                        onSamplerChange = { txt2imgSampler = it },
                        threads = txt2imgThreads,
                        onThreadsChange = { txt2imgThreads = it },
                        vaePath = txt2imgVaePath,
                        onVaeChange = { txt2imgVaePath = it },
                        taePath = txt2imgTaePath,
                        onTaeChange = { txt2imgTaePath = it },
                        clipLPath = txt2imgClipLPath,
                        onClipLChange = { txt2imgClipLPath = it },
                        clipGPath = txt2imgClipGPath,
                        onClipGChange = { txt2imgClipGPath = it },
                        t5xxlPath = txt2imgT5xxlPath,
                        onT5xxlChange = { txt2imgT5xxlPath = it },
                        llmPath = txt2imgLlmPath,
                        onLlmChange = { txt2imgLlmPath = it },
                        llmVisionPath = txt2imgLlmVisionPath,
                        onLlmVisionChange = { txt2imgLlmVisionPath = it },
                        photoMakerPath = txt2imgPhotoMakerPath,
                        onPhotoMakerChange = { txt2imgPhotoMakerPath = it },
                        upscalerPath = upscalerPath,
                        onUpscalerChange = { upscalerPath = it },
                        upscaleFactor = upscaleFactor,
                        onUpscaleFactorChange = { upscaleFactor = it },
                        upscaleRepeats = upscaleRepeats,
                        onUpscaleRepeatsChange = { upscaleRepeats = it },
                        upscaleThreads = upscaleThreads,
                        onUpscaleThreadsChange = { upscaleThreads = it },
                        isRunning = txt2imgIsRunning,
                        currentStep = txt2imgStep,
                        progress = txt2imgProgress,
                        resultPath = txt2imgResultPath,
                        errorMessage = txt2imgError,
                        onRunningChange = { txt2imgIsRunning = it },
                        onStepChange = { txt2imgStep = it },
                        onProgressChange = { txt2imgProgress = it },
                        onResultChange = { txt2imgResultPath = it },
                        onErrorChange = { txt2imgError = it },
                        onCancel = {
                            context.startService(StableDiffusionService.createCancelWorkflowIntent(context))
                            SDModeStateHolder.workflowTxt2img.reset()
                            SDModeStateHolder.workflowUpscale.reset()
                            txt2imgIsRunning = false
                            txt2imgStep = ""
                            txt2imgProgress = 0f
                        }
                    )
                }

                3 -> {
                    MangaTranslationWorkflowContent(
                        db = db,
                        selectedUris = mangaCbzUris,
                        isRunning = mangaIsRunning,
                        currentStep = mangaStep,
                        progress = mangaProgress,
                        results = mangaResults,
                        errorMessage = mangaError,
                        onPickFiles = {
                            mangaCbzPicker.launch(
                                arrayOf(
                                    "application/vnd.comicbook+zip",
                                    "application/zip",
                                    "application/octet-stream",
                                    "*/*"
                                )
                            )
                        },
                        onOpenSettings = { navController.navigate("settings_pdf_translation") },
                        onRun = {
                            if (mangaCbzUris.isEmpty()) {
                                mangaError = context.getString(R.string.workflow_manga_select_first)
                            } else {
                                mangaIsRunning = true
                                mangaError = null
                                mangaResults = emptyList()
                                mangaStep = context.getString(R.string.workflow_step_starting)
                                mangaProgress = 0f
                                if (!PDFTranslationJobService.startMangaCbzBatchTranslation(context, mangaCbzUris)) {
                                    mangaIsRunning = false
                                    mangaError = context.getString(R.string.pdf_translation_already_running)
                                    mangaStep = ""
                                }
                            }
                        }
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))
        }
    }

    BatteryOptimizationWarningDialog(state = batteryGateState)
    
    // Recording Dialog
    if (showRecordingDialog) {
        // Use stable file name for recording (timestamp added when saving to output folder)
        val recordingFile = remember { File(context.cacheDir, "workflow_recording.m4a") }
        
        AlertDialog(
            onDismissRequest = {
                if (isRecording) {
                    try { mediaRecorder?.stop(); mediaRecorder?.release() } catch (e: Exception) {}
                    mediaRecorder = null
                    WorkflowStateHolder.setIsRecording(false)
                }
                WorkflowStateHolder.setShowRecordingDialog(false)
            },
            title = { Text(stringResource(R.string.workflow_recording_title), fontWeight = FontWeight.Bold) },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    val minutes = recordingSeconds / 60
                    val seconds = recordingSeconds % 60
                    Text(
                        String.format("%02d:%02d", minutes, seconds),
                        style = MaterialTheme.typography.headlineLarge,
                        fontWeight = FontWeight.Bold,
                        color = if (isRecording) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    if (isRecording) {
                        Text(stringResource(R.string.workflow_recording_status), color = MaterialTheme.colorScheme.error)
                    } else if (recordingSeconds > 0) {
                        Text(stringResource(R.string.workflow_recording_saved), color = MaterialTheme.colorScheme.primary)
                    } else {
                        Text(stringResource(R.string.workflow_recording_hint))
                    }
                }
            },
            confirmButton = {
                if (!isRecording && recordingSeconds > 0) {
                    TextButton(onClick = {
                        // Save recording to Recordings folder
                        try {
                            val recordingsDir = File(context.filesDir, "sd_output/Recordings").apply { mkdirs() }
                            val timestamp = SimpleDateFormat("yyyy-MM-dd_HHmmss", Locale.getDefault()).format(Date())
                            val savedFile = File(recordingsDir, "recording_$timestamp.m4a")
                            recordingFile.copyTo(savedFile, overwrite = true)
                            WorkflowStateHolder.setSavedRecordingPath(savedFile.absolutePath)
                            recordingFile.delete()
                            
                            // Use recording as audio source
                            WorkflowStateHolder.setAudioUri(Uri.fromFile(savedFile))
                            WorkflowStateHolder.setAudioPath(savedFile.absolutePath)
                            
                            WorkflowStateHolder.setShowRecordingDialog(false)
                            WorkflowStateHolder.setRecordingSeconds(0)
                        } catch (e: Exception) {
                            WorkflowStateHolder.setError("Failed to save recording: ${e.message}")
                        }
                    }) { Text(stringResource(R.string.workflow_use_recording)) }
                } else if (!isRecording) {
                    TextButton(onClick = {
                        try {
                            @Suppress("DEPRECATION")
                            val recorder = MediaRecorder().apply {
                                setAudioSource(MediaRecorder.AudioSource.MIC)
                                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                                setAudioSamplingRate(44100)
                                setAudioEncodingBitRate(128000)
                                setOutputFile(recordingFile.absolutePath)
                                prepare()
                                start()
                            }
                            mediaRecorder = recorder
                            WorkflowStateHolder.setIsRecording(true)
                        } catch (e: Exception) {
                            WorkflowStateHolder.setError("Failed to start recording: ${e.message}")
                            WorkflowStateHolder.setShowRecordingDialog(false)
                        }
                    }) { Text(stringResource(R.string.action_start)) }
                } else {
                    TextButton(onClick = {
                        try { mediaRecorder?.stop(); mediaRecorder?.release(); mediaRecorder = null; WorkflowStateHolder.setIsRecording(false) } 
                        catch (e: Exception) { WorkflowStateHolder.setError("Failed to stop recording: ${e.message}") }
                    }) { Text(stringResource(R.string.action_stop)) }
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    if (isRecording) {
                        try { mediaRecorder?.stop(); mediaRecorder?.release() } catch (e: Exception) {}
                        mediaRecorder = null
                        WorkflowStateHolder.setIsRecording(false)
                    }
                    WorkflowStateHolder.setRecordingSeconds(0)
                    WorkflowStateHolder.setShowRecordingDialog(false)
                }) { Text(stringResource(R.string.action_cancel)) }
            }
        )
    }
}

@Composable
private fun MangaTranslationWorkflowContent(
    db: AppDatabase,
    selectedUris: List<Uri>,
    isRunning: Boolean,
    currentStep: String,
    progress: Float,
    results: List<MangaTranslationFileResult>,
    errorMessage: String?,
    onPickFiles: () -> Unit,
    onOpenSettings: () -> Unit,
    onRun: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val templates by db.workflowTemplateDao()
        .getByType(com.example.llamadroid.data.db.WorkflowType.MANGA_TRANSLATION)
        .collectAsState(initial = emptyList())
    var templateName by remember { mutableStateOf("") }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    stringResource(R.string.workflow_manga_translation),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    stringResource(R.string.workflow_manga_translation_help),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Button(onClick = onPickFiles, modifier = Modifier.fillMaxWidth(), enabled = !isRunning) {
                    Icon(Icons.Default.FolderOpen, null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.workflow_manga_select_cbz))
                }
                OutlinedButton(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth(), enabled = !isRunning) {
                    Icon(Icons.Default.Settings, null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.pdf_translation_settings_title))
                }
                if (selectedUris.isNotEmpty()) {
                    Text(
                        stringResource(R.string.workflow_manga_selected_count, selectedUris.size),
                        style = MaterialTheme.typography.labelLarge
                    )
                    selectedUris.take(6).forEach { uri ->
                        Text(
                            uri.lastPathSegment?.substringAfterLast('/') ?: uri.toString(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
                        )
                    }
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    stringResource(R.string.workflow_manga_presets_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                if (templates.isEmpty()) {
                    Text(
                        stringResource(R.string.workflow_no_templates),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    templates.take(4).forEach { template ->
                        AssistChip(
                            onClick = { },
                            label = { Text(template.name, maxLines = 1) }
                        )
                    }
                }
                OutlinedTextField(
                    value = templateName,
                    onValueChange = { templateName = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.workflow_template_name)) },
                    singleLine = true
                )
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            db.workflowTemplateDao().insert(
                                com.example.llamadroid.data.db.WorkflowTemplateEntity(
                                    name = templateName.ifBlank { "Manga Translation" },
                                    type = com.example.llamadroid.data.db.WorkflowType.MANGA_TRANSLATION,
                                    configJson = """{"workflow":"manga_translation","version":1}"""
                                )
                            )
                            templateName = ""
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = templateName.isNotBlank()
                ) {
                    Text(stringResource(R.string.workflow_save_template))
                }
            }
        }

        if (isRunning) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    LinearProgressIndicator(progress = progress, modifier = Modifier.fillMaxWidth())
                    Text(currentStep, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        errorMessage?.let {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Text(
                    it,
                    modifier = Modifier.padding(16.dp),
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        }

        if (results.isNotEmpty()) {
            Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        stringResource(R.string.workflow_manga_results_title),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    results.forEach { result ->
                        Text(
                            if (result.isSuccess) {
                                stringResource(R.string.workflow_manga_result_success, result.sourceName)
                            } else {
                                stringResource(R.string.workflow_manga_result_failed, result.sourceName, result.errorMessage.orEmpty())
                            },
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }

        Button(
            onClick = onRun,
            enabled = !isRunning && selectedUris.isNotEmpty(),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (isRunning) stringResource(R.string.workflow_running_btn) else stringResource(R.string.workflow_manga_run_batch))
        }
    }
}

@Composable
private fun WorkflowCard(
    emoji: String,
    title: String,
    description: String,
    gradientColors: List<Color>,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(brush = Brush.horizontalGradient(gradientColors))
                .padding(20.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(emoji, fontSize = 36.sp)
                Spacer(modifier = Modifier.width(16.dp))
                Column {
                    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(modifier = Modifier.weight(1f))
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

/**
 * txt2img + Upscale Workflow Content (with all options)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Txt2ImgUpscaleWorkflowContent(
    db: AppDatabase,
    batteryGateState: BatteryOptimizationGateState,
    outputDir: File,
    modelPath: String?,
    onModelChange: (String?) -> Unit,
    prompt: String,
    onPromptChange: (String) -> Unit,
    negativePrompt: String,
    onNegativePromptChange: (String) -> Unit,
    width: Int,
    onWidthChange: (Int) -> Unit,
    height: Int,
    onHeightChange: (Int) -> Unit,
    steps: Int,
    onStepsChange: (Int) -> Unit,
    cfgScale: Float,
    onCfgScaleChange: (Float) -> Unit,
    seed: Long,
    onSeedChange: (Long) -> Unit,
    sampler: SamplingMethod,
    onSamplerChange: (SamplingMethod) -> Unit,
    threads: Int,
    onThreadsChange: (Int) -> Unit,
    vaePath: String?,
    onVaeChange: (String?) -> Unit,
    taePath: String?,
    onTaeChange: (String?) -> Unit,
    clipLPath: String?,
    onClipLChange: (String?) -> Unit,
    clipGPath: String?,
    onClipGChange: (String?) -> Unit,
    t5xxlPath: String?,
    onT5xxlChange: (String?) -> Unit,
    llmPath: String?,
    onLlmChange: (String?) -> Unit,
    llmVisionPath: String?,
    onLlmVisionChange: (String?) -> Unit,
    photoMakerPath: String?,
    onPhotoMakerChange: (String?) -> Unit,
    upscalerPath: String?,
    onUpscalerChange: (String?) -> Unit,
    upscaleFactor: Int,
    onUpscaleFactorChange: (Int) -> Unit,
    upscaleRepeats: Int,
    onUpscaleRepeatsChange: (Int) -> Unit,
    upscaleThreads: Int,
    onUpscaleThreadsChange: (Int) -> Unit,
    isRunning: Boolean,
    currentStep: String,
    progress: Float,
    resultPath: String?,
    errorMessage: String?,
    onRunningChange: (Boolean) -> Unit,
    onStepChange: (String) -> Unit,
    onProgressChange: (Float) -> Unit,
    onResultChange: (String?) -> Unit,
    onErrorChange: (String?) -> Unit,
    onCancel: () -> Unit
) {
    val context = LocalContext.current
    
    // Models
    val sdCheckpoints by db.modelDao().getModelsByType(ModelType.SD_CHECKPOINT).collectAsState(initial = emptyList())
    val fluxDiffusionModels by db.modelDao().getModelsByType(ModelType.SD_DIFFUSION).collectAsState(initial = emptyList())
    val sdClipLModels by db.modelDao().getModelsByType(ModelType.SD_CLIP_L).collectAsState(initial = emptyList())
    val sdClipGModels by db.modelDao().getModelsByType(ModelType.SD_CLIP_G).collectAsState(initial = emptyList())
    val sdT5xxlModels by db.modelDao().getModelsByType(ModelType.SD_T5XXL).collectAsState(initial = emptyList())
    val sdTaeModels by db.modelDao().getModelsByType(ModelType.SD_TAE).collectAsState(initial = emptyList())
    val sdVaeModels by db.modelDao().getModelsByType(ModelType.SD_VAE).collectAsState(initial = emptyList())
    val sdPhotoMakerModels by db.modelDao().getModelsByType(ModelType.SD_PHOTOMAKER).collectAsState(initial = emptyList())
    val sdImageSupportModels by db.modelDao()
        .getModelsByTypes(listOf(ModelType.LLM, ModelType.VISION_PROJECTOR))
        .collectAsState(initial = emptyList())
    val upscalerModels by db.modelDao().getModelsByType(ModelType.SD_UPSCALER).collectAsState(initial = emptyList())
    val allGenerationModels = (sdCheckpoints + fluxDiffusionModels).filter { model ->
        model.isSdImageMainModel()
    }
    val selectedGenerationModel = allGenerationModels.firstOrNull { it.path == modelPath }
    val selectedGenerationFamily = selectedGenerationModel?.resolvedSdFamily()
    val selectedGenerationFamilyEnum = selectedGenerationFamily?.first
    val selectedGenerationVariant = selectedGenerationFamily?.second
    val selectedGenerationSpec = remember(selectedGenerationFamilyEnum, selectedGenerationVariant) {
        selectedGenerationFamilyEnum?.let { resolveSdFamilySpec(it, selectedGenerationVariant) }
    }
    val componentRoles = remember(selectedGenerationSpec) {
        selectedGenerationSpec?.let { spec ->
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
    }
    val compatibleVaeModels = remember(sdVaeModels, selectedGenerationFamilyEnum, selectedGenerationVariant) {
        filterWorkflowSdComponents(sdVaeModels, selectedGenerationFamilyEnum, selectedGenerationVariant)
    }
    val compatibleTaeModels = remember(sdTaeModels, selectedGenerationFamilyEnum, selectedGenerationVariant) {
        filterWorkflowSdComponents(sdTaeModels, selectedGenerationFamilyEnum, selectedGenerationVariant)
    }
    val compatibleClipLModels = remember(sdClipLModels, selectedGenerationFamilyEnum, selectedGenerationVariant) {
        filterWorkflowSdComponents(sdClipLModels, selectedGenerationFamilyEnum, selectedGenerationVariant)
    }
    val compatibleClipGModels = remember(sdClipGModels, selectedGenerationFamilyEnum, selectedGenerationVariant) {
        filterWorkflowSdComponents(sdClipGModels, selectedGenerationFamilyEnum, selectedGenerationVariant)
    }
    val compatibleT5xxlModels = remember(sdT5xxlModels, selectedGenerationFamilyEnum, selectedGenerationVariant) {
        filterWorkflowSdComponents(sdT5xxlModels, selectedGenerationFamilyEnum, selectedGenerationVariant)
    }
    val compatibleLlmModels = remember(sdImageSupportModels, selectedGenerationFamilyEnum, selectedGenerationVariant) {
        filterWorkflowSdComponents(
            sdImageSupportModels.filter { it.type == ModelType.LLM },
            selectedGenerationFamilyEnum,
            selectedGenerationVariant
        )
    }
    val compatibleLlmVisionModels = remember(sdImageSupportModels, selectedGenerationFamilyEnum, selectedGenerationVariant) {
        filterWorkflowSdComponents(
            sdImageSupportModels.filter { it.type == ModelType.VISION_PROJECTOR },
            selectedGenerationFamilyEnum,
            selectedGenerationVariant
        )
    }
    val compatiblePhotoMakerModels = remember(sdPhotoMakerModels, selectedGenerationFamilyEnum, selectedGenerationVariant) {
        filterWorkflowSdComponents(sdPhotoMakerModels, selectedGenerationFamilyEnum, selectedGenerationVariant)
    }
    val missingRequiredComponents = remember(
        selectedGenerationSpec,
        vaePath,
        taePath,
        clipLPath,
        clipGPath,
        t5xxlPath,
        llmPath,
        llmVisionPath,
        photoMakerPath
    ) {
        selectedGenerationSpec?.requiredRoles?.filter { role ->
            when (role) {
                SdComponentRole.VAE -> vaePath.isNullOrBlank()
                SdComponentRole.TAE -> taePath.isNullOrBlank()
                SdComponentRole.CLIP_L -> clipLPath.isNullOrBlank()
                SdComponentRole.CLIP_G -> clipGPath.isNullOrBlank()
                SdComponentRole.T5XXL -> t5xxlPath.isNullOrBlank()
                SdComponentRole.LLM -> llmPath.isNullOrBlank()
                SdComponentRole.LLM_VISION -> llmVisionPath.isNullOrBlank()
                SdComponentRole.PHOTOMAKER -> photoMakerPath.isNullOrBlank()
                else -> false
            }
        } ?: emptyList()
    }
    
    // Calculate final resolution
    val finalFactor = upscaleFactor.toDouble().pow(upscaleRepeats.toDouble()).toInt()
    val finalWidth = width * finalFactor
    val finalHeight = height * finalFactor
    
    val runWorkflow: () -> Unit = workflow@{
        if (missingRequiredComponents.isNotEmpty()) {
            onErrorChange(
                context.getString(
                    R.string.imagegen_error_missing_required_components,
                    missingRequiredComponents.joinToString(", ") { workflowComponentRoleLabel(context, it) }
                )
            )
            return@workflow
        }
        if (modelPath != null && upscalerPath != null && prompt.isNotBlank()) {
            onRunningChange(true)
            onErrorChange(null)
            onStepChange(context.getString(R.string.workflow_step_generating))
            onProgressChange(0f)
            
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val txt2imgFile = File(outputDir, "txt2img_$timestamp.png")
            val upscaledFile = File(outputDir, "upscaled_$timestamp.png")
            
            val txt2imgConfig = SDConfig(
                mode = SDMode.TXT2IMG,
                modelPath = modelPath,
                prompt = prompt,
                negativePrompt = negativePrompt,
                width = width,
                height = height,
                steps = steps,
                cfgScale = cfgScale,
                seed = seed,
                samplingMethod = sampler,
                outputPath = txt2imgFile.absolutePath,
                threads = threads,
                isFluxModel = selectedGenerationModel?.type == ModelType.SD_DIFFUSION,
                modelFamily = selectedGenerationFamilyEnum?.storedValue,
                modelVariant = selectedGenerationVariant,
                vaePath = vaePath,
                taePath = taePath,
                clipLPath = clipLPath,
                clipGPath = clipGPath,
                t5xxlPath = t5xxlPath,
                llmPath = llmPath,
                llmVisionPath = llmVisionPath,
                photoMakerPath = photoMakerPath
            )

            val upscaleConfig = SDConfig(
                mode = SDMode.UPSCALE,
                modelPath = upscalerPath,
                prompt = "",
                outputPath = upscaledFile.absolutePath,
                initImage = txt2imgFile.absolutePath,
                upscaleModel = upscalerPath,
                upscaleRepeats = upscaleRepeats,
                threads = upscaleThreads
            )

            val workflowConfig = SDWorkflowConfig(
                txt2imgConfig = txt2imgConfig,
                upscaleConfig = upscaleConfig
            )

            batteryGateState.runAfterCheck {
                context.startForegroundService(
                    StableDiffusionService.createStartWorkflowIntent(context, workflowConfig)
                )
            }
        }
    }
    
    val scope = rememberCoroutineScope()
    
    Column {
        // ===== Template Save/Load for Txt2Img =====
        var showTemplateMenu by remember { mutableStateOf(false) }
        var showSaveDialog by remember { mutableStateOf(false) }
        var showEditDialog by remember { mutableStateOf(false) }
        var templateName by remember { mutableStateOf("") }
        var editingTemplate by remember { mutableStateOf<com.example.llamadroid.data.db.WorkflowTemplateEntity?>(null) }
        val templates by db.workflowTemplateDao().getByType(com.example.llamadroid.data.db.WorkflowType.TXT2IMG_UPSCALE).collectAsState(initial = emptyList())
        
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box {
                OutlinedButton(onClick = { showTemplateMenu = true }) {
                    Icon(Icons.Default.Settings, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.workflow_templates_btn, templates.size))
                }
                DropdownMenu(expanded = showTemplateMenu, onDismissRequest = { showTemplateMenu = false }) {
                    if (templates.isEmpty()) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.workflow_no_templates), color = MaterialTheme.colorScheme.onSurfaceVariant) }, onClick = {})
                    } else {
                        templates.forEach { template ->
                            DropdownMenuItem(
                                text = { 
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = if (template.name.isNotBlank()) template.name else "Template #${template.id}",
                                            modifier = Modifier.weight(1f)
                                        )
                                    }
                                },
                                onClick = {
                                    try {
                                        val config = org.json.JSONObject(template.configJson)
                                        onModelChange(config.optString("model").takeIf { it.isNotEmpty() })
                                        onPromptChange(config.optString("prompt", ""))
                                        onNegativePromptChange(config.optString("negativePrompt", ""))
                                        onWidthChange(config.optInt("width", 512))
                                        onHeightChange(config.optInt("height", 512))
                                        onStepsChange(config.optInt("steps", 20))
                                        onCfgScaleChange(config.optDouble("cfgScale", 7.0).toFloat())
                                        onSamplerChange(SamplingMethod.entries.find { it.name == config.optString("sampler") } ?: SamplingMethod.EULER_A)
                                        onThreadsChange(config.optInt("threads", 4))
                                        onVaeChange(config.optString("vae").takeIf { it.isNotEmpty() })
                                        onTaeChange(config.optString("tae").takeIf { it.isNotEmpty() })
                                        onClipLChange(config.optString("clipL").takeIf { it.isNotEmpty() })
                                        onClipGChange(config.optString("clipG").takeIf { it.isNotEmpty() })
                                        onT5xxlChange(config.optString("t5xxl").takeIf { it.isNotEmpty() })
                                        onLlmChange(config.optString("llm").takeIf { it.isNotEmpty() })
                                        onLlmVisionChange(config.optString("llmVision").takeIf { it.isNotEmpty() })
                                        onPhotoMakerChange(config.optString("photoMaker").takeIf { it.isNotEmpty() })
                                        onUpscalerChange(config.optString("upscaler").takeIf { it.isNotEmpty() })
                                        onUpscaleFactorChange(config.optInt("upscaleFactor", 2))
                                        onUpscaleRepeatsChange(config.optInt("upscaleRepeats", 1))
                                        onUpscaleThreadsChange(config.optInt("upscaleThreads", 4))
                                    } catch (e: Exception) {}
                                    showTemplateMenu = false
                                },
                                trailingIcon = {
                                    Row {
                                        IconButton(onClick = {
                                            editingTemplate = template
                                            templateName = template.name
                                            showEditDialog = true
                                            showTemplateMenu = false
                                        }, modifier = Modifier.size(32.dp)) { 
                                            Icon(Icons.Default.Edit, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp)) 
                                        }
                                        IconButton(onClick = {
                                            scope.launch { db.workflowTemplateDao().delete(template) }
                                        }, modifier = Modifier.size(32.dp)) { 
                                            Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp)) 
                                        }
                                    }
                                }
                            )
                        }
                    }
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.workflow_save_current_template), fontWeight = FontWeight.Bold) },
                        onClick = { showTemplateMenu = false; templateName = ""; showSaveDialog = true },
                        leadingIcon = { Icon(Icons.Default.Add, null) }
                    )
                }
            }
        }
        
        // Edit template dialog
        // Edit template dialog
        if (showEditDialog) {
            val currentEditing = editingTemplate
            if (currentEditing != null) {
                var editName by remember(currentEditing) { mutableStateOf(currentEditing.name) }
                
                AlertDialog(
                    onDismissRequest = { showEditDialog = false; editingTemplate = null },
                    title = { Text(stringResource(R.string.workflow_edit_template)) },
                    text = {
                        OutlinedTextField(
                            value = editName,
                            onValueChange = { editName = it },
                            label = { Text(stringResource(R.string.workflow_template_name)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            if (editName.isNotBlank()) {
                                scope.launch { db.workflowTemplateDao().insert(currentEditing.copy(name = editName)) }
                                showEditDialog = false
                                editingTemplate = null
                            }
                        }) { Text(stringResource(R.string.action_save)) }
                    },
                    dismissButton = { TextButton(onClick = { showEditDialog = false; editingTemplate = null }) { Text(stringResource(R.string.action_cancel)) } }
                )
            } else {
                showEditDialog = false
            }
        }
        
        // Save template dialog
        // Save template dialog
        if (showSaveDialog) {
            var saveName by remember { mutableStateOf("") }
            
            AlertDialog(
                onDismissRequest = { showSaveDialog = false },
                title = { Text(stringResource(R.string.workflow_save_template)) },
                text = {
                    OutlinedTextField(
                        value = saveName,
                        onValueChange = { saveName = it },
                        label = { Text("Template Name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        if (saveName.isNotBlank()) {
                            val nameToSave = saveName  // Capture before async
                            scope.launch {
                                val configJson = org.json.JSONObject().apply {
                                    put("model", modelPath ?: "")
                                    put("prompt", prompt)
                                    put("negativePrompt", negativePrompt)
                                    put("width", width)
                                    put("height", height)
                                    put("steps", steps)
                                    put("cfgScale", cfgScale.toDouble())
                                    put("sampler", sampler.name)
                                    put("threads", threads)
                                    put("vae", vaePath ?: "")
                                    put("tae", taePath ?: "")
                                    put("clipL", clipLPath ?: "")
                                    put("clipG", clipGPath ?: "")
                                    put("t5xxl", t5xxlPath ?: "")
                                    put("llm", llmPath ?: "")
                                    put("llmVision", llmVisionPath ?: "")
                                    put("photoMaker", photoMakerPath ?: "")
                                    put("upscaler", upscalerPath ?: "")
                                    put("upscaleFactor", upscaleFactor)
                                    put("upscaleRepeats", upscaleRepeats)
                                    put("upscaleThreads", upscaleThreads)
                                }.toString()
                                
                                db.workflowTemplateDao().insert(
                                    com.example.llamadroid.data.db.WorkflowTemplateEntity(
                                        name = nameToSave,
                                        type = com.example.llamadroid.data.db.WorkflowType.TXT2IMG_UPSCALE,
                                        configJson = configJson
                                    )
                                )
                            }
                            showSaveDialog = false
                        }
                    }) { Text("Save") }
                },
                dismissButton = { TextButton(onClick = { showSaveDialog = false }) { Text("Cancel") } }
            )
        }
        
        // Step 1: txt2img Settings
        Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(stringResource(R.string.workflow_step_gen_img), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(12.dp))
                
                // Model selector
                var modelExpanded by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(expanded = modelExpanded, onExpandedChange = { modelExpanded = it }) {
                    OutlinedTextField(
                        value = modelPath?.substringAfterLast("/") ?: stringResource(R.string.workflow_select_model),
                        onValueChange = {},
                        readOnly = true,
                        label = { Text(stringResource(R.string.workflow_sd_model_label)) },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(modelExpanded) },
                        modifier = Modifier.fillMaxWidth().menuAnchor()
                    )
                    ExposedDropdownMenu(expanded = modelExpanded, onDismissRequest = { modelExpanded = false }) {
                        allGenerationModels.forEach { model ->
                            DropdownMenuItem(
                                text = { Text(model.filename) },
                                onClick = {
                                    onModelChange(model.path)
                                    onVaeChange(null)
                                    onTaeChange(null)
                                    onClipLChange(null)
                                    onClipGChange(null)
                                    onT5xxlChange(null)
                                    onLlmChange(null)
                                    onLlmVisionChange(null)
                                    onPhotoMakerChange(null)
                                    modelExpanded = false
                                }
                            )
                        }
                    }
                }
                
                if (componentRoles.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                        )
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                stringResource(R.string.imagegen_components_title),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                stringResource(
                                    R.string.imagegen_components_desc,
                                    selectedGenerationFamilyEnum?.storedValue ?: ""
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (missingRequiredComponents.isNotEmpty()) {
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = stringResource(
                                        R.string.imagegen_error_missing_required_components,
                                        missingRequiredComponents.joinToString(", ") {
                                            workflowComponentRoleLabel(context, it)
                                        }
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                            componentRoles.forEach { role ->
                                Spacer(modifier = Modifier.height(10.dp))
                                WorkflowSdComponentPickerField(
                                    label = workflowComponentRoleLabel(role),
                                    models = when (role) {
                                        SdComponentRole.VAE -> compatibleVaeModels
                                        SdComponentRole.TAE -> compatibleTaeModels
                                        SdComponentRole.CLIP_L -> compatibleClipLModels
                                        SdComponentRole.CLIP_G -> compatibleClipGModels
                                        SdComponentRole.T5XXL -> compatibleT5xxlModels
                                        SdComponentRole.LLM -> compatibleLlmModels
                                        SdComponentRole.LLM_VISION -> compatibleLlmVisionModels
                                        SdComponentRole.PHOTOMAKER -> compatiblePhotoMakerModels
                                        else -> emptyList()
                                    },
                                    selectedPath = when (role) {
                                        SdComponentRole.VAE -> vaePath
                                        SdComponentRole.TAE -> taePath
                                        SdComponentRole.CLIP_L -> clipLPath
                                        SdComponentRole.CLIP_G -> clipGPath
                                        SdComponentRole.T5XXL -> t5xxlPath
                                        SdComponentRole.LLM -> llmPath
                                        SdComponentRole.LLM_VISION -> llmVisionPath
                                        SdComponentRole.PHOTOMAKER -> photoMakerPath
                                        else -> null
                                    },
                                    onSelectionChange = { path ->
                                        when (role) {
                                            SdComponentRole.VAE -> onVaeChange(path)
                                            SdComponentRole.TAE -> onTaeChange(path)
                                            SdComponentRole.CLIP_L -> onClipLChange(path)
                                            SdComponentRole.CLIP_G -> onClipGChange(path)
                                            SdComponentRole.T5XXL -> onT5xxlChange(path)
                                            SdComponentRole.LLM -> onLlmChange(path)
                                            SdComponentRole.LLM_VISION -> onLlmVisionChange(path)
                                            SdComponentRole.PHOTOMAKER -> onPhotoMakerChange(path)
                                            else -> Unit
                                        }
                                    },
                                    allowNone = role !in selectedGenerationSpec?.requiredRoles.orEmpty(),
                                    emptyMessage = stringResource(workflowEmptyMessageRes(role))
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
                
                // Prompt
                OutlinedTextField(
                    value = prompt,
                    onValueChange = onPromptChange,
                    label = { Text(stringResource(R.string.workflow_prompt_label)) },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                // Negative prompt
                OutlinedTextField(
                    value = negativePrompt,
                    onValueChange = onNegativePromptChange,
                    label = { Text(stringResource(R.string.workflow_negative_prompt_label)) },
                    modifier = Modifier.fillMaxWidth()
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                // Dimensions
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DraftIntTextField(
                        value = width,
                        onValueChange = onWidthChange,
                        label = { Text(stringResource(R.string.workflow_width_label)) },
                        modifier = Modifier.weight(1f)
                    )
                    DraftIntTextField(
                        value = height,
                        onValueChange = onHeightChange,
                        label = { Text(stringResource(R.string.workflow_height_label)) },
                        modifier = Modifier.weight(1f)
                    )
                }
                
                Spacer(modifier = Modifier.height(12.dp))
                
                // Steps slider
                IntSliderWithInput(
                    value = steps,
                    onValueChange = onStepsChange,
                    valueRange = 1..50,
                    label = stringResource(R.string.workflow_steps_label)
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                // CFG Scale slider
                SliderWithInput(
                    value = cfgScale,
                    onValueChange = onCfgScaleChange,
                    valueRange = 1f..20f,
                    label = stringResource(R.string.workflow_cfg_label),
                    decimalPlaces = 1
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                // Threads slider
                IntSliderWithInput(
                    value = threads,
                    onValueChange = onThreadsChange,
                    valueRange = 1..16,
                    label = stringResource(R.string.workflow_threads_label)
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                // Sampler
                var samplerExpanded by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(expanded = samplerExpanded, onExpandedChange = { samplerExpanded = it }) {
                    OutlinedTextField(
                        value = sampler.cliName,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text(stringResource(R.string.workflow_sampler_label)) },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(samplerExpanded) },
                        modifier = Modifier.fillMaxWidth().menuAnchor()
                    )
                    ExposedDropdownMenu(expanded = samplerExpanded, onDismissRequest = { samplerExpanded = false }) {
                        SamplingMethod.entries.forEach { s ->
                            DropdownMenuItem(text = { Text(s.cliName) }, onClick = { onSamplerChange(s); samplerExpanded = false })
                        }
                    }
                }
                
                Spacer(modifier = Modifier.height(8.dp))
                
                // Seed
                Row(verticalAlignment = Alignment.CenterVertically) {
                    DraftLongTextField(
                        value = seed,
                        onValueChange = onSeedChange,
                        blankValue = -1L,
                        modifier = Modifier.weight(1f),
                        label = { Text(stringResource(R.string.workflow_seed_label)) }
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    IconButton(onClick = { onSeedChange((0..Int.MAX_VALUE).random().toLong()) }) {
                        Icon(Icons.Default.Refresh, "Random")
                    }
                }
            }
        }
        
        Spacer(modifier = Modifier.height(12.dp))
        
        // Step 2: Upscale Settings
        Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(stringResource(R.string.workflow_step_upscale), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(12.dp))
                
                // Upscaler selector
                var upscalerExpanded by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(expanded = upscalerExpanded, onExpandedChange = { upscalerExpanded = it }) {
                    OutlinedTextField(
                        value = upscalerPath?.substringAfterLast("/") ?: stringResource(R.string.workflow_select_upscaler),
                        onValueChange = {},
                        readOnly = true,
                        label = { Text(stringResource(R.string.workflow_upscaler_model_label)) },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(upscalerExpanded) },
                        modifier = Modifier.fillMaxWidth().menuAnchor()
                    )
                    ExposedDropdownMenu(expanded = upscalerExpanded, onDismissRequest = { upscalerExpanded = false }) {
                        upscalerModels.forEach { model ->
                            DropdownMenuItem(
                                text = { Text(model.filename) },
                                onClick = {
                                    onUpscalerChange(model.path)
                                    Regex("x(\\d)").find(model.filename.lowercase())?.groupValues?.get(1)?.toIntOrNull()?.let(onUpscaleFactorChange)
                                    upscalerExpanded = false
                                }
                            )
                        }
                    }
                }
                
                Spacer(modifier = Modifier.height(8.dp))
                
                // Repeats slider
                IntSliderWithInput(
                    value = upscaleRepeats,
                    onValueChange = onUpscaleRepeatsChange,
                    valueRange = 1..4,
                    label = stringResource(R.string.workflow_repeats_label),
                    steps = 2
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                // Upscale threads
                IntSliderWithInput(
                    value = upscaleThreads,
                    onValueChange = onUpscaleThreadsChange,
                    valueRange = 1..16,
                    label = stringResource(R.string.workflow_upscale_threads_label)
                )
                
                Spacer(modifier = Modifier.height(12.dp))
                
                // Final resolution preview
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f))) {
                    Row(modifier = Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(stringResource(R.string.workflow_final_res_label), fontWeight = FontWeight.Bold)
                        Text("${finalWidth} × ${finalHeight}", color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // Progress / Error / Result
        if (isRunning) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(currentStep, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(8.dp))
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                }
            }
        }
        
        errorMessage?.let { error ->
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Text(error, modifier = Modifier.padding(12.dp), color = MaterialTheme.colorScheme.onErrorContainer)
            }
        }
        
        resultPath?.let { path ->
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(stringResource(R.string.workflow_complete), fontWeight = FontWeight.Bold)
                            Text(stringResource(R.string.workflow_saved_to, path.substringAfterLast("/")), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    // Show result image with badge
                    val bitmap = remember(path) { BitmapFactory.decodeFile(path)?.asImageBitmap() }
                    bitmap?.let {
                        Box {
                            Image(
                                bitmap = it,
                                contentDescription = null,
                                modifier = Modifier.fillMaxWidth().aspectRatio(it.width.toFloat() / it.height),
                                contentScale = ContentScale.Fit
                            )
                            // Workflow badge
                            Surface(
                                modifier = Modifier.align(Alignment.TopStart).padding(8.dp),
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.primary
                            ) {
                                Text(stringResource(R.string.workflow_badge), modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp), 
                                     color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // Run and Cancel buttons
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val canRun =
                modelPath != null &&
                    upscalerPath != null &&
                    prompt.isNotBlank() &&
                    missingRequiredComponents.isEmpty() &&
                    !isRunning
            Button(
                onClick = runWorkflow, 
                enabled = canRun, 
                modifier = Modifier.weight(1f)
            ) {
                if (isRunning) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(if (isRunning) stringResource(R.string.workflow_running_btn) else stringResource(R.string.workflow_run_btn))
            }
            
            if (isRunning) {
                OutlinedButton(
                    onClick = onCancel,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Icon(Icons.Default.Close, null)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(stringResource(R.string.action_cancel))
                }
            }
        }
    }
}

private fun filterWorkflowSdComponents(
    models: List<ModelEntity>,
    family: com.example.llamadroid.sd.SdModelFamily?,
    variant: String?
): List<ModelEntity> {
    if (family == null) return emptyList()
    return models.filter { it.matchesSdFamily(family, variant) }
}

private fun workflowComponentRoleLabelRes(role: SdComponentRole): Int = when (role) {
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

private fun workflowEmptyMessageRes(role: SdComponentRole): Int = when (role) {
    SdComponentRole.VAE -> R.string.imagegen_no_vae_installed
    SdComponentRole.TAE -> R.string.imagegen_no_tae_installed
    SdComponentRole.CLIP_L -> R.string.imagegen_no_clip_l
    SdComponentRole.CLIP_G -> R.string.imagegen_no_clip_g
    SdComponentRole.T5XXL -> R.string.imagegen_no_t5xxl
    SdComponentRole.LLM -> R.string.imagegen_no_llm
    SdComponentRole.LLM_VISION -> R.string.imagegen_no_llm_vision
    SdComponentRole.PHOTOMAKER -> R.string.imagegen_no_photomaker
    SdComponentRole.CONTROLNET -> R.string.imagegen_no_controlnet
    SdComponentRole.LORA -> R.string.imagegen_no_lora
    else -> R.string.imagegen_no_models_installed
}

private fun workflowComponentRoleLabel(context: Context, role: SdComponentRole): String =
    context.getString(workflowComponentRoleLabelRes(role))

@Composable
private fun workflowComponentRoleLabel(role: SdComponentRole): String =
    stringResource(workflowComponentRoleLabelRes(role))

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WorkflowSdComponentPickerField(
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
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) }
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

/**
 * Transcribe + Summary Workflow Content
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TranscribeSummaryWorkflowContent(
    db: AppDatabase,
    settingsRepo: SettingsRepository,
    whisperModelPath: String?,
    onWhisperModelChange: (String?) -> Unit,
    audioUri: Uri?,
    onAudioUriChange: (Uri?) -> Unit,
    audioPath: String?,
    onAudioPathChange: (String?) -> Unit,
    summaryBackend: String,
    onSummaryBackendChange: (String) -> Unit,
    summaryOllamaUrl: String,
    onSummaryOllamaUrlChange: (String) -> Unit,
    summaryLlamaUrl: String,
    onSummaryLlamaUrlChange: (String) -> Unit,
    summaryLlamaSwapUrl: String,
    onSummaryLlamaSwapUrlChange: (String) -> Unit,
    summaryOllamaModel: String?,
    onSummaryOllamaModelChange: (String?) -> Unit,
    summaryLlamaSwapModel: String?,
    onSummaryLlamaSwapModelChange: (String?) -> Unit,
    summaryLlamaServerModelLabel: String?,
    summaryLlamaServerContextLabel: String?,
    summaryLlamaServerContextTokens: Int,
    summaryTargetLanguage: String,
    onSummaryTargetLanguageChange: (String) -> Unit,
    systemPrompt: String,
    onSystemPromptChange: (String) -> Unit,
    temperature: Float,
    onTemperatureChange: (Float) -> Unit,
    whisperThreads: Int,
    onWhisperThreadsChange: (Int) -> Unit,
    whisperLanguage: String,
    onWhisperLanguageChange: (String) -> Unit,
    contextSize: Int,
    onContextChange: (Int) -> Unit,
    maxTokens: Int,
    onMaxTokensChange: (Int) -> Unit,
    mergeContext: Int,
    onMergeContextChange: (Int) -> Unit,
    mergeMaxTokens: Int,
    onMergeMaxTokensChange: (Int) -> Unit,
    timeoutMinutes: Int,
    onTimeoutMinutesChange: (Int) -> Unit,
    thinkingEnabled: Boolean,
    onThinkingEnabledChange: (Boolean) -> Unit,
    isRunning: Boolean,
    currentStep: String,
    progress: Float,
    transcriptionText: String,
    summaryText: String,
    partialSummaries: List<String>,
    currentChunk: Int,
    totalChunks: Int,
    projectedChunkCount: Int,
    cancelled: Boolean,
    errorMessage: String?,
    onRun: () -> Unit,
    onComplete: (String, String) -> Unit,
    onError: (String) -> Unit,
    onCancel: () -> Unit,
    onRecord: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    
    val whisperModels by db.modelDao().getModelsByType(ModelType.WHISPER).collectAsState(initial = emptyList())
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { 
            onAudioUriChange(it)
            // Copy to cache for native access
            try {
                val inputStream = context.contentResolver.openInputStream(it)
                // Determine file extension from MIME type
                val mimeType = context.contentResolver.getType(it)
                val extension = when {
                    mimeType?.contains("mp3") == true -> "mp3"
                    mimeType?.contains("wav") == true -> "wav"
                    mimeType?.contains("mp4") == true -> "mp4"
                    mimeType?.contains("m4a") == true -> "m4a"
                    mimeType?.contains("ogg") == true -> "ogg"
                    mimeType?.contains("flac") == true -> "flac"
                    else -> "audio"
                }
                val tempFile = File(context.cacheDir, "workflow_audio_${System.currentTimeMillis()}.$extension")
                inputStream?.use { input -> tempFile.outputStream().use { output -> input.copyTo(output) } }
                onAudioPathChange(tempFile.absolutePath)
                android.util.Log.d("WorkflowsScreen", "File picked: ${uri.lastPathSegment}, saved to: ${tempFile.absolutePath}")
            } catch (e: Exception) {
                onError(context.getString(R.string.workflow_error_load_audio, e.message ?: context.getString(R.string.error_generic)))
            }
        }
    }
    
    Column {
        // ===== Template Save/Load =====
        var showTemplateMenu by remember { mutableStateOf(false) }
        var showSaveDialog by remember { mutableStateOf(false) }
        var showEditDialog by remember { mutableStateOf(false) }
        var templateName by remember { mutableStateOf("") }
        var editingTemplate by remember { mutableStateOf<com.example.llamadroid.data.db.WorkflowTemplateEntity?>(null) }
        val templates by db.workflowTemplateDao().getByType(com.example.llamadroid.data.db.WorkflowType.TRANSCRIBE_SUMMARY).collectAsState(initial = emptyList())
        
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Load template dropdown
            Box {
                OutlinedButton(onClick = { showTemplateMenu = true }) {
                    Icon(Icons.Default.Settings, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.workflow_templates_btn, templates.size))
                }
                DropdownMenu(expanded = showTemplateMenu, onDismissRequest = { showTemplateMenu = false }) {
                    if (templates.isEmpty()) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.workflow_no_templates), color = MaterialTheme.colorScheme.onSurfaceVariant) }, onClick = {})
                    } else {
                        templates.forEach { template ->
                            DropdownMenuItem(
                                text = { 
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = if (template.name.isNotBlank()) template.name else stringResource(R.string.workflow_template_fallback, template.id),
                                            modifier = Modifier.weight(1f)
                                        )
                                    }
                                },
                                onClick = {
                                    // Load template config
                                    try {
                                        val config = org.json.JSONObject(template.configJson)
                                        onWhisperModelChange(config.optString("whisperModel").takeIf { it.isNotEmpty() })
                                        onWhisperLanguageChange(config.optString("language", "auto"))
                                        onWhisperThreadsChange(config.optInt("whisperThreads", 4))
                                        onSummaryBackendChange(config.optString("summaryBackend", SettingsRepository.PDF_BACKEND_OLLAMA))
                                        onSummaryOllamaUrlChange(config.optString("summaryOllamaUrl", summaryOllamaUrl))
                                        onSummaryLlamaUrlChange(config.optString("summaryLlamaUrl", summaryLlamaUrl))
                                        onSummaryLlamaSwapUrlChange(config.optString("summaryLlamaSwapUrl", summaryLlamaSwapUrl))
                                        onSummaryOllamaModelChange(config.optString("summaryOllamaModel").takeIf { it.isNotEmpty() })
                                        onSummaryLlamaSwapModelChange(config.optString("summaryLlamaSwapModel").takeIf { it.isNotEmpty() })
                                        onSummaryTargetLanguageChange(config.optString("summaryTargetLanguage", summaryTargetLanguage))
                                        onTemperatureChange(config.optDouble("temperature", 0.7).toFloat())
                                        onContextChange(config.optInt("contextSize", 2048))
                                        onMaxTokensChange(config.optInt("maxTokens", maxTokens))
                                        onMergeContextChange(config.optInt("mergeContext", mergeContext))
                                        onMergeMaxTokensChange(config.optInt("mergeMaxTokens", mergeMaxTokens))
                                        onTimeoutMinutesChange(config.optInt("timeoutMinutes", timeoutMinutes))
                                        onThinkingEnabledChange(config.optBoolean("thinkingEnabled", thinkingEnabled))
                                    } catch (e: Exception) {}
                                    showTemplateMenu = false
                                },
                                trailingIcon = {
                                    Row {
                                        IconButton(onClick = {
                                            editingTemplate = template
                                            templateName = template.name
                                            showEditDialog = true
                                            showTemplateMenu = false
                                        }, modifier = Modifier.size(32.dp)) { 
                                            Icon(Icons.Default.Edit, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp)) 
                                        }
                                        IconButton(onClick = {
                                            scope.launch { db.workflowTemplateDao().delete(template) }
                                        }, modifier = Modifier.size(32.dp)) { 
                                            Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp)) 
                                        }
                                    }
                                }
                            )
                        }
                    }
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.workflow_save_current_template), fontWeight = FontWeight.Bold) },
                        onClick = { showTemplateMenu = false; templateName = ""; showSaveDialog = true },
                        leadingIcon = { Icon(Icons.Default.Add, null) }
                    )
                }
            }
        }
        
        // Edit template dialog
        if (showEditDialog) {
            val currentEditing = editingTemplate
            if (currentEditing != null) {
                var editName by remember(currentEditing) { mutableStateOf(currentEditing.name) }
                
                AlertDialog(
                    onDismissRequest = { showEditDialog = false; editingTemplate = null },
                    title = { Text(stringResource(R.string.workflow_edit_template)) },
                    text = {
                        OutlinedTextField(
                            value = editName,
                            onValueChange = { editName = it },
                            label = { Text(stringResource(R.string.workflow_template_name)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            if (editName.isNotBlank()) {
                                scope.launch {
                                    db.workflowTemplateDao().insert(
                                        currentEditing.copy(name = editName)
                                    )
                                }
                                showEditDialog = false
                                editingTemplate = null
                            }
                        }) { Text(stringResource(R.string.action_save)) }
                    },
                    dismissButton = { TextButton(onClick = { showEditDialog = false; editingTemplate = null }) { Text(stringResource(R.string.action_cancel)) } }
                )
            } else {
                showEditDialog = false
            }
        }
        
        // Save template dialog
        if (showSaveDialog) {
            var saveName by remember { mutableStateOf("") }
            
            AlertDialog(
                onDismissRequest = { showSaveDialog = false },
                title = { Text(stringResource(R.string.workflow_save_template)) },
                text = {
                    OutlinedTextField(
                        value = saveName,
                        onValueChange = { saveName = it },
                        label = { Text(stringResource(R.string.workflow_template_name)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        if (saveName.isNotBlank()) {
                            val nameToSave = saveName  // Capture value before async
                            scope.launch {
                                val configJson = org.json.JSONObject().apply {
                                    put("whisperModel", whisperModelPath ?: "")
                                    put("language", whisperLanguage)
                                    put("whisperThreads", whisperThreads)
                                    put("summaryBackend", summaryBackend)
                                    put("summaryOllamaUrl", summaryOllamaUrl)
                                    put("summaryLlamaUrl", summaryLlamaUrl)
                                    put("summaryLlamaSwapUrl", summaryLlamaSwapUrl)
                                    put("summaryOllamaModel", summaryOllamaModel ?: "")
                                    put("summaryLlamaSwapModel", summaryLlamaSwapModel ?: "")
                                    put("summaryTargetLanguage", summaryTargetLanguage)
                                    put("temperature", temperature.toDouble())
                                    put("contextSize", contextSize)
                                    put("maxTokens", maxTokens)
                                    put("mergeContext", mergeContext)
                                    put("mergeMaxTokens", mergeMaxTokens)
                                    put("timeoutMinutes", timeoutMinutes)
                                    put("thinkingEnabled", thinkingEnabled)
                                }.toString()
                                
                                db.workflowTemplateDao().insert(
                                    com.example.llamadroid.data.db.WorkflowTemplateEntity(
                                        name = nameToSave,
                                        type = com.example.llamadroid.data.db.WorkflowType.TRANSCRIBE_SUMMARY,
                                        configJson = configJson
                                    )
                                )
                            }
                            showSaveDialog = false
                        }
                    }) { Text(stringResource(R.string.action_save)) }
                },
                dismissButton = { TextButton(onClick = { showSaveDialog = false }) { Text(stringResource(R.string.action_cancel)) } }
            )
        }
        
        // Step 1: Transcription Settings
        Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(stringResource(R.string.workflow_step_transcribe), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(12.dp))
                
                // Whisper model
                var whisperExpanded by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(expanded = whisperExpanded, onExpandedChange = { whisperExpanded = it }) {
                    OutlinedTextField(
                        value = whisperModelPath?.substringAfterLast("/") ?: stringResource(R.string.workflow_select_whisper),
                        onValueChange = {},
                        readOnly = true,
                        label = { Text(stringResource(R.string.workflow_whisper_model_label)) },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(whisperExpanded) },
                        modifier = Modifier.fillMaxWidth().menuAnchor()
                    )
                    ExposedDropdownMenu(expanded = whisperExpanded, onDismissRequest = { whisperExpanded = false }) {
                        whisperModels.forEach { model ->
                            DropdownMenuItem(text = { Text(model.filename) }, onClick = { onWhisperModelChange(model.path); whisperExpanded = false })
                        }
                    }
                }
                
                Spacer(modifier = Modifier.height(8.dp))
                
                // Language selector
                var languageExpanded by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(expanded = languageExpanded, onExpandedChange = { languageExpanded = it }) {
                    OutlinedTextField(
                        value = WhisperLanguages.languages.find { it.first == whisperLanguage }?.second ?: stringResource(R.string.whisper_auto_detect),
                        onValueChange = {},
                        readOnly = true,
                        label = { Text(stringResource(R.string.workflow_language_label)) },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(languageExpanded) },
                        modifier = Modifier.fillMaxWidth().menuAnchor()
                    )
                    ExposedDropdownMenu(expanded = languageExpanded, onDismissRequest = { languageExpanded = false }) {
                        WhisperLanguages.languages.take(20).forEach { (code, name) ->
                            DropdownMenuItem(text = { Text(name) }, onClick = { onWhisperLanguageChange(code); languageExpanded = false })
                        }
                    }
                }
                
                Spacer(modifier = Modifier.height(8.dp))
                
                // Whisper Threads
                IntSliderWithInput(
                    value = whisperThreads,
                    onValueChange = onWhisperThreadsChange,
                    valueRange = 1..16,
                    label = stringResource(R.string.label_threads)
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                // Audio file picker OR Record
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { filePicker.launch("audio/*") },
                        modifier = Modifier.weight(1f),
                        enabled = !isRunning
                    ) {
                        Icon(Icons.AutoMirrored.Filled.List, null)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(audioUri?.lastPathSegment?.take(12) ?: stringResource(R.string.workflow_audio_video_placeholder))
                    }
                    OutlinedButton(
                        onClick = onRecord,
                        modifier = Modifier.weight(1f),
                        enabled = !isRunning
                    ) {
                        Icon(Icons.Default.Add, null)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(stringResource(R.string.workflow_record_btn))
                    }
                }
            }
        }
        
        Spacer(modifier = Modifier.height(12.dp))
        
        // Step 2: Summary Settings
        Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(stringResource(R.string.workflow_step_summarize), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(12.dp))
                RemoteSummaryBackendEditor(
                    title = stringResource(R.string.video_summary_remote_settings_title),
                    backend = summaryBackend,
                    onBackendChange = onSummaryBackendChange,
                    ollamaUrl = summaryOllamaUrl,
                    onOllamaUrlChange = onSummaryOllamaUrlChange,
                    llamaServerUrl = summaryLlamaUrl,
                    onLlamaServerUrlChange = onSummaryLlamaUrlChange,
                    llamaSwapUrl = summaryLlamaSwapUrl,
                    onLlamaSwapUrlChange = onSummaryLlamaSwapUrlChange,
                    ollamaModel = summaryOllamaModel,
                    onOllamaModelSelected = { onSummaryOllamaModelChange(it) },
                    llamaSwapModel = summaryLlamaSwapModel,
                    onLlamaSwapModelSelected = { onSummaryLlamaSwapModelChange(it) },
                    llamaServerModelLabel = summaryLlamaServerModelLabel,
                    llamaServerContextLabel = summaryLlamaServerContextLabel,
                    llamaServerContextTokens = summaryLlamaServerContextTokens,
                    requestedContextForWarning = mergeContext,
                    fetchMetadata = {
                        RemoteSummaryClientFactory.fromSnapshot(
                            RemoteSummarySettingsSnapshot(
                                backend = summaryBackend,
                                ollamaUrl = summaryOllamaUrl,
                                llamaServerUrl = summaryLlamaUrl,
                                llamaSwapUrl = summaryLlamaSwapUrl,
                                ollamaModel = summaryOllamaModel,
                                llamaSwapModel = summaryLlamaSwapModel,
                                thinkingEnabled = thinkingEnabled,
                                llamaServerModelLabel = summaryLlamaServerModelLabel,
                                llamaServerContextTokens = summaryLlamaServerContextTokens,
                                llamaServerContextLabel = summaryLlamaServerContextLabel,
                                chunkContext = contextSize,
                                chunkMaxTokens = maxTokens,
                                mergeContext = mergeContext,
                                mergeMaxTokens = mergeMaxTokens,
                                temperature = temperature,
                                timeoutMinutes = timeoutMinutes,
                                targetLanguage = summaryTargetLanguage,
                                summaryPrompt = systemPrompt,
                                mergePrompt = null
                            )
                        ).fetchMetadata()
                    },
                    onMetadataLoaded = { metadata ->
                        if (SettingsRepository.isLlamaServerBackend(metadata.backend)) {
                            settingsRepo.setWorkflowSummaryLlamaServerModelLabel(metadata.serverModelLabel)
                            settingsRepo.setWorkflowSummaryLlamaServerContextTokens(metadata.serverContextTokens)
                            settingsRepo.setWorkflowSummaryLlamaServerContextLabel(metadata.serverContextLabel)
                        }
                    }
                )

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = summaryTargetLanguage,
                    onValueChange = onSummaryTargetLanguageChange,
                    label = { Text(stringResource(R.string.pdf_target_language_label)) },
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = systemPrompt,
                    onValueChange = onSystemPromptChange,
                    label = { Text(stringResource(R.string.workflow_system_prompt_label)) },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                // Temperature
                SliderWithInput(
                    value = temperature,
                    onValueChange = onTemperatureChange,
                    valueRange = 0f..2f,
                    label = stringResource(R.string.label_temperature),
                    decimalPlaces = 2
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                // Context size
                IntInputField(
                    value = contextSize,
                    onValueChange = onContextChange,
                    label = stringResource(R.string.label_context_size)
                )

                Spacer(modifier = Modifier.height(8.dp))

                IntInputField(
                    value = maxTokens,
                    onValueChange = onMaxTokensChange,
                    label = stringResource(R.string.pdf_max_tokens_label)
                )

                Spacer(modifier = Modifier.height(8.dp))

                IntInputField(
                    value = mergeContext,
                    onValueChange = onMergeContextChange,
                    label = stringResource(R.string.pdf_merge_context_label)
                )

                Spacer(modifier = Modifier.height(8.dp))

                IntInputField(
                    value = mergeMaxTokens,
                    onValueChange = onMergeMaxTokensChange,
                    label = stringResource(R.string.pdf_merge_max_tokens_label)
                )

                Spacer(modifier = Modifier.height(8.dp))

                IntSliderWithInput(
                    value = timeoutMinutes,
                    onValueChange = onTimeoutMinutesChange,
                    valueRange = SettingsRepository.PDF_TIMEOUT_MINUTES_RANGE,
                    label = stringResource(R.string.pdf_timeout_label),
                    suffix = stringResource(R.string.pdf_minutes_suffix)
                )

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(stringResource(R.string.pdf_thinking_toggle_title))
                    Switch(
                        checked = thinkingEnabled,
                        onCheckedChange = onThinkingEnabledChange
                    )
                }
            }
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // Progress
        if (isRunning) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(currentStep, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(8.dp))
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                    if (totalChunks > 0) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            stringResource(R.string.summary_progress_chunk, currentChunk.coerceAtLeast(1), totalChunks),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
        
        errorMessage?.let { error ->
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Text(error, modifier = Modifier.padding(12.dp), color = MaterialTheme.colorScheme.onErrorContainer)
            }
        }
        
        if (projectedChunkCount > 0) {
            Text(
                stringResource(R.string.video_summary_chunk_count, projectedChunkCount),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (cancelled && !isRunning && errorMessage == null) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f))) {
                Text(
                    stringResource(R.string.summary_cancelled_message),
                    modifier = Modifier.padding(12.dp),
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
        }

        if (partialSummaries.isNotEmpty()) {
            Spacer(modifier = Modifier.height(12.dp))
            com.example.llamadroid.ui.components.SummaryMarkdownCard(
                title = stringResource(R.string.pdf_partial_results_title),
                markdown = partialSummaries.mapIndexed { index, part ->
                    "### ${context.getString(R.string.summary_partial_item_label, index + 1)}\n$part"
                }.joinToString("\n\n")
            )
        }

        // Summary result
        if (summaryText.isNotBlank()) {
            Spacer(modifier = Modifier.height(12.dp))
            com.example.llamadroid.ui.components.SummaryMarkdownCard(
                title = stringResource(R.string.workflow_summary_label),
                markdown = summaryText
            )
        }

        if (transcriptionText.isNotBlank()) {
            Spacer(modifier = Modifier.height(12.dp))
            com.example.llamadroid.ui.components.SummaryMarkdownCard(
                title = stringResource(R.string.transcript_section_title),
                markdown = transcriptionText
            )
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // Run/Cancel buttons
        val backendReady = when (SettingsRepository.normalizeOllamaOrLlamaBackend(summaryBackend)) {
            SettingsRepository.PDF_BACKEND_LLAMA_SERVER -> summaryLlamaUrl.isNotBlank()
            SettingsRepository.PDF_BACKEND_LLAMA_SWAP -> summaryLlamaSwapUrl.isNotBlank() && !summaryLlamaSwapModel.isNullOrBlank()
            else -> summaryOllamaUrl.isNotBlank() && !summaryOllamaModel.isNullOrBlank()
        }
        val canRun = whisperModelPath != null && audioUri != null && backendReady && !isRunning
        
        if (isRunning) {
            // Cancel button when running
            OutlinedButton(
                onClick = onCancel,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
            ) {
                Icon(Icons.Default.Close, null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.action_cancel))
            }
        } else {
            // Run button when not running
            Button(
                onClick = onRun,
                enabled = canRun,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.workflow_run_btn))
            }
        }
    }
}
