package com.example.llamadroid.ui.ai.llama

import android.Manifest
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.net.Uri
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Square
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import coil.compose.AsyncImage
import androidx.compose.ui.window.Dialog
import androidx.core.content.FileProvider
import com.example.llamadroid.R
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.data.db.KnowledgeBaseSourceStatus
import com.example.llamadroid.data.db.KnowledgeSourceEntity
import com.example.llamadroid.data.db.ModelType
import com.example.llamadroid.data.db.NoteEntity
import com.example.llamadroid.data.db.NoteType
import com.example.llamadroid.data.model.EmbeddedDocumentText
import com.example.llamadroid.data.model.LITERT_BACKEND_CPU
import com.example.llamadroid.data.model.LITERT_BACKEND_GPU
import com.example.llamadroid.data.model.estimateNativeChatTextTokens
import com.example.llamadroid.data.model.extractEmbeddedAudioTranscript
import com.example.llamadroid.data.model.extractEmbeddedDocumentText
import com.example.llamadroid.data.model.LlamaMessageEntity
import com.example.llamadroid.data.model.LlamaServerEntity
import com.example.llamadroid.data.model.stripEmbeddedAudioTranscript
import com.example.llamadroid.data.model.stripEmbeddedDocumentText
import com.example.llamadroid.data.model.normalizeLiteRtBackend
import com.example.llamadroid.data.repository.KnowledgeBaseRepository
import com.example.llamadroid.data.repository.LlamaRepository
import com.example.llamadroid.onnx.OnnxBackendOverride
import com.example.llamadroid.onnx.OnnxExecutionMode
import com.example.llamadroid.onnx.OnnxGraphOptimizationLevel
import com.example.llamadroid.onnx.OnnxRuntimeBackend
import com.example.llamadroid.onnx.SUPERTONIC_DEFAULT_LANGUAGE
import com.example.llamadroid.onnx.isOnnxTxt2ImgBundle
import com.example.llamadroid.onnx.resolveSupertonicVoices
import com.example.llamadroid.onnx.supertonicLanguageCodes
import com.example.llamadroid.service.KnowledgeBaseIndexingService
import com.example.llamadroid.service.LlamaClientService
import com.example.llamadroid.service.NativeChatImageToolParams
import com.example.llamadroid.service.NativeChatToolConfig
import com.example.llamadroid.ui.components.DraftFloatTextField
import com.example.llamadroid.ui.components.DraftIntTextField
import com.example.llamadroid.ui.components.DraftNullableIntTextField
import com.example.llamadroid.ui.navigation.Screen
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LlamaChatScreen(
    navController: NavController,
    chatId: Long,
    initialServerId: Long
) {
    val context = LocalContext.current
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
    val viewModel: LlamaChatViewModel = viewModel(factory = LlamaChatViewModelFactory(repository))

    // UI State
    val messages by viewModel.messages.collectAsState()
    val generationState by LlamaClientService.generationState.collectAsState()
    val whisperModels by remember(database) {
        database.modelDao().getModelsByType(ModelType.WHISPER)
    }.collectAsState(initial = emptyList())
    val onnxImageModels by remember(database) {
        database.modelDao().getModelsByType(ModelType.ONNX_IMAGE_GEN)
    }.collectAsState(initial = emptyList())
    val onnxTtsModels by remember(database) {
        database.modelDao().getModelsByType(ModelType.ONNX_TTS)
    }.collectAsState(initial = emptyList())
    val nativeChatImageModelOptions = remember(onnxImageModels) {
        onnxImageModels
            .filter { it.isOnnxTxt2ImgBundle() }
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

    var inputMessage by remember { mutableStateOf("") }
    var activeServer by remember { mutableStateOf<LlamaServerEntity?>(null) }
    var activeServerId by remember { mutableLongStateOf(initialServerId) }
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
    val openedAtMs = remember { System.currentTimeMillis() }
    val autoPlayedAssistantAudioPaths = remember { mutableStateListOf<String>() }

    // Search state
    var isSearching by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var currentMatchIndex by remember { mutableIntStateOf(0) }

    // Export menu state
    var showOverflowMenu by remember { mutableStateOf(false) }
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
                                audioPath = it.audioPath
                            )
                        }
                    )
                    val json = Gson().toJson(exportData)
                    context.contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray()) }
                    Toast.makeText(context, context.getString(R.string.llama_export_success), Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(
                        context,
                        context.getString(
                            R.string.llama_export_failed,
                            e.message ?: context.getString(R.string.error_generic)
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
                        systemLabel = context.getString(R.string.llama_note_transcript_system),
                        imageLabel = context.getString(R.string.llama_note_transcript_image),
                        audioLabel = context.getString(R.string.llama_note_transcript_audio)
                    ),
                    type = NoteType.MANUAL,
                    sourceFile = context.getString(R.string.notes_import_source_native_chat),
                    isLlmWhitelisted = false
                )
                database.noteDao().insert(note)
                Toast.makeText(context, context.getString(R.string.llama_save_chat_as_note_success), Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(
                    context,
                    context.getString(
                        R.string.llama_save_chat_as_note_failed,
                        e.message ?: context.getString(R.string.error_generic)
                    ),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    var showParams by remember { mutableStateOf(false) }
    var attachedImagePath by remember { mutableStateOf<String?>(null) }
    var attachedAudioPath by remember { mutableStateOf<String?>(null) }
    var isExtractingDocument by remember { mutableStateOf(false) }
    var showAttachmentMenu by remember { mutableStateOf(false) }
    var isRecording by remember { mutableStateOf(false) }
    var recordingSeconds by remember { mutableIntStateOf(0) }
    var mediaRecorder by remember { mutableStateOf<MediaRecorder?>(null) }
    var recordingFile by remember { mutableStateOf<File?>(null) }
    var pendingMicStart by remember { mutableStateOf(false) }
    var imagePreviewPath by remember { mutableStateOf<String?>(null) }

    val supportsVision = activeServer?.supportsVision == true
    val whisperFallbackAvailable = activeServer?.whisperModelPath?.isNotBlank() == true || whisperModels.isNotEmpty()
    val supportsAudioInput = activeServer?.supportsDirectAudioInput() == true ||
        (activeServer != null && whisperFallbackAvailable)
    val generationStateSnapshot = generationState
    val activeGenerationState = generationStateSnapshot as? LlamaClientService.GenerationState.Generating
    val streamingGenerationState = activeGenerationState?.takeIf {
        it.chatId == chatId && !it.isTranscribingAudio
    }
    val completedGenerationState = (generationStateSnapshot as? LlamaClientService.GenerationState.Completed)
        ?.takeIf { it.chatId == chatId }
    val isGeneratingAnyChat = generationStateSnapshot is LlamaClientService.GenerationState.Generating
    val isCurrentChatGenerating = activeGenerationState?.chatId == chatId
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
    val canContinueChat = !isGeneratingAnyChat && messages.lastOrNull()?.role == "assistant"
    val activeServerSubtitle = remember(activeServer, context) {
        activeServer?.let { server ->
            buildList {
                add(server.name)
                add(
                    if (server.isOllamaEngine()) {
                        context.getString(R.string.llama_engine_ollama)
                    } else {
                        context.getString(R.string.llama_engine_llama_server)
                    }
                )
                server.modelName?.takeIf { it.isNotBlank() }?.let(::add)
            }.joinToString(separator = " · ")
        } ?: context.getString(R.string.llama_no_servers)
    }

    // Parameter States
    var temperature by remember(currentChat?.apiParams) {
        mutableFloatStateOf(parseParam(currentChat?.apiParams, "temperature", 0.8f))
    }
    var topP by remember(currentChat?.apiParams) {
        mutableFloatStateOf(parseParam(currentChat?.apiParams, "top_p", 0.95f))
    }
    var topK by remember(currentChat?.apiParams) {
        mutableFloatStateOf(parseParam(currentChat?.apiParams, "top_k", 40f))
    }
    var minP by remember(currentChat?.apiParams) {
        mutableFloatStateOf(parseParam(currentChat?.apiParams, "min_p", 0.05f))
    }
    var repPen by remember(currentChat?.apiParams) {
        mutableFloatStateOf(parseParam(currentChat?.apiParams, "repeat_penalty", 1.1f))
    }
    var enableThinking by remember(currentChat?.apiParams) {
        mutableStateOf(parseParam(currentChat?.apiParams, "enable_thinking", true))
    }
    var liteRtMaxTokens by remember(currentChat?.id, currentChat?.contextSize) {
        mutableIntStateOf((currentChat?.contextSize ?: 4000).coerceIn(2000, 4000))
    }
    var liteRtSystemPrompt by remember(currentChat?.id, currentChat?.systemPrompt) {
        mutableStateOf(currentChat?.systemPrompt.orEmpty())
    }
    var liteRtAccelerator by remember(activeServer?.id, activeServer?.liteRtBackend) {
        mutableStateOf(
            if (normalizeLiteRtBackend(activeServer?.liteRtBackend) == LITERT_BACKEND_CPU) {
                LITERT_BACKEND_CPU
            } else {
                LITERT_BACKEND_GPU
            }
        )
    }
    var toolsEnabled by remember(currentChat?.apiParams) {
        mutableStateOf(parseParam(currentChat?.apiParams, NativeChatToolConfig.KEY_TOOLS_ENABLED, false))
    }
    var webSearchEnabled by remember(currentChat?.apiParams) {
        mutableStateOf(parseParam(currentChat?.apiParams, NativeChatToolConfig.KEY_WEB_SEARCH_ENABLED, false))
    }
    var webSearchMaxPages by remember(currentChat?.apiParams) {
        mutableIntStateOf(parseParam(currentChat?.apiParams, NativeChatToolConfig.KEY_WEB_SEARCH_MAX_PAGES, NativeChatToolConfig.DEFAULT_SEARCH_PAGES))
    }
    var webSearchMaxChars by remember(currentChat?.apiParams) {
        mutableIntStateOf(parseParam(currentChat?.apiParams, NativeChatToolConfig.KEY_WEB_SEARCH_MAX_CHARS, NativeChatToolConfig.DEFAULT_PAGE_CHARS))
    }
    var kiwixSearchEnabled by remember(currentChat?.apiParams) {
        mutableStateOf(parseParam(currentChat?.apiParams, NativeChatToolConfig.KEY_KIWIX_SEARCH_ENABLED, false))
    }
    var kiwixServerUrl by remember(currentChat?.apiParams) {
        mutableStateOf(parseParam(currentChat?.apiParams, NativeChatToolConfig.KEY_KIWIX_SERVER_URL, NativeChatToolConfig.DEFAULT_KIWIX_URL))
    }
    var kiwixMaxPages by remember(currentChat?.apiParams) {
        mutableIntStateOf(parseParam(currentChat?.apiParams, NativeChatToolConfig.KEY_KIWIX_MAX_PAGES, NativeChatToolConfig.DEFAULT_SEARCH_PAGES))
    }
    var kiwixMaxChars by remember(currentChat?.apiParams) {
        mutableIntStateOf(parseParam(currentChat?.apiParams, NativeChatToolConfig.KEY_KIWIX_MAX_CHARS, NativeChatToolConfig.DEFAULT_PAGE_CHARS))
    }
    var fetchUrlEnabled by remember(currentChat?.apiParams) {
        mutableStateOf(parseParam(currentChat?.apiParams, NativeChatToolConfig.KEY_FETCH_URL_ENABLED, false))
    }
    var fetchUrlMaxChars by remember(currentChat?.apiParams) {
        mutableIntStateOf(parseParam(currentChat?.apiParams, NativeChatToolConfig.KEY_FETCH_URL_MAX_CHARS, NativeChatToolConfig.DEFAULT_FETCH_CHARS))
    }
    var deepResearchEnabled by remember(currentChat?.apiParams) {
        mutableStateOf(parseParam(currentChat?.apiParams, NativeChatToolConfig.KEY_DEEP_RESEARCH_ENABLED, false))
    }
    var deepResearchImportIntoSelectedKbEnabled by remember(currentChat?.apiParams) {
        mutableStateOf(parseParam(currentChat?.apiParams, NativeChatToolConfig.KEY_DEEP_RESEARCH_IMPORT_SELECTED_KB_ENABLED, false))
    }
    var deepResearchSourceLimit by remember(currentChat?.apiParams) {
        mutableIntStateOf(parseParam(currentChat?.apiParams, NativeChatToolConfig.KEY_DEEP_RESEARCH_SOURCE_LIMIT, NativeChatToolConfig.DEFAULT_DEEP_RESEARCH_SOURCE_LIMIT))
    }
    var dateTimeEnabled by remember(currentChat?.apiParams) {
        mutableStateOf(parseParam(currentChat?.apiParams, NativeChatToolConfig.KEY_DATETIME_ENABLED, true))
    }
    var calculatorEnabled by remember(currentChat?.apiParams) {
        mutableStateOf(parseParam(currentChat?.apiParams, NativeChatToolConfig.KEY_CALCULATOR_ENABLED, true))
    }
    var noteToolsEnabled by remember(currentChat?.apiParams) {
        mutableStateOf(parseParam(currentChat?.apiParams, NativeChatToolConfig.KEY_NOTE_TOOLS_ENABLED, false))
    }
    var todoToolsEnabled by remember(currentChat?.apiParams) {
        mutableStateOf(parseParam(currentChat?.apiParams, NativeChatToolConfig.KEY_TODO_TOOLS_ENABLED, false))
    }
    var calendarToolsEnabled by remember(currentChat?.apiParams) {
        mutableStateOf(parseParam(currentChat?.apiParams, NativeChatToolConfig.KEY_CALENDAR_TOOLS_ENABLED, false))
    }
    var alarmToolsEnabled by remember(currentChat?.apiParams) {
        mutableStateOf(parseParam(currentChat?.apiParams, NativeChatToolConfig.KEY_ALARM_TOOLS_ENABLED, false))
    }
    var knowledgeBaseEnabled by remember(currentChat?.apiParams) {
        mutableStateOf(parseParam(currentChat?.apiParams, NativeChatToolConfig.KEY_KNOWLEDGE_BASE_ENABLED, false))
    }
    var knowledgeBaseAutoContextEnabled by remember(currentChat?.apiParams) {
        mutableStateOf(parseParam(currentChat?.apiParams, NativeChatToolConfig.KEY_KNOWLEDGE_AUTO_CONTEXT_ENABLED, false))
    }
    var selectedKnowledgeBaseIds by remember(currentChat?.apiParams) {
        mutableStateOf(NativeChatToolConfig.fromApiParams(currentChat?.apiParams).selectedKnowledgeBaseIds)
    }
    var chatDocumentKnowledgeBaseId by remember(currentChat?.apiParams) {
        mutableStateOf(NativeChatToolConfig.fromApiParams(currentChat?.apiParams).chatDocumentKnowledgeBaseId)
    }
    val chatDocumentSources by remember(chatDocumentKnowledgeBaseId) {
        chatDocumentKnowledgeBaseId?.let { knowledgeBaseRepository.observeSources(it) }
            ?: flowOf(emptyList<KnowledgeSourceEntity>())
    }.collectAsState(initial = emptyList())
    var knowledgeBaseMaxResults by remember(currentChat?.apiParams) {
        mutableIntStateOf(parseParam(currentChat?.apiParams, NativeChatToolConfig.KEY_KNOWLEDGE_MAX_RESULTS, NativeChatToolConfig.DEFAULT_KB_RESULTS))
    }
    var imageGenerationEnabled by remember(currentChat?.apiParams) {
        mutableStateOf(parseParam(currentChat?.apiParams, NativeChatToolConfig.KEY_IMAGE_GENERATION_ENABLED, false))
    }
    var imageIterationEnabled by remember(currentChat?.apiParams) {
        mutableStateOf(parseParam(currentChat?.apiParams, NativeChatToolConfig.KEY_IMAGE_ITERATION_ENABLED, false))
    }
    var assistantTtsEnabled by remember(currentChat?.apiParams) {
        mutableStateOf(parseParam(currentChat?.apiParams, NativeChatToolConfig.KEY_ASSISTANT_TTS_ENABLED, false))
    }
    var assistantTtsLanguage by remember(currentChat?.apiParams) {
        mutableStateOf(NativeChatToolConfig.fromApiParams(currentChat?.apiParams).assistantTtsLanguage)
    }
    var assistantTtsVoiceName by remember(currentChat?.apiParams) {
        mutableStateOf(NativeChatToolConfig.fromApiParams(currentChat?.apiParams).assistantTtsVoiceName.orEmpty())
    }
    var maxToolRounds by remember(currentChat?.apiParams) {
        mutableIntStateOf(parseParam(currentChat?.apiParams, NativeChatToolConfig.KEY_MAX_TOOL_ROUNDS, NativeChatToolConfig.DEFAULT_TOOL_ROUNDS))
    }
    var imageToolModel by remember(currentChat?.apiParams) {
        mutableStateOf(NativeChatToolConfig.fromApiParams(currentChat?.apiParams).imageParams.model.orEmpty())
    }
    var imageToolWidth by remember(currentChat?.apiParams) {
        mutableIntStateOf(NativeChatToolConfig.fromApiParams(currentChat?.apiParams).imageParams.width)
    }
    var imageToolHeight by remember(currentChat?.apiParams) {
        mutableIntStateOf(NativeChatToolConfig.fromApiParams(currentChat?.apiParams).imageParams.height)
    }
    var imageToolSteps by remember(currentChat?.apiParams) {
        mutableIntStateOf(NativeChatToolConfig.fromApiParams(currentChat?.apiParams).imageParams.steps)
    }
    var imageToolCfg by remember(currentChat?.apiParams) {
        mutableFloatStateOf(NativeChatToolConfig.fromApiParams(currentChat?.apiParams).imageParams.cfgScale)
    }
    var imageToolSeed by remember(currentChat?.apiParams) {
        mutableStateOf(NativeChatToolConfig.fromApiParams(currentChat?.apiParams).imageParams.seed)
    }
    var imageToolNegativePrompt by remember(currentChat?.apiParams) {
        mutableStateOf(NativeChatToolConfig.fromApiParams(currentChat?.apiParams).imageParams.negativePrompt)
    }
    var imageToolBackend by remember(currentChat?.apiParams) {
        mutableStateOf(NativeChatToolConfig.fromApiParams(currentChat?.apiParams).imageParams.backend)
    }
    var imageToolRuntimeThreads by remember(currentChat?.apiParams) {
        mutableStateOf(NativeChatToolConfig.fromApiParams(currentChat?.apiParams).imageParams.runtimeThreads)
    }
    var imageToolGraphOpt by remember(currentChat?.apiParams) {
        mutableStateOf(NativeChatToolConfig.fromApiParams(currentChat?.apiParams).imageParams.graphOptimizationLevel)
    }
    var imageToolUnetBackend by remember(currentChat?.apiParams) {
        mutableStateOf(NativeChatToolConfig.fromApiParams(currentChat?.apiParams).imageParams.unetBackendOverride)
    }
    var imageToolVaeDecoderBackend by remember(currentChat?.apiParams) {
        mutableStateOf(NativeChatToolConfig.fromApiParams(currentChat?.apiParams).imageParams.vaeDecoderBackendOverride)
    }
    var imageToolVaeEncoderBackend by remember(currentChat?.apiParams) {
        mutableStateOf(NativeChatToolConfig.fromApiParams(currentChat?.apiParams).imageParams.vaeEncoderBackendOverride)
    }
    var imageToolIntraThreads by remember(currentChat?.apiParams) {
        mutableStateOf(NativeChatToolConfig.fromApiParams(currentChat?.apiParams).imageParams.intraOpThreads)
    }
    var imageToolInterThreads by remember(currentChat?.apiParams) {
        mutableStateOf(NativeChatToolConfig.fromApiParams(currentChat?.apiParams).imageParams.interOpThreads)
    }
    var imageToolExecutionMode by remember(currentChat?.apiParams) {
        mutableStateOf(NativeChatToolConfig.fromApiParams(currentChat?.apiParams).imageParams.executionMode)
    }
    var imageToolMemoryPattern by remember(currentChat?.apiParams) {
        mutableStateOf(NativeChatToolConfig.fromApiParams(currentChat?.apiParams).imageParams.memoryPatternOptimization)
    }
    var imageToolCpuArena by remember(currentChat?.apiParams) {
        mutableStateOf(NativeChatToolConfig.fromApiParams(currentChat?.apiParams).imageParams.cpuArenaAllocator)
    }
    var imageToolNnapiCpuDisabled by remember(currentChat?.apiParams) {
        mutableStateOf(NativeChatToolConfig.fromApiParams(currentChat?.apiParams).imageParams.nnapiCpuDisabled)
    }
    var imageToolNnapiFp16 by remember(currentChat?.apiParams) {
        mutableStateOf(NativeChatToolConfig.fromApiParams(currentChat?.apiParams).imageParams.nnapiUseFp16)
    }

    LaunchedEffect(nativeChatTtsVoiceOptions, assistantTtsVoiceName) {
        if (nativeChatTtsVoiceOptions.isNotEmpty() && assistantTtsVoiceName !in nativeChatTtsVoiceOptions) {
            assistantTtsVoiceName = nativeChatTtsVoiceOptions.first()
        }
    }

    LaunchedEffect(assistantTtsLanguage) {
        if (assistantTtsLanguage !in supertonicLanguageCodes) {
            assistantTtsLanguage = SUPERTONIC_DEFAULT_LANGUAGE
        }
    }

    val activeServerToolDefaults = remember(activeServer?.defaultApiParams) {
        NativeChatToolConfig.fromApiParams(activeServer?.defaultApiParams)
    }
    val activeServerIsLiteRt = activeServer?.isLiteRtEngine() == true
    val serverAllowsTools = activeServerToolDefaults.toolsEnabled
    val serverAllowsWebSearch = serverAllowsTools && activeServerToolDefaults.webSearchEnabled
    val serverAllowsKiwixSearch = serverAllowsTools && activeServerToolDefaults.kiwixSearchEnabled
    val serverAllowsFetchUrl = serverAllowsTools && activeServerToolDefaults.fetchUrlEnabled
    val serverAllowsDeepResearch = serverAllowsTools && activeServerToolDefaults.deepResearchEnabled
    val serverAllowsDeepResearchSelectedKbImport =
        serverAllowsDeepResearch && activeServerToolDefaults.deepResearchImportIntoSelectedKbEnabled
    val serverAllowsDateTime = serverAllowsTools && activeServerToolDefaults.dateTimeEnabled
    val serverAllowsCalculator = serverAllowsTools && activeServerToolDefaults.calculatorEnabled
    val serverAllowsNotes = serverAllowsTools && activeServerToolDefaults.noteToolsEnabled
    val serverAllowsTodoLists = serverAllowsTools && activeServerToolDefaults.todoToolsEnabled
    val serverAllowsKnowledgeBases = serverAllowsTools && activeServerToolDefaults.knowledgeBaseEnabled
    val serverAllowsKnowledgeAutoContext =
        serverAllowsKnowledgeBases && activeServerToolDefaults.knowledgeBaseAutoContextEnabled
    val serverAllowsCalendar = serverAllowsTools && activeServerToolDefaults.calendarToolsEnabled
    val serverAllowsAlarms = serverAllowsTools && activeServerToolDefaults.alarmToolsEnabled
    val serverAllowsImageGeneration = serverAllowsTools && activeServerToolDefaults.imageGenerationEnabled
    val serverAllowsImageIteration =
        serverAllowsImageGeneration && activeServerToolDefaults.imageIterationEnabled
    val chatDocumentForcesKnowledgeTools = chatDocumentKnowledgeBaseId != null && serverAllowsKnowledgeBases
    val chatToolsEnabledByUser = toolsEnabled && serverAllowsTools
    val effectiveToolsEnabled = chatToolsEnabledByUser || chatDocumentForcesKnowledgeTools
    val effectiveWebSearchEnabled = chatToolsEnabledByUser && webSearchEnabled && serverAllowsWebSearch
    val effectiveKiwixSearchEnabled = chatToolsEnabledByUser && kiwixSearchEnabled && serverAllowsKiwixSearch
    val effectiveFetchUrlEnabled = chatToolsEnabledByUser && fetchUrlEnabled && serverAllowsFetchUrl
    val effectiveDeepResearchEnabled = chatToolsEnabledByUser && deepResearchEnabled && serverAllowsDeepResearch
    val effectiveDeepResearchImportIntoSelectedKbEnabled =
        effectiveDeepResearchEnabled && deepResearchImportIntoSelectedKbEnabled && serverAllowsDeepResearchSelectedKbImport
    val effectiveDateTimeEnabled = chatToolsEnabledByUser && dateTimeEnabled && serverAllowsDateTime
    val effectiveCalculatorEnabled = chatToolsEnabledByUser && calculatorEnabled && serverAllowsCalculator
    val effectiveNoteToolsEnabled = chatToolsEnabledByUser && noteToolsEnabled && serverAllowsNotes
    val effectiveTodoToolsEnabled = chatToolsEnabledByUser && todoToolsEnabled && serverAllowsTodoLists
    val effectiveKnowledgeBaseEnabled = chatDocumentForcesKnowledgeTools || (chatToolsEnabledByUser && knowledgeBaseEnabled && serverAllowsKnowledgeBases)
    val effectiveKnowledgeBaseAutoContextEnabled =
        chatDocumentForcesKnowledgeTools || (chatToolsEnabledByUser && knowledgeBaseAutoContextEnabled && serverAllowsKnowledgeAutoContext)
    val effectiveCalendarToolsEnabled = chatToolsEnabledByUser && calendarToolsEnabled && serverAllowsCalendar
    val effectiveAlarmToolsEnabled = chatToolsEnabledByUser && alarmToolsEnabled && serverAllowsAlarms
    val effectiveImageGenerationEnabled = chatToolsEnabledByUser && imageGenerationEnabled && serverAllowsImageGeneration
    val effectiveImageIterationEnabled = chatToolsEnabledByUser && imageIterationEnabled && serverAllowsImageIteration

    fun saveParams(
        chatDocumentKnowledgeBaseIdOverride: Long? = chatDocumentKnowledgeBaseId,
        forceKnowledgeBaseTools: Boolean = false
    ) {
        val nextToolsEnabled = toolsEnabled || forceKnowledgeBaseTools
        val nextKnowledgeBaseEnabled = knowledgeBaseEnabled || forceKnowledgeBaseTools
        val nextKnowledgeBaseAutoContextEnabled = knowledgeBaseAutoContextEnabled || forceKnowledgeBaseTools
        val map = linkedMapOf<String, Any>(
            "temperature" to if (activeServerIsLiteRt) temperature.coerceIn(0f, 1f) else temperature,
            "top_p" to if (activeServerIsLiteRt) topP.coerceIn(0f, 0.95f) else topP,
            "top_k" to if (activeServerIsLiteRt) topK.toInt().coerceIn(5, 64) else topK.toInt()
        )
        if (activeServerIsLiteRt) {
            currentChat?.let { chat ->
                viewModel.updateChat(
                    chat = chat,
                    newTitle = chat.title,
                    newContextSize = liteRtMaxTokens.coerceIn(2000, 4000),
                    newSystemPrompt = liteRtSystemPrompt.trim().takeIf { it.isNotBlank() }
                )
            }
            activeServer?.takeIf { it.isLiteRtEngine() }?.let { server ->
                val nextBackend = if (liteRtAccelerator == LITERT_BACKEND_CPU) {
                    LITERT_BACKEND_CPU
                } else {
                    LITERT_BACKEND_GPU
                }
                if (normalizeLiteRtBackend(server.liteRtBackend) != nextBackend) {
                    val updated = server.copy(liteRtBackend = nextBackend)
                    activeServer = updated
                    scope.launch { repository.updateServer(updated) }
                }
            }
        } else {
            map["min_p"] = minP
            map["repeat_penalty"] = repPen
            map["enable_thinking"] = enableThinking
            map.putAll(
                NativeChatToolConfig(
                    toolsEnabled = nextToolsEnabled,
                    webSearchEnabled = webSearchEnabled,
                    webSearchMaxPages = webSearchMaxPages,
                    webSearchMaxChars = webSearchMaxChars,
                    kiwixSearchEnabled = kiwixSearchEnabled,
                    kiwixServerUrl = kiwixServerUrl,
                    kiwixMaxPages = kiwixMaxPages,
                    kiwixMaxChars = kiwixMaxChars,
                    fetchUrlEnabled = fetchUrlEnabled,
                    fetchUrlMaxChars = fetchUrlMaxChars,
                    deepResearchEnabled = deepResearchEnabled,
                    deepResearchImportIntoSelectedKbEnabled = deepResearchImportIntoSelectedKbEnabled,
                    deepResearchSourceLimit = deepResearchSourceLimit,
                    dateTimeEnabled = dateTimeEnabled,
                    calculatorEnabled = calculatorEnabled,
                    noteToolsEnabled = noteToolsEnabled,
                    todoToolsEnabled = todoToolsEnabled,
                    calendarToolsEnabled = calendarToolsEnabled,
                    alarmToolsEnabled = alarmToolsEnabled,
                    knowledgeBaseEnabled = nextKnowledgeBaseEnabled,
                    knowledgeBaseAutoContextEnabled = nextKnowledgeBaseAutoContextEnabled,
                    selectedKnowledgeBaseIds = selectedKnowledgeBaseIds,
                    chatDocumentKnowledgeBaseId = chatDocumentKnowledgeBaseIdOverride,
                    knowledgeBaseMaxResults = knowledgeBaseMaxResults,
                    imageGenerationEnabled = imageGenerationEnabled,
                    imageIterationEnabled = imageIterationEnabled,
                    assistantTtsEnabled = assistantTtsEnabled,
                    assistantTtsLanguage = assistantTtsLanguage,
                    assistantTtsVoiceName = assistantTtsVoiceName.takeIf { it.isNotBlank() },
                    imageParams = NativeChatImageToolParams(
                        model = imageToolModel.takeIf { it.isNotBlank() },
                        width = imageToolWidth,
                        height = imageToolHeight,
                        steps = imageToolSteps,
                        cfgScale = imageToolCfg,
                        seed = imageToolSeed,
                        negativePrompt = imageToolNegativePrompt,
                        backend = imageToolBackend,
                        runtimeThreads = imageToolRuntimeThreads,
                        graphOptimizationLevel = imageToolGraphOpt,
                        unetBackendOverride = imageToolUnetBackend,
                        vaeDecoderBackendOverride = imageToolVaeDecoderBackend,
                        vaeEncoderBackendOverride = imageToolVaeEncoderBackend,
                        intraOpThreads = imageToolIntraThreads,
                        interOpThreads = imageToolInterThreads,
                        executionMode = imageToolExecutionMode,
                        memoryPatternOptimization = imageToolMemoryPattern,
                        cpuArenaAllocator = imageToolCpuArena,
                        nnapiCpuDisabled = imageToolNnapiCpuDisabled,
                        nnapiUseFp16 = imageToolNnapiFp16
                    ),
                    maxToolRounds = maxToolRounds
                ).toParamMap()
            )
        }
        val json = Gson().toJson(map)
        viewModel.updateChatApiParams(chatId, json)
    }

    fun resetParamsFromChat() {
        val params = currentChat?.apiParams
        temperature = parseParam(params, "temperature", 0.8f)
        topP = parseParam(params, "top_p", 0.95f)
        topK = parseParam(params, "top_k", 40f)
        minP = parseParam(params, "min_p", 0.05f)
        repPen = parseParam(params, "repeat_penalty", 1.1f)
        enableThinking = parseParam(params, "enable_thinking", true)
        liteRtMaxTokens = (currentChat?.contextSize ?: 4000).coerceIn(2000, 4000)
        liteRtSystemPrompt = currentChat?.systemPrompt.orEmpty()
        liteRtAccelerator = if (normalizeLiteRtBackend(activeServer?.liteRtBackend) == LITERT_BACKEND_CPU) {
            LITERT_BACKEND_CPU
        } else {
            LITERT_BACKEND_GPU
        }
        toolsEnabled = parseParam(params, NativeChatToolConfig.KEY_TOOLS_ENABLED, false)
        webSearchEnabled = parseParam(params, NativeChatToolConfig.KEY_WEB_SEARCH_ENABLED, false)
        webSearchMaxPages = parseParam(params, NativeChatToolConfig.KEY_WEB_SEARCH_MAX_PAGES, NativeChatToolConfig.DEFAULT_SEARCH_PAGES)
        webSearchMaxChars = parseParam(params, NativeChatToolConfig.KEY_WEB_SEARCH_MAX_CHARS, NativeChatToolConfig.DEFAULT_PAGE_CHARS)
        kiwixSearchEnabled = parseParam(params, NativeChatToolConfig.KEY_KIWIX_SEARCH_ENABLED, false)
        kiwixServerUrl = parseParam(params, NativeChatToolConfig.KEY_KIWIX_SERVER_URL, NativeChatToolConfig.DEFAULT_KIWIX_URL)
        kiwixMaxPages = parseParam(params, NativeChatToolConfig.KEY_KIWIX_MAX_PAGES, NativeChatToolConfig.DEFAULT_SEARCH_PAGES)
        kiwixMaxChars = parseParam(params, NativeChatToolConfig.KEY_KIWIX_MAX_CHARS, NativeChatToolConfig.DEFAULT_PAGE_CHARS)
        fetchUrlEnabled = parseParam(params, NativeChatToolConfig.KEY_FETCH_URL_ENABLED, false)
        fetchUrlMaxChars = parseParam(params, NativeChatToolConfig.KEY_FETCH_URL_MAX_CHARS, NativeChatToolConfig.DEFAULT_FETCH_CHARS)
        deepResearchEnabled = parseParam(params, NativeChatToolConfig.KEY_DEEP_RESEARCH_ENABLED, false)
        deepResearchImportIntoSelectedKbEnabled = parseParam(params, NativeChatToolConfig.KEY_DEEP_RESEARCH_IMPORT_SELECTED_KB_ENABLED, false)
        deepResearchSourceLimit = parseParam(params, NativeChatToolConfig.KEY_DEEP_RESEARCH_SOURCE_LIMIT, NativeChatToolConfig.DEFAULT_DEEP_RESEARCH_SOURCE_LIMIT)
        dateTimeEnabled = parseParam(params, NativeChatToolConfig.KEY_DATETIME_ENABLED, true)
        calculatorEnabled = parseParam(params, NativeChatToolConfig.KEY_CALCULATOR_ENABLED, true)
        noteToolsEnabled = parseParam(params, NativeChatToolConfig.KEY_NOTE_TOOLS_ENABLED, false)
        todoToolsEnabled = parseParam(params, NativeChatToolConfig.KEY_TODO_TOOLS_ENABLED, false)
        calendarToolsEnabled = parseParam(params, NativeChatToolConfig.KEY_CALENDAR_TOOLS_ENABLED, false)
        alarmToolsEnabled = parseParam(params, NativeChatToolConfig.KEY_ALARM_TOOLS_ENABLED, false)
        knowledgeBaseEnabled = parseParam(params, NativeChatToolConfig.KEY_KNOWLEDGE_BASE_ENABLED, false)
        knowledgeBaseAutoContextEnabled = parseParam(params, NativeChatToolConfig.KEY_KNOWLEDGE_AUTO_CONTEXT_ENABLED, false)
        selectedKnowledgeBaseIds = NativeChatToolConfig.fromApiParams(params).selectedKnowledgeBaseIds
        chatDocumentKnowledgeBaseId = NativeChatToolConfig.fromApiParams(params).chatDocumentKnowledgeBaseId
        knowledgeBaseMaxResults = parseParam(params, NativeChatToolConfig.KEY_KNOWLEDGE_MAX_RESULTS, NativeChatToolConfig.DEFAULT_KB_RESULTS)
        imageGenerationEnabled = parseParam(params, NativeChatToolConfig.KEY_IMAGE_GENERATION_ENABLED, false)
        imageIterationEnabled = parseParam(params, NativeChatToolConfig.KEY_IMAGE_ITERATION_ENABLED, false)
        assistantTtsEnabled = parseParam(params, NativeChatToolConfig.KEY_ASSISTANT_TTS_ENABLED, false)
        assistantTtsLanguage = NativeChatToolConfig.fromApiParams(params).assistantTtsLanguage
        assistantTtsVoiceName = NativeChatToolConfig.fromApiParams(params).assistantTtsVoiceName.orEmpty()
        maxToolRounds = parseParam(params, NativeChatToolConfig.KEY_MAX_TOOL_ROUNDS, NativeChatToolConfig.DEFAULT_TOOL_ROUNDS)
        val imageParams = NativeChatToolConfig.fromApiParams(params).imageParams
        imageToolModel = imageParams.model.orEmpty()
        imageToolWidth = imageParams.width
        imageToolHeight = imageParams.height
        imageToolSteps = imageParams.steps
        imageToolCfg = imageParams.cfgScale
        imageToolSeed = imageParams.seed
        imageToolNegativePrompt = imageParams.negativePrompt
        imageToolBackend = imageParams.backend
        imageToolRuntimeThreads = imageParams.runtimeThreads
        imageToolGraphOpt = imageParams.graphOptimizationLevel
        imageToolUnetBackend = imageParams.unetBackendOverride
        imageToolVaeDecoderBackend = imageParams.vaeDecoderBackendOverride
        imageToolVaeEncoderBackend = imageParams.vaeEncoderBackendOverride
        imageToolIntraThreads = imageParams.intraOpThreads
        imageToolInterThreads = imageParams.interOpThreads
        imageToolExecutionMode = imageParams.executionMode
        imageToolMemoryPattern = imageParams.memoryPatternOptimization
        imageToolCpuArena = imageParams.cpuArenaAllocator
        imageToolNnapiCpuDisabled = imageParams.nnapiCpuDisabled
        imageToolNnapiFp16 = imageParams.nnapiUseFp16
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

    fun retryUserMessage(message: LlamaMessageEntity) {
        val serverId = activeServer?.id
        if (serverId == null) {
            Toast.makeText(
                context,
                context.getString(R.string.llama_no_server_selected),
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        scope.launch {
            viewModel.deleteMessagesAfter(chatId, message.timestamp, message.id)
            LlamaClientService.resetStateIfIdle()
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
            Toast.makeText(
                context,
                context.getString(R.string.llama_no_server_selected),
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        val resendContent = stripEmbeddedAudioTranscript(message.content)
        scope.launch {
            viewModel.deleteMessagesAfter(chatId, message.timestamp, message.id)
            viewModel.deleteMessageNow(message)
            LlamaClientService.resetStateIfIdle()
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
            }
            context.startForegroundService(intent)
        }
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
                            context.getString(R.string.llama_attach_media_failed),
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
                                context.getString(R.string.kb_upload_needs_embedding),
                                Toast.LENGTH_LONG
                            ).show()
                            return@launch
                        }
                        val knowledgeBaseId = chatDocumentKnowledgeBaseId
                            ?: knowledgeBaseRepository.ensureChatDocumentKnowledgeBase(
                                chatId = chatId,
                                chatTitle = currentChat?.title.orEmpty()
                        )
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
                        assistantTtsEnabled = false
                        saveParams(
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
                                    context.getString(
                                        R.string.llama_document_queue_failed,
                                        error.message ?: context.getString(R.string.error_generic)
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
                                context.getString(R.string.llama_document_queued_for_chat, queuedCount),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    } catch (e: Exception) {
                        Toast.makeText(
                            context,
                            context.getString(
                                R.string.llama_document_queue_failed,
                                e.message ?: context.getString(R.string.error_generic)
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
                    context.getString(R.string.llama_record_permission_denied),
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
                        context.getString(R.string.llama_attach_media_failed),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
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
    }

    // Determine Active Server
    LaunchedEffect(activeServerId) {
        if (activeServerId == -1L) {
            // Find last used
            val last = checkDao.getLastUsedServer()
            if (last != null) {
                activeServerId = last.id
                activeServer = last
            } else {
                // No server found
                activeServer = null
            }
        } else {
             activeServer = checkDao.getServerById(activeServerId)
        }
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

    // Smart auto-scroll: only scroll when user is at (or near) bottom
    // We use a stable flag that the user can "break out of" by scrolling up
    var userWantsAutoScroll by remember { mutableStateOf(true) }

    // Detect user manual scroll: if user scrolls up, disable auto-scroll
    // If user scrolls back to bottom, re-enable it
    LaunchedEffect(listState) {
        snapshotFlow {
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            val total = listState.layoutInfo.totalItemsCount
            total == 0 || lastVisible >= total - 2
        }.collect { atBottom ->
            userWantsAutoScroll = atBottom
        }
    }

    // Only auto-scroll when new content arrives AND user hasn't scrolled away
    LaunchedEffect(messages.size) {
        if (userWantsAutoScroll && listState.layoutInfo.totalItemsCount > 0) {
            listState.animateScrollToItem(listState.layoutInfo.totalItemsCount - 1)
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0.dp, 0.dp, 0.dp, 0.dp),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = currentChat?.title ?: stringResource(R.string.llama_client_title),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = activeServerSubtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    // Search toggle
                    IconButton(onClick = {
                        isSearching = !isSearching
                        if (!isSearching) searchQuery = ""
                    }) {
                        Icon(
                            if (isSearching) Icons.Default.Close else Icons.Default.Search,
                            contentDescription = stringResource(R.string.llama_search_chat)
                        )
                    }
                    IconButton(onClick = { showParams = !showParams }) {
                        Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.llama_parameters))
                    }
                    // Overflow menu
                    Box {
                        IconButton(onClick = { showOverflowMenu = true }) {
                            Icon(
                                Icons.Default.MoreVert,
                                contentDescription = stringResource(R.string.llama_chat_actions)
                            )
                        }
                        DropdownMenu(
                            expanded = showOverflowMenu,
                            onDismissRequest = { showOverflowMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.llama_clear_chat)) },
                                enabled = messages.isNotEmpty() && !isGeneratingAnyChat,
                                leadingIcon = {
                                    Icon(
                                        Icons.Default.Delete,
                                        contentDescription = null
                                    )
                                },
                                onClick = {
                                    showOverflowMenu = false
                                    showClearChatConfirm = true
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.llama_export_chat)) },
                                onClick = {
                                    showOverflowMenu = false
                                    val fileName = (currentChat?.title ?: "chat") + ".json"
                                    exportLauncher.launch(fileName)
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.llama_save_chat_as_note)) },
                                onClick = {
                                    showOverflowMenu = false
                                    saveCurrentChatAsNote()
                                }
                            )
                        }
                    }
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
            // Parameters Panel
            AnimatedVisibility(visible = showParams) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 520.dp)
                            .verticalScroll(rememberScrollState())
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = if (activeServerIsLiteRt) {
                                stringResource(R.string.litert_gallery_model_configs)
                            } else {
                                stringResource(R.string.llama_parameters)
                            },
                            style = MaterialTheme.typography.titleSmall
                        )

                        if (activeServerIsLiteRt) {
                            LlamaToolNumberRow(
                                label = stringResource(R.string.litert_gallery_max_tokens),
                                value = liteRtMaxTokens,
                                onValueChange = { liteRtMaxTokens = it },
                                range = 2000..4000
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = stringResource(R.string.litert_gallery_temperature, "%.2f".format(temperature.coerceIn(0f, 1f))),
                                    modifier = Modifier.width(120.dp),
                                    style = MaterialTheme.typography.bodySmall
                                )
                                Slider(
                                    value = temperature.coerceIn(0f, 1f),
                                    onValueChange = { temperature = it.coerceIn(0f, 1f) },
                                    valueRange = 0f..1f,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = stringResource(R.string.litert_gallery_top_p, "%.2f".format(topP.coerceIn(0f, 0.95f))),
                                    modifier = Modifier.width(120.dp),
                                    style = MaterialTheme.typography.bodySmall
                                )
                                Slider(
                                    value = topP.coerceIn(0f, 0.95f),
                                    onValueChange = { topP = it.coerceIn(0f, 0.95f) },
                                    valueRange = 0f..0.95f,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = stringResource(R.string.litert_gallery_top_k, topK.toInt().coerceIn(5, 64)),
                                    modifier = Modifier.width(120.dp),
                                    style = MaterialTheme.typography.bodySmall
                                )
                                Slider(
                                    value = topK.coerceIn(5f, 64f),
                                    onValueChange = { topK = it.coerceIn(5f, 64f) },
                                    valueRange = 5f..64f,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            Text(
                                text = stringResource(R.string.litert_gallery_accelerator),
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Bold
                            )
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                listOf(
                                    LITERT_BACKEND_GPU to stringResource(R.string.litert_backend_gpu),
                                    LITERT_BACKEND_CPU to stringResource(R.string.general_acceleration_mode_cpu)
                                ).forEach { (backend, label) ->
                                    FilterChip(
                                        selected = liteRtAccelerator == backend,
                                        onClick = { liteRtAccelerator = backend },
                                        label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                                    )
                                }
                            }
                            OutlinedTextField(
                                value = liteRtSystemPrompt,
                                onValueChange = { liteRtSystemPrompt = it },
                                label = { Text(stringResource(R.string.llama_system_prompt)) },
                                placeholder = { Text(stringResource(R.string.llama_system_prompt_placeholder)) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 96.dp),
                                minLines = 3
                            )
                        } else {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Temp (${"%.1f".format(temperature)})", modifier = Modifier.width(80.dp), style = MaterialTheme.typography.bodySmall)
                                Slider(value = temperature, onValueChange = { temperature = it }, valueRange = 0f..2f, modifier = Modifier.weight(1f))
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Top P (${"%.2f".format(topP)})", modifier = Modifier.width(80.dp), style = MaterialTheme.typography.bodySmall)
                                Slider(value = topP, onValueChange = { topP = it }, valueRange = 0f..1f, modifier = Modifier.weight(1f))
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Top K (${topK.toInt()})", modifier = Modifier.width(80.dp), style = MaterialTheme.typography.bodySmall)
                                Slider(value = topK, onValueChange = { topK = it }, valueRange = 1f..100f, modifier = Modifier.weight(1f))
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Min P (${"%.2f".format(minP)})", modifier = Modifier.width(80.dp), style = MaterialTheme.typography.bodySmall)
                                Slider(value = minP, onValueChange = { minP = it }, valueRange = 0f..1f, modifier = Modifier.weight(1f))
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Rep Pen (${"%.2f".format(repPen)})", modifier = Modifier.width(80.dp), style = MaterialTheme.typography.bodySmall)
                                Slider(value = repPen, onValueChange = { repPen = it }, valueRange = 1f..2f, modifier = Modifier.weight(1f))
                            }

                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(stringResource(R.string.llama_thinking_process), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                                Switch(checked = enableThinking, onCheckedChange = { enableThinking = it })
                            }

                            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                            Text(stringResource(R.string.llama_voice_replies_title), style = MaterialTheme.typography.titleSmall)
                        LlamaToolToggleRow(
                            label = stringResource(R.string.llama_tool_assistant_tts),
                            description = stringResource(R.string.llama_tool_assistant_tts_desc),
                            checked = assistantTtsEnabled,
                            enabled = true,
                            onCheckedChange = { assistantTtsEnabled = it }
                        )
                        if (assistantTtsEnabled) {
                            if (onnxTtsModels.isEmpty()) {
                                Text(
                                    text = stringResource(R.string.llama_tool_assistant_tts_no_model),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                            LlamaStringDropdown(
                                label = stringResource(R.string.llama_tool_assistant_tts_language),
                                selected = assistantTtsLanguage,
                                values = supertonicLanguageCodes,
                                onSelected = { assistantTtsLanguage = it },
                                enabled = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            LlamaStringDropdown(
                                label = stringResource(R.string.llama_tool_assistant_tts_voice),
                                selected = assistantTtsVoiceName.ifBlank { nativeChatTtsVoiceOptions.firstOrNull().orEmpty() },
                                values = nativeChatTtsVoiceOptions,
                                onSelected = { assistantTtsVoiceName = it },
                                enabled = nativeChatTtsVoiceOptions.isNotEmpty(),
                                modifier = Modifier.fillMaxWidth()
                            )
                            if (nativeChatTtsVoiceOptions.isEmpty() && onnxTtsModels.isNotEmpty()) {
                                Text(
                                    text = stringResource(R.string.llama_tool_assistant_tts_no_voices),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                        Text(stringResource(R.string.llama_tools_title), style = MaterialTheme.typography.titleSmall)
                        LlamaToolToggleRow(
                            label = stringResource(R.string.llama_tools_enable),
                            description = stringResource(R.string.llama_tools_enable_desc),
                            checked = effectiveToolsEnabled,
                            enabled = serverAllowsTools,
                            onCheckedChange = { toolsEnabled = it }
                        )
                        if (activeServerIsLiteRt) {
                            Text(
                                text = stringResource(R.string.litert_tools_unavailable_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        if (effectiveToolsEnabled) {
                            LlamaToolToggleRow(
                                label = stringResource(R.string.llama_tool_web_search),
                                description = stringResource(R.string.llama_tool_web_search_desc),
                                checked = effectiveWebSearchEnabled,
                                enabled = serverAllowsWebSearch,
                                onCheckedChange = { webSearchEnabled = it }
                            )
                            if (effectiveWebSearchEnabled) {
                                LlamaToolNumberRow(
                                    label = stringResource(R.string.llama_tool_pages),
                                    value = webSearchMaxPages,
                                    onValueChange = { webSearchMaxPages = it },
                                    range = NativeChatToolConfig.MIN_SEARCH_PAGES..NativeChatToolConfig.MAX_SEARCH_PAGES
                                )
                                LlamaToolNumberRow(
                                    label = stringResource(R.string.llama_tool_chars_per_page),
                                    value = webSearchMaxChars,
                                    onValueChange = { webSearchMaxChars = it },
                                    range = NativeChatToolConfig.MIN_PAGE_CHARS..NativeChatToolConfig.MAX_PAGE_CHARS
                                )
                            }

                            LlamaToolToggleRow(
                                label = stringResource(R.string.llama_tool_kiwix_search),
                                description = stringResource(R.string.llama_tool_kiwix_search_desc),
                                checked = effectiveKiwixSearchEnabled,
                                enabled = serverAllowsKiwixSearch,
                                onCheckedChange = { kiwixSearchEnabled = it }
                            )
                            if (effectiveKiwixSearchEnabled) {
                                OutlinedTextField(
                                    value = kiwixServerUrl,
                                    onValueChange = { kiwixServerUrl = it },
                                    label = { Text(stringResource(R.string.llama_tool_kiwix_url)) },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true
                                )
                                LlamaToolNumberRow(
                                    label = stringResource(R.string.llama_tool_pages),
                                    value = kiwixMaxPages,
                                    onValueChange = { kiwixMaxPages = it },
                                    range = NativeChatToolConfig.MIN_SEARCH_PAGES..NativeChatToolConfig.MAX_SEARCH_PAGES
                                )
                                LlamaToolNumberRow(
                                    label = stringResource(R.string.llama_tool_chars_per_page),
                                    value = kiwixMaxChars,
                                    onValueChange = { kiwixMaxChars = it },
                                    range = NativeChatToolConfig.MIN_PAGE_CHARS..NativeChatToolConfig.MAX_PAGE_CHARS
                                )
                            }

                            LlamaToolToggleRow(
                                label = stringResource(R.string.llama_tool_fetch_url),
                                description = stringResource(R.string.llama_tool_fetch_url_desc),
                                checked = effectiveFetchUrlEnabled,
                                enabled = serverAllowsFetchUrl,
                                onCheckedChange = { fetchUrlEnabled = it }
                            )
                            if (effectiveFetchUrlEnabled) {
                                LlamaToolNumberRow(
                                    label = stringResource(R.string.llama_tool_max_chars),
                                    value = fetchUrlMaxChars,
                                    onValueChange = { fetchUrlMaxChars = it },
                                    range = NativeChatToolConfig.MIN_FETCH_CHARS..NativeChatToolConfig.MAX_FETCH_CHARS
                                )
                            }

                            LlamaToolToggleRow(
                                label = stringResource(R.string.llama_tool_deep_research),
                                description = stringResource(R.string.llama_tool_deep_research_desc),
                                checked = effectiveDeepResearchEnabled,
                                enabled = serverAllowsDeepResearch,
                                onCheckedChange = { deepResearchEnabled = it }
                            )
                            if (effectiveDeepResearchEnabled) {
                                LlamaToolToggleRow(
                                    label = stringResource(R.string.llama_tool_deep_research_import_selected_kb),
                                    description = stringResource(R.string.llama_tool_deep_research_import_selected_kb_desc),
                                    checked = effectiveDeepResearchImportIntoSelectedKbEnabled,
                                    enabled = serverAllowsDeepResearchSelectedKbImport,
                                    onCheckedChange = { deepResearchImportIntoSelectedKbEnabled = it }
                                )
                                LlamaToolNumberRow(
                                    label = stringResource(R.string.llama_tool_deep_research_source_limit),
                                    value = deepResearchSourceLimit,
                                    onValueChange = { deepResearchSourceLimit = it },
                                    range = NativeChatToolConfig.MIN_DEEP_RESEARCH_SOURCE_LIMIT..Int.MAX_VALUE
                                )
                                Text(
                                    text = stringResource(R.string.llama_tool_deep_research_source_limit_desc),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            LlamaToolToggleRow(
                                label = stringResource(R.string.llama_tool_datetime),
                                description = stringResource(R.string.llama_tool_datetime_desc),
                                checked = effectiveDateTimeEnabled,
                                enabled = serverAllowsDateTime,
                                onCheckedChange = { dateTimeEnabled = it }
                            )
                            LlamaToolToggleRow(
                                label = stringResource(R.string.llama_tool_calculator),
                                description = stringResource(R.string.llama_tool_calculator_desc),
                                checked = effectiveCalculatorEnabled,
                                enabled = serverAllowsCalculator,
                                onCheckedChange = { calculatorEnabled = it }
                            )
                            LlamaToolToggleRow(
                                label = stringResource(R.string.llama_tool_notes),
                                description = stringResource(R.string.llama_tool_notes_desc),
                                checked = effectiveNoteToolsEnabled,
                                enabled = serverAllowsNotes,
                                onCheckedChange = { noteToolsEnabled = it }
                            )
                            LlamaToolToggleRow(
                                label = stringResource(R.string.llama_tool_todo_lists),
                                description = stringResource(R.string.llama_tool_todo_lists_desc),
                                checked = effectiveTodoToolsEnabled,
                                enabled = serverAllowsTodoLists,
                                onCheckedChange = { todoToolsEnabled = it }
                            )
                            LlamaToolToggleRow(
                                label = stringResource(R.string.llama_tool_knowledge_bases),
                                description = stringResource(R.string.llama_tool_knowledge_bases_desc),
                                checked = effectiveKnowledgeBaseEnabled,
                                enabled = serverAllowsKnowledgeBases,
                                onCheckedChange = { knowledgeBaseEnabled = it }
                            )
                            if (effectiveKnowledgeBaseEnabled) {
                                LlamaToolToggleRow(
                                    label = stringResource(R.string.llama_tool_kb_auto_context),
                                    description = stringResource(R.string.llama_tool_kb_auto_context_desc),
                                    checked = effectiveKnowledgeBaseAutoContextEnabled,
                                    enabled = serverAllowsKnowledgeAutoContext,
                                    onCheckedChange = { knowledgeBaseAutoContextEnabled = it }
                                )
                                if (knowledgeBases.isEmpty()) {
                                    Text(
                                        text = stringResource(R.string.kb_no_bases_yet),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                } else {
                                    Text(
                                        text = stringResource(R.string.llama_tool_kb_selected_count, selectedKnowledgeBaseIds.size),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .heightIn(max = 180.dp)
                                            .verticalScroll(rememberScrollState()),
                                        verticalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        knowledgeBases.forEach { kb ->
                                            val selected = kb.id in selectedKnowledgeBaseIds
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .clip(RoundedCornerShape(8.dp))
                                                    .clickable {
                                                        selectedKnowledgeBaseIds = if (selected) {
                                                            selectedKnowledgeBaseIds - kb.id
                                                        } else {
                                                            (selectedKnowledgeBaseIds + kb.id).distinct()
                                                        }
                                                    }
                                                    .padding(horizontal = 8.dp, vertical = 4.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Checkbox(
                                                    checked = selected,
                                                    onCheckedChange = { checked ->
                                                        selectedKnowledgeBaseIds = if (checked) {
                                                            (selectedKnowledgeBaseIds + kb.id).distinct()
                                                        } else {
                                                            selectedKnowledgeBaseIds - kb.id
                                                        }
                                                    }
                                                )
                                                Text(
                                                    text = kb.name,
                                                    modifier = Modifier.weight(1f),
                                                    style = MaterialTheme.typography.bodySmall,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            }
                                        }
                                    }
                                }
                                LlamaToolNumberRow(
                                    label = stringResource(R.string.llama_tool_kb_max_results),
                                    value = knowledgeBaseMaxResults,
                                    onValueChange = { knowledgeBaseMaxResults = it },
                                    range = NativeChatToolConfig.MIN_KB_RESULTS..NativeChatToolConfig.MAX_KB_RESULTS
                                )
                                OutlinedButton(
                                    onClick = { navController.navigate(Screen.KnowledgeBase.route) },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Icon(Icons.Default.Settings, contentDescription = null)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(stringResource(R.string.kb_manage_action))
                                }
                            }
                            LlamaToolToggleRow(
                                label = stringResource(R.string.llama_tool_calendar),
                                description = stringResource(R.string.llama_tool_calendar_desc),
                                checked = effectiveCalendarToolsEnabled,
                                enabled = serverAllowsCalendar,
                                onCheckedChange = { calendarToolsEnabled = it }
                            )
                            LlamaToolToggleRow(
                                label = stringResource(R.string.llama_tool_alarms),
                                description = stringResource(R.string.llama_tool_alarms_desc),
                                checked = effectiveAlarmToolsEnabled,
                                enabled = serverAllowsAlarms,
                                onCheckedChange = { alarmToolsEnabled = it }
                            )
                            LlamaToolToggleRow(
                                label = stringResource(R.string.llama_tool_image_generation),
                                description = stringResource(R.string.llama_tool_image_generation_desc),
                                checked = effectiveImageGenerationEnabled,
                                enabled = serverAllowsImageGeneration,
                                onCheckedChange = { imageGenerationEnabled = it }
                            )
                            if (effectiveImageGenerationEnabled) {
                                LlamaToolToggleRow(
                                    label = stringResource(R.string.llama_tool_image_iteration),
                                    description = stringResource(R.string.llama_tool_image_iteration_desc),
                                    checked = effectiveImageIterationEnabled,
                                    enabled = serverAllowsImageIteration,
                                    onCheckedChange = { imageIterationEnabled = it }
                                )
                                LlamaNativeImageToolSettings(
                                    model = imageToolModel,
                                    availableModels = nativeChatImageModelOptions,
                                    onModelChange = { imageToolModel = it },
                                    width = imageToolWidth,
                                    onWidthChange = { imageToolWidth = it },
                                    height = imageToolHeight,
                                    onHeightChange = { imageToolHeight = it },
                                    steps = imageToolSteps,
                                    onStepsChange = { imageToolSteps = it },
                                    cfg = imageToolCfg,
                                    onCfgChange = { imageToolCfg = it },
                                    seed = imageToolSeed,
                                    onSeedChange = { imageToolSeed = it },
                                    negativePrompt = imageToolNegativePrompt,
                                    onNegativePromptChange = { imageToolNegativePrompt = it },
                                    backend = imageToolBackend,
                                    onBackendChange = { imageToolBackend = it },
                                    runtimeThreads = imageToolRuntimeThreads,
                                    onRuntimeThreadsChange = { imageToolRuntimeThreads = it },
                                    graphOptimizationLevel = imageToolGraphOpt,
                                    onGraphOptimizationLevelChange = { imageToolGraphOpt = it },
                                    unetBackendOverride = imageToolUnetBackend,
                                    onUnetBackendOverrideChange = { imageToolUnetBackend = it },
                                    vaeDecoderBackendOverride = imageToolVaeDecoderBackend,
                                    onVaeDecoderBackendOverrideChange = { imageToolVaeDecoderBackend = it },
                                    vaeEncoderBackendOverride = imageToolVaeEncoderBackend,
                                    onVaeEncoderBackendOverrideChange = { imageToolVaeEncoderBackend = it },
                                    intraOpThreads = imageToolIntraThreads,
                                    onIntraOpThreadsChange = { imageToolIntraThreads = it },
                                    interOpThreads = imageToolInterThreads,
                                    onInterOpThreadsChange = { imageToolInterThreads = it },
                                    executionMode = imageToolExecutionMode,
                                    onExecutionModeChange = { imageToolExecutionMode = it },
                                    memoryPatternOptimization = imageToolMemoryPattern,
                                    onMemoryPatternOptimizationChange = { imageToolMemoryPattern = it },
                                    cpuArenaAllocator = imageToolCpuArena,
                                    onCpuArenaAllocatorChange = { imageToolCpuArena = it },
                                    nnapiCpuDisabled = imageToolNnapiCpuDisabled,
                                    onNnapiCpuDisabledChange = { imageToolNnapiCpuDisabled = it },
                                    nnapiUseFp16 = imageToolNnapiFp16,
                                    onNnapiUseFp16Change = { imageToolNnapiFp16 = it }
                                )
                            }
                            LlamaToolNumberRow(
                                label = stringResource(R.string.llama_tool_max_rounds),
                                value = maxToolRounds,
                                onValueChange = { maxToolRounds = it },
                                range = NativeChatToolConfig.MIN_TOOL_ROUNDS..NativeChatToolConfig.MAX_TOOL_ROUNDS
                            )
                        }
                        }

                        Row(
                            horizontalArrangement = Arrangement.End,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            TextButton(onClick = {
                                resetParamsFromChat()
                                showParams = false
                            }) {
                                Text(stringResource(R.string.action_cancel))
                            }
                            TextButton(onClick = {
                                saveParams()
                                showParams = false
                            }) {
                                Text(stringResource(R.string.action_save))
                            }
                        }
                    }
                }
            }

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
                                    Icon(Icons.Default.Close, contentDescription = "Clear")
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
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(Icons.Default.ExpandLess, contentDescription = "Previous", modifier = Modifier.size(20.dp))
                        }
                        IconButton(
                            onClick = {
                                currentMatchIndex = if (currentMatchIndex < matchIndices.size - 1) currentMatchIndex + 1 else 0
                                scope.launch { listState.animateScrollToItem(matchIndices[currentMatchIndex]) }
                            },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(Icons.Default.ExpandMore, contentDescription = "Next", modifier = Modifier.size(20.dp))
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

            // Messages List + Scroll-to-bottom FAB
            Box(modifier = Modifier.weight(1f)) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(vertical = 8.dp)
                ) {
                    if (isExtractingDocument || chatDocumentSources.isNotEmpty()) {
                        item(key = "chat_document_knowledge_status") {
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                if (isExtractingDocument) {
                                    DocumentExtractionPending()
                                }
                                if (chatDocumentSources.isNotEmpty()) {
                                    ChatDocumentKnowledgeCard(sources = chatDocumentSources)
                                }
                            }
                        }
                    }

                    items(displayedMessages, key = { it.id }) { msg ->
                        LlamaMessageItem(
                            message = msg,
                            onRegenerate = {
                                val serverId = activeServer?.id
                                if (msg.role == "assistant" && serverId != null) {
                                    scope.launch {
                                        viewModel.deleteMessagesAfter(chatId, msg.timestamp, msg.id)
                                        viewModel.deleteMessageNow(msg)
                                        LlamaClientService.resetStateIfIdle()
                                        val intent = Intent(context, LlamaClientService::class.java).apply {
                                            action = LlamaClientService.ACTION_GENERATE
                                            putExtra(LlamaClientService.EXTRA_CHAT_ID, chatId)
                                            putExtra(LlamaClientService.EXTRA_SERVER_ID, serverId)
                                        }
                                        context.startForegroundService(intent)
                                    }
                                }
                            },
                            onEdit = { newContent ->
                                viewModel.updateMessage(msg.id, newContent)
                            },
                            onRetry = { messagePendingRetry = msg },
                            retryEnabled = !isGeneratingAnyChat &&
                                !msg.isError &&
                                activeServer != null,
                            onRetryTranscription = { retryFailedTranscription(msg) },
                            onDiscardFailedMessage = { viewModel.deleteMessage(msg) },
                            onDelete = { messagePendingDelete = msg },
                            autoPlayAssistantAudio = msg.role == "assistant" &&
                                assistantTtsEnabled &&
                                msg.timestamp >= openedAtMs &&
                                msg.audioPath?.takeIf { it.isNotBlank() } !in autoPlayedAssistantAudioPaths,
                            onAssistantAudioAutoPlayed = { path ->
                                if (path !in autoPlayedAssistantAudioPaths) {
                                    autoPlayedAssistantAudioPaths.add(path)
                                }
                            },
                            onKnowledgeLinkClick = onKnowledgeLinkClick
                        )
                    }

                    // Active Generation Indicator
                    streamingGenerationState?.let { genState ->
                        item {
                            Column(modifier = Modifier.padding(8.dp)) {
                                if (!genState.thinking.isNullOrBlank()) {
                                    ThinkingMessageContent(
                                        genState.thinking,
                                        genState.content,
                                        forceExpand = true,
                                        onKnowledgeLinkClick = onKnowledgeLinkClick
                                    )
                                } else if (genState.content.isNotBlank()) {
                                    MarkdownText(
                                        text = genState.content,
                                        textColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                        onLinkClick = onKnowledgeLinkClick
                                    )
                                }

                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(14.dp),
                                        strokeWidth = 2.dp
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = genState.statusText ?: if (genState.tokenCount > 0) {
                                            stringResource(
                                                R.string.llama_stream_stats,
                                                genState.tokenCount,
                                                genState.tokensPerSecond
                                            )
                                        } else {
                                            stringResource(R.string.llama_thinking)
                                        },
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.secondary,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f)
                                    )
                                    if (genState.toolEvents.isNotEmpty()) {
                                        TextButton(
                                            onClick = { showToolActivity = true },
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                                        ) {
                                            Icon(
                                                Icons.Default.Settings,
                                                contentDescription = null,
                                                modifier = Modifier.size(14.dp)
                                            )
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text(
                                                text = stringResource(R.string.llama_tool_activity_open),
                                                style = MaterialTheme.typography.labelSmall
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Completed stats
                    completedGenerationState?.let { compState ->
                        item {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "✓ ",
                                    color = MaterialTheme.colorScheme.primary,
                                    fontSize = 14.sp
                                )
                                Text(
                                    text = if (compState.promptTokens > 0) {
                                        stringResource(
                                            R.string.llama_completion_stats_with_prompt,
                                            compState.completionTokens,
                                            compState.tokensPerSecond,
                                            compState.promptTokens
                                        )
                                    } else {
                                        stringResource(
                                            R.string.llama_completion_stats,
                                            compState.completionTokens,
                                            compState.tokensPerSecond
                                        )
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.outline
                                )
                            }
                        }
                    }

                    // Continue button – shown when NOT generating and last message is from assistant
                    if (canContinueChat) {
                        item {
                            TextButton(
                                onClick = {
                                    val serverId = activeServer?.id
                                    if (serverId != null) {
                                        val intent = Intent(context, LlamaClientService::class.java).apply {
                                            action = LlamaClientService.ACTION_GENERATE
                                            putExtra(LlamaClientService.EXTRA_CHAT_ID, chatId)
                                            putExtra(LlamaClientService.EXTRA_SERVER_ID, serverId)
                                            // No EXTRA_USER_MESSAGE → service continues from existing history
                                        }
                                        context.startForegroundService(intent)
                                    }
                                },
                                modifier = Modifier.padding(start = 4.dp)
                            ) {
                                Icon(
                                    Icons.Default.PlayArrow,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(stringResource(R.string.llama_continue))
                            }
                        }
                    }
                }

            // Scroll-to-bottom FAB
            if (!userWantsAutoScroll) {
                SmallFloatingActionButton(
                    onClick = {
                        scope.launch {
                            if (listState.layoutInfo.totalItemsCount > 0) {
                                listState.animateScrollToItem(listState.layoutInfo.totalItemsCount - 1)
                            }
                            userWantsAutoScroll = true
                        }
                    },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(16.dp),
                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                ) {
                    Icon(Icons.Default.KeyboardArrowDown, contentDescription = stringResource(R.string.llama_scroll_to_bottom))
                }
            }
            } // end Box

            // Input Area
            Surface(
                tonalElevation = 2.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 8.dp, top = 6.dp, end = 8.dp, bottom = 0.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (isRecording) {
                        RecordingStrip(
                            seconds = recordingSeconds,
                            onStop = { stopCurrentRecording() }
                        )
                    }

                    if (attachedImagePath != null) {
                        PendingImageAttachment(
                            imagePath = attachedImagePath!!,
                            onPreview = { imagePreviewPath = attachedImagePath },
                            onRemove = { clearImageAttachment() }
                        )
                    }

                    if (attachedAudioPath != null) {
                        PendingAudioAttachment(
                            audioPath = attachedAudioPath!!,
                            onRemove = { clearAudioAttachment() }
                        )
                    }

                    val approxTokens = estimateNativeChatTextTokens(inputMessage)
                    if (approxTokens > 0) {
                        Text(
                            text = stringResource(R.string.llama_token_estimate, approxTokens),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.padding(start = 16.dp)
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.Bottom
                    ) {
                        Box(modifier = Modifier.padding(end = 4.dp)) {
                            IconButton(
                                onClick = { showAttachmentMenu = true },
                                enabled = !isGeneratingAnyChat
                            ) {
                                Icon(
                                    Icons.Default.Add,
                                    contentDescription = stringResource(R.string.llama_attachment_menu),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                            DropdownMenu(
                                expanded = showAttachmentMenu,
                                onDismissRequest = { showAttachmentMenu = false }
                            ) {
                                if (supportsVision) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.llama_attach_image)) },
                                        leadingIcon = {
                                            Icon(
                                                Icons.Default.Image,
                                                contentDescription = null
                                            )
                                        },
                                        onClick = {
                                            showAttachmentMenu = false
                                            photoPickerLauncher.launch(
                                                androidx.activity.result.PickVisualMediaRequest(
                                                    ActivityResultContracts.PickVisualMedia.ImageOnly
                                                )
                                            )
                                        }
                                    )
                                }
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.llama_attach_document)) },
                                    leadingIcon = {
                                        Icon(
                                            Icons.Default.AttachFile,
                                            contentDescription = null
                                        )
                                    },
                                    enabled = !isExtractingDocument,
                                    onClick = {
                                        showAttachmentMenu = false
                                        documentPickerLauncher.launch(nativeChatDocumentMimeTypes())
                                    }
                                )
                                if (supportsAudioInput && !isRecording) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.llama_record_audio)) },
                                        leadingIcon = {
                                            Icon(
                                                painter = painterResource(android.R.drawable.ic_btn_speak_now),
                                                contentDescription = null
                                            )
                                        },
                                        onClick = {
                                            showAttachmentMenu = false
                                            startCurrentRecording()
                                        }
                                    )
                                }
                            }
                        }

                        OutlinedTextField(
                            value = inputMessage,
                            onValueChange = { inputMessage = it },
                            modifier = Modifier
                                .weight(1f)
                                .padding(end = 8.dp),
                            placeholder = { Text(stringResource(R.string.chat_placeholder)) },
                            maxLines = 4,
                            shape = RoundedCornerShape(24.dp),
                            enabled = !isGeneratingAnyChat
                        )

                        if (isGeneratingAnyChat) {
                            IconButton(
                                onClick = {
                                    val intent = Intent(context, LlamaClientService::class.java).apply {
                                        action = LlamaClientService.ACTION_STOP
                                    }
                                    context.startForegroundService(intent)
                                },
                                modifier = Modifier
                                    .background(MaterialTheme.colorScheme.errorContainer, CircleShape)
                                    .size(48.dp)
                            ) {
                                Icon(
                                    painter = painterResource(android.R.drawable.ic_media_pause),
                                    contentDescription = stringResource(R.string.chat_stop_generating),
                                    tint = MaterialTheme.colorScheme.onErrorContainer
                                )
                            }
                        } else {
                            val canSend = inputMessage.isNotBlank() ||
                                attachedImagePath != null ||
                                attachedAudioPath != null
                            IconButton(
                                onClick = {
                                    if (!canSend) return@IconButton
                                    val serverId = activeServer?.id
                                    if (serverId != null) {
                                        val text = inputMessage
                                        val intentImagePath = attachedImagePath
                                        val intentAudioPath = attachedAudioPath
                                        inputMessage = ""
                                        attachedImagePath = null
                                        attachedAudioPath = null

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
                                        }
                                        context.startForegroundService(intent)
                                    } else {
                                        Toast.makeText(
                                            context,
                                            context.getString(R.string.llama_no_server_selected),
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                },
                                enabled = canSend,
                                modifier = Modifier
                                    .background(MaterialTheme.colorScheme.primary, CircleShape)
                                    .size(48.dp)
                            ) {
                                Icon(
                                    Icons.AutoMirrored.Filled.Send,
                                    contentDescription = stringResource(R.string.chat_send),
                                    tint = MaterialTheme.colorScheme.onPrimary
                                )
                            }
                        }
                    }

                }
            }
            Spacer(modifier = Modifier.height(effectiveImePadding))
            if (imagePreviewPath != null) {
                LlamaImagePreviewDialog(
                    imageFile = File(imagePreviewPath!!),
                    onDismiss = { imagePreviewPath = null }
                )
            }
            if (showToolActivity) {
                LlamaToolActivityDialog(
                    events = activeToolEvents,
                    onDismiss = { showToolActivity = false }
                )
            }
        }
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
                        val chatDocumentBaseToDelete = chatDocumentKnowledgeBaseId
                        viewModel.clearChatNow(chatId)
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
                            imageGenerationEnabled
                        saveParams(chatDocumentKnowledgeBaseIdOverride = null)
                        chatDocumentBaseToDelete?.let { baseId ->
                            runCatching { knowledgeBaseRepository.deleteKnowledgeBase(baseId) }
                        }
                        isSearching = false
                        searchQuery = ""
                        currentMatchIndex = 0
                        Toast.makeText(
                            context,
                            context.getString(R.string.llama_clear_chat_done),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                },
                onDismiss = { showClearChatConfirm = false }
            )
        }
        }
    }

