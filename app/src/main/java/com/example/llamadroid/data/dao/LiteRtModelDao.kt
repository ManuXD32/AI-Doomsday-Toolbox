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

    @Query("SELECT * FROM litert_models WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): LiteRtModelEntity?

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
}
