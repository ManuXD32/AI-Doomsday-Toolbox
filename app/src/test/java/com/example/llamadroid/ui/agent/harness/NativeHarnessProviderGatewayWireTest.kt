package com.example.llamadroid.ui.agent.harness

import com.example.llamadroid.harness.client.HarnessRpcResult
import com.example.llamadroid.harness.client.OkHttpHarnessClient
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.putJsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Exercises the real OkHttp client against the authenticated ADT route wire. */
class NativeHarnessProviderGatewayWireTest {
    @Test
    fun customProviderRoutesUseTheOfficialRpcEnvelope() = runBlocking {
        val requests = mutableListOf<JsonObject>()
        val server = routeServer(requests)
        server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
        val client = OkHttpHarnessClient()
        try {
            client.adoptAuthenticatedEndpoint(
                "http://127.0.0.1:${server.listeningPort}",
                "session=fixture",
            )
            val request = NativeHarnessCustomProviderRequest(
                route = "qa-route",
                api = "openai-completions",
                baseUrl = "http://127.0.0.1:8080/v1",
                apiKey = "private-key",
                models = emptyList(),
            )
            assertTrue(NativeHarnessProviderGateway.create(client, request, 7) is HarnessRpcResult.Success)

            val binding = NativeHarnessProviderBinding(
                providerId = "qa-route",
                displayName = "QA route",
                settingsNamespace = NATIVE_CUSTOM_PROVIDER_SETTINGS_NAMESPACE,
                settingsPath = listOf("providers", "qa-route"),
                revision = 7,
                value = buildJsonObject {},
                apiKeyReference = "QA_ROUTE_API_KEY",
                writable = true,
            )
            assertTrue(
                NativeHarnessProviderGateway.updateCredential(
                    client,
                    binding,
                    "QA_ROUTE_API_KEY",
                    "private-key",
                ) is HarnessRpcResult.Success
            )
            assertTrue(NativeHarnessProviderGateway.delete(client, binding) is HarnessRpcResult.Success)

            assertTrue(
                NativeHarnessCapabilities.discoverModels(
                    client,
                    NATIVE_CUSTOM_PROVIDER_SETTINGS_NAMESPACE,
                    buildJsonObject {
                        put("provider", "qa-route")
                        put("api", "openai-completions")
                    },
                ) is HarnessRpcResult.Success
            )

            assertEquals(
                listOf(
                    "/api/adt/providerCreate",
                    "/api/adt/providerUpdate",
                    "/api/adt/providerDelete",
                    "/api/adt/providerDiscovery",
                ),
                server.paths,
            )
            assertEquals(List(4) { "session=fixture" }, server.cookies)
            requests.forEachIndexed { index, requestBody ->
                assertEquals("client-request", requestBody["type"]?.jsonPrimitive?.content)
                assertFalse(requestBody["rpcId"]?.jsonPrimitive?.content.isNullOrBlank())
                assertEquals(
                    server.paths[index].removePrefix("/api/"),
                    requestBody["method"]?.jsonPrimitive?.content,
                )
                assertEquals(
                    "session=fixture",
                    server.cookies[index],
                )
                assertTrue(requestBody["payload"]?.jsonObject?.containsKey("args") == true)
            }
            val createArgs = requests[0]["payload"]!!.jsonObject["args"]!!.jsonObject
            assertEquals("qa-route", createArgs["route"]?.jsonPrimitive?.content)
            assertEquals(7, createArgs["expectedRevision"]?.jsonPrimitive?.int)
            assertEquals("private-key", createArgs["apiKey"]?.jsonPrimitive?.content)
            assertFalse(createArgs["profile"].toString().contains("private-key"))

            val updateArgs = requests[1]["payload"]!!.jsonObject["args"]!!.jsonObject
            assertEquals("llm-pi-ai", updateArgs["ns"]?.jsonPrimitive?.content)
            assertEquals(7, updateArgs["expectedRevision"]?.jsonPrimitive?.int)
            assertEquals(
                "QA_ROUTE_API_KEY",
                updateArgs["credential"]?.jsonObject?.get("ref")?.jsonPrimitive?.content,
            )
            assertEquals("set", updateArgs["ops"]?.jsonArray?.single()?.jsonObject?.get("op")?.jsonPrimitive?.content)

            val deleteArgs = requests[2]["payload"]!!.jsonObject["args"]!!.jsonObject
            assertEquals("llm-pi-ai", deleteArgs["target"]?.jsonObject?.get("settingsNs")?.jsonPrimitive?.content)
            assertEquals(7, deleteArgs["expectedRevision"]?.jsonPrimitive?.int)

            val discoveryArgs = requests[3]["payload"]!!.jsonObject["args"]!!.jsonObject
            assertEquals("llm-pi-ai", discoveryArgs["settingsNs"]?.jsonPrimitive?.content)
            assertEquals("qa-route", discoveryArgs["request"]?.jsonObject?.get("provider")?.jsonPrimitive?.content)
        } finally {
            client.close()
            server.stop()
        }
    }

