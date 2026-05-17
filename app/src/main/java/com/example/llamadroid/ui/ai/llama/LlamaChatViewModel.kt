package com.example.llamadroid.ui.ai.llama

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.llamadroid.data.model.LlamaChatEntity
import com.example.llamadroid.data.model.LlamaChatFolderEntity
import com.example.llamadroid.data.model.LlamaChatPromptProfileEntity
import com.example.llamadroid.data.model.LlamaMessageEntity
import com.example.llamadroid.data.repository.LlamaRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import com.example.llamadroid.service.LlamaClientService

class LlamaChatViewModel(
    private val repository: LlamaRepository
) : ViewModel() {

    private val _chats = MutableStateFlow<List<LlamaChatEntity>>(emptyList())
    val chats = _chats.asStateFlow()

    private val _folders = MutableStateFlow<List<LlamaChatFolderEntity>>(emptyList())
    val folders = _folders.asStateFlow()

    private val _promptProfiles = MutableStateFlow<List<LlamaChatPromptProfileEntity>>(emptyList())
    val promptProfiles = _promptProfiles.asStateFlow()

    private val _messages = MutableStateFlow<List<LlamaMessageEntity>>(emptyList())
    val messages = _messages.asStateFlow()
    
    // For Chat Screen
    private var currentChatId: Long = -1L
    private var messageCollectionJob: Job? = null

    init {
        viewModelScope.launch {
            repository.allChats.collectLatest {
                _chats.value = it
            }
        }
        viewModelScope.launch {
            repository.allChatFolders.collectLatest {
                _folders.value = it
            }
        }
        viewModelScope.launch {
            repository.allChatPromptProfiles.collectLatest {
                _promptProfiles.value = it
            }
        }
    }

    fun createChat(
        title: String,
        contextSize: Int = 8192,
        systemPrompt: String? = null,
        apiParams: String? = null,
        folderId: Long? = null,
        onCreated: (Long) -> Unit
    ) {
        viewModelScope.launch {
            val id = repository.createChat(title, contextSize, systemPrompt, apiParams, folderId)
            onCreated(id)
        }
    }

    fun updateChat(chat: LlamaChatEntity, newTitle: String, newContextSize: Int, newSystemPrompt: String? = null) {
        viewModelScope.launch {
            repository.updateChatTitle(chat.id, newTitle)
            repository.updateChatContextSize(chat.id, newContextSize)
            repository.updateChatSystemPrompt(chat.id, newSystemPrompt)
        }
    }

    fun updateChatApiParams(chatId: Long, apiParams: String?) {
        viewModelScope.launch {
            repository.updateChatApiParams(chatId, apiParams)
        }
    }

    fun deleteChat(chat: LlamaChatEntity) {
        viewModelScope.launch {
            repository.deleteChat(chat)
        }
    }

    fun createFolder(name: String, onResult: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            val success = runCatching { repository.createChatFolder(name) }.isSuccess
            onResult(success)
        }
    }

    fun deleteFolder(folder: LlamaChatFolderEntity) {
        viewModelScope.launch {
            repository.deleteChatFolder(folder)
        }
    }

    fun moveChatToFolder(chat: LlamaChatEntity, folderId: Long?) {
        viewModelScope.launch {
            repository.updateChatFolder(chat.id, folderId)
        }
    }

    fun createPromptProfile(name: String, content: String, onResult: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            val success = runCatching { repository.createChatPromptProfile(name, content) }.isSuccess
            onResult(success)
        }
    }

    fun updatePromptProfile(
        profile: LlamaChatPromptProfileEntity,
        name: String,
        content: String,
        onResult: (Boolean) -> Unit = {}
    ) {
        viewModelScope.launch {
            val success = runCatching { repository.updateChatPromptProfile(profile, name, content) }.isSuccess
            onResult(success)
        }
    }

    fun deletePromptProfile(profile: LlamaChatPromptProfileEntity) {
        viewModelScope.launch {
            repository.deleteChatPromptProfile(profile)
        }
    }
    
    fun loadMessages(chatId: Long) {
        if (currentChatId == chatId && messageCollectionJob?.isActive == true) {
            return
        }
        if (currentChatId != chatId) {
            LlamaClientService.resetStateIfIdle()
            _messages.value = emptyList()
        }
        currentChatId = chatId
        messageCollectionJob?.cancel()
        messageCollectionJob = viewModelScope.launch {
            repository.getMessages(chatId).collectLatest {
                if (currentChatId == chatId) {
                    _messages.value = it
                }
            }
        }
    }
    
    fun sendMessage(content: String, serverId: Long) {
       // Logic handled in UI/Service via Intent for now, or we can move it here.
       // For better architecture, ViewModel should interact with Repository/Service. 
       // But the service logic is complex. 
       // Let's stick to the Service Intent pattern for generation to keep UI non-blocking 
       // and persistent, but we can save the USER message here immediately for instant feedback.
    }
    
    fun deleteMessage(message: LlamaMessageEntity) {
        viewModelScope.launch {
            repository.deleteMessage(message)
        }
    }

    suspend fun deleteMessageNow(message: LlamaMessageEntity) {
        repository.deleteMessage(message)
    }

    fun clearChat(chatId: Long) {
        viewModelScope.launch {
            repository.clearChatMessages(chatId)
        }
    }

    suspend fun clearChatNow(chatId: Long) {
        repository.clearChatMessages(chatId)
    }
    
    fun updateMessage(id: Long, content: String) {
        viewModelScope.launch {
             // This is for editing user message. 
             // We also need to delete subsequent messages if we want "branching" or "retry" behavior 
             // from that point.
             repository.updateMessage(id, content)
        }
    }

    suspend fun deleteMessagesAfter(chatId: Long, timestamp: Long, messageId: Long) {
        repository.deleteMessagesAfter(chatId, timestamp, messageId)
    }
    
    suspend fun getMessagesOnce(chatId: Long): List<LlamaMessageEntity> {
        return repository.getMessagesOnce(chatId)
    }
    
    fun importChat(
        title: String,
        systemPrompt: String?,
        messages: List<LlamaChatSerializedMessage>,
        onCreated: (Long) -> Unit
    ) {
        viewModelScope.launch {
            val chatId = repository.createChat(title, 8192, systemPrompt)
            for (message in messages) {
                repository.addMessage(
                    chatId = chatId,
                    role = message.role,
                    content = message.content,
                    imagePath = message.imagePath,
                    audioPath = message.audioPath
                )
            }
            onCreated(chatId)
        }
    }
}

class LlamaChatViewModelFactory(private val repository: LlamaRepository) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(LlamaChatViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return LlamaChatViewModel(repository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
