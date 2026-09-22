package com.example.llamadroid.ui.agent.harness

import com.example.llamadroid.harness.client.HarnessAuthResult
import com.example.llamadroid.harness.client.HarnessCallPolicy
import com.example.llamadroid.harness.client.HarnessClient
import com.example.llamadroid.harness.client.HarnessConnectionState
import com.example.llamadroid.harness.client.HarnessRpcResult
import com.example.llamadroid.harness.client.HarnessRpcError
import com.example.llamadroid.harness.client.HarnessStreamPolicy
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

class NativeHarnessControllerWireTest {
    private fun providerRouteFailure(code: String): HarnessRpcResult =
        HarnessRpcResult.Failure(HarnessRpcError(code, "private backend detail"))

    @Test
    fun sessionChangeClearsCatalogsEvenWhenTheNewReadsFail(): Unit = runBlocking {
        val client = RecordingHarnessClient().apply {
            sessionItems = listOf("session-1", "session-2")
            serveReferenceCatalogs = true
        }
        val controller = controller(client)
        try {
            waitUntil { controller.state.value.commands.isNotEmpty() && controller.state.value.extensions.skills.isNotEmpty() }
            val reads = client.calls.count { it.namespace == "commands" && it.method == "list" }
            client.failReferenceCatalogs = true
            controller.dispatch(NativeHarnessUiAction.SelectSession("session-2"))
            waitUntil { controller.state.value.selectedSessionId == "session-2" &&
                client.calls.count { it.namespace == "commands" && it.method == "list" } > reads }
            assertTrue(controller.state.value.commands.isEmpty())
            assertTrue(controller.state.value.extensions.skills.isEmpty())
        } finally { controller.close() }
    }

    @Test
    fun transcriptLinksCaptureTheirSessionAndIgnoreStaleRows(): Unit = runBlocking {
        val client = RecordingHarnessClient().apply { sessionItems = listOf("session-1", "session-2") }
        val delivered = mutableListOf<NativeHarnessUiAction.OpenTranscriptLink>()
        val controller = controller(client) { if (it is NativeHarnessUiAction.OpenTranscriptLink) delivered += it }
        try {
            waitUntil { controller.state.value.selectedSessionId == "session-1" }
            controller.dispatch(NativeHarnessUiAction.OpenTranscriptLink("session-1",
                HarnessInlineTarget.File("first.txt", 12), workspaceSessionId = "unrelated"))
            waitUntil { delivered.size == 1 }
            assertEquals("session-1", delivered.single().workspaceSessionId)
            controller.dispatch(NativeHarnessUiAction.SelectSession("session-2"))
            waitUntil { controller.state.value.selectedSessionId == "session-2" }
            controller.dispatch(NativeHarnessUiAction.OpenTranscriptLink("session-1", HarnessInlineTarget.File("stale.txt")))
            controller.dispatch(NativeHarnessUiAction.OpenTranscriptLink("session-2", HarnessInlineTarget.Skill("review")))
            waitUntil { delivered.size == 2 }
            assertEquals(listOf("session-1", "session-2"), delivered.map { it.sessionId })
            assertEquals(HarnessInlineTarget.Skill("review"), delivered.last().target)
        } finally { controller.close() }
    }

    @Test
    fun customProviderCreationUsesAuthenticatedAtomicProviderRoute() = runBlocking {
        val client = RecordingHarnessClient().also { it.customProviderSettings = true }
        val controller = controller(client)
        try {
            controller.dispatch(NativeHarnessUiAction.LoadManagement(HarnessManagementArea.SETTINGS))
            waitUntil { controller.state.value.provider.canCreateProvider }
            controller.dispatch(
                NativeHarnessUiAction.CreateCustomProvider(
                    NativeHarnessCustomProviderRequest(
                        route = "qa-route",
                        displayName = "QA route",
                        api = "openai-completions",
                        baseUrl = "http://127.0.0.1:9/v1",
                        apiKey = "qa-secret",
                        models = listOf(NativeHarnessCustomProviderModel("qa-model", maxTokens = 256))
                    )
                )
            )
            waitUntil { client.routeCalls.any { it.path == "/api/adt/providerCreate" } }
            val request = client.routeCalls.single { it.path == "/api/adt/providerCreate" }
                .body?.get("args")?.jsonObject ?: error("provider create args missing")
            assertEquals("qa-route", request["route"]?.toString()?.trim('"'))
            assertEquals("3", request["expectedRevision"]?.toString())
            assertEquals("qa-secret", request["apiKey"]?.toString()?.trim('"'))
            val profile = request["profile"]?.jsonObject ?: error("profile missing")
            assertEquals("openai-completions", profile["api"]?.toString()?.trim('"'))
            assertFalse(profile.toString().contains("qa-secret"))
            assertEquals("qa-model", profile["models"]?.jsonArray?.single()?.jsonObject
                ?.get("id")?.toString()?.trim('"'))
            val operation = request["profile"]?.jsonObject
            assertEquals(
                "openai-completions",
                operation?.get("api")?.toString()?.trim('"')
            )
        } finally {
            controller.close()
        }
    }