@Composable
private fun RecordingStrip(
    seconds: Int,
    onStop: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Square,
                contentDescription = stringResource(R.string.llama_recording),
                tint = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = String.format(Locale.getDefault(), "%02d:%02d", seconds / 60, seconds % 60),
                color = MaterialTheme.colorScheme.onErrorContainer,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onStop) {
                Text(stringResource(R.string.action_stop))
            }
        }
    }
}

@Composable
private fun PendingImageAttachment(
    imagePath: String,
    onPreview: () -> Unit,
    onRemove: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = RoundedCornerShape(12.dp),
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
                    .size(56.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .clickable(onClick = onPreview)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.llama_image_attached), style = MaterialTheme.typography.bodyMedium)
                Text(
                    text = File(imagePath).name,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            IconButton(onClick = onRemove) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = stringResource(R.string.llama_remove_attachment)
                )
            }
        }
    }
}

@Composable
private fun PendingAudioAttachment(
    audioPath: String,
    onRemove: () -> Unit
) {
    AudioPlaybackRow(
        audioFile = File(audioPath),
        onRemove = onRemove
    )
}

@Composable
private fun DocumentExtractionPending() {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = stringResource(R.string.llama_document_extracting),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
        }
    }
}

@Composable
private fun ChatDocumentKnowledgeCard(sources: List<KnowledgeSourceEntity>) {
    val pendingStatuses = setOf(
        KnowledgeBaseSourceStatus.QUEUED,
        KnowledgeBaseSourceStatus.EXTRACTING,
        KnowledgeBaseSourceStatus.CHUNKING,
        KnowledgeBaseSourceStatus.EMBEDDING,
        KnowledgeBaseSourceStatus.INDEXING
    )
    val pending = sources.any { it.status in pendingStatuses }
    val errors = sources.count { it.status == KnowledgeBaseSourceStatus.ERROR }
    val totalChunks = sources.sumOf { maxOf(it.progressTotal, it.chunkCount) }
    val embeddedChunks = sources.sumOf { source ->
        maxOf(source.embeddedChunkCount, if (source.progressTotal > 0) source.progressDone else 0)
    }
    val progress = if (totalChunks > 0) {
        (embeddedChunks.toFloat() / totalChunks.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }

    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.AttachFile, contentDescription = null)
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.llama_chat_documents_title),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                    Text(
                        text = when {
                            errors > 0 -> stringResource(R.string.llama_chat_documents_error, errors)
                            pending -> stringResource(
                                R.string.llama_chat_documents_preparing,
                                sources.size,
                                embeddedChunks,
                                totalChunks
                            )
                            else -> stringResource(R.string.llama_chat_documents_ready, embeddedChunks)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }
            if (pending && totalChunks > 0) {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth()
                )
            }
            sources.take(3).forEach { source ->
                Text(
                    text = stringResource(
                        R.string.llama_chat_document_status,
                        source.title,
                        chatDocumentSourceStatusLabel(source.status)
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (source.status == KnowledgeBaseSourceStatus.ERROR) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSecondaryContainer
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (sources.size > 3) {
                Text(
                    text = stringResource(R.string.llama_chat_documents_more, sources.size - 3),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
        }
    }
}

@Composable
private fun chatDocumentSourceStatusLabel(status: String): String = when (status) {
    KnowledgeBaseSourceStatus.QUEUED -> stringResource(R.string.kb_status_queued)
    KnowledgeBaseSourceStatus.EXTRACTING -> stringResource(R.string.kb_status_extracting)
    KnowledgeBaseSourceStatus.CHUNKING -> stringResource(R.string.kb_status_chunking)
    KnowledgeBaseSourceStatus.EMBEDDING,
    KnowledgeBaseSourceStatus.INDEXING -> stringResource(R.string.kb_status_embedding)
    KnowledgeBaseSourceStatus.INDEXED -> stringResource(R.string.kb_status_indexed)
    KnowledgeBaseSourceStatus.STALE -> stringResource(R.string.kb_status_stale)
    KnowledgeBaseSourceStatus.ERROR -> stringResource(R.string.kb_status_error)
    else -> status
}

@Composable
private fun EmbeddedDocumentAttachment(document: EmbeddedDocumentText) {
    DocumentAttachmentSurface(
        name = document.name,
        text = document.text,
        onRemove = null
    )
}

@Composable
private fun DocumentAttachmentSurface(
    name: String,
    text: String,
    onRemove: (() -> Unit)?
) {
    var isExpanded by remember(name, text) { mutableStateOf(false) }

    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { isExpanded = !isExpanded }
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.AttachFile,
                    contentDescription = stringResource(R.string.llama_document_attached),
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.llama_document_attached),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                    Text(
                        text = stringResource(
                            R.string.llama_document_summary,
                            name,
                            estimateNativeChatTextTokens(text)
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Icon(
                    imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer
                )
                onRemove?.let { remove ->
                    IconButton(
                        onClick = remove,
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = stringResource(R.string.llama_remove_attachment)
                        )
                    }
                }
            }

            if (isExpanded) {
                Spacer(modifier = Modifier.height(8.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.llama_document_content),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
                Spacer(modifier = Modifier.height(6.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 220.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(
                        text = text,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.86f)
                    )
                }
            }
        }
    }
}

