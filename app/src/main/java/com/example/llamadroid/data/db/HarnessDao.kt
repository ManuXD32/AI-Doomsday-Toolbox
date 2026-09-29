package com.example.llamadroid.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * Room persistence boundary for Harness ownership and identity mappings.
 *
 * Runtime-bearing methods default to [HarnessRuntimeIds.LEGACY] so existing
 * callers continue to see rows created before multiple installations were
 * introduced. New code should obtain [AppDatabase.harnessDao] with an explicit
 * runtime id instead of relying on those defaults.
 */
@Dao
interface HarnessDao {
    @Query("SELECT * FROM agent_harness_runtime WHERE id = 'shared'")
    suspend fun runtime(): HarnessRuntimeEntity?

    @Query("SELECT * FROM agent_harness_runtime WHERE id = 'shared'")
    fun observeRuntime(): Flow<HarnessRuntimeEntity?>

    @Query("SELECT * FROM agent_harness_runtime WHERE id = 'shared' AND environmentId = :environmentId")
    suspend fun runtimeForEnvironment(environmentId: String): HarnessRuntimeEntity?

    @Query("SELECT * FROM agent_harness_runtime WHERE id = 'shared' AND environmentId = :environmentId")
    fun observeRuntimeForEnvironment(environmentId: String): Flow<HarnessRuntimeEntity?>

    @Upsert
    suspend fun saveRuntime(runtime: HarnessRuntimeEntity)

    @Query("SELECT * FROM agent_harness_workspaces WHERE runtimeId = :runtimeId ORDER BY title COLLATE NOCASE, id")
    fun observeWorkspaces(runtimeId: String = HarnessRuntimeIds.LEGACY): Flow<List<HarnessWorkspaceEntity>>

    @Query("SELECT * FROM agent_harness_workspaces WHERE runtimeId = :runtimeId ORDER BY createdAt, id")
    suspend fun workspaces(runtimeId: String = HarnessRuntimeIds.LEGACY): List<HarnessWorkspaceEntity>

    @Query("SELECT * FROM agent_harness_workspaces WHERE id = :id AND runtimeId = :runtimeId")
    suspend fun workspace(id: String, runtimeId: String = HarnessRuntimeIds.LEGACY): HarnessWorkspaceEntity?

    @Query(
        "SELECT * FROM agent_harness_workspaces " +
            "WHERE runtimeId = :runtimeId AND backend = :backend AND projectFolder = :folder " +
            "AND connectionKey = :connectionKey LIMIT 1"
    )
    suspend fun workspaceForRoot(
        backend: String,
        folder: String,
        connectionKey: String = "",
        runtimeId: String = HarnessRuntimeIds.LEGACY
    ): HarnessWorkspaceEntity?

    @Upsert
    suspend fun saveWorkspaceForRuntime(workspace: HarnessWorkspaceEntity)

    /** Backward-compatible writes are forced into the legacy runtime. */
    suspend fun saveWorkspace(workspace: HarnessWorkspaceEntity) {
        saveWorkspaceForRuntime(workspace.copy(runtimeId = HarnessRuntimeIds.LEGACY))
    }

    @Query(
        "DELETE FROM agent_harness_workspaces WHERE id = :id AND runtimeId = :runtimeId " +
            "AND NOT EXISTS (SELECT 1 FROM agent_harness_sessions " +
            "WHERE runtimeId = :runtimeId AND workspaceId = :id)"
    )
    suspend fun deleteUnreferencedWorkspace(id: String, runtimeId: String = HarnessRuntimeIds.LEGACY)

    @Query(
        "SELECT s.* FROM agent_harness_sessions s " +
            "JOIN agent_conversations c ON c.id = s.conversationId AND c.runtimeId = s.runtimeId " +
            "WHERE s.runtimeId = :runtimeId AND s.harnessSessionId = :id"
    )
    suspend fun session(id: String, runtimeId: String = HarnessRuntimeIds.LEGACY): HarnessSessionEntity?

