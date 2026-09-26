package com.example.llamadroid.data.model.library

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/** Durable operation metadata. JSON keeps the journal forward-compatible as the preview grows. */
@Entity(
    tableName = "model_deletion_journal_operations",
    indices = [
        Index(value = ["status"]),
        Index(value = ["updatedAt"]),
        Index(value = ["targetKey"])
    ]
)
data class ModelDeletionJournalOperationEntity(
    @androidx.room.PrimaryKey val operationId: String,
    val targetKey: String,
    val targetKind: String,
    val status: String,
    val previewJson: String,
    val resultJson: String? = null,
    val createdAt: Long,
    val updatedAt: Long
)

/** One row per canonical component path, updated after each deletion attempt. */
@Entity(
    tableName = "model_deletion_journal_paths",
    primaryKeys = ["operationId", "path"],
    foreignKeys = [
        ForeignKey(
            entity = ModelDeletionJournalOperationEntity::class,
            parentColumns = ["operationId"],
            childColumns = ["operationId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["operationId", "status"]),
        Index(value = ["updatedAt"])
    ]
)
data class ModelDeletionJournalPathEntity(
    val operationId: String,
    val path: String,
    val status: String,
    val failureCode: String? = null,
    val failureMessage: String? = null,
    val updatedAt: Long
)

@Dao
interface ModelDeletionJournalDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertOperation(operation: ModelDeletionJournalOperationEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPaths(paths: List<ModelDeletionJournalPathEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPath(path: ModelDeletionJournalPathEntity)

    @Query("DELETE FROM model_deletion_journal_paths WHERE operationId = :operationId")
    suspend fun deletePaths(operationId: String)

    @Query("SELECT * FROM model_deletion_journal_operations WHERE operationId = :operationId LIMIT 1")
    suspend fun getOperation(operationId: String): ModelDeletionJournalOperationEntity?

    @Query("SELECT * FROM model_deletion_journal_paths WHERE operationId = :operationId ORDER BY path ASC")
    suspend fun getPaths(operationId: String): List<ModelDeletionJournalPathEntity>

    @Query(
        "UPDATE model_deletion_journal_operations SET status = :status, resultJson = :resultJson, " +
            "updatedAt = :updatedAt WHERE operationId = :operationId"
    )
    suspend fun finishOperation(
        operationId: String,
        status: String,
        resultJson: String?,
        updatedAt: Long
    ): Int

    @Query("UPDATE model_deletion_journal_operations SET updatedAt = :updatedAt WHERE operationId = :operationId")
    suspend fun touchOperation(operationId: String, updatedAt: Long): Int

    @Query(
        "SELECT * FROM model_deletion_journal_operations " +
            "WHERE status IN ('IN_PROGRESS', 'RECOVERABLE', 'FAILED') " +
            "ORDER BY updatedAt DESC LIMIT :limit"
    )
    suspend fun getRecoverableOperations(limit: Int): List<ModelDeletionJournalOperationEntity>
}
