package com.example.llamadroid.service

import org.json.JSONObject
import java.util.LinkedHashSet
import java.util.Locale

/**
 * The tool results in this set carry workflow state rather than disposable
 * tool output.  Keep the set small and explicit so an ordinary file or shell
 * result is still eligible for normal history compaction.
 */
private val CONTROL_BEARING_TOOL_NAMES = setOf(
    "question",
    "answer_question",
    "call_agent",
    "agent_report_read",
    "project_state_read",
    "project_order_read",
    "plan_read",
    "propose_plan",
    "report_progress",
    "todo_read",
    "todo_write",
    "todo_reconcile",
    "todo_transition",
    "finish_task",
    "reflection",
    "memory_gate",
    "needs_direction",
    "retryable_needs_direction",
    "continue"
)

private val EVIDENCE_BEARING_TOOL_NAMES = setOf(
    "web_search",
    "kiwix_search",
    "fetch_url",
    "search_page",
    "kb_search",
    "kb_read_chunk"
)

private val OPAQUE_CONTROL_ID_KEYS = linkedMapOf(
    "report_id" to "reportIds",
    "reportid" to "reportIds",
    "todo_id" to "todoIds",
    "todoid" to "todoIds",
    "question_id" to "questionIds",
    "questionid" to "questionIds",
    "invocation_id" to "invocationIds",
    "invocationid" to "invocationIds",
    "root_turn_id" to "rootTurnIds",
    "rootturnid" to "rootTurnIds",
    "action_id" to "actionIds",
    "actionid" to "actionIds",
    "continuation_id" to "continuationIds",
    "continuationid" to "continuationIds",
    "plan_id" to "planIds",
    "planid" to "planIds",
    "tool_call_id" to "toolCallIds",
    "toolcallid" to "toolCallIds"
)

private val CONTROL_LINE_PATTERN = Regex(
    "^\\s*(status|tool|summary|important_output|next_hint|" +
        "error_code|report_id|todo_id|question_id|invocation_id|" +
        "root_turn_id|action_id|continuation_id|plan_id|tool_call_id)" +
        "\\s*:\\s*(.*)$",
    RegexOption.IGNORE_CASE
)

private val OPAQUE_ID_VALUE_PATTERN = Regex(
    "[A-Za-z0-9][A-Za-z0-9._:/-]{0,159}"
)

private val OPAQUE_ID_KEY_PATTERN = Regex(
    "(?i)[\\\"']?(report[_-]?id|todo[_-]?id|question[_-]?id|" +
        "invocation[_-]?id|root[_-]?turn[_-]?id|action[_-]?id|" +
        "continuation[_-]?id|plan[_-]?id|tool[_-]?call[_-]?id)[\\\"']?\\s*[:=]\\s*" +
        "[\\\"']?([A-Za-z0-9][A-Za-z0-9._:/-]{0,159})"
)

private val OPAQUE_ID_LABEL_PATTERN = Regex(
    "(?i)\\b(?:report|todo|question|invocation|root[-_ ]?turn|action|" +
    "continuation|plan|tool[-_ ]?call)[-_ ]id\\s*[:=]\\s*" +
        "[\\\"']?([A-Za-z0-9][A-Za-z0-9._:/-]{0,159})"
)

private val EVIDENCE_RESULT_NUMBER_PATTERN = Regex(
    "^\\s*(\\d+)[.)]\\s+(.+)$"
)

private val EVIDENCE_URL_PATTERN = Regex(
    "(?i)\\b(?:source_url|url)\\s*:\\s*(https?://\\S+)"
)

private val EVIDENCE_MARKER_PATTERN = Regex(
    "(?i)\\b(?:source_citations?|citation_token|source_url|citation)\\s*:"
)

private val EVIDENCE_MARKDOWN_LINK_PATTERN = Regex(
    "\\[[^\\]]{1,240}]\\((https?://[^)\\s]+)\\)"
)

private val EVIDENCE_FIELD_PATTERN = Regex(
    "(?i)^\\s*(summary|finding|description|result)\\s*:\\s*(.*)$"
)


private const val CONTROL_PACKET_CAPACITY_FAILURE_MARKER =
    "control_state_does_not_fit: true"

private val REPORT_SUMMARY_FIELD_PATTERN = Regex(
    "^\\s*-\\s*([A-Za-z][A-Za-z0-9_]*)\\s*:\\s*(.*)$"
)

private val REPORT_SUMMARY_FIELD_ORDER = listOf(
    "status",
    "error_code",
    "sources",
    "facts",
    "findings",
    "recommendations",
    "uncertainties",
    "conflicts",
    "research_question",
    "summary",
    "next_hint"
)

private val REPORT_EVIDENCE_KEYS = listOf(
    "changed_files",
    "command_ids",
    "line_references",
    "memory_files_touched"
)

/** Opaque workflow IDs found in a control envelope. */
data class AgentControlOpaqueIds(
    val reportIds: Set<String> = emptySet(),
    val todoIds: Set<String> = emptySet(),
    val questionIds: Set<String> = emptySet(),
    val invocationIds: Set<String> = emptySet(),
    val rootTurnIds: Set<String> = emptySet(),
    val actionIds: Set<String> = emptySet(),
    val continuationIds: Set<String> = emptySet(),
    val planIds: Set<String> = emptySet(),
    val toolCallIds: Set<String> = emptySet()
) {
    val all: Set<String>
        get() = buildSet {
            addAll(reportIds)
            addAll(todoIds)
            addAll(questionIds)
            addAll(invocationIds)
            addAll(rootTurnIds)
            addAll(actionIds)
            addAll(continuationIds)
            addAll(planIds)
            addAll(toolCallIds)
        }

    fun isEmpty(): Boolean = all.isEmpty()
}

/**
 * Returns true for a tool whose result can change the next workflow action or
 * carries an authoritative user decision.  The nullable overload is useful
 * for messages whose tool name is supplied by a legacy persistence record.
 */
fun isControlBearingTool(toolName: String?): Boolean =
    toolName
        ?.trim()
        ?.lowercase(Locale.ROOT)
        ?.let(CONTROL_BEARING_TOOL_NAMES::contains)
        ?: false

/**
 * Returns true for tools whose output is useful as compact external or
 * knowledge-base evidence. This is intentionally independent from workflow
 * control: a successful search result is evidence, while a failed search
 * result is handled by the control-envelope path through its error status.
 */
fun isEvidenceBearingTool(toolName: String?): Boolean =
    toolName
        ?.trim()
        ?.lowercase(Locale.ROOT)
        ?.let(EVIDENCE_BEARING_TOOL_NAMES::contains)
        ?: false

/** True for typed evidence tools or legacy rows with citation markers. */
fun isEvidenceBearingTool(message: AgentService.Companion.ChatMessage): Boolean {
    val toolName = message.toolName ?: message.pendingToolCall?.name
    return isEvidenceBearingTool(toolName) ||
        EVIDENCE_MARKER_PATTERN.containsMatchIn(message.content)
}


/** True only for the explicit durable-state capacity marker. */
fun isControlPacketCapacityFailure(packet: String): Boolean =
    packet.lineSequence().any { line ->
        line.trim()
            .removePrefix("-")
            .trim()
            .equals(CONTROL_PACKET_CAPACITY_FAILURE_MARKER, ignoreCase = true)
    }

