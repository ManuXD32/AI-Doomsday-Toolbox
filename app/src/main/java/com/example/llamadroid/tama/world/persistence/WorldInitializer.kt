package com.example.llamadroid.tama.world.persistence

import androidx.room.withTransaction
import com.example.llamadroid.tama.data.TamaPet
import com.example.llamadroid.tama.db.TamaDatabase
import com.example.llamadroid.tama.db.TamaLocationEntity
import com.example.llamadroid.tama.game.PetMapper
import com.example.llamadroid.tama.world.core.*
import java.nio.ByteBuffer
import java.security.MessageDigest

/** Procedural work runs after Room's schema migration, atomically and once per pet identity. */
class WorldInitializer(
    private val database: TamaDatabase,
    private val layouts: Map<StructureType, StructureLayout> = emptyMap()
) {
    private val store = WorldStateStore(database)

    suspend fun ensure(pet: TamaPet, now: Long): WorldState = database.withTransaction {
        store.load(pet.id)?.let { return@withTransaction it }
        val legacy = database.tamaDao().getAllLocations()
        val seed = ByteBuffer.wrap(MessageDigest.getInstance("SHA-256")
            .digest("${pet.id}:${pet.birthTimestamp}:world-v1".toByteArray())).long
        val generated = WorldGenerator.generate(seed, structureLayouts = layouts)
        val locationId = normalizeLocation(pet.currentLocationId, legacy) ?: LegacyLocationAliases.HOME
        val structure = generated.structures.first { it.id == locationId }
        val discoveredIds = (pet.discoveredLocationIds.mapNotNull { normalizeLocation(it, legacy) } +
            legacy.filter { it.isDiscovered }.mapNotNull { normalizeLocation(it.id, legacy) } +
            locationId + LegacyLocationAliases.HOME).toSet()
        val explored = generated.explored.toMutableSet()
        generated.structures.filter { it.id in discoveredIds }.forEach { known ->
            for (y in known.entrance.y - 6..known.entrance.y + 6) {
                for (x in known.entrance.x - 6..known.entrance.x + 6) {
                    if (x in 0 until generated.width && y in 0 until generated.height) explored += WorldCoordinate(x, y)
                }
            }
        }
        val state = generated.copy(
            worldId = "world:${pet.id}", petId = pet.id, lastSimulatedAt = now,
            timezoneOffsetMinutes = java.util.TimeZone.getDefault().getOffset(now) / 60_000,
            npcs = generated.npcs.map { it.copy(lastSimulatedAt = now) },
            actor = generated.actor.copy(
                actorId = pet.id, x = structure.entrance.x, y = structure.entrance.y,
                preciseX = structure.entrance.x.toDouble(), preciseY = structure.entrance.y.toDouble(),
                presence = if (structure.type == StructureType.HOME) PresenceMode.HOME else PresenceMode.INTERIOR,
                structureId = structure.id
            ),
            explored = explored.sortedWith(compareBy({ it.y }, { it.x })),
            knownPlaces = generated.structures.filter { it.id in discoveredIds }.map {
                KnownPlace(it.id, it.type.name, it.entrance.x, it.entrance.y, now)
            }
        )
        store.save(state)
        database.worldDao().saveRelationships(pet.relationships.map { (npcId, friendship) ->
            TamaWorldRelationshipEntity(pet.id, npcId, friendship.coerceIn(0, 100).toFloat(),
                friendship.coerceIn(0, 100).toFloat(), 0f, 0L, 0)
        })
        database.tamaDao().savePet(PetMapper.toEntity(pet.copy(
            currentLocationId = locationId, discoveredLocationIds = pet.discoveredLocationIds + discoveredIds
        )))
        state
    }

    companion object {
        fun normalizeLocation(raw: String?, legacy: List<TamaLocationEntity> = emptyList()): String? {
            LegacyLocationAliases.normalize(raw)?.let { return it }
            val row = legacy.firstOrNull { it.id == raw }
            if (row != null) {
                LegacyLocationAliases.normalize("fixed_${row.x}_${row.y}")?.let { return it }
                LegacyLocationAliases.normalize(row.type)?.let { return it }
            }
            // Bare legacy dungeon used the first fixed dungeon. Explicit second IDs never collapse.
            return if (raw.equals("dungeon", ignoreCase = true)) LegacyLocationAliases.DUNGEON_A else null
        }
    }
}
