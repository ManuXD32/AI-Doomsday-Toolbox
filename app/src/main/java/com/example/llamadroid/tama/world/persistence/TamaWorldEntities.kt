package com.example.llamadroid.tama.world.persistence

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

@Serializable
@Entity(tableName = "tama_worlds", indices = [Index(value = ["petId"], unique = true)])
data class TamaWorldEntity(
    @PrimaryKey val id: String,
    val petId: String,
    val seed: Long,
    val generatorVersion: Int,
    val width: Int,
    val height: Int,
    val createdAt: Long,
    val lastSimulatedAt: Long,
    val stateJson: String
)

@Serializable
@Entity(tableName = "tama_world_actors")
data class TamaWorldActorEntity(@PrimaryKey val worldId: String, val petId: String, val payload: String)

@Serializable
@Entity(tableName = "tama_world_chunks", primaryKeys = ["worldId", "chunkX", "chunkY"])
data class TamaChunkStateEntity(val worldId: String, val chunkX: Int, val chunkY: Int, val payload: String)

@Serializable
@Entity(tableName = "tama_world_structures", primaryKeys = ["worldId", "id"])
data class TamaWorldStructureEntity(val worldId: String, val id: String, val payload: String)

@Serializable
@Entity(tableName = "tama_world_objects", primaryKeys = ["worldId", "id"])
data class TamaWorldObjectEntity(val worldId: String, val id: String, val payload: String)

@Serializable
@Entity(tableName = "tama_world_npcs", primaryKeys = ["worldId", "npcId"])
data class TamaWorldNpcEntity(val worldId: String, val npcId: String, val payload: String)

@Serializable
@Entity(tableName = "tama_world_relationships", primaryKeys = ["petId", "npcId"])
data class TamaWorldRelationshipEntity(
    val petId: String,
    val npcId: String,
    val familiarity: Float,
    val friendship: Float,
    val trust: Float,
    val lastInteraction: Long,
    val sharedEventCount: Int
)

/** This table can only receive verified living-world events. Training has no dependency on it. */
@Serializable
@Entity(tableName = "tama_world_events", indices = [Index(value = ["petId", "timestamp"]), Index(value = ["episodeId"])])
data class TamaWorldEventEntity(
    @PrimaryKey val id: String,
    val worldId: String,
    val petId: String,
    val timestamp: Long,
    val importance: String,
    val actorId: String,
    val eventType: String,
    val payload: String,
    val memoryEligible: Boolean,
    val episodeId: String?
)

@Serializable
@Entity(tableName = "tama_world_episodes", indices = [Index(value = ["petId", "startTime"])])
data class TamaWorldEpisodeEntity(
    @PrimaryKey val id: String,
    val worldId: String,
    val petId: String,
    val startTime: Long,
    val endTime: Long,
    val title: String,
    val summary: String,
    val importance: String,
    val memoryStatus: String,
    val evidenceJson: String
)

/** Only explicitly adopted inference artifacts are stored with the living pet. */
@Serializable
@Entity(tableName = "tama_world_policies", indices = [Index(value = ["petId", "createdAt"])])
data class TamaPolicyCheckpointEntity(
    @PrimaryKey val id: String,
    val petId: String,
    val version: Int,
    val parentId: String?,
    val modelHash: String,
    val inferenceArtifact: String,
    val metadataJson: String,
    val active: Boolean,
    val createdAt: Long
)