    @Query(
        "SELECT s.* FROM agent_harness_sessions s " +
            "JOIN agent_conversations c ON c.id = s.conversationId AND c.runtimeId = s.runtimeId " +
            "WHERE s.runtimeId = :runtimeId AND s.conversationId = :conversationId"
    )
    suspend fun sessionForConversation(
        conversationId: Long,
        runtimeId: String = HarnessRuntimeIds.LEGACY
    ): HarnessSessionEntity?

    @Query(
        "SELECT s.* FROM agent_harness_sessions s " +
            "JOIN agent_conversations c ON c.id = s.conversationId AND c.runtimeId = s.runtimeId " +
            "WHERE s.runtimeId = :runtimeId ORDER BY s.updatedAt DESC"
    )
    fun observeSessions(runtimeId: String = HarnessRuntimeIds.LEGACY): Flow<List<HarnessSessionEntity>>

    @Upsert
    suspend fun saveSessionForRuntime(session: HarnessSessionEntity)

    /** Backward-compatible writes are forced into the legacy runtime. */
    suspend fun saveSession(session: HarnessSessionEntity) {
        saveSessionForRuntime(session.copy(runtimeId = HarnessRuntimeIds.LEGACY))
    }

    @Query(
        "SELECT * FROM agent_conversations " +
            "WHERE runtimeId = :runtimeId AND runtimeSource = 'LEGACY_ARCHIVE' " +
            "ORDER BY updatedAt DESC"
    )
    fun observeLegacyConversations(runtimeId: String = HarnessRuntimeIds.LEGACY): Flow<List<AgentConversationEntity>>

    @Query("SELECT * FROM agent_conversations WHERE runtimeId = :runtimeId ORDER BY createdAt, id")
    suspend fun conversations(runtimeId: String = HarnessRuntimeIds.LEGACY): List<AgentConversationEntity>

    @Query(
        "SELECT m.id, m.conversationId, m.sequenceNumber, m.role, " +
            "substr(m.toolName, 1, 256) AS toolName, substr(m.content, 1, 4000) AS preview " +
            "FROM agent_messages m " +
            "WHERE m.conversationId = :conversationId " +
            "AND EXISTS (SELECT 1 FROM agent_conversations c " +
            "WHERE c.id = m.conversationId AND c.runtimeId = :runtimeId) " +
            "AND (m.sequenceNumber < :beforeSequence OR " +
            "(m.sequenceNumber = :beforeSequence AND m.id < :beforeId)) " +
            "ORDER BY m.sequenceNumber DESC, m.id DESC LIMIT :limit"
    )
    suspend fun legacyMessagePreviews(
        conversationId: Long,
        beforeSequence: Int = Int.MAX_VALUE,
        beforeId: Long = Long.MAX_VALUE,
        limit: Int = 81,
        runtimeId: String = HarnessRuntimeIds.LEGACY
    ): List<HarnessLegacyMessagePreview>

    @Query(
        "SELECT substr(CASE :part " +
            "WHEN 'thinking' THEN COALESCE(m.thinking, '') " +
            "WHEN 'toolOutput' THEN COALESCE(m.toolOutput, '') " +
            "WHEN 'terminalOutput' THEN COALESCE(m.terminalOutput, '') " +
            "ELSE m.content END, :offset, 24000) AS text, " +
            "length(CASE :part " +
            "WHEN 'thinking' THEN COALESCE(m.thinking, '') " +
            "WHEN 'toolOutput' THEN COALESCE(m.toolOutput, '') " +
            "WHEN 'terminalOutput' THEN COALESCE(m.terminalOutput, '') " +
            "ELSE m.content END) AS totalLength " +
            "FROM agent_messages m " +
            "WHERE m.id = :id AND m.conversationId = :conversationId " +
            "AND EXISTS (SELECT 1 FROM agent_conversations c " +
            "WHERE c.id = m.conversationId AND c.runtimeId = :runtimeId)"
    )
    suspend fun legacyMessageChunk(
        conversationId: Long,
        id: Long,
        part: String,
        offset: Int,
        runtimeId: String = HarnessRuntimeIds.LEGACY
    ): HarnessLegacyMessageChunk?
}

