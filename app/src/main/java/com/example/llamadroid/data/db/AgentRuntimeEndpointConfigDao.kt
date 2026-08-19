package com.example.llamadroid.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface AgentRuntimeEndpointConfigDao {
    @Query("SELECT * FROM agent_runtime_endpoint_configs ORDER BY name COLLATE NOCASE ASC")
    fun observeAll(): Flow<List<AgentRuntimeEndpointConfigEntity>>

    @Query("SELECT * FROM agent_runtime_endpoint_configs WHERE id = :id LIMIT 1")
    suspend fun get(id: Long): AgentRuntimeEndpointConfigEntity?

    @Query("SELECT * FROM agent_runtime_endpoint_configs ORDER BY name COLLATE NOCASE ASC")
    suspend fun getAll(): List<AgentRuntimeEndpointConfigEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(config: AgentRuntimeEndpointConfigEntity): Long

    @Delete
    suspend fun delete(config: AgentRuntimeEndpointConfigEntity)

    @Query("DELETE FROM agent_runtime_endpoint_configs WHERE id = :id")
    suspend fun deleteById(id: Long)
}
