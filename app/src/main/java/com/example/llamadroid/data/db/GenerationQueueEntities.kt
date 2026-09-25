package com.example.llamadroid.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/** A frozen local image or video request. Terminal rows are the queue history. */
@Entity(
    tableName = "generation_queue_items",
    indices = [Index("status", "sortOrder"), Index("runId")]
)
data class GenerationQueueItemEntity(
    @PrimaryKey val id: String,
    val kind: String,
    val mode: String,
    val promptPreview: String,
    val configJson: String,
    val sortOrder: Long,
    val status: String = "PENDING",
    val runId: String? = null,
    val createdAtMillis: Long,
    val startedAtMillis: Long? = null,
    val finishedAtMillis: Long? = null,
    val resultPath: String? = null,
    val metadataPath: String? = null,
    val errorMessage: String? = null
)

/** There is exactly one on-device generation queue, shared by both workspaces. */
@Entity(tableName = "generation_queue_control")
data class GenerationQueueControlEntity(
    @PrimaryKey val id: Int = 1,
    val state: String = "IDLE",
    val scheduledAtMillis: Long? = null,
    val runId: String? = null,
    val activeItemId: String? = null,
    val updatedAtMillis: Long = System.currentTimeMillis()
)

@Dao
interface GenerationQueueDao {
    @Query("SELECT * FROM generation_queue_items WHERE status IN ('PENDING', 'RUNNING') ORDER BY CASE WHEN status = 'RUNNING' THEN 0 ELSE 1 END, sortOrder ASC")
    fun observeQueue(): Flow<List<GenerationQueueItemEntity>>

    @Query("SELECT * FROM generation_queue_items WHERE status IN ('SUCCEEDED', 'FAILED', 'STOPPED', 'INTERRUPTED') ORDER BY finishedAtMillis DESC, createdAtMillis DESC")
    fun observeHistory(): Flow<List<GenerationQueueItemEntity>>

    @Query("SELECT * FROM generation_queue_control WHERE id = 1")
    fun observeControl(): Flow<GenerationQueueControlEntity?>

    @Query("SELECT * FROM generation_queue_control WHERE id = 1")
    suspend fun getControl(): GenerationQueueControlEntity?

    @Query("SELECT * FROM generation_queue_items WHERE id = :id")
    suspend fun getItem(id: String): GenerationQueueItemEntity?

    @Query("SELECT * FROM generation_queue_items WHERE status = 'PENDING' ORDER BY sortOrder ASC")
    suspend fun pendingItems(): List<GenerationQueueItemEntity>

    @Query("SELECT * FROM generation_queue_items WHERE status = 'RUNNING'")
    suspend fun runningItems(): List<GenerationQueueItemEntity>

    @Query("SELECT * FROM generation_queue_items WHERE runId = :runId ORDER BY sortOrder ASC")
    suspend fun runItems(runId: String): List<GenerationQueueItemEntity>

    @Query("SELECT COALESCE(MAX(sortOrder), 0) FROM generation_queue_items")
    suspend fun maxSortOrder(): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putItem(item: GenerationQueueItemEntity)

    @Update
    suspend fun updateItem(item: GenerationQueueItemEntity)

    @Query("DELETE FROM generation_queue_items WHERE id = :id AND status = 'PENDING'")
    suspend fun deletePending(id: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putControl(control: GenerationQueueControlEntity)
}
