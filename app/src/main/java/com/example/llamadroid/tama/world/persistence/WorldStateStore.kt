package com.example.llamadroid.tama.world.persistence

import androidx.room.withTransaction
import com.example.llamadroid.tama.db.TamaDatabase
import com.example.llamadroid.tama.world.core.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

/** Sparse state only. Terrain is reconstructed using the world's pinned generator version. */
class WorldStateStore(private val database: TamaDatabase) {
    private val dao = database.worldDao()
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    @Serializable
    private data class ChunkPayload(
        val explored: List<Discovery> = emptyList(),
        val deltas: List<Change> = emptyList()
    )
    @Serializable private data class Discovery(val order: Int, val coordinate: WorldCoordinate)
    @Serializable private data class Change(val order: Int, val delta: WorldDelta)

    @Serializable
    private data class Metadata(
        val state: WorldState,
        val structureOrder: List<String>,
        val objectOrder: List<String>,
        val npcOrder: List<String>
    )

    suspend fun load(petId: String): WorldState? = database.withTransaction {
        val header = dao.worldForPet(petId) ?: return@withTransaction null
        val metadata = json.decodeFromString<Metadata>(header.stateJson)
        val base = metadata.state
        require(base.petId == petId && base.worldId == header.id) { "World ownership mismatch" }
        val chunkData = dao.chunks(header.id).map { json.decodeFromString<ChunkPayload>(it.payload) }
        base.copy(
            actor = dao.actor(header.id)?.let { json.decodeFromString<WorldActor>(it.payload) } ?: base.actor,
            structures = dao.structures(header.id).map { json.decodeFromString<WorldStructure>(it.payload) }.associateBy { it.id }
                .let { rows -> metadata.structureOrder.map { rows.getValue(it) } },
            objects = dao.objects(header.id).map { json.decodeFromString<WorldObject>(it.payload) }.associateBy { it.id }
                .let { rows -> metadata.objectOrder.map { rows.getValue(it) } },
            npcs = dao.npcs(header.id).map { json.decodeFromString<WorldNpc>(it.payload) }.associateBy { it.id }
                .let { rows -> metadata.npcOrder.map { rows.getValue(it) } },
            explored = chunkData.flatMap { it.explored }.sortedBy { it.order }.map { it.coordinate },
            deltas = chunkData.flatMap { it.deltas }.sortedBy { it.order }.map { it.delta }
        )
    }

    suspend fun save(state: WorldState, previous: WorldState? = null) = database.withTransaction {
        require(state.petId.isNotBlank() && state.worldId.isNotBlank()) { "World ownership required" }
        val previousHeader = dao.worldForPet(state.petId)
        val metadata = state.copy(
            actor = WorldActor(actorId = state.petId), structures = emptyList(), objects = emptyList(),
            npcs = emptyList(), explored = emptyList(), deltas = emptyList()
        )
        dao.saveWorld(TamaWorldEntity(
            id = state.worldId, petId = state.petId, seed = state.seed,
            generatorVersion = state.generatorVersion, width = state.width, height = state.height,
            createdAt = previousHeader?.createdAt ?: state.lastSimulatedAt,
            lastSimulatedAt = state.lastSimulatedAt, stateJson = json.encodeToString(Metadata(metadata,
                state.structures.map { it.id }, state.objects.map { it.id }, state.npcs.map { it.id }))
        ))
        // Canonical pet needs are read from TamaPet on every step, never restored from actor storage.
        dao.saveActor(TamaWorldActorEntity(state.worldId, state.petId,
            json.encodeToString(state.actor.copy(needs = NeedsProjection()))))
        if (previous == null || previous.structures != state.structures) {
            dao.clearStructures(state.worldId)
            dao.saveStructures(state.structures.map { TamaWorldStructureEntity(state.worldId, it.id, json.encodeToString(it)) })
        }
        if (previous == null || previous.objects != state.objects) {
            dao.clearObjects(state.worldId)
            dao.saveObjects(state.objects.map { TamaWorldObjectEntity(state.worldId, it.id, json.encodeToString(it)) })
        }
        if (previous == null || previous.npcs != state.npcs) {
            dao.clearNpcs(state.worldId)
            dao.saveNpcs(state.npcs.map { TamaWorldNpcEntity(state.worldId, it.id, json.encodeToString(it)) })
        }
        if (previous == null || previous.explored != state.explored || previous.deltas != state.deltas) {
            val explored = state.explored.mapIndexed { index, coordinate -> Discovery(index, coordinate) }
                .groupBy { WorldChunkCoordinate(it.coordinate.x / CHUNK_SIZE_TILES, it.coordinate.y / CHUNK_SIZE_TILES) }
            val deltas = state.deltas.mapIndexed { index, delta -> Change(index, delta) }
                .groupBy { WorldChunkCoordinate(it.delta.x / CHUNK_SIZE_TILES, it.delta.y / CHUNK_SIZE_TILES) }
            val keys = (explored.keys + deltas.keys).sortedWith(compareBy({ it.y }, { it.x }))
            dao.clearChunks(state.worldId)
            dao.saveChunks(keys.map { key -> TamaChunkStateEntity(state.worldId, key.x, key.y,
                json.encodeToString(ChunkPayload(explored[key].orEmpty(), deltas[key].orEmpty()))) })
        }
    }
}