    @Test
    fun keylessCustomProviderCreationAllowsEmptyModelCatalog() = runBlocking {
        val client = RecordingHarnessClient().also { it.customProviderSettings = true }
        val controller = controller(client)
        try {
            controller.dispatch(NativeHarnessUiAction.LoadManagement(HarnessManagementArea.SETTINGS))
            waitUntil { controller.state.value.provider.canCreateProvider }
            controller.dispatch(
                NativeHarnessUiAction.CreateCustomProvider(
                    NativeHarnessCustomProviderRequest(
                        route = "local-route",
                        api = "openai-completions",
                        baseUrl = "http://127.0.0.1:8080/v1",
                        apiKey = "",
                        models = emptyList(),
                    )
                )
            )
            waitUntil { client.routeCalls.any { it.path == "/api/adt/providerCreate" } }
            val request = client.routeCalls.single { it.path == "/api/adt/providerCreate" }
                .body?.get("args")?.jsonObject ?: error("provider create args missing")
            val profile = request["profile"]?.jsonObject
            assertEquals("[]", profile?.get("models")?.toString())
            assertTrue(request["apiKey"] == null)
        } finally {
            controller.close()
        }
    }

    @Test
    fun derivedCredentialBindsApiKeyEnvBeforeStoringSecret() = runBlocking {
        val client = RecordingHarnessClient()
        val binding = NativeHarnessProviderBinding(
            providerId = "qa-route",
            displayName = "QA route",
            settingsNamespace = "llm-pi-ai",
            settingsPath = listOf("providers", "qa-route"),
            revision = 7,
            value = null,
            apiKeyReference = "QA_ROUTE_API_KEY",
            credentialReferenceDerived = true,
            writable = true
        )
        val failures = mutableListOf<String>()
        val actions = NativeHarnessProviderCredentialActions(
            clientProvider = { client },
            bindingProvider = { if (it == binding.providerId) binding else null },
            refresh = {},
            reportFailure = { code, _ -> failures += code }
        )

        actions.set("qa-route", "qa-secret")

        assertTrue(failures.isEmpty())
        val route = client.routeCalls.single { it.path == "/api/adt/providerUpdate" }
        val args = route.body?.get("args")?.jsonObject ?: error("provider update args missing")
        assertEquals("llm-pi-ai", args["ns"]?.toString()?.trim('"'))
        assertEquals("7", args["expectedRevision"]?.toString())
        assertEquals(
            listOf("providers", "qa-route", "apiKeyEnv"),
            args["ops"]?.jsonArray?.single()?.jsonObject?.get("path")?.jsonArray
                ?.map { it.toString().trim('"') }
        )
        assertEquals("QA_ROUTE_API_KEY", args["credential"]?.jsonObject?.get("ref")?.toString()?.trim('"'))
        assertEquals("qa-secret", args["credential"]?.jsonObject?.get("value")?.toString()?.trim('"'))
    }

    @Test
    fun failedDerivedCredentialWriteRollsBackProfileReferenceWithCommittedRevision() = runBlocking {
        val client = RecordingHarnessClient().apply {
            providerRouteResult = providerRouteFailure("settings/conflict")
        }
        val binding = NativeHarnessProviderBinding(
            providerId = "qa-route",
            displayName = "QA route",
            settingsNamespace = NATIVE_CUSTOM_PROVIDER_SETTINGS_NAMESPACE,
            settingsPath = listOf("providers", "qa-route"),
            revision = 7,
            value = null,
            apiKeyReference = null,
            credentialReferenceDerived = true,
            writable = true,
        )
        val failures = mutableListOf<String>()
        val actions = NativeHarnessProviderCredentialActions(
            clientProvider = { client },
            bindingProvider = { if (it == binding.providerId) binding else null },
            refresh = {},
            reportFailure = { code, _ -> failures += code },
        )

        actions.set("qa-route", "qa-secret")

        assertEquals(1, client.routeCalls.count { it.path == "/api/adt/providerUpdate" })
        assertTrue(failures.contains("settings/conflict"))
        assertTrue(client.calls.none { it.namespace == "credentials" })
    }

    @Test
    fun unsettingCustomCredentialAlsoClearsPersistedApiKeyReference() = runBlocking {
        val client = RecordingHarnessClient()
        val binding = NativeHarnessProviderBinding(
            providerId = "qa-route",
            displayName = "QA route",
            settingsNamespace = NATIVE_CUSTOM_PROVIDER_SETTINGS_NAMESPACE,
            settingsPath = listOf("providers", "qa-route"),
            revision = 7,
            value = null,
            apiKeyReference = "QA_ROUTE_API_KEY",
            credentialReferenceDerived = false,
            writable = true,
        )
        val failures = mutableListOf<String>()
        val actions = NativeHarnessProviderCredentialActions(
            clientProvider = { client },
            bindingProvider = { if (it == binding.providerId) binding else null },
            refresh = {},
            reportFailure = { code, _ -> failures += code },
        )

        actions.unset("qa-route")

        assertTrue(failures.isEmpty())
        val cleanup = client.routeCalls.single { it.path == "/api/adt/providerUpdate" }
            .body?.get("args")?.jsonObject ?: error("provider update args missing")
        assertEquals("7", cleanup["expectedRevision"]?.toString())
        assertEquals(
            listOf("providers", "qa-route", "apiKeyEnv"),
            cleanup["ops"]?.jsonArray?.single()?.jsonObject?.get("path")?.jsonArray
                ?.map { it.toString().trim('"') },
        )
        assertEquals("QA_ROUTE_API_KEY", cleanup["credential"]?.jsonObject?.get("ref")?.toString()?.trim('"'))
        assertTrue(cleanup["credential"]?.jsonObject?.get("value") == null)
    }

