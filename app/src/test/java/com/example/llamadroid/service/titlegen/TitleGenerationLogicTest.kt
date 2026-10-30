package com.example.llamadroid.service.titlegen

import com.example.llamadroid.service.ephemeral.EphemeralLlamaServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TitleGenerationLogicTest {
    private val defaults = setOf("New Chat", "Nuevo Chat")
    private val mib = 1024L * 1024L

    @Test fun ramDecisionRequiresHeadroomAndNoLowMemory() {
        val model = 1000L * mib
        val needed = EphemeralLlamaServer.decideRam(0L, false, model).neededBytes
        assertTrue(TitleGenerationLogic.hasEnoughRam(needed, false, model))
        assertFalse(TitleGenerationLogic.hasEnoughRam(needed - 1, false, model))
        assertFalse(TitleGenerationLogic.hasEnoughRam(needed * 2, true, model))
        assertFalse(TitleGenerationLogic.hasEnoughRam(needed * 2, false, 0L))
    }

    @Test fun sanitizerStripsDecorations() {
        assertEquals("Planning a trip", TitleGenerationLogic.sanitizeTitle("  \"**Planning a trip**.\"\nextra line"))
        assertEquals("Hola mundo", TitleGenerationLogic.sanitizeTitle("Title: «Hola mundo»"))
        assertEquals("Answer", TitleGenerationLogic.sanitizeTitle("<think>hmm\nmore</think>\n# Answer"))
        assertNull(TitleGenerationLogic.sanitizeTitle("   "))
        assertNull(TitleGenerationLogic.sanitizeTitle("\"\""))
        assertNull(TitleGenerationLogic.sanitizeTitle(null))
    }

    @Test fun sanitizerCapsLengthOnWordBoundary() {
        val title = TitleGenerationLogic.sanitizeTitle("word ".repeat(30))!!
        assertTrue(title.length <= TitleGenerationLogic.MAX_TITLE_CHARS)
        assertFalse(title.endsWith(" "))
    }

    @Test fun promptTruncatesEachMessage() {
        val prompt = TitleGenerationLogic.buildUserPrompt("u".repeat(5000), "a".repeat(5000))
        assertTrue(prompt.startsWith(TitleGenerationLogic.INSTRUCTION))
        assertEquals(1500, prompt.count { it == 'u' })
        assertTrue(prompt.length < 3400)
    }

    @Test fun responseParsing() {
        val body = """{"choices":[{"message":{"role":"assistant","content":"\"Trip plan\""}}]}"""
        assertEquals("Trip plan", TitleGenerationLogic.parseTitleResponse(body))
        assertNull(TitleGenerationLogic.parseTitleResponse("{}"))
    }

    @Test fun enqueueOnAnyReplyWithDefaultTitle() {
        fun e(title: String = "New Chat", handled: Set<Long> = emptySet(), u: Int = 1, a: Int = 1) =
            TitleGenerationLogic.shouldEnqueue(title, defaults, handled, 7L, u, a)
        assertTrue(e())
        assertTrue(e(title = "nuevo chat"))
        assertTrue(e(title = " "))
        assertFalse(e(title = "My own name"))
        assertFalse(e(handled = setOf(7L)))
        assertFalse(e(a = 0))
        assertFalse(e(u = 0))
        assertTrue(e(a = 2))
    }

    @Test fun processingAllowsGrownChatsButNotRenamedOnes() {
        assertTrue(TitleGenerationLogic.stillEligible("New Chat", defaults, emptySet(), 1L, 2, 2))
        assertFalse(TitleGenerationLogic.stillEligible("Renamed", defaults, emptySet(), 1L, 1, 1))
        assertFalse(TitleGenerationLogic.stillEligible("New Chat", defaults, setOf(1L), 1L, 1, 1))
    }

    @Test fun statusMappingWhenIdle() {
        assertEquals(TitleGenerationStatus.NoModel, TitleGenerationLogic.baselineStatus(true, ""))
        assertEquals(TitleGenerationStatus.Disabled, TitleGenerationLogic.baselineStatus(false, "/m.gguf"))
        assertEquals(TitleGenerationStatus.Idle, TitleGenerationLogic.baselineStatus(true, "/m.gguf"))
    }

    @Test fun thinkBlocksAreStrippedIncludingUnterminated() {
        assertEquals("Real title", TitleGenerationLogic.sanitizeTitle("<think>a\nb</think>Real title"))
        assertNull(TitleGenerationLogic.sanitizeTitle("<think>still reasoning about"))
    }

    @Test fun requestDisablesThinkingAndHonoursMaxTokens() {
        val json = org.json.JSONObject(TitleGenerationLogic.buildRequestBody("hi", 64))
        assertEquals(64, json.getInt("max_tokens"))
        assertFalse(json.getJSONObject("chat_template_kwargs").getBoolean("enable_thinking"))
        assertEquals("none", json.getString("reasoning_effort"))
    }

    @Test fun ephemeralRamDecisionReportsNeedAndAvailability() {
        val model = 1000L * mib
        val d = EphemeralLlamaServer.decideRam(model, false, model, extraBytes = 100L * mib)
        assertFalse(d.ok)
        val estimate = com.example.llamadroid.service.ram.LlamaRamEstimator.estimate(
            com.example.llamadroid.service.ram.LlamaRamInputs(
                modelFileBytes = model, mmprojBytes = 100L * mib, totalContext = 2048
            )
        )
        assertEquals(
            estimate.totalBytes + com.example.llamadroid.service.ram.LlamaRamEstimator.SAFETY_MARGIN_BYTES,
            d.neededBytes
        )
        assertTrue(EphemeralLlamaServer.decideRam(d.neededBytes, false, model, 100L * mib).ok)
        assertFalse(EphemeralLlamaServer.decideRam(d.neededBytes, true, model, 100L * mib).ok)
    }
}
