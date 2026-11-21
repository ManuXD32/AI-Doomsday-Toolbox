package com.example.llamadroid.branchy

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface BranchyRunDao {
    @Query("SELECT * FROM branchy_run ORDER BY updatedAt DESC, id DESC")
    fun observeAll(): Flow<List<BranchyRunEntity>>

    @Query("SELECT * FROM branchy_run WHERE id = :id")
    fun observeById(id: Long): Flow<BranchyRunEntity?>

    @Query("SELECT * FROM branchy_run WHERE id = :id")
    suspend fun getById(id: Long): BranchyRunEntity?

    @Query("SELECT * FROM branchy_run WHERE status IN (:statuses)")
    suspend fun getByStatuses(statuses: List<String>): List<BranchyRunEntity>

    @Insert
    suspend fun insert(run: BranchyRunEntity): Long

    @Update
    suspend fun update(run: BranchyRunEntity)

    @Query("UPDATE branchy_run SET configJson = :configJson, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateConfig(id: Long, configJson: String, updatedAt: Long)

    @Query("UPDATE branchy_run SET status = :status, error = :error, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateStatus(id: Long, status: String, error: String?, updatedAt: Long)

    @Query(
        "UPDATE branchy_run SET lastStep = :lastStep, totalSteps = :totalSteps, lastLoss = :lastLoss, " +
            "lastValLoss = COALESCE(:lastValLoss, lastValLoss), tokensSeen = :tokensSeen, updatedAt = :updatedAt WHERE id = :id"
    )
    suspend fun updateProgress(
        id: Long,
        lastStep: Long,
        totalSteps: Long,
        lastLoss: Double?,
        lastValLoss: Double?,
        tokensSeen: Long,
        updatedAt: Long
    )

    @Query("DELETE FROM branchy_run WHERE id = :id")
    suspend fun deleteById(id: Long)
}
