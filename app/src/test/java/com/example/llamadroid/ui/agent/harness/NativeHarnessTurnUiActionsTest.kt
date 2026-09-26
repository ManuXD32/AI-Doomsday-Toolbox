package com.example.llamadroid.ui.agent.harness

import com.example.llamadroid.harness.client.HarnessAuthResult
import com.example.llamadroid.harness.client.HarnessCallPolicy
import com.example.llamadroid.harness.client.HarnessClient
import com.example.llamadroid.harness.client.HarnessConnectionState
import com.example.llamadroid.harness.client.HarnessRpcResult
import com.example.llamadroid.harness.client.HarnessStreamPolicy
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class NativeHarnessTurnUiActionsTest {
    @Test
    fun clipboardFailureIsRecoverableAndClearsBusyState(): Unit = runBlocking {
        val state = MutableStateFlow(NativeHarnessUiState(selectedSessionId = "one"))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val failure = CompletableDeferred<String>()
        val client = TurnClient { copiedPage() }
        val actions = NativeHarnessTurnUiActions(scope, { client }, { state.value },
            { ordinaryHarnessSessionAddress(it).toWire() }, { state.update(it) },
            deliverCopy = { _, text ->
                assertEquals("complete answer", text)
                error("private platform error")
            }, forked = { _, _ -> error("unexpected fork") }, report = { code, message ->
                assertEquals("Transcript action failed", message)
                failure.complete(code)
            })
        try {
            actions.dispatch(NativeHarnessUiAction.CopyTranscriptTurn("one", 1, 9, 8))
            assertEquals("TRANSCRIPT_ACTION_FAILED", withTimeout(5_000) { failure.await() })
            withTimeout(5_000) { while (state.value.isCopyingTranscript) delay(5) }
            assertEquals(null, state.value.notice)
        } finally { actions.close(); scope.cancel() }
    }

    @Test
    fun cancellingCopyReleasesBusyStateWithoutPublishingLateClipboardText(): Unit = runBlocking {
        val state = MutableStateFlow(NativeHarnessUiState(selectedSessionId = "one"))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val entered = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val never = CompletableDeferred<Unit>()
        val client = TurnClient {
            entered.complete(Unit)
            try { never.await(); copiedPage() } finally { cancelled.complete(Unit) }
        }
        val copied = CompletableDeferred<Unit>()
        val actions = NativeHarnessTurnUiActions(scope, { client }, { state.value },
            { ordinaryHarnessSessionAddress(it).toWire() }, { state.update(it) },
            deliverCopy = { _, _ -> copied.complete(Unit) }, forked = { _, _ -> },
            report = { _, _ -> error("cancellation must not report failure") })
        try {
            actions.dispatch(NativeHarnessUiAction.CopyTranscriptTurn("one", 1, 9, 8))
            withTimeout(5_000) { entered.await() }
            actions.dispatch(NativeHarnessUiAction.CancelTranscriptCopy)
            withTimeout(5_000) {
                cancelled.await()
                while (state.value.isCopyingTranscript) delay(5)
            }
            assertFalse(copied.isCompleted)
        } finally { actions.close(); scope.cancel() }
    }

    private class TurnClient(private val read: suspend () -> HarnessRpcResult) : HarnessClient {
        override val state = MutableStateFlow(HarnessConnectionState.DISCONNECTED)
        override suspend fun authenticate(launchUrl: String): HarnessAuthResult = error("unused")
        override fun adoptAuthenticatedEndpoint(origin: String, cookieHeader: String): HarnessAuthResult = error("unused")
        override suspend fun call(namespace: String, method: String, args: JsonObject,
            policy: HarnessCallPolicy, requestId: String): HarnessRpcResult {
            assertEquals("session", namespace)
            assertEquals("page", method)
            return read()
        }
        override fun stream(namespace: String, method: String, args: JsonObject, policy: HarnessStreamPolicy) = emptyFlow<JsonElement>()
        override fun close() = Unit
    }

    private fun copiedPage(): HarnessRpcResult = HarnessRpcResult.Success(buildJsonObject {
        put("hasMore", false)
        putJsonArray("records") {
            add(buildJsonObject {
                put("seq", 8)
                put("type", "assistant/message")
                put("surfaceOp", "append")
                putJsonObject("data") {
                    put("turn", 1)
                    putJsonObject("message") {
                        putJsonArray("content") { add(buildJsonObject {
                            put("type", "text"); put("text", "complete answer")
                        }) }
                    }
                }
            })
        }
    })
}
