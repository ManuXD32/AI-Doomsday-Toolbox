package com.example.llamadroid.ui.agent.harness

import com.example.llamadroid.harness.client.HarnessAuthResult
import com.example.llamadroid.harness.client.HarnessCallPolicy
import com.example.llamadroid.harness.client.HarnessClient
import com.example.llamadroid.harness.client.HarnessConnectionState
import com.example.llamadroid.harness.client.HarnessRpcError
import com.example.llamadroid.harness.client.HarnessRpcResult
import com.example.llamadroid.harness.client.HarnessStreamPolicy
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeHarnessProviderGatewayTest {
    @Test
    fun keylessCredentialClearUsesProviderUpdateCasEnvelope() = runBlocking {
        val client = Client()
        val binding = binding(apiKeyReference = "QA_ROUTE_API_KEY")

        val result = NativeHarnessProviderGateway.updateCredential(
            client,
            binding,
            "QA_ROUTE_API_KEY",
            null,
        )

        assertTrue(result is HarnessRpcResult.Success)
        val args = client.requests.single().args
        assertEquals("llm-pi-ai", args["ns"]?.jsonPrimitive?.content)
        assertEquals(7, args["expectedRevision"]?.jsonPrimitive?.int)
        assertEquals(
            "unset",
            args["ops"]?.jsonArray?.single()?.jsonObject?.get("op")?.jsonPrimitive?.content,
        )
        assertEquals("QA_ROUTE_API_KEY", args["credential"]?.jsonObject?.get("ref")?.jsonPrimitive?.content)
        assertFalse(args["credential"]?.jsonObject?.containsKey("value") == true)
    }

    @Test
    fun providerUpdateFailureIsRedactedAndKeepsCasOperationBounded() = runBlocking {
        val client = Client(providerRouteFailure("settings/conflict", "private backend detail"))
        val result = NativeHarnessProviderGateway.updateCredential(
            client,
            binding(apiKeyReference = null),
            "QA_ROUTE_API_KEY",
            "super-private-key",
        )

        assertEquals("settings/conflict", (result as HarnessRpcResult.Failure).error.code)
        assertEquals("Provider settings operation failed", result.error.message)
        assertFalse(result.error.message.contains("super-private-key"))
        assertEquals("set", client.requests.single().args
            .get("ops")?.jsonArray?.single()?.jsonObject?.get("op")?.jsonPrimitive?.content)
    }

    @Test
    fun customCreateAndDeleteUseSharedAtomicRoutes() = runBlocking {
        val client = Client()
        val request = NativeHarnessCustomProviderRequest(
            route = "qa-route",
            api = "openai-completions",
            baseUrl = "http://127.0.0.1:8080/v1",
            apiKey = "super-private-key",
            models = emptyList(),
        )

        NativeHarnessProviderGateway.create(client, request, expectedRevision = 7)
        val createArgs = client.requests.single().args
        assertEquals("[]", createArgs["profile"]?.jsonObject?.get("models")?.toString())
        assertFalse(createArgs["profile"].toString().contains("super-private-key"))
        assertEquals("super-private-key", createArgs["apiKey"]?.jsonPrimitive?.content)

        client.requests.clear()
        NativeHarnessProviderGateway.delete(client, binding(apiKeyReference = "QA_ROUTE_API_KEY"))
        val deleteArgs = client.requests.single().args
        val target = deleteArgs["target"]?.jsonObject ?: error("delete target missing")
        assertEquals("llm-pi-ai", target["settingsNs"]?.jsonPrimitive?.content)
        assertEquals("QA_ROUTE_API_KEY", target["credentialRef"]?.jsonPrimitive?.content)
        assertEquals(7, deleteArgs["expectedRevision"]?.jsonPrimitive?.int)
    }

    private fun binding(apiKeyReference: String?): NativeHarnessProviderBinding = NativeHarnessProviderBinding(
        providerId = "qa-route",
        displayName = "QA route",
        settingsNamespace = NATIVE_CUSTOM_PROVIDER_SETTINGS_NAMESPACE,
        settingsPath = listOf("providers", "qa-route"),
        revision = 7,
        value = buildJsonObject {},
        apiKeyReference = apiKeyReference,
        writable = true,
    )

    private fun providerRouteFailure(code: String, message: String): HarnessRpcResult =
        HarnessRpcResult.Failure(HarnessRpcError(code, message))

    private class Client(
        private val providerRouteResult: HarnessRpcResult = HarnessRpcResult.Success(
            buildJsonObject { put("saved", true) }
        ),
    ) : HarnessClient {
        data class Request(val namespace: String, val method: String, val args: JsonObject)

        val requests = mutableListOf<Request>()
        override val state = MutableStateFlow(HarnessConnectionState.READY)

        override suspend fun call(
            namespace: String,
            method: String,
            args: JsonObject,
            policy: HarnessCallPolicy,
            requestId: String,
        ): HarnessRpcResult {
            requests += Request(namespace, method, args)
            return providerRouteResult
        }

        override fun stream(
            namespace: String,
            method: String,
            args: JsonObject,
            policy: HarnessStreamPolicy,
        ): Flow<JsonElement> = emptyFlow()

        override suspend fun authenticate(launchUrl: String): HarnessAuthResult =
            HarnessAuthResult.Success("http://127.0.0.1")

        override fun adoptAuthenticatedEndpoint(origin: String, cookieHeader: String): HarnessAuthResult =
            HarnessAuthResult.Success(origin)

        override fun close() = Unit
    }
}