    @Test
    fun controllerUsesAgentScopedGoalsEnvelope() = runBlocking {
        val client = RecordingHarnessClient()
        val controller = controller(client)
        try {
            controller.dispatch(NativeHarnessUiAction.CreateGoal("ship the app", 5))
            waitUntil { client.calls.any { it.namespace == "goals" && it.method == "create" } }
            val create = client.calls.first { it.namespace == "goals" && it.method == "create" }
            assertEquals("session-1", create.args["agentId"]?.toString()?.trim('"'))
            assertEquals("ship the app", create.args["request"]?.jsonObject?.get("objective")?.toString()?.trim('"'))
            assertEquals(5, create.args["request"]?.jsonObject?.get("maxGoalRounds")?.toString()?.toInt())
        } finally {
            controller.close()
        }
    }

    @Test
    fun cancellingInstallUsesTheRequestIdCreatedByController(): Unit = runBlocking {
        val client = RecordingHarnessClient()
        val controller = controller(client)
        try {
            waitForPluginManager(controller)
            controller.dispatch(NativeHarnessUiAction.InstallPlugin("official-tools"))
            val requestId = client.installRequest.await()
            controller.dispatch(NativeHarnessUiAction.CancelPluginInstall(requestId))
            waitUntil { client.calls.any { it.namespace == "pluginManager" && it.method == "cancelInstall" } }
            val cancel = client.calls.first { it.method == "cancelInstall" }
            assertEquals(requestId, cancel.args["requestId"]?.toString()?.trim('"'))
            client.releaseInstall.complete(Unit)
        } finally {
            if (!client.releaseInstall.isCompleted) client.releaseInstall.complete(Unit)
            controller.close()
        }
    }

    @Test(timeout = 10_000)
    fun cancellingDeferredPluginInspectionDoesNotStartInstaller(): Unit = runBlocking {
        val client = RecordingHarnessClient().also { it.deferInspect = true }
        val controller = controller(client)
        try {
            waitForPluginManager(controller)
            controller.dispatch(NativeHarnessUiAction.InstallPlugin("official-tools"))
            client.inspectStarted.await()
            val requestId = controller.state.value.extensions.pluginInstall.requestId
            assertTrue("inspection must publish a request id", !requestId.isNullOrBlank())

            controller.dispatch(NativeHarnessUiAction.CancelPluginInstall(requestId!!))
            waitUntil { controller.state.value.extensions.pluginInstall.phase == "cancelled" }
            controller.dispatch(NativeHarnessUiAction.CancelPluginInstall(requestId))
            client.releaseInspect.complete(Unit)
            repeat(20) { Thread.yield() }

            assertEquals(0, client.installCalls)
            assertTrue(client.calls.none {
                it.namespace == "pluginManager" && it.method == "cancelInstall"
            })
        } finally {
            if (!client.releaseInspect.isCompleted) client.releaseInspect.complete(Unit)
            if (!client.releaseInstall.isCompleted) client.releaseInstall.complete(Unit)
            controller.close()
        }
    }

    @Test
    fun refusingPlainDependencyInspectionDoesNotStartInstaller(): Unit = runBlocking {
        val client = RecordingHarnessClient().also { it.inspectBundle = false }
        val controller = controller(client)
        try {
            waitForPluginManager(controller)
            controller.dispatch(NativeHarnessUiAction.InstallPlugin("plain-dependency"))
            waitUntil { controller.state.value.extensions.pluginInstall.phase == "failed" }
            assertEquals(0, client.installCalls)
            assertTrue(client.calls.none {
                it.namespace == "pluginManager" && it.method == "installBundle"
            })
        } finally {
            controller.close()
        }
    }

    @Test(timeout = 10_000)
    fun acceptedGitInspectionWithUnknownBundleFlagReachesInstaller(): Unit = runBlocking {
        val client = RecordingHarnessClient().also {
            it.inspectKind = "git"
            it.inspectBundle = null
        }
        val controller = controller(client)
        try {
            waitForPluginManager(controller)
            controller.dispatch(NativeHarnessUiAction.InstallPlugin("git+https://example.invalid/qa.git"))
            client.installRequest.await()
            assertEquals(1, client.installCalls)
            client.releaseInstall.complete(Unit)
        } finally {
            if (!client.releaseInstall.isCompleted) client.releaseInstall.complete(Unit)
            controller.close()
        }
    }

    @Test(timeout = 10_000)
    fun approvedBuildRetryReusesAcceptedInspectionSubject(): Unit = runBlocking {
        val client = RecordingHarnessClient().also {
            it.installResult = HarnessRpcResult.Success(buildJsonObject {
                put("application", "failed")
                putJsonArray("pendingBuilds") { add(JsonPrimitive("native-addon")) }
                putJsonObject("packageResult") {
                    put("kind", "build-blocked")
                    put("output", "scripts need approval")
                }
            })
        }
        val controller = controller(client)
        try {
            waitForPluginManager(controller)
            controller.dispatch(NativeHarnessUiAction.InstallPlugin("official-tools"))
            client.installRequest.await()
            client.releaseInstall.complete(Unit)
            waitUntil {
                controller.state.value.extensions.pluginInstall.phase == "failed" &&
                    controller.state.value.extensions.pluginInstall.pendingBuilds == listOf("native-addon")
            }
            assertEquals(1, client.inspectCalls)

            client.installResult = HarnessRpcResult.Success(buildJsonObject {
                put("application", "applied")
            })
            controller.dispatch(
                NativeHarnessUiAction.ApprovePluginBuilds(
                    packageSpec = "official-tools",
                    approvedBuilds = listOf("native-addon")
                )
            )
            waitUntil { client.installCalls == 2 }
            waitUntil { controller.state.value.extensions.pluginInstall.phase == "done" }
            assertEquals("approved-build retry must retain the accepted subject", 1, client.inspectCalls)
        } finally {
            if (!client.releaseInstall.isCompleted) client.releaseInstall.complete(Unit)
            controller.close()
        }
    }

