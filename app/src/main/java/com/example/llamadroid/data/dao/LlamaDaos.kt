package com.example.llamadroid.data.dao

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import com.example.llamadroid.data.model.LlamaServerEntity
import com.example.llamadroid.data.model.LlamaChatEntity
import com.example.llamadroid.data.model.LlamaChatFolderEntity
import com.example.llamadroid.data.model.LlamaChatPromptProfileEntity
import com.example.llamadroid.data.model.LlamaScheduledTaskEntity
import com.example.llamadroid.data.model.LlamaScheduledTaskLogEntity
import com.example.llamadroid.data.model.LlamaMessageEntity
import com.example.llamadroid.data.model.LlamaSpeculativeRunEntity

@Dao
interface LlamaServerDao {
    @Query("SELECT * FROM llama_servers ORDER BY lastUsed DESC")
    fun getAllServers(): Flow<List<LlamaServerEntity>>

    @Query("SELECT * FROM llama_servers WHERE id = :id")
    suspend fun getServerById(id: Long): LlamaServerEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertServer(server: LlamaServerEntity): Long

    @Delete
    suspend fun deleteServer(server: LlamaServerEntity)
    
    @Query("UPDATE llama_servers SET lastUsed = :timestamp WHERE id = :id")
    suspend fun updateLastUsed(id: Long, timestamp: Long = System.currentTimeMillis())

    @Query("SELECT * FROM llama_servers ORDER BY lastUsed DESC LIMIT 1")
    suspend fun getLastUsedServer(): LlamaServerEntity?

    @Query("UPDATE llama_servers SET supportsVision = :supportsVision WHERE id = :id")
    suspend fun updateSupportsVision(id: Long, supportsVision: Boolean)

    @Query("UPDATE llama_servers SET modelName = :modelName WHERE id = :id")
    suspend fun updateModelName(id: Long, modelName: String?)

    @Query(
        "UPDATE llama_servers SET modelName = :modelName, supportsVision = :supportsVision, supportsAudio = :supportsAudio WHERE id = :id"
    )
    suspend fun updateModelMetadata(
        id: Long,
        modelName: String?,
        supportsVision: Boolean,
        supportsAudio: Boolean
    )

    @Update
    suspend fun updateServer(server: LlamaServerEntity)
}

@Dao
interface LlamaChatFolderDao {
    @Query("SELECT * FROM llama_chat_folders ORDER BY name COLLATE NOCASE ASC")
    fun getAllFolders(): Flow<List<LlamaChatFolderEntity>>

    @Query("SELECT * FROM llama_chat_folders WHERE id = :id")
    suspend fun getFolderById(id: Long): LlamaChatFolderEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertFolder(folder: LlamaChatFolderEntity): Long

    @Update
    suspend fun updateFolder(folder: LlamaChatFolderEntity)

    @Query("DELETE FROM llama_chat_folders WHERE id = :id")
    suspend fun deleteFolderById(id: Long)
}

@Dao
interface LlamaChatPromptProfileDao {
    @Query("SELECT * FROM llama_chat_prompt_profiles ORDER BY updatedAt DESC, name COLLATE NOCASE ASC")
    fun getAllProfiles(): Flow<List<LlamaChatPromptProfileEntity>>

    @Query("SELECT * FROM llama_chat_prompt_profiles WHERE id = :id")
    suspend fun getProfileById(id: Long): LlamaChatPromptProfileEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertProfile(profile: LlamaChatPromptProfileEntity): Long

    @Query(
        "UPDATE llama_chat_prompt_profiles " +
            "SET name = :name, content = :content, updatedAt = :updatedAt " +
            "WHERE id = :id"
    )
    suspend fun updateProfile(id: Long, name: String, content: String, updatedAt: Long)

    @Query("DELETE FROM llama_chat_prompt_profiles WHERE id = :id")
    suspend fun deleteProfileById(id: Long)
}

@Dao
interface LlamaChatDao {
    @Query("SELECT * FROM llama_chats ORDER BY lastModified DESC")
    fun getAllChats(): Flow<List<LlamaChatEntity>>

    @Query("SELECT * FROM llama_chats WHERE pinnedToAiHub = 1 ORDER BY pinnedAt DESC, lastModified DESC")
    fun getPinnedAiHubChats(): Flow<List<LlamaChatEntity>>

