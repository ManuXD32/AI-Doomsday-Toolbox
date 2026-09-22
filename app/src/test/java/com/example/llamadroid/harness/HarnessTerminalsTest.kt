package com.example.llamadroid.harness

import com.example.llamadroid.harness.client.HarnessAuthResult
import com.example.llamadroid.harness.client.HarnessCallPolicy
import com.example.llamadroid.harness.client.HarnessClient
import com.example.llamadroid.harness.client.HarnessConnectionState
import com.example.llamadroid.harness.client.HarnessRpcResult
import com.example.llamadroid.harness.client.HarnessStreamPolicy
import com.example.llamadroid.service.AgentWorkspaceBackendType
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HarnessTerminalsTest {
    @Test fun identicalTerminalIdsInDifferentSessionsNeverRedirectInput() = runBlocking {
        val client = FakeClient()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val terminals = HarnessTerminals(scope, { client }, mockk(relaxed = true))
        try {
            for (session in listOf("project-a", "project-b")) {
                terminals.refresh(session)
                terminals.attach(session, "same-id")
            }
            terminals.send("project-a", "same-id", "pwd")
            terminals.send("project-b", "same-id", "\u0003", newline = false)
            assertEquals(listOf("project-a", "project-b"), client.writes.map { it.getValue("agentId").jsonPrimitive.content })
            assertEquals("pwd\r", client.writes.first().getValue("data").jsonPrimitive.content)
            assertEquals("\u0003", client.writes.last().getValue("data").jsonPrimitive.content)
            assertTrue(client.writes.first().getValue("attachmentId") != client.writes.last().getValue("attachmentId"))
            assertEquals(listOf("pwd"), terminals.states.value.getValue("project-a").single().commandHistory)
            assertTrue(terminals.states.value.getValue("project-b").single().commandHistory.isEmpty())
        } finally { terminals.interrupted(); scope.cancel() }
    }

    @Test fun largeScreenIsBoundedAndEnvironmentLossDoesNotCreateReplacementShells() = runBlocking {
        val client = FakeClient("x".repeat(90_000))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val terminals = HarnessTerminals(scope, { client }, mockk(relaxed = true))
        try {
            terminals.refresh("project-a")
            terminals.attach("project-a", "same-id")
            assertTrue(terminals.states.value.getValue("project-a").single().transcript.length <= 64 * 1024)
            terminals.interrupted()
            assertFalse(terminals.states.value.getValue("project-a").single().isConnected)
            assertEquals(0, client.creates)
        } finally { scope.cancel() }
    }

    @Test fun completedFollowMarksRemoteTerminalDisconnectedWithoutChangingItsBackend() = runBlocking {
        val client = FakeClient(endFollow = true)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val terminals = HarnessTerminals(scope, { client }, mockk(relaxed = true)) { AgentWorkspaceBackendType.REMOTE_SSH }
        try {
            terminals.refresh("ssh-project")
            terminals.attach("ssh-project", "same-id")
            val terminal = terminals.states.value.getValue("ssh-project").single()
            assertEquals(AgentWorkspaceBackendType.REMOTE_SSH, terminal.backend)
            assertFalse(terminal.isConnected)
            assertFalse(terminal.isConnecting)
            assertEquals(0, client.creates)
        } finally { terminals.interrupted(); scope.cancel() }
    }

    private class FakeClient(private val screen: String = "ready", private val endFollow: Boolean = false) : HarnessClient {
        override val state = MutableStateFlow(HarnessConnectionState.READY)
        val writes = mutableListOf<JsonObject>()
        var creates = 0
        override suspend fun authenticate(launchUrl: String) = HarnessAuthResult.Success("http://127.0.0.1:1234")
        override fun adoptAuthenticatedEndpoint(origin: String, cookieHeader: String) = HarnessAuthResult.Success(origin)
        override suspend fun call(namespace: String, method: String, args: JsonObject, policy: HarnessCallPolicy, requestId: String): HarnessRpcResult {
            assertEquals("terminal", namespace)
            assertFalse("Legacy agent parameter is not a pinned descriptor field", args.containsKey("agent"))
            if (method != "list") assertTrue(args.containsKey("agentId"))
            return HarnessRpcResult.Success(when (method) {
                "list" -> JsonArray(listOf(info(args.getValue("sessionId").jsonPrimitive.content)))
                "write" -> { writes += args; JsonNull }
                "create" -> { creates++; JsonNull }
                else -> error("Unexpected terminal operation: $method")
            })
        }
        override fun stream(namespace: String, method: String, args: JsonObject, policy: HarnessStreamPolicy): Flow<JsonElement> = flow {
            assertEquals("terminal", namespace); assertEquals("follow", method)
            assertFalse(args.containsKey("agent")); assertTrue(args.containsKey("agentId"))
            emit(buildJsonObject { put("type", "snapshot"); put("sequence", 0); put("screen", screen)
                put("info", info(args.getValue("agentId").jsonPrimitive.content)) })
            if (!endFollow) awaitCancellation()
        }
        override fun close() = Unit
        private fun info(session: String) = buildJsonObject {
            put("id", "same-id"); put("title", session); put("cwd", "/workspace/projects/$session"); put("state", "running")
        }
    }
}
