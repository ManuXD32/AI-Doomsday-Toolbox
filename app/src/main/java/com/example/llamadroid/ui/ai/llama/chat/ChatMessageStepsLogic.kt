package com.example.llamadroid.ui.ai.llama.chat

import com.example.llamadroid.service.chat.NativeChatStep
import com.example.llamadroid.service.chat.NativeChatStepStatus
import com.example.llamadroid.service.chat.NativeChatStepType
import com.example.llamadroid.service.chat.NativeChatSteps
import com.example.llamadroid.service.chat.NativeChatStepsCodec
import java.util.Locale
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Pure helpers of the inline work-steps UI (collapse plan, durations, previews, export lines). */

internal const val STEPS_COLLAPSE_THRESHOLD = 8
internal const val STEPS_COLLAPSE_HEAD = 2
internal const val STEPS_COLLAPSE_TAIL = 2
internal const val STEP_TEXT_PREVIEW_CHARS = 600
internal const val STEP_EXPORT_TEXT_CHARS = 600

internal sealed interface StepRow {
    data class Step(val index: Int) : StepRow
    data class Hidden(val count: Int) : StepRow
}

/**
 * Rows to render for [total] steps. Everything is shown while generating, when the list is short,
 * or after the user expanded it; otherwise only the first and last few steps plus a "show N more" row.
 */
internal fun planStepRows(total: Int, collapsible: Boolean, expanded: Boolean): List<StepRow> {
    if (total <= 0) return emptyList()
    val all = (0 until total).map { StepRow.Step(it) }
    if (!collapsible || expanded || total <= STEPS_COLLAPSE_THRESHOLD) return all
    val hidden = total - STEPS_COLLAPSE_HEAD - STEPS_COLLAPSE_TAIL
    if (hidden <= 0) return all
    return all.take(STEPS_COLLAPSE_HEAD) + StepRow.Hidden(hidden) + all.takeLast(STEPS_COLLAPSE_TAIL)
}

/** "850 ms", "1.2 s", "42 s", "2 min 5 s"; null for a missing or negative duration. */
internal fun formatStepDuration(ms: Long?): String? {
    if (ms == null || ms < 0) return null
    return when {
        ms < 1_000 -> "$ms ms"
        ms < 10_000 -> String.format(Locale.ROOT, "%.1f s", ms / 1000.0)
        ms < 60_000 -> "${(ms + 500) / 1000} s"
        else -> {
            val totalSeconds = (ms + 500) / 1000
            val seconds = totalSeconds % 60
            if (seconds == 0L) "${totalSeconds / 60} min" else "${totalSeconds / 60} min $seconds s"
        }
    }
}

/** Bounded preview: the text and whether it was cut. */
internal fun previewStepText(text: String, limit: Int = STEP_TEXT_PREVIEW_CHARS): Pair<String, Boolean> {
    if (text.length <= limit) return text to false
    var end = limit
    if (end > 0 && Character.isHighSurrogate(text[end - 1])) end -= 1
    return text.substring(0, end).trimEnd() + "…" to true
}

private val PRIMARY_ARGUMENT_KEYS = listOf("query", "url", "path", "file", "name", "title", "id")

/** Short, most descriptive argument of a tool call (query, url, path…), for the card subtitle. */
internal fun toolPrimaryArgument(arguments: String?, maxChars: Int = 80): String? {
    if (arguments.isNullOrBlank()) return null
    val obj = runCatching { Json.parseToJsonElement(arguments) as? JsonObject }.getOrNull() ?: return null
    for (key in PRIMARY_ARGUMENT_KEYS) {
        val value = (obj[key] as? JsonPrimitive)?.content?.trim()?.replace('\n', ' ')
        if (!value.isNullOrBlank()) return value.take(maxChars)
    }
    return null
}

/** A tool step still marked running although nothing is generating means it was interrupted. */
internal fun isInterruptedStep(step: NativeChatStep, generating: Boolean): Boolean =
    step.type == NativeChatStepType.TOOL && step.status == NativeChatStepStatus.RUNNING && !generating

/** Continue is allowed on an assistant message whose tool loop did not finish. */
internal fun hasUnfinishedSteps(stepsJson: String?): Boolean {
    val steps = NativeChatStepsCodec.decode(stepsJson) ?: return false
    return !steps.isEmpty && !steps.complete
}

internal data class StepsExportLabels(
    /** Format with %1$s = tool name and %2$s = status/duration detail. */
    val toolUsed: String,
    val statusOk: String,
    val statusError: String,
    val statusSkipped: String,
    val statusRunning: String
)

/** Plain-text lines for the steps of one reply (export / save as note); thinking is omitted. */
internal fun stepsToPlainLines(steps: NativeChatSteps, labels: StepsExportLabels): String =
    buildString {
        steps.steps.forEach { step ->
            when (step.type) {
                NativeChatStepType.TEXT -> {
                    val text = previewStepText(step.text.trim(), STEP_EXPORT_TEXT_CHARS).first
                    if (text.isNotBlank()) append(text).append("\n\n")
                }
                NativeChatStepType.NOTICE -> {
                    val text = previewStepText(step.text.trim(), STEP_EXPORT_TEXT_CHARS).first
                    if (text.isNotBlank()) append("> ").append(text.replace('\n', ' ')).append("\n\n")
                }
                NativeChatStepType.TOOL -> {
                    val status = when (step.status) {
                        NativeChatStepStatus.OK -> labels.statusOk
                        NativeChatStepStatus.ERROR -> labels.statusError
                        NativeChatStepStatus.SKIPPED -> labels.statusSkipped
                        else -> labels.statusRunning
                    }
                    val detail = listOfNotNull(status, formatStepDuration(step.durationMs)).joinToString(", ")
                    append("> ")
                        .append(String.format(Locale.ROOT, labels.toolUsed, step.toolName ?: "?", detail))
                        .append("\n\n")
                }
                // THINKING and model-facing NOTE steps are not exported.
                else -> Unit
            }
        }
    }
