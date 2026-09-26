package com.example.llamadroid.tama.world.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorldIndoorRecoveryTest {
    @Test
    fun recoveredManualInteriorActionCompletesOnceWithAutonomyOff() {
        val world = WorldGenerator.generate(191L)
        val farm = world.structures.first { it.type == StructureType.FARM }
        val arguments = mapOf("canonicalAction" to "farmMaintenance", "operation" to "buy_drone", "type" to "planting_drone")
        var state = world.copy(actor = world.actor.at(farm.entrance).copy(
            presence = PresenceMode.INTERIOR,
            structureId = farm.id,
            actionState = ActionState.IDLE,
            pendingCommand = PendingWorldCommand(ActionId.USE, farm.id, farm.entrance.x, farm.entrance.y, arguments)
        ), lastSimulatedAt = 0L)
        val pet = CanonicalPetSnapshot(petId = state.petId, money = 10_000,
            autonomy = AutonomyPolicy(level = AutonomyLevel.OFF))
        val committed = mutableListOf<WorldEffectRequest.CanonicalAction>()
        repeat(20) {
            val result = WorldSimulation.step(state, canonicalPetSnapshot = pet,
                now = state.lastSimulatedAt + DEFAULT_TICK_MILLIS,
                options = WorldSimulationOptions(simulateNpcs = false, decayPetNeeds = false))
            assertTrue(result.acceptedCommand)
            committed += result.effects.filterIsInstance<WorldEffectRequest.CanonicalAction>()
            state = result.state
        }
        assertEquals(listOf(WorldEffectRequest.CanonicalAction("farmMaintenance", arguments - "canonicalAction")), committed)
        assertNull(state.actor.pendingCommand)
        assertEquals(PresenceMode.INTERIOR, state.actor.presence)
        assertEquals(farm.entrance, state.actor.coordinate)
    }
}
