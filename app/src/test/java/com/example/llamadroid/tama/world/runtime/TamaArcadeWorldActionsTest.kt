package com.example.llamadroid.tama.world.runtime

import com.example.llamadroid.tama.world.core.ActionId
import com.example.llamadroid.tama.world.core.ActionState
import com.example.llamadroid.tama.world.core.LegacyLocationAliases
import com.example.llamadroid.tama.world.core.PresenceMode
import com.example.llamadroid.tama.world.core.WorldActor
import com.example.llamadroid.tama.world.core.WorldState
import com.example.llamadroid.tama.world.presentation.ArcadeSessionRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TamaArcadeWorldActionsTest {
    @Test
    fun beginIdentityIsAcceptedButIncompleteTerminalMetricsAreRejected() {
        val begin = ArcadeSessionRequest("pet-1", "session-1", "catch", score = 0)
        assertNull(TamaArcadeWorldActions.validate(begin))
        val invalid = begin.copy(score = 5, totalObjects = 10, catches = 5, misses = 4)
        assertEquals("arcade_metrics_invalid", TamaArcadeWorldActions.validate(invalid))
    }

    @Test
    fun rewardMatchesCanonicalCatchAndMemoryRules() {
        val catch = ArcadeSessionRequest("pet-1", "catch-1", "catch", 18, 18, 0, 18)
        assertEquals(50L, TamaArcadeWorldActions.rewardFor(catch).coins)
        assertEquals(12f, TamaArcadeWorldActions.rewardFor(catch).happiness, 0.001f)

        val memory = ArcadeSessionRequest("pet-1", "memory-1", "memory", 80,
            totalObjects = 8, pairsMatched = 8, turnsUsed = 11)
        assertEquals(50L, TamaArcadeWorldActions.rewardFor(memory).coins)
        assertEquals(12f, TamaArcadeWorldActions.rewardFor(memory).happiness, 0.001f)
    }

    @Test
    fun leaseUsesUseArcadeActionAndRestoresPreviousAction() {
        val state = WorldState(
            seed = 7L,
            actor = WorldActor(
                actorId = "pet-1",
                presence = PresenceMode.INTERIOR,
                structureId = LegacyLocationAliases.ARCADE,
                action = ActionId.SIT,
                actionState = ActionState.IDLE
            ),
            worldId = "world-1",
            petId = "pet-1"
        )
        val leased = TamaArcadeWorldActions.withLease(state, "session-1")
        assertEquals(ActionId.USE_ARCADE, leased.actor.action)
        assertTrue(TamaArcadeWorldActions.leaseSessionId(leased) == "session-1")
        assertTrue(leased.actor.pendingActivity?.blocksPetSimulation == true)
        assertEquals(leased.actor.pendingActivity,
            TamaArcadeWorldActions.withLease(leased, "session-1").actor.pendingActivity)
        val released = TamaArcadeWorldActions.clearLease(leased, "session-1")
        assertEquals(ActionId.SIT, released.actor.action)
        assertEquals(ActionState.IDLE, released.actor.actionState)
        assertNull(TamaArcadeWorldActions.leaseSessionId(released))
    }
}
