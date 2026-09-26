package com.example.llamadroid.tama.world.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorldExternalActivityLeaseTest {
    @Test
    fun leaseHoldsPetActionWhilePassiveNeedsAndNpcsAdvance() {
        val generated = WorldGenerator.generate(0xA7C0L)
        val arcade = generated.structures.first { it.type == StructureType.ARCADE }
        val petNeeds = NeedsProjection(hunger = 10f, hydration = 10f, energy = 10f)
        val leasedActor = generated.actor.copy(
            x = arcade.entrance.x,
            y = arcade.entrance.y,
            preciseX = arcade.entrance.x.toDouble(),
            preciseY = arcade.entrance.y.toDouble(),
            presence = PresenceMode.INTERIOR,
            structureId = arcade.id,
            action = ActionId.USE_ARCADE,
            actionState = ActionState.RUNNING,
            actionTicksRemaining = 8,
            needs = petNeeds,
            pendingActivity = PendingActivityIntent(
                action = "ARCADE_SESSION",
                destinationId = arcade.id,
                arguments = mapOf("arcadeSessionId" to "session-1"),
                blocksPetSimulation = true
            )
        )
        val initial = generated.copy(
            actor = leasedActor,
            npcs = generated.npcs.map { it.copy(lastSimulatedAt = 0L) },
            lastSimulatedAt = 0L
        )
        val pet = CanonicalPetSnapshot(petId = "pet", needs = petNeeds)

        val result = WorldSimulation.step(
            initial,
            canonicalPetSnapshot = pet,
            now = DEFAULT_TICK_MILLIS,
            options = WorldSimulationOptions(
                decayPetNeeds = true,
                npcSimulationRadius = initial.width
            )
        )

        assertEquals(leasedActor.copy(needs = result.state.actor.needs), result.state.actor)
        assertTrue(result.effects.any { it is WorldEffectRequest.NeedDelta })
        assertTrue(result.effects.none {
            it is WorldEffectRequest.Activity ||
                it is WorldEffectRequest.CanonicalAction ||
                it is WorldEffectRequest.NeedDelta && it.need == NeedType.HAPPINESS && it.delta > 0f
        })
        assertTrue(result.state.npcs.any { it.lastSimulatedAt > 0L })
    }

    @Test
    fun longCatchUpCannotCompleteOrMoveLeasedPet() {
        val generated = WorldGenerator.generate(0xA7C1L)
        val arcade = generated.structures.first { it.type == StructureType.ARCADE }
        val actor = generated.actor.copy(
            x = arcade.entrance.x,
            y = arcade.entrance.y,
            preciseX = arcade.entrance.x.toDouble(),
            preciseY = arcade.entrance.y.toDouble(),
            presence = PresenceMode.INTERIOR,
            structureId = arcade.id,
            action = ActionId.USE_ARCADE,
            actionState = ActionState.RUNNING,
            actionTicksRemaining = 1,
            pendingActivity = PendingActivityIntent(
                action = "ARCADE_SESSION",
                destinationId = arcade.id,
                arguments = mapOf("arcadeSessionId" to "session-2"),
                blocksPetSimulation = true
            )
        )
        val initial = generated.copy(actor = actor, lastSimulatedAt = 0L)
        val result = WorldSimulation.catchUp(
            initial,
            canonicalPetSnapshot = CanonicalPetSnapshot(petId = "pet"),
            now = DEFAULT_TICK_MILLIS * 10L,
            options = WorldSimulationOptions(maxCatchUpTicks = 1L, simulateNpcs = false)
        )

        assertEquals(actor, result.state.actor)
        assertTrue(result.state.lastSimulatedAt >= DEFAULT_TICK_MILLIS * 10L)
        assertTrue(result.effects.none {
            it is WorldEffectRequest.Activity || it is WorldEffectRequest.CanonicalAction
        })
    }
}
