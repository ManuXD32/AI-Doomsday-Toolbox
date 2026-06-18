package com.example.llamadroid.service

import com.example.llamadroid.data.db.LIVE_TRANSLATOR_ENGINE_OLLAMA
import com.example.llamadroid.data.db.LiveTranslatorTemplateEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class LiveTranslatorNativeRunnerTest {

    @Test
    fun remoteSnapshotPrefersStoredFullUrlsOverLegacyHostPort() {
        val snapshot = buildLiveTranslatorRemoteSnapshot(
            LiveTranslatorTemplateEntity(
                name = "Travel",
                backendEngine = LIVE_TRANSLATOR_ENGINE_OLLAMA,
                llamaServerUrl = "https://llama.example/v1",
                llamaSwapUrl = "https://swap.example/openai",
                llamaHost = "legacy-llama",
                llamaPort = 8081,
                ollamaUrl = "https://ollama.example/api",
                ollamaHost = "legacy-ollama",
                ollamaPort = 11435
            )
        )

        assertEquals("https://ollama.example:11434/api", snapshot.ollamaUrl)
        assertEquals("https://llama.example:8080/v1", snapshot.llamaServerUrl)
        assertEquals("https://swap.example:9292/openai", snapshot.llamaSwapUrl)
    }
}
