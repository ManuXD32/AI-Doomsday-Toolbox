package com.example.llamadroid.tama.world.persistence

import androidx.room.withTransaction
import com.example.llamadroid.tama.db.TamaDatabase
import com.example.llamadroid.tama.world.core.WorldState
import com.example.llamadroid.tama.world.core.WorldGenerator
import com.example.llamadroid.tama.world.core.WorldCoordinate
import com.example.llamadroid.tama.world.runtime.WorldPolicyRepository
import java.util.Base64
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

/** Explicit living-world boundary: no trainer session, rollout, evaluation event or training log. */
@Serializable
data class WorldTransfer(
    val state: WorldState,
    val relationships: List<TamaWorldRelationshipEntity> = emptyList(),
    val events: List<TamaWorldEventEntity> = emptyList(),
    val episodes: List<TamaWorldEpisodeEntity> = emptyList(),
    val adoptedPolicies: List<TamaPolicyCheckpointEntity> = emptyList(),
    val actionReceipts: List<TamaWorldActionReceiptEntity> = emptyList()
)

object WorldTransfers {
    suspend fun export(database: TamaDatabase, petId: String): WorldTransfer? = database.withTransaction {
        val state = WorldStateStore(database).load(petId) ?: return@withTransaction null
        val dao = database.worldDao()
        WorldTransfer(state, dao.relationships(petId), dao.events(petId, Int.MAX_VALUE),
            dao.episodes(petId, Int.MAX_VALUE), dao.policies(petId),
            database.worldActionReceiptDao().recent(petId, Int.MAX_VALUE))
    }

    /** Validate a portable world before any caller replaces live rows or files. */
    fun validate(petId: String, value: WorldTransfer?) {
        if (value != null) {
            require(value.state.petId == petId && value.state.worldId.isNotBlank()) { "World belongs to a different pet" }
            require(value.state.actor.actorId == petId) { "Invalid actor ownership" }
            require(value.state.generatorVersion in WorldGenerator.supportedGeneratorVersions) { "Unsupported world generator" }
            require(value.state.width == 256 && value.state.height == 256) { "Invalid living-world dimensions" }
            require(value.state.contains(WorldCoordinate(value.state.actor.x, value.state.actor.y))) { "Invalid actor position" }
            require(value.relationships.all { it.petId == petId }) { "Invalid relationship ownership" }
            require(value.events.all { it.petId == petId && it.worldId == value.state.worldId }) { "Invalid event ownership" }
            require(value.episodes.all { it.petId == petId && it.worldId == value.state.worldId }) { "Invalid episode ownership" }
            require(value.adoptedPolicies.all { it.petId == petId }) { "Invalid policy ownership" }
            require(value.adoptedPolicies.count { it.active } <= 1) { "Multiple active brains" }
            require(value.actionReceipts.map { it.id }.toSet().size == value.actionReceipts.size) { "Duplicate action receipts" }
            require(value.actionReceipts.all { it.petId == petId && it.worldId == value.state.worldId &&
                it.id.isNotBlank() && it.status in TamaWorldActionReceiptStatus.active + TamaWorldActionReceiptStatus.terminal
            }) { "Invalid action receipt ownership or status" }
            value.actionReceipts.forEach { receipt ->
                val request = Json.decodeFromString<TamaWorldActionReceiptRequest>(receipt.requestJson)
                require(request.receiptId == receipt.id && request.petId == petId &&
                    request.worldId == value.state.worldId && request.kind == receipt.kind) { "Action receipt facts do not match" }
            }
            value.adoptedPolicies.forEach { policy ->
                val bytes = Base64.getDecoder().decode(policy.inferenceArtifact)
                WorldPolicyRepository.validate(bytes)
                require(WorldPolicyRepository.sha256(bytes) == policy.modelHash) { "Policy content hash mismatch" }
            }
        }
    }

    suspend fun restore(database: TamaDatabase, petId: String, value: WorldTransfer?) {
        validate(petId, value)
        database.withTransaction {
            val dao = database.worldDao()
            dao.clearPet(petId)
            if (value != null) {
                WorldStateStore(database).save(value.state)
                dao.saveRelationships(value.relationships)
                dao.saveEvents(value.events)
                dao.saveEpisodes(value.episodes)
                dao.savePolicies(value.adoptedPolicies)
                value.actionReceipts.forEach { database.worldActionReceiptDao().save(it) }
            }
        }
    }
}
