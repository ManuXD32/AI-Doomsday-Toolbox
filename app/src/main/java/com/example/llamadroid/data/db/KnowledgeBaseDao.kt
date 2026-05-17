package com.example.llamadroid.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface KnowledgeBaseDao {
    @Query("SELECT * FROM knowledge_bases ORDER BY updatedAt DESC, name COLLATE NOCASE ASC")
    fun observeKnowledgeBases(): Flow<List<KnowledgeBaseEntity>>

    @Query("SELECT * FROM knowledge_bases ORDER BY updatedAt DESC, name COLLATE NOCASE ASC")
    suspend fun getKnowledgeBasesOnce(): List<KnowledgeBaseEntity>

    @Query("SELECT * FROM knowledge_bases WHERE id = :id")
    suspend fun getKnowledgeBase(id: Long): KnowledgeBaseEntity?

    @Query("SELECT * FROM knowledge_bases WHERE name = :name LIMIT 1")
    suspend fun getKnowledgeBaseByName(name: String): KnowledgeBaseEntity?

    @Query("SELECT * FROM knowledge_bases WHERE id IN (:ids)")
    suspend fun getKnowledgeBasesByIds(ids: List<Long>): List<KnowledgeBaseEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertKnowledgeBase(base: KnowledgeBaseEntity): Long

    @Update
    suspend fun updateKnowledgeBase(base: KnowledgeBaseEntity)

    @Query("DELETE FROM knowledge_bases WHERE id = :id")
    suspend fun deleteKnowledgeBaseById(id: Long)

    @Query("SELECT * FROM knowledge_sources WHERE knowledgeBaseId = :knowledgeBaseId ORDER BY updatedAt DESC")
    fun observeSources(knowledgeBaseId: Long): Flow<List<KnowledgeSourceEntity>>

    @Query("SELECT * FROM knowledge_sources WHERE knowledgeBaseId = :knowledgeBaseId ORDER BY updatedAt DESC")
    suspend fun getSourcesOnce(knowledgeBaseId: Long): List<KnowledgeSourceEntity>

    @Query("SELECT * FROM knowledge_sources ORDER BY updatedAt DESC")
    suspend fun getAllSourcesOnce(): List<KnowledgeSourceEntity>

    @Query("SELECT * FROM knowledge_sources WHERE id = :id")
    suspend fun getSource(id: Long): KnowledgeSourceEntity?

    @Query("SELECT * FROM knowledge_sources WHERE id IN (:ids)")
    suspend fun getSourcesByIds(ids: List<Long>): List<KnowledgeSourceEntity>

    @Query("SELECT * FROM knowledge_sources WHERE knowledgeBaseId = :knowledgeBaseId AND sourceRef = :sourceRef LIMIT 1")
    suspend fun getSourceByRef(knowledgeBaseId: Long, sourceRef: String): KnowledgeSourceEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSource(source: KnowledgeSourceEntity): Long

    @Update
    suspend fun updateSource(source: KnowledgeSourceEntity)

    @Delete
    suspend fun deleteSource(source: KnowledgeSourceEntity)

    @Query("DELETE FROM knowledge_sources WHERE id = :id")
    suspend fun deleteSourceById(id: Long)

    @Query("UPDATE knowledge_sources SET enabled = :enabled, updatedAt = :updatedAt WHERE id = :id")
    suspend fun setSourceEnabled(id: Long, enabled: Boolean, updatedAt: Long = System.currentTimeMillis())

    @Query(
        "UPDATE knowledge_sources SET status = :status, processingStage = :stage, progressDone = :done, " +
            "progressTotal = :total, embeddedChunkCount = :embeddedChunks, errorMessage = :errorMessage, " +
            "updatedAt = :updatedAt, progressUpdatedAt = :updatedAt WHERE id = :id"
    )
    suspend fun updateSourceProgress(
        id: Long,
        status: String,
        stage: String,
        done: Int,
        total: Int,
        embeddedChunks: Int,
        errorMessage: String? = null,
        updatedAt: Long = System.currentTimeMillis()
    )

    @Query(
        "UPDATE knowledge_sources SET status = 'stale', processingStage = 'stale', updatedAt = :updatedAt " +
            "WHERE status = 'indexed' AND embeddingConfigHash != :embeddingConfigHash"
    )
    suspend fun markIndexedSourcesStaleForConfig(
        embeddingConfigHash: String,
        updatedAt: Long = System.currentTimeMillis()
    )

    @Query("DELETE FROM knowledge_chunks WHERE sourceId = :sourceId")
    suspend fun deleteChunksForSource(sourceId: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChunks(chunks: List<KnowledgeChunkEntity>)

    @Query(
        "UPDATE knowledge_chunks SET embedding = :embedding, embeddingNorm = :embeddingNorm " +
            "WHERE sourceId = :sourceId AND chunkIndex = :chunkIndex"
    )
    suspend fun updateChunkEmbedding(
        sourceId: Long,
        chunkIndex: Int,
        embedding: ByteArray,
        embeddingNorm: Float
    ): Int

    @Query(
        "SELECT * FROM knowledge_chunks " +
            "WHERE knowledgeBaseId IN (:knowledgeBaseIds) " +
            "AND embedding IS NOT NULL " +
            "AND sourceId IN (" +
            "SELECT id FROM knowledge_sources " +
            "WHERE enabled = 1 AND status = 'indexed' AND embeddingConfigHash = :embeddingConfigHash" +
            ") " +
            "ORDER BY knowledgeBaseId, sourceId, chunkIndex"
    )
    suspend fun getSearchableChunks(
        knowledgeBaseIds: List<Long>,
        embeddingConfigHash: String
    ): List<KnowledgeChunkEntity>

    @Query("SELECT * FROM knowledge_chunks WHERE id = :id")
    suspend fun getChunk(id: Long): KnowledgeChunkEntity?

    @Query("SELECT * FROM knowledge_chunks WHERE sourceId = :sourceId ORDER BY chunkIndex")
    suspend fun getChunksForSource(sourceId: Long): List<KnowledgeChunkEntity>

    @Query("SELECT * FROM knowledge_chunks WHERE sourceId = :sourceId ORDER BY chunkIndex LIMIT :limit OFFSET :offset")
    suspend fun getChunksForSourcePaged(sourceId: Long, limit: Int, offset: Int): List<KnowledgeChunkEntity>

    @Query("SELECT * FROM knowledge_chunks WHERE sourceId = :sourceId AND embedding IS NULL ORDER BY chunkIndex")
    suspend fun getChunksMissingEmbedding(sourceId: Long): List<KnowledgeChunkEntity>

    @Query("SELECT * FROM knowledge_chunks WHERE sourceId = :sourceId AND chunkIndex BETWEEN :fromIndex AND :toIndex ORDER BY chunkIndex")
    suspend fun getNeighborChunks(sourceId: Long, fromIndex: Int, toIndex: Int): List<KnowledgeChunkEntity>

    @Query("SELECT COUNT(*) FROM knowledge_bases")
    fun observeKnowledgeBaseCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM knowledge_bases")
    suspend fun getKnowledgeBaseCount(): Int

    @Query("SELECT COUNT(*) FROM knowledge_sources")
    fun observeSourceCount(): Flow<Int>

    @Query(
        "SELECT COUNT(*) FROM knowledge_sources WHERE knowledgeBaseId IN (" +
            "SELECT id FROM knowledge_bases WHERE name NOT GLOB :hiddenNameGlob" +
            ")"
    )
    fun observeSourceCountExcluding(hiddenNameGlob: String): Flow<Int>

    @Query("SELECT COUNT(*) FROM knowledge_chunks WHERE embedding IS NOT NULL")
    fun observeChunkCount(): Flow<Int>

    @Query(
        "SELECT COUNT(*) FROM knowledge_chunks WHERE embedding IS NOT NULL AND knowledgeBaseId IN (" +
            "SELECT id FROM knowledge_bases WHERE name NOT GLOB :hiddenNameGlob" +
            ")"
    )
    fun observeChunkCountExcluding(hiddenNameGlob: String): Flow<Int>

    @Query("SELECT COUNT(*) FROM knowledge_sources WHERE status = 'error'")
    fun observeErrorSourceCount(): Flow<Int>

    @Query(
        "SELECT COUNT(*) FROM knowledge_sources WHERE status = 'error' AND knowledgeBaseId IN (" +
            "SELECT id FROM knowledge_bases WHERE name NOT GLOB :hiddenNameGlob" +
            ")"
    )
    fun observeErrorSourceCountExcluding(hiddenNameGlob: String): Flow<Int>

    @Query("SELECT COUNT(*) FROM knowledge_sources WHERE status IN ('queued', 'extracting', 'chunking', 'embedding', 'stale')")
    fun observePendingSourceCount(): Flow<Int>

    @Query(
        "SELECT COUNT(*) FROM knowledge_sources " +
            "WHERE status IN ('queued', 'extracting', 'chunking', 'embedding', 'stale') " +
            "AND knowledgeBaseId IN (" +
            "SELECT id FROM knowledge_bases WHERE name NOT GLOB :hiddenNameGlob" +
            ")"
    )
    fun observePendingSourceCountExcluding(hiddenNameGlob: String): Flow<Int>

    @Query("SELECT COUNT(*) FROM knowledge_chunks WHERE knowledgeBaseId = :knowledgeBaseId AND embedding IS NOT NULL")
    suspend fun getChunkCountForBase(knowledgeBaseId: Long): Int

    @Query("SELECT COUNT(*) FROM knowledge_chunks WHERE embedding IS NOT NULL")
    suspend fun getChunkCount(): Int

    @Query("SELECT COUNT(*) FROM knowledge_chunks WHERE sourceId = :sourceId")
    suspend fun getTextChunkCountForSource(sourceId: Long): Int

    @Query("SELECT COUNT(*) FROM knowledge_chunks WHERE sourceId = :sourceId AND embedding IS NOT NULL")
    suspend fun getEmbeddedChunkCountForSource(sourceId: Long): Int
}
