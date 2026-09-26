package com.example.llamadroid.ui.agent.harness

import java.net.URI
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** Destinations retain their authored path; the selected Session resolves the filesystem scope. */
sealed interface HarnessInlineTarget {
    data class File(val path: String, val line: Int? = null) : HarnessInlineTarget
    data class Skill(val name: String) : HarnessInlineTarget
    data class External(val url: String) : HarnessInlineTarget
}

internal data class HarnessInlineLink(val start: Int, val end: Int, val target: HarnessInlineTarget)

/** Mirrors alpha2 ui-primitives/markdown/file-link.ts; decoding must not turn '+' into a space. */
internal fun parseHarnessInlineDestination(value: String): HarnessInlineTarget? {
    if (value.length !in 1..8_192 || value.any { it.code < 32 || it.code == 127 }) return null
    val uri = runCatching { URI(value) }.getOrNull()
    if (uri?.scheme?.lowercase() in setOf("http", "https")) {
        return if (!uri?.host.isNullOrBlank() && uri?.rawUserInfo == null) HarnessInlineTarget.External(value) else null
    }
    if (uri?.scheme?.lowercase() == "mailto" && !uri.rawSchemeSpecificPart.isNullOrBlank()) {
        return HarnessInlineTarget.External(value)
    }
    val destination = value.substringBefore('#')
    if ('?' in destination) return null
    val path = runCatching { decodeHarnessFileDestination(destination) }.getOrNull() ?: return null
    if (path.isEmpty() || path.any { it.code < 32 || it.code == 127 } ||
        path.startsWith("//") || path.startsWith("\\\\") ||
        Regex("^[A-Za-z][A-Za-z0-9+.-]*:").containsMatchIn(path)
    ) return null
    if ('#' !in value) return HarnessInlineTarget.File(path)
    val fragment = Regex("^L([1-9][0-9]*)(?:-L([1-9][0-9]*))?$")
        .matchEntire(value.substringAfter('#')) ?: return null
    val first = fragment.groupValues[1].toIntOrNull() ?: return null
    val last = fragment.groupValues[2].takeIf(String::isNotEmpty)?.toIntOrNull()
        ?: if (fragment.groupValues[2].isEmpty()) first else return null
    if (last < first) return null
    return HarnessInlineTarget.File(path, first)
}

private fun decodeHarnessFileDestination(value: String): String = buildString {
    var cursor = 0
    while (cursor < value.length) {
        if (value[cursor] != '%') { append(value[cursor++]); continue }
        val bytes = ByteArrayOutputStream()
        while (cursor < value.length && value[cursor] == '%') {
            require(cursor + 2 < value.length)
            bytes.write(value.substring(cursor + 1, cursor + 3).toInt(16))
            cursor += 3
        }
        append(Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes.toByteArray())))
    }
}

/**
 * Bounded annotation of the existing text preview, without constructing a Markdown layout tree.
 * Authored Markdown links, HTTP autolinks and sent @file references remain clickable in place.
 * Only names from the message's actual skill injections decorate slash tokens.
 */
internal fun harnessInlineLinks(
    text: String,
    userReferences: Boolean = false,
    skillNames: Set<String> = emptySet(),
): List<HarnessInlineLink> {
    val links = ArrayList<HarnessInlineLink>()
    val maximum = minOf(text.length, 24_000)
    val code = BooleanArray(maximum)
    var delimiter = 0
    var cursor = 0
    while (cursor < maximum) {
        if (text[cursor].code == 96) {
            val end = (cursor until maximum).firstOrNull { text[it].code != 96 } ?: maximum
            val run = end - cursor
            for (index in cursor until end) code[index] = true
            delimiter = if (delimiter == 0) run else if (delimiter == run) 0 else delimiter
            cursor = end
        } else {
            code[cursor] = delimiter != 0
            cursor++
        }
    }
    fun add(start: Int, end: Int, target: HarnessInlineTarget?) {
        if (target == null || end > maximum || start >= end || links.size >= 128 ||
            code[start] || links.any { start < it.end && end > it.start }
        ) return
        links += HarnessInlineLink(start, end, target)
    }
    cursor = 0
    while (cursor < maximum && links.size < 128) {
        if (text[cursor] != '[' || code[cursor]) { cursor++; continue }
        val labelEnd = text.indexOf(']', cursor + 1)
        if (labelEnd < 0 || labelEnd + 1 >= maximum || text[labelEnd + 1] != '(') { cursor++; continue }
        var end = labelEnd + 2
        var depth = 1
        var escaped = false
        while (end < maximum && depth > 0) {
            val char = text[end]
            if (escaped) escaped = false
            else if (char == '\\') escaped = true
            else if (char == '(') depth++
            else if (char == ')') depth--
            end++
        }
        if (depth == 0) {
            var value = text.substring(labelEnd + 2, end - 1).trim()
            value = if (value.startsWith('<')) value.substringAfter('<').substringBefore('>')
                else value.substringBefore(" \"").substringBefore(" '")
            add(cursor, end, parseHarnessInlineDestination(value))
            cursor = end
        } else cursor++
    }
    val definitions = Regex("(?m)^ {0,3}\\[([^]\\n]+)\\]:[\\t ]*(<[^>\\n]+>|\\S+)")
        .findAll(text.take(maximum)).filter { !code[it.range.first] }.toList()
    fun referenceKey(value: String) = value.trim().replace(Regex("\\s+"), " ").lowercase()
    val references = definitions.associate { definition ->
        referenceKey(definition.groupValues[1]) to
            parseHarnessInlineDestination(definition.groupValues[2].removeSurrounding("<", ">"))
    }
    Regex("\\[([^]\\n]+)\\](?:\\[([^]\\n]*)\\])?").findAll(text.take(maximum)).forEach { match ->
        if (definitions.none { match.range.first in it.range }) {
            val reference = match.groupValues[2].ifBlank { match.groupValues[1] }
            add(match.range.first, match.range.last + 1, references[referenceKey(reference)])
        }
    }
    Regex("https?://[^\\s<>]+", RegexOption.IGNORE_CASE).findAll(text.take(maximum)).forEach { match ->
        val value = match.value.trimEnd('.', ',', ';', ':', '!', '?', ')', ']')
        add(match.range.first, match.range.first + value.length, parseHarnessInlineDestination(value))
    }
    if (userReferences) {
        Regex("(^|\\s)(/[-\\w]+(?=\\s|$)|@\"[^\"\\n]+\"|@[^\\s]+)").findAll(text.take(maximum)).forEach { match ->
            val raw = match.groupValues[2]
            val token = if (raw.startsWith("@\"")) raw else raw.trimEnd('.', ',', ';', ':', '!', '?', '，', '。', '；', '：', '！', '？')
            val start = match.range.first + match.groupValues[1].length
            when {
                token.startsWith('/') && token.drop(1) in skillNames ->
                    add(start, start + token.length, HarnessInlineTarget.Skill(token.drop(1)))
                token.startsWith('@') && !token.startsWith("@[") -> {
                    val path = token.drop(1).removeSurrounding("\"")
                    if (!path.endsWith('/')) add(start, start + token.length, HarnessInlineTarget.File(path).takeIf { path.isNotBlank() })
                }
            }
        }
    }
    return links.sortedBy(HarnessInlineLink::start)
}
