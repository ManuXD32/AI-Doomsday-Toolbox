package com.example.llamadroid.ui.agent

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.res.stringResource
import com.example.llamadroid.R
import androidx.navigation.NavController
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.example.llamadroid.data.db.AiRuntimeJobEntity
import com.example.llamadroid.data.db.AgentMessageEntity
import com.example.llamadroid.data.db.KnowledgeBaseEntity
import com.example.llamadroid.data.db.ModelType
import com.example.llamadroid.data.repository.KnowledgeBaseRepository
import com.example.llamadroid.service.AgentForegroundService
import com.example.llamadroid.service.AiRuntimeJobStore
import com.example.llamadroid.service.AgentService
import com.example.llamadroid.service.OllamaService
import com.example.llamadroid.service.StagedFileCache
import com.example.llamadroid.onnx.isOnnxTxt2ImgBundle
import com.example.llamadroid.ui.components.ApprovalQueueDialog
import com.example.llamadroid.ui.navigation.Screen
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt
import org.json.JSONObject
import org.json.JSONArray
import com.example.llamadroid.data.SettingsRepository

// Removed AgentChatMessage data class as it is now in AgentService.ChatMessage

/**
 * AgentScreen - AI Coding Agent Chat Interface
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentScreen(navController: NavController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Services
    val ollamaService = remember { AgentForegroundService.getOllamaService(context) }
    val agentService = remember { AgentForegroundService.getAgentService(context) }
    val db = remember { com.example.llamadroid.data.db.AppDatabase.getDatabase(context) }
    val knowledgeBaseRepository = remember { KnowledgeBaseRepository(context, db) }
    val settingsRepository = remember { SettingsRepository(context) }
    val lifecycleOwner = LocalLifecycleOwner.current
    val density = LocalDensity.current
    val rootView = LocalView.current
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

    // Initialize Ollama URL from saved settings
    remember { ollamaService.initFromSettings() }

    // State - use STATIC companion object for navigation persistence
    val messages by AgentService.messages.collectAsStateWithLifecycle()
    val isLoading by AgentService.isLoading.collectAsStateWithLifecycle()
    val selectedModel by AgentService.selectedModel.collectAsStateWithLifecycle()
    val currentAgent by AgentService.currentAgent.collectAsStateWithLifecycle()
    val currentTask by AgentService.currentTask.collectAsStateWithLifecycle()
    val runtimeActiveConversationId by AgentService.activeConversationId.collectAsStateWithLifecycle()
    val debugLog by AgentService.debugLog.collectAsStateWithLifecycle()
    val selectedKnowledgeBaseIds by AgentService.selectedKnowledgeBaseIds.collectAsStateWithLifecycle()

    // UI Local state
    var inputText by rememberSaveable { mutableStateOf("") }
    var attachedImagePath by remember { mutableStateOf<String?>(null) }
    var imagePreviewPath by remember { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()
    val agentBackend by settingsRepository.agentBackend.collectAsStateWithLifecycle()
    val isAgentLlamaServer = SettingsRepository.isLlamaServerBackend(agentBackend)
    val isAgentLlamaSwap = SettingsRepository.isLlamaSwapBackend(agentBackend)
    val isAgentOpenAiBackend = SettingsRepository.usesOpenAiChatBackend(agentBackend)
    val llamaServerUrl by settingsRepository.llamaServerUrl.collectAsStateWithLifecycle()
    val llamaSwapUrl by settingsRepository.agentLlamaSwapUrl.collectAsStateWithLifecycle()
    val llamaServerRuntimeState by AgentService.llamaServerRuntimeState.collectAsStateWithLifecycle()
    val orchestratorVisionEnabled by settingsRepository.agentOrchestratorVisionEnabled.collectAsStateWithLifecycle()

    val initialConversationId = remember {
        settingsRepository.lastAgentConversationId.value.takeIf { it != -1L }
    }
    var runtimeConversationId by rememberSaveable { mutableStateOf<Long?>(null) }
    var selectedConversationId by rememberSaveable { mutableStateOf<Long?>(initialConversationId) }
    var isConversationRestoring by remember { mutableStateOf(initialConversationId != null) }
    var hydratingConversationTitle by remember { mutableStateOf<String?>(null) }
    var initialConversationRestorePending by remember { mutableStateOf(initialConversationId != null) }
    var showConversations by remember { mutableStateOf(false) }
    var restoreToken by remember { mutableIntStateOf(0) }

    LaunchedEffect(agentBackend, llamaServerUrl, llamaSwapUrl) {
        delay(350)
        if (isAgentOpenAiBackend) {
            val remoteUrl = if (isAgentLlamaSwap) llamaSwapUrl else llamaServerUrl
            if (remoteUrl.isNotBlank()) {
                agentService.refreshLlamaServerRuntimeState(settingsRepository, force = true)
            }
        } else {
            ollamaService.checkConnection()
        }
    }

    // --- Core Functions ---
    fun saveConversationSnapshot(
        conversationId: Long,
        snapshot: List<AgentService.Companion.ChatMessage>
    ) {
        val currentAgentRef = currentAgent
        val currentTaskRef = currentTask
        scope.launch {
            if (db.agentChatDao().getConversation(conversationId) == null) {
                AgentService.addDebugLog("⚠️ Skipping autosave for missing conversation $conversationId")
                return@launch
            }
            db.agentChatDao().updateConversationState(
                conversationId,
                currentAgentRef.name,
                currentTaskRef
            )
            // Save messages
            val entities = snapshot
                .filterNot { AgentService.isTransientCompactionStatusMessageForPersistence(it) }
                .map { msg ->
                AgentService.chatMessageToEntity(msg, conversationId)
                }
            db.agentChatDao().deleteAllMessagesInConversation(conversationId)
            db.agentChatDao().insertMessages(entities)
        }
    }
    // --- End Core Functions ---
    var showModelSelector by remember { mutableStateOf(false) }
    var showSetupInfo by remember { mutableStateOf(false) }
    var showConnectionSettings by remember { mutableStateOf(false) }
    var showDebugPanel by remember { mutableStateOf(false) }
    var showAgentSettings by remember { mutableStateOf(false) }
    val showAllOutputState by settingsRepository.showExtraOutput.collectAsStateWithLifecycle()
    var showAllOutput by remember { mutableStateOf(showAllOutputState) }

    // Update local state when preference changes, and vice versa
    LaunchedEffect(showAllOutputState) {
        showAllOutput = showAllOutputState
    }
    LaunchedEffect(showAllOutput) {
        if (showAllOutput != showAllOutputState) {
            settingsRepository.setShowExtraOutput(showAllOutput)
        }
    }
    var showNewProjectDialog by remember { mutableStateOf(false) } // New project name dialog
    var showCustomTools by remember { mutableStateOf(false) } // Custom Tools screen
    var showCustomAgents by remember { mutableStateOf(false) } // Custom Agents screen
    var showProjectManagement by remember { mutableStateOf(false) } // Project Management screen
    var showDeleteConfirmation by remember { mutableStateOf<Long?>(null) } // Delete confirmation dialog
    var pendingDeleteFolder by remember { mutableStateOf<String?>(null) } // Folder to delete
    var newProjectName by remember { mutableStateOf("") }
    var editingMessageId by remember { mutableStateOf<String?>(null) }
    var editingText by remember { mutableStateOf("") }
    var pendingDenyMessage by remember { mutableStateOf<AgentService.Companion.ChatMessage?>(null) }
    var denyExplanation by remember { mutableStateOf("") }
    
    // First-run popup - show once to remind user to create project
    val prefs = remember { context.getSharedPreferences("agent_prefs", Context.MODE_PRIVATE) }
    var showFirstRunPopup by remember { mutableStateOf(false) }
    
    LaunchedEffect(Unit) {
        if (!prefs.getBoolean("first_run_shown", false)) {
            showFirstRunPopup = true
            prefs.edit().putBoolean("first_run_shown", true).apply()
        }
    }
    
    // Remote connection state
    val isAgentConnected by AgentService.isConnected.collectAsStateWithLifecycle()
    val agentConnectionStatus by AgentService.connectionStatus.collectAsStateWithLifecycle()
    val retryMessage by AgentService.retryMessage.collectAsStateWithLifecycle()
    val isOllamaConnected by OllamaService.isConnected.collectAsStateWithLifecycle()
    val ollamaIsRecovering by OllamaService.isRecovering.collectAsStateWithLifecycle()
    val availableModelsData by OllamaService.availableModels.collectAsStateWithLifecycle()
    val ollamaHasChecked by OllamaService.hasCheckedConnection.collectAsStateWithLifecycle()
    val availableModels = availableModelsData.map { it.name }
    
    // Connection settings state
    var sshHost by remember { mutableStateOf("127.0.0.1") }
    var sshPort by remember { mutableStateOf("8023") }
    var sshUser by remember { mutableStateOf("root") }
    var sshPassword by remember { mutableStateOf("") }
    val ollamaUrl by ollamaService.baseUrl.collectAsStateWithLifecycle()
    
    // Database and conversation management
    val conversations by db.agentChatDao().getAllConversations().collectAsState(initial = emptyList())
    val knowledgeBases by knowledgeBaseRepository.observeKnowledgeBases().collectAsState(initial = emptyList())
    val activeRuntimeJobs by db.aiRuntimeJobDao().observeActiveJobs().collectAsState(initial = emptyList())
    val selectedConversationMessageFlow = remember(selectedConversationId) {
        selectedConversationId?.let { db.agentChatDao().getMessagesForConversation(it) }
            ?: flowOf(emptyList<AgentMessageEntity>())
    }
    val selectedConversationEntities by selectedConversationMessageFlow.collectAsState(initial = emptyList())
    val selectedConversationMessages = remember(selectedConversationEntities) {
        selectedConversationEntities.map { AgentService.chatMessageFromEntity(it) }
    }

    val customTools by db.customToolDao().getEnabledTools().collectAsState(initial = emptyList())
    LaunchedEffect(customTools) {
        AgentService.setLoadedCustomTools(customTools)
    }

    val installedOnnxModels by db.modelDao().getModelsByType(ModelType.ONNX_IMAGE_GEN).collectAsState(initial = emptyList())
    val availableImageGenerationModels = remember(installedOnnxModels) {
        installedOnnxModels.filter { it.isOnnxTxt2ImgBundle() }.map { it.filename }
    }
    
    // Load custom agents from database
    val customAgents by db.customAgentDao().getEnabledAgents().collectAsState(initial = emptyList())
    LaunchedEffect(customAgents) {
        AgentService.setLoadedCustomAgents(customAgents)
    }

    fun clearImageAttachment() {
        attachedImagePath?.let { path ->
            runCatching { File(path).delete() }
        }
        if (imagePreviewPath == attachedImagePath) {
            imagePreviewPath = null
        }
        attachedImagePath = null
    }

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                runCatching {
                    persistAgentChatImage(
                        context = context,
                        projectFolder = AgentService.currentProjectFolder.value,
                        uri = uri
                    )
                }.onSuccess { path ->
                    clearImageAttachment()
                    attachedImagePath = path
                }.onFailure {
                    Toast.makeText(
                        context,
                        context.getString(R.string.agent_attach_image_failed),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }
    

    suspend fun loadStoredConversationMessages(
        conversationId: Long?
    ): List<AgentService.Companion.ChatMessage> {
        if (conversationId == null) return emptyList()
        return db.agentChatDao()
            .getMessagesForConversationSync(conversationId)
            .map { AgentService.chatMessageFromEntity(it) }
    }

    fun newestConversationIdExcluding(excludedId: Long? = null): Long? {
        return com.example.llamadroid.ui.agent.newestConversationIdExcluding(
            conversations.map { it.id },
            excludedId
        )
    }

    fun resolveRelevantAgentJob(): AiRuntimeJobEntity? {
        return resolveRelevantAgentRuntimeJob(
            activeRuntimeJobs = activeRuntimeJobs,
            runtimeActiveConversationId = runtimeActiveConversationId,
            runtimeConversationId = runtimeConversationId,
            selectedConversationId = selectedConversationId
        )
    }

    fun syncConversationUiFromRuntime(conversationId: Long?) {
        if (conversationId == null) return
        runtimeConversationId = conversationId
        selectedConversationId = conversationId
        AgentService.setPreferredConversationId(conversationId)
        hydratingConversationTitle = conversations.firstOrNull { it.id == conversationId }?.title
        isConversationRestoring = false
        initialConversationRestorePending = false
    }

    suspend fun activateConversationRuntime(
        conversationId: Long,
        projectFolder: String,
        conversationTitle: String?,
        restoredRole: AgentService.Companion.AgentRole,
        restoredTask: String?,
        restoredMessages: List<AgentService.Companion.ChatMessage>,
        knowledgeBaseIdsCsv: String = "",
        dismissPicker: Boolean,
        token: Int? = null
    ) {
        clearImageAttachment()
        settingsRepository.setLastAgentConversationId(conversationId)
        AgentService.setPreferredConversationId(conversationId)
        if (token != null && token != restoreToken) return
        AgentService.clearTransientConversationState()
        AgentService.clearAllSessions()
        StagedFileCache.clear()
        AgentService.clearMessages()
        if (token != null && token != restoreToken) return
        runtimeConversationId = conversationId
        selectedConversationId = conversationId
        hydratingConversationTitle = conversationTitle
        AgentService.setPreferredConversationId(conversationId)
        AgentService.setActiveConversationId(conversationId)
        AgentService.setCurrentProjectFolder(projectFolder)
        AgentService.setCurrentAgent(restoredRole)
        AgentService.setCurrentTask(restoredTask)
        AgentService.setSelectedKnowledgeBaseIdsCsv(knowledgeBaseIdsCsv)
        val maxSeq = restoredMessages.maxOfOrNull { it.sequenceNumber } ?: 0
        AgentService.resetMessageCounter(maxSeq)
        AgentService.setMessages(restoredMessages)
        AgentService.restoreHardCompactionStateFromBrain()
        if (dismissPicker && (token == null || token == restoreToken)) {
            showConversations = false
        }
    }

    suspend fun reconcileRuntimeUiState(triggerResume: Boolean = false) {
        if (triggerResume) {
            AgentForegroundService.requestResume(context)
        }

        val liveConversationId = AgentService.activeConversationId.value
        val liveMessages = AgentService.messages.value
        if (shouldAdoptLiveRuntimeConversation(
                selectedConversationId = selectedConversationId,
                liveConversationId = liveConversationId,
                knownConversationIds = conversations.map { it.id }
            )
        ) {
            liveConversationId?.let {
                syncConversationUiFromRuntime(it)
                settingsRepository.setLastAgentConversationId(it)
            }
            if (liveMessages.isNotEmpty()) {
                return
            }
        }

        val activeJob = resolveRelevantAgentJob() ?: return
        val jobConversationId = activeJob.conversationId
        if (jobConversationId != null &&
            shouldAdoptLiveRuntimeConversation(
                selectedConversationId = selectedConversationId,
                liveConversationId = jobConversationId,
                knownConversationIds = conversations.map { it.id }
            ) &&
            (liveConversationId == null || liveMessages.isEmpty() || liveConversationId == jobConversationId)
        ) {
            agentService.restorePersistentState(activeJob.payloadJson)
            val restoredConversationId = AgentService.activeConversationId.value ?: jobConversationId
            syncConversationUiFromRuntime(restoredConversationId)
            settingsRepository.setLastAgentConversationId(restoredConversationId)
        }
    }

    suspend fun restoreConversation(conversationId: Long, dismissPicker: Boolean, token: Int) {
        if (isConversationRestoring && runtimeConversationId == conversationId && selectedConversationId == conversationId) return

        isConversationRestoring = true
        val conv = db.agentChatDao().getConversation(conversationId)
        if (token != restoreToken) return
        hydratingConversationTitle = conv?.title

        try {
            if (conv == null) {
                val fallbackConversationId = newestConversationIdExcluding(conversationId)
                if (fallbackConversationId != null) {
                    selectedConversationId = fallbackConversationId
                    hydratingConversationTitle = conversations.firstOrNull { it.id == fallbackConversationId }?.title
                    restoreConversation(fallbackConversationId, dismissPicker, token)
                    return
                }
                runtimeConversationId = null
                selectedConversationId = null
                AgentService.setPreferredConversationId(null)
                hydratingConversationTitle = null
                AgentService.clearMessages()
                AgentService.clearAllSessions()
                AgentService.clearTransientConversationState()
                AgentService.setActiveConversationId(null)
                AgentService.setSelectedKnowledgeBaseIds(emptyList())
                return
            }

            clearImageAttachment()
            if (token != restoreToken) return
            val restoredRole = AgentService.Companion.AgentRole.values().find { it.name == conv.lastAgentRole }
                ?: AgentService.Companion.AgentRole.ORCHESTRATOR
            val restoredMessages = loadStoredConversationMessages(conversationId)
            if (token != restoreToken) return
            activateConversationRuntime(
                conversationId = conversationId,
                projectFolder = conv.projectFolder,
                conversationTitle = conv.title,
                restoredRole = restoredRole,
                restoredTask = conv.lastTask,
                restoredMessages = restoredMessages,
                knowledgeBaseIdsCsv = conv.knowledgeBaseIds,
                dismissPicker = dismissPicker,
                token = token
            )
            if (token != restoreToken) return

            if (restoredMessages.isNotEmpty()) {
                AgentService.addDebugLog(context.getString(R.string.agent_restored_messages, restoredMessages.size))
            }
        } finally {
            if (token == restoreToken) {
                isConversationRestoring = false
                initialConversationRestorePending = false
            }
        }
    }

    suspend fun beginConversationRestore(conversationId: Long, dismissPicker: Boolean) {
        if (!dismissPicker &&
            !initialConversationRestorePending &&
            !isConversationRestoring &&
            selectedConversationId == conversationId &&
            runtimeConversationId == conversationId
        ) {
            return
        }
        restoreToken += 1
        val token = restoreToken
        selectedConversationId = conversationId
        hydratingConversationTitle = conversations.firstOrNull { it.id == conversationId }?.title
        isConversationRestoring = true
        restoreConversation(conversationId, dismissPicker = dismissPicker, token = token)
    }

    LaunchedEffect(initialConversationRestorePending, conversations) {
        if (!initialConversationRestorePending) return@LaunchedEffect
        val startupConversationId = when {
            initialConversationId != null && conversations.any { it.id == initialConversationId } -> initialConversationId
            conversations.isNotEmpty() -> conversations.first().id
            else -> null
        }
        if (startupConversationId == null) {
            initialConversationRestorePending = false
            isConversationRestoring = false
            selectedConversationId = null
            runtimeConversationId = null
            AgentService.setPreferredConversationId(null)
            return@LaunchedEffect
        }
        beginConversationRestore(startupConversationId, dismissPicker = false)
    }

    LaunchedEffect(Unit) {
        reconcileRuntimeUiState(triggerResume = true)
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START || event == Lifecycle.Event.ON_RESUME) {
                scope.launch {
                    reconcileRuntimeUiState(triggerResume = true)
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    LaunchedEffect(activeRuntimeJobs, runtimeActiveConversationId) {
        val activeJob = resolveRelevantAgentJob() ?: return@LaunchedEffect
        val shouldRestoreFromRuntime = activeJob.type == AiRuntimeJobStore.TYPE_AGENT_CHAT &&
            (runtimeActiveConversationId == null || messages.isEmpty() || activeJob.conversationId == selectedConversationId)
        if (shouldRestoreFromRuntime) {
            reconcileRuntimeUiState(triggerResume = false)
        }
    }
    // Auto-save after stable conversation updates rather than every streaming chunk.
    LaunchedEffect(runtimeConversationId, messages, isConversationRestoring, restoreToken) {
        val targetConversationId = runtimeConversationId ?: return@LaunchedEffect
        if (!shouldAutosaveConversationSnapshot(
                runtimeConversationId = runtimeConversationId,
                activeConversationId = AgentService.activeConversationId.value,
                isConversationRestoring = isConversationRestoring,
                messagesEmpty = messages.isEmpty(),
                hasStreamingMessages = messages.any { it.isStreaming },
                targetConversationId = targetConversationId
            )
        ) return@LaunchedEffect
        val observedRestoreToken = restoreToken
        delay(300)
        if (observedRestoreToken != restoreToken) return@LaunchedEffect
        if (!shouldAutosaveConversationSnapshot(
                runtimeConversationId = runtimeConversationId,
                activeConversationId = AgentService.activeConversationId.value,
                isConversationRestoring = isConversationRestoring,
                messagesEmpty = false,
                hasStreamingMessages = messages.any { it.isStreaming },
                targetConversationId = targetConversationId
            )
        ) {
            AgentService.addDebugLog("⚠️ Skipping autosave due to active conversation mismatch")
            return@LaunchedEffect
        }
        val snapshot = AgentService.messages.value
        if (!shouldAutosaveConversationSnapshot(
                runtimeConversationId = runtimeConversationId,
                activeConversationId = AgentService.activeConversationId.value,
                isConversationRestoring = isConversationRestoring,
                messagesEmpty = snapshot.isEmpty(),
                hasStreamingMessages = snapshot.any { it.isStreaming },
                targetConversationId = targetConversationId
            )
        ) return@LaunchedEffect
        saveConversationSnapshot(targetConversationId, snapshot)
    }
    
    val currentStatusText by AgentService.statusText.collectAsStateWithLifecycle()
    val promptContextSnapshot by AgentService.promptContextSnapshot.collectAsStateWithLifecycle()
    val lastOrchestratorPromptSnapshot by AgentService.lastOrchestratorPromptSnapshot.collectAsStateWithLifecycle()
    fun triggerAgent(isRedo: Boolean = false) {
        AgentService.sendMessage(
            context,
            ollamaService,
            settingsRepository,
            agentService,
            isRedo = isRedo,
            userInitiated = true
        )
    }

    fun stopGeneration() {
        AgentService.stopAllJobs()
    }
    
    fun continueAfterToolExecution() {
        triggerAgent()
    }

    fun handleApproval(approved: Boolean, msg: AgentService.Companion.ChatMessage, denyReason: String = "") {
        if (msg.isPlan) {
            if (approved) {
                AgentService.updateMessage(msg.id) { it.copy(isPlanApproved = true) }
                AgentService.addDebugLog(context.getString(R.string.agent_plan_approved))
                AgentService.markMemoryDirty("An implementation plan was approved. Record the chosen direction in project memory before finishing.")
                com.example.llamadroid.service.UnifiedNotificationManager.dismissAgentAttention()
                
                // Send official tool result back to LLM
                AgentService.addMessage(AgentService.Companion.ChatMessage(
                    role = "tool",
                    toolName = "propose_plan",
                    toolCallId = msg.toolCallId,
                    content = context.getString(R.string.agent_plan_approved_msg)
                ))
                scope.launch {
                    agentService.persistVisibleRuntimeStateNow("Plan approved by user.")
                }
                triggerAgent()
            } else {
                AgentService.updateMessage(msg.id) { it.copy(isPlanApproved = false) }
                AgentService.addDebugLog(context.getString(R.string.agent_plan_rejected))
                com.example.llamadroid.service.UnifiedNotificationManager.dismissAgentAttention()
                scope.launch {
                    agentService.persistVisibleRuntimeStateNow("Plan rejected by user.")
                }
            }
        } else if (approved) {
            AgentService.updateMessage(msg.id) { it.copy(needsApproval = false, isApproved = true) }
            com.example.llamadroid.service.UnifiedNotificationManager.dismissAgentAttention()
            val toolCall = msg.pendingToolCall ?: com.example.llamadroid.service.OllamaService.ToolCall(
                name = msg.toolName ?: "",
                arguments = msg.toolArgs ?: emptyMap(),
                id = null
            )
            scope.launch {
                agentService.persistVisibleRuntimeStateNow("Tool approval granted for ${msg.toolName.orEmpty()}.")
            }
            AgentService.executeToolCall(context, ollamaService, settingsRepository, agentService, toolCall, isForced = true)
        } else {
            AgentService.updateMessage(msg.id) { it.copy(needsApproval = false, isApproved = false) }
            com.example.llamadroid.service.UnifiedNotificationManager.dismissAgentAttention()
            val toolName = msg.toolName ?: context.getString(R.string.agent_generic_tool)
            val denialContent = if (denyReason.isNotBlank()) {
                "DENIED by user: $toolName. Reason: $denyReason"
            } else {
                context.getString(R.string.agent_denied_execution, toolName)
            }
            AgentService.addMessage(AgentService.Companion.ChatMessage(
                role = "user",
                content = denialContent
            ))
            scope.launch {
                agentService.persistVisibleRuntimeStateNow("Tool denied by user for $toolName.")
            }
            triggerAgent()
        }
    }
    // --- End Helper Functions ---


    fun loadConversation(convId: Long) {
        scope.launch {
            beginConversationRestore(convId, dismissPicker = true)
        }
    }

    fun createNewConversation(projectName: String = context.getString(R.string.agent_project_default_prefix) + System.currentTimeMillis()) {
        scope.launch {
            val safeName = projectName.trim().replace(Regex("[^a-zA-Z0-9_-]"), "_").take(50).ifBlank { context.getString(R.string.agent_project_default_prefix) + System.currentTimeMillis() }
            val newId = db.agentChatDao().insertConversation(com.example.llamadroid.data.db.AgentConversationEntity(title = projectName, projectFolder = safeName))
            restoreToken += 1
            runtimeConversationId = newId
            selectedConversationId = newId
            AgentService.setPreferredConversationId(newId)
            isConversationRestoring = false
            initialConversationRestorePending = false
            hydratingConversationTitle = projectName
            AgentService.setActiveCustomAgent(null)
            
            if (!AgentService.isConnected.value) agentService.connect()
            if (AgentService.isConnected.value) {
                agentService.executeRawCommand("mkdir -p /workspace/$safeName/brain")
            }
            
            val initialMessages = listOf(
                AgentService.Companion.ChatMessage(
                    role = "system",
                    content = context.getString(R.string.agent_ready_msg, projectName, safeName)
                )
            )
            activateConversationRuntime(
                conversationId = newId,
                projectFolder = safeName,
                conversationTitle = projectName,
                restoredRole = AgentService.Companion.AgentRole.ORCHESTRATOR,
                restoredTask = null,
                restoredMessages = initialMessages,
                dismissPicker = true
            )
            agentService.persistVisibleRuntimeStateNow("Created new project conversation $safeName.")
            showConversations = false
        }
    }

    fun deleteConversation(convId: Long, projectFolder: String? = null) {
        scope.launch {
            val fallbackConversationId = newestConversationIdExcluding(convId)
            if (runtimeConversationId == convId) AgentService.stopAllJobs()
            db.agentChatDao().deleteConversationById(convId)
            if (runtimeConversationId == convId || selectedConversationId == convId) {
                if (fallbackConversationId != null) {
                    beginConversationRestore(fallbackConversationId, dismissPicker = false)
                } else {
                    restoreToken += 1
                    runtimeConversationId = null
                    selectedConversationId = null
                    AgentService.setPreferredConversationId(null)
                    hydratingConversationTitle = null
                    isConversationRestoring = false
                    settingsRepository.setLastAgentConversationId(-1L)
                    AgentService.clearMessages()
                    AgentService.clearAllSessions()
                    AgentService.clearTransientConversationState()
                    AgentService.setActiveConversationId(null)
                    AgentService.setSelectedKnowledgeBaseIds(emptyList())
                }
            }
            if (projectFolder != null && projectFolder.isNotBlank()) {
                val safeName = projectFolder.replace(Regex("[^a-zA-Z0-9_-]"), "_")
                agentService.runCommand("rm -rf /workspace/$safeName")
            }
        }
    }

    LaunchedEffect(selectedConversationId, runtimeConversationId, runtimeActiveConversationId) {
        AgentService.setPreferredConversationId(
            selectedConversationId ?: runtimeConversationId ?: runtimeActiveConversationId
        )
    }

    fun deleteMessage(id: String) = AgentService.deleteMessage(id)
    
    fun regenerateMessage(id: String) {
        AgentService.truncateHistoryAt(id)
        triggerAgent(isRedo = true)
    }

    fun editMessage(id: String, content: String) {
        editingMessageId = id
        editingText = content
    }

    fun saveEdit() {
        val id = editingMessageId ?: return
        val activeUiConversationId = runtimeActiveConversationId ?: runtimeConversationId
        val activeMessages = if (
            shouldPreferLiveRuntimeMessages(
                selectedConversationId = selectedConversationId,
                runtimeConversationId = runtimeConversationId,
                activeConversationId = runtimeActiveConversationId,
                showConversationLoading = isConversationRestoring || initialConversationRestorePending,
                liveMessagesEmpty = messages.isEmpty()
            )
        ) {
            messages
        } else if (shouldUseSelectedConversationPreview(
                selectedConversationId = selectedConversationId,
                runtimeConversationId = runtimeConversationId,
                activeConversationId = activeUiConversationId,
                showConversationLoading = isConversationRestoring || initialConversationRestorePending
            )
        ) {
            selectedConversationMessages
        } else {
            messages
        }
        val message = activeMessages.find { it.id == id }
        
        if (message?.isPlan == true) {
            AgentService.handlePlanModified(context, ollamaService, settingsRepository, agentService, id, editingText)
        } else {
            AgentService.updateMessage(id) { it.copy(content = editingText) }
            AgentService.truncateHistoryAt(id, inclusive = false)
            triggerAgent()
        }
        
        editingMessageId = null
    }

    fun sendMessage() {
        val currentConversation = runtimeActiveConversationId ?: runtimeConversationId
        if ((inputText.isBlank() && attachedImagePath == null) || isLoading || currentConversation == null || isConversationRestoring) return
        val userMsg = AgentService.Companion.ChatMessage(
            role = "user",
            content = inputText.trim(),
            imagePath = attachedImagePath
        )
        AgentService.addMessage(userMsg)
        inputText = ""
        attachedImagePath = null
        imagePreviewPath = null
        triggerAgent()
    }

    val showConversationLoading = isConversationRestoring || initialConversationRestorePending
    val activeUiConversationId = runtimeActiveConversationId ?: runtimeConversationId
    val renderedMessages = remember(
        messages,
        selectedConversationMessages,
        selectedConversationId,
        runtimeConversationId,
        runtimeActiveConversationId,
        showConversationLoading
    ) {
        if (shouldPreferLiveRuntimeMessages(
                selectedConversationId = selectedConversationId,
                runtimeConversationId = runtimeConversationId,
                activeConversationId = runtimeActiveConversationId,
                showConversationLoading = showConversationLoading,
                liveMessagesEmpty = messages.isEmpty()
            )
        ) {
            messages
        } else if (shouldUseSelectedConversationPreview(
                selectedConversationId = selectedConversationId,
                runtimeConversationId = runtimeConversationId,
                activeConversationId = activeUiConversationId,
                showConversationLoading = showConversationLoading
            )
        ) {
            selectedConversationMessages
        } else {
            messages
        }
    }

    fun updateActiveKnowledgeBases(ids: List<Long>) {
        val conversationId = activeUiConversationId ?: return
        val normalized = ids.distinct().filter { it > 0L }
        AgentService.setSelectedKnowledgeBaseIds(normalized)
        scope.launch {
            db.agentChatDao().updateKnowledgeBaseIds(
                conversationId,
                KnowledgeBaseRepository.selectedKnowledgeBaseIdsToCsv(normalized)
            )
        }
    }
    val showSshWarning = !isAgentConnected &&
        agentConnectionStatus != AgentService.Companion.ConnectionStatus.CONNECTING &&
        agentConnectionStatus != AgentService.Companion.ConnectionStatus.RECONNECTING

    
    // Smart auto-scroll: only scroll if user is near the bottom
    val isNearBottom by remember {
        derivedStateOf {
            val lastVisibleItem = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            val totalItems = listState.layoutInfo.totalItemsCount
            totalItems == 0 || lastVisibleItem >= totalItems - 3
        }
    }
    
    LaunchedEffect(renderedMessages.size, selectedConversationId, runtimeConversationId) {
        if (renderedMessages.isNotEmpty() && isNearBottom) {
            listState.animateScrollToItem(renderedMessages.size - 1)
        }
    }

    LaunchedEffect(editingMessageId, renderedMessages) {
        val targetId = editingMessageId ?: return@LaunchedEffect
        val editIndex = renderedMessages.indexOfFirst { it.id == targetId }
        if (editIndex >= 0) {
            listState.animateScrollToItem(editIndex)
        }
    }
    
    Scaffold(
        contentWindowInsets = WindowInsets(0.dp, 0.dp, 0.dp, 0.dp),
        topBar = {
            AgentTopBar(
                onShowConversations = { showConversations = true },
                onShowAgentSettings = { showAgentSettings = true },
                onShowSettings = { showConnectionSettings = true },
                onShowSetupInfo = { showSetupInfo = true },
                onShowProjectManagement = { showProjectManagement = true },
                onShowCustomTools = { showCustomTools = true },
                onShowCustomAgents = { showCustomAgents = true },
                showAllOutput = showAllOutput,
                onToggleAllOutput = { showAllOutput = !showAllOutput },
                showDebugPanel = showDebugPanel,
                onToggleDebugPanel = { showDebugPanel = !showDebugPanel },
                onStopAll = { stopGeneration() },
                onNavigateToWorkspace = { navController.navigate(Screen.AgentWorkspace.route) }
            )
        },
        bottomBar = {
            if (editingMessageId == null) {
                Surface(
                    color = MaterialTheme.colorScheme.surface
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        attachedImagePath?.let { imagePath ->
                            AgentImageAttachmentChip(
                                imagePath = imagePath,
                                onPreview = { imagePreviewPath = imagePath },
                                onRemove = { clearImageAttachment() },
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                            )
                        }
                        AgentInputBar(
                            inputText = inputText,
                            onInputTextChange = { inputText = it },
                            isLoading = isLoading,
                            onSend = { sendMessage() },
                            onStop = { stopGeneration() },
                            canSend = activeUiConversationId != null && !showConversationLoading && currentAgent == AgentService.Companion.AgentRole.ORCHESTRATOR,
                            canAttachImage = activeUiConversationId != null && !showConversationLoading && currentAgent == AgentService.Companion.AgentRole.ORCHESTRATOR && orchestratorVisionEnabled,
                            hasImageAttachment = attachedImagePath != null,
                            keyboardPadding = effectiveImePadding,
                            onAttachImage = {
                                if (activeUiConversationId != null && !showConversationLoading && currentAgent == AgentService.Companion.AgentRole.ORCHESTRATOR && orchestratorVisionEnabled) {
                                    photoPickerLauncher.launch(
                                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                    )
                                }
                            }
                        )
                    }
                }
            }
        },
        floatingActionButton = {
            if (!isNearBottom && renderedMessages.isNotEmpty()) {
                androidx.compose.material3.SmallFloatingActionButton(
                    onClick = {
                        scope.launch {
                            listState.animateScrollToItem(renderedMessages.size - 1)
                        }
                    },
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.padding(bottom = 72.dp)
                ) {
                    Icon(
                        Icons.Default.KeyboardArrowDown,
                        contentDescription = stringResource(R.string.llama_scroll_to_bottom)
                    )
                }
            }
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
            ConnectionStatusBar(
                isBackendConnected = if (isAgentLlamaServer) {
                    llamaServerRuntimeState.isConnected
                } else if (isAgentLlamaSwap) {
                    llamaServerRuntimeState.isConnected
                } else {
                    isOllamaConnected
                },
                backendIsRecovering = if (isAgentLlamaServer) {
                    llamaServerRuntimeState.isRefreshing
                } else if (isAgentLlamaSwap) {
                    llamaServerRuntimeState.isRefreshing
                } else {
                    ollamaIsRecovering
                },
                backendHasChecked = if (isAgentLlamaServer) {
                    llamaServerRuntimeState.hasChecked
                } else if (isAgentLlamaSwap) {
                    llamaServerRuntimeState.hasChecked
                } else {
                    ollamaHasChecked
                },
                backendOfflineMessage = if (isAgentLlamaServer) {
                    stringResource(R.string.agent_llama_server_offline)
                } else if (isAgentLlamaSwap) {
                    stringResource(R.string.agent_llama_swap_offline)
                } else {
                    stringResource(R.string.agent_ollama_offline)
                },
                backendReconnectingMessage = if (isAgentLlamaServer) {
                    stringResource(R.string.agent_llama_server_reconnecting)
                } else if (isAgentLlamaSwap) {
                    stringResource(R.string.agent_llama_swap_reconnecting)
                } else {
                    stringResource(R.string.agent_ollama_reconnecting)
                },
                agentConnectionStatus = agentConnectionStatus,
                retryMessage = retryMessage,
                onRetry = {
                    scope.launch {
                        if (isAgentOpenAiBackend) {
                            agentService.refreshLlamaServerRuntimeState(settingsRepository, force = true)
                        } else if (!isOllamaConnected) {
                            ollamaService.checkConnection()
                        }
                        if (agentConnectionStatus == AgentService.Companion.ConnectionStatus.DISCONNECTED) {
                            agentService.connect(sshHost)
                        }
                    }
                }
            )

            if (showSshWarning) {
                SshConnectionWarningCard(
                    title = stringResource(R.string.agent_ssh_required_title),
                    message = stringResource(R.string.agent_ssh_required_desc),
                    onRetry = {
                        scope.launch {
                            val portInt = sshPort.toIntOrNull() ?: 8023
                            agentService.connect(
                                host = sshHost,
                                port = portInt,
                                username = sshUser,
                                password = sshPassword.ifEmpty { "agent" }
                            )
                        }
                    },
                    onOpenSettings = { showConnectionSettings = true }
                )
            }

            AgentActivityBanner(
                statusText = currentStatusText,
                isVisible = isLoading && currentStatusText.isNotBlank()
            )

            AgentContextWindowBanner(
                snapshot = when {
                    promptContextSnapshot?.agentRole == AgentService.Companion.AgentRole.ORCHESTRATOR.name -> promptContextSnapshot
                    else -> lastOrchestratorPromptSnapshot
                }
            )

            if (activeUiConversationId != null) {
                AgentKnowledgeBaseSelector(
                    knowledgeBases = knowledgeBases,
                    selectedIds = selectedKnowledgeBaseIds,
                    onSelectionChange = { updateActiveKnowledgeBases(it) },
                    onManage = { navController.navigate(Screen.KnowledgeBase.route) }
                )
            }
            
            if (showDebugPanel) {
                DebugPanel(
                    debugLog = debugLog,
                    onClear = { AgentService.clearDebugLog() }
                )
            }
            
            when {
                showConversationLoading && renderedMessages.isEmpty() -> {
                    AgentConversationStatePanel(
                        title = hydratingConversationTitle ?: stringResource(R.string.agent_loading_project_title),
                        message = stringResource(R.string.agent_loading_project_desc),
                        showProgress = true,
                        modifier = Modifier.weight(1f)
                    )
                }
                selectedConversationId == null && activeUiConversationId == null -> {
                    AgentConversationStatePanel(
                        title = stringResource(R.string.agent_no_project_loaded_title),
                        message = stringResource(R.string.agent_no_project_loaded_desc),
                        showProgress = false,
                        modifier = Modifier.weight(1f)
                    )
                }
                else -> {
                    AgentChatList(
                        messages = renderedMessages,
                        listState = listState,
                        showAllOutput = showAllOutput,
                        onApprove = { msg -> handleApproval(true, msg) },
                        onDeny = { msg ->
                            pendingDenyMessage = msg
                            denyExplanation = ""
                        },
                        onDelete = { id -> deleteMessage(id) },
                        onRegenerate = { id -> regenerateMessage(id) },
                        onEdit = { id, content -> editMessage(id, content) },
                        editingMessageId = editingMessageId,
                        editingText = editingText,
                        onEditingTextChange = { editingText = it },
                        onSaveEdit = { saveEdit() },
                        onCancelEdit = { editingMessageId = null },
                        onToggleOutput = { id -> AgentService.toggleMessageOutput(id) },
                        onKnowledgeLinkClick = onKnowledgeLinkClick,
                        modifier = Modifier
                            .weight(1f)
                            .then(if (editingMessageId != null) Modifier.imePadding() else Modifier)
                    )
                }
            }
        }
    }

    // Dialogs
    if (showModelSelector) {
        ModelSelectorDialog(
            currentModel = selectedModel,
            availableModels = availableModelsData,
            onModelSelected = { model ->
                AgentService.setSelectedModel(model)
                showModelSelector = false
            },
            onDismiss = { showModelSelector = false },
            onPullModel = { modelName ->
                scope.launch {
                    Toast.makeText(context, context.getString(R.string.agent_downloading_model, modelName), Toast.LENGTH_SHORT).show()
                    ollamaService.pullModel(modelName) { _: String -> }
                    Toast.makeText(context, context.getString(R.string.agent_model_ready, modelName), Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    if (showSetupInfo) {
        SetupInfoDialog(onDismiss = { showSetupInfo = false })
    }

    if (showConnectionSettings) {
        ConnectionSettingsDialog(
            host = sshHost,
            port = sshPort,
            user = sshUser,
            password = sshPassword,
            ollamaUrl = ollamaUrl,
            ollamaService = ollamaService,
            onHostChange = { sshHost = it },
            onPortChange = { sshPort = it },
            onUserChange = { sshUser = it },
            onPasswordChange = { sshPassword = it },
            onOllamaUrlChange = { 
                ollamaService.setBaseUrl(it)
                SettingsRepository(context).setOllamaUrl(it)
            },
            onConnect = {
                scope.launch {
                    val portInt = sshPort.toIntOrNull() ?: 8023
                    if (isAgentOpenAiBackend) {
                        agentService.refreshLlamaServerRuntimeState(settingsRepository, force = true)
                    } else {
                        ollamaService.initFromSettings()
                        ollamaService.checkConnection()
                    }
                    agentService.connect(host = sshHost, port = portInt, username = sshUser, password = sshPassword.ifEmpty { "agent" })
                    showConnectionSettings = false
                }
            },
            onDismiss = { showConnectionSettings = false }
        )
    }

    if (showAgentSettings) {
        // Refresh models list every time the dialog opens
        LaunchedEffect(Unit) {
            if (isAgentOpenAiBackend) {
                agentService.refreshLlamaServerRuntimeState(settingsRepository, force = true)
            } else {
                ollamaService.checkConnection()
            }
        }
        AgentSettingsDialog(
            settingsRepository = settingsRepository,
            availableModels = if (isAgentLlamaSwap) {
                llamaServerRuntimeState.availableModels
            } else {
                availableModels
            },
            availableImageGenerationModels = availableImageGenerationModels,
            onDismiss = { showAgentSettings = false }
        )
    }
    
    // Custom Tools Screen (full screen)
    if (showCustomTools) {
        androidx.compose.ui.window.Dialog(
            onDismissRequest = { showCustomTools = false },
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
        ) {
            CustomToolsScreen(onBack = { showCustomTools = false })
        }
    }
    
    // Custom Agents Screen (full screen)
    if (showCustomAgents) {
        androidx.compose.ui.window.Dialog(
            onDismissRequest = { showCustomAgents = false },
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
        ) {
            CustomAgentsScreen(onBack = { showCustomAgents = false })
        }
    }
    
    // Project Management Screen (Export/Import/Snapshots)
    if (showProjectManagement && activeUiConversationId != null) {
        val currentProjectFolder = AgentService.currentProjectFolder.collectAsState().value ?: "default"
        androidx.compose.ui.window.Dialog(
            onDismissRequest = { showProjectManagement = false },
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
        ) {
            ProjectManagementScreen(
                projectFolder = currentProjectFolder,
                conversationId = activeUiConversationId!!,
                agentService = agentService,
                onBack = { showProjectManagement = false }
            )
        }
    }

    if (showNewProjectDialog) {
        AlertDialog(
            onDismissRequest = { showNewProjectDialog = false },
            title = { Text(stringResource(R.string.agent_new_project_title)) },
            text = {
                Column {
                    Text(stringResource(R.string.agent_new_project_desc), fontSize = 12.sp)
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = newProjectName,
                        onValueChange = { newProjectName = it },
                        label = { Text(stringResource(R.string.agent_project_name_label)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    createNewConversation(if (newProjectName.isNotBlank()) newProjectName else "project")
                    showNewProjectDialog = false
                }) { Text(stringResource(R.string.action_create)) }
            },
            dismissButton = {
                TextButton(onClick = { showNewProjectDialog = false }) { Text(stringResource(R.string.action_cancel)) }
            }
        )
    }
    
    // First-run popup dialog
    if (showFirstRunPopup) {
        AlertDialog(
            onDismissRequest = { showFirstRunPopup = false },
            title = { Text(stringResource(R.string.agent_welcome_title)) },
            text = {
                Column {
                    Text(stringResource(R.string.agent_welcome_desc), style = MaterialTheme.typography.bodyMedium)
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(stringResource(R.string.agent_welcome_step1), style = MaterialTheme.typography.bodySmall)
                    Text(stringResource(R.string.agent_welcome_step2), style = MaterialTheme.typography.bodySmall)
                    Text(stringResource(R.string.agent_welcome_step3), style = MaterialTheme.typography.bodySmall)
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(stringResource(R.string.agent_welcome_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
            },
            confirmButton = {
                TextButton(onClick = { showFirstRunPopup = false }) {
                    Text(stringResource(R.string.action_got_it))
                }
            }
        )
    }
    
    // Deny explanation dialog
    if (pendingDenyMessage != null) {
        AlertDialog(
            onDismissRequest = {
                // Quick deny without explanation
                handleApproval(false, pendingDenyMessage!!)
                pendingDenyMessage = null
            },
            title = { Text(stringResource(R.string.agent_deny_reason_title)) },
            text = {
                Column {
                    Text(stringResource(R.string.agent_deny_reason_desc), fontSize = 12.sp, color = Color.Gray)
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = denyExplanation,
                        onValueChange = { denyExplanation = it },
                        label = { Text(stringResource(R.string.agent_deny_reason_hint)) },
                        modifier = Modifier.fillMaxWidth(),
                        maxLines = 3
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    handleApproval(false, pendingDenyMessage!!, denyExplanation)
                    pendingDenyMessage = null
                    denyExplanation = ""
                }) {
                    Text(stringResource(R.string.action_deny))
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    handleApproval(false, pendingDenyMessage!!)
                    pendingDenyMessage = null
                    denyExplanation = ""
                }) {
                    Text(stringResource(R.string.agent_deny_skip_reason))
                }
            }
        )
    }
    
    // Conversations drawer dialog
    if (showConversations) {
        AlertDialog(
            onDismissRequest = { showConversations = false },
            title = { Text(stringResource(R.string.agent_conversations_title)) },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 400.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    // New conversation button
                    Button(
                        onClick = { 
                            newProjectName = ""
                            showNewProjectDialog = true 
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Add, null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.agent_new_project_btn))
                    }
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    // List of conversations
                    if (conversations.isEmpty()) {
                        Text(
                            stringResource(R.string.agent_no_conversations),
                            modifier = Modifier.padding(16.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        conversations.forEach { conv ->
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                                    .clickable { loadConversation(conv.id) },
                                colors = CardDefaults.cardColors(
                                    containerColor = if (selectedConversationId == conv.id)
                                        MaterialTheme.colorScheme.primaryContainer 
                                    else 
                                        MaterialTheme.colorScheme.surfaceVariant
                                )
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            conv.title,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 14.sp
                                        )
                                        Text(
                                            java.text.SimpleDateFormat("MMM dd, HH:mm")
                                                .format(java.util.Date(conv.updatedAt)),
                                            fontSize = 11.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    IconButton(
                                        onClick = { 
                                            showDeleteConfirmation = conv.id
                                            pendingDeleteFolder = conv.projectFolder
                                        },
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.Delete,
                                            stringResource(R.string.action_delete),
                                            modifier = Modifier.size(18.dp),
                                            tint = MaterialTheme.colorScheme.error
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showConversations = false }) {
                    Text(stringResource(R.string.action_close))
                }
            }
        )
    }

    imagePreviewPath?.let { previewPath ->
        Dialog(onDismissRequest = { imagePreviewPath = null }) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    AsyncImage(
                        model = File(previewPath),
                        contentDescription = stringResource(R.string.agent_image_attachment_title),
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 220.dp, max = 520.dp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    TextButton(
                        onClick = { imagePreviewPath = null },
                        modifier = Modifier.align(Alignment.End)
                    ) {
                        Text(stringResource(R.string.action_close))
                    }
                }
            }
        }
    }
    
    // Delete confirmation dialog
    if (showDeleteConfirmation != null) {
        AlertDialog(
            onDismissRequest = { 
                showDeleteConfirmation = null
                pendingDeleteFolder = null
            },
            icon = { Icon(Icons.Default.Warning, stringResource(R.string.icon_warning_desc), tint = MaterialTheme.colorScheme.error) },
            title = { Text(stringResource(R.string.agent_delete_project_title)) },
            text = {
                Column {
                    Text(stringResource(R.string.agent_delete_project_desc))
                    if (pendingDeleteFolder != null) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            stringResource(R.string.agent_delete_files_warning),
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.error,
                            fontSize = 12.sp
                        )
                        Surface(
                            color = MaterialTheme.colorScheme.errorContainer,
                            shape = RoundedCornerShape(4.dp),
                            modifier = Modifier.padding(top = 4.dp)
                        ) {
                            Text(
                                "rm -rf /workspace/${pendingDeleteFolder}",
                                modifier = Modifier.padding(8.dp),
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirmation?.let { convId ->
                            deleteConversation(convId, pendingDeleteFolder)
                        }
                        showDeleteConfirmation = null
                        pendingDeleteFolder = null
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text(stringResource(R.string.action_delete_everything))
                }
            },
            dismissButton = {
                TextButton(onClick = { 
                    showDeleteConfirmation = null
                    pendingDeleteFolder = null
                }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }
    
}

@Composable
private fun AgentConversationStatePanel(
    title: String,
    message: String,
    showProgress: Boolean,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(20.dp),
        contentAlignment = Alignment.Center
    ) {
        ElevatedCard(
            colors = CardDefaults.elevatedCardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (showProgress) {
                    CircularProgressIndicator()
                } else {
                    Icon(
                        Icons.Default.FolderOpen,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(36.dp)
                    )
                }
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun AgentKnowledgeBaseSelector(
    knowledgeBases: List<KnowledgeBaseEntity>,
    selectedIds: List<Long>,
    onSelectionChange: (List<Long>) -> Unit,
    onManage: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.agent_kb_selector_title),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = if (selectedIds.isEmpty()) {
                            stringResource(R.string.agent_kb_selector_none)
                        } else {
                            stringResource(R.string.agent_kb_selector_count, selectedIds.size)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                TextButton(onClick = onManage) {
                    Text(stringResource(R.string.kb_manage_action))
                }
            }
            if (knowledgeBases.isNotEmpty()) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    knowledgeBases.forEach { kb ->
                        val selected = kb.id in selectedIds
                        FilterChip(
                            selected = selected,
                            onClick = {
                                onSelectionChange(
                                    if (selected) {
                                        selectedIds - kb.id
                                    } else {
                                        (selectedIds + kb.id).distinct()
                                    }
                                )
                            },
                            label = {
                                Text(kb.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        )
                    }
                }
            }
        }
    }
}

private suspend fun persistAgentChatImage(
    context: Context,
    projectFolder: String?,
    uri: Uri
): String = withContext(Dispatchers.IO) {
    val safeProject = projectFolder?.replace(Regex("[^a-zA-Z0-9_-]"), "_").orEmpty().ifBlank { "default" }
    val timestamp = SimpleDateFormat("yyyy-MM-dd_HHmmss", Locale.getDefault()).format(Date())
    val imagesDir = File(context.filesDir, "agent_chat_images/$safeProject").apply { mkdirs() }
    val savedFile = File(imagesDir, "image_$timestamp.${guessAgentImageExtension(context, uri)}")
    context.contentResolver.openInputStream(uri)?.use { input ->
        savedFile.outputStream().use { output ->
            input.copyTo(output)
        }
    } ?: throw IllegalStateException("Unable to open selected image.")
    savedFile.absolutePath
}

private fun guessAgentImageExtension(context: Context, uri: Uri): String {
    val mimeType = context.contentResolver.getType(uri)?.lowercase(Locale.getDefault()).orEmpty()
    return when {
        mimeType.endsWith("/png") -> "png"
        mimeType.endsWith("/webp") -> "webp"
        mimeType.endsWith("/gif") -> "gif"
        mimeType.endsWith("/bmp") -> "bmp"
        mimeType.endsWith("/jpeg") || mimeType.endsWith("/jpg") -> "jpg"
        else -> {
            val path = uri.lastPathSegment?.lowercase(Locale.getDefault()).orEmpty()
            when {
                path.endsWith(".png") -> "png"
                path.endsWith(".webp") -> "webp"
                path.endsWith(".gif") -> "gif"
                path.endsWith(".bmp") -> "bmp"
                path.endsWith(".jpg") || path.endsWith(".jpeg") -> "jpg"
                else -> "jpg"
            }
        }
    }
}