    @Test(timeout = 10_000)
    fun cancellingWhileInstallTransitionPublishesCannotStartInstaller(): Unit = runBlocking {
        val client = RecordingHarnessClient()
        var uiState = NativeHarnessUiState(
            extensions = HarnessExtensionsUiState(pluginManagementAvailable = true)
        )
        val transitionStarted = CompletableDeferred<Unit>()
        val releaseTransition = CompletableDeferred<Unit>()
        val actions = NativeHarnessPluginActions(
            clientProvider = { client },
            stateProvider = { uiState },
            mutate = { transform ->
                val next = transform(uiState)
                if (next.extensions.pluginInstall.phase == "installing" &&
                    !transitionStarted.isCompleted
                ) {
                    transitionStarted.complete(Unit)
                    releaseTransition.await()
                }
                uiState = next
            },
            reportFailure = { _, _ -> }
        )
        val installJob = launch(start = CoroutineStart.UNDISPATCHED) {
            actions.install(NativeHarnessUiAction.InstallPlugin("official-tools"))
        }
        var cancelJob: Job? = null
        try {
            withTimeout(5_000) {
                transitionStarted.await()
                val requestId = uiState.extensions.pluginInstall.requestId
                assertTrue("transition must expose a request id", !requestId.isNullOrBlank())

                // Start undispatched so the cancellation intent is recorded before it
                // waits on the install transition's lock.
                cancelJob = launch(start = CoroutineStart.UNDISPATCHED) {
                    actions.cancel(requestId!!)
                }
                releaseTransition.complete(Unit)
                installJob.join()
                cancelJob?.join()

                assertEquals("cancelled", uiState.extensions.pluginInstall.phase)
                assertEquals(0, client.installCalls)
                assertTrue(client.calls.none {
                    it.namespace == "pluginManager" && it.method == "installBundle"
                })
            }
        } finally {
            releaseTransition.complete(Unit)
            if (!client.releaseInstall.isCompleted) client.releaseInstall.complete(Unit)
            installJob.cancelAndJoin()
            cancelJob?.cancelAndJoin()
        }
    }

    @Test
    fun pluginRowToggleRequiresLiveEntryId() = runBlocking {
        val client = RecordingHarnessClient()
        val controller = controller(client)
        try {
            waitForPluginManager(controller)
            controller.dispatch(NativeHarnessUiAction.SetPluginRowEnabled(null, enabled = true))
            repeat(10) { Thread.yield() }
            assertTrue(client.calls.none {
                it.namespace == "pluginManager" && it.method == "setPluginEnabled"
            })

            controller.dispatch(NativeHarnessUiAction.SetPluginEnabled("missing-entry", enabled = true))
            repeat(10) { Thread.yield() }
            assertTrue("unknown inventory ids must not cross the mutation boundary", client.calls.none {
                it.namespace == "pluginManager" && it.method == "setPluginEnabled"
                    && it.args["id"]?.toString()?.trim('"') == "missing-entry"
            })

            controller.dispatch(
                NativeHarnessUiAction.SetPluginRowEnabled("include:managed", enabled = false)
            )
            waitUntil {
                client.calls.any {
                    it.namespace == "pluginManager" && it.method == "setPluginEnabled"
                }
            }
            val toggle = client.calls.first { it.method == "setPluginEnabled" }
            assertEquals("include:managed", toggle.args["id"]?.toString()?.trim('"'))
            assertEquals("false", toggle.args["enabled"]?.toString())
        } finally {
            controller.close()
        }
    }

    @Test
    fun presetDefaultUsesOfficialSettingsArguments() = runBlocking {
        val client = RecordingHarnessClient()
        val controller = controller(client)
        try {
            controller.dispatch(NativeHarnessUiAction.SetAgentPresetDefault("standard"))
            waitUntil {
                client.calls.any { it.namespace == "settings" && it.method == "update" }
            }
            val update = client.calls.first { it.namespace == "settings" && it.method == "update" }
            assertEquals("agent-presets", update.args["ns"]?.toString()?.trim('"'))
            assertEquals(
                "standard",
                update.args["patch"]?.jsonObject?.get("default")?.toString()?.trim('"')
            )
        } finally {
            controller.close()
        }
    }

    @Test
    fun workspaceRenameUsesOfficialRequestEnvelope() = runBlocking {
        val client = RecordingHarnessClient()
        val controller = controller(client)
        try {
            controller.dispatch(NativeHarnessUiAction.RenameWorkspace("workspace-1", "Project files"))
            waitUntil {
                client.calls.any { it.namespace == "workspace" && it.method == "rename" }
            }
            val rename = client.calls.first { it.namespace == "workspace" && it.method == "rename" }
            assertEquals("workspace-1", rename.args["request"]?.jsonObject?.get("workspaceId")?.toString()?.trim('"'))
            assertEquals("Project files", rename.args["request"]?.jsonObject?.get("title")?.toString()?.trim('"'))
        } finally {
            controller.close()
        }
    }

