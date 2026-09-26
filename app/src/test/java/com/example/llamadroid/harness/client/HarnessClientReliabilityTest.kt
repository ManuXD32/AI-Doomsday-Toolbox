package com.example.llamadroid.harness.client

import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class HarnessClientReliabilityTest {
    @Test fun pinnedSessionFollowReplayIdentityUsesNestedEventSequence() {
        val event = buildJsonObject {
            put("type", "event")
            putJsonObject("event") {
                put("type", "assistant/message")
                put("seq", 17)
                put("time", 1234)
                put("data", "bounded")
            }
        }
        val snapshot = buildJsonObject {
            put("type", "snapshot")
            put("cursor", 17)
            put("records", kotlinx.serialization.json.buildJsonArray {})
        }
        assertEquals("seq:17", durableEventKey(event))
        assertEquals(null, durableEventKey(snapshot))
        assertEquals(null, durableEventKey(buildJsonObject { put("type", "event"); put("id", "reused") }))
    }

    @Test fun pinnedRemoteMuxRejectsExtraServerFrameFields() {
        val malformed = buildJsonObject {
            put("type", "item")
            put("streamId", "stream")
            put("value", 1)
            put("unexpected", true)
        }
        assertTrue(runCatching { parseStreamFrame(malformed.toString()) }.isFailure)
    }

    @Test fun concurrentDuplicateRequestsShareOneMutationAndRejectDifferentArguments() = runBlocking {
        val receipts = HarnessRequestReceipts()
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        var calls = 0
        val first = async(start = CoroutineStart.UNDISPATCHED) {
            receipts.execute("request", "same") { calls++; started.complete(Unit); finish.await(); HarnessRpcResult.Success(JsonPrimitive(7)) }
        }
        started.await()
        val second = async(start = CoroutineStart.UNDISPATCHED) {
            receipts.execute("request", "same") { error("Duplicate mutation ran") }
        }
        assertEquals("client/request-id-reused", (receipts.execute("request", "different") { error("Conflicting mutation ran") } as HarnessRpcResult.Failure).error.code)
        finish.complete(Unit)
        assertEquals(first.await(), second.await())
        assertEquals(1, calls)
        assertEquals(first.await(), receipts.execute("request", "same") { error("Completed mutation replayed") })
    }

    @Test fun cancellingRpcReleasesTheHttpCallAndDoesNotReplayTheMutation() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val calls = AtomicInteger()
        val server = object : NanoHTTPD("127.0.0.1", 0) {
            override fun serve(session: IHTTPSession): NanoHTTPD.Response {
                calls.incrementAndGet(); entered.countDown(); release.await(4, TimeUnit.SECONDS)
                return newFixedLengthResponse("{}")
            }
        }
        server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
        val transport = OkHttpClient.Builder().readTimeout(30, TimeUnit.SECONDS).build()
        val client = OkHttpHarnessClient(transport)
        try {
            assertTrue(client.adoptAuthenticatedEndpoint("http://127.0.0.1:${server.listeningPort}", "session=test") is HarnessAuthResult.Success)
            val request = async { client.call("session", "prompt", requestId = "one-mutation") }
            withTimeout(2000) { while (entered.count > 0) delay(5) }
            withTimeout(1000) { request.cancelAndJoin() }
            assertEquals("client/request-cancelled", (client.call("session", "prompt", requestId = "one-mutation") as HarnessRpcResult.Failure).error.code)
            assertEquals(1, calls.get())
        } finally {
            release.countDown(); client.close(); server.stop()
            transport.dispatcher.executorService.shutdownNow(); transport.connectionPool.evictAll()
        }
    }

    @Test fun realOkHttpMuxUsesHttpUpgradeUrlAndRecordsHandshakeStatus() = runBlocking {
        val requestPath = AtomicReference<String>()
        val requestCookie = AtomicReference<String>()
        val requestOrigin = AtomicReference<String>()
        val events = CopyOnWriteArrayList<HarnessTransportEvent>()
        val server = object : NanoHTTPD("127.0.0.1", 0) {
            override fun serve(session: IHTTPSession): NanoHTTPD.Response {
                requestPath.set(session.uri)
                requestCookie.set(session.headers["cookie"])
                requestOrigin.set(session.headers["origin"])
                return newFixedLengthResponse(Response.Status.FORBIDDEN, "text/plain", "")
            }
        }
        server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
        val transport = OkHttpClient.Builder().readTimeout(2, TimeUnit.SECONDS).build()
        val client = OkHttpHarnessClient(transport) { event -> events.add(event) }
        try {
            assertTrue(client.adoptAuthenticatedEndpoint("http://127.0.0.1:${server.listeningPort}", "session=test") is HarnessAuthResult.Success)
            val failed = runCatching {
                client.stream(
                    "session", "follow", policy = HarnessStreamPolicy(
                        maxReconnects = 0, initialBackoffMs = 0, maxBackoffMs = 0
                    )
                ).collect { error("Unexpected stream item") }
            }
            assertTrue(failed.exceptionOrNull() is HarnessTransportException)
            assertEquals("/api/remote.mux", requestPath.get())
            assertEquals("session=test", requestCookie.get())
            assertEquals("http://127.0.0.1:${server.listeningPort}", requestOrigin.get())
            withTimeout(2000) {
                while (events.none {
                    it.kind == HarnessTransportKind.WEBSOCKET &&
                        it.phase == HarnessTransportPhase.FAILURE &&
                        it.webSocketStatus == 403
                }) delay(5)
            }
        } finally {
            client.close(); server.stop()
            transport.dispatcher.executorService.shutdownNow(); transport.connectionPool.evictAll()
        }
    }

    @Test fun streamOverflowIsVisibleInsteadOfSilentlyLosingEvents() = runBlocking {
        val fixture = MuxFixture()
        val entered = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        val result = async {
            runCatching { fixture.mux.open(HarnessEndpoint("session", "follow"), emptyArgs).collect {
                entered.complete(Unit); resume.await()
            } }
        }
        try {
            val socket = fixture.opened()
            assertEquals("session=test", socket.request().header("Cookie"))
            assertEquals("http://127.0.0.1:1234", socket.request().header("Origin"))
            socket.item(1)
            entered.await()
            repeat(300) { socket.item(it + 2) }
            resume.complete(Unit)
            val error = withTimeout(2000) { result.await() }.exceptionOrNull()
            assertTrue(error is HarnessTransportException)
            assertTrue(socket.sent.any { it.contains("\"type\":\"cancel\"") })
        } finally { fixture.mux.close(); result.cancel() }
    }

    @Test fun lateCloseFromReplacedSocketCannotInterruptTheNewStream() = runBlocking {
        val fixture = MuxFixture()
        val first = async { runCatching { fixture.mux.open(HarnessEndpoint("session", "follow"), emptyArgs).toList() } }
        val old = fixture.opened()
        fixture.mux.reset()
        assertTrue(withTimeout(2000) { first.await() }.isFailure)
        val second = async { fixture.mux.open(HarnessEndpoint("session", "follow"), emptyArgs).toList() }
        try {
            val current = fixture.opened(2)
            old.listener.onClosed(old, 1000, "late close")
            current.item(42); current.end()
            assertEquals(listOf(JsonPrimitive(42)), withTimeout(2000) { second.await() })
            assertFalse(current.cancelled)
        } finally { fixture.mux.close(); second.cancel() }
    }

    @Test fun malformedFrameInterruptsTheConnectionAndClosesOwnedStreams() = runBlocking {
        val fixture = MuxFixture()
        val result = async { runCatching { fixture.mux.open(HarnessEndpoint("session", "follow"), emptyArgs).toList() } }
        try {
            val socket = fixture.opened()
            socket.listener.onMessage(socket, "not json")
            assertTrue(withTimeout(2000) { result.await() }.exceptionOrNull() is HarnessTransportException)
            assertTrue(socket.cancelled)
        } finally { fixture.mux.close(); result.cancel() }
    }

    private class MuxFixture {
        val sockets = java.util.concurrent.CopyOnWriteArrayList<FakeSocket>()
        val mux = HarnessMux(
            httpClient = OkHttpClient(),
            webSocketUrl = { "ws://127.0.0.1:1234/api/remote.mux" },
            cookieHeader = { "session=test" },
            openSocket = { request, listener ->
                FakeSocket(request, listener).also { socket ->
                    sockets.add(socket)
                    listener.onOpen(socket, Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(101).message("Switching Protocols").build())
                }
            },
            originHeader = { "http://127.0.0.1:1234" },
        )
        suspend fun opened(count: Int = 1): FakeSocket = withTimeout(2000) {
            while (sockets.size < count || sockets[count - 1].sent.isEmpty()) delay(5)
            sockets[count - 1]
        }
    }

    private class FakeSocket(private val request: Request, val listener: WebSocketListener) : WebSocket {
        val sent = java.util.concurrent.CopyOnWriteArrayList<String>()
        var cancelled = false
        override fun request() = request
        override fun queueSize() = 0L
        override fun send(text: String): Boolean { sent.add(text); return true }
        override fun send(bytes: ByteString) = true
        override fun close(code: Int, reason: String?) = true
        override fun cancel() { cancelled = true }
        private fun streamId() = HarnessJson.parseToJsonElement(sent.first()).jsonObject.getValue("streamId").jsonPrimitive.content
        fun item(value: Int) = listener.onMessage(this, buildJsonObject {
            put("type", "item"); put("streamId", streamId()); put("value", value)
        }.toString())
        fun end() = listener.onMessage(this, buildJsonObject { put("type", "end"); put("streamId", streamId()) }.toString())
    }

    private companion object { val emptyArgs = JsonObject(emptyMap()) }
}
