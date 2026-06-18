package com.example.llamadroid.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LlamaServerEntityTest {

    @Test
    fun `ollama capabilities derive vision and audio flags`() {
        val state = deriveOllamaCapabilityState(listOf("completion", "vision", "audio"))

        assertTrue(state.supportsVision)
        assertTrue(state.supportsAudio)
    }

    @Test
    fun `engine helpers detect direct audio support`() {
        val llamaServer = LlamaServerEntity(
            name = "llama",
            host = "localhost",
            port = 8080,
            engine = LlamaServerEntity.ENGINE_LLAMA_SERVER,
            supportsAudio = true
        )
        val ollamaServer = LlamaServerEntity(
            name = "ollama",
            host = "localhost",
            port = 11434,
            engine = LlamaServerEntity.ENGINE_OLLAMA,
            supportsAudio = true
        )
        val llamaSwapServer = LlamaServerEntity(
            name = "swap",
            host = "localhost",
            port = 9292,
            engine = "llama_swap",
            supportsAudio = true
        )

        assertTrue(llamaServer.supportsDirectAudioInput())
        assertFalse(llamaServer.requiresAudioTranscriptionFallback())
        assertFalse(ollamaServer.supportsDirectAudioInput())
        assertTrue(ollamaServer.requiresAudioTranscriptionFallback())
        assertTrue(llamaSwapServer.isLlamaSwapEngine())
        assertTrue(llamaSwapServer.usesOpenAiCompatibleEngine())
        assertTrue(llamaSwapServer.supportsDirectAudioInput())
    }

    @Test
    fun `audio transcript merge keeps typed text and disables audio embedding`() {
        val merged = mergeUserTextWithAudioTranscript(
            userText = "Please summarize this",
            transcript = "Hello from the recording"
        )

        assertEquals(
            "Please summarize this\n\nThis is the transcription of an audio sent by the user: Hello from the recording",
            merged
        )
        assertTrue(hasEmbeddedAudioTranscript(merged))
        assertFalse(shouldEmbedAudioAttachment(merged, "/tmp/audio.m4a"))
    }

    @Test
    fun `embedded transcript can be extracted without polluting visible user text`() {
        val merged = mergeUserTextWithAudioTranscript(
            userText = "Please summarize this",
            transcript = "Hello from the recording"
        )

        assertEquals("Hello from the recording", extractEmbeddedAudioTranscript(merged))
        assertEquals("Please summarize this", stripEmbeddedAudioTranscript(merged))
    }
}