    @Test
    fun cordisRunUsesOfficialPanelArguments() = runBlocking {
        val client = RecordingHarnessClient()
        val controller = controller(client)
        try {
            controller.dispatch(NativeHarnessUiAction.RunCordis("cordis-demo", "pkg-2"))
            waitUntil {
                client.calls.any { it.namespace == "dynamicCordisRunner" && it.method == "runHostHalf" }
            }
            val run = client.calls.first { it.namespace == "dynamicCordisRunner" && it.method == "runHostHalf" }
            assertEquals("session-1", run.args["agentId"]?.toString()?.trim('"'))
            assertEquals("cordis-demo", run.args["pluginId"]?.toString()?.trim('"'))
            assertEquals("pkg-2", run.args["packageId"]?.toString()?.trim('"'))
            assertEquals("run", run.args["mode"]?.toString()?.trim('"'))
            assertEquals("null", run.args["requestId"]?.toString())
        } finally {
            controller.close()
        }
    }

    @Test
    fun selectingAgentPresetUsesAgentIdWireName() = runBlocking {
        val client = RecordingHarnessClient()
        val controller = controller(client)
        try {
            controller.dispatch(NativeHarnessUiAction.SelectAgentPreset("session-1", "standard"))
            waitUntil {
                client.calls.any { it.namespace == "agentPresets" && it.method == "select" }
            }
            val select = client.calls.first { it.namespace == "agentPresets" && it.method == "select" }
            assertEquals("session-1", select.args["agentId"]?.toString()?.trim('"'))
            assertEquals("standard", select.args["agentPreset"]?.toString()?.trim('"'))
        } finally {
            controller.close()
        }
    }

    @Test
    fun sessionExportIsForwardedToAuthenticatedHost() = runBlocking {
        val client = RecordingHarnessClient()
        var forwarded: NativeHarnessUiAction? = null
        val controller = controller(client) { forwarded = it }
        try {
            controller.dispatch(NativeHarnessUiAction.ExportSessionLog("session-1"))
            waitUntil { forwarded is NativeHarnessUiAction.ExportSessionLog }
            assertEquals(
                "session-1",
                (forwarded as NativeHarnessUiAction.ExportSessionLog).sessionId
            )
        } finally {
            controller.close()
        }
    }

    @Test
    fun providerLoginActionsUseTheNativeAuthorizationBroker() = runBlocking {
        val client = RecordingHarnessClient()
        var loginProvider: String? = null
        var loginMethod: String? = null
        val controller = NativeHarnessController(
            parentScope = CoroutineScope(Dispatchers.Unconfined + Job()),
            clientProvider = { client },
            runtime = runtimeCallbacks(),
            providerAuth = NativeHarnessProviderAuthHooks(
                login = { provider, method ->
                    loginProvider = provider
                    loginMethod = method
                    HarnessRpcResult.Success(JsonNull)
                }
            )
        )
        try {
            controller.dispatch(NativeHarnessUiAction.StartProviderLogin("provider-1", "oauth"))
            waitUntil { loginProvider != null }
            assertEquals("provider-1", loginProvider)
            assertEquals("oauth", loginMethod)
        } finally {
            controller.close()
        }
    }

    @Test
    fun messageFeedbackUsesTheOfficialRequestEnvelope() = runBlocking {
        val client = RecordingHarnessClient()
        val controller = controller(client)
        try {
            waitUntil { controller.state.value.selectedSessionId == "session-1" }
            controller.dispatch(
                NativeHarnessUiAction.SubmitMessageFeedback(
                    messageId = "message-2",
                    rating = HarnessFeedbackRating.POSITIVE,
                    note = "Useful answer",
                    category = "task-result"
                )
            )
            waitUntil { client.calls.any { it.namespace == "messageFeedback" && it.method == "put" } }
            val put = client.calls.first { it.namespace == "messageFeedback" && it.method == "put" }
            val request = put.args["request"]?.jsonObject
            assertEquals("session-1", request?.get("sessionId")?.toString()?.trim('"'))
            assertEquals("message-2", request?.get("messageId")?.toString()?.trim('"'))
            assertEquals("positive", request?.get("rating")?.toString()?.trim('"'))
            assertEquals("Useful answer", request?.get("note")?.toString()?.trim('"'))
            assertEquals("task-result", request?.get("category")?.toString()?.trim('"'))
            assertEquals("null", request?.get("ifVersion")?.toString())
        } finally {
            controller.close()
        }
    }

    @Test
    fun stoppedRuntimeShowsPersistedSessionsWithoutStartingHarness() = runBlocking {
        var starts = 0
        val controller = NativeHarnessController(
            parentScope = CoroutineScope(Dispatchers.Unconfined + Job()),
            clientProvider = { null },
            runtime = NativeHarnessRuntimeCallbacks(
                current = { HarnessRuntimeUiState(status = HarnessRuntimeStatus.STOPPED) },
                start = { starts += 1; HarnessRuntimeUiState(status = HarnessRuntimeStatus.RUNNING) },
                stop = { HarnessRuntimeUiState(status = HarnessRuntimeStatus.STOPPED) },
                forceStop = { HarnessRuntimeUiState(status = HarnessRuntimeStatus.STOPPED) }
            ),
            workspace = NativeHarnessWorkspaceHooks(
                readOfflineSessions = {
                    listOf(NativeHarnessOfflineSession("saved-1", "Saved task", "/workspace/projects/demo"))
                },
                resolveSession = { _, _, _, _ ->
                    HarnessWorkspaceUiState(projectFolder = "demo", backendLabel = "Local")
                }
            )
        )
        try {
            waitUntil { controller.state.value.sessions.any { it.id == "saved-1" } }
            assertEquals("saved-1", controller.state.value.selectedSessionId)
            assertEquals("demo", controller.state.value.workspace.projectFolder)
            controller.dispatch(NativeHarnessUiAction.SelectSession("saved-1"))
            assertEquals(0, starts)
        } finally {
            controller.close()
        }
    }

