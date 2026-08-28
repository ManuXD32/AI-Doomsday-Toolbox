package com.example.llamadroid.ui.agent

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.llamadroid.data.SettingsRepository
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.data.db.AgentConversationEntity
import com.example.llamadroid.data.db.AgentMessageEntity
import com.example.llamadroid.service.AgentService
import com.example.llamadroid.service.OllamaService
import com.example.llamadroid.service.StagedFileCache
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * AgentViewModel - Legacy helper for the AI Agent area
 * 
 * Note: AgentScreen now owns the authoritative conversation restore/save/delete
 * flow. This class is retained for older helper paths, but it must not diverge
 * into a second source of truth for active project switching or persistence.
 *
 * Extracted from AgentScreen to improve testability and maintainability.
 * Manages:
 * - Message sending and LLM interactions
 * - Tool execution with rate limiting
 * - Agent delegation and session management
 * - Conversation persistence
 * - Error handling and propagation to UI
 */
class AgentViewModel(
    private val context: Context,
    private val db: AppDatabase,
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    // Services
    val ollamaService = OllamaService(context)
    val agentService = AgentService(context)

    // ========== RATE LIMITING ==========
    private var toolCallCount = 0
    private var lastToolCallReset = System.currentTimeMillis()
    
    companion object {
        const val MAX_TOOL_CALLS_PER_MINUTE = 30
        const val TOOL_CALL_RESET_INTERVAL_MS = 60_000L
    }

    // ========== ERROR STATE ==========
    private val _lastError = MutableStateFlow<AgentError?>(null)
    val lastError: StateFlow<AgentError?> = _lastError.asStateFlow()

    data class AgentError(
        val message: String,
        val toolName: String? = null,
        val isRecoverable: Boolean = true,
        val timestamp: Long = System.currentTimeMillis()
    )

    fun clearError() {
        _lastError.value = null
    }

    // ========== CONVERSATION STATE ==========
    private val _currentConversationId = MutableStateFlow<Long?>(null)
    val currentConversationId: StateFlow<Long?> = _currentConversationId.asStateFlow()

    private val _conversations = MutableStateFlow<List<AgentConversationEntity>>(emptyList())
    val conversations: StateFlow<List<AgentConversationEntity>> = _conversations.asStateFlow()

    // ========== ACTIVE CUSTOM AGENT ==========
    private var _activeCustomAgent: com.example.llamadroid.data.db.CustomAgentEntity? = null
    val activeCustomAgent: com.example.llamadroid.data.db.CustomAgentEntity?
        get() = _activeCustomAgent

    init {
        // Load conversations on init
        viewModelScope.launch {
            db.agentChatDao().getAllConversations().collect { convs ->
                _conversations.value = convs
            }
        }
        
        // Initialize Ollama settings
        ollamaService.initFromSettings()
    }

    // ========== RATE LIMITING ==========
    
    /**
     * Check if we can execute another tool call (rate limiting)
     * Returns true if allowed, false if rate limited
     */
    fun checkToolRateLimit(): Boolean {
        val now = System.currentTimeMillis()
        
        // Reset counter if interval passed
        if (now - lastToolCallReset > TOOL_CALL_RESET_INTERVAL_MS) {
            toolCallCount = 0
            lastToolCallReset = now
        }
        
        if (toolCallCount >= MAX_TOOL_CALLS_PER_MINUTE) {
            _lastError.value = AgentError(
                message = "Rate limit: $MAX_TOOL_CALLS_PER_MINUTE tool calls per minute exceeded. Wait a moment.",
                isRecoverable = true
            )
            AgentService.addDebugLog("⚠️ Rate limit reached: $toolCallCount calls in last minute")
            return false
        }
        
        toolCallCount++
        return true
    }

    /**
     * Get current rate limit status
     */
    fun getRateLimitStatus(): Pair<Int, Int> = Pair(toolCallCount, MAX_TOOL_CALLS_PER_MINUTE)

    // ========== CONVERSATION MANAGEMENT ==========
    
    fun setCurrentConversationId(id: Long?) {
        _currentConversationId.value = id
        if (id != null) {
            viewModelScope.launch {
                settingsRepository.setLastAgentConversationId(id)
            }
        }
    }

    @Deprecated(
        message = "AgentScreen owns authoritative conversation restore. Keep AgentViewModel as helper-only.",
        level = DeprecationLevel.WARNING
    )
    suspend fun loadConversation(conversationId: Long) {
        _currentConversationId.value = conversationId
        settingsRepository.setLastAgentConversationId(conversationId)
        AgentService.clearTransientConversationState()
        AgentService.clearAllSessions()
        
        val conv = db.agentChatDao().getConversation(conversationId)
        if (conv != null) {
            AgentService.setCurrentProjectFolder(conv.projectFolder)
            conv.lastAgentRole?.let { role ->
                try {
                    AgentService.setCurrentAgent(AgentService.Companion.AgentRole.valueOf(role))
                } catch (e: Exception) {
                    AgentService.setCurrentAgent(AgentService.Companion.AgentRole.ORCHESTRATOR)
                }
            }
            AgentService.setCurrentTask(conv.lastTask)
        }
        
        // Load messages
        val entities = db.agentChatDao().getMessagesForConversationSync(conversationId)
        val restoredMessages = entities.map { AgentService.chatMessageFromEntity(it) }
        AgentService.resetMessageCounter(restoredMessages.maxOfOrNull { it.sequenceNumber } ?: 0)
        AgentService.setMessages(restoredMessages)
        AgentService.restoreHardCompactionStateFromBrain()
        if (restoredMessages.isNotEmpty()) {
            AgentService.addDebugLog("📜 Restored ${restoredMessages.size} messages")
        }
        AgentService.recordAgentEvent(
            "conversation_resume",
            "Loaded conversation ${conv?.title ?: conversationId}",
            "Project folder: ${conv?.projectFolder ?: "unknown"}"
        )
    }

    @Deprecated(
        message = "AgentScreen owns authoritative conversation activation and creation.",
        level = DeprecationLevel.WARNING
    )
    suspend fun createNewConversation(projectName: String = ""): Long {
        val folderName = if (projectName.isNotBlank()) {
            projectName.lowercase().replace(Regex("[^a-z0-9_-]"), "_")
        } else {
            "project_${System.currentTimeMillis()}"
        }
        
        val conversation = AgentConversationEntity(
            title = if (projectName.isNotBlank()) projectName else "New Project",
            projectFolder = folderName,
            planningModeEnabled = true,
            lastAgentRole = AgentService.Companion.AgentRole.ORCHESTRATOR.name
        )
        
        val newId = db.agentChatDao().insertConversation(conversation)
        AgentService.clearTransientConversationState()
        AgentService.clearMessages()
        AgentService.clearAllSessions()
        StagedFileCache.clear()
        AgentService.setCurrentProjectFolder(folderName)
        AgentService.setCurrentAgent(AgentService.Companion.AgentRole.ORCHESTRATOR)
        AgentService.setCurrentTask(null)
        _activeCustomAgent = null
        AgentService.recordAgentEvent("conversation_new", "Created conversation ${conversation.title}", "Project folder: $folderName")
        
        _currentConversationId.value = newId
        settingsRepository.setLastAgentConversationId(newId)
        
        // Create brain folder
        viewModelScope.launch {
            agentService.connect()
            val brainPath = AgentService.getBrainPath()
            // mkdir is done automatically on writeMemory
        }
        
        return newId
    }

    @Deprecated(
        message = "AgentScreen owns authoritative conversation persistence.",
        level = DeprecationLevel.WARNING
    )
    suspend fun saveCurrentConversation() {
        val convId = _currentConversationId.value ?: return
        val messages = AgentService.messages.value
        val currentAgent = AgentService.currentAgent.value
        val currentTask = AgentService.currentTask.value
        
        // Update conversation metadata
        db.agentChatDao().updateConversation(
            db.agentChatDao().getConversation(convId)?.copy(
                lastAgentRole = currentAgent.name,
                lastTask = currentTask,
                updatedAt = System.currentTimeMillis()
            ) ?: return
        )
        
        // Save messages
        db.agentChatDao().deleteAllMessagesInConversation(convId)
        val entities = messages
            .filterNot { AgentService.isTransientCompactionStatusMessageForPersistence(it) }
            .map { msg ->
            AgentService.chatMessageToEntity(msg, convId)
            }
        db.agentChatDao().insertMessages(entities)
    }

    @Deprecated(
        message = "AgentScreen owns authoritative conversation deletion and fallback selection.",
        level = DeprecationLevel.WARNING
    )
    suspend fun deleteConversation(conversationId: Long, projectFolder: String?) {
        db.agentChatDao().deleteConversationById(conversationId)
        
        if (projectFolder != null) {
            // Note: We don't delete workspace here - user must confirm separately
            // The project folder deletion is handled by the UI confirmation dialog
            AgentService.addDebugLog("🗑️ Project $projectFolder marked for deletion")
        }
        
        if (_currentConversationId.value == conversationId) {
            _currentConversationId.value = null
            AgentService.clearMessages()
            AgentService.clearAllSessions()
        }
    }

    // ========== ERROR PROPAGATION ==========
    
    /**
     * Report an error that should be shown to the user
     */
    fun reportError(message: String, toolName: String? = null, isRecoverable: Boolean = true) {
        _lastError.value = AgentError(
            message = message,
            toolName = toolName,
            isRecoverable = isRecoverable
        )
        
        // Also add to chat so user sees it
        AgentService.addMessage(AgentService.Companion.ChatMessage(
            role = "system",
            content = "⚠️ **Error${toolName?.let { " in $it" } ?: ""}:** $message"
        ))
        
        AgentService.addDebugLog("❌ Error: $message")
    }

    // ========== ACTIVE AGENT MANAGEMENT ==========
    
    fun setActiveCustomAgent(agent: com.example.llamadroid.data.db.CustomAgentEntity?) {
        _activeCustomAgent = agent
    }

    // ========== VIEWMODEL FACTORY ==========
    
    class Factory(
        private val context: Context,
        private val db: AppDatabase,
        private val settingsRepository: SettingsRepository
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(AgentViewModel::class.java)) {
                return AgentViewModel(context, db, settingsRepository) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class")
        }
    }
}
