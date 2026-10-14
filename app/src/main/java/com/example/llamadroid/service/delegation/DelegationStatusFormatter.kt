package com.example.llamadroid.service.delegation

import com.example.llamadroid.data.model.NativeChatDelegateTaskEntity
import com.example.llamadroid.data.model.NativeChatDelegateTaskState
import org.json.JSONArray
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** One result file as shown to the main LLM (`f12 report.pdf`). */
data class DelegationResultFileRef(val name: String, val attachmentId: Long?) {
    val reference: String get() = if (attachmentId != null && attachmentId > 0) "f$attachmentId $name" else name
}

/** Pure, bounded model-facing task status text. */
object DelegationStatusFormatter {
    const val BRIEF_MAX_CHARS = 1_500
    const val FULL_MAX_CHARS = 2_500
    private const val BRIEF_DIGEST_LINES = 3

    fun parseResultFiles(json: String?): List<DelegationResultFileRef> {
        if (json.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(json)
            (0 until array.length()).mapNotNull { i ->
                val o = array.optJSONObject(i) ?: return@mapNotNull null
                val name = o.optString("name").takeIf { it.isNotBlank() }
                    ?: o.optString("path").substringAfterLast('/').takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null
                val id = if (o.has("attachmentId") && !o.isNull("attachmentId")) o.optLong("attachmentId", -1L).takeIf { it > 0 } else null
                DelegationResultFileRef(name, id)
            }
        }.getOrDefault(emptyList())
    }

    fun statusText(
        task: NativeChatDelegateTaskEntity,
        live: DelegationLiveStatus?,
        detail: DelegationStatusDetail,
        nowMs: Long = System.currentTimeMillis()
    ): String {
        val limit = if (detail == DelegationStatusDetail.FULL) FULL_MAX_CHARS else BRIEF_MAX_CHARS
        val active = task.state in NativeChatDelegateTaskState.ACTIVE
        val footer = if (active) footer(task, nowMs) else ""
        val bodyLimit = (limit - footer.length - 1).coerceAtLeast(200)
        val endMs = task.finishedAt ?: nowMs
        val lines = mutableListOf<String>()
        lines += "DeepSeek task \"${task.title.take(60)}\": state ${task.state}, elapsed ${formatElapsed(endMs - task.createdAt)}"
        live?.phase?.takeIf { active && it.isNotBlank() }?.let { lines += "Phase: $it" }
        task.progressNote?.takeIf { it.isNotBlank() }?.let { lines += "Progress: " + it.trim().take(300) }
        val used = live?.subagentContextUsed ?: task.ctxUsedTokens
        val window = live?.subagentContextWindow ?: task.ctxWindowTokens
        if (active && window > 0) lines += "Subagent context: ${(used * 100L / window).coerceIn(0, 100)}%"
        val pending = live?.pendingAttention ?: 0
        if (active && pending > 0) lines += "Harness is waiting on $pending pending item(s)."
        if (active && task.state == NativeChatDelegateTaskState.WAITING_MAIN) {
            lines += "The subagent asked you a question: answer it with deepseek_task_message."
        }
        if (task.state == NativeChatDelegateTaskState.NEEDS_DIRECTION || task.state == NativeChatDelegateTaskState.INTERRUPTED) {
            lines += "The task is stopped. Use deepseek_task_message to guide/resume it or deepseek_task_cancel."
        }
        val digest = HarnessTaskDigest.fromJson(task.digestJson)
        if (digest != null && (active || detail == DelegationStatusDetail.FULL)) {
            val rendered = digest.render(if (detail == DelegationStatusDetail.FULL) 1_200 else 600)
            val text = if (detail == DelegationStatusDetail.FULL) rendered
            else rendered.lines().take(BRIEF_DIGEST_LINES).joinToString("\n")
            lines += "Last harness digest:\n$text"
        }
        if (!active) {
            task.resultSummary?.takeIf { it.isNotBlank() }?.let {
                lines += "Result (${task.resultStatus ?: task.state.lowercase()}): " +
                    it.trim().take(if (detail == DelegationStatusDetail.FULL) 1_200 else 500)
            }
            val files = parseResultFiles(task.resultFilesJson)
            if (files.isNotEmpty()) lines += "Files: " + files.take(20).joinToString(", ") { it.reference }
        }
        task.lastErrorCode?.takeIf { it.isNotBlank() }?.let { lines += "Last error: $it" }
        val body = lines.joinToString("\n").let {
            if (it.length <= bodyLimit) it else it.take(bodyLimit - 1).trimEnd() + "…"
        }
        return if (footer.isEmpty()) body else "$body\n$footer"
    }

    fun footer(task: NativeChatDelegateTaskEntity, nowMs: Long = System.currentTimeMillis()): String {
        val cadence = task.checkCadenceMinutes?.takeIf { it > 0 }?.toString() ?: "none"
        val next = task.nextCheckAt?.takeIf { it > nowMs }
            ?.let { SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(it)) } ?: "none"
        return "To check again later, call deepseek_schedule_check(minutes=N) (last cadence: $cadence min; " +
            "current reminder: $next). Re-arm it if you still want updates."
    }

    fun formatElapsed(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return when {
            s < 60 -> "${s}s"
            s < 3600 -> "${s / 60}m"
            else -> "${s / 3600}h${(s % 3600) / 60}m"
        }
    }
}

/** Builds the `[DeepSeek task finished]` wake notice. Bounded below the wake coalescing limit. */
object DelegationResultNotice {
    const val MAX_CHARS = 1_800
    private const val MAX_SUMMARY_CHARS = 1_000

    fun finished(title: String, status: String, summary: String, files: List<DelegationResultFileRef>): String {
        val tail = buildString {
            if (files.isNotEmpty()) {
                append("\nFiles (attached to your next reply automatically): ")
                append(files.take(20).joinToString(", ") { it.reference })
            }
            append("\nRelay the result to the user")
            append(if (files.isNotEmpty()) " and mention the attached files." else ".")
        }
        val head = "${DelegationNotices.TAG_FINISHED} Task \"${title.trim().take(60)}\" finished ($status)."
        val room = (MAX_CHARS - head.length - tail.length - "\nSummary: ".length).coerceIn(80, MAX_SUMMARY_CHARS)
        val clipped = summary.trim().let { if (it.length <= room) it else it.take(room - 1).trimEnd() + "…" }
        return (head + "\nSummary: " + clipped + tail).take(MAX_CHARS)
    }

    fun needsDirection(reason: String): String =
        "[DeepSeek task needs direction] ${reason.trim().take(400)} Use deepseek_task_message to guide it or deepseek_task_cancel."

    fun question(text: String): String =
        "[DeepSeek subagent question] ${text.trim().take(1_200)} Answer with deepseek_task_message."
}