    @Test
    fun continueReopensSelectedSessionAndControlStreams() = runBlocking {
        val client = RecordingHarnessClient()
        val controller = controller(client)
        try {
            waitUntil { client.followCalls > 0 && client.controlCalls > 0 }
            val previousFollow = client.followCalls
            val previousControl = client.controlCalls
            controller.dispatch(NativeHarnessUiAction.Continue)
            waitUntil { client.followCalls > previousFollow && client.controlCalls > previousControl }
        } finally {
            controller.close()
        }
    }

    @Test
    fun controllerPublishesStructuredBaselineAndLoadsBoundedDetail() = runBlocking {
        val client = RecordingHarnessClient()
        val controller = controller(client)
        try {
            client.followReady.await()
            client.followFrames.emit(buildJsonObject {
                put("type", "snapshot")
                putJsonArray("records") {
                    add(controllerEvent(30, "tool/call") {
                        put("callId", "call-controller")
                        put("name", "shell")
                        put("arguments", "{\"command\":\"pwd\"}")
                    })
                    (31L..35L).forEach { sequence ->
                        add(controllerEvent(sequence, "user/message") {
                            putJsonArray("content") {
                                add(buildJsonObject {
                                    put("type", "text")
                                    put("text", "intervening-$sequence")
                                })
                            }
                        })
                    }
                    add(controllerEvent(36, "tool/result") {
                        putJsonObject("message") {
                            putJsonObject("source") { put("callId", "call-controller") }
                            putJsonArray("content") {
                                add(buildJsonObject {
                                    put("type", "tool-result")
                                    put("toolCallId", "call-controller")
                                    putJsonArray("content") {
                                        add(buildJsonObject { put("type", "text"); put("text", "done") })
                                    }
                                })
                            }
                        }
                    })
                }
            })
            waitUntil {
                controller.state.value.structuredTranscript.any { it.id == "structured-tool-call-controller" }
            }
            val row = controller.state.value.structuredTranscript.first { it.id == "structured-tool-call-controller" }
            val tool = row.parts.single() as NativeHarnessStructuredTranscriptPart.Tool
            assertEquals(NativeHarnessStructuredToolStatus.COMPLETED, tool.status)
            assertEquals(listOf(30L, 36L), (row.detailRef as NativeHarnessTranscriptDetailRef.SessionEvents).sequences)

            client.pageResultsByThroughSeq = mapOf(
                30L to HarnessRpcResult.Success(buildJsonObject {
                    putJsonArray("records") {
                        add(controllerEvent(30, "tool/call") {
                            put("callId", "call-controller")
                            put("name", "shell")
                            put("arguments", "{\"command\":\"pwd\"}")
                        })
                    }
                }),
                36L to HarnessRpcResult.Success(buildJsonObject {
                    putJsonArray("records") {
                        add(controllerEvent(36, "tool/result") {
                            putJsonObject("message") {
                                putJsonObject("source") { put("callId", "call-controller") }
                                putJsonArray("content") {
                                    add(buildJsonObject {
                                        put("type", "tool-result")
                                        put("toolCallId", "call-controller")
                                        putJsonArray("content") {
                                            add(buildJsonObject { put("type", "text"); put("text", "done") })
                                        }
                                    })
                                }
                            }
                        })
                    }
                }),
            )
            controller.dispatch(NativeHarnessUiAction.LoadTranscriptDetail(row.id, 0))
            waitUntil { controller.state.value.structuredDetail.page != null }
            assertTrue(controller.state.value.structuredDetail.page!!.lines.any { it.text == "done" })
            val pageCalls = client.calls.filter { it.namespace == "session" && it.method == "page" }
            assertEquals(2, pageCalls.size)
            assertEquals(
                setOf(30L, 36L),
                pageCalls.map { it.args["request"]!!.jsonObject["throughSeq"]!!.toString().toLong() }.toSet(),
            )
            pageCalls.forEach { call ->
                val request = call.args["request"]!!.jsonObject
                val throughSeq = request["throughSeq"]!!.toString().toLong()
                assertEquals(1, request["maxMessages"]?.toString()?.toInt())
                assertEquals(throughSeq + 1L, request["beforeSeq"]?.toString()?.toLong())
            }
        } finally {
            controller.close()
        }
    }

    @Test
    fun controllerMergesLiveToolResultWithoutRetainingRawEvents() = runBlocking {
        val client = RecordingHarnessClient()
        val controller = controller(client)
        try {
            client.followReady.await()
            client.followFrames.emit(buildJsonObject {
                put("type", "event")
                put("event", controllerEvent(50, "tool/call") {
                    put("callId", "live-call")
                    put("name", "list")
                    put("arguments", "{}")
                })
            })
            waitUntil {
                controller.state.value.structuredTranscript.singleOrNull()
                    ?.parts?.filterIsInstance<NativeHarnessStructuredTranscriptPart.Tool>()
                    ?.singleOrNull()?.status == NativeHarnessStructuredToolStatus.RUNNING
            }
            client.followFrames.emit(buildJsonObject {
                put("type", "event")
                put("event", controllerEvent(51, "tool/result") {
                    putJsonObject("message") {
                        putJsonObject("source") { put("callId", "live-call") }
                        putJsonArray("content") {
                            add(buildJsonObject {
                                put("type", "tool-result")
                                put("toolCallId", "live-call")
                                putJsonArray("content") {
                                    add(buildJsonObject { put("type", "text"); put("text", "ok") })
                                }
                            })
                        }
                    }
                })
            })
            waitUntil {
                controller.state.value.structuredTranscript.singleOrNull()
                    ?.parts?.filterIsInstance<NativeHarnessStructuredTranscriptPart.Tool>()
                    ?.singleOrNull()?.status == NativeHarnessStructuredToolStatus.COMPLETED
            }
            assertEquals(
                listOf(50L, 51L),
                (controller.state.value.structuredTranscript.single().detailRef
                    as NativeHarnessTranscriptDetailRef.SessionEvents).sequences,
            )
        } finally {
            controller.close()
        }
    }

