package com.example.llamadroid.harness

import com.example.llamadroid.harness.client.HarnessAuthResult
import com.example.llamadroid.harness.client.HarnessCallPolicy
import com.example.llamadroid.harness.client.HarnessClient
import com.example.llamadroid.harness.client.HarnessConnectionState
import com.example.llamadroid.harness.client.HarnessRpcError
import com.example.llamadroid.harness.client.HarnessRpcResult
import com.example.llamadroid.harness.client.HarnessStreamPolicy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HarnessReferencesTest {
    @Test fun canonicalFileGrammarAndActiveMentionReplacementMatchThePinnedRelease() {
        assertEquals("@src/main.kt", formatHarnessFileMention("src/main.kt", false))
        assertEquals("@\"two words.txt\"", formatHarnessFileMention("two words.txt", false))
        assertEquals("@\"two words/", formatHarnessFileMention("two words", true))
        assertNull(formatHarnessFileMention("bad\npath", false))
        assertNull(formatHarnessFileMention("bad\"path", false))
        assertEquals("Read @src/main.kt ", insertHarnessReference("Read @sr", "@src/main.kt"))
        assertEquals("Read @\"two words.txt\" ", insertHarnessReference("Read @\"two wor", "@\"two words.txt\""))
        assertEquals("me@example.org @src/main.kt ", insertHarnessReference("me@example.org", "@src/main.kt"))
        assertEquals("Read @\"two words/", insertHarnessReference("Read @two", "@\"two words/"))
    }

    @Test fun oneUnavailableDomainKeepsTheOtherAndBothLookupsKeepTheCapturedSession() = runBlocking {
        val calls = mutableListOf<Pair<String, JsonObject>>()
        val client = object : HarnessClient {
            override val state = MutableStateFlow(HarnessConnectionState.READY)
            override suspend fun authenticate(launchUrl: String) = HarnessAuthResult.Success("http://127.0.0.1:1")
            override fun adoptAuthenticatedEndpoint(origin: String, cookieHeader: String) = HarnessAuthResult.Success(origin)
            override fun close() = Unit
            override fun stream(namespace: String, method: String, args: JsonObject, policy: HarnessStreamPolicy) = emptyFlow<JsonElement>()
            override suspend fun call(namespace: String, method: String, args: JsonObject, policy: HarnessCallPolicy, requestId: String): HarnessRpcResult {
                calls += "$namespace/$method" to args
                return if (namespace == "fileReferences") HarnessRpcResult.Failure(HarnessRpcError("FILES_UNAVAILABLE", "Unavailable"))
                else HarnessRpcResult.Success(Json.parseToJsonElement("""[{"sessionId":"source","label":"Source","displayTitle":"Child","mention":"@[Child](dsh-session:source)","cwd":"/workspace/projects/a"}]"""))
            }
        }
        val result = loadHarnessReferences(client, "captured-destination", "part")
        assertTrue(result.incomplete)
        assertEquals("@[Child](dsh-session:source)", result.entries.single().mention)
        assertEquals("Child", result.entries.single().title)
        assertEquals(setOf("fileReferences/list", "sessionReferenceResolver/candidates"), calls.map { it.first }.toSet())
        assertTrue(calls.all { it.second.getValue("agentId").jsonPrimitive.content == "captured-destination" && "agent" !in it.second })
        assertTrue(calls.all { it.second.getValue("query").jsonPrimitive.content == "part" })
    }
}