@Composable
private fun LlamaImagePreviewDialog(
    imageFile: File,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                AsyncImage(
                    model = imageFile,
                    contentDescription = stringResource(R.string.llama_image_attached),
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 200.dp, max = 480.dp)
                )
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = { shareLlamaChatImage(context, imageFile) }) {
                        Icon(
                            Icons.Default.Share,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(stringResource(R.string.action_share))
                    }
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.action_close))
                    }
                }
            }
        }
    }
}

private fun shareLlamaChatImage(context: Context, imageFile: File) {
    runCatching {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            imageFile
        )
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "image/*"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(
            Intent.createChooser(
                shareIntent,
                context.getString(R.string.imagegen_share_chooser)
            )
        )
    }.onFailure { error ->
        Toast.makeText(
            context,
            context.getString(
                R.string.onnx_image_gen_share_failed,
                error.message ?: context.getString(R.string.error_generic)
            ),
            Toast.LENGTH_SHORT
        ).show()
    }
}

@Composable
private fun AudioPlaybackRow(
    audioFile: File,
    onRemove: (() -> Unit)? = null,
    onPlaybackChanged: ((Boolean, MediaPlayer?) -> Unit)? = null,
    autoPlay: Boolean = false,
    onAutoPlayConsumed: (() -> Unit)? = null
) {
    var mediaPlayer by remember(audioFile.absolutePath) { mutableStateOf<MediaPlayer?>(null) }
    var isPlaying by remember(audioFile.absolutePath) { mutableStateOf(false) }

    fun startPlayback() {
        mediaPlayer?.release()
        val player = MediaPlayer().apply {
            setDataSource(audioFile.absolutePath)
            prepare()
            setOnCompletionListener {
                isPlaying = false
                onPlaybackChanged?.invoke(false, it)
                runCatching { it.release() }
                mediaPlayer = null
            }
            start()
        }
        mediaPlayer = player
        isPlaying = true
        onPlaybackChanged?.invoke(true, player)
    }

    DisposableEffect(audioFile.absolutePath) {
        onDispose {
            runCatching { mediaPlayer?.release() }
            mediaPlayer = null
            isPlaying = false
            onPlaybackChanged?.invoke(false, null)
        }
    }

    LaunchedEffect(autoPlay, audioFile.absolutePath) {
        if (autoPlay && !isPlaying) {
            runCatching { startPlayback() }
            onAutoPlayConsumed?.invoke()
        }
    }

    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = {
                    val current = mediaPlayer
                    if (current?.isPlaying == true) {
                        current.pause()
                        isPlaying = false
                        onPlaybackChanged?.invoke(false, current)
                    } else {
                        startPlayback()
                    }
                },
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = stringResource(R.string.llama_audio_attached),
                    tint = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.llama_audio_attached), style = MaterialTheme.typography.bodyMedium)
                Text(
                    text = audioFile.name,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            onRemove?.let {
                IconButton(onClick = it) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = stringResource(R.string.llama_remove_attachment)
                    )
                }
            }
        }
    }
}

