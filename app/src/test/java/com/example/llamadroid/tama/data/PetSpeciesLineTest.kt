package com.example.llamadroid.tama.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PetSpeciesLineTest {
    @Test
    fun `new pet species stays unchanged`() {
        assertEquals("dragon", normalizePetSpecies("dragon", legacyBodyStyle = 2))
        assertEquals("unicorn", normalizePetSpecies("unicorn", legacyBodyStyle = 0))
        assertEquals("kitsune", normalizePetSpecies("kitsune", legacyBodyStyle = 1))
    }

    @Test
    fun `legacy creature species maps deterministically from body style`() {
        assertEquals("dragon", normalizePetSpecies("creature", legacyBodyStyle = 0))
        assertEquals("unicorn", normalizePetSpecies("creature", legacyBodyStyle = 1))
        assertEquals("kitsune", normalizePetSpecies("creature", legacyBodyStyle = 2))
        assertEquals("dragon", normalizePetSpecies("creature", legacyBodyStyle = 3))
    }

    @Test
    fun `asset path matches species stage state and frame`() {
        assertEquals(
            "tama/animations/frames/unicorn/teen/walk_1.png",
            resolvePetSpriteAssetPath(
                speciesLine = PetSpeciesLine.UNICORN,
                stage = GrowthStage.TEEN,
                state = PetSpriteState.WALK,
                frameIndex = 1
            )
        )
        assertEquals(
            "tama/animations/frames/kitsune/adult/sleep_1.png",
            resolvePetSpriteAssetPath(
                speciesLine = PetSpeciesLine.KITSUNE,
                stage = GrowthStage.ADULT,
                state = PetSpriteState.SLEEP,
                frameIndex = 9
            )
        )
    }

    @Test
    fun `all current Tama actions resolve to a valid sprite state`() {
        TamaSpriteSupportedActions.forEach { action ->
            val resolved = mapPetActionToSpriteState(action, isSleeping = false)
            assertTrue(
                "Unexpected sprite state for action $action",
                resolved in PetSpriteState.entries
            )
        }
        assertEquals(PetSpriteState.SLEEP, mapPetActionToSpriteState("playing", isSleeping = true))
    }

    @Test fun `activities have authored clips and eggs always select stationary idle`() {
        mapOf("playing" to PetSpriteState.PLAY, "studying" to PetSpriteState.STUDY,
            "working" to PetSpriteState.WORK, "training" to PetSpriteState.TRAIN,
            "cleaning" to PetSpriteState.CLEAN).forEach { (action, expected) ->
            assertEquals(expected, mapPetActionToSpriteState(action, false))
        }
        PetSpriteState.entries.forEach { state ->
            assertEquals("tama/animations/frames/dragon/egg/idle_1.png",
                resolvePetSpriteAssetPath(PetSpeciesLine.DRAGON, GrowthStage.EGG, state, 3))
        }
    }
}
