package com.example.llamadroid.data.repository

import com.example.llamadroid.data.dao.LlamaServerDao
import com.example.llamadroid.data.dao.LlamaChatDao
import com.example.llamadroid.data.dao.LlamaChatFolderDao
import com.example.llamadroid.data.dao.LlamaChatPromptProfileDao
import com.example.llamadroid.data.dao.LlamaMessageDao
import com.example.llamadroid.data.model.LlamaServerEntity
import com.example.llamadroid.data.model.LlamaChatEntity
import com.example.llamadroid.data.model.LlamaChatFolderEntity
import com.example.llamadroid.data.model.LlamaChatPromptProfileEntity
import com.example.llamadroid.data.model.LlamaMessageEntity
import com.example.llamadroid.LlamaApplication
import com.example.llamadroid.service.NativeChatKvCacheStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

class LlamaRepository(
    private val serverDao: LlamaServerDao,
    private val chatDao: LlamaChatDao,
    private val folderDao: LlamaChatFolderDao,
    private val messageDao: LlamaMessageDao,
    private val promptProfileDao: LlamaChatPromptProfileDao? = null
) {
    // Servers
    val allServers: Flow<List<LlamaServerEntity>> = serverDao.getAllServers()
    
    suspend fun saveServer(server: LlamaServerEntity) = serverDao.insertServer(server)
    suspend fun deleteServer(server: LlamaServerEntity) = serverDao.deleteServer(server)
    suspend fun getServer(id: Long) = serverDao.getServerById(id)
    suspend fun updateServerUsage(id: Long) = serverDao.updateLastUsed(id)
    suspend fun updateServer(server: LlamaServerEntity) = serverDao.updateServer(server)
    suspend fun updateServerModelName(id: Long, modelName: String?) = serverDao.updateModelName(id, modelName)
    suspend fun updateServerModelMetadata(
        id: Long,
        modelName: String?,
        supportsVision: Boolean,
        supportsAudio: Boolean,
        supportsVideo: Boolean = false
    ) = serverDao.updateModelMetadata(id, modelName, supportsVision, supportsAudio, supportsVideo)

    // Chat <-> server binding
    suspend fun bindChatServer(chatId: Long, serverId: Long?) = chatDao.bindChatServer(chatId, serverId)
    suspend fun bindChatServerIfUnbound(chatId: Long, serverId: Long): Boolean =
        chatDao.bindChatServerIfUnbound(chatId, serverId) > 0
    suspend fun clearChatServerBinding(serverId: Long) = chatDao.clearChatServerBinding(serverId)
    /** Turning the cache off also deletes the chat's saved slot files. */
    suspend fun setPersistKvCache(chatId: Long, enabled: Boolean) {
        chatDao.setPersistKvCache(chatId, enabled)
        if (!enabled) deleteKvCacheFiles(chatId)
    }

    /** Best effort: the repository is also built in tests and workers without an Application. */
    private suspend fun deleteKvCacheFiles(chatId: Long) = withContext(Dispatchers.IO) {
        runCatching { NativeChatKvCacheStore(LlamaApplication.instance).delete(chatId) }
    }
    suspend fun getChatsBoundToServer(serverId: Long): List<LlamaChatEntity> = chatDao.getChatsBoundToServer(serverId)
    fun observeBoundChatCounts(): Flow<Map<Long, Int>> =
        chatDao.observeBoundChatCounts().map { rows -> rows.associate { it.serverId to it.chatCount } }

    // Chats
    val allChats: Flow<List<LlamaChatEntity>> = chatDao.getAllChats()
    val allChatFolders: Flow<List<LlamaChatFolderEntity>> = folderDao.getAllFolders()
    val allChatPromptProfiles: Flow<List<LlamaChatPromptProfileEntity>> =
        promptProfileDao?.getAllProfiles() ?: flowOf(emptyList())
    
    suspend fun createChat(
        title: String,
        contextSize: Int = 8192,
        systemPrompt: String? = null,
        apiParams: String? = null,
        folderId: Long? = null,
        isEphemeral: Boolean = false,
        source: String? = null,
        deleteAfterSession: Boolean = false,
        expiresAtMillis: Long? = null,
        serverId: Long? = null
    ): Long {
        val chat = LlamaChatEntity(
            title = title,
            contextSize = contextSize,
            systemPrompt = systemPrompt,
            apiParams = apiParams,
            folderId = folderId,
            isEphemeral = isEphemeral,
            source = source,
            deleteAfterSession = deleteAfterSession,
            expiresAtMillis = expiresAtMillis,
            serverId = serverId
        )
        return chatDao.insertChat(chat)
    }
    
    suspend fun getChat(id: Long) = chatDao.getChatById(id)
    suspend fun deleteChat(chat: LlamaChatEntity) {
        chatDao.deleteChat(chat)
        deleteKvCacheFiles(chat.id)
    }
    suspend fun deleteEphemeralChat(id: Long) {
        chatDao.deleteEphemeralChatById(id)
        if (chatDao.getChatById(id) == null) deleteKvCacheFiles(id)
    }
    suspend fun deleteExpiredEphemeralChats(now: Long = System.currentTimeMillis()) {
        chatDao.getExpiredEphemeralChats(now).forEach {
            chatDao.deleteChat(it)
            deleteKvCacheFiles(it.id)
        }
    }
    suspend fun updateChatTitle(id: Long, title: String) = chatDao.updateTitle(id, title)
    suspend fun updateChatContextSize(id: Long, contextSize: Int) = chatDao.updateContextSize(id, contextSize)
    suspend fun updateChatModified(id: Long) = chatDao.updateLastModified(id)
    suspend fun updateChatSystemPrompt(id: Long, systemPrompt: String?) = chatDao.updateSystemPrompt(id, systemPrompt)
    suspend fun updateChatApiParams(id: Long, apiParams: String?) = chatDao.updateApiParams(id, apiParams)
    suspend fun updateChatFolder(id: Long, folderId: Long?) = chatDao.updateFolder(id, folderId)
    suspend fun updateChatAiHubPin(id: Long, pinned: Boolean, serverId: Long?) =
        chatDao.updateAiHubPin(
            id = id,
            pinned = pinned,
            serverId = serverId.takeIf { pinned },
            pinnedAt = if (pinned) System.currentTimeMillis() else null
        )

    suspend fun createChatFolder(name: String): Long {
        val cleanName = name.trim()
        require(cleanName.isNotBlank()) { "Folder name is required." }
        return folderDao.insertFolder(LlamaChatFolderEntity(name = cleanName))
    }
    suspend fun deleteChatFolder(folder: LlamaChatFolderEntity) {
        chatDao.clearFolder(folder.id)
        folderDao.deleteFolderById(folder.id)
    }

    suspend fun createChatPromptProfile(name: String, content: String): Long {
        val cleanName = name.trim()
        val cleanContent = content.trim()
        require(cleanName.isNotBlank()) { "Profile name is required." }
        require(cleanContent.isNotBlank()) { "Profile prompt is required." }
        return requireNotNull(promptProfileDao) { "Prompt profile DAO is not configured." }
            .insertProfile(
                LlamaChatPromptProfileEntity(
                    name = cleanName,
                    content = cleanContent
                )
            )
    }

    suspend fun updateChatPromptProfile(profile: LlamaChatPromptProfileEntity, name: String, content: String) {
        val cleanName = name.trim()
        val cleanContent = content.trim()
        require(cleanName.isNotBlank()) { "Profile name is required." }
        require(cleanContent.isNotBlank()) { "Profile prompt is required." }
        requireNotNull(promptProfileDao) { "Prompt profile DAO is not configured." }
            .updateProfile(profile.id, cleanName, cleanContent, System.currentTimeMillis())
    }

    suspend fun deleteChatPromptProfile(profile: LlamaChatPromptProfileEntity) {
        requireNotNull(promptProfileDao) { "Prompt profile DAO is not configured." }
            .deleteProfileById(profile.id)
    }

    // Messages
    fun getMessages(chatId: Long): Flow<List<LlamaMessageEntity>> = messageDao.getMessagesForChat(chatId)
    suspend fun getMessagesOnce(chatId: Long): List<LlamaMessageEntity> = messageDao.getMessagesOnce(chatId)
    
    suspend fun addMessage(
        chatId: Long,
        role: String,
        content: String,
        imagePath: String? = null,
        audioPath: String? = null,
        videoPath: String? = null
    ): Long {
        val msg = LlamaMessageEntity(
            chatId = chatId,
            role = role,
            content = content,
            imagePath = imagePath,
            audioPath = audioPath,
            videoPath = videoPath
        )
        val id = messageDao.insertMessage(msg)
        chatDao.updateLastModified(chatId)
        return id
    }
    
    suspend fun updateMessage(id: Long, content: String) = messageDao.updateMessageContent(id, content)
    suspend fun updateMessageContentAndError(id: Long, content: String, isError: Boolean) =
        messageDao.updateMessageContentAndError(id, content, isError)
    suspend fun updateMessageErrorStatus(id: Long, isError: Boolean) =
        messageDao.updateMessageErrorStatus(id, isError)
    suspend fun updateMessageThinkingAndContent(
        id: Long, 
        content: String, 
        thinking: String?,
        promptTokens: Int,
        completionTokens: Int,
        tps: Double,
        generationTimeMs: Long
    ) = messageDao.updateMessageThinkingAndContent(id, content, thinking, promptTokens, completionTokens, tps, generationTimeMs)
    
    suspend fun deleteMessage(message: LlamaMessageEntity) = messageDao.deleteMessage(message)

    suspend fun clearChatMessages(chatId: Long) {
        messageDao.deleteAllMessagesForChat(chatId)
        chatDao.updateLastModified(chatId)
        // The saved KV state describes the deleted history, so it is stale now.
        deleteKvCacheFiles(chatId)
    }
    
    // For "Edit and Regenerate": Delete all messages after the edited one
    suspend fun deleteMessagesAfter(chatId: Long, timestamp: Long, messageId: Long) =
        messageDao.deleteMessagesAfter(chatId, timestamp, messageId)

    suspend fun updateMessageTruncatedStatus(id: Long, isTruncated: Boolean) = messageDao.updateMessageTruncatedStatus(id, isTruncated)
    suspend fun updateMessageAudioPath(id: Long, audioPath: String?) = messageDao.updateMessageAudioPath(id, audioPath)
}