private fun startLlamaRecording(
    context: Context,
    onRecorderReady: (MediaRecorder, File) -> Unit,
    onError: (String) -> Unit
) {
    try {
        val recordingDir = File(context.filesDir, "llama_chat_audio").apply { mkdirs() }
        val tempFile = File(recordingDir, "recording_in_progress.m4a")
        tempFile.delete()
        @Suppress("DEPRECATION")
        val recorder = MediaRecorder().apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioSamplingRate(44100)
            setAudioEncodingBitRate(128000)
            setOutputFile(tempFile.absolutePath)
            prepare()
            start()
        }
        onRecorderReady(recorder, tempFile)
    } catch (e: Exception) {
        onError(context.getString(R.string.llama_record_error, e.message ?: context.getString(R.string.error_generic)))
    }
}

private fun stopLlamaRecording(recorder: MediaRecorder?) {
    if (recorder == null) return
    runCatching { recorder.stop() }
    runCatching { recorder.release() }
}

private fun nativeChatDocumentMimeTypes(): Array<String> = arrayOf(
    "application/pdf",
    "text/*",
    "application/json",
    "application/xml",
    "text/markdown",
    "text/csv",
    "*/*"
)

private fun queryDocumentDisplayName(context: Context, uri: Uri): String {
    val fromCursor = runCatching {
        context.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null
        )?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
        }
    }.getOrNull()
    return fromCursor
        ?.takeIf { it.isNotBlank() }
        ?: uri.lastPathSegment
            ?.substringAfterLast('/')
            ?.takeIf { it.isNotBlank() }
        ?: "document"
}

