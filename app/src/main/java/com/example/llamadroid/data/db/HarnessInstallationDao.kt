package com.example.llamadroid.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * Catalog access for installable Harness environments.
 *
 * The rows live in [AgentProotEnvironmentEntity]'s existing table so generic
 * Debian management and Harness installation management share status, size,
 * image pins, and lifecycle metadata. The purpose predicate keeps the two
 * product families discoverable without exposing one another's rows.
 */
@Dao
interface HarnessInstallationDao {
    @Query(
        "SELECT * FROM agent_proot_environments " +
            "WHERE purpose = '${HarnessInstallationPurpose.HARNESS}' " +
            "ORDER BY updatedAt DESC, displayName COLLATE NOCASE ASC"
    )
    fun observeAll(): Flow<List<AgentProotEnvironmentEntity>>

    @Query(
        "SELECT * FROM agent_proot_environments " +
            "WHERE purpose = '${HarnessInstallationPurpose.HARNESS}' " +
            "ORDER BY updatedAt DESC, displayName COLLATE NOCASE ASC"
    )
    suspend fun getAll(): List<AgentProotEnvironmentEntity>

    @Query(
        "SELECT * FROM agent_proot_environments " +
            "WHERE id = :id AND purpose = '${HarnessInstallationPurpose.HARNESS}' LIMIT 1"
    )
    suspend fun getById(id: String): AgentProotEnvironmentEntity?

    @Query(
        "SELECT * FROM agent_proot_environments " +
            "WHERE id = :id AND purpose = '${HarnessInstallationPurpose.HARNESS}' LIMIT 1"
    )
    fun observeById(id: String): Flow<AgentProotEnvironmentEntity?>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(environment: AgentProotEnvironmentEntity)

    @Update
    suspend fun update(environment: AgentProotEnvironmentEntity)

    @Query("UPDATE agent_proot_environments SET status = 'READY', updatedAt = :updatedAt " +
        "WHERE id = :id AND purpose = '${HarnessInstallationPurpose.HARNESS}'")
    suspend fun markReady(id: String, updatedAt: Long): Int

    /** A late size estimate must not restore old names, status, or deleted rows. */
    @Query("UPDATE agent_proot_environments SET sizeBytes = :bytes " +
        "WHERE id = :id AND purpose = '${HarnessInstallationPurpose.HARNESS}'")
    suspend fun updateSize(id: String, bytes: Long): Int

    /** Delete only a Harness installation row; generic Legacy environments are protected. */
    @Query(
        "DELETE FROM agent_proot_environments " +
            "WHERE id = :id AND purpose = '${HarnessInstallationPurpose.HARNESS}'"
    )
    suspend fun deleteById(id: String): Int

    @Query("DELETE FROM agent_conversations WHERE runtimeId = :runtimeId")
    suspend fun deleteConversationsForRuntime(runtimeId: String): Int

    /** Jobs have no Room foreign key, so remove their conversation-owned rows explicitly. */
    @Query(
        "DELETE FROM ai_runtime_jobs WHERE conversationId IN (" +
            "SELECT id FROM agent_conversations WHERE runtimeId = :runtimeId)"
    )
    suspend fun deleteRuntimeJobsForRuntime(runtimeId: String): Int

    @Query("DELETE FROM agent_harness_sessions WHERE runtimeId = :runtimeId")
    suspend fun deleteSessionsForRuntime(runtimeId: String): Int

    @Query("DELETE FROM agent_harness_workspaces WHERE runtimeId = :runtimeId")
    suspend fun deleteWorkspacesForRuntime(runtimeId: String): Int

    @Query("DELETE FROM agent_harness_transfer_receipts WHERE targetRuntimeId = :runtimeId")
    suspend fun deleteTransferReceiptsForRuntime(runtimeId: String): Int

    /**
     * Count references that belong to another conversation runtime. The
     * manager should check this before filesystem quarantine; the transaction
     * below checks it again so a stale preflight cannot delete a shared env.
     */
    @Query(
        "SELECT " +
            "(SELECT COUNT(*) FROM agent_conversations c " +
            "WHERE c.prootEnvironmentId = :runtimeId AND c.runtimeId != :runtimeId) + " +
            "(SELECT COUNT(*) FROM agent_proot_runs r " +
            "LEFT JOIN agent_conversations c ON c.id = r.conversationId " +
            "WHERE r.environmentId = :runtimeId " +
            "AND (c.id IS NULL OR c.runtimeId != :runtimeId)) + " +
            "(SELECT COUNT(*) FROM agent_project_runs r " +
            "LEFT JOIN agent_conversations c ON c.id = r.conversationId " +
            "WHERE r.prootEnvironmentId = :runtimeId " +
            "AND (c.id IS NULL OR c.runtimeId != :runtimeId))"
    )
    suspend fun countExternalRuntimeReferences(runtimeId: String): Int

    suspend fun ensureRuntimeDeletionAllowed(runtimeId: String) {
        require(runtimeId.isNotBlank()) { "HARNESS_RUNTIME_REQUIRED" }
        check(countExternalRuntimeReferences(runtimeId) == 0) { "HARNESS_RUNTIME_REFERENCED" }
    }

    /**
     * Remove one runtime's durable rows in dependency order. The environment
     * metadata is removed only when no global Proot row still references it.
     * Filesystem payloads are intentionally outside this database operation.
     *
     * The adopted legacy runtime is a real Harness installation and may be
     * deleted after the manager has quarantined its files. Do not reject that
     * id here: this transaction removes only rows owned by [runtimeId], while
     * generic legacy environment rows remain protected by the purpose filter.
     */
    @Transaction
    suspend fun deleteRuntimeRecords(runtimeId: String) {
        ensureRuntimeDeletionAllowed(runtimeId)
        deleteRuntimeJobsForRuntime(runtimeId)
        deleteConversationsForRuntime(runtimeId)
        deleteSessionsForRuntime(runtimeId)
        deleteWorkspacesForRuntime(runtimeId)
        deleteTransferReceiptsForRuntime(runtimeId)
        deleteEnvironmentIfUnreferenced(runtimeId)
    }

    @Query(
        "DELETE FROM agent_proot_environments " +
            "WHERE id = :runtimeId AND purpose = '${HarnessInstallationPurpose.HARNESS}' " +
            "AND NOT EXISTS (SELECT 1 FROM agent_proot_runs WHERE environmentId = :runtimeId) " +
            "AND NOT EXISTS (SELECT 1 FROM agent_project_runs WHERE prootEnvironmentId = :runtimeId) " +
            "AND NOT EXISTS (SELECT 1 FROM agent_conversations WHERE prootEnvironmentId = :runtimeId)"
    )
    suspend fun deleteEnvironmentIfUnreferenced(runtimeId: String): Int
}
