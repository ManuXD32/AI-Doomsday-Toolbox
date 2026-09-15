package com.example.llamadroid.tama.world.presentation

import com.example.llamadroid.tama.world.core.ActionId
import com.example.llamadroid.tama.world.core.PresenceMode
import com.example.llamadroid.tama.world.core.StructureType
import com.example.llamadroid.tama.world.core.WorldGenerator
import com.example.llamadroid.ui.navigation.Screen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorldArrivalHandoffTest {
    @Test
    fun bothDungeonEntrancesKeepTheirDistinctStructureIdentity() {
        val generated = WorldGenerator.generate(83L)
        val dungeonA = generated.structures.first { it.type == StructureType.DUNGEON_A }
        val dungeonB = generated.structures.first { it.type == StructureType.DUNGEON_B }

        val arrivalA = generated.copy(
            actor = generated.actor.copy(
                presence = PresenceMode.INTERIOR,
                structureId = dungeonA.id,
                action = ActionId.ENTER_STRUCTURE
            )
        ).activityArrivalOrNull()
        val arrivalB = generated.copy(
            actor = generated.actor.copy(
                presence = PresenceMode.INTERIOR,
                structureId = dungeonB.id,
                action = ActionId.ENTER_DUNGEON
            )
        ).activityArrivalOrNull()

        assertEquals(WorldArrivalDestination.DUNGEON, arrivalA?.destination)
        assertEquals(WorldArrivalDestination.DUNGEON, arrivalB?.destination)
        assertEquals(dungeonA.id, arrivalA?.destinationId)
        assertEquals(dungeonB.id, arrivalB?.destinationId)
        assertNotEquals(arrivalA?.destinationId, arrivalB?.destinationId)
        assertEquals(
            "${Screen.Dungeon.route}?worldStructureId=${dungeonA.id}",
            Screen.Dungeon.createRoute(dungeonA.id)
        )
        assertEquals(
            "${Screen.Dungeon.route}?worldStructureId=${dungeonB.id}",
            Screen.Dungeon.createRoute(dungeonB.id)
        )
    }

    @Test
    fun adventureGateMapsToItsExistingActivitySurface() {
        val generated = WorldGenerator.generate(89L)
        val gate = generated.structures.first { it.type == StructureType.ADVENTURE_GATE }
        val arrival = generated.copy(
            actor = generated.actor.copy(
                presence = PresenceMode.INTERIOR,
                structureId = gate.id,
                action = ActionId.ENTER_ADVENTURE_GATE
            )
        ).activityArrivalOrNull()

        assertEquals(WorldArrivalDestination.ADVENTURE_GATE, arrival?.destination)
        assertEquals(gate.id, arrival?.destinationId)
        assertEquals(StructureType.ADVENTURE_GATE, arrival?.structureType)
    }

    @Test
    fun handoffRequiresPhysicalInteriorArrivalAction() {
        val generated = WorldGenerator.generate(97L)
        val dungeon = generated.structures.first { it.type == StructureType.DUNGEON_A }
        val outside = generated.copy(
            actor = generated.actor.copy(
                presence = PresenceMode.WORLD,
                structureId = null,
                action = ActionId.ENTER_DUNGEON
            )
        )
        val idleInside = generated.copy(
            actor = generated.actor.copy(
                presence = PresenceMode.INTERIOR,
                structureId = dungeon.id,
                action = ActionId.WAIT
            )
        )

        assertNull(outside.activityArrivalOrNull())
        assertNull(idleInside.activityArrivalOrNull())
        assertTrue(generated.structures.any { it.type == StructureType.DUNGEON_A })
    }
}
