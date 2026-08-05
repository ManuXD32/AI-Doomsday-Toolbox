package com.example.llamadroid.ui.agent

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.room.withTransaction
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.DragInteraction
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
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalClipboardManager
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
import com.example.llamadroid.data.db.AgentConversationEntity
import com.example.llamadroid.data.db.AgentMessageEntity
import com.example.llamadroid.data.db.AgentProjectFolderEntity
import com.example.llamadroid.data.db.KnowledgeBaseEntity
import com.example.llamadroid.data.db.ModelEntity
import com.example.llamadroid.data.db.ModelType
import com.example.llamadroid.data.repository.KnowledgeBaseRepository
import com.example.llamadroid.service.AgentForegroundService
import com.example.llamadroid.service.AgentLocalRuntimeCapabilities
import com.example.llamadroid.service.AgentLocalWorkspaceSupport
import com.example.llamadroid.service.AiRuntimeJobStore
import com.example.llamadroid.service.AgentWorkspaceBackendType
import com.example.llamadroid.service.AgentSkillRepository
import com.example.llamadroid.service.AgentService
import com.example.llamadroid.service.GenerationDiagnosticsStore
import com.example.llamadroid.service.OllamaService
import com.example.llamadroid.service.StagedFileCache
import com.example.llamadroid.onnx.isOnnxTxt2ImgBundle
import com.example.llamadroid.onnx.isOnnxBackgroundRemovalModel
import com.example.llamadroid.sd.isSdImageMainModel
import com.example.llamadroid.service.supportsSdTxt2Img
import com.example.llamadroid.ui.components.ApprovalQueueDialog
import com.example.llamadroid.ui.components.AppPageBackground
import com.example.llamadroid.ui.navigation.Screen
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    val skillRepository = remember { AgentSkillRepository(context.applicationContext, db) }
    val lifecycleOwner = LocalLifecycleOwner.current
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
    // Initialize Ollama URL from saved settings
    remember { ollamaService.initFromSettings() }

    // State - use STATIC companion object for navigation persistence
    val messages by AgentService.messages.collectAsStateWithLifecycle()
    val isLoading by AgentService.isLoading.collectAsStateWithLifecycle()
    val pendingUrgentGuidanceCount by AgentService.pendingUrgentUserGuidanceCount.collectAsStateWithLifecycle()
    val selectedModel by AgentService.selectedModel.collectAsStateWithLifecycle()
    val currentAgent by AgentService.currentAgent.collectAsStateWithLifecycle()
    val currentTask by AgentService.currentTask.collectAsStateWithLifecycle()
    val runtimeActiveConversationId by AgentService.activeConversationId.collectAsStateWithLifecycle()
    val selectedKnowledgeBaseIds by AgentService.selectedKnowledgeBaseIds.collectAsStateWithLifecycle()
    val currentProjectFolder by AgentService.currentProjectFolder.collectAsStateWithLifecycle()
    val currentWorkspaceBackend by AgentService.currentWorkspaceBackend.collectAsStateWithLifecycle()
    val currentPlanningModeEnabled by AgentService.currentPlanningModeEnabled.collectAsStateWithLifecycle()

    // UI Local state
    var inputText by rememberSaveable { mutableStateOf("") }
    var attachedImagePath by remember { mutableStateOf<String?>(null) }
    var imagePreviewPath by remember { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()
    val agentBackend by settingsRepository.agentBackend.collectAsStateWithLifecycle()
    val isAgentLlamaServer = SettingsRepository.isLlamaServerBackend(agentBackend)
    val isAgentLlamaSwap = SettingsRepository.isLlamaSwapBackend(agentBackend)
    val isAgentLiteRt = SettingsRepository.isLiteRtBackend(agentBackend)
    val isAgentOpenAiBackend = SettingsRepository.usesOpenAiChatBackend(agentBackend)
    val llamaServerUrl by settingsRepository.llamaServerUrl.collectAsStateWithLifecycle()
    val llamaSwapUrl by settingsRepository.agentLlamaSwapUrl.collectAsStateWithLifecycle()
    val llamaServerRuntimeState by AgentService.llamaServerRuntimeState.collectAsStateWithLifecycle()
    val orchestratorVisionEnabled by settingsRepository.agentOrchestratorVisionEnabled.collectAsStateWithLifecycle()

    var runtimeConversationId by rememberSaveable { mutableStateOf<Long?>(null) }
    var selectedConversationId by rememberSaveable { mutableStateOf<Long?>(null) }
    val journalConversationId = selectedConversationId ?: runtimeActiveConversationId ?: runtimeConversationId
    val projectJournalFlow = remember(journalConversationId) {
        journalConversationId?.let { db.agentChatDao().getProjectEvents(it, 1000) }
            ?: flowOf(emptyList<com.example.llamadroid.data.db.AgentProjectEventEntity>())
    }
    val projectJournalEvents by projectJournalFlow.collectAsState(initial = emptyList())
    var isConversationRestoring by remember { mutableStateOf(false) }
    var hydratingConversationTitle by remember { mutableStateOf<String?>(null) }
    var initialConversationRestorePending by remember { mutableStateOf(false) }
    var showConversations by remember { mutableStateOf(false) }
    val conversationSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var restoreToken by remember { mutableIntStateOf(0) }

    LaunchedEffect(agentBackend, llamaServerUrl, llamaSwapUrl) {
        delay(350)
        if (isAgentOpenAiBackend) {
            val remoteUrl = if (isAgentLlamaSwap) llamaSwapUrl else llamaServerUrl
            if (remoteUrl.isNotBlank()) {
                agentService.refreshLlamaServerRuntimeState(settingsRepository, force = true)
            }
        } else if (!isAgentLiteRt) {
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
            if (AgentService.activeConversationId.value == conversationId) {
                agentService.persistVisibleRuntimeStateNow("Conversation autosave.")
                return@launch
            }
            val entities = snapshot
                .filterNot { AgentService.isTransientCompactionStatusMessageForPersistence(it) }
                .map { msg ->
                    AgentService.chatMessageToEntity(msg, conversationId)
                }
            db.withTransaction {
                db.agentChatDao().updateConversationState(
                    conversationId,
                    currentAgentRef.name,
                    currentTaskRef
                )
                // This fallback is only for a non-runtime conversation. Active Agent
                // persistence is serialized by AgentService to avoid competing
                // delete/reinsert transactions.
                db.agentChatDao().deleteAllMessagesInConversation(conversationId)
                db.agentChatDao().insertMessages(entities)
                }
        }
    }
    // --- End Core Functions ---
    var showModelSelector by remember { mutableStateOf(false) }
    var showSetupInfo by remember { mutableStateOf(false) }
    var showConnectionSettings by remember { mutableStateOf(false) }
    var showDebugPanel by remember { mutableStateOf(false) }
    var showAgentSettings by remember { mutableStateOf(false) }
    var showToolSettings by remember { mutableStateOf(false) }
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
    var showSkillManager by remember { mutableStateOf(false) }
    var showCommands by remember { mutableStateOf(false) }
    var showTodos by rememberSaveable { mutableStateOf(false) }
    var showBuildSwitchOffer by remember { mutableStateOf(false) }
    var showProjectManagement by remember { mutableStateOf(false) } // Project Management screen
    var showDeleteConfirmation by remember { mutableStateOf<Long?>(null) } // Delete confirmation dialog
    var pendingDeleteFolder by remember { mutableStateOf<String?>(null) } // Folder to delete
    var pendingDeleteProject by remember { mutableStateOf<AgentConversationEntity?>(null) }
    var newProjectName by remember { mutableStateOf("") }
    var newProjectBackend by remember { mutableStateOf(AgentWorkspaceBackendType.REMOTE_SSH) }
    var targetFolderForNewProject by rememberSaveable { mutableStateOf<Long?>(null) }
    var showNewFolderDialog by remember { mutableStateOf(false) }
    var newFolderName by remember { mutableStateOf("") }
    var targetFolderForNewFolder by remember { mutableStateOf<Long?>(null) }
    var renameFolderTarget by remember { mutableStateOf<AgentProjectFolderEntity?>(null) }
    var renameProjectTarget by remember { mutableStateOf<AgentConversationEntity?>(null) }
    var renameText by remember { mutableStateOf("") }
    var moveProjectTarget by remember { mutableStateOf<AgentConversationEntity?>(null) }
    var moveProjectBatch by remember { mutableStateOf<List<AgentConversationEntity>>(emptyList()) }
    var moveFolderTarget by remember { mutableStateOf<AgentProjectFolderEntity?>(null) }
    var moveTargetFolderId by remember { mutableStateOf<Long?>(null) }
    var editingMessageId by remember { mutableStateOf<String?>(null) }
    var editingText by remember { mutableStateOf("") }
    var resolvingPlanMessageId by remember { mutableStateOf<String?>(null) }
    var pendingDenyMessage by remember { mutableStateOf<AgentService.Companion.ChatMessage?>(null) }
    var pendingActiveUserMessage by remember { mutableStateOf<AgentService.Companion.ChatMessage?>(null) }
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
    val projectFolders by db.agentChatDao().getProjectFolders().collectAsState(initial = emptyList())
    val knowledgeBases by knowledgeBaseRepository.observeKnowledgeBases().collectAsState(initial = emptyList())
    val activeRuntimeJobs by db.aiRuntimeJobDao().observeActiveJobs().collectAsState(initial = emptyList())
    val localProjectRunStates by agentService.localProjectRunStates.collectAsStateWithLifecycle()
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

    val installedOnnxModels by db.modelDao()
        .getModelsByTypes(listOf(ModelType.ONNX_IMAGE_GEN, ModelType.ONNX_BACKGROUND_REMOVAL))
        .collectAsState(initial = emptyList())
    val availableImageGenerationModels = remember(installedOnnxModels) {
        installedOnnxModels.filter { it.isOnnxTxt2ImgBundle() }.map { it.filename }
    }
    val availableBackgroundRemovalModels = remember(installedOnnxModels) {
        installedOnnxModels.filter { it.isOnnxBackgroundRemovalModel() }.map { it.filename }
    }
    val installedSdImageMainModels by db.modelDao()
        .getModelsByTypes(listOf(ModelType.SD_CHECKPOINT, ModelType.SD_DIFFUSION))
        .collectAsState(initial = emptyList())
    val availableSdImageMainModels = remember(installedSdImageMainModels) {
        installedSdImageMainModels.filter { it.isSdImageMainModel() && it.supportsSdTxt2Img() }
    }
    val availableSdImageSupportModels by db.modelDao()
        .getModelsByTypes(
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
        .collectAsState(initial = emptyList())
    
    // Load custom agents from database
    val customAgents by db.customAgentDao().getEnabledAgents().collectAsState(initial = emptyList())
    LaunchedEffect(customAgents) {
        AgentService.setLoadedCustomAgents(customAgents)
    }
    val installedSkills by db.agentWorkflowDao().observeSkills().collectAsState(initial = emptyList())

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
    val skillZipLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        val temporary = File(context.cacheDir, "agent_skill_import_${System.currentTimeMillis()}.zip")
                        try {
                            context.contentResolver.openInputStream(uri).use { input ->
                                requireNotNull(input) { "Could not read the selected skill archive" }
                                temporary.outputStream().use(input::copyTo)
                            }
                            skillRepository.installZip(temporary)
                        } finally {
                            temporary.delete()
                        }
                    }
                }
                Toast.makeText(
                    context,
                    result.fold(
                        onSuccess = { context.getString(R.string.agent_skill_import_success, it.name) },
                        onFailure = { context.getString(R.string.agent_skill_import_failed, it.message ?: it.javaClass.simpleName) }
                    ),
                    Toast.LENGTH_LONG
                ).show()
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

    fun createProjectFolder(parentId: Long?) {
        scope.launch {
            val name = newFolderName.trim()
            if (name.isBlank()) return@launch
            val sortOrder = (projectFolders.filter { it.parentId == parentId }.maxOfOrNull { it.sortOrder } ?: -1) + 1
            db.agentChatDao().insertProjectFolder(
                AgentProjectFolderEntity(
                    parentId = parentId,
                    name = name.take(80),
                    sortOrder = sortOrder
                )
            )
            newFolderName = ""
            targetFolderForNewFolder = null
            showNewFolderDialog = false
        }
    }

    fun isFolderDescendant(folderId: Long, possibleParentId: Long?): Boolean {
        var current = possibleParentId
        val byId = projectFolders.associateBy { it.id }
        while (current != null) {
            if (current == folderId) return true
            current = byId[current]?.parentId
        }
        return false
    }

    fun moveProject(conv: AgentConversationEntity, folderId: Long?) {
        scope.launch {
            val sortOrder = (conversations.filter { it.projectFolderId == folderId }.maxOfOrNull { it.sortOrder } ?: -1) + 1
            db.agentChatDao().moveConversationToFolder(conv.id, folderId, sortOrder)
            moveProjectTarget = null
            moveTargetFolderId = null
        }
    }

    fun moveProjects(projects: List<AgentConversationEntity>, folderId: Long?) {
        scope.launch {
            var sortOrder = (conversations.filter { it.projectFolderId == folderId }.maxOfOrNull { it.sortOrder } ?: -1) + 1
            projects.distinctBy { it.id }.forEach { project ->
                db.agentChatDao().moveConversationToFolder(project.id, folderId, sortOrder++)
            }
            moveProjectBatch = emptyList()
            moveTargetFolderId = null
        }
    }

    fun moveFolder(folder: AgentProjectFolderEntity, parentId: Long?) {
        if (folder.id == parentId || isFolderDescendant(folder.id, parentId)) {
            Toast.makeText(context, context.getString(R.string.agent_folder_move_invalid), Toast.LENGTH_SHORT).show()
            return
        }
        scope.launch {
            val sortOrder = (projectFolders.filter { it.parentId == parentId }.maxOfOrNull { it.sortOrder } ?: -1) + 1
            db.agentChatDao().moveProjectFolder(folder.id, parentId, sortOrder)
            moveFolderTarget = null
            moveTargetFolderId = null
        }
    }

    fun reorderProject(conv: AgentConversationEntity, direction: Int) {
        val siblings = conversations
            .filter { it.projectFolderId == conv.projectFolderId }
            .sortedWith(compareBy<AgentConversationEntity> { it.sortOrder }.thenBy { it.title.lowercase(Locale.getDefault()) })
        val index = siblings.indexOfFirst { it.id == conv.id }
        val swap = siblings.getOrNull(index + direction) ?: return
        scope.launch {
            db.agentChatDao().updateConversationSortOrder(conv.id, swap.sortOrder)
            db.agentChatDao().updateConversationSortOrder(swap.id, conv.sortOrder)
        }
    }

    fun reorderFolder(folder: AgentProjectFolderEntity, direction: Int) {
        val siblings = projectFolders
            .filter { it.parentId == folder.parentId }
            .sortedWith(compareBy<AgentProjectFolderEntity> { it.sortOrder }.thenBy { it.name.lowercase(Locale.getDefault()) })
        val index = siblings.indexOfFirst { it.id == folder.id }
        val swap = siblings.getOrNull(index + direction) ?: return
        scope.launch {
            db.agentChatDao().updateProjectFolderSortOrder(folder.id, swap.sortOrder)
            db.agentChatDao().updateProjectFolderSortOrder(swap.id, folder.sortOrder)
        }
    }

    fun deleteFolder(folder: AgentProjectFolderEntity) {
        val hasChildren = projectFolders.any { it.parentId == folder.id } ||
            conversations.any { it.projectFolderId == folder.id }
        if (hasChildren) {
            Toast.makeText(context, context.getString(R.string.agent_folder_delete_not_empty), Toast.LENGTH_SHORT).show()
            return
        }
        scope.launch {
            db.agentChatDao().deleteProjectFolderById(folder.id)
        }
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
        workspaceBackend: AgentWorkspaceBackendType = AgentWorkspaceBackendType.REMOTE_SSH,
        runtimeCapabilities: AgentLocalRuntimeCapabilities = AgentLocalRuntimeCapabilities(),
        planningModeEnabled: Boolean = true,
        dismissPicker: Boolean,
        token: Int? = null
    ) {
        // Re-entering this screen must attach to the already-running singleton runtime.
        // Rehydrating the same project would call clearTransientConversationState(),
        // which cancels the active HTTP worker and makes navigation look like a model failure.
        if (shouldAttachToLiveConversationRuntime(
                targetConversationId = conversationId,
                activeConversationId = AgentService.activeConversationId.value,
                isLoading = AgentService.isLoading.value,
                liveMessagesEmpty = AgentService.messages.value.isEmpty()
            )
        ) {
            runtimeConversationId = conversationId
            selectedConversationId = conversationId
            hydratingConversationTitle = conversationTitle
            AgentService.setPreferredConversationId(conversationId)
            isConversationRestoring = false
            initialConversationRestorePending = false
            if (dismissPicker) showConversations = false
            com.example.llamadroid.service.GenerationDiagnosticsStore.recordBreadcrumb(
                source = "agent_screen",
                event = "attached_to_live_runtime",
                details = "conversation=$conversationId messages=${AgentService.messages.value.size}"
            )
            return
        }
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
        AgentService.setCurrentWorkspaceBackend(workspaceBackend)
        AgentService.setCurrentRuntimeCapabilities(runtimeCapabilities)
        AgentService.setCurrentPlanningModeEnabled(planningModeEnabled)
        AgentService.clearPlanningImplementationUnlock()
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
        if (selectedConversationId == null) return
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
                workspaceBackend = AgentWorkspaceBackendType.fromStored(conv.workspaceBackend),
                runtimeCapabilities = AgentLocalRuntimeCapabilities.fromJson(conv.runtimeCapabilitiesJson),
                planningModeEnabled = conv.planningModeEnabled,
                dismissPicker = dismissPicker,
                token = token
            )
            if (token != restoreToken) return

            if (restoredMessages.isNotEmpty()) {
                AgentService.addDebugLog(context.getString(R.string.agent_restored_messages, restoredMessages.size))
            }
            AgentService.restoreQuestionWorkflow(
                context = context,
                ollamaService = ollamaService,
                settingsRepo = settingsRepository,
                agentService = agentService,
                conversationId = conversationId
            )
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
        if (selectedConversationId == null) return@LaunchedEffect
        val activeJob = resolveRelevantAgentJob() ?: return@LaunchedEffect
        val shouldRestoreFromRuntime = activeJob.type == AiRuntimeJobStore.TYPE_AGENT_CHAT &&
            (runtimeActiveConversationId == null || messages.isEmpty() || activeJob.conversationId == selectedConversationId)
        if (shouldRestoreFromRuntime) {
            reconcileRuntimeUiState(triggerResume = false)
        }
    }
    // AgentService is the single persistence owner for the active conversation. Keeping a
    // second Compose-driven autosave here caused duplicate runtime payload transactions at
    // tool boundaries and made long remote runs substantially more fragile.
    
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

    fun returnToProjectDashboard() {
        restoreToken += 1
        selectedConversationId = null
        runtimeConversationId = null
        hydratingConversationTitle = null
        isConversationRestoring = false
        initialConversationRestorePending = false
        editingMessageId = null
        editingText = ""
        showConversations = false
        clearImageAttachment()
        AgentService.setPreferredConversationId(null)
        scope.launch {
            settingsRepository.setLastAgentConversationId(-1L)
        }
    }

    fun handleApproval(approved: Boolean, msg: AgentService.Companion.ChatMessage, denyReason: String = "") {
        if (msg.isPlan) {
            if (approved) {
                if (resolvingPlanMessageId != null) return
                resolvingPlanMessageId = msg.id
                scope.launch {
                    try {
                        val result = AgentService.approvePendingPlan(context, agentService, msg.id)
                        Toast.makeText(
                            context,
                            if (result.approved) {
                                result.message
                            } else {
                                context.getString(R.string.agent_workflow_plan_approval_failed, result.message)
                            },
                            Toast.LENGTH_LONG
                        ).show()
                    } finally {
                        resolvingPlanMessageId = null
                    }
                }
            } else {
                if (resolvingPlanMessageId != null) return
                resolvingPlanMessageId = msg.id
                scope.launch {
                    try {
                        val result = AgentService.rejectPendingPlan(
                            context = context,
                            agentService = agentService,
                            id = msg.id
                        )
                        Toast.makeText(context, result.message, Toast.LENGTH_LONG).show()
                        if (result.approved && denyReason.isNotBlank()) {
                            AgentService.addMessage(
                                AgentService.Companion.ChatMessage(
                                    role = "user",
                                    content = buildString {
                                        appendLine("The proposed plan was rejected.")
                                        appendLine("Revise it according to these requested changes:")
                                        appendLine()
                                        append(denyReason.trim())
                                    }
                                )
                            )
                            agentService.persistVisibleRuntimeStateNow(
                                "Plan rejected with user revision feedback."
                            )
                            triggerAgent()
                        }
                    } finally {
                        resolvingPlanMessageId = null
                    }
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

    fun continueConversation(conv: AgentConversationEntity) {
        scope.launch {
            beginConversationRestore(conv.id, dismissPicker = true)
            val reason = conv.lastStopReason?.takeIf { it.isNotBlank() }
                ?: when (conv.resumeState) {
                    AgentService.RESUME_STATE_STOPPED_BY_USER -> context.getString(R.string.agent_resume_reason_stopped_by_user)
                    AgentService.RESUME_STATE_INTERRUPTED -> context.getString(R.string.agent_resume_reason_interrupted)
                    AgentService.RESUME_STATE_NEEDS_DIRECTION -> context.getString(R.string.agent_resume_reason_needs_direction)
                    else -> context.getString(R.string.agent_resume_reason_generic)
                }
            AgentService.addMessage(
                AgentService.Companion.ChatMessage(
                    role = "system",
                    content = context.getString(R.string.agent_resume_system_note, reason)
                )
            )
            db.agentChatDao().updateResumeState(conv.id, AgentService.RESUME_STATE_IDLE, null)
            triggerAgent()
        }
    }

    fun createNewConversation(
        projectName: String = context.getString(R.string.agent_project_default_prefix) + System.currentTimeMillis(),
        backend: AgentWorkspaceBackendType = AgentWorkspaceBackendType.REMOTE_SSH,
        parentFolderId: Long? = null
    ) {
        scope.launch {
            val safeName = projectName.trim().replace(Regex("[^a-zA-Z0-9_-]"), "_").take(50).ifBlank { context.getString(R.string.agent_project_default_prefix) + System.currentTimeMillis() }
            val sortOrder = (conversations.filter { it.projectFolderId == parentFolderId }.maxOfOrNull { it.sortOrder } ?: -1) + 1
            val newId = db.agentChatDao().insertConversation(
                AgentConversationEntity(
                    title = projectName,
                    projectFolder = safeName,
                    projectFolderId = parentFolderId,
                    sortOrder = sortOrder,
                    planningModeEnabled = true,
                    workspaceBackend = backend.name
                )
            )
            restoreToken += 1
            runtimeConversationId = newId
            selectedConversationId = newId
            AgentService.setPreferredConversationId(newId)
            isConversationRestoring = false
            initialConversationRestorePending = false
            hydratingConversationTitle = projectName
            AgentService.setActiveCustomAgent(null)
            AgentService.setCurrentWorkspaceBackend(backend)
            AgentService.setCurrentRuntimeCapabilities(AgentLocalRuntimeCapabilities())
            AgentService.setCurrentPlanningModeEnabled(true)
            
            if (backend == AgentWorkspaceBackendType.LOCAL_SANDBOX) {
                withContext(Dispatchers.IO) {
                    AgentLocalWorkspaceSupport.rootForProject(context.applicationContext, safeName)
                    AgentLocalWorkspaceSupport.resolvePath(context.applicationContext, safeName, "brain")
                        .mkdirs()
                }
            } else {
                if (!AgentService.isConnected.value) agentService.connect()
            }
            if (backend == AgentWorkspaceBackendType.REMOTE_SSH && AgentService.isConnected.value) {
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
                workspaceBackend = backend,
                runtimeCapabilities = AgentLocalRuntimeCapabilities(),
                planningModeEnabled = true,
                dismissPicker = true
            )
            agentService.persistVisibleRuntimeStateNow("Created new project conversation $safeName.")
            showConversations = false
        }
    }

    fun deleteConversation(convId: Long, projectFolder: String? = null) {
        scope.launch {
            val conversationToDelete = db.agentChatDao().getConversation(convId)
            val fallbackConversationId = newestConversationIdExcluding(convId)
            val deletingActiveConversation = runtimeConversationId == convId ||
                selectedConversationId == convId ||
                AgentService.activeConversationId.value == convId
            val deleteBackend = AgentWorkspaceBackendType.fromStored(conversationToDelete?.workspaceBackend)
            if (deletingActiveConversation) {
                AgentService.stopAllJobs()
                if (deleteBackend == AgentWorkspaceBackendType.LOCAL_SANDBOX) {
                    agentService.stopLocalProjectRun(force = true)
                }
            }
            AiRuntimeJobStore.deleteByConversationId(context.applicationContext, convId)
            db.agentChatDao().deleteProjectRuns(convId)
            db.agentChatDao().deleteConversationById(convId)
            if (deletingActiveConversation) {
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
                val safeName = AgentLocalWorkspaceSupport.sanitizeProjectFolder(projectFolder)
                if (deleteBackend == AgentWorkspaceBackendType.LOCAL_SANDBOX) {
                    withContext(Dispatchers.IO) {
                        AgentLocalWorkspaceSupport.deleteProjectRoot(context.applicationContext, safeName)
                    }
                } else if (conversationToDelete != null) {
                    agentService.executeRawCommand("rm -rf /workspace/$safeName")
                }
            }
        }
    }

    LaunchedEffect(selectedConversationId, runtimeConversationId, runtimeActiveConversationId) {
        AgentService.setPreferredConversationId(
            selectedConversationId ?: runtimeConversationId
        )
    }

    fun deleteMessage(id: String) = AgentService.deleteMessage(id)
    
    fun regenerateMessage(id: String) {
        if (AgentService.prepareRegenerateAt(id)) {
            triggerAgent(isRedo = true)
        }
    }

    fun editMessage(id: String, content: String) {
        val sourceMessage = messages.firstOrNull { it.id == id }
            ?: selectedConversationMessages.firstOrNull { it.id == id }
        editingMessageId = id
        editingText = if (sourceMessage?.isPlan == true) {
            sourceMessage.planModifiedContent
                ?.takeIf { it.isNotBlank() }
                ?: sourceMessage.content.substringAfter("\n\n", sourceMessage.content)
        } else {
            content
        }
    }

    fun saveEdit() {
        val id = editingMessageId ?: return
        val activeUiConversationId = selectedConversationId?.let { selectedId ->
            runtimeConversationId?.takeIf { it == selectedId }
                ?: runtimeActiveConversationId?.takeIf { it == selectedId }
                ?: selectedId
        }
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
            ?: messages.find { it.id == id }
            ?: selectedConversationMessages.find { it.id == id }
        
        if (message?.isPlan == true) {
            val modifiedPlan = editingText
            if (resolvingPlanMessageId != null) return
            resolvingPlanMessageId = id
            editingMessageId = null
            scope.launch {
                try {
                    val result = AgentService.handlePlanModified(
                        context = context,
                        agentService = agentService,
                        id = id,
                        newContent = modifiedPlan
                    )
                    Toast.makeText(
                        context,
                        if (result.approved) {
                            result.message
                        } else {
                            context.getString(R.string.agent_workflow_plan_approval_failed, result.message)
                        },
                        Toast.LENGTH_LONG
                    ).show()
                    if (!result.approved) {
                        editingMessageId = id
                    }
                } finally {
                    resolvingPlanMessageId = null
                }
            }
        } else {
            AgentService.updateMessage(id) { it.copy(content = editingText) }
            AgentService.truncateHistoryAt(id, inclusive = false)
            triggerAgent()
        }
        
        if (message?.isPlan != true) editingMessageId = null
    }

    fun handleImmediateViewCommand(rawCommand: String, clearDraft: Boolean): Boolean {
        val command = rawCommand.trim().substringBefore(' ').lowercase()
        fun clearDraftIfRequested() {
            if (clearDraft) inputText = ""
        }
        return when (command) {
            "/details" -> {
                showAllOutput = true
                clearDraftIfRequested()
                true
            }
            "/skills" -> {
                showSkillManager = true
                clearDraftIfRequested()
                true
            }
            "/todos" -> {
                showTodos = !showTodos
                clearDraftIfRequested()
                true
            }
            "/agents" -> {
                clearDraftIfRequested()
                navController.currentBackStackEntry?.savedStateHandle?.set("agent_workspace_initial_tab", "agents")
                navController.navigate(Screen.AgentWorkspace.route)
                true
            }
            "/custom-agents" -> {
                showCustomAgents = true
                clearDraftIfRequested()
                true
            }
            "/custom-tools" -> {
                showCustomTools = true
                clearDraftIfRequested()
                true
            }
            "/commands", "/help" -> {
                showCommands = true
                clearDraftIfRequested()
                true
            }
            else -> false
        }
    }

    fun sendMessage() {
        val currentConversation = selectedConversationId?.let { selectedId ->
            runtimeConversationId?.takeIf { it == selectedId }
                ?: runtimeActiveConversationId?.takeIf { it == selectedId }
                ?: selectedId
        }
        if ((inputText.isBlank() && attachedImagePath == null) || currentConversation == null || isConversationRestoring) return
        var outgoingText = inputText.trim()
        val command = outgoingText.substringBefore(' ').lowercase()
        val commandRemainder = outgoingText.substringAfter(' ', "").trim()
        // View commands are intentionally handled before approval/loading gates.
        // They never alter the active model turn and remain useful while it runs.
        if (handleImmediateViewCommand(command, clearDraft = true)) return
        if (AgentService.hasPendingPlanApproval()) {
            Toast.makeText(context, R.string.agent_status_awaiting_approval, Toast.LENGTH_SHORT).show()
            return
        }
        if (isLoading && command in setOf("/plan", "/build", "/compact")) {
            AgentService.queueWorkflowControl(command, commandRemainder)
            inputText = ""
            attachedImagePath = null
            imagePreviewPath = null
            Toast.makeText(context, R.string.agent_workflow_command_queued, Toast.LENGTH_SHORT).show()
            return
        }
        when (command) {
            "/plan" -> {
                AgentService.setCurrentPlanningModeEnabled(true)
                scope.launch { db.agentChatDao().updatePlanningMode(currentConversation, true) }
                inputText = commandRemainder
                if (commandRemainder.isBlank()) return
                outgoingText = commandRemainder
            }
            "/build" -> {
                AgentService.setCurrentPlanningModeEnabled(false)
                scope.launch { db.agentChatDao().updatePlanningMode(currentConversation, false) }
                inputText = commandRemainder
                if (commandRemainder.isBlank()) return
                outgoingText = commandRemainder
            }
            "/compact" -> {
                AgentService.requestManualCompaction(commandRemainder.takeIf { it.isNotBlank() })
                AgentService.addMessage(
                    AgentService.Companion.ChatMessage(
                        role = "system",
                        content = context.getString(R.string.agent_compaction_requested)
                    )
                )
                inputText = ""
                triggerAgent()
                return
            }
        }
        installedSkills.firstOrNull { "/${it.name}".equals(command, ignoreCase = true) }?.let { skill ->
            outgoingText = buildString {
                append("Load the installed skill named \"")
                append(skill.name)
                append("\" with the skill tool and follow it for this turn.")
                if (commandRemainder.isNotBlank()) {
                    append("\n\nTask: ")
                    append(commandRemainder)
                }
            }
        }
        if (outgoingText.startsWith("@")) {
            val selectedAgent = outgoingText.substringBefore(' ').removePrefix("@")
            val delegatedTask = outgoingText.substringAfter(' ', "").trim()
            outgoingText = "Delegate to agent \"$selectedAgent\" using call_agent and use \"$selectedAgent\" as the required invocation name." +
                delegatedTask.takeIf { it.isNotBlank() }?.let { "\n\nTask: $it" }.orEmpty()
        }
        val userMsg = AgentService.Companion.ChatMessage(
            role = "user",
            content = outgoingText,
            imagePath = attachedImagePath
        )
        if (isLoading) {
            AgentService.queueUrgentUserGuidance(userMsg)
            inputText = ""
            attachedImagePath = null
            imagePreviewPath = null
            Toast.makeText(context, R.string.agent_guidance_queued, Toast.LENGTH_SHORT).show()
        } else {
            AgentService.addMessage(userMsg)
            inputText = ""
            attachedImagePath = null
            imagePreviewPath = null
            triggerAgent()
        }
    }

    val showConversationLoading = isConversationRestoring || initialConversationRestorePending
    val activeUiConversationId = selectedConversationId?.let { selectedId ->
        runtimeConversationId?.takeIf { it == selectedId }
            ?: runtimeActiveConversationId?.takeIf { it == selectedId }
            ?: selectedId
    }
    val pendingQuestionsFlow = remember(activeUiConversationId) {
        activeUiConversationId?.let { db.agentWorkflowDao().observePendingQuestions(it) }
            ?: flowOf(emptyList())
    }
    val pendingQuestions by pendingQuestionsFlow.collectAsState(initial = emptyList())
    val invocationFlow = remember(activeUiConversationId) {
        activeUiConversationId?.let { db.agentWorkflowDao().observeInvocations(it) }
            ?: flowOf(emptyList())
    }
    val invocations by invocationFlow.collectAsState(initial = emptyList())
    val delegationsByParentToolCallId = remember(invocations) {
        invocations.mapNotNull { invocation ->
            invocation.parentToolCallId.takeIf { it.isNotBlank() }?.let { toolCallId ->
                toolCallId to AgentDelegationInfo(
                    invocationId = invocation.id,
                    parentToolCallId = toolCallId,
                    displayName = "${invocation.agentClass} - ${invocation.resolvedName}",
                    status = invocation.status,
                    task = invocation.task,
                    returnSummary = invocation.resultSummary,
                    startedAt = invocation.startedAt
                )
            }
        }.toMap()
    }
    val todosFlow = remember(activeUiConversationId) {
        activeUiConversationId?.let { db.agentWorkflowDao().observeTodos(it) }
            ?: flowOf(emptyList())
    }
    val projectTodos by todosFlow.collectAsState(initial = emptyList())
    val queuedOrchestratorInputsFlow = remember(activeUiConversationId) {
        activeUiConversationId?.let { conversationId ->
            db.agentWorkflowDao().observeQueuedInputs(conversationId, null)
        } ?: flowOf(emptyList())
    }
    val queuedOrchestratorInputs by queuedOrchestratorInputsFlow.collectAsState(initial = emptyList())
    val renderedMessages = remember(
        messages,
        selectedConversationMessages,
        queuedOrchestratorInputs,
        selectedConversationId,
        runtimeConversationId,
        runtimeActiveConversationId,
        showConversationLoading
    ) {
        val selectedMessages = if (shouldPreferLiveRuntimeMessages(
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
        val orchestratorMessages = selectedMessages.filter { it.invocationId == null }
        val existingIds = orchestratorMessages.asSequence().map { it.id }.toHashSet()
        orchestratorMessages + queuedOrchestratorInputs
            .asSequence()
            .filter { input ->
                input.content.isNotBlank() &&
                    input.kind in setOf("USER_MESSAGE", "MODE_PLAN", "MODE_BUILD") &&
                    input.id !in existingIds
            }
            .map { AgentService.queuedInputAsChatMessage(it) }
            .toList()
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
    val backendConnected = when {
        isAgentLlamaServer || isAgentLlamaSwap -> llamaServerRuntimeState.isConnected
        isAgentOpenAiBackend -> true
        isAgentLiteRt -> true
        else -> isOllamaConnected
    }
    val backendRecovering = when {
        isAgentLlamaServer || isAgentLlamaSwap -> llamaServerRuntimeState.isRefreshing
        isAgentOpenAiBackend -> false
        isAgentLiteRt -> false
        else -> ollamaIsRecovering
    }
    val backendHasChecked = when {
        isAgentLlamaServer || isAgentLlamaSwap -> llamaServerRuntimeState.hasChecked
        isAgentOpenAiBackend -> true
        isAgentLiteRt -> true
        else -> ollamaHasChecked
    }
    val selectedContextSnapshot = when {
        promptContextSnapshot?.agentRole == AgentService.Companion.AgentRole.ORCHESTRATOR.name -> promptContextSnapshot
        else -> lastOrchestratorPromptSnapshot
    }
    val relevantRuntimeJob = resolveRelevantAgentJob()
    val projectHasActiveAgentJob = relevantRuntimeJob?.let { job ->
        job.type == AiRuntimeJobStore.TYPE_AGENT_CHAT &&
            !AiRuntimeJobStore.isJobStale(job) &&
            job.status == AiRuntimeJobStore.STATUS_RUNNING
    } == true
    val liveGenerationBelongsToSelectedProject =
        isLoading &&
            runtimeActiveConversationId != null &&
            runtimeActiveConversationId == activeUiConversationId
    val visibleAgentIsWorking = liveGenerationBelongsToSelectedProject || projectHasActiveAgentJob
    val visibleStatusText = currentStatusText.ifBlank {
        relevantRuntimeJob?.progressText ?: stringResource(R.string.agent_status_working)
    }
    val activeProjectConversation = conversations.firstOrNull { conv ->
        conv.id == (activeUiConversationId ?: selectedConversationId)
    }
    val activeProjectBackend = activeProjectConversation
        ?.let { AgentWorkspaceBackendType.fromStored(it.workspaceBackend) }
        ?: if (activeUiConversationId != null) currentWorkspaceBackend else null
    val hasSelectedProject = activeUiConversationId != null || selectedConversationId != null

    LaunchedEffect(activeUiConversationId, isLoading, activeRuntimeJobs) {
        if (!isLoading && activeRuntimeJobs.none {
                it.type == AiRuntimeJobStore.TYPE_AGENT_CHAT &&
                    !AiRuntimeJobStore.isJobStale(it) &&
                    it.status == AiRuntimeJobStore.STATUS_RUNNING
            }
        ) {
            AgentForegroundService.reconcileIdleState(context)
        }
    }
    val selectedProjectNeedsSsh = hasSelectedProject && activeProjectBackend != AgentWorkspaceBackendType.LOCAL_SANDBOX
    val activeProjectTitle = activeProjectConversation?.title ?: hydratingConversationTitle
    val activeProjectFolderName = activeProjectConversation?.projectFolder
        ?: currentProjectFolder.takeIf { hasSelectedProject }
    val activeProjectPath = activeProjectFolderName?.let { folderName ->
        if (activeProjectBackend == AgentWorkspaceBackendType.LOCAL_SANDBOX) {
            AgentLocalWorkspaceSupport.displayRoot(folderName)
        } else {
            "/workspace/$folderName"
        }
    }
    val backendLabel = when {
        isAgentLlamaSwap -> stringResource(R.string.agent_console_backend_llama_swap)
        isAgentLlamaServer -> stringResource(R.string.agent_console_backend_llama_server)
        isAgentLiteRt -> stringResource(R.string.agent_console_backend_litert)
        isAgentOpenAiBackend -> stringResource(R.string.agent_console_backend_openai)
        else -> stringResource(R.string.agent_console_backend_ollama)
    }
    val modelLabel = selectedModel.takeIf { it.isNotBlank() }
        ?: stringResource(R.string.agent_console_model_unknown)
    val consoleConnected = hasSelectedProject &&
        (!selectedProjectNeedsSsh || isAgentConnected) &&
        (isAgentLiteRt || backendConnected)
    val connectionLabel = when {
        !hasSelectedProject -> stringResource(R.string.agent_console_ready)
        backendRecovering || (selectedProjectNeedsSsh && agentConnectionStatus == AgentService.Companion.ConnectionStatus.RECONNECTING) ->
            stringResource(R.string.agent_console_recovering)
        selectedProjectNeedsSsh && agentConnectionStatus == AgentService.Companion.ConnectionStatus.CONNECTING ->
            stringResource(R.string.agent_console_connecting)
        consoleConnected -> stringResource(R.string.agent_console_connected)
        else -> stringResource(R.string.agent_console_disconnected)
    }
    val visibleAgentConnectionStatus = if (selectedProjectNeedsSsh) {
        agentConnectionStatus
    } else {
        AgentService.Companion.ConnectionStatus.CONNECTED
    }
    val showConnectionWarnings = hasSelectedProject
    val showSshWarning = selectedProjectNeedsSsh && !isAgentConnected &&
        agentConnectionStatus != AgentService.Companion.ConnectionStatus.CONNECTING &&
        agentConnectionStatus != AgentService.Companion.ConnectionStatus.RECONNECTING

    
    // Streaming changes the size of the final item without changing its index. Only
    // a real user drag may disable follow mode; returning to the exact bottom enables it.
    var followAgentOutput by rememberSaveable { mutableStateOf(true) }
    var manualAgentScrollPending by remember { mutableStateOf(false) }

    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect { interaction ->
            when (interaction) {
                is DragInteraction.Start -> manualAgentScrollPending = true
                is DragInteraction.Stop,
                is DragInteraction.Cancel -> {
                    followAgentOutput = !listState.canScrollForward
                    if (!listState.isScrollInProgress) manualAgentScrollPending = false
                }
            }
        }
    }

    LaunchedEffect(listState) {
        snapshotFlow {
            Triple(
                manualAgentScrollPending,
                listState.isScrollInProgress,
                listState.canScrollForward
            )
        }.collect { (manual, scrolling, canScrollForward) ->
            if (manual) {
                followAgentOutput = !canScrollForward
                if (!scrolling) manualAgentScrollPending = false
            }
        }
    }
    
    LaunchedEffect(renderedMessages.size, selectedConversationId, runtimeConversationId, followAgentOutput) {
        GenerationDiagnosticsStore.recordBreadcrumb(
            source = "agent_render",
            event = "chat_projection_changed",
            details = "renderedMessages=${renderedMessages.size} totalMessages=${messages.size} follow=$followAgentOutput"
        )
        combine(AgentService.streamingContent, AgentService.streamingThinking) { content, thinking ->
            content.length to thinking.length
        }.distinctUntilChanged().let { streamSizes ->
            var lastRecordedStreamBucket = -1
            var lastAutoScrollAt = 0L
            streamSizes.collect { (contentChars, thinkingChars) ->
                val totalItems = listState.layoutInfo.totalItemsCount
                val now = android.os.SystemClock.elapsedRealtime()
                if (
                    totalItems > 0 &&
                    followAgentOutput &&
                    !listState.isScrollInProgress &&
                    now - lastAutoScrollAt >= 350L
                ) {
                    listState.scrollToItem(totalItems - 1, Int.MAX_VALUE)
                    lastAutoScrollAt = now
                }
                val streamBucket = (contentChars + thinkingChars) / 8_000
                if ((contentChars + thinkingChars) > 0 && streamBucket > lastRecordedStreamBucket) {
                    lastRecordedStreamBucket = streamBucket
                    GenerationDiagnosticsStore.recordBreadcrumb(
                        source = "agent_render",
                        event = "stream_preview_size",
                        details = "contentChars=$contentChars thinkingChars=$thinkingChars renderedMessages=${renderedMessages.size}"
                    )
                }
            }
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
                onShowDashboard = { returnToProjectDashboard() },
                onShowAgentSettings = { showAgentSettings = true },
                onShowToolSettings = { showToolSettings = true },
                onShowSettings = { showConnectionSettings = true },
                onShowSetupInfo = { showSetupInfo = true },
                onShowProjectManagement = { showProjectManagement = true },
                onShowCustomTools = { showCustomTools = true },
                onShowCustomAgents = { showCustomAgents = true },
                onShowSkills = { showSkillManager = true },
                onShowCommands = { showCommands = true },
                showAllOutput = showAllOutput,
                onToggleAllOutput = { showAllOutput = !showAllOutput },
                showDebugPanel = showDebugPanel,
                onToggleDebugPanel = { showDebugPanel = !showDebugPanel },
                onStopAll = { stopGeneration() },
                onNavigateToWorkspace = { navController.navigate(Screen.AgentWorkspace.route) }
            )
        },
        bottomBar = {
            if (editingMessageId == null && activeUiConversationId != null) {
                AgentComposerHost {
                        pendingQuestions.firstOrNull()?.let { pendingQuestion ->
                            AgentPendingQuestionPanel(
                                pendingQuestion = pendingQuestion,
                                onSubmit = { answerJson ->
                                    AgentService.answerPendingQuestion(
                                        context = context,
                                        ollamaService = ollamaService,
                                        settingsRepo = settingsRepository,
                                        agentService = agentService,
                                        questionId = pendingQuestion.id,
                                        answerJson = answerJson
                                    )
                                },
                                onDraftChanged = { draftJson, page, collapsed ->
                                    AgentService.savePendingQuestionDraft(
                                        context = context,
                                        questionId = pendingQuestion.id,
                                        draftAnswerJson = draftJson,
                                        currentPage = page,
                                        isCollapsed = collapsed
                                    )
                                },
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                            )
                        }
                        attachedImagePath?.let { imagePath ->
                            AgentImageAttachmentChip(
                                imagePath = imagePath,
                                onPreview = { imagePreviewPath = imagePath },
                                onRemove = { clearImageAttachment() },
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                            )
                        }
                        AgentCommandSuggestions(
                            input = inputText,
                            installedSkillNames = installedSkills.map { it.name },
                            customAgentNames = AgentService.Companion.AgentRole.entries.map { it.name.lowercase() } +
                                customAgents.map { it.name },
                            onSelect = { inputText = it },
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                        )
                        if (pendingUrgentGuidanceCount > 0) {
                            Text(
                                text = stringResource(
                                    R.string.agent_messages_queued_count,
                                    pendingUrgentGuidanceCount
                                ),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
                            )
                        }
                        AgentInputBar(
                            inputText = inputText,
                            onInputTextChange = { inputText = it },
                            isLoading = visibleAgentIsWorking,
                            onSend = { sendMessage() },
                            onStop = { stopGeneration() },
                            // This composer always targets the root orchestrator. A child role may
                            // be active in the worker, but view commands and queued guidance must
                            // remain sendable from the main timeline.
                            canSend = activeUiConversationId != null && !showConversationLoading,
                            canAttachImage = activeUiConversationId != null && !showConversationLoading && currentAgent == AgentService.Companion.AgentRole.ORCHESTRATOR && orchestratorVisionEnabled,
                            hasImageAttachment = attachedImagePath != null,
                            keyboardPadding = 0.dp,
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
        },
        floatingActionButton = {
            if (activeUiConversationId != null && !followAgentOutput && renderedMessages.isNotEmpty()) {
                androidx.compose.material3.SmallFloatingActionButton(
                    onClick = {
                        scope.launch {
                            val totalItems = listState.layoutInfo.totalItemsCount
                            if (totalItems > 0) {
                                listState.animateScrollToItem(totalItems - 1, Int.MAX_VALUE)
                            }
                            followAgentOutput = true
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
        AppPageBackground(modifier = Modifier.padding(padding)) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
            ) {
                AgentWorkspaceConsoleHeader(
                    projectTitle = activeProjectTitle,
                    projectPath = activeProjectPath,
                    backendLabel = backendLabel,
                    modelLabel = modelLabel,
                    connectionLabel = connectionLabel,
                    isConnected = consoleConnected,
                    isRunning = hasSelectedProject && visibleAgentIsWorking,
                    statusText = visibleStatusText,
                    contextSnapshot = selectedContextSnapshot,
                    lastSavedAt = relevantRuntimeJob?.updatedAt,
                    planningModeEnabled = currentPlanningModeEnabled,
                    onShowDashboard = { returnToProjectDashboard() },
                    onNavigateToWorkspace = { navController.navigate(Screen.AgentWorkspace.route) },
                    onStopAll = { stopGeneration() },
                    onPlanningModeChanged = { enabled ->
                        val conversationId = activeUiConversationId ?: selectedConversationId ?: return@AgentWorkspaceConsoleHeader
                        AgentService.setCurrentPlanningModeEnabled(enabled)
                        scope.launch {
                            db.agentChatDao().updatePlanningMode(conversationId, enabled)
                        }
                    },
                    onShowAgentSettings = { showAgentSettings = true },
                    onShowKnowledgeBases = { navController.navigate(Screen.KnowledgeBase.route) }
                )

                AgentActivityBanner(
                    statusText = visibleStatusText,
                    isVisible = hasSelectedProject && visibleAgentIsWorking
                )

                if (showConnectionWarnings) {
                    ConnectionStatusBar(
                isBackendConnected = backendConnected,
                backendIsRecovering = backendRecovering,
                backendHasChecked = backendHasChecked,
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
                // SSH is not part of a local workspace. Passing the global SSH
                // state here used to show a false "Agent disconnected" banner.
                agentConnectionStatus = visibleAgentConnectionStatus,
                retryMessage = retryMessage.takeIf { selectedProjectNeedsSsh },
                onRetry = {
                    scope.launch {
                        val backendName = backendLabel
                        AgentService.addDebugLog(context.getString(R.string.agent_retry_debug_start, backendName))
                        if (isAgentOpenAiBackend) {
                            agentService.refreshLlamaServerRuntimeState(settingsRepository, force = true)
                        } else if (!isAgentLiteRt && !isOllamaConnected) {
                            ollamaService.checkConnection()
                        }
                        if (selectedProjectNeedsSsh && agentConnectionStatus == AgentService.Companion.ConnectionStatus.DISCONNECTED) {
                            val portInt = sshPort.toIntOrNull() ?: 8023
                            AgentService.addDebugLog(context.getString(R.string.agent_retry_debug_ssh, sshHost, portInt))
                            agentService.connect(
                                host = sshHost,
                                port = portInt,
                                username = sshUser,
                                password = sshPassword.ifEmpty { "agent" }
                            )
                        }
                        val sshState = if (selectedProjectNeedsSsh) {
                            agentConnectionStatus.name
                        } else {
                            context.getString(R.string.agent_retry_debug_ssh_not_required)
                        }
                        AgentService.addDebugLog(
                            context.getString(
                                R.string.agent_retry_debug_status,
                                backendName,
                                backendConnected.toString(),
                                selectedProjectNeedsSsh.toString(),
                                sshState,
                                retryMessage ?: context.getString(R.string.agent_retry_debug_no_detail)
                            )
                        )
                        AgentService.addDebugLog(context.getString(R.string.agent_retry_debug_done))
                    }
                }
            )
                }

            if (showSshWarning) {
                SshConnectionWarningCard(
                    title = stringResource(R.string.agent_ssh_required_title),
                    message = stringResource(R.string.agent_ssh_required_desc),
                    onRetry = {
                        scope.launch {
                            val portInt = sshPort.toIntOrNull() ?: 8023
                            AgentService.addDebugLog(context.getString(R.string.agent_retry_debug_ssh, sshHost, portInt))
                            agentService.connect(
                                host = sshHost,
                                port = portInt,
                                username = sshUser,
                                password = sshPassword.ifEmpty { "agent" }
                            )
                            AgentService.addDebugLog(
                                context.getString(
                                    R.string.agent_retry_debug_status,
                                    backendLabel,
                                    backendConnected.toString(),
                                    selectedProjectNeedsSsh.toString(),
                                    agentConnectionStatus.name,
                                    retryMessage ?: context.getString(R.string.agent_retry_debug_no_detail)
                                )
                            )
                        }
                    },
                    onOpenSettings = { showConnectionSettings = true }
                )
            }

            if (showDebugPanel) {
                DebugPanel(
                    events = projectJournalEvents,
                    onClear = {
                        journalConversationId?.let { conversationId ->
                            scope.launch(Dispatchers.IO) {
                                db.agentChatDao().clearProjectEvents(conversationId)
                            }
                        }
                        AgentService.clearDebugLog()
                    }
                )
            }

            if (showTodos && activeUiConversationId != null) {
                AgentTodoDialog(
                    todos = projectTodos,
                    onClose = { showTodos = false }
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
                    AgentProjectDashboard(
                        folders = projectFolders,
                        projects = conversations,
                        activeRuntimeJobs = activeRuntimeJobs,
                        localRunStates = localProjectRunStates,
                        activeConversationId = runtimeActiveConversationId,
                        agentIsLoading = visibleAgentIsWorking,
                        onOpenProject = { loadConversation(it.id) },
                        onContinueProject = { continueConversation(it) },
                        onCreateProject = { parentId ->
                            newProjectName = ""
                            newProjectBackend = AgentWorkspaceBackendType.REMOTE_SSH
                            targetFolderForNewProject = parentId
                            showNewProjectDialog = true
                        },
                        onCreateFolder = { parentId ->
                            targetFolderForNewFolder = parentId
                            newFolderName = ""
                            showNewFolderDialog = true
                        },
                        onToggleFolder = { folder ->
                            scope.launch { db.agentChatDao().updateProjectFolderCollapsed(folder.id, !folder.isCollapsed) }
                        },
                        onRenameFolder = { folder ->
                            renameFolderTarget = folder
                            renameProjectTarget = null
                            renameText = folder.name
                        },
                        onRenameProject = { project ->
                            renameProjectTarget = project
                            renameFolderTarget = null
                            renameText = project.title
                        },
                        onMoveFolder = { folder ->
                            moveFolderTarget = folder
                            moveProjectTarget = null
                            moveTargetFolderId = folder.parentId
                        },
                        onDeleteFolder = { folder -> deleteFolder(folder) },
                        onMoveProject = { project ->
                            moveProjectTarget = project
                            moveProjectBatch = emptyList()
                            moveFolderTarget = null
                            moveTargetFolderId = project.projectFolderId
                        },
                        onMoveProjects = { projects ->
                            moveProjectTarget = null
                            moveFolderTarget = null
                            moveProjectBatch = projects
                            moveTargetFolderId = projects.firstOrNull()?.projectFolderId
                        },
                        onReorderFolder = { folder, direction -> reorderFolder(folder, direction) },
                        onReorderProject = { project, direction -> reorderProject(project, direction) },
                        onDeleteProject = { project ->
                            showDeleteConfirmation = project.id
                            pendingDeleteFolder = project.projectFolder
                            pendingDeleteProject = project
                        },
                        onDeleteProjects = { projects ->
                            projects.forEach { project ->
                                deleteConversation(project.id, project.projectFolder)
                            }
                        },
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
                        resolvingPlanMessageId = resolvingPlanMessageId,
                        onToggleOutput = { id -> AgentService.toggleMessageOutput(id) },
                        onKnowledgeLinkClick = onKnowledgeLinkClick,
                        delegationsByParentToolCallId = delegationsByParentToolCallId,
                        onOpenDelegation = { delegation ->
                            navController.navigate(Screen.AgentInvocation.createRoute(delegation.invocationId))
                        },
                        modifier = Modifier
                            .weight(1f)
                    )
                }
                }
            }
        }
    }

    // Dialogs
    pendingActiveUserMessage?.let { pendingMessage ->
        AlertDialog(
            onDismissRequest = { pendingActiveUserMessage = null },
            title = { Text(stringResource(R.string.agent_active_message_title)) },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    modifier = Modifier.verticalScroll(rememberScrollState())
                ) {
                    Text(stringResource(R.string.agent_active_message_body))
                    Surface(
                        onClick = {
                            AgentService.queueUrgentUserGuidance(pendingMessage)
                            pendingActiveUserMessage = null
                            inputText = ""
                            attachedImagePath = null
                            imagePreviewPath = null
                        },
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.primaryContainer
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                stringResource(R.string.agent_active_message_queue),
                                style = MaterialTheme.typography.titleSmall
                            )
                            Text(
                                stringResource(R.string.agent_active_message_queue_desc),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                    Surface(
                        onClick = {
                            pendingActiveUserMessage = null
                            inputText = ""
                            attachedImagePath = null
                            imagePreviewPath = null
                            AgentService.addDebugLog(context.getString(R.string.agent_guidance_interrupting))
                            AgentService.stopAllJobs()
                            AgentService.updateActiveConversationResumeState(AgentService.RESUME_STATE_IDLE, null)
                            AgentService.addMessage(pendingMessage)
                            scope.launch {
                                delay(150)
                                triggerAgent()
                            }
                        },
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.errorContainer
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                stringResource(R.string.agent_active_message_interrupt),
                                style = MaterialTheme.typography.titleSmall
                            )
                            Text(
                                stringResource(R.string.agent_active_message_interrupt_desc),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { pendingActiveUserMessage = null }) {
                    Text(stringResource(android.R.string.cancel))
                }
            }
        )
    }
    if (showBuildSwitchOffer) {
        AlertDialog(
            onDismissRequest = { showBuildSwitchOffer = false },
            title = { Text(stringResource(R.string.agent_plan_approved_switch_build)) },
            text = { Text(stringResource(R.string.agent_mode_build_description)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showBuildSwitchOffer = false
                        val conversationId = activeUiConversationId
                        AgentService.setCurrentPlanningModeEnabled(false)
                        scope.launch {
                            if (conversationId != null) {
                                db.agentChatDao().updatePlanningMode(conversationId, false)
                            }
                            triggerAgent()
                        }
                    }
                ) {
                    Text(stringResource(R.string.agent_switch_to_build))
                }
            },
            dismissButton = {
                TextButton(onClick = { showBuildSwitchOffer = false }) {
                    Text(stringResource(R.string.agent_mode_plan))
                }
            }
        )
    }
    if (showSkillManager) {
        AgentSkillManagerDialog(
            repository = skillRepository,
            conversationId = activeUiConversationId,
            agentKey = currentAgent.name,
            onImportZip = {
                skillZipLauncher.launch(arrayOf("application/zip", "application/octet-stream"))
            },
            onDismiss = { showSkillManager = false }
        )
    }
    if (showCommands) {
        AgentCommandsDialog(
            onCommandSelected = { command ->
                showCommands = false
                if (!handleImmediateViewCommand(command, clearDraft = false)) {
                    // Workflow controls may need a message/focus, so clicking them
                    // primes the composer instead of silently changing the run.
                    inputText = "$command "
                }
            },
            onDismiss = { showCommands = false }
        )
    }
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
            settingsRepository = settingsRepository,
            onHostChange = { sshHost = it },
            onPortChange = { sshPort = it },
            onUserChange = { sshUser = it },
            onPasswordChange = { sshPassword = it },
            onOllamaUrlChange = { 
                ollamaService.setBaseUrl(it)
                settingsRepository.setOllamaUrl(it)
            },
            onConnect = {
                scope.launch {
                    val portInt = sshPort.toIntOrNull() ?: 8023
                    if (isAgentOpenAiBackend) {
                        agentService.refreshLlamaServerRuntimeState(settingsRepository, force = true)
                    } else if (!isAgentLiteRt) {
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
            } else if (!isAgentLiteRt) {
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
            knowledgeBases = knowledgeBases,
            selectedKnowledgeBaseIds = selectedKnowledgeBaseIds,
            availableImageGenerationModels = availableImageGenerationModels,
            availableSdImageMainModels = availableSdImageMainModels,
            availableSdImageSupportModels = availableSdImageSupportModels,
            availableBackgroundRemovalModels = availableBackgroundRemovalModels,
            onKnowledgeBaseSelectionChange = { updateActiveKnowledgeBases(it) },
            onManageKnowledgeBases = { navController.navigate(Screen.KnowledgeBase.route) },
            section = AgentSettingsSection.AGENTS,
            onDismiss = { showAgentSettings = false }
        )
    }
    
    if (showToolSettings) {
        LaunchedEffect(Unit) {
            if (isAgentOpenAiBackend) {
                agentService.refreshLlamaServerRuntimeState(
                    settingsRepository,
                    force = true
                )
            } else if (!isAgentLiteRt) {
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
            knowledgeBases = knowledgeBases,
            selectedKnowledgeBaseIds = selectedKnowledgeBaseIds,
            availableImageGenerationModels = availableImageGenerationModels,
            availableSdImageMainModels = availableSdImageMainModels,
            availableSdImageSupportModels = availableSdImageSupportModels,
            availableBackgroundRemovalModels = availableBackgroundRemovalModels,
            onKnowledgeBaseSelectionChange = { updateActiveKnowledgeBases(it) },
            onManageKnowledgeBases = {
                navController.navigate(Screen.KnowledgeBase.route)
            },
            section = AgentSettingsSection.TOOLS,
            onDismiss = { showToolSettings = false }
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
    activeUiConversationId?.let { conversationId ->
        if (!showProjectManagement) return@let
        val managementProjectFolder = AgentService.currentProjectFolder.collectAsState().value
        androidx.compose.ui.window.Dialog(
            onDismissRequest = { showProjectManagement = false },
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
        ) {
            ProjectManagementScreen(
                projectFolder = managementProjectFolder,
                conversationId = conversationId,
                agentService = agentService,
                onBack = { showProjectManagement = false }
            )
        }
    }

    if (showNewProjectDialog) {
        AlertDialog(
            onDismissRequest = {
                showNewProjectDialog = false
                targetFolderForNewProject = null
            },
            title = { Text(stringResource(R.string.agent_new_project_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.agent_new_project_desc), fontSize = 12.sp)
                    OutlinedTextField(
                        value = newProjectName,
                        onValueChange = { newProjectName = it },
                        label = { Text(stringResource(R.string.agent_project_name_label)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(stringResource(R.string.agent_project_backend_label), fontWeight = FontWeight.SemiBold)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        FilterChip(
                            selected = newProjectBackend == AgentWorkspaceBackendType.REMOTE_SSH,
                            onClick = { newProjectBackend = AgentWorkspaceBackendType.REMOTE_SSH },
                            label = { Text(stringResource(R.string.agent_project_backend_remote), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            leadingIcon = { Icon(Icons.Default.Terminal, null, modifier = Modifier.size(18.dp)) },
                            modifier = Modifier.weight(1f)
                        )
                        FilterChip(
                            selected = newProjectBackend == AgentWorkspaceBackendType.LOCAL_SANDBOX,
                            onClick = { newProjectBackend = AgentWorkspaceBackendType.LOCAL_SANDBOX },
                            label = { Text(stringResource(R.string.agent_project_backend_local), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            leadingIcon = { Icon(Icons.Default.Security, null, modifier = Modifier.size(18.dp)) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Text(
                        if (newProjectBackend == AgentWorkspaceBackendType.LOCAL_SANDBOX) {
                            stringResource(R.string.agent_project_backend_local_desc)
                        } else {
                            stringResource(R.string.agent_project_backend_remote_desc)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    createNewConversation(
                        projectName = if (newProjectName.isNotBlank()) newProjectName else "project",
                        backend = newProjectBackend,
                        parentFolderId = targetFolderForNewProject
                    )
                    showNewProjectDialog = false
                    targetFolderForNewProject = null
                }) { Text(stringResource(R.string.action_create)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    showNewProjectDialog = false
                    targetFolderForNewProject = null
                }) { Text(stringResource(R.string.action_cancel)) }
            }
        )
    }

    if (showNewFolderDialog) {
        AlertDialog(
            onDismissRequest = { showNewFolderDialog = false },
            title = { Text(stringResource(R.string.agent_folder_create_title)) },
            text = {
                OutlinedTextField(
                    value = newFolderName,
                    onValueChange = { newFolderName = it },
                    label = { Text(stringResource(R.string.agent_folder_name_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(onClick = { createProjectFolder(targetFolderForNewFolder) }) {
                    Text(stringResource(R.string.action_create))
                }
            },
            dismissButton = {
                TextButton(onClick = { showNewFolderDialog = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    if (renameFolderTarget != null || renameProjectTarget != null) {
        AlertDialog(
            onDismissRequest = {
                renameFolderTarget = null
                renameProjectTarget = null
            },
            title = { Text(stringResource(R.string.agent_rename_title)) },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    label = { Text(stringResource(R.string.agent_rename_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(onClick = {
                    val nextName = renameText.trim()
                    if (nextName.isNotBlank()) {
                        scope.launch {
                            renameFolderTarget?.let { db.agentChatDao().renameProjectFolder(it.id, nextName.take(80)) }
                            renameProjectTarget?.let { db.agentChatDao().updateConversationTitle(it.id, nextName.take(120)) }
                            renameFolderTarget = null
                            renameProjectTarget = null
                        }
                    }
                }) {
                    Text(stringResource(R.string.action_save))
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    renameFolderTarget = null
                    renameProjectTarget = null
                }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    if (moveProjectTarget != null || moveFolderTarget != null || moveProjectBatch.isNotEmpty()) {
        AgentMoveTargetDialog(
            folders = projectFolders,
            excludedFolderId = moveFolderTarget?.id,
            selectedFolderId = moveTargetFolderId,
            onSelectedFolderChanged = { moveTargetFolderId = it },
            onDismiss = {
                moveProjectTarget = null
                moveProjectBatch = emptyList()
                moveFolderTarget = null
                moveTargetFolderId = null
            },
            onConfirm = {
                moveProjectTarget?.let { moveProject(it, moveTargetFolderId) }
                moveProjectBatch.takeIf { it.isNotEmpty() }?.let { moveProjects(it, moveTargetFolderId) }
                moveFolderTarget?.let { moveFolder(it, moveTargetFolderId) }
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
                pendingDenyMessage = null
                denyExplanation = ""
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
    
    // Conversations picker sheet
    if (showConversations) {
        ModalBottomSheet(
            onDismissRequest = { showConversations = false },
            sheetState = conversationSheetState
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = stringResource(R.string.agent_conversations_title),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = stringResource(R.string.agent_conversations_sheet_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                AgentProjectDashboard(
                    folders = projectFolders,
                    projects = conversations,
                    activeRuntimeJobs = activeRuntimeJobs,
                    localRunStates = localProjectRunStates,
                    activeConversationId = runtimeActiveConversationId,
                    agentIsLoading = isLoading,
                    onOpenProject = { conv ->
                        showConversations = false
                        loadConversation(conv.id)
                    },
                    onContinueProject = { conv ->
                        showConversations = false
                        continueConversation(conv)
                    },
                    onCreateProject = { parentId ->
                        newProjectName = ""
                        newProjectBackend = AgentWorkspaceBackendType.REMOTE_SSH
                        targetFolderForNewProject = parentId
                        showConversations = false
                        showNewProjectDialog = true
                    },
                    onCreateFolder = { parentId ->
                        targetFolderForNewFolder = parentId
                        newFolderName = ""
                        showNewFolderDialog = true
                    },
                    onToggleFolder = { folder ->
                        scope.launch { db.agentChatDao().updateProjectFolderCollapsed(folder.id, !folder.isCollapsed) }
                    },
                    onRenameFolder = { folder ->
                        renameFolderTarget = folder
                        renameProjectTarget = null
                        renameText = folder.name
                    },
                    onRenameProject = { project ->
                        renameProjectTarget = project
                        renameFolderTarget = null
                        renameText = project.title
                    },
                    onMoveFolder = { folder ->
                        moveFolderTarget = folder
                        moveProjectTarget = null
                        moveTargetFolderId = folder.parentId
                    },
                    onDeleteFolder = { folder -> deleteFolder(folder) },
                    onMoveProject = { project ->
                        moveProjectTarget = project
                        moveProjectBatch = emptyList()
                        moveFolderTarget = null
                        moveTargetFolderId = project.projectFolderId
                    },
                    onMoveProjects = { projects ->
                        moveProjectTarget = null
                        moveFolderTarget = null
                        moveProjectBatch = projects
                        moveTargetFolderId = projects.firstOrNull()?.projectFolderId
                    },
                    onReorderFolder = { folder, direction -> reorderFolder(folder, direction) },
                    onReorderProject = { project, direction -> reorderProject(project, direction) },
                    onDeleteProject = { project ->
                        showDeleteConfirmation = project.id
                        pendingDeleteFolder = project.projectFolder
                        pendingDeleteProject = project
                    },
                    onDeleteProjects = { projects ->
                        projects.forEach { project ->
                            deleteConversation(project.id, project.projectFolder)
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 620.dp)
                )

                Spacer(modifier = Modifier.height(8.dp))
            }
        }
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
        val deleteProject = pendingDeleteProject
        val deleteBackend = AgentWorkspaceBackendType.fromStored(deleteProject?.workspaceBackend)
        val deleteFolderName = pendingDeleteFolder
        AlertDialog(
            onDismissRequest = { 
                showDeleteConfirmation = null
                pendingDeleteFolder = null
                pendingDeleteProject = null
            },
            icon = { Icon(Icons.Default.Warning, stringResource(R.string.icon_warning_desc), tint = MaterialTheme.colorScheme.error) },
            title = { Text(stringResource(R.string.agent_delete_project_title)) },
            text = {
                Column {
                    Text(stringResource(R.string.agent_delete_project_desc))
                    if (!deleteFolderName.isNullOrBlank()) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            if (deleteBackend == AgentWorkspaceBackendType.LOCAL_SANDBOX) {
                                stringResource(R.string.agent_delete_local_files_warning)
                            } else {
                                stringResource(R.string.agent_delete_remote_files_warning)
                            },
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
                                if (deleteBackend == AgentWorkspaceBackendType.LOCAL_SANDBOX) {
                                    AgentLocalWorkspaceSupport.displayRoot(deleteFolderName)
                                } else {
                                    "/workspace/$deleteFolderName"
                                },
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
                        pendingDeleteProject = null
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
                    pendingDeleteProject = null
                }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }
    
}

private data class AgentDashboardRow(
    val folder: AgentProjectFolderEntity? = null,
    val project: AgentConversationEntity? = null,
    val depth: Int
)

private fun buildAgentDashboardRows(
    folders: List<AgentProjectFolderEntity>,
    projects: List<AgentConversationEntity>,
    parentId: Long? = null,
    depth: Int = 0
): List<AgentDashboardRow> {
    val folderRows = folders
        .filter { it.parentId == parentId }
        .sortedWith(compareBy<AgentProjectFolderEntity> { it.sortOrder }.thenBy { it.name.lowercase(Locale.getDefault()) })
        .flatMap { folder ->
            buildList {
                add(AgentDashboardRow(folder = folder, depth = depth))
                if (!folder.isCollapsed) {
                    addAll(buildAgentDashboardRows(folders, projects, folder.id, depth + 1))
                }
            }
        }
    val projectRows = projects
        .filter { it.projectFolderId == parentId }
        .sortedWith(compareBy<AgentConversationEntity> { it.sortOrder }.thenBy { it.title.lowercase(Locale.getDefault()) })
        .map { AgentDashboardRow(project = it, depth = depth) }
    return folderRows + projectRows
}

@Composable
@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
private fun AgentProjectDashboard(
    folders: List<AgentProjectFolderEntity>,
    projects: List<AgentConversationEntity>,
    activeRuntimeJobs: List<AiRuntimeJobEntity>,
    localRunStates: Map<Long, com.example.llamadroid.service.AgentLocalRunState>,
    activeConversationId: Long?,
    agentIsLoading: Boolean,
    onOpenProject: (AgentConversationEntity) -> Unit,
    onContinueProject: (AgentConversationEntity) -> Unit,
    onCreateProject: (Long?) -> Unit,
    onCreateFolder: (Long?) -> Unit,
    onToggleFolder: (AgentProjectFolderEntity) -> Unit,
    onRenameFolder: (AgentProjectFolderEntity) -> Unit,
    onRenameProject: (AgentConversationEntity) -> Unit,
    onMoveFolder: (AgentProjectFolderEntity) -> Unit,
    onDeleteFolder: (AgentProjectFolderEntity) -> Unit,
    onMoveProject: (AgentConversationEntity) -> Unit,
    onMoveProjects: (List<AgentConversationEntity>) -> Unit,
    onReorderFolder: (AgentProjectFolderEntity, Int) -> Unit,
    onReorderProject: (AgentConversationEntity, Int) -> Unit,
    onDeleteProject: (AgentConversationEntity) -> Unit,
    onDeleteProjects: (List<AgentConversationEntity>) -> Unit,
    modifier: Modifier = Modifier
) {
    var currentFolderId by rememberSaveable { mutableStateOf<Long?>(null) }
    var actionTarget by remember { mutableStateOf<AgentDashboardActionTarget?>(null) }
    var selectionMode by rememberSaveable { mutableStateOf(false) }
    var selectedProjectIds by rememberSaveable { mutableStateOf(emptyList<Long>()) }
    var confirmBatchDelete by remember { mutableStateOf(false) }
    val foldersById = remember(folders) { folders.associateBy { it.id } }
    val currentFolder = currentFolderId?.let { foldersById[it] }
    LaunchedEffect(folders) {
        if (currentFolderId != null && currentFolder == null) {
            currentFolderId = null
        }
    }
    val childFolders = remember(folders, currentFolderId) {
        folders
            .filter { it.parentId == currentFolderId }
            .sortedWith(compareBy<AgentProjectFolderEntity> { it.sortOrder }.thenBy { it.name.lowercase(Locale.getDefault()) })
    }
    val childProjects = remember(projects, currentFolderId) {
        projects
            .filter { it.projectFolderId == currentFolderId }
            .sortedWith(compareBy<AgentConversationEntity> { it.sortOrder }.thenBy { it.title.lowercase(Locale.getDefault()) })
    }
    val selectedProjects = remember(childProjects, selectedProjectIds) {
        childProjects.filter { it.id in selectedProjectIds }
    }
    LaunchedEffect(currentFolderId, projects) {
        selectedProjectIds = selectedProjectIds.filter { selectedId -> childProjects.any { it.id == selectedId } }
        if (selectedProjectIds.isEmpty() && childProjects.isEmpty()) selectionMode = false
    }
    val breadcrumbs = remember(currentFolderId, folders) {
        buildList {
            var cursor = currentFolderId
            while (cursor != null) {
                val folder = foldersById[cursor] ?: break
                add(folder)
                cursor = folder.parentId
            }
        }.asReversed()
    }

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(R.string.agent_dashboard_title),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = stringResource(R.string.agent_dashboard_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AssistChip(
                        onClick = { currentFolderId = null },
                        label = { Text(stringResource(R.string.agent_folder_root), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        leadingIcon = { Icon(Icons.Default.Home, null, modifier = Modifier.size(18.dp)) }
                    )
                    breadcrumbs.forEach { folder ->
                        AssistChip(
                            onClick = { currentFolderId = folder.id },
                            label = { Text(folder.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            leadingIcon = { Icon(Icons.Default.ChevronRight, null, modifier = Modifier.size(18.dp)) }
                        )
                    }
                }
                currentFolder?.let { folder ->
                    OutlinedButton(
                        onClick = { currentFolderId = folder.parentId },
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, null)
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.agent_folder_go_up), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Button(onClick = { onCreateProject(currentFolderId) }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(8.dp)) {
                        Icon(Icons.Default.Add, null)
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.agent_new_project_btn), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    OutlinedButton(onClick = { onCreateFolder(null) }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(8.dp)) {
                        Icon(Icons.Default.CreateNewFolder, null)
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.agent_folder_create_short), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                OutlinedButton(
                    onClick = {
                        selectionMode = !selectionMode
                        if (!selectionMode) selectedProjectIds = emptyList()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = childProjects.isNotEmpty()
                ) {
                    Icon(if (selectionMode) Icons.Default.Close else Icons.Default.Checklist, null)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        stringResource(
                            if (selectionMode) R.string.action_cancel
                            else R.string.agent_batch_select
                        )
                    )
                }
                if (selectionMode) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.primaryContainer
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                stringResource(R.string.agent_batch_selected_count, selectedProjects.size),
                                fontWeight = FontWeight.SemiBold
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedButton(
                                    onClick = { onMoveProjects(selectedProjects) },
                                    enabled = selectedProjects.isNotEmpty(),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(Icons.AutoMirrored.Filled.DriveFileMove, null)
                                    Spacer(Modifier.width(4.dp))
                                    Text(stringResource(R.string.action_move), maxLines = 1)
                                }
                                Button(
                                    onClick = { confirmBatchDelete = true },
                                    enabled = selectedProjects.isNotEmpty(),
                                    modifier = Modifier.weight(1f),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.error,
                                        contentColor = MaterialTheme.colorScheme.onError
                                    )
                                ) {
                                    Icon(Icons.Default.Delete, null)
                                    Spacer(Modifier.width(4.dp))
                                    Text(stringResource(R.string.action_delete), maxLines = 1)
                                }
                            }
                        }
                    }
                }
            }
        }
        if (childFolders.isEmpty() && childProjects.isEmpty()) {
            item {
                Card(shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Text(
                        stringResource(R.string.agent_dashboard_empty),
                        modifier = Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            items(childFolders, key = { folder -> "folder-${folder.id}" }) { folder ->
                AgentDashboardFolderRow(
                    folder = folder,
                    onOpen = { currentFolderId = folder.id },
                    onLongPress = { actionTarget = AgentDashboardActionTarget(folder = folder) }
                )
            }
            items(childProjects, key = { project -> "project-${project.id}" }) { project ->
                val localRunning = localRunStates[project.id]?.status == "RUNNING"
                val llmWorking = activeRuntimeJobs.any {
                    it.conversationId == project.id &&
                        it.type == AiRuntimeJobStore.TYPE_AGENT_CHAT &&
                        !AiRuntimeJobStore.isJobStale(it) &&
                        it.status == AiRuntimeJobStore.STATUS_RUNNING
                } ||
                        (agentIsLoading && activeConversationId == project.id)
                AgentDashboardProjectRow(
                    project = project,
                    localRunning = localRunning,
                    llmWorking = llmWorking,
                    selectionMode = selectionMode,
                    selected = project.id in selectedProjectIds,
                    onOpen = {
                        if (selectionMode) {
                            selectedProjectIds = if (project.id in selectedProjectIds) {
                                selectedProjectIds - project.id
                            } else {
                                (selectedProjectIds + project.id).distinct()
                            }
                        } else {
                            onOpenProject(project)
                        }
                    },
                    onLongPress = {
                        if (selectionMode) {
                            selectedProjectIds = (selectedProjectIds + project.id).distinct()
                        } else {
                            actionTarget = AgentDashboardActionTarget(project = project)
                        }
                    }
                )
            }
        }
    }

    actionTarget?.let { target ->
        ModalBottomSheet(onDismissRequest = { actionTarget = null }) {
            target.folder?.let { folder ->
                Text(
                    text = stringResource(R.string.agent_dashboard_folder_actions, folder.name),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                AgentDashboardActionItem(Icons.Default.Folder, R.string.action_open) {
                    actionTarget = null
                    currentFolderId = folder.id
                }
                AgentDashboardActionItem(Icons.Default.Add, R.string.agent_new_project_btn) {
                    actionTarget = null
                    onCreateProject(folder.id)
                }
                AgentDashboardActionItem(Icons.Default.CreateNewFolder, R.string.agent_folder_create_short) {
                    actionTarget = null
                    onCreateFolder(folder.id)
                }
                AgentDashboardActionItem(Icons.Default.Edit, R.string.action_rename) {
                    actionTarget = null
                    onRenameFolder(folder)
                }
                AgentDashboardActionItem(Icons.AutoMirrored.Filled.DriveFileMove, R.string.action_move) {
                    actionTarget = null
                    onMoveFolder(folder)
                }
                AgentDashboardActionItem(Icons.Default.KeyboardArrowUp, R.string.agent_move_up) {
                    actionTarget = null
                    onReorderFolder(folder, -1)
                }
                AgentDashboardActionItem(Icons.Default.KeyboardArrowDown, R.string.agent_move_down) {
                    actionTarget = null
                    onReorderFolder(folder, 1)
                }
                AgentDashboardActionItem(Icons.Default.Delete, R.string.action_delete, MaterialTheme.colorScheme.error) {
                    actionTarget = null
                    onDeleteFolder(folder)
                }
            }
            target.project?.let { project ->
                val resumable = project.resumeState != AgentService.RESUME_STATE_IDLE
                Text(
                    text = stringResource(R.string.agent_dashboard_project_actions, project.title),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                AgentDashboardActionItem(Icons.Default.Description, R.string.action_open) {
                    actionTarget = null
                    onOpenProject(project)
                }
                if (resumable) {
                    AgentDashboardActionItem(Icons.Default.PlayArrow, R.string.action_continue) {
                        actionTarget = null
                        onContinueProject(project)
                    }
                }
                AgentDashboardActionItem(Icons.Default.Edit, R.string.action_rename) {
                    actionTarget = null
                    onRenameProject(project)
                }
                AgentDashboardActionItem(Icons.AutoMirrored.Filled.DriveFileMove, R.string.action_move) {
                    actionTarget = null
                    onMoveProject(project)
                }
                AgentDashboardActionItem(Icons.Default.KeyboardArrowUp, R.string.agent_move_up) {
                    actionTarget = null
                    onReorderProject(project, -1)
                }
                AgentDashboardActionItem(Icons.Default.KeyboardArrowDown, R.string.agent_move_down) {
                    actionTarget = null
                    onReorderProject(project, 1)
                }
                AgentDashboardActionItem(Icons.Default.Delete, R.string.action_delete, MaterialTheme.colorScheme.error) {
                    actionTarget = null
                    onDeleteProject(project)
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
    if (confirmBatchDelete) {
        AlertDialog(
            onDismissRequest = { confirmBatchDelete = false },
            title = { Text(stringResource(R.string.agent_batch_delete_title)) },
            text = { Text(stringResource(R.string.agent_batch_delete_message, selectedProjects.size)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDeleteProjects(selectedProjects)
                        selectedProjectIds = emptyList()
                        selectionMode = false
                        confirmBatchDelete = false
                    }
                ) {
                    Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmBatchDelete = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }
}

private data class AgentDashboardActionTarget(
    val folder: AgentProjectFolderEntity? = null,
    val project: AgentConversationEntity? = null
)

@Composable
private fun AgentDashboardActionItem(
    icon: ImageVector,
    labelRes: Int,
    tint: Color = MaterialTheme.colorScheme.onSurface,
    onClick: () -> Unit
) {
    ListItem(
        headlineContent = { Text(stringResource(labelRes)) },
        leadingContent = { Icon(icon, contentDescription = null, tint = tint) },
        modifier = Modifier.clickable(onClick = onClick)
    )
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun AgentDashboardFolderRow(
    folder: AgentProjectFolderEntity,
    onOpen: () -> Unit,
    onLongPress: () -> Unit
) {
    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onOpen, onLongClick = onLongPress),
        shape = RoundedCornerShape(8.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(Icons.Default.Folder, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(36.dp))
            Text(folder.name, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
            Icon(Icons.Default.MoreVert, stringResource(R.string.agent_dashboard_item_options), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun AgentDashboardProjectRow(
    project: AgentConversationEntity,
    localRunning: Boolean,
    llmWorking: Boolean,
    selectionMode: Boolean,
    selected: Boolean,
    onOpen: () -> Unit,
    onLongPress: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onOpen, onLongClick = onLongPress),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(
            modifier = Modifier.padding(14.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (selectionMode) {
                Checkbox(checked = selected, onCheckedChange = { onOpen() })
            }
            Icon(Icons.Default.Description, null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(36.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(project.title, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    if (project.workspaceBackend == AgentWorkspaceBackendType.LOCAL_SANDBOX.name) {
                        stringResource(R.string.agent_project_backend_local)
                    } else {
                        stringResource(R.string.agent_project_backend_remote)
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (localRunning) AgentStatusPill(stringResource(R.string.agent_dashboard_running), Color(0xFF2E7D32))
            if (llmWorking) AgentStatusPill(stringResource(R.string.agent_dashboard_llm_working), Color(0xFFF57C00))
            Icon(Icons.Default.MoreVert, stringResource(R.string.agent_dashboard_item_options), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun AgentStatusPill(text: String, color: Color) {
    Surface(shape = RoundedCornerShape(50), color = color.copy(alpha = 0.16f), contentColor = color) {
        Text(text, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun AgentMoveTargetDialog(
    folders: List<AgentProjectFolderEntity>,
    excludedFolderId: Long?,
    selectedFolderId: Long?,
    onSelectedFolderChanged: (Long?) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    val rows = remember(folders, excludedFolderId) {
        buildAgentDashboardRows(
            folders = folders.filter { it.id != excludedFolderId },
            projects = emptyList()
        )
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.agent_move_to_folder_title)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelectedFolderChanged(null) }
                        .padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(selected = selectedFolderId == null, onClick = { onSelectedFolderChanged(null) })
                    Text(stringResource(R.string.agent_folder_root))
                }
                rows.mapNotNull { it.folder to it.depth }.forEach { (folder, depth) ->
                    if (folder != null) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelectedFolderChanged(folder.id) }
                                .padding(start = (depth * 16).dp, top = 8.dp, bottom = 8.dp, end = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = selectedFolderId == folder.id, onClick = { onSelectedFolderChanged(folder.id) })
                            Icon(Icons.Default.Folder, null)
                            Spacer(Modifier.width(8.dp))
                            Text(folder.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onConfirm) { Text(stringResource(R.string.action_move)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        }
    )
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
fun AgentKnowledgeBaseSelector(
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