    @Test
    fun detailReplyIsDiscardedAfterSessionSelectionChanges() = runBlocking {
        val client = RecordingHarnessClient().also {
            it.sessionItems = listOf("session-1", "session-2")
            it.pageGate = CompletableDeferred()
        }
        val controller = controller(client)
        try {
            client.followReady.await()
            client.followFrames.emit(buildJsonObject {
                put("type", "snapshot")
                putJsonArray("records") {
                    add(controllerEvent(40, "tool/call") {
                        put("callId", "guard-call")
                        put("name", "shell")
                        put("arguments", "{}")
                    })
                }
            })
            waitUntil { controller.state.value.structuredTranscript.isNotEmpty() }
            val itemId = controller.state.value.structuredTranscript.single().id
            controller.dispatch(NativeHarnessUiAction.LoadTranscriptDetail(itemId, 0))
            waitUntil { client.calls.any { it.namespace == "session" && it.method == "page" } }
            controller.dispatch(NativeHarnessUiAction.SelectSession("session-2"))
            waitUntil { controller.state.value.selectedSessionId == "session-2" }
            client.pageGate!!.complete(Unit)
            Thread.sleep(20)
            assertTrue(controller.state.value.structuredTranscript.isEmpty())
            assertNull(controller.state.value.structuredDetail.itemId)
            assertNull(controller.state.value.structuredDetail.page)
        } finally {
            client.pageGate?.complete(Unit)
            controller.close()
        }
    }

    private fun controller(
        client: RecordingHarnessClient,
        externalAction: suspend (NativeHarnessUiAction) -> Unit = {}
    ): NativeHarnessController = NativeHarnessController(
        parentScope = CoroutineScope(Dispatchers.Unconfined + Job()),
        clientProvider = { client },
        runtime = NativeHarnessRuntimeCallbacks(
            current = { HarnessRuntimeUiState(status = HarnessRuntimeStatus.RUNNING) },
            start = { HarnessRuntimeUiState(status = HarnessRuntimeStatus.RUNNING) },
            stop = { HarnessRuntimeUiState(status = HarnessRuntimeStatus.STOPPED) },
            forceStop = { HarnessRuntimeUiState(status = HarnessRuntimeStatus.STOPPED) }
        ),
        handleExternalAction = externalAction
    )

    private fun runtimeCallbacks(): NativeHarnessRuntimeCallbacks = NativeHarnessRuntimeCallbacks(
        current = { HarnessRuntimeUiState(status = HarnessRuntimeStatus.RUNNING) },
        start = { HarnessRuntimeUiState(status = HarnessRuntimeStatus.RUNNING) },
        stop = { HarnessRuntimeUiState(status = HarnessRuntimeStatus.STOPPED) },
        forceStop = { HarnessRuntimeUiState(status = HarnessRuntimeStatus.STOPPED) }
    )

    private fun waitUntil(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 2_000_000_000L
        while (System.nanoTime() < deadline) {
            if (condition()) return
            Thread.sleep(1)
        }
        assertTrue("Harness action was not sent", condition())
    }

    private fun waitForPluginManager(controller: NativeHarnessController) {
        controller.dispatch(NativeHarnessUiAction.LoadManagement(HarnessManagementArea.EXTENSIONS))
        waitUntil { controller.state.value.extensions.pluginManagementAvailable == true }
    }

    private fun controllerEvent(
        sequence: Long,
        type: String,
        data: JsonObjectBuilder.() -> Unit,
    ): JsonObject = buildJsonObject {
        put("seq", sequence)
        put("type", type)
        if (type in setOf("assistant/message", "user/message", "tool/result")) {
            put("surfaceOp", "append")
        }
        put("data", buildJsonObject(data))
    }

    private class RecordingHarnessClient : HarnessClient {
        data class Call(val namespace: String, val method: String, val args: JsonObject)
        data class RouteCall(val path: String, val body: JsonObject?)

        val calls = CopyOnWriteArrayList<Call>()
        val routeCalls = CopyOnWriteArrayList<RouteCall>()
        val followFrames = MutableSharedFlow<JsonElement>(extraBufferCapacity = 16)
        val followReady = CompletableDeferred<Unit>()
        var sessionItems: List<String> = listOf("session-1")
        var serveReferenceCatalogs = false
        var failReferenceCatalogs = false
        var pageResult: HarnessRpcResult = HarnessRpcResult.Success(buildJsonObject {
            putJsonArray("records") {}
        })
        var pageResultsByThroughSeq: Map<Long, HarnessRpcResult> = emptyMap()
        var pageGate: CompletableDeferred<Unit>? = null
        @Volatile var followCalls = 0
        @Volatile var controlCalls = 0
        var customProviderSettings = false
        var settingsMutateResult: HarnessRpcResult = HarnessRpcResult.Success(JsonNull)
        var credentialsSetResult: HarnessRpcResult = HarnessRpcResult.Success(JsonNull)
        var credentialsUnsetResult: HarnessRpcResult = HarnessRpcResult.Success(JsonNull)
        var providerRouteResult: HarnessRpcResult = HarnessRpcResult.Success(
            buildJsonObject { put("saved", true) }
        )
        val installRequest = CompletableDeferred<String>()
        val releaseInstall = CompletableDeferred<Unit>()
        var deferInspect = false
        var inspectBundle: Boolean? = true
        var inspectKind = "registry"
        val inspectStarted = CompletableDeferred<Unit>()
        val releaseInspect = CompletableDeferred<Unit>()
        var installCalls = 0
        var inspectCalls = 0
        var installResult: HarnessRpcResult = HarnessRpcResult.Success(JsonNull)
        override val state = MutableStateFlow(HarnessConnectionState.READY)