    @Query("SELECT * FROM llama_chats WHERE id = :id")
    suspend fun getChatById(id: Long): LlamaChatEntity?

    @Query("SELECT * FROM llama_chats WHERE isEphemeral = 1 AND deleteAfterSession = 1 AND expiresAtMillis IS NOT NULL AND expiresAtMillis <= :now")
    suspend fun getExpiredEphemeralChats(now: Long = System.currentTimeMillis()): List<LlamaChatEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChat(chat: LlamaChatEntity): Long

    @Delete
    suspend fun deleteChat(chat: LlamaChatEntity)

    @Query("UPDATE llama_chats SET title = :title WHERE id = :id")
    suspend fun updateTitle(id: Long, title: String)

    @Query("UPDATE llama_chats SET contextSize = :contextSize WHERE id = :id")
    suspend fun updateContextSize(id: Long, contextSize: Int)
    
    @Query("UPDATE llama_chats SET lastModified = :timestamp WHERE id = :id")
    suspend fun updateLastModified(id: Long, timestamp: Long = System.currentTimeMillis())

    @Query("UPDATE llama_chats SET systemPrompt = :systemPrompt WHERE id = :id")
    suspend fun updateSystemPrompt(id: Long, systemPrompt: String?)

    @Query("UPDATE llama_chats SET apiParams = :apiParams WHERE id = :id")
    suspend fun updateApiParams(id: Long, apiParams: String?)

    @Query("UPDATE llama_chats SET folderId = :folderId, lastModified = :timestamp WHERE id = :id")
    suspend fun updateFolder(id: Long, folderId: Long?, timestamp: Long = System.currentTimeMillis())

    @Query("UPDATE llama_chats SET folderId = NULL WHERE folderId = :folderId")
    suspend fun clearFolder(folderId: Long)

    @Query(
        "UPDATE llama_chats " +
            "SET pinnedToAiHub = :pinned, pinnedServerId = :serverId, pinnedAt = :pinnedAt " +
            "WHERE id = :id"
    )
    suspend fun updateAiHubPin(
        id: Long,
        pinned: Boolean,
        serverId: Long?,
        pinnedAt: Long?
    )

    @Query("DELETE FROM llama_chats WHERE id = :id AND isEphemeral = 1 AND deleteAfterSession = 1")
    suspend fun deleteEphemeralChatById(id: Long)
}

@Dao
interface LlamaSpeculativeRunDao {
    @Query("SELECT * FROM llama_speculative_runs ORDER BY savedForever DESC, createdAt DESC")
    fun observeRuns(): Flow<List<LlamaSpeculativeRunEntity>>

    @Query("SELECT * FROM llama_speculative_runs WHERE id = :id")
    suspend fun getRunById(id: Long): LlamaSpeculativeRunEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRun(run: LlamaSpeculativeRunEntity): Long

    @Query(
        "UPDATE llama_speculative_runs " +
            "SET sampleCount = :sampleCount, acceptanceRate = :acceptanceRate, " +
            "promptTokensPerSecond = :promptTokensPerSecond, " +
            "generationTokensPerSecond = :generationTokensPerSecond, rawMetrics = :rawMetrics, " +
            "updatedAt = :updatedAt WHERE id = :id"
    )
    suspend fun updateRunMetrics(
        id: Long,
        sampleCount: Int,
        acceptanceRate: Double?,
        promptTokensPerSecond: Double?,
        generationTokensPerSecond: Double?,
        rawMetrics: String,
        updatedAt: Long = System.currentTimeMillis()
    )

    @Query("UPDATE llama_speculative_runs SET name = :name, updatedAt = :updatedAt WHERE id = :id")
    suspend fun renameRun(id: Long, name: String?, updatedAt: Long = System.currentTimeMillis())

    @Query("UPDATE llama_speculative_runs SET savedForever = :savedForever, updatedAt = :updatedAt WHERE id = :id")
    suspend fun setSavedForever(id: Long, savedForever: Boolean, updatedAt: Long = System.currentTimeMillis())

    @Query("DELETE FROM llama_speculative_runs WHERE id = :id")
    suspend fun deleteRun(id: Long)

    @Query(
        "SELECT id FROM llama_speculative_runs " +
            "WHERE savedForever = 0 AND (name IS NULL OR TRIM(name) = '') " +
            "ORDER BY createdAt DESC"
    )
    suspend fun getPrunableRunIdsNewestFirst(): List<Long>

