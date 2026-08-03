package com.example.llamadroid.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Entity(
    tableName = "ai_runtime_jobs",
    indices = [
        Index(value = ["jobKey"], unique = true),
        Index(value = ["status"])
    ]
)
data class AiRuntimeJobEntity(
    @PrimaryKey
    val jobId: String,
    val jobKey: String,
    val type: String,
    val status: String,
    val conversationId: Long? = null,
    val sessionId: String? = null,
    val projectFolder: String? = null,
    val backendIdentifier: String? = null,
    val modelName: String? = null,
    val payloadJson: String,
    val checkpointJson: String? = null,
    val progressText: String? = null,
    val errorMessage: String? = null,
    val resumable: Boolean = true,
    val createdAt: Long,
    val updatedAt: Long
)

@Dao
interface AiRuntimeJobDao {
    @Query("SELECT * FROM ai_runtime_jobs WHERE status IN ('RUNNING', 'RECOVERING') ORDER BY updatedAt DESC")
    suspend fun getActiveJobs(): List<AiRuntimeJobEntity>

    @Query("SELECT * FROM ai_runtime_jobs WHERE status IN ('RUNNING', 'RECOVERING') ORDER BY updatedAt DESC")
    fun observeActiveJobs(): Flow<List<AiRuntimeJobEntity>>

    @Query("SELECT * FROM ai_runtime_jobs WHERE jobKey = :jobKey LIMIT 1")
    suspend fun getByJobKey(jobKey: String): AiRuntimeJobEntity?

    @Query("SELECT * FROM ai_runtime_jobs WHERE jobId = :jobId LIMIT 1")
    suspend fun getById(jobId: String): AiRuntimeJobEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(job: AiRuntimeJobEntity)

    @Query(
        """
        UPDATE ai_runtime_jobs
        SET status = :status,
            checkpointJson = :checkpointJson,
            progressText = :progressText,
            errorMessage = :errorMessage,
            updatedAt = :updatedAt
        WHERE jobId = :jobId
        """
    )
    suspend fun updateState(
        jobId: String,
        status: String,
        checkpointJson: String?,
        progressText: String?,
        errorMessage: String?,
        updatedAt: Long
    )

    @Query("DELETE FROM ai_runtime_jobs WHERE status IN ('COMPLETED', 'FAILED', 'CANCELLED') AND updatedAt < :olderThan")
    suspend fun deleteTerminalJobsOlderThan(olderThan: Long)

    @Query("DELETE FROM ai_runtime_jobs WHERE jobId = :jobId")
    suspend fun deleteById(jobId: String)

    @Query("DELETE FROM ai_runtime_jobs WHERE conversationId = :conversationId")
    suspend fun deleteByConversationId(conversationId: Long)
}
