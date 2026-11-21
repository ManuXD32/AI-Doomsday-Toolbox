package com.example.llamadroid.training

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** Where a training dataset came from. Stored as the lowercase [value]. */
enum class DatasetSource(val value: String) {
    CURATED("curated"), HF("hf"), FILE("file");

    companion object {
        fun fromValue(value: String?): DatasetSource = entries.firstOrNull { it.value == value } ?: FILE
    }
}

/** Download/import lifecycle of a dataset row. */
enum class DatasetStatus(val value: String) {
    PENDING("pending"), DOWNLOADING("downloading"), READY("ready"), FAILED("failed");

    companion object {
        fun fromValue(value: String?): DatasetStatus = entries.firstOrNull { it.value == value } ?: FAILED
    }
}

/** Data format understood by `branchy prepare --format`. */
enum class DatasetFormat(val value: String) {
    AUTO("auto"), CHAT("chat"), INSTRUCTION("instruction"), TEXT("text");

    companion object {
        fun fromValue(value: String?): DatasetFormat = entries.firstOrNull { it.value == value } ?: AUTO
    }
}

/**
 * A dataset usable for Branchy training. [localPath] is the dataset directory
 * (`.../training/datasets/<id>/`) that holds the downloaded or imported files.
 */
@Entity(tableName = "training_dataset")
data class TrainingDatasetEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    /** `curated`, `hf` or `file`. */
    val source: String,
    val hfRepo: String?,
    val hfConfig: String?,
    val hfSplit: String?,
    val revision: String?,
    val licence: String,
    /** `auto`, `chat`, `instruction` or `text`. */
    val format: String,
    /** JSON object of `prepare --columns` pairs. */
    val columnMapJson: String,
    val localPath: String,
    val sizeBytes: Long,
    val rowCount: Long?,
    /** `pending`, `downloading`, `ready` or `failed`. */
    val status: String,
    val error: String?,
    val createdAt: Long
)

@Dao
interface TrainingDatasetDao {
    @Query("SELECT * FROM training_dataset ORDER BY createdAt DESC, id DESC")
    fun observeAll(): Flow<List<TrainingDatasetEntity>>

    @Query("SELECT * FROM training_dataset ORDER BY createdAt DESC, id DESC")
    suspend fun getAll(): List<TrainingDatasetEntity>

    @Query("SELECT * FROM training_dataset WHERE id = :id")
    suspend fun getById(id: Long): TrainingDatasetEntity?

    @Query("SELECT * FROM training_dataset WHERE status = :status")
    suspend fun getByStatus(status: String): List<TrainingDatasetEntity>

    @Insert
    suspend fun insert(dataset: TrainingDatasetEntity): Long

    @androidx.room.Update
    suspend fun update(dataset: TrainingDatasetEntity)

    @Query("UPDATE training_dataset SET status = :status, error = :error WHERE id = :id")
    suspend fun updateStatus(id: Long, status: String, error: String?)

    @Query("UPDATE training_dataset SET localPath = :localPath WHERE id = :id")
    suspend fun updateLocalPath(id: Long, localPath: String)

    @Query("UPDATE training_dataset SET sizeBytes = :sizeBytes, rowCount = :rowCount WHERE id = :id")
    suspend fun updateSize(id: Long, sizeBytes: Long, rowCount: Long?)

    @Query("DELETE FROM training_dataset WHERE id = :id")
    suspend fun deleteById(id: Long)
}
