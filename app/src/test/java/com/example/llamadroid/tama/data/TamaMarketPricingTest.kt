package com.example.llamadroid.tama.data

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Calendar

class TamaMarketPricingTest {
    @Test
    fun `crop prices start at max and reach seed floor at sixty four weekly sales`() {
        assertEquals(150, TamaMarketPricing.maxPrice("crop_melon"))
        assertEquals(47, TamaMarketPricing.minPrice("crop_melon"))
        assertEquals(150, TamaMarketPricing.priceForWeeklySales("crop_melon", 0))
        assertEquals(47, TamaMarketPricing.priceForWeeklySales("crop_melon", 64))
        assertEquals(47, TamaMarketPricing.priceForWeeklySales("crop_melon", 200))
    }

    @Test
    fun `produce prices use half fixed sell price as minimum`() {
        assertEquals(25, TamaMarketPricing.minPrice("produce_milk"))
        assertEquals(10, TamaMarketPricing.minPrice("produce_egg"))
        assertEquals(50, TamaMarketPricing.priceForWeeklySales("produce_milk", 0))
        assertEquals(25, TamaMarketPricing.priceForWeeklySales("produce_milk", 64))
        assertEquals(20, TamaMarketPricing.priceForWeeklySales("produce_egg", 0))
        assertEquals(10, TamaMarketPricing.priceForWeeklySales("produce_egg", 64))
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