private fun persistContentUriToAppPrivateFile(
    context: Context,
    uri: Uri,
    prefix: String,
    defaultExtension: String
): String? {
    val mimeType = context.contentResolver.getType(uri)
    val extension = mimeTypeToExtension(mimeType, defaultExtension)
    val mediaDir = File(context.filesDir, "llama_chat_media").apply { mkdirs() }
    val cacheFile = java.io.File(
        mediaDir,
        "${prefix}_${System.currentTimeMillis()}.$extension"
    )

    context.contentResolver.openInputStream(uri)?.use { input ->
        cacheFile.outputStream().use { output ->
            input.copyTo(output)
        }
    } ?: return null

    return cacheFile.absolutePath
}

private fun persistRecordedAudioToAppPrivateFile(
    context: Context,
    recordingFile: File
): String? {
    if (!recordingFile.exists()) return null
    val mediaDir = File(context.filesDir, "llama_chat_audio").apply { mkdirs() }
    val timestamp = SimpleDateFormat("yyyy-MM-dd_HHmmss", Locale.getDefault()).format(Date())
    val savedFile = File(mediaDir, "recording_$timestamp.m4a")
    recordingFile.copyTo(savedFile, overwrite = true)
    recordingFile.delete()
    return savedFile.absolutePath
}

