package com.example.llamadroid.tama.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class TamaMemorySupportTest {
    @Test
    fun `sanitizeTamaModelOutput strips bracketed thinking blocks`() {
        val raw = """
            [Start thinking]
            I should not show this planning text.
            [End thinking]
            Final answer: Peque dreamed about a moonlit garden.
        """.trimIndent()

        val cleaned = sanitizeTamaModelOutput(raw)

        assertEquals("Peque dreamed about a moonlit garden.", cleaned)
        assertFalse(cleaned.contains("planning", ignoreCase = true))
    }

    @Test
    fun `sanitizeTamaModelOutput strips heading based reasoning before final content`() {
        val raw = """
            Thinking Process:
            The pet was hungry, then studied, then slept. Build a cozy recap.
            Story: Peque wandered through a tiny library under soft stars.
        """.trimIndent()

        val cleaned = sanitizeTamaModelOutput(raw)

        assertEquals("Peque wandered through a tiny library under soft stars.", cleaned)
        assertFalse(cleaned.contains("Build a cozy recap", ignoreCase = true))
    }

    @Test
    fun `sanitizeTamaModelOutput keeps required prompt prefix for deep dream image steps`() {
        val raw = """
            Thoughts:
            Choose a simple retro scene.
            Prompt: retro handheld game dream scene, Peque beside glowing mushrooms
        """.trimIndent()

        val cleaned = sanitizeTamaModelOutput(raw)

        assertEquals("Prompt: retro handheld game dream scene, Peque beside glowing mushrooms", cleaned)
    }
}
