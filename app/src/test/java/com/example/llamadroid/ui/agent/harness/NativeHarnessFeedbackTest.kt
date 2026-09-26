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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeHarnessFeedbackTest {
    @Test
    fun finalizedAssistantRowsCarryTheUpstreamMessageId() {
        val item = harnessParseEvent(Json.parseToJsonElement("""
            {"type":"assistant/message","seq":2,"data":{"message":{"id":"message-2","content":[{"type":"text","text":"answer"}]}}}
        """).jsonObject)

        assertEquals("message-2", item?.messageId)
    }

    @Test
    fun listPutAndDeletePreserveOfficialVersionedProtocol() = runBlocking {
        val client = FeedbackClient()
        var state = NativeHarnessUiState(selectedSessionId = "session-1")
        val failures = mutableListOf<String>()
        val actions = NativeHarnessFeedbackActions(
            clientProvider = { client },
            selectedSessionProvider = { state.selectedSessionId },
            stateProvider = { state },
            mutate = { transform -> state = transform(state) },
            reportFailure = { code, _ -> failures += code }
        )

        assertTrue(actions.refresh(report = true))
        assertEquals(HarnessFeedbackRating.POSITIVE, state.messageFeedback["message-2"]?.rating)
        assertTrue(actions.submit("message-2", HarnessFeedbackRating.NEGATIVE, "Needs context", "task-result"))
        assertEquals("v2", state.messageFeedback["message-2"]?.version)
        assertTrue(actions.retract("message-2", HarnessFeedbackRating.NEGATIVE))
        assertTrue(state.messageFeedback.isEmpty())
        assertTrue(failures.isEmpty())

        assertEquals(listOf("list", "put", "delete"), client.methods)
        assertEquals("v1", client.putVersion)
        assertEquals("v2", client.deleteVersion)
        assertEquals("task-result", client.putCategory)
    }

    private class FeedbackClient : HarnessClient {
        override val state = MutableStateFlow(HarnessConnectionState.READY)
        val methods = mutableListOf<String>()
        var putVersion: String? = null
        var deleteVersion: String? = null
        var putCategory: String? = null

        override suspend fun call(
            namespace: String,
            method: String,
            args: JsonObject,
            policy: HarnessCallPolicy,
            requestId: String
        ): HarnessRpcResult {
            assertEquals("messageFeedback", namespace)
            methods += method
            val requestObject = args["request"]?.jsonObject ?: kotlin.error("missing request envelope")
            return when (method) {
                "list" -> accepted(buildJsonObject {
                    putJsonArray("items") {
                        add(buildJsonObject {
                            put("messageId", "message-2")
                            put("rating", "positive")
                            put("version", "v1")
                        })
                    }
                })
                "put" -> {
                    putVersion = requestObject["ifVersion"]?.toString()?.trim('"')
                    putCategory = requestObject["category"]?.toString()?.trim('"')
                    accepted(item("negative", "v2"))
                }
                "delete" -> {
                    deleteVersion = requestObject["ifVersion"]?.toString()?.trim('"')
                    accepted(buildJsonObject { put("absent", true) })
                }
                else -> HarnessRpcResult.Failure(error("UNKNOWN_METHOD"))
            }
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

        private fun accepted(value: JsonObject): HarnessRpcResult = HarnessRpcResult.Success(buildJsonObject {
            put("ok", true)
            putJsonObject("value") {
                put("ok", true)
                put("value", value)
            }
        })

        private fun item(rating: String, version: String): JsonObject = buildJsonObject {
            put("messageId", "message-2")
            put("rating", rating)
            put("version", version)
        }

        private fun error(code: String) = com.example.llamadroid.harness.client.HarnessRpcError(code, code)
    }
}
