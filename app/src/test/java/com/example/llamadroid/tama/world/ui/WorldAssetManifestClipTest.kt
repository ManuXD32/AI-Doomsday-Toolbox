package com.example.llamadroid.tama.world.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorldAssetManifestClipTest {
    private val authoredActorClips = setOf(
        "IDLE", "WALK", "RUN", "SIT", "SLEEP", "TALK", "PLAY", "INTERACT"
    )

    @Test
    fun canonicalActivityActionsUseAuthoredActorClips() {
        val activities = listOf(
            "work" to "INTERACT",
            "study" to "INTERACT",
            "training" to "INTERACT",
            "train_boxing" to "INTERACT",
            "visit_hospital" to "INTERACT",
            "visit_alchemist" to "INTERACT",
            "use_alchemy" to "INTERACT",
            "use_arcade" to "PLAY",
            "wake" to "IDLE",
            "run" to "RUN"
        )

        activities.forEach { (action, expectedClip) ->
            assertEquals(expectedClip, worldManifestAction(action))
            assertTrue(worldManifestAction(action) in authoredActorClips)
        }
    }
}
