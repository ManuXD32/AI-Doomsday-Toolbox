package com.example.llamadroid.ui.ai.llama.chat

import com.example.llamadroid.ui.walkthrough.WalkthroughAlertDialog as AlertDialog
import android.Manifest
import android.content.Intent
import android.media.MediaRecorder
import android.widget.Toast
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.google.gson.Gson
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.example.llamadroid.R
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.data.db.KnowledgeSourceEntity
import com.example.llamadroid.data.db.ModelType
import com.example.llamadroid.data.db.NoteEntity
import com.example.llamadroid.data.db.NoteType
import com.example.llamadroid.data.model.defaultLiteRtChatContextTokens
import com.example.llamadroid.data.model.defaultLiteRtEngineMaxTokens
import com.example.llamadroid.data.model.LlamaMessageEntity
import com.example.llamadroid.data.model.LlamaServerEntity
import com.example.llamadroid.data.model.stripEmbeddedAudioTranscript
import com.example.llamadroid.data.model.normalizeLiteRtBackend
import com.example.llamadroid.data.model.supportsLiteRtVision
import com.example.llamadroid.data.model.supportsLiteRtAudio
import com.example.llamadroid.data.repository.KnowledgeBaseRepository
import com.example.llamadroid.data.repository.LlamaRepository
import com.example.llamadroid.onnx.SUPERTONIC_DEFAULT_LANGUAGE
import com.example.llamadroid.onnx.isOnnxBackgroundRemovalModel
import com.example.llamadroid.onnx.isOnnxTxt2ImgBundle
import com.example.llamadroid.onnx.resolveSupertonicVoices
import com.example.llamadroid.onnx.supertonicLanguageCodes
import com.example.llamadroid.service.KnowledgeBaseIndexingService
import com.example.llamadroid.service.LlamaCallService
import com.example.llamadroid.service.LlamaClientService
import com.example.llamadroid.service.NativeChatGenerationStates
import com.example.llamadroid.ui.agent.CustomToolsScreen
import com.example.llamadroid.service.NativeChatKvCacheStore
import com.example.llamadroid.service.NativeChatMediaFailures
import com.example.llamadroid.service.NativeChatToolConfig
import com.example.llamadroid.service.withChatAudioOverrides
import com.example.llamadroid.service.supportsSdTxt2Img
import com.example.llamadroid.ui.navigation.Screen
import com.example.llamadroid.service.delegation.DelegationWakeDispatcher
import java.io.File
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.example.llamadroid.ui.ai.llama.NativeChatWorkspaceDialog
import com.example.llamadroid.ui.ai.llama.displayLlamaModelName
import com.example.llamadroid.ui.ai.llama.LlamaChatExportPayload
import com.example.llamadroid.ui.ai.llama.LlamaChatSerializedMessage
import com.example.llamadroid.ui.ai.llama.LlamaChatViewModel
import com.example.llamadroid.ui.ai.llama.LlamaChatViewModelFactory
import com.example.llamadroid.ui.ai.llama.servers.ServerDetailSheet
import com.example.llamadroid.ui.ai.llama.servers.ServerEditorSheet
import com.example.llamadroid.ui.ai.llama.servers.ServerPickerSheet
import com.example.llamadroid.ui.ai.llama.servers.ServerRegistryViewModel
import com.example.llamadroid.ui.ai.llama.servers.effectiveServerStatus
import com.example.llamadroid.ui.ai.llama.servers.key
import com.example.llamadroid.ui.ai.llama.servers.serverErrorText
import com.example.llamadroid.data.repository.UnifiedServer
import com.example.llamadroid.data.repository.UnifiedServerKind
import com.example.llamadroid.data.repository.UnifiedServerStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LlamaChatScreen(
    navController: NavController,
    chatId: Long,
    initialServerId: Long
) {
    val context = LocalContext.current
    val videoOpenFailedMessage = stringResource(R.string.llama_video_open_failed)
    val generatingTitleMessage = stringResource(R.string.chat_generating_title)
    val localizedStrings = llamaChatLocalizedStrings()
    val density = LocalDensity.current
    val rootView = LocalView.current
    val database = AppDatabase.getDatabase(context)
    val checkDao = remember { database.llamaServerDao() }
    val knowledgeBaseRepository = remember { KnowledgeBaseRepository(context, database) }
    val repository = remember {
        LlamaRepository(
            database.llamaServerDao(),
            database.llamaChatDao(),
            database.llamaChatFolderDao(),
            database.llamaMessageDao()
        )
    }
    val registryViewModel: ServerRegistryViewModel = viewModel()
    val viewModel: LlamaChatViewModel = viewModel(factory = LlamaChatViewModelFactory(repository))

    // UI State
    val messages by viewModel.messages.collectAsState()
    val attachmentsUi = rememberChatAttachmentsUi(repository, chatId)
    val pickChatFile = rememberChatFilePicker(chatId)
    val generationState by remember(chatId) { NativeChatGenerationStates.flowFor(chatId) }
        .collectAsState(initial = NativeChatGenerationStates.stateFor(chatId))
    val callState by LlamaCallService.state.collectAsState()
    val whisperModels by remember(database) {
        database.modelDao().getModelsByType(ModelType.WHISPER)
    }.collectAsState(initial = emptyList())
    val onnxImageModels by remember(database) {
        database.modelDao().getModelsByType(ModelType.ONNX_IMAGE_GEN)
    }.collectAsState(initial = emptyList())
    val sdImageMainModels by remember(database) {
        database.modelDao().getModelsByTypes(listOf(ModelType.SD_CHECKPOINT, ModelType.SD_DIFFUSION))
    }.collectAsState(initial = emptyList())
    val sdImageSupportModels by remember(database) {
        database.modelDao().getModelsByTypes(
            listOf(
                ModelType.SD_VAE,
                ModelType.SD_TAE,
                ModelType.SD_CLIP_L,
                ModelType.SD_CLIP_G,
                ModelType.SD_T5XXL,
                ModelType.LLM,
                ModelType.VISION_PROJECTOR,
                ModelType.SD_PHOTOMAKER
            )
        )
    }.collectAsState(initial = emptyList())
    val onnxBackgroundRemovalModels by remember(database) {
        database.modelDao().getModelsByType(ModelType.ONNX_BACKGROUND_REMOVAL)
    }.collectAsState(initial = emptyList())
    val onnxTtsModels by remember(database) {
        database.modelDao().getModelsByType(ModelType.ONNX_TTS)
    }.collectAsState(initial = emptyList())
    val liteRtModels by remember(database) {
        database.liteRtModelDao().observeAll()
    }.collectAsState(initial = emptyList())
    val nativeChatImageModelOptions = remember(onnxImageModels) {
        onnxImageModels
            .filter { it.isOnnxTxt2ImgBundle() }
            .map { it.filename }
            .distinct()
    }
    val nativeChatSdImageModelOptions = remember(sdImageMainModels) {
        sdImageMainModels
            .filter { it.supportsSdTxt2Img() }
            .map { it.filename }
            .distinct()
    }
    val nativeChatBgrModelOptions = remember(onnxBackgroundRemovalModels) {
        onnxBackgroundRemovalModels
            .filter { it.isOnnxBackgroundRemovalModel() }
            .map { it.filename }
            .distinct()
    }
    val nativeChatTtsVoiceOptions = remember(onnxTtsModels) {
        onnxTtsModels.firstOrNull()
            ?.let { runCatching { resolveSupertonicVoices(File(it.path)) }.getOrDefault(emptyList()) }
            .orEmpty()
    }
    val knowledgeBases by knowledgeBaseRepository.observeKnowledgeBases().collectAsState(initial = emptyList())
    val onKnowledgeLinkClick: (String) -> Boolean = remember(navController) {
        { uri ->
            val chunkId = Screen.KnowledgeChunkReader.chunkIdFromUri(uri)
            if (chunkId != null) {
                navController.navigate(Screen.KnowledgeChunkReader.createRoute(chunkId))
                true
            } else {
                false
            }
        }
    }

    val settings = remember { ChatSettingsState() }
    var inputMessage by remember { mutableStateOf("") }
    var resolvedServer by remember { mutableStateOf<LlamaServerEntity?>(null) }
    var serverResolved by remember { mutableStateOf(false) }
    var boundServerMissing by remember { mutableStateOf(false) }
    var serverRefreshTick by remember { mutableIntStateOf(0) }
    val activeServer = resolvedServer
    val isCallActiveForChat = callState.isActive && callState.chatId == chatId
    var messagePendingDelete by remember { mutableStateOf<LlamaMessageEntity?>(null) }
    var messagePendingRetry by remember { mutableStateOf<LlamaMessageEntity?>(null) }
    var chatContentBottomInWindowPx by remember { mutableIntStateOf(0) }
    val fullWindowHeightPx = maxOf(
        rootView.rootView.height,
        rootView.resources.displayMetrics.heightPixels
    )
    val alreadyReservedBottomPx = (
        fullWindowHeightPx - chatContentBottomInWindowPx
    ).coerceAtLeast(0)
    val effectiveImePadding = with(density) {
        (
            WindowInsets.ime.getBottom(this) - alreadyReservedBottomPx
        ).coerceAtLeast(0).toDp()
    }
    var showToolActivity by remember { mutableStateOf(false) }
    var showServerSheet by remember { mutableStateOf(false) }
    var showServerPicker by remember { mutableStateOf(false) }
    var pendingSwitchRowId by remember { mutableStateOf<Long?>(null) }
    var showSettingsSheet by remember { mutableStateOf(false) }
    var showChatOptions by remember { mutableStateOf(false) }
    var detailServerKey by remember { mutableStateOf<String?>(null) }
    var editingServer by remember { mutableStateOf<UnifiedServer?>(null) }
    var showServerEditor by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var renameText by remember { mutableStateOf("") }
    var showWorkspaceDialog by rememberSaveable { mutableStateOf(false) }
    var showCustomToolsManager by rememberSaveable { mutableStateOf(false) }
    val openedAtMs = remember { System.currentTimeMillis() }
    val autoPlayedAssistantAudioPaths = remember { mutableStateListOf<String>() }

    // Search state
    var isSearching by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var currentMatchIndex by remember { mutableIntStateOf(0) }

    // Export menu state
    var showClearChatConfirm by remember { mutableStateOf(false) }

    // Coroutine Scope for UI events
    val scope = rememberCoroutineScope()

    // Error Handling (Toasts)
    LaunchedEffect(generationState) {
        (generationState as? LlamaClientService.GenerationState.Error)?.let { errorState ->
            if (errorState.chatId == -1L || errorState.chatId == chatId) {
                Toast.makeText(context, errorState.message, Toast.LENGTH_LONG).show()
            }
        }
    }

    // Server & Chat details
    val chats by viewModel.chats.collectAsState()
    val currentChat = chats.find { it.id == chatId }

    // Export launcher (declared after scope and currentChat are available)
    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            scope.launch {
                try {
                    val chat = currentChat ?: return@launch
                    val msgs = viewModel.getMessagesOnce(chatId)
                    val exportData = LlamaChatExportPayload(
                        title = chat.title,
                        systemPrompt = chat.systemPrompt,
                        apiParams = chat.apiParams,
                        messages = msgs.map {
                            LlamaChatSerializedMessage(
                                role = it.role,
                                content = it.content,
                                imagePath = it.imagePath,
                                audioPath = it.audioPath,
                                videoPath = it.videoPath
                            )
                        }
                    )
                    val json = Gson().toJson(exportData)
                    context.contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray()) }
                    Toast.makeText(context, localizedStrings.exportSuccess, Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(
                        context,
                        formatLlamaChatString(
                            localizedStrings.exportFailedFormat,
                            e.message ?: localizedStrings.errorGeneric
                        ),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    fun saveCurrentChatAsNote() {
        scope.launch {
            try {
                val chat = currentChat ?: return@launch
                val msgs = viewModel.getMessagesOnce(chatId)
                val note = NoteEntity(
                    title = chat.title,
                    content = llamaMessagesToNoteMarkdown(
                        systemPrompt = chat.systemPrompt,
                        messages = msgs,
                        systemLabel = localizedStrings.noteTranscriptSystem,
                        imageLabel = localizedStrings.noteTranscriptImage,
                        audioLabel = localizedStrings.noteTranscriptAudio,
                        videoLabel = localizedStrings.noteTranscriptVideo
                    ),
                    type = NoteType.MANUAL,
                    sourceFile = localizedStrings.notesImportSourceNativeChat,
                    isLlmWhitelisted = false
                )
                database.noteDao().insert(note)
                Toast.makeText(context, localizedStrings.saveChatAsNoteSuccess, Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(
                    context,
                    formatLlamaChatString(
                        localizedStrings.saveChatAsNoteFailedFormat,
                        e.message ?: localizedStrings.errorGeneric
                    ),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    val unifiedServers by registryViewModel.servers.collectAsState()
    val serverActions by registryViewModel.actions.collectAsState()
    val activeUnified = activeServer?.id?.let { rowId -> unifiedServers.firstOrNull { it.rowId == rowId } }
    val activeServerAction = activeUnified?.let { serverActions[it.key] }

    var attachedImagePath by remember { mutableStateOf<String?>(null) }
    var attachedAudioPath by remember { mutableStateOf<String?>(null) }
    var attachedVideoPath by remember { mutableStateOf<String?>(null) }
    var isExtractingDocument by remember { mutableStateOf(false) }
    var isRecording by remember { mutableStateOf(false) }
    var recordingSeconds by remember { mutableIntStateOf(0) }
    var mediaRecorder by remember { mutableStateOf<MediaRecorder?>(null) }
    var recordingFile by remember { mutableStateOf<File?>(null) }
    var pendingMicStart by remember { mutableStateOf(false) }
    var pendingCallStart by remember { mutableStateOf(false) }
    var imagePreviewPath by remember { mutableStateOf<String?>(null) }
    var videoPreviewPath by remember { mutableStateOf<String?>(null) }

    val activeLiteRtModel = remember(activeServer?.liteRtModelId, liteRtModels) {
        liteRtModels.firstOrNull { model -> model.id == activeServer?.liteRtModelId }
    }
    val supportsVision = activeServer?.let { server ->
        if (server.isLiteRtEngine()) {
            activeLiteRtModel?.supportsLiteRtVision() == true
        } else {
            server.supportsVision
        }
    } == true
    // Video button: "native" is tried even on unflagged servers, "transcript" needs Whisper; both
    // let the chat override the unreliable capability flag (see NativeChatMediaRouting).
    val supportsVideo = activeServer?.let { server ->
        val llamaEngine = server.isLlamaServerEngine() || server.isLlamaSwapEngine()
        when (NativeChatToolConfig.normalizeVideoInputMode(settings.videoInputMode)) {
            NativeChatToolConfig.VIDEO_INPUT_NATIVE -> llamaEngine
            NativeChatToolConfig.VIDEO_INPUT_TRANSCRIPT ->
                llamaEngine && (server.whisperModelPath?.isNotBlank() == true || whisperModels.isNotEmpty())
            else -> server.supportsVideo && llamaEngine
        }
    } == true
    // The chat's own audio options (Chat options in the menu) override the server card.
    val chatAudioServer = remember(
        activeServer, settings.audioInputMode, settings.whisperModelPath, settings.whisperLanguage
    ) {
        activeServer?.withChatAudioOverrides(
            NativeChatToolConfig(
                audioInputMode = settings.audioInputMode,
                whisperModelPath = settings.whisperModelPath.takeIf { it.isNotBlank() },
                whisperLanguage = settings.whisperLanguage.takeIf { it.isNotBlank() }
            ),
            directRejected = NativeChatMediaFailures.audioRejected(chatId)
        )
    }
    val whisperFallbackAvailable = chatAudioServer?.whisperModelPath?.isNotBlank() == true || whisperModels.isNotEmpty()
    val chatDirectAudioCapable = activeServer?.let { server ->
        if (server.isLiteRtEngine()) activeLiteRtModel?.supportsLiteRtAudio() == true
        else server.hasDirectAudioInputCapability()
    } == true
    val supportsDirectAudioInput = chatAudioServer?.let { server ->
        if (server.isLiteRtEngine()) {
            val audioMode = NativeChatToolConfig.normalizeAudioInputMode(settings.audioInputMode)
            audioMode == NativeChatToolConfig.AUDIO_INPUT_DIRECT ||
                (activeLiteRtModel?.supportsLiteRtAudio() == true && audioMode != NativeChatToolConfig.AUDIO_INPUT_WHISPER)
        } else {
            server.supportsDirectAudioInput()
        }
    } == true
    val supportsAudioInput = supportsDirectAudioInput ||
        (activeServer != null && whisperFallbackAvailable)
    val generationStateSnapshot = generationState
    val activeGenerationState = generationStateSnapshot as? LlamaClientService.GenerationState.Generating
    val streamingGenerationState = activeGenerationState?.takeIf { !it.isTranscribingAudio }
    val completedGenerationState = generationStateSnapshot as? LlamaClientService.GenerationState.Completed
    val queuedGenerationState = generationStateSnapshot as? LlamaClientService.GenerationState.Queued
    val isCurrentChatGenerating = activeGenerationState != null
    // Generating or waiting in the queue: this chat cannot start, clear or retry another turn.
    val isChatBusy = isCurrentChatGenerating || queuedGenerationState != null
    val isTranscribingAudio = isCurrentChatGenerating && activeGenerationState?.isTranscribingAudio == true
    val isStreamingResponse = streamingGenerationState != null
    val activeToolEvents = streamingGenerationState?.toolEvents.orEmpty()
    val displayedMessages = remember(messages, isStreamingResponse) {
        if (isStreamingResponse) {
            messages.filterIndexed { index, msg ->
                !(index == messages.lastIndex && msg.role == "assistant")
            }
        } else {
            messages
        }
    }
    val lastPersistedMessage = messages.lastOrNull()
    val canContinueChat = !isChatBusy &&
        lastPersistedMessage?.role == "assistant" &&
        (
            lastPersistedMessage.content.isNotBlank() ||
                !lastPersistedMessage.thinking.isNullOrBlank() ||
                !lastPersistedMessage.imagePath.isNullOrBlank() ||
                !lastPersistedMessage.audioPath.isNullOrBlank() ||
                !lastPersistedMessage.videoPath.isNullOrBlank()
        )
    val activeServerSubtitle = remember(
        activeServer,
        localizedStrings.engineLiteRt,
        localizedStrings.engineOllama,
        localizedStrings.engineLlamaServer,
        localizedStrings.noServers
    ) {
        activeServer?.let { server ->
            buildList {
                add(server.name)
                add(
                    if (server.isLiteRtEngine()) {
                        localizedStrings.engineLiteRt
                    } else if (server.isOllamaEngine()) {
                        localizedStrings.engineOllama
                    } else {
                        localizedStrings.engineLlamaServer
                    }
                )
                server.modelName?.takeIf { it.isNotBlank() }?.let { add(displayLlamaModelName(it)) }
            }.joinToString(separator = " · ")
        } ?: localizedStrings.noServers
    }
    val liteRtContextCap = activeLiteRtModel?.defaultLiteRtEngineMaxTokens()
        ?: LITERT_CHAT_FALLBACK_CONTEXT_MAX
    val liteRtDefaultContext = activeLiteRtModel
        ?.defaultLiteRtChatContextTokens()
        ?.coerceAtMost(liteRtContextCap)
        ?: liteRtContextCap
    val liteRtContextRange = remember(liteRtContextCap) {
        LITERT_CHAT_MIN_CONTEXT_TOKENS..liteRtContextCap.coerceAtLeast(LITERT_CHAT_MIN_CONTEXT_TOKENS)
    }

    val chatDocumentSources by remember(settings.chatDocumentKnowledgeBaseId) {
        settings.chatDocumentKnowledgeBaseId?.let { knowledgeBaseRepository.observeSources(it) }
            ?: flowOf(emptyList<KnowledgeSourceEntity>())
    }.collectAsState(initial = emptyList())

    // ---- Server resolution: the chat's own binding wins, then the route argument, then last used.
    LaunchedEffect(currentChat?.id, currentChat?.serverId, serverRefreshTick) {
        val chat = currentChat ?: return@LaunchedEffect
        val wanted = resolveEffectiveServerId(chat.serverId, initialServerId)
        if (wanted != null) {
            val row = checkDao.getServerById(wanted)
            if (row == null && chat.serverId != null) {
                resolvedServer = null
                boundServerMissing = true
            } else {
                resolvedServer = row ?: checkDao.getLastUsedServer()
                boundServerMissing = false
            }
        } else {
            resolvedServer = checkDao.getLastUsedServer()
            boundServerMissing = false
        }
        serverResolved = true
    }

    // ---- Settings: load from the chat, auto-save every valid change (debounced).
    val writtenParams = remember { HashSet<String>() }
    var loadedKey by remember { mutableStateOf<Pair<Long, Long?>?>(null) }
    var lastSavedSignature by remember { mutableStateOf<String?>(null) }

    fun settingsSignature(): String = settings.buildApiParamsJson() + "|" +
        (if (settings.activeServerIsLiteRt) {
            "${settings.liteRtContextForSave()}|${settings.liteRtSystemPromptForSave()}|${settings.liteRtBackendForSave()}"
        } else {
            ""
        })

    fun persistSettings(
        chatDocumentKnowledgeBaseIdOverride: Long? = settings.chatDocumentKnowledgeBaseId,
        forceKnowledgeBaseTools: Boolean = false
    ) {
        val json = settings.buildApiParamsJson(chatDocumentKnowledgeBaseIdOverride, forceKnowledgeBaseTools)
        writtenParams.add(json)
        viewModel.updateChatApiParams(chatId, json)
        if (settings.activeServerIsLiteRt) {
            currentChat?.let { chat ->
                val nextContext = settings.liteRtContextForSave()
                val nextPrompt = settings.liteRtSystemPromptForSave()
                if (chat.contextSize != nextContext || chat.systemPrompt != nextPrompt) {
                    viewModel.updateChat(chat, chat.title, nextContext, nextPrompt)
                }
            }
            resolvedServer?.takeIf { it.isLiteRtEngine() }?.let { server ->
                val nextBackend = settings.liteRtBackendForSave()
                if (normalizeLiteRtBackend(server.liteRtBackend) != nextBackend) {
                    val updated = server.copy(liteRtBackend = nextBackend)
                    resolvedServer = updated
                    settings.activeServer = updated
                    scope.launch { repository.updateServer(updated) }
                }
            }
        }
        lastSavedSignature = settingsSignature()
    }

    fun flushSettings() {
        if (settings.loaded && shouldAutoSave(true, lastSavedSignature, settingsSignature())) persistSettings()
    }

    LaunchedEffect(
        currentChat?.id,
        currentChat?.apiParams,
        serverResolved,
        resolvedServer?.id,
        liteRtDefaultContext,
        liteRtContextRange
    ) {
        val chat = currentChat ?: return@LaunchedEffect
        if (!serverResolved) return@LaunchedEffect
        val key = chat.id to resolvedServer?.id
        val isOwnEcho = chat.apiParams != null && chat.apiParams in writtenParams
        if (settings.loaded && loadedKey == key && isOwnEcho) return@LaunchedEffect
        settings.loadFrom(chat, resolvedServer, liteRtDefaultContext, liteRtContextRange)
        loadedKey = key
        lastSavedSignature = settingsSignature()
    }

    // These effects outlive a single composition, so they call the latest closures (fresh chat/server).
    val persistSettingsLatest by rememberUpdatedState { persistSettings() }
    val flushSettingsLatest by rememberUpdatedState { flushSettings() }

    LaunchedEffect(settings) {
        snapshotFlow { if (settings.loaded) settingsSignature() else null }
            .collectLatest { signature ->
                if (signature == null) return@collectLatest
                delay(AUTO_SAVE_DEBOUNCE_MS)
                if (shouldAutoSave(settings.loaded, lastSavedSignature, signature)) persistSettingsLatest()
            }
    }

    DisposableEffect(Unit) {
        onDispose { flushSettingsLatest() }
    }

    LaunchedEffect(nativeChatTtsVoiceOptions, settings.assistantTtsVoiceName) {
        if (nativeChatTtsVoiceOptions.isNotEmpty() && settings.assistantTtsVoiceName !in nativeChatTtsVoiceOptions) {
            settings.assistantTtsVoiceName = nativeChatTtsVoiceOptions.first()
        }
    }

    LaunchedEffect(settings.assistantTtsLanguage) {
        if (settings.assistantTtsLanguage !in supertonicLanguageCodes) {
            settings.assistantTtsLanguage = SUPERTONIC_DEFAULT_LANGUAGE
        }
    }

    fun clearImageAttachment() {
        attachedImagePath?.let { File(it).delete() }
        if (imagePreviewPath == attachedImagePath) {
            imagePreviewPath = null
        }
        attachedImagePath = null
    }

    fun clearAudioAttachment() {
        attachedAudioPath?.let { File(it).delete() }
        attachedAudioPath = null
    }

    fun clearVideoAttachment() {
        attachedVideoPath?.let { File(it).delete() }
        if (videoPreviewPath == attachedVideoPath) {
            videoPreviewPath = null
        }
        attachedVideoPath = null
    }

    fun retryUserMessage(message: LlamaMessageEntity) {
        val serverId = activeServer?.id
        if (serverId == null) {
            showServerPicker = true
            return
        }

        scope.launch {
            viewModel.deleteMessagesAfter(chatId, message.timestamp, message.id)
            NativeChatGenerationStates.clearIfIdle(chatId)
            val intent = Intent(context, LlamaClientService::class.java).apply {
                action = LlamaClientService.ACTION_GENERATE
                putExtra(LlamaClientService.EXTRA_CHAT_ID, chatId)
                putExtra(LlamaClientService.EXTRA_SERVER_ID, serverId)
            }
            context.startForegroundService(intent)
        }
    }

    fun retryFailedTranscription(message: LlamaMessageEntity) {
        val serverId = activeServer?.id
        if (serverId == null) {
            showServerPicker = true
            return
        }

        val resendContent = stripEmbeddedAudioTranscript(message.content)
        scope.launch {
            viewModel.deleteMessagesAfter(chatId, message.timestamp, message.id)
            viewModel.deleteMessageNow(message)
            NativeChatGenerationStates.clearIfIdle(chatId)
            val intent = Intent(context, LlamaClientService::class.java).apply {
                action = LlamaClientService.ACTION_GENERATE
                putExtra(LlamaClientService.EXTRA_CHAT_ID, chatId)
                putExtra(LlamaClientService.EXTRA_SERVER_ID, serverId)
                putExtra(LlamaClientService.EXTRA_USER_MESSAGE, resendContent)
                message.imagePath?.takeIf { it.isNotBlank() }?.let {
                    putExtra(LlamaClientService.EXTRA_IMAGE_PATH, it)
                }
                message.audioPath?.takeIf { it.isNotBlank() }?.let {
                    putExtra(LlamaClientService.EXTRA_AUDIO_PATH, it)
                }
                message.videoPath?.takeIf { it.isNotBlank() }?.let {
                    putExtra(LlamaClientService.EXTRA_VIDEO_PATH, it)
                }
            }
            context.startForegroundService(intent)
        }
    }

    fun regenerateAssistantMessage(msg: LlamaMessageEntity) {
        val serverId = activeServer?.id
        if (serverId == null) {
            showServerPicker = true
            return
        }
        if (msg.role != "assistant") return
        scope.launch {
            viewModel.deleteMessagesAfter(chatId, msg.timestamp, msg.id)
            viewModel.deleteMessageNow(msg)
            NativeChatGenerationStates.clearIfIdle(chatId)
            val intent = Intent(context, LlamaClientService::class.java).apply {
                action = LlamaClientService.ACTION_GENERATE
                putExtra(LlamaClientService.EXTRA_CHAT_ID, chatId)
                putExtra(LlamaClientService.EXTRA_SERVER_ID, serverId)
            }
            context.startForegroundService(intent)
        }
    }

    fun continueGeneration() {
        val serverId = activeServer?.id
        if (serverId == null) {
            showServerPicker = true
            return
        }
        // No EXTRA_USER_MESSAGE: the service continues from the existing history.
        val intent = Intent(context, LlamaClientService::class.java).apply {
            action = LlamaClientService.ACTION_GENERATE
            putExtra(LlamaClientService.EXTRA_CHAT_ID, chatId)
            putExtra(LlamaClientService.EXTRA_SERVER_ID, serverId)
        }
        context.startForegroundService(intent)
    }

    fun sendCurrentMessage() {
        val serverId = activeServer?.id
        if (serverId == null) {
            showServerPicker = true
            return
        }
        val text = if (inputMessage.isBlank() && attachmentsUi.pendingFiles.isNotEmpty()) {
            attachedFilesFallbackText(context, attachmentsUi.pendingFiles)
        } else {
            inputMessage
        }
        val intentImagePath = attachedImagePath
        val intentAudioPath = attachedAudioPath
        val intentVideoPath = attachedVideoPath
        inputMessage = ""
        attachedImagePath = null
        attachedAudioPath = null
        attachedVideoPath = null

        val intent = Intent(context, LlamaClientService::class.java).apply {
            action = LlamaClientService.ACTION_GENERATE
            putExtra(LlamaClientService.EXTRA_CHAT_ID, chatId)
            putExtra(LlamaClientService.EXTRA_SERVER_ID, serverId)
            putExtra(LlamaClientService.EXTRA_USER_MESSAGE, text)
            if (!intentImagePath.isNullOrBlank()) {
                putExtra(LlamaClientService.EXTRA_IMAGE_PATH, intentImagePath)
            }
            if (!intentAudioPath.isNullOrBlank()) {
                putExtra(LlamaClientService.EXTRA_AUDIO_PATH, intentAudioPath)
            }
            if (!intentVideoPath.isNullOrBlank()) {
                putExtra(LlamaClientService.EXTRA_VIDEO_PATH, intentVideoPath)
            }
        }
        context.startForegroundService(intent)
    }

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
        onResult = { uri ->
            if (uri != null) {
                scope.launch {
                    try {
                        val path = withContext(Dispatchers.IO) {
                            persistContentUriToAppPrivateFile(
                                context = context,
                                uri = uri,
                                prefix = "llama_image_upload",
                                defaultExtension = "jpg"
                            )
                        }
                        attachedImagePath = path
                    } catch (e: Exception) {
                        Toast.makeText(
                            context,
                            localizedStrings.attachMediaFailed,
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
        }
    )
    val videoPickerLauncher = rememberLauncherForActivityResult(
        // Keep video selection on the same persistable document contract as Video Summary. The
        // staged private copy survives picker process recreation and is cleaned with the pending
        // attachment lifecycle below.
        contract = ActivityResultContracts.OpenDocument(),
        onResult = { uri ->
            if (uri != null) {
                scope.launch {
                    try {
                        runCatching {
                            context.contentResolver.takePersistableUriPermission(
                                uri,
                                Intent.FLAG_GRANT_READ_URI_PERMISSION
                            )
                        }
                        val path = withContext(Dispatchers.IO) {
                            persistContentUriToAppPrivateFile(
                                context = context,
                                uri = uri,
                                prefix = "llama_video_upload",
                                defaultExtension = "mp4"
                            )
                        } ?: throw IllegalStateException(videoOpenFailedMessage)
                        clearVideoAttachment()
                        attachedVideoPath = path
                    } catch (e: Exception) {
                        Toast.makeText(
                            context,
                            localizedStrings.attachMediaFailed,
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
        }
    )
    val documentPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments(),
        onResult = { uris ->
            val selectedUris = uris.distinctBy { it.toString() }
            if (selectedUris.isNotEmpty()) {
                scope.launch {
                    isExtractingDocument = true
                    try {
                        val embeddingConfig = knowledgeBaseRepository.currentEmbeddingConfig()
                        if (!embeddingConfig.isConfigured) {
                            Toast.makeText(
                                context,
                                localizedStrings.kbUploadNeedsEmbedding,
                                Toast.LENGTH_LONG
                            ).show()
                            return@launch
                        }
                        val knowledgeBaseId = settings.chatDocumentKnowledgeBaseId
                            ?: knowledgeBaseRepository.ensureChatDocumentKnowledgeBase(
                                chatId = chatId,
                                chatTitle = currentChat?.title.orEmpty()
                        )
                        with(settings) {
                            chatDocumentKnowledgeBaseId = knowledgeBaseId
                            selectedKnowledgeBaseIds = emptyList()
                            toolsEnabled = true
                            webSearchEnabled = false
                            kiwixSearchEnabled = false
                            fetchUrlEnabled = false
                            dateTimeEnabled = false
                            calculatorEnabled = false
                            noteToolsEnabled = false
                            todoToolsEnabled = false
                            calendarToolsEnabled = false
                            alarmToolsEnabled = false
                            knowledgeBaseEnabled = true
                            knowledgeBaseAutoContextEnabled = true
                            imageGenerationEnabled = false
                            imageIterationEnabled = false
                            backgroundRemovalEnabled = false
                            assistantTtsEnabled = false
                        }
                        persistSettings(
                            chatDocumentKnowledgeBaseIdOverride = knowledgeBaseId,
                            forceKnowledgeBaseTools = true
                        )

                        val queuedCount = selectedUris.mapNotNull { uri ->
                            runCatching {
                                runCatching {
                                    context.contentResolver.takePersistableUriPermission(
                                        uri,
                                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                                    )
                                }
                                knowledgeBaseRepository.queueFile(
                                    knowledgeBaseId = knowledgeBaseId,
                                    uri = uri,
                                    displayName = queryDocumentDisplayName(context, uri)
                                )
                            }.onFailure { error ->
                                Toast.makeText(
                                    context,
                                    formatLlamaChatString(
                                        localizedStrings.documentQueueFailedFormat,
                                        error.message ?: localizedStrings.errorGeneric
                                    ),
                                    Toast.LENGTH_LONG
                                ).show()
                            }.getOrNull()
                        }.onEach { sourceId ->
                            KnowledgeBaseIndexingService.enqueueQueuedFile(context, sourceId)
                        }.size

                        if (queuedCount > 0) {
                            Toast.makeText(
                                context,
                                formatLlamaChatString(localizedStrings.documentQueuedForChatFormat, queuedCount),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    } catch (e: Exception) {
                        Toast.makeText(
                            context,
                            formatLlamaChatString(
                                localizedStrings.documentQueueFailedFormat,
                                e.message ?: localizedStrings.errorGeneric
                            ),
                            Toast.LENGTH_LONG
                        ).show()
                    } finally {
                        isExtractingDocument = false
                    }
                }
            }
        }
    )
    val recordPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted && pendingMicStart) {
            pendingMicStart = false
            clearAudioAttachment()
            startLlamaRecording(
                context = context,
                onRecorderReady = { recorder, tempFile ->
                    mediaRecorder = recorder
                    recordingFile = tempFile
                    isRecording = true
                    recordingSeconds = 0
                },
                onError = { message ->
                    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                }
            )
        } else {
            pendingMicStart = false
            if (!granted) {
                Toast.makeText(
                    context,
                    localizedStrings.recordPermissionDenied,
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }
    val callPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted && pendingCallStart) {
            pendingCallStart = false
            val serverId = activeServer?.id
            if (serverId != null) {
                context.startForegroundService(LlamaCallService.startIntent(context, chatId, serverId))
            }
        } else {
            pendingCallStart = false
            if (!granted) {
                Toast.makeText(
                    context,
                    localizedStrings.recordPermissionDenied,
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    fun stopCurrentRecording() {
        if (!isRecording) return
        stopLlamaRecording(mediaRecorder)
        mediaRecorder = null
        isRecording = false
        val tempFile = recordingFile
        recordingFile = null
        if (tempFile != null) {
            scope.launch {
                try {
                    val savedPath = withContext(Dispatchers.IO) {
                        persistRecordedAudioToAppPrivateFile(
                            context = context,
                            recordingFile = tempFile
                        )
                    }
                    attachedAudioPath = savedPath
                    recordingSeconds = 0
                } catch (e: Exception) {
                    Toast.makeText(
                        context,
                        localizedStrings.attachMediaFailed,
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    fun startCallMode() {
        val serverId = activeServer?.id
        if (serverId == null) {
            showServerPicker = true
            return
        }
        val permission = Manifest.permission.RECORD_AUDIO
        if (ContextCompat.checkSelfPermission(context, permission) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            context.startForegroundService(LlamaCallService.startIntent(context, chatId, serverId))
        } else {
            pendingCallStart = true
            callPermissionLauncher.launch(permission)
        }
    }

    fun hangUpCallMode() {
        context.startService(LlamaCallService.hangUpIntent(context))
    }

    fun startCurrentRecording() {
        if (isRecording) return
        pendingMicStart = true
        val permission = Manifest.permission.RECORD_AUDIO
        if (ContextCompat.checkSelfPermission(context, permission) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            pendingMicStart = false
            clearAudioAttachment()
            startLlamaRecording(
                context = context,
                onRecorderReady = { recorder, tempFile ->
                    mediaRecorder = recorder
                    recordingFile = tempFile
                    isRecording = true
                    recordingSeconds = 0
                },
                onError = { message ->
                    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                }
            )
        } else {
            recordPermissionLauncher.launch(permission)
        }
    }

    // Load messages
    LaunchedEffect(chatId) {
        viewModel.loadMessages(chatId)
        DelegationWakeDispatcher.deliverPending(context.applicationContext, chatId)
    }

    LaunchedEffect(isRecording) {
        if (!isRecording) return@LaunchedEffect
        while (isRecording) {
            kotlinx.coroutines.delay(1000)
            recordingSeconds++
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            stopLlamaRecording(mediaRecorder)
            mediaRecorder = null
            recordingFile?.takeIf { it.exists() }?.delete()
        }
    }

    val listState = rememberLazyListState()

    // Follow the exact bottom while the user is there. Layout changes caused by a
    // streaming bubble must not be mistaken for a manual scroll away from the bottom.
    var userWantsAutoScroll by remember { mutableStateOf(true) }
    var manualScrollPending by remember { mutableStateOf(false) }

    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect { interaction ->
            when (interaction) {
                is DragInteraction.Start -> manualScrollPending = true
                is DragInteraction.Stop,
                is DragInteraction.Cancel -> {
                    userWantsAutoScroll = !listState.canScrollForward
                    if (!listState.isScrollInProgress) manualScrollPending = false
                }
            }
        }
    }

    LaunchedEffect(listState) {
        snapshotFlow {
            Triple(manualScrollPending, listState.isScrollInProgress, listState.canScrollForward)
        }.collect { (manual, scrolling, canScrollForward) ->
            if (manual) {
                userWantsAutoScroll = !canScrollForward
                if (!scrolling) manualScrollPending = false
            }
        }
    }

    val streamingAutoScrollKey = remember(
        displayedMessages,
        streamingGenerationState?.content,
        streamingGenerationState?.thinking,
        streamingGenerationState?.tokenCount,
        streamingGenerationState?.statusText,
        activeToolEvents
    ) {
        val persistedLength = displayedMessages.sumOf { message ->
            message.content.length +
                (message.thinking?.length ?: 0) +
                (message.imagePath?.length ?: 0) +
                (message.audioPath?.length ?: 0)
        }
        persistedLength +
            (streamingGenerationState?.content?.length ?: 0) +
            (streamingGenerationState?.thinking?.length ?: 0) +
            (streamingGenerationState?.tokenCount ?: 0) +
            activeToolEvents.size
    }

    // Auto-scroll whenever streaming content, thinking, or tools change and the user is still at the bottom.
    LaunchedEffect(displayedMessages.size, streamingAutoScrollKey, userWantsAutoScroll) {
        if (userWantsAutoScroll && listState.layoutInfo.totalItemsCount > 0) {
            listState.scrollToItem(
                index = listState.layoutInfo.totalItemsCount - 1,
                scrollOffset = Int.MAX_VALUE
            )
        }
    }

    val activeStatus = activeUnified?.let { effectiveServerStatus(it, activeServerAction) }
    val generationStatusText = (generationState as? LlamaClientService.GenerationState.Generating)?.statusText
    val bannerKind = if (!serverResolved) {
        ChatServerBannerKind.NONE
    } else {
        resolveChatServerBanner(
            boundServerMissing = boundServerMissing,
            hasActiveServer = activeServer != null,
            isLocal = activeUnified != null && activeUnified.kind != UnifiedServerKind.REMOTE,
            status = activeStatus,
            actionBusy = activeServerAction?.busy == true,
            actionHasError = activeServerAction?.error != null,
            generationStatusText = generationStatusText
        )
    }
    val bannerErrorText = activeUnified?.let { serverErrorText(it, activeServerAction) }
    val serverChip = activeServer?.let { server ->
        ChatServerChip(
            label = server.modelName?.takeIf { it.isNotBlank() }?.let { displayLlamaModelName(it) } ?: server.name,
            status = activeStatus
        )
    }
    val settingsEnv = ChatSettingsEnv(
        onnxTtsModels = onnxTtsModels,
        ttsVoiceOptions = nativeChatTtsVoiceOptions,
        knowledgeBases = knowledgeBases,
        imageModelOptions = nativeChatImageModelOptions,
        sdImageMainModels = sdImageMainModels,
        sdImageModelOptions = nativeChatSdImageModelOptions,
        sdImageSupportModels = sdImageSupportModels,
        bgrModelOptions = nativeChatBgrModelOptions,
        liteRtContextCap = liteRtContextCap,
        onOpenKnowledgeBase = { navController.navigate(Screen.KnowledgeBase.route) },
        onManageCustomTools = { showCustomToolsManager = true }
    )

    Scaffold(
        contentWindowInsets = WindowInsets(0.dp, 0.dp, 0.dp, 0.dp),
        topBar = {
            ChatTopBar(
                title = currentChat?.title ?: stringResource(R.string.llama_client_title),
                serverChip = serverChip,
                isCallActive = isCallActiveForChat,
                callEnabled = activeServer != null,
                isSearching = isSearching,
                showWorkspace = settings.effectiveToolsEnabled && settings.fileToolsEnabled && currentChat != null,
                pinned = currentChat?.pinnedToAiHub == true,
                pinEnabled = currentChat != null && (currentChat.pinnedToAiHub || activeServer != null),
                clearEnabled = messages.isNotEmpty() && !isChatBusy,
                onBack = { navController.popBackStack() },
                onRename = {
                    renameText = currentChat?.title.orEmpty()
                    showRenameDialog = true
                },
                onServerChip = { showServerSheet = true },
                onChangeServer = { showServerPicker = true },
                onChatOptions = { showChatOptions = true },
                onCall = {
                    if (isCallActiveForChat) {
                        hangUpCallMode()
                    } else {
                        startCallMode()
                    }
                },
                onSettings = { showSettingsSheet = true },
                onToggleSearch = {
                    isSearching = !isSearching
                    if (!isSearching) searchQuery = ""
                },
                onWorkspace = { showWorkspaceDialog = true },
                onPin = {
                    val chat = currentChat
                    if (chat != null) {
                        if (chat.pinnedToAiHub) {
                            viewModel.setChatPinnedToAiHub(chat, pinned = false, serverId = null)
                            Toast.makeText(context, localizedStrings.unpinAiHubDone, Toast.LENGTH_SHORT).show()
                        } else {
                            val serverId = activeServer?.id
                            if (serverId == null) {
                                Toast.makeText(context, localizedStrings.pinAiHubRequiresServer, Toast.LENGTH_SHORT).show()
                            } else {
                                viewModel.setChatPinnedToAiHub(chat, pinned = true, serverId = serverId)
                                Toast.makeText(context, localizedStrings.pinAiHubDone, Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                },
                onClear = { showClearChatConfirm = true },
                onExport = { exportLauncher.launch((currentChat?.title ?: "chat") + ".json") },
                onSaveAsNote = { saveCurrentChatAsNote() },
                chatId = chatId,
                autoCompactEnabled = settings.autoCompactEnabled,
                onAutoCompactChange = { settings.autoCompactEnabled = it },
                isGenerating = isChatBusy,
                delegationBar = if (settings.delegation.enabled) {
                    ChatDelegationBar(chatId, settings.delegation.runtimeId, navController)
                } else null,
                // The web UI WebView only reaches servers on this device (127.0.0.1), so remote servers hide it.
                showWebUi = activeUnified != null && activeUnified.kind != UnifiedServerKind.REMOTE &&
                    activeUnified.engine == LlamaServerEntity.ENGINE_LLAMA_SERVER &&
                    activeStatus == UnifiedServerStatus.RUNNING,
                onOpenWebUi = { activeUnified?.let { navController.navigate("chat?port=${it.port}") } },
                onGenerateTitle = {
                    Toast.makeText(context, generatingTitleMessage, Toast.LENGTH_SHORT).show()
                    com.example.llamadroid.service.titlegen.TitleGenerationManager.generateNow(context, chatId)
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .onGloballyPositioned { coordinates ->
                    chatContentBottomInWindowPx = (
                        coordinates.positionInWindow().y + coordinates.size.height
                    ).roundToInt()
                }
        ) {
            // Search bar
            AnimatedVisibility(visible = isSearching) {
                val matchIndices = remember(searchQuery, messages) {
                    if (searchQuery.isBlank()) emptyList()
                    else messages.mapIndexedNotNull { idx, msg ->
                        if (msg.content.contains(searchQuery, ignoreCase = true)) idx else null
                    }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = {
                            searchQuery = it
                            currentMatchIndex = 0
                        },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text(stringResource(R.string.llama_search_chat)) },
                        singleLine = true,
                        trailingIcon = {
                            if (searchQuery.isNotEmpty()) {
                                IconButton(onClick = { searchQuery = "" }) {
                                    Icon(Icons.Default.Close, contentDescription = stringResource(R.string.action_clear))
                                }
                            }
                        },
                        shape = RoundedCornerShape(24.dp)
                    )
                    if (matchIndices.isNotEmpty()) {
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "${currentMatchIndex + 1}/${matchIndices.size}",
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 4.dp)
                        )
                        IconButton(
                            onClick = {
                                currentMatchIndex = if (currentMatchIndex > 0) currentMatchIndex - 1 else matchIndices.size - 1
                                scope.launch { listState.animateScrollToItem(matchIndices[currentMatchIndex]) }
                            },
                            modifier = Modifier.size(48.dp)
                        ) {
                            Icon(Icons.Default.ExpandLess, contentDescription = stringResource(R.string.action_previous), modifier = Modifier.size(20.dp))
                        }
                        IconButton(
                            onClick = {
                                currentMatchIndex = if (currentMatchIndex < matchIndices.size - 1) currentMatchIndex + 1 else 0
                                scope.launch { listState.animateScrollToItem(matchIndices[currentMatchIndex]) }
                            },
                            modifier = Modifier.size(48.dp)
                        ) {
                            Icon(Icons.Default.ExpandMore, contentDescription = stringResource(R.string.action_next), modifier = Modifier.size(20.dp))
                        }
                    }
                }
                // Auto-scroll to first match
                LaunchedEffect(searchQuery) {
                    if (matchIndices.isNotEmpty()) {
                        listState.animateScrollToItem(matchIndices[0])
                    }
                }
            }

            AnimatedVisibility(visible = isTranscribingAudio) {
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp
                        )
                        Text(
                            text = stringResource(R.string.llama_transcribing_audio),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                }
            }

            ChatMessageList(
                listState = listState,
                displayedMessages = displayedMessages,
                isExtractingDocument = isExtractingDocument,
                chatDocumentSources = chatDocumentSources,
                streamingGenerationState = streamingGenerationState,
                completedGenerationState = completedGenerationState,
                canContinueChat = canContinueChat,
                isChatBusy = isChatBusy,
                hasActiveServer = activeServer != null,
                userWantsAutoScroll = userWantsAutoScroll,
                onUserWantsAutoScrollChange = { userWantsAutoScroll = it },
                isCallActiveForChat = isCallActiveForChat,
                callState = callState,
                onHangUp = { hangUpCallMode() },
                assistantTtsEnabled = settings.assistantTtsEnabled,
                openedAtMs = openedAtMs,
                autoPlayedAssistantAudioPaths = autoPlayedAssistantAudioPaths,
                onKnowledgeLinkClick = onKnowledgeLinkClick,
                onRegenerate = { regenerateAssistantMessage(it) },
                onEditMessage = { message, newContent -> viewModel.updateMessage(message.id, newContent) },
                onRetryMessage = { messagePendingRetry = it },
                onRetryTranscription = { retryFailedTranscription(it) },
                onDiscardFailedMessage = { viewModel.deleteMessage(it) },
                onDeleteMessage = { messagePendingDelete = it },
                onOpenToolActivity = { showToolActivity = true },
                onContinue = { continueGeneration() },
                modifier = Modifier.weight(1f),
                compactions = attachmentsUi.compactions,
                attachmentsByMessage = attachmentsUi.byMessage
            )

            ChatServerBanner(
                kind = bannerKind,
                progressText = activeServerAction?.progress ?: generationStatusText,
                errorText = bannerErrorText,
                onStart = { activeUnified?.let { registryViewModel.start(it) } },
                onRetry = {
                    activeUnified?.let {
                        registryViewModel.clearError(it)
                        registryViewModel.start(it)
                    }
                },
                onViewLog = { activeUnified?.let { detailServerKey = it.key } },
                onChooseServer = { showServerPicker = true }
            )

            if (chatId >= 0L) ChatDelegationStopBar(chatId = chatId, navController = navController)

            ChatComposer(
                inputMessage = inputMessage,
                onInputChange = { inputMessage = it },
                isRecording = isRecording,
                recordingSeconds = recordingSeconds,
                onStopRecording = { stopCurrentRecording() },
                attachedImagePath = attachedImagePath,
                attachedAudioPath = attachedAudioPath,
                attachedVideoPath = attachedVideoPath,
                onPreviewImage = { imagePreviewPath = attachedImagePath },
                onRemoveImage = { clearImageAttachment() },
                onRemoveAudio = { clearAudioAttachment() },
                onPreviewVideo = { videoPreviewPath = attachedVideoPath },
                onRemoveVideo = { clearVideoAttachment() },
                isChatGenerating = isCurrentChatGenerating,
                queuedState = queuedGenerationState,
                chatId = chatId,
                isCallActiveForChat = isCallActiveForChat,
                isExtractingDocument = isExtractingDocument,
                supportsVision = supportsVision,
                supportsVideo = supportsVideo,
                supportsAudioInput = supportsAudioInput,
                onPickImage = {
                    photoPickerLauncher.launch(
                        androidx.activity.result.PickVisualMediaRequest(
                            ActivityResultContracts.PickVisualMedia.ImageOnly
                        )
                    )
                },
                onPickVideo = { videoPickerLauncher.launch(arrayOf("video/*")) },
                onPickDocument = { documentPickerLauncher.launch(nativeChatDocumentMimeTypes()) },
                onStartRecording = { startCurrentRecording() },
                onSend = { sendCurrentMessage() },
                showAttachFile = settings.delegation.enabled,
                onPickFile = pickChatFile,
                pendingFiles = attachmentsUi.pendingFiles,
                onRemovePendingFile = { file -> scope.launch { removePendingChatFile(context, file) } }
            )
            Spacer(modifier = Modifier.height(effectiveImePadding))
            if (imagePreviewPath != null) {
                LlamaImagePreviewDialog(
                    imageFile = File(imagePreviewPath!!),
                    onDismiss = { imagePreviewPath = null }
                )
            }
            if (videoPreviewPath != null) {
                LlamaVideoPreviewDialog(
                    videoFile = File(videoPreviewPath!!),
                    onDismiss = { videoPreviewPath = null }
                )
            }
            if (showToolActivity) {
                LlamaToolActivityDialog(
                    events = activeToolEvents,
                    onDismiss = { showToolActivity = false }
                )
            }
            if (showWorkspaceDialog && currentChat != null) {
                NativeChatWorkspaceDialog(
                    context = context,
                    chatId = currentChat.id,
                    onDismiss = { showWorkspaceDialog = false }
                )
            }
            if (showCustomToolsManager) {
                androidx.compose.ui.window.Dialog(
                    onDismissRequest = { showCustomToolsManager = false },
                    properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
                ) {
                    androidx.compose.material3.Surface(modifier = Modifier.fillMaxSize()) {
                        CustomToolsScreen(onBack = { showCustomToolsManager = false })
                    }
                }
            }
        }
        ChatKvCacheNoticeHost(
            chatId = chatId,
            persistKvCache = currentChat?.persistKvCache == true,
            server = activeServer,
            hasNoMessages = { viewModel.getMessagesOnce(chatId).isEmpty() },
            onTurnOff = { scope.launch { repository.setPersistKvCache(chatId, false) } }
        )
        messagePendingDelete?.let { messageToDelete ->
            LlamaDeleteMessageDialog(
                onConfirm = {
                    viewModel.deleteMessage(messageToDelete)
                    messagePendingDelete = null
                },
                onDismiss = { messagePendingDelete = null }
            )
        }
        messagePendingRetry?.let { messageToRetry ->
            LlamaRetryMessageDialog(
                onConfirm = {
                    messagePendingRetry = null
                    retryUserMessage(messageToRetry)
                },
                onDismiss = { messagePendingRetry = null }
            )
        }
        if (showClearChatConfirm) {
            LlamaClearChatDialog(
                onConfirm = {
                    showClearChatConfirm = false
                    scope.launch {
                        val chatDocumentBaseToDelete = settings.chatDocumentKnowledgeBaseId
                        viewModel.clearChatNow(chatId)
                        with(settings) {
                            chatDocumentKnowledgeBaseId = null
                            knowledgeBaseEnabled = false
                            knowledgeBaseAutoContextEnabled = false
                            toolsEnabled = webSearchEnabled ||
                                kiwixSearchEnabled ||
                                fetchUrlEnabled ||
                                dateTimeEnabled ||
                                calculatorEnabled ||
                                noteToolsEnabled ||
                                todoToolsEnabled ||
                                calendarToolsEnabled ||
                                alarmToolsEnabled ||
                                fileToolsEnabled ||
                                customToolsEnabled ||
                                imageGenerationEnabled ||
                                delegation.enabled
                        }
                        persistSettings(chatDocumentKnowledgeBaseIdOverride = null)
                        chatDocumentBaseToDelete?.let { baseId ->
                            runCatching { knowledgeBaseRepository.deleteKnowledgeBase(baseId) }
                        }
                        isSearching = false
                        searchQuery = ""
                        currentMatchIndex = 0
                        Toast.makeText(
                            context,
                            localizedStrings.clearChatDone,
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                },
                onDismiss = { showClearChatConfirm = false }
            )
        }
        if (showRenameDialog) {
            ChatRenameDialog(
                initialTitle = renameText,
                onConfirm = { newTitle ->
                    showRenameDialog = false
                    viewModel.renameChat(chatId, newTitle)
                },
                onDismiss = { showRenameDialog = false }
            )
        }
        if (showServerSheet) {
            ChatServerSheet(
                server = activeUnified,
                action = activeServerAction,
                fallbackName = activeServer?.name,
                onStart = { activeUnified?.let { registryViewModel.start(it) } },
                onStop = { activeUnified?.let { registryViewModel.stop(it) } },
                onSwitchServer = {
                    showServerSheet = false
                    showServerPicker = true
                },
                onServerDetails = {
                    activeUnified?.let {
                        showServerSheet = false
                        detailServerKey = it.key
                    }
                },
                onDismiss = { showServerSheet = false }
            )
        }
        if (showServerPicker) {
            ServerPickerSheet(
                viewModel = registryViewModel,
                selectedRowId = activeServer?.id,
                onDismiss = { showServerPicker = false },
                onPick = { rowId ->
                    showServerPicker = false
                    if (shouldWarnOnServerSwitch(activeServer?.id, rowId)) {
                        pendingSwitchRowId = rowId
                    } else if (activeServer?.id != rowId) {
                        // Nothing was bound (removed/missing server), so nothing cached to warn about.
                        viewModel.bindChatServer(chatId, rowId)
                        serverRefreshTick++
                    }
                }
            )
        }
        pendingSwitchRowId?.let { newRowId ->
            val deletesCache = serverSwitchDeletesCache(currentChat?.persistKvCache == true)
            ChatSwitchServerDialog(
                deletesCache = deletesCache,
                onConfirm = {
                    pendingSwitchRowId = null
                    viewModel.bindChatServer(chatId, newRowId)
                    if (deletesCache) {
                        scope.launch(Dispatchers.IO) { NativeChatKvCacheStore(context.applicationContext).delete(chatId) }
                    }
                    serverRefreshTick++
                },
                onDismiss = { pendingSwitchRowId = null }
            )
        }
        detailServerKey?.let { key ->
            ServerDetailSheet(
                serverKey = key,
                viewModel = registryViewModel,
                onDismiss = { detailServerKey = null },
                onEdit = { server ->
                    detailServerKey = null
                    editingServer = server
                    showServerEditor = true
                },
                onOpenChat = { otherChatId, rowId ->
                    detailServerKey = null
                    navController.navigate(Screen.LlamaChat.createRoute(otherChatId, rowId))
                },
                onUpgraded = { serverRefreshTick++ }
            )
        }
        if (showServerEditor) {
            ServerEditorSheet(
                viewModel = registryViewModel,
                editing = editingServer,
                onDismiss = {
                    showServerEditor = false
                    editingServer = null
                },
                onSaved = { serverRefreshTick++ }
            )
        }
        if (showChatOptions) {
            ChatOptionsDialog(
                settings = settings,
                server = activeServer,
                serverSummary = activeServerSubtitle,
                directAudioCapable = chatDirectAudioCapable,
                directAudioRejected = NativeChatMediaFailures.audioRejected(chatId),
                whisperModelPaths = whisperModels.map { it.path },
                onOpenSettings = {
                    flushSettings()
                    showChatOptions = false
                    showSettingsSheet = true
                },
                onChangeServer = {
                    flushSettings()
                    showChatOptions = false
                    showServerPicker = true
                },
                onDismiss = {
                    flushSettings()
                    showChatOptions = false
                }
            )
        }
        if (showSettingsSheet) {
            ChatSettingsSheet(
                state = settings,
                env = settingsEnv,
                serverSummary = activeServerSubtitle,
                onOpenServerSheet = {
                    flushSettings()
                    showSettingsSheet = false
                    showServerSheet = true
                },
                onChangeServer = {
                    flushSettings()
                    showSettingsSheet = false
                    showServerPicker = true
                },
                onDismiss = {
                    flushSettings()
                    showSettingsSheet = false
                },
                delegationSection = {
                    ChatDelegationSettingsSection(
                        settings = settings,
                        chat = currentChat,
                        chatServer = activeServer,
                        registryViewModel = registryViewModel,
                        navController = navController,
                        onSettingsChanged = { persistSettings() }
                    )
                },
                memorySection = currentChat?.let { chat -> {
                    ChatKvCacheSection(
                        chat = chat,
                        server = activeServer,
                        unifiedServer = activeUnified,
                        isGenerating = isChatBusy,
                        delegationEnabled = settings.delegation.enabled,
                        onRestartServer = { registryViewModel.restart(it) }
                    )
                } }
            )
        }
    }
}

private const val AUTO_SAVE_DEBOUNCE_MS = 450L

/** Single-field rename dialog for the chat title. */
@Composable
private fun ChatRenameDialog(
    initialTitle: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var title by remember { mutableStateOf(initialTitle) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.native_chat_rename_title)) },
        text = {
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                singleLine = true,
                label = { Text(stringResource(R.string.native_chat_rename_label)) },
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(title) }, enabled = title.isNotBlank()) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}
