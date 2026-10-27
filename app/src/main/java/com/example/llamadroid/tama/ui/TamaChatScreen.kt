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
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.llamadroid.ui.walkthrough.WalkthroughDialog as Dialog
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
import com.example.llamadroid.service.LlamaPromptProcessingProgress
import com.example.llamadroid.service.OllamaService
import com.example.llamadroid.ui.components.ContextUsageRing
import com.example.llamadroid.service.WhisperLanguages
import com.example.llamadroid.tama.game.TamaAgentService
import com.example.llamadroid.tama.game.TamaGameEngine
import com.example.llamadroid.tama.game.TamaTranscriptionStatus
import com.example.llamadroid.tama.db.TamaSummaryEntity
import com.example.llamadroid.ui.navigation.Screen
import com.example.llamadroid.ui.walkthrough.LocalWalkthroughTargets
import com.example.llamadroid.ui.walkthrough.walkthroughTarget
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
    navController: NavController?,
    gameEngine: TamaGameEngine,
    agentService: TamaAgentService,
    settingsRepo: SettingsRepository,
    /** Set by the floating desktop-pet chat: the back arrow closes the host instead of popping a route. */
    onClose: (() -> Unit)? = null
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val resources = LocalResources.current
    val walkthroughTargets = LocalWalkthroughTargets.current
    val pet by gameEngine.pet.collectAsState()
    val messages by agentService.messages.collectAsState()
    val isLoading by agentService.isLoading.collectAsState()
    val isBackendConnected by agentService.isBackendConnected.collectAsState()
    val promptProgress by agentService.promptProgress.collectAsState()
    val contextUsage by agentService.contextUsage.collectAsState()
    var showContextSheet by remember { mutableStateOf(false) }
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
            // The scrollable gameplay guidance occupies the first list item.
            listState.animateScrollToItem(messages.size)
        }
    }

    // Load history once when pet ID is available
    LaunchedEffect(pet?.id, backend) {
        pet?.id?.let {
            agentService.loadHistory(it)
            agentService.retryConnection()
        }
    }
    LaunchedEffect(pet?.id, backend) {
        pet?.let { agentService.refreshContextUsage(it) }
    }

    val cacheDecision = rememberTamaPetCacheDecision(settingsRepo, pet?.id)

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
            voiceError = resources.getString(R.string.whisper_error_start_recording, e.message ?: resources.getString(R.string.error_generic))
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
            voiceError = resources.getString(R.string.whisper_error_permission)
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
            voiceError = resources.getString(R.string.tama_chat_image_input_disabled_error)
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
                IconButton(onClick = { onClose?.invoke() ?: navController?.popBackStack() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                }
            },
            actions = {
                        com.example.llamadroid.ui.walkthrough.FeatureGuideAction()
                contextUsage?.let { usage ->
                    ContextUsageRing(
                        percentUsed = usage.percent,
                        size = 30.dp,
                        isWorking = isLoading,
                        contentDescription = stringResource(R.string.tama_chat_context_ring_desc, usage.percent),
                        onClick = { showContextSheet = true }
                    )
                }
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
        TamaPetCacheWarningLine(
            decision = cacheDecision,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 3.dp)
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
            item(key = "gameplay_controls_guidance") {
                Text(
                    text = stringResource(R.string.tama_chat_gameplay_limits),
                    style = MaterialTheme.typography.bodySmall,
                    color = TamaMutedText,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp)
                )
            }
            items(messages, key = { it.id ?: UUID.randomUUID().toString() }) { message ->
                TamaChatBubble(message) {
                    agentService.deleteMessage(message.id!!)
                }
            }

            if (isLoading) {
                item {
                    ThinkingIndicator(promptProgress)
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

                OutlinedTextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp)
                        .walkthroughTarget("tama.chat"),
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
                            .size(48.dp)
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
                            .size(48.dp)
                            .padding(bottom = 4.dp)
                    ) {
                        Icon(
                            imageVector = if (voiceRecording) Icons.Default.Stop else Icons.Default.Mic,
                            contentDescription = stringResource(R.string.tama_chat_voice_button),
                            tint = if (isLoading) TamaMutedText else TamaDark
                        )
                    }

                    Spacer(Modifier.weight(1f))

                    IconButton(
                        onClick = {
                            scope.launch {
                                sendCurrentMessage()
                                walkthroughTargets?.recordEvent("tama.chat")
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

    val sheetPet = pet
    val sheetUsage = contextUsage
    if (showContextSheet && sheetPet != null && sheetUsage != null) {
        TamaChatContextSheet(
            pet = sheetPet,
            usage = sheetUsage,
            generating = isLoading,
            settingsRepo = settingsRepo,
            agentService = agentService,
            onDismiss = { showContextSheet = false }
        )
    }
    if (showSettings) {
        TamaChatSettingsDialog(
            navController = navController,
            settingsRepo = settingsRepo,
            agentService = agentService,
            petId = pet?.id,
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
private fun ThinkingIndicator(promptProgress: LlamaPromptProcessingProgress? = null) {
    Column(modifier = Modifier.padding(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
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
        if (promptProgress != null) {
            Spacer(modifier = Modifier.height(6.dp))
            TamaPromptProgressLine(promptProgress)
        }
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

