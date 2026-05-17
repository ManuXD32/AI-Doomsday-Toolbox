package com.example.llamadroid.tama.game

import com.example.llamadroid.service.PDFSummaryLogic
import com.example.llamadroid.tama.db.TamaSummaryEntity
import org.json.JSONArray
import org.json.JSONObject

internal object TamaTranscriptionStatus {
    const val PENDING = "pending"
    const val COMPLETED = "completed"
    const val FAILED = "failed"
}

internal data class TamaRetrievalNote(
    val text: String,
    val tags: List<String> = emptyList()
)

internal data class TamaStructuredMemory(
    val shortTermSummary: String,
    val longTermSummary: String,
    val retrievalNotes: List<TamaRetrievalNote>
) {
    fun toDisplaySummary(): String {
        val notesBlock = retrievalNotes
            .map { "- ${it.text}" }
            .joinToString("\n")
            .ifBlank { "- None yet." }
        return buildString {
            appendLine("[Short-term Memory]")
            appendLine(shortTermSummary.ifBlank { "No short-term memory yet." })
            appendLine()
            appendLine("[Long-term Memory]")
            appendLine(longTermSummary.ifBlank { "No long-term memory yet." })
            appendLine()
            appendLine("[Retrieval Notes]")
            append(notesBlock)
        }.trim()
    }
}

internal fun sanitizeTamaModelOutput(raw: String): String {
    if (raw.isBlank()) return ""
    var cleaned = raw
        .replace(Regex("(?is)<(think|thinking|reasoning)>.*?</\\1>"), " ")
        .replaceReasoningBlock(
            start = Regex("(?im)^\\s*\\[Start thinking]\\s*$"),
            end = Regex("(?im)^\\s*\\[End thinking]\\s*$")
        )
        .replaceReasoningBlock(
            start = Regex("(?im)^\\s*(Thinking Process|Reasoning|Thoughts|Analysis)\\s*:\\s*$"),
            end = Regex("(?im)^\\s*(Final answer|Answer|Dream|Story|Content|Summary|Prompt|Closing|JSON)\\s*:\\s*")
        )
        .replace(Regex("(?im)^\\s*\\[End thinking]\\s*$"), " ")
        .trim()

    val reasoningPrefixes = listOf(
        "reasoning_content",
        "reasoning:",
        "thinking:",
        "thoughts:",
        "analysis:"
    )
    cleaned = cleaned
        .lineSequence()
        .mapNotNull { line ->
            val normalized = line.trim().lowercase()
            if (reasoningPrefixes.any { normalized.startsWith(it) }) {
                null
            } else {
                stripFinalAnswerPrefix(line)
            }
        }
        .joinToString("\n")
        .trim()

    return PDFSummaryLogic.cleanLlamaOutput(cleaned).trim()
}

private fun String.replaceReasoningBlock(start: Regex, end: Regex): String {
    val lines = lineSequence().toList()
    val output = mutableListOf<String>()
    var skipping = false
    for (line in lines) {
        when {
            !skipping && start.containsMatchIn(line) -> {
                skipping = true
            }
            skipping && end.containsMatchIn(line) -> {
                skipping = false
                val endMarker = end.find(line)?.groupValues?.getOrNull(1).orEmpty().lowercase()
                val finalLine = if (endMarker == "prompt") line else end.replace(line, "")
                output.add(finalLine.trim())
            }
            !skipping -> output.add(line)
        }
    }
    return output.joinToString("\n")
}

private fun stripFinalAnswerPrefix(line: String): String {
    return line.replace(
        Regex("(?i)^\\s*(Final answer|Answer)\\s*:\\s*"),
        ""
    )
}

internal fun sanitizeTamaJsonPayload(raw: String): String {
    val cleaned = sanitizeTamaModelOutput(raw)
    extractFirstJsonObject(cleaned)?.let { return it }
    extractFirstJsonArray(cleaned)?.let { return it }
    return cleaned
}

