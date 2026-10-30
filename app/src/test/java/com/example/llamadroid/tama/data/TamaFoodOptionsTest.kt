package com.example.llamadroid.tama.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TamaFoodOptionsTest {
    private fun item(id: String, type: ItemType, qty: Int) = InventoryItem(id, id, type, qty)

    @Test
    fun freeFoodsComeFirstAndAreUnlimited() {
        val options = TamaFoodOptions.foodOptions(emptyList())
        assertEquals(listOf("lettuce", "candy"), options.map { it.id })
        assertTrue(options.all { it.quantity == null && it.kind == TamaFoodKind.FREE })
    }

    @Test
    fun duplicateStacksMergeAndIdsAreCaseInsensitive() {
        val options = TamaFoodOptions.foodOptions(listOf(
            item("apple", ItemType.FOOD, 2), item("Apple", ItemType.FOOD, 3), item("crop_carrot", ItemType.CROP, 1)
        ))
        assertEquals(5, options.first { it.id == "apple" }.quantity)
        assertEquals(1, options.first { it.id == "crop_carrot" }.quantity)
        assertEquals(TamaFoodKind.CROP, options.first { it.id == "crop_carrot" }.kind)
    }

    @Test
    fun worldFoodAliasesMergeAndFlowersAreNotEdible() {
        val options = TamaFoodOptions.foodOptions(listOf(
            item("berry", ItemType.FOOD, 1), item("berries", ItemType.FOOD, 2), item("mushroom", ItemType.FOOD, 1),
            item("crop_rose", ItemType.CROP, 4), item("crop_daisy", ItemType.CROP, 4), item("wood", ItemType.MATERIAL, 9)
        ))
        assertEquals(3, options.first { it.id == "berry" }.quantity)
        assertNotNull(options.firstOrNull { it.id == "mushroom" })
        assertNull(options.firstOrNull { it.id == "crop_rose" || it.id == "crop_daisy" || it.id == "wood" })
    }

    @Test
    fun everyEdibleFarmCropHasFoodValues() {
        CropDefinitions.CROPS.keys.forEach { cropId ->
            val def = TamaFoodOptions.definition("crop_$cropId")
            if (cropId !in TamaCropEdibility.EDIBLE) assertNull(cropId, def)
            else {
                assertNotNull(cropId, def)
                assertTrue(def!!.hungerGain in 5..30 && def.happinessGain in 1..6)
            }
        }
        assertFalse(TamaFoodOptions.foodOptions(emptyList()).any { it.kind == TamaFoodKind.CROP })
    }

    @Test
    fun definitionNormalizesAliasCase() {
        assertEquals("berry", TamaFoodOptions.definition("Berries")?.id)
        assertEquals("apple", TamaFoodOptions.definition(" APPLE ")?.id)
    }

    @Test
    fun onlyFiveRawCropsAreEdibleAndFeedListExcludesTheRest() {
        assertEquals(setOf("carrot", "tomato", "strawberry", "melon", "corn"), TamaCropEdibility.EDIBLE)
        val inv = CropDefinitions.CROPS.keys.map { item("crop_$it", ItemType.CROP, 2) }
        val ids = TamaFoodOptions.foodOptions(inv).filter { it.kind == TamaFoodKind.CROP }.map { it.id }.toSet()
        assertEquals(TamaCropEdibility.EDIBLE.map { "crop_$it" }.toSet(), ids)
        listOf("wheat", "rice", "pumpkin", "daisy", "sunflower", "rose", "tullip").forEach {
            assertTrue(it, TamaCropEdibility.isInedibleCrop("crop_$it"))
            assertFalse(it, TamaCropEdibility.isEdible(it))
        }
        assertFalse(TamaCropEdibility.isInedibleCrop("apple"))
        assertFalse(TamaCropEdibility.isInedibleCrop("crop_carrot"))
    }
}
