package com.example.llamadroid.tama.world.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.example.llamadroid.tama.world.core.WorldState
import com.example.llamadroid.tama.world.presentation.toCoreCommand

class WorldUiContractsTest {
    @Test
    fun assetCatalogKeepsTheFullNpcAndPetStageContract() {
        val required = WorldAssetCatalog.requiredAssetIds
        assertEquals(20, required.count { it.startsWith("npc_") })
        assertEquals(18, required.count { it.startsWith("pet_") })
        assertEquals(3, required.count { it.endsWith("_egg") })
        assertEquals(3, required.count { it.endsWith("_senior") })
        assertTrue(required.containsAll(listOf("fx_hatch", "icon_journal", "map_adventure_gate")))
    }

    @Test
    fun allEightBiomesHaveStableIds() {
        assertEquals(8, WorldBiome.entries.size)
        assertEquals(8, WorldBiome.entries.map(WorldBiome::id).toSet().size)
        assertTrue(WorldBiome.entries.all { it.id.isNotBlank() })
    }

    @Test
    fun trainingEventsCannotBeEligibleByDefault() {
        val trainingEvent = WorldEventUi(
            id = "training-1",
            timestamp = "00:01",
            title = "Episode",
            detail = "Disposable environment",
            importance = WorldEventImportance.ROUTINE,
            source = WorldEventSource.TRAINING_WORLD,
            locationLabel = "Training",
            biome = WorldBiome.MEADOW
        )
        assertTrue(!trainingEvent.memoryEligible)
    }

    @Test
    fun memoryReviewDefaultsToAutomaticMajorAndOnlyClosedRowsCanSave() {
        assertEquals(WorldMemoryPolicy.AUTO_MAJOR, WorldJournalUiState(emptyList()).memoryPolicy)
        assertTrue(WorldMemoryCandidateUi("closed", "", "", "", WorldEventImportance.MAJOR, "CLOSED").canSave)
        assertTrue(WorldMemoryCandidateUi("pending", "", "", "", WorldEventImportance.MAJOR, "PENDING").canSave)
        assertTrue(!WorldMemoryCandidateUi("approved", "", "", "", WorldEventImportance.MAJOR, "APPROVED").canSave)
    }

    @Test
    fun inspectorSelectionDoesNotAdvanceTheWorld() {
        val state = WorldState(seed = 1L)
        assertNull(
            WorldUiCommand.Inspect(WorldInspectTarget.Tile(0, 0)).toCoreCommand(state)
        )
        assertNull(
            WorldUiCommand.Inspect(WorldInspectTarget.Actor(state.actor.actorId)).toCoreCommand(state)
        )
    }
}
