package com.example.llamadroid.service

import kotlinx.coroutines.*

private val NativeChatCitationLinkPattern = Regex(
    """\[((?:\\.|[^\]\n])+)]\(\s*<?((?:https?://[^\s)>]+)|(?:kb://chunk/\d+))>?(?:\s+"[^"]*")?\s*\)""",
    RegexOption.IGNORE_CASE
)

internal data class NativeChatSourceCitation(
    val label: String,
    val url: String,
    val markdown: String
)

internal data class LiteRtToolCallExtraction(
    val visibleContent: String,
    val payloads: List<String>
)

private val LiteRtToolCallTagPattern = Regex(
    pattern = """(?is)(?:<\s*(?:tool[_-]?)?call\s*>|_call\s*>)\s*([\s\S]*?)\s*(?:<\s*/\s*(?:tool[_-]?)?call\s*>|</_call\s*>)"""
)

internal fun extractLiteRtToolCallPayloads(text: String): LiteRtToolCallExtraction {
    val payloads = LiteRtToolCallTagPattern.findAll(text)
        .mapNotNull { match ->
            match.groupValues.getOrNull(1)
                ?.trim()
                ?.takeIf { payload -> payload.startsWith("{") || payload.startsWith("[") }
        }
        .toList()
    return LiteRtToolCallExtraction(
        visibleContent = text.replace(LiteRtToolCallTagPattern, "").trim(),
        payloads = payloads
    )
}

internal fun extractNativeChatSourceCitations(toolOutput: String): List<NativeChatSourceCitation> {
    return toolOutput
        .lineSequence()
        .filter { line ->
            line.contains("citation", ignoreCase = true) ||
                line.trimStart().matches(Regex("""^\d+\.\s+\[.*"""))
        }
        .flatMap { line ->
            NativeChatCitationLinkPattern.findAll(line).map { match ->
                val label = unescapeNativeChatMarkdownLabel(match.groupValues[1])
                val url = match.groupValues[2]
                NativeChatSourceCitation(
                    label = label,
                    url = url,
                    markdown = "[${escapeNativeChatMarkdownLabel(label)}]($url)"
                )
            }
        }
        .distinctBy { it.url }
        .toList()
}

internal fun applyNativeChatSourceCitationFallback(
    content: String,
    citations: List<NativeChatSourceCitation>
): String {
    val uniqueCitations = citations.distinctBy { it.url }
    if (uniqueCitations.isEmpty() || content.isBlank()) return content

    var updated = Regex("""\[(\d+)](?!\()""").replace(content) { match ->
        val sourceIndex = match.groupValues[1].toIntOrNull()?.minus(1) ?: return@replace match.value
        val citation = uniqueCitations.getOrNull(sourceIndex) ?: return@replace match.value
        "[${match.groupValues[1]}](${citation.url})"
    }

    uniqueCitations.forEach { citation ->
        val label = citation.label.trim()
        if (label.isBlank()) return@forEach
        updated = Regex("""\[${Regex.escape(label)}](?!\()""").replace(updated) { citation.markdown }
    }

    val hasSourceLink = uniqueCitations.any { citation -> updated.contains(citation.url) }
    if (!hasSourceLink) {
        updated = buildString {
            append(updated.trimEnd())
            appendLine()
            appendLine()
            appendLine("Sources:")
            uniqueCitations.take(8).forEachIndexed { index, citation ->
                appendLine("${index + 1}. ${citation.markdown}")
            }
        }.trimEnd()
    }

    return updated
}

private fun unescapeNativeChatMarkdownLabel(label: String): String {
    val result = StringBuilder()
    var escaped = false
    for (char in label) {
        if (escaped) {
            result.append(char)
            escaped = false
        } else if (char == '\\') {
            escaped = true
        } else {
            result.append(char)
        }
    }
    if (escaped) result.append('\\')
    return result.toString()
}

private fun escapeNativeChatMarkdownLabel(label: String): String =
    label
        .replace("\\", "\\\\")
        .replace("[", "\\[")
        .replace("]", "\\]")
