package com.example.llamadroid.ui.ai

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.net.Uri
import android.os.IBinder
import android.provider.Settings
import android.widget.Toast
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.navigation.NavController
import com.example.llamadroid.data.SettingsRepository
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.data.db.ModelType
import com.example.llamadroid.service.*
import com.example.llamadroid.ui.navigation.Screen
import androidx.compose.ui.res.stringResource
import com.example.llamadroid.R
import com.example.llamadroid.ui.components.ResponsiveAction
import com.example.llamadroid.ui.components.ResponsiveActionGroup
import com.example.llamadroid.ui.components.ResponsiveActionStyle
import kotlinx.coroutines.launch
import java.io.File

private const val RECORD_PERMISSION_PREFS = "audio_transcription_permissions"
private const val RECORD_PERMISSION_REQUESTED_KEY = "audio_transcription_record_audio_permission_requested_v1"

/**
 * Audio Transcription Screen using WhisperCPP
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AudioTranscriptionScreen(navController: NavController) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val settingsRepo = remember { SettingsRepository(context) }
    
    // Check for required asset packs
    
    val db = remember { AppDatabase.getDatabase(context) }
    
    // Service binding
    var whisperService by remember { mutableStateOf<WhisperService?>(null) }
    
    DisposableEffect(Unit) {
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                whisperService = (binder as WhisperService.WhisperBinder).getService()
            }
            override fun onServiceDisconnected(name: ComponentName?) {
                whisperService = null
            }
        }
        val intent = Intent(context, WhisperService::class.java)
        context.startForegroundService(intent)
        context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        
        onDispose {
            context.unbindService(connection)
        }
    }
    
    // State
    val whisperState by whisperService?.state?.collectAsState() ?: remember { mutableStateOf(WhisperState.Idle) }
    val whisperProgress by whisperService?.progress?.collectAsState() ?: remember { mutableStateOf("") }
    
    val lastOutputFormats by settingsRepo.whisperLastOutputFormats.collectAsStateWithLifecycle()
    var transcriptionUiState by remember { mutableStateOf(TranscriptionUiState()) }
    val selectedAudioPath = transcriptionUiState.selectedAudioPath
    val transcriptionResult = transcriptionUiState.transcriptionResult
    val errorMessage = transcriptionUiState.errorMessage
    val statusMessage = transcriptionUiState.statusMessage
    var selectedLanguage by remember {
        mutableStateOf(settingsRepo.whisperLastLanguage.value)
    }
    var translateToEnglish by remember {
        mutableStateOf(settingsRepo.whisperLastTranslate.value)
    }
    var outputSrt by remember {
        mutableStateOf(WhisperOutputFormat.SRT in lastOutputFormats)
    }
    var outputTxt by remember {
        mutableStateOf(WhisperOutputFormat.TXT in lastOutputFormats)
    }
    var outputVtt by remember {
        mutableStateOf(WhisperOutputFormat.VTT in lastOutputFormats)
    }
    var outputJson by remember {
        mutableStateOf(WhisperOutputFormat.JSON in lastOutputFormats)
    }

    // Check for shared file (from share intent)
    LaunchedEffect(Unit) {
        val pendingFile = com.example.llamadroid.data.SharedFileHolder.consumeFor(
            com.example.llamadroid.data.SharedFileTarget.AUDIO_TRANSCRIPTION
        )
        if (pendingFile != null) {
            try {
                val mimeType = pendingFile.mimeType
                val isVideo = mimeType.startsWith("video/")
                val extension = if (isVideo) "mp4" else "audio"
                val tempFile = File(
                    context.cacheDir,
                    "whisper_shared_${pendingFile.id}.$extension"
                )
                val inputStream = context.contentResolver.openInputStream(pendingFile.uri)
                    ?: throw IllegalStateException("Shared input stream is unavailable")
                inputStream.use { input ->
                    tempFile.outputStream().use(input::copyTo)
                }
                transcriptionUiState = transcriptionUiState.onAudioSelected(
                    path = tempFile.absolutePath,
                    statusMessage = resources.getString(R.string.whisper_video_loaded_note)
                        .takeIf { isVideo }
                )
            } catch (e: Exception) {
                transcriptionUiState = transcriptionUiState.onTranscriptionFailed(
                    resources.getString(R.string.whisper_error_shared_file)
                )
            }
        }
    }
    
    // Model selection
    val whisperModels by db.modelDao().getModelsByType(ModelType.WHISPER).collectAsState(initial = emptyList())
    var selectedModelPath by remember {
        mutableStateOf(settingsRepo.whisperLastModelPath.value)
    }
    var showModelPicker by remember { mutableStateOf(false) }

    // Settings
    val threads by settingsRepo.whisperThreads.collectAsState()
    val vadConfig by settingsRepo.whisperVadConfig.collectAsState()
    val effectiveVadPath = WhisperVadAssetStore.resolvePath(context, vadConfig.modelPath)

    LaunchedEffect(whisperModels) {
        val selectedStillExists = whisperModels.any { it.path == selectedModelPath }
        if (!selectedStillExists) {
            selectedModelPath = whisperModels.firstOrNull()?.path
            settingsRepo.setWhisperLastModelPath(selectedModelPath)
        }
    }
    
    // State for video extraction
    var isExtractingAudio by remember { mutableStateOf(false) }
    var extractionProgress by remember { mutableStateOf("") }
    
    // File picker - accepts both audio and video
    val mediaFilePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            transcriptionUiState = transcriptionUiState.onInputSelectionStarted()
            scope.launch {
                try {
                    val mimeType = context.contentResolver.getType(it) ?: ""
                    val isVideo = mimeType.startsWith("video/")
                    val extension = if (isVideo) "mp4" else "audio"
                    val tempFile = File(context.cacheDir, "whisper_input.$extension")
                    val inputStream = context.contentResolver.openInputStream(it)
                        ?: throw IllegalStateException("Selected input stream is unavailable")
                    inputStream.use { input ->
                        tempFile.outputStream().use(input::copyTo)
                    }

                    if (!isVideo) {
                        transcriptionUiState = transcriptionUiState.onAudioSelected(tempFile.absolutePath)
                        return@launch
                    }

                    // Extract audio from video using FFmpeg
                    isExtractingAudio = true
                    extractionProgress = resources.getString(R.string.whisper_extracting_audio)
                    
                    val binaryRepo = com.example.llamadroid.data.binary.BinaryRepository(context)
                    val ffmpegBinary = binaryRepo.getFFmpegBinary()
                    val audioOutput = File(context.cacheDir, "whisper_extracted_audio.wav")
                    
                    // Setup FFmpeg library path (like WhisperService does)
                    val libDir = File(context.filesDir, "ffmpeg_libs")
                    if (!libDir.exists()) libDir.mkdirs()
                    
                    if (ffmpegBinary == null || !ffmpegBinary.exists()) {
                        throw IllegalStateException(resources.getString(R.string.whisper_error_ffmpeg_not_found))
                    }
                        
                    android.util.Log.d("AudioTranscription", "FFmpeg binary: ${ffmpegBinary.absolutePath}")
                    android.util.Log.d("AudioTranscription", "FFmpeg exists: ${ffmpegBinary.exists()}")
                    android.util.Log.d("AudioTranscription", "Input file: ${tempFile.absolutePath}")
                    android.util.Log.d("AudioTranscription", "Input exists: ${tempFile.exists()}, size: ${tempFile.length()}")

                    val process = ProcessBuilder(
                        ffmpegBinary.absolutePath,
                        "-y",
                        "-i", tempFile.absolutePath,
                        "-vn",
                        "-acodec", "pcm_s16le",
                        "-ar", "16000",
                        "-ac", "1",
                        audioOutput.absolutePath
                    ).apply {
                        environment()["LD_LIBRARY_PATH"] = "${libDir.absolutePath}:${context.applicationInfo.nativeLibraryDir}"
                        redirectErrorStream(true)
                    }.start()

                    // Read output
                    val output = process.inputStream.bufferedReader().readText()
                    android.util.Log.d("AudioTranscription", "FFmpeg output: $output")

                    val exitCode = process.waitFor()
                    android.util.Log.d("AudioTranscription", "FFmpeg exit code: $exitCode")
                        
                    if (exitCode == 0 && audioOutput.exists()) {
                        extractionProgress = resources.getString(R.string.whisper_extraction_success)
                        transcriptionUiState = transcriptionUiState.onAudioSelected(
                            audioOutput.absolutePath,
                            resources.getString(R.string.whisper_extraction_success)
                        )
                        android.util.Log.d("AudioTranscription", "Audio extracted: ${audioOutput.length()} bytes")
                    } else {
                        transcriptionUiState = transcriptionUiState.onTranscriptionFailed(
                            resources.getString(R.string.whisper_error_extraction, exitCode)
                        )
                        android.util.Log.e("AudioTranscription", "FFmpeg failed: $output")
                    }
                    tempFile.delete()
                } catch (e: Exception) {
                    android.util.Log.e("AudioTranscription", "Audio input error", e)
                    transcriptionUiState = transcriptionUiState.onTranscriptionFailed(
                        if (isExtractingAudio) {
                            resources.getString(
                                R.string.whisper_error_ffmpeg_detail,
                                e.message ?: resources.getString(R.string.error_generic)
                            )
                        } else {
                            resources.getString(R.string.whisper_error_media_load)
                        }
                    )
                } finally {
                    isExtractingAudio = false
                }
            }
        }
    }
    
    // Recording state
    var showRecordingDialog by remember { mutableStateOf(false) }
    var isRecording by remember { mutableStateOf(false) }
    var recordingSeconds by remember { mutableIntStateOf(0) }
    var mediaRecorder by remember { mutableStateOf<MediaRecorder?>(null) }
    var microphonePermissionState by remember {
        mutableStateOf(MicrophonePermissionState.Requestable)
    }
    var showPermissionRationale by rememberSaveable { mutableStateOf(false) }
    var showPermissionSettings by rememberSaveable { mutableStateOf(false) }
    val permissionPreferences = remember(context) {
        context.getSharedPreferences(RECORD_PERMISSION_PREFS, Context.MODE_PRIVATE)
    }
    var hasRequestedRecordPermission by rememberSaveable {
        mutableStateOf(permissionPreferences.getBoolean(RECORD_PERMISSION_REQUESTED_KEY, false))
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    val activity = context as? Activity

    fun refreshRecordPermission() {
        val isGranted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        val shouldShowRationale = activity?.let {
            ActivityCompat.shouldShowRequestPermissionRationale(
                it,
                Manifest.permission.RECORD_AUDIO
            )
        } == true
        microphonePermissionState = classifyMicrophonePermission(
            isGranted = isGranted,
            hasRequestedPermission = hasRequestedRecordPermission,
            shouldShowRationale = shouldShowRationale
        )
        if (microphonePermissionState == MicrophonePermissionState.Granted) {
            showPermissionRationale = false
            showPermissionSettings = false
            transcriptionUiState = transcriptionUiState.clearError(
                resources.getString(R.string.whisper_error_permission)
            )
        }
    }

    LaunchedEffect(context) {
        refreshRecordPermission()
    }

    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                refreshRecordPermission()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    
    // Permission launcher
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasRequestedRecordPermission = true
        permissionPreferences.edit()
            .putBoolean(RECORD_PERMISSION_REQUESTED_KEY, true)
            .apply()
        val shouldShowRationale = activity?.let {
            ActivityCompat.shouldShowRequestPermissionRationale(
                it,
                Manifest.permission.RECORD_AUDIO
            )
        } == true
        microphonePermissionState = classifyMicrophonePermission(
            isGranted = granted,
            hasRequestedPermission = true,
            shouldShowRationale = shouldShowRationale
        )
        if (granted) {
            transcriptionUiState = transcriptionUiState.clearFeedback()
            showPermissionRationale = false
            showPermissionSettings = false
            showRecordingDialog = true
        } else {
            transcriptionUiState = transcriptionUiState.onTranscriptionFailed(
                resources.getString(R.string.whisper_error_permission)
            )
            when (microphonePermissionState) {
                MicrophonePermissionState.RationaleRequired -> showPermissionRationale = true
                MicrophonePermissionState.PermanentlyDenied -> showPermissionSettings = true
                else -> Unit
            }
        }
    }

    fun requestRecordPermission() {
        refreshRecordPermission()
        when (microphonePermissionState) {
            MicrophonePermissionState.Granted -> {
                transcriptionUiState = transcriptionUiState.clearFeedback()
                showRecordingDialog = true
            }
            MicrophonePermissionState.Requestable -> {
                if (activity == null) {
                    transcriptionUiState = transcriptionUiState.onTranscriptionFailed(
                        resources.getString(R.string.whisper_error_permission)
                    )
                } else {
                    hasRequestedRecordPermission = true
                    permissionPreferences.edit()
                        .putBoolean(RECORD_PERMISSION_REQUESTED_KEY, true)
                        .apply()
                    permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                }
            }
            MicrophonePermissionState.RationaleRequired -> showPermissionRationale = true
            MicrophonePermissionState.PermanentlyDenied -> showPermissionSettings = true
        }
    }

    fun openRecordPermissionSettings() {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${context.packageName}")
            }
        )
        showPermissionSettings = false
    }
    
    // Recording timer
    LaunchedEffect(isRecording) {
        if (isRecording) {
            recordingSeconds = 0
            while (isRecording) {
                kotlinx.coroutines.delay(1000)
                recordingSeconds++
            }
        }
    }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.whisper_title)) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                actions = {
                    IconButton(onClick = { navController.navigate("settings_whisper") }) {
                        Icon(
                            Icons.Default.Settings,
                            contentDescription = stringResource(R.string.whisper_settings_title)
                        )
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
            // Model Selection
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(stringResource(R.string.whisper_model), style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    if (whisperModels.isEmpty()) {
                        OutlinedButton(
                            onClick = { navController.navigate(Screen.WhisperModels.route) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.action_download))
                        }
                    } else {
                        OutlinedButton(
                            onClick = { showModelPicker = true },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(selectedModelPath?.substringAfterLast("/") ?: stringResource(R.string.whisper_select_model))
                        }
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // Audio Selection
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(stringResource(R.string.whisper_source_label), style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    val mediaLabel = stringResource(R.string.whisper_media_btn)
                    val recordLabel = stringResource(R.string.whisper_record_btn)
                    ResponsiveActionGroup(
                        actions = listOf(
                            ResponsiveAction(
                                label = mediaLabel,
                                onClick = { mediaFilePicker.launch(arrayOf("audio/*", "video/*")) },
                                modifier = Modifier.heightIn(min = 48.dp),
                                icon = Icons.Default.List,
                                contentDescription = mediaLabel,
                                style = ResponsiveActionStyle.Secondary
                            ),
                            ResponsiveAction(
                                label = recordLabel,
                                onClick = { requestRecordPermission() },
                                modifier = Modifier.heightIn(min = 48.dp),
                                icon = Icons.Default.Add,
                                contentDescription = recordLabel,
                                style = ResponsiveActionStyle.Secondary
                            )
                        )
                    )
                    
                    // Show extraction progress for video files
                    if (isExtractingAudio) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Text(
                                extractionProgress,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                    
                    if (selectedAudioPath != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            stringResource(R.string.whisper_selected_file, selectedAudioPath!!.substringAfterLast("/")),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // Settings
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(stringResource(R.string.whisper_settings_label), style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    // Language selection
                    var languageExpanded by remember { mutableStateOf(false) }
                    ExposedDropdownMenuBox(
                        expanded = languageExpanded,
                        onExpandedChange = { languageExpanded = it }
                    ) {
                        OutlinedTextField(
                            value = WhisperLanguages.languages.find { it.first == selectedLanguage }?.second ?: stringResource(R.string.whisper_auto_detect),
                            onValueChange = {},
                            readOnly = true,
                            label = { Text(stringResource(R.string.whisper_language_label)) },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = languageExpanded) },
                            modifier = Modifier.fillMaxWidth().menuAnchor()
                        )
                        ExposedDropdownMenu(
                            expanded = languageExpanded,
                            onDismissRequest = { languageExpanded = false }
                        ) {
                            WhisperLanguages.languages.take(20).forEach { (code, name) ->
                                DropdownMenuItem(
                                    text = { Text(name) },
                                    onClick = {
                                        selectedLanguage = code
                                        settingsRepo.setWhisperLastLanguage(code)
                                        languageExpanded = false
                                    }
                                )
                            }
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    // Translate toggle
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(stringResource(R.string.whisper_translate_label))
                        Switch(
                            checked = translateToEnglish,
                            onCheckedChange = { enabled ->
                                translateToEnglish = enabled
                                settingsRepo.setWhisperLastTranslate(enabled)
                            }
                        )
                    }
                    
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    // Output formats
                    Text(stringResource(R.string.whisper_output_formats), style = MaterialTheme.typography.labelLarge)
                    Spacer(modifier = Modifier.height(4.dp))
                    ResponsiveActionGroup(
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilterChip(
                            selected = outputTxt,
                            onClick = { outputTxt = !outputTxt },
                            label = { Text(stringResource(R.string.format_txt)) }
                        )
                        FilterChip(
                            selected = outputSrt,
                            onClick = { outputSrt = !outputSrt },
                            label = { Text(stringResource(R.string.format_srt)) }
                        )
                        FilterChip(
                            selected = outputVtt,
                            onClick = { outputVtt = !outputVtt },
                            label = { Text(stringResource(R.string.format_vtt)) }
                        )
                        FilterChip(
                            selected = outputJson,
                            onClick = { outputJson = !outputJson },
                            label = { Text(stringResource(R.string.format_json)) }
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(
                            when {
                                !vadConfig.enabled -> R.string.whisper_vad_status_disabled
                                effectiveVadPath == null ->
                                    R.string.whisper_vad_status_enabled_missing
                                else -> R.string.whisper_vad_status_enabled
                            }
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (vadConfig.enabled && effectiveVadPath == null) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(24.dp))
            
            // Transcribe Button Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = {
                        if (selectedModelPath == null) {
                            transcriptionUiState = transcriptionUiState.onTranscriptionFailed(
                                resources.getString(R.string.whisper_error_no_model)
                            )
                            return@Button
                        }
                        if (selectedAudioPath == null) {
                            transcriptionUiState = transcriptionUiState.onTranscriptionFailed(
                                resources.getString(R.string.whisper_error_no_audio)
                            )
                            return@Button
                        }
                        
                        val formats = mutableSetOf<WhisperOutputFormat>()
                        if (outputTxt) formats.add(WhisperOutputFormat.TXT)
                        if (outputSrt) formats.add(WhisperOutputFormat.SRT)
                        if (outputVtt) formats.add(WhisperOutputFormat.VTT)
                        if (outputJson) formats.add(WhisperOutputFormat.JSON)
                        if (formats.isEmpty()) formats.add(WhisperOutputFormat.TXT)

                        val whisperVad = settingsRepo.whisperVadConfigSnapshot()
                        if (whisperVad.enabled && whisperVad.modelPath.isNullOrBlank()) {
                            transcriptionUiState = transcriptionUiState.onTranscriptionFailed(
                                resources.getString(R.string.whisper_error_vad_model_missing)
                            )
                            return@Button
                        }
                        transcriptionUiState = transcriptionUiState.onTranscriptionStarted()
                        settingsRepo.setWhisperLastModelPath(selectedModelPath)
                        settingsRepo.setWhisperLastLanguage(selectedLanguage)
                        settingsRepo.setWhisperLastTranslate(translateToEnglish)
                        settingsRepo.setWhisperLastOutputFormats(formats)

                        val config = WhisperConfig(
                            modelPath = selectedModelPath!!,
                            audioPath = selectedAudioPath!!,
                            language = selectedLanguage,
                            translate = translateToEnglish,
                            outputFormats = formats,
                            threads = threads,
                            purpose = WhisperInvocationPurpose.BATCH_TRANSCRIPTION,
                            vad = whisperVad
                        )
                        
                        scope.launch {
                            val service = whisperService
                            if (service == null) {
                                transcriptionUiState = transcriptionUiState.onTranscriptionFailed(
                                    resources.getString(R.string.whisper_error_no_service)
                                )
                                return@launch
                            }
                            service.transcribe(config).fold(
                                onSuccess = {
                                    transcriptionUiState = transcriptionUiState
                                        .onTranscriptionSucceeded(it.text)
                                },
                                onFailure = { error ->
                                    transcriptionUiState = if (
                                        error is kotlinx.coroutines.CancellationException
                                    ) {
                                        transcriptionUiState.onTranscriptionCancelled()
                                    } else {
                                        transcriptionUiState.onTranscriptionFailed(
                                            error.message
                                                ?: resources.getString(R.string.error_generic)
                                        )
                                    }
                                }
                            )
                        }
                    },
                    modifier = Modifier.weight(1f),
                    enabled = !transcriptionUiState.isRunning && (
                        whisperState == WhisperState.Idle ||
                            whisperState == WhisperState.Completed ||
                            whisperState == WhisperState.Cancelled ||
                            whisperState is WhisperState.Error
                        )
                ) {
                    when (whisperState) {
                        is WhisperState.Converting -> {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.whisper_status_converting))
                        }
                        is WhisperState.Transcribing -> {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.whisper_status_transcribing))
                        }
                        else -> {
                            Icon(Icons.Default.PlayArrow, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.whisper_transcribe_btn))
                        }
                    }
                }
                
                // Cancel button - visible when transcribing or converting
                if (whisperState is WhisperState.Converting || whisperState is WhisperState.Transcribing) {
                    OutlinedButton(
                        onClick = {
                            whisperService?.cancel()
                            transcriptionUiState = transcriptionUiState.onTranscriptionCancelled()
                        },
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Icon(Icons.Default.Close, contentDescription = null)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(stringResource(R.string.action_cancel))
                    }
                }
            }
            
            // Progress
            if (whisperProgress.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    whisperProgress,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            
            // Error
            errorMessage?.let {
                Spacer(modifier = Modifier.height(8.dp))
                Text(it, color = MaterialTheme.colorScheme.error)
            }

            statusMessage?.let {
                Spacer(modifier = Modifier.height(8.dp))
                Text(it, color = MaterialTheme.colorScheme.primary)
            }
            
            // Result
            transcriptionResult?.let {
                Spacer(modifier = Modifier.height(16.dp))
                TranscriptionResultCard(result = it)
            }
        }
    }
    
    // Model Picker Dialog
    if (showModelPicker) {
        AlertDialog(
            onDismissRequest = { showModelPicker = false },
            title = { Text(stringResource(R.string.whisper_select_model)) },
            text = {
                Column {
                    whisperModels.forEach { model ->
                        TextButton(
                            onClick = {
                                selectedModelPath = model.path
                                settingsRepo.setWhisperLastModelPath(model.path)
                                showModelPicker = false
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(model.filename)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showModelPicker = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }
    
    // Recording Dialog
    if (showRecordingDialog) {
        val recordingFile = remember { File(context.cacheDir, "whisper_recording.m4a") }
        
        AlertDialog(
            onDismissRequest = {
                // Stop recording if active
                if (isRecording) {
                    try {
                        mediaRecorder?.stop()
                        mediaRecorder?.release()
                    } catch (e: Exception) { }
                    mediaRecorder = null
                    isRecording = false
                }
                showRecordingDialog = false
            },
            title = { Text(stringResource(R.string.whisper_record_title), fontWeight = FontWeight.Bold) },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Timer display
                    val minutes = recordingSeconds / 60
                    val seconds = recordingSeconds % 60
                    Text(
                        String.format(java.util.Locale.getDefault(), "%02d:%02d", minutes, seconds),
                        style = MaterialTheme.typography.headlineLarge,
                        fontWeight = FontWeight.Bold,
                        color = if (isRecording) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                    )
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    if (isRecording) {
                        Text(stringResource(R.string.whisper_recording), color = MaterialTheme.colorScheme.error)
                    } else if (recordingSeconds > 0) {
                        Text(stringResource(R.string.whisper_record_saved), color = MaterialTheme.colorScheme.primary)
                    } else {
                        Text(stringResource(R.string.whisper_record_hint))
                    }
                }
            },
            confirmButton = {
                if (!isRecording && recordingSeconds > 0) {
                    // Use and save recording to permanent storage
                    TextButton(onClick = {
                        try {
                            val recordingsDir = java.io.File(context.filesDir, "sd_output/Recordings").apply { mkdirs() }
                            val timestamp = java.text.SimpleDateFormat("yyyy-MM-dd_HHmmss", java.util.Locale.getDefault()).format(java.util.Date())
                            val savedFile = java.io.File(recordingsDir, "recording_$timestamp.m4a")
                            recordingFile.copyTo(savedFile, overwrite = true)
                            recordingFile.delete()
                            transcriptionUiState = transcriptionUiState.onAudioSelected(
                                savedFile.absolutePath
                            )
                            showRecordingDialog = false
                            recordingSeconds = 0
                        } catch (e: Exception) {
                            transcriptionUiState = transcriptionUiState.onTranscriptionFailed(
                                resources.getString(
                                    R.string.whisper_error_save_recording,
                                    e.message ?: resources.getString(R.string.error_generic)
                                )
                            )
                        }
                    }) {
                        Text(stringResource(R.string.whisper_use_recording))
                    }
                } else if (!isRecording) {
                    // Start recording
                    TextButton(onClick = {
                        try {
                            transcriptionUiState = transcriptionUiState.clearFeedback()
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
                            isRecording = true
                        } catch (e: Exception) {
                            transcriptionUiState = transcriptionUiState.onTranscriptionFailed(
                                resources.getString(
                                    R.string.whisper_error_start_recording,
                                    e.message ?: resources.getString(R.string.error_generic)
                                )
                            )
                            showRecordingDialog = false
                        }
                    }) {
                        Text(stringResource(R.string.action_start))
                    }
                } else {
                    // Stop recording
                    TextButton(onClick = {
                        try {
                            mediaRecorder?.stop()
                            mediaRecorder?.release()
                            mediaRecorder = null
                            isRecording = false
                        } catch (e: Exception) {
                            transcriptionUiState = transcriptionUiState.onTranscriptionFailed(
                                resources.getString(
                                    R.string.whisper_error_stop_recording,
                                    e.message ?: resources.getString(R.string.error_generic)
                                )
                            )
                        }
                    }) {
                        Text(stringResource(R.string.action_stop))
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    if (isRecording) {
                        try {
                            mediaRecorder?.stop()
                            mediaRecorder?.release()
                        } catch (e: Exception) { }
                        mediaRecorder = null
                        isRecording = false
                    }
                    recordingSeconds = 0
                    showRecordingDialog = false
                }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    if (showPermissionRationale) {
        AlertDialog(
            onDismissRequest = { showPermissionRationale = false },
            title = { Text(stringResource(R.string.whisper_permission_rationale_title)) },
            text = { Text(stringResource(R.string.whisper_permission_rationale_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showPermissionRationale = false
                        hasRequestedRecordPermission = true
                        permissionPreferences.edit()
                            .putBoolean(RECORD_PERMISSION_REQUESTED_KEY, true)
                            .apply()
                        permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }
                ) {
                    Text(stringResource(R.string.whisper_permission_retry))
                }
            },
            dismissButton = {
                TextButton(onClick = { showPermissionRationale = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    if (showPermissionSettings) {
        AlertDialog(
            onDismissRequest = { showPermissionSettings = false },
            title = { Text(stringResource(R.string.whisper_permission_settings_title)) },
            text = { Text(stringResource(R.string.whisper_permission_settings_message)) },
            confirmButton = {
                TextButton(onClick = { openRecordPermissionSettings() }) {
                    Text(stringResource(R.string.whisper_open_settings))
                }
            },
            dismissButton = {
                TextButton(onClick = { showPermissionSettings = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }
}

@Composable
internal fun TranscriptionResultCard(
    result: String,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                stringResource(R.string.whisper_result_label),
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(modifier = Modifier.height(8.dp))
            val copyLabel = stringResource(R.string.action_copy)
            val shareLabel = stringResource(R.string.action_share)
            ResponsiveActionGroup(
                actions = listOf(
                    ResponsiveAction(
                        label = copyLabel,
                        onClick = { copyTranscriptionResult(context, result) },
                        modifier = Modifier.heightIn(min = 48.dp),
                        icon = Icons.Default.ContentCopy,
                        contentDescription = copyLabel,
                        style = ResponsiveActionStyle.Secondary
                    ),
                    ResponsiveAction(
                        label = shareLabel,
                        onClick = {
                            context.startActivity(
                                Intent.createChooser(
                                    createTranscriptionShareIntent(result),
                                    resources.getString(R.string.whisper_share_result)
                                )
                            )
                        },
                        modifier = Modifier.heightIn(min = 48.dp),
                        icon = Icons.Default.Share,
                        contentDescription = shareLabel,
                        style = ResponsiveActionStyle.Secondary
                    )
                )
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(result, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

internal fun copyTranscriptionResult(context: Context, result: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(
        ClipData.newPlainText(context.getString(R.string.whisper_result_label), result)
    )
    Toast.makeText(
        context,
        context.getString(R.string.whisper_copy_success),
        Toast.LENGTH_SHORT
    ).show()
}

internal fun createTranscriptionShareIntent(result: String): Intent =
    Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, result)
    }
