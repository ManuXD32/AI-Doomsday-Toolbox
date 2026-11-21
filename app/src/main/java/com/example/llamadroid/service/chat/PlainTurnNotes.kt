package com.example.llamadroid.service.chat

import com.example.llamadroid.data.api.LlamaChatMessage
import com.example.llamadroid.data.model.LlamaMessageEntity

/**
 * Append-only per-turn app messages (extension hints) for replies without a tool loop. The notes are
 * stored as NOTE steps on the assistant row and replayed right after the user message on every later
 * turn, so the model-facing prefix of turn N+1 starts with exactly what turn N sent.
 */
object PlainTurnNotes {
    /**
     * Like [NativeChatStepHistory.flattenEntities], but keeps the round-0 NOTE steps as a notes-only
     * `stepsJson` so the request builder can replay them in place.
     */
    fun flattenKeepingNotes(history: List<LlamaMessageEntity>): List<LlamaMessageEntity> =
        if (history.none { it.role == "assistant" && !it.stepsJson.isNullOrBlank() }) history
        else history.map { message ->
            val steps = if (message.role == "assistant") NativeChatStepHistory.decode(message.stepsJson) else null
            if (steps == null) {
                message
            } else {
                val notes = steps.steps.filter { it.type == NativeChatStepType.NOTE && it.imagePath.isNullOrBlank() }
                message.copy(
                    content = NativeChatStepHistory.flatten(message.content, steps),
                    stepsJson = notes.takeIf { it.isNotEmpty() }
                        ?.let { NativeChatStepsCodec.encode(NativeChatSteps(steps = it, complete = true)) }
                )
            }
        }

    /**
     * A mid-conversation app message as the request carries it. Qwen-style templates reject a system
     * message that is not first, so it travels as a user message tagged like the tools path does
     * (`normalizeLlamaServerSystemMessages`).
     */
    fun noteMessage(text: String, role: String? = null): LlamaChatMessage =
        if (role == null || role == "system") LlamaChatMessage("user", "[Runtime context]\n$text") else LlamaChatMessage(role, text)

    /** Request messages for the notes stored on [message] (empty for anything but an assistant row). */
    fun noteMessages(message: LlamaMessageEntity): List<LlamaChatMessage> {
        if (message.role != "assistant") return emptyList()
        val steps = NativeChatStepHistory.decode(message.stepsJson) ?: return emptyList()
        return steps.steps
            .filter { it.type == NativeChatStepType.NOTE && it.imagePath.isNullOrBlank() && it.text.isNotBlank() }
            .map { noteMessage(it.text, it.role) }
    }

    /**
     * The model-facing transcript of a plain (no tool loop) llama-server turn: per history row its
     * stored hint notes, then the row itself with its reasoning replayed as `reasoning_content` (the
     * chat template decides whether to render it). [contentOf] builds the row's content.
     */
    fun requestMessages(
        history: List<LlamaMessageEntity>,
        contentOf: (index: Int, message: LlamaMessageEntity) -> Any
    ): List<LlamaChatMessage> = history.flatMapIndexed { index, message ->
        noteMessages(message) + LlamaChatMessage(
            message.role,
            contentOf(index, message),
            reasoningContent = message.thinking?.takeIf { message.role == "assistant" && it.isNotBlank() }
        )
    }

    /** Records [hints] as round-0 notes of a fresh recorder; returns it, or null when there is nothing to store. */
    fun recordHints(hints: List<String>): NativeChatStepRecorder? {
        val usable = hints.filter { it.isNotBlank() }
        if (usable.isEmpty()) return null
        return NativeChatStepRecorder().also { recorder ->
            val round = recorder.roundFor(0)
            usable.forEach { recorder.addNote(round, it) }
        }
    }
}
