package com.example.llamadroid.ui.agent.harness

import com.example.llamadroid.harness.client.HarnessAuthResult
import com.example.llamadroid.harness.client.HarnessCallPolicy
import com.example.llamadroid.harness.client.HarnessClient
import com.example.llamadroid.harness.client.HarnessConnectionState
import com.example.llamadroid.harness.client.HarnessRpcResult
import com.example.llamadroid.harness.client.HarnessStreamPolicy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeHarnessReferenceCatalogsTest {
    @Test
    fun replacedClientCannotPublishSkillsIntoTheSameSession(): Unit = runBlocking {
        var state = NativeHarnessUiState(selectedSessionId = "one")
        var current: HarnessClient? = null
        val client = CatalogClient {
            current = null
            """{"skills":[{"name":"obsolete","description":"old"}]}"""
        }
        current = client
        val catalogs = NativeHarnessReferenceCatalogs({ current }, { state.selectedSessionId },
            { current === it }, { state = it(state) }, { _, _ -> error("unexpected failure") })
        catalogs.skills(true)
        assertTrue(state.extensions.skills.isEmpty())
    }

    @Test
    fun changedSessionKeepsItsCatalogWhenAnOldReadCompletes(): Unit = runBlocking {
        var state = NativeHarnessUiState(selectedSessionId = "one", commands = listOf(HarnessCommandUi("/current")))
        val client = CatalogClient {
            state = state.copy(selectedSessionId = "two")
            """[{"name":"obsolete","description":"old"}]"""
        }
        val catalogs = NativeHarnessReferenceCatalogs({ client }, { state.selectedSessionId },
            { it === client }, { state = it(state) }, { _, _ -> error("unexpected failure") })
        catalogs.commands(true)
        assertEquals(listOf("/current"), state.commands.map { it.name })
    }

    private class CatalogClient(private val response: () -> String) : HarnessClient {
        override val state = MutableStateFlow(HarnessConnectionState.READY)
        override suspend fun authenticate(launchUrl: String): HarnessAuthResult = error("unused")
        override fun adoptAuthenticatedEndpoint(origin: String, cookieHeader: String): HarnessAuthResult = error("unused")
        override suspend fun call(namespace: String, method: String, args: JsonObject,
            policy: HarnessCallPolicy, requestId: String) = HarnessRpcResult.Success(Json.parseToJsonElement(response()))
        override fun stream(namespace: String, method: String, args: JsonObject, policy: HarnessStreamPolicy) = emptyFlow<JsonElement>()
        override fun close() = Unit
    }
}
