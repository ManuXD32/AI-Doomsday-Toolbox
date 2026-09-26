package com.example.llamadroid.ui.agent.harness

import com.example.llamadroid.harness.client.HarnessClient
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

/** Connects the bounded turn loader to native transient state; clipboard text is never retained. */
internal class NativeHarnessTurnUiActions(
    private val scope: CoroutineScope,
    private val client: () -> HarnessClient?,
    private val state: () -> NativeHarnessUiState,
    private val address: (String) -> JsonObject?,
    private val update: suspend ((NativeHarnessUiState) -> NativeHarnessUiState) -> Unit,
    private val deliverCopy: suspend (String, String) -> Unit,
    private val forked: suspend (String, String) -> Unit,
    private val report: suspend (String, String) -> Unit,
) : AutoCloseable {
    private val generation = AtomicLong()
    private val backend = NativeHarnessTurnActions(
        scope,
        transportProvider = { client()?.let(::NativeHarnessClientTurnTransport) },
        selectedSessionProvider = { state().selectedSessionId },
        sessionAddressProvider = address,
        onResult = { result ->
            try {
                when (result) {
                    is NativeHarnessTurnResult.Copied -> {
                        if (state().selectedSessionId == result.sessionId) {
                            deliverCopy(result.sessionId, result.text)
                        }
                    }
                    is NativeHarnessTurnResult.Forked -> forked(result.sourceSessionId, result.childSessionId)
                    is NativeHarnessTurnResult.Failed -> report(result.code, result.message)
                    is NativeHarnessTurnResult.Ignored -> Unit
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { report("TRANSCRIPT_ACTION_FAILED", "Transcript action failed") }
        },
    )

    suspend fun dispatch(action: NativeHarnessUiAction) {
        if (action == NativeHarnessUiAction.CancelTranscriptCopy) {
            invalidateSelection()
            return
        }
        val target = when (action) {
            is NativeHarnessUiAction.CopyTranscriptTurn -> NativeHarnessTurnAction.CopyAssistantTurn(
                action.sessionId, action.turn, action.throughSequence, action.finalAssistantSequence,
            )
            is NativeHarnessUiAction.CopyTranscriptMessage -> NativeHarnessTurnAction.CopyUserMessage(
                action.sessionId, action.turn, action.messageSequence, action.messageSequence,
            )
            is NativeHarnessUiAction.ForkTranscriptTurn -> NativeHarnessTurnAction.ForkAt(action.sessionId, action.assistantSequence)
            else -> return
        }
        val selected = when (action) {
            is NativeHarnessUiAction.CopyTranscriptTurn -> action.sessionId
            is NativeHarnessUiAction.CopyTranscriptMessage -> action.sessionId
            is NativeHarnessUiAction.ForkTranscriptTurn -> action.sessionId
            else -> return
        }
        if (selected != state().selectedSessionId) return
        val token = generation.incrementAndGet()
        update { it.copy(isCopyingTranscript = true, notice = null) }
        backend.dispatch(target).invokeOnCompletion {
            scope.launch { if (generation.get() == token) update { it.copy(isCopyingTranscript = false) } }
        }
    }

    fun invalidateSelection() {
        val token = generation.incrementAndGet()
        backend.invalidateSelection()
        scope.launch { if (generation.get() == token) update { it.copy(isCopyingTranscript = false) } }
    }

    override fun close() = backend.close()
}
