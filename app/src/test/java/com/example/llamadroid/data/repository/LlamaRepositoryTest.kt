package com.example.llamadroid.data.repository

import com.example.llamadroid.data.dao.LlamaChatDao
import com.example.llamadroid.data.dao.LlamaChatFolderDao
import com.example.llamadroid.data.dao.LlamaChatPromptProfileDao
import com.example.llamadroid.data.dao.LlamaMessageDao
import com.example.llamadroid.data.dao.LlamaServerDao
import com.example.llamadroid.data.model.LlamaChatEntity
import com.example.llamadroid.data.model.LlamaChatFolderEntity
import com.example.llamadroid.data.model.LlamaChatPromptProfileEntity
import com.example.llamadroid.data.model.LlamaMessageEntity
import com.example.llamadroid.data.model.LlamaServerEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class LlamaRepositoryTest {
    @Test
    fun `delete chat folder moves chats to unfiled without deleting chats`() = runBlocking {
        val folder = LlamaChatFolderEntity(id = 5, name = "Work")
        val chatDao = FakeLlamaChatDao(
            mutableMapOf(
                1L to LlamaChatEntity(id = 1, title = "A", folderId = 5),
                2L to LlamaChatEntity(id = 2, title = "B", folderId = 9)
            )
        )
        val folderDao = FakeLlamaFolderDao(mutableMapOf(5L to folder))
        val repository = LlamaRepository(
            FakeLlamaServerDao(),
            chatDao,
            folderDao,
            FakeLlamaMessageDao()
        )

        repository.deleteChatFolder(folder)

        assertNull(chatDao.chats.getValue(1).folderId)
        assertEquals(9L, chatDao.chats.getValue(2).folderId)
        assertFalse(folderDao.folders.containsKey(5))
    }

    @Test
    fun `prompt profiles trim save update and delete through dedicated dao`() = runBlocking {
        val profileDao = FakePromptProfileDao()
        val repository = LlamaRepository(
            FakeLlamaServerDao(),
            FakeLlamaChatDao(),
            FakeLlamaFolderDao(),
            FakeLlamaMessageDao(),
            profileDao
        )

        val id = repository.createChatPromptProfile(" Researcher ", " Use sources. ")
        assertEquals("Researcher", profileDao.profiles.getValue(id).name)
        assertEquals("Use sources.", profileDao.profiles.getValue(id).content)

        repository.updateChatPromptProfile(profileDao.profiles.getValue(id), " Coder ", " Read code first. ")
        assertEquals("Coder", profileDao.profiles.getValue(id).name)
        assertEquals("Read code first.", profileDao.profiles.getValue(id).content)

        repository.deleteChatPromptProfile(profileDao.profiles.getValue(id))
        assertFalse(profileDao.profiles.containsKey(id))
    }

    @Test
    fun `chat AI Hub pin stores and clears remembered server`() = runBlocking {
        val chatDao = FakeLlamaChatDao(
            mutableMapOf(7L to LlamaChatEntity(id = 7, title = "Pinned chat"))
        )
        val repository = LlamaRepository(
            FakeLlamaServerDao(),
            chatDao,
            FakeLlamaFolderDao(),
            FakeLlamaMessageDao()
        )

        repository.updateChatAiHubPin(7L, pinned = true, serverId = 3L)

        val pinned = chatDao.chats.getValue(7L)
        assertEquals(true, pinned.pinnedToAiHub)
        assertEquals(3L, pinned.pinnedServerId)

        repository.updateChatAiHubPin(7L, pinned = false, serverId = null)

        val unpinned = chatDao.chats.getValue(7L)
        assertEquals(false, unpinned.pinnedToAiHub)
        assertNull(unpinned.pinnedServerId)
        assertNull(unpinned.pinnedAt)
    }
}

private class FakeLlamaServerDao : LlamaServerDao {
    override fun getAllServers(): Flow<List<LlamaServerEntity>> = flowOf(emptyList())
    override suspend fun getServerById(id: Long): LlamaServerEntity? = null
    override suspend fun insertServer(server: LlamaServerEntity): Long = server.id
    override suspend fun deleteServer(server: LlamaServerEntity) = Unit
    override suspend fun updateLastUsed(id: Long, timestamp: Long) = Unit
    override suspend fun getLastUsedServer(): LlamaServerEntity? = null
    override suspend fun updateSupportsVision(id: Long, supportsVision: Boolean) = Unit
    override suspend fun updateModelName(id: Long, modelName: String?) = Unit
    override suspend fun updateModelMetadata(id: Long, modelName: String?, supportsVision: Boolean, supportsAudio: Boolean) = Unit
    override suspend fun updateServer(server: LlamaServerEntity) = Unit
}

