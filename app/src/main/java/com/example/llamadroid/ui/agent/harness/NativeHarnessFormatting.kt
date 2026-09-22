package com.example.llamadroid.ui.agent.harness

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.util.UUID

internal fun harnessParseRecord(record: JsonObject): List<HarnessTranscriptItem> {
    val event = record.objectValue("event") ?: record
    return harnessParseEvent(event)?.let(::listOf).orEmpty()
}

/**
 * Parse the durable record window into structured transcript rows. The plain
 * transcript projection above remains the compatibility path for existing
 * callers; the controller stores the structured projection separately and the
 * native timeline renders it in place of matching plain event rows.
 */
internal fun harnessParseStructuredRecords(records: List<JsonObject>): List<NativeHarnessStructuredTranscriptItem> =
    parseNativeHarnessStructuredTranscript(records)

internal fun harnessParseStructuredRecord(record: JsonObject): List<NativeHarnessStructuredTranscriptItem> =
    harnessParseStructuredRecords(listOf(record))

internal fun harnessParseEvent(event: JsonObject): HarnessTranscriptItem? {
    val type = event.string("type") ?: return null
    val data = event["data"] ?: return null
    val text = harnessTextFromEvent(type, data)
    val usage = harnessUsageFromEvent(type, data)
    if (text.isBlank() && usage == null) return null
    val role = when {
        type.startsWith("user/") -> HarnessTranscriptRole.USER
        type.startsWith("assistant/") -> HarnessTranscriptRole.ASSISTANT
        type.startsWith("tool/") -> HarnessTranscriptRole.TOOL
        type.startsWith("system/") -> HarnessTranscriptRole.SYSTEM
        type.startsWith("request/") -> HarnessTranscriptRole.THINKING
        else -> HarnessTranscriptRole.SYSTEM
    }
    val seq = event.long("seq") ?: UUID.randomUUID().mostSignificantBits
    val messageId = if (type == "assistant/message") {
        (data as? JsonObject)?.objectValue("message")?.string("id")
    } else {
        null
    }
    return HarnessTranscriptItem(
        id = "event-$seq",
        role = role,
        text = boundedHarnessMessage(text),
        messageId = messageId,
        usage = usage,
        label = null,
        isStreaming = false,
        isExpandable = role == HarnessTranscriptRole.TOOL || role == HarnessTranscriptRole.THINKING || text.length > 2_000
    )
}

internal fun harnessParseTokenUsageProjection(value: JsonElement?): HarnessTokenUsageUi? {
    val usage = value?.jsonObjectOrNull() ?: return null
    val input = usage.long("uncachedInputTokens") ?: return null
    val output = usage.long("outputTokens") ?: return null
    return HarnessTokenUsageUi(
        inputTokens = input,
        outputTokens = output,
        cacheReadTokens = usage.long("cacheReadTokens") ?: 0L,
        cacheWriteTokens = usage.long("cacheWriteTokens") ?: 0L,
        cacheMetricsKnown = usage.containsKey("cacheReadTokens") && usage.containsKey("cacheWriteTokens"),
        totalTokens = usage.long("totalTokens")
    )
}

private fun harnessParseTurnUsage(value: JsonElement?): HarnessTokenUsageUi? {
    val usage = value?.jsonObjectOrNull() ?: return null
    val input = usage.long("inputTokens") ?: return null
    val output = usage.long("outputTokens") ?: return null
    return HarnessTokenUsageUi(
        inputTokens = input,
        outputTokens = output,
        cacheReadTokens = usage.long("cacheReadTokens") ?: 0L,
        cacheWriteTokens = usage.long("cacheWriteTokens") ?: 0L,
        cacheMetricsKnown = usage.containsKey("cacheReadTokens") && usage.containsKey("cacheWriteTokens"),
        reasoningTokens = usage.long("reasoningTokens"),
        totalTokens = usage.long("totalTokens")
    )
}

private fun harnessUsageFromEvent(type: String, value: JsonElement): HarnessTokenUsageUi? {
    val data = value as? JsonObject ?: return null
    harnessParseTurnUsage(data["usage"])?.let { return it }
    if (type != "assistant/message" && type != "assistant/attempt") return null
    return data.objectArray("stream").asReversed().firstNotNullOfOrNull { record ->
        val chunk = record.objectValue("chunk") ?: return@firstNotNullOfOrNull null
        if (chunk.string("type") != "usage") return@firstNotNullOfOrNull null
        harnessParseTurnUsage(chunk["usage"])
    }
}

private fun harnessTextFromEvent(type: String, value: JsonElement): String {
    val objectValue = value as? JsonObject
    if (type == "assistant/message") {
        val message = objectValue?.objectValue("message") ?: objectValue
        val content = message?.get("content")
        if (content != null) return harnessTextFrom(content)
    }
    if (type == "user/message") {
        val content = objectValue?.get("content") ?: objectValue?.objectValue("message")?.get("content")
        if (content != null) return harnessTextFrom(content)
    }
    return harnessTextFrom(value)
}

internal fun harnessTextFrom(value: JsonElement): String = when (value) {
    JsonNull -> ""
    is JsonPrimitive -> value.contentOrNull.orEmpty()
    is JsonArray -> value.joinToString("\n") { harnessTextFrom(it) }.trim()
    is JsonObject -> listOf("text", "content", "message", "output", "summary", "title", "chunk", "name")
        .asSequence()
        .mapNotNull { key -> value[key] }
        .map(::harnessTextFrom)
        .firstOrNull { it.isNotBlank() }
        ?: value.entries.joinToString(" ") { (key, child) -> "$key: ${harnessTextFrom(child)}" }.trim()
}

internal fun harnessProjectFromCwd(cwd: String?): String {
    val clean = cwd?.trimEnd('/')?.substringAfterLast('/')
    return clean?.takeIf { it.isNotBlank() } ?: "."
}

internal fun harnessDefaultSessionTitle(id: String, cwd: String?): String =
    harnessProjectFromCwd(cwd).takeIf { it != "." } ?: id.takeLast(8).ifBlank { "." }

internal fun harnessLatestSequence(transcript: List<HarnessTranscriptItem>): Long =
    transcript.maxOfOrNull { harnessSequenceFromId(it.id) } ?: 0L

internal fun harnessOldestSequence(transcript: List<HarnessTranscriptItem>): Long =
    transcript.minOfOrNull { harnessSequenceFromId(it.id) } ?: 0L

private fun harnessSequenceFromId(id: String): Long = id.substringAfterLast('-').toLongOrNull() ?: 0L

internal fun harnessJsonAtPath(value: JsonElement?, path: List<String>): JsonElement? =
    path.fold(value) { current, segment -> current?.jsonObjectOrNull()?.get(segment) }

internal fun harnessProviderFieldPath(bindingPrefix: String, key: String): List<String>? {
    val relative = key.removePrefix("$bindingPrefix.")
    if (relative == key || relative.isBlank()) return null
    return harnessSchemaPathFromKey(relative)
}
