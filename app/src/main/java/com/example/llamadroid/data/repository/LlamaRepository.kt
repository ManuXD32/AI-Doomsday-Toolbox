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
import com.example.llamadroid.data.model.LlamaMessageAttachmentEntity
import com.example.llamadroid.data.model.NativeChatCompactionEntity
import com.example.llamadroid.data.dao.LlamaMessageAttachmentDao
import com.example.llamadroid.data.dao.NativeChatCompactionDao
import com.example.llamadroid.data.dao.NativeChatDelegateTaskDao
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.service.KvCacheKey
import java.io.File
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
    private val promptProfileDao: LlamaChatPromptProfileDao? = null,
    attachmentDao: LlamaMessageAttachmentDao? = null,
    compactionDao: NativeChatCompactionDao? = null,
    delegateTaskDao: NativeChatDelegateTaskDao? = null
) {
    // The delegation DAOs default to the app database so existing construction sites keep working;
    // tests (or hosts without an Application) either inject fakes or get null and skip the work.
    private val attachmentDao: LlamaMessageAttachmentDao? by lazy {
        attachmentDao ?: runCatching { AppDatabase.getDatabase(LlamaApplication.instance).llamaMessageAttachmentDao() }.getOrNull()
    }
    private val compactionDao: NativeChatCompactionDao? by lazy {
        compactionDao ?: runCatching { AppDatabase.getDatabase(LlamaApplication.instance).nativeChatCompactionDao() }.getOrNull()
    }
    private val delegateTaskDao: NativeChatDelegateTaskDao? by lazy {
        delegateTaskDao ?: runCatching { AppDatabase.getDatabase(LlamaApplication.instance).nativeChatDelegateTaskDao() }.getOrNull()
    }

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

    /**
     * Removes delegation leftovers that the row cascade cannot reach: the subagent KV caches of
     * the chat's tasks and the chat's attachment files. Call before the chat row is deleted.
     */
    private suspend fun deleteDelegationArtifacts(chatId: Long) = withContext(Dispatchers.IO) {
        runCatching {
            val taskIds = delegateTaskDao?.getIdsForChat(chatId).orEmpty()
            if (taskIds.isNotEmpty()) {
                val store = NativeChatKvCacheStore(LlamaApplication.instance)
                taskIds.forEach { taskId -> runCatching { store.delete(KvCacheKey.Delegate(taskId)) } }
            }
        }
        deleteAttachmentFiles(chatId)
        runCatching {
            File(LlamaApplication.instance.filesDir, "native_chat_workspaces/$chatId").deleteRecursively()
        }
    }

    private fun deleteAttachmentFiles(chatId: Long) {
        runCatching { attachmentFilesDir(chatId).deleteRecursively() }
    }

    private fun attachmentFilesDir(chatId: Long): File =
        File(File(LlamaApplication.instance.filesDir, ATTACHMENT_ROOT), chatId.toString())

    /** Drops summaries that cover or follow [messageId]; they are rebuilt on the next compaction. */
    private suspend fun invalidateCompactionsFrom(chatId: Long, messageId: Long) {
        runCatching { compactionDao?.deleteCoveringOrAfter(chatId, messageId) }
    }

    suspend fun getAttachmentsForChat(chatId: Long): List<LlamaMessageAttachmentEntity> =
        attachmentDao?.getForChat(chatId).orEmpty()
    fun observeAttachmentsForChat(chatId: Long): Flow<List<LlamaMessageAttachmentEntity>> =
        attachmentDao?.observeForChat(chatId) ?: flowOf(emptyList())
    fun observeCompactions(chatId: Long): Flow<List<NativeChatCompactionEntity>> =
        compactionDao?.observeForChat(chatId) ?: flowOf(emptyList())

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
        serverId: Long? = null,
        persistKvCache: Boolean = false
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
            serverId = serverId,
            persistKvCache = persistKvCache
        )
        return chatDao.insertChat(chat)
    }
    
    suspend fun getChat(id: Long) = chatDao.getChatById(id)
    suspend fun deleteChat(chat: LlamaChatEntity) {
        deleteDelegationArtifacts(chat.id)
        chatDao.deleteChat(chat)
        deleteKvCacheFiles(chat.id)
    }
    suspend fun deleteEphemeralChat(id: Long) {
        val existed = chatDao.getChatById(id) != null
        if (existed) deleteDelegationArtifacts(id)
        chatDao.deleteEphemeralChatById(id)
        if (chatDao.getChatById(id) == null) deleteKvCacheFiles(id)
    }
    suspend fun deleteExpiredEphemeralChats(now: Long = System.currentTimeMillis()) {
        chatDao.getExpiredEphemeralChats(now).forEach {
            deleteDelegationArtifacts(it.id)
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
        videoPath: String? = null,
        kind: String? = null
    ): Long {
        val msg = LlamaMessageEntity(
            chatId = chatId,
            role = role,
            content = content,
            imagePath = imagePath,
            audioPath = audioPath,
            videoPath = videoPath,
            kind = kind
        )
        val id = messageDao.insertMessage(msg)
        chatDao.updateLastModified(chatId)
        return id
    }
    
    suspend fun addMessageWithKind(chatId: Long, role: String, content: String, kind: String?): Long =
        addMessage(chatId, role, content, kind = kind)

    suspend fun updateMessage(id: Long, content: String) {
        runCatching { compactionDao?.deleteCoveringOrAfterMessage(id) }
        messageDao.updateMessageContent(id, content)
    }
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

    /** Streaming/final flush; [stepsJson] null keeps the stored steps untouched (replies without steps). */
    suspend fun updateMessageStreaming(
        id: Long,
        content: String,
        thinking: String?,
        promptTokens: Int,
        completionTokens: Int,
        tps: Double,
        generationTimeMs: Long,
        stepsJson: String?
    ) = if (stepsJson == null) {
        messageDao.updateMessageThinkingAndContent(id, content, thinking, promptTokens, completionTokens, tps, generationTimeMs)
    } else {
        messageDao.updateMessageThinkingContentAndSteps(
            id, content, thinking, promptTokens, completionTokens, tps, generationTimeMs, stepsJson
        )
    }

    suspend fun updateMessageSteps(id: Long, stepsJson: String?) = messageDao.updateMessageSteps(id, stepsJson)

    suspend fun updateMessageStepsAndText(id: Long, content: String, thinking: String?, stepsJson: String?) =
        messageDao.updateMessageStepsAndText(id, content, thinking, stepsJson)
    
    suspend fun deleteMessage(message: LlamaMessageEntity) {
        invalidateCompactionsFrom(message.chatId, message.id)
        messageDao.deleteMessage(message)
    }

    suspend fun clearChatMessages(chatId: Long) {
        runCatching { compactionDao?.deleteForChat(chatId) }
        runCatching { attachmentDao?.deleteForChat(chatId) }
        withContext(Dispatchers.IO) { deleteAttachmentFiles(chatId) }
        messageDao.deleteAllMessagesForChat(chatId)
        chatDao.updateLastModified(chatId)
        // The saved KV state describes the deleted history, so it is stale now.
        deleteKvCacheFiles(chatId)
    }
    
    // For "Edit and Regenerate": Delete all messages after the edited one
    suspend fun deleteMessagesAfter(chatId: Long, timestamp: Long, messageId: Long) {
        invalidateCompactionsFrom(chatId, messageId)
        messageDao.deleteMessagesAfter(chatId, timestamp, messageId)
    }

    suspend fun updateMessageTruncatedStatus(id: Long, isTruncated: Boolean) = messageDao.updateMessageTruncatedStatus(id, isTruncated)
    suspend fun updateMessageAudioPath(id: Long, audioPath: String?) = messageDao.updateMessageAudioPath(id, audioPath)

    private companion object {
        /** Per-chat attachment folder root under `filesDir`; also listed in the portable backup roots. */
        const val ATTACHMENT_ROOT = "llama_chat_files"
    }
}
