package com.example.llamadroid.tama.ui

import com.example.llamadroid.R
import com.example.llamadroid.tama.data.CropDefinitions
import com.example.llamadroid.tama.data.FarmTile
import com.example.llamadroid.tama.data.InventoryItem
import com.example.llamadroid.tama.data.ItemType
import com.example.llamadroid.tama.data.PlantedCrop
import com.example.llamadroid.tama.data.TileStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FarmJuiceTest {
    private val totalMs = CropDefinitions.CROPS.getValue("wheat").stageTimes.take(3).sum()

    private fun tile(stage: Int = 0, decayed: Boolean = false, start: Long = 1_000L) = FarmTile(
        id = 0,
        status = TileStatus.WET_FARMLAND,
        crop = PlantedCrop("wheat", stage, plantedTime = start, lastStageUpdateTime = start, isDecayed = decayed),
        lastWateredTime = start
    )

    @Test
    fun `growth progress runs from zero to one on a legacy always-wet crop`() {
        val t = tile()
        assertEquals(0f, farmCropProgress(t, 1_000L)!!, 0.001f)
        assertEquals(0.5f, farmCropProgress(t, 1_000L + totalMs / 2)!!, 0.001f)
        assertEquals(1f, farmCropProgress(t, 1_000L + totalMs * 2)!!, 0.001f)
    }

    @Test
    fun `progress is null without a growing crop and one when ripe`() {
        assertNull(farmCropProgress(FarmTile(id = 1), 0L))
        assertNull(farmCropProgress(tile(decayed = true), 0L))
        assertEquals(1f, farmCropProgress(tile(stage = 3), 0L)!!, 0f)
    }

    @Test
    fun `inventory gains report only increased crop stacks`() {
        val before = listOf(
            InventoryItem("wheat", "Wheat", ItemType.CROP, 2),
            InventoryItem("seed_wheat", "Seed", ItemType.SEED, 5)
        )
        val after = listOf(
            InventoryItem("wheat", "Wheat", ItemType.CROP, 5),
            InventoryItem("carrot", "Carrot", ItemType.CROP, 1),
            InventoryItem("seed_wheat", "Seed", ItemType.SEED, 9)
        )
        val gains = farmInventoryGains(before, after).associate { it.first.id to it.second }
        assertEquals(mapOf("wheat" to 3, "carrot" to 1), gains)
    }

    @Test
    fun `limit codes map to the need strings`() {
        assertEquals(R.string.tama_farm_limit_water, farmLimitRes("water_required"))
        assertEquals(R.string.tama_farm_limit_seeds, farmLimitRes("seed_required"))
        assertEquals(R.string.tama_farm_limit_tool, farmLimitRes("tool_required"))
        assertNull(farmLimitRes("crop_not_ready"))
        assertNull(farmLimitRes(null))
    }

    @Test
    fun `twinkle alpha is a triangle wave`() {
        assertEquals(0f, twinkleAlpha(0f, 0f), 0.001f)
        assertEquals(1f, twinkleAlpha(0.5f, 0f), 0.001f)
        assertEquals(1f, twinkleAlpha(0.1f, 0.4f), 0.001f)
    }
}
