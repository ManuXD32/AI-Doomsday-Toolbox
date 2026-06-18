package com.example.llamadroid.tama.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import coil.compose.AsyncImage
import androidx.navigation.NavController
import com.example.llamadroid.R
import com.example.llamadroid.data.SettingsRepository
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.data.db.ModelType
import com.example.llamadroid.data.model.LITERT_BACKEND_AUTO
import com.example.llamadroid.data.model.LITERT_BACKEND_CPU
import com.example.llamadroid.data.model.LITERT_BACKEND_GPU
import com.example.llamadroid.data.model.normalizeLiteRtBackend
import com.example.llamadroid.service.OllamaService
import com.example.llamadroid.service.WhisperLanguages
import com.example.llamadroid.tama.game.TamaAgentService
import com.example.llamadroid.tama.game.TamaGameEngine
import com.example.llamadroid.tama.game.TamaTranscriptionStatus
import com.example.llamadroid.tama.db.TamaSummaryEntity
import com.example.llamadroid.ui.navigation.Screen
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.PickVisualMediaRequest
import java.util.UUID
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.flowOf

private const val TAMA_CHAT_SENDER_YOU_ASSET = "tama/chat/sender_you.png"
private const val TAMA_CHAT_SENDER_TAMA_ASSET = "tama/chat/sender_tama.png"
private const val TAMA_CHAT_AUDIO_ASSET = "tama/chat/audio.png"
private const val TAMA_CHAT_TRANSCRIPT_ASSET = "tama/chat/transcript.png"
private const val TAMA_CHAT_WARNING_ASSET = "tama/chat/warning.png"
private val TamaChatSenderIconSize = 22.dp
private val TamaChatInlineIconSize = 24.dp
private val TamaChatTranscriptIconSize = 22.dp

