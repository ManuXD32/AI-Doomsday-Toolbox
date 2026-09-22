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
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeHarnessSessionAddressTest {
    @Test
    fun listProjectionBuildsSubagentWireAddressAndReadOnlyCapability() {
        val store = NativeHarnessSessionAddressStore()
        val address = store.observeListRow(buildJsonObject {
            put("sessionId", "child")
            put("origin", "subagent")
            put("parentSessionId", "parent")
            putJsonObject("projections") {
                putJsonObject("values") {
                    putJsonObject("subagent") { put("mode", "one-shot") }
                }
            }
        })
        assertNotNull(address)
        assertTrue(address!!.isSubagent)
        assertFalse(address.queueMutable)
        assertEquals("subagent", address.toWire()!!["kind"]?.jsonPrimitive?.content)
        assertEquals("parent", address.toWire()!!["parentSessionId"]?.jsonPrimitive?.content)
    }

    @Test
    fun catalogFallbackSuppliesExactContinuableMode() {
        val store = NativeHarnessSessionAddressStore()
        store.observeListRow(buildJsonObject {
            put("sessionId", "child")
            put("origin", "subagent")
            put("parentSessionId", "parent")
        })
        assertNull(store.addressFor("child")!!.toWire())
        assertNull(store.wireAddress("child"))
        val address = store.observeSubagent("parent", "child", "continuable")
        assertNotNull(address)
        assertTrue(address!!.queueMutable)
        assertEquals("continuable", address.toWire()!!["mode"]?.jsonPrimitive?.content)
    }

    @Test
    fun laterCatalogRowsWithoutModeDoNotEraseValidatedMode() {
        val store = NativeHarnessSessionAddressStore()
        store.observeSubagent("parent", "child", "continuable")
        store.observeSubagent("parent", "child", null)
        assertEquals("continuable", store.addressFor("child")?.mode)
        store.observeListRow(buildJsonObject {
            put("sessionId", "child")
            put("origin", "subagent")
            put("parentSessionId", "parent")
        })
        assertEquals("continuable", store.addressFor("child")?.mode)
    }

    @Test
    fun ensureRoutableUsesAuthoritativeParentCatalogWhenProjectionModeIsMissing() = runBlocking {
        val store = NativeHarnessSessionAddressStore()
        store.observeListRow(buildJsonObject {
            put("sessionId", "child")
            put("origin", "subagent")
            put("parentSessionId", "parent")
        })
        val client = RecordingHarnessClient().apply {
            nextResult = HarnessRpcResult.Success(buildJsonObject {
                putJsonArray("entries") {
                    add(buildJsonObject {
                        put("kind", "child")
                        put("id", "child")
                        put("mode", "continuable")
                    })
                }
            })
        }
        val resolved = store.ensureRoutable(client, "child")
        assertEquals("continuable", resolved?.mode)
        assertEquals("list", client.lastMethod)
    }

    @Test
    fun promptAndCancelUseDurableSubagentAddressMethods() = runBlocking {
        val client = RecordingHarnessClient()
        val address = NativeHarnessSessionAddress("child", "parent", "continuable", "subagent")
        val content = buildJsonArray {
            add(buildJsonObject { put("type", "text"); put("text", "hello") })
        }
        assertTrue(promptNativeHarnessSession(client, address, content, "steer", "UTC") is HarnessRpcResult.Success)
        assertEquals("subagents", client.lastNamespace)
        assertEquals("prompt", client.lastMethod)
        assertEquals("parent", client.lastArgs?.get("parentSessionId")?.jsonPrimitive?.content)
        assertEquals("child", client.lastArgs?.get("childSessionId")?.jsonPrimitive?.content)
        assertNull(client.lastArgs?.get("request"))
        assertTrue(cancelNativeHarnessSession(client, address) is HarnessRpcResult.Success)
        assertEquals("interruptByParent", client.lastMethod)
        assertEquals("continuable", client.lastArgs?.get("mode")?.jsonPrimitive?.content)
    }

    @Test
    fun oneShotPromptIsReadOnlyButCancelUsesInterrupt() = runBlocking {
        val client = RecordingHarnessClient()
        val address = NativeHarnessSessionAddress("child", "parent", "one-shot", "subagent")
        val content = buildJsonArray { add(buildJsonObject { put("type", "text"); put("text", "hello") }) }
        val prompt = promptNativeHarnessSession(client, address, content, "queue", "UTC")
        assertEquals("SUBAGENTS_READ_ONLY", (prompt as HarnessRpcResult.Failure).error.code)
        assertTrue(cancelNativeHarnessSession(client, address) is HarnessRpcResult.Success)
        assertEquals("interruptByParent", client.lastMethod)
        assertEquals("continuable", client.lastArgs?.get("mode")?.jsonPrimitive?.content)
    }

    @Test
    fun missingSubagentParentReturnsTypedAddressFailure() = runBlocking {
        val client = RecordingHarnessClient()
        val address = NativeHarnessSessionAddress("child", null, "continuable", "subagent")
        val content = buildJsonArray { add(buildJsonObject { put("type", "text"); put("text", "hello") }) }
        assertEquals(
            "SUBAGENT_ADDRESS_UNAVAILABLE",
            (promptNativeHarnessSession(client, address, content, "queue", "UTC") as HarnessRpcResult.Failure).error.code,
        )
        assertEquals(
            "SUBAGENT_ADDRESS_UNAVAILABLE",
            (cancelNativeHarnessSession(client, address) as HarnessRpcResult.Failure).error.code,
        )
        assertNull(client.lastMethod)
    }

    private class RecordingHarnessClient : HarnessClient {
        override val state = MutableStateFlow(HarnessConnectionState.READY)
        var lastNamespace: String? = null
        var lastMethod: String? = null
        var lastArgs: JsonObject? = null
        var nextResult: HarnessRpcResult = HarnessRpcResult.Success(JsonNull)

        override suspend fun call(
            namespace: String,
            method: String,
            args: JsonObject,
            policy: HarnessCallPolicy,
            requestId: String,
        ): HarnessRpcResult {
            lastNamespace = namespace
            lastMethod = method
            lastArgs = args
            return nextResult
        }

        override fun stream(namespace: String, method: String, args: JsonObject, policy: HarnessStreamPolicy): Flow<kotlinx.serialization.json.JsonElement> = emptyFlow()
        override suspend fun authenticate(launchUrl: String): HarnessAuthResult = HarnessAuthResult.Success("http://127.0.0.1")
        override fun adoptAuthenticatedEndpoint(origin: String, cookieHeader: String): HarnessAuthResult = HarnessAuthResult.Success(origin)
        override fun close() = Unit
    }
}
