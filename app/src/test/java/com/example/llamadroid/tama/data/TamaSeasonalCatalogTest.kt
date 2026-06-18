package com.example.llamadroid.tama.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class TamaSeasonalCatalogTest {
    @Test
    fun meteorologicalSeasonMappingUsesExpectedMonths() {
        assertEquals(TamaSeason.WINTER, TamaSeason.forMonth(Calendar.JANUARY))
        assertEquals(TamaSeason.WINTER, TamaSeason.forMonth(Calendar.FEBRUARY))
        assertEquals(TamaSeason.SPRING, TamaSeason.forMonth(Calendar.MARCH))
        assertEquals(TamaSeason.SPRING, TamaSeason.forMonth(Calendar.MAY))
        assertEquals(TamaSeason.SUMMER, TamaSeason.forMonth(Calendar.JUNE))
        assertEquals(TamaSeason.SUMMER, TamaSeason.forMonth(Calendar.AUGUST))
        assertEquals(TamaSeason.AUTUMN, TamaSeason.forMonth(Calendar.SEPTEMBER))
        assertEquals(TamaSeason.AUTUMN, TamaSeason.forMonth(Calendar.NOVEMBER))
        assertEquals(TamaSeason.WINTER, TamaSeason.forMonth(Calendar.DECEMBER))
    }

    @Test
    fun seasonalDecorIsFilteredBySeasonButStillLookupable() {
        TamaSeason.values().forEach { season ->
            val seasonal = TamaDecorCatalog.seasonalDecorForSeason(season)
            assertEquals(6, seasonal.size)
            assertTrue(seasonal.all { it.season == season })
        }

        val springOnly = "seasonal_spring_blossom_chime"
        assertTrue(TamaDecorCatalog.seasonalDecorForSeason(TamaSeason.SPRING).any { it.id == springOnly })
        assertTrue(TamaDecorCatalog.seasonalDecorForSeason(TamaSeason.WINTER).none { it.id == springOnly })
        assertNotNull(TamaDecorCatalog.decorById(springOnly))
        assertEquals(12, TamaDecorCatalog.shopDecor().size)
        assertNull(TamaDecorCatalog.shopDecor().first().season)
    }

    @Test
    fun seasonalRoomsAreFilteredBySeasonButStillLookupable() {
        TamaSeason.values().forEach { season ->
            val seasonal = TamaRoomCatalog.seasonalRoomsForSeason(season)
            assertEquals(4, seasonal.size)
            assertTrue(seasonal.all { it.season == season })
        }

        val summerOnly = "seasonal_summer_beach_cabana"
        assertTrue(TamaRoomCatalog.seasonalRoomsForSeason(TamaSeason.SUMMER).any { it.id == summerOnly })
        assertTrue(TamaRoomCatalog.seasonalRoomsForSeason(TamaSeason.AUTUMN).none { it.id == summerOnly })
        assertNotNull(TamaRoomCatalog.roomById(summerOnly))
        assertEquals(7, TamaRoomCatalog.shopRooms().size)
        assertNull(TamaRoomCatalog.shopRooms().first().season)
    }
}
