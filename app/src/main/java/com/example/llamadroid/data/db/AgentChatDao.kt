package com.example.llamadroid.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface AgentChatDao {
    
    // ========== Conversations ==========
    
    @Query("SELECT * FROM agent_conversations ORDER BY updatedAt DESC")
    fun getAllConversations(): Flow<List<AgentConversationEntity>>

    @Query("SELECT * FROM agent_project_folders ORDER BY parentId ASC, sortOrder ASC, name COLLATE NOCASE ASC")
    fun getProjectFolders(): Flow<List<AgentProjectFolderEntity>>
    
    @Query("SELECT * FROM agent_conversations WHERE id = :id")
    suspend fun getConversation(id: Long): AgentConversationEntity?
    
    @Insert
    suspend fun insertConversation(conversation: AgentConversationEntity): Long
    
    @Update
    suspend fun updateConversation(conversation: AgentConversationEntity)
    
    @Delete
    suspend fun deleteConversation(conversation: AgentConversationEntity)
    
    @Query("DELETE FROM agent_conversations WHERE id = :id")
    suspend fun deleteConversationById(id: Long)
    
    @Query("UPDATE agent_conversations SET title = :title, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateConversationTitle(id: Long, title: String, updatedAt: Long = System.currentTimeMillis())

    @Query("UPDATE agent_conversations SET title = :title, projectFolder = :projectFolder, workspaceBackend = :workspaceBackend, projectFolderId = :projectFolderId, sortOrder = :sortOrder, planningModeEnabled = :planningModeEnabled, resumeState = :resumeState, lastStopReason = :lastStopReason, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateConversationProjectMetadata(
        id: Long,
        title: String,
        projectFolder: String,
        workspaceBackend: String,
        projectFolderId: Long?,
        sortOrder: Int,
        planningModeEnabled: Boolean,
        resumeState: String,
        lastStopReason: String?,
        updatedAt: Long = System.currentTimeMillis()
    )

    @Query("UPDATE agent_conversations SET projectFolderId = :folderId, sortOrder = :sortOrder, updatedAt = :updatedAt WHERE id = :id")
    suspend fun moveConversationToFolder(id: Long, folderId: Long?, sortOrder: Int, updatedAt: Long = System.currentTimeMillis())

    @Query("UPDATE agent_conversations SET sortOrder = :sortOrder, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateConversationSortOrder(id: Long, sortOrder: Int, updatedAt: Long = System.currentTimeMillis())

    @Query("UPDATE agent_conversations SET planningModeEnabled = :enabled, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updatePlanningMode(id: Long, enabled: Boolean, updatedAt: Long = System.currentTimeMillis())

    @Query("UPDATE agent_conversations SET resumeState = :resumeState, lastStopReason = :reason, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateResumeState(id: Long, resumeState: String, reason: String?, updatedAt: Long = System.currentTimeMillis())
    
    @Query("UPDATE agent_conversations SET updatedAt = :updatedAt WHERE id = :id")
    suspend fun touchConversation(id: Long, updatedAt: Long = System.currentTimeMillis())
    
    @Query("UPDATE agent_conversations SET lastAgentRole = :role, lastTask = :task, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateConversationState(id: Long, role: String, task: String?, updatedAt: Long = System.currentTimeMillis())

    @Query("UPDATE agent_conversations SET knowledgeBaseIds = :knowledgeBaseIds, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateKnowledgeBaseIds(id: Long, knowledgeBaseIds: String, updatedAt: Long = System.currentTimeMillis())

    @Query(
        """
        UPDATE agent_conversations
        SET workspaceBackend = :workspaceBackend,
            runtimeCapabilitiesJson = :runtimeCapabilitiesJson,
            runEntrypointPath = :runEntrypointPath,
            runUiMode = :runUiMode,
            lastRunProfileJson = :lastRunProfileJson,
            updatedAt = :updatedAt
        WHERE id = :id
        """
    )
    suspend fun updateRuntimeSettings(
        id: Long,
        workspaceBackend: String,
        runtimeCapabilitiesJson: String,
        runEntrypointPath: String?,
        runUiMode: String,
        lastRunProfileJson: String,
        updatedAt: Long = System.currentTimeMillis()
    )

    // ========== Project folders ==========

    @Insert
    suspend fun insertProjectFolder(folder: AgentProjectFolderEntity): Long

    @Update
    suspend fun updateProjectFolder(folder: AgentProjectFolderEntity)

    @Query("DELETE FROM agent_project_folders WHERE id = :id")
    suspend fun deleteProjectFolderById(id: Long)

    @Query("SELECT * FROM agent_project_folders WHERE id = :id")
    suspend fun getProjectFolder(id: Long): AgentProjectFolderEntity?

    @Query("UPDATE agent_project_folders SET name = :name, updatedAt = :updatedAt WHERE id = :id")
    suspend fun renameProjectFolder(id: Long, name: String, updatedAt: Long = System.currentTimeMillis())

    @Query("UPDATE agent_project_folders SET parentId = :parentId, sortOrder = :sortOrder, updatedAt = :updatedAt WHERE id = :id")
    suspend fun moveProjectFolder(id: Long, parentId: Long?, sortOrder: Int, updatedAt: Long = System.currentTimeMillis())

    @Query("UPDATE agent_project_folders SET sortOrder = :sortOrder, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateProjectFolderSortOrder(id: Long, sortOrder: Int, updatedAt: Long = System.currentTimeMillis())

    @Query("UPDATE agent_project_folders SET isCollapsed = :collapsed, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateProjectFolderCollapsed(id: Long, collapsed: Boolean, updatedAt: Long = System.currentTimeMillis())
    
    // ========== Messages ==========
    
    @Query("SELECT * FROM agent_messages WHERE conversationId = :conversationId ORDER BY sequenceNumber ASC")
    fun getMessagesForConversation(conversationId: Long): Flow<List<AgentMessageEntity>>
    
    @Query("SELECT * FROM agent_messages WHERE conversationId = :conversationId ORDER BY sequenceNumber ASC")
    suspend fun getMessagesForConversationSync(conversationId: Long): List<AgentMessageEntity>

    @Query("SELECT * FROM agent_messages WHERE invocationId = :invocationId ORDER BY sequenceNumber ASC")
    fun observeMessagesForInvocation(invocationId: String): Flow<List<AgentMessageEntity>>

    @Query(
        """
        SELECT * FROM agent_project_events
        WHERE invocationId = :invocationId
        ORDER BY sequenceNumber ASC, timestamp ASC
        """
    )
    fun observeProjectEventsForInvocation(invocationId: String): Flow<List<AgentProjectEventEntity>>
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: AgentMessageEntity): Long
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessages(messages: List<AgentMessageEntity>)
    
    @Update
    suspend fun updateMessage(message: AgentMessageEntity)
    
    @Delete
    suspend fun deleteMessage(message: AgentMessageEntity)
    
    @Query("DELETE FROM agent_messages WHERE id = :id")
    suspend fun deleteMessageById(id: Long)
    
    @Query("DELETE FROM agent_messages WHERE conversationId = :conversationId")
    suspend fun deleteAllMessagesInConversation(conversationId: Long)
    
    @Query("DELETE FROM agent_messages WHERE conversationId = :conversationId AND timestamp >= :afterTimestamp")
    suspend fun deleteMessagesAfter(conversationId: Long, afterTimestamp: Long)
    
    // ========== Utilities ==========
    
    @Query("SELECT COUNT(*) FROM agent_messages WHERE conversationId = :conversationId")
    suspend fun getMessageCount(conversationId: Long): Int
    
    @Query("SELECT * FROM agent_messages WHERE conversationId = :conversationId ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLastMessage(conversationId: Long): AgentMessageEntity?

    // ========== Local project runs ==========

    @Query("SELECT * FROM agent_project_runs WHERE conversationId = :conversationId ORDER BY updatedAt DESC")
    fun getProjectRuns(conversationId: Long): Flow<List<AgentProjectRunEntity>>

    @Query("SELECT * FROM agent_project_runs WHERE conversationId = :conversationId ORDER BY updatedAt DESC LIMIT 1")
    suspend fun getLatestProjectRun(conversationId: Long): AgentProjectRunEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertProjectRun(run: AgentProjectRunEntity): Long

    @Update
    suspend fun updateProjectRun(run: AgentProjectRunEntity)

    @Query("DELETE FROM agent_project_runs WHERE conversationId = :conversationId")
    suspend fun deleteProjectRuns(conversationId: Long)

    // ========== Durable project debug journal ==========

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertProjectEvent(event: AgentProjectEventEntity): Long

    @Query(
        """
        SELECT * FROM agent_project_events
        WHERE conversationId = :conversationId
        ORDER BY timestamp DESC, id DESC
        LIMIT :limit
        """
    )
    fun getProjectEvents(conversationId: Long, limit: Int = 1000): Flow<List<AgentProjectEventEntity>>

    @Query(
        """
        SELECT * FROM agent_project_events
        WHERE conversationId = :conversationId
          AND (:category = 'ALL' OR category = :category OR (:category = 'ERRORS' AND (category = 'ERROR' OR status = 'ERROR')))
        ORDER BY timestamp DESC, id DESC
        LIMIT :limit
        """
    )
    fun getProjectEventsByCategory(
        conversationId: Long,
        category: String,
        limit: Int = 1000
    ): Flow<List<AgentProjectEventEntity>>

    @Query(
        """
        SELECT * FROM agent_project_events
        WHERE conversationId = :conversationId
        ORDER BY timestamp DESC, id DESC
        LIMIT :limit
        """
    )
    suspend fun getRecentProjectEventsSync(
        conversationId: Long,
        limit: Int = 100
    ): List<AgentProjectEventEntity>

    @Query(
        """
        SELECT * FROM agent_project_events
        ORDER BY timestamp DESC, id DESC
        LIMIT :limit
        """
    )
    suspend fun getRecentProjectEventsSync(limit: Int = 100): List<AgentProjectEventEntity>

    @Query(
        """
        DELETE FROM agent_project_events
        WHERE conversationId = :conversationId
          AND id NOT IN (
              SELECT id FROM agent_project_events
              WHERE conversationId = :conversationId
              ORDER BY timestamp DESC, id DESC
              LIMIT :keep
          )
        """
    )
    suspend fun pruneProjectEvents(conversationId: Long, keep: Int = 10_000)

    @Query("DELETE FROM agent_project_events WHERE conversationId = :conversationId")
    suspend fun clearProjectEvents(conversationId: Long)
}
