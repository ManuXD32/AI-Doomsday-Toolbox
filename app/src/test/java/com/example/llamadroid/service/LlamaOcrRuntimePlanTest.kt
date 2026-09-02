package com.example.llamadroid.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LlamaOcrRuntimePlanTest {
    @Test
    fun `coexistence never selects an existing local runtime for pause`() {
        val plan = planLlamaOcrRuntimePause(
            capturedRuntimes = listOf(
                captured("card:translation", 8080),
                captured("card:other", 9090)
            ),
            ocrPort = 8087,
            temporarilyReplaceRunningServer = false,
            preferredTranslationPort = 8080
        )

        assertEquals(emptyList<LlamaOcrCapturedRuntime>(), plan.runtimesToPause)
        assertEquals(8080, plan.preferredTranslationPort)
    }

    @Test
    fun `opt in prioritizes selected translation server and adds only OCR port occupant`() {
        val plan = planLlamaOcrRuntimePause(
            capturedRuntimes = listOf(
                captured("card:unrelated", 9191),
                captured("card:ocr-port", 8087),
                captured("card:translation", 8080)
            ),
            ocrPort = 8087,
            temporarilyReplaceRunningServer = true,
            preferredTranslationPort = 8080
        )

        assertEquals(
            listOf("card:translation", "card:ocr-port"),
            plan.runtimesToPause.map { it.sessionId }
        )
    }

    @Test
    fun `opt in falls back to first deterministic server and adds OCR port occupant`() {
        val plan = planLlamaOcrRuntimePause(
            capturedRuntimes = listOf(
                captured("card:zulu", 9191),
                captured("card:alpha", 9090),
                captured("card:bravo", 8087)
            ),
            ocrPort = 8087,
            temporarilyReplaceRunningServer = true,
            preferredTranslationPort = 8080
        )

        // The first deterministic app-owned server is the fallback. A different server
        // occupying the reserved OCR port is added separately so the OCR session can bind.
        assertEquals(
            listOf("card:alpha", "card:bravo"),
            plan.runtimesToPause.map { it.sessionId }
        )
    }

    @Test
    fun `opt in chooses first deterministic server when no translation or OCR occupant exists`() {
        val plan = planLlamaOcrRuntimePause(
            capturedRuntimes = listOf(
                captured("card:zulu", 9191),
                captured("card:alpha", 9090)
            ),
            ocrPort = 8087,
            temporarilyReplaceRunningServer = true,
            preferredTranslationPort = null
        )

        assertEquals(listOf("card:alpha"), plan.runtimesToPause.map { it.sessionId })
    }

    @Test
    fun `translation endpoint already on OCR port is not duplicated`() {
        val plan = planLlamaOcrRuntimePause(
            capturedRuntimes = listOf(captured("card:translation", 8087)),
            ocrPort = 8087,
            temporarilyReplaceRunningServer = true,
            preferredTranslationPort = 8087
        )

        assertEquals(listOf("card:translation"), plan.runtimesToPause.map { it.sessionId })
    }

    @Test
    fun `only loopback llama URLs produce a preferred local port`() {
        assertEquals(8080, localLlamaServerPort("http://127.0.0.1"))
        assertEquals(9123, localLlamaServerPort("localhost:9123/v1"))
        assertEquals(9124, localLlamaServerPort("https://[::1]:9124/v1"))
        assertNull(localLlamaServerPort("https://example.test:8080"))
        assertNull(localLlamaServerPort("not a URL"))
        assertNull(localLlamaServerPort(null))
    }

    private fun captured(sessionId: String, port: Int): LlamaOcrCapturedRuntime =
        LlamaOcrCapturedRuntime(
            sessionId = sessionId,
            kind = LlamaOcrCapturedRuntimeKind.CARD,
            port = port,
            launchProfileJson = LlamaServerLaunchProfile.encode(
                LlamaServerLaunchProfile(
                    modelPath = "/models/$sessionId.gguf",
                    serverPort = port
                )
            )
        )
}