/**
 * TamaChatScreen - AI Chat interface for the virtual pet.
 * Uses the retro LCD aesthetic from the main Tama screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TamaChatScreen(
    navController: NavController,
    gameEngine: TamaGameEngine,
    agentService: TamaAgentService,
    settingsRepo: SettingsRepository
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val pet by gameEngine.pet.collectAsState()
    val messages by agentService.messages.collectAsState()
    val isLoading by agentService.isLoading.collectAsState()
    val isBackendConnected by agentService.isBackendConnected.collectAsState()
    val backend by settingsRepo.tamaBackend.collectAsState()
    val imageInputEnabled by settingsRepo.tamaChatImageInputEnabled.collectAsState()
    val latestSummary by remember(pet?.id) {
        pet?.let { agentService.observeLatestSummary(it.id) } ?: flowOf<TamaSummaryEntity?>(null)
    }.collectAsState(initial = null)

    val listState = rememberLazyListState()
    var inputText by remember { mutableStateOf("") }
    var showSummaryDialog by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var currentSummary by remember { mutableStateOf("") }
    var voiceRecorder by remember { mutableStateOf<MediaRecorder?>(null) }
    var voiceRecording by remember { mutableStateOf(false) }
    var voiceRecordingSeconds by remember { mutableIntStateOf(0) }
    var attachedAudioPath by remember { mutableStateOf<String?>(null) }
    var attachedAudioDurationMs by remember { mutableStateOf<Long?>(null) }
    var attachedImagePath by remember { mutableStateOf<String?>(null) }
    var imagePreviewPath by remember { mutableStateOf<String?>(null) }
    var voiceError by remember { mutableStateOf<String?>(null) }
    val voiceRecordingFile = remember { File(context.cacheDir, "tama_voice_recording.m4a") }

    LaunchedEffect(latestSummary?.summary) {
        currentSummary = latestSummary?.summary.orEmpty()
    }

    LaunchedEffect(voiceRecording) {
        if (!voiceRecording) return@LaunchedEffect
        voiceRecordingSeconds = 0
        while (voiceRecording) {
            kotlinx.coroutines.delay(1000)
            voiceRecordingSeconds++
        }
    }

    // Auto-scroll to bottom
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    // Load history once when pet ID is available
    LaunchedEffect(pet?.id, backend) {
        pet?.id?.let {
            agentService.loadHistory(it)
            agentService.retryConnection()
        }
    }

    val backendLabel = when (SettingsRepository.normalizeOllamaOrLlamaBackend(backend)) {
        SettingsRepository.PDF_BACKEND_LLAMA_SERVER -> stringResource(R.string.tama_backend_llama_server)
        SettingsRepository.PDF_BACKEND_LLAMA_SWAP -> stringResource(R.string.tama_backend_llama_swap)
        SettingsRepository.PDF_BACKEND_LITERT -> stringResource(R.string.tama_backend_litert)
        else -> stringResource(R.string.tama_backend_ollama)
    }

    fun startVoiceRecording() {
        if (voiceRecording) return
        try {
            voiceError = null
            attachedAudioPath = null
            attachedAudioDurationMs = null
            voiceRecordingSeconds = 0
            @Suppress("DEPRECATION")
            val recorder = MediaRecorder().apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioSamplingRate(44100)
                setAudioEncodingBitRate(128000)
                setOutputFile(voiceRecordingFile.absolutePath)
                prepare()
                start()
            }
            voiceRecorder = recorder
            voiceRecording = true
        } catch (e: Exception) {
            voiceError = context.getString(R.string.whisper_error_start_recording, e.message ?: context.getString(R.string.error_generic))
            voiceRecording = false
            voiceRecorder?.release()
            voiceRecorder = null
        }
    }

    fun persistVoiceRecording(): String? {
        if (!voiceRecordingFile.exists()) return null
        val petId = pet?.id ?: return null
        val timestamp = SimpleDateFormat("yyyy-MM-dd_HHmmss", Locale.getDefault()).format(Date())
        val recordingsDir = File(context.filesDir, "tama_chat_audio/$petId").apply { mkdirs() }
        val savedFile = File(recordingsDir, "voice_$timestamp.m4a")
        voiceRecordingFile.copyTo(savedFile, overwrite = true)
        voiceRecordingFile.delete()
        attachedAudioDurationMs = measureAudioDurationMs(savedFile.absolutePath)
        return savedFile.absolutePath
    }

    fun stopVoiceRecording() {
        if (!voiceRecording) return
        try {
            voiceRecorder?.stop()
        } catch (_: Exception) {
        } finally {
            voiceRecorder?.release()
            voiceRecorder = null
            voiceRecording = false
            attachedAudioPath = persistVoiceRecording()
            voiceRecordingSeconds = 0
        }
    }

    fun dropAudioAttachment(deleteFile: Boolean) {
        val previousPath = attachedAudioPath
        runCatching { voiceRecorder?.release() }
        voiceRecorder = null
        voiceRecording = false
        voiceRecordingSeconds = 0
        attachedAudioPath = null
        attachedAudioDurationMs = null
        voiceError = null
        voiceRecordingFile.delete()
        if (deleteFile) {
            previousPath?.let { path ->
                runCatching { File(path).takeIf(File::exists)?.delete() }
            }
        }
    }

    fun dropImageAttachment(deleteFile: Boolean) {
        val previousPath = attachedImagePath
        attachedImagePath = null
        imagePreviewPath = null
        if (deleteFile) {
            previousPath?.let { path ->
                runCatching { File(path).takeIf(File::exists)?.delete() }
            }
        }
    }

    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        uri?.let {
            attachedImagePath = persistTamaChatImage(context, pet?.id, it)
            voiceError = null
        }
    }

    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            startVoiceRecording()
            voiceError = null
        } else {
            voiceError = context.getString(R.string.whisper_error_permission)
        }
    }

    LaunchedEffect(imageInputEnabled, attachedImagePath) {
        if (!imageInputEnabled && attachedImagePath != null) {
            dropImageAttachment(deleteFile = true)
        }
    }

    suspend fun sendCurrentMessage() {
        val currentPet = pet ?: return
        val audioPath = attachedAudioPath
        val imagePath = attachedImagePath?.takeIf { imageInputEnabled }
        val text = inputText.trim()

        if (!imageInputEnabled && attachedImagePath != null) {
            voiceError = context.getString(R.string.tama_chat_image_input_disabled_error)
            return
        }
        if (text.isBlank() && imagePath == null && audioPath == null) {
            return
        }
        agentService.sendMessage(
            currentPet,
            text,
            audioPath = audioPath,
            audioDurationMs = attachedAudioDurationMs,
            imagePath = imagePath
        )
        inputText = ""
        dropAudioAttachment(deleteFile = false)
        dropImageAttachment(deleteFile = false)
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(TamaBackground)
    ) {
        // App Bar / Header
        TopAppBar(
            title = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "${pet?.name ?: stringResource(R.string.tama_chat_default_pet_name)} (${pet?.mood?.name ?: stringResource(R.string.tama_chat_unknown_mood)})",
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(
                                modifier = Modifier.size(6.dp),
                                shape = androidx.compose.foundation.shape.CircleShape,
                                color = if (isBackendConnected) Color(0xFF4CAF50) else Color(0xFFF44336)
                            ) {}
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = backendLabel,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 9.sp,
                                color = if (isBackendConnected) Color(0xFF4CAF50) else Color(0xFFF44336)
                            )
                        }
                    }
                }
            },
            navigationIcon = {
                IconButton(onClick = { navController.popBackStack() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                }
            },
            actions = {
                // Retry Connection
                IconButton(onClick = {
                    agentService.retryConnection()
                }) {
                    Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.tama_chat_retry_connection))
                }

                // View/Edit Summary (Brain)
                IconButton(onClick = {
                    scope.launch {
                        currentSummary = latestSummary?.summary.orEmpty()
                        showSummaryDialog = true
                    }
                }) {
                    Icon(Icons.Default.Psychology, contentDescription = stringResource(R.string.tama_chat_memory))
                }

                IconButton(onClick = { showSettings = true }) {
                    Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.action_settings))
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = TamaDark,
                titleContentColor = TamaLight,
                navigationIconContentColor = TamaLight,
                actionIconContentColor = TamaLight
            )
        )

        // Chat Messages
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(top = 16.dp, bottom = 16.dp)
        ) {
            items(messages, key = { it.id ?: UUID.randomUUID().toString() }) { message ->
                TamaChatBubble(message) {
                    agentService.deleteMessage(message.id!!)
                }
            }

            if (isLoading) {
                item {
                    ThinkingIndicator()
                }
            }
        }

        // Input Field
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = TamaLight,
            shadowElevation = 4.dp
        ) {
            Column(
                modifier = Modifier
                    .padding(8.dp)
                    .imePadding(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (voiceRecording) {
                    RecordingStrip(
                        seconds = voiceRecordingSeconds,
                        onStop = { stopVoiceRecording() }
                    )
                }

                if (attachedImagePath != null) {
                    TamaChatImageAttachmentChip(
                        imagePath = attachedImagePath!!,
                        onPreview = { imagePreviewPath = attachedImagePath },
                        onRemove = { dropImageAttachment(deleteFile = true) }
                    )
                }

                if (attachedAudioPath != null) {
                    TamaChatAudioAttachmentChip(
                        audioPath = attachedAudioPath!!,
                        audioDurationMs = attachedAudioDurationMs,
                        onPreview = {
                            // no-op preview handled inside chip
                        },
                        onRemove = { dropAudioAttachment(deleteFile = true) }
                    )
                }

                Row(
                    verticalAlignment = Alignment.Bottom,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    IconButton(
                        onClick = {
                            if (imageInputEnabled) {
                                imagePickerLauncher.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                )
                            }
                        },
                        enabled = !isLoading && imageInputEnabled,
                        modifier = Modifier
                            .padding(bottom = 4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Image,
                            contentDescription = stringResource(R.string.llama_attach_image),
                            tint = if (isLoading || !imageInputEnabled) TamaMutedText else TamaDark
                        )
                    }

                    IconButton(
                        onClick = {
                            if (voiceRecording) {
                                stopVoiceRecording()
                            } else {
                                micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        },
                        enabled = !isLoading,
                        modifier = Modifier
                            .padding(bottom = 4.dp)
                    ) {
                        Icon(
                            imageVector = if (voiceRecording) Icons.Default.Stop else Icons.Default.Mic,
                            contentDescription = stringResource(R.string.tama_chat_voice_button),
                            tint = if (isLoading) TamaMutedText else TamaDark
                        )
                    }

                    OutlinedTextField(
                        value = inputText,
                        onValueChange = { inputText = it },
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 8.dp),
                        placeholder = { Text(stringResource(R.string.tama_chat_placeholder), color = TamaMutedText, fontSize = 14.sp) },
                        colors = tamaOutlinedFieldColors(containerColor = Color.Transparent),
                        maxLines = 4,
                        textStyle = TextStyle(
                            fontFamily = FontFamily.Monospace,
                            color = TamaDark,
                            fontSize = 14.sp
                        ),
                        shape = RoundedCornerShape(8.dp)
                    )

                    IconButton(
                        onClick = {
                            scope.launch {
                                sendCurrentMessage()
                            }
                        },
                        modifier = Modifier
                            .padding(bottom = 4.dp)
                            .size(52.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(
                                if ((inputText.isNotBlank() || attachedImagePath != null || attachedAudioPath != null) && !isLoading) TamaDark else TamaAccent
                            ),
                        enabled = (inputText.isNotBlank() || attachedImagePath != null || attachedAudioPath != null) && !isLoading
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Send,
                            contentDescription = stringResource(R.string.action_send),
                            tint = TamaLight
                        )
                    }
                }

                voiceError?.let {
                    Text(
                        text = it,
                        fontFamily = FontFamily.Monospace,
                        color = Color(0xFFB00020),
                        fontSize = 11.sp,
                        modifier = Modifier.padding(horizontal = 4.dp)
                    )
                }
            }
        }
    }

    if (showSettings) {
        TamaChatSettingsDialog(
            navController = navController,
            settingsRepo = settingsRepo,
            agentService = agentService,
            onDismiss = { showSettings = false }
        )
    }

    if (imagePreviewPath != null) {
        val previewFile = File(imagePreviewPath!!)
        if (previewFile.exists()) {
            TamaPopupDialog(
                title = stringResource(R.string.llama_image_attached),
                backgroundAsset = "tama/backgrounds/library_room.png",
                onDismissRequest = { imagePreviewPath = null },
                bodyContent = {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = TamaBackground)
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            AsyncImage(
                                model = previewFile,
                                contentDescription = stringResource(R.string.llama_image_attached),
                                contentScale = ContentScale.Fit,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 220.dp, max = 520.dp)
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            TextButton(onClick = { imagePreviewPath = null }, modifier = Modifier.align(Alignment.End)) {
                                Text(stringResource(R.string.action_close))
                            }
                        }
                    }
                },
                footerContent = {}
            )
        } else {
            imagePreviewPath = null
        }
    }

    if (showSummaryDialog) {
        SummaryEditDialog(
            currentSummary = currentSummary,
            isLoading = isLoading,
            onSave = {
                scope.launch {
                    agentService.updateSummary(pet!!, it)
                }
                showSummaryDialog = false
            },
            onRecreateMemory = {
                agentService.requestSummaryRefresh(pet!!)
            },
            onDismiss = { showSummaryDialog = false }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SummaryEditDialog(
    currentSummary: String,
    isLoading: Boolean,
    onSave: (String) -> Unit,
    onRecreateMemory: () -> Unit,
    onDismiss: () -> Unit
) {
    var text by remember(currentSummary) { mutableStateOf(currentSummary) }
    var showRecreateConfirmation by remember { mutableStateOf(false) }
    val hasUnsavedChanges = text != currentSummary

    if (showRecreateConfirmation) {
        TamaPopupDialog(
            title = stringResource(R.string.tama_chat_recreate_memory_confirm_title),
            backgroundAsset = "tama/backgrounds/library_room.png",
            compact = true,
            onDismissRequest = { showRecreateConfirmation = false },
            bodyContent = {
                Text(
                    stringResource(R.string.tama_chat_recreate_memory_confirm_body),
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    color = TamaDark
                )
            },
            footerContent = {
                TextButton(
                    onClick = { showRecreateConfirmation = false },
                    modifier = Modifier.weight(1f)
                ) {
                    Text(stringResource(R.string.action_cancel), color = TamaLight, fontFamily = FontFamily.Monospace)
                }
                Button(
                    onClick = {
                        showRecreateConfirmation = false
                        onRecreateMemory()
                    },
                    enabled = !isLoading,
                    colors = ButtonDefaults.buttonColors(containerColor = TamaLight),
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        stringResource(R.string.tama_chat_recreate_memory_confirm_action),
                        color = TamaDark,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        )
    }

    TamaPopupDialog(
        title = stringResource(R.string.tama_chat_memory_title),
        backgroundAsset = "tama/backgrounds/library_room.png",
        onDismissRequest = onDismiss,
        showFooter = false,
        bodyContent = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    stringResource(R.string.tama_chat_memory_hint),
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    color = TamaMutedText
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 160.dp, max = 260.dp),
                    textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = TamaDark),
                    colors = tamaOutlinedFieldColors()
                )
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { onSave(text) },
                        colors = ButtonDefaults.buttonColors(containerColor = TamaDark),
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(stringResource(R.string.tama_chat_save_memory), color = TamaLight, fontFamily = FontFamily.Monospace)
                    }
                    OutlinedButton(
                        onClick = {
                            if (hasUnsavedChanges) {
                                showRecreateConfirmation = true
                            } else {
                                onRecreateMemory()
                            }
                        },
                        enabled = !isLoading,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (isLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = TamaDark
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                        } else {
                            Icon(
                                Icons.Default.Sync,
                                contentDescription = stringResource(R.string.tama_chat_recreate_memory),
                                tint = TamaDark
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                        Text(
                            stringResource(R.string.tama_chat_recreate_memory),
                            color = TamaDark,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                    TextButton(
                        onClick = onDismiss,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.action_cancel), color = TamaDark, fontFamily = FontFamily.Monospace)
                    }
                }
            }
        },
        footerContent = {}
    )
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun TamaChatBubble(
    message: OllamaService.ChatMessage,
    onDelete: () -> Unit
) {
    if (message.role == "system") return

    val isUser = message.role == "user"
    val audioFile = remember(message.audioPath) { message.audioPath?.let(::File)?.takeIf { it.exists() } }
    val imageFile = remember(message.imagePath) { message.imagePath?.let(::File)?.takeIf { it.exists() } }
    val audioDurationLabel = remember(message.audioPath, message.audioDurationMs) {
        formatAudioDuration(
            message.audioDurationMs ?: message.audioPath?.let(::measureAudioDurationMs)
        )
    }
    val warningBubble = !isUser && message.content.startsWith("⚠️")
    val displayContent = remember(message.content, warningBubble) {
        if (warningBubble) message.content.removePrefix("⚠️").trimStart() else message.content
    }
    var mediaPlayer by remember(message.audioPath) { mutableStateOf<MediaPlayer?>(null) }
    var isAudioPlaying by remember(message.audioPath) { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var showImagePreview by remember(message.imagePath) { mutableStateOf(false) }
    var transcriptExpanded by remember(message.id) { mutableStateOf(false) }

    DisposableEffect(message.audioPath) {
        onDispose {
            runCatching {
                mediaPlayer?.release()
            }
            mediaPlayer = null
            isAudioPlaying = false
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AsyncImage(
                model = "file:///android_asset/${if (isUser) TAMA_CHAT_SENDER_YOU_ASSET else TAMA_CHAT_SENDER_TAMA_ASSET}",
                contentDescription = null,
                modifier = Modifier.size(TamaChatSenderIconSize),
                contentScale = ContentScale.Fit
            )
            Text(
                text = if (isUser) stringResource(R.string.tama_chat_sender_you) else stringResource(R.string.tama_chat_sender_tama),
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                color = TamaMutedText
            )
        }

        Box {
            Surface(
                color = if (isUser) TamaDark else (if (warningBubble) Color(0xFFB71C1C) else TamaLight),
                shape = RoundedCornerShape(
                    topStart = 16.dp,
                    topEnd = 16.dp,
                    bottomStart = if (isUser) 16.dp else 4.dp,
                    bottomEnd = if (isUser) 4.dp else 16.dp
                ),
                border = if (!isUser && !warningBubble) androidx.compose.foundation.BorderStroke(2.dp, TamaDark) else null,
                shadowElevation = 2.dp,
                modifier = Modifier.combinedClickable(
                    onClick = { },
                    onLongClick = { showMenu = true }
                )
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (imageFile != null) {
                        AsyncImage(
                            model = imageFile,
                            contentDescription = stringResource(R.string.llama_image_attached),
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 120.dp, max = 260.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { showImagePreview = true }
                        )
                    }
                    if (audioFile != null) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            AsyncImage(
                                model = "file:///android_asset/$TAMA_CHAT_AUDIO_ASSET",
                                contentDescription = null,
                                modifier = Modifier.size(TamaChatInlineIconSize),
                                contentScale = ContentScale.Fit
                            )
                            IconButton(
                                onClick = {
                                    val currentPlayer = mediaPlayer
                                    if (currentPlayer?.isPlaying == true) {
                                        currentPlayer.pause()
                                        isAudioPlaying = false
                                    } else {
                                        currentPlayer?.release()
                                        val player = MediaPlayer().apply {
                                            setDataSource(audioFile.absolutePath)
                                            prepare()
                                            setOnCompletionListener {
                                                isAudioPlaying = false
                                                runCatching { it.release() }
                                                mediaPlayer = null
                                            }
                                            start()
                                        }
                                        mediaPlayer = player
                                        isAudioPlaying = true
                                    }
                                },
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(
                                    imageVector = if (isAudioPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                    contentDescription = stringResource(R.string.tama_chat_voice_play),
                                    tint = if (isUser || warningBubble) TamaLight else TamaDark
                                )
                            }
                            Text(
                                listOfNotNull(
                                    stringResource(R.string.tama_chat_voice_saved),
                                    audioDurationLabel
                                ).joinToString(" • "),
                                color = if (isUser || warningBubble) TamaLight else TamaDark,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp
                            )
                        }
                    }
                    val transcriptStatus = message.transcriptionStatus
                    val showTranscriptSection = isUser && message.audioPath != null && (
                        !message.transcribedText.isNullOrBlank() ||
                            transcriptStatus == TamaTranscriptionStatus.PENDING ||
                            transcriptStatus == TamaTranscriptionStatus.FAILED
                    )
                    if (showTranscriptSection) {
                        Surface(
                            color = Color(0xFF7A7A7A).copy(alpha = 0.35f),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(
                                    enabled = !message.transcribedText.isNullOrBlank()
                                ) {
                                    transcriptExpanded = !transcriptExpanded
                                }
                        ) {
                            Column(modifier = Modifier.padding(10.dp)) {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    AsyncImage(
                                        model = "file:///android_asset/$TAMA_CHAT_TRANSCRIPT_ASSET",
                                        contentDescription = null,
                                        modifier = Modifier.size(TamaChatTranscriptIconSize),
                                        contentScale = ContentScale.Fit
                                    )
                                    Text(
                                        text = when (transcriptStatus) {
                                            TamaTranscriptionStatus.PENDING -> stringResource(R.string.tama_chat_transcript_pending)
                                            TamaTranscriptionStatus.FAILED -> stringResource(R.string.tama_chat_transcript_failed)
                                            else -> stringResource(R.string.tama_chat_transcript_title)
                                        },
                                        color = if (isUser) TamaLight else TamaDark,
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                val transcriptBody = when {
                                    transcriptStatus == TamaTranscriptionStatus.PENDING ->
                                        stringResource(R.string.tama_chat_transcript_pending_body)
                                    transcriptStatus == TamaTranscriptionStatus.FAILED ->
                                        (message.transcriptionError ?: stringResource(R.string.error_generic))
                                    transcriptExpanded ->
                                        message.transcribedText.orEmpty()
                                    !message.transcribedText.isNullOrBlank() ->
                                        stringResource(R.string.tama_chat_transcript_tap_to_expand)
                                    else -> ""
                                }
                                if (transcriptBody.isNotBlank()) {
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = transcriptBody,
                                        color = if (isUser) TamaLight else TamaDark,
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 11.sp
                                    )
                                }
                            }
                        }
                    }
                    if (displayContent.isNotBlank()) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            if (warningBubble) {
                                AsyncImage(
                                    model = "file:///android_asset/$TAMA_CHAT_WARNING_ASSET",
                                    contentDescription = null,
                                    modifier = Modifier.size(TamaChatInlineIconSize),
                                    contentScale = ContentScale.Fit
                                )
                            }
                            Text(
                                text = displayContent,
                                color = if (isUser || warningBubble) TamaLight else TamaDark,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 14.sp,
                                modifier = Modifier.weight(1f, fill = false)
                            )
                        }
                    }
                }
            }

            DropdownMenu(
                expanded = showMenu,
                onDismissRequest = { showMenu = false },
                modifier = Modifier.background(TamaBackground)
            ) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.tama_chat_remove_message), color = Color.Red, fontFamily = FontFamily.Monospace) },
                    onClick = {
                        onDelete()
                        showMenu = false
                    },
                    leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null, tint = Color.Red) }
                )
            }
        }
    }

    if (showImagePreview && imageFile != null) {
        TamaPopupDialog(
            title = stringResource(R.string.llama_image_attached),
            backgroundAsset = "tama/backgrounds/library_room.png",
            onDismissRequest = { showImagePreview = false },
            bodyContent = {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = TamaBackground)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        AsyncImage(
                            model = imageFile,
                            contentDescription = stringResource(R.string.llama_image_attached),
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 220.dp, max = 520.dp)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        TextButton(onClick = { showImagePreview = false }, modifier = Modifier.align(Alignment.End)) {
                            Text(stringResource(R.string.action_close))
                        }
                    }
                }
            },
            footerContent = {}
        )
    }
}

@Composable
private fun ThinkingIndicator() {
    Row(
        modifier = Modifier.padding(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(16.dp),
            strokeWidth = 2.dp,
            color = TamaDark
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            stringResource(R.string.tama_chat_thinking),
            color = TamaDark,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp
        )
    }
}

@Composable
private fun RecordingStrip(
    seconds: Int,
    onStop: () -> Unit
) {
    Surface(
        color = Color(0xFFFFE3E3),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.FiberManualRecord,
                contentDescription = stringResource(R.string.llama_recording),
                tint = Color(0xFFB00020)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = String.format(Locale.getDefault(), "%02d:%02d:%02d", seconds / 3600, (seconds % 3600) / 60, seconds % 60),
                color = Color(0xFFB00020),
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onStop) {
                Text(stringResource(R.string.action_stop))
            }
        }
    }
}

@Composable
private fun TamaChatImageAttachmentChip(
    imagePath: String,
    onPreview: () -> Unit,
    onRemove: () -> Unit
) {
    Surface(
        color = TamaLight,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, TamaAccent),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AsyncImage(
                model = File(imagePath),
                contentDescription = stringResource(R.string.llama_image_attached),
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(52.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .clickable(onClick = onPreview)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.llama_image_attached),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = TamaDark
                )
                Text(
                    text = File(imagePath).name,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    color = TamaMutedText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            IconButton(onClick = onRemove) {
                Icon(Icons.Default.Close, contentDescription = stringResource(R.string.llama_remove_attachment))
            }
        }
    }
}

@Composable
private fun TamaChatAudioAttachmentChip(
    audioPath: String,
    audioDurationMs: Long?,
    onPreview: () -> Unit,
    onRemove: () -> Unit
) {
    Surface(
        color = TamaLight,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, TamaAccent),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AudioPlaybackChip(audioPath = audioPath, onPreview = onPreview)
            Spacer(modifier = Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    listOfNotNull(
                        stringResource(R.string.tama_chat_voice_saved),
                        formatAudioDuration(audioDurationMs ?: measureAudioDurationMs(audioPath))
                    ).joinToString(" • "),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = TamaDark
                )
                Text(
                    text = File(audioPath).name,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    color = TamaMutedText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            IconButton(onClick = onRemove) {
                Icon(Icons.Default.Close, contentDescription = stringResource(R.string.llama_remove_attachment))
            }
        }
    }
}

@Composable
private fun AudioPlaybackChip(
    audioPath: String,
    onPreview: () -> Unit
) {
    val audioFile = remember(audioPath) { File(audioPath).takeIf { it.exists() } }
    var mediaPlayer by remember(audioPath) { mutableStateOf<MediaPlayer?>(null) }
    var isPlaying by remember(audioPath) { mutableStateOf(false) }

    DisposableEffect(audioPath) {
        onDispose {
            runCatching { mediaPlayer?.release() }
            mediaPlayer = null
            isPlaying = false
        }
    }

    IconButton(
        onClick = {
            onPreview()
            val file = audioFile ?: return@IconButton
            val current = mediaPlayer
            if (current?.isPlaying == true) {
                current.pause()
                isPlaying = false
            } else {
                current?.release()
                val player = MediaPlayer().apply {
                    setDataSource(file.absolutePath)
                    prepare()
                    setOnCompletionListener {
                        isPlaying = false
                        runCatching { it.release() }
                        mediaPlayer = null
                    }
                    start()
                }
                mediaPlayer = player
                isPlaying = true
            }
        },
        enabled = audioFile != null
    ) {
        Icon(
            imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
            contentDescription = stringResource(R.string.tama_chat_voice_play),
            tint = TamaDark
        )
    }
}

private fun measureAudioDurationMs(audioPath: String): Long? {
    val file = File(audioPath)
    if (!file.exists()) return null
    val retriever = MediaMetadataRetriever()
    return runCatching {
        retriever.setDataSource(audioPath)
        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
    }.getOrNull().also {
        runCatching { retriever.release() }
    }
}

private fun formatAudioDuration(durationMs: Long?): String? {
    val totalSeconds = ((durationMs ?: return null) / 1000L).coerceAtLeast(0L)
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    return String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TamaChatSettingsDialog(
    navController: NavController,
    settingsRepo: SettingsRepository,
    agentService: TamaAgentService,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val scope = rememberCoroutineScope()
    val backend by settingsRepo.tamaBackend.collectAsState()
    val thinkingEnabled by settingsRepo.tamaThinkingEnabled.collectAsState()
    val petModel by settingsRepo.tamaPetModel.collectAsState()
    val summarizerModel by settingsRepo.tamaSummarizerModel.collectAsState()
    val tamaWhisperModelPath by settingsRepo.tamaWhisperModelPath.collectAsState()
    val tamaWhisperLanguage by settingsRepo.tamaWhisperLanguage.collectAsState()
    val imageInputEnabled by settingsRepo.tamaChatImageInputEnabled.collectAsState()
    val petPrompt by settingsRepo.tamaPetPrompt.collectAsState()
    val summarizerPrompt by settingsRepo.tamaSummarizerPrompt.collectAsState()
    val ollamaUrl by settingsRepo.tamaOllamaUrl.collectAsState()
    val ollamaMmap by settingsRepo.tamaOllamaMmap.collectAsState()
    val ollamaThreads by settingsRepo.tamaOllamaThreads.collectAsState()
    val ollamaNumCtx by settingsRepo.tamaOllamaNumCtx.collectAsState()
    val llamaServerUrl by settingsRepo.tamaLlamaServerUrl.collectAsState()
    val llamaSwapUrl by settingsRepo.tamaLlamaSwapUrl.collectAsState()
    val llamaServerModelLabel by settingsRepo.tamaLlamaServerModelLabel.collectAsState()
    val llamaServerContextTokens by settingsRepo.tamaLlamaServerContextTokens.collectAsState()
    val llamaServerContextLabel by settingsRepo.tamaLlamaServerContextLabel.collectAsState()
    val tamaLiteRtModelId by settingsRepo.tamaLiteRtModelId.collectAsState()
    val tamaLiteRtBackend by settingsRepo.tamaLiteRtBackend.collectAsState()
    val tamaLiteRtMtpEnabled by settingsRepo.tamaLiteRtMtpEnabled.collectAsState()
    val availableModels by OllamaService.availableModels.collectAsState()
    val modelNames = remember(availableModels) { availableModels.map { it.name } }
    val whisperModelsFlow = remember(appContext) {
        runCatching {
            AppDatabase.getDatabase(appContext).modelDao().getModelsByType(ModelType.WHISPER)
        }.getOrElse {
            flowOf(emptyList())
        }
    }
    val whisperModels by whisperModelsFlow.collectAsState(initial = emptyList())
    val liteRtModels by remember(appContext) {
        AppDatabase.getDatabase(appContext).liteRtModelDao().observeAll()
    }.collectAsState(initial = emptyList())
    val thinkingStatusLabel = stringResource(
        if (thinkingEnabled) R.string.action_enabled else R.string.action_disabled
    )

    var tempBackend by remember(backend) { mutableStateOf(backend) }
    var tempPetPrompt by remember { mutableStateOf(petPrompt) }
    var tempSummarizerPrompt by remember { mutableStateOf(summarizerPrompt) }
    var tempOllamaUrl by remember { mutableStateOf(ollamaUrl) }
    var tempLlamaServerUrl by remember { mutableStateOf(llamaServerUrl) }
    var tempLlamaSwapUrl by remember { mutableStateOf(llamaSwapUrl) }
    var tempNumCtx by remember { mutableStateOf(ollamaNumCtx.toString()) }
    var availableLlamaSwapModels by remember(petModel, summarizerModel) {
        mutableStateOf(listOf(petModel, summarizerModel).filter { it.isNotBlank() }.distinct())
    }
    var metadataMessage by remember { mutableStateOf<String?>(null) }
    var showVoiceLanguageMenu by remember { mutableStateOf(false) }
    var showLiteRtModelMenu by remember { mutableStateOf(false) }

        TamaPopupDialog(
        title = stringResource(R.string.tama_chat_settings_title),
        backgroundAsset = "tama/backgrounds/library_room.png",
        onDismissRequest = onDismiss,
        bodyContent = {
            Card(
                modifier = Modifier
                    .fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = TamaBackground)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    Spacer(modifier = Modifier.height(4.dp))

                Text(stringResource(R.string.tama_chat_reasoning_title), fontWeight = FontWeight.Bold, fontSize = 14.sp, color = TamaDark)
                Spacer(modifier = Modifier.height(8.dp))
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = TamaLight.copy(alpha = 0.9f),
                    shape = RoundedCornerShape(12.dp),
                    tonalElevation = 1.dp
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                stringResource(R.string.tama_chat_thinking_setting_title),
                                fontSize = 12.sp,
                                color = TamaDark,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                stringResource(R.string.tama_chat_thinking_setting_desc),
                                fontSize = 11.sp,
                                color = TamaMutedText,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                        Text(
                            thinkingStatusLabel,
                            fontSize = 12.sp,
                            color = TamaDark,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(end = 8.dp)
                        )
                        Switch(
                            checked = thinkingEnabled,
                            onCheckedChange = { settingsRepo.setTamaThinkingEnabled(it) },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = TamaDark,
                                checkedTrackColor = TamaAccent
                            )
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Text(stringResource(R.string.tama_chat_media_title), fontWeight = FontWeight.Bold, fontSize = 14.sp, color = TamaDark)
                Spacer(modifier = Modifier.height(8.dp))
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = TamaLight.copy(alpha = 0.9f),
                    shape = RoundedCornerShape(12.dp),
                    tonalElevation = 1.dp
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                stringResource(R.string.tama_chat_image_support_title),
                                fontSize = 12.sp,
                                color = TamaDark,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                stringResource(R.string.tama_chat_image_support_desc),
                                fontSize = 11.sp,
                                color = TamaMutedText,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                        Switch(
                            checked = imageInputEnabled,
                            onCheckedChange = settingsRepo::setTamaChatImageInputEnabled,
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = TamaDark,
                                checkedTrackColor = TamaAccent
                            )
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Text(stringResource(R.string.tama_chat_engine_title), fontWeight = FontWeight.Bold, fontSize = 14.sp, color = TamaDark)
                Spacer(modifier = Modifier.height(8.dp))

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        TamaBackendChoiceButton(
                            label = stringResource(R.string.tama_backend_ollama),
                            selected = tempBackend == SettingsRepository.PDF_BACKEND_OLLAMA,
                            onClick = { tempBackend = SettingsRepository.PDF_BACKEND_OLLAMA },
                            modifier = Modifier.weight(1f)
                        )
                        TamaBackendChoiceButton(
                            label = stringResource(R.string.tama_backend_llama_server),
                            selected = tempBackend == SettingsRepository.PDF_BACKEND_LLAMA_SERVER,
                            onClick = {
                                tempBackend = SettingsRepository.PDF_BACKEND_LLAMA_SERVER
                                if (tempLlamaServerUrl.isBlank() || tempLlamaServerUrl == SettingsRepository.PDF_LLAMA_SWAP_DEFAULT_URL) {
                                    tempLlamaServerUrl = SettingsRepository.PDF_LLAMA_SERVER_DEFAULT_URL
                                }
                            },
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        TamaBackendChoiceButton(
                            label = stringResource(R.string.tama_backend_llama_swap),
                            selected = tempBackend == SettingsRepository.PDF_BACKEND_LLAMA_SWAP,
                            onClick = {
                                tempBackend = SettingsRepository.PDF_BACKEND_LLAMA_SWAP
                                if (tempLlamaSwapUrl.isBlank() || tempLlamaSwapUrl == SettingsRepository.PDF_LLAMA_SERVER_DEFAULT_URL) {
                                    tempLlamaSwapUrl = SettingsRepository.PDF_LLAMA_SWAP_DEFAULT_URL
                                }
                            },
                            modifier = Modifier.weight(1f)
                        )
                        TamaBackendChoiceButton(
                            label = stringResource(R.string.tama_backend_litert),
                            selected = SettingsRepository.isLiteRtBackend(tempBackend),
                            onClick = { tempBackend = SettingsRepository.PDF_BACKEND_LITERT },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                if (!SettingsRepository.isLiteRtBackend(tempBackend)) {
                    Text(stringResource(R.string.tama_chat_connection_title), fontWeight = FontWeight.Bold, fontSize = 14.sp, color = TamaDark)
                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedTextField(
                        value = when (tempBackend) {
                            SettingsRepository.PDF_BACKEND_LLAMA_SERVER -> tempLlamaServerUrl
                            SettingsRepository.PDF_BACKEND_LLAMA_SWAP -> tempLlamaSwapUrl
                            else -> tempOllamaUrl
                        },
                        onValueChange = {
                            when (tempBackend) {
                                SettingsRepository.PDF_BACKEND_LLAMA_SERVER -> tempLlamaServerUrl = it
                                SettingsRepository.PDF_BACKEND_LLAMA_SWAP -> tempLlamaSwapUrl = it
                                else -> tempOllamaUrl = it
                            }
                        },
                        label = {
                            Text(
                                when (tempBackend) {
                                    SettingsRepository.PDF_BACKEND_LLAMA_SERVER -> stringResource(R.string.pdf_llama_server_url_label)
                                    SettingsRepository.PDF_BACKEND_LLAMA_SWAP -> stringResource(R.string.pdf_llama_swap_url_label)
                                    else -> stringResource(R.string.pdf_ollama_url_label)
                                },
                                fontSize = 10.sp
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = TextStyle(fontSize = 12.sp, fontFamily = FontFamily.Monospace),
                        colors = tamaOutlinedFieldColors()
                    )

                    Spacer(modifier = Modifier.height(12.dp))
                }

                if (SettingsRepository.isLiteRtBackend(tempBackend)) {
                    Text(stringResource(R.string.tama_chat_models_title), fontWeight = FontWeight.Bold, fontSize = 14.sp, color = TamaDark)
                    Spacer(modifier = Modifier.height(8.dp))
                    val selectedLiteRtModel = liteRtModels.firstOrNull { it.id == tamaLiteRtModelId }
                        ?: liteRtModels.firstOrNull()
                    ExposedDropdownMenuBox(
                        expanded = showLiteRtModelMenu,
                        onExpandedChange = { showLiteRtModelMenu = it }
                    ) {
                        OutlinedTextField(
                            value = selectedLiteRtModel?.displayName.orEmpty(),
                            onValueChange = {},
                            readOnly = true,
                            enabled = liteRtModels.isNotEmpty(),
                            label = { Text(stringResource(R.string.litert_model_label), fontSize = 10.sp) },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = showLiteRtModelMenu) },
                            modifier = Modifier
                                .menuAnchor()
                                .fillMaxWidth(),
                            textStyle = TextStyle(fontSize = 12.sp, fontFamily = FontFamily.Monospace),
                            colors = tamaOutlinedFieldColors()
                        )
                        ExposedDropdownMenu(
                            expanded = showLiteRtModelMenu,
                            onDismissRequest = { showLiteRtModelMenu = false }
                        ) {
                            liteRtModels.forEach { model ->
                                DropdownMenuItem(
                                    text = { Text(model.displayName) },
                                    onClick = {
                                        settingsRepo.setTamaLiteRtModelId(model.id)
                                        showLiteRtModelMenu = false
                                    }
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))
                    Text(stringResource(R.string.litert_gallery_accelerator), fontSize = 12.sp, color = TamaDark, fontWeight = FontWeight.Bold)
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            TamaBackendChoiceButton(
                                label = stringResource(R.string.general_acceleration_mode_auto),
                                selected = normalizeLiteRtBackend(tamaLiteRtBackend) == LITERT_BACKEND_AUTO,
                                onClick = { settingsRepo.setTamaLiteRtBackend(LITERT_BACKEND_AUTO) },
                                modifier = Modifier.weight(1f)
                            )
                            TamaBackendChoiceButton(
                                label = stringResource(R.string.general_acceleration_mode_cpu),
                                selected = normalizeLiteRtBackend(tamaLiteRtBackend) == LITERT_BACKEND_CPU,
                                onClick = { settingsRepo.setTamaLiteRtBackend(LITERT_BACKEND_CPU) },
                                modifier = Modifier.weight(1f)
                            )
                        }
                        TamaBackendChoiceButton(
                            label = stringResource(R.string.litert_backend_gpu),
                            selected = normalizeLiteRtBackend(tamaLiteRtBackend) == LITERT_BACKEND_GPU,
                            onClick = { settingsRepo.setTamaLiteRtBackend(LITERT_BACKEND_GPU) },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.litert_gallery_mtp_title), fontSize = 12.sp, color = TamaDark, fontWeight = FontWeight.Bold)
                            Text(
                                stringResource(R.string.litert_gallery_mtp_desc),
                                fontSize = 11.sp,
                                color = TamaMutedText,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                        Switch(
                            checked = tamaLiteRtMtpEnabled,
                            onCheckedChange = settingsRepo::setTamaLiteRtMtpEnabled,
                            colors = SwitchDefaults.colors(checkedThumbColor = TamaDark, checkedTrackColor = TamaAccent)
                        )
                    }
                } else if (SettingsRepository.usesOpenAiChatBackend(tempBackend)) {
                    OutlinedButton(
                        onClick = {
                            settingsRepo.setTamaBackend(tempBackend)
                            if (SettingsRepository.isLlamaSwapBackend(tempBackend)) {
                                settingsRepo.setTamaLlamaSwapUrl(tempLlamaSwapUrl)
                            } else {
                                settingsRepo.setTamaLlamaServerUrl(tempLlamaServerUrl)
                            }
                            scope.launch {
                                agentService.refreshRemoteBackendMetadata()
                                    .fold(
                                        onSuccess = { metadata ->
                                            if (SettingsRepository.isLlamaSwapBackend(metadata.backend)) {
                                                availableLlamaSwapModels = metadata.availableModels
                                                context.getString(R.string.pdf_metadata_llama_swap_loaded, metadata.availableModels.size)
                                            } else {
                                                context.getString(R.string.tama_chat_backend_info_loaded)
                                            }
                                        },
                                        onFailure = {
                                            context.getString(
                                                R.string.tama_chat_backend_info_failed,
                                                it.message ?: context.getString(R.string.error_generic)
                                            )
                                        }
                                    )
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.pdf_refresh_backend_info), color = TamaDark)
                    }

                    metadataMessage?.let {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(it, fontSize = 11.sp, color = TamaMutedText, fontFamily = FontFamily.Monospace)
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    Text(stringResource(R.string.tama_chat_models_title), fontWeight = FontWeight.Bold, fontSize = 14.sp, color = TamaDark)
                    Spacer(modifier = Modifier.height(8.dp))
                    if (SettingsRepository.isLlamaSwapBackend(tempBackend)) {
                        TamaModelSelector(
                            label = stringResource(R.string.tama_chat_pet_model_label),
                            selectedModel = petModel,
                            availableModels = availableLlamaSwapModels,
                            onModelChange = { settingsRepo.setTamaPetModel(it) }
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        TamaModelSelector(
                            label = stringResource(R.string.tama_chat_summarizer_model_label),
                            selectedModel = summarizerModel,
                            availableModels = availableLlamaSwapModels,
                            onModelChange = { settingsRepo.setTamaSummarizerModel(it) }
                        )
                    } else {
                        Text(
                            stringResource(R.string.pdf_llama_server_model_label),
                            fontSize = 12.sp,
                            color = TamaDark,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            llamaServerModelLabel ?: stringResource(R.string.pdf_server_value_unavailable),
                            fontSize = 12.sp,
                            color = TamaDark
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            stringResource(R.string.pdf_llama_server_context_label),
                            fontSize = 12.sp,
                            color = TamaDark,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            llamaServerContextLabel
                                ?: llamaServerContextTokens.takeIf { it > 0 }?.toString()
                                ?: stringResource(R.string.pdf_server_value_unavailable),
                            fontSize = 12.sp,
                            color = TamaDark
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            stringResource(R.string.tama_chat_llama_shared_model_hint),
                            fontSize = 11.sp,
                            color = TamaMutedText,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                } else {
                    Text(stringResource(R.string.tama_chat_models_title), fontWeight = FontWeight.Bold, fontSize = 14.sp, color = TamaDark)
                    Spacer(modifier = Modifier.height(8.dp))

                    TamaModelSelector(
                        label = stringResource(R.string.tama_chat_pet_model_label),
                        selectedModel = petModel,
                        availableModels = modelNames,
                        onModelChange = { settingsRepo.setTamaPetModel(it) }
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    TamaModelSelector(
                        label = stringResource(R.string.tama_chat_summarizer_model_label),
                        selectedModel = summarizerModel,
                        availableModels = modelNames,
                        onModelChange = { settingsRepo.setTamaSummarizerModel(it) }
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.tama_chat_use_mmap), modifier = Modifier.weight(1f), fontSize = 12.sp, color = TamaDark)
                        Switch(
                            checked = ollamaMmap,
                            onCheckedChange = {
                                settingsRepo.setTamaOllamaMmap(it)
                                agentService.ollamaService.setUseMmap(it)
                            },
                            colors = SwitchDefaults.colors(checkedThumbColor = TamaDark, checkedTrackColor = TamaAccent)
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(stringResource(R.string.tama_chat_threads_label, ollamaThreads), fontSize = 12.sp, color = TamaDark)
                    Slider(
                        value = ollamaThreads.toFloat(),
                        onValueChange = {
                            val newVal = it.toInt()
                            settingsRepo.setTamaOllamaThreads(newVal)
                            agentService.ollamaService.setNumThreads(newVal)
                        },
                        valueRange = 1f..16f,
                        steps = 14,
                        colors = SliderDefaults.colors(thumbColor = TamaDark, activeTrackColor = TamaDark)
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedTextField(
                        value = tempNumCtx,
                        onValueChange = { tempNumCtx = it },
                        label = { Text(stringResource(R.string.tama_chat_context_size_label), fontSize = 10.sp) },
                        modifier = Modifier.width(140.dp),
                        textStyle = TextStyle(fontSize = 12.sp, fontFamily = FontFamily.Monospace),
                        colors = tamaOutlinedFieldColors()
                    )
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp), color = TamaAccent.copy(alpha = 0.3f))

                Text(stringResource(R.string.tama_chat_voice_section_title), fontWeight = FontWeight.Bold, fontSize = 14.sp, color = TamaDark)
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    stringResource(R.string.tama_chat_voice_section_desc),
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = TamaMutedText
                )
                Spacer(modifier = Modifier.height(8.dp))

                TamaModelSelector(
                    label = stringResource(R.string.tama_chat_voice_model_label),
                    selectedModel = tamaWhisperModelPath.orEmpty(),
                    availableModels = whisperModels.map { it.path },
                    onModelChange = settingsRepo::setTamaWhisperModelPath,
                    displayValue = { File(it).name }
                )

                Spacer(modifier = Modifier.height(8.dp))

                ExposedDropdownMenuBox(
                    expanded = showVoiceLanguageMenu,
                    onExpandedChange = { showVoiceLanguageMenu = !showVoiceLanguageMenu }
                ) {
                    OutlinedTextField(
                        value = WhisperLanguages.languages.firstOrNull { it.first == tamaWhisperLanguage }?.second
                            ?: stringResource(R.string.whisper_auto_detect),
                        onValueChange = {},
                        readOnly = true,
                        label = { Text(stringResource(R.string.tama_chat_voice_language_label)) },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = showVoiceLanguageMenu) },
                        modifier = Modifier
                            .menuAnchor()
                            .fillMaxWidth(),
                        colors = tamaOutlinedFieldColors()
                    )
                    ExposedDropdownMenu(
                        expanded = showVoiceLanguageMenu,
                        onDismissRequest = { showVoiceLanguageMenu = false }
                    ) {
                        WhisperLanguages.languages.forEach { (code, label) ->
                            DropdownMenuItem(
                                text = { Text(label) },
                                onClick = {
                                    settingsRepo.setTamaWhisperLanguage(code)
                                    showVoiceLanguageMenu = false
                                }
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
                TextButton(onClick = { navController.navigate(Screen.WhisperModels.route) }) {
                    Text(stringResource(R.string.tama_chat_voice_open_models))
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp), color = TamaAccent.copy(alpha = 0.3f))

                Text(stringResource(R.string.tama_chat_identity_memory_title), fontWeight = FontWeight.Bold, fontSize = 14.sp, color = TamaDark)
                Spacer(modifier = Modifier.height(12.dp))

                Text(stringResource(R.string.tama_chat_pet_prompt_label), fontSize = 12.sp, color = TamaDark)
                OutlinedTextField(
                    value = tempPetPrompt,
                    onValueChange = { tempPetPrompt = it },
                    modifier = Modifier.fillMaxWidth().height(120.dp),
                    textStyle = TextStyle(fontSize = 11.sp, fontFamily = FontFamily.Monospace),
                    colors = tamaOutlinedFieldColors()
                )

                Spacer(modifier = Modifier.height(12.dp))

                Text(stringResource(R.string.tama_chat_summarizer_prompt_label), fontSize = 12.sp, color = TamaDark)
                OutlinedTextField(
                    value = tempSummarizerPrompt,
                    onValueChange = { tempSummarizerPrompt = it },
                    modifier = Modifier.fillMaxWidth().height(120.dp),
                    textStyle = TextStyle(fontSize = 11.sp, fontFamily = FontFamily.Monospace),
                    colors = tamaOutlinedFieldColors()
                )

                Spacer(modifier = Modifier.height(24.dp))

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = onDismiss, modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.action_cancel), color = TamaDark)
                        }
                        Button(
                            onClick = {
                                settingsRepo.setTamaBackend(tempBackend)
                                settingsRepo.setTamaPetPrompt(tempPetPrompt)
                                settingsRepo.setTamaSummarizerPrompt(tempSummarizerPrompt)
                                settingsRepo.setTamaOllamaUrl(tempOllamaUrl)
                                settingsRepo.setTamaLlamaServerUrl(tempLlamaServerUrl)
                                settingsRepo.setTamaLlamaSwapUrl(tempLlamaSwapUrl)
                                agentService.ollamaService.setBaseUrl(tempOllamaUrl)
                                tempNumCtx.toIntOrNull()?.let {
                                    settingsRepo.setTamaOllamaNumCtx(it)
                                    agentService.ollamaService.setNumCtx(it)
                                }
                                agentService.retryConnection()
                                onDismiss()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = TamaDark),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(stringResource(R.string.action_save), color = TamaLight)
                        }
                    }
                }
            }
        },
        footerContent = {}
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TamaModelSelector(
    label: String,
    selectedModel: String,
    availableModels: List<String>,
    onModelChange: (String) -> Unit,
    displayValue: (String) -> String = { it }
) {
    var expanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it }
    ) {
        OutlinedTextField(
            value = displayValue(selectedModel),
            onValueChange = { },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(),
            label = { Text(label, fontSize = 10.sp) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            singleLine = true,
            textStyle = TextStyle(fontSize = 12.sp, fontFamily = FontFamily.Monospace),
            readOnly = true,
            colors = tamaOutlinedFieldColors()
        )

        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.background(TamaBackground)
        ) {
            availableModels.forEach { model ->
                DropdownMenuItem(
                    text = {
                        Text(
                            displayValue(model),
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            color = TamaDark
                        )
                    },
                    onClick = {
                        onModelChange(model)
                        expanded = false
                    }
                )
            }
        }
    }
}

private fun persistTamaChatImage(context: Context, petId: String?, uri: Uri): String? {
    val safePetId = petId ?: return null
    return try {
        val timestamp = SimpleDateFormat("yyyy-MM-dd_HHmmss", Locale.getDefault()).format(Date())
        val imagesDir = File(context.filesDir, "tama_chat_images/$safePetId").apply { mkdirs() }
        val savedFile = File(imagesDir, "image_$timestamp.${guessImageExtension(context, uri)}")
        context.contentResolver.openInputStream(uri)?.use { input ->
            savedFile.outputStream().use { output ->
                input.copyTo(output)
            }
        } ?: return null
        savedFile.absolutePath
    } catch (_: Exception) {
        null
    }
}

private fun guessImageExtension(context: Context, uri: Uri): String {
    val mimeType = context.contentResolver.getType(uri)?.lowercase(Locale.getDefault()).orEmpty()
    when {
        mimeType.endsWith("/png") -> return "png"
        mimeType.endsWith("/webp") -> return "webp"
        mimeType.endsWith("/gif") -> return "gif"
        mimeType.endsWith("/bmp") -> return "bmp"
        mimeType.endsWith("/jpeg") || mimeType.endsWith("/jpg") -> return "jpg"
    }
    val path = uri.lastPathSegment?.lowercase(Locale.getDefault()).orEmpty()
    return when {
        path.endsWith(".png") -> "png"
        path.endsWith(".webp") -> "webp"
        path.endsWith(".gif") -> "gif"
        path.endsWith(".bmp") -> "bmp"
        path.endsWith(".jpg") || path.endsWith(".jpeg") -> "jpg"
        else -> "jpg"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun tamaOutlinedFieldColors(containerColor: Color = TamaBackground): TextFieldColors {
    return OutlinedTextFieldDefaults.colors(
        focusedBorderColor = TamaDark,
        unfocusedBorderColor = TamaAccent,
        focusedContainerColor = containerColor,
        unfocusedContainerColor = containerColor,
        cursorColor = TamaDark,
        focusedTextColor = TamaDark,
        unfocusedTextColor = TamaDark,
        focusedLabelColor = TamaDark,
        unfocusedLabelColor = TamaMutedText,
        focusedPlaceholderColor = TamaMutedText,
        unfocusedPlaceholderColor = TamaMutedText,
        focusedTrailingIconColor = TamaDark,
        unfocusedTrailingIconColor = TamaAccent
    )
}

@Composable
private fun TamaBackendChoiceButton(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (selected) {
        Button(
            onClick = onClick,
            modifier = modifier,
            colors = ButtonDefaults.buttonColors(containerColor = TamaDark, contentColor = TamaLight),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text(label, fontWeight = FontWeight.Bold, color = TamaLight)
        }
    } else {
        OutlinedButton(
            onClick = onClick,
            modifier = modifier,
            colors = ButtonDefaults.outlinedButtonColors(contentColor = TamaDark),
            border = androidx.compose.foundation.BorderStroke(1.dp, TamaAccent),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text(label, fontWeight = FontWeight.Bold, color = TamaDark)
        }
    }
}