private fun compactReportEvidenceJson(raw: String): String = runCatching {
    val json = JSONObject(raw)
    REPORT_EVIDENCE_KEYS.mapNotNull { key ->
        val values = json.optJSONArray(key)?.let { array ->
            buildList {
                for (index in 0 until array.length()) {
                    val value = array.optString(index).trim()
                    if (value.isNotBlank() && value != "null") add(value)
                }
            }
        }.orEmpty()
        values.takeIf { it.isNotEmpty() }?.let {
            "$key=${it.take(4).joinToString(",")}"
        }
    }.joinToString("; ")
}.getOrDefault("")

/**
 * Projects a durable work report into a compact, evidence-bearing packet row.
 * Research sources and facts are selected before generic prose, and stored
 * evidence metadata remains available when the report summary is long.
 */
fun projectAgentReportForControlPacket(
    summary: String,
    evidenceJson: String = "{}",
    maxChars: Int = 640
): String {
    val limit = maxChars.coerceAtLeast(1)
    val fields = linkedMapOf<String, String>()
    summary.replace("\r", "").lineSequence().forEach { rawLine ->
        val match = REPORT_SUMMARY_FIELD_PATTERN.matchEntire(rawLine)
            ?: return@forEach
        val key = match.groupValues[1].lowercase(Locale.ROOT)
        if (key !in REPORT_SUMMARY_FIELD_ORDER) return@forEach
        val value = match.groupValues[2].trim()
        if (value.isNotBlank()) fields.putIfAbsent(key, value)
    }
    if (fields.isEmpty() && summary.isNotBlank()) {
        fields["summary"] = summary
    }

    val candidates = REPORT_SUMMARY_FIELD_ORDER.mapNotNull { key ->
        fields[key]?.let { key to it }
    }.toMutableList()
    compactReportEvidenceJson(evidenceJson)
        .takeIf(String::isNotBlank)
        ?.let { candidates += "evidence" to it }

    val selected = mutableListOf<String>()
    candidates.forEach { (key, rawValue) ->
        val used = selected.joinToString("; ").length
        val separatorLength = if (selected.isEmpty()) 0 else 2
        val available = limit - used - separatorLength - key.length - 2
        if (available <= 0) return@forEach
        val fieldLimit = when (key) {
            "sources" -> 260
            "facts", "findings", "recommendations" -> 280
            "uncertainties", "conflicts" -> 180
            "research_question" -> 200
            "summary", "next_hint" -> 220
            else -> 120
        }
        val value = compactEvidenceField(rawValue, minOf(fieldLimit, available))
        if (value.isNotBlank()) selected += "$key: $value"
    }

    return selected.joinToString("; ")
        .ifBlank { compactEvidenceField(summary, limit) }
        .take(limit)
}

/** Extract typed opaque IDs without asking the model to reproduce them. */
fun extractAgentControlOpaqueIds(content: String): AgentControlOpaqueIds {
    if (content.isBlank()) return AgentControlOpaqueIds()

    val buckets = OPAQUE_CONTROL_ID_KEYS.values
        .distinct()
        .associateWith { LinkedHashSet<String>() }
        .toMutableMap()

    fun add(key: String, value: String) {
        val normalizedKey = key
            .trim()
            .lowercase(Locale.ROOT)
            .replace('-', '_')
        val bucket = OPAQUE_CONTROL_ID_KEYS[normalizedKey]
            ?: OPAQUE_CONTROL_ID_KEYS[normalizedKey.replace("_", "")]
            ?: return
        val cleaned = value.trim().trim('"', '\'', ',', '`')
        if (cleaned.isNotBlank() && OPAQUE_ID_VALUE_PATTERN.matches(cleaned)) {
            buckets.getValue(bucket).add(cleaned)
        }
    }

    OPAQUE_ID_KEY_PATTERN.findAll(content).forEach { match ->
        add(match.groupValues[1], match.groupValues[2])
    }
    OPAQUE_ID_LABEL_PATTERN.findAll(content).forEach { match ->
        val label = match.value.substringBeforeLast(match.groupValues[1])
        val key = label
            .trim()
            .substringBefore(':')
            .trim()
            .replace(' ', '_')
        add(key, match.groupValues[1])
    }

    return AgentControlOpaqueIds(
        reportIds = buckets.getValue("reportIds"),
        todoIds = buckets.getValue("todoIds"),
        questionIds = buckets.getValue("questionIds"),
        invocationIds = buckets.getValue("invocationIds"),
        rootTurnIds = buckets.getValue("rootTurnIds"),
        actionIds = buckets.getValue("actionIds"),
        continuationIds = buckets.getValue("continuationIds"),
        planIds = buckets.getValue("planIds"),
        toolCallIds = buckets.getValue("toolCallIds")
    )
}

/**
 * Result of preserving a control envelope for a bounded prompt.  [fits] is
 * false when required state itself cannot fit.  Callers must pause or request
 * direction in that case; [content] is deliberately empty instead of being a
 * lossy partial envelope.
 */
data class AgentControlPromptContent(
    val content: String,
    val fits: Boolean,
    val requiredCharacters: Int,
    val requiredLines: Int,
    val preservedFields: Set<String> = emptySet(),
    val omittedFields: Set<String> = emptySet(),
    val opaqueIds: AgentControlOpaqueIds = AgentControlOpaqueIds()
) {
    val canSend: Boolean get() = fits
    val requiredStateFits: Boolean get() = fits
}

/**
 * Compact projection of an external or knowledge-base result. Evidence is
 * optional history, so it may be shortened; each retained source keeps its
 * citation link and one actionable finding whenever the caller's bounds allow
 * it. Opaque workflow IDs are carried separately and re-emitted canonically.
 */
data class AgentEvidencePromptContent(
    val content: String,
    val sourceCount: Int,
    val retainedSourceCount: Int,
    val truncated: Boolean,
    val opaqueIds: AgentControlOpaqueIds = AgentControlOpaqueIds()
)

private data class AgentEvidenceRecord(
    var title: String = "",
    var url: String = "",
    var citation: String = "",
    var finding: String = ""
)

private fun compactEvidenceField(value: String, maxChars: Int): String =
    value
        .replace(Regex("\\s+"), " ")
        .trim()
        .trimEnd('.', ',', ';', '`')
        .take(maxChars.coerceAtLeast(0))
        .trimEnd()

private fun evidenceMarkdownLink(line: String): Pair<String, String>? {
    val match = EVIDENCE_MARKDOWN_LINK_PATTERN.find(line) ?: return null
    val title = match.value
        .substringAfter('[', "")
        .substringBeforeLast(']', "")
        .trim()
    return title to match.groupValues[1].trimEnd('.', ',', ';', '`')
}

private fun evidenceUrl(line: String): String? =
    EVIDENCE_URL_PATTERN.find(line)
        ?.groupValues
        ?.getOrNull(1)
        ?.trimEnd('.', ',', ';', ')', ']', '`')

private fun isEvidenceMetadataLine(line: String): Boolean {
    val normalized = line.trim().lowercase(Locale.ROOT)
    return normalized.startsWith("trust:") ||
        normalized.startsWith("citation token:") ||
        normalized.startsWith("citation_token:") ||
        normalized.startsWith("content_type:") ||
        normalized.startsWith("redirects_followed:") ||
        normalized.startsWith("final_answer_requirement:") ||
        normalized.startsWith("source_citations:") ||
        normalized.startsWith("source_citation:") ||
        normalized.startsWith("source_url:") ||
        normalized.startsWith("url:") ||
        normalized.startsWith("next_hint:") ||
        normalized.startsWith("tip:")
}

