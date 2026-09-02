package com.example.llamadroid.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MangaPaintedOcrSupportTest {
    @Test
    fun `diagonal mask pixels are one 8-connected region`() {
        val analysis = MangaPaintedOcrSupport.analyzeMask(
            mask = byteArrayOf(
                3, 0, 0,
                0, 3, 0,
                0, 0, 3
            ),
            width = 3,
            height = 3,
            minComponentPixels = 3
        )

        assertEquals(1, analysis.regions.size)
        assertEquals(3, analysis.regions.single().pixelCount)
        assertEquals(MangaNormalizedRect(0f, 0f, 1f, 1f), analysis.regions.single().bounds)
    }

    @Test
    fun `separate painted islands stay separate and tiny marks are warned`() {
        val analysis = MangaPaintedOcrSupport.analyzeMask(
            mask = byteArrayOf(
                3, 3, 0, 0, 3,
                0, 0, 0, 0, 0,
                0, 0, 0, 3, 0,
                0, 0, 0, 0, 0
            ),
            width = 5,
            height = 4,
            minComponentPixels = 2
        )

        assertEquals(1, analysis.regions.size)
        assertEquals(2, analysis.ignoredTinyMarks)
        assertTrue(analysis.warnings.any { it.contains("tiny", ignoreCase = true) })
        assertEquals(MangaNormalizedRect(0f, 0f, 0.4f, 0.25f), analysis.regions.single().bounds)
    }

    @Test
    fun `normalized rectangles clamp and padded bounds remain within page`() {
        val region = MangaPaintedRegionDescriptor(
            regionId = "region",
            pageIndex = 0,
            bounds = MangaNormalizedRect(-0.1f, 0.2f, 0.4f, 1.4f)
        )

        assertEquals(MangaNormalizedRect(0f, 0.2f, 0.4f, 1f), region.bounds.clamped())
        val padded = MangaPaintedOcrSupport.paddedRegion(region)
        assertTrue(padded.left >= 0f)
        assertTrue(padded.top >= 0f)
        assertTrue(padded.right <= 1f)
        assertTrue(padded.bottom <= 1f)
        val bounds = MangaNormalizedRect(0f, 0f, 0.5f, 0.5f).toPixelBounds(100, 80)
        assertEquals(0, bounds.left)
        assertEquals(50, bounds.right)
        assertEquals(40, bounds.bottom)
    }

    @Test
    fun `reading direction changes region order within each row`() {
        val regions = listOf(
            descriptor("left", 0.10f, 0.10f),
            descriptor("right", 0.60f, 0.10f),
            descriptor("lower", 0.20f, 0.65f)
        )

        assertEquals(
            listOf("left", "right", "lower"),
            MangaPaintedOcrSupport.orderRegions(
                regions,
                pageHeight = 1f,
                direction = MangaReadingDirection.LEFT_TO_RIGHT
            ).map { it.regionId }
        )
        assertEquals(
            listOf("right", "left", "lower"),
            MangaPaintedOcrSupport.orderRegions(
                regions,
                pageHeight = 1f,
                direction = MangaReadingDirection.RIGHT_TO_LEFT
            ).map { it.regionId }
        )
    }

    @Test
    fun `workspace fingerprints include revision and source identity`() {
        val first = MangaPaintedOcrWorkspaceRef("workspace", revision = 2L, sourceFingerprint = "source-a")
        val changedRevision = first.copy(revision = 3L)
        val changedSource = first.copy(sourceFingerprint = "source-b")

        assertTrue(
            MangaPaintedOcrSupport.workspaceFingerprint(first) !=
                MangaPaintedOcrSupport.workspaceFingerprint(changedRevision)
        )
        assertTrue(
            MangaPaintedOcrSupport.workspaceFingerprint(first) !=
                MangaPaintedOcrSupport.workspaceFingerprint(changedSource)
        )
    }

    private fun descriptor(id: String, left: Float, top: Float): MangaPaintedRegionDescriptor =
        MangaPaintedRegionDescriptor(
            regionId = id,
            pageIndex = 0,
            bounds = MangaNormalizedRect(left, top, left + 0.2f, top + 0.15f),
            pixelCount = 10
        )
}
