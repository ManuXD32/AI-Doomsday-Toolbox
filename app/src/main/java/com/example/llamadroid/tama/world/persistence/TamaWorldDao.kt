package com.example.llamadroid.tama.world.persistence

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface TamaWorldDao {
    @Query("SELECT * FROM tama_worlds WHERE petId = :petId LIMIT 1")
    suspend fun worldForPet(petId: String): TamaWorldEntity?
    @Query("SELECT * FROM tama_worlds WHERE petId = :petId LIMIT 1")
    fun observeWorld(petId: String): Flow<TamaWorldEntity?>
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveWorld(value: TamaWorldEntity)
    @Query("SELECT * FROM tama_world_actors WHERE worldId = :worldId")
    suspend fun actor(worldId: String): TamaWorldActorEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveActor(value: TamaWorldActorEntity)
    @Query("SELECT * FROM tama_world_chunks WHERE worldId = :worldId ORDER BY chunkY, chunkX")
    suspend fun chunks(worldId: String): List<TamaChunkStateEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveChunks(values: List<TamaChunkStateEntity>)
    @Query("SELECT * FROM tama_world_structures WHERE worldId = :worldId ORDER BY id")
    suspend fun structures(worldId: String): List<TamaWorldStructureEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveStructures(values: List<TamaWorldStructureEntity>)
    @Query("SELECT * FROM tama_world_objects WHERE worldId = :worldId ORDER BY id")
    suspend fun objects(worldId: String): List<TamaWorldObjectEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveObjects(values: List<TamaWorldObjectEntity>)
    @Query("SELECT * FROM tama_world_npcs WHERE worldId = :worldId ORDER BY npcId")
    suspend fun npcs(worldId: String): List<TamaWorldNpcEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveNpcs(values: List<TamaWorldNpcEntity>)
    @Query("SELECT * FROM tama_world_relationships WHERE petId = :petId ORDER BY npcId")
    suspend fun relationships(petId: String): List<TamaWorldRelationshipEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveRelationships(values: List<TamaWorldRelationshipEntity>)
    @Query("SELECT * FROM tama_world_events WHERE petId = :petId ORDER BY timestamp DESC, id DESC LIMIT :limit OFFSET :offset")
    suspend fun events(petId: String, limit: Int = 250, offset: Int = 0): List<TamaWorldEventEntity>
    /** Bounded routine-only lookup used by journal replay compression. */
    @Query("""SELECT * FROM tama_world_events
        WHERE petId = :petId AND importance = 'ROUTINE'
        AND timestamp BETWEEN :fromTime AND :toTime
        ORDER BY timestamp DESC, id DESC LIMIT :limit""")
    suspend fun routineEventsBetween(petId: String, fromTime: Long, toTime: Long, limit: Int = 256): List<TamaWorldEventEntity>
    @Query("""SELECT * FROM tama_world_events WHERE petId = :petId AND timestamp BETWEEN :fromTime AND :toTime
        AND (:npcId IS NULL OR actorId = :npcId OR payload LIKE :npcPattern)
        ORDER BY CASE importance WHEN 'MAJOR' THEN 4 WHEN 'MEMORABLE' THEN 3 WHEN 'NOTABLE' THEN 2 ELSE 1 END DESC,
        timestamp DESC, id DESC LIMIT :limit""")
    suspend fun queryAdventures(petId: String, fromTime: Long, toTime: Long, npcId: String? = null,
        npcPattern: String? = null, limit: Int = 80): List<TamaWorldEventEntity>
    @Query("SELECT * FROM tama_world_events WHERE petId = :petId ORDER BY timestamp DESC, id DESC LIMIT 250")
    fun observeEvents(petId: String): Flow<List<TamaWorldEventEntity>>
    @Query("SELECT * FROM tama_world_events WHERE episodeId = :episodeId ORDER BY timestamp, id")
    suspend fun episodeEvents(episodeId: String): List<TamaWorldEventEntity>
    @Query("SELECT id FROM tama_world_events WHERE id IN (:ids)")
    suspend fun existingEventIds(ids: List<String>): List<String>
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun saveEvents(values: List<TamaWorldEventEntity>)
    @Query("SELECT * FROM tama_world_episodes WHERE petId = :petId ORDER BY startTime DESC LIMIT :limit OFFSET :offset")
    suspend fun episodes(petId: String, limit: Int = 250, offset: Int = 0): List<TamaWorldEpisodeEntity>
    @Query("SELECT * FROM tama_world_episodes WHERE petId = :petId ORDER BY startTime DESC LIMIT 250")
    fun observeEpisodes(petId: String): Flow<List<TamaWorldEpisodeEntity>>
    @Query("SELECT * FROM tama_world_episodes WHERE petId = :petId AND memoryStatus = 'PENDING' ORDER BY startTime LIMIT 10")
    suspend fun pendingMemories(petId: String): List<TamaWorldEpisodeEntity>
    @Query("SELECT * FROM tama_world_episodes WHERE petId = :petId AND (memoryStatus = 'APPROVED' OR (:automatic AND memoryStatus = 'PENDING')) ORDER BY startTime LIMIT 10")
    suspend fun readyMemories(petId: String, automatic: Boolean): List<TamaWorldEpisodeEntity>
    @Query("SELECT * FROM tama_world_episodes WHERE id = :id LIMIT 1")
    suspend fun episode(id: String): TamaWorldEpisodeEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveEpisodes(values: List<TamaWorldEpisodeEntity>)
    @Query("SELECT * FROM tama_world_policies WHERE petId = :petId ORDER BY createdAt DESC")
    suspend fun policies(petId: String): List<TamaPolicyCheckpointEntity>
    @Query("SELECT * FROM tama_world_policies WHERE petId = :petId AND active = 1 LIMIT 1")
    suspend fun activePolicy(petId: String): TamaPolicyCheckpointEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun savePolicies(values: List<TamaPolicyCheckpointEntity>)
    @Query("UPDATE tama_world_policies SET active = 0 WHERE petId = :petId")
    suspend fun deactivatePolicies(petId: String)
    @Query("DELETE FROM tama_world_chunks WHERE worldId = :worldId")
    suspend fun clearChunks(worldId: String)
    @Query("DELETE FROM tama_world_objects WHERE worldId = :worldId")
    suspend fun clearObjects(worldId: String)
    @Query("DELETE FROM tama_world_structures WHERE worldId = :worldId")
    suspend fun clearStructures(worldId: String)
    @Query("DELETE FROM tama_world_npcs WHERE worldId = :worldId")
    suspend fun clearNpcs(worldId: String)
    @Query("DELETE FROM tama_world_actors WHERE worldId = :worldId")
    suspend fun clearActor(worldId: String)
    @Query("DELETE FROM tama_world_events WHERE petId = :petId")
    suspend fun clearEvents(petId: String)
    @Query("DELETE FROM tama_world_episodes WHERE petId = :petId")
    suspend fun clearEpisodes(petId: String)
    @Query("DELETE FROM tama_world_relationships WHERE petId = :petId")
    suspend fun clearRelationships(petId: String)
    @Query("DELETE FROM tama_world_policies WHERE petId = :petId")
    suspend fun clearPolicies(petId: String)
    @Query("DELETE FROM tama_worlds WHERE petId = :petId")
    suspend fun clearWorld(petId: String)
    @Query("DELETE FROM tama_world_action_receipts WHERE petId = :petId")
    suspend fun clearActionReceipts(petId: String)
    @Transaction
    suspend fun clearPet(petId: String) {
        worldForPet(petId)?.let { world ->
            clearChunks(world.id)
            clearObjects(world.id)
            clearStructures(world.id)
            clearNpcs(world.id)
            clearActor(world.id)
        }
        clearEvents(petId)
        clearEpisodes(petId)
        clearRelationships(petId)
        clearPolicies(petId)
        clearActionReceipts(petId)
        clearWorld(petId)
    }
}
