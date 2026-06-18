package com.example.llamadroid.service

import com.example.llamadroid.data.db.LIVE_TRANSLATOR_SPEAKER_ONE
import com.example.llamadroid.data.db.LIVE_TRANSLATOR_SPEAKER_TWO
import com.example.llamadroid.data.db.LiveTranslatorTemplateEntity
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveTranslatorLogicTest {
    @Test
    fun nextSpeaker_alternatesAndOverrideCanResumeFromChosenSpeaker() {
        assertEquals(LIVE_TRANSLATOR_SPEAKER_TWO, LiveTranslatorLogic.nextSpeaker(LIVE_TRANSLATOR_SPEAKER_ONE))
        assertEquals(LIVE_TRANSLATOR_SPEAKER_ONE, LiveTranslatorLogic.nextSpeaker(LIVE_TRANSLATOR_SPEAKER_TWO))
    }

    @Test
    fun prompt_targetsOppositeSpeakerLanguage() {
        val template = LiveTranslatorTemplateEntity(
            name = "Travel",
            speaker1Language = "English",
            speaker2Language = "Spanish"
        )

        val prompt = LiveTranslatorLogic.buildUserPrompt(
            sourceLanguage = LiveTranslatorLogic.sourceLanguage(template, LIVE_TRANSLATOR_SPEAKER_ONE),
            targetLanguage = LiveTranslatorLogic.targetLanguage(template, LIVE_TRANSLATOR_SPEAKER_ONE),
            transcript = "Where is the train station?"
        )

        assertTrue(prompt.contains("conversation languages are English and Spanish"))
        assertTrue(prompt.contains("If the source text is English, translate it to Spanish"))
        assertTrue(prompt.contains("If the source text is Spanish, translate it to English"))
        assertTrue(prompt.contains("Where is the train station?"))
    }

    @Test
    fun whisperLanguage_isAlwaysAutoConstant() {
        assertEquals("auto", LiveTranslatorLogic.WHISPER_LANGUAGE_AUTO)
    }

    @Test
    fun targetTtsLanguage_usesOppositeSpeakerSetting() {
        val template = LiveTranslatorTemplateEntity(
            name = "Travel",
            speaker1TtsLanguage = "en",
            speaker2TtsLanguage = "es"
        )

        assertEquals("es", LiveTranslatorLogic.targetTtsLanguage(template, LIVE_TRANSLATOR_SPEAKER_ONE))
        assertEquals("en", LiveTranslatorLogic.targetTtsLanguage(template, LIVE_TRANSLATOR_SPEAKER_TWO))
    }

    @Test
    fun resolveTurnRouting_forcesTtsToTargetLanguageWhenTemplateVoiceIsMismatched() {
        val template = LiveTranslatorTemplateEntity(
            name = "Travel",
            speaker1Language = "English",
            speaker2Language = "Spanish",
            speaker1TtsLanguage = "es",
            speaker2TtsLanguage = "en"
        )

        val routing = LiveTranslatorLogic.resolveTurnRouting(
            template = template,
            expectedSpeaker = LIVE_TRANSLATOR_SPEAKER_ONE,
            detectedLanguage = "Spanish"
        )

        assertEquals("English", routing.targetLanguage)
        assertEquals("en", routing.targetTtsLanguage)
    }

    @Test
    fun resolveTurnRouting_usesDetectedLanguageWhenSpeakerOrderIsWrong() {
        val template = LiveTranslatorTemplateEntity(
            name = "Travel",
            speaker1Language = "English",
            speaker2Language = "Spanish",
            speaker1TtsLanguage = "en",
            speaker2TtsLanguage = "es"
        )

        val routing = LiveTranslatorLogic.resolveTurnRouting(
            template = template,
            expectedSpeaker = LIVE_TRANSLATOR_SPEAKER_ONE,
            detectedLanguage = "Spanish"
        )

        assertEquals(LIVE_TRANSLATOR_SPEAKER_TWO, routing.sourceSpeaker)
        assertEquals(LIVE_TRANSLATOR_SPEAKER_ONE, routing.targetSpeaker)
        assertEquals("Spanish", routing.sourceLanguage)
        assertEquals("English", routing.targetLanguage)
        assertEquals("en", routing.targetTtsLanguage)
        assertTrue(routing.usedDetectedLanguage)
    }

    @Test
    fun resolveTurnRouting_allowsSameSpeakerToSpeakAgain() {
        val template = LiveTranslatorTemplateEntity(
            name = "Travel",
            speaker1Language = "English",
            speaker2Language = "Spanish",
            speaker1TtsLanguage = "en",
            speaker2TtsLanguage = "es"
        )

        val routing = LiveTranslatorLogic.resolveTurnRouting(
            template = template,
            expectedSpeaker = LIVE_TRANSLATOR_SPEAKER_TWO,
            detectedLanguage = "en"
        )

        assertEquals(LIVE_TRANSLATOR_SPEAKER_ONE, routing.sourceSpeaker)
        assertEquals("English", routing.sourceLanguage)
        assertEquals("Spanish", routing.targetLanguage)
        assertEquals("es", routing.targetTtsLanguage)
    }

    @Test
    fun resolveTurnRouting_fallsBackToExpectedSpeakerWhenDetectionIsUnknown() {
        val template = LiveTranslatorTemplateEntity(
            name = "Travel",
            speaker1Language = "English",
            speaker2Language = "Spanish",
            speaker1TtsLanguage = "en",
            speaker2TtsLanguage = "es"
        )

        val routing = LiveTranslatorLogic.resolveTurnRouting(
            template = template,
            expectedSpeaker = LIVE_TRANSLATOR_SPEAKER_TWO,
            detectedLanguage = "Welsh"
        )

        assertEquals(LIVE_TRANSLATOR_SPEAKER_TWO, routing.sourceSpeaker)
        assertEquals("Spanish", routing.sourceLanguage)
        assertEquals("English", routing.targetLanguage)
        assertEquals("en", routing.targetTtsLanguage)
        assertFalse(routing.usedDetectedLanguage)
    }

    @Test
    fun shouldRetryTranslation_whenModelReturnsSourceLanguage() {
        assertTrue(
            LiveTranslatorLogic.shouldRetryTranslation(
                sourceLanguage = "Spanish",
                targetLanguage = "English",
                transcript = "Hola, necesito ayuda con el tren.",
                translated = "Hola, necesito ayuda con el tren."
            )
        )
    }

    @Test
    fun shouldRetryTranslation_allowsTargetLanguageOutput() {
        assertFalse(
            LiveTranslatorLogic.shouldRetryTranslation(
                sourceLanguage = "Spanish",
                targetLanguage = "English",
                transcript = "Hola, necesito ayuda con el tren.",
                translated = "Hello, I need help with the train."
            )
        )
    }

    @Test
    fun ttsLanguageForTranslatedText_usesDetectedTextLanguageAsFinalSafety() {
        assertEquals(
            "es",
            LiveTranslatorLogic.ttsLanguageForTranslatedText(
                targetTtsLanguage = "en",
                translatedText = "Hola, necesito ayuda con el tren."
            )
        )
    }

    @Test
    fun templateSnapshot_containsRuntimeSettings() {
        val snapshot = JSONObject(
            LiveTranslatorLogic.templateSnapshotJson(
                LiveTranslatorTemplateEntity(
                    id = 7,
                    name = "Clinic",
                    speaker1Language = "English",
                    speaker2Language = "Spanish",
                    whisperModelPath = "/models/whisper.bin",
                    whisperThreads = 6,
                    ttsModelPath = "/models/supertonic",
                    ttsLanguage = "es",
                    speaker1TtsLanguage = "en",
                    speaker2TtsLanguage = "es",
                    llamaServerUrl = "https://llama.example/v1",
                    llamaSwapUrl = "https://swap.example/openai",
                    ollamaUrl = "https://ollama.example/api",
                    liteRtMtpEnabled = true,
                    backendEngine = "litert-lm",
                    liteRtModelId = 4,
                    startSpeakingTimeoutSeconds = 12,
                    finishedTalkingTimeoutSeconds = 3
                )
            )
        )

        assertEquals("Clinic", snapshot.getString("name"))
        assertEquals("/models/whisper.bin", snapshot.getString("whisperModelPath"))
        assertEquals(6, snapshot.getInt("whisperThreads"))
        assertEquals("en", snapshot.getString("speaker1TtsLanguage"))
        assertEquals("es", snapshot.getString("speaker2TtsLanguage"))
        assertEquals("https://llama.example/v1", snapshot.getString("llamaServerUrl"))
        assertEquals("https://swap.example/openai", snapshot.getString("llamaSwapUrl"))
        assertEquals("https://ollama.example/api", snapshot.getString("ollamaUrl"))
        assertTrue(snapshot.getBoolean("liteRtMtpEnabled"))
        assertEquals("litert-lm", snapshot.getString("backendEngine"))
        assertEquals(4L, snapshot.getLong("liteRtModelId"))
        assertEquals(12, snapshot.getInt("startSpeakingTimeoutSeconds"))
        assertEquals(3, snapshot.getInt("finishedTalkingTimeoutSeconds"))
    }
}
