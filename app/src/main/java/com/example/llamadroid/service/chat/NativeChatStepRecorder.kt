package com.example.llamadroid.service.chat

import com.example.llamadroid.data.repository.LlamaRepository

/**
 * Per-turn, thread-safe collector of the visible steps of one assistant reply. The chat service
 * records into it while the tool loop runs and persists [toJson] into `llama_messages.stepsJson`
 * after every change, so a Stop or crash keeps every finished step.
 *
 * Round numbers: the loop passes its own 0-based round to [roundFor]; a resumed reply continues
 * after the last stored round ([firstRound]). Pure (no Android types) apart from the persistence
 * extension at the bottom.
 */
class NativeChatStepRecorder(initial: NativeChatSteps? = null) {
    private val lock = Any()
    private val steps = ArrayList<NativeChatStep>()
    private var storedChars = 0
    private var dirty = false

    /** First round number new steps of this turn use. */
    val firstRound: Int

    /** True once the tool-round limit stopped the turn; the reply then stays resumable (complete=false). */
    @Volatile
    var limitReached: Boolean = false

    init {
        initial?.steps?.let { steps.addAll(sealInterrupted(it)) }
        storedChars = steps.sumOf { it.text.length + (it.arguments?.length ?: 0) }
        firstRound = (steps.maxOfOrNull { it.round } ?: -1) + 1
    }

    val hasSteps: Boolean get() = synchronized(lock) { steps.isNotEmpty() }

    fun roundFor(loopRound: Int): Int = firstRound + loopRound

    /** True once no more tool steps fit (a reserve stays free for the round-limit wrap-up). */
    val atToolCapacity: Boolean get() = synchronized(lock) { steps.size >= NativeChatStepsCodec.MAX_STEPS - NativeChatStepsCodec.STEP_RESERVE }

    val hasToolSteps: Boolean get() = synchronized(lock) { steps.any { it.type == NativeChatStepType.TOOL } }

    /** Round text is stored verbatim (no trim) so the model sees exactly what it generated. */
    fun addText(round: Int, text: String): Boolean = addPlain(NativeChatStepType.TEXT, round, NativeChatStepsCodec.boundText(text))

    fun addThinking(round: Int, text: String): Boolean =
        addPlain(NativeChatStepType.THINKING, round, NativeChatStepsCodec.boundThinking(text))

    /** An app message sent to the model at this position ([NativeChatStepType.NOTE]); [role] defaults to system. */
    fun addNote(round: Int, text: String, role: String? = null, imagePath: String? = null): Boolean = synchronized(lock) {
        if (text.isBlank() || steps.size >= NativeChatStepsCodec.MAX_STEPS) return@synchronized false
        val bounded = NativeChatStepsCodec.boundText(text)
        storedChars += bounded.length
        dirty = true
        steps += NativeChatStep(
            type = NativeChatStepType.NOTE, round = round, text = bounded,
            role = role?.takeIf { it != "system" }, imagePath = imagePath
        )
        true
    }

    fun addNotice(round: Int, text: String): Boolean = addPlain(NativeChatStepType.NOTICE, round, text.trim())

    /** Adds a RUNNING tool step; returns its index for [finishTool], or -1 when [NativeChatStepsCodec.MAX_STEPS] is reached. */
    fun startTool(round: Int, toolCallId: String?, toolName: String, argumentsJson: String): Int = synchronized(lock) {
        if (steps.size >= NativeChatStepsCodec.MAX_STEPS - NativeChatStepsCodec.STEP_RESERVE) return@synchronized -1
        val args = NativeChatStepsCodec.boundArguments(argumentsJson)
        storedChars += args.length
        dirty = true
        steps += NativeChatStep(
            type = NativeChatStepType.TOOL,
            round = round,
            toolCallId = toolCallId,
            toolName = toolName,
            arguments = args,
            status = NativeChatStepStatus.RUNNING
        )
        steps.lastIndex
    }