private class FakeLlamaFolderDao(
    val folders: MutableMap<Long, LlamaChatFolderEntity> = mutableMapOf()
) : LlamaChatFolderDao {
    private var nextId = (folders.keys.maxOrNull() ?: 0L) + 1L

    override fun getAllFolders(): Flow<List<LlamaChatFolderEntity>> = flowOf(folders.values.toList())
    override suspend fun getFolderById(id: Long): LlamaChatFolderEntity? = folders[id]
    override suspend fun insertFolder(folder: LlamaChatFolderEntity): Long {
        val id = folder.id.takeIf { it != 0L } ?: nextId++
        folders[id] = folder.copy(id = id)
        return id
    }
    override suspend fun updateFolder(folder: LlamaChatFolderEntity) {
        folders[folder.id] = folder
    }
    override suspend fun deleteFolderById(id: Long) {
        folders.remove(id)
    }
}

private class FakePromptProfileDao : LlamaChatPromptProfileDao {
    val profiles: MutableMap<Long, LlamaChatPromptProfileEntity> = mutableMapOf()
    private var nextId = 1L

    override fun getAllProfiles(): Flow<List<LlamaChatPromptProfileEntity>> = flowOf(profiles.values.toList())
    override suspend fun getProfileById(id: Long): LlamaChatPromptProfileEntity? = profiles[id]
    override suspend fun insertProfile(profile: LlamaChatPromptProfileEntity): Long {
        val id = nextId++
        profiles[id] = profile.copy(id = id)
        return id
    }
    override suspend fun updateProfile(id: Long, name: String, content: String, updatedAt: Long) {
        profiles[id] = profiles.getValue(id).copy(name = name, content = content, updatedAt = updatedAt)
    }
    override suspend fun deleteProfileById(id: Long) {
        profiles.remove(id)
    }
}

private class FakeLlamaChatDao(
    val chats: MutableMap<Long, LlamaChatEntity> = mutableMapOf()
) : LlamaChatDao {
    override fun getAllChats(): Flow<List<LlamaChatEntity>> = flowOf(chats.values.toList())
    override fun getPinnedAiHubChats(): Flow<List<LlamaChatEntity>> =
        flowOf(chats.values.filter { it.pinnedToAiHub })

    override suspend fun getChatById(id: Long): LlamaChatEntity? = chats[id]
    override suspend fun insertChat(chat: LlamaChatEntity): Long {
        val id = chat.id.takeIf { it != 0L } ?: ((chats.keys.maxOrNull() ?: 0L) + 1L)
        chats[id] = chat.copy(id = id)
        return id
    }
    override suspend fun deleteChat(chat: LlamaChatEntity) {
        chats.remove(chat.id)
    }
    override suspend fun updateTitle(id: Long, title: String) {
        chats[id] = chats.getValue(id).copy(title = title)
    }
    override suspend fun updateContextSize(id: Long, contextSize: Int) {
        chats[id] = chats.getValue(id).copy(contextSize = contextSize)
    }
    override suspend fun updateLastModified(id: Long, timestamp: Long) {
        chats[id] = chats.getValue(id).copy(lastModified = timestamp)
    }
    override suspend fun updateSystemPrompt(id: Long, systemPrompt: String?) {
        chats[id] = chats.getValue(id).copy(systemPrompt = systemPrompt)
    }
    override suspend fun updateApiParams(id: Long, apiParams: String?) {
        chats[id] = chats.getValue(id).copy(apiParams = apiParams)
    }
    override suspend fun updateFolder(id: Long, folderId: Long?, timestamp: Long) {
        chats[id] = chats.getValue(id).copy(folderId = folderId, lastModified = timestamp)
    }
    override suspend fun clearFolder(folderId: Long) {
        chats.keys.toList().forEach { id ->
            val chat = chats.getValue(id)
            if (chat.folderId == folderId) {
                chats[id] = chat.copy(folderId = null)
            }
        }
    }
    override suspend fun updateAiHubPin(id: Long, pinned: Boolean, serverId: Long?, pinnedAt: Long?) {
        chats[id] = chats.getValue(id).copy(
            pinnedToAiHub = pinned,
            pinnedServerId = serverId,
            pinnedAt = pinnedAt
        )
    }
}

private class FakeLlamaMessageDao : LlamaMessageDao {
    override fun getMessagesForChat(chatId: Long): Flow<List<LlamaMessageEntity>> = flowOf(emptyList())
    override suspend fun getMessagesOnce(chatId: Long): List<LlamaMessageEntity> = emptyList()
    override suspend fun insertMessage(message: LlamaMessageEntity): Long = message.id
    override suspend fun deleteMessage(message: LlamaMessageEntity) = Unit
    override suspend fun deleteAllMessagesForChat(chatId: Long) = Unit
    override suspend fun deleteMessagesAfter(chatId: Long, timestamp: Long, messageId: Long) = Unit
    override suspend fun updateMessageContent(id: Long, content: String) = Unit
    override suspend fun updateMessageContentAndError(id: Long, content: String, isError: Boolean) = Unit
    override suspend fun updateMessageErrorStatus(id: Long, isError: Boolean) = Unit
    override suspend fun updateMessageThinkingAndContent(
        id: Long,
        content: String,
        thinking: String?,
        promptTokens: Int,
        completionTokens: Int,
        tps: Double,
        generationTimeMs: Long
    ) = Unit
    override suspend fun updateMessageTruncatedStatus(id: Long, isTruncated: Boolean) = Unit
    override suspend fun updateMessageAudioPath(id: Long, audioPath: String?) = Unit
}