private fun evidenceRecordsFromContent(
    raw: String,
    parsed: ParsedControlEnvelope
): List<AgentEvidenceRecord> {
    val body = parsed.importantOutput
        .takeIf(String::isNotBlank)
        ?: raw
    val records = mutableListOf<AgentEvidenceRecord>()
    val citations = mutableListOf<AgentEvidenceRecord>()
    var current: AgentEvidenceRecord? = null
    var inCitationSection = false

    fun flushCurrent() {
        current?.let { record ->
            if (record.title.isNotBlank() || record.url.isNotBlank() ||
                record.finding.isNotBlank()
            ) {
                records += record
            }
        }
        current = null
    }

    body.replace("\r", "")
        .lineSequence()
        .forEach { sourceLine ->
            val line = sourceLine.trim()
            if (line.isBlank()) return@forEach

            if (line.startsWith("source_citations:", ignoreCase = true)) {
                flushCurrent()
                inCitationSection = true
                return@forEach
            }

            val markdown = evidenceMarkdownLink(line)
            if (inCitationSection) {
                if (markdown != null) {
                    citations += AgentEvidenceRecord(
                        title = markdown.first,
                        url = markdown.second,
                        citation = markdown.first
                    )
                }
                return@forEach
            }

            val numbered = EVIDENCE_RESULT_NUMBER_PATTERN.matchEntire(line)
            if (numbered != null) {
                flushCurrent()
                val title = numbered.groupValues[2].trim()
                val inlineFinding = markdown
                    ?.let { title.substringAfter(it.first, "") }
                    ?.trim()
                    ?.trimStart('-', '—', ':')
                current = AgentEvidenceRecord(
                    title = markdown?.first ?: title,
                    url = markdown?.second.orEmpty(),
                    citation = markdown?.first.orEmpty(),
                    finding = inlineFinding.orEmpty()
                )
                return@forEach
            }

            val citationLine = line.startsWith("citation:", ignoreCase = true) ||
                line.startsWith("source citation:", ignoreCase = true)
            if (citationLine && markdown != null) {
                val record = current ?: AgentEvidenceRecord()
                record.title = record.title.ifBlank { markdown.first }
                record.url = record.url.ifBlank { markdown.second }
                record.citation = markdown.first
                current = record
                return@forEach
            }

            evidenceUrl(line)?.let { url ->
                val record = current ?: AgentEvidenceRecord()
                record.url = record.url.ifBlank { url }
                current = record
                return@forEach
            }

            val field = EVIDENCE_FIELD_PATTERN.matchEntire(line)
            if (field != null) {
                val record = current ?: AgentEvidenceRecord()
                val value = field.groupValues[2].trim()
                if (record.finding.isBlank()) {
                    record.finding = value
                } else if (value.isNotBlank()) {
                    record.finding = "${record.finding} $value"
                }
                current = record
                return@forEach
            }

            if (current != null && !isEvidenceMetadataLine(line)) {
                val record = current ?: return@forEach
                if (record.finding.isBlank()) {
                    record.finding = line
                } else if (
                    record.finding.length < 700 &&
                    !line.startsWith("source_", ignoreCase = true)
                ) {
                    record.finding = "${record.finding} $line"
                }
            } else if (current == null && markdown != null) {
                records += AgentEvidenceRecord(
                    title = markdown.first,
                    url = markdown.second,
                    citation = markdown.first
                )
            }
        }
    flushCurrent()

    // A few legacy Kiwix/search rows contain links without a numbered result
    // section. Keep those links as evidence records instead of falling back to
    // the first title-only snippet.
    if (records.isEmpty() && citations.isEmpty()) {
        EVIDENCE_MARKDOWN_LINK_PATTERN.findAll(body).forEach { match ->
            evidenceMarkdownLink(match.value)?.let { markdown ->
                records += AgentEvidenceRecord(
                    title = markdown.first,
                    url = markdown.second,
                    citation = markdown.first
                )
            }
        }
    }

    fun mergeInto(target: MutableList<AgentEvidenceRecord>, incoming: AgentEvidenceRecord) {
        val index = target.indexOfFirst { existing ->
            (incoming.url.isNotBlank() && existing.url == incoming.url) ||
                (incoming.url.isBlank() && existing.url.isBlank() &&
                    incoming.title.isNotBlank() &&
                    existing.title.equals(incoming.title, ignoreCase = true))
        }
        if (index < 0) {
            target += incoming
            return
        }
        val existing = target[index]
        if (existing.title.isBlank()) existing.title = incoming.title
        if (existing.url.isBlank()) existing.url = incoming.url
        if (existing.citation.isBlank()) existing.citation = incoming.citation
        if (existing.finding.isBlank()) existing.finding = incoming.finding
    }

    val merged = mutableListOf<AgentEvidenceRecord>()
    records.forEach { mergeInto(merged, it) }
    citations.forEach { mergeInto(merged, it) }
    return merged
}

private fun evidenceRecordLine(record: AgentEvidenceRecord, index: Int): String {
    val title = compactEvidenceField(record.title, 100)
        .ifBlank { "Source $index" }
    val link = if (record.url.isNotBlank()) {
        "[$title](${record.url})"
    } else if (record.citation.isNotBlank()) {
        record.citation
    } else {
        title
    }
    val finding = compactEvidenceField(record.finding, 150)
    return buildString {
        append("evidence ")
        append(index)
        append(": ")
        append(link)
        if (finding.isNotBlank()) {
            append(" — ")
            append(finding)
        }
    }
}

private fun evidenceRecordSourceOnlyLine(record: AgentEvidenceRecord, index: Int): String {
    val title = compactEvidenceField(record.title, 80)
        .ifBlank { "Source $index" }
    val link = if (record.url.isNotBlank()) {
        "[$title](${record.url})"
    } else if (record.citation.isNotBlank()) {
        record.citation
    } else {
        title
    }
    return "evidence $index: $link"
}

private data class ParsedControlEnvelope(
    val fields: LinkedHashMap<String, String>,
    val importantOutput: String,
    val optionalLines: List<String>
)

private fun parseControlEnvelope(raw: String): ParsedControlEnvelope {
    val fields = LinkedHashMap<String, String>()
    val importantLines = mutableListOf<String>()
    val optionalLines = mutableListOf<String>()
    var insideImportantOutput = false

    raw.replace("\r", "")
        .trim()
        .lineSequence()
        .forEach { line ->
            val match = CONTROL_LINE_PATTERN.matchEntire(line)
            // Evidence providers use Summary/Result labels inside the
            // important body; they are not envelope headers.
            val nestedEvidenceField = insideImportantOutput &&
                (match?.groupValues?.getOrNull(1)
                    ?.lowercase(Locale.ROOT)
                    ?.let { it in setOf(
                        "summary",
                        "finding",
                        "description",
                        "result"
                    ) } == true)
            if (match != null && !nestedEvidenceField) {
                val key = match.groupValues[1].lowercase(Locale.ROOT)
                val value = match.groupValues[2].trim()
                if (key == "important_output") {
                    insideImportantOutput = true
                    if (value.isNotBlank()) importantLines += value
                } else {
                    insideImportantOutput = false
                    fields.putIfAbsent(key, value)
                }
            } else if (insideImportantOutput) {
                importantLines += line
            } else if (line.isNotBlank()) {
                optionalLines += line
            }
        }

    return ParsedControlEnvelope(
        fields = fields,
        importantOutput = importantLines.joinToString("\n").trim(),
        optionalLines = optionalLines
    )
}

