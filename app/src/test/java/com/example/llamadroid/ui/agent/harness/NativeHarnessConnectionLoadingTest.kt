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

    @Test fun initialAndWebViewRefreshReadProviderSettingsBeforeTheModelCatalog() = runBlocking {
        val fake = Client()
        val job = SupervisorJob()
        val controller = NativeHarnessController(CoroutineScope(job + Dispatchers.Unconfined), { fake }, callbacks())
        try {
            eventually { controller.state.value.provider.providers.isNotEmpty() }
            assertSettingsPrecedeLatestCatalog(fake.callOrder)

            controller.refreshAfterWebView()
            eventually { fake.callOrder.count { it == "session/modelCatalog" } >= 2 }
            assertSettingsPrecedeLatestCatalog(fake.callOrder)
        } finally { controller.close(); job.cancel() }
    }

    @Test fun coldControllerRecreationHydratesSettingsBeforeItsCatalogRead() = runBlocking {
        val fake = Client()
        val firstJob = SupervisorJob()
        val first = NativeHarnessController(CoroutineScope(firstJob + Dispatchers.Unconfined), { fake }, callbacks())
        eventually { fake.callOrder.count { it == "session/modelCatalog" } >= 1 }
        first.close()
        firstJob.cancel()

        val secondJob = SupervisorJob()
        val second = NativeHarnessController(CoroutineScope(secondJob + Dispatchers.Unconfined), { fake }, callbacks())
        try {
            eventually { fake.callOrder.count { it == "session/modelCatalog" } >= 2 }
            assertSettingsPrecedeLatestCatalog(fake.callOrder)
        } finally { second.close(); secondJob.cancel() }
    }

    @Test fun failedProviderGroupsRetainTheLastGoodRows() = runBlocking {
        val fake = Client()
        val job = SupervisorJob()
        val controller = NativeHarnessController(CoroutineScope(job + Dispatchers.Unconfined), { fake }, callbacks())
        try {
            eventually { controller.state.value.provider.providers.singleOrNull()?.models?.isNotEmpty() == true }
            fake.catalogJson = """
                {"groups":[{"id":"deepseek-official","name":"DeepSeek","models":[]}],
                 "failures":[{"id":"deepseek-official","name":"DeepSeek","message":"temporarily unavailable"}]}
            """.trimIndent()
            controller.dispatch(NativeHarnessUiAction.RefreshModelCatalog)
            eventually { controller.state.value.provider.catalogFailures.isNotEmpty() }

            assertEquals(
                listOf("deepseek-flash", "deepseek-v4-pro"),
                controller.state.value.provider.providers.single { it.id == "deepseek-official" }.models,
            )
        } finally { controller.close(); job.cancel() }
    }

    @Test fun failedAndroidCatalogProjectionRetainsLastGoodManagedRows() = runBlocking {
        val fake = Client()
        var localReadFails = false
        val localCatalog = Json.parseToJsonElement(
            """{"data":[{"id":"llama:7","owned_by":"adt-llama-server","name":"Q4.gguf"}]}"""
        ).jsonObject
        val job = SupervisorJob()
        val controller = NativeHarnessController(
            CoroutineScope(job + Dispatchers.Unconfined),
            { fake },
            callbacks(),
            localModelCatalog = { if (localReadFails) error("bridge unavailable") else localCatalog },
        )
        try {
            eventually { controller.state.value.provider.providers.any { it.id == "adt-llama-server" } }
            localReadFails = true
            controller.dispatch(NativeHarnessUiAction.RefreshModelCatalog)
            eventually { fake.callOrder.count { it == "session/modelCatalog" } >= 2 }
            assertEquals(
                listOf("llama:7"),
                controller.state.value.provider.providers.single { it.id == "adt-llama-server" }.models,
            )
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

    private fun assertSettingsPrecedeLatestCatalog(calls: List<String>) {
        val snapshot = synchronized(calls) { calls.toList() }
        val catalogIndex = snapshot.indexOfLast { it == "session/modelCatalog" }
        assertTrue(catalogIndex >= 0)
        assertTrue(snapshot.subList(0, catalogIndex).contains("settings/describe"))
        assertTrue(snapshot.subList(0, catalogIndex).contains("llm/listConfigurableProviders"))
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
        var catalogJson = """{"groups":[{"id":"deepseek-official","name":"DeepSeek","models":[{"id":"deepseek-flash"},{"id":"deepseek-v4-pro"}]}],"default":{"provider":"deepseek-official","model":"deepseek-flash"}}"""
        val callOrder = mutableListOf<String>()
        var savedReference: String? = null
        var savedValue: String? = null
        var savedSetting: JsonObject? = null
        override suspend fun authenticate(launchUrl: String) = HarnessAuthResult.Success("http://127.0.0.1:43127")
        override fun adoptAuthenticatedEndpoint(origin: String, cookieHeader: String) = HarnessAuthResult.Success(origin)
        override fun close() = Unit
        override fun stream(namespace: String, method: String, args: JsonObject, policy: HarnessStreamPolicy): Flow<JsonElement> = emptyFlow()
        override suspend fun call(namespace: String, method: String, args: JsonObject, policy: HarnessCallPolicy, requestId: String): HarnessRpcResult {
            synchronized(callOrder) { callOrder += "$namespace/$method" }
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
                    catalogJson
                }
                "session/list" -> """{"items":[]}"""
                else -> "{}"
            }
            return HarnessRpcResult.Success(Json.parseToJsonElement(response))
        }
    }
}
