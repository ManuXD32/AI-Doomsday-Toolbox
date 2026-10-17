package com.example.llamadroid.service.delegation

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/** Pure argument parsing for the five main-LLM DeepSeek tools. */
object DelegationToolArgs {
    const val MAX_TITLE_CHARS = 60
    const val MAX_MESSAGE_CHARS = 2_000
    const val MAX_NOTE_CHARS = 200
    const val MAX_ATTACHMENTS = 24
    const val MAX_ID_CHARS = 200
    const val MIN_WAIT_SECONDS = 0
    const val MAX_WAIT_SECONDS = 120
    const val DEFAULT_WAIT_SECONDS = 45

    private val anyRefPattern = Regex("\\b(f|img)(\\d{1,12})\\b")

    /** Looks an argument up ignoring case, dashes and underscores. */
    fun arg(arguments: Map<String, String>, vararg names: String): String? {
        fun norm(s: String) = s.lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }
        val normalized = arguments.entries.associate { norm(it.key) to it.value }
        for (name in names) normalized[norm(name)]?.let { return it }
        return null
    }

    fun boundedText(raw: String?, max: Int): String? =
        raw?.trim()?.takeIf { it.isNotEmpty() }?.let { if (it.length > max) it.take(max).trimEnd() else it }

    /**
     * Chat file references (`f12`, `img55`) from a CSV, a JSON array or a messy string with
     * spaces, brackets and quotes. Every `f<n>`/`img<n>` token is extracted ("(f5)", "f5." -> f5); order is kept; duplicates removed.
     */
    fun parseAttachmentRefs(raw: String?): List<String> {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return emptyList()
        return anyRefPattern.findAll(text.lowercase(Locale.ROOT))
            .map { m -> m.groupValues[1] + m.groupValues[2].trimStart('0').ifEmpty { "0" } }
            .distinct()
            .take(MAX_ATTACHMENTS)
            .toList()
    }

    /** Integer from "15", "15.0", "15.7" or " 15 min "; null when there is no number. */
    fun coerceInt(raw: String?): Int? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null
        text.toIntOrNull()?.let { return it }
        text.toDoubleOrNull()?.let { return if (it.isNaN() || it.isInfinite()) null else Math.round(it).toInt() }
        val match = Regex("-?\\d+(?:\\.\\d+)?").find(text) ?: return null
        return match.value.toDoubleOrNull()?.let { Math.round(it).toInt() }
    }

    fun coerceBoolean(raw: String?, default: Boolean): Boolean = when (raw?.trim()?.lowercase(Locale.ROOT)) {
        "true", "yes", "1", "on" -> true
        "false", "no", "0", "off" -> false
        else -> default
    }

    fun coerceWaitSeconds(raw: String?): Int =
        (coerceInt(raw) ?: DEFAULT_WAIT_SECONDS).coerceIn(MIN_WAIT_SECONDS, MAX_WAIT_SECONDS)

    fun parseEnum(raw: String?, allowed: Set<String>, default: String): String {
        val value = raw?.trim()?.lowercase(Locale.ROOT).orEmpty()
        return if (value in allowed) value else default
    }

    fun parseProjectContinue(raw: String?): Boolean = parseEnum(raw, setOf("new", "continue"), "new") == "continue"

    /** What `deepseek_task_start(project = ...)` asked for. */
    sealed class ProjectChoice {
        data object New : ProjectChoice()
        data object Continue : ProjectChoice()
        data class Id(val id: String) : ProjectChoice()
    }

    /** blank/`new` = [ProjectChoice.New], `continue` = [ProjectChoice.Continue], anything else is a project id. */
    fun parseProjectChoice(raw: String?): ProjectChoice {
        val text = raw?.trim()?.trim('"', '\'').orEmpty()
        return when (text.lowercase(Locale.ROOT)) {
            "", "new" -> ProjectChoice.New
            "continue" -> ProjectChoice.Continue
            else -> ProjectChoice.Id(text.take(MAX_ID_CHARS))
        }
    }

    /** `thread`: blank/`new` = null (a new thread), anything else is a harness session id. */
    fun parseThreadChoice(raw: String?): String? {
        val text = raw?.trim()?.trim('"', '\'').orEmpty()
        return if (text.isEmpty() || text.equals("new", ignoreCase = true)) null else text.take(MAX_ID_CHARS)
    }

    fun parseFullDetail(raw: String?): Boolean = parseEnum(raw, setOf("brief", "full"), "brief") == "full"

    // ---- JSON Schemas (model-facing, English) ----

    private fun obj(properties: JSONObject, required: List<String>): String = JSONObject()
        .put("type", "object")
        .put("properties", properties)
        .put("required", JSONArray(required))
        .put("additionalProperties", false)
        .toString()

    private fun prop(type: String, description: String) = JSONObject().put("type", type).put("description", description)

    fun startSchema(): String = obj(
        JSONObject()
            .put("task", prop("string", "Self-contained brief: the goal, constraints, acceptance criteria and the expected output files. The subagent cannot see this chat."))
            .put("title", prop("string", "Short task title (at most $MAX_TITLE_CHARS characters).").put("maxLength", MAX_TITLE_CHARS))
            .put("attachments", prop("string", "Chat file ids to hand over, comma separated or a JSON array, e.g. \"f12,img55\". If omitted, the files attached to the latest user message are handed over."))
            .put("project", prop("string", "new (default) starts a fresh project; continue reuses the previous project of this chat; or the id of an existing project from deepseek_projects."))
            .put("thread", prop("string", "new (default) or the id of an existing thread of that project, to keep the harness agent's context. Needs project=continue or a project id.")),
        listOf("task")
    )

    fun projectsSchema(): String = obj(
        JSONObject().put("query", prop("string", "Optional text to filter project titles (case-insensitive).")),
        emptyList()
    )

    fun statusSchema(): String = obj(
        JSONObject().put("detail", prop("string", "brief (default) or full.")
            .put("enum", JSONArray(listOf("brief", "full"))).put("default", "brief")),
        emptyList()
    )

    fun messageSchema(): String = obj(
        JSONObject()
            .put("message", prop("string", "Message or question for the subagent (at most $MAX_MESSAGE_CHARS characters). Works in every state: also resumes an interrupted task and reopens a finished one.").put("maxLength", MAX_MESSAGE_CHARS))
            .put("attachments", prop("string", "Chat file ids to hand over with this message, comma separated or a JSON array, e.g. \"f12,img55\"."))
            .put("wait_seconds", prop("integer", "Seconds to wait for the reply, $MIN_WAIT_SECONDS to $MAX_WAIT_SECONDS.")
                .put("minimum", MIN_WAIT_SECONDS).put("maximum", MAX_WAIT_SECONDS).put("default", DEFAULT_WAIT_SECONDS)),
        listOf("message")
    )

    fun cancelSchema(): String = obj(
        JSONObject()
            .put("reason", prop("string", "Why the task is cancelled."))
            .put("stop_runtime", prop("boolean", "Also stop the DeepSeek runtime. Default false.").put("default", false)),
        emptyList()
    )

    fun scheduleSchema(): String = obj(
        JSONObject()
            .put("minutes", prop("integer", "Minutes until the check (${DelegationAlarmPolicy.MIN_MINUTES} to ${DelegationAlarmPolicy.MAX_MINUTES}); 0 cancels the reminder.")
                .put("minimum", 0).put("maximum", DelegationAlarmPolicy.MAX_MINUTES))
            .put("note", prop("string", "Optional reminder note (at most $MAX_NOTE_CHARS characters).").put("maxLength", MAX_NOTE_CHARS)),
        listOf("minutes")
    )
}