    @Test
    fun routeFailureKeepsCodeButRedactsBackendMessage() = runBlocking {
        val server = routeServer(
            requests = mutableListOf(),
            failure = true,
        )
        server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
        val client = OkHttpHarnessClient()
        try {
            client.adoptAuthenticatedEndpoint(
                "http://127.0.0.1:${server.listeningPort}",
                "session=fixture",
            )
            val binding = NativeHarnessProviderBinding(
                providerId = "qa-route",
                displayName = "QA route",
                settingsNamespace = NATIVE_CUSTOM_PROVIDER_SETTINGS_NAMESPACE,
                settingsPath = listOf("providers", "qa-route"),
                revision = 7,
                value = buildJsonObject {},
                apiKeyReference = null,
                writable = true,
            )
            val result = NativeHarnessProviderGateway.updateCredential(
                client,
                binding,
                "QA_ROUTE_API_KEY",
                "private-key",
            ) as HarnessRpcResult.Failure
            assertEquals("settings/conflict", result.error.code)
            assertEquals("Provider settings operation failed", result.error.message)
            assertFalse(result.error.message.contains("private-backend"))
            assertFalse(result.error.details.toString().contains("private-backend"))
        } finally {
            client.close()
            server.stop()
        }
    }

    private fun routeServer(
        requests: MutableList<JsonObject>,
        failure: Boolean = false,
    ): RecordingServer = RecordingServer(requests, failure)

    private class RecordingServer(
        private val requests: MutableList<JsonObject>,
        private val failure: Boolean,
    ) : NanoHTTPD("127.0.0.1", 0) {
        val paths = mutableListOf<String>()
        val cookies = mutableListOf<String?>()

        override fun serve(session: IHTTPSession): Response {
            require(session.method == Method.POST)
            paths += session.uri
            cookies += session.headers["cookie"]
            val body = mutableMapOf<String, String>()
            session.parseBody(body)
            val request = Json.parseToJsonElement(requireNotNull(body["postData"])).jsonObject
            require(request["type"]?.jsonPrimitive?.content == "client-request")
            require(!request["rpcId"]?.jsonPrimitive?.content.isNullOrBlank())
            require(request["method"]?.jsonPrimitive?.content?.startsWith("adt/") == true)
            require(request["payload"]?.jsonObject?.get("args")?.jsonObject != null)
            requests += request
            val response = buildJsonObject {
                put("type", "server-response")
                put("rpcId", request["rpcId"] ?: error("missing rpcId"))
                putJsonObject("result") {
                    if (failure) {
                        put("ok", false)
                        putJsonObject("error") {
                            put("code", "settings/conflict")
                            put("message", "private-backend-token=secret")
                            putJsonObject("details") {}
                        }
                    } else {
                        put("ok", true)
                        putJsonObject("value") {
                            when (request["method"]?.jsonPrimitive?.content) {
                                "adt/providerDelete" -> put("deleted", true)
                                "adt/providerDiscovery" -> {
                                    put("status", "empty")
                                    putJsonArray("models") {}
                                }
                                else -> put("saved", true)
                            }
                        }
                    }
                }
            }
            return newFixedLengthResponse(Response.Status.OK, "application/json", response.toString())
        }
    }
}