/**
 * Keep compact, actionable evidence for an external or knowledge-base result.
 * Each retained source remains a Markdown citation link and carries a bounded
 * finding when the result supplied one. This projection is intentionally
 * lossy: unlike a control envelope, old evidence may be shortened or omitted.
 */
fun preserveToolEvidenceContent(
    content: String,
    toolName: String? = null,
    maxChars: Int = 1_200,
    maxLines: Int = 12
): AgentEvidencePromptContent {
    val raw = content.replace("\r", "").trim()
    val charLimit = maxChars.coerceAtLeast(1)
    val lineLimit = maxLines.coerceAtLeast(1)
    val parsed = parseControlEnvelope(raw)
    val effectiveToolName = toolName?.takeIf(String::isNotBlank)
        ?: parsed.fields["tool"]
    val opaqueIds = extractAgentControlOpaqueIds(raw)
    val records = evidenceRecordsFromContent(raw, parsed)
    val sourceCount = records.size

    val candidates = mutableListOf<String>()
    parsed.fields["status"]
        ?.takeIf(String::isNotBlank)
        ?.let { candidates += fieldLine("status", compactEvidenceField(it, 120)) }
    effectiveToolName
        ?.takeIf(String::isNotBlank)
        ?.let { candidates += fieldLine("tool", compactEvidenceField(it, 80)) }
    canonicalOpaqueIdLines(opaqueIds).forEach { candidates += it }
    parsed.fields["summary"]
        ?.takeIf(String::isNotBlank)
        ?.let {
            candidates += fieldLine("summary", compactEvidenceField(it, 220))
        }

    val selected = mutableListOf<String>()
    fun tryAppend(line: String): Boolean {
        if (line.isBlank() || selected.size >= lineLimit) return false
        val candidate = (selected + line).joinToString("\n").trim()
        if (candidate.length > charLimit) return false
        selected += line
        return true
    }
    candidates.forEach(::tryAppend)

    var retainedSourceCount = 0
    var evidenceOmitted = false
    records.forEachIndexed { index, record ->
        val fullLine = evidenceRecordLine(record, index + 1)
        val sourceOnlyLine = evidenceRecordSourceOnlyLine(record, index + 1)
        if (tryAppend(fullLine) || tryAppend(sourceOnlyLine)) {
            retainedSourceCount += 1
        } else {
            evidenceOmitted = true
        }
    }

    parsed.fields["next_hint"]
        ?.takeIf(String::isNotBlank)
        ?.let {
            if (!tryAppend(fieldLine("next_hint", compactEvidenceField(it, 220)))) {
                evidenceOmitted = true
            }
        }

    // Results from older tools sometimes have no parseable fields. Preserve
    // bounded whole lines as a final fallback, without cutting a URL or a
    // finding in the middle.
    if (selected.isEmpty()) {
        raw.lineSequence()
            .map(String::trim)
            .filter(String::isNotBlank)
            .take(lineLimit)
            .forEach { line ->
                if (!tryAppend(compactEvidenceField(line, charLimit))) {
                    evidenceOmitted = true
                }
            }
    }

    val result = selected.joinToString("\n").trim()
    val truncated = evidenceOmitted ||
        records.size != retainedSourceCount ||
        raw.length > result.length
    return AgentEvidencePromptContent(
        content = result,
        sourceCount = sourceCount,
        retainedSourceCount = retainedSourceCount,
        truncated = truncated,
        opaqueIds = opaqueIds
    )
}

/** Message overload keeps typed tool-call IDs alongside parsed evidence IDs. */
fun preserveToolEvidenceContent(
    message: AgentService.Companion.ChatMessage,
    maxChars: Int = 1_200,
    maxLines: Int = 12
): AgentEvidencePromptContent {
    val messageIds = buildSet {
        message.toolCallId?.takeIf(String::isNotBlank)?.let(::add)
        message.pendingToolCall?.id?.takeIf(String::isNotBlank)?.let(::add)
    }
    val contentWithTypedIds = buildString {
        append(message.content)
        messageIds
            .filterNot { message.content.contains(it) }
            .forEach { id ->
                appendLine()
                append("tool_call_id: ")
                append(id)
            }
    }
    return preserveToolEvidenceContent(
        content = contentWithTypedIds,
        toolName = message.toolName ?: message.pendingToolCall?.name,
        maxChars = maxChars,
        maxLines = maxLines
    )
}

private fun canonicalOpaqueIdLines(ids: AgentControlOpaqueIds): List<String> = buildList {
    ids.reportIds.forEach { add("report_id: $it") }
    ids.todoIds.forEach { add("todo_id: $it") }
    ids.questionIds.forEach { add("question_id: $it") }
    ids.invocationIds.forEach { add("invocation_id: $it") }
    ids.rootTurnIds.forEach { add("root_turn_id: $it") }
    ids.actionIds.forEach { add("action_id: $it") }
    ids.continuationIds.forEach { add("continuation_id: $it") }
    ids.planIds.forEach { add("plan_id: $it") }
    ids.toolCallIds.forEach { add("tool_call_id: $it") }
}

private fun fieldLine(key: String, value: String): String = "$key: $value"

private fun parsedFieldValue(fields: Map<String, String>, key: String): String? =
    fields[key]?.takeIf(String::isNotBlank)

private fun requiredControlFieldNames(
    toolName: String?,
    fields: Map<String, String>,
    importantOutput: String
): Set<String> {
    val normalizedTool = toolName?.trim()?.lowercase(Locale.ROOT)
        ?: fields["tool"]?.trim()?.lowercase(Locale.ROOT)
    val required = LinkedHashSet<String>()
    listOf("status", "tool", "summary", "error_code", "next_hint")
        .forEach { key ->
            if (key in fields) required += key
        }

    // A question answer, plan decision, state packet, report, or delegation
    // result is authoritative input. Keep its complete important_output; if
    // it cannot fit, the caller gets an explicit pause signal.
    val authoritativeOutput = normalizedTool in setOf(
        "question",
        "answer_question",
        "call_agent",
        "agent_report_read",
        "project_state_read",
        "project_order_read",
        "plan_read",
        "propose_plan",
        "finish_task",
        "reflection"
    )
    if (authoritativeOutput && importantOutput.isNotBlank()) {
        required += "important_output"
    }
    return required
}

/**
 * Preserve the semantic fields of a control-bearing tool result while
 * allowing disposable prose to be omitted. Required fields are never cut in
 * the middle. When their complete representation exceeds the requested
 * bound, this returns [AgentControlPromptContent.fits] == false.
 */
