package com.example.llamadroid.service

import com.example.llamadroid.data.db.LIVE_TRANSLATOR_SPEAKER_ONE
import com.example.llamadroid.data.db.LIVE_TRANSLATOR_SPEAKER_TWO
import com.example.llamadroid.data.db.LiveTranslatorTemplateEntity
import org.json.JSONObject
import java.text.Normalizer
import java.util.Locale

object LiveTranslatorLogic {
    const val WHISPER_LANGUAGE_AUTO = "auto"

    data class TurnRouting(
        val sourceSpeaker: Int,
        val targetSpeaker: Int,
        val sourceLanguage: String,
        val targetLanguage: String,
        val targetTtsLanguage: String,
        val detectedLanguage: String?,
        val usedDetectedLanguage: Boolean
    )

    fun nextSpeaker(currentSpeaker: Int): Int =
        if (currentSpeaker == LIVE_TRANSLATOR_SPEAKER_ONE) {
            LIVE_TRANSLATOR_SPEAKER_TWO
        } else {
            LIVE_TRANSLATOR_SPEAKER_ONE
        }

    fun sourceLanguage(template: LiveTranslatorTemplateEntity, speaker: Int): String =
        if (speaker == LIVE_TRANSLATOR_SPEAKER_ONE) template.speaker1Language else template.speaker2Language

    fun targetLanguage(template: LiveTranslatorTemplateEntity, speaker: Int): String =
        if (speaker == LIVE_TRANSLATOR_SPEAKER_ONE) template.speaker2Language else template.speaker1Language

    fun targetTtsLanguage(template: LiveTranslatorTemplateEntity, sourceSpeaker: Int): String =
        if (sourceSpeaker == LIVE_TRANSLATOR_SPEAKER_ONE) {
            template.speaker2TtsLanguage
        } else {
            template.speaker1TtsLanguage
        }

    fun resolvedTargetTtsLanguage(template: LiveTranslatorTemplateEntity, sourceSpeaker: Int, targetLanguage: String): String {
        val targetTag = normalizeLanguageTag(targetLanguage)
        val configuredTag = normalizeLanguageTag(targetTtsLanguage(template, sourceSpeaker))
        return when {
            targetTag == null -> configuredTag ?: "en"
            configuredTag == targetTag -> targetTag
            else -> targetTag
        }
    }

    fun ttsLanguageForTranslatedText(targetTtsLanguage: String, translatedText: String): String {
        val targetTag = normalizeLanguageTag(targetTtsLanguage) ?: "en"
        val likelyTag = likelyTextLanguage(translatedText)
        return likelyTag ?: targetTag
    }

    fun resolveTurnRouting(
        template: LiveTranslatorTemplateEntity,
        expectedSpeaker: Int,
        detectedLanguage: String?
    ): TurnRouting {
        val detectedTag = normalizeLanguageTag(detectedLanguage)
        val speaker1Tag = normalizeLanguageTag(template.speaker1Language)
        val speaker2Tag = normalizeLanguageTag(template.speaker2Language)
        val matchesSpeaker1 = detectedTag != null && detectedTag == speaker1Tag
        val matchesSpeaker2 = detectedTag != null && detectedTag == speaker2Tag
        val sourceSpeaker = when {
            matchesSpeaker1 && !matchesSpeaker2 -> LIVE_TRANSLATOR_SPEAKER_ONE
            matchesSpeaker2 && !matchesSpeaker1 -> LIVE_TRANSLATOR_SPEAKER_TWO
            else -> expectedSpeaker.coerceIn(LIVE_TRANSLATOR_SPEAKER_ONE, LIVE_TRANSLATOR_SPEAKER_TWO)
        }
        val targetSpeaker = nextSpeaker(sourceSpeaker)
        val sourceLanguage = sourceLanguage(template, sourceSpeaker)
        val targetLanguage = targetLanguage(template, sourceSpeaker)
        return TurnRouting(
            sourceSpeaker = sourceSpeaker,
            targetSpeaker = targetSpeaker,
            sourceLanguage = sourceLanguage,
            targetLanguage = targetLanguage,
            targetTtsLanguage = resolvedTargetTtsLanguage(template, sourceSpeaker, targetLanguage),
            detectedLanguage = detectedLanguage?.trim()?.takeIf { it.isNotBlank() },
            usedDetectedLanguage = sourceSpeaker != expectedSpeaker && (matchesSpeaker1 || matchesSpeaker2)
        )
    }

    fun languageCodeForTts(language: String): String =
        normalizeLanguageTag(language) ?: "en"

