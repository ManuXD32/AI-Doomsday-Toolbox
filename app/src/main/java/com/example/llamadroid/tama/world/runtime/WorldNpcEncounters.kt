package com.example.llamadroid.tama.world.runtime

import com.example.llamadroid.tama.world.core.EventImportance
import com.example.llamadroid.tama.world.core.RelationshipProjection
import com.example.llamadroid.tama.world.core.WorldEffectRequest
import com.example.llamadroid.tama.world.core.WorldState

/** Only completed NPC-to-pet interactions enter the pet's canonical relationship records. */
internal object WorldNpcEncounters {
    fun effects(before: WorldState, after: WorldState): List<WorldEffectRequest> = buildList {
        if (after.petId.isBlank()) return@buildList
        val previous = before.npcs.associateBy { it.id }
        after.npcs.forEach { npc ->
            val current = npc.relationships[after.petId] ?: return@forEach
            val prior = previous[npc.id]?.relationships?.get(after.petId) ?: RelationshipProjection()
            if (prior == current) return@forEach
            add(WorldEffectRequest.RelationshipDelta(npc.id, current.familiarity - prior.familiarity,
                current.friendship - prior.friendship, current.trust - prior.trust, "npc_initiated_social"))
            add(WorldEffectRequest.Event("npc_social", EventImportance.NOTABLE, npc.id,
                mapOf("npcId" to npc.id, "recipientId" to after.petId)))
        }
    }
}
