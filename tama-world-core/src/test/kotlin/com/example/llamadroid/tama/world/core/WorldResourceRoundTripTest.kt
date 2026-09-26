package com.example.llamadroid.tama.world.core

import org.junit.Assert.*
import org.junit.Test

class WorldResourceRoundTripTest {
    private val pet = CanonicalPetSnapshot(autonomy = AutonomyPolicy(level = AutonomyLevel.OFF))
    private val options = WorldSimulationOptions(simulateNpcs = false)

    @Test fun pickedUpItemsKeepTheirIdentityAndCannotBeCollectedTwiceAfterReload() {
        val generated = WorldGenerator.generate(119L)
        val coordinate = generated.actor.coordinate
        val item = WorldObject("dropped_apple", WorldObjectType.DROPPED_ITEM, coordinate.x, coordinate.y,
            quantity = 3, itemId = "apple")
        val world = generated.copy(objects = listOf(item), npcs = emptyList(),
            actor = generated.actor.copy(presence = PresenceMode.WORLD, structureId = null))
        val picked = WorldSimulation.step(world, WorldCommand.PerformAction(ActionId.PICK_UP, item.id), pet, options = options)
        assertTrue(picked.acceptedCommand)
        assertEquals(listOf("apple" to 3), picked.effects.filterIsInstance<WorldEffectRequest.InventoryDelta>().map { it.itemId to it.quantity })
        val restored = WorldStateCodec.roundTrip(picked.state)
        val second = WorldSimulation.step(restored, WorldCommand.PerformAction(ActionId.PICK_UP, item.id), pet, options = options)
        assertFalse(second.acceptedCommand)
        assertTrue(second.effects.none { it is WorldEffectRequest.InventoryDelta })
    }

    @Test fun customPatchRegrowsItsSavedYieldAndDoesNotConsumeAnUnrelatedDroppedItem() {
        val generated = WorldGenerator.generate(120L)
        val coordinate = generated.actor.coordinate
        val patch = WorldObject("custom_berries", WorldObjectType.BERRY_PATCH, coordinate.x, coordinate.y,
            state = "empty", quantity = 0, regrowthQuantity = 4)
        val delta = WorldDelta(coordinate.x, coordinate.y, WorldDeltaKind.RESOURCE, "consumed", 20, patch.id)
        val empty = generated.copy(tick = 20, objects = listOf(patch), deltas = listOf(delta))
        assertEquals(0, WorldGenerator.objectAt(empty, coordinate.x, coordinate.y)!!.quantity)
        val restored = WorldStateCodec.roundTrip(empty).copy(tick = 20 + 864_000L)
        assertEquals(4, WorldGenerator.objectAt(restored, coordinate.x, coordinate.y)!!.quantity)
        val dropped = WorldObject("dropped_water", WorldObjectType.DROPPED_ITEM, coordinate.x, coordinate.y, itemId = "water")
        assertEquals(1, WorldGenerator.objectAt(empty.copy(objects = listOf(patch, dropped)), coordinate.x, coordinate.y)!!.quantity)
    }

    @Test fun standingTreesCannotUseTheToolFreeFallenWoodAction() {
        assertEquals("wrong_object_type", WorldActionSemantics.objectFailure(ActionId.GATHER_WOOD,
            ActionTarget(ActionTargetKind.OBJECT, objectType = WorldObjectType.TREE)))
        assertNull(WorldActionSemantics.objectFailure(ActionId.GATHER_WOOD,
            ActionTarget(ActionTargetKind.OBJECT, objectType = WorldObjectType.FALLEN_LOG)))
    }
}
