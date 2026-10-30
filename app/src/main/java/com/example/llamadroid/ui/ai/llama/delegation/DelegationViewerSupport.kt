package com.example.llamadroid.ui.ai.llama.delegation

import androidx.annotation.StringRes
import com.example.llamadroid.R
import com.example.llamadroid.data.model.NativeChatDelegateTaskState
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/** Pure helpers for the delegation viewer; kept free of Compose so they are unit-testable. */

const val DELEGATION_PREVIEW_CHARS = 400
const val DELEGATION_DIGEST_LINE_CHARS = 400
const val DELEGATION_DIGEST_MAX_LINES = 30

/** Localized label for every [NativeChatDelegateTaskState] value; null for unknown states. */
@StringRes
fun delegationStateLabelRes(state: String): Int? = when (state) {
    NativeChatDelegateTaskState.QUEUED -> R.string.native_chat_delegation_viewer_state_queued
    NativeChatDelegateTaskState.STARTING_RUNTIME -> R.string.native_chat_delegation_viewer_state_starting_runtime
    NativeChatDelegateTaskState.RUNNING -> R.string.native_chat_delegation_viewer_state_running
    NativeChatDelegateTaskState.WAITING_HARNESS -> R.string.native_chat_delegation_viewer_state_waiting_harness
    NativeChatDelegateTaskState.WAITING_MAIN -> R.string.native_chat_delegation_viewer_state_waiting_main
    NativeChatDelegateTaskState.NEEDS_DIRECTION -> R.string.native_chat_delegation_viewer_state_needs_direction
    NativeChatDelegateTaskState.INTERRUPTED -> R.string.native_chat_delegation_viewer_state_interrupted
    NativeChatDelegateTaskState.COMPLETED -> R.string.native_chat_delegation_viewer_state_completed
    NativeChatDelegateTaskState.FAILED -> R.string.native_chat_delegation_viewer_state_failed
    NativeChatDelegateTaskState.CANCELLED -> R.string.native_chat_delegation_viewer_state_cancelled
    else -> null
}

/** Every state the viewer knows how to label (used by tests to guard completeness). */
val DELEGATION_ALL_STATES: List<String> = listOf(
    NativeChatDelegateTaskState.QUEUED, NativeChatDelegateTaskState.STARTING_RUNTIME,
    NativeChatDelegateTaskState.RUNNING, NativeChatDelegateTaskState.WAITING_HARNESS,
    NativeChatDelegateTaskState.WAITING_MAIN, NativeChatDelegateTaskState.NEEDS_DIRECTION,
    NativeChatDelegateTaskState.INTERRUPTED, NativeChatDelegateTaskState.COMPLETED,
    NativeChatDelegateTaskState.FAILED, NativeChatDelegateTaskState.CANCELLED
)

@StringRes
fun delegationPhaseLabelRes(phase: String): Int? = when (phase) {
    "starting_runtime" -> R.string.native_chat_delegation_viewer_phase_starting_runtime
    "model_call" -> R.string.native_chat_delegation_viewer_phase_model_call
    "harness_wait" -> R.string.native_chat_delegation_viewer_phase_harness_wait
    "compacting" -> R.string.native_chat_delegation_viewer_phase_compacting
    "waiting_main" -> R.string.native_chat_delegation_viewer_phase_waiting_main
    "queued" -> R.string.native_chat_delegation_viewer_phase_queued
    "tool_call" -> R.string.native_chat_delegation_viewer_phase_tool_call
    "finishing" -> R.string.native_chat_delegation_viewer_phase_finishing
    else -> null
}

/** Readable text for a stable `lastErrorCode`; null means "show the generic code line". */
@StringRes
fun delegationErrorLabelRes(code: String): Int? = when (code) {
    "runtime_missing" -> R.string.native_chat_delegation_viewer_error_runtime_missing
    "server_down" -> R.string.native_chat_delegation_viewer_error_server_down
    "runtime_stopped" -> R.string.native_chat_delegation_viewer_error_runtime_stopped
    "model_error" -> R.string.native_chat_delegation_viewer_error_model_error
    "step_limit" -> R.string.native_chat_delegation_viewer_error_step_limit
    "time_limit" -> R.string.native_chat_delegation_viewer_error_time_limit
    "tool_errors" -> R.string.native_chat_delegation_viewer_error_tool_errors
    "harness_model_error" -> R.string.native_chat_delegation_viewer_error_harness_model_error
    else -> null
}