private fun mimeTypeToExtension(mimeType: String?, defaultExtension: String): String {
    return when {
        mimeType.isNullOrBlank() -> defaultExtension
        mimeType.startsWith("audio/") -> when (mimeType) {
            "audio/wav" -> "wav"
            "audio/x-wav" -> "wav"
            "audio/mpeg" -> "mp3"
            "audio/mp4" -> "m4a"
            "audio/x-m4a" -> "m4a"
            "audio/ogg" -> "ogg"
            "audio/webm" -> "webm"
            "audio/aac" -> "aac"
            "audio/flac" -> "flac"
            "audio/3gpp" -> "3gp"
            "audio/3gpp2" -> "3gpp"
            else -> defaultExtension
        }
        mimeType.startsWith("image/") -> when (mimeType) {
            "image/png" -> "png"
            "image/webp" -> "webp"
            "image/bmp" -> "bmp"
            "image/gif" -> "gif"
            else -> defaultExtension
        }
        else -> defaultExtension
    }
}

private fun parseParam(jsonStr: String?, key: String, default: Float): Float {
    if (jsonStr.isNullOrBlank()) return default
    return try {
        val mapType = object : TypeToken<Map<String, Any>>() {}.type
        val map: Map<String, Any> = Gson().fromJson(jsonStr, mapType)
        (map[key] as? Number)?.toFloat() ?: default
    } catch (e: Exception) {
        default
    }
}

