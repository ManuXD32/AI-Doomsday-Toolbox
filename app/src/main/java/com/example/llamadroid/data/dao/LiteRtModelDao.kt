package com.example.llamadroid.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.llamadroid.data.model.LiteRtModelEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface LiteRtModelDao {
    @Query("SELECT * FROM litert_models ORDER BY updatedAt DESC, displayName COLLATE NOCASE ASC")
    fun observeAll(): Flow<List<LiteRtModelEntity>>

    @Query("SELECT * FROM litert_models ORDER BY updatedAt DESC, displayName COLLATE NOCASE ASC")
    suspend fun getAllOnce(): List<LiteRtModelEntity>

    @Query("SELECT * FROM litert_models WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): LiteRtModelEntity?

    @Query("SELECT * FROM litert_models WHERE id = :id LIMIT 1")
    fun getByIdImmediate(id: Long): LiteRtModelEntity?

    @Query("SELECT * FROM litert_models WHERE path = :path LIMIT 1")
    suspend fun getByPath(path: String): LiteRtModelEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(model: LiteRtModelEntity): Long

    @Update
    suspend fun update(model: LiteRtModelEntity)

    @Delete
    suspend fun delete(model: LiteRtModelEntity)

    @Query("DELETE FROM litert_models WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("UPDATE litert_models SET displayName = :displayName, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateDisplayName(id: Long, displayName: String, updatedAt: Long = System.currentTimeMillis())

    @Query("UPDATE litert_models SET backendPreference = :backend, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateBackendPreference(id: Long, backend: String, updatedAt: Long = System.currentTimeMillis())

    @Query("UPDATE litert_models SET maxContextTokens = :maxContextTokens, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateMaxContextTokens(
        id: Long,
        maxContextTokens: Int?,
        updatedAt: Long = System.currentTimeMillis()
    )

    @Query(
        """
        UPDATE litert_models
        SET supportsVision = :supportsVision,
            supportsAudio = :supportsAudio,
            supportsEmbedding = :supportsEmbedding,
            kbEmbeddingRunnable = 0,
            kbEmbeddingRuntime = NULL,
            kbEmbeddingStatus = 'manual_recheck_required',
            updatedAt = :updatedAt
        WHERE id = :id
        """
    )
    suspend fun updateCapabilitySupport(
        id: Long,
        supportsVision: Boolean,
        supportsAudio: Boolean,
        supportsEmbedding: Boolean,
        updatedAt: Long = System.currentTimeMillis()
    )

    @Query("SELECT * FROM litert_models WHERE supportsEmbedding = 1 AND kbEmbeddingRunnable = 1 ORDER BY updatedAt DESC, displayName COLLATE NOCASE ASC")
    fun observeEmbeddingCapableModels(): Flow<List<LiteRtModelEntity>>

    @Query(
        """
        UPDATE litert_models
        SET kbEmbeddingRunnable = :runnable,
            kbEmbeddingRuntime = :runtime,
            kbEmbeddingStatus = :status,
            updatedAt = :updatedAt
        WHERE id = :id
        """
    )
    suspend fun updateKbEmbeddingRuntime(
        id: Long,
        runnable: Boolean,
        runtime: String?,
        status: String?,
        updatedAt: Long = System.currentTimeMillis()
    )
}
