package com.example.llamadroid.tama.ui

import com.example.llamadroid.tama.data.FarmMaterialCost
import com.example.llamadroid.tama.data.FarmObstacle
import com.example.llamadroid.tama.data.FarmTile
import com.example.llamadroid.tama.data.InventoryItem
import com.example.llamadroid.tama.data.ItemType
import com.example.llamadroid.tama.data.TileStatus
import com.example.llamadroid.tama.game.FarmPlotAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FarmObstacleUiTest {
    private fun tile(id: Int = 0, obstacle: FarmObstacle? = null) = FarmTile(id = id, obstacle = obstacle)
    private fun item(id: String, qty: Int) = InventoryItem(id, id, ItemType.MATERIAL, quantity = qty)

    @Test
    fun `axe clears stumps and weeds but not rocks`() {
        assertEquals(FarmPlotAction.CLEAR_OBSTACLE, farmTileActionForTool(tile(obstacle = FarmObstacle.STUMP), "axe"))
        assertEquals(FarmPlotAction.CLEAR_OBSTACLE, farmTileActionForTool(tile(obstacle = FarmObstacle.STUMP), "axe_starter"))
        assertEquals(FarmPlotAction.CLEAR_OBSTACLE, farmTileActionForTool(tile(obstacle = FarmObstacle.WEEDS), "axe"))
        assertNull(farmTileActionForTool(tile(obstacle = FarmObstacle.ROCK), "axe"))
        assertNull(farmTileActionForTool(tile(), "axe"))
    }

    @Test
    fun `pickaxe clears rocks only`() {
        assertEquals(FarmPlotAction.CLEAR_OBSTACLE, farmTileActionForTool(tile(obstacle = FarmObstacle.ROCK), "pickaxe"))
        assertNull(farmTileActionForTool(tile(obstacle = FarmObstacle.STUMP), "pickaxe"))
    }

    @Test
    fun `hoe still tills idle soil including weeds`() {
        assertEquals(FarmPlotAction.TILL_SOIL, farmTileActionForTool(tile(obstacle = FarmObstacle.WEEDS), "hoe"))
        assertEquals(FarmPlotAction.TILL_SOIL, farmTileActionForTool(tile(), "hoe_starter"))
        assertEquals(FarmPlotAction.WATER, farmTileActionForTool(FarmTile(0, TileStatus.FARMLAND), "watering_can"))
    }

    @Test
    fun `material gains report only grown wood and stone`() {
        val before = listOf(item("wood", 2), item("stone", 5))
        val after = listOf(item("wood", 5), item("stone", 5), item("axe", 1))
        assertEquals(listOf("wood" to 3), farmMaterialGains(before, after))
    }

    @Test
    fun `clearable count only covers the page`() {
        val tiles = (0 until 18).map { id -> tile(id, if (id == 2 || id == 4 || id == 10) FarmObstacle.ROCK else null) }
        assertEquals(2, farmClearableObstacleCount(tiles, 0))
        assertEquals(1, farmClearableObstacleCount(tiles, 1))
    }

    @Test
    fun `stock coverage and unknown stock`() {
        val stock = FarmMaterialStock.of(listOf(item("wood", 3), item("wood", 2), item("stone", 1)))
        assertEquals(FarmMaterialStock(5, 1), stock)
        assertTrue(FarmMaterialCost(5, 1).isCoveredBy(stock))
        assertFalse(FarmMaterialCost(5, 2).isCoveredBy(stock))
        assertTrue(FarmMaterialCost(99, 99).isCoveredBy(null))
    }
}
