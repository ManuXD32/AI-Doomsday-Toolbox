package com.example.llamadroid.service.delegation

import com.example.llamadroid.ui.agent.harness.HarnessTranscriptRole
import com.example.llamadroid.ui.agent.harness.boolean
import com.example.llamadroid.ui.agent.harness.harnessParseEvent
import com.example.llamadroid.ui.agent.harness.int
import com.example.llamadroid.ui.agent.harness.jsonArrayOrNull
import com.example.llamadroid.ui.agent.harness.jsonObjectOrNull
import com.example.llamadroid.ui.agent.harness.long
import com.example.llamadroid.ui.agent.harness.objectArray
import com.example.llamadroid.ui.agent.harness.objectValue
import com.example.llamadroid.ui.agent.harness.string
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** High-level state shown to the subagent. Derived, never stored. */
enum class HarnessDigestState { IDLE, WORKING, WAITING_APPROVAL, WAITING_QUESTION, WAITING_PLAN, ERROR }

/** One approval, question or plan waiting for an answer. [kind] is `approval`, `question` or `plan`. */
data class HarnessDigestPending(
    val kind: String,
    val id: String,
    val title: String,
    val options: List<String> = emptyList(),
)

data class HarnessDigestFile(val path: String, val added: Int, val deleted: Int)

data class HarnessDigestDeliverable(val path: String, val description: String? = null)

data class HarnessDigestTodo(val content: String, val status: String)

/**
 * Compact, bounded picture of one harness thread, folded from `session/follow` frames and the
 * attention store. Pure data: no Android or network access. Never contains raw tool output.
 */