private fun parseParam(jsonStr: String?, key: String, default: Boolean): Boolean {
    if (jsonStr.isNullOrBlank()) return default
    return try {
        val mapType = object : TypeToken<Map<String, Any>>() {}.type
        val map: Map<String, Any> = Gson().fromJson(jsonStr, mapType)
        (map[key] as? Boolean) ?: default
    } catch (e: Exception) {
        default
    }
}

private fun parseParam(jsonStr: String?, key: String, default: Int): Int {
    if (jsonStr.isNullOrBlank()) return default
    return try {
        val mapType = object : TypeToken<Map<String, Any>>() {}.type
        val map: Map<String, Any> = Gson().fromJson(jsonStr, mapType)
        when (val value = map[key]) {
            is Number -> value.toInt()
            is String -> value.toIntOrNull() ?: default
            else -> default
        }
    } catch (e: Exception) {
        default
    }
}

private fun parseParam(jsonStr: String?, key: String, default: String): String {
    if (jsonStr.isNullOrBlank()) return default
    return try {
        val mapType = object : TypeToken<Map<String, Any>>() {}.type
        val map: Map<String, Any> = Gson().fromJson(jsonStr, mapType)
        (map[key] as? String)?.takeIf { it.isNotBlank() } ?: default
    } catch (e: Exception) {
        default
    }
}

@Composable
private fun LlamaToolToggleRow(
    label: String,
    description: String,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit
) {
    val labelColor = if (enabled) {
        MaterialTheme.colorScheme.onSurface
    } else {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    }
    val descriptionColor = if (enabled) {
        MaterialTheme.colorScheme.onSurfaceVariant
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                label,
                style = MaterialTheme.typography.bodyMedium,
                color = labelColor
            )
            Text(
                description,
                style = MaterialTheme.typography.labelSmall,
                color = descriptionColor,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        Switch(
            checked = checked,
            enabled = enabled,
            onCheckedChange = onCheckedChange
        )
    }
}

@Composable
private fun LlamaToolNumberRow(
    label: String,
    value: Int,
    onValueChange: (Int) -> Unit,
    range: IntRange
) {
    var text by remember { mutableStateOf(value.toString()) }
    var focused by remember { mutableStateOf(false) }

    LaunchedEffect(value, focused) {
        if (!focused && text.toIntOrNull() != value) {
            text = value.toString()
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall
        )
        OutlinedTextField(
            value = text,
            onValueChange = { raw ->
                val filtered = raw.filter { it.isDigit() }
                text = filtered
                filtered.toIntOrNull()
                    ?.takeIf { it in range }
                    ?.let(onValueChange)
            },
            modifier = Modifier
                .width(120.dp)
                .onFocusChanged { focusState ->
                    if (focused && !focusState.isFocused) {
                        val coerced = text.toIntOrNull()?.coerceIn(range) ?: value
                        text = coerced.toString()
                        onValueChange(coerced)
                    }
                    focused = focusState.isFocused
                },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodySmall,
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                keyboardType = androidx.compose.ui.text.input.KeyboardType.Number
            )
        )
    }
}

