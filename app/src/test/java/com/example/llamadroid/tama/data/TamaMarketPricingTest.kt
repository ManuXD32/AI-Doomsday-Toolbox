package com.example.llamadroid.tama.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class TamaMarketPricingTest {
    @Test
    fun `crop prices start at max and reach the half price floor at sixty four weekly sales`() {
        assertEquals(118, TamaMarketPricing.maxPrice("crop_melon"))
        assertEquals(59, TamaMarketPricing.minPrice("crop_melon"))
        assertEquals(118, TamaMarketPricing.priceForWeeklySales("crop_melon", 0))
        assertEquals(59, TamaMarketPricing.priceForWeeklySales("crop_melon", 64))
        assertEquals(59, TamaMarketPricing.priceForWeeklySales("crop_melon", 200))
    }

    @Test
    fun `no item ever sells below half of its base price`() {
        FarmTradeItemCatalog.allDefinitions().forEach { definition ->
            val id = definition.inventoryId
            val base = TamaMarketPricing.maxPrice(id)
            assertTrue("$id floor", TamaMarketPricing.minPrice(id) * 2 >= base)
            (0..200 step 7).forEach { sold ->
                assertTrue("$id at $sold sales", TamaMarketPricing.priceForWeeklySales(id, sold) * 2 >= base)
            }
        }
    }

    @Test
    fun `produce prices use half fixed sell price as minimum`() {
        assertEquals(14, TamaMarketPricing.minPrice("produce_milk"))
        assertEquals(8, TamaMarketPricing.minPrice("produce_egg"))
        assertEquals(27, TamaMarketPricing.priceForWeeklySales("produce_milk", 0))
        assertEquals(14, TamaMarketPricing.priceForWeeklySales("produce_milk", 64))
        assertEquals(16, TamaMarketPricing.priceForWeeklySales("produce_egg", 0))
        assertEquals(8, TamaMarketPricing.priceForWeeklySales("produce_egg", 64))
    }

    @Test
    fun `weekly quote keys roll from the previous friday until the next friday`() {
        val thursday = Calendar.getInstance().apply {
            clear()
            set(2026, Calendar.JUNE, 18, 12, 0, 0)
        }
        val friday = Calendar.getInstance().apply {
            clear()
            set(2026, Calendar.JUNE, 19, 0, 0, 0)
        }

        assertEquals("2026-06-12", TamaMarketPricing.quoteWeekKey(thursday.timeInMillis))
        assertEquals("2026-06-19", TamaMarketPricing.quoteWeekKey(friday.timeInMillis))
    }
}
