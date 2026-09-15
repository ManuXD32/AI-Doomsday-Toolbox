package com.example.llamadroid.tama.world.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorldFrontierNavigationTest {
    private fun separatedDiscoveries(): WorldState = WorldState(seed = 4L, width = 32, height = 32,
        actor = WorldActor(x = 9, y = 10, preciseX = 9.0, preciseY = 10.0, presence = PresenceMode.WORLD),
        roads = (9..24).map { WorldCoordinate(it, 10) },
        explored = (9..11).map { WorldCoordinate(it, 10) } + (22..24).map { WorldCoordinate(it, 10) })

    @Test fun knownFacilityAcrossUnseenLandUsesAReachableFrontier() {
        val state = separatedDiscoveries()
        val plan = Pathfinder.planForKnownWorld(state, WorldCoordinate(24, 10))
        assertTrue(plan.stoppedAtKnownFrontier)
        assertEquals(WorldCoordinate(11, 10), plan.path.last())
        assertTrue(plan.path.all { it in state.explored })
    }

    @Test fun manualTravelContinuesAcrossFrontiersWithAutonomyOff() {
        val pet = CanonicalPetSnapshot(autonomy = AutonomyPolicy(level = AutonomyLevel.OFF))
        val started = WorldSimulation.step(separatedDiscoveries(), WorldCommand.GoTo(24, 10), pet,
            options = WorldSimulationOptions(simulateNpcs = false))
        assertTrue(started.acceptedCommand)
        var state = started.state
        repeat(100) {
            state = WorldSimulation.step(state, canonicalPetSnapshot = pet,
                options = WorldSimulationOptions(simulateNpcs = false)).state
        }
        assertEquals(WorldCoordinate(24, 10), state.actor.coordinate)
    }
}