    fun normalizeLanguageTag(language: String?): String? {
        val raw = language?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val normalized = Normalizer.normalize(raw, Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .lowercase(Locale.US)
            .trim()
        if (normalized == WHISPER_LANGUAGE_AUTO || normalized == "automatic") return null
        Regex("""\(([a-z]{2,3})(?:[-_][a-z]{2})?\)""").find(normalized)
            ?.groupValues
            ?.getOrNull(1)
            ?.let { return languageAliases[it] ?: it.take(2) }
        languageAliases[normalized]?.let { return it }
        normalized
            .split(Regex("[\\s/_-]+"))
            .firstOrNull { it.isNotBlank() }
            ?.let { token ->
                languageAliases[token]?.let { return it }
                if (token.length in 2..3) return token.take(2)
            }
        return null
    }

    fun buildSystemPrompt(): String =
        "You are a live interpreter. Identify the spoken language silently, translate faithfully, and return only the translated utterance."

    fun buildUserPrompt(
        sourceLanguage: String,
        targetLanguage: String,
        transcript: String
    ): String = buildString {
        appendLine("The conversation languages are $sourceLanguage and $targetLanguage.")
        appendLine("First identify the language of the source text silently.")
        appendLine("If the source text is $sourceLanguage, translate it to $targetLanguage.")
        appendLine("If the source text is $targetLanguage, translate it to $sourceLanguage.")
        appendLine("If uncertain, prefer translating to $targetLanguage.")
        appendLine("Keep names, numbers, and intent intact.")
        appendLine("Return only the translated text, with no explanation.")
        appendLine()
        appendLine("Source text:")
        append(transcript.trim())
    }.trim()

    fun buildRetryUserPrompt(
        sourceLanguage: String,
        targetLanguage: String,
        transcript: String,
        previousOutput: String
    ): String = buildString {
        appendLine("The previous answer was not a valid $targetLanguage translation.")
        appendLine("The conversation languages are $sourceLanguage and $targetLanguage.")
        appendLine("Identify the source text language silently.")
        appendLine("If the source text is $sourceLanguage, translate it to $targetLanguage.")
        appendLine("If the source text is $targetLanguage, translate it to $sourceLanguage.")
        appendLine("If uncertain, prefer natural $targetLanguage text.")
        appendLine("Return only the translated text. Do not repeat the source text.")
        appendLine()
        appendLine("Source text:")
        appendLine(transcript.trim())
        appendLine()
        appendLine("Previous invalid answer:")
        append(previousOutput.trim())
    }.trim()

    fun shouldRetryTranslation(
        sourceLanguage: String,
        targetLanguage: String,
        transcript: String,
        translated: String
    ): Boolean {
        val sourceTag = normalizeLanguageTag(sourceLanguage) ?: return false
        val targetTag = normalizeLanguageTag(targetLanguage) ?: return false
        if (sourceTag == targetTag) return false
        val sourceText = normalizeTextForComparison(transcript)
        val translatedText = normalizeTextForComparison(translated)
        if (sourceText.isNotBlank() && sourceText == translatedText) return true
        if (sourceText.isNotBlank() && translatedText.isNotBlank()) {
            val sourceWords = sourceText.split(' ').filter { it.isNotBlank() }.toSet()
            val translatedWords = translatedText.split(' ').filter { it.isNotBlank() }.toSet()
            val overlap = sourceWords.intersect(translatedWords).size
            val smaller = minOf(sourceWords.size, translatedWords.size).coerceAtLeast(1)
            if (sourceWords.size >= 4 && translatedWords.size >= 4 && overlap.toFloat() / smaller >= 0.85f) {
                return true
            }
        }
        val likelyLanguage = likelyTextLanguage(translated)
        return likelyLanguage == sourceTag && likelyLanguage != targetTag
    }

    private fun normalizeTextForComparison(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .lowercase(Locale.US)
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()

    fun likelyTextLanguage(text: String): String? {
        val normalized = " ${normalizeTextForComparison(text)} "
        if (normalized.isBlank()) return null
        var spanishScore = 0
        var englishScore = 0
        if (Regex("[áéíóúñ¿¡]", RegexOption.IGNORE_CASE).containsMatchIn(text)) spanishScore += 3
        spanishStopWords.forEach { if (normalized.contains(" $it ")) spanishScore++ }
        englishStopWords.forEach { if (normalized.contains(" $it ")) englishScore++ }
        return when {
            spanishScore >= englishScore + 2 -> "es"
            englishScore >= spanishScore + 2 -> "en"
            else -> null
        }
    }

    fun templateSnapshotJson(template: LiveTranslatorTemplateEntity): String =
        JSONObject().apply {
            put("id", template.id)
            put("name", template.name)
            put("speaker1Language", template.speaker1Language)
            put("speaker2Language", template.speaker2Language)
            put("whisperModelPath", template.whisperModelPath)
            put("whisperThreads", template.whisperThreads)
            put("ttsModelPath", template.ttsModelPath)
            put("ttsModelName", template.ttsModelName)
            put("ttsLanguage", template.ttsLanguage)
            put("speaker1TtsLanguage", template.speaker1TtsLanguage)
            put("speaker2TtsLanguage", template.speaker2TtsLanguage)
            put("ttsVoiceName", template.ttsVoiceName)
            put("ttsSteps", template.ttsSteps)
            put("ttsSpeed", template.ttsSpeed.toDouble())
            put("backendEngine", template.backendEngine)
            put("llamaServerUrl", template.llamaServerUrl)
            put("llamaSwapUrl", template.llamaSwapUrl)
            put("llamaHost", template.llamaHost)
            put("llamaPort", template.llamaPort)
            put("llamaModelName", template.llamaModelName)
            put("ollamaUrl", template.ollamaUrl)
            put("ollamaHost", template.ollamaHost)
            put("ollamaPort", template.ollamaPort)
            put("ollamaModelName", template.ollamaModelName)
            put("liteRtModelId", template.liteRtModelId)
            put("liteRtBackend", template.liteRtBackend)
            put("liteRtMtpEnabled", template.liteRtMtpEnabled)
            put("contextSize", template.contextSize)
            put("maxTokens", template.maxTokens)
            put("temperature", template.temperature.toDouble())
            put("timeoutSeconds", template.timeoutSeconds)
            put("startSpeakingTimeoutSeconds", template.startSpeakingTimeoutSeconds)
            put("finishedTalkingTimeoutSeconds", template.finishedTalkingTimeoutSeconds)
        }.toString()

    private val languageAliases = mapOf(
        "ar" to "ar",
        "arabic" to "ar",
        "bg" to "bg",
        "bulgarian" to "bg",
        "cs" to "cs",
        "czech" to "cs",
        "da" to "da",
        "danish" to "da",
        "de" to "de",
        "deu" to "de",
        "german" to "de",
        "deutsch" to "de",
        "el" to "el",
        "greek" to "el",
        "en" to "en",
        "eng" to "en",
        "english" to "en",
        "ingles" to "en",
        "es" to "es",
        "spa" to "es",
        "spanish" to "es",
        "espanol" to "es",
        "castellano" to "es",
        "et" to "et",
        "estonian" to "et",
        "fi" to "fi",
        "finnish" to "fi",
        "fr" to "fr",
        "fra" to "fr",
        "fre" to "fr",
        "french" to "fr",
        "francais" to "fr",
        "hi" to "hi",
        "hindi" to "hi",
        "hr" to "hr",
        "croatian" to "hr",
        "hu" to "hu",
        "hungarian" to "hu",
        "id" to "id",
        "indonesian" to "id",
        "it" to "it",
        "italian" to "it",
        "ja" to "ja",
        "jpn" to "ja",
        "japanese" to "ja",
        "ko" to "ko",
        "kor" to "ko",
        "korean" to "ko",
        "lt" to "lt",
        "lithuanian" to "lt",
        "lv" to "lv",
        "latvian" to "lv",
        "nl" to "nl",
        "dutch" to "nl",
        "pl" to "pl",
        "polish" to "pl",
        "pt" to "pt",
        "por" to "pt",
        "portuguese" to "pt",
        "portugues" to "pt",
        "ro" to "ro",
        "romanian" to "ro",
        "ru" to "ru",
        "rus" to "ru",
        "russian" to "ru",
        "sk" to "sk",
        "slovak" to "sk",
        "sl" to "sl",
        "slovenian" to "sl",
        "sv" to "sv",
        "swedish" to "sv",
        "tr" to "tr",
        "turkish" to "tr",
        "uk" to "uk",
        "ukrainian" to "uk",
        "vi" to "vi",
        "vietnamese" to "vi"
    )

    private val spanishStopWords = setOf(
        "el", "la", "los", "las", "un", "una", "unos", "unas", "de", "del", "que", "y",
        "en", "es", "no", "si", "por", "para", "con", "como", "donde", "cuando", "hola",
        "gracias", "buenos", "buenas", "quiero", "necesito", "tengo", "puedo", "usted"
    )

    private val englishStopWords = setOf(
        "the", "a", "an", "of", "to", "and", "in", "is", "are", "not", "yes", "for",
        "with", "how", "where", "when", "hello", "thanks", "thank", "you", "want",
        "need", "have", "can", "please", "do", "does", "did"
    )
}
