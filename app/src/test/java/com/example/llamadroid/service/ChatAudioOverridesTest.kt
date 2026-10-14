package com.example.llamadroid.service

import com.example.llamadroid.data.model.LlamaServerEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatAudioOverridesTest {
    private fun server(audio: Boolean, whisper: Boolean = false) = LlamaServerEntity(
        name = "s", host = "h", port = 1, supportsAudio = audio, preferWhisperAudioTranscription = whisper,
        whisperModelPath = "/m/a.bin", whisperLanguage = "es"
    )

    @Test
    fun serverModeKeepsTheServerUntouched() {
        val s = server(audio = true, whisper = true)
        assertSame(s, s.withChatAudioOverrides(NativeChatToolConfig()))
    }

    @Test
    fun whisperModeForcesTranscription() {
        val s = server(audio = true).withChatAudioOverrides(NativeChatToolConfig(audioInputMode = "whisper"))
        assertTrue(s.preferWhisperAudioTranscription)
        assertTrue(s.requiresAudioTranscriptionFallback())
    }

    @Test
    fun directModeForcesDirectEvenWithoutCapabilityUnlessRejected() {
        val forced = server(audio = false).withChatAudioOverrides(NativeChatToolConfig(audioInputMode = "direct"))
        assertTrue(forced.supportsDirectAudioInput())
        val rejected = server(audio = false).withChatAudioOverrides(NativeChatToolConfig(audioInputMode = "direct"), directRejected = true)
        assertTrue(rejected.requiresAudioTranscriptionFallback())
        val rejectedCapable = server(audio = true).withChatAudioOverrides(NativeChatToolConfig(audioInputMode = "direct"), directRejected = true)
        assertTrue(rejectedCapable.preferWhisperAudioTranscription)
    }

    @Test
    fun directModeAppliesWithCapability() {
        val capable = server(audio = true, whisper = true).withChatAudioOverrides(NativeChatToolConfig(audioInputMode = "direct"))
        assertFalse(capable.preferWhisperAudioTranscription)
        assertTrue(capable.supportsDirectAudioInput())
    }

    @Test
    fun modelAndLanguageOverrideWhenSet() {
        val s = server(audio = false).withChatAudioOverrides(
            NativeChatToolConfig(whisperModelPath = "/m/b.bin", whisperLanguage = "fr")
        )
        assertEquals("/m/b.bin", s.whisperModelPath)
        assertEquals("fr", s.whisperLanguage)
        val kept = server(audio = false).withChatAudioOverrides(NativeChatToolConfig())
        assertEquals("/m/a.bin", kept.whisperModelPath)
    }

    @Test
    fun configRoundTripsThroughApiParams() {
        val cfg = NativeChatToolConfig(audioInputMode = "whisper", whisperModelPath = "/m/b.bin", whisperLanguage = "de")
        val json = org.json.JSONObject(cfg.toParamMap()).toString()
        val back = NativeChatToolConfig.fromApiParams(json)
        assertEquals("whisper", back.audioInputMode)
        assertEquals("/m/b.bin", back.whisperModelPath)
        assertEquals("de", back.whisperLanguage)
        assertEquals("server", NativeChatToolConfig.fromApiParams("{\"chat_audio_input_mode\":\"bogus\"}").audioInputMode)
    }

    @Test
    fun seedParamsCopyServerWhisperSettings() {
        val seed = server(audio = true, whisper = true).chatAudioSeedParams()
        assertEquals("whisper", seed[NativeChatToolConfig.KEY_AUDIO_INPUT_MODE])
        assertEquals("/m/a.bin", seed[NativeChatToolConfig.KEY_WHISPER_MODEL_PATH])
        assertEquals("es", seed[NativeChatToolConfig.KEY_WHISPER_LANGUAGE])
        // Without direct capability Whisper is already the only route, so the mode stays "server".
        assertFalse(server(audio = false, whisper = true).chatAudioSeedParams().containsKey(NativeChatToolConfig.KEY_AUDIO_INPUT_MODE))
        val neutral = LlamaServerEntity(name = "n", host = "h", port = 1).chatAudioSeedParams()
        assertTrue(neutral.isEmpty())
    }
}
