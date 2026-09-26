package com.example.llamadroid.tama.world.memory

import com.example.llamadroid.tama.world.core.Biome
import com.example.llamadroid.tama.world.core.EventImportance
import com.example.llamadroid.tama.world.core.StructureType
import com.example.llamadroid.tama.world.core.WorldCoordinate
import com.example.llamadroid.tama.world.core.WorldEffectRequest
import com.example.llamadroid.tama.world.core.WorldGenerator
import com.example.llamadroid.tama.world.core.WorldState
import com.example.llamadroid.tama.world.persistence.TamaWorldRelationshipEntity

/** Derives milestones only from committed facts, without an LLM or reward hook. */
internal class LivingAdventureMilestones {
    private var cachedCoordinates: List<WorldCoordinate>? = null
    private var cachedWorldId: String? = null
    private var cachedBiomes = emptySet<Biome>()

    fun events(before: WorldState, after: WorldState,
               relationshipsBefore: List<TamaWorldRelationshipEntity>,
               relationshipsAfter: List<TamaWorldRelationshipEntity>): List<WorldEffectRequest.Event> = buildList {
        val knownIds = before.knownPlaces.map { it.id }.toHashSet()
        after.knownPlaces.filter { it.id !in knownIds }.forEach { place ->
            val gate = place.kind == StructureType.ADVENTURE_GATE.name
            add(WorldEffectRequest.Event(if (gate) "ADVENTURE_GATE_DISCOVERED" else "PLACE_DISCOVERED",
                if (gate) EventImportance.MAJOR else EventImportance.MEMORABLE, after.petId,
                mapOf("locationId" to place.id, "x" to place.x.toString(), "y" to place.y.toString())))
        }
        val previousBiomes = biomes(before)
        val currentBiomes = biomes(after)
        (currentBiomes - previousBiomes).sortedBy { it.ordinal }.forEach { biome ->
            add(WorldEffectRequest.Event("BIOME_DISCOVERED", EventImportance.MEMORABLE, after.petId,
                mapOf("discoveredBiome" to biome.name)))
        }
        val previous = relationshipsBefore.associateBy { it.npcId }
        relationshipsAfter.forEach { relationship ->
            val old = previous[relationship.npcId]
            if (relationship.sharedEventCount > 0 && (old?.sharedEventCount ?: 0) == 0 &&
                (old?.familiarity ?: 0f) == 0f && (old?.friendship ?: 0f) == 0f) {
                add(WorldEffectRequest.Event("FIRST_MEETING", EventImportance.MEMORABLE, after.petId,
                    mapOf("npcId" to relationship.npcId)))
            }
            if (relationship.friendship >= CLOSE_FRIENDSHIP && (old?.friendship ?: 0f) < CLOSE_FRIENDSHIP) {
                add(WorldEffectRequest.Event("CLOSE_FRIENDSHIP", EventImportance.MAJOR, after.petId,
                    mapOf("npcId" to relationship.npcId, "friendship" to relationship.friendship.toString())))
            }
        }
    }

    private fun biomes(state: WorldState): Set<Biome> {
        if (cachedWorldId == state.worldId && cachedCoordinates === state.explored) return cachedBiomes
        val result = state.explored.asSequence().map { WorldGenerator.biomeAt(state, it.x, it.y) }.toSet()
        cachedWorldId = state.worldId
        cachedCoordinates = state.explored
        cachedBiomes = result
        return result
    }

    companion object { const val CLOSE_FRIENDSHIP = 60f }
}
