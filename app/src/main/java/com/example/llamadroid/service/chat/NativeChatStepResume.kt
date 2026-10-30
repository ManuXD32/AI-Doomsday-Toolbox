package com.example.llamadroid.service.chat

import com.example.llamadroid.data.model.LlamaMessageEntity

/**
 * Continue: one path for an interrupted tool loop, a reply stopped mid-sentence and a finished reply
 * the user wants to extend. The loop runs with the same request shape as the turn (tools on) and
 * appends to the same row. No resume instruction is added: if the turn stopped mid-text, the partial
 * text is sent as the trailing assistant message (llama-server prefill) and the generated
 * continuation is appended to it; if it stopped after a tool result, the next round simply runs. Pure.
 */
object NativeChatStepResume {
    /** Recorded (and sent) once when Continue follows the round limit, whose wrap-up note ended the reply. */
    const val AFTER_LIMIT_NOTE =
        "The step limit was reached earlier. Continue the task: call tools again if more work is needed, then give the final answer."

    /**
     * [message] keeps the partial `content` / `thinking` to prefill (the live columns are NOT cleared);
     * [steps] are the sealed steps, `message.stepsJson` their encoding (null when there are none).
     */
    class Resumed(val message: LlamaMessageEntity, val steps: NativeChatSteps)

    /**
     * Seals RUNNING tools as errors. Partial thinking without text cannot be continued, so it only
     * becomes a display THINKING step of its own round (never sent). Returns null when [message] is not
     * continuable: not an assistant reply, or a finished reply while [allowComplete] is false.
     */
    fun prepare(message: LlamaMessageEntity, allowComplete: Boolean = false): Resumed? {
        if (message.role != "assistant") return null
        val stored = NativeChatStepHistory.decode(message.stepsJson)
        if ((stored == null || stored.complete) && !allowComplete) return null
        val sealed = NativeChatStepRecorder.sealInterrupted(stored?.steps.orEmpty())
        val round = (sealed.maxOfOrNull { it.round } ?: -1) + 1
        val extra = buildList {
            val partialText = message.content.isNotBlank()
            val partialThinking = message.thinking?.trim().orEmpty()
            if (!partialText && partialThinking.isNotEmpty()) {
                add(NativeChatStep(NativeChatStepType.THINKING, round, NativeChatStepsCodec.boundThinking(partialThinking)))
            }
            if (sealed.lastOrNull()?.type == NativeChatStepType.NOTICE) {
                add(NativeChatStep(NativeChatStepType.NOTE, round + size, AFTER_LIMIT_NOTE))
            }
        }
        val steps = NativeChatSteps(v = stored?.v ?: 1, steps = sealed + extra, complete = false)
        val keepThinking = message.content.isNotBlank()
        return Resumed(
            message = message.copy(
                thinking = message.thinking.takeIf { keepThinking },
                stepsJson = if (steps.isEmpty) null else NativeChatStepsCodec.encode(steps)
            ),
            steps = steps
        )
    }
}
