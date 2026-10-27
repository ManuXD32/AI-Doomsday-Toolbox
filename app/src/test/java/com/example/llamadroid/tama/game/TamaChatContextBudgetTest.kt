package com.example.llamadroid.tama.game

import com.example.llamadroid.service.OllamaService
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TamaChatContextBudgetTest {
    private fun msg(i: Int, images: List<String>? = null, path: String? = null) =
        OllamaService.ChatMessage(id = "m$i", role = "user", content = "t$i", images = images, imagePath = path)

    @Test fun percentIsRoundedDownAndClamped() {
        assertEquals(50, TamaContextUsage(2048, 4096).percent)
        assertEquals(33, TamaContextUsage(1, 3).percent)
        assertEquals(100, TamaContextUsage(9000, 4096).percent)
        assertEquals(0, TamaContextUsage(100, 0).percent)
    }

    @Test fun outputCapNeverExceedsQuarterOfWindow() {
        assertEquals(1024, tamaOutputCap(16384))
        assertEquals(512, tamaOutputCap(2048))
        assertEquals(1024, tamaOutputCap(0))
        assertEquals(256, tamaOutputCap(8192, configured = 256))
    }

    @Test fun compactGateUsesReserve() {
        // usable = 4096 - 1024 = 3072; default threshold is below 1 so a nearly full prompt triggers.
        assertTrue(tamaShouldCompact(3500, 4096, 1024))
        assertFalse(tamaShouldCompact(100, 4096, 1024))
        assertFalse(tamaShouldCompact(100000, 0, 1024))
    }

    @Test fun windowLevelsShrinkStepwise() {
        assertEquals(20, nextTamaWindowMax(30))
        assertEquals(10, nextTamaWindowMax(20))
        assertEquals(10, nextTamaWindowMax(10))
    }

    @Test fun minimumHistoryIsKept() {
        val history = (0 until 11).toList()
        // max 10, block 10 would keep only 1 message.
        assertEquals(history.takeLast(TAMA_MIN_HISTORY_MESSAGES), anchoredTamaHistoryWindowMin(history, 10))
        assertEquals((0 until 5).toList(), anchoredTamaHistoryWindowMin((0 until 5).toList(), 10))
    }

    @Test fun fitPicksLargestFittingLevel() = runBlocking {
        assertEquals(30, fitTamaWindowMax(30) { true })
        assertEquals(20, fitTamaWindowMax(30) { it <= 20 })
        assertEquals(10, fitTamaWindowMax(30) { false })
        assertEquals(10, fitTamaWindowMax(20) { it <= 10 })
        assertEquals(10, fitTamaWindowMax(5) { false })
    }

    @Test fun usedTokensPrefersPromptPlusCompletion() {
        assertEquals(150, tamaUsedTokens(OllamaService.ChatUsage(promptTokens = 100, completionTokens = 50, totalTokens = 999)))
        assertEquals(70, tamaUsedTokens(OllamaService.ChatUsage(totalTokens = 70)))
        assertNull(tamaUsedTokens(null))
    }

    @Test fun visionMappingDescribesNewestAndNotesTheRest() = runBlocking {
        val messages = listOf(msg(0, path = "/a.png"), msg(1), msg(2, images = listOf("QQ==", "Qg==")))
        val out = mapTamaImages(messages, configured = true, describe = { "ZZZ" }, maxImages = 2)
        assertTrue(out[0].content.contains(TAMA_IMAGE_OMITTED_NOTE))
        assertEquals("t1", out[1].content)
        assertEquals(2, Regex("ZZZ").findAll(out[2].content).count())
        assertTrue(out.all { it.imagePath == null && it.images == null })
    }

    @Test fun visionMappingNotesUnavailable() = runBlocking {
        val messages = listOf(msg(0, path = "/a.png"), msg(1, images = listOf("QQ==")))
        val notConfigured = mapTamaImages(messages, configured = false, describe = { error("not called") })
        assertTrue(notConfigured[0].content.contains(TAMA_IMAGE_UNDESCRIBED_NOTE))
        val failing = mapTamaImages(messages, configured = true, describe = { null })
        assertTrue(failing[1].content.endsWith(TAMA_IMAGE_UNDESCRIBED_NOTE))
        assertNull(failing[1].images)
    }
}
