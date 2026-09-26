package com.example.llamadroid.tama.world.presentation

import com.example.llamadroid.tama.world.core.ActionId
import com.example.llamadroid.tama.world.core.PresenceMode
import com.example.llamadroid.tama.world.core.StructureType
import com.example.llamadroid.tama.world.core.WorldState

/** Existing app surface that should be shown after a physical world arrival. */
enum class WorldArrivalDestination {
    DUNGEON,
    ADVENTURE_GATE
}

/**
 * Durable structure identity carried across the world-to-activity handoff.
 * The two dungeon entrances deliberately remain separate even though they
 * currently share the existing Dungeon selection screen.
 */
data class WorldActivityArrival(
    val destinationId: String,
    val structureType: StructureType,
    val destination: WorldArrivalDestination
)

private val ARRIVAL_ACTIONS = setOf(
    ActionId.ENTER_STRUCTURE,
    ActionId.ENTER_DUNGEON,
    ActionId.ENTER_ADVENTURE_GATE
)

/**
 * Returns an activity handoff only for an actor that is already inside the
 * corresponding generated structure. This function never uses a hidden tile
 * or a legacy location label; the persisted presence and structure identity
 * are the arrival authority.
 */
fun WorldState.activityArrivalOrNull(): WorldActivityArrival? {
    val actor = actor
    if (actor.presence != PresenceMode.INTERIOR || actor.action !in ARRIVAL_ACTIONS) return null
    val structure = actor.structureId?.let { id -> structures.firstOrNull { it.id == id } } ?: return null
    val destination = when (structure.type) {
        StructureType.DUNGEON_A, StructureType.DUNGEON_B -> WorldArrivalDestination.DUNGEON
        StructureType.ADVENTURE_GATE -> WorldArrivalDestination.ADVENTURE_GATE
        else -> return null
    }
    return WorldActivityArrival(
        destinationId = structure.id,
        structureType = structure.type,
        destination = destination
    )
}