fun preserveToolControlContent(
    content: String,
    toolName: String? = null,
    maxChars: Int = Int.MAX_VALUE,
    maxLines: Int = Int.MAX_VALUE
): AgentControlPromptContent {
    val raw = content.replace("\r", "").trim()
    val opaqueIds = extractAgentControlOpaqueIds(raw)
    val parsed = parseControlEnvelope(raw)
    val effectiveToolName = toolName?.takeIf(String::isNotBlank)
        ?: parsed.fields["tool"]
    val controlMessage = isControlBearingTool(effectiveToolName) ||
        parsed.fields["status"]?.equals("error", ignoreCase = true) == true ||
        !opaqueIds.isEmpty()

    if (!controlMessage) {
        val lines = raw.takeIf(String::isNotBlank)?.lines().orEmpty()
        val fits = raw.length <= maxChars && lines.size <= maxLines
        return AgentControlPromptContent(
            content = if (fits) raw else "",
            fits = fits,
            requiredCharacters = raw.length,
            requiredLines = lines.size,
            preservedFields = emptySet(),
            omittedFields = if (fits) emptySet() else setOf("content"),
            opaqueIds = opaqueIds
        )
    }

    // Some restored legacy rows carry only the raw JSON body and the typed
    // tool name. Treat that body as authoritative output instead of returning
    // an empty projection merely because no envelope headers survived.
    val importantOutput = if (
        parsed.fields.isEmpty() && parsed.importantOutput.isBlank()
    ) {
        raw
    } else {
        parsed.importantOutput
    }
    val requiredNames = requiredControlFieldNames(
        toolName = effectiveToolName,
        fields = parsed.fields,
        importantOutput = importantOutput
    )
    val requiredLines = mutableListOf<String>()
    listOf(
        "status",
        "tool",
        "summary",
        "error_code",
        "important_output",
        "next_hint"
    ).forEach { key ->
        if (key !in requiredNames) return@forEach
        when (key) {
            "important_output" -> {
                requiredLines += fieldLine("important_output", "")
                if (importantOutput.isNotBlank()) {
                    requiredLines += importantOutput.lines()
                }
            }
            else -> parsedFieldValue(parsed.fields, key)?.let {
                requiredLines += fieldLine(key, it)
            }
        }
    }
    val existingFieldIds = parsed.fields.keys
        .filter { it.endsWith("_id") }
        .mapNotNull { parsed.fields[it]?.let { value -> "$it: $value" } }
    requiredLines += existingFieldIds
    val existingIdText = requiredLines.joinToString("\n")
    canonicalOpaqueIdLines(opaqueIds).forEach { line ->
        if (!existingIdText.contains(line)) requiredLines += line
    }

    val requiredText = requiredLines.joinToString("\n").trim()
    val requiredLineCount = requiredText.takeIf(String::isNotBlank)
        ?.lines()
        ?.size
        ?: 0
    val requiredCharacters = requiredText.length
    val sizeAllowsRequiredState = requiredCharacters <= maxChars &&
        requiredLineCount <= maxLines
    if (!sizeAllowsRequiredState) {
        return AgentControlPromptContent(
            content = "",
            fits = false,
            requiredCharacters = requiredCharacters,
            requiredLines = requiredLineCount,
            preservedFields = requiredNames,
            omittedFields = requiredNames,
            opaqueIds = opaqueIds
        )
    }

    val candidateLines = requiredLines.toMutableList()
    val optional = buildList {
        addAll(parsed.optionalLines)
        if (importantOutput.isNotBlank() &&
            "important_output" !in requiredNames
        ) {
            add("important_output:")
            addAll(importantOutput.lines())
        }
    }
    val omitted = LinkedHashSet<String>()
    optional.forEach { line ->
        val candidate = (candidateLines + line).joinToString("\n").trim()
        if (candidate.length <= maxChars && candidate.lines().size <= maxLines) {
            candidateLines += line
        } else {
            omitted += line.substringBefore(':').trim()
        }
    }

    val finalText = candidateLines.joinToString("\n").trim()
    return AgentControlPromptContent(
        content = finalText,
        fits = true,
        requiredCharacters = requiredCharacters,
        requiredLines = requiredLineCount,
        preservedFields = (requiredNames + opaqueIds.all.mapNotNull { id ->
            when {
                id in opaqueIds.reportIds -> "report_id"
                id in opaqueIds.todoIds -> "todo_id"
                id in opaqueIds.questionIds -> "question_id"
                id in opaqueIds.invocationIds -> "invocation_id"
                id in opaqueIds.rootTurnIds -> "root_turn_id"
                id in opaqueIds.actionIds -> "action_id"
                id in opaqueIds.continuationIds -> "continuation_id"
                id in opaqueIds.planIds -> "plan_id"
                id in opaqueIds.toolCallIds -> "tool_call_id"
                else -> null
            }
        }).toSet(),
        omittedFields = omitted,
        opaqueIds = opaqueIds
    )
}

/** Message overload used by the context normalizer. */
fun preserveToolControlContent(
    message: AgentService.Companion.ChatMessage,
    maxChars: Int = Int.MAX_VALUE,
    maxLines: Int = Int.MAX_VALUE
): AgentControlPromptContent {
    val messageIds = buildSet {
        message.toolCallId?.takeIf(String::isNotBlank)?.let(::add)
        message.pendingToolCall?.id?.takeIf(String::isNotBlank)?.let(::add)
    }
    val contentWithTypedIds = buildString {
        append(message.content)
        messageIds
            .filterNot { message.content.contains(it) }
            .forEach { id ->
                appendLine()
                append("tool_call_id: ")
                append(id)
            }
    }
    return preserveToolControlContent(
        content = contentWithTypedIds,
        toolName = message.toolName ?: message.pendingToolCall?.name,
        maxChars = maxChars,
        maxLines = maxLines
    )
}

private fun comparableProjectControlPacket(value: String): String =
    value
        .replace("\r\n", "\n")
        .replace('\r', '\n')
        .lineSequence()
        .filter(String::isNotBlank)
        .joinToString("\n") { it.trimEnd() }
        .trim()

/**
 * Replaces an exact duplicate project-state result with a typed pointer to
 * the fresh packet already present in the same request. This is a prompt-only
 * projection. Any mismatch, error, or capacity marker returns the original
 * message so a state change can never be hidden by a lossy shortcut.
 */
internal fun projectExactProjectStateReadReceipt(
    message: AgentService.Companion.ChatMessage,
    canonicalPacket: String?
): AgentService.Companion.ChatMessage {
    val packet = canonicalPacket
        ?.takeIf(String::isNotBlank)
        ?: return message
    if (message.role != "tool") return message
    val effectiveToolName = message.toolName
        ?.takeIf(String::isNotBlank)
        ?: message.pendingToolCall?.name
    if (!effectiveToolName.equals("project_state_read", ignoreCase = true)) {
        return message
    }

    val parsed = parseControlEnvelope(message.content)
    if (!parsed.fields["status"].equals("ok", ignoreCase = true)) {
        return message
    }
    if (!parsed.fields["tool"].equals("project_state_read", ignoreCase = true)) {
        return message
    }
    val stateOutput = parsed.importantOutput
    if (
        stateOutput.isBlank() ||
        isControlPacketCapacityFailure(message.content) ||
        isControlPacketCapacityFailure(stateOutput) ||
        isControlPacketCapacityFailure(packet) ||
        comparableProjectControlPacket(stateOutput) !=
            comparableProjectControlPacket(packet)
    ) {
        return message
    }

    val opaqueIds = extractAgentControlOpaqueIds(message.content)
    val typedToolCallIds = buildList {
        message.toolCallId
            ?.takeIf(String::isNotBlank)
            ?.let { add("tool_call_id: $it") }
        message.pendingToolCall?.id
            ?.takeIf(String::isNotBlank)
            ?.let { add("tool_call_id: $it") }
    }
    val packetLines = packet.replace("\r\n", "\n").replace('\r', '\n').lines()
    val stateRevision = packetFieldValues(packetLines, "state_revision")
        .firstOrNull()
    val mode = packetFieldValues(packetLines, "mode").firstOrNull()
    val receiptLines = mutableListOf<String>()
    listOf("status", "tool", "summary", "error_code").forEach { key ->
        parsed.fields[key]?.takeIf(String::isNotBlank)?.let {
            receiptLines += "$key: $it"
        }
    }
    receiptLines += "important_output:"
    stateRevision?.let { receiptLines += "state_revision: $it" }
    mode?.let { receiptLines += "mode: $it" }
    receiptLines += "canonical_packet: current_request"
    receiptLines += "next_hint: " + (
        parsed.fields["next_hint"]?.takeIf(String::isNotBlank)
            ?: "Use the current Project Control Packet in this request."
    )
    val existingIdLines = receiptLines.toSet()
    (canonicalOpaqueIdLines(opaqueIds) + typedToolCallIds)
        .distinct()
        .filterNot(existingIdLines::contains)
        .forEach(receiptLines::add)

    return message.copy(content = receiptLines.joinToString("\n"))
}