    @Query("DELETE FROM llama_speculative_runs WHERE id IN (:ids)")
    suspend fun deleteRuns(ids: List<Long>)
}

@Dao
interface LlamaMessageDao {
    @Query("SELECT * FROM llama_messages WHERE chatId = :chatId ORDER BY timestamp ASC")
    fun getMessagesForChat(chatId: Long): Flow<List<LlamaMessageEntity>>

    @Query("SELECT * FROM llama_messages WHERE chatId = :chatId ORDER BY timestamp ASC")
    suspend fun getMessagesOnce(chatId: Long): List<LlamaMessageEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: LlamaMessageEntity): Long

    @Delete
    suspend fun deleteMessage(message: LlamaMessageEntity)
    
    @Query("DELETE FROM llama_messages WHERE chatId = :chatId")
    suspend fun deleteAllMessagesForChat(chatId: Long)
    
    // Delete all messages after a specific message while preserving the anchor message itself.
    @Query(
        "DELETE FROM llama_messages " +
            "WHERE chatId = :chatId AND (timestamp > :timestamp OR (timestamp = :timestamp AND id > :messageId))"
    )
    suspend fun deleteMessagesAfter(chatId: Long, timestamp: Long, messageId: Long)
    
    @Query("UPDATE llama_messages SET content = :content WHERE id = :id")
    suspend fun updateMessageContent(id: Long, content: String)

    @Query("UPDATE llama_messages SET content = :content, isError = :isError WHERE id = :id")
    suspend fun updateMessageContentAndError(id: Long, content: String, isError: Boolean)

    @Query("UPDATE llama_messages SET isError = :isError WHERE id = :id")
    suspend fun updateMessageErrorStatus(id: Long, isError: Boolean)

    @Query("UPDATE llama_messages SET content = :content, thinking = :thinking, promptTokens = :promptTokens, completionTokens = :completionTokens, tps = :tps, generationTimeMs = :generationTimeMs WHERE id = :id")
    suspend fun updateMessageThinkingAndContent(
        id: Long, 
        content: String, 
        thinking: String?,
        promptTokens: Int,
        completionTokens: Int,
        tps: Double,
        generationTimeMs: Long
    )

    @Query("UPDATE llama_messages SET isTruncated = :isTruncated WHERE id = :id")
    suspend fun updateMessageTruncatedStatus(id: Long, isTruncated: Boolean)

    @Query("UPDATE llama_messages SET audioPath = :audioPath WHERE id = :id")
    suspend fun updateMessageAudioPath(id: Long, audioPath: String?)
}

@Dao
interface LlamaScheduledTaskDao {
    @Query("SELECT * FROM llama_scheduled_tasks ORDER BY enabled DESC, nextRunAtMillis IS NULL ASC, nextRunAtMillis ASC, name COLLATE NOCASE ASC")
    fun getAllTasks(): Flow<List<LlamaScheduledTaskEntity>>

    @Query("SELECT * FROM llama_scheduled_tasks WHERE id = :id")
    suspend fun getTaskById(id: Long): LlamaScheduledTaskEntity?

    @Query(
        "SELECT * FROM llama_scheduled_tasks " +
            "WHERE enabled = 1 AND nextRunAtMillis IS NOT NULL AND nextRunAtMillis <= :nowMillis " +
            "ORDER BY nextRunAtMillis ASC, id ASC"
    )
    suspend fun getDueTasks(nowMillis: Long): List<LlamaScheduledTaskEntity>

    @Query(
        "SELECT * FROM llama_scheduled_tasks " +
            "WHERE enabled = 1 AND nextRunAtMillis IS NOT NULL AND nextRunAtMillis > :nowMillis " +
            "ORDER BY nextRunAtMillis ASC, id ASC"
    )
    suspend fun getEnabledFutureTasks(nowMillis: Long): List<LlamaScheduledTaskEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTask(task: LlamaScheduledTaskEntity): Long

    @Update
    suspend fun updateTask(task: LlamaScheduledTaskEntity)

    @Query("DELETE FROM llama_scheduled_tasks WHERE id = :id")
    suspend fun deleteTaskById(id: Long)

