package com.example.llamadroid.tama.world.runtime

import android.content.Context
import com.example.llamadroid.R
import com.example.llamadroid.tama.data.*
import com.example.llamadroid.tama.db.TamaDatabase
import com.example.llamadroid.tama.world.core.WorldState
import java.util.Calendar

/** Existing NPC dialogue, gifts and market flows, activated by a physical meeting. */
internal class WorldSocialEncounters(private val context: Context, private val database: TamaDatabase) {
    data class Meeting(val pet: TamaPet, val events: List<Pair<EventType, String>> = emptyList())

    suspend fun meet(pet: TamaPet, state: WorldState, npcId: String): Meeting {
        val npc = state.npcs.firstOrNull { it.id == npcId } ?: return Meeting(pet)
        if (npc.coordinate.chebyshevDistanceTo(state.actor.coordinate) > 1) return Meeting(pet)
        val ambient = TamaAmbientNpcCatalog.byId(npcId)
        if (ambient != null) {
            return Meeting(pet.copy(currentAmbientNpc = TamaAmbientNpcState(npcId,
                index(state.seed, npcId, ambient.lines.size), state.lastSimulatedAt)))
        }
        val definition = TamaParkSocialCatalog.npcById(npcId) ?: return Meeting(pet)
        val calendar = Calendar.getInstance().apply { timeInMillis = state.lastSimulatedAt }
        val dateKey = TamaParkSocialCatalog.parkDateKey(calendar)
        val hour = calendar.get(Calendar.HOUR_OF_DAY)
        if (definition.specialType == TamaParkEncounterType.RECYCLER &&
            (calendar.get(Calendar.DAY_OF_MONTH) !in setOf(10, 20, 30) || hour !in 8..16 || pet.lastRecyclerEncounterDate == dateKey)) return Meeting(pet)
        if (definition.specialType == TamaParkEncounterType.SELLER &&
            (calendar.get(Calendar.DAY_OF_WEEK) != Calendar.THURSDAY || hour !in 8..17)) return Meeting(pet)
        val existing = pet.currentParkEncounter
        if (existing?.phase in setOf(TamaParkEncounterPhase.CLEANUP, TamaParkEncounterPhase.SELLER_MARKET)) return Meeting(pet)
        var encounter = TamaParkEncounter(npcId, definition.specialType,
            lineIndex = index(state.seed xor dateKey.hashCode().toLong(), npcId, definition.lines.size), startedAt = state.lastSimulatedAt)
        var inventory = pet.inventory
        val events = mutableListOf<Pair<EventType, String>>()
        if (definition.specialType == TamaParkEncounterType.REGULAR &&
            index(state.seed xor dateKey.hashCode().toLong(), npcId, 10) == 0) {
            val midnight = calendar.apply {
                set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            }.timeInMillis
            if (database.tamaDao().getEventsSince(pet.id, midnight).none { it.eventType == EventType.RECEIVED_GIFT.name }) {
                val crops = CropDefinitions.CROPS.keys.sorted()
                val crop = crops[index(state.seed, dateKey, crops.size)]
                val seed = InventoryItem("seed_$crop", seedDisplayText(crop).resolve(context.resources.configuration.locales[0]), ItemType.SEED)
                val prior = inventory.firstOrNull { it.id == seed.id }
                inventory = inventory.filterNot { it.id == seed.id } + seed.copy(quantity = (prior?.quantity ?: 0) + 1)
                encounter = encounter.copy(giftItemId = seed.id, giftQuantity = 1)
                events += EventType.RECEIVED_GIFT to context.getString(R.string.tama_park_gift_event,
                    definition.name.resolve(context.resources.configuration.locales[0]), inventoryItemDisplayName(context, seed))
            }
        }
        return Meeting(pet.copy(inventory = inventory, currentParkEncounter = encounter, currentAmbientNpc = null), events)
    }

    private fun index(seed: Long, key: String, size: Int): Int =
        ((seed xor key.hashCode().toLong()).ushr(1) % size.coerceAtLeast(1)).toInt()
}
