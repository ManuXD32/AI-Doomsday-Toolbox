package com.example.llamadroid.service.chat

import com.example.llamadroid.data.repository.LlamaRepository
import com.example.llamadroid.service.OllamaService
import com.example.llamadroid.service.canonicalToolArguments

/**
 * Glue between the tool loop of `LlamaClientService` and the persisted steps of the reply row: each
 * call records into the [recorder] and writes `stepsJson` straight away (one DB update), so the row
 * is always the source of truth for the UI and survives Stop or a crash. Keeps the service file small.
 */
internal class NativeChatStepSession(
    val recorder: NativeChatStepRecorder,
    private val repository: LlamaRepository,
    private val messageId: Long
) {
    /**
     * A round ended with tool calls: its visible text and thinking become steps and the live columns
     * are cleared in the same write, so nothing flashes or disappears between rounds.
     */
    suspend fun roundEnded(loopRound: Int, text: String, thinking: String) {
        val round = recorder.roundFor(loopRound)
        recorder.addThinking(round, thinking)
        recorder.addText(round, text)
        recorder.toJson(false)?.let { repository.updateMessageStepsAndText(messageId, "", null, it) }
    }

    /** Records a RUNNING tool step; returns its index for [toolFinished]. */
    suspend fun toolStarted(loopRound: Int, toolCall: OllamaService.ToolCall): Int {
        val index = recorder.startTool(
            recorder.roundFor(loopRound), toolCall.id, toolCall.name, canonicalToolArguments(toolCall)
        )
        save()
        return index
    }

    suspend fun toolFinished(index: Int, succeeded: Boolean, result: String, startedAtMs: Long) {
        recorder.finishTool(
            index,
            if (succeeded) NativeChatStepStatus.OK else NativeChatStepStatus.ERROR,
            result,
            System.currentTimeMillis() - startedAtMs
        )
        save()
    }

    /** A call that was not executed; [reason] is stored as its result and also sent to the model. */
    suspend fun toolSkipped(loopRound: Int, toolCall: OllamaService.ToolCall, reason: String) {
        recorder.addSkippedTool(
            recorder.roundFor(loopRound), toolCall.id, toolCall.name, canonicalToolArguments(toolCall), reason
        )
        save()
    }

    /**
     * The round limit ended the turn: the closing progress note (written without tools) becomes a TEXT
     * step, followed by a NOTICE, and the reply stays resumable (`complete=false`).
     */
    suspend fun limitReached(loopRound: Int, progressNote: String, notice: String) {
        val round = recorder.roundFor(loopRound)
        recorder.limitReached = true
        recorder.addText(round, progressNote)
        recorder.addNotice(round, notice)
        save()
    }

    /** Records app messages sent to the model after the tool results of [loopRound] (warnings, reviews). */
    suspend fun notes(loopRound: Int, notes: List<NativeChatTurnNotes.Note>) {
        if (notes.isEmpty()) return
        val round = recorder.roundFor(loopRound)
        notes.forEach { recorder.addNote(round, it.text, it.role, it.imagePath) }
        save()
    }

    suspend fun note(loopRound: Int, note: NativeChatTurnNotes.Note) = notes(loopRound, listOf(note))

    /** Once-per-turn hints (DeepSeek...) go right after the user message: round [loopRound] 0, before any model output. */
    suspend fun turnHints(hints: List<String>) = notes(0, hints.map { NativeChatTurnNotes.Note(it) })

    suspend fun save(complete: Boolean = false) = recorder.persist(repository, messageId, complete)

    companion object {
        const val SKIPPED_DUPLICATE = NativeChatTurnNotes.SKIPPED_DUPLICATE

        fun skippedLimit(toolName: String) = "Skipped: $toolName already completed in this turn and is not run again."
    }
}