data class HarnessTaskDigest(
    val threadIndex: Int = 1,
    val threadCount: Int = 1,
    val running: Boolean = false,
    val lastTurnFailed: Boolean = false,
    val turnCount: Int = 0,
    val elapsedMs: Long = 0L,
    val ctxUsedTokens: Long? = null,
    val ctxWindowTokens: Long? = null,
    val pending: List<HarnessDigestPending> = emptyList(),
    val errors: List<String> = emptyList(),
    /** Provider requests the harness retried during the current turn; recovered retries are not errors. */
    val modelRetries: Int = 0,
    /** Message plus code of the latest failed `turn/end`; empty when the latest turn did not fail. */
    val lastFailure: String = "",
    val lastAssistant: String = "",
    /** Tool calls by name since the last [markRendered]. */
    val toolCounts: Map<String, Int> = emptyMap(),
    val toolCallsTotal: Int = 0,
    val changedFiles: List<HarnessDigestFile> = emptyList(),
    val changedTotal: Int = 0,
    val deliverables: List<HarnessDigestDeliverable> = emptyList(),
    val compactions: Int = 0,
    val todos: List<HarnessDigestTodo> = emptyList(),
    /** Highest session event sequence folded; replays at or below it are ignored. */
    val lastSeq: Long = -1L,
    /** Coordinates of the newest `workspace/changes` event, for the driver to fetch the summary. */
    val changesSeq: Long? = null,
    val changesTurn: Int? = null,
) {
    val state: HarnessDigestState
        get() = when {
            pending.any { it.kind == "approval" } -> HarnessDigestState.WAITING_APPROVAL
            pending.any { it.kind == "question" } -> HarnessDigestState.WAITING_QUESTION
            pending.any { it.kind == "plan" } -> HarnessDigestState.WAITING_PLAN
            running -> HarnessDigestState.WORKING
            lastTurnFailed -> HarnessDigestState.ERROR
            else -> HarnessDigestState.IDLE
        }

    val contextPercent: Int?
        get() {
            val used = ctxUsedTokens ?: return null
            val window = ctxWindowTokens?.takeIf { it > 0 } ?: return null
            return ((used.coerceIn(0, window) * 100.0) / window).toInt().coerceIn(0, 100)
        }

    fun withElapsed(ms: Long) = copy(elapsedMs = ms.coerceAtLeast(0L))
    fun withThread(index: Int, count: Int) = copy(threadIndex = index.coerceAtLeast(1), threadCount = count.coerceAtLeast(1))
    fun withPending(items: List<HarnessDigestPending>) = copy(pending = items.take(MAX_PENDING))
    fun withRunning(value: Boolean) = if (value) copy(running = true, lastTurnFailed = false) else copy(running = false)
    fun withError(text: String): HarnessTaskDigest =
        copy(errors = (errors + text.trim().take(MAX_ERROR_STORED)).filter { it.isNotBlank() }.takeLast(MAX_ERRORS), lastTurnFailed = true)

    /**
     * The code of the latest turn failure when it looks like a model-provider failure
     * (llama-server, bridge, managed or LiteRT), else null.
     */
    fun providerFailureCode(): String? {
        if (!lastTurnFailed || lastFailure.isBlank()) return null
        val code = PROVIDER_CODE.findAll(lastFailure).map { it.value }.firstOrNull(::isProviderCode)
        if (code != null) return code
        return if (lastFailure.contains("provider request failed", ignoreCase = true)) "PROVIDER_INTERRUPTED" else null
    }

    fun withChangedFiles(files: List<HarnessDigestFile>, total: Int) =
        copy(changedFiles = files.take(MAX_FILES_STORED), changedTotal = total.coerceAtLeast(files.size))

    fun withTodos(items: List<HarnessDigestTodo>) = copy(todos = items.take(MAX_TODOS_STORED))

    /** Reset the per-render tool counters after the digest has been shown to the subagent. */
    fun markRendered() = copy(toolCounts = emptyMap())

    /** Stable content hash for the "unchanged digest" guard. Ignores elapsed time. */
    fun hash(): String {
        val sb = StringBuilder()
        sb.append(state).append('|').append(turnCount).append('|').append(threadIndex).append('/').append(threadCount)
            .append('|').append(contextPercent).append('|').append(toolCallsTotal).append('|').append(compactions)
            .append('|').append(changedTotal).append('|').append(lastSeq).append('|').append(modelRetries)
        pending.forEach { sb.append("|P").append(it.kind).append(it.id) }
        errors.forEach { sb.append("|E").append(it) }
        sb.append("|A").append(lastAssistant)
        changedFiles.forEach { sb.append("|F").append(it.path).append(it.added).append(',').append(it.deleted) }
        deliverables.forEach { sb.append("|D").append(it.path) }
        todos.forEach { sb.append("|T").append(it.content).append(it.status) }
        return Integer.toHexString(sb.toString().hashCode()) + java.lang.Long.toHexString(sb.length.toLong())
    }

    // ---- folding -------------------------------------------------------------------------

    /** Fold one `session/follow` frame (`snapshot`, `event`; others are ignored). */
    fun foldFrame(frame: JsonElement): HarnessTaskDigest {
        val root = frame.jsonObjectOrNull() ?: return this
        return when (root.string("type")) {
            "snapshot" -> foldSnapshot(root)
            "event" -> root.objectValue("event")?.let(::foldEvent) ?: this
            else -> this
        }
    }

    private fun foldSnapshot(frame: JsonObject): HarnessTaskDigest {
        var next = this
        frame.objectArray("records").forEach { record -> next = next.foldEvent(record.objectValue("event") ?: record) }
        val values = frame.objectValue("projections")?.objectValue("values")
        if (values != null) next = next.foldProjections(values)
        if (frame.objectValue("assistantStream")?.objectValue("activeAttempt") != null) next = next.copy(running = true)
        // The opening frame's cursor is the session's last event sequence (applied after the records so none are skipped).
        frame.long("cursor")?.takeIf { it > next.lastSeq }?.let { next = next.copy(lastSeq = it) }
        return next
    }

    /** Fold the `tokenUsage`, `contextPressure` and `todos` projection values. */
    fun foldProjections(values: JsonObject): HarnessTaskDigest {
        var next = this
        val pressure = values.objectValue("contextPressure")
        if (pressure != null) {
            val used = pressure.long("projectedTokens") ?: pressure.long("pressureTokens")
            val window = pressure.long("contextWindow")
            next = next.copy(ctxUsedTokens = used ?: next.ctxUsedTokens, ctxWindowTokens = window ?: next.ctxWindowTokens)
        }
        values["todos"]?.jsonArrayOrNull()?.let { array ->
            next = next.withTodos(array.mapNotNull { item ->
                val row = item.jsonObjectOrNull() ?: return@mapNotNull null
                val content = row.string("content")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                HarnessDigestTodo(content.take(MAX_TODO_CHARS), row.string("status") ?: "pending")
            })
        }
        return next
    }

    /** Fold one session event object (`{seq, type, data}`). */
    fun foldEvent(event: JsonObject): HarnessTaskDigest {
        val seq = event.long("seq")
        if (seq != null && seq <= lastSeq) return this
        val type = event.string("type") ?: return this
        val data = event.objectValue("data")
        var next = if (seq != null) copy(lastSeq = seq) else this
        when (type) {
            "turn/start" -> {
                val turn = data?.int("turn")
                next = next.copy(running = true, lastTurnFailed = false, lastFailure = "", modelRetries = 0, errors = emptyList(),
                    turnCount = if (turn != null) maxOf(next.turnCount, turn) else next.turnCount + 1)
            }
            "turn/end" -> {
                val failed = data?.objectValue("reason")?.string("kind") == "error"
                next = next.copy(running = false)
                if (failed) {
                    val reason = data?.objectValue("reason")
                    val message = reason?.string("message")?.takeIf { it.isNotBlank() }
                    val code = reason?.string("code")?.takeIf { it.isNotBlank() }
                    val detail = when {
                        message != null && code != null && !message.contains(code) -> "$message ($code)"
                        else -> message ?: code
                    }
                    val text = "turn ended with an error" + (detail?.let { ": $it" } ?: "")
                    next = next.withError(text).copy(lastFailure = text.take(MAX_ERROR_STORED))
                }
            }
            "assistant/message" -> {
                val parsed = harnessParseEvent(event)
                if (parsed != null && parsed.role == HarnessTranscriptRole.ASSISTANT && parsed.text.isNotBlank()) {
                    next = next.copy(lastAssistant = parsed.text.trim().take(MAX_ASSISTANT_STORED))
                }
                parsed?.usage?.let { usage ->
                    val used = usage.inputTokens + usage.cacheReadTokens + usage.cacheWriteTokens
                    if (used > 0) next = next.copy(ctxUsedTokens = used)
                }
            }
            "tool/call" -> {
                val name = (data?.string("name") ?: "tool").take(40)
                val counts = next.toolCounts.toMutableMap()
                counts[name] = (counts[name] ?: 0) + 1
                next = next.copy(toolCounts = counts, toolCallsTotal = next.toolCallsTotal + 1)
            }
            "tool/result" -> {
                val error = data?.objectValue("error")
                if (data?.boolean("isError") == true || error != null) {
                    next = next.withError("tool error" + (error?.string("code")?.let { ": $it" } ?: "")).copy(lastTurnFailed = next.lastTurnFailed)
                }
            }
            "llm/retry" -> {
                // A retry that later succeeds is not a failure: count it instead of listing an error.
                // A real final failure still arrives as a failed turn/end.
                next = next.copy(modelRetries = (next.modelRetries + 1).coerceAtMost(MAX_RETRIES_COUNTED))
            }
            "workspace/changes" -> {
                val turn = data?.int("turn")
                if (seq != null && turn != null && turn >= 1) next = next.copy(changesSeq = seq, changesTurn = turn)
            }
            "deliverables/presented" -> {
                val added = data?.get("files")?.jsonArrayOrNull().orEmpty().mapNotNull { item ->
                    val row = item.jsonObjectOrNull() ?: return@mapNotNull null
                    val path = row.string("path")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    HarnessDigestDeliverable(path.take(MAX_PATH_CHARS), row.string("description")?.take(MAX_DESC_CHARS))
                }
                if (added.isNotEmpty()) {
                    val byPath = LinkedHashMap<String, HarnessDigestDeliverable>()
                    (next.deliverables + added).forEach { byPath[it.path] = it }
                    next = next.copy(deliverables = byPath.values.toList().takeLast(MAX_DELIVERABLES_STORED))
                }
            }
            "compaction/end" -> next = next.copy(compactions = next.compactions + 1)
        }
        return next
    }

    // ---- rendering -----------------------------------------------------------------------

    /**
     * Render the digest, hard-capped at [maxChars]. When trimming, pending items survive first,
     * then errors, the last assistant message, changed files, then deliverables/todos/tools.
     */
    fun render(maxChars: Int = DEFAULT_MAX_CHARS): String {
        val header = buildString {
            append("[digest v1] thread ").append(threadIndex).append('/').append(threadCount)
            append(" · state=").append(state.name)
            append(" · turn ").append(turnCount)
            append(" · ").append(formatElapsed(elapsedMs))
            contextLabel()?.let { append(" · ").append(it) }
        }
        var budget = maxChars - header.length - 1
        fun take(line: String?, cap: Int = Int.MAX_VALUE): String? {
            if (line == null || budget < MIN_SECTION) return null
            val clipped = clip(line, minOf(cap, budget - 1))
            budget -= clipped.length + 1
            return clipped
        }
        val pendingLine = take(pendingLine(), maxChars / 2)
        val errorsLine = take(errors.takeLast(2).takeIf { it.isNotEmpty() }
            ?.joinToString(" | ", "Errors: ") { clip(it, 200) })
        val retriesLine = take(modelRetries.takeIf { it > 0 }?.let {
            if (running) "Retried ${it}x, recovered so far (not a failure unless the turn ends with an error)"
            else if (lastTurnFailed) "Retried ${it}x before the turn failed"
            else "Retried ${it}x, recovered"
        })
        val assistantLine = take(lastAssistant.takeIf { it.isNotBlank() }
            ?.let { "Last assistant: " + it.replace('\n', ' ') }, 1250)
        val filesLine = take(filesLine(), 520)
        val deliverablesLine = take(deliverables.takeIf { it.isNotEmpty() }?.let { list ->
            "Deliverables: " + list.take(10).joinToString(", ") { it.path } +
                if (list.size > 10) " (+${list.size - 10} more)" else ""
        }, 400)
        val todosLine = take(todos.takeIf { it.isNotEmpty() }?.take(5)?.joinToString("; ", "Todos: ") {
            "[" + (if (it.status == "completed") "x" else if (it.status == "in_progress") "~" else " ") + "] " + clip(it.content, 80)
        }, 500)
        val compactionsLine = take(compactions.takeIf { it > 0 }?.let { "Compactions: $it" })
        val toolsLine = take(toolCounts.takeIf { it.isNotEmpty() }?.entries?.sortedByDescending { it.value }?.take(8)
            ?.joinToString(" ", "Tools since last: ") { "${it.key}×${it.value}" })
        return listOfNotNull(header, pendingLine, errorsLine, retriesLine, assistantLine, filesLine, deliverablesLine,
            todosLine, compactionsLine, toolsLine).joinToString("\n").take(maxChars)
    }

    private fun contextLabel(): String? {
        val window = ctxWindowTokens?.takeIf { it > 0 } ?: return null
        val pct = contextPercent
        return "ctx " + (pct?.let { "$it%" } ?: "?") + " of " + formatTokens(window)
    }

    private fun pendingLine(): String? {
        if (pending.isEmpty()) return null
        return "Pending: " + pending.joinToString(" | ") { item ->
            item.kind + " " + item.id + " \"" + clip(item.title.replace('\n', ' '), 120) + "\"" +
                if (item.options.isNotEmpty()) " [" + item.options.take(6).joinToString("|") { clip(it, 30) } + "]" else ""
        }
    }

    private fun filesLine(): String? {
        if (changedFiles.isEmpty() && changedTotal == 0) return null
        val shown = changedFiles.take(MAX_FILES_SHOWN)
        val more = maxOf(changedTotal, changedFiles.size) - shown.size
        return "Changed files (${maxOf(changedTotal, changedFiles.size)}): " +
            shown.joinToString(", ") { "${it.path} +${it.added}/-${it.deleted}" } +
            if (more > 0) " (+$more more)" else ""
    }

    // ---- persistence ---------------------------------------------------------------------

    /** Serialize for `NativeChatDelegateTaskEntity.digestJson`; always at most [MAX_JSON_CHARS]. */
    fun toJson(): String {
        var assistant = lastAssistant
        var files = changedFiles
        var slim = false
        var json = encode(assistant, files, slim)
        repeat(8) {
            if (json.length <= MAX_JSON_CHARS) return json
            if (assistant.length > 200) assistant = assistant.take(assistant.length / 2)
            else if (files.size > 4) files = files.take(files.size / 2)
            else { assistant = ""; files = files.take(2); slim = true }
            json = encode(assistant, files, slim)
        }
        if (json.length <= MAX_JSON_CHARS) return json
        return encode("", emptyList(), true)
    }

    private fun encode(assistant: String, files: List<HarnessDigestFile>, slim: Boolean): String = buildJsonObject {
        put("v", 1)
        put("threadIndex", threadIndex); put("threadCount", threadCount)
        put("running", running); put("lastTurnFailed", lastTurnFailed)
        put("turnCount", turnCount); put("elapsedMs", elapsedMs)
        ctxUsedTokens?.let { put("ctxUsed", it) }
        ctxWindowTokens?.let { put("ctxWindow", it) }
        put("pending", buildJsonArray {
            pending.take(if (slim) 4 else MAX_PENDING).forEach { p -> add(buildJsonObject {
                put("kind", p.kind); put("id", p.id); put("title", p.title.take(if (slim) 80 else 200))
                put("options", buildJsonArray { p.options.take(if (slim) 3 else 6).forEach { add(JsonPrimitive(it.take(40))) } })
            }) }
        })
        put("errors", buildJsonArray { errors.takeLast(if (slim) 2 else MAX_ERRORS).forEach { add(JsonPrimitive(it.take(if (slim) 100 else 200))) } })
        if (modelRetries > 0) put("modelRetries", modelRetries)
        put("lastAssistant", assistant)
        if (lastFailure.isNotEmpty()) put("lastFailure", lastFailure.take(if (slim) 120 else 300))
        put("toolCallsTotal", toolCallsTotal)
        put("changedTotal", changedTotal)
        put("files", buildJsonArray {
            files.forEach { f -> add(buildJsonObject { put("p", f.path); put("a", f.added); put("d", f.deleted) }) }
        })
        put("deliverables", buildJsonArray {
            deliverables.takeLast(if (slim) 3 else 10).forEach { d -> add(buildJsonObject {
                put("p", d.path); d.description?.let { put("d", it.take(80)) }
            }) }
        })
        put("compactions", compactions)
        put("todos", buildJsonArray {
            todos.take(if (slim) 3 else 5).forEach { t -> add(buildJsonObject { put("c", t.content); put("s", t.status) }) }
        })
        put("lastSeq", lastSeq)
        changesSeq?.let { put("changesSeq", it) }
        changesTurn?.let { put("changesTurn", it) }
    }.toString()

    companion object {
        const val DEFAULT_MAX_CHARS = 2400
        const val MAX_JSON_CHARS = 8 * 1024
        private const val MIN_SECTION = 24
        private const val MAX_PENDING = 12
        private const val MAX_ERRORS = 5
        private const val MAX_RETRIES_COUNTED = 99
        private const val MAX_ERROR_STORED = 300
        private const val MAX_ASSISTANT_STORED = 4_000
        private const val MAX_FILES_STORED = 20
        private const val MAX_FILES_SHOWN = 20
        private const val MAX_DELIVERABLES_STORED = 20
        private const val MAX_TODOS_STORED = 20
        private const val MAX_TODO_CHARS = 120
        private const val MAX_PATH_CHARS = 200
        private const val MAX_DESC_CHARS = 120

        private val PROVIDER_CODE = Regex("[A-Z][A-Z0-9_]{3,80}")
        private val PROVIDER_PREFIXES = listOf("LLAMA_SERVER_", "PROVIDER_", "BRIDGE_", "MANAGED_", "LITERT_")

        private fun isProviderCode(code: String): Boolean =
            code == "CONTEXT_WINDOW_EXCEEDED" || code == "TRANSPORT" || code == "ADAPTER_STREAM_ERROR" ||
                code == "MODEL_SERVER_UNSTABLE" || PROVIDER_PREFIXES.any(code::startsWith)

        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        /** Null when [text] is blank or not a digest produced by [toJson]. */
        fun fromJson(text: String?): HarnessTaskDigest? {
            if (text.isNullOrBlank()) return null
            val root = runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return null
            if (root.int("v") != 1) return null
            return runCatching {
                HarnessTaskDigest(
                    threadIndex = root.int("threadIndex") ?: 1,
                    threadCount = root.int("threadCount") ?: 1,
                    running = root.boolean("running") ?: false,
                    lastTurnFailed = root.boolean("lastTurnFailed") ?: false,
                    turnCount = root.int("turnCount") ?: 0,
                    elapsedMs = root.long("elapsedMs") ?: 0L,
                    ctxUsedTokens = root.long("ctxUsed"),
                    ctxWindowTokens = root.long("ctxWindow"),
                    pending = root.objectArray("pending").mapNotNull { p ->
                        HarnessDigestPending(
                            kind = p.string("kind") ?: return@mapNotNull null,
                            id = p.string("id") ?: return@mapNotNull null,
                            title = p.string("title").orEmpty(),
                            options = p["options"].stringList(),
                        )
                    },
                    errors = root["errors"].stringList(),
                    modelRetries = root.int("modelRetries") ?: 0,
                    lastFailure = root.string("lastFailure").orEmpty(),
                    lastAssistant = root.string("lastAssistant").orEmpty(),
                    toolCallsTotal = root.int("toolCallsTotal") ?: 0,
                    changedTotal = root.int("changedTotal") ?: 0,
                    changedFiles = root.objectArray("files").mapNotNull { f ->
                        HarnessDigestFile(f.string("p") ?: return@mapNotNull null, f.int("a") ?: 0, f.int("d") ?: 0)
                    },
                    deliverables = root.objectArray("deliverables").mapNotNull { d ->
                        HarnessDigestDeliverable(d.string("p") ?: return@mapNotNull null, d.string("d"))
                    },
                    compactions = root.int("compactions") ?: 0,
                    todos = root.objectArray("todos").mapNotNull { t ->
                        HarnessDigestTodo(t.string("c") ?: return@mapNotNull null, t.string("s") ?: "pending")
                    },
                    lastSeq = root.long("lastSeq") ?: -1L,
                    changesSeq = root.long("changesSeq"),
                    changesTurn = root.int("changesTurn"),
                )
            }.getOrNull()
        }

        private fun JsonElement?.stringList(): List<String> =
            (this as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }.orEmpty()

        internal fun clip(text: String, max: Int): String =
            if (max <= 0) "" else if (text.length <= max) text else text.take((max - 1).coerceAtLeast(0)) + "…"

        internal fun formatElapsed(ms: Long): String {
            val seconds = (ms / 1000).coerceAtLeast(0)
            return when {
                seconds < 60 -> "${seconds}s"
                seconds < 3600 -> "${seconds / 60}m"
                else -> "${seconds / 3600}h%02dm".format((seconds % 3600) / 60)
            }
        }

        internal fun formatTokens(tokens: Long): String = when {
            tokens >= 1_000_000 -> "${tokens / 1_000_000}M"
            tokens >= 1000 -> "${tokens / 1000}k"
            else -> tokens.toString()
        }
    }
}
