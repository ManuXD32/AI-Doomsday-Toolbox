package com.example.llamadroid.ui.agent.harness

import com.example.llamadroid.harness.client.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class NativeHarnessConnectionLoadingTest {
    @Test fun providerFieldSaveSendsTheEditedValueInsteadOfThePreviousSnapshot() = runBlocking {
        val fake = Client()
        val job = SupervisorJob()
        val controller = NativeHarnessController(CoroutineScope(job + Dispatchers.Unconfined), { fake }, callbacks())
        try {
            controller.dispatch(NativeHarnessUiAction.LoadManagement(HarnessManagementArea.SETTINGS))
            eventually { controller.state.value.provider.configs.isNotEmpty() }
            val config = controller.state.value.provider.configs.single()
            val field = config.fields.first { it.path == listOf("baseUrl") }
            controller.dispatch(NativeHarnessUiAction.UpdateProviderField(config.id, field.key, "https://api.deepseek.com/v1"))
            eventually { fake.savedSetting != null }
            val operation = fake.savedSetting!!.getValue("ops").jsonArray.single().jsonObject
            assertEquals(JsonArray(listOf(JsonPrimitive("baseUrl"))), operation["path"])
            assertEquals("https://api.deepseek.com/v1", operation.getValue("value").jsonPrimitive.content)
        } finally { controller.close(); job.cancel() }
    }

    @Test fun settingsAndOfficialDeepSeekKeyBecomeAvailableAfterStartingFromStopped() = runBlocking {
        val fake = Client()
        var client: HarnessClient? = null
        val job = SupervisorJob()
        val controller = NativeHarnessController(
            CoroutineScope(job + Dispatchers.Unconfined), { client },
            NativeHarnessRuntimeCallbacks(
                current = { HarnessRuntimeUiState() },
                start = { client = fake; HarnessRuntimeUiState(status = HarnessRuntimeStatus.RUNNING) },
                stop = { HarnessRuntimeUiState() }, forceStop = { HarnessRuntimeUiState() },
            ),
        )
        try {
            assertTrue(controller.state.value.provider.configs.isEmpty())
            controller.dispatch(NativeHarnessUiAction.StartRuntime)
            controller.dispatch(NativeHarnessUiAction.LoadManagement(HarnessManagementArea.SETTINGS))
            eventually { controller.state.value.provider.configs.isNotEmpty() }
            val config = controller.state.value.provider.configs.single()
            assertEquals("deepseek-official", config.id)
            assertEquals("DEEPSEEK_API_KEY", config.credentialReference)
            assertTrue(config.credentialWritable)
            assertEquals(listOf("deepseek-flash", "deepseek-v4-pro"), controller.state.value.provider.providers.single().models)
            controller.dispatch(NativeHarnessUiAction.SetProviderCredential(config.id, "test-private-key"))
            eventually { fake.savedReference != null }
            assertEquals("DEEPSEEK_API_KEY", fake.savedReference)
            assertEquals("test-private-key", fake.savedValue)
            assertFalse(controller.state.value.toString().contains("test-private-key"))
        } finally { controller.close(); job.cancel() }
    }

    @Test fun composerUpdatesImmediatelyWhileAReadHoldsTheActionQueue() = runBlocking {
        val fake = Client()
        val job = SupervisorJob()
        val controller = NativeHarnessController(CoroutineScope(job + Dispatchers.Unconfined), { fake }, callbacks())
        try {
            controller.dispatch(NativeHarnessUiAction.LoadManagement(HarnessManagementArea.SETTINGS))
            eventually { controller.state.value.managementLoads[HarnessManagementArea.SETTINGS]?.loaded == true }
            fake.catalogGate = CompletableDeferred()
            controller.dispatch(NativeHarnessUiAction.RefreshModelCatalog)
            eventually { fake.catalogWaiting }
            controller.dispatch(NativeHarnessUiAction.UpdateComposer("hello while loading"))
            assertEquals("hello while loading", controller.state.value.composerText)
            controller.dispatch(NativeHarnessUiAction.UpdateComposer("hello"))
            assertEquals("hello", controller.state.value.composerText)
        } finally { controller.close(); job.cancel() }
    }

    @Test fun managementLoadsCoalesceAndReloadForANewClient() = runBlocking {
        var client: HarnessClient = Client()
        var state = NativeHarnessUiState()
        var reads = 0
        val gate = CompletableDeferred<Unit>()
        val job = SupervisorJob()
        val loader = NativeHarnessManagementLoader(CoroutineScope(job + Dispatchers.Unconfined),
            { client }, { state }, { state = it(state) }) { reads++; gate.await() }
        try {
            loader.request(HarnessManagementArea.SETTINGS)
            loader.request(HarnessManagementArea.SETTINGS)
            assertEquals(1, reads)
            assertTrue(state.managementLoads[HarnessManagementArea.SETTINGS]?.isLoading == true)
            loader.invalidate(HarnessManagementArea.SETTINGS)
            loader.invalidate(HarnessManagementArea.SETTINGS)
            assertEquals(1, reads)
            assertTrue(state.managementLoads[HarnessManagementArea.SETTINGS]?.isLoading == true)
            gate.complete(Unit)
            eventually { state.managementLoads[HarnessManagementArea.SETTINGS]?.loaded == true }
            loader.invalidate(HarnessManagementArea.SETTINGS)
            loader.invalidate(HarnessManagementArea.SETTINGS)
            assertFalse(state.managementLoads[HarnessManagementArea.SETTINGS]?.loaded == true)
            assertEquals(1, reads)
            loader.request(HarnessManagementArea.SETTINGS)
            eventually { state.managementLoads[HarnessManagementArea.SETTINGS]?.loaded == true }
            assertEquals(2, reads)
            loader.request(HarnessManagementArea.SETTINGS)
            assertEquals(2, reads)
            client = Client()
            loader.request(HarnessManagementArea.SETTINGS)
            assertEquals(3, reads)
        } finally { job.cancel() }
    }

    private suspend fun eventually(condition: () -> Boolean) = withTimeout(2_000) {
        while (!condition()) delay(1)
    }

    private fun callbacks() = NativeHarnessRuntimeCallbacks(
        current = { HarnessRuntimeUiState(status = HarnessRuntimeStatus.RUNNING) },
        start = { HarnessRuntimeUiState(status = HarnessRuntimeStatus.RUNNING) },
        stop = { HarnessRuntimeUiState() }, forceStop = { HarnessRuntimeUiState() },
    )

    /** Shapes from the pinned llm-deepseek directory and credential remotes. */
    private class Client : HarnessClient {
        override val state = MutableStateFlow(HarnessConnectionState.READY)
        var catalogGate: CompletableDeferred<Unit>? = null
        var catalogWaiting = false
        var savedReference: String? = null
        var savedValue: String? = null
        var savedSetting: JsonObject? = null
        override suspend fun authenticate(launchUrl: String) = HarnessAuthResult.Success("http://127.0.0.1:43127")
        override fun adoptAuthenticatedEndpoint(origin: String, cookieHeader: String) = HarnessAuthResult.Success(origin)
        override fun close() = Unit
        override fun stream(namespace: String, method: String, args: JsonObject, policy: HarnessStreamPolicy): Flow<JsonElement> = emptyFlow()
        override suspend fun call(namespace: String, method: String, args: JsonObject, policy: HarnessCallPolicy, requestId: String): HarnessRpcResult {
            val response = when ("$namespace/$method") {
                "settings/describe" -> """{"writable":true,"hasDocument":true,"namespaces":[{"ns":"llm-deepseek","revision":1,"value":{"apiKeyEnv":"DEEPSEEK_API_KEY","baseUrl":"https://api.deepseek.com"},"schema":{"type":"object","properties":{"baseUrl":{"type":"string"}}}}]}"""
                "settings/mutate" -> { savedSetting = args; "{}" }
                "llm/listConfigurableProviders" -> """[{"provider":"deepseek-official","displayName":"DeepSeek","settingsNs":"llm-deepseek","settingsPath":[]}]"""
                "credentials/describe" -> """{"DEEPSEEK_API_KEY":{"configured":false,"writable":true}}"""
                "credentials/set" -> {
                    savedReference = args["ref"]?.jsonPrimitive?.content
                    savedValue = args["value"]?.jsonPrimitive?.content
                    "{}"
                }
                "session/modelCatalog" -> {
                    catalogGate?.let { catalogWaiting = true; it.await() }
                    """{"groups":[{"id":"deepseek-official","name":"DeepSeek","models":[{"id":"deepseek-flash"},{"id":"deepseek-v4-pro"}]}],"default":{"provider":"deepseek-official","model":"deepseek-flash"}}"""
                }
                "session/list" -> """{"items":[]}"""
                else -> "{}"
            }
            return HarnessRpcResult.Success(Json.parseToJsonElement(response))
        }
    }
}
