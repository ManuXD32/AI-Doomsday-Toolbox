package com.example.llamadroid.harness.client

import com.example.llamadroid.harness.HarnessProjectReviewTransport
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class HarnessHttpRoutesTest {
    @Test fun workspaceTextPreviewUsesTheGeneratedLookupWireAndLineWindow() = runBlocking {
        val captured = AtomicReference<JsonObject>()
        val server = object : NanoHTTPD("127.0.0.1", 0) {
            override fun serve(session: IHTTPSession): Response {
                val body = mutableMapOf<String, String>()
                session.parseBody(body)
                val request = Json.parseToJsonElement(requireNotNull(body["postData"])).jsonObject
                captured.set(request)
                val response = buildJsonObject {
                    put("type", "server-response")
                    put("rpcId", request.getValue("rpcId"))
                    putJsonObject("result") {
                        put("ok", true)
                        putJsonObject("value") { put("text", "line 201"); put("offset", 201); put("lines", 1); put("eof", true) }
                    }
                }
                return newFixedLengthResponse(Response.Status.OK, "application/json", response.toString())
            }
        }
        server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
        val client = OkHttpHarnessClient()
        try {
            client.adoptAuthenticatedEndpoint("http://127.0.0.1:${server.listeningPort}", "session=fixture")
            val result = HarnessProjectReviewTransport { client }.readWorkspaceFile("captured-session", "notes.txt", 201, 200)
            assertEquals("line 201", result.jsonObject.getValue("text").jsonPrimitive.content)
            val request = requireNotNull(captured.get())
            assertEquals("workspaceFiles/read", request.getValue("method").jsonPrimitive.content)
            val args = request.getValue("payload").jsonObject.getValue("args").jsonObject
            assertEquals(setOf("workspaceFileScopeId", "path", "range"), args.keys)
            assertEquals("captured-session", args.getValue("workspaceFileScopeId").jsonPrimitive.content)
            assertEquals("notes.txt", args.getValue("path").jsonPrimitive.content)
            assertEquals("201", args.getValue("range").jsonObject.getValue("offset").jsonPrimitive.content)
            assertEquals("200", args.getValue("range").jsonObject.getValue("limit").jsonPrimitive.content)
        } finally { client.close(); server.stop() }
    }

    @Test fun reviewRoutesUseTheSameCookieQueryEncodingAndMutationReceipt() = runBlocking {
        val hits = AtomicInteger()
        val server = object : NanoHTTPD("127.0.0.1", 0) {
            override fun serve(session: IHTTPSession): Response {
                assertEquals("session=fixture", session.headers["cookie"])
                assertEquals("one /&?", session.parameters["sessionId"]?.single())
                hits.incrementAndGet()
                return if (session.method == Method.POST) newFixedLengthResponse(Response.Status.NO_CONTENT, "application/json", "")
                else newFixedLengthResponse(Response.Status.OK, "application/json", "{\"turn\":7}")
            }
        }
        server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
        val client = OkHttpHarnessClient()
        try {
            client.adoptAuthenticatedEndpoint("http://127.0.0.1:${server.listeningPort}", "session=fixture")
            val query = mapOf("sessionId" to "one /&?", "seq" to "6")
            val read = client.fetchJson("/api/changes.summary", query) as HarnessRpcResult.Success
            assertEquals("7", read.value.jsonObject.getValue("turn").jsonPrimitive.content)
            val first = client.fetchJson("/api/present.open", query, buildJsonObject {}, "open-once")
            assertEquals(JsonNull, (first as HarnessRpcResult.Success).value)
            assertEquals(first, client.fetchJson("/api/present.open", query, buildJsonObject {}, "open-once"))
            assertEquals(2, hits.get())
            assertTrue(client.fetchJson("http://elsewhere/api/changes.summary") is HarnessRpcResult.Failure)
            assertTrue(client.fetchJson("/api/../outside") is HarnessRpcResult.Failure)
            assertEquals(2, hits.get())
        } finally { client.close(); server.stop() }
    }

    @Test fun authenticatedRouteDoesNotFollowRedirectsOrTreatHtmlAsJson() = runBlocking {
        val hits = AtomicInteger()
        val server = object : NanoHTTPD("127.0.0.1", 0) {
            override fun serve(session: IHTTPSession): Response {
                hits.incrementAndGet()
                return if (session.uri == "/api/redirect") newFixedLengthResponse(Response.Status.REDIRECT, "text/plain", "")
                    .also { it.addHeader("Location", "/api/private") }
                else newFixedLengthResponse("<html>not a JSON route</html>")
            }
        }
        server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
        val client = OkHttpHarnessClient()
        try {
            client.adoptAuthenticatedEndpoint("http://127.0.0.1:${server.listeningPort}", "session=fixture")
            assertEquals("transport/http-301", (client.fetchJson("/api/redirect") as HarnessRpcResult.Failure).error.code)
            assertEquals(1, hits.get())
            assertEquals("transport/invalid-response", (client.fetchJson("/api/html") as HarnessRpcResult.Failure).error.code)
        } finally { client.close(); server.stop() }
    }

    @Test fun exactFetchRoutesAllowNestedApiPaths() = runBlocking {
        val uri = AtomicReference<String>()
        val server = object : NanoHTTPD("127.0.0.1", 0) {
            override fun serve(session: IHTTPSession): Response {
                uri.set(session.uri)
                return newFixedLengthResponse(Response.Status.OK, "application/json", "{\"providerIds\":[]}")
            }
        }
        server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
        val client = OkHttpHarnessClient()
        try {
            client.adoptAuthenticatedEndpoint("http://127.0.0.1:${server.listeningPort}", "session=fixture")
            val result = client.fetchJson("/api/custom/providerAuth", mapOf("operation" to "describe"))
            assertEquals("providerIds", (result as HarnessRpcResult.Success).value.jsonObject.keys.single())
            assertEquals("/api/custom/providerAuth", uri.get())
        } finally { client.close(); server.stop() }
    }
}