/** True when any typed message metadata or envelope content carries control. */
fun isControlBearingTool(message: AgentService.Companion.ChatMessage): Boolean {
    val toolName = message.toolName ?: message.pendingToolCall?.name
    if (isControlBearingTool(toolName)) return true
    val content = message.content
    val inlineToolName = content.lineSequence()
        .firstOrNull { it.trimStart().startsWith("tool:", ignoreCase = true) }
        ?.substringAfter(':')
        ?.trim()
    if (isControlBearingTool(inlineToolName)) return true
    val ids = extractAgentControlOpaqueIds(content)
    return message.role == "tool" && (
        content.lineSequence().any {
            it.trimStart().startsWith("status: error", ignoreCase = true)
        } ||
            !ids.isEmpty()
        )
}

/** Alias that reads naturally at an atomic-unit call site. */
val AgentPromptAtomicUnit.isControlBearing: Boolean
    get() = messages.any(::isControlBearingTool)

/** Stable group key for an individual message when an exchange is orphaned. */
fun agentPromptGroupId(message: AgentService.Companion.ChatMessage): String? =
    message.pendingToolCall?.id?.takeIf(String::isNotBlank)
        ?: message.toolCallId?.takeIf(String::isNotBlank)

private val TOOL_CALL_IDENTITY_ARGUMENT_KEYS = setOf(
    "action",
    "action_id",
    "actionid",
    "command_id",
    "commandid",
    "path",
    "file_path",
    "filepath",
    "status",
    "workspace_path"
)

private const val TOOL_CALL_ARGUMENT_OMISSION_MARKER = "[omitted:%d chars]"

private val PROMPT_MUTATION_TOOL_NAMES = setOf(
    "write_file",
    "edit_lines",
    "apply_patch",
    "create_folder",
    "write_memory",
    "rewrite_memory",
    "delete_memory"
)

/** True for mutation calls whose completed prompt copy may omit large bodies. */
internal fun isPromptMutationTool(toolName: String?): Boolean =
    toolName
        ?.trim()
        ?.lowercase(Locale.ROOT)
        ?.let(PROMPT_MUTATION_TOOL_NAMES::contains)
        ?: false

/**
 * Returns a prompt-only copy of a completed tool call with large mutation
 * bodies removed. The execution copy is never changed. Tool name, call ID,
 * action/path/status fields, and small arguments remain typed metadata; large
 * `content`, `new_content`, `patch`, command, or script values become bounded
 * omission markers. Identity fields are kept byte-for-byte; if those fields
 * alone exceed [maxChars], the caller must treat the required state as over
 * budget and pause rather than truncating an ID or path. Clearing
 * [rawArgumentsJson] is required so request serialization cannot resurrect the
 * original large JSON body.
 */
internal fun compactAgentPromptToolCallArguments(
    toolCall: OllamaService.ToolCall,
    maxChars: Int = 640
): OllamaService.ToolCall {
    val limit = maxChars.coerceAtLeast(96)
    val projected = linkedMapOf<String, String>()
    fun serialized(values: Map<String, String>): String = JSONObject().apply {
        values.toSortedMap().forEach { (candidateKey, candidateValue) ->
            put(candidateKey, candidateValue)
        }
    }.toString()

    val orderedArguments = toolCall.arguments.entries.sortedWith(
        compareBy<Map.Entry<String, String>> {
            val normalizedKey = it.key
                .trim()
                .lowercase(Locale.ROOT)
                .replace('-', '_')
            normalizedKey !in TOOL_CALL_IDENTITY_ARGUMENT_KEYS
        }.thenBy { it.key }
    )
    orderedArguments.forEach { (key, rawValue) ->
        val normalizedKey = key
            .trim()
            .lowercase(Locale.ROOT)
            .replace('-', '_')
        val value = if (
            normalizedKey in TOOL_CALL_IDENTITY_ARGUMENT_KEYS ||
            rawValue.length <= 180
        ) {
            rawValue
        } else {
            TOOL_CALL_ARGUMENT_OMISSION_MARKER.format(rawValue.length)
        }
        val candidate = projected.toMutableMap().apply { put(key, value) }
        val serializedLength = serialized(candidate).length
        if (serializedLength <= limit || normalizedKey in TOOL_CALL_IDENTITY_ARGUMENT_KEYS) {
            projected[key] = value
        }
    }
    return toolCall.copy(
        arguments = projected,
        rawArgumentsJson = null
    )
}

private const val FAILED_PROPOSE_PLAN_PROMPT_MARKER =
    "[rejected/not-approved propose_plan body omitted from historical prompt; follow the matching error result and next_hint before retrying]"

private val PROPOSE_PLAN_BODY_ARGUMENT_KEYS = setOf(
    "plan",
    "plan_content",
    "plan_text"
)

/**
 * Projects a failed plan proposal for prompt history only. A proposal body is
 * removed only when the same atomic unit contains the matching typed
 * `propose_plan` result with `status: error`. Pending, successful, and
 * explicitly approved proposals are returned byte-for-byte unchanged. The
 * execution copy is never mutated, and clearing raw arguments prevents the
 * provider serializer from resurrecting the rejected body.
 */
internal fun projectFailedProposePlanPromptUnit(
    unit: AgentPromptAtomicUnit
): AgentPromptAtomicUnit {
    if (unit.messages.isEmpty()) return unit

    val projectedMessages = unit.messages.map { message ->
        if (message.role != "assistant") return@map message
        val call = message.pendingToolCall ?: return@map message
        if (!call.name.equals("propose_plan", ignoreCase = true)) return@map message
        if (message.isPlanApproved == true) return@map message
        val callId = call.id?.takeIf(String::isNotBlank) ?: return@map message
        val hasMatchingError = unit.messages.any { candidate ->
            if (candidate.role != "tool" || candidate.toolCallId != callId) {
                return@any false
            }
            val parsed = parseControlEnvelope(candidate.content)
            val resultToolName = candidate.toolName
                ?.takeIf(String::isNotBlank)
                ?: parsed.fields["tool"]
            resultToolName.equals("propose_plan", ignoreCase = true) &&
                parsed.fields["status"].equals("error", ignoreCase = true)
        }
        if (!hasMatchingError) return@map message
        // Keep the matching result untouched. Its summary, error details, and
        // next_hint are the actionable correction for the next proposal.

        val bodyEntry = call.arguments.entries.firstOrNull { (key, value) ->
            key.trim().lowercase(Locale.ROOT).replace('-', '_') in
                PROPOSE_PLAN_BODY_ARGUMENT_KEYS && value.isNotBlank()
        } ?: return@map message
        val projectedArguments = call.arguments.toMutableMap().apply {
            this[bodyEntry.key] = FAILED_PROPOSE_PLAN_PROMPT_MARKER
        }
        val projectedContent = when {
            bodyEntry.value in message.content ->
                message.content.replace(bodyEntry.value, FAILED_PROPOSE_PLAN_PROMPT_MARKER)
            message.isPlan && message.content.isNotBlank() ->
                FAILED_PROPOSE_PLAN_PROMPT_MARKER
            else -> message.content
        }
        message.copy(
            content = projectedContent,
            pendingToolCall = call.copy(
                arguments = projectedArguments,
                rawArgumentsJson = null
            )
        )
    }
    return if (projectedMessages == unit.messages) {
        unit
    } else {
        unit.copy(messages = projectedMessages)
    }
}

