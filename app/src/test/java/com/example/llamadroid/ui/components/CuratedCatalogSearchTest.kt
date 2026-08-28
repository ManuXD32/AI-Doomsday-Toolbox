package com.example.llamadroid.ui.components

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CuratedCatalogSearchTest {
    @Test
    fun `curated catalogs are visible only while browsing`() {
        assertTrue(isCuratedCatalogBrowseMode(""))
        assertTrue(isCuratedCatalogBrowseMode("  \t"))
        assertFalse(isCuratedCatalogBrowseMode("qwen"))
        assertFalse(isCuratedCatalogBrowseMode("  adetailer detector  "))
    }
}
