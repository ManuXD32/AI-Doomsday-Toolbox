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
    tableName = "download_tasks",
    indices = [
        Index(value = ["modelType"]),
        Index(value = ["status"]),
        Index(value = ["updatedAt"])
    ]
)
data class DownloadTaskEntity(
    @PrimaryKey val id: String,
    val url: String,
    val destPath: String,
    val filename: String,
    val repoId: String,
    val progressKey: String,
    val modelType: String,
    val isVision: Boolean = false,
    val sdCapabilities: String? = null,
    val sdFamily: String? = null,
    val sdVariant: String? = null,
    val sdCompatProfiles: String? = null,
    val onnxCapabilities: String? = null,
    val onnxAssetKind: String? = null,
    val onnxPipelineFamily: String? = null,
    val onnxReferenceUri: String? = null,
    val onnxReferencePath: String? = null,
    val onnxInstallKind: String? = null,
    val onnxInstallDirPath: String? = null,
    val huggingFaceToken: String? = null,
    val liteRtDisplayName: String? = null,
    val liteRtSourceUri: String? = null,
    val liteRtBackendPreference: String? = null,
    val liteRtSupportsCpu: Boolean? = null,
    val liteRtSupportsGpu: Boolean? = null,
    val liteRtSupportsVision: Boolean? = null,
    val liteRtSupportsAudio: Boolean? = null,
    val liteRtSupportsEmbedding: Boolean? = null,
    val liteRtMaxContextTokens: Int? = null,
    val sourceId: String? = null,
    val artifactFamily: String? = null,
    val artifactRole: String? = null,
    val pendingArtifactId: String? = null,
    @androidx.room.ColumnInfo(defaultValue = "0") val stageOnly: Boolean = false,
    val status: String = DOWNLOAD_TASK_STATUS_ACTIVE,
    val bytesDownloaded: Long = 0L,
    val totalBytes: Long? = null,
    val lastError: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

const val DOWNLOAD_TASK_STATUS_ACTIVE = "ACTIVE"
const val DOWNLOAD_TASK_STATUS_RESUMABLE = "RESUMABLE"
const val DOWNLOAD_TASK_STATUS_FAILED = "FAILED"
const val DOWNLOAD_TASK_STATUS_COMPLETED = "COMPLETED"
const val DOWNLOAD_TASK_STATUS_CANCELLED = "CANCELLED"
const val DOWNLOAD_TASK_STATUS_DISCARDED = "DISCARDED"
const val DOWNLOAD_TASK_STATUS_STALE = "STALE"

@Dao
interface DownloadTaskDao {
    @Query("SELECT * FROM download_tasks ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<DownloadTaskEntity>>

    @Query("SELECT * FROM download_tasks WHERE modelType IN (:modelTypes) ORDER BY updatedAt DESC")
    fun observeByModelTypes(modelTypes: List<String>): Flow<List<DownloadTaskEntity>>

    @Query("SELECT * FROM download_tasks WHERE (artifactFamily IS NULL AND modelType IN (:modelTypes)) OR artifactFamily = :family ORDER BY updatedAt DESC")
    fun observeByLibraryFamily(modelTypes: List<String>, family: String): Flow<List<DownloadTaskEntity>>

    @Query("SELECT * FROM download_tasks WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): DownloadTaskEntity?

    @Query("SELECT * FROM download_tasks WHERE filename = :filename LIMIT 1")
    suspend fun getByFilename(filename: String): DownloadTaskEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(task: DownloadTaskEntity)

    @Query(
        "UPDATE download_tasks SET status = :status, bytesDownloaded = :bytesDownloaded, " +
            "totalBytes = :totalBytes, lastError = :lastError, updatedAt = :updatedAt WHERE id = :id"
    )
    suspend fun updateState(
        id: String,
        status: String,
        bytesDownloaded: Long,
        totalBytes: Long?,
        lastError: String?,
        updatedAt: Long = System.currentTimeMillis()
    )

    @Query("UPDATE download_tasks SET status = :status, lastError = :lastError, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateStatus(
        id: String,
        status: String,
        lastError: String? = null,
        updatedAt: Long = System.currentTimeMillis()
    )

    @Query("DELETE FROM download_tasks WHERE id = :id")
    suspend fun deleteById(id: String)
}