@Composable
private fun LlamaToolActivityDialog(
    events: List<LlamaClientService.ToolActivityEvent>,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.82f)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.llama_tool_activity_title),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = stringResource(R.string.action_close))
                    }
                }
                HorizontalDivider()
                if (events.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = stringResource(R.string.llama_tool_activity_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentPadding = PaddingValues(vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(events, key = { it.id }) { event ->
                            LlamaToolActivityRow(event)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LlamaToolActivityRow(event: LlamaClientService.ToolActivityEvent) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (!event.isComplete) {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    strokeWidth = 2.dp
                )
            } else {
                Text(
                    text = "✓",
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelMedium
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = event.status,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
        }
        event.title?.takeIf { it.isNotBlank() }?.let { title ->
            Text(
                text = title,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        event.url?.takeIf { it.isNotBlank() }?.let { url ->
            Text(
                text = url,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.secondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        event.outputPreview?.takeIf { it.isNotBlank() }?.let { output ->
            SelectionContainer {
                Text(
                    text = output,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 8,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        HorizontalDivider(modifier = Modifier.padding(top = 4.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LlamaNativeImageToolSettings(
    model: String,
    availableModels: List<String>,
    onModelChange: (String) -> Unit,
    width: Int,
    onWidthChange: (Int) -> Unit,
    height: Int,
    onHeightChange: (Int) -> Unit,
    steps: Int,
    onStepsChange: (Int) -> Unit,
    cfg: Float,
    onCfgChange: (Float) -> Unit,
    seed: String,
    onSeedChange: (String) -> Unit,
    negativePrompt: String,
    onNegativePromptChange: (String) -> Unit,
    backend: OnnxRuntimeBackend,
    onBackendChange: (OnnxRuntimeBackend) -> Unit,
    runtimeThreads: Int?,
    onRuntimeThreadsChange: (Int?) -> Unit,
    graphOptimizationLevel: OnnxGraphOptimizationLevel,
    onGraphOptimizationLevelChange: (OnnxGraphOptimizationLevel) -> Unit,
    unetBackendOverride: OnnxBackendOverride,
    onUnetBackendOverrideChange: (OnnxBackendOverride) -> Unit,
    vaeDecoderBackendOverride: OnnxBackendOverride,
    onVaeDecoderBackendOverrideChange: (OnnxBackendOverride) -> Unit,
    vaeEncoderBackendOverride: OnnxBackendOverride,
    onVaeEncoderBackendOverrideChange: (OnnxBackendOverride) -> Unit,
    intraOpThreads: Int?,
    onIntraOpThreadsChange: (Int?) -> Unit,
    interOpThreads: Int?,
    onInterOpThreadsChange: (Int?) -> Unit,
    executionMode: OnnxExecutionMode,
    onExecutionModeChange: (OnnxExecutionMode) -> Unit,
    memoryPatternOptimization: Boolean,
    onMemoryPatternOptimizationChange: (Boolean) -> Unit,
    cpuArenaAllocator: Boolean,
    onCpuArenaAllocatorChange: (Boolean) -> Unit,
    nnapiCpuDisabled: Boolean,
    onNnapiCpuDisabledChange: (Boolean) -> Unit,
    nnapiUseFp16: Boolean,
    onNnapiUseFp16Change: (Boolean) -> Unit
) {
    var modelExpanded by remember { mutableStateOf(false) }
    var settingsExpanded by remember { mutableStateOf(false) }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.65f)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { settingsExpanded = !settingsExpanded },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.native_chat_image_generation_settings_title),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        stringResource(
                            R.string.native_chat_image_generation_collapsed_summary,
                            width,
                            height,
                            steps,
                            cfg
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Icon(
                    imageVector = if (settingsExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = stringResource(R.string.native_chat_image_generation_settings_title),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            AnimatedVisibility(visible = settingsExpanded) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        stringResource(R.string.native_chat_image_generation_settings_desc),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    ExposedDropdownMenuBox(
                        expanded = modelExpanded,
                        onExpandedChange = { modelExpanded = it }
                    ) {
                        OutlinedTextField(
                            value = model,
                            onValueChange = onModelChange,
                            modifier = Modifier
                                .fillMaxWidth()
                                .menuAnchor(),
                            label = { Text(stringResource(R.string.agent_image_generation_model_label)) },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = modelExpanded) },
                            singleLine = true
                        )
                        ExposedDropdownMenu(
                            expanded = modelExpanded,
                            onDismissRequest = { modelExpanded = false }
                        ) {
                            availableModels.forEach { option ->
                                DropdownMenuItem(
                                    text = { Text(option) },
                                    onClick = {
                                        onModelChange(option)
                                        modelExpanded = false
                                    }
                                )
                            }
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LlamaImageToolNumberField(
                    value = width,
                    onValueChange = onWidthChange,
                    label = stringResource(R.string.onnx_image_gen_width_label),
                    modifier = Modifier.weight(1f)
                )
                LlamaImageToolNumberField(
                    value = height,
                    onValueChange = onHeightChange,
                    label = stringResource(R.string.onnx_image_gen_height_label),
                    modifier = Modifier.weight(1f)
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LlamaImageToolNumberField(
                    value = steps,
                    onValueChange = onStepsChange,
                    label = stringResource(R.string.onnx_image_gen_steps_label),
                    modifier = Modifier.weight(1f)
                )
                LlamaImageToolFloatField(
                    value = cfg,
                    onValueChange = onCfgChange,
                    label = stringResource(R.string.onnx_image_gen_cfg_label),
                    modifier = Modifier.weight(1f)
                )
            }

            OutlinedTextField(
                value = seed,
                onValueChange = onSeedChange,
                label = { Text(stringResource(R.string.onnx_image_gen_seed_label)) },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text(stringResource(R.string.onnx_image_gen_seed_placeholder)) },
                singleLine = true,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    keyboardType = androidx.compose.ui.text.input.KeyboardType.Number
                )
            )

            OutlinedTextField(
                value = negativePrompt,
                onValueChange = onNegativePromptChange,
                label = { Text(stringResource(R.string.native_chat_image_generation_negative_prompt_label)) },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
                maxLines = 4
            )

            Text(
                stringResource(R.string.native_chat_image_generation_runtime_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )

            LlamaImageToolEnumDropdown(
                label = stringResource(R.string.onnx_image_gen_backend_label),
                selected = backend,
                values = OnnxRuntimeBackend.entries,
                labelFor = {
                    when (it) {
                        OnnxRuntimeBackend.CPU -> stringResource(R.string.onnx_image_gen_backend_cpu)
                        OnnxRuntimeBackend.NNAPI -> stringResource(R.string.onnx_image_gen_backend_nnapi)
                    }
                },
                onSelected = onBackendChange
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LlamaImageToolOptionalNumberField(
                    value = runtimeThreads,
                    onValueChange = onRuntimeThreadsChange,
                    label = stringResource(R.string.onnx_image_gen_runtime_threads_label),
                    modifier = Modifier.weight(1f)
                )
                LlamaImageToolEnumDropdown(
                    label = stringResource(R.string.onnx_image_gen_graph_opt_title),
                    selected = graphOptimizationLevel,
                    values = OnnxGraphOptimizationLevel.entries,
                    labelFor = { it.name },
                    onSelected = onGraphOptimizationLevelChange,
                    modifier = Modifier.weight(1f)
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LlamaImageToolOptionalNumberField(
                    value = intraOpThreads,
                    onValueChange = onIntraOpThreadsChange,
                    label = stringResource(R.string.onnx_image_gen_intra_threads_label),
                    modifier = Modifier.weight(1f)
                )
                LlamaImageToolOptionalNumberField(
                    value = interOpThreads,
                    onValueChange = onInterOpThreadsChange,
                    label = stringResource(R.string.onnx_image_gen_inter_threads_label),
                    modifier = Modifier.weight(1f)
                )
            }

            LlamaImageToolEnumDropdown(
                label = stringResource(R.string.onnx_image_gen_execution_mode_title),
                selected = executionMode,
                values = OnnxExecutionMode.entries,
                labelFor = { it.name },
                onSelected = onExecutionModeChange
            )

            Text(
                stringResource(R.string.native_chat_image_generation_component_backends_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            LlamaImageToolEnumDropdown(
                label = stringResource(R.string.onnx_image_gen_component_backend_unet),
                selected = unetBackendOverride,
                values = OnnxBackendOverride.entries,
                labelFor = { it.name },
                onSelected = onUnetBackendOverrideChange
            )
            LlamaImageToolEnumDropdown(
                label = stringResource(R.string.onnx_image_gen_component_backend_vae_decoder),
                selected = vaeDecoderBackendOverride,
                values = OnnxBackendOverride.entries,
                labelFor = { it.name },
                onSelected = onVaeDecoderBackendOverrideChange
            )
            LlamaImageToolEnumDropdown(
                label = stringResource(R.string.onnx_image_gen_component_backend_vae_encoder),
                selected = vaeEncoderBackendOverride,
                values = OnnxBackendOverride.entries,
                labelFor = { it.name },
                onSelected = onVaeEncoderBackendOverrideChange
            )

            LlamaImageToolSwitchRow(
                title = stringResource(R.string.onnx_image_gen_memory_pattern_label),
                checked = memoryPatternOptimization,
                onCheckedChange = onMemoryPatternOptimizationChange
            )
            LlamaImageToolSwitchRow(
                title = stringResource(R.string.onnx_image_gen_cpu_arena_label),
                checked = cpuArenaAllocator,
                onCheckedChange = onCpuArenaAllocatorChange
            )
            LlamaImageToolSwitchRow(
                title = stringResource(R.string.onnx_image_gen_nnapi_cpu_disabled_label),
                checked = nnapiCpuDisabled,
                onCheckedChange = onNnapiCpuDisabledChange
            )
            LlamaImageToolSwitchRow(
                title = stringResource(R.string.onnx_image_gen_nnapi_fp16_label),
                checked = nnapiUseFp16,
                onCheckedChange = onNnapiUseFp16Change
            )
                }
            }
        }
    }
}

@Composable
private fun LlamaImageToolNumberField(
    value: Int,
    onValueChange: (Int) -> Unit,
    label: String,
    modifier: Modifier = Modifier
) {
    DraftIntTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        modifier = modifier
    )
}

@Composable
private fun LlamaImageToolFloatField(
    value: Float,
    onValueChange: (Float) -> Unit,
    label: String,
    modifier: Modifier = Modifier
) {
    DraftFloatTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        modifier = modifier
    )
}

@Composable
private fun LlamaImageToolOptionalNumberField(
    value: Int?,
    onValueChange: (Int?) -> Unit,
    label: String,
    modifier: Modifier = Modifier
) {
    DraftNullableIntTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        modifier = modifier
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LlamaStringDropdown(
    label: String,
    selected: String,
    values: List<String>,
    onSelected: (String) -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { if (enabled && values.isNotEmpty()) expanded = it },
        modifier = modifier
    ) {
        OutlinedTextField(
            value = selected,
            onValueChange = {},
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(),
            readOnly = true,
            enabled = enabled,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            singleLine = true
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            values.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option) },
                    onClick = {
                        onSelected(option)
                        expanded = false
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T : Enum<T>> LlamaImageToolEnumDropdown(
    label: String,
    selected: T,
    values: List<T>,
    labelFor: @Composable (T) -> String,
    onSelected: (T) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = modifier
    ) {
        OutlinedTextField(
            value = labelFor(selected),
            onValueChange = {},
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(),
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            singleLine = true
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            values.forEach { option ->
                DropdownMenuItem(
                    text = { Text(labelFor(option)) },
                    onClick = {
                        onSelected(option)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
private fun LlamaImageToolSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            title,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
fun LlamaDeleteMessageDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.llama_delete_message_confirm_title)) },
        text = { Text(stringResource(R.string.llama_delete_message_confirm_text)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.action_delete))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}

@Composable
fun LlamaClearChatDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.llama_clear_chat_confirm_title)) },
        text = { Text(stringResource(R.string.llama_clear_chat_confirm_text)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.llama_clear_chat_confirm_action))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}

@Composable
fun LlamaRetryMessageDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.llama_retry_message_confirm_title)) },
        text = { Text(stringResource(R.string.llama_retry_message_confirm_text)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.action_retry))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}

@Composable
fun LlamaMessageItem(
    message: LlamaMessageEntity,
    onRegenerate: () -> Unit,
    onEdit: (String) -> Unit,
    onRetry: () -> Unit,
    retryEnabled: Boolean,
    onRetryTranscription: () -> Unit,
    onDiscardFailedMessage: () -> Unit,
    onDelete: () -> Unit,
    autoPlayAssistantAudio: Boolean = false,
    onAssistantAudioAutoPlayed: (String) -> Unit = {},
    onKnowledgeLinkClick: (String) -> Boolean = { false }
) {
    val isUser = message.role == "user"
    val context = LocalContext.current
    val clipboardManager: ClipboardManager =
        context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val imageFile = remember(message.imagePath) { message.imagePath?.let(::File)?.takeIf { it.exists() } }
    val audioFile = remember(message.audioPath) { message.audioPath?.let(::File)?.takeIf { it.exists() } }
    var isEditing by remember(message.id) { mutableStateOf(false) }
    var editContent by remember(message.id) { mutableStateOf(message.content) }
    var showImagePreview by remember(message.imagePath) { mutableStateOf(false) }
    var audioPlayer by remember(message.audioPath) { mutableStateOf<MediaPlayer?>(null) }
    var isAudioPlaying by remember(message.audioPath) { mutableStateOf(false) }
    val embeddedTranscript = remember(message.content) { extractEmbeddedAudioTranscript(message.content) }
    val embeddedDocument = remember(message.content) { extractEmbeddedDocumentText(message.content) }
    val displayContent = remember(message.content) {
        stripEmbeddedDocumentText(stripEmbeddedAudioTranscript(message.content)).trim()
    }
    val transcriptionFailed = isUser && audioFile != null && message.isError && embeddedTranscript.isNullOrBlank()

    fun copyMessageToClipboard() {
        val clip = android.content.ClipData.newPlainText("Message", message.content)
        clipboardManager.setPrimaryClip(clip)
        Toast.makeText(context, context.getString(R.string.termux_copy_toast), Toast.LENGTH_SHORT).show()
    }

    DisposableEffect(message.audioPath) {
        onDispose {
            runCatching { audioPlayer?.release() }
            audioPlayer = null
            isAudioPlaying = false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
    ) {
        Card(
            colors = CardDefaults.cardColors(
                containerColor = if (isUser) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
            ),
            shape = RoundedCornerShape(
                topStart = 16.dp,
                topEnd = 16.dp,
                bottomStart = if (isUser) 16.dp else 4.dp,
                bottomEnd = if (isUser) 4.dp else 16.dp
            ),
            modifier = Modifier.widthIn(max = 320.dp)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                if (isEditing) {
                    OutlinedTextField(
                        value = editContent,
                        onValueChange = { editContent = it },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(
                        horizontalArrangement = Arrangement.End,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                    ) {
                        TextButton(onClick = { isEditing = false }) {
                            Text(stringResource(R.string.action_cancel))
                        }
                        TextButton(
                            onClick = {
                                onEdit(editContent)
                                isEditing = false
                            }
                        ) {
                            Text(stringResource(R.string.action_save))
                        }
                    }
                } else {
                    SelectionContainer {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (imageFile != null) {
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { showImagePreview = true }
                                ) {
                                    AsyncImage(
                                        model = imageFile,
                                        contentDescription = stringResource(R.string.llama_image_attached),
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .heightIn(min = 140.dp, max = 260.dp)
                                    )
                                }
                            }

                            if (audioFile != null) {
                                AudioPlaybackRow(
                                    audioFile = audioFile,
                                    onPlaybackChanged = { playing, player ->
                                        isAudioPlaying = playing
                                        audioPlayer = player
                                    },
                                    autoPlay = autoPlayAssistantAudio,
                                    onAutoPlayConsumed = { onAssistantAudioAutoPlayed(audioFile.absolutePath) }
                                )
                            }

                            if (isUser && embeddedDocument != null) {
                                EmbeddedDocumentAttachment(document = embeddedDocument)
                            }

                            val thinkStartRegex = Regex("<[^>]*?(?:think|thought|Thought|Think)[^>]*?>")
                            val renderedContent = if (isUser) displayContent else message.content
                            val hasThinkingTags = thinkStartRegex.containsMatchIn(renderedContent)

                            if (renderedContent.isNotBlank()) {
                                if (!isUser && (!message.thinking.isNullOrBlank() || hasThinkingTags)) {
                                    if (!message.thinking.isNullOrBlank()) {
                                        ThinkingMessageContent(
                                            message.thinking,
                                            renderedContent,
                                            onKnowledgeLinkClick = onKnowledgeLinkClick
                                        )
                                    } else {
                                        val combinedRegex = Regex("(<[^>]*?(?:think|thought|Thought|Think)[^>]*?>)(.*?)(<[^>]*?/(?:think|thought|Thought|Think)[^>]*?>|$)", setOf(RegexOption.DOT_MATCHES_ALL))
                                        val match = combinedRegex.find(renderedContent)
                                        val thinking = match?.groupValues?.get(2)?.trim() ?: ""
                                        val content = renderedContent.replace(combinedRegex, "").trim()
                                        ThinkingMessageContent(
                                            thinking,
                                            content,
                                            onKnowledgeLinkClick = onKnowledgeLinkClick
                                        )
                                    }
                                } else {
                                    MarkdownText(
                                        text = renderedContent,
                                        textColor = if (isUser) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                                        onLinkClick = onKnowledgeLinkClick
                                    )
                                }
                            }

                            if (isUser && !embeddedTranscript.isNullOrBlank()) {
                                AudioTranscriptContent(transcript = embeddedTranscript)
                            } else if (transcriptionFailed) {
                                AudioTranscriptionErrorContent(
                                    onRetry = onRetryTranscription,
                                    onDiscard = onDiscardFailedMessage
                                )
                            }
                        }
                    }
                }
            }
        }

        // Message meta + actions
        Column(
            modifier = Modifier
                .widthIn(max = 320.dp)
                .padding(horizontal = 4.dp),
            horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = message.role.replaceFirstChar { it.uppercase() },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
                if (!isUser && message.completionTokens > 0) {
                    Text(
                        text = stringResource(
                            R.string.llama_message_stats,
                            "%.1fs".format(message.generationTimeMs / 1000.0),
                            message.completionTokens,
                            message.tps,
                            message.promptTokens
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.7f)
                    )
                }
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                if (!isUser) {
                    TextButton(
                        onClick = { copyMessageToClipboard() },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.ContentCopy,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = stringResource(R.string.action_copy),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    IconButton(onClick = onRegenerate, modifier = Modifier.size(28.dp)) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = stringResource(R.string.llama_regenerate),
                            tint = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    IconButton(onClick = onDelete, modifier = Modifier.size(28.dp)) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = stringResource(R.string.action_delete),
                            tint = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                } else {
                    if (!transcriptionFailed) {
                        TextButton(
                            onClick = onRetry,
                            enabled = retryEnabled,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                        ) {
                            Icon(
                                Icons.Default.Refresh,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = stringResource(R.string.action_retry),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    IconButton(onClick = { copyMessageToClipboard() }, modifier = Modifier.size(28.dp)) {
                        Icon(
                            Icons.Default.ContentCopy,
                            contentDescription = stringResource(R.string.action_copy),
                            tint = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    IconButton(
                        onClick = {
                            isEditing = true
                            editContent = message.content
                        },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            Icons.Default.Edit,
                            contentDescription = stringResource(R.string.action_edit),
                            tint = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    IconButton(onClick = onDelete, modifier = Modifier.size(28.dp)) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = stringResource(R.string.action_delete),
                            tint = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }
    }

    if (showImagePreview && imageFile != null) {
        LlamaImagePreviewDialog(
            imageFile = imageFile,
            onDismiss = { showImagePreview = false }
        )
    }
}

@Composable
private fun AudioTranscriptContent(transcript: String) {
    var isExpanded by remember(transcript) { mutableStateOf(false) }

    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { isExpanded = !isExpanded }
                .padding(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.llama_audio_transcription),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (isExpanded) {
                Spacer(modifier = Modifier.height(6.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = transcript,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f)
                )
            }
        }
    }
}

@Composable
private fun AudioTranscriptionErrorContent(
    onRetry: () -> Unit,
    onDiscard: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = stringResource(R.string.llama_audio_transcription),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = stringResource(R.string.llama_transcription_error_prompt),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f)
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onRetry, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) {
                    Text(stringResource(R.string.action_yes))
                }
                TextButton(onClick = onDiscard, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) {
                    Text(stringResource(R.string.action_no))
                }
            }
        }
    }
}

private fun llamaMessagesToNoteMarkdown(
    systemPrompt: String?,
    messages: List<LlamaMessageEntity>,
    systemLabel: String,
    imageLabel: String,
    audioLabel: String
): String {
    return buildString {
        systemPrompt?.takeIf { it.isNotBlank() }?.let {
            append("## ")
            append(systemLabel)
            append("\n\n")
            append(it.trim())
            append("\n\n")
        }
        messages.forEach { message ->
            append("## ")
            append(message.role.replaceFirstChar { it.titlecase(Locale.getDefault()) })
            append("\n\n")
            append(message.content.trim())
            message.imagePath?.takeIf { it.isNotBlank() }?.let { append("\n\n").append(imageLabel).append(": ").append(it) }
            message.audioPath?.takeIf { it.isNotBlank() }?.let { append("\n\n").append(audioLabel).append(": ").append(it) }
            append("\n\n")
        }
    }.trim()
}

@Composable
fun ThinkingMessageContent(
    thinkingContent: String,
    finalResponse: String,
    forceExpand: Boolean = false,
    onKnowledgeLinkClick: (String) -> Boolean = { false }
) {
    var isExpanded by remember { mutableStateOf(forceExpand) }

    // Auto-update expansion state when forceExpand changes (e.g. at start of generation)
    LaunchedEffect(forceExpand) {
        if (forceExpand) isExpanded = true
    }

    // Auto-expand if the block is actively generating and we haven't seen the final response yet
    val isThinkingFinished = finalResponse.isNotBlank()
    LaunchedEffect(isThinkingFinished) {
        if (!isThinkingFinished) {
            isExpanded = true
        }
    }

    Column {
        if (thinkingContent.isNotEmpty()) {
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { isExpanded = !isExpanded }
                    .padding(bottom = 8.dp)
            ) {
                Column(modifier = Modifier.padding(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Square,
                            contentDescription = "Thinking",
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.llama_thinking_process),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                        Spacer(modifier = Modifier.weight(1f))
                        Icon(
                            imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = if (isExpanded) "Collapse" else "Expand",
                            tint = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }

                    if (isExpanded && thinkingContent.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        Spacer(modifier = Modifier.height(4.dp))
                        MarkdownText(
                            text = thinkingContent,
                            textColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                            onLinkClick = onKnowledgeLinkClick
                        )
                    }
                }
            }
        }

        if (finalResponse.isNotEmpty()) {
            MarkdownText(
                text = finalResponse,
                textColor = MaterialTheme.colorScheme.onSurfaceVariant,
                onLinkClick = onKnowledgeLinkClick
            )
        }
    }
}