    /** Stores the bounded result once; [status] is OK, ERROR or SKIPPED. */
    fun finishTool(index: Int, status: String, result: String, durationMs: Long?) = synchronized(lock) {
        val step = steps.getOrNull(index) ?: return@synchronized
        val (bounded, original) = boundForStorage(result)
        storedChars += bounded.length
        dirty = true
        steps[index] = step.copy(status = status, text = bounded, originalChars = original, durationMs = durationMs)
    }

    /** One call that was not executed (duplicate, per-turn limit); the stored text tells the model why. */
    fun addSkippedTool(round: Int, toolCallId: String?, toolName: String, argumentsJson: String, reason: String): Boolean {
        val index = startTool(round, toolCallId, toolName, argumentsJson)
        if (index < 0) return false
        finishTool(index, NativeChatStepStatus.SKIPPED, reason, 0L)
        return true
    }

    fun snapshot(complete: Boolean): NativeChatSteps = synchronized(lock) {
        NativeChatSteps(steps = steps.toList(), complete = complete && !limitReached)
    }

    /** Encoded steps, or null when nothing was recorded (replies without tools keep a null column). */
    fun toJson(complete: Boolean): String? = synchronized(lock) {
        if (steps.isEmpty()) return@synchronized null
        dirty = false
        NativeChatStepsCodec.encode(snapshot(complete))
    }

    /** Like [toJson] (incomplete) but null when nothing changed since the last encode; used by the periodic flush. */
    fun toJsonIfDirty(): String? = if (synchronized(lock) { dirty }) toJson(false) else null

    /** Stop or error: tool steps still RUNNING can never finish, so they are stored as interrupted errors. */
    fun sealRunning() = synchronized(lock) {
        val sealed = sealInterrupted(steps)
        if (sealed != steps) {
            steps.clear()
            steps.addAll(sealed)
            dirty = true
        }
    }

    private fun addPlain(type: String, round: Int, text: String): Boolean = synchronized(lock) {
        if (text.isBlank() || steps.size >= NativeChatStepsCodec.MAX_STEPS) return@synchronized false
        storedChars += text.length
        dirty = true
        steps += NativeChatStep(type = type, round = round, text = text)
        true
    }

    /** Past a soft total the results are cut harder so one row never outgrows a SQLite cursor window. */
    private fun boundForStorage(result: String): Pair<String, Int?> {
        if (storedChars < SOFT_TOTAL_CHARS) return NativeChatStepsCodec.boundResult(result)
        if (result.length <= TIGHT_HEAD_CHARS + TIGHT_TAIL_CHARS) return result to null
        val omitted = result.length - TIGHT_HEAD_CHARS - TIGHT_TAIL_CHARS
        return (result.take(TIGHT_HEAD_CHARS) + "\n[... $omitted characters omitted ...]\n" + result.takeLast(TIGHT_TAIL_CHARS)) to result.length
    }

    companion object {
        const val INTERRUPTED_RESULT = "tool_error: the tool call was interrupted before it finished."
        private const val SOFT_TOTAL_CHARS = 500_000
        private const val TIGHT_HEAD_CHARS = 1_000
        private const val TIGHT_TAIL_CHARS = 500

        /** Tool steps left RUNNING by a stop or crash become ERROR with a fixed text. Idempotent. */
        fun sealInterrupted(steps: List<NativeChatStep>): List<NativeChatStep> = steps.map { step ->
            if (step.type == NativeChatStepType.TOOL && step.status == NativeChatStepStatus.RUNNING) {
                step.copy(status = NativeChatStepStatus.ERROR, text = step.text.ifBlank { INTERRUPTED_RESULT })
            } else {
                step
            }
        }
    }
}

/** Writes the recorder's steps (null leaves the column untouched). */
suspend fun NativeChatStepRecorder.persist(repository: LlamaRepository, messageId: Long, complete: Boolean = false) {
    if (messageId <= 0L) return
    toJson(complete)?.let { repository.updateMessageSteps(messageId, it) }
}
