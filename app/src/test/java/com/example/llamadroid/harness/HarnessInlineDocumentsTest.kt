package com.example.llamadroid.harness

import com.example.llamadroid.harness.client.HarnessAuthResult
import com.example.llamadroid.harness.client.HarnessCallPolicy
import com.example.llamadroid.harness.client.HarnessClient
import com.example.llamadroid.harness.client.HarnessConnectionState
import com.example.llamadroid.harness.client.HarnessRpcResult
import com.example.llamadroid.harness.client.HarnessStreamPolicy
import java.util.Base64
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HarnessInlineDocumentsTest {
    @Test
    fun skillAndLinePreviewUseTheCapturedSessionContract(): Unit = runBlocking {
        val calls = mutableListOf<JsonObject>()
        val client = Client { namespace, method, args ->
            calls += args
            when (namespace to method) {
                "skills" to "list" -> success {
                    putJsonArray("skills") {
                        add(buildJsonObject { put("name", "review"); put("path", "/harness/home/skills/review/SKILL.md") })
                    }
                }
                else -> {
                    assertEquals("workspaceFiles", namespace)
                    assertEquals("read", method)
                    assertEquals(41, args["range"]!!.jsonObject["offset"]!!.jsonPrimitive.int)
                    success {
                        put("absolutePath", "/workspace/projects/demo/a.kt"); put("version", "v1")
                        put("offset", 41); put("text", "line 41"); put("lines", 1); put("eof", true)
                    }
                }
            }
        }
        val reader = HarnessInlineDocumentReader(client, "captured-session")
        assertEquals("/harness/home/skills/review/SKILL.md", reader.skillPath("review"))
        assertEquals("line 41", reader.page("a.kt", 41).text)
        assertEquals("captured-session", calls[0]["request"]!!.jsonObject["sessionId"]!!.jsonPrimitive.content)
        assertEquals("captured-session", calls[1]["workspaceFileScopeId"]!!.jsonPrimitive.content)
    }

    @Test
    fun documentPagesRemainScopedAndPreserveCompleteBytes(): Unit = runBlocking {
        val bytes = ByteArray(HarnessInlineDocumentReader.BYTE_PAGE + 17) { (it % 251).toByte() }
        var calls = 0
        val reader = HarnessInlineDocumentReader(Client { _, method, args ->
            assertEquals("readBytes", method)
            assertEquals("session-a", args["workspaceFileScopeId"]!!.jsonPrimitive.content)
            val offset = args["range"]!!.jsonObject["offset"]!!.jsonPrimitive.int
            val count = minOf(HarnessInlineDocumentReader.BYTE_PAGE, bytes.size - offset)
            calls++
            bytePage(bytes.copyOfRange(offset, offset + count), offset, bytes.size, "v1")
        }, "session-a")
        assertArrayEquals(bytes, reader.document("result.pdf"))
        assertEquals(2, calls)
    }

    @Test
    fun changingOrOversizedFilesFailWithoutReturningAPartialDocument(): Unit = runBlocking {
        var calls = 0
        val reader = HarnessInlineDocumentReader(Client { _, _, args ->
            val offset = args["range"]!!.jsonObject["offset"]!!.jsonPrimitive.int
            calls++
            bytePage(ByteArray(if (offset == 0) HarnessInlineDocumentReader.BYTE_PAGE else 1), offset,
                HarnessInlineDocumentReader.BYTE_PAGE + 1, if (calls == 1) "v1" else "v2")
        }, "session-a")
        assertEquals("INLINE_FILE_CHANGED", runCatching { reader.document("changing.bin") }.exceptionOrNull()?.message)
        val oversized = HarnessInlineDocumentReader(Client { _, _, _ ->
            bytePage(ByteArray(0), 0, HarnessInlineDocumentReader.MAX_DOCUMENT + 1, "v1")
        }, "session-a")
        assertEquals("INLINE_FILE_TOO_LARGE", runCatching { oversized.document("large.bin") }.exceptionOrNull()?.message)
    }

    @Test
    fun cancellingAFileReadCancelsItsInFlightRpc(): Unit = runBlocking {
        val entered = CompletableDeferred<Unit>()
        var calls = 0
        val reader = HarnessInlineDocumentReader(Client { _, _, _ ->
            calls++
            entered.complete(Unit)
            awaitCancellation()
        }, "session-a")
        val job = async { reader.document("slow.pdf") }
        entered.await()
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
        assertEquals(1, calls)
    }

    private fun bytePage(bytes: ByteArray, offset: Int, total: Int, version: String) = success {
        put("offset", offset); put("bytes", total); put("version", version); put("absolutePath", "/workspace/result")
        put("data", Base64.getEncoder().encodeToString(bytes)); put("eof", offset + bytes.size == total)
    }
    private fun success(body: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit) =
        HarnessRpcResult.Success(buildJsonObject(body))

    private class Client(val reply: suspend (String, String, JsonObject) -> HarnessRpcResult) : HarnessClient {
        override val state = MutableStateFlow(HarnessConnectionState.READY)
        override suspend fun authenticate(launchUrl: String): HarnessAuthResult = error("Unused")
        override fun adoptAuthenticatedEndpoint(origin: String, cookieHeader: String): HarnessAuthResult = error("Unused")
        override suspend fun call(namespace: String, method: String, args: JsonObject, policy: HarnessCallPolicy, requestId: String) =
            reply(namespace, method, args)
        override fun stream(namespace: String, method: String, args: JsonObject, policy: HarnessStreamPolicy) = emptyFlow<JsonElement>()
        override fun close() = Unit
    }
}