internal fun parseStructuredMemory(raw: String): TamaStructuredMemory {
    val payload = sanitizeTamaJsonPayload(raw)
    val json = JSONObject(payload)
    val notes = json.optJSONArray("retrievalNotes")
        ?.let(::parseRetrievalNotes)
        ?: emptyList()
    return TamaStructuredMemory(
        shortTermSummary = json.optString("shortTermSummary").trim(),
        longTermSummary = json.optString("longTermSummary").trim(),
        retrievalNotes = notes
    )
}

internal fun TamaSummaryEntity.toStructuredMemory(): TamaStructuredMemory {
    val notes = runCatching { parseRetrievalNotes(JSONArray(retrievalNotesJson)) }.getOrDefault(emptyList())
    if (shortTermSummary.isNotBlank() || longTermSummary.isNotBlank() || notes.isNotEmpty()) {
        return TamaStructuredMemory(
            shortTermSummary = shortTermSummary,
            longTermSummary = longTermSummary,
            retrievalNotes = notes
        )
    }
    return TamaStructuredMemory(
        shortTermSummary = "",
        longTermSummary = summary,
        retrievalNotes = emptyList()
    )
}

internal fun TamaStructuredMemory.filteredRetrievalNotes(hints: Set<String>, maxItems: Int = 8): List<TamaRetrievalNote> {
    if (retrievalNotes.isEmpty()) return emptyList()
    val normalizedHints = hints.map { it.trim().lowercase() }.filter { it.isNotBlank() }.toSet()
    if (normalizedHints.isEmpty()) return retrievalNotes.take(maxItems)
    val prioritized = retrievalNotes.filter { note ->
        note.tags.any { tag ->
            val normalizedTag = tag.trim().lowercase()
            normalizedTag in normalizedHints || normalizedHints.any { it.contains(normalizedTag) || normalizedTag.contains(it) }
        } || normalizedHints.any { hint ->
            note.text.lowercase().contains(hint)
        }
    }
    return (prioritized + retrievalNotes)
        .distinctBy { it.text }
        .take(maxItems)
}

private fun parseRetrievalNotes(array: JSONArray): List<TamaRetrievalNote> {
    val notes = mutableListOf<TamaRetrievalNote>()
    for (index in 0 until array.length()) {
        when (val item = array.opt(index)) {
            is JSONObject -> {
                val text = item.optString("text")
                    .ifBlank { item.optString("note") }
                    .ifBlank { item.optString("value") }
                    .trim()
                if (text.isBlank()) continue
                val tags = mutableListOf<String>()
                val tagsArray = item.optJSONArray("tags")
                if (tagsArray != null) {
                    for (tagIndex in 0 until tagsArray.length()) {
                        tagsArray.optString(tagIndex).trim().takeIf { it.isNotBlank() }?.let(tags::add)
                    }
                }
                item.optString("category").trim().takeIf { it.isNotBlank() }?.let(tags::add)
                notes += TamaRetrievalNote(text = text, tags = tags.distinct())
            }
            is String -> {
                item.trim().takeIf { it.isNotBlank() }?.let { notes += TamaRetrievalNote(text = it) }
            }
        }
    }
    return notes
}

private fun extractFirstJsonObject(raw: String): String? {
    val start = raw.indexOf('{')
    if (start < 0) return null
    var depth = 0
    var inString = false
    var escaped = false
    for (index in start until raw.length) {
        val char = raw[index]
        if (escaped) {
            escaped = false
            continue
        }
        when (char) {
            '\\' -> if (inString) escaped = true
            '"' -> inString = !inString
            '{' -> if (!inString) depth++
            '}' -> if (!inString) {
                depth--
                if (depth == 0) {
                    return raw.substring(start, index + 1)
                }
            }
        }
    }
    return null
}

private fun extractFirstJsonArray(raw: String): String? {
    val start = raw.indexOf('[')
    if (start < 0) return null
    var depth = 0
    var inString = false
    var escaped = false
    for (index in start until raw.length) {
        val char = raw[index]
        if (escaped) {
            escaped = false
            continue
        }
        when (char) {
            '\\' -> if (inString) escaped = true
            '"' -> inString = !inString
            '[' -> if (!inString) depth++
            ']' -> if (!inString) {
                depth--
                if (depth == 0) {
                    return raw.substring(start, index + 1)
                }
            }
        }
    }
    return null
}
