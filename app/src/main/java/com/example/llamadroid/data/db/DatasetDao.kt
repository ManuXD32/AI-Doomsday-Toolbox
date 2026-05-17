package com.example.llamadroid.data.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow

/**
 * DAO for Dataset Q&A generation operations
 */
@Dao
interface DatasetDao {
    
    // ========== Projects ==========
    
    @Query("SELECT * FROM dataset_projects ORDER BY createdAt DESC")
    fun getAllProjects(): Flow<List<DatasetProjectEntity>>
    
    @Query("SELECT * FROM dataset_projects WHERE id = :id")
    fun getProjectFlow(id: Long): Flow<DatasetProjectEntity?>
    
    @Query("SELECT * FROM dataset_projects WHERE id = :id")
    suspend fun getProject(id: Long): DatasetProjectEntity?
    
    @Insert
    suspend fun insertProject(project: DatasetProjectEntity): Long
    
    @Update
    suspend fun updateProject(project: DatasetProjectEntity)
    
    @Delete
    suspend fun deleteProject(project: DatasetProjectEntity)
    
    // ========== Sources ==========
    
    @Query("SELECT id, projectId, type, uri, name, NULL AS extractedText, addedAt FROM dataset_sources WHERE projectId = :projectId ORDER BY addedAt DESC")
    fun getSourcesForProject(projectId: Long): Flow<List<DatasetSourceEntity>>

    @Query("SELECT id, projectId, type, uri, name, NULL AS extractedText, addedAt FROM dataset_sources WHERE projectId = :projectId ORDER BY addedAt DESC")
    suspend fun getSourcesForProjectSync(projectId: Long): List<DatasetSourceEntity>
    
    @Query("SELECT id, projectId, type, uri, name, NULL AS extractedText, addedAt FROM dataset_sources WHERE id = :id")
    suspend fun getSource(id: Long): DatasetSourceEntity?

    @Query("SELECT id, projectId, type, uri, name, NULL AS extractedText, addedAt FROM dataset_sources WHERE projectId = :projectId AND uri = :uri LIMIT 1")
    suspend fun getSourceByProjectAndUri(projectId: Long, uri: String): DatasetSourceEntity?
    
    @Insert
    suspend fun insertSource(source: DatasetSourceEntity): Long
    
    @Update
    suspend fun updateSource(source: DatasetSourceEntity)
    
    @Delete
    suspend fun deleteSource(source: DatasetSourceEntity)
    
    @Query("SELECT COUNT(*) FROM dataset_sources WHERE projectId = :projectId")
    suspend fun getSourceCount(projectId: Long): Int
    
    // ========== Chunks ==========
    
    @Query("SELECT * FROM dataset_chunks WHERE projectId = :projectId ORDER BY sourceId, chunkIndex")
    fun getChunksForProject(projectId: Long): Flow<List<DatasetChunkEntity>>

    @Query("SELECT * FROM dataset_chunks WHERE projectId = :projectId ORDER BY sourceId, chunkIndex")
    suspend fun getChunksForProjectSync(projectId: Long): List<DatasetChunkEntity>
    
    @Query("SELECT * FROM dataset_chunks WHERE sourceId = :sourceId ORDER BY chunkIndex")
    fun getChunksForSource(sourceId: Long): Flow<List<DatasetChunkEntity>>
    
    @Query("SELECT * FROM dataset_chunks WHERE id = :id")
    suspend fun getChunk(id: Long): DatasetChunkEntity?
    
    @Query("SELECT * FROM dataset_chunks WHERE projectId = :projectId AND status = :status ORDER BY chunkIndex")
    suspend fun getChunksByStatus(projectId: Long, status: ChunkStatus): List<DatasetChunkEntity>
    
    @Insert
    suspend fun insertChunk(chunk: DatasetChunkEntity): Long
    
    @Insert
    suspend fun insertChunks(chunks: List<DatasetChunkEntity>)
    
    @Update
    suspend fun updateChunk(chunk: DatasetChunkEntity)
    
    @Delete
    suspend fun deleteChunk(chunk: DatasetChunkEntity)

