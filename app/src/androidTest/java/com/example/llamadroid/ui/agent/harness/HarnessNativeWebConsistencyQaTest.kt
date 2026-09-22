package com.example.llamadroid.ui.agent.harness

import android.annotation.SuppressLint
import android.app.Instrumentation
import android.os.Build
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.core.content.FileProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.llamadroid.BuildConfig
import com.example.llamadroid.data.db.HarnessWorkspaceEntity
import com.example.llamadroid.harness.HarnessAppRuntime
import com.example.llamadroid.harness.HarnessWorkspaceScope
import com.example.llamadroid.harness.uploadHarnessAttachment
import com.example.llamadroid.harness.client.HarnessCallPolicy
import com.example.llamadroid.harness.client.HarnessClient
import com.example.llamadroid.harness.client.HarnessRpcResult
import com.example.llamadroid.harness.runtime.HarnessEndpoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

private fun qaHarnessFail(message: String): Nothing = throw AssertionError(message)

/**
 * Opt-in release-carrier coverage for the two authenticated Harness surfaces.
 *
 * This test deliberately uses the production AppRuntime and NativeHarnessController while a
 * separate WebView posts the same RPC envelopes to the same loopback origin. It never prompts a
 * model, contacts a provider endpoint, or changes a pre-existing credential. Its provider test
 * writes one uniquely scoped synthetic credential and removes it before returning. The test is
 * opt-in because it starts the packaged DSH process.
 */
