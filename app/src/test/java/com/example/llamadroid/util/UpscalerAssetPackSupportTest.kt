package com.example.llamadroid.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class UpscalerAssetPackSupportTest {

    @Test
    fun `refresh is required when extracted models are missing`() {
        assertTrue(
            UpscalerAssetPackSupport.shouldRefreshExtractedModels(
                hasExtractedModels = false,
                storedVersionCode = 936L,
                currentVersionCode = 936L
            )
        )
    }

    @Test
    fun `refresh is skipped when app updates but extracted models remain present`() {
        assertFalse(
            UpscalerAssetPackSupport.shouldRefreshExtractedModels(
                hasExtractedModels = true,
                storedVersionCode = 935L,
                currentVersionCode = 936L
            )
        )
    }

    @Test
    fun `refresh is skipped when extracted models match current version`() {
        assertFalse(
            UpscalerAssetPackSupport.shouldRefreshExtractedModels(
                hasExtractedModels = true,
                storedVersionCode = 936L,
                currentVersionCode = 936L
            )
        )
    }

    @Test
    fun `stored version marker is parsed from extracted models directory`() {
        val tempDir = Files.createTempDirectory("upscaler-models").toFile()
        File(tempDir, ".version_code").writeText("936")

        assertEquals(936L, UpscalerAssetPackSupport.readStoredVersionCode(tempDir))
    }

    @Test
    fun `extracted model detection ignores version marker-only directories`() {
        val tempDir = Files.createTempDirectory("upscaler-models").toFile()
        File(tempDir, ".version_code").writeText("936")

        assertFalse(UpscalerAssetPackSupport.hasExtractedModels(tempDir))

        File(tempDir, "realesrgan-x4.bin").writeText("model")
        assertTrue(UpscalerAssetPackSupport.hasExtractedModels(tempDir))
    }
}
