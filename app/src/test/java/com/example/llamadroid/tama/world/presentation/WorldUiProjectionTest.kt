package com.example.llamadroid.tama.world.presentation

import org.junit.Assert.assertEquals
import org.junit.Test

class WorldUiProjectionTest {
    @Test
    fun arcadeHostUsesTheAuthoredPixelPopWorldSheet() {
        assertEquals("npc_arcade_machine", npcAssetId("arcade_host"))
    }

    @Test
    fun ordinaryNpcIdsKeepTheCanonicalPrefixMapping() {
        assertEquals("npc_farm_farmer", npcAssetId("farm_farmer"))
        assertEquals("npc_seller", npcAssetId("seller"))
    }
}
