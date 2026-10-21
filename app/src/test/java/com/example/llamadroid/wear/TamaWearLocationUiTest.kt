package com.example.llamadroid.wear

import com.example.llamadroid.tama.data.LocationType
import com.example.llamadroid.tama.data.LegacyLocationAliases
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TamaWearLocationUiTest {
    @Test
    fun fixedGridIdsResolveToTheirFacilityPresentation() {
        assertEquals(LocationType.ARCADE, TamaWearLocationCatalog.resolve(LegacyLocationAliases.ARCADE).type)
        assertEquals(
            "tama/backgrounds/arcade_location.png",
            TamaWearLocationCatalog.resolve(LegacyLocationAliases.ARCADE).backgroundAssetPath
        )
        assertEquals(LocationType.HOSPITAL, TamaWearLocationCatalog.resolve("fixed_3_0").type)
        assertTrue(TamaWearLocationCatalog.resolve(LegacyLocationAliases.HOME).usesHomeRoom)
    }

    @Test
    fun farmBarnKeepsItsFacilityPresentationAndUnknownIdsUseTheLandmarkFallback() {
        assertEquals(LocationType.FARM, TamaWearLocationCatalog.resolve("farm_barn").type)
        assertTrue(TamaWearLocationCatalog.resolve("farm_barn").canonical)
        assertEquals(false, TamaWearLocationCatalog.resolve("market_stall").canonical)
    }
}
