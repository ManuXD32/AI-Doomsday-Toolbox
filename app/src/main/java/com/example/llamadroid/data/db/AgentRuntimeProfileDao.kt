package com.example.llamadroid.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** Room access for the authoritative per-agent runtime profiles. */
@Dao
interface AgentRuntimeProfileDao {
    @Query("SELECT * FROM agent_runtime_profiles ORDER BY agentKey COLLATE NOCASE ASC")
    fun observeAll(): Flow<List<AgentRuntimeProfileEntity>>

    @Query("SELECT * FROM agent_runtime_profiles WHERE agentKey = :agentKey LIMIT 1")
    fun observe(agentKey: String): Flow<AgentRuntimeProfileEntity?>

    @Query("SELECT * FROM agent_runtime_profiles WHERE agentKey = :agentKey LIMIT 1")
    suspend fun get(agentKey: String): AgentRuntimeProfileEntity?

    @Query("SELECT * FROM agent_runtime_profiles ORDER BY agentKey COLLATE NOCASE ASC")
    suspend fun getAll(): List<AgentRuntimeProfileEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(profile: AgentRuntimeProfileEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(profiles: List<AgentRuntimeProfileEntity>)

    @Delete
    suspend fun delete(profile: AgentRuntimeProfileEntity)

    @Query("DELETE FROM agent_runtime_profiles WHERE agentKey = :agentKey")
    suspend fun deleteByAgentKey(agentKey: String)

    @Query("UPDATE agent_runtime_profiles SET endpointConfigId = NULL, model = NULL, updatedAt = :updatedAt WHERE endpointConfigId = :endpointConfigId")
    suspend fun clearEndpointConfigReferences(endpointConfigId: Long, updatedAt: Long)
}