    @Query(
        "UPDATE llama_scheduled_tasks " +
            "SET enabled = :enabled, updatedAt = :updatedAt " +
            "WHERE id = :id"
    )
    suspend fun setTaskEnabled(id: Long, enabled: Boolean, updatedAt: Long = System.currentTimeMillis())

    @Query(
        "UPDATE llama_scheduled_tasks " +
            "SET nextRunAtMillis = :nextRunAtMillis, lastRunAtMillis = :lastRunAtMillis, updatedAt = :updatedAt " +
            "WHERE id = :id"
    )
    suspend fun updateTaskRunState(
        id: Long,
        nextRunAtMillis: Long?,
        lastRunAtMillis: Long?,
        updatedAt: Long = System.currentTimeMillis()
    )

    @Query("SELECT * FROM llama_scheduled_task_logs ORDER BY createdAt DESC LIMIT :limit")
    fun getRecentLogs(limit: Int = 200): Flow<List<LlamaScheduledTaskLogEntity>>

    @Query("SELECT * FROM llama_scheduled_task_logs WHERE taskId = :taskId ORDER BY createdAt DESC LIMIT :limit")
    fun getLogsForTask(taskId: Long, limit: Int = 100): Flow<List<LlamaScheduledTaskLogEntity>>

    @Query("SELECT * FROM llama_scheduled_task_logs WHERE id = :id")
    suspend fun getLogById(id: Long): LlamaScheduledTaskLogEntity?

    @Query("DELETE FROM llama_scheduled_task_logs WHERE id = :id")
    suspend fun deleteLogById(id: Long)

    @Query("DELETE FROM llama_scheduled_task_logs WHERE id IN (:ids)")
    suspend fun deleteLogsByIds(ids: List<Long>)

    @Query("DELETE FROM llama_scheduled_task_logs")
    suspend fun deleteAllLogs()

    @Query(
        "SELECT * FROM llama_scheduled_task_logs " +
            "WHERE taskId = :taskId AND scheduledAtMillis = :scheduledAtMillis AND status = :status " +
            "LIMIT 1"
    )
    suspend fun findLogByTaskScheduleAndStatus(
        taskId: Long,
        scheduledAtMillis: Long,
        status: String
    ): LlamaScheduledTaskLogEntity?

    @Query(
        "SELECT * FROM llama_scheduled_task_logs " +
            "WHERE status = :status ORDER BY scheduledAtMillis ASC, id ASC"
    )
    suspend fun getLogsByStatus(status: String): List<LlamaScheduledTaskLogEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLog(log: LlamaScheduledTaskLogEntity): Long

    @Update
    suspend fun updateLog(log: LlamaScheduledTaskLogEntity)

    @Query(
        "UPDATE llama_scheduled_task_logs " +
            "SET status = :status, startedAtMillis = :startedAtMillis, serverId = :serverId, " +
            "serverName = :serverName, serverBaseUrl = :serverBaseUrl " +
            "WHERE id = :id"
    )
    suspend fun markLogRunning(
        id: Long,
        status: String,
        startedAtMillis: Long,
        serverId: Long?,
        serverName: String?,
        serverBaseUrl: String?
    )

    @Query(
        "UPDATE llama_scheduled_task_logs " +
            "SET status = :status, finishedAtMillis = :finishedAtMillis, durationMs = :durationMs, " +
            "finalOutput = :finalOutput, error = NULL, toolActivity = :toolActivity " +
            "WHERE id = :id"
    )
    suspend fun markLogSuccess(
        id: Long,
        status: String,
        finishedAtMillis: Long,
        durationMs: Long,
        finalOutput: String,
        toolActivity: String
    )

    @Query(
        "UPDATE llama_scheduled_task_logs " +
            "SET status = :status, finishedAtMillis = :finishedAtMillis, durationMs = :durationMs, " +
            "error = :error, toolActivity = :toolActivity " +
            "WHERE id = :id"
    )
    suspend fun markLogFailure(
        id: Long,
        status: String,
        finishedAtMillis: Long,
        durationMs: Long?,
        error: String,
        toolActivity: String
    )

    @Query(
        "UPDATE llama_scheduled_task_logs " +
            "SET status = :status, finishedAtMillis = :finishedAtMillis, durationMs = 0 " +
            "WHERE id = :id"
    )
    suspend fun markLogSkipped(
        id: Long,
        status: String,
        finishedAtMillis: Long = System.currentTimeMillis()
    )
}
