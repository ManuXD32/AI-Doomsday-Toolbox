package com.example.llamadroid.data.easy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EasyModelCatalogTest {
    private val gib = 1024L * 1024L * 1024L

    @Test
    fun `every preset defaults to 512 square and lists it first`() {
        assertEquals(EasyImageKind.entries.toSet(), EasyImagePresets.all.map { it.kind }.toSet())
        EasyImagePresets.all.forEach { preset ->
            assertEquals(512 to 512, preset.sizes.first())
            assertEquals(512 to 512, preset.defaultSize)
            assertEquals(preset.sizes.size, preset.sizes.distinct().size)
            // ONNX pipeline requires multiples of 8; keep all presets on a 64 grid.
            assertTrue(preset.sizes.all { it.first % 64 == 0 && it.second % 64 == 0 })
        }
    }

    @Test
    fun `sd cpp presets carry a sampler and scheduler while onnx does not`() {
        EasyImagePresets.all.forEach { preset ->
            val onnx = preset.kind == EasyImageKind.ONNX_SD15
            assertEquals(onnx, preset.sampler == null)
            assertEquals(onnx, preset.scheduler == null)
        }
        assertEquals(20, EasyImagePresets.forKind(EasyImageKind.ONNX_SD15).steps)
    }

    @Test
    fun `every curated id resolves in its source catalog`() {
        assertTrue(EasyModelCatalog.entries.isNotEmpty())
        assertEquals(EasyModelCatalog.entries.size, EasyModelCatalog.entries.map { it.id }.distinct().size)
        EasyModelCatalog.entries.forEach { entry ->
            assertTrue("unresolved ${entry.id}", EasyModelCatalog.resolves(entry))
            assertTrue("no size for ${entry.id}", EasyModelCatalog.approxBytes(entry) > 0L)
            if (entry.kind == EasyModelKind.IMAGE) {
                assertNotNull(entry.engine)
                assertNotNull(entry.imageKind)
            }
        }
        assertEquals(4, EasyModelCatalog.entriesFor(EasyModelKind.CHAT).size)
        assertEquals(5, EasyModelCatalog.entriesFor(EasyModelKind.IMAGE).size)
        assertEquals(2, EasyModelCatalog.entriesFor(EasyModelKind.VOICE).size)
    }

    @Test
    fun `recommendFor picks sensible entries for 4 8 and 12 GB`() {
        fun id(kind: EasyModelKind, ram: Long) = EasyModelCatalog.recommendFor(kind, ram * gib)?.id
        assertEquals("chat-qwen35-2b", id(EasyModelKind.CHAT, 4))
        assertEquals("chat-qwen35-4b", id(EasyModelKind.CHAT, 8))
        assertEquals("chat-qwen35-4b", id(EasyModelKind.CHAT, 12))
        assertEquals("image-sd15-q4", id(EasyModelKind.IMAGE, 4))
        assertEquals("image-sdxl-turbo", id(EasyModelKind.IMAGE, 8))
        assertEquals("image-flux-schnell", id(EasyModelKind.IMAGE, 12))
        assertEquals("voice-whisper-small", id(EasyModelKind.VOICE, 4))
    }

    @Test
    fun `recommendFor never returns null and falls back to the lightest entry`() {
        EasyModelKind.entries.forEach { kind ->
            assertNotNull(EasyModelCatalog.recommendFor(kind, 1 * gib))
        }
        assertEquals("chat-lfm25-12b", EasyModelCatalog.recommendFor(EasyModelKind.CHAT, 1 * gib)?.id)
    }

    @Test
    fun `exceedsRecommendation flags flux on 8 GB without filtering it`() {
        val flux = EasyModelCatalog.byId("image-flux-schnell")!!
        assertTrue(EasyModelCatalog.exceedsRecommendation(flux, 8 * gib))
        assertFalse(EasyModelCatalog.exceedsRecommendation(flux, 12 * gib))
        assertFalse(EasyModelCatalog.exceedsRecommendation(EasyModelCatalog.byId("image-sd15-q4")!!, 8 * gib))
        assertTrue(EasyModelCatalog.entriesFor(EasyModelKind.IMAGE).contains(flux))
    }
}
