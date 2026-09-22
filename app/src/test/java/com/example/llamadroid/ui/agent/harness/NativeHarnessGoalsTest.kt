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
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeHarnessGoalsTest {
    @Test
    fun projectionAndViewParsingKeepGoalCasFields() {
        val projection = buildJsonObject {
            putJsonObject("goal") {
                put("id", "goal-1")
                put("revision", 2)
                put("objective", "ship the native surface")
                put("phase", "paused")
                put("maxGoalRounds", 8)
            }
            put("roundsStarted", 3)
        }

        val parsed = parseHarnessGoal(projection)

        assertEquals("goal-1", parsed?.id)
        assertEquals(2, parsed?.revision)
        assertEquals(3, parsed?.roundsStarted)
        assertEquals(8, parsed?.maxGoalRounds)
        assertEquals("paused", parsed?.phase)
    }

    @Test
    fun createUsesAgentScopedOfficialGoalEnvelopeAndRefreshesView() = runBlocking {
        val client = RecordingGoalClient(
            listOf(
                buildJsonObject {
                    putJsonObject("ref") {
                        put("id", "goal-1")
                        put("revision", 1)
                    }
                },
                buildJsonObject {
                    put("id", "goal-1")
                    put("revision", 1)
                    put("objective", "ship it")
                    put("phase", "active")
                    put("maxGoalRounds", 12)
                    put("roundsStarted", 0)
                    put("activation", "armed")
                }
            )
        )
        var goal: HarnessGoalUi? = null
        val failures = mutableListOf<String>()
        val actions = NativeHarnessGoalActions(
            clientProvider = { client },
            selectedSessionProvider = { "session-1" },
            selectedGoalProvider = { goal },
            updateGoal = { _, next -> goal = next },
            reportFailure = { code, _ -> failures += code }
        )

        assertTrue(actions.dispatch(NativeHarnessUiAction.CreateGoal("ship it", 12)))

        assertEquals(emptyList<String>(), failures)
        assertEquals("goals", client.calls[0].namespace)
        assertEquals("create", client.calls[0].method)
        assertEquals("session-1", client.calls[0].args["agentId"]?.toString()?.trim('"'))
        assertEquals("ship it", client.calls[0].args["request"]?.jsonObject?.get("objective")?.toString()?.trim('"'))
        assertEquals("goals", client.calls[1].namespace)
        assertEquals("get", client.calls[1].method)
        assertEquals("armed", goal?.activation)
    }

    private data class Call(val namespace: String, val method: String, val args: JsonObject)

    private class RecordingGoalClient(responses: List<JsonElement>) : HarnessClient {
        private val pending = ArrayDeque(responses)
        val calls = mutableListOf<Call>()
        override val state = MutableStateFlow(HarnessConnectionState.READY)

        override suspend fun call(
            namespace: String,
            method: String,
            args: JsonObject,
            policy: HarnessCallPolicy,
            requestId: String
        ): HarnessRpcResult {
            calls += Call(namespace, method, args)
            return HarnessRpcResult.Success(pending.removeFirstOrNull() ?: JsonNull)
        }

        override fun stream(
            namespace: String,
            method: String,
            args: JsonObject,
            policy: HarnessStreamPolicy
        ): Flow<JsonElement> = emptyFlow()

        override suspend fun authenticate(launchUrl: String): HarnessAuthResult =
            HarnessAuthResult.Success("http://127.0.0.1")

        override fun adoptAuthenticatedEndpoint(origin: String, cookieHeader: String): HarnessAuthResult =
            HarnessAuthResult.Success(origin)

        override fun close() = Unit
    }
}
