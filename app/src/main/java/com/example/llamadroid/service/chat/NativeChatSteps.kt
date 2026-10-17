package com.example.llamadroid.service.chat

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Values of [NativeChatStep.type]. */
object NativeChatStepType {
    /** Visible text the model wrote in a round that also called tools (e.g. "I'll read the PDF first."). */
    const val TEXT = "text"
    /** Reasoning of one round; shown folded. Sent back as the assistant's reasoning only inside the current turn (see NativeChatStepHistory). */
    const val THINKING = "thinking"
    /** One tool call with its bounded arguments and result. */
    const val TOOL = "tool"
    /** App-written line inside the reply (e.g. "Round limit reached"); never sent to the model. */
    const val NOTICE = "notice"
    /**
     * App-written note that IS sent to the model at this position (a do-not-repeat warning, the
     * round-limit instruction, the one-turn DeepSeek hint...), so a replayed turn is byte-identical
     * to the live one. Hidden in the chat UI and in exports.
     */
    const val NOTE = "note"
}

/** Values of [NativeChatStep.status] for [NativeChatStepType.TOOL] steps. */
object NativeChatStepStatus {
    const val RUNNING = "running"
    const val OK = "ok"
    const val ERROR = "error"
    /** Not executed (duplicate call, per-turn limit…); [NativeChatStep.text] says why. */
    const val SKIPPED = "skipped"
}

/**
 * One visible work step of an assistant reply. Steps of the same model response share [round]
 * (0-based). For a [NativeChatStepType.TOOL] step, [text] is the bounded result sent back to the
 * model; it is stored once and never rewritten, so the re-sent history stays byte-identical.
 */
@Serializable
data class NativeChatStep(
    val type: String,
    val round: Int,
    val text: String = "",
    val toolCallId: String? = null,
    val toolName: String? = null,
    /** Tool arguments JSON as the model sent them, bounded to [NativeChatStepsCodec.MAX_ARGUMENT_CHARS]. */
    val arguments: String? = null,
    val status: String? = null,
    val durationMs: Long? = null,
    /** Original result length when [text] was shortened by [NativeChatStepsCodec.boundResult]; null when complete. */
    val originalChars: Int? = null,
    /** [NativeChatStepType.NOTE] only: role of the message sent to the model ("system" when null). */
    val role: String? = null,
    /** [NativeChatStepType.NOTE] only: image attached to the message (generated-image review). */
    val imagePath: String? = null
)

/**
 * All steps of one reply, stored in `llama_messages.stepsJson`. The message's `content` stays the
 * final answer. [complete] is false while the turn is still working (or was interrupted mid-loop):
 * Continue then resumes the tool loop from the last step instead of writing plain text.
 */
@Serializable
data class NativeChatSteps(
    val v: Int = 1,
    val steps: List<NativeChatStep> = emptyList(),
    val complete: Boolean = false
) {
    val isEmpty: Boolean get() = steps.isEmpty()
    val lastRound: Int get() = steps.maxOfOrNull { it.round } ?: -1
}

/** JSON encoding and size bounds of [NativeChatSteps]. Pure and deterministic. */
object NativeChatStepsCodec {
    const val RESULT_HEAD_CHARS = 6_000
    const val RESULT_TAIL_CHARS = 1_500
    const val MAX_ARGUMENT_CHARS = 65_536
    const val MAX_TEXT_CHARS = 8_000
    const val MAX_THINKING_CHARS = 8_000
    /** Hard cap on steps per reply; older steps are never dropped, new ones beyond it are not recorded. */
    const val MAX_STEPS = 400
    /** Steps kept free for TEXT / NOTE / NOTICE so the round-limit wrap-up can always be recorded. */
    const val STEP_RESERVE = 8

    private val JSON = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    fun encode(steps: NativeChatSteps): String = JSON.encodeToString(NativeChatSteps.serializer(), steps)

    /** Lenient: null, blank or malformed JSON gives null. */
    fun decode(json: String?): NativeChatSteps? =
        json?.takeIf { it.isNotBlank() }
            ?.let { runCatching { JSON.decodeFromString(NativeChatSteps.serializer(), it) }.getOrNull() }

    /**
     * Keeps the first [RESULT_HEAD_CHARS] and last [RESULT_TAIL_CHARS] characters of a long tool
     * result with a fixed marker between them. Returns the text and the original length when cut.
     */
    fun boundResult(text: String): Pair<String, Int?> {
        if (text.length <= RESULT_HEAD_CHARS + RESULT_TAIL_CHARS) return text to null
        val omitted = text.length - RESULT_HEAD_CHARS - RESULT_TAIL_CHARS
        val bounded = text.take(RESULT_HEAD_CHARS) +
            "\n[... $omitted characters omitted ...]\n" +
            text.takeLast(RESULT_TAIL_CHARS)
        return bounded to text.length
    }

    fun boundArguments(text: String): String = text.take(MAX_ARGUMENT_CHARS)
    fun boundText(text: String): String = text.take(MAX_TEXT_CHARS)
    fun boundThinking(text: String): String = text.take(MAX_THINKING_CHARS)
}