/**
 * Select the newest control-bearing tool exchange IDs. An atomic unit already
 * contains its assistant call and all contiguous matching tool results, so
 * retaining one returned ID retains the whole protocol exchange.
 */
fun protectedAgentPromptGroupIds(
    units: List<AgentPromptAtomicUnit>,
    latestControlExchangeCount: Int = 1
): Set<String> {
    if (latestControlExchangeCount <= 0) return emptySet()
    return units.asSequence()
        .filter { it.isToolExchange && it.isControlBearing }
        .toList()
        .takeLast(latestControlExchangeCount)
        .map { it.id }
        .toSet()
}

/** Verb-first alias for callers that build a protected-ID set. */
fun protectAgentPromptGroupIds(
    units: List<AgentPromptAtomicUnit>,
    latestControlExchangeCount: Int = 1
): Set<String> = protectedAgentPromptGroupIds(units, latestControlExchangeCount)

/**
 * Exact model-visible coverage supplied by the current durable packet.
 *
 * User messages can be released from the recent-unit protection only when
 * their complete content is present byte-for-byte in this coverage. The
 * packet's approved plan ID also makes completed plan proposals historical
 * when the proposal receipt carries that exact ID; an unapproved or legacy
 * proposal without that receipt remains protected as the current action.
 * Compact basis references may only be omitted when [approvedPlanContent]
 * matches the durable plan body exactly after line-ending normalization.
 */
internal data class AgentPromptCanonicalCoverage(
    val exactUserContents: Set<String> = emptySet(),
    val approvedPlanId: String? = null,
    val initialGoal: String? = null,
    val approvedPlanHash: String? = null,
    val approvedPlanContent: String? = null
)

/** Selection returned to the context packer before it evicts whole units. */
internal data class AgentPromptProtectionSelection(
    val protectedUnitIds: Set<String>,
    val protectedControlUnitIds: Set<String>,
    val protectedUserUnitIds: Set<String>,
    val coveredUserUnitIds: Set<String>,
    val obsoleteApprovedPlanUnitIds: Set<String>
)

private val CANONICAL_PACKET_FIELD_PATTERN = Regex(
    "^([ \\t]*)(-\\s*)?([A-Za-z][A-Za-z0-9_]*)\\s*:\\s?(.*)$"
)

private val CANONICAL_PACKET_HEADING_PATTERN = Regex(
    "^\\s*##\\s+(.+?)\\s*#*\\s*$"
)

/**
 * Reads the exact goal/correction values and approved plan identity/body from
 * a packet projection. The packet renderer may use either explicit
 * `content`/`correction` fields or an `answer` JSON object; no fuzzy text
 * matching is performed. A malformed or missing `full_plan_json` field leaves
 * approved-plan body coverage absent, so the compact basis retains its plan
 * reference.
 */
internal fun canonicalAgentPromptCoverage(
    packet: String?,
    additionalExactUserContents: Collection<String> = emptyList()
): AgentPromptCanonicalCoverage {
    val raw = packet?.let(::normalizeAgentPromptCoverageText)
        ?.takeIf(String::isNotBlank)
        ?: return AgentPromptCanonicalCoverage(
            exactUserContents = additionalExactUserContents
                .filter(String::isNotEmpty)
                .toSet()
        )
    val lines = raw.lines()
    val exactContents = LinkedHashSet<String>()
    val initialGoal = packetFieldValues(lines, "initial_goal").firstOrNull()
        ?.takeIf(String::isNotEmpty)
        ?.let(::normalizeAgentPromptCoverageText)
        ?.takeIf(String::isNotEmpty)
    initialGoal?.let {
        exactContents += it
    }

    var inCorrectionSection = false
    var inDecisionSection = false
    var inApprovedPlanSection = false
    var approvedPlanId: String? = null
    var approvedPlanHash: String? = null
    var approvedPlanContent: String? = null
    lines.forEach { line ->
        CANONICAL_PACKET_HEADING_PATTERN.matchEntire(line)?.let { heading ->
            val title = heading.groupValues[1].trim().trimEnd('#').trim()
            inCorrectionSection = title.contains("correction", ignoreCase = true)
            inDecisionSection = title.equals("Durable Decisions", ignoreCase = true)
            inApprovedPlanSection = title.equals("Approved Plan", ignoreCase = true)
        }

        val field = CANONICAL_PACKET_FIELD_PATTERN.matchEntire(line)
            ?: return@forEach
        val key = field.groupValues[3].lowercase(Locale.ROOT)
        val value = field.groupValues[4]
        if (inApprovedPlanSection && key == "id") {
            approvedPlanId = value.takeIf(String::isNotBlank)
        }
        if (inApprovedPlanSection && key == "hash") {
            approvedPlanHash = value.takeIf(String::isNotBlank)
        }
        if (
            inApprovedPlanSection &&
            key in setOf("full_plan_json", "full_plan")
        ) {
            runCatching { JSONObject(value.trim()) }
                .getOrNull()
                ?.let { planJson ->
                    planJson.optString("id")
                        .takeIf(String::isNotBlank)
                        ?.let { jsonId ->
                            if (approvedPlanId.isNullOrBlank()) {
                                approvedPlanId = jsonId
                            }
                        }
                    planJson.optString("hash")
                        .takeIf(String::isNotBlank)
                        ?.let { jsonHash -> approvedPlanHash = jsonHash }
                    listOf("plan_markdown", "markdown")
                        .asSequence()
                        .map { key -> planJson.optString(key) }
                        .firstOrNull(String::isNotBlank)
                        ?.let { markdown ->
                            approvedPlanContent =
                                normalizeAgentPromptCoverageText(markdown)
                        }
                }
        }
        if (
            (inCorrectionSection || inDecisionSection) &&
            key in setOf("content", "correction", "custom_answer") &&
            value.isNotEmpty()
        ) {
            packetFieldValues(lines, key)
                .filter(String::isNotEmpty)
                .forEach(exactContents::add)
        }
        if (inDecisionSection && key == "answer") {
            extractExactUserContentsFromAnswerJson(value).forEach(exactContents::add)
        }
    }

    exactContents += additionalExactUserContents.filter(String::isNotEmpty)
    return AgentPromptCanonicalCoverage(
        exactUserContents = exactContents,
        approvedPlanId = approvedPlanId,
        initialGoal = initialGoal,
        approvedPlanHash = approvedPlanHash,
        approvedPlanContent = approvedPlanContent
    )
}

/** Normalizes only line endings and outer whitespace for exact packet coverage checks. */
internal fun normalizeAgentPromptCoverageText(value: String): String =
    value.replace("\r\n", "\n").replace('\r', '\n').trim()

/**
 * Chooses mandatory atomic units after accounting for exact durable coverage.
 * This is deliberately separate from [protectedAgentPromptGroupIds] so the
 * existing default behavior remains unchanged for callers without a packet.
 */