        override suspend fun call(
            namespace: String,
            method: String,
            args: JsonObject,
            policy: HarnessCallPolicy,
            requestId: String
        ): HarnessRpcResult {
            calls += Call(namespace, method, args)
            return when (namespace to method) {
                "skills" to "list", "commands" to "list" -> when {
                    failReferenceCatalogs -> HarnessRpcResult.Failure(HarnessRpcError("CATALOG_UNAVAILABLE", "Unavailable"))
                    !serveReferenceCatalogs -> HarnessRpcResult.Success(JsonNull)
                    namespace == "skills" -> HarnessRpcResult.Success(buildJsonObject {
                        putJsonArray("skills") { add(buildJsonObject { put("name", "old-skill") }) }
                    })
                    else -> HarnessRpcResult.Success(kotlinx.serialization.json.buildJsonArray {
                        add(buildJsonObject { put("name", "old-command") })
                    })
                }
                "session" to "list" -> HarnessRpcResult.Success(buildJsonObject {
                    putJsonArray("items") {
                        sessionItems.forEach { sessionId ->
                            add(buildJsonObject {
                                put("sessionId", sessionId)
                                put("cwd", "/workspace/projects/demo")
                            })
                        }
                    }
                })
                "session" to "page" -> {
                    pageGate?.await()
                    val throughSeq = args["request"]?.jsonObject
                        ?.get("throughSeq")?.toString()?.toLongOrNull()
                    throughSeq?.let { pageResultsByThroughSeq[it] } ?: pageResult
                }
                "settings" to "describe" -> HarnessRpcResult.Success(buildJsonObject {
                    put("writable", true)
                    put("hasDocument", true)
                    putJsonArray("namespaces") {
                        if (customProviderSettings) {
                            add(buildJsonObject {
                                put("ns", "llm-pi-ai")
                                put("revision", 3)
                                putJsonObject("schema") {}
                                putJsonObject("value") {}
                                putJsonObject("user") {}
                            })
                        }
                    }
                })
            "settings" to "mutate" -> settingsMutateResult
            "credentials" to "set" -> credentialsSetResult
            "credentials" to "unset" -> credentialsUnsetResult
            "adt" to "providerCreate",
            "adt" to "providerUpdate",
            "adt" to "providerDelete" -> {
                routeCalls += RouteCall(
                    "/api/adt/$method",
                    buildJsonObject { put("args", args) },
                )
                providerRouteResult
            }
            "pluginInventory" to "list" -> HarnessRpcResult.Success(buildJsonObject {
                    put("managementAvailable", true)
                    putJsonArray("entries") {
                        add(buildJsonObject {
                            put("entryId", "include:managed")
                            put("moduleName", "managed")
                            put("enabled", true)
                        })
                    }
                })
                "pluginManager" to "inspect" -> {
                    inspectCalls += 1
                    inspectStarted.complete(Unit)
                    if (deferInspect) releaseInspect.await()
                    HarnessRpcResult.Success(buildJsonObject {
                        put("status", "accepted")
                        put("kind", inspectKind)
                        put("name", "@adt/official-tools")
                        put("version", "0.0.1")
                        put("bundle", inspectBundle)
                    })
                }
                "pluginManager" to "installBundle" -> {
                    installCalls += 1
                    val id = args["options"]?.jsonObject?.get("requestId")?.toString()?.trim('"')
                        ?: error("controller did not send an install request id")
                    installRequest.complete(id)
                    releaseInstall.await()
                    installResult
                }
                "pluginManager" to "cancelInstall" -> HarnessRpcResult.Success(buildJsonObject {
                    put("status", "cancelled")
                })
                else -> HarnessRpcResult.Success(JsonNull)
            }
        }

        override fun stream(
            namespace: String,
            method: String,
            args: JsonObject,
            policy: HarnessStreamPolicy
        ): Flow<JsonElement> {
            if (namespace == "session" && method == "follow") followCalls++
            if (namespace == "session" && method == "control") controlCalls++
            if (namespace == "session" && method == "follow") {
                return flow {
                    emitAll(followFrames.onSubscription { followReady.complete(Unit) })
                }
            }
            return emptyFlow()
        }

        override suspend fun fetchJson(
            path: String,
            query: Map<String, String>,
            body: JsonObject?,
            requestId: String,
        ): HarnessRpcResult {
            routeCalls += RouteCall(path, body)
            return providerRouteResult
        }

        override suspend fun authenticate(launchUrl: String): HarnessAuthResult =
            HarnessAuthResult.Success("http://127.0.0.1")

        override fun adoptAuthenticatedEndpoint(origin: String, cookieHeader: String): HarnessAuthResult =
            HarnessAuthResult.Success(origin)

        override fun close() = Unit
    }
}
