package com.example.llamadroid.audio

import java.util.Locale

/**
 * Splits source text at natural boundaries before a model-specific adapter
 * invokes native or ONNX inference. The splitter is deterministic and keeps
 * whitespace out of the generated chunks so callers can safely persist them.
 */
object AudioTextChunker {
    fun chunk(text: String, maxCharacters: Int = 800, overlap: Int = 0): List<String> {
        val clean = text.replace("\u0000", " ").replace(Regex("[\\t ]+"), " ").trim()
        if (clean.isBlank()) return emptyList()
        val limit = maxCharacters.coerceAtLeast(1)
        // Overlap is deliberately ignored for speech. Repeating context in a
        // TTS chunk repeats words in the rendered audio. Keep the parameter in
        // the API for old draft values, but never duplicate spoken text.
        val requestedOverlap = 0
        if (clean.length <= limit) return listOf(clean)

        val chunks = mutableListOf<String>()
        var cursor = 0
        while (cursor < clean.length) {
            val end = (cursor + limit).coerceAtMost(clean.length)
            val boundary = if (end < clean.length) findBoundary(clean, cursor, end) else end
            val safeBoundary = if (
                boundary in 1 until clean.length &&
                Character.isHighSurrogate(clean[boundary - 1]) &&
                Character.isLowSurrogate(clean[boundary])
            ) boundary - 1 else boundary
            val chunkEnd = safeBoundary.coerceAtLeast((cursor + 1).coerceAtMost(clean.length))
            val value = clean.substring(cursor, chunkEnd).trim()
            if (value.isNotBlank()) chunks += value
            if (chunkEnd >= clean.length) break

            // Overlap is useful for adapters that need context. The default is
            // zero because repeating words would otherwise repeat spoken audio.
            val next = (chunkEnd - requestedOverlap).coerceAtLeast(cursor + 1)
            cursor = next
        }
        return chunks
    }

    fun count(text: String?, maxCharacters: Int = 800, overlap: Int = 0): Int =
        text?.let { chunk(it, maxCharacters, overlap).size } ?: 0

    private fun findBoundary(text: String, start: Int, preferredEnd: Int): Int {
        val searchStart = (preferredEnd - preferredEnd.coerceAtMost(160)).coerceAtLeast(start + 1)
        val punctuation = ".!?;:\n"
        for (index in preferredEnd downTo searchStart) {
            if (text[index - 1] in punctuation) return index
        }
        for (index in preferredEnd downTo searchStart) {
            if (text[index - 1].isWhitespace()) return index
        }
        return preferredEnd
    }
}

internal fun normalizeAudioLanguage(language: String?): String =
    language?.trim()?.lowercase(Locale.US)?.ifBlank { "en" } ?: "en"
