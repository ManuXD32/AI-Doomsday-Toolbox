package com.example.llamadroid.ui.agent.harness

import com.example.llamadroid.harness.client.HarnessAuthResult
import com.example.llamadroid.harness.client.HarnessCallPolicy
import com.example.llamadroid.harness.client.HarnessClient
import com.example.llamadroid.harness.client.HarnessConnectionState
import com.example.llamadroid.harness.client.HarnessRpcResult
import com.example.llamadroid.harness.client.HarnessStreamPolicy
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeHarnessSessionProjectionReaderTest {
    @Test
    fun oneSnapshotReadsAllListPagesAndOneWorkspaceBaseline() = runBlocking {
        val client = ProjectionClient()
        val reader = NativeHarnessSessionProjectionReader()

        val second = reader.read(client, "session-2")
        val first = reader.read(client, "session-1")

        assertEquals("Second session", second?.title)
        assertTrue(second?.archived == true)
        assertEquals("First session", first?.title)
        assertEquals(listOf(null, "page-2"), client.listCursors)
        assertEquals(1, client.workspaceFollowCalls)

        // The authoritative workspace projection can remove an id after the
        // WebUI unarchives it. A successful empty set must therefore override
        // the previous archived=true mirror instead of preserving it.
        client.archivedSessionIds = emptySet()
        reader.invalidate()
        val unarchived = reader.read(client, "session-2")
        assertEquals(false, unarchived?.archived)
        assertEquals(2, client.workspaceFollowCalls)
    }

    private class ProjectionClient : HarnessClient {
        override val state = MutableStateFlow(HarnessConnectionState.READY)
        val listCursors = mutableListOf<String?>()
        var workspaceFollowCalls = 0
        var archivedSessionIds: Set<String> = setOf("session-2")

        override suspend fun call(
            namespace: String,
            method: String,
            args: JsonObject,
            policy: HarnessCallPolicy,
            requestId: String
        ): HarnessRpcResult {
            if (namespace != "session" || method != "list") return HarnessRpcResult.Success(JsonNull)
            val cursor = args["_request"]?.let { request ->
                (request as? JsonObject)?.get("cursor")?.toString()?.trim('"')
            }
            listCursors += cursor
            return if (cursor == null) {
                HarnessRpcResult.Success(buildJsonObject {
                    putJsonArray("items") {
                        add(sessionRow("session-1", "First session"))
                    }
                    put("nextCursor", "page-2")
                })
            } else {
                HarnessRpcResult.Success(buildJsonObject {
                    putJsonArray("items") {
                        add(sessionRow("session-2", "Second session"))
                    }
                })
            }
        }

        override fun stream(
            namespace: String,
            method: String,
            args: JsonObject,
            policy: HarnessStreamPolicy
        ): Flow<JsonElement> {
            if (namespace != "workspace" || method != "follow") return emptyFlow()
            workspaceFollowCalls++
            return flowOf(buildJsonObject {
                put("type", "baseline")
                putJsonObject("value") {
                    putJsonArray("archivedSessionIds") {
                        archivedSessionIds.forEach { add(JsonPrimitive(it)) }
                    }
                }
            })
        }

        override suspend fun authenticate(launchUrl: String): HarnessAuthResult =
            HarnessAuthResult.Success("http://127.0.0.1:43127")

        override fun adoptAuthenticatedEndpoint(origin: String, cookieHeader: String): HarnessAuthResult =
            HarnessAuthResult.Success(origin)

        override fun close() = Unit

        private fun sessionRow(id: String, title: String): JsonObject = buildJsonObject {
            put("sessionId", id)
            putJsonObject("projections") {
                putJsonObject("values") { put("title", title) }
            }
        }
    }
}
