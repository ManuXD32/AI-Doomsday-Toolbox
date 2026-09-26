package com.example.llamadroid.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface HarnessDao {
    @Query("SELECT * FROM agent_harness_runtime WHERE id = 'shared'")
    suspend fun runtime(): HarnessRuntimeEntity?

    @Query("SELECT * FROM agent_harness_runtime WHERE id = 'shared'")
    fun observeRuntime(): Flow<HarnessRuntimeEntity?>

    @Upsert
    suspend fun saveRuntime(runtime: HarnessRuntimeEntity)

    @Query("SELECT * FROM agent_harness_workspaces ORDER BY title COLLATE NOCASE, id")
    fun observeWorkspaces(): Flow<List<HarnessWorkspaceEntity>>

    @Query("SELECT * FROM agent_harness_workspaces ORDER BY createdAt, id")
    suspend fun workspaces(): List<HarnessWorkspaceEntity>

    @Query("SELECT * FROM agent_harness_workspaces WHERE id = :id")
    suspend fun workspace(id: String): HarnessWorkspaceEntity?

    @Query("SELECT * FROM agent_harness_workspaces WHERE backend = :backend AND projectFolder = :folder AND connectionKey = :connectionKey LIMIT 1")
    suspend fun workspaceForRoot(backend: String, folder: String, connectionKey: String = ""): HarnessWorkspaceEntity?

    @Upsert
    suspend fun saveWorkspace(workspace: HarnessWorkspaceEntity)

    @Query("DELETE FROM agent_harness_workspaces WHERE id = :id AND NOT EXISTS (SELECT 1 FROM agent_harness_sessions WHERE workspaceId = :id)")
    suspend fun deleteUnreferencedWorkspace(id: String)

    @Query("SELECT * FROM agent_harness_sessions WHERE harnessSessionId = :id")
    suspend fun session(id: String): HarnessSessionEntity?

    @Query("SELECT * FROM agent_harness_sessions WHERE conversationId = :conversationId")
    suspend fun sessionForConversation(conversationId: Long): HarnessSessionEntity?

    @Query("SELECT * FROM agent_harness_sessions ORDER BY updatedAt DESC")
    fun observeSessions(): Flow<List<HarnessSessionEntity>>

    @Upsert
    suspend fun saveSession(session: HarnessSessionEntity)

    @Query("SELECT * FROM agent_conversations WHERE runtimeSource = 'LEGACY_ARCHIVE' ORDER BY updatedAt DESC")
    fun observeLegacyConversations(): Flow<List<AgentConversationEntity>>

    @Query("SELECT * FROM agent_conversations ORDER BY createdAt, id")
    suspend fun conversations(): List<AgentConversationEntity>

    @Query("SELECT id, conversationId, sequenceNumber, role, substr(toolName, 1, 256) AS toolName, substr(content, 1, 4000) AS preview FROM agent_messages WHERE conversationId = :conversationId AND (sequenceNumber < :beforeSequence OR (sequenceNumber = :beforeSequence AND id < :beforeId)) ORDER BY sequenceNumber DESC, id DESC LIMIT :limit")
    suspend fun legacyMessagePreviews(conversationId: Long, beforeSequence: Int = Int.MAX_VALUE, beforeId: Long = Long.MAX_VALUE, limit: Int = 81): List<HarnessLegacyMessagePreview>

    @Query("SELECT substr(CASE :part WHEN 'thinking' THEN COALESCE(thinking, '') WHEN 'toolOutput' THEN COALESCE(toolOutput, '') WHEN 'terminalOutput' THEN COALESCE(terminalOutput, '') ELSE content END, :offset, 24000) AS text, length(CASE :part WHEN 'thinking' THEN COALESCE(thinking, '') WHEN 'toolOutput' THEN COALESCE(toolOutput, '') WHEN 'terminalOutput' THEN COALESCE(terminalOutput, '') ELSE content END) AS totalLength FROM agent_messages WHERE id = :id AND conversationId = :conversationId")
    suspend fun legacyMessageChunk(conversationId: Long, id: Long, part: String, offset: Int): HarnessLegacyMessageChunk?
}

data class HarnessLegacyMessagePreview(val id: Long, val conversationId: Long, val sequenceNumber: Int, val role: String, val toolName: String?, val preview: String)
data class HarnessLegacyMessageChunk(val text: String, val totalLength: Int)
