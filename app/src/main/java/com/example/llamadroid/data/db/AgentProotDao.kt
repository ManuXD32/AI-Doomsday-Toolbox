package com.example.llamadroid.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/** Persistence boundary for app-managed Debian/PRoot environments. */
@Dao
interface AgentProotEnvironmentDao {
    @Query("SELECT * FROM agent_proot_environments ORDER BY updatedAt DESC, displayName COLLATE NOCASE ASC")
    fun observeAll(): Flow<List<AgentProotEnvironmentEntity>>

    @Query("SELECT * FROM agent_proot_environments ORDER BY updatedAt DESC, displayName COLLATE NOCASE ASC")
    suspend fun getAll(): List<AgentProotEnvironmentEntity>

    @Query("SELECT * FROM agent_proot_environments WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): AgentProotEnvironmentEntity?

    @Query("SELECT * FROM agent_proot_environments WHERE id = :id LIMIT 1")
    fun observeById(id: String): Flow<AgentProotEnvironmentEntity?>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(environment: AgentProotEnvironmentEntity)

    @Update
    suspend fun update(environment: AgentProotEnvironmentEntity)

    @Delete
    suspend fun delete(environment: AgentProotEnvironmentEntity)

    @Query("DELETE FROM agent_proot_environments WHERE id = :id")
    suspend fun deleteById(id: String): Int

    @Query("SELECT COUNT(*) FROM agent_conversations WHERE prootEnvironmentId = :environmentId")
    suspend fun countProjectReferences(environmentId: String): Int

    @Query("SELECT COUNT(*) FROM agent_proot_runs WHERE environmentId = :environmentId AND status IN ('QUEUED', 'RUNNING', 'STOP_REQUESTED')")
    suspend fun countActiveRuns(environmentId: String): Int

    @Query(
        "SELECT c.* FROM agent_conversations c " +
            "WHERE c.prootEnvironmentId = :environmentId " +
            "ORDER BY c.updatedAt DESC, c.id ASC"
    )
    fun observeProjects(environmentId: String): Flow<List<AgentConversationEntity>>

    @Query(
        "UPDATE agent_conversations SET prootEnvironmentId = :environmentId, updatedAt = :updatedAt " +
            "WHERE id = :conversationId"
    )
    suspend fun assignEnvironment(
        conversationId: Long,
        environmentId: String?,
        updatedAt: Long = System.currentTimeMillis()
    ): Int
}

/** Durable process/preview lease metadata for the PRoot broker. */
@Dao
interface AgentProotRunDao {
    @Query("SELECT * FROM agent_proot_runs ORDER BY updatedAt DESC, id DESC")
    fun observeAll(): Flow<List<AgentProotRunEntity>>

    @Query("SELECT * FROM agent_proot_runs WHERE conversationId = :conversationId ORDER BY updatedAt DESC, id DESC")
    fun observeForConversation(conversationId: Long): Flow<List<AgentProotRunEntity>>

    @Query("SELECT * FROM agent_proot_runs WHERE conversationId = :conversationId ORDER BY updatedAt DESC, id DESC")
    suspend fun getForConversation(conversationId: Long): List<AgentProotRunEntity>

    @Query("SELECT * FROM agent_proot_runs WHERE environmentId = :environmentId ORDER BY updatedAt DESC, id DESC")
    fun observeForEnvironment(environmentId: String): Flow<List<AgentProotRunEntity>>

    @Query("SELECT * FROM agent_proot_runs WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): AgentProotRunEntity?

    @Query(
        "SELECT * FROM agent_proot_runs WHERE environmentId = :environmentId " +
            "AND status IN ('QUEUED', 'RUNNING', 'STOP_REQUESTED') " +
            "AND commandDigest NOT LIKE 'interactive_terminal:%' " +
            "ORDER BY createdAt ASC, id ASC LIMIT 1"
    )
    suspend fun getActiveForEnvironment(environmentId: String): AgentProotRunEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(run: AgentProotRunEntity)

    @Update
    suspend fun update(run: AgentProotRunEntity)

    @Query("UPDATE agent_proot_runs SET status = :status, errorClass = :errorClass, errorMessage = :errorMessage, endedAt = :endedAt, updatedAt = :updatedAt WHERE id = :id")
    suspend fun finish(
        id: String,
        status: String,
        errorClass: String? = null,
        errorMessage: String? = null,
        endedAt: Long? = System.currentTimeMillis(),
        updatedAt: Long = System.currentTimeMillis()
    ): Int

    @Query(
        "UPDATE agent_proot_runs SET status = 'INTERRUPTED', errorClass = :errorClass, " +
            "errorMessage = :errorMessage, endedAt = :endedAt, updatedAt = :updatedAt " +
            "WHERE processGeneration = :processGeneration AND status IN ('QUEUED', 'RUNNING', 'STOP_REQUESTED')"
    )
    suspend fun markGenerationInterrupted(
        processGeneration: String,
        errorClass: String = "PROCESS_DEATH",
        errorMessage: String? = null,
        endedAt: Long? = System.currentTimeMillis(),
        updatedAt: Long = System.currentTimeMillis()
    ): Int

    @Query(
        "UPDATE agent_proot_runs SET status = 'INTERRUPTED', errorClass = 'PROCESS_DEATH', " +
            "errorMessage = :reason, endedAt = :now, updatedAt = :now " +
            "WHERE status IN ('QUEUED', 'RUNNING', 'STOP_REQUESTED') " +
            "AND (processGeneration IS NULL OR processGeneration != :currentGeneration)"
    )
    suspend fun interruptStaleActiveRuns(
        currentGeneration: String,
        reason: String,
        now: Long = System.currentTimeMillis()
    ): Int

    @Query("DELETE FROM agent_proot_runs WHERE conversationId = :conversationId")
    suspend fun deleteForConversation(conversationId: Long): Int

    @Query("DELETE FROM agent_proot_runs WHERE environmentId = :environmentId")
    suspend fun deleteForEnvironment(environmentId: String): Int
}
