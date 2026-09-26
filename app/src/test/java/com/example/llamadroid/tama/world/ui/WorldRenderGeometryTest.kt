package com.example.llamadroid.tama.world.ui

import org.junit.Assert.*
import org.junit.Test

class WorldRenderGeometryTest {
    @Test fun atlasContractHas47CanonicalMasksAndNeverSelectsTransparentSlot() {
        assertEquals(47, WorldBlobTiles.masks.size)
        assertEquals(0, WorldBlobTiles.index(0))
        assertEquals(46, WorldBlobTiles.index(255))
        repeat(256) { assertTrue(WorldBlobTiles.index(it) in 0..46) }
        assertEquals(0, WorldBlobTiles.normalize(2 or 8 or 32 or 128))
        assertEquals(7, WorldBlobTiles.normalize(1 or 2 or 4))
    }

    @Test fun fogDoesNotRevealNeighborMaterialAndWaterRetainsAnAnimatedBlockOffset() {
        val water = WorldTileUi(0, 0, "terrain_shallow_water", WorldBiome.COAST, true, water = true)
        val shore = WorldTileUi(1, 0, "terrain_sand", WorldBiome.COAST, false)
        val concealed = mapOf((0 to 0) to water, (1 to 0) to shore)
        assertEquals(255, WorldBlobTiles.mask(water, concealed) { it.water })
        assertTrue(WorldBlobTiles.overlays(water, concealed).isEmpty())
        val visible = concealed + ((1 to 0) to shore.copy(known = true))
        val boundary = WorldBlobTiles.overlays(water, visible).single()
        assertEquals("edge_water_shore", boundary.first)
        assertEquals(0, boundary.second and 4)
        assertTrue(96 + WorldBlobTiles.index(boundary.second) in 96..142)
    }

    @Test fun movementInterpolatesAndFreeCameraDoesNotChangeActorTravel() {
        val motion = WorldRenderMotion()
        val actor = WorldActorUi("pet", "Pet", "pet", "dragon", "pet_dragon_baby", WorldPointUi(1f, 1f))
        val camera = WorldCameraUi(1f, 1f)
        motion.update(listOf(actor), camera, 1_000L, 100L)
        val moved = actor.copy(position = WorldPointUi(2f, 1f), action = "walk")
        motion.update(listOf(moved), camera.copy(centerX = 2f), 1_100L, 100L)
        assertEquals(1f, motion.position(moved, 1_100L).x, 0f)
        assertEquals(1.5f, motion.position(moved, 1_150L).x, 0f)
        assertEquals(2f, motion.position(moved, 1_200L).x, 0f)
        motion.update(listOf(moved), camera.copy(centerX = 40f, mode = WorldCameraMode.FREE), 1_150L, 100L)
        assertEquals(40f, motion.camera(camera, 1_150L).centerX, 0f)
        assertEquals(1.5f, motion.position(moved, 1_150L).x, 0f)
        assertEquals(50L, motion.elapsed(moved.id, 1_150L))
    }
}
