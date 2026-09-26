package com.example.llamadroid.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update
import androidx.paging.PagingSource
import kotlinx.coroutines.flow.Flow

/** A frozen local image or video request. Terminal rows are the queue history. */
@Entity(
    tableName = "generation_queue_items",
    indices = [
        Index("status", "sortOrder"),
        Index("runId"),
        Index(value = ["finishedAtMillis", "createdAtMillis", "id"],
            orders = [Index.Order.DESC, Index.Order.DESC, Index.Order.DESC])
    ]
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

/** Queue and history row without the frozen request payload. */
data class GenerationQueueListItem(
    val id: String,
    val kind: String,
    val mode: String,
    val promptPreview: String,
    val status: String,
    val runId: String?,
    val createdAtMillis: Long,
    val startedAtMillis: Long?,
    val finishedAtMillis: Long?,
    val resultPath: String?,
    val metadataPath: String?,
    val errorMessage: String?
)

data class GenerationQueueRunSummary(
    val total: Int,
    val finished: Int,
    val succeeded: Int,
    val failed: Int,
    val waiting: Int,
    val hasMediaTimeout: Int
) {
    companion object {
        val EMPTY = GenerationQueueRunSummary(0, 0, 0, 0, 0, 0)
    }
}

data class GenerationQueuePendingOrder(val id: String, val sortOrder: Long)

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
    @Query("""SELECT id, kind, mode, promptPreview, status, runId, createdAtMillis,
        startedAtMillis, finishedAtMillis, resultPath, metadataPath, errorMessage
        FROM generation_queue_items WHERE status IN ('PENDING', 'RUNNING')
        ORDER BY CASE WHEN status = 'RUNNING' THEN 0 ELSE 1 END, sortOrder ASC""")
    fun observeQueue(): Flow<List<GenerationQueueListItem>>

    @Query("""SELECT id, kind, mode, promptPreview, status, runId, createdAtMillis,
        startedAtMillis, finishedAtMillis, resultPath, metadataPath, errorMessage
        FROM generation_queue_items INDEXED BY index_generation_queue_items_finishedAtMillis_createdAtMillis_id
        WHERE status IN ('SUCCEEDED', 'FAILED', 'STOPPED', 'INTERRUPTED')
        ORDER BY finishedAtMillis DESC, createdAtMillis DESC, id DESC""")
    fun historyPagingSource(): PagingSource<Int, GenerationQueueListItem>

    @Query("SELECT COUNT(*) FROM generation_queue_items WHERE status = 'PENDING'")
    fun observePendingCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM generation_queue_items WHERE status = 'PENDING'")
    suspend fun pendingCount(): Int

    @Query("""SELECT COUNT(*) AS total,
        COALESCE(SUM(CASE WHEN status IN ('SUCCEEDED', 'FAILED', 'STOPPED', 'INTERRUPTED') THEN 1 ELSE 0 END), 0) AS finished,
        COALESCE(SUM(CASE WHEN status = 'SUCCEEDED' THEN 1 ELSE 0 END), 0) AS succeeded,
        COALESCE(SUM(CASE WHEN status IN ('FAILED', 'INTERRUPTED') THEN 1 ELSE 0 END), 0) AS failed,
        COALESCE(SUM(CASE WHEN status = 'PENDING' THEN 1 ELSE 0 END), 0) AS waiting,
        COALESCE(SUM(CASE WHEN errorMessage = 'media_processing_time_limit' THEN 1 ELSE 0 END), 0) AS hasMediaTimeout
        FROM generation_queue_items WHERE runId = :runId""")
    fun observeRunSummary(runId: String): Flow<GenerationQueueRunSummary>

    @Query("""SELECT COUNT(*) AS total,
        COALESCE(SUM(CASE WHEN status IN ('SUCCEEDED', 'FAILED', 'STOPPED', 'INTERRUPTED') THEN 1 ELSE 0 END), 0) AS finished,
        COALESCE(SUM(CASE WHEN status = 'SUCCEEDED' THEN 1 ELSE 0 END), 0) AS succeeded,
        COALESCE(SUM(CASE WHEN status IN ('FAILED', 'INTERRUPTED') THEN 1 ELSE 0 END), 0) AS failed,
        COALESCE(SUM(CASE WHEN status = 'PENDING' THEN 1 ELSE 0 END), 0) AS waiting,
        COALESCE(SUM(CASE WHEN errorMessage = 'media_processing_time_limit' THEN 1 ELSE 0 END), 0) AS hasMediaTimeout
        FROM generation_queue_items WHERE runId = :runId""")
    suspend fun runSummary(runId: String): GenerationQueueRunSummary

    @Query("SELECT * FROM generation_queue_control WHERE id = 1")
    fun observeControl(): Flow<GenerationQueueControlEntity?>

    @Query("SELECT * FROM generation_queue_control WHERE id = 1")
    suspend fun getControl(): GenerationQueueControlEntity?

    @Query("SELECT * FROM generation_queue_items WHERE id = :id")
    suspend fun getItem(id: String): GenerationQueueItemEntity?

    @Query("SELECT * FROM generation_queue_items WHERE status = 'PENDING' ORDER BY sortOrder ASC LIMIT 1")
    suspend fun nextPending(): GenerationQueueItemEntity?

    @Query("SELECT id, sortOrder FROM generation_queue_items WHERE status = 'PENDING' ORDER BY sortOrder ASC")
    suspend fun pendingOrder(): List<GenerationQueuePendingOrder>

    @Query("UPDATE generation_queue_items SET sortOrder = :order WHERE id = :id AND status = 'PENDING'")
    suspend fun updatePendingOrder(id: String, order: Long)

    @Query("UPDATE generation_queue_items SET runId = :runId WHERE status = 'PENDING'")
    suspend fun assignRunToPending(runId: String): Int

    @Query("SELECT * FROM generation_queue_items WHERE status = 'RUNNING'")
    suspend fun runningItems(): List<GenerationQueueItemEntity>

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
