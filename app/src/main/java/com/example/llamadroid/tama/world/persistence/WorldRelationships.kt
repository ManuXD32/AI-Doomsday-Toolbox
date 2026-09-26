package com.example.llamadroid.tama.world.persistence

import com.example.llamadroid.tama.data.TamaPet
import com.example.llamadroid.tama.db.TamaDatabase

/** Legacy activity screens write friendship through this compatibility projection. */
object WorldRelationships {
    suspend fun projectLegacyWrites(database: TamaDatabase, pet: TamaPet): TamaPet {
        val dao = database.worldDao()
        if (dao.worldForPet(pet.id) == null) return pet
        val records = dao.relationships(pet.id).associateBy { it.npcId }.toMutableMap()
        val changed = pet.relationships.mapNotNull { (npcId, legacyFriendship) ->
            val friendship = legacyFriendship.coerceIn(0, 100).toFloat()
            val old = records[npcId]
            if (old?.friendship == friendship) null else {
                val updated = old?.copy(friendship = friendship) ?: TamaWorldRelationshipEntity(
                    pet.id, npcId, friendship, friendship, 0f, 0L, 0
                )
                records[npcId] = updated
                updated
            }
        }
        if (changed.isNotEmpty()) dao.saveRelationships(changed)
        return pet.copy(relationships = records.mapValues { it.value.friendship.toInt() })
    }
}
