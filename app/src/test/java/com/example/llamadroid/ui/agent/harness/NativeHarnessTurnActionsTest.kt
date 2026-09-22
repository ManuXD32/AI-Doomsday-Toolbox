package com.example.llamadroid.ui.agent.harness

import com.example.llamadroid.harness.client.HarnessRpcResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeHarnessTurnActionsTest {
    @Test
    fun assistantTurnCopyPaginatesHistoryAndPreservesVisibleTextOrder(): Unit = runBlocking {
        val transport = FakeTransport { request ->
            when (request.long("beforeSeq")) {
                null -> successPage((5L..9L).map { event(it, "turn/start", 2L) }, hasMore = true)
                5L -> successPage(
                    listOf(
                        event(0, "turn/start", 1),
                        message(1, "user/message", 1, "prompt"),
                        message(2, "assistant/message", 1, "first"),
                        message(3, "assistant/message", 1, "second"),
                        event(4, "turn/end", 1),
                    ),
                    hasMore = false,
                )
                else -> error("unexpected cursor ${request.long("beforeSeq")}")
            }
        }
        val results = mutableListOf<NativeHarnessTurnResult>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val actions = NativeHarnessTurnActions(
            scope = scope,
            transportProvider = { transport },
            selectedSessionProvider = { "session-1" },
            onResult = { results += it },
        )
        try {
            actions.dispatch(
                NativeHarnessTurnAction.CopyAssistantTurn(
                    sessionId = "session-1",
                    turn = 1,
                    throughSequence = 9,
                ),
            ).join()
            val copied = results.single() as NativeHarnessTurnResult.Copied
            assertEquals(NativeHarnessTurnCopyKind.ASSISTANT_TURN, copied.kind)
            assertEquals("first\nsecond", copied.text)
            assertEquals(2, copied.pageCount)
            assertEquals(listOf(null, 5L), transport.pageRequests.map { it.long("beforeSeq") })
            assertEquals("session", transport.pageRequests.first().objectValue("address")?.string("kind"))
            assertEquals(32, transport.pageRequests.first().int("maxMessages"))
        } finally {
            actions.close()
            scope.cancel()
        }
    }

    @Test
    fun userCopyFindsExactMessageAcrossPagesWithoutCopyingRawJson(): Unit = runBlocking {
        val transport = FakeTransport { request ->
            if (request.long("beforeSeq") == null) {
                successPage(listOf(message(8, "assistant/message", 2, "other")), hasMore = true)
            } else {
                successPage(listOf(message(2, "user/message", 1, "copy this")), hasMore = false)
            }
        }
        val results = mutableListOf<NativeHarnessTurnResult>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val actions = NativeHarnessTurnActions(scope, { transport }, { "session-1" }, onResult = { results += it })
        try {
            actions.dispatch(
                NativeHarnessTurnAction.CopyUserMessage("session-1", 1, 8, messageSequence = 2),
            ).join()
            assertEquals("copy this", (results.single() as NativeHarnessTurnResult.Copied).text)
            assertTrue(results.single().toString().contains("copy this"))
            assertFalse(results.single().toString().contains("surfaceOp"))
        } finally {
            actions.close()
            scope.cancel()
        }
    }

    @Test
    fun userCopyAcceptsARealMessageWithoutTurnMetadata(): Unit = runBlocking {
        val transport = FakeTransport { _ ->
            successPage(listOf(messageWithoutTurn(4, "copy without turn")), hasMore = false)
        }
        val results = mutableListOf<NativeHarnessTurnResult>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val actions = NativeHarnessTurnActions(scope, { transport }, { "session-1" }, onResult = { results += it })
        try {
            actions.dispatch(
                NativeHarnessTurnAction.CopyUserMessage(
                    sessionId = "session-1",
                    turn = null,
                    throughSequence = 4,
                    messageSequence = 4,
                ),
            ).join()
            val copied = results.single() as NativeHarnessTurnResult.Copied
            assertEquals("copy without turn", copied.text)
            assertEquals(null, copied.turn)
        } finally {
            actions.close()
            scope.cancel()
        }
    }

    @Test
    fun userCopyDoesNotRejectKnownUiTurnWhenEventOmitsTurn(): Unit = runBlocking {
        val transport = FakeTransport { _ ->
            successPage(listOf(messageWithoutTurn(4, "copy with inferred turn")), hasMore = false)
        }
        val results = mutableListOf<NativeHarnessTurnResult>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val actions = NativeHarnessTurnActions(scope, { transport }, { "session-1" }, onResult = { results += it })
        try {
            actions.dispatch(
                NativeHarnessTurnAction.CopyUserMessage(
                    sessionId = "session-1",
                    turn = 7,
                    throughSequence = 4,
                    messageSequence = 4,
                ),
            ).join()
            val copied = results.single() as NativeHarnessTurnResult.Copied
            assertEquals("copy with inferred turn", copied.text)
            assertEquals(7L, copied.turn)
        } finally {
            actions.close()
            scope.cancel()
        }
    }

    @Test
    fun forkUsesRealAssistantSequenceAndExactSessionForkEnvelope(): Unit = runBlocking {
        val transport = FakeTransport(
            forkHandler = { args ->
                assertEquals("session-1", args.objectValue("request")?.string("sessionId"))
                assertEquals(17L, args.objectValue("request")?.long("atSeq"))
                assertFalse(args.objectValue("request")?.containsKey("increaseTitle") == true)
                HarnessRpcResult.Success(buildJsonObject { put("sessionId", "child-1") })
            },
        )
        val results = mutableListOf<NativeHarnessTurnResult>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val actions = NativeHarnessTurnActions(scope, { transport }, { "session-1" }, onResult = { results += it })
        try {
            actions.dispatch(NativeHarnessTurnAction.ForkAt("session-1", 17)).join()
            assertEquals(
                NativeHarnessTurnResult.Forked("session-1", 17, "child-1"),
                results.single(),
            )
        } finally {
            actions.close()
            scope.cancel()
        }
    }

    @Test
    fun selectionInvalidationDropsInFlightCopyWithoutPublishingStaleText(): Unit = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var selected = "session-1"
        val transport = FakeTransport { _ ->
            entered.complete(Unit)
            release.await()
            successPage(listOf(message(1, "assistant/message", 1, "stale")), hasMore = false)
        }
        val results = mutableListOf<NativeHarnessTurnResult>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val actions = NativeHarnessTurnActions(scope, { transport }, { selected }, onResult = { results += it })
        try {
            val job = actions.dispatch(NativeHarnessTurnAction.CopyAssistantTurn("session-1", 1, 1))
            entered.await()
            selected = "session-2"
            actions.invalidateSelection()
            release.complete(Unit)
            job.join()
            assertTrue(results.isEmpty())
        } finally {
            actions.close()
            scope.cancel()
        }
    }

    @Test
    fun cancellationStopsPageReadAndNeverReportsSuccess(): Unit = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val transport = FakeTransport { _ ->
            gate.await()
            successPage(listOf(message(1, "assistant/message", 1, "cancelled")), hasMore = false)
        }
        val results = mutableListOf<NativeHarnessTurnResult>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val actions = NativeHarnessTurnActions(scope, { transport }, { "session-1" }, onResult = { results += it })
        try {
            val job = actions.dispatch(NativeHarnessTurnAction.CopyAssistantTurn("session-1", 1, 1))
            job.cancel()
            job.join()
            assertTrue(results.isEmpty())
        } finally {
            gate.complete(Unit)
            actions.close()
            scope.cancel()
        }
    }

    @Test
    fun oversizedTextFailsInsteadOfSilentlyTruncatingClipboardPayload(): Unit = runBlocking {
        val transport = FakeTransport { _ ->
            successPage(listOf(message(1, "assistant/message", 1, "x".repeat(256 * 1024 + 1))), hasMore = false)
        }
        val results = mutableListOf<NativeHarnessTurnResult>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val actions = NativeHarnessTurnActions(scope, { transport }, { "session-1" }, onResult = { results += it })
        try {
            actions.dispatch(NativeHarnessTurnAction.CopyAssistantTurn("session-1", 1, 1, finalAssistantSequence = 1)).join()
            assertEquals("TRANSCRIPT_COPY_TOO_LARGE", (results.single() as NativeHarnessTurnResult.Failed).code)
        } finally {
            actions.close()
            scope.cancel()
        }
    }

    @Test
    fun unexpectedTransportFailureBecomesTypedRecoverableResult(): Unit = runBlocking {
        val transport = FakeTransport { _ ->
            throw IllegalStateException("provider echoed private request")
        }
        val results = mutableListOf<NativeHarnessTurnResult>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val actions = NativeHarnessTurnActions(scope, { transport }, { "session-1" }, onResult = { results += it })
        try {
            actions.dispatch(NativeHarnessTurnAction.CopyAssistantTurn("session-1", 1, 1)).join()
            assertEquals(
                NativeHarnessTurnResult.Failed(
                    sessionId = "session-1",
                    code = "TRANSCRIPT_ACTION_FAILED",
                    message = "Transcript action failed",
                ),
                results.single(),
            )
        } finally {
            actions.close()
            scope.cancel()
        }
    }

    @Test
    fun aggregateAssistantCopyFailsBeforeRetainingMoreThanClipboardBudget(): Unit = runBlocking {
        val chunk = "x".repeat(140 * 1024)
        val transport = FakeTransport { request ->
            when (request.long("beforeSeq")) {
                null -> successPage(listOf(message(3, "assistant/message", 1, chunk)), hasMore = true)
                3L -> successPage(
                    listOf(event(0, "turn/start", 1), message(1, "assistant/message", 1, chunk)),
                    hasMore = false,
                )
                else -> error("unexpected cursor ${request.long("beforeSeq")}")
            }
        }
        val results = mutableListOf<NativeHarnessTurnResult>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val actions = NativeHarnessTurnActions(scope, { transport }, { "session-1" }, onResult = { results += it })
        try {
            actions.dispatch(NativeHarnessTurnAction.CopyAssistantTurn("session-1", 1, 3)).join()
            assertEquals("TRANSCRIPT_COPY_TOO_LARGE", (results.single() as NativeHarnessTurnResult.Failed).code)
            assertEquals(2, transport.pageRequests.size)
        } finally {
            actions.close()
            scope.cancel()
        }
    }

    private class FakeTransport(
        private val forkHandler: suspend (JsonObject) -> HarnessRpcResult = {
            HarnessRpcResult.Success(buildJsonObject { put("sessionId", "child") })
        },
        private val pageHandler: suspend (JsonObject) -> HarnessRpcResult = {
            HarnessRpcResult.Success(buildJsonObject {
                putJsonArray("records") {}
                put("hasMore", false)
            })
        },
    ) : NativeHarnessTurnTransport {
        override val identity: Any = Any()
        val pageRequests = mutableListOf<JsonObject>()

        override suspend fun pageSession(request: JsonObject): HarnessRpcResult {
            pageRequests += request
            return pageHandler(request)
        }

        override suspend fun forkSession(callArgs: JsonObject): HarnessRpcResult = forkHandler(callArgs)
    }

    private fun successPage(records: List<JsonObject>, hasMore: Boolean): HarnessRpcResult =
        HarnessRpcResult.Success(buildJsonObject {
            putJsonArray("records") { records.forEach(::add) }
            put("hasMore", hasMore)
        })

    private fun event(sequence: Long, type: String, turn: Long): JsonObject = buildJsonObject {
        put("type", "event")
        putJsonObject("event") {
            put("type", type)
            put("seq", sequence)
            putJsonObject("data") { put("turn", turn) }
        }
    }

    private fun message(sequence: Long, type: String, turn: Long, text: String): JsonObject = buildJsonObject {
        put("type", "event")
        putJsonObject("event") {
            put("type", type)
            put("seq", sequence)
            put("surfaceOp", "append")
            putJsonObject("data") {
                put("turn", turn)
                putJsonObject("message") {
                    putJsonArray("content") {
                        add(buildJsonObject {
                            put("type", "text")
                            put("text", text)
                        })
                    }
                }
            }
        }
    }

    private fun messageWithoutTurn(sequence: Long, text: String): JsonObject = buildJsonObject {
        put("type", "event")
        putJsonObject("event") {
            put("type", "user/message")
            put("seq", sequence)
            put("surfaceOp", "append")
            putJsonObject("data") {
                putJsonObject("message") {
                    putJsonArray("content") {
                        add(buildJsonObject {
                            put("type", "text")
                            put("text", text)
                        })
                    }
                }
            }
        }
    }
}