internal fun selectProtectedAgentPromptUnits(
    units: List<AgentPromptAtomicUnit>,
    canonicalCoverage: AgentPromptCanonicalCoverage = AgentPromptCanonicalCoverage(),
    latestControlExchangeCount: Int = 1,
    latestUserMessageCount: Int = 2
): AgentPromptProtectionSelection {
    val controlUnits = units
        .filter { it.isToolExchange && it.isControlBearing }
    val newestControlId = controlUnits.lastOrNull()?.id
    val obsoletePlanIds = if (canonicalCoverage.approvedPlanId.isNullOrBlank()) {
        emptySet()
    } else {
        controlUnits.asSequence()
            .filter { isCompletedProposePlanUnit(it) }
            .filter { unit ->
                unit.id != newestControlId ||
                    unitContainsPlanId(unit, canonicalCoverage.approvedPlanId)
            }
            .map { it.id }
            .toSet()
    }
    val controlIds = controlUnits
        .asSequence()
        .filterNot { it.id in obsoletePlanIds }
        .map { it.id }
        .toList()
    val controlLimit = latestControlExchangeCount.coerceAtLeast(0)
    val eligibleControlIds = controlIds
        .takeLast(controlLimit)
        .toMutableList()
    // A successful mutation/read result may have no control-envelope marker,
    // while an old failed read does. Prefer the newest non-obsolete atomic
    // tool exchange over the oldest selected error when a trailing mode or
    // recovery system message would otherwise become the last unit.
    val latestRequiredToolId = units.asReversed()
        .firstOrNull {
            it.isToolExchange && it.id !in obsoletePlanIds
        }
        ?.id
    if (
        controlLimit > 0 &&
        latestRequiredToolId != null &&
        latestRequiredToolId !in eligibleControlIds
    ) {
        if (eligibleControlIds.size >= controlLimit) {
            eligibleControlIds.removeAt(0)
        }
        eligibleControlIds += latestRequiredToolId
    }
    val eligibleControlIdSet = eligibleControlIds.toSet()

    val recentUserUnits = units
        .filter(AgentPromptAtomicUnit::containsUserMessage)
        .takeLast(latestUserMessageCount.coerceAtLeast(0))
    val coveredUserIds = recentUserUnits.asSequence()
        .filter { unit ->
            val userContents = unit.messages
                .filter { it.role == "user" }
                .map { it.content }
            userContents.isNotEmpty() && userContents.all {
                it in canonicalCoverage.exactUserContents
            }
        }
        .map { it.id }
        .toSet()
    val protectedUserIds = recentUserUnits
        .asSequence()
        .map { it.id }
        .filterNot { it in coveredUserIds }
        .toSet()

    val protectedIds = buildSet {
        addAll(eligibleControlIdSet)
        addAll(protectedUserIds)
        units.lastOrNull()?.let { latest ->
            if (
                latest.id !in coveredUserIds &&
                latest.id !in obsoletePlanIds
            ) {
                add(latest.id)
            }
        }
    }
    return AgentPromptProtectionSelection(
        protectedUnitIds = protectedIds,
        protectedControlUnitIds = eligibleControlIdSet,
        protectedUserUnitIds = protectedUserIds,
        coveredUserUnitIds = coveredUserIds,
        obsoleteApprovedPlanUnitIds = obsoletePlanIds
    )
}

private fun packetFieldValues(
    lines: List<String>,
    requestedKey: String
): List<String> {
    val values = mutableListOf<String>()
    var index = 0
    while (index < lines.size) {
        val match = CANONICAL_PACKET_FIELD_PATTERN.matchEntire(lines[index])
        if (
            match == null ||
            !match.groupValues[3].equals(requestedKey, ignoreCase = true)
        ) {
            index += 1
            continue
        }
        val fieldIndent = match.groupValues[1].length
        val fieldLines = mutableListOf(match.groupValues[4])
        index += 1
        while (index < lines.size) {
            val next = lines[index]
            if (CANONICAL_PACKET_HEADING_PATTERN.matches(next)) break
            val nextField = CANONICAL_PACKET_FIELD_PATTERN.matchEntire(next)
            if (
                nextField != null &&
                isCanonicalPacketFieldBoundary(
                    field = nextField,
                    parentIndent = fieldIndent,
                    requestedKey = requestedKey
                )
            ) {
                break
            }
            fieldLines += next.trimEnd()
            index += 1
        }
        values += fieldLines.joinToString("\n").trimEnd()
    }
    return values
}

private fun isCanonicalPacketFieldBoundary(
    field: MatchResult,
    parentIndent: Int,
    requestedKey: String
): Boolean {
    val indent = field.groupValues[1].length
    val isBullet = field.groupValues[2].isNotBlank()
    val isSameRequestedField = indent == parentIndent &&
        field.groupValues[3].equals(requestedKey, ignoreCase = true)
    return isBullet || indent > parentIndent || isSameRequestedField
}

private fun extractExactUserContentsFromAnswerJson(raw: String): List<String> =
    runCatching { JSONObject(raw) }.getOrNull()?.let { json ->
        listOfNotNull(
            json.optString("content").takeIf(String::isNotEmpty),
            json.optString("correction").takeIf(String::isNotEmpty),
            json.optString("custom_answer").takeIf(String::isNotEmpty)
        )
    }.orEmpty()

private fun isCompletedProposePlanUnit(unit: AgentPromptAtomicUnit): Boolean {
    val hasProposePlan = unit.messages.any { message ->
        val typedName = message.toolName ?: message.pendingToolCall?.name
        typedName?.equals("propose_plan", ignoreCase = true) == true ||
            message.content.lineSequence().any { line ->
                line.trimStart().startsWith("tool: propose_plan", ignoreCase = true)
            }
    }
    return hasProposePlan && unit.messages.any { it.role == "tool" }
}

private fun unitContainsPlanId(
    unit: AgentPromptAtomicUnit,
    planId: String?
): Boolean {
    val expected = planId?.takeIf(String::isNotBlank) ?: return false
    return unit.messages.any { message ->
        val typedValues = buildList {
            message.pendingToolCall?.arguments?.forEach { (key, value) ->
                if (key.equals("plan_id", ignoreCase = true) ||
                    key.equals("planid", ignoreCase = true)
                ) {
                    add(value)
                }
            }
            message.toolArgs?.forEach { (key, value) ->
                if (key.equals("plan_id", ignoreCase = true) ||
                    key.equals("planid", ignoreCase = true)
                ) {
                    add(value)
                }
            }
        }
        expected in typedValues ||
            expected in extractAgentControlOpaqueIds(message.content).planIds
    }
}

/**
 * Selects historical atomic units that may still contribute to an optional
 * context digest. Exact user content already rendered in the fresh canonical
 * packet is redundant. All tool/proposal units remain eligible because their
 * summary, evidence, IDs, hints, or provider-specific fields may not be
 * represented by the packet. The returned units are unchanged and retain
 * their order.
 */
internal fun retainHistoricalPromptUnitsForDigest(
    units: List<AgentPromptAtomicUnit>,
    canonicalCoverage: AgentPromptCanonicalCoverage
): List<AgentPromptAtomicUnit> {
    val exactUserContents = canonicalCoverage.exactUserContents
        .map(::normalizeAgentPromptCoverageText)
        .filter(String::isNotBlank)
        .toSet()
    fun isCoveredUserUnit(unit: AgentPromptAtomicUnit): Boolean {
        if (unit.kind != AgentPromptUnitKind.USER_MESSAGE) return false
        val userMessages = unit.messages.filter { it.role == "user" }
        return userMessages.isNotEmpty() &&
            userMessages.size == unit.messages.size &&
            userMessages.all {
                normalizeAgentPromptCoverageText(it.content) in exactUserContents
            }
    }

    return units.filterNot(::isCoveredUserUnit)
}