/** `m:ss` below one hour, `h:mm:ss` above. Negative values clamp to zero. */
fun formatDelegationElapsed(elapsedMs: Long): String {
    val total = (elapsedMs / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
    else String.format(Locale.US, "%d:%02d", m, s)
}

/** Percent used (0..100) from token counts, or null when the window is unknown. */
fun delegationContextPercent(used: Int, window: Int): Int? =
    if (window <= 0) null else ((used.coerceAtLeast(0) * 100L) / window).toInt().coerceIn(0, 100)

/** Single bounded preview: trimmed, truncated with an ellipsis. */
fun boundedPreview(text: String, maxChars: Int = DELEGATION_PREVIEW_CHARS): String {
    val trimmed = text.trim()
    return if (trimmed.length <= maxChars) trimmed else trimmed.take(maxChars).trimEnd() + "…"
}

fun isPreviewTruncated(text: String, maxChars: Int = DELEGATION_PREVIEW_CHARS): Boolean = text.trim().length > maxChars

/** Tool names of an OpenAI-style `tool_calls` array; tolerant of malformed JSON. */
fun parseToolCallNames(toolCallsJson: String?): List<String> {
    if (toolCallsJson.isNullOrBlank()) return emptyList()
    return runCatching {
        val array = JSONArray(toolCallsJson)
        buildList {
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val name = obj.optJSONObject("function")?.optString("name")?.takeIf { it.isNotBlank() }
                    ?: obj.optString("name").takeIf { it.isNotBlank() }
                if (name != null) add(name)
            }
        }
    }.getOrDefault(emptyList())
}

data class DelegationResultFile(val path: String?, val name: String, val attachmentId: Long?)

/** Parses `resultFilesJson` (`[{path,name,attachmentId}]`); never throws. */
fun parseResultFiles(json: String?): List<DelegationResultFile> {
    if (json.isNullOrBlank()) return emptyList()
    return runCatching {
        val array = JSONArray(json)
        buildList {
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val path = obj.optString("path").takeIf { it.isNotBlank() }
                val name = obj.optString("name").takeIf { it.isNotBlank() }
                    ?: path?.substringAfterLast('/')
                    ?: continue
                val id = if (obj.has("attachmentId") && !obj.isNull("attachmentId")) {
                    obj.optLong("attachmentId", -1L).takeIf { it > 0 }
                } else null
                add(DelegationResultFile(path, name, id))
            }
        }
    }.getOrDefault(emptyList())
}

enum class DigestSectionKind { STATE, PENDING, ERRORS, LAST_ASSISTANT, CHANGED, DELIVERABLES, OTHER }

data class DigestSection(val kind: DigestSectionKind, val title: String?, val lines: List<String>, val hiddenCount: Int = 0)

/** Parsed digest: either structured [sections] or the [raw] bounded text when parsing fails. */
data class DigestView(val sections: List<DigestSection>, val raw: String?)

internal fun classifyDigestKey(key: String): DigestSectionKind {
    val k = key.lowercase(Locale.ROOT).filter { it.isLetter() }
    return when {
        "pending" in k || "approval" in k || "question" in k || "attention" in k -> DigestSectionKind.PENDING
        "error" in k -> DigestSectionKind.ERRORS
        "assistant" in k -> DigestSectionKind.LAST_ASSISTANT
        "deliver" in k || "present" in k -> DigestSectionKind.DELIVERABLES
        "changed" in k || "changes" in k || "file" in k -> DigestSectionKind.CHANGED
        k == "state" || k == "status" -> DigestSectionKind.STATE
        else -> DigestSectionKind.OTHER
    }
}

private fun flattenDigestValue(value: Any?, out: MutableList<String>) {
    when (value) {
        null, JSONObject.NULL -> Unit
        is JSONArray -> for (i in 0 until value.length()) flattenDigestValue(value.opt(i), out)
        is JSONObject -> {
            val parts = value.keys().asSequence().mapNotNull { k ->
                val v = value.opt(k)
                if (v == null || v == JSONObject.NULL || v is JSONArray || v is JSONObject) null else "$k: $v"
            }.toList()
            if (parts.isNotEmpty()) out.add(parts.joinToString(", "))
        }
        else -> value.toString().takeIf { it.isNotBlank() }?.let(out::add)
    }
}

/** Defensive digest parser; accepts any JSON object, or falls back to bounded raw text. */
fun parseDigest(digestJson: String?): DigestView {
    if (digestJson.isNullOrBlank()) return DigestView(emptyList(), null)
    val obj = runCatching { JSONObject(digestJson) }.getOrNull()
        ?: return DigestView(emptyList(), boundedPreview(digestJson, 2_400))
    val sections = ArrayList<DigestSection>()
    for (key in obj.keys()) {
        val lines = ArrayList<String>()
        flattenDigestValue(obj.opt(key), lines)
        if (lines.isEmpty()) continue
        val kind = classifyDigestKey(key)
        val bounded = lines.take(DELEGATION_DIGEST_MAX_LINES).map { boundedPreview(it, DELEGATION_DIGEST_LINE_CHARS) }
        sections.add(
            DigestSection(
                kind = kind,
                title = if (kind == DigestSectionKind.OTHER) humanizeKey(key) else null,
                lines = bounded,
                hiddenCount = (lines.size - bounded.size).coerceAtLeast(0)
            )
        )
    }
    if (sections.isEmpty()) return DigestView(emptyList(), boundedPreview(digestJson, 2_400))
    return DigestView(sections.sortedBy { it.kind.ordinal }, null)
}

internal fun humanizeKey(key: String): String {
    val spaced = key.replace(Regex("([a-z0-9])([A-Z])"), "$1 $2").replace('_', ' ').trim()
    return spaced.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }
}