    @Query("DELETE FROM dataset_chunks WHERE id IN (:chunkIds)")
    suspend fun deleteChunksByIds(chunkIds: List<Long>)
    
    @Query("DELETE FROM dataset_chunks WHERE sourceId = :sourceId")
    suspend fun deleteChunksForSource(sourceId: Long)
    
    @Query("SELECT COUNT(*) FROM dataset_chunks WHERE projectId = :projectId")
    suspend fun getChunkCount(projectId: Long): Int
    
    @Query("SELECT COUNT(*) FROM dataset_chunks WHERE sourceId = :sourceId")
    suspend fun getChunkCountForSource(sourceId: Long): Int
    
    // ========== Q&A ==========
    
    @Query("SELECT * FROM dataset_qa WHERE projectId = :projectId ORDER BY chunkId, id")
    fun getQAForProject(projectId: Long): Flow<List<DatasetQAEntity>>

    @Query("SELECT * FROM dataset_qa WHERE projectId = :projectId ORDER BY chunkId, id")
    suspend fun getQAForProjectSync(projectId: Long): List<DatasetQAEntity>
    
    @Query("SELECT * FROM dataset_qa WHERE chunkId = :chunkId ORDER BY id")
    fun getQAForChunk(chunkId: Long): Flow<List<DatasetQAEntity>>
    
    @Query("SELECT * FROM dataset_qa WHERE id = :id")
    suspend fun getQA(id: Long): DatasetQAEntity?
    
    @Query("SELECT * FROM dataset_qa WHERE projectId = :projectId AND score >= :minScore ORDER BY chunkId, id")
    suspend fun getQAByMinScore(projectId: Long, minScore: Int): List<DatasetQAEntity>
    
    @Query("SELECT * FROM dataset_qa WHERE projectId = :projectId AND score IS NULL ORDER BY chunkId, id")
    suspend fun getUnratedQA(projectId: Long): List<DatasetQAEntity>
    
    @Query("SELECT * FROM dataset_qa WHERE projectId = :projectId AND status = :status ORDER BY chunkId, id")
    suspend fun getQAByStatus(projectId: Long, status: QAStatus): List<DatasetQAEntity>
    
    @Insert
    suspend fun insertQA(qa: DatasetQAEntity): Long
    
    @Insert
    suspend fun insertQAs(qas: List<DatasetQAEntity>)
    
    @Update
    suspend fun updateQA(qa: DatasetQAEntity)
    
    @Delete
    suspend fun deleteQA(qa: DatasetQAEntity)
    
    @Query("DELETE FROM dataset_qa WHERE chunkId = :chunkId")
    suspend fun deleteQAForChunk(chunkId: Long)
    
    @Query("SELECT COUNT(*) FROM dataset_qa WHERE projectId = :projectId")
    suspend fun getQACount(projectId: Long): Int
    
    @Query("SELECT COUNT(*) FROM dataset_qa WHERE projectId = :projectId AND score IS NOT NULL")
    suspend fun getRatedQACount(projectId: Long): Int
    
    // ========== Prompts ==========
    
    @Query("SELECT * FROM dataset_prompts ORDER BY type, name")
    fun getAllPrompts(): Flow<List<DatasetPromptEntity>>
    
    @Query("SELECT * FROM dataset_prompts WHERE type = :type ORDER BY isDefault DESC, name")
    fun getPromptsByType(type: PromptType): Flow<List<DatasetPromptEntity>>
    
    @Query("SELECT * FROM dataset_prompts WHERE type = :type AND isDefault = 1 LIMIT 1")
    suspend fun getDefaultPrompt(type: PromptType): DatasetPromptEntity?
    
    @Query("SELECT * FROM dataset_prompts WHERE id = :id")
    suspend fun getPrompt(id: Long): DatasetPromptEntity?
    
    @Insert
    suspend fun insertPrompt(prompt: DatasetPromptEntity): Long
    
    @Update
    suspend fun updatePrompt(prompt: DatasetPromptEntity)
    
    @Delete
    suspend fun deletePrompt(prompt: DatasetPromptEntity)
    
    @Query("UPDATE dataset_prompts SET isDefault = 0 WHERE type = :type")
    suspend fun clearDefaultForType(type: PromptType)
}