@RunWith(AndroidJUnit4::class)
@SuppressLint("SetJavaScriptEnabled")
class HarnessNativeWebConsistencyQaTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test
    fun qaNativeControllerRefreshesAfterAuthenticatedWebMutations() = runBlocking {
        assumeTrue("Use the explicit x86 Harness QA carrier", BuildConfig.HARNESS_QA_X86)
        assumeTrue("Use the x86_64 Harness QA carrier", Build.SUPPORTED_ABIS.firstOrNull() == "x86_64")
        assumeTrue(
            "Pass harness_native_web_consistency_qa=true on the isolated QA carrier",
            InstrumentationRegistry.getArguments().getString("harness_native_web_consistency_qa") == "true"
        )

        val runtime = HarnessAppRuntime.get(context)
        val wasRunning = runtime.status.value?.state == "RUNNING" && runtime.client != null
        var ownsRuntime = false
        var workspace: HarnessWorkspaceEntity? = null
        var fixtureScope: HarnessWorkspaceScope? = null
        var syntheticPlugin: SyntheticPluginFixture? = null
        var sessionId: String? = null
        var markerPath: String? = null
        var controller: NativeHarnessController? = null
        var scenario: ActivityScenario<ComponentActivity>? = null
        var web: AuthenticatedWebSurface? = null
        var primaryFailure: Throwable? = null
        try {
            val activity = ActivityScenario.launch(ComponentActivity::class.java)
            scenario = activity
            ownsRuntime = !wasRunning
            startAndAwait(runtime)
            val client = requireNotNull(runtime.client) { "HarnessAppRuntime reported RUNNING without a client" }
            val endpoint = requireNotNull(runtime.endpoint.value) { "HarnessAppRuntime published no endpoint" }

            val seed = "native-web-consistency-${UUID.randomUUID()}"
            val createdWorkspace = runtime.workspaces.createWorkspace(seed)
            workspace = createdWorkspace
            val offlineScope = runtime.workspaces.openWorkspace(createdWorkspace.id)
            fixtureScope = offlineScope
            val marker = "qa/$seed.txt"
            markerPath = marker
            val markerText = "NATIVE_WEB_SHARED_${UUID.randomUUID()}\n"
            runtime.files.writeBytes(offlineScope, marker, markerText.toByteArray())

            requireSuccess(client.prepareWorkspace(buildJsonObject {
                put("workspaceId", createdWorkspace.id)
                put("guestPath", createdWorkspace.guestPath)
                put("backend", createdWorkspace.backend)
            }), "adt/prepareWorkspace")
            val created = requireSuccess(client.createSession(buildJsonObject {
                put("cwd", createdWorkspace.guestPath)
            }), "session/create")
            val createdSessionId = created.string("sessionId")
                ?: qaHarnessFail("session/create returned no sessionId")
            sessionId = createdSessionId
            runtime.workspaces.importSession(
                sessionId = createdSessionId,
                title = seed,
                cwd = createdWorkspace.guestPath,
                preferredWorkspaceId = createdWorkspace.id
            )

            val projectionReader = NativeHarnessSessionProjectionReader()
            val nativeController = NativeHarnessController(
                parentScope = runtime.scope,
                clientProvider = { runtime.client },
                runtime = NativeHarnessRuntimeCallbacks(
                    current = { runtime.toHarnessRuntimeUiState() },
                    start = { runtime.start(); runtime.toHarnessRuntimeUiState() },
                    stop = { runtime.stop(); runtime.toHarnessRuntimeUiState() },
                    forceStop = { runtime.forceStop(); runtime.toHarnessRuntimeUiState() }
                ),
                workspace = NativeHarnessWorkspaceHooks(
                    resolveSession = resolve@{ id, title, cwd, archived ->
                        val existing = runtime.database.harnessDao().session(id)
                        val existingWorkspace = existing?.let {
                            runtime.database.harnessDao().workspace(it.workspaceId)
                        }
                        val resolvedCwd = cwd?.takeIf { it.isNotBlank() }
                            ?: existingWorkspace?.guestPath
                            ?: createdWorkspace.guestPath.takeIf { id == createdSessionId }
                        if (resolvedCwd == null) return@resolve null
                        runtime.workspaces.importSession(
                            sessionId = id,
                            title = title,
                            cwd = resolvedCwd,
                            preferredWorkspaceId = existing?.workspaceId
                                ?: createdWorkspace.id.takeIf { id == createdSessionId },
                            archived = archived
                        )
                        val mapped = runtime.workspaces.scope(id)
                        HarnessWorkspaceUiState(
                            projectFolder = mapped.workspace.projectFolder,
                            backendLabel = mapped.workspace.backend,
                            rootLabel = mapped.workspace.guestPath,
                            previewAvailable = true
                        )
                    },
                    readSessionProjection = { id -> projectionReader.read(runtime.client, id) },
                    invalidateSessionProjection = { projectionReader.invalidate() }
                )
            )
            controller = nativeController

            awaitCondition("native controller did not list the synthetic session") {
                nativeController.state.value.sessions.any { it.id == createdSessionId }
            }
            nativeController.dispatch(NativeHarnessUiAction.SelectSession(createdSessionId))
            awaitCondition("native controller did not select the synthetic session") {
                nativeController.state.value.selectedSessionId == createdSessionId
            }
            val mappedBeforeWeb = requireNotNull(runtime.database.harnessDao().session(createdSessionId))
            assertEquals("Native session mapping changed workspace identity", createdWorkspace.id, mappedBeforeWeb.workspaceId)

            val authenticatedWeb = AuthenticatedWebSurface(instrumentation, endpoint)
            web = authenticatedWeb
            authenticatedWeb.attach(activity)

            val fileValue = authenticatedWeb.rpc("workspaceFiles", "read", buildJsonObject {
                put("workspaceFileScopeId", createdSessionId)
                put("path", "${createdWorkspace.guestPath}/$marker")
                putJsonObject("range") { put("offset", 1); put("limit", 8) }
            })
            assertEquals("Native-written marker was not visible through WebView", markerText.trimEnd(), fileValue.string("text"))

            val renamed = "$seed-renamed"
            requireWebSuccess(authenticatedWeb.rpc("session", "rename", buildJsonObject {
                putJsonObject("request") {
                    put("sessionId", createdSessionId)
                    put("title", renamed)
                }
            }), "session/rename")
            requireWebSuccess(authenticatedWeb.rpc("workspace", "archiveSession", buildJsonObject {
                putJsonObject("request") { put("sessionId", createdSessionId) }
            }), "workspace/archiveSession")

            // Direct controller refresh intentionally bypasses HarnessAgentRoute's WebView
            // dismissal callback. This isolates the native client/controller consistency seam.
            withTimeout(45_000L) { nativeController.refreshAfterWebView() }
            awaitCondition("native refresh did not retain the renamed session") {
                nativeController.state.value.sessions.firstOrNull { it.id == createdSessionId }?.title == renamed
            }
            awaitCondition("native refresh did not project archiveSession") {
                nativeController.state.value.sessions.firstOrNull { it.id == createdSessionId }?.isArchived == true
            }
            assertEquals(
                "Room archive mirror did not follow authoritative WebView mutation",
                true,
                runtime.database.harnessDao().session(createdSessionId)?.archived
            )
            assertEquals(
                "Room title mirror did not follow authoritative WebView mutation",
                renamed,
                runtime.database.agentChatDao().getConversation(mappedBeforeWeb.conversationId)?.title
            )

            requireWebSuccess(authenticatedWeb.rpc("workspace", "unarchiveSession", buildJsonObject {
                putJsonObject("request") { put("sessionId", createdSessionId) }
            }), "workspace/unarchiveSession")
            withTimeout(45_000L) { nativeController.refreshAfterWebView() }
            awaitCondition("native refresh did not project unarchiveSession") {
                nativeController.state.value.sessions.firstOrNull { it.id == createdSessionId }?.isArchived == false
            }
            assertEquals(false, runtime.database.harnessDao().session(createdSessionId)?.archived)

            exerciseTerminalLifecycle(runtime, createdSessionId)
            exerciseAttachmentUploadAndNativeReceipt(runtime, nativeController, createdSessionId)
            exerciseSettingsRevisionRoundTrip(authenticatedWeb, nativeController)
            exercisePluginInventory(authenticatedWeb, nativeController)
            val fixture = newSyntheticPluginFixture(createdWorkspace.guestPath, seed)
            syntheticPlugin = fixture
            createSyntheticPluginFixture(runtime, offlineScope, fixture)
            exerciseSyntheticPluginLifecycle(authenticatedWeb, nativeController, fixture)
            exerciseSyntheticProviderLifecycle(authenticatedWeb, nativeController)
        } catch (failure: Throwable) {
            primaryFailure = failure
        } finally {
            withContext(NonCancellable) {
                suspend fun recordCleanup(action: suspend () -> Unit) {
                    try {
                        action()
                    } catch (failure: Throwable) {
                        primaryFailure = combineFailures(primaryFailure, failure)
                    }
                }
                recordCleanup {
                    cleanupSyntheticPlugin(
                        runtime = runtime,
                        web = web,
                        controller = controller,
                        scope = fixtureScope,
                        fixture = syntheticPlugin
                    )
                }
                recordCleanup { web?.close() }
                recordCleanup { scenario?.close() }
                recordCleanup { controller?.close() }
                recordCleanup {
                    cleanupOwnedHarnessData(
                        runtime = runtime,
                        workspace = workspace,
                        sessionId = sessionId,
                        markerPath = markerPath
                    )
                }
                if (ownsRuntime) {
                    recordCleanup {
                        withTimeout(30_000L) { runtime.forceStop() }
                        check(runtime.status.value?.state == "STOPPED") {
                            "Harness force cleanup ended in ${runtime.status.value?.state}"
                        }
                        check(runtime.client == null) { "Harness force cleanup left an authenticated client" }
                    }
                }
            }
        }
        primaryFailure?.let { throw it }
    }

    private suspend fun startAndAwait(runtime: HarnessAppRuntime) {
        try {
            runtime.start()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            qaHarnessFail("HarnessAppRuntime.start failed: ${failure.javaClass.simpleName}: ${failure.message}")
        }
        withTimeout(120_000L) {
            while (runtime.status.value?.state != "RUNNING" || runtime.client == null || runtime.endpoint.value == null) {
                val state = runtime.status.value?.state
                if (state in setOf("FAILED", "INTERRUPTED")) {
                    qaHarnessFail("HarnessAppRuntime did not become ready (state=$state, error=${runtime.errorCode.value})")
                }
                delay(100L)
            }
        }
    }

    private suspend fun awaitCondition(message: String, predicate: () -> Boolean) {
        withTimeout(45_000L) {
            while (!predicate()) delay(100L)
        }
        assertTrue(message, predicate())
    }

    private suspend fun exerciseSettingsRevisionRoundTrip(
        web: AuthenticatedWebSurface,
        controller: NativeHarnessController
    ) {
        val target = findKnownSafeSetting(web.rpc("settings", "describe"))
            ?: qaHarnessFail("pinned ui-onboarding.welcomeNoticeVersion setting was unavailable")
        val distinct = "adt-qa-${UUID.randomUUID()}"
        var primaryFailure: Throwable? = null
        try {
            val setResult = web.rpc("settings", "mutate", buildJsonObject {
                put("ns", target.namespace)
                putJsonArray("ops") { add(setOperation(target.key, JsonPrimitive(distinct))) }
                put("expectedRevision", target.revision)
            })
            assertTrue("settings/mutate returned an invalid result", setResult !is JsonNull)
            val applied = findNamespace(web.rpc("settings", "describe"), target.namespace)
                ?: qaHarnessFail("settings/describe lost namespace ${target.namespace} after mutation")
            assertEquals(
                "settings/mutate did not apply the distinct value",
                JsonPrimitive(distinct),
                applied.objectValue("user")?.get(target.key)
            )
            withTimeout(45_000L) { controller.refreshAfterWebView() }
            awaitCondition("native settings schema did not project the WebView mutation") {
                controller.state.value.schemaSections
                    .firstOrNull { it.title == target.namespace }
                    ?.fields
                    ?.any { it.key == "${target.namespace}.${target.key}" && it.value == distinct } == true
            }
        } catch (failure: Throwable) {
            primaryFailure = failure
        }
        try {
            val latest = findNamespace(web.rpc("settings", "describe"), target.namespace)
                ?: qaHarnessFail("settings/describe lost namespace ${target.namespace} before restore")
            val latestRevision = latest.int("revision")
                ?: qaHarnessFail("settings/describe returned no revision before restore")
            web.rpc("settings", "mutate", buildJsonObject {
                put("ns", target.namespace)
                putJsonArray("ops") {
                    if (target.wasOverridden) add(setOperation(target.key, target.original))
                    else add(unsetOperation(target.key))
                }
                put("expectedRevision", latestRevision)
            })
            val restored = findNamespace(web.rpc("settings", "describe"), target.namespace)
                ?: qaHarnessFail("settings/describe lost namespace ${target.namespace} after restore")
            val restoredUser = restored.objectValue("user") ?: buildJsonObject {}
            if (target.wasOverridden) {
                assertEquals("settings restore changed the original user value", target.original, restoredUser[target.key])
            } else {
                assertFalse("settings restore left an override behind", restoredUser.containsKey(target.key))
            }
            withTimeout(45_000L) { controller.refreshAfterWebView() }
        } catch (restoreFailure: Throwable) {
            primaryFailure = combineFailures(primaryFailure, restoreFailure)
        }
        primaryFailure?.let { throw it }
    }

    /**
     * Exercises the retained native terminal repository without starting a model turn. The
     * repository owns the follow attachment, so this catches agentId/attachmentId drift in
     * create, follow, write, resize, rename, and close while the WebView remains authenticated.
     */
    private suspend fun exerciseTerminalLifecycle(runtime: HarnessAppRuntime, sessionId: String) {
        val terminals = runtime.terminals
        val terminalId = "qa-${UUID.randomUUID()}"
        val marker = "NATIVE_WEB_TERMINAL_${UUID.randomUUID()}"
        var created = false
        try {
            terminals.create(sessionId, id = terminalId)
            created = true
            terminals.awaitAttached(sessionId, terminalId)
            terminals.send(sessionId, terminalId, "printf '$marker\\n'")
            awaitCondition("native terminal follow did not expose shell output") {
                terminals.states.value[sessionId].orEmpty()
                    .firstOrNull { it.sessionId == terminalId }
                    ?.transcript?.contains(marker) == true
            }
            terminals.resize(sessionId, terminalId, cols = 100, rows = 30)
            val renamed = "QA terminal ${terminalId.takeLast(8)}"
            terminals.rename(sessionId, terminalId, renamed)
            awaitCondition("native terminal rename did not refresh the row") {
                terminals.states.value[sessionId].orEmpty()
                    .firstOrNull { it.sessionId == terminalId }
                    ?.displayName == renamed
            }
        } finally {
            if (created) {
                try {
                    terminals.close(sessionId, terminalId)
                } catch (_: Throwable) {
                    // The owning test reports the primary terminal assertion; close is best effort.
                }
            }
        }
        assertFalse(
            "native terminal close left the synthetic terminal visible",
            terminals.states.value[sessionId].orEmpty().any { it.sessionId == terminalId }
        )
    }

    /** Uses the same FileProvider path as the Android picker and the production upload helper. */
    private suspend fun exerciseAttachmentUploadAndNativeReceipt(
        runtime: HarnessAppRuntime,
        controller: NativeHarnessController,
        sessionId: String
    ) {
        val file = File(context.cacheDir, "harness-qa-attachment-${UUID.randomUUID()}.txt")
        val text = "NATIVE_WEB_ATTACHMENT_${UUID.randomUUID()}"
        file.writeText(text)
        try {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            val receipt = uploadHarnessAttachment(runtime, uri, sessionId)
            val receiptId = receipt.string("receiptId")
                ?: qaHarnessFail("fileUploads/upload returned no receiptId")
            assertNotNull("fileUploads/upload returned no durable file", receipt.objectValue("file"))
            controller.addAttachment(sessionId, receipt)
            awaitCondition("native controller did not accept the captured upload receipt") {
                controller.state.value.attachments.any { it.id == receiptId && it.label == file.name }
            }
        } finally {
            assertTrue("synthetic attachment cache file could not be deleted", file.delete() || !file.exists())
        }
    }

    private suspend fun exercisePluginInventory(
        web: AuthenticatedWebSurface,
        controller: NativeHarnessController
    ) {
        val plugins = web.rpc("pluginManager", "listPlugins").rows()
        val bundles = web.rpc("pluginManager", "listBundles").rows()
        assertTrue("plugin manager returned no inventory", plugins.isNotEmpty() || bundles.isNotEmpty())
        withTimeout(45_000L) { controller.refreshAfterWebView() }
        awaitCondition("native plugin inventory did not refresh after WebView inventory") {
            controller.state.value.extensions.pluginInventory.isNotEmpty() || plugins.isEmpty()
        }
        android.util.Log.i(TAG, "Plugin inventory verified without toggling a bundled runtime component")
    }

    /** Exercises the official custom-provider route and credential seams without inference. */
    private suspend fun exerciseSyntheticProviderLifecycle(
        web: AuthenticatedWebSurface,
        controller: NativeHarnessController
    ) {
        val suffix = UUID.randomUUID().toString().replace("-", "").take(12)
        val route = "adt-qa-gateway-$suffix"
        val fixture = SyntheticProviderFixture(
            route = route,
            displayName = "ADT QA gateway $suffix",
            credentialReference = nativeCustomProviderCredentialReference(route),
            modelId = "adt-qa-model-$suffix",
            baseUrl = "http://127.0.0.1:9/v1",
            nativeBaseUrl = "http://127.0.0.1:8/v1",
            secret = "qa_secret_$suffix"
        )
        var primaryFailure: Throwable? = null
        var created = false
        var baselineProviders = buildJsonObject {}
        try {
            val section = findNamespace(web.rpc("settings", "describe"), "llm-pi-ai")
                ?: qaHarnessFail("llm-pi-ai settings namespace was unavailable")
            val currentProviders = section.objectValue("value")?.objectValue("providers")
                ?: buildJsonObject {}
            baselineProviders = currentProviders
            assertFalse(
                "synthetic provider route unexpectedly existed before creation",
                currentProviders.containsKey(fixture.route)
            )
            controller.dispatch(
                NativeHarnessUiAction.CreateCustomProvider(
                    NativeHarnessCustomProviderRequest(
                        route = fixture.route,
                        displayName = fixture.displayName,
                        api = "openai-completions",
                        baseUrl = fixture.baseUrl,
                        apiKey = fixture.secret,
                        models = listOf(
                            NativeHarnessCustomProviderModel(
                                id = fixture.modelId,
                                name = "ADT QA model ${fixture.modelId.takeLast(12)}",
                                contextWindow = 4096,
                                maxTokens = 256
                            )
                        )
                    )
                )
            )
            awaitProviderProfile(web, fixture) { it.string("baseURL") == fixture.baseUrl }
            created = true

            // Native refresh must discover the newly declared route, then the native editor saves
            // the same route through its production field mapping.
            var native = awaitNativeProvider(controller, fixture.route)
            assertEquals(fixture.credentialReference, native.credentialReference)
            assertTrue("native provider form was unexpectedly read-only", native.canSave)
            val endpointField = native.fields.firstOrNull { it.key.substringAfterLast('.') == "baseURL" }
                ?: qaHarnessFail("native provider form did not expose the baseURL field")
            val modelsField = native.fields.firstOrNull { it.key.substringAfterLast('.') == "models" }
                ?: qaHarnessFail("native provider form did not expose the models field")
            controller.dispatch(NativeHarnessUiAction.EditProvider(fixture.route))
            controller.dispatch(NativeHarnessUiAction.SaveProvider(fixture.route))
            awaitNativeProvider(controller, fixture.route)
            awaitProviderProfile(web, fixture) { it.string("baseURL") == fixture.baseUrl }

            // This is a harmless route-only edit: the loopback endpoint is never contacted.
            controller.dispatch(
                NativeHarnessUiAction.UpdateProviderField(
                    providerId = fixture.route,
                    key = endpointField.key,
                    value = fixture.nativeBaseUrl
                )
            )
            native = awaitNativeProvider(controller, fixture.route) { config ->
                config.fields.firstOrNull { it.key == endpointField.key }?.value == fixture.nativeBaseUrl
            }
            assertEquals(
                "native provider field update did not change baseURL",
                fixture.nativeBaseUrl,
                native.fields.first { it.key == endpointField.key }.value
            )
            awaitProviderProfile(web, fixture) { it.string("baseURL") == fixture.nativeBaseUrl }

            // The native credential action stores the secret under the profile's exact reference;
            // only redacted metadata is read back through WebView.
            controller.dispatch(NativeHarnessUiAction.SetProviderCredential(fixture.route, fixture.secret))
            native = awaitNativeProvider(controller, fixture.route) { it.credentialConfigured }
            assertTrue("native credential action did not report configured", native.credentialConfigured)
            val credentialView = web.rpc("credentials", "describe", buildJsonObject {
                putJsonArray("refs") { add(JsonPrimitive(fixture.credentialReference)) }
            })
            assertTrue(
                "WebView did not report the synthetic credential as configured",
                credentialView.objectValue(fixture.credentialReference)?.boolean("configured").orDefault(false)
            )
            assertFalse("credential secret escaped through the redacted WebView API", credentialView.toString().contains(fixture.secret))
            assertFalse(
                "provider settings exposed the synthetic credential secret",
                (awaitProviderProfile(web, fixture) { it.string("baseURL") == fixture.nativeBaseUrl }).toString()
                    .contains(fixture.secret)
            )

            // Change a model output limit through WebView and prove the native JSON field projects
            // it. The route is never selected and no discovery or inference request is issued.
            val latestProfile = awaitProviderProfile(web, fixture) { it.string("baseURL") == fixture.nativeBaseUrl }
            val models = latestProfile["models"] as? JsonArray
                ?: qaHarnessFail("synthetic provider profile returned no models array")
            val changedModels = buildJsonArray {
                models.forEach { model ->
                    val modelObject = model as? JsonObject
                    if (modelObject?.string("id") == fixture.modelId) {
                        add(buildJsonObject {
                            modelObject.forEach { (key, value) -> put(key, value) }
                            put("maxTokens", 384)
                        })
                    } else add(model)
                }
            }
            val latestSection = findNamespace(web.rpc("settings", "describe"), "llm-pi-ai")
                ?: qaHarnessFail("llm-pi-ai namespace disappeared before model mutation")
            val latestRevision = latestSection.int("revision")
                ?: qaHarnessFail("llm-pi-ai namespace returned no revision before model mutation")
            requireWebSuccess(web.rpc("settings", "mutate", buildJsonObject {
                put("ns", "llm-pi-ai")
                putJsonArray("ops") {
                    add(setPathOperation(listOf("providers", fixture.route, "models"), changedModels))
                }
                put("expectedRevision", latestRevision)
            }), "settings/mutate synthetic model limit")
            native = awaitNativeProvider(controller, fixture.route) { config ->
                val modelsValue = config.fields.firstOrNull { it.key == modelsField.key }?.value
                modelsValue != null && runCatching {
                    (Json.parseToJsonElement(modelsValue) as? JsonArray)
                        ?.mapNotNull { it as? JsonObject }
                        ?.firstOrNull { it.string("id") == fixture.modelId }
                        ?.int("maxTokens") == 384
                }.getOrDefault(false)
            }
            val projectedModels = native.fields.firstOrNull { it.key == modelsField.key }
                ?: qaHarnessFail("native provider model field disappeared after WebView mutation")
            val projectedModel = (Json.parseToJsonElement(projectedModels.value) as? JsonArray)
                ?.mapNotNull { it as? JsonObject }
                ?.firstOrNull { it.string("id") == fixture.modelId }
                ?: qaHarnessFail("native provider model JSON omitted the synthetic model")
            assertEquals(384, projectedModel.int("maxTokens"))
        } catch (failure: Throwable) {
            primaryFailure = failure
        } finally {
            try {
                cleanupSyntheticProvider(web, controller, fixture, created, baselineProviders)
            } catch (failure: Throwable) {
                primaryFailure = combineFailures(primaryFailure, failure)
            }
        }
        primaryFailure?.let { throw it }
    }

    private data class SyntheticProviderFixture(
        val route: String,
        val displayName: String,
        val credentialReference: String,
        val modelId: String,
        val baseUrl: String,
        val nativeBaseUrl: String,
        val secret: String
    )

    private suspend fun cleanupSyntheticProvider(
        web: AuthenticatedWebSurface,
        controller: NativeHarnessController,
        fixture: SyntheticProviderFixture,
        created: Boolean,
        baselineProviders: JsonObject
    ) {
        if (!created) return
        var cleanupFailure: Throwable? = null
        suspend fun recordCleanup(action: suspend () -> Unit) {
            try {
                action()
            } catch (failure: Throwable) {
                cleanupFailure = combineFailures(cleanupFailure, failure)
            }
        }
        recordCleanup {
            if (controller.state.value.provider.configs.any { it.id == fixture.route }) {
                controller.dispatch(NativeHarnessUiAction.UnsetProviderCredential(fixture.route))
                awaitCondition("native credential cleanup did not complete") {
                    controller.state.value.provider.configs
                        .firstOrNull { it.id == fixture.route }
                        ?.credentialConfigured == false
                }
            }
        }
        // Keep cleanup exact even if the native view was lost during a failed action.
        recordCleanup {
            web.rpc("credentials", "unset", buildJsonObject { put("ref", fixture.credentialReference) })
        }
        recordCleanup {
            if (controller.state.value.provider.configs.any { it.id == fixture.route }) {
                controller.dispatch(NativeHarnessUiAction.DeleteProvider(fixture.route))
                awaitNoNativeProvider(controller, fixture.route)
            }
        }
        recordCleanup {
            val section = findNamespace(web.rpc("settings", "describe"), "llm-pi-ai")
            val profile = section?.objectValue("value")?.objectValue("providers")?.get(fixture.route)
            if (profile != null) {
                val revision = section.int("revision")
                    ?: qaHarnessFail("llm-pi-ai namespace returned no revision during provider cleanup")
                requireWebSuccess(web.rpc("settings", "mutate", buildJsonObject {
                    put("ns", "llm-pi-ai")
                    putJsonArray("ops") {
                        add(unsetPathOperation(listOf("providers", fixture.route)))
                    }
                    put("expectedRevision", revision)
                }), "settings/unset synthetic provider")
            }
            awaitNoProviderProfile(web, fixture.route)
            val credentialAfter = web.rpc("credentials", "describe", buildJsonObject {
                putJsonArray("refs") { add(JsonPrimitive(fixture.credentialReference)) }
            })
            assertFalse(
                "synthetic provider credential remained configured after cleanup",
                credentialAfter.objectValue(fixture.credentialReference)?.boolean("configured").orDefault(false)
            )
            val after = findNamespace(web.rpc("settings", "describe"), "llm-pi-ai")
                ?.objectValue("value")?.objectValue("providers") ?: buildJsonObject {}
            baselineProviders.forEach { (providerId, expected) ->
                assertEquals(
                    "provider $providerId changed while cleaning the synthetic route",
                    expected,
                    after[providerId]
                )
            }
        }
        cleanupFailure?.let { throw it }
    }

    private suspend fun awaitNativeProvider(
        controller: NativeHarnessController,
        providerId: String,
        predicate: (HarnessProviderConfigUi) -> Boolean = { true }
    ): HarnessProviderConfigUi {
        var provider: HarnessProviderConfigUi? = null
        withTimeout(90_000L) {
            while (provider == null) {
                controller.refreshAfterWebView()
                provider = controller.state.value.provider.configs
                    .firstOrNull { it.id == providerId && predicate(it) }
                if (provider == null) delay(200L)
            }
        }
        return requireNotNull(provider) { "native controller did not expose provider $providerId" }
    }

    private suspend fun awaitNoNativeProvider(controller: NativeHarnessController, providerId: String) {
        withTimeout(90_000L) {
            while (true) {
                controller.refreshAfterWebView()
                if (controller.state.value.provider.configs.none { it.id == providerId }) return@withTimeout
                delay(200L)
            }
        }
    }

    private suspend fun awaitProviderProfile(
        web: AuthenticatedWebSurface,
        fixture: SyntheticProviderFixture,
        predicate: (JsonObject) -> Boolean
    ): JsonObject {
        var profile: JsonObject? = null
        withTimeout(90_000L) {
            while (profile == null) {
                val section = findNamespace(web.rpc("settings", "describe"), "llm-pi-ai")
                val candidate = section?.objectValue("value")?.objectValue("providers")
                    ?.objectValue(fixture.route)
                if (candidate != null && predicate(candidate)) profile = candidate else delay(200L)
            }
        }
        return requireNotNull(profile) { "WebView did not expose provider ${fixture.route}" }
    }

    private suspend fun awaitNoProviderProfile(web: AuthenticatedWebSurface, route: String) {
        withTimeout(90_000L) {
            while (true) {
                val section = findNamespace(web.rpc("settings", "describe"), "llm-pi-ai")
                val profile = section?.objectValue("value")?.objectValue("providers")?.get(route)
                if (profile == null) return@withTimeout
                delay(200L)
            }
        }
    }

    private data class SyntheticPluginFixture(
        val bundleName: String,
        val rowId: String,
        val relativeDir: String,
        val spec: String
    )

    private fun newSyntheticPluginFixture(guestPath: String, seed: String): SyntheticPluginFixture {
        val suffix = UUID.randomUUID().toString().replace("-", "").take(12)
        val bundleName = "@fixture/adt-native-web-$suffix"
        val relativeDir = "qa/$seed/plugin-$suffix"
        return SyntheticPluginFixture(
            bundleName = bundleName,
            rowId = "adt-qa-fixture-$suffix",
            relativeDir = relativeDir,
            spec = "file:$guestPath/$relativeDir"
        )
    }

    /** Creates a package-manager fixture inside the captured LOCAL_PROOT workspace. */
    private suspend fun createSyntheticPluginFixture(
        runtime: HarnessAppRuntime,
        scope: HarnessWorkspaceScope,
        fixture: SyntheticPluginFixture
    ) {
        val parent = fixture.relativeDir.substringBeforeLast('/')
        for (directory in listOf(parent, fixture.relativeDir)) {
            runtime.files.invoke(scope, "workspace.mkdir", JSONObject().put("path", directory))
        }
        val packageJson = """
            {
              "name": "${fixture.bundleName}",
              "version": "0.0.1",
              "private": true,
              "type": "module",
              "main": "index.js",
              "description": "Android Harness consistency fixture",
              "dsh": { "bundle": { "patch": "./cordis.patch.yml" } }
            }
        """.trimIndent()
        val patch = """
            - insert:
                - id: ${fixture.rowId}
                  name: '${fixture.bundleName}'
        """.trimIndent()
        // The small schema is deliberately self-contained: it avoids adding a dependency to the
        // fixture package while still exercising the official settings.describe/mutate contract.
        val index = """
            const schema = (input) => {
              const source = input && typeof input === 'object' ? input : {}
              return { enabled: source.enabled === true }
            }
            schema.type = 'object'
            schema.meta = {}
            schema.dict = { enabled: { type: 'boolean', meta: {} } }
            schema.toJSON = () => ({
              type: 'object',
              properties: { enabled: { type: 'boolean', title: 'QA fixture enabled' } },
            })

            export function apply(ctx) {
              const settings = ctx.get('settings')
              if (settings !== undefined) settings.register('qa-native-web', schema)
            }
        """.trimIndent()
        val files = mapOf(
            "package.json" to packageJson,
            "cordis.patch.yml" to patch,
            "index.js" to index
        )
        for ((name, content) in files) {
            runtime.files.writeBytes(
                scope,
                "${fixture.relativeDir}/$name",
                content.toByteArray(Charsets.UTF_8)
            )
        }
    }

    private suspend fun exerciseSyntheticPluginLifecycle(
        web: AuthenticatedWebSurface,
        controller: NativeHarnessController,
        fixture: SyntheticPluginFixture
    ) {
        controller.dispatch(NativeHarnessUiAction.InspectPlugin(fixture.spec))
        awaitCondition("native plugin inspection did not accept the local fixture") {
            controller.state.value.extensions.pluginInspection?.let { inspection ->
                inspection.spec == fixture.spec && inspection.accepted && inspection.isBundle == true
            } == true
        }

        controller.dispatch(NativeHarnessUiAction.InstallPlugin(fixture.spec))
        awaitCondition("native plugin installation did not complete") {
            val install = controller.state.value.extensions.pluginInstall
            if (install.packageSpec == fixture.spec && install.phase in setOf("failed", "restart-required")) {
                qaHarnessFail("fixture installation ended in ${install.phase} (${install.errorCode})")
            }
            install.packageSpec == fixture.spec && install.phase == "done"
        }
        awaitBundleRow(web, fixture.bundleName) { row ->
            row.boolean("installed").orDefault(false) && !row.boolean("enabled").orDefault(false)
        }

        controller.dispatch(NativeHarnessUiAction.SetPluginBundleEnabled(fixture.bundleName, true))
        awaitBundleRow(web, fixture.bundleName) { row -> row.boolean("enabled").orDefault(false) }
        val enabledPlugin = awaitNativePlugin(controller, fixture.bundleName) { it.enabled }
        val enabledRow = enabledPlugin.rows.firstOrNull { it.rowId == fixture.rowId }
            ?: qaHarnessFail("native plugin bundle omitted fixture row ${fixture.rowId}")
        val entryId = enabledRow.entryId
            ?: qaHarnessFail("enabled fixture row did not expose a live entry id")
        awaitWebPluginEntry(web, entryId) { row -> row.boolean("enabled").orDefault(false) }

        val initialSettings = awaitSettingsNamespace(web, "qa-native-web") { true }
        val initialRevision = initialSettings.int("revision")
            ?: qaHarnessFail("fixture settings namespace returned no revision")
        requireWebSuccess(web.rpc("settings", "mutate", buildJsonObject {
            put("ns", "qa-native-web")
            putJsonArray("ops") { add(setOperation("enabled", JsonPrimitive(true))) }
            put("expectedRevision", initialRevision)
        }), "settings/mutate qa-native-web")
        awaitSettingsNamespace(web, "qa-native-web") { section ->
            section.objectValue("user")?.boolean("enabled").orDefault(false)
        }
        withTimeout(45_000L) { controller.refreshAfterWebView() }
        awaitCondition("native settings did not project the fixture configuration") {
            controller.state.value.schemaSections
                .firstOrNull { it.title == "qa-native-web" }
                ?.fields
                ?.any { it.key == "qa-native-web.enabled" && it.value == "true" } == true
        }

        controller.dispatch(NativeHarnessUiAction.SetPluginEnabled(entryId, false))
        awaitWebPluginEntry(web, entryId) { row -> !row.boolean("enabled").orDefault(false) }
        awaitNoSettingsNamespace(web, "qa-native-web")

        controller.dispatch(NativeHarnessUiAction.SetPluginEnabled(entryId, true))
        awaitWebPluginEntry(web, entryId) { row -> row.boolean("enabled").orDefault(false) }
        val reloadedSettings = awaitSettingsNamespace(web, "qa-native-web") { section ->
            section.objectValue("user")?.boolean("enabled").orDefault(false)
        }
        assertTrue(
            "fixture setting was not retained across the native plugin row toggle",
            reloadedSettings.objectValue("user")?.boolean("enabled").orDefault(false)
        )
        withTimeout(45_000L) { controller.refreshAfterWebView() }
        awaitCondition("native settings did not recover after the fixture row re-enabled") {
            controller.state.value.schemaSections
                .firstOrNull { it.title == "qa-native-web" }
                ?.fields
                ?.any { it.key == "qa-native-web.enabled" && it.value == "true" } == true
        }

        val latestRevision = reloadedSettings.int("revision")
            ?: qaHarnessFail("reloaded fixture settings returned no revision")
        requireWebSuccess(web.rpc("settings", "mutate", buildJsonObject {
            put("ns", "qa-native-web")
            putJsonArray("ops") { add(unsetOperation("enabled")) }
            put("expectedRevision", latestRevision)
        }), "settings/reset qa-native-web")
        awaitSettingsNamespace(web, "qa-native-web") { section ->
            section.objectValue("user")?.containsKey("enabled") != true
        }
        withTimeout(45_000L) { controller.refreshAfterWebView() }
        awaitCondition("native settings reset did not clear the fixture override") {
            controller.state.value.schemaSections
                .firstOrNull { it.title == "qa-native-web" }
                ?.fields
                ?.any { it.key == "qa-native-web.enabled" && it.value == "false" } == true
        }

        controller.dispatch(NativeHarnessUiAction.SetPluginBundleEnabled(fixture.bundleName, false))
        awaitBundleRow(web, fixture.bundleName) { row -> !row.boolean("enabled").orDefault(false) }
        awaitNoSettingsNamespace(web, "qa-native-web")
        controller.dispatch(NativeHarnessUiAction.UninstallPlugin(fixture.bundleName))
        awaitNoBundle(web, fixture.bundleName)
    }

    private suspend fun awaitBundleRow(
        web: AuthenticatedWebSurface,
        bundleName: String,
        predicate: (JsonObject) -> Boolean
    ): JsonObject {
        return withTimeout(90_000L) {
            var row: JsonObject? = web.rpc("pluginManager", "listBundles").bundleRow(bundleName)
            while (row?.let(predicate) != true) {
                delay(200L)
                row = web.rpc("pluginManager", "listBundles").bundleRow(bundleName)
            }
            requireNotNull(row) { "WebView did not expose bundle $bundleName" }
        }
    }

    private suspend fun awaitNoBundle(web: AuthenticatedWebSurface, bundleName: String) {
        withTimeout(90_000L) {
            while (web.rpc("pluginManager", "listBundles").bundleRow(bundleName) != null) delay(200L)
        }
    }

    private suspend fun awaitWebPluginEntry(
        web: AuthenticatedWebSurface,
        entryId: String,
        predicate: (JsonObject) -> Boolean
    ): JsonObject {
        var found: JsonObject? = null
        withTimeout(90_000L) {
            while (true) {
                val row = web.rpc("pluginManager", "listPlugins").rows()
                    .firstOrNull { it.string("entryId") == entryId }
                if (row != null && predicate(row)) {
                    found = row
                    break
                }
                delay(200L)
            }
        }
        return requireNotNull(found) { "WebView did not observe plugin entry $entryId" }
    }

    private suspend fun awaitSettingsNamespace(
        web: AuthenticatedWebSurface,
        namespace: String,
        predicate: (JsonObject) -> Boolean
    ): JsonObject {
        return withTimeout(90_000L) {
            var section: JsonObject? = findNamespace(web.rpc("settings", "describe"), namespace)
            while (section?.let(predicate) != true) {
                delay(200L)
                section = findNamespace(web.rpc("settings", "describe"), namespace)
            }
            requireNotNull(section) { "WebView did not expose settings namespace $namespace" }
        }
    }

    private suspend fun awaitNoSettingsNamespace(web: AuthenticatedWebSurface, namespace: String) {
        withTimeout(90_000L) {
            while (findNamespace(web.rpc("settings", "describe"), namespace) != null) delay(200L)
        }
    }

    private suspend fun awaitNativePlugin(
        controller: NativeHarnessController,
        bundleName: String,
        predicate: (HarnessPluginUi) -> Boolean
    ): HarnessPluginUi {
        var found: HarnessPluginUi? = null
        withTimeout(90_000L) {
            while (true) {
                val candidate = controller.state.value.extensions.plugins
                    .firstOrNull { it.bundleName == bundleName }
                if (candidate != null && predicate(candidate)) {
                    found = candidate
                    break
                }
                delay(200L)
            }
        }
        return requireNotNull(found) { "Native controller did not observe plugin bundle $bundleName" }
    }

    private fun JsonElement.bundleRow(bundleName: String): JsonObject? =
        rows().firstOrNull { it.string("name") == bundleName }

    private suspend fun cleanupSyntheticPlugin(
        runtime: HarnessAppRuntime,
        web: AuthenticatedWebSurface?,
        controller: NativeHarnessController?,
        scope: HarnessWorkspaceScope?,
        fixture: SyntheticPluginFixture?
    ) {
        val ownedFixture = fixture ?: return
        val existing = if (web != null) {
            try {
                web.rpc("pluginManager", "listBundles").bundleRow(ownedFixture.bundleName)
            } catch (_: Throwable) {
                null
            }
        } else {
            val client = runtime.client
            if (client == null) {
                null
            } else {
                try {
                    requireSuccess(client.listBundles(), "pluginManager/listBundles cleanup")
                        .bundleRow(ownedFixture.bundleName)
                } catch (_: Throwable) {
                    null
                }
            }
        }
        if (existing != null) {
            if (controller != null && web != null) {
                controller.dispatch(NativeHarnessUiAction.UninstallPlugin(ownedFixture.bundleName))
                awaitNoBundle(web, ownedFixture.bundleName)
            } else {
                val client = runtime.client
                if (client != null) requireSuccess(
                    client.removeBundle(ownedFixture.bundleName),
                    "pluginManager/removeBundle cleanup"
                )
            }
        }
        if (scope != null) {
            for (name in listOf("package.json", "cordis.patch.yml", "index.js")) {
                deleteFixturePathIfPresent(runtime, scope, "${ownedFixture.relativeDir}/$name")
            }
            deleteFixturePathIfPresent(runtime, scope, ownedFixture.relativeDir)
            deleteFixturePathIfPresent(runtime, scope, ownedFixture.relativeDir.substringBeforeLast('/'))
        }
    }

    private suspend fun deleteFixturePathIfPresent(
        runtime: HarnessAppRuntime,
        scope: HarnessWorkspaceScope,
        path: String
    ) {
        val stat = try {
            runtime.files.invoke(scope, "workspace.stat", JSONObject().put("path", path)) as JSONObject
        } catch (_: Throwable) {
            null
        }
        if (stat?.optBoolean("exists") == true || stat?.optBoolean("directory") == true) {
            runtime.files.invoke(scope, "workspace.delete", JSONObject().put("path", path))
        }
    }

    private suspend fun cleanupOwnedHarnessData(
        runtime: HarnessAppRuntime,
        workspace: HarnessWorkspaceEntity?,
        sessionId: String?,
        markerPath: String?
    ) {
        val ownedWorkspace = workspace ?: return
        val ownedSession = sessionId ?: return
        val dao = runtime.database.harnessDao()
        val mapping = dao.session(ownedSession)
        if (mapping?.workspaceId != ownedWorkspace.id) return
        val mappedWorkspace = dao.workspace(mapping.workspaceId)
        if (mappedWorkspace?.projectFolder != ownedWorkspace.projectFolder ||
            mappedWorkspace.guestPath != ownedWorkspace.guestPath
        ) return

        runtime.client?.call(
            "workspace",
            "archiveSession",
            buildJsonObject { putJsonObject("request") { put("sessionId", ownedSession) } },
            HarnessCallPolicy.NoRetry
        )
        if (markerPath != null) {
            val scope = runtime.workspaces.scope(ownedSession)
            val stat = runtime.files.invoke(scope, "workspace.stat", JSONObject().put("path", markerPath)) as JSONObject
            if (stat.optBoolean("exists") && !stat.optBoolean("symbolicLink")) {
                runtime.files.invoke(scope, "workspace.delete", JSONObject().put("path", markerPath))
            }
        }
        val activeClient = runtime.client ?: return
        val dshWorkspace = findOwnedDshWorkspace(activeClient, ownedWorkspace.guestPath, ownedSession)
        if (dshWorkspace != null) {
            activeClient.call(
                "workspace",
                "delete",
                buildJsonObject { putJsonObject("request") { put("workspaceId", dshWorkspace) } },
                HarnessCallPolicy.NoRetry
            )
        }
        // Alpha2 has no session/delete RPC and the app DAO intentionally retains the mapping for
        // readable history. The test archives that owned session and removes only its marker.
        android.util.Log.i(TAG, "Synthetic session retained as archived history; no upstream session/delete RPC exists")
    }

    private suspend fun findOwnedDshWorkspace(
        client: HarnessClient,
        guestPath: String,
        sessionId: String
    ): String? = withTimeoutOrNull(10_000L) {
        try {
            client.stream("workspace", "follow").first { it.string("type") == "baseline" }
                .objectValue("value")?.objectArray("items")
                ?.firstOrNull { row ->
                    row.string("path") == guestPath && row.stringArray("sessionIds").contains(sessionId)
                }
                ?.string("workspaceId")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            null
        }
    }

    private data class SafeSetting(
        val namespace: String,
        val revision: Int,
        val key: String,
        val original: JsonElement,
        val wasOverridden: Boolean
    )

    /** `ui-onboarding.welcomeNoticeVersion` is a pinned bundled scalar with no runtime side effect. */
    private fun findKnownSafeSetting(describe: JsonElement): SafeSetting? {
        val namespace = findNamespace(describe, "ui-onboarding") ?: return null
        val revision = namespace.int("revision") ?: return null
        val user = namespace.objectValue("user") ?: buildJsonObject {}
        val value = namespace.objectValue("value") ?: buildJsonObject {}
        val key = "welcomeNoticeVersion"
        return SafeSetting(
            namespace = "ui-onboarding",
            revision = revision,
            key = key,
            original = user[key] ?: value[key] ?: JsonPrimitive(""),
            wasOverridden = user.containsKey(key)
        )
    }

    private fun findNamespace(describe: JsonElement, namespace: String): JsonObject? =
        describe.objectArray("namespaces").firstOrNull { it.string("ns") == namespace }

    private fun setOperation(key: String, value: JsonElement): JsonObject = buildJsonObject {
        put("op", "set")
        put("path", buildJsonArray { add(JsonPrimitive(key)) })
        put("value", value)
    }

    private fun setPathOperation(path: List<String>, value: JsonElement): JsonObject = buildJsonObject {
        put("op", "set")
        put("path", buildJsonArray { path.forEach { add(JsonPrimitive(it)) } })
        put("value", value)
    }

    private fun unsetOperation(key: String): JsonObject = buildJsonObject {
        put("op", "unset")
        put("path", buildJsonArray { add(JsonPrimitive(key)) })
    }

    private fun unsetPathOperation(path: List<String>): JsonObject = buildJsonObject {
        put("op", "unset")
        put("path", buildJsonArray { path.forEach { add(JsonPrimitive(it)) } })
    }

    private fun JsonElement.rows(): List<JsonObject> = when (this) {
        is JsonArray -> mapNotNull { it as? JsonObject }
        is JsonObject -> objectArray("items").ifEmpty {
            objectArray("plugins").ifEmpty { objectArray("bundles").ifEmpty { objectArray("entries") } }
        }
        else -> emptyList()
    }

    private fun requireSuccess(result: HarnessRpcResult, operation: String): JsonElement = when (result) {
        is HarnessRpcResult.Success -> result.value
        is HarnessRpcResult.Failure -> qaHarnessFail("$operation failed: ${result.error.code}: ${result.error.message}")
    }

    private fun requireWebSuccess(value: JsonElement, operation: String): JsonElement {
        assertTrue("$operation returned null", value !is JsonNull)
        return value
    }

    private fun combineFailures(primary: Throwable?, secondary: Throwable): Throwable {
        if (primary == null) return secondary
        primary.addSuppressed(secondary)
        return primary
    }

    private fun HarnessAppRuntime.toHarnessRuntimeUiState(): HarnessRuntimeUiState {
        val state = status.value?.state
        val uiStatus = when (state) {
            "STARTING" -> HarnessRuntimeStatus.STARTING
            "RUNNING" -> HarnessRuntimeStatus.RUNNING
            "STOP_REQUESTED" -> HarnessRuntimeStatus.STOPPING
            "FORCE_STOPPING" -> HarnessRuntimeStatus.STOPPING
            "FAILED" -> HarnessRuntimeStatus.ERROR
            "INTERRUPTED" -> HarnessRuntimeStatus.INTERRUPTED
            else -> HarnessRuntimeStatus.STOPPED
        }
        return HarnessRuntimeUiState(
            status = uiStatus,
            detail = errorCode.value,
            endpointLabel = endpoint.value?.origin,
            canStart = uiStatus == HarnessRuntimeStatus.STOPPED || uiStatus == HarnessRuntimeStatus.ERROR,
            canStop = uiStatus == HarnessRuntimeStatus.RUNNING,
            canForceStop = uiStatus in setOf(HarnessRuntimeStatus.STARTING, HarnessRuntimeStatus.RUNNING, HarnessRuntimeStatus.STOPPING)
        )
    }

    private class AuthenticatedWebSurface(
        private val instrumentation: Instrumentation,
        private val endpoint: HarnessEndpoint
    ) {
        private val viewRef = AtomicReference<WebView?>()
        private val pageFailure = AtomicReference<String?>()
        private val pageReady = CountDownLatch(1)

        fun attach(scenario: ActivityScenario<ComponentActivity>) {
            val cookieInstalled = CountDownLatch(1)
            val cookieOk = AtomicReference(false)
            scenario.onActivity { activity ->
                val view = WebView(activity).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView, url: String) {
                            pageReady.countDown()
                        }

                        override fun onReceivedError(
                            view: WebView,
                            request: WebResourceRequest,
                            error: WebResourceError
                        ) {
                            if (request.isForMainFrame) {
                                pageFailure.set("${error.errorCode}:${error.description}")
                                pageReady.countDown()
                            }
                        }

                        override fun onReceivedHttpError(
                            view: WebView,
                            request: WebResourceRequest,
                            response: WebResourceResponse
                        ) {
                            if (request.isForMainFrame && response.statusCode >= 400) {
                                pageFailure.set("HTTP ${response.statusCode}")
                                pageReady.countDown()
                            }
                        }
                    }
                }
                viewRef.set(view)
                activity.setContentView(view)
                val cookies = CookieManager.getInstance()
                cookies.setCookie(endpoint.origin, endpoint.cookieHeader + "; Path=/; HttpOnly; SameSite=Strict") { ok ->
                    cookieOk.set(ok)
                    cookieInstalled.countDown()
                    if (ok) {
                        cookies.flush()
                        view.loadUrl(endpoint.origin + "/")
                    } else pageReady.countDown()
                }
            }
            assertTrue("WebView cookie installation timed out", cookieInstalled.await(5, TimeUnit.SECONDS))
            assertTrue("WebView cookie installation failed", cookieOk.get())
            assertTrue("Authenticated WebView did not finish loading", pageReady.await(30, TimeUnit.SECONDS))
            assertEquals(null, pageFailure.get())
        }

        suspend fun rpc(namespace: String, method: String, args: JsonObject = buildJsonObject {}): JsonElement {
            val rpcId = UUID.randomUUID().toString()
            val global = "__adtQaRpc_${rpcId.replace("-", "")}"
            val envelope = buildJsonObject {
                put("type", "client-request")
                put("rpcId", rpcId)
                put("method", "$namespace/$method")
                putJsonObject("payload") { put("args", args) }
            }
            val key = JSONObject.quote(global)
            val path = JSONObject.quote("/api/$namespace/$method")
            val body = JSONObject.quote(envelope.toString())
            evaluate(
                """
                (function() {
                  window[$key] = null;
                  fetch($path, {
                    method: 'POST',
                    credentials: 'include',
                    headers: {'Content-Type': 'application/json', 'Accept': 'application/json'},
                    body: $body
                  }).then(async function(response) {
                    window[$key] = JSON.stringify({status: response.status, body: await response.text()});
                  }).catch(function(error) {
                    window[$key] = JSON.stringify({status: 0, body: String(error)});
                  });
                })();
                """.trimIndent()
            )
            val encoded = withTimeout(30_000L) {
                var completed: String? = null
                while (completed == null) {
                    // The stored value is already JSON text. Android's evaluateJavascript
                    // callback adds the one required JSON string layer; stringifying it again
                    // would leave a JsonPrimitive after the first Kotlin decode.
                    val current = evaluate("window[$key] === null ? null : window[$key]")
                    if (current != "null") {
                        completed = Json.parseToJsonElement(current).jsonPrimitive.content
                    } else {
                        delay(50L)
                    }
                }
                requireNotNull(completed)
            }
            evaluate("delete window[$key]")
            val transport = Json.parseToJsonElement(encoded).jsonObject
            assertEquals("WebView RPC HTTP status for $namespace/$method", 200, transport["status"]?.jsonPrimitive?.intOrNull)
            val response = Json.parseToJsonElement(transport["body"]?.jsonPrimitive?.content.orEmpty()).jsonObject
            assertEquals("server-response", response.string("type"))
            assertEquals(rpcId, response.string("rpcId"))
            val result = response.objectValue("result")
                ?: qaHarnessFail("WebView RPC $namespace/$method had no result")
            assertEquals("WebView RPC $namespace/$method failed", true, result.boolean("ok"))
            return result["value"] ?: JsonNull
        }

        fun close() {
            val view = viewRef.getAndSet(null) ?: return
            instrumentation.runOnMainSync {
                view.stopLoading()
                view.loadUrl("about:blank")
                view.destroy()
            }
        }

        private fun evaluate(script: String): String {
            val latch = CountDownLatch(1)
            val result = AtomicReference<String?>(null)
            instrumentation.runOnMainSync {
                viewRef.get()?.evaluateJavascript(script) {
                    result.set(it)
                    latch.countDown()
                } ?: latch.countDown()
            }
            assertTrue("WebView JavaScript evaluation timed out", latch.await(15, TimeUnit.SECONDS))
            return result.get() ?: "null"
        }
    }

    private companion object {
        const val TAG = "HarnessNativeWebConsistencyQa"
    }
}
