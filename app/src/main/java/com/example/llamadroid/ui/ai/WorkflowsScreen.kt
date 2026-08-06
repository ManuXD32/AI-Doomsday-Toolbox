package com.example.llamadroid.ui.ai

import android.Manifest
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.media.MediaRecorder
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.res.stringResource
import androidx.core.content.FileProvider
import com.example.llamadroid.R
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
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
import androidx.compose.ui.text.style.TextOverflow
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
import com.example.llamadroid.data.PdfOcrProvider
import com.example.llamadroid.data.SettingsRepository
import com.example.llamadroid.data.SharedFileHolder
import com.example.llamadroid.onnx.resolveSupertonicVoices
import com.example.llamadroid.onnx.supertonicLanguageCodes
import com.example.llamadroid.service.*
import com.example.llamadroid.ui.components.DraftIntTextField
import com.example.llamadroid.ui.components.DraftLongTextField
import com.example.llamadroid.ui.components.IntInputField
import com.example.llamadroid.ui.components.RemoteSummaryBackendEditor
import com.example.llamadroid.ui.components.SliderWithInput
import com.example.llamadroid.ui.components.IntSliderWithInput
import com.example.llamadroid.ui.components.WhisperVadInlineControl
import com.example.llamadroid.ui.settings.PdfTranslationQualityModeSelector
import com.example.llamadroid.ui.navigation.Screen
import com.example.llamadroid.util.FormatUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import com.example.llamadroid.util.UpscalerAssetPackSupport
import java.util.*
import kotlin.math.pow
import org.json.JSONObject

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
    
    // Selected workflow: 0 = none, 1 = transcribe+summary, 2 = txt2img+upscale, 3 = manga translation,
    // 4 = media dubbing, 5 = subtitle translation, 6 = video interpolation+upscale
    val scope = rememberCoroutineScope()
    var selectedWorkflow by remember { mutableIntStateOf(0) }
    val pendingSharedFile by SharedFileHolder.pendingFile.collectAsState()
    LaunchedEffect(pendingSharedFile) {
        if (pendingSharedFile?.targetScreen == "interpolate_then_upscale") selectedWorkflow = 6
    }
    var mangaCbzUris by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var mangaIsRunning by remember { mutableStateOf(false) }
    var mangaStep by remember { mutableStateOf("") }
    var mangaProgress by remember { mutableFloatStateOf(0f) }
    var mangaResults by remember { mutableStateOf<List<MangaTranslationFileResult>>(emptyList()) }
    var mangaError by remember { mutableStateOf<String?>(null) }
    var mangaErrorDetails by remember { mutableStateOf<String?>(null) }
    var mangaExportPdf by remember { mutableStateOf(true) }
    var mangaExportCbz by remember { mutableStateOf(true) }
    var mangaPreview by remember { mutableStateOf<MangaTranslationPreviewResult?>(null) }

    LaunchedEffect(
        pdfTranslationJobState.isRunning,
        pdfTranslationJobState.kind,
        pdfTranslationJobState.cancelled,
        pdfTranslationJobState.progressMessage,
        pdfTranslationJobState.progressFraction,
        pdfTranslationJobState.currentFileName,
        pdfTranslationJobState.currentFileIndex,
        pdfTranslationJobState.totalFiles,
        pdfTranslationJobState.mangaResults,
        pdfTranslationJobState.errorMessage,
        pdfTranslationJobState.errorDetails
    ) {
        if (
            pdfTranslationJobState.kind == PdfTranslationJobKind.MANGA_BATCH ||
            pdfTranslationJobState.kind == PdfTranslationJobKind.MANGA_PREVIEW
        ) {
            mangaIsRunning = pdfTranslationJobState.isRunning
            if (pdfTranslationJobState.isRunning) {
                mangaStep = pdfTranslationJobState.currentFileName?.let { name ->
                    context.getString(
                        R.string.workflow_manga_progress_file_stage,
                        pdfTranslationJobState.currentFileIndex,
                        pdfTranslationJobState.totalFiles,
                        name,
                        pdfTranslationJobState.progressMessage
                    )
                } ?: pdfTranslationJobState.progressMessage
                mangaProgress = pdfTranslationJobState.progressFraction
                mangaError = null
                mangaErrorDetails = null
            } else if (pdfTranslationJobState.cancelled) {
                mangaResults = pdfTranslationJobState.mangaResults
                mangaStep = context.getString(R.string.action_cancelled)
                mangaError = null
                mangaErrorDetails = null
            } else if (pdfTranslationJobState.mangaResults.isNotEmpty()) {
                mangaResults = pdfTranslationJobState.mangaResults
                mangaStep = context.getString(R.string.workflow_complete)
                mangaProgress = 1f
                mangaError = null
                mangaErrorDetails = null
            } else if (pdfTranslationJobState.mangaPreview != null) {
                mangaPreview = pdfTranslationJobState.mangaPreview
                mangaStep = context.getString(R.string.workflow_manga_preview_ready)
                mangaProgress = 1f
                mangaError = null
                mangaErrorDetails = null
            } else if (pdfTranslationJobState.errorMessage != null) {
                mangaError = pdfTranslationJobState.errorMessage
                mangaErrorDetails = pdfTranslationJobState.errorDetails
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
    val initialTxt2imgIpAdapter = remember {
        settingsRepo.sdIpAdapterLastUsedDraft()?.readSdIpAdapterDraft()
            ?: SdIpAdapterDraftState()
    }
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
    var txt2imgIpAdapterEnabled by remember { mutableStateOf(initialTxt2imgIpAdapter.enabled) }
    var txt2imgIpAdapterPath by remember { mutableStateOf(initialTxt2imgIpAdapter.adapterPath) }
    var txt2imgClipVisionPath by remember { mutableStateOf(initialTxt2imgIpAdapter.clipVisionPath) }
    var txt2imgIpAdapterReferencePath by remember {
        mutableStateOf(
            SdIpAdapterReferenceStore.resolveOwnedImagePath(
                context,
                initialTxt2imgIpAdapter.imagePath
            )
        )
    }
    var txt2imgIpAdapterStrength by remember {
        mutableFloatStateOf(initialTxt2imgIpAdapter.strength)
    }
    var upscalerPath by remember { mutableStateOf<String?>(null) }
    var upscaleFactor by remember { mutableIntStateOf(2) }
    var upscaleRepeats by remember { mutableIntStateOf(1) }
    var upscaleThreads by remember { mutableIntStateOf(4) }
    var txt2imgIsRunning by remember { mutableStateOf(false) }
    var txt2imgStep by remember { mutableStateOf("") }
    var txt2imgProgress by remember { mutableFloatStateOf(0f) }
    var txt2imgResultPath by remember { mutableStateOf<String?>(null) }
    var txt2imgError by remember { mutableStateOf<String?>(null) }
    
    LaunchedEffect(
        txt2imgIpAdapterEnabled,
        txt2imgIpAdapterPath,
        txt2imgClipVisionPath,
        txt2imgIpAdapterReferencePath,
        txt2imgIpAdapterStrength
    ) {
        settingsRepo.setSdIpAdapterLastUsedDraft(
            org.json.JSONObject().putSdIpAdapterDraft(
                SdIpAdapterDraftState(
                    enabled = txt2imgIpAdapterEnabled,
                    adapterPath = txt2imgIpAdapterPath,
                    clipVisionPath = txt2imgClipVisionPath,
                    imagePath = txt2imgIpAdapterReferencePath,
                    strength = txt2imgIpAdapterStrength
                )
            )
        )
    }

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
    val persistedSummaryLiteRtModelId by settingsRepo.workflowSummaryLiteRtModelId.collectAsState()
    val persistedSummaryLiteRtBackend by settingsRepo.workflowSummaryLiteRtBackend.collectAsState()
    val persistedSummaryLiteRtMtpEnabled by settingsRepo.workflowSummaryLiteRtMtpEnabled.collectAsState()
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
    var summaryLiteRtModelId by remember(persistedSummaryLiteRtModelId) { mutableStateOf(persistedSummaryLiteRtModelId) }
    var summaryLiteRtBackend by remember(persistedSummaryLiteRtBackend) { mutableStateOf(persistedSummaryLiteRtBackend) }
    var summaryLiteRtMtpEnabled by remember(persistedSummaryLiteRtMtpEnabled) { mutableStateOf(persistedSummaryLiteRtMtpEnabled) }
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
    val mediaTranslationState by MediaTranslationWorkflowStateHolder.state.collectAsState()
    val mediaDubbingRuntimeState = mediaTranslationStateForWorkflow(mediaTranslationState, MEDIA_TRANSLATION_WORKFLOW_KIND_MEDIA)
    val subtitleTranslationRuntimeState = mediaTranslationStateForWorkflow(mediaTranslationState, MEDIA_TRANSLATION_WORKFLOW_KIND_SUBTITLE)
    val defaultTranslationTargetLanguage = stringResource(R.string.pdf_translation_language_english)

    // ===== Media dubbing/audio translation state =====
    var mediaTranslationUri by remember { mutableStateOf<Uri?>(null) }
    var mediaTranslationPath by remember { mutableStateOf<String?>(null) }
    var mediaTranslationName by remember { mutableStateOf("") }
    var mediaTranslationMimeType by remember { mutableStateOf<String?>(null) }
    var mediaTranslationBatch by remember { mutableStateOf<List<WorkflowMediaInput>>(emptyList()) }
    var mediaTranslationTargetLanguage by remember(defaultTranslationTargetLanguage) { mutableStateOf(defaultTranslationTargetLanguage) }
    var mediaTranslationTtsModelPath by remember { mutableStateOf<String?>(null) }
    var mediaTranslationTtsModelName by remember { mutableStateOf<String?>(null) }
    var mediaTranslationTtsVoice by remember { mutableStateOf<String?>(null) }
    var mediaTranslationTtsLanguage by remember { mutableStateOf("en") }
    var mediaTranslationTtsSteps by remember { mutableIntStateOf(8) }
    var mediaTranslationOutputMode by remember { mutableStateOf(MediaTranslationOutputMode.AUTO) }
    var mediaTranslationReplaceAudio by remember { mutableStateOf(true) }
    var mediaTranslationContextEnabled by remember { mutableStateOf(true) }
    var mediaTranslationContextLines by remember { mutableIntStateOf(2) }
    var mediaTranslationSkipFailedLines by remember { mutableStateOf(false) }

    // ===== Subtitle translation/subtitled video workflow state =====
    var subtitleTranslationVideoUri by remember { mutableStateOf<Uri?>(null) }
    var subtitleTranslationVideoPath by remember { mutableStateOf<String?>(null) }
    var subtitleTranslationVideoName by remember { mutableStateOf("") }
    var subtitleTranslationVideoBatch by remember { mutableStateOf<List<WorkflowMediaInput>>(emptyList()) }
    var subtitleTranslationSrtUri by remember { mutableStateOf<Uri?>(null) }
    var subtitleTranslationSrtPath by remember { mutableStateOf<String?>(null) }
    var subtitleTranslationSrtName by remember { mutableStateOf("") }
    var subtitleTranslationTargetLanguage by remember(defaultTranslationTargetLanguage) { mutableStateOf(defaultTranslationTargetLanguage) }
    var subtitleTranslationTranslateSubtitles by remember { mutableStateOf(true) }
    var subtitleTranslationContextEnabled by remember { mutableStateOf(true) }
    var subtitleTranslationContextLines by remember { mutableIntStateOf(2) }
    var subtitleTranslationSkipFailedLines by remember { mutableStateOf(false) }
    var subtitleTranslationBurnIntoVideo by remember { mutableStateOf(true) }
    var subtitleTranslationFontSize by remember { mutableIntStateOf(24) }
    var subtitleTranslationAlignment by remember { mutableIntStateOf(2) }
    var subtitleTranslationMarginV by remember { mutableIntStateOf(20) }
    var subtitleTranslationMarginL by remember { mutableIntStateOf(0) }
    var subtitleTranslationColor by remember { mutableStateOf(Color.White) }
    var subtitleTranslationFontName by remember { mutableStateOf("Default") }
    
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

    LaunchedEffect(mediaTranslationState.workflowKind, mediaTranslationState.isRunning, mediaTranslationState.finalOutputPath) {
        if (mediaTranslationState.isRunning || mediaTranslationState.finalOutputPath != null) {
            workflowIndexForMediaTranslationKind(mediaTranslationState.workflowKind)?.let { selectedWorkflow = it }
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
            mangaCbzUris = (mangaCbzUris + uris).distinct()
            selectedWorkflow = 3
        }
    }

    val mangaAllFilesPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            uris.forEach { uri ->
                try {
                    context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } catch (_: Exception) {
                }
            }
            mangaCbzUris = (mangaCbzUris + uris).distinct()
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
    LaunchedEffect(summaryLiteRtModelId) { settingsRepo.setWorkflowSummaryLiteRtModelId(summaryLiteRtModelId.takeIf { it > 0L }) }
    LaunchedEffect(summaryLiteRtBackend) { settingsRepo.setWorkflowSummaryLiteRtBackend(summaryLiteRtBackend) }
    LaunchedEffect(summaryLiteRtMtpEnabled) { settingsRepo.setWorkflowSummaryLiteRtMtpEnabled(summaryLiteRtMtpEnabled) }
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
                    4 -> stringResource(R.string.workflow_media_translate_title)
                    5 -> stringResource(R.string.workflow_subtitle_translate_title)
                    6 -> stringResource(R.string.workflow_video_interpolate_upscale)
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
                .then(
                    if (selectedWorkflow == 3 || selectedWorkflow == 6) {
                        Modifier
                    } else {
                        Modifier.verticalScroll(rememberScrollState())
                    }
                )
        ) {
            // Show running workflow indicator (visible on all tabs)
            if (transcribeIsRunning || txt2imgIsRunning || mangaIsRunning || mediaTranslationState.isRunning) {
                val runningTitle = when {
                    transcribeIsRunning -> stringResource(R.string.workflow_running_transcribe)
                    txt2imgIsRunning -> stringResource(R.string.workflow_running_txt2img)
                    mangaIsRunning -> stringResource(R.string.workflow_manga_foreground_title)
                    mediaTranslationState.workflowKind == MEDIA_TRANSLATION_WORKFLOW_KIND_SUBTITLE -> stringResource(R.string.workflow_subtitle_translate_running)
                    else -> stringResource(R.string.workflow_media_translate_running)
                }
                val runningStep = when {
                    transcribeIsRunning -> transcribeStep
                    txt2imgIsRunning -> txt2imgStep
                    mangaIsRunning -> mangaStep
                    else -> mediaTranslationState.status
                }
                val runningProgress = when {
                    transcribeIsRunning -> transcribeProgress
                    txt2imgIsRunning -> txt2imgProgress
                    mangaIsRunning -> mangaProgress
                    else -> mediaTranslationState.progress
                }
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { 
                            // Switch to the running workflow
                            if (transcribeIsRunning) selectedWorkflow = 1
                            else if (txt2imgIsRunning) selectedWorkflow = 2
                            else if (mangaIsRunning) selectedWorkflow = 3
                            else if (mediaTranslationState.isRunning) {
                                workflowIndexForMediaTranslationKind(mediaTranslationState.workflowKind)?.let { selectedWorkflow = it }
                            }
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
                                runningTitle,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                runningStep,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                            )
                        }
                        Text(
                            "${(runningProgress * 100).toInt()}%",
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

                    Spacer(modifier = Modifier.height(12.dp))

                    WorkflowCard(
                        emoji = "🎬→🗣️",
                        title = stringResource(R.string.workflow_media_translate_title),
                        description = stringResource(R.string.workflow_media_translate_desc),
                        gradientColors = listOf(
                            Color(0xFF009688).copy(alpha = 0.15f),
                            Color(0xFFFF9800).copy(alpha = 0.28f)
                        ),
                        onClick = { selectedWorkflow = 4 }
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    WorkflowCard(
                        emoji = "🎬→💬",
                        title = stringResource(R.string.workflow_subtitle_translate_title),
                        description = stringResource(R.string.workflow_subtitle_translate_desc),
                        gradientColors = listOf(
                            Color(0xFF673AB7).copy(alpha = 0.14f),
                            Color(0xFF03A9F4).copy(alpha = 0.26f)
                        ),
                        onClick = { selectedWorkflow = 5 }
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    WorkflowCard(
                        emoji = "VI",
                        title = stringResource(R.string.workflow_video_interpolate_upscale),
                        description = stringResource(R.string.workflow_video_interpolate_upscale_desc),
                        gradientColors = listOf(
                            Color(0xFF00BCD4).copy(alpha = 0.14f),
                            Color(0xFF7E57C2).copy(alpha = 0.28f)
                        ),
                        onClick = { selectedWorkflow = 6 }
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
                        summaryLiteRtModelId = summaryLiteRtModelId,
                        onSummaryLiteRtModelIdChange = { summaryLiteRtModelId = it ?: -1L },
                        summaryLiteRtBackend = summaryLiteRtBackend,
                        onSummaryLiteRtBackendChange = { summaryLiteRtBackend = it },
                        summaryLiteRtMtpEnabled = summaryLiteRtMtpEnabled,
                        onSummaryLiteRtMtpEnabledChange = { summaryLiteRtMtpEnabled = it },
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
                                SettingsRepository.PDF_BACKEND_LITERT -> summaryLiteRtModelId > 0L
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
                                    vadConfig = settingsRepo.whisperVadConfigSnapshot(),
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
                6 -> {
                    VideoInterpolationScreen(navController = navController, embeddedWorkflow = true)
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
                        ipAdapterEnabled = txt2imgIpAdapterEnabled,
                        onIpAdapterEnabledChange = { txt2imgIpAdapterEnabled = it },
                        ipAdapterPath = txt2imgIpAdapterPath,
                        onIpAdapterPathChange = { txt2imgIpAdapterPath = it },
                        clipVisionPath = txt2imgClipVisionPath,
                        onClipVisionPathChange = { txt2imgClipVisionPath = it },
                        ipAdapterReferencePath = txt2imgIpAdapterReferencePath,
                        onIpAdapterReferencePathChange = { txt2imgIpAdapterReferencePath = it },
                        ipAdapterStrength = txt2imgIpAdapterStrength,
                        onIpAdapterStrengthChange = { txt2imgIpAdapterStrength = it },
                        onOpenSdModels = { navController.navigate(Screen.SDModels.route) },
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
                        modifier = Modifier.weight(1f),
                        db = db,
                        settingsRepo = settingsRepo,
                        selectedUris = mangaCbzUris,
                        isRunning = mangaIsRunning,
                        currentStep = mangaStep,
                        progress = mangaProgress,
                        results = mangaResults,
                        preview = mangaPreview,
                        errorMessage = mangaError,
                        errorDetails = mangaErrorDetails,
                        exportPdf = mangaExportPdf,
                        exportCbz = mangaExportCbz,
                        onExportPdfChange = { mangaExportPdf = it },
                        onExportCbzChange = { mangaExportCbz = it },
                        onPickFiles = {
                            mangaCbzPicker.launch(MangaTranslationSupport.primaryPickerMimeTypes)
                        },
                        onBrowseAllFiles = {
                            mangaAllFilesPicker.launch(MangaTranslationSupport.fallbackPickerMimeTypes)
                        },
                        onRemoveFile = { uri -> mangaCbzUris = mangaCbzUris - uri },
                        onClearFiles = { mangaCbzUris = emptyList() },
                        onOpenModels = { navController.navigate(Screen.LLMModels.route) },
                        onCancel = { PDFTranslationJobService.cancel() },
                        onPreview = { spec ->
                            mangaError = null
                            mangaErrorDetails = null
                            if (!PDFTranslationJobService.startMangaPreview(context, spec)) {
                                mangaError = context.getString(R.string.pdf_translation_already_running)
                            }
                        },
                        onRun = { spec ->
                            mangaIsRunning = true
                            mangaError = null
                            mangaErrorDetails = null
                            mangaResults = emptyList()
                            mangaStep = context.getString(R.string.workflow_step_starting)
                            mangaProgress = 0f
                            if (!PDFTranslationJobService.startMangaTranslation(context, spec)) {
                                mangaIsRunning = false
                                mangaError = pdfTranslationJobState.errorMessage
                                    ?: context.getString(R.string.pdf_translation_already_running)
                                mangaStep = ""
                            }
                        }
                    )
                }

                4 -> {
                    MediaDubbingTranslationWorkflowContent(
                        db = db,
                        settingsRepo = settingsRepo,
                        selectedUri = mediaTranslationUri,
                        selectedPath = mediaTranslationPath,
                        selectedName = mediaTranslationName,
                        selectedMimeType = mediaTranslationMimeType,
                        onMediaSelected = { uri, path, name, mimeType ->
                            mediaTranslationUri = uri
                            mediaTranslationPath = path
                            mediaTranslationName = name
                            mediaTranslationMimeType = mimeType
                            mediaTranslationBatch = listOf(WorkflowMediaInput(uri, path, name, mimeType))
                        },
                        mediaBatch = mediaTranslationBatch,
                        onMediaBatchSelected = { items ->
                            mediaTranslationBatch = items
                            val first = items.firstOrNull()
                            mediaTranslationUri = first?.uri
                            mediaTranslationPath = first?.path
                            mediaTranslationName = first?.name.orEmpty()
                            mediaTranslationMimeType = first?.mimeType
                        },
                        whisperModelPath = whisperModelPath,
                        onWhisperModelChange = { whisperModelPath = it },
                        whisperLanguage = whisperLanguage,
                        onWhisperLanguageChange = { whisperLanguage = it },
                        whisperThreads = whisperThreads,
                        onWhisperThreadsChange = { whisperThreads = it },
                        targetLanguage = mediaTranslationTargetLanguage,
                        onTargetLanguageChange = { mediaTranslationTargetLanguage = it },
                        backend = summaryBackend,
                        onBackendChange = { summaryBackend = it },
                        ollamaUrl = summaryOllamaUrl,
                        onOllamaUrlChange = { summaryOllamaUrl = it },
                        llamaServerUrl = summaryLlamaUrl,
                        onLlamaServerUrlChange = { summaryLlamaUrl = it },
                        llamaSwapUrl = summaryLlamaSwapUrl,
                        onLlamaSwapUrlChange = { summaryLlamaSwapUrl = it },
                        ollamaModel = summaryOllamaModel,
                        onOllamaModelChange = { summaryOllamaModel = it },
                        llamaSwapModel = summaryLlamaSwapModel,
                        onLlamaSwapModelChange = { summaryLlamaSwapModel = it },
                        liteRtModelId = summaryLiteRtModelId,
                        llamaServerModelLabel = persistedWorkflowLlamaServerModelLabel,
                        llamaServerContextLabel = persistedWorkflowLlamaServerContextLabel,
                        llamaServerContextTokens = persistedWorkflowLlamaServerContextTokens,
                        contextSize = summaryContext,
                        maxTokens = summaryMaxTokens,
                        temperature = summaryTemperature,
                        timeoutMinutes = summaryTimeoutMinutes,
                        thinkingEnabled = summaryThinkingEnabled,
                        translationContextEnabled = mediaTranslationContextEnabled,
                        onTranslationContextEnabledChange = { mediaTranslationContextEnabled = it },
                        translationContextLines = mediaTranslationContextLines,
                        onTranslationContextLinesChange = { mediaTranslationContextLines = it },
                        skipFailedTranslationLines = mediaTranslationSkipFailedLines,
                        onSkipFailedTranslationLinesChange = { mediaTranslationSkipFailedLines = it },
                        ttsModelPath = mediaTranslationTtsModelPath,
                        ttsModelName = mediaTranslationTtsModelName,
                        onTtsModelChange = { path, name ->
                            mediaTranslationTtsModelPath = path
                            mediaTranslationTtsModelName = name
                        },
                        ttsVoice = mediaTranslationTtsVoice,
                        onTtsVoiceChange = { mediaTranslationTtsVoice = it },
                        ttsLanguage = mediaTranslationTtsLanguage,
                        onTtsLanguageChange = { mediaTranslationTtsLanguage = it },
                        ttsSteps = mediaTranslationTtsSteps,
                        onTtsStepsChange = { mediaTranslationTtsSteps = it },
                        outputMode = mediaTranslationOutputMode,
                        onOutputModeChange = { mediaTranslationOutputMode = it },
                        replaceOriginalAudio = mediaTranslationReplaceAudio,
                        onReplaceOriginalAudioChange = { mediaTranslationReplaceAudio = it },
                        state = mediaDubbingRuntimeState,
                        onRun = {
                            val backendReady = isWorkflowTranslationBackendReady(
                                backend = summaryBackend,
                                ollamaUrl = summaryOllamaUrl,
                                ollamaModel = summaryOllamaModel,
                                llamaServerUrl = summaryLlamaUrl,
                                llamaSwapUrl = summaryLlamaSwapUrl,
                                llamaSwapModel = summaryLlamaSwapModel,
                                liteRtModelId = summaryLiteRtModelId
                            )
                            val sourceItems = mediaTranslationBatch.ifEmpty {
                                val path = mediaTranslationPath
                                val uri = mediaTranslationUri
                                if (path != null && uri != null) listOf(WorkflowMediaInput(uri, path, mediaTranslationName, mediaTranslationMimeType)) else emptyList()
                            }
                            val ttsPath = mediaTranslationTtsModelPath
                            val ttsName = mediaTranslationTtsModelName
                            if (sourceItems.isNotEmpty() && whisperModelPath != null && ttsPath != null && ttsName != null && backendReady) {
                                val specs = sourceItems.map { item ->
                                    MediaTranslationJobSpec(
                                        sourcePath = item.path,
                                        sourceName = item.name.ifBlank { context.getString(R.string.workflow_audio_video_placeholder) },
                                        sourceMimeType = item.mimeType,
                                        whisperModelPath = whisperModelPath!!,
                                        whisperLanguage = whisperLanguage,
                                        whisperThreads = whisperThreads,
                                        whisperVad = settingsRepo.whisperVadConfigSnapshot(),
                                        targetLanguage = mediaTranslationTargetLanguage,
                                        ttsModelPath = ttsPath,
                                        ttsModelName = ttsName,
                                        ttsLanguage = mediaTranslationTtsLanguage,
                                        ttsVoiceName = mediaTranslationTtsVoice,
                                        ttsSteps = mediaTranslationTtsSteps,
                                        outputMode = mediaTranslationOutputMode,
                                        replaceOriginalAudio = mediaTranslationReplaceAudio,
                                        backendSnapshot = RemoteSummarySettingsSnapshot(
                                            backend = summaryBackend,
                                            ollamaUrl = summaryOllamaUrl,
                                            llamaServerUrl = summaryLlamaUrl,
                                            llamaSwapUrl = summaryLlamaSwapUrl,
                                            ollamaModel = summaryOllamaModel,
                                            llamaSwapModel = summaryLlamaSwapModel,
                                            liteRtModelId = summaryLiteRtModelId.takeIf { it > 0L },
                                            liteRtBackend = summaryLiteRtBackend,
                                            liteRtMtpEnabled = summaryLiteRtMtpEnabled,
                                            thinkingEnabled = summaryThinkingEnabled,
                                            llamaServerModelLabel = persistedWorkflowLlamaServerModelLabel,
                                            llamaServerContextTokens = persistedWorkflowLlamaServerContextTokens,
                                            llamaServerContextLabel = persistedWorkflowLlamaServerContextLabel,
                                            chunkContext = summaryContext,
                                            chunkMaxTokens = summaryMaxTokens,
                                            mergeContext = summaryMergeContext,
                                            mergeMaxTokens = summaryMergeMaxTokens,
                                            temperature = summaryTemperature,
                                            timeoutMinutes = summaryTimeoutMinutes,
                                            targetLanguage = mediaTranslationTargetLanguage,
                                            summaryPrompt = null,
                                            mergePrompt = null
                                        ),
                                        translationContextEnabled = mediaTranslationContextEnabled,
                                        translationContextLines = mediaTranslationContextLines,
                                        skipFailedTranslationLines = mediaTranslationSkipFailedLines
                                    )
                                }
                                MediaTranslationWorkflowService.startBatch(
                                    context,
                                    specs
                                )
                            }
                        },
                        onCancel = { MediaTranslationWorkflowService.cancel(context) },
                        onPause = { MediaTranslationWorkflowService.pause(context) },
                        onMetadataLoaded = { metadata ->
                            if (SettingsRepository.isLlamaServerBackend(metadata.backend)) {
                                settingsRepo.setWorkflowSummaryLlamaServerModelLabel(metadata.serverModelLabel)
                                settingsRepo.setWorkflowSummaryLlamaServerContextTokens(metadata.serverContextTokens)
                                settingsRepo.setWorkflowSummaryLlamaServerContextLabel(metadata.serverContextLabel)
                            }
                        }
                    )
                }

                5 -> {
                    SubtitleTranslationWorkflowContent(
                        db = db,
                        settingsRepo = settingsRepo,
                        videoUri = subtitleTranslationVideoUri,
                        videoPath = subtitleTranslationVideoPath,
                        videoName = subtitleTranslationVideoName,
                        onVideoSelected = { uri, path, name ->
                            subtitleTranslationVideoUri = uri
                            subtitleTranslationVideoPath = path
                            subtitleTranslationVideoName = name
                            subtitleTranslationVideoBatch = listOf(WorkflowMediaInput(uri, path, name, "video/*"))
                        },
                        videoBatch = subtitleTranslationVideoBatch,
                        onVideoBatchSelected = { items ->
                            subtitleTranslationVideoBatch = items
                            val first = items.firstOrNull()
                            subtitleTranslationVideoUri = first?.uri
                            subtitleTranslationVideoPath = first?.path
                            subtitleTranslationVideoName = first?.name.orEmpty()
                        },
                        subtitleUri = subtitleTranslationSrtUri,
                        subtitlePath = subtitleTranslationSrtPath,
                        subtitleName = subtitleTranslationSrtName,
                        onSubtitleSelected = { uri, path, name ->
                            subtitleTranslationSrtUri = uri
                            subtitleTranslationSrtPath = path
                            subtitleTranslationSrtName = name
                        },
                        onClearSubtitle = {
                            subtitleTranslationSrtUri = null
                            subtitleTranslationSrtPath = null
                            subtitleTranslationSrtName = ""
                        },
                        whisperModelPath = whisperModelPath,
                        onWhisperModelChange = { whisperModelPath = it },
                        whisperLanguage = whisperLanguage,
                        onWhisperLanguageChange = { whisperLanguage = it },
                        whisperThreads = whisperThreads,
                        onWhisperThreadsChange = { whisperThreads = it },
                        targetLanguage = subtitleTranslationTargetLanguage,
                        onTargetLanguageChange = { subtitleTranslationTargetLanguage = it },
                        translateSubtitles = subtitleTranslationTranslateSubtitles,
                        onTranslateSubtitlesChange = { subtitleTranslationTranslateSubtitles = it },
                        backend = summaryBackend,
                        onBackendChange = { summaryBackend = it },
                        ollamaUrl = summaryOllamaUrl,
                        onOllamaUrlChange = { summaryOllamaUrl = it },
                        llamaServerUrl = summaryLlamaUrl,
                        onLlamaServerUrlChange = { summaryLlamaUrl = it },
                        llamaSwapUrl = summaryLlamaSwapUrl,
                        onLlamaSwapUrlChange = { summaryLlamaSwapUrl = it },
                        ollamaModel = summaryOllamaModel,
                        onOllamaModelChange = { summaryOllamaModel = it },
                        llamaSwapModel = summaryLlamaSwapModel,
                        onLlamaSwapModelChange = { summaryLlamaSwapModel = it },
                        liteRtModelId = summaryLiteRtModelId,
                        llamaServerModelLabel = persistedWorkflowLlamaServerModelLabel,
                        llamaServerContextLabel = persistedWorkflowLlamaServerContextLabel,
                        llamaServerContextTokens = persistedWorkflowLlamaServerContextTokens,
                        contextSize = summaryContext,
                        maxTokens = summaryMaxTokens,
                        temperature = summaryTemperature,
                        timeoutMinutes = summaryTimeoutMinutes,
                        thinkingEnabled = summaryThinkingEnabled,
                        translationContextEnabled = subtitleTranslationContextEnabled,
                        onTranslationContextEnabledChange = { subtitleTranslationContextEnabled = it },
                        translationContextLines = subtitleTranslationContextLines,
                        onTranslationContextLinesChange = { subtitleTranslationContextLines = it },
                        skipFailedTranslationLines = subtitleTranslationSkipFailedLines,
                        onSkipFailedTranslationLinesChange = { subtitleTranslationSkipFailedLines = it },
                        burnIntoVideo = subtitleTranslationBurnIntoVideo,
                        onBurnIntoVideoChange = { subtitleTranslationBurnIntoVideo = it },
                        fontSize = subtitleTranslationFontSize,
                        onFontSizeChange = { subtitleTranslationFontSize = it },
                        alignment = subtitleTranslationAlignment,
                        onAlignmentChange = { subtitleTranslationAlignment = it },
                        marginV = subtitleTranslationMarginV,
                        onMarginVChange = { subtitleTranslationMarginV = it },
                        marginL = subtitleTranslationMarginL,
                        onMarginLChange = { subtitleTranslationMarginL = it },
                        primaryColor = subtitleTranslationColor,
                        onPrimaryColorChange = { subtitleTranslationColor = it },
                        fontName = subtitleTranslationFontName,
                        onFontNameChange = { subtitleTranslationFontName = it },
                        state = subtitleTranslationRuntimeState,
                        onRun = {
                            val backendReady = isWorkflowTranslationBackendReady(
                                backend = summaryBackend,
                                ollamaUrl = summaryOllamaUrl,
                                ollamaModel = summaryOllamaModel,
                                llamaServerUrl = summaryLlamaUrl,
                                llamaSwapUrl = summaryLlamaSwapUrl,
                                llamaSwapModel = summaryLlamaSwapModel,
                                liteRtModelId = summaryLiteRtModelId
                            )
                            val videoItems = subtitleTranslationVideoBatch.ifEmpty {
                                val path = subtitleTranslationVideoPath
                                val uri = subtitleTranslationVideoUri
                                if (path != null && uri != null) listOf(WorkflowMediaInput(uri, path, subtitleTranslationVideoName, "video/*")) else emptyList()
                            }
                            val translationReady = !subtitleTranslationTranslateSubtitles || backendReady
                            val sourceReady = subtitleTranslationSrtPath != null || whisperModelPath != null
                            if (videoItems.isNotEmpty() && sourceReady && translationReady && !(subtitleTranslationSrtPath != null && videoItems.size > 1)) {
                                val specs = videoItems.map { item ->
                                    SubtitleTranslationJobSpec(
                                        videoPath = item.path,
                                        videoName = item.name.ifBlank { context.getString(R.string.workflow_subtitle_translate_video_placeholder) },
                                        sourceSubtitlePath = subtitleTranslationSrtPath,
                                        sourceSubtitleName = subtitleTranslationSrtName.ifBlank { null },
                                        whisperModelPath = whisperModelPath,
                                        whisperLanguage = whisperLanguage,
                                        whisperThreads = whisperThreads,
                                        whisperVad = settingsRepo.whisperVadConfigSnapshot(),
                                        targetLanguage = subtitleTranslationTargetLanguage,
                                        translateSubtitles = subtitleTranslationTranslateSubtitles,
                                        burnIntoVideo = subtitleTranslationBurnIntoVideo,
                                        burnStyle = SubtitleBurnStyleSpec(
                                            fontSize = subtitleTranslationFontSize,
                                            alignment = subtitleTranslationAlignment,
                                            marginV = subtitleTranslationMarginV,
                                            marginL = subtitleTranslationMarginL,
                                            primaryColorRed = subtitleTranslationColor.red,
                                            primaryColorGreen = subtitleTranslationColor.green,
                                            primaryColorBlue = subtitleTranslationColor.blue,
                                            fontName = subtitleTranslationFontName
                                        ),
                                        backendSnapshot = RemoteSummarySettingsSnapshot(
                                            backend = summaryBackend,
                                            ollamaUrl = summaryOllamaUrl,
                                            llamaServerUrl = summaryLlamaUrl,
                                            llamaSwapUrl = summaryLlamaSwapUrl,
                                            ollamaModel = summaryOllamaModel,
                                            llamaSwapModel = summaryLlamaSwapModel,
                                            liteRtModelId = summaryLiteRtModelId.takeIf { it > 0L },
                                            liteRtBackend = summaryLiteRtBackend,
                                            liteRtMtpEnabled = summaryLiteRtMtpEnabled,
                                            thinkingEnabled = summaryThinkingEnabled,
                                            llamaServerModelLabel = persistedWorkflowLlamaServerModelLabel,
                                            llamaServerContextTokens = persistedWorkflowLlamaServerContextTokens,
                                            llamaServerContextLabel = persistedWorkflowLlamaServerContextLabel,
                                            chunkContext = summaryContext,
                                            chunkMaxTokens = summaryMaxTokens,
                                            mergeContext = summaryMergeContext,
                                            mergeMaxTokens = summaryMergeMaxTokens,
                                            temperature = summaryTemperature,
                                            timeoutMinutes = summaryTimeoutMinutes,
                                            targetLanguage = subtitleTranslationTargetLanguage,
                                            summaryPrompt = null,
                                            mergePrompt = null
                                        ),
                                        translationContextEnabled = subtitleTranslationContextEnabled,
                                        translationContextLines = subtitleTranslationContextLines,
                                        skipFailedTranslationLines = subtitleTranslationSkipFailedLines
                                    )
                                }
                                MediaTranslationWorkflowService.startSubtitleTranslationBatch(
                                    context,
                                    specs
                                )
                            }
                        },
                        onCancel = { MediaTranslationWorkflowService.cancel(context) },
                        onPause = { MediaTranslationWorkflowService.pause(context) },
                        onMetadataLoaded = { metadata ->
                            if (SettingsRepository.isLlamaServerBackend(metadata.backend)) {
                                settingsRepo.setWorkflowSummaryLlamaServerModelLabel(metadata.serverModelLabel)
                                settingsRepo.setWorkflowSummaryLlamaServerContextTokens(metadata.serverContextTokens)
                                settingsRepo.setWorkflowSummaryLlamaServerContextLabel(metadata.serverContextLabel)
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MangaTranslationWorkflowContent(
    modifier: Modifier = Modifier,
    db: AppDatabase,
    settingsRepo: SettingsRepository,
    selectedUris: List<Uri>,
    isRunning: Boolean,
    currentStep: String,
    progress: Float,
    results: List<MangaTranslationFileResult>,
    preview: MangaTranslationPreviewResult?,
    errorMessage: String?,
    errorDetails: String?,
    exportPdf: Boolean,
    exportCbz: Boolean,
    onExportPdfChange: (Boolean) -> Unit,
    onExportCbzChange: (Boolean) -> Unit,
    onPickFiles: () -> Unit,
    onBrowseAllFiles: () -> Unit,
    onRemoveFile: (Uri) -> Unit,
    onClearFiles: () -> Unit,
    onOpenModels: () -> Unit,
    onCancel: () -> Unit,
    onPreview: (MangaTranslationJobSpec) -> Unit,
    onRun: (MangaTranslationJobSpec) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val savedConfig = remember { settingsRepo.mangaTranslationRunConfigSnapshot() }
    val outputFolder by settingsRepo.outputFolderUri.collectAsState()
    val installedModels by db.modelDao().getAllModels().collectAsState(initial = emptyList())
    val ocrModels = remember(installedModels) { MangaTranslationSupport.installedOcrModels(installedModels) }
    val projectors = remember(installedModels) { MangaTranslationSupport.installedProjectors(installedModels) }
    val templates by db.workflowTemplateDao()
        .getByType(com.example.llamadroid.data.db.WorkflowType.MANGA_TRANSLATION)
        .collectAsState(initial = emptyList())
    val sources = remember(selectedUris) {
        selectedUris.map { uri -> mangaSourceForUri(context, uri) }
    }
    var profile by remember { mutableStateOf(savedConfig.profile) }
    var targetLanguage by remember { mutableStateOf(savedConfig.targetLanguage) }
    var readingDirection by remember { mutableStateOf(savedConfig.readingDirection) }
    var behavior by remember { mutableStateOf(savedConfig.behavior) }
    var translationSettings by remember { mutableStateOf(savedConfig.translationSettings) }
    var translationOptions by remember { mutableStateOf(savedConfig.translationOptions) }
    var ocrModelRef by remember { mutableStateOf(savedConfig.ocrModelRef) }
    var ocrProjectorRef by remember { mutableStateOf(savedConfig.ocrProjectorRef) }
    var showOcrExpert by remember { mutableStateOf(false) }
    var templatesExpanded by remember { mutableStateOf(false) }
    var templateName by remember { mutableStateOf("") }
    var templatePendingDelete by remember {
        mutableStateOf<com.example.llamadroid.data.db.WorkflowTemplateEntity?>(null)
    }
    var templatePendingRename by remember {
        mutableStateOf<com.example.llamadroid.data.db.WorkflowTemplateEntity?>(null)
    }
    var renamedTemplateName by remember { mutableStateOf("") }
    var resumableJobs by remember {
        mutableStateOf(PDFTranslationJobService.discoverResumableMangaJobs(context))
    }

    fun currentConfig(): MangaTranslationRunConfig = MangaTranslationRunConfig(
        profile = profile,
        targetLanguage = targetLanguage,
        readingDirection = readingDirection,
        translationSettings = translationSettings,
        translationOptions = translationOptions,
        behavior = behavior,
        pageImageContextAvailable = true,
        pageImageContextReason = null
    ).copy(
        ocrModelRef = ocrModelRef,
        ocrProjectorRef = ocrProjectorRef
    )
    fun currentSpec(): MangaTranslationJobSpec = MangaTranslationJobSpec(
        sources = sources,
        exportPdf = exportPdf,
        exportCbz = exportCbz,
        config = currentConfig()
    )
    fun applyTemplate(template: com.example.llamadroid.data.db.WorkflowTemplateEntity) {
        runCatching {
            val json = JSONObject(template.configJson)
            onExportPdfChange(json.optBoolean("exportPdf", exportPdf))
            onExportCbzChange(json.optBoolean("exportCbz", exportCbz))
            if (json.optInt("version", 2) >= 3) {
                val saved = MangaTranslationSupport.runConfigFromJson(
                    json.optJSONObject("runConfig") ?: json,
                    currentConfig()
                )
                val resolvedOcrModel = MangaTranslationSupport.resolveTemplateModelRef(
                    saved.ocrModelRef,
                    ocrModels
                )
                val resolvedProjector = MangaTranslationSupport.resolveTemplateModelRef(
                    saved.ocrProjectorRef,
                    projectors
                )
                profile = saved.profile
                targetLanguage = saved.targetLanguage
                readingDirection = saved.readingDirection
                behavior = saved.behavior
                translationSettings = saved.translationSettings
                translationOptions = saved.translationOptions.copy(
                    llamaOcr = saved.translationOptions.llamaOcr.copy(
                        modelPath = resolvedOcrModel?.path
                            ?: saved.translationOptions.llamaOcr.modelPath,
                        mmprojPath = resolvedProjector?.path
                            ?: saved.translationOptions.llamaOcr.mmprojPath
                    )
                )
                ocrModelRef = saved.ocrModelRef
                    ?: resolvedOcrModel?.let(MangaTranslationSupport::modelRef)
                ocrProjectorRef = saved.ocrProjectorRef
                    ?: resolvedProjector?.let(MangaTranslationSupport::modelRef)
            }
        }
    }
    val runConfig = currentConfig()
    val preflight = MangaTranslationSupport.preflight(currentSpec())
    val previewIsStale = preview != null && preview.configFingerprint != runConfig.fingerprint()

    LaunchedEffect(preview?.configFingerprint) {
        if (preview != null && !previewIsStale) {
            settingsRepo.saveMangaTranslationRunConfig(currentConfig())
        }
    }
    LaunchedEffect(results) {
        if (results.any { it.isSuccess }) {
            settingsRepo.saveMangaTranslationRunConfig(currentConfig())
        }
    }

    templatePendingDelete?.let { template ->
        AlertDialog(
            onDismissRequest = { templatePendingDelete = null },
            title = { Text(stringResource(R.string.workflow_manga_delete_preset_title)) },
            text = { Text(stringResource(R.string.workflow_manga_delete_preset_message, template.name)) },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch { db.workflowTemplateDao().delete(template) }
                    templatePendingDelete = null
                }) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { templatePendingDelete = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }
    templatePendingRename?.let { template ->
        AlertDialog(
            onDismissRequest = { templatePendingRename = null },
            title = { Text(stringResource(R.string.workflow_manga_rename_preset_title)) },
            text = {
                OutlinedTextField(
                    value = renamedTemplateName,
                    onValueChange = { renamedTemplateName = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.workflow_template_name)) },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val updatedName = renamedTemplateName.trim()
                        if (updatedName.isNotBlank()) {
                            scope.launch {
                                db.workflowTemplateDao().insert(template.copy(name = updatedName))
                            }
                            templatePendingRename = null
                        }
                    },
                    enabled = renamedTemplateName.isNotBlank()
                ) {
                    Text(stringResource(R.string.action_rename))
                }
            },
            dismissButton = {
                TextButton(onClick = { templatePendingRename = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (resumableJobs.isNotEmpty()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer
                    ),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            stringResource(R.string.workflow_manga_resume_title),
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            stringResource(
                                R.string.workflow_manga_resume_desc,
                                resumableJobs.first().spec.sources.size
                            ),
                            style = MaterialTheme.typography.bodySmall
                        )
                        Button(
                            onClick = {
                                val manifest = resumableJobs.first()
                                if (PDFTranslationJobService.resumeMangaTranslation(context, manifest)) {
                                    resumableJobs = resumableJobs.drop(1)
                                }
                            },
                            enabled = !isRunning,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(stringResource(R.string.workflow_manga_resume_action))
                        }
                    }
                }
            }
            MangaStepCard(number = 1, title = stringResource(R.string.workflow_manga_files_title)) {
                Text(
                    stringResource(R.string.workflow_manga_files_help),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = onPickFiles,
                        modifier = Modifier.weight(1f),
                        enabled = !isRunning
                    ) {
                        Icon(Icons.Default.Add, null)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(stringResource(R.string.workflow_manga_add_files), maxLines = 1)
                    }
                    OutlinedButton(
                        onClick = onBrowseAllFiles,
                        modifier = Modifier.weight(1f),
                        enabled = !isRunning
                    ) {
                        Icon(Icons.Default.FolderOpen, null)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(stringResource(R.string.workflow_manga_browse), maxLines = 1)
                    }
                }
                if (sources.isEmpty()) {
                    Text(
                        stringResource(R.string.workflow_manga_no_files),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            stringResource(R.string.workflow_manga_selected_count, sources.size),
                            style = MaterialTheme.typography.labelLarge
                        )
                        TextButton(onClick = onClearFiles, enabled = !isRunning) {
                            Text(stringResource(R.string.action_clear))
                        }
                    }
                    sources.forEachIndexed { index, source ->
                        val valid = MangaTranslationSupport.isSupportedSource(source.displayName, source.mimeType)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                                    RoundedCornerShape(12.dp)
                                )
                                .padding(start = 12.dp, top = 6.dp, bottom = 6.dp, end = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                if (valid) Icons.Default.CheckCircle else Icons.Default.Error,
                                contentDescription = null,
                                tint = if (valid) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    source.displayName,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                if (!valid) {
                                    Text(
                                        stringResource(R.string.workflow_manga_unsupported_file),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                            IconButton(
                                onClick = { onRemoveFile(selectedUris[index]) },
                                enabled = !isRunning
                            ) {
                                Icon(Icons.Default.Close, stringResource(R.string.action_remove))
                            }
                        }
                    }
                }
            }

            MangaStepCard(number = 2, title = stringResource(R.string.workflow_manga_translation_card_title)) {
                OutlinedTextField(
                    value = targetLanguage,
                    onValueChange = {
                        targetLanguage = it
                        translationSettings = translationSettings.copy(targetLanguage = it)
                    },
                    label = { Text(stringResource(R.string.pdf_translation_language_label)) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isRunning,
                    singleLine = true
                )
                RemoteSummaryBackendEditor(
                    title = stringResource(R.string.workflow_manga_translation_provider),
                    backend = translationSettings.backend,
                    onBackendChange = { translationSettings = translationSettings.copy(backend = it) },
                    ollamaUrl = translationSettings.ollamaUrl,
                    onOllamaUrlChange = { translationSettings = translationSettings.copy(ollamaUrl = it) },
                    llamaServerUrl = translationSettings.llamaServerUrl,
                    onLlamaServerUrlChange = { translationSettings = translationSettings.copy(llamaServerUrl = it) },
                    llamaSwapUrl = translationSettings.llamaSwapUrl,
                    onLlamaSwapUrlChange = { translationSettings = translationSettings.copy(llamaSwapUrl = it) },
                    ollamaModel = translationSettings.ollamaModel,
                    onOllamaModelSelected = { translationSettings = translationSettings.copy(ollamaModel = it) },
                    llamaSwapModel = translationSettings.llamaSwapModel,
                    onLlamaSwapModelSelected = { translationSettings = translationSettings.copy(llamaSwapModel = it) },
                    llamaServerModelLabel = translationSettings.llamaServerModelLabel,
                    llamaServerContextLabel = translationSettings.llamaServerContextLabel,
                    llamaServerContextTokens = translationSettings.llamaServerContextTokens,
                    requestedContextForWarning = translationSettings.chunkContext,
                    liteRtModelId = translationSettings.liteRtModelId,
                    onLiteRtModelSelected = { translationSettings = translationSettings.copy(liteRtModelId = it) },
                    liteRtBackend = translationSettings.liteRtBackend,
                    onLiteRtBackendChange = { translationSettings = translationSettings.copy(liteRtBackend = it) },
                    liteRtMtpEnabled = translationSettings.liteRtMtpEnabled,
                    onLiteRtMtpEnabledChange = {
                        translationSettings = translationSettings.copy(liteRtMtpEnabled = it)
                    },
                    liteRtThinkingEnabled = translationSettings.thinkingEnabled,
                    onLiteRtThinkingEnabledChange = {
                        translationSettings = translationSettings.copy(thinkingEnabled = it)
                    },
                    fetchMetadata = {
                        RemoteSummaryClientFactory.fromSnapshot(context, translationSettings).fetchMetadata()
                    },
                    onMetadataLoaded = { metadata ->
                        translationSettings = translationSettings.copy(
                            llamaServerModelLabel = metadata.serverModelLabel,
                            llamaServerContextTokens = metadata.serverContextTokens
                                ?: translationSettings.llamaServerContextTokens,
                            llamaServerContextLabel = metadata.serverContextLabel
                        )
                    }
                )
                Text(
                    stringResource(R.string.workflow_manga_reading_direction),
                    style = MaterialTheme.typography.labelLarge
                )
                MangaReadingDirection.entries.forEach { direction ->
                    MangaChoiceRow(
                        title = mangaReadingDirectionLabel(direction),
                        selected = readingDirection == direction,
                        enabled = !isRunning,
                        onClick = { readingDirection = direction }
                    )
                }
                Text(
                    stringResource(R.string.workflow_manga_profile_title),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold
                )
                MangaTranslationProfile.entries.forEach { option ->
                    MangaProfileCard(
                        profile = option,
                        selected = profile == option,
                        enabled = !isRunning,
                        onClick = {
                            profile = option
                            behavior = MangaTranslationSupport.defaultBehavior(option)
                        }
                    )
                }
                if (profile == MangaTranslationProfile.CUSTOM) {
                    HorizontalDivider()
                    Text(
                        stringResource(R.string.workflow_manga_custom_capabilities),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold
                    )
                    MangaOutputSwitchRow(
                        title = stringResource(R.string.workflow_manga_page_understanding),
                        description = stringResource(R.string.workflow_manga_page_understanding_desc),
                        checked = behavior.pageUnderstandingEnabled,
                        enabled = !isRunning,
                        onCheckedChange = { behavior = behavior.copy(pageUnderstandingEnabled = it) }
                    )
                    MangaOutputSwitchRow(
                        title = stringResource(R.string.workflow_manga_continuity),
                        description = stringResource(R.string.workflow_manga_continuity_desc),
                        checked = behavior.continuityEnabled,
                        enabled = !isRunning,
                        onCheckedChange = { behavior = behavior.copy(continuityEnabled = it) }
                    )
                    MangaOutputSwitchRow(
                        title = stringResource(R.string.workflow_manga_correction),
                        description = stringResource(R.string.workflow_manga_correction_desc),
                        checked = behavior.correctionEnabled,
                        enabled = !isRunning,
                        onCheckedChange = { behavior = behavior.copy(correctionEnabled = it) }
                    )
                    MangaOutputSwitchRow(
                        title = stringResource(R.string.workflow_manga_page_image),
                        description = stringResource(R.string.workflow_manga_page_image_desc),
                        checked = behavior.pageImageContextEnabled,
                        enabled = !isRunning,
                        onCheckedChange = { behavior = behavior.copy(pageImageContextEnabled = it) }
                    )
                    MangaOutputSwitchRow(
                        title = stringResource(R.string.workflow_manga_translate_decorative),
                        description = stringResource(R.string.workflow_manga_translate_decorative_desc),
                        checked = behavior.translateDecorativeText,
                        enabled = !isRunning,
                        onCheckedChange = { behavior = behavior.copy(translateDecorativeText = it) }
                    )
                    MangaOutputSwitchRow(
                        title = stringResource(R.string.workflow_manga_exhaustive_llama_ocr),
                        description = stringResource(R.string.workflow_manga_exhaustive_llama_ocr_desc),
                        checked = behavior.exhaustiveLlamaOcrRegions,
                        enabled = !isRunning && translationOptions.ocrProvider == PdfOcrProvider.LLAMA_CPP_GGUF,
                        onCheckedChange = { behavior = behavior.copy(exhaustiveLlamaOcrRegions = it) }
                    )
                }
            }

            MangaStepCard(number = 3, title = stringResource(R.string.workflow_manga_recognition_title)) {
                Text(
                    stringResource(R.string.workflow_manga_recognition_help),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                PdfOcrProvider.entries.forEach { provider ->
                    MangaChoiceRow(
                        title = if (provider == PdfOcrProvider.ML_KIT) {
                            stringResource(R.string.pdf_ocr_provider_mlkit)
                        } else {
                            stringResource(R.string.pdf_ocr_provider_llama_cpp)
                        },
                        selected = translationOptions.ocrProvider == provider,
                        enabled = !isRunning,
                        onClick = { translationOptions = translationOptions.copy(ocrProvider = provider) }
                    )
                }
                if (translationOptions.ocrProvider == PdfOcrProvider.LLAMA_CPP_GGUF) {
                    Text(
                        stringResource(R.string.workflow_manga_llama_fast_preview_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    MangaInstalledModelPicker(
                        label = stringResource(R.string.workflow_manga_ocr_model),
                        models = ocrModels,
                        selectedPath = translationOptions.llamaOcr.modelPath,
                        onSelected = { model ->
                            val preset = MangaTranslationSupport.inferOcrPreset(model.filename, model.repoId)
                            val paired = MangaTranslationSupport.matchProjector(model, projectors)
                            ocrModelRef = MangaTranslationSupport.modelRef(model)
                            if (paired != null) {
                                ocrProjectorRef = MangaTranslationSupport.modelRef(paired)
                            }
                            translationOptions = translationOptions.copy(
                                llamaOcr = translationOptions.llamaOcr.copy(
                                    modelPath = model.path,
                                    mmprojPath = paired?.path ?: translationOptions.llamaOcr.mmprojPath,
                                    promptPreset = preset,
                                    customFlags = null
                                )
                            )
                        }
                    )
                    MangaInstalledModelPicker(
                        label = stringResource(R.string.workflow_manga_projector_model),
                        models = projectors,
                        selectedPath = translationOptions.llamaOcr.mmprojPath,
                        onSelected = { model ->
                            ocrProjectorRef = MangaTranslationSupport.modelRef(model)
                            translationOptions = translationOptions.copy(
                                llamaOcr = translationOptions.llamaOcr.copy(mmprojPath = model.path)
                            )
                        }
                    )
                    if (ocrModels.isEmpty() || projectors.isEmpty()) {
                        Text(
                            stringResource(R.string.workflow_manga_no_installed_ocr_models),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                        OutlinedButton(onClick = onOpenModels, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Default.ViewInAr, null)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.workflow_manga_open_models))
                        }
                    }
                    TextButton(
                        onClick = { showOcrExpert = !showOcrExpert },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(if (showOcrExpert) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.workflow_manga_expert_settings))
                    }
                    if (showOcrExpert) {
                        IntInputField(
                            value = translationOptions.llamaOcr.contextSize,
                            onValueChange = {
                                translationOptions = translationOptions.copy(
                                    llamaOcr = translationOptions.llamaOcr.copy(contextSize = it)
                                )
                            },
                            label = stringResource(R.string.pdf_context_size_label)
                        )
                        IntInputField(
                            value = translationOptions.llamaOcr.maxTokens,
                            onValueChange = {
                                translationOptions = translationOptions.copy(
                                    llamaOcr = translationOptions.llamaOcr.copy(maxTokens = it)
                                )
                            },
                            label = stringResource(R.string.pdf_max_tokens_label)
                        )
                        IntInputField(
                            value = translationOptions.llamaOcr.port,
                            onValueChange = {
                                translationOptions = translationOptions.copy(
                                    llamaOcr = translationOptions.llamaOcr.copy(port = it)
                                )
                            },
                            label = stringResource(R.string.pdf_ocr_llama_port_label)
                        )
                        OutlinedTextField(
                            value = translationOptions.llamaOcr.customPrompt.orEmpty(),
                            onValueChange = {
                                translationOptions = translationOptions.copy(
                                    llamaOcr = translationOptions.llamaOcr.copy(customPrompt = it.ifBlank { null })
                                )
                            },
                            label = { Text(stringResource(R.string.pdf_ocr_llama_prompt_label)) },
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 2
                        )
                    }
                }
                Text(
                    stringResource(R.string.workflow_manga_ocr_strategy),
                    style = MaterialTheme.typography.labelLarge
                )
                MangaOcrStrategy.entries
                    .filter { it != MangaOcrStrategy.BUBBLE_ONLY }
                    .forEach { strategy ->
                        FilterChip(
                            selected = behavior.ocrStrategy == strategy,
                            onClick = {
                                if (profile != MangaTranslationProfile.CUSTOM) {
                                    profile = MangaTranslationProfile.CUSTOM
                                }
                                behavior = behavior.copy(ocrStrategy = strategy)
                            },
                            label = { Text(mangaOcrStrategyLabel(strategy)) },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
            }

            MangaStepCard(number = 4, title = stringResource(R.string.workflow_manga_output_title)) {
                MangaOutputSwitchRow(
                    title = stringResource(R.string.workflow_manga_output_pdf),
                    description = stringResource(R.string.workflow_manga_output_pdf_desc),
                    checked = exportPdf,
                    enabled = !isRunning,
                    onCheckedChange = onExportPdfChange
                )
                MangaOutputSwitchRow(
                    title = stringResource(R.string.workflow_manga_output_cbz),
                    description = stringResource(R.string.workflow_manga_output_cbz_desc),
                    checked = exportCbz,
                    enabled = !isRunning,
                    onCheckedChange = onExportCbzChange
                )
                HorizontalDivider()
                Text(
                    stringResource(R.string.workflow_manga_readiness_title),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold
                )
                MangaReadinessRow(
                    ready = preflight.blockers.none {
                        it.code in setOf(
                            MangaPreflightCode.BACKEND_URL_MISSING,
                            MangaPreflightCode.BACKEND_MODEL_MISSING,
                            MangaPreflightCode.LITERT_MODEL_MISSING
                        )
                    },
                    label = stringResource(R.string.workflow_manga_ready_translation),
                    detail = mangaBackendSummary(translationSettings)
                )
                MangaReadinessRow(
                    ready = preflight.blockers.none {
                        it.code == MangaPreflightCode.OCR_MODEL_MISSING ||
                            it.code == MangaPreflightCode.OCR_MMPROJ_MISSING
                    },
                    label = stringResource(R.string.workflow_manga_ready_ocr),
                    detail = mangaOcrProviderSummary(translationOptions)
                )
                MangaReadinessRow(
                    ready = true,
                    label = stringResource(R.string.workflow_manga_ready_vision),
                    detail = if (!behavior.pageImageContextEnabled) {
                        stringResource(R.string.workflow_manga_disabled)
                    } else {
                        stringResource(R.string.workflow_manga_vision_checked_at_start)
                    }
                )
                MangaReadinessRow(
                    ready = true,
                    label = stringResource(R.string.workflow_manga_ready_output),
                    detail = outputFolder?.let { stringResource(R.string.workflow_manga_selected_folder) }
                        ?: stringResource(R.string.workflow_manga_app_storage)
                )
                if (preflight.blockers.isNotEmpty()) {
                    Text(
                        stringResource(R.string.workflow_manga_blockers_count, preflight.blockers.size),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = !isRunning) {
                                templatesExpanded = !templatesExpanded
                            },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                stringResource(R.string.workflow_manga_presets_title),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                stringResource(R.string.workflow_templates_count, templates.size),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Icon(
                            if (templatesExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = stringResource(
                                if (templatesExpanded) R.string.action_collapse else R.string.action_expand
                            )
                        )
                    }
                    if (templatesExpanded) {
                        if (templates.isEmpty()) {
                            Text(
                                stringResource(R.string.workflow_no_templates),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 240.dp)
                                    .verticalScroll(rememberScrollState()),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                templates.forEach { template ->
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        AssistChip(
                                            onClick = { applyTemplate(template) },
                                            label = {
                                                Text(
                                                    template.name,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            },
                                            modifier = Modifier.weight(1f)
                                        )
                                        IconButton(
                                            onClick = {
                                                renamedTemplateName = template.name
                                                templatePendingRename = template
                                            },
                                            enabled = !isRunning
                                        ) {
                                            Icon(
                                                Icons.Default.Edit,
                                                stringResource(R.string.action_rename)
                                            )
                                        }
                                        IconButton(
                                            onClick = { templatePendingDelete = template },
                                            enabled = !isRunning
                                        ) {
                                            Icon(
                                                Icons.Default.Delete,
                                                stringResource(R.string.action_delete)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        HorizontalDivider()
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
                                    val json = JSONObject()
                                        .put("workflow", "manga_translation")
                                        .put("version", MangaTranslationSupport.TEMPLATE_VERSION)
                                        .put("exportPdf", exportPdf)
                                        .put("exportCbz", exportCbz)
                                        .put("runConfig", MangaTranslationSupport.runConfigToJson(currentConfig()))
                                    db.workflowTemplateDao().insert(
                                        com.example.llamadroid.data.db.WorkflowTemplateEntity(
                                            name = templateName.trim(),
                                            type = com.example.llamadroid.data.db.WorkflowType.MANGA_TRANSLATION,
                                            configJson = json.toString()
                                        )
                                    )
                                    templateName = ""
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = templateName.isNotBlank() && !isRunning
                        ) {
                            Text(stringResource(R.string.workflow_save_template))
                        }
                    }
                }
            }

            errorMessage?.let {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(it, color = MaterialTheme.colorScheme.onErrorContainer)
                        if (!errorDetails.isNullOrBlank()) {
                            Text(
                                errorDetails,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                }
            }

            preview?.let {
                Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            stringResource(R.string.workflow_manga_preview_title),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        if (previewIsStale) {
                            Text(
                                stringResource(R.string.workflow_manga_preview_stale),
                                color = MaterialTheme.colorScheme.tertiary,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        MangaPreviewImage(
                            title = stringResource(R.string.workflow_manga_preview_original),
                            uri = it.originalPageUri
                        )
                        MangaPreviewImage(
                            title = stringResource(R.string.workflow_manga_preview_translated),
                            uri = it.translatedPageUri
                        )
                        MangaQualityReportSummary(it.qualityReport)
                    }
                }
            }

            if (results.isNotEmpty()) {
                Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        stringResource(R.string.workflow_manga_results_title),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    results.forEach { result ->
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                if (result.isSuccess && result.qualityReport.warningCount > 0) {
                                    stringResource(
                                        R.string.workflow_manga_result_warning,
                                        result.sourceName
                                    )
                                } else if (result.isSuccess) {
                                    stringResource(R.string.workflow_manga_result_success, result.sourceName)
                                } else {
                                    stringResource(
                                        R.string.workflow_manga_result_failed,
                                        result.sourceName,
                                        result.errorMessage.orEmpty()
                                    )
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium
                            )
                            if (result.isSuccess) {
                                MangaResultActions(result)
                                MangaQualityReportSummary(result.qualityReport)
                            } else if (!result.errorDetails.isNullOrBlank()) {
                                Text(
                                    result.errorDetails.orEmpty(),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
        }

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
            shape = RoundedCornerShape(16.dp)
        ) {
            if (isRunning) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(currentStep, style = MaterialTheme.typography.bodyMedium, maxLines = 2)
                    OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.Close, null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.action_cancel), maxLines = 1)
                    }
                }
            } else {
                Row(
                    modifier = Modifier.padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(
                        onClick = { onPreview(currentSpec()) },
                        enabled = preflight.canRun,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Preview, null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(stringResource(R.string.workflow_manga_preview_action), maxLines = 2)
                    }
                    Button(
                        onClick = { onRun(currentSpec()) },
                        enabled = preflight.canRun,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            stringResource(R.string.workflow_manga_translate_files, sources.size),
                            maxLines = 2
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MangaInstalledModelPicker(
    label: String,
    models: List<ModelEntity>,
    selectedPath: String?,
    onSelected: (ModelEntity) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = models.firstOrNull { it.path == selectedPath }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded }
    ) {
        OutlinedTextField(
            value = selected?.filename
                ?: selectedPath?.let { stringResource(R.string.workflow_manga_legacy_model_unavailable) }
                ?: stringResource(R.string.workflow_manga_choose_installed_model),
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .menuAnchor()
                .fillMaxWidth(),
            supportingText = selected?.let { model ->
                {
                    Text(
                        "${FormatUtils.Display.formatBytes(LocalContext.current, model.sizeBytes)} • ${model.repoId}",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            models.forEach { model ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(model.filename, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                "${FormatUtils.Display.formatBytes(LocalContext.current, model.sizeBytes)} • ${model.repoId}",
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    },
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
private fun MangaStepCard(
    number: Int,
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = RoundedCornerShape(50),
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(30.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            number.toString(),
                            color = MaterialTheme.colorScheme.onPrimary,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                Spacer(modifier = Modifier.width(10.dp))
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            content()
        }
    }
}

@Composable
private fun MangaChoiceRow(
    title: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
        },
        border = if (selected) {
            androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary)
        } else {
            null
        }
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RadioButton(selected = selected, onClick = onClick, enabled = enabled)
            Spacer(modifier = Modifier.width(8.dp))
            Text(title, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
internal fun MangaProfileCard(
    profile: MangaTranslationProfile,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val title = when (profile) {
        MangaTranslationProfile.BEST_READING -> stringResource(R.string.workflow_manga_profile_best)
        MangaTranslationProfile.BALANCED -> stringResource(R.string.workflow_manga_profile_balanced)
        MangaTranslationProfile.FAST -> stringResource(R.string.workflow_manga_profile_fast)
        MangaTranslationProfile.CUSTOM -> stringResource(R.string.workflow_manga_profile_custom)
    }
    val description = when (profile) {
        MangaTranslationProfile.BEST_READING -> stringResource(R.string.workflow_manga_profile_best_desc)
        MangaTranslationProfile.BALANCED -> stringResource(R.string.workflow_manga_profile_balanced_desc)
        MangaTranslationProfile.FAST -> stringResource(R.string.workflow_manga_profile_fast_desc)
        MangaTranslationProfile.CUSTOM -> stringResource(R.string.workflow_manga_profile_custom_desc)
    }
    val speed = when (profile) {
        MangaTranslationProfile.BEST_READING -> stringResource(R.string.workflow_manga_speed_slowest)
        MangaTranslationProfile.BALANCED -> stringResource(R.string.workflow_manga_speed_medium)
        MangaTranslationProfile.FAST -> stringResource(R.string.workflow_manga_speed_fast)
        MangaTranslationProfile.CUSTOM -> stringResource(R.string.workflow_manga_speed_varies)
    }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        border = if (selected) {
            androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary)
        } else null
    ) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.Top) {
            RadioButton(selected = selected, onClick = onClick, enabled = enabled)
            Spacer(modifier = Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(title, fontWeight = FontWeight.Bold)
                    Text(
                        speed,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun MangaReadinessRow(ready: Boolean, label: String, detail: String) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val icon: @Composable () -> Unit = {
            Icon(
                if (ready) Icons.Default.CheckCircle else Icons.Default.Warning,
                contentDescription = null,
                tint = if (ready) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.size(18.dp)
            )
        }
        if (maxWidth < 360.dp) {
            Row(verticalAlignment = Alignment.Top, modifier = Modifier.fillMaxWidth()) {
                icon()
                Spacer(modifier = Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(label, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                    Text(
                        detail,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                icon()
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    label,
                    modifier = Modifier.weight(0.9f),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    detail,
                    modifier = Modifier.weight(1.1f),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun mangaReadingDirectionLabel(direction: MangaReadingDirection): String =
    when (direction) {
        MangaReadingDirection.AUTO -> stringResource(R.string.workflow_manga_direction_auto)
        MangaReadingDirection.RIGHT_TO_LEFT -> stringResource(R.string.workflow_manga_direction_rtl)
        MangaReadingDirection.LEFT_TO_RIGHT -> stringResource(R.string.workflow_manga_direction_ltr)
    }

@Composable
private fun mangaOcrStrategyLabel(strategy: MangaOcrStrategy): String =
    when (strategy) {
        MangaOcrStrategy.HYBRID -> stringResource(R.string.workflow_manga_ocr_hybrid)
        MangaOcrStrategy.ADAPTIVE -> stringResource(R.string.workflow_manga_ocr_adaptive)
        MangaOcrStrategy.FULL_PAGE -> stringResource(R.string.workflow_manga_ocr_full_page)
        MangaOcrStrategy.BUBBLE_ONLY -> stringResource(R.string.workflow_manga_ocr_bubbles)
    }

@Composable
private fun mangaBackendSummary(snapshot: RemoteSummarySettingsSnapshot): String =
    when (SettingsRepository.normalizeOllamaOrLlamaBackend(snapshot.backend)) {
        SettingsRepository.PDF_BACKEND_LLAMA_SERVER ->
            snapshot.llamaServerModelLabel ?: stringResource(R.string.pdf_backend_llama_server)
        SettingsRepository.PDF_BACKEND_LLAMA_SWAP ->
            snapshot.llamaSwapModel ?: stringResource(R.string.pdf_backend_llama_swap)
        SettingsRepository.PDF_BACKEND_LITERT ->
            stringResource(R.string.pdf_backend_litert)
        else -> snapshot.ollamaModel ?: stringResource(R.string.pdf_backend_ollama)
    }

@Composable
private fun mangaOcrProviderSummary(options: com.example.llamadroid.data.PdfTranslationOptionsSnapshot): String =
    when (options.ocrProvider) {
        com.example.llamadroid.data.PdfOcrProvider.ML_KIT ->
            stringResource(R.string.workflow_manga_ocr_mlkit)
        com.example.llamadroid.data.PdfOcrProvider.LLAMA_CPP_GGUF ->
            stringResource(R.string.workflow_manga_ocr_gguf)
    }

@Composable
private fun MangaPreviewImage(title: String, uri: Uri) {
    val context = LocalContext.current
    val bitmap by produceState<android.graphics.Bitmap?>(initialValue = null, uri) {
        value = withContext(Dispatchers.IO) {
            if (uri.scheme == "file") {
                BitmapFactory.decodeFile(uri.path)
            } else {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    BitmapFactory.decodeStream(input)
                }
            }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.labelMedium)
        if (bitmap != null) {
            Image(
                bitmap = bitmap!!.asImageBitmap(),
                contentDescription = title,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 360.dp),
                contentScale = ContentScale.Fit
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(modifier = Modifier.size(28.dp))
            }
        }
    }
}

@Composable
private fun MangaQualityReportSummary(report: MangaTranslationQualityReport) {
    Text(
        stringResource(
            R.string.workflow_manga_quality_summary,
            report.totalPages,
            report.emptyOcrPages,
            report.weakTranslationsRetried,
            report.visionFallbacks,
            report.jsonRepairs,
            report.plainTextFallbacks,
            report.untranslatedUnits,
            report.ungroundedOcrResponses,
            report.regionalOcrFallbacks,
            report.promptLeakRejections,
            report.skippedLlamaCropRequests,
            report.decorativeTextPreserved,
            report.skippedOverlayUnits,
            report.liteRtRuntimeFallbacks,
            report.llamaOcrRequests,
            formatWorkflowDuration(report.llamaOcrElapsedMs),
            report.reconciledOcrAlternatives,
            report.coalescedBubbleFragments,
            report.incompleteTranslationRetries,
            report.wholeBubblesPreserved
        ),
        style = MaterialTheme.typography.bodySmall,
        color = if (report.warningCount > 0) {
            MaterialTheme.colorScheme.tertiary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        }
    )
}

@Composable
private fun MangaResultActions(result: MangaTranslationFileResult) {
    val context = LocalContext.current
    val outputs = buildList {
        result.pdfUri?.let { add(it to "application/pdf") }
        result.cbzUri?.let { add(it to "application/vnd.comicbook+zip") }
    }
    outputs.forEach { (uri, mimeType) ->
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = {
                    runCatching {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW).apply {
                                setDataAndType(uri, mimeType)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                        )
                    }
                },
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Default.OpenInNew, null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(stringResource(R.string.action_open))
            }
            OutlinedButton(
                onClick = {
                    val share = Intent(Intent.ACTION_SEND).apply {
                        type = mimeType
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(
                        Intent.createChooser(share, context.getString(R.string.action_share))
                    )
                },
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Default.Share, null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(stringResource(R.string.action_share))
            }
        }
    }
}

private fun mangaSourceForUri(context: Context, uri: Uri): MangaTranslationSource {
    var displayName: String? = null
    runCatching {
        context.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                displayName = cursor.getString(0)
            }
        }
    }
    return MangaTranslationSource(
        uri = uri,
        displayName = displayName
            ?: uri.lastPathSegment?.substringAfterLast('/')
            ?: uri.toString(),
        mimeType = context.contentResolver.getType(uri)
    )
}

@Composable
private fun MangaOutputSwitchRow(
    title: String,
    description: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Medium)
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled
        )
    }
}

private data class WorkflowMediaInput(
    val uri: Uri,
    val path: String,
    val name: String,
    val mimeType: String?
)

internal fun workflowIndexForMediaTranslationKind(kind: String?): Int? =
    when (kind) {
        MEDIA_TRANSLATION_WORKFLOW_KIND_MEDIA -> 4
        MEDIA_TRANSLATION_WORKFLOW_KIND_SUBTITLE -> 5
        else -> null
    }

internal fun mediaTranslationStateForWorkflow(
    state: MediaTranslationWorkflowState,
    expectedKind: String
): MediaTranslationWorkflowState =
    if (state.workflowKind == expectedKind || (state.workflowKind == null && !state.isRunning && state.finalOutputPath == null && state.errorMessage == null)) {
        state
    } else {
        MediaTranslationWorkflowState()
    }

private data class WorkflowOutputGalleryFile(
    val file: File,
    val title: String,
    val kind: String,
    val mimeType: String
)

private data class WorkflowOutputTiming(
    val label: String,
    val durationMs: Long
)

private data class WorkflowOutputGallerySet(
    val directory: File,
    val title: String,
    val subtitle: String,
    val outputKind: String,
    val files: List<WorkflowOutputGalleryFile>,
    val timings: List<WorkflowOutputTiming>,
    val primaryFile: WorkflowOutputGalleryFile?,
    val thumbnailFile: File?,
    val totalSizeBytes: Long,
    val segmentCount: Int,
    val modifiedTimeMillis: Long
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun WorkflowOutputGalleryCard(
    folderName: String,
    refreshKey: Any?,
    isWorkflowRunning: Boolean = false,
    resumeTranslationOverride: MediaTranslationResumeTranslationOverride? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var refreshTick by remember { mutableIntStateOf(0) }
    var selectedSet by remember { mutableStateOf<WorkflowOutputGallerySet?>(null) }
    var pendingDeleteSet by remember { mutableStateOf<WorkflowOutputGallerySet?>(null) }
    var pendingDeleteFile by remember { mutableStateOf<WorkflowOutputGalleryFile?>(null) }
    var selectedDirectories by remember { mutableStateOf<Set<String>>(emptySet()) }
    var recoverableRuntimeInfo by remember(folderName) { mutableStateOf<MediaTranslationRecoverableInfo?>(null) }
    var importedInputBytes by remember(folderName) { mutableLongStateOf(0L) }
    var pendingClearImportedInputs by remember { mutableStateOf(false) }
    var pendingDiscardRecoverable by remember { mutableStateOf<MediaTranslationRecoverableInfo?>(null) }
    val scope = rememberCoroutineScope()
    val outputs = remember(folderName, refreshKey, refreshTick) {
        scanWorkflowOutputGallery(context, folderName)
    }
    val isSelectionMode = selectedDirectories.isNotEmpty()
    val resumeRequest: (() -> Unit)? = when (folderName) {
        "workflow_media_translation" -> ({ MediaTranslationWorkflowService.requestResumeMedia(context, resumeTranslationOverride) })
        "workflow_subtitle_translation" -> ({ MediaTranslationWorkflowService.requestResumeSubtitle(context, resumeTranslationOverride) })
        else -> null
    }
    val discardRecoverableRequest: (() -> Unit)? = when (folderName) {
        "workflow_media_translation" -> ({ MediaTranslationWorkflowService.discardRecoverableMediaRuntime(context) })
        "workflow_subtitle_translation" -> ({ MediaTranslationWorkflowService.discardRecoverableSubtitleRuntime(context) })
        else -> null
    }

    LaunchedEffect(folderName, refreshKey, refreshTick, isWorkflowRunning) {
        recoverableRuntimeInfo = null
        recoverableRuntimeInfo = withContext(Dispatchers.IO) {
            when (folderName) {
                "workflow_media_translation" -> MediaTranslationWorkflowService.getRecoverableMediaRuntimeInfo(context)
                "workflow_subtitle_translation" -> MediaTranslationWorkflowService.getRecoverableSubtitleRuntimeInfo(context)
                else -> null
            }
        }
        importedInputBytes = withContext(Dispatchers.IO) {
            MediaTranslationWorkflowService.workflowImportedInputBytes(context)
        }
    }

    fun refreshGallery() {
        selectedDirectories = emptySet()
        selectedSet = null
        refreshTick++
    }

    Card(modifier = modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            if (isSelectionMode) stringResource(R.string.workflow_output_gallery_selected_count, selectedDirectories.size)
                            else stringResource(R.string.workflow_output_gallery_title),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        if (!isSelectionMode) {
                            Text(
                                stringResource(R.string.workflow_output_gallery_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    if (!isSelectionMode) {
                        TextButton(onClick = { refreshTick++ }) {
                            Text(stringResource(R.string.action_refresh))
                        }
                    }
                }
                if (isSelectionMode) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { selectedDirectories = emptySet() }) {
                            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.action_cancel))
                        }
                        IconButton(onClick = {
                            outputs.firstOrNull { it.directory.absolutePath in selectedDirectories }?.let { pendingDeleteSet = it }
                        }) {
                            Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.action_delete), tint = MaterialTheme.colorScheme.error)
                        }
                    }
                } else {
                    val resumeInfo = recoverableRuntimeInfo
                    if (resumeInfo != null && resumeRequest != null) {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
                            tonalElevation = 1.dp
                        ) {
                            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(
                                    resumeInfo.sourceName,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    stringResource(
                                        R.string.workflow_output_gallery_resume_named_hint,
                                        resumeInfo.sourceName,
                                        resumeInfo.completedCount,
                                        resumeInfo.totalCount
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                                if (resumeTranslationOverride != null) {
                                    Text(
                                        stringResource(R.string.workflow_output_gallery_resume_current_engine_hint),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    OutlinedButton(
                                        onClick = {
                                            resumeRequest()
                                            refreshTick++
                                        },
                                        enabled = !isWorkflowRunning,
                                        modifier = Modifier.weight(1f),
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                                    ) {
                                        Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text(
                                            stringResource(R.string.workflow_output_gallery_resume_button),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    if (discardRecoverableRequest != null) {
                                        OutlinedButton(
                                            onClick = { pendingDiscardRecoverable = resumeInfo },
                                            enabled = !isWorkflowRunning,
                                            modifier = Modifier.weight(1f),
                                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                                        ) {
                                            Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                                            Spacer(Modifier.width(6.dp))
                                            Text(
                                                stringResource(R.string.workflow_output_gallery_discard_resume_button),
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                    if (importedInputBytes > 0L) {
                        OutlinedButton(
                            onClick = { pendingClearImportedInputs = true },
                            enabled = !isWorkflowRunning,
                            modifier = Modifier.fillMaxWidth(),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Icon(Icons.Default.DeleteSweep, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(
                                stringResource(
                                    R.string.workflow_imported_inputs_clear_button,
                                    FormatUtils.Display.formatBytes(context, importedInputBytes)
                                ),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
            if (outputs.isEmpty()) {
                Text(
                    stringResource(R.string.workflow_output_gallery_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(150.dp),
                    modifier = Modifier.fillMaxWidth().heightIn(max = 460.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(outputs, key = { it.directory.absolutePath }) { set ->
                        val isSelected = set.directory.absolutePath in selectedDirectories
                        WorkflowOutputGallerySetCard(
                            set = set,
                            isSelectionMode = isSelectionMode,
                            isSelected = isSelected,
                            onClick = {
                                if (isSelectionMode) {
                                    selectedDirectories = if (isSelected) selectedDirectories - set.directory.absolutePath
                                    else selectedDirectories + set.directory.absolutePath
                                } else {
                                    selectedSet = set
                                }
                            },
                            onLongClick = {
                                selectedDirectories = selectedDirectories + set.directory.absolutePath
                            }
                        )
                    }
                }
            }
        }
    }

    selectedSet?.let { set ->
        AlertDialog(
            onDismissRequest = { selectedSet = null },
            title = { Text(set.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    WorkflowOutputThumbnail(
                        file = set.thumbnailFile,
                        mimeType = set.primaryFile?.mimeType.orEmpty(),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp)
                    )
                    Text(set.subtitle, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        stringResource(
                            R.string.workflow_output_gallery_set_details,
                            set.files.size,
                            set.segmentCount,
                            FormatUtils.Display.formatBytes(context, set.totalSizeBytes)
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (set.timings.isNotEmpty()) {
                        Text(
                            stringResource(R.string.workflow_output_gallery_process_title),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        set.timings.forEach { timing ->
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(timing.label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                                Text(formatWorkflowDuration(timing.durationMs), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                            }
                        }
                    }
                    Text(
                        stringResource(R.string.workflow_output_gallery_files_title),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    set.files.forEach { item ->
                        WorkflowOutputFileRow(
                            item = item,
                            onOpen = { openWorkflowOutput(context, item) },
                            onShare = { shareWorkflowOutput(context, item) },
                            onDelete = { pendingDeleteFile = item }
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { pendingDeleteSet = set }) {
                    Text(stringResource(R.string.workflow_output_gallery_delete_set), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { selectedSet = null }) {
                    Text(stringResource(R.string.action_close))
                }
            }
        )
    }

    pendingDeleteFile?.let { item ->
        AlertDialog(
            onDismissRequest = { pendingDeleteFile = null },
            title = { Text(stringResource(R.string.workflow_output_gallery_delete_file_title)) },
            text = { Text(stringResource(R.string.workflow_output_gallery_delete_file_message, item.title)) },
            confirmButton = {
                TextButton(onClick = {
                    if (item.file.delete()) {
                        Toast.makeText(context, context.getString(R.string.workflow_output_gallery_deleted), Toast.LENGTH_SHORT).show()
                    }
                    pendingDeleteFile = null
                    refreshGallery()
                }) {
                    Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteFile = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    pendingDeleteSet?.let { firstSet ->
        val setsToDelete = if (isSelectionMode) outputs.filter { it.directory.absolutePath in selectedDirectories } else listOf(firstSet)
        AlertDialog(
            onDismissRequest = { pendingDeleteSet = null },
            title = { Text(stringResource(R.string.workflow_output_gallery_delete_sets_title, setsToDelete.size)) },
            text = { Text(stringResource(R.string.workflow_output_gallery_delete_sets_message, setsToDelete.size)) },
            confirmButton = {
                TextButton(onClick = {
                    setsToDelete.forEach { it.directory.deleteRecursively() }
                    Toast.makeText(context, context.getString(R.string.workflow_output_gallery_deleted_sets, setsToDelete.size), Toast.LENGTH_SHORT).show()
                    pendingDeleteSet = null
                    refreshGallery()
                }) {
                    Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteSet = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    if (pendingClearImportedInputs) {
        AlertDialog(
            onDismissRequest = { pendingClearImportedInputs = false },
            title = { Text(stringResource(R.string.workflow_imported_inputs_clear_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.workflow_imported_inputs_clear_message,
                        FormatUtils.Display.formatBytes(context, importedInputBytes)
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingClearImportedInputs = false
                    scope.launch {
                        val result = withContext(Dispatchers.IO) {
                            MediaTranslationWorkflowService.clearWorkflowImportedInputs(context)
                        }
                        importedInputBytes = withContext(Dispatchers.IO) {
                            MediaTranslationWorkflowService.workflowImportedInputBytes(context)
                        }
                        Toast.makeText(
                            context,
                            context.getString(
                                R.string.workflow_imported_inputs_cleared,
                                result.deletedCount,
                                FormatUtils.Display.formatBytes(context, result.freedBytes)
                            ),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }) {
                    Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingClearImportedInputs = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    pendingDiscardRecoverable?.let { resumeInfo ->
        AlertDialog(
            onDismissRequest = { pendingDiscardRecoverable = null },
            title = { Text(stringResource(R.string.workflow_output_gallery_discard_resume_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.workflow_output_gallery_discard_resume_message,
                        resumeInfo.sourceName
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingDiscardRecoverable = null
                    discardRecoverableRequest?.invoke()
                    recoverableRuntimeInfo = null
                    refreshTick++
                    Toast.makeText(
                        context,
                        context.getString(R.string.workflow_output_gallery_discard_resume_done),
                        Toast.LENGTH_SHORT
                    ).show()
                }) {
                    Text(stringResource(R.string.workflow_output_gallery_discard_resume_button), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDiscardRecoverable = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun WorkflowOutputGallerySetCard(
    set: WorkflowOutputGallerySet,
    isSelectionMode: Boolean,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .then(if (isSelected) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(12.dp)) else Modifier),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column {
            Box {
                WorkflowOutputThumbnail(
                    file = set.thumbnailFile,
                    mimeType = set.primaryFile?.mimeType.orEmpty(),
                    modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f)
                )
                if (isSelectionMode) {
                    Icon(
                        if (isSelected) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                        contentDescription = null,
                        tint = if (isSelected) MaterialTheme.colorScheme.primary else Color.White,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(8.dp)
                            .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(18.dp))
                            .padding(2.dp)
                    )
                }
            }
            Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(set.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                Text(
                    set.subtitle,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    stringResource(R.string.workflow_output_gallery_card_meta, set.outputKind, set.files.size, set.segmentCount),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@Composable
private fun WorkflowOutputThumbnail(
    file: File?,
    mimeType: String,
    modifier: Modifier = Modifier
) {
    val bitmap by produceState<android.graphics.Bitmap?>(initialValue = null, file?.absolutePath, mimeType) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                when {
                    file == null || !file.isFile -> null
                    mimeType.startsWith("video/") -> {
                        val retriever = MediaMetadataRetriever()
                        try {
                            retriever.setDataSource(file.absolutePath)
                            retriever.getFrameAtTime(1_000_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                        } finally {
                            retriever.release()
                        }
                    }
                    mimeType.startsWith("image/") -> BitmapFactory.decodeFile(file.absolutePath)
                    else -> null
                }
            }.getOrNull()
        }
    }
    Box(
        modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap!!.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        } else {
            Icon(
                when {
                    mimeType.startsWith("video/") -> Icons.Default.Movie
                    mimeType.startsWith("audio/") -> Icons.Default.GraphicEq
                    else -> Icons.AutoMirrored.Filled.List
                },
                contentDescription = null,
                modifier = Modifier.size(44.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun WorkflowOutputFileRow(
    item: WorkflowOutputGalleryFile,
    onOpen: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                when {
                    item.mimeType.startsWith("video/") -> Icons.Default.Movie
                    item.mimeType.startsWith("audio/") -> Icons.Default.GraphicEq
                    else -> Icons.AutoMirrored.Filled.List
                },
                contentDescription = null
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(item.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                Text(
                    "${item.kind} • ${FormatUtils.Technical.formatBytes(item.file.length())}",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onOpen) {
                Icon(Icons.Default.OpenInNew, contentDescription = stringResource(R.string.action_open))
            }
            IconButton(onClick = onShare) {
                Icon(Icons.Default.Share, contentDescription = stringResource(R.string.action_share))
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.action_delete), tint = MaterialTheme.colorScheme.error)
            }
        }
    }
}

private fun scanWorkflowOutputGallery(context: Context, folderName: String): List<WorkflowOutputGallerySet> {
    val root = File(context.filesDir, folderName)
    if (!root.isDirectory) return emptyList()
    return root.listFiles()
        ?.filter { it.isDirectory }
        ?.mapNotNull { directory ->
            val files = directory.listFiles()
                ?.filter { it.isFile && it.extension.lowercase(Locale.US) in setOf("mp4", "m4a", "mp3", "wav", "srt", "txt", "json", "png", "jpg", "jpeg", "webp") }
                ?.sortedWith(compareByDescending<File> { workflowGalleryFilePriority(it) }.thenBy { it.name })
                ?.map { file ->
                    WorkflowOutputGalleryFile(
                        file = file,
                        title = file.name,
                        kind = workflowGalleryFileKind(context, file),
                        mimeType = workflowOutputMimeType(file)
                    )
                }
                .orEmpty()
            if (files.isEmpty()) return@mapNotNull null
            val primary = files.firstOrNull { it.mimeType.startsWith("video/") }
                ?: files.firstOrNull { it.mimeType.startsWith("audio/") }
                ?: files.firstOrNull { workflowGalleryIsTranslatedSubtitleFileName(it.file.name) }
                ?: files.firstOrNull()
            val metadata = readWorkflowGalleryMetadata(directory)
            val translatedSrt = files.firstOrNull { workflowGalleryIsTranslatedSubtitleFileName(it.file.name) }?.file
            val originalSrt = files.firstOrNull { workflowGalleryIsOriginalSubtitleFileName(it.file.name) }?.file
            val segmentCount = metadata?.optInt("segments", 0)?.takeIf { it > 0 }
                ?: (translatedSrt ?: originalSrt)?.let(::countWorkflowSrtSegments)
                ?: 0
            val title = metadata?.optString("sourceName")?.takeIf { it.isNotBlank() }
                ?: primary?.file?.nameWithoutExtension?.replace('_', ' ')
                ?: directory.name
            val outputKind = when {
                primary?.mimeType?.startsWith("video/") == true -> context.getString(R.string.workflow_output_gallery_kind_video)
                primary?.mimeType?.startsWith("audio/") == true -> context.getString(R.string.workflow_output_gallery_kind_audio)
                else -> context.getString(R.string.workflow_output_gallery_kind_subtitles)
            }
            WorkflowOutputGallerySet(
                directory = directory,
                title = title,
                subtitle = context.getString(R.string.workflow_output_gallery_set_subtitle, outputKind, workflowGalleryDate(directory.lastModified())),
                outputKind = outputKind,
                files = files,
                timings = workflowGalleryTimings(context, metadata),
                primaryFile = primary,
                thumbnailFile = primary?.file?.takeIf { primary.mimeType.startsWith("video/") || primary.mimeType.startsWith("image/") },
                totalSizeBytes = files.sumOf { it.file.length() },
                segmentCount = segmentCount,
                modifiedTimeMillis = directory.lastModified()
            )
        }
        ?.sortedByDescending { it.modifiedTimeMillis }
        ?.take(40)
        .orEmpty()
}

private fun readWorkflowGalleryMetadata(directory: File): org.json.JSONObject? =
    runCatching {
        File(directory, "workflow_metadata.json").takeIf { it.isFile }?.let { org.json.JSONObject(it.readText()) }
    }.getOrNull()

private fun workflowGalleryTimings(context: Context, metadata: org.json.JSONObject?): List<WorkflowOutputTiming> {
    metadata ?: return emptyList()
    val entries = listOf(
        "totalDurationMs" to context.getString(R.string.workflow_output_gallery_time_total),
        "extractAudioDurationMs" to context.getString(R.string.workflow_output_gallery_time_extract_audio),
        "transcriptionDurationMs" to context.getString(R.string.workflow_output_gallery_time_transcription),
        "translationDurationMs" to context.getString(R.string.workflow_output_gallery_time_translation),
        "ttsDurationMs" to context.getString(R.string.workflow_output_gallery_time_tts),
        "audioExportDurationMs" to context.getString(R.string.workflow_output_gallery_time_audio_export),
        "muxOrExportDurationMs" to context.getString(R.string.workflow_output_gallery_time_mux_export),
        "subtitleBurnDurationMs" to context.getString(R.string.workflow_output_gallery_time_subtitle_burn)
    )
    return entries.mapNotNull { (key, label) ->
        val value = metadata.optLong(key, 0L)
        if (value > 0L || key == "totalDurationMs") WorkflowOutputTiming(label, value) else null
    }
}

private fun workflowGalleryFilePriority(file: File): Int =
    when (file.extension.lowercase(Locale.US)) {
        "mp4" -> 5
        "m4a", "mp3", "wav" -> 4
        "srt" -> if (workflowGalleryIsTranslatedSubtitleFileName(file.name)) 3 else 2
        "txt" -> 1
        else -> 0
    }

private fun workflowGalleryFileKind(context: Context, file: File): String =
    when {
        file.name == "workflow_metadata.json" -> context.getString(R.string.workflow_output_gallery_file_metadata)
        workflowGalleryIsOriginalSubtitleFileName(file.name) -> context.getString(R.string.workflow_output_gallery_file_original_srt)
        workflowGalleryIsTranslatedSubtitleFileName(file.name) -> context.getString(R.string.workflow_output_gallery_file_translated_srt)
        file.name == "original_transcript.txt" -> context.getString(R.string.workflow_output_gallery_file_original_text)
        file.name == "translated_audio.m4a" -> context.getString(R.string.workflow_output_gallery_file_translated_audio)
        else -> when (file.extension.lowercase(Locale.US)) {
            "mp4" -> context.getString(R.string.workflow_output_gallery_file_final_video)
            "m4a", "mp3", "wav" -> context.getString(R.string.workflow_output_gallery_file_final_audio)
            else -> context.getString(R.string.workflow_output_gallery_file_output)
        }
    }

internal fun workflowGalleryIsOriginalSubtitleFileName(fileName: String): Boolean =
    fileName == "original.srt" || (fileName.startsWith("original_") && fileName.endsWith(".srt", ignoreCase = true))

internal fun workflowGalleryIsTranslatedSubtitleFileName(fileName: String): Boolean =
    fileName == "translated.srt" || (fileName.startsWith("translated_") && fileName.endsWith(".srt", ignoreCase = true))

private fun workflowGalleryDate(modifiedTimeMillis: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(modifiedTimeMillis))

private fun countWorkflowSrtSegments(file: File): Int =
    runCatching {
        file.readText()
            .replace("\r\n", "\n")
            .split(Regex("""\n{2,}"""))
            .count { block -> Regex("""\d{2}:\d{2}:\d{2},\d{3}\s*-->""").containsMatchIn(block) }
    }.getOrDefault(0)

private fun formatWorkflowDuration(durationMs: Long): String =
    FormatUtils.Display.formatDuration(durationMs / 1000.0)

private fun workflowOutputMimeType(file: File): String =
    when (file.extension.lowercase(Locale.US)) {
        "mp4" -> "video/mp4"
        "m4a" -> "audio/mp4"
        "mp3" -> "audio/mpeg"
        "wav" -> "audio/wav"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "webp" -> "image/webp"
        "srt" -> "application/x-subrip"
        "txt" -> "text/plain"
        "json" -> "application/json"
        else -> "application/octet-stream"
    }

private fun openWorkflowOutput(context: Context, item: WorkflowOutputGalleryFile) {
    runCatching {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", item.file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, item.mimeType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, context.getString(R.string.workflow_output_gallery_open_with)))
    }.onFailure { error ->
        Toast.makeText(
            context,
            context.getString(R.string.workflow_output_gallery_open_failed, error.message ?: context.getString(R.string.error_generic)),
            Toast.LENGTH_SHORT
        ).show()
    }
}

private fun shareWorkflowOutput(context: Context, item: WorkflowOutputGalleryFile) {
    runCatching {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", item.file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = item.mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, context.getString(R.string.action_share)))
    }.onFailure { error ->
        Toast.makeText(
            context,
            context.getString(R.string.workflow_output_gallery_open_failed, error.message ?: context.getString(R.string.error_generic)),
            Toast.LENGTH_SHORT
        ).show()
    }
}

private fun mediaWorkflowExtensionForMime(mimeType: String?): String =
    when {
        mimeType?.startsWith("video/") == true -> "mp4"
        mimeType?.contains("mpeg") == true -> "mp3"
        mimeType?.contains("wav") == true -> "wav"
        mimeType?.contains("mp4") == true -> "m4a"
        mimeType?.contains("ogg") == true -> "ogg"
        mimeType?.contains("flac") == true -> "flac"
        else -> "media"
    }

private fun isWorkflowTranslationBackendReady(
    backend: String,
    ollamaUrl: String,
    ollamaModel: String?,
    llamaServerUrl: String,
    llamaSwapUrl: String,
    llamaSwapModel: String?,
    liteRtModelId: Long
): Boolean =
    when (SettingsRepository.normalizeOllamaOrLlamaBackend(backend)) {
        SettingsRepository.PDF_BACKEND_LLAMA_SERVER -> llamaServerUrl.isNotBlank()
        SettingsRepository.PDF_BACKEND_LLAMA_SWAP -> llamaSwapUrl.isNotBlank() && !llamaSwapModel.isNullOrBlank()
        SettingsRepository.PDF_BACKEND_LITERT -> liteRtModelId > 0L
        else -> ollamaUrl.isNotBlank() && !ollamaModel.isNullOrBlank()
    }

private fun workflowResumeTranslationOverride(
    backend: String,
    ollamaUrl: String,
    llamaServerUrl: String,
    llamaSwapUrl: String,
    ollamaModel: String?,
    llamaSwapModel: String?,
    liteRtModelId: Long,
    liteRtBackend: String,
    liteRtMtpEnabled: Boolean,
    thinkingEnabled: Boolean,
    llamaServerModelLabel: String?,
    llamaServerContextLabel: String?,
    llamaServerContextTokens: Int,
    contextSize: Int,
    maxTokens: Int,
    temperature: Float,
    timeoutMinutes: Int,
    targetLanguage: String,
    translationContextEnabled: Boolean,
    translationContextLines: Int,
    skipFailedTranslationLines: Boolean
): MediaTranslationResumeTranslationOverride =
    MediaTranslationResumeTranslationOverride(
        targetLanguage = targetLanguage,
        backendSnapshot = RemoteSummarySettingsSnapshot(
            backend = backend,
            ollamaUrl = ollamaUrl,
            llamaServerUrl = llamaServerUrl,
            llamaSwapUrl = llamaSwapUrl,
            ollamaModel = ollamaModel,
            llamaSwapModel = llamaSwapModel,
            liteRtModelId = liteRtModelId.takeIf { it > 0L },
            liteRtBackend = liteRtBackend,
            liteRtMtpEnabled = liteRtMtpEnabled,
            thinkingEnabled = thinkingEnabled,
            llamaServerModelLabel = llamaServerModelLabel,
            llamaServerContextTokens = llamaServerContextTokens,
            llamaServerContextLabel = llamaServerContextLabel,
            chunkContext = contextSize,
            chunkMaxTokens = maxTokens,
            mergeContext = contextSize,
            mergeMaxTokens = maxTokens,
            temperature = temperature,
            timeoutMinutes = timeoutMinutes,
            targetLanguage = targetLanguage,
            summaryPrompt = null,
            mergePrompt = null
        ),
        translationContextEnabled = translationContextEnabled,
        translationContextLines = translationContextLines.coerceIn(0, 10),
        skipFailedTranslationLines = skipFailedTranslationLines
    )

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SubtitleTranslationWorkflowContent(
    db: AppDatabase,
    settingsRepo: SettingsRepository,
    videoUri: Uri?,
    videoPath: String?,
    videoName: String,
    onVideoSelected: (Uri, String, String) -> Unit,
    videoBatch: List<WorkflowMediaInput>,
    onVideoBatchSelected: (List<WorkflowMediaInput>) -> Unit,
    subtitleUri: Uri?,
    subtitlePath: String?,
    subtitleName: String,
    onSubtitleSelected: (Uri, String, String) -> Unit,
    onClearSubtitle: () -> Unit,
    whisperModelPath: String?,
    onWhisperModelChange: (String?) -> Unit,
    whisperLanguage: String,
    onWhisperLanguageChange: (String) -> Unit,
    whisperThreads: Int,
    onWhisperThreadsChange: (Int) -> Unit,
    targetLanguage: String,
    onTargetLanguageChange: (String) -> Unit,
    translateSubtitles: Boolean,
    onTranslateSubtitlesChange: (Boolean) -> Unit,
    backend: String,
    onBackendChange: (String) -> Unit,
    ollamaUrl: String,
    onOllamaUrlChange: (String) -> Unit,
    llamaServerUrl: String,
    onLlamaServerUrlChange: (String) -> Unit,
    llamaSwapUrl: String,
    onLlamaSwapUrlChange: (String) -> Unit,
    ollamaModel: String?,
    onOllamaModelChange: (String?) -> Unit,
    llamaSwapModel: String?,
    onLlamaSwapModelChange: (String?) -> Unit,
    liteRtModelId: Long,
    llamaServerModelLabel: String?,
    llamaServerContextLabel: String?,
    llamaServerContextTokens: Int,
    contextSize: Int,
    maxTokens: Int,
    temperature: Float,
    timeoutMinutes: Int,
    thinkingEnabled: Boolean,
    translationContextEnabled: Boolean,
    onTranslationContextEnabledChange: (Boolean) -> Unit,
    translationContextLines: Int,
    onTranslationContextLinesChange: (Int) -> Unit,
    skipFailedTranslationLines: Boolean,
    onSkipFailedTranslationLinesChange: (Boolean) -> Unit,
    burnIntoVideo: Boolean,
    onBurnIntoVideoChange: (Boolean) -> Unit,
    fontSize: Int,
    onFontSizeChange: (Int) -> Unit,
    alignment: Int,
    onAlignmentChange: (Int) -> Unit,
    marginV: Int,
    onMarginVChange: (Int) -> Unit,
    marginL: Int,
    onMarginLChange: (Int) -> Unit,
    primaryColor: Color,
    onPrimaryColorChange: (Color) -> Unit,
    fontName: String,
    onFontNameChange: (String) -> Unit,
    state: MediaTranslationWorkflowState,
    onRun: () -> Unit,
    onCancel: () -> Unit,
    onPause: () -> Unit,
    onMetadataLoaded: (RemoteSummaryMetadata) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val whisperModels by db.modelDao().getModelsByType(ModelType.WHISPER).collectAsState(initial = emptyList())
    val templates by db.workflowTemplateDao()
        .getByType(com.example.llamadroid.data.db.WorkflowType.SUBTITLE_TRANSLATION)
        .collectAsState(initial = emptyList())
    var showTemplateMenu by remember { mutableStateOf(false) }
    var showSaveDialog by remember { mutableStateOf(false) }
    var showGalleryDialog by remember { mutableStateOf(false) }
    val liteRtBackend by settingsRepo.workflowSummaryLiteRtBackend.collectAsState()
    val liteRtMtpEnabled by settingsRepo.workflowSummaryLiteRtMtpEnabled.collectAsState()

    val videoPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        try {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: Exception) {
        }
        try {
            val displayName = uri.lastPathSegment?.substringAfterLast('/') ?: context.getString(R.string.workflow_subtitle_translate_video_placeholder)
            val inputDir = File(context.filesDir, "workflow_media_inputs").apply { mkdirs() }
            val tempFile = File(inputDir, "workflow_subtitle_video_${System.currentTimeMillis()}.mp4")
            context.contentResolver.openInputStream(uri)?.use { input ->
                tempFile.outputStream().use { output -> input.copyTo(output) }
            }
            onVideoSelected(uri, tempFile.absolutePath, displayName)
        } catch (error: Exception) {
            MediaTranslationWorkflowStateHolder.update {
                it.copy(
                    workflowKind = MEDIA_TRANSLATION_WORKFLOW_KIND_SUBTITLE,
                    errorMessage = context.getString(R.string.workflow_error_load_audio, error.message ?: context.getString(R.string.error_generic))
                )
            }
        }
    }
    val videoBatchPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        val items = uris.mapNotNull { uri ->
            try {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Exception) {
            }
            runCatching {
                val displayName = uri.lastPathSegment?.substringAfterLast('/') ?: context.getString(R.string.workflow_subtitle_translate_video_placeholder)
                val inputDir = File(context.filesDir, "workflow_media_inputs").apply { mkdirs() }
                val tempFile = File(inputDir, "workflow_subtitle_video_${System.currentTimeMillis()}_${displayName.hashCode()}.mp4")
                context.contentResolver.openInputStream(uri)?.use { input ->
                    tempFile.outputStream().use { output -> input.copyTo(output) }
                }
                WorkflowMediaInput(uri, tempFile.absolutePath, displayName, context.contentResolver.getType(uri))
            }.getOrNull()
        }
        if (items.isNotEmpty()) onVideoBatchSelected(items)
    }
    val subtitlePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        try {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: Exception) {
        }
        try {
            val displayName = uri.lastPathSegment?.substringAfterLast('/') ?: context.getString(R.string.workflow_subtitle_translate_srt_placeholder)
            val inputDir = File(context.filesDir, "workflow_media_inputs").apply { mkdirs() }
            val tempFile = File(inputDir, "workflow_source_subtitles_${System.currentTimeMillis()}.srt")
            context.contentResolver.openInputStream(uri)?.use { input ->
                tempFile.outputStream().use { output -> input.copyTo(output) }
            }
            onSubtitleSelected(uri, tempFile.absolutePath, displayName)
        } catch (error: Exception) {
            MediaTranslationWorkflowStateHolder.update {
                it.copy(
                    workflowKind = MEDIA_TRANSLATION_WORKFLOW_KIND_SUBTITLE,
                    errorMessage = context.getString(R.string.workflow_error_load_audio, error.message ?: context.getString(R.string.error_generic))
                )
            }
        }
    }

    if (showGalleryDialog) {
        AlertDialog(
            onDismissRequest = { showGalleryDialog = false },
            confirmButton = {
                TextButton(onClick = { showGalleryDialog = false }) {
                    Text(stringResource(R.string.action_close))
                }
            },
            text = {
                WorkflowOutputGalleryCard(
                    folderName = "workflow_subtitle_translation",
                    refreshKey = state.finalOutputPath,
                    isWorkflowRunning = state.isRunning,
                    resumeTranslationOverride = if (translateSubtitles) {
                        workflowResumeTranslationOverride(
                            backend = backend,
                            ollamaUrl = ollamaUrl,
                            llamaServerUrl = llamaServerUrl,
                            llamaSwapUrl = llamaSwapUrl,
                            ollamaModel = ollamaModel,
                            llamaSwapModel = llamaSwapModel,
                            liteRtModelId = liteRtModelId,
                            liteRtBackend = liteRtBackend,
                            liteRtMtpEnabled = liteRtMtpEnabled,
                            thinkingEnabled = thinkingEnabled,
                            llamaServerModelLabel = llamaServerModelLabel,
                            llamaServerContextLabel = llamaServerContextLabel,
                            llamaServerContextTokens = llamaServerContextTokens,
                            contextSize = contextSize,
                            maxTokens = maxTokens,
                            temperature = temperature,
                            timeoutMinutes = timeoutMinutes,
                            targetLanguage = targetLanguage,
                            translationContextEnabled = translationContextEnabled,
                            translationContextLines = translationContextLines,
                            skipFailedTranslationLines = skipFailedTranslationLines
                        )
                    } else null
                )
            }
        )
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                stringResource(R.string.workflow_subtitle_translate_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 2
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(modifier = Modifier.weight(1f)) {
                    OutlinedButton(
                        onClick = { showTemplateMenu = true },
                        enabled = !state.isRunning,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Settings, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.workflow_templates_btn, templates.size), maxLines = 1)
                    }
                    DropdownMenu(
                        expanded = showTemplateMenu,
                        onDismissRequest = { showTemplateMenu = false },
                        modifier = Modifier.widthIn(min = 280.dp, max = 360.dp)
                    ) {
                        if (templates.isEmpty()) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.workflow_no_templates), color = MaterialTheme.colorScheme.onSurfaceVariant) },
                                onClick = {}
                            )
                        } else {
                            templates.forEach { template ->
                                DropdownMenuItem(
                                    text = { Text(template.name, maxLines = 1) },
                                    onClick = {
                                        runCatching {
                                            val json = org.json.JSONObject(template.configJson)
                                            onWhisperModelChange(json.optString("whisperModel").takeIf { it.isNotBlank() })
                                            onWhisperLanguageChange(json.optString("whisperLanguage", whisperLanguage))
                                            onWhisperThreadsChange(json.optInt("whisperThreads", whisperThreads))
                                            json.readWhisperVadConfigOrNull("whisperVad")
                                                ?.let(settingsRepo::applyWhisperVadConfig)
                                            onTargetLanguageChange(json.optString("targetLanguage", targetLanguage))
                                            onTranslateSubtitlesChange(json.optBoolean("translateSubtitles", translateSubtitles))
                                            onTranslationContextEnabledChange(json.optBoolean("translationContextEnabled", translationContextEnabled))
                                            onTranslationContextLinesChange(json.optInt("translationContextLines", translationContextLines).coerceIn(0, 10))
                                            onSkipFailedTranslationLinesChange(json.optBoolean("skipFailedTranslationLines", skipFailedTranslationLines))
                                            onBurnIntoVideoChange(json.optBoolean("burnIntoVideo", burnIntoVideo))
                                            onFontSizeChange(json.optInt("fontSize", fontSize))
                                            onAlignmentChange(json.optInt("alignment", alignment))
                                            onMarginVChange(json.optInt("marginV", marginV))
                                            onMarginLChange(json.optInt("marginL", marginL))
                                            onFontNameChange(json.optString("fontName", fontName))
                                        }
                                        showTemplateMenu = false
                                    },
                                    trailingIcon = {
                                        IconButton(
                                            onClick = { scope.launch { db.workflowTemplateDao().delete(template) } },
                                            modifier = Modifier.size(32.dp)
                                        ) {
                                            Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
                                        }
                                    }
                                )
                            }
                        }
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.workflow_save_current_template), fontWeight = FontWeight.Bold) },
                            onClick = { showTemplateMenu = false; showSaveDialog = true },
                            leadingIcon = { Icon(Icons.Default.Add, null) }
                        )
                    }
                }
                OutlinedButton(onClick = { showGalleryDialog = true }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.Collections, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.imagegen_tab_gallery), maxLines = 1)
                }
            }
        }

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
                            val config = org.json.JSONObject().apply {
                                put("whisperModel", whisperModelPath ?: "")
                                put("whisperLanguage", whisperLanguage)
                                put("whisperThreads", whisperThreads)
                                putWhisperVadConfig("whisperVad", settingsRepo.whisperVadConfigSnapshot())
                                put("targetLanguage", targetLanguage)
                                put("translateSubtitles", translateSubtitles)
                                put("translationContextEnabled", translationContextEnabled)
                                put("translationContextLines", translationContextLines)
                                put("skipFailedTranslationLines", skipFailedTranslationLines)
                                put("burnIntoVideo", burnIntoVideo)
                                put("fontSize", fontSize)
                                put("alignment", alignment)
                                put("marginV", marginV)
                                put("marginL", marginL)
                                put("fontName", fontName)
                            }.toString()
                            val nameToSave = saveName
                            scope.launch {
                                db.workflowTemplateDao().insert(
                                    com.example.llamadroid.data.db.WorkflowTemplateEntity(
                                        name = nameToSave,
                                        type = com.example.llamadroid.data.db.WorkflowType.SUBTITLE_TRANSLATION,
                                        configJson = config
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

        SubtitleTranslationSourceCard(
            videoUri = videoUri,
            videoName = videoName,
            videoBatch = videoBatch,
            subtitleUri = subtitleUri,
            subtitleName = subtitleName,
            isRunning = state.isRunning,
            onPickVideo = { videoPicker.launch(arrayOf("video/*")) },
            onPickVideoBatch = { videoBatchPicker.launch(arrayOf("video/*")) },
            onPickSubtitle = { subtitlePicker.launch(arrayOf("application/x-subrip", "text/plain", "*/*")) },
            onClearSubtitle = onClearSubtitle
        )

        if (subtitlePath == null) {
            MediaTranslationWhisperCard(
                settingsRepo = settingsRepo,
                whisperModels = whisperModels,
                whisperModelPath = whisperModelPath,
                onWhisperModelChange = onWhisperModelChange,
                whisperLanguage = whisperLanguage,
                onWhisperLanguageChange = onWhisperLanguageChange,
                whisperThreads = whisperThreads,
                onWhisperThreadsChange = onWhisperThreadsChange
            )
        }

        SubtitleTranslationModeCard(
            translateSubtitles = translateSubtitles,
            onTranslateSubtitlesChange = onTranslateSubtitlesChange,
            isRunning = state.isRunning
        )

        if (translateSubtitles) {
            MediaTranslationBackendCard(
                settingsRepo = settingsRepo,
                targetLanguage = targetLanguage,
                onTargetLanguageChange = onTargetLanguageChange,
                backend = backend,
                onBackendChange = onBackendChange,
                ollamaUrl = ollamaUrl,
                onOllamaUrlChange = onOllamaUrlChange,
                llamaServerUrl = llamaServerUrl,
                onLlamaServerUrlChange = onLlamaServerUrlChange,
                llamaSwapUrl = llamaSwapUrl,
                onLlamaSwapUrlChange = onLlamaSwapUrlChange,
                ollamaModel = ollamaModel,
                onOllamaModelChange = onOllamaModelChange,
                llamaSwapModel = llamaSwapModel,
                onLlamaSwapModelChange = onLlamaSwapModelChange,
                llamaServerModelLabel = llamaServerModelLabel,
                llamaServerContextLabel = llamaServerContextLabel,
                llamaServerContextTokens = llamaServerContextTokens,
                contextSize = contextSize,
                maxTokens = maxTokens,
                temperature = temperature,
                timeoutMinutes = timeoutMinutes,
                thinkingEnabled = thinkingEnabled,
                translationContextEnabled = translationContextEnabled,
                onTranslationContextEnabledChange = onTranslationContextEnabledChange,
                translationContextLines = translationContextLines,
                onTranslationContextLinesChange = onTranslationContextLinesChange,
                skipFailedTranslationLines = skipFailedTranslationLines,
                onSkipFailedTranslationLinesChange = onSkipFailedTranslationLinesChange,
                onMetadataLoaded = onMetadataLoaded
            )
        }

        SubtitleBurnWorkflowSettingsCard(
            burnIntoVideo = burnIntoVideo,
            onBurnIntoVideoChange = onBurnIntoVideoChange,
            fontSize = fontSize,
            onFontSizeChange = onFontSizeChange,
            alignment = alignment,
            onAlignmentChange = onAlignmentChange,
            marginV = marginV,
            onMarginVChange = onMarginVChange,
            marginL = marginL,
            onMarginLChange = onMarginLChange,
            primaryColor = primaryColor,
            onPrimaryColorChange = onPrimaryColorChange,
            fontName = fontName,
            onFontNameChange = onFontNameChange,
            isRunning = state.isRunning
        )

        MediaTranslationRuntimeCards(state = state)

        WorkflowOutputGalleryCard(
            folderName = "workflow_subtitle_translation",
            refreshKey = state.finalOutputPath,
            isWorkflowRunning = state.isRunning,
            resumeTranslationOverride = if (translateSubtitles) {
                workflowResumeTranslationOverride(
                    backend = backend,
                    ollamaUrl = ollamaUrl,
                    llamaServerUrl = llamaServerUrl,
                    llamaSwapUrl = llamaSwapUrl,
                    ollamaModel = ollamaModel,
                    llamaSwapModel = llamaSwapModel,
                    liteRtModelId = liteRtModelId,
                    liteRtBackend = liteRtBackend,
                    liteRtMtpEnabled = liteRtMtpEnabled,
                    thinkingEnabled = thinkingEnabled,
                    llamaServerModelLabel = llamaServerModelLabel,
                    llamaServerContextLabel = llamaServerContextLabel,
                    llamaServerContextTokens = llamaServerContextTokens,
                    contextSize = contextSize,
                    maxTokens = maxTokens,
                    temperature = temperature,
                    timeoutMinutes = timeoutMinutes,
                    targetLanguage = targetLanguage,
                    translationContextEnabled = translationContextEnabled,
                    translationContextLines = translationContextLines,
                    skipFailedTranslationLines = skipFailedTranslationLines
                )
            } else null
        )

        val backendReady = !translateSubtitles || isWorkflowTranslationBackendReady(
            backend = backend,
            ollamaUrl = ollamaUrl,
            ollamaModel = ollamaModel,
            llamaServerUrl = llamaServerUrl,
            llamaSwapUrl = llamaSwapUrl,
            llamaSwapModel = llamaSwapModel,
            liteRtModelId = liteRtModelId
        )
        val srtBatchConflict = subtitlePath != null && videoBatch.size > 1
        val canRun = !state.isRunning &&
            (videoPath != null || videoBatch.isNotEmpty()) &&
            (subtitlePath != null || whisperModelPath != null) &&
            (!translateSubtitles || targetLanguage.isNotBlank()) &&
            backendReady &&
            !srtBatchConflict
        if (srtBatchConflict) {
            Text(
                stringResource(R.string.workflow_subtitle_translate_srt_batch_warning),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }

        if (state.isRunning) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onPause,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Pause, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.action_pause), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                OutlinedButton(
                    onClick = onCancel,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Icon(Icons.Default.Close, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.action_cancel), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        } else {
            Button(onClick = onRun, enabled = canRun, modifier = Modifier.fillMaxWidth()) {
                Text(
                    stringResource(
                        if (translateSubtitles) R.string.workflow_subtitle_translate_run
                        else R.string.workflow_subtitle_generate_run
                    )
                )
            }
        }
    }
}

@Composable
private fun SubtitleTranslationSourceCard(
    videoUri: Uri?,
    videoName: String,
    videoBatch: List<WorkflowMediaInput>,
    subtitleUri: Uri?,
    subtitleName: String,
    isRunning: Boolean,
    onPickVideo: () -> Unit,
    onPickVideoBatch: () -> Unit,
    onPickSubtitle: () -> Unit,
    onClearSubtitle: () -> Unit
) {
    val context = LocalContext.current
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.workflow_subtitle_translate_source_section), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Text(
                stringResource(R.string.workflow_subtitle_translate_help),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Button(onClick = onPickVideo, modifier = Modifier.fillMaxWidth(), enabled = !isRunning) {
                Icon(Icons.Default.FolderOpen, null)
                Spacer(Modifier.width(8.dp))
                Text(videoName.ifBlank { stringResource(R.string.workflow_subtitle_translate_pick_video) }, maxLines = 1)
            }
            OutlinedButton(onClick = onPickVideoBatch, modifier = Modifier.fillMaxWidth(), enabled = !isRunning) {
                Icon(Icons.AutoMirrored.Filled.List, null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.workflow_pick_batch_videos))
            }
            if (videoBatch.size > 1) {
                Text(
                    stringResource(R.string.workflow_batch_selected_count, videoBatch.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
                videoBatch.take(4).forEach { item ->
                    Text(
                        item.name,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }
            }
            videoUri?.let {
                Text(
                    it.toString(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
            OutlinedButton(onClick = onPickSubtitle, modifier = Modifier.fillMaxWidth(), enabled = !isRunning) {
                Icon(Icons.AutoMirrored.Filled.List, null)
                Spacer(Modifier.width(8.dp))
                Text(subtitleName.ifBlank { stringResource(R.string.workflow_subtitle_translate_pick_srt) }, maxLines = 1)
            }
            if (subtitleUri != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.workflow_subtitle_translate_using_existing_srt),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = onClearSubtitle, enabled = !isRunning) {
                        Text(stringResource(R.string.action_clear))
                    }
                }
            }
        }
    }
}

@Composable
private fun SubtitleTranslationModeCard(
    translateSubtitles: Boolean,
    onTranslateSubtitlesChange: (Boolean) -> Unit,
    isRunning: Boolean
) {
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.workflow_subtitle_translate_mode_section), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.workflow_subtitle_translate_translate_toggle))
                    Text(
                        stringResource(R.string.workflow_subtitle_translate_translate_toggle_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = translateSubtitles,
                    onCheckedChange = onTranslateSubtitlesChange,
                    enabled = !isRunning
                )
            }
        }
    }
}

@Composable
private fun SubtitleBurnWorkflowSettingsCard(
    burnIntoVideo: Boolean,
    onBurnIntoVideoChange: (Boolean) -> Unit,
    fontSize: Int,
    onFontSizeChange: (Int) -> Unit,
    alignment: Int,
    onAlignmentChange: (Int) -> Unit,
    marginV: Int,
    onMarginVChange: (Int) -> Unit,
    marginL: Int,
    onMarginLChange: (Int) -> Unit,
    primaryColor: Color,
    onPrimaryColorChange: (Color) -> Unit,
    fontName: String,
    onFontNameChange: (String) -> Unit,
    isRunning: Boolean
) {
    val fonts = remember { SubtitleBurnService.getSystemFonts() }
    val colorOptions = listOf(Color.White, Color.Yellow, Color.Cyan, Color(0xFFFFD54F), Color(0xFFFF8A80))
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.workflow_subtitle_translate_burn_section), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.workflow_subtitle_translate_burn_video))
                    Text(
                        stringResource(R.string.workflow_subtitle_translate_burn_video_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(checked = burnIntoVideo, onCheckedChange = onBurnIntoVideoChange, enabled = !isRunning)
            }
            if (burnIntoVideo) {
                IntSliderWithInput(
                    value = fontSize,
                    onValueChange = onFontSizeChange,
                    valueRange = 12..72,
                    label = stringResource(R.string.subtitle_burn_font_size)
                )
                SimpleDropdownField(
                    label = stringResource(R.string.subtitle_burn_font_label),
                    selected = fontName,
                    values = fonts,
                    enabled = !isRunning,
                    onSelected = onFontNameChange
                )
                Text(stringResource(R.string.subtitle_burn_alignment), style = MaterialTheme.typography.bodyMedium)
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (row in 0..2) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                            for (col in 0..2) {
                                val value = (2 - row) * 3 + col + 1
                                FilterChip(
                                    selected = alignment == value,
                                    onClick = { onAlignmentChange(value) },
                                    enabled = !isRunning,
                                    label = { Text(value.toString()) },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                }
                IntSliderWithInput(
                    value = marginV,
                    onValueChange = onMarginVChange,
                    valueRange = 0..120,
                    label = stringResource(R.string.subtitle_burn_margin_vertical)
                )
                IntSliderWithInput(
                    value = marginL,
                    onValueChange = onMarginLChange,
                    valueRange = 0..120,
                    label = stringResource(R.string.subtitle_burn_margin_horizontal)
                )
                Text(stringResource(R.string.subtitle_burn_color), style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    colorOptions.forEach { color ->
                        Surface(
                            modifier = Modifier
                                .size(36.dp)
                                .clickable(enabled = !isRunning) { onPrimaryColorChange(color) },
                            shape = RoundedCornerShape(18.dp),
                            color = color,
                            border = if (primaryColor == color) ButtonDefaults.outlinedButtonBorder else null
                        ) {}
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MediaDubbingTranslationWorkflowContent(
    db: AppDatabase,
    settingsRepo: SettingsRepository,
    selectedUri: Uri?,
    selectedPath: String?,
    selectedName: String,
    selectedMimeType: String?,
    onMediaSelected: (Uri, String, String, String?) -> Unit,
    mediaBatch: List<WorkflowMediaInput>,
    onMediaBatchSelected: (List<WorkflowMediaInput>) -> Unit,
    whisperModelPath: String?,
    onWhisperModelChange: (String?) -> Unit,
    whisperLanguage: String,
    onWhisperLanguageChange: (String) -> Unit,
    whisperThreads: Int,
    onWhisperThreadsChange: (Int) -> Unit,
    targetLanguage: String,
    onTargetLanguageChange: (String) -> Unit,
    backend: String,
    onBackendChange: (String) -> Unit,
    ollamaUrl: String,
    onOllamaUrlChange: (String) -> Unit,
    llamaServerUrl: String,
    onLlamaServerUrlChange: (String) -> Unit,
    llamaSwapUrl: String,
    onLlamaSwapUrlChange: (String) -> Unit,
    ollamaModel: String?,
    onOllamaModelChange: (String?) -> Unit,
    llamaSwapModel: String?,
    onLlamaSwapModelChange: (String?) -> Unit,
    liteRtModelId: Long,
    llamaServerModelLabel: String?,
    llamaServerContextLabel: String?,
    llamaServerContextTokens: Int,
    contextSize: Int,
    maxTokens: Int,
    temperature: Float,
    timeoutMinutes: Int,
    thinkingEnabled: Boolean,
    translationContextEnabled: Boolean,
    onTranslationContextEnabledChange: (Boolean) -> Unit,
    translationContextLines: Int,
    onTranslationContextLinesChange: (Int) -> Unit,
    skipFailedTranslationLines: Boolean,
    onSkipFailedTranslationLinesChange: (Boolean) -> Unit,
    ttsModelPath: String?,
    ttsModelName: String?,
    onTtsModelChange: (String?, String?) -> Unit,
    ttsVoice: String?,
    onTtsVoiceChange: (String?) -> Unit,
    ttsLanguage: String,
    onTtsLanguageChange: (String) -> Unit,
    ttsSteps: Int,
    onTtsStepsChange: (Int) -> Unit,
    outputMode: MediaTranslationOutputMode,
    onOutputModeChange: (MediaTranslationOutputMode) -> Unit,
    replaceOriginalAudio: Boolean,
    onReplaceOriginalAudioChange: (Boolean) -> Unit,
    state: MediaTranslationWorkflowState,
    onRun: () -> Unit,
    onCancel: () -> Unit,
    onPause: () -> Unit,
    onMetadataLoaded: (RemoteSummaryMetadata) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val whisperModels by db.modelDao().getModelsByType(ModelType.WHISPER).collectAsState(initial = emptyList())
    val ttsModels by db.modelDao().getModelsByType(ModelType.ONNX_TTS).collectAsState(initial = emptyList())
    val selectedTtsModel = remember(ttsModels, ttsModelPath, ttsModelName) {
        ttsModels.firstOrNull { it.path == ttsModelPath || it.filename == ttsModelName } ?: ttsModels.firstOrNull()
    }
    val voiceOptions = remember(selectedTtsModel?.path) {
        selectedTtsModel?.let { resolveSupertonicVoices(File(it.path)) }.orEmpty()
    }
    val templates by db.workflowTemplateDao()
        .getByType(com.example.llamadroid.data.db.WorkflowType.MEDIA_DUB_TRANSLATION)
        .collectAsState(initial = emptyList())
    var showTemplateMenu by remember { mutableStateOf(false) }
    var showSaveDialog by remember { mutableStateOf(false) }
    var showGalleryDialog by remember { mutableStateOf(false) }
    val liteRtBackend by settingsRepo.workflowSummaryLiteRtBackend.collectAsState()
    val liteRtMtpEnabled by settingsRepo.workflowSummaryLiteRtMtpEnabled.collectAsState()

    LaunchedEffect(ttsModels) {
        if (ttsModelPath == null && ttsModels.isNotEmpty()) {
            val first = ttsModels.first()
            onTtsModelChange(first.path, first.filename)
        }
    }
    LaunchedEffect(voiceOptions) {
        if (voiceOptions.isNotEmpty() && ttsVoice !in voiceOptions) {
            onTtsVoiceChange(voiceOptions.first())
        }
    }

    val mediaPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        try {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: Exception) {
        }
        try {
            val mimeType = context.contentResolver.getType(uri)
            val extension = when {
                mimeType?.startsWith("video/") == true -> "mp4"
                mimeType?.contains("mpeg") == true -> "mp3"
                mimeType?.contains("wav") == true -> "wav"
                mimeType?.contains("mp4") == true -> "m4a"
                mimeType?.contains("ogg") == true -> "ogg"
                mimeType?.contains("flac") == true -> "flac"
                else -> "media"
            }
            val displayName = uri.lastPathSegment?.substringAfterLast('/') ?: context.getString(R.string.workflow_audio_video_placeholder)
            val inputDir = File(context.filesDir, "workflow_media_inputs").apply { mkdirs() }
            val tempFile = File(inputDir, "workflow_media_translate_${System.currentTimeMillis()}.$extension")
            context.contentResolver.openInputStream(uri)?.use { input ->
                tempFile.outputStream().use { output -> input.copyTo(output) }
            }
            onMediaSelected(uri, tempFile.absolutePath, displayName, mimeType)
        } catch (error: Exception) {
            MediaTranslationWorkflowStateHolder.update {
                it.copy(
                    workflowKind = MEDIA_TRANSLATION_WORKFLOW_KIND_MEDIA,
                    errorMessage = context.getString(R.string.workflow_error_load_audio, error.message ?: context.getString(R.string.error_generic))
                )
            }
        }
    }
    val mediaBatchPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        val items = uris.mapNotNull { uri ->
            try {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Exception) {
            }
            runCatching {
                val mimeType = context.contentResolver.getType(uri)
                val extension = mediaWorkflowExtensionForMime(mimeType)
                val displayName = uri.lastPathSegment?.substringAfterLast('/') ?: context.getString(R.string.workflow_audio_video_placeholder)
                val inputDir = File(context.filesDir, "workflow_media_inputs").apply { mkdirs() }
                val tempFile = File(inputDir, "workflow_media_translate_${System.currentTimeMillis()}_${displayName.hashCode()}.$extension")
                context.contentResolver.openInputStream(uri)?.use { input ->
                    tempFile.outputStream().use { output -> input.copyTo(output) }
                }
                WorkflowMediaInput(uri, tempFile.absolutePath, displayName, mimeType)
            }.getOrNull()
        }
        if (items.isNotEmpty()) onMediaBatchSelected(items)
    }

    if (showGalleryDialog) {
        AlertDialog(
            onDismissRequest = { showGalleryDialog = false },
            confirmButton = {
                TextButton(onClick = { showGalleryDialog = false }) {
                    Text(stringResource(R.string.action_close))
                }
            },
            text = {
                WorkflowOutputGalleryCard(
                    folderName = "workflow_media_translation",
                    refreshKey = state.finalOutputPath,
                    isWorkflowRunning = state.isRunning,
                    resumeTranslationOverride = workflowResumeTranslationOverride(
                        backend = backend,
                        ollamaUrl = ollamaUrl,
                        llamaServerUrl = llamaServerUrl,
                        llamaSwapUrl = llamaSwapUrl,
                        ollamaModel = ollamaModel,
                        llamaSwapModel = llamaSwapModel,
                        liteRtModelId = liteRtModelId,
                        liteRtBackend = liteRtBackend,
                        liteRtMtpEnabled = liteRtMtpEnabled,
                        thinkingEnabled = thinkingEnabled,
                        llamaServerModelLabel = llamaServerModelLabel,
                        llamaServerContextLabel = llamaServerContextLabel,
                        llamaServerContextTokens = llamaServerContextTokens,
                        contextSize = contextSize,
                        maxTokens = maxTokens,
                        temperature = temperature,
                        timeoutMinutes = timeoutMinutes,
                        targetLanguage = targetLanguage,
                        translationContextEnabled = translationContextEnabled,
                        translationContextLines = translationContextLines,
                        skipFailedTranslationLines = skipFailedTranslationLines
                    )
                )
            }
        )
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                stringResource(R.string.workflow_media_translate_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 2
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(modifier = Modifier.weight(1f)) {
                    OutlinedButton(
                        onClick = { showTemplateMenu = true },
                        enabled = !state.isRunning,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Settings, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.workflow_templates_btn, templates.size), maxLines = 1)
                    }
                    DropdownMenu(
                        expanded = showTemplateMenu,
                        onDismissRequest = { showTemplateMenu = false },
                        modifier = Modifier.widthIn(min = 280.dp, max = 360.dp)
                    ) {
                        if (templates.isEmpty()) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.workflow_no_templates), color = MaterialTheme.colorScheme.onSurfaceVariant) },
                                onClick = {}
                            )
                        } else {
                            templates.forEach { template ->
                                DropdownMenuItem(
                                    text = { Text(template.name, maxLines = 1) },
                                    onClick = {
                                        runCatching {
                                            val json = org.json.JSONObject(template.configJson)
                                            onWhisperModelChange(json.optString("whisperModel").takeIf { it.isNotBlank() })
                                            onWhisperLanguageChange(json.optString("whisperLanguage", whisperLanguage))
                                            onWhisperThreadsChange(json.optInt("whisperThreads", whisperThreads))
                                            json.readWhisperVadConfigOrNull("whisperVad")
                                                ?.let(settingsRepo::applyWhisperVadConfig)
                                            onTargetLanguageChange(json.optString("targetLanguage", targetLanguage))
                                            onTranslationContextEnabledChange(json.optBoolean("translationContextEnabled", translationContextEnabled))
                                            onTranslationContextLinesChange(json.optInt("translationContextLines", translationContextLines).coerceIn(0, 10))
                                            onSkipFailedTranslationLinesChange(json.optBoolean("skipFailedTranslationLines", skipFailedTranslationLines))
                                            onTtsModelChange(
                                                json.optString("ttsModelPath").takeIf { it.isNotBlank() },
                                                json.optString("ttsModelName").takeIf { it.isNotBlank() }
                                            )
                                            onTtsVoiceChange(json.optString("ttsVoice").takeIf { it.isNotBlank() })
                                            onTtsLanguageChange(json.optString("ttsLanguage", ttsLanguage))
                                            onTtsStepsChange(json.optInt("ttsSteps", ttsSteps))
                                            onOutputModeChange(
                                                runCatching {
                                                    MediaTranslationOutputMode.valueOf(json.optString("outputMode", outputMode.name))
                                                }.getOrDefault(MediaTranslationOutputMode.AUTO)
                                            )
                                            onReplaceOriginalAudioChange(json.optBoolean("replaceOriginalAudio", replaceOriginalAudio))
                                        }
                                        showTemplateMenu = false
                                    },
                                    trailingIcon = {
                                        IconButton(
                                            onClick = { scope.launch { db.workflowTemplateDao().delete(template) } },
                                            modifier = Modifier.size(32.dp)
                                        ) {
                                            Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
                                        }
                                    }
                                )
                            }
                        }
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.workflow_save_current_template), fontWeight = FontWeight.Bold) },
                            onClick = { showTemplateMenu = false; showSaveDialog = true },
                            leadingIcon = { Icon(Icons.Default.Add, null) }
                        )
                    }
                }
                OutlinedButton(onClick = { showGalleryDialog = true }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.Collections, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.imagegen_tab_gallery), maxLines = 1)
                }
            }
        }

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
                            val config = org.json.JSONObject().apply {
                                put("whisperModel", whisperModelPath ?: "")
                                put("whisperLanguage", whisperLanguage)
                                put("whisperThreads", whisperThreads)
                                putWhisperVadConfig("whisperVad", settingsRepo.whisperVadConfigSnapshot())
                                put("targetLanguage", targetLanguage)
                                put("translationContextEnabled", translationContextEnabled)
                                put("translationContextLines", translationContextLines)
                                put("skipFailedTranslationLines", skipFailedTranslationLines)
                                put("ttsModelPath", ttsModelPath ?: "")
                                put("ttsModelName", ttsModelName ?: "")
                                put("ttsVoice", ttsVoice ?: "")
                                put("ttsLanguage", ttsLanguage)
                                put("ttsSteps", ttsSteps)
                                put("outputMode", outputMode.name)
                                put("replaceOriginalAudio", replaceOriginalAudio)
                            }.toString()
                            val nameToSave = saveName
                            scope.launch {
                                db.workflowTemplateDao().insert(
                                    com.example.llamadroid.data.db.WorkflowTemplateEntity(
                                        name = nameToSave,
                                        type = com.example.llamadroid.data.db.WorkflowType.MEDIA_DUB_TRANSLATION,
                                        configJson = config
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

        MediaTranslationSourceCard(
            selectedUri = selectedUri,
            selectedName = selectedName,
            selectedMimeType = selectedMimeType,
            batch = mediaBatch,
            isRunning = state.isRunning,
            onPick = { mediaPicker.launch(arrayOf("audio/*", "video/*")) },
            onPickBatch = { mediaBatchPicker.launch(arrayOf("audio/*", "video/*")) }
        )

        MediaTranslationWhisperCard(
            settingsRepo = settingsRepo,
            whisperModels = whisperModels,
            whisperModelPath = whisperModelPath,
            onWhisperModelChange = onWhisperModelChange,
            whisperLanguage = whisperLanguage,
            onWhisperLanguageChange = onWhisperLanguageChange,
            whisperThreads = whisperThreads,
            onWhisperThreadsChange = onWhisperThreadsChange
        )

        MediaTranslationBackendCard(
            settingsRepo = settingsRepo,
            targetLanguage = targetLanguage,
            onTargetLanguageChange = onTargetLanguageChange,
            backend = backend,
            onBackendChange = onBackendChange,
            ollamaUrl = ollamaUrl,
            onOllamaUrlChange = onOllamaUrlChange,
            llamaServerUrl = llamaServerUrl,
            onLlamaServerUrlChange = onLlamaServerUrlChange,
            llamaSwapUrl = llamaSwapUrl,
            onLlamaSwapUrlChange = onLlamaSwapUrlChange,
            ollamaModel = ollamaModel,
            onOllamaModelChange = onOllamaModelChange,
            llamaSwapModel = llamaSwapModel,
            onLlamaSwapModelChange = onLlamaSwapModelChange,
            llamaServerModelLabel = llamaServerModelLabel,
            llamaServerContextLabel = llamaServerContextLabel,
            llamaServerContextTokens = llamaServerContextTokens,
            contextSize = contextSize,
            maxTokens = maxTokens,
            temperature = temperature,
            timeoutMinutes = timeoutMinutes,
            thinkingEnabled = thinkingEnabled,
            translationContextEnabled = translationContextEnabled,
            onTranslationContextEnabledChange = onTranslationContextEnabledChange,
            translationContextLines = translationContextLines,
            onTranslationContextLinesChange = onTranslationContextLinesChange,
            skipFailedTranslationLines = skipFailedTranslationLines,
            onSkipFailedTranslationLinesChange = onSkipFailedTranslationLinesChange,
            onMetadataLoaded = onMetadataLoaded
        )

        MediaTranslationVoiceCard(
            ttsModels = ttsModels,
            selectedTtsModel = selectedTtsModel,
            voiceOptions = voiceOptions,
            ttsVoice = ttsVoice,
            onTtsModelChange = onTtsModelChange,
            onTtsVoiceChange = onTtsVoiceChange,
            ttsLanguage = ttsLanguage,
            onTtsLanguageChange = onTtsLanguageChange,
            ttsSteps = ttsSteps,
            onTtsStepsChange = onTtsStepsChange,
            isRunning = state.isRunning
        )

        MediaTranslationOutputCard(
            outputMode = outputMode,
            onOutputModeChange = onOutputModeChange,
            replaceOriginalAudio = replaceOriginalAudio,
            onReplaceOriginalAudioChange = onReplaceOriginalAudioChange,
            isRunning = state.isRunning
        )

        MediaTranslationRuntimeCards(state = state)

        WorkflowOutputGalleryCard(
            folderName = "workflow_media_translation",
            refreshKey = state.finalOutputPath,
            isWorkflowRunning = state.isRunning,
            resumeTranslationOverride = workflowResumeTranslationOverride(
                backend = backend,
                ollamaUrl = ollamaUrl,
                llamaServerUrl = llamaServerUrl,
                llamaSwapUrl = llamaSwapUrl,
                ollamaModel = ollamaModel,
                llamaSwapModel = llamaSwapModel,
                liteRtModelId = liteRtModelId,
                liteRtBackend = liteRtBackend,
                liteRtMtpEnabled = liteRtMtpEnabled,
                thinkingEnabled = thinkingEnabled,
                llamaServerModelLabel = llamaServerModelLabel,
                llamaServerContextLabel = llamaServerContextLabel,
                llamaServerContextTokens = llamaServerContextTokens,
                contextSize = contextSize,
                maxTokens = maxTokens,
                temperature = temperature,
                timeoutMinutes = timeoutMinutes,
                targetLanguage = targetLanguage,
                translationContextEnabled = translationContextEnabled,
                translationContextLines = translationContextLines,
                skipFailedTranslationLines = skipFailedTranslationLines
            )
        )

        val backendReady = isWorkflowTranslationBackendReady(
            backend = backend,
            ollamaUrl = ollamaUrl,
            ollamaModel = ollamaModel,
            llamaServerUrl = llamaServerUrl,
            llamaSwapUrl = llamaSwapUrl,
            llamaSwapModel = llamaSwapModel,
            liteRtModelId = liteRtModelId
        )
        val canRun = !state.isRunning &&
            (selectedPath != null || mediaBatch.isNotEmpty()) &&
            whisperModelPath != null &&
            selectedTtsModel != null &&
            targetLanguage.isNotBlank() &&
            backendReady

        if (state.isRunning) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onPause,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Pause, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.action_pause), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                OutlinedButton(
                    onClick = onCancel,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Icon(Icons.Default.Close, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.action_cancel), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        } else {
            Button(onClick = onRun, enabled = canRun, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.workflow_media_translate_run))
            }
        }
    }
}

@Composable
private fun MediaTranslationSourceCard(
    selectedUri: Uri?,
    selectedName: String,
    selectedMimeType: String?,
    batch: List<WorkflowMediaInput>,
    isRunning: Boolean,
    onPick: () -> Unit,
    onPickBatch: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.workflow_media_translate_source_section), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Text(
                stringResource(R.string.workflow_media_translate_help),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Button(onClick = onPick, modifier = Modifier.fillMaxWidth(), enabled = !isRunning) {
                Icon(Icons.Default.FolderOpen, null)
                Spacer(Modifier.width(8.dp))
                Text(selectedName.ifBlank { stringResource(R.string.workflow_media_translate_pick_media) }, maxLines = 1)
            }
            OutlinedButton(onClick = onPickBatch, modifier = Modifier.fillMaxWidth(), enabled = !isRunning) {
                Icon(Icons.Default.Queue, null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.workflow_pick_batch_media))
            }
            if (batch.size > 1) {
                Text(
                    stringResource(R.string.workflow_batch_selected_count, batch.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
                batch.take(4).forEach { item ->
                    Text(
                        item.name,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }
            }
            selectedUri?.let {
                Text(
                    selectedMimeType ?: it.toString(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MediaTranslationWhisperCard(
    settingsRepo: SettingsRepository,
    whisperModels: List<ModelEntity>,
    whisperModelPath: String?,
    onWhisperModelChange: (String?) -> Unit,
    whisperLanguage: String,
    onWhisperLanguageChange: (String) -> Unit,
    whisperThreads: Int,
    onWhisperThreadsChange: (Int) -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.workflow_step_transcribe), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
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
                    WhisperLanguages.languages.take(30).forEach { (code, name) ->
                        DropdownMenuItem(text = { Text(name) }, onClick = { onWhisperLanguageChange(code); languageExpanded = false })
                    }
                }
            }
            IntSliderWithInput(
                value = whisperThreads,
                onValueChange = onWhisperThreadsChange,
                valueRange = 1..16,
                label = stringResource(R.string.label_threads)
            )
            WhisperVadInlineControl(settingsRepo = settingsRepo)
        }
    }
}

@Composable
private fun MediaTranslationBackendCard(
    settingsRepo: SettingsRepository,
    targetLanguage: String,
    onTargetLanguageChange: (String) -> Unit,
    backend: String,
    onBackendChange: (String) -> Unit,
    ollamaUrl: String,
    onOllamaUrlChange: (String) -> Unit,
    llamaServerUrl: String,
    onLlamaServerUrlChange: (String) -> Unit,
    llamaSwapUrl: String,
    onLlamaSwapUrlChange: (String) -> Unit,
    ollamaModel: String?,
    onOllamaModelChange: (String?) -> Unit,
    llamaSwapModel: String?,
    onLlamaSwapModelChange: (String?) -> Unit,
    llamaServerModelLabel: String?,
    llamaServerContextLabel: String?,
    llamaServerContextTokens: Int,
    contextSize: Int,
    maxTokens: Int,
    temperature: Float,
    timeoutMinutes: Int,
    thinkingEnabled: Boolean,
    translationContextEnabled: Boolean,
    onTranslationContextEnabledChange: (Boolean) -> Unit,
    translationContextLines: Int,
    onTranslationContextLinesChange: (Int) -> Unit,
    skipFailedTranslationLines: Boolean,
    onSkipFailedTranslationLinesChange: (Boolean) -> Unit,
    onMetadataLoaded: (RemoteSummaryMetadata) -> Unit
) {
    val context = LocalContext.current
    val liteRtModelId by settingsRepo.workflowSummaryLiteRtModelId.collectAsState()
    val liteRtBackend by settingsRepo.workflowSummaryLiteRtBackend.collectAsState()
    val liteRtMtpEnabled by settingsRepo.workflowSummaryLiteRtMtpEnabled.collectAsState()
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.workflow_media_translate_translation_section), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            OutlinedTextField(
                value = targetLanguage,
                onValueChange = onTargetLanguageChange,
                label = { Text(stringResource(R.string.pdf_target_language_label)) },
                modifier = Modifier.fillMaxWidth()
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.workflow_media_translate_context_toggle),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        stringResource(R.string.workflow_media_translate_context_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.width(12.dp))
                Switch(
                    checked = translationContextEnabled,
                    onCheckedChange = onTranslationContextEnabledChange
                )
            }
            DraftIntTextField(
                value = translationContextLines,
                onValueChange = { onTranslationContextLinesChange(it.coerceIn(0, 10)) },
                enabled = translationContextEnabled,
                valueRange = 0..10,
                blankValue = 0,
                label = { Text(stringResource(R.string.workflow_media_translate_context_lines)) },
                suffix = { Text(stringResource(R.string.workflow_media_translate_context_lines_suffix)) },
                modifier = Modifier.fillMaxWidth()
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.workflow_media_translate_skip_failed_lines),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        stringResource(R.string.workflow_media_translate_skip_failed_lines_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.width(12.dp))
                Switch(
                    checked = skipFailedTranslationLines,
                    onCheckedChange = onSkipFailedTranslationLinesChange
                )
            }
            val promptPreview = remember(targetLanguage, translationContextEnabled, translationContextLines) {
                mediaTranslationBuildLineTranslationPrompt(
                    sourceLanguage = "auto",
                    targetLanguage = targetLanguage.ifBlank { "English" },
                    segments = listOf(
                        TimedTranscriptSegment(1, 0L, 1_000L, "I did not expect that."),
                        TimedTranscriptSegment(2, 1_000L, 2_000L, "We should leave before sunset."),
                        TimedTranscriptSegment(3, 2_000L, 3_000L, "There is still time.")
                    ),
                    targetIndex = 1,
                    includeContext = translationContextEnabled,
                    contextLines = translationContextLines
                )
            }
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        stringResource(R.string.workflow_media_translate_prompt_preview),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        promptPreview,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            RemoteSummaryBackendEditor(
                title = stringResource(R.string.video_summary_remote_settings_title),
                backend = backend,
                onBackendChange = onBackendChange,
                ollamaUrl = ollamaUrl,
                onOllamaUrlChange = onOllamaUrlChange,
                llamaServerUrl = llamaServerUrl,
                onLlamaServerUrlChange = onLlamaServerUrlChange,
                llamaSwapUrl = llamaSwapUrl,
                onLlamaSwapUrlChange = onLlamaSwapUrlChange,
                ollamaModel = ollamaModel,
                onOllamaModelSelected = onOllamaModelChange,
                llamaSwapModel = llamaSwapModel,
                onLlamaSwapModelSelected = onLlamaSwapModelChange,
                llamaServerModelLabel = llamaServerModelLabel,
                llamaServerContextLabel = llamaServerContextLabel,
                llamaServerContextTokens = llamaServerContextTokens,
                requestedContextForWarning = contextSize,
                liteRtModelId = liteRtModelId.takeIf { it > 0L },
                onLiteRtModelSelected = settingsRepo::setWorkflowSummaryLiteRtModelId,
                liteRtBackend = liteRtBackend,
                onLiteRtBackendChange = settingsRepo::setWorkflowSummaryLiteRtBackend,
                liteRtMtpEnabled = liteRtMtpEnabled,
                onLiteRtMtpEnabledChange = settingsRepo::setWorkflowSummaryLiteRtMtpEnabled,
                liteRtThinkingEnabled = thinkingEnabled,
                onLiteRtThinkingEnabledChange = settingsRepo::setWorkflowSummaryThinkingEnabled,
                fetchMetadata = {
                    RemoteSummaryClientFactory.fromSnapshot(
                        context,
                        RemoteSummarySettingsSnapshot(
                            backend = backend,
                            ollamaUrl = ollamaUrl,
                            llamaServerUrl = llamaServerUrl,
                            llamaSwapUrl = llamaSwapUrl,
                            ollamaModel = ollamaModel,
                            llamaSwapModel = llamaSwapModel,
                            liteRtModelId = liteRtModelId.takeIf { it > 0L },
                            liteRtBackend = liteRtBackend,
                            liteRtMtpEnabled = liteRtMtpEnabled,
                            thinkingEnabled = thinkingEnabled,
                            llamaServerModelLabel = llamaServerModelLabel,
                            llamaServerContextTokens = llamaServerContextTokens,
                            llamaServerContextLabel = llamaServerContextLabel,
                            chunkContext = contextSize,
                            chunkMaxTokens = maxTokens,
                            mergeContext = contextSize,
                            mergeMaxTokens = maxTokens,
                            temperature = temperature,
                            timeoutMinutes = timeoutMinutes,
                            targetLanguage = targetLanguage,
                            summaryPrompt = null,
                            mergePrompt = null
                        )
                    ).fetchMetadata()
                },
                onMetadataLoaded = { metadata ->
                    onMetadataLoaded(metadata)
                    if (SettingsRepository.isLlamaServerBackend(metadata.backend)) {
                        settingsRepo.setWorkflowSummaryLlamaServerModelLabel(metadata.serverModelLabel)
                        settingsRepo.setWorkflowSummaryLlamaServerContextTokens(metadata.serverContextTokens)
                        settingsRepo.setWorkflowSummaryLlamaServerContextLabel(metadata.serverContextLabel)
                    }
                }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MediaTranslationVoiceCard(
    ttsModels: List<ModelEntity>,
    selectedTtsModel: ModelEntity?,
    voiceOptions: List<String>,
    ttsVoice: String?,
    onTtsModelChange: (String?, String?) -> Unit,
    onTtsVoiceChange: (String?) -> Unit,
    ttsLanguage: String,
    onTtsLanguageChange: (String) -> Unit,
    ttsSteps: Int,
    onTtsStepsChange: (Int) -> Unit,
    isRunning: Boolean
) {
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.workflow_media_translate_voice_section), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            if (ttsModels.isEmpty()) {
                Text(stringResource(R.string.onnx_tts_no_model), color = MaterialTheme.colorScheme.error)
            } else {
                var ttsExpanded by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(expanded = ttsExpanded, onExpandedChange = { ttsExpanded = it }) {
                    OutlinedTextField(
                        value = selectedTtsModel?.filename ?: stringResource(R.string.onnx_tts_model_section),
                        onValueChange = {},
                        readOnly = true,
                        label = { Text(stringResource(R.string.onnx_tts_model_section)) },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(ttsExpanded) },
                        modifier = Modifier.fillMaxWidth().menuAnchor()
                    )
                    ExposedDropdownMenu(expanded = ttsExpanded, onDismissRequest = { ttsExpanded = false }) {
                        ttsModels.forEach { model ->
                            DropdownMenuItem(
                                text = { Text(model.filename) },
                                onClick = {
                                    onTtsModelChange(model.path, model.filename)
                                    ttsExpanded = false
                                }
                            )
                        }
                    }
                }
            }
            SimpleDropdownField(
                label = stringResource(R.string.onnx_tts_voice_label),
                selected = ttsVoice.orEmpty(),
                values = voiceOptions,
                enabled = voiceOptions.isNotEmpty() && !isRunning,
                onSelected = onTtsVoiceChange
            )
            SimpleDropdownField(
                label = stringResource(R.string.onnx_tts_language_label),
                selected = ttsLanguage,
                values = supertonicLanguageCodes,
                enabled = !isRunning,
                onSelected = onTtsLanguageChange
            )
            IntSliderWithInput(
                value = ttsSteps,
                onValueChange = onTtsStepsChange,
                valueRange = 1..32,
                label = stringResource(R.string.workflow_media_translate_tts_steps)
            )
        }
    }
}

@Composable
private fun MediaTranslationOutputCard(
    outputMode: MediaTranslationOutputMode,
    onOutputModeChange: (MediaTranslationOutputMode) -> Unit,
    replaceOriginalAudio: Boolean,
    onReplaceOriginalAudioChange: (Boolean) -> Unit,
    isRunning: Boolean
) {
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.workflow_media_translate_output_section), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            SimpleDropdownField(
                label = stringResource(R.string.workflow_media_translate_output_mode),
                selected = outputMode.name,
                values = MediaTranslationOutputMode.entries.map { it.name },
                enabled = !isRunning,
                onSelected = { raw -> onOutputModeChange(MediaTranslationOutputMode.valueOf(raw)) },
                labelForValue = { raw ->
                    when (MediaTranslationOutputMode.valueOf(raw)) {
                        MediaTranslationOutputMode.AUTO -> stringResource(R.string.workflow_media_translate_output_auto)
                        MediaTranslationOutputMode.DUB_VIDEO -> stringResource(R.string.workflow_media_translate_output_video)
                        MediaTranslationOutputMode.AUDIO_ONLY -> stringResource(R.string.workflow_media_translate_output_audio)
                    }
                }
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.workflow_media_translate_replace_audio))
                    Text(
                        stringResource(R.string.workflow_media_translate_replace_audio_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = replaceOriginalAudio,
                    onCheckedChange = onReplaceOriginalAudioChange,
                    enabled = !isRunning
                )
            }
        }
    }
}

@Composable
private fun MediaTranslationRuntimeCards(state: MediaTranslationWorkflowState) {
    if (state.isRunning) {
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer), shape = RoundedCornerShape(16.dp)) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth())
                Text(state.status, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                state.toolProgressDetail?.let { detail ->
                    Text(
                        detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
                if (state.totalBatchItems > 1) {
                    Text(
                        stringResource(R.string.workflow_batch_progress, state.currentBatchItem.coerceAtLeast(1), state.totalBatchItems),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                if (state.totalChunks > 0) {
                    Text(
                        stringResource(R.string.workflow_media_translate_line_progress, state.currentChunk.coerceAtLeast(1), state.totalChunks),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
    state.errorMessage?.let { error ->
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
            Text(error, modifier = Modifier.padding(12.dp), color = MaterialTheme.colorScheme.onErrorContainer)
        }
    }
    if (state.cancelled && !state.isRunning && state.errorMessage == null) {
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f))) {
            Text(
                stringResource(R.string.workflow_media_cancelled_message),
                modifier = Modifier.padding(12.dp),
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
        }
    }
    if (state.paused && !state.isRunning && state.errorMessage == null) {
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f))) {
            Text(
                stringResource(R.string.workflow_media_paused_message),
                modifier = Modifier.padding(12.dp),
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
        }
    }
    state.finalOutputPath?.let {
        Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.workflow_media_translate_results), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                if (state.skippedTranslationLineCount > 0) {
                    Text(
                        stringResource(R.string.workflow_media_translate_skipped_lines_summary, state.skippedTranslationLineCount),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                WorkflowOutputPathRow(stringResource(R.string.workflow_media_translate_final_output), it)
                state.translatedAudioPath?.let { path -> WorkflowOutputPathRow(stringResource(R.string.workflow_media_translate_audio_output), path) }
                state.originalSrtPath?.let { path -> WorkflowOutputPathRow(stringResource(R.string.workflow_media_translate_original_srt), path) }
                state.translatedSrtPath?.let { path -> WorkflowOutputPathRow(stringResource(R.string.workflow_media_translate_translated_srt), path) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SimpleDropdownField(
    label: String,
    selected: String,
    values: List<String>,
    enabled: Boolean,
    onSelected: (String) -> Unit,
    labelForValue: @Composable (String) -> String = { it }
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { if (enabled) expanded = it }) {
        OutlinedTextField(
            value = values.firstOrNull { it == selected }?.let { labelForValue(it) } ?: selected,
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor()
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            values.forEach { value ->
                DropdownMenuItem(
                    text = { Text(labelForValue(value)) },
                    onClick = {
                        onSelected(value)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
private fun WorkflowOutputPathRow(label: String, path: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        Text(
            path,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2
        )
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
                Surface(
                    modifier = Modifier.size(48.dp),
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.55f)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(emoji, fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                    }
                }
                Spacer(modifier = Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 4, overflow = TextOverflow.Ellipsis)
                }
                Spacer(modifier = Modifier.width(8.dp))
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
    ipAdapterEnabled: Boolean,
    onIpAdapterEnabledChange: (Boolean) -> Unit,
    ipAdapterPath: String?,
    onIpAdapterPathChange: (String?) -> Unit,
    clipVisionPath: String?,
    onClipVisionPathChange: (String?) -> Unit,
    ipAdapterReferencePath: String?,
    onIpAdapterReferencePathChange: (String?) -> Unit,
    ipAdapterStrength: Float,
    onIpAdapterStrengthChange: (Float) -> Unit,
    onOpenSdModels: () -> Unit,
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
    val settingsRepo = remember { SettingsRepository(context) }
    val sdMaxCpuRamEnabled by settingsRepo.sdMaxCpuRamEnabled.collectAsState()
    val sdMaxCpuRamGiB by settingsRepo.sdMaxCpuRamGiB.collectAsState()

    // Models
    val sdCheckpoints by db.modelDao().getModelsByType(ModelType.SD_CHECKPOINT).collectAsState(initial = emptyList())
    val fluxDiffusionModels by db.modelDao().getModelsByType(ModelType.SD_DIFFUSION).collectAsState(initial = emptyList())
    val sdClipLModels by db.modelDao().getModelsByType(ModelType.SD_CLIP_L).collectAsState(initial = emptyList())
    val sdClipGModels by db.modelDao().getModelsByType(ModelType.SD_CLIP_G).collectAsState(initial = emptyList())
    val sdT5xxlModels by db.modelDao().getModelsByType(ModelType.SD_T5XXL).collectAsState(initial = emptyList())
    val sdTaeModels by db.modelDao().getModelsByType(ModelType.SD_TAE).collectAsState(initial = emptyList())
    val sdVaeModels by db.modelDao().getModelsByType(ModelType.SD_VAE).collectAsState(initial = emptyList())
    val sdPhotoMakerModels by db.modelDao().getModelsByType(ModelType.SD_PHOTOMAKER).collectAsState(initial = emptyList())
    val sdClipVisionModels by db.modelDao().getModelsByType(ModelType.SD_CLIP_VISION).collectAsState(initial = emptyList())
    val sdIpAdapterModels by db.modelDao().getModelsByType(ModelType.SD_IP_ADAPTER).collectAsState(initial = emptyList())
    val sdImageSupportModels by db.modelDao()
        .getModelsByTypes(listOf(ModelType.LLM, ModelType.VISION_PROJECTOR))
        .collectAsState(initial = emptyList())
    val upscalerModels by db.modelDao().getModelsByType(ModelType.SD_UPSCALER).collectAsState(initial = emptyList())
    val allGenerationModels = (sdCheckpoints + fluxDiffusionModels).filter { model ->
        model.isSdImageMainModel()
    }
    val selectedGenerationModel = allGenerationModels.firstOrNull { it.path == modelPath }
    val selectedUpscalerModel = upscalerModels.firstOrNull { it.path == upscalerPath }
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
    val compatibleClipVisionModels = remember(sdClipVisionModels, selectedGenerationFamilyEnum, selectedGenerationVariant) {
        filterWorkflowSdComponents(sdClipVisionModels, selectedGenerationFamilyEnum, selectedGenerationVariant)
    }
    val compatibleIpAdapterModels = remember(sdIpAdapterModels, selectedGenerationFamilyEnum, selectedGenerationVariant) {
        filterWorkflowSdComponents(sdIpAdapterModels, selectedGenerationFamilyEnum, selectedGenerationVariant)
    }
    val supportsIpAdapter = selectedGenerationSpec?.supportsIpAdapter == true
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
        val effectiveIpAdapter = if (ipAdapterEnabled) {
            try {
                validateSdIpAdapterConfig(
                    config = SdIpAdapterConfig(
                        adapterPath = ipAdapterPath.orEmpty(),
                        clipVisionPath = clipVisionPath.orEmpty(),
                        imagePath = ipAdapterReferencePath.orEmpty(),
                        strength = ipAdapterStrength
                    ),
                    supportsIpAdapter = supportsIpAdapter,
                    adapterCompatible = compatibleIpAdapterModels.any {
                        it.path == ipAdapterPath
                    },
                    clipVisionCompatible = compatibleClipVisionModels.any {
                        it.path == clipVisionPath
                    }
                )
            } catch (error: SdIpAdapterConfigurationException) {
                onErrorChange(sdIpAdapterErrorMessage(context, error))
                return@workflow
            }
        } else {
            null
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
                photoMakerPath = photoMakerPath,
                ipAdapter = effectiveIpAdapter,
                sdParamsBackendMode = selectedGenerationModel?.sdParamsBackendMode ?: "auto",
                sdRuntimeBackendMode = selectedGenerationModel?.sdRuntimeBackendMode ?: "auto",
                maxVramCpuGiB = if (sdMaxCpuRamEnabled) sdMaxCpuRamGiB else ""
            )

            val upscaleConfig = SDConfig(
                mode = SDMode.UPSCALE,
                modelPath = upscalerPath,
                prompt = "",
                outputPath = upscaledFile.absolutePath,
                initImage = txt2imgFile.absolutePath,
                upscaleModel = upscalerPath,
                upscaleRepeats = upscaleRepeats,
                threads = upscaleThreads,
                sdParamsBackendMode = selectedUpscalerModel?.sdParamsBackendMode ?: "auto",
                sdRuntimeBackendMode = selectedUpscalerModel?.sdRuntimeBackendMode ?: "auto",
                maxVramCpuGiB = if (sdMaxCpuRamEnabled) sdMaxCpuRamGiB else ""
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
                                        val restoredIpAdapter = config.readSdIpAdapterDraft()
                                        onIpAdapterEnabledChange(restoredIpAdapter.enabled)
                                        onIpAdapterPathChange(restoredIpAdapter.adapterPath)
                                        onClipVisionPathChange(restoredIpAdapter.clipVisionPath)
                                        onIpAdapterReferencePathChange(
                                            SdIpAdapterReferenceStore.resolveOwnedImagePath(
                                                context,
                                                restoredIpAdapter.imagePath
                                            )
                                        )
                                        onIpAdapterStrengthChange(restoredIpAdapter.strength)
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
                        label = { Text(stringResource(R.string.workflow_template_name)) },
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
                                    putSdIpAdapterDraft(
                                        SdIpAdapterDraftState(
                                            enabled = ipAdapterEnabled,
                                            adapterPath = ipAdapterPath,
                                            clipVisionPath = clipVisionPath,
                                            imagePath = ipAdapterReferencePath,
                                            strength = ipAdapterStrength
                                        )
                                    )
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
                    }) { Text(stringResource(R.string.action_save)) }
                },
                dismissButton = { TextButton(onClick = { showSaveDialog = false }) { Text(stringResource(R.string.action_cancel)) } }
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
        
        SdIpAdapterCard(
            supported = supportsIpAdapter,
            enabled = ipAdapterEnabled,
            onEnabledChange = onIpAdapterEnabledChange,
            adapterModels = compatibleIpAdapterModels,
            clipVisionModels = compatibleClipVisionModels,
            selectedAdapterPath = ipAdapterPath,
            onAdapterPathChange = onIpAdapterPathChange,
            selectedClipVisionPath = clipVisionPath,
            onClipVisionPathChange = onClipVisionPathChange,
            referenceImagePath = ipAdapterReferencePath,
            onReferenceImagePathChange = onIpAdapterReferencePathChange,
            strength = ipAdapterStrength,
            onStrengthChange = onIpAdapterStrengthChange,
            onClearConfiguration = {
                onIpAdapterEnabledChange(false)
                onIpAdapterPathChange(null)
                onClipVisionPathChange(null)
                onIpAdapterReferencePathChange(null)
                onIpAdapterStrengthChange(SdIpAdapterDraftState.DEFAULT_STRENGTH)
            },
            onOpenModels = onOpenSdModels
        )

        Spacer(modifier = Modifier.height(16.dp))

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
    SdComponentRole.CLIP_VISION -> R.string.sd_type_clip_vision
    SdComponentRole.IP_ADAPTER -> R.string.sd_type_ip_adapter
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
    summaryLiteRtModelId: Long,
    onSummaryLiteRtModelIdChange: (Long?) -> Unit,
    summaryLiteRtBackend: String,
    onSummaryLiteRtBackendChange: (String) -> Unit,
    summaryLiteRtMtpEnabled: Boolean,
    onSummaryLiteRtMtpEnabledChange: (Boolean) -> Unit,
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
                                        config.readWhisperVadConfigOrNull("whisperVad")
                                            ?.let(settingsRepo::applyWhisperVadConfig)
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
                                    putWhisperVadConfig("whisperVad", settingsRepo.whisperVadConfigSnapshot())
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
                Spacer(modifier = Modifier.height(10.dp))
                WhisperVadInlineControl(settingsRepo = settingsRepo)
                
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
                    liteRtModelId = summaryLiteRtModelId.takeIf { it > 0L },
                    onLiteRtModelSelected = onSummaryLiteRtModelIdChange,
                    liteRtBackend = summaryLiteRtBackend,
                    onLiteRtBackendChange = onSummaryLiteRtBackendChange,
                    liteRtMtpEnabled = summaryLiteRtMtpEnabled,
                    onLiteRtMtpEnabledChange = onSummaryLiteRtMtpEnabledChange,
                    liteRtThinkingEnabled = thinkingEnabled,
                    onLiteRtThinkingEnabledChange = onThinkingEnabledChange,
                    fetchMetadata = {
                        RemoteSummaryClientFactory.fromSnapshot(
                            context,
                            RemoteSummarySettingsSnapshot(
                                backend = summaryBackend,
                                ollamaUrl = summaryOllamaUrl,
                                llamaServerUrl = summaryLlamaUrl,
                                llamaSwapUrl = summaryLlamaSwapUrl,
                                ollamaModel = summaryOllamaModel,
                                llamaSwapModel = summaryLlamaSwapModel,
                                liteRtModelId = summaryLiteRtModelId.takeIf { it > 0L },
                                liteRtBackend = summaryLiteRtBackend,
                                liteRtMtpEnabled = summaryLiteRtMtpEnabled,
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
            SettingsRepository.PDF_BACKEND_LITERT -> summaryLiteRtModelId > 0L
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