data class HarnessLegacyMessagePreview(
    val id: Long,
    val conversationId: Long,
    val sequenceNumber: Int,
    val role: String,
    val toolName: String?,
    val preview: String
)

data class HarnessLegacyMessageChunk(val text: String, val totalLength: Int)

/**
 * Runtime-scoped facade over the Room DAO.
 *
 * Room only generates the no-argument DAO accessor. This facade captures the
 * selected runtime once, so every identity/history operation carries the same
 * runtime predicate. Process-owner methods deliberately delegate to the
 * singleton owner row.
 */
class HarnessRuntimeDao internal constructor(
    private val delegate: HarnessDao,
    val runtimeId: String
) {
    init {
        require(runtimeId.isNotBlank()) { "Harness runtime id must not be blank" }
    }

    suspend fun runtime(): HarnessRuntimeEntity? = delegate.runtimeForEnvironment(runtimeId)
    fun observeRuntime(): Flow<HarnessRuntimeEntity?> = delegate.observeRuntimeForEnvironment(runtimeId)
    suspend fun saveRuntime(runtime: HarnessRuntimeEntity) =
        delegate.saveRuntime(runtime.copy(id = "shared", environmentId = runtimeId))

    fun observeWorkspaces(): Flow<List<HarnessWorkspaceEntity>> = delegate.observeWorkspaces(runtimeId)
    suspend fun workspaces(): List<HarnessWorkspaceEntity> = delegate.workspaces(runtimeId)
    suspend fun workspace(id: String): HarnessWorkspaceEntity? = delegate.workspace(id, runtimeId)
    suspend fun workspaceForRoot(
        backend: String,
        folder: String,
        connectionKey: String = ""
    ): HarnessWorkspaceEntity? = delegate.workspaceForRoot(backend, folder, connectionKey, runtimeId)

    suspend fun saveWorkspace(workspace: HarnessWorkspaceEntity) =
        delegate.saveWorkspaceForRuntime(workspace.copy(runtimeId = runtimeId))

    suspend fun deleteUnreferencedWorkspace(id: String) =
        delegate.deleteUnreferencedWorkspace(id, runtimeId)

    suspend fun session(id: String): HarnessSessionEntity? = delegate.session(id, runtimeId)
    suspend fun sessionForConversation(conversationId: Long): HarnessSessionEntity? =
        delegate.sessionForConversation(conversationId, runtimeId)
    fun observeSessions(): Flow<List<HarnessSessionEntity>> = delegate.observeSessions(runtimeId)

    suspend fun saveSession(session: HarnessSessionEntity) =
        delegate.saveSessionForRuntime(session.copy(runtimeId = runtimeId))

    fun observeLegacyConversations(): Flow<List<AgentConversationEntity>> =
        delegate.observeLegacyConversations(runtimeId)
    suspend fun conversations(): List<AgentConversationEntity> = delegate.conversations(runtimeId)

    suspend fun legacyMessagePreviews(
        conversationId: Long,
        beforeSequence: Int = Int.MAX_VALUE,
        beforeId: Long = Long.MAX_VALUE,
        limit: Int = 81
    ): List<HarnessLegacyMessagePreview> = delegate.legacyMessagePreviews(
        conversationId = conversationId,
        beforeSequence = beforeSequence,
        beforeId = beforeId,
        limit = limit,
        runtimeId = runtimeId
    )

    suspend fun legacyMessageChunk(
        conversationId: Long,
        id: Long,
        part: String,
        offset: Int
    ): HarnessLegacyMessageChunk? = delegate.legacyMessageChunk(
        conversationId = conversationId,
        id = id,
        part = part,
        offset = offset,
        runtimeId = runtimeId
    )
}
