package com.example.llamadroid.ui.agent.harness

import android.os.Build
import androidx.activity.ComponentActivity
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.llamadroid.BuildConfig
import com.example.llamadroid.harness.HarnessAppRuntime
import com.example.llamadroid.harness.client.HarnessRpcResult
import com.example.llamadroid.harness.client.HarnessClient
import com.example.llamadroid.harness.runtime.HarnessRuntimeState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Opt-in positive proof for one complete turn boundary. A synthetic loopback
 * OpenAI-compatible route asks the official `ask_user_question` tool to pause;
 * the native Remote Event adapter and the authenticated original Web UI must
 * both render that same pending request before the native session is cancelled.
 */
@RunWith(AndroidJUnit4::class)
class HarnessNativeWebInteractionQaTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test
    fun qaPinnedTurnQuestionIsNativeAndWebMirroredThenCancelled(): Unit = runBlocking {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue("Use the x86_64 Harness QA carrier", BuildConfig.HARNESS_QA_X86)
        assumeTrue("Use an x86_64 emulator", Build.SUPPORTED_ABIS.firstOrNull() == "x86_64")
        assumeTrue(
            "Pass harness_native_web_interaction_qa=true on the isolated QA carrier",
            arguments.getString("harness_native_web_interaction_qa") == "true"
        )

        var scenario: ActivityScenario<ComponentActivity>? = null
        val runtime = HarnessAppRuntime.get(context)
        val alreadyRunning = runtime.status.value?.state == HarnessRuntimeState.RUNNING.name && runtime.client != null
        val ownsRuntime = !alreadyRunning
        var provider: HarnessQaLoopbackQuestionProvider? = null
        var client: HarnessClient? = null
        var controller: NativeHarnessController? = null
        var web: HarnessQaAuthenticatedWebSurface? = null
        var followJob: Job? = null
        var sessionId: String? = null
        var providerRoute: String? = null
        var providerInstalled = false
        var primaryFailure: Throwable? = null
        try {
            // Keep an instrumentation activity in the foreground before starting the app-owned
            // runtime. Android can reject foreground-service startup from a background test process.
            scenario = ActivityScenario.launch(ComponentActivity::class.java)
            if (ownsRuntime) startAndAwait(runtime)
            val activeClient = requireNotNull(runtime.client) { "Harness runtime has no authenticated client" }
            client = activeClient
            val endpoint = requireNotNull(runtime.endpoint.value) { "Harness runtime has no endpoint" }

            val suffix = UUID.randomUUID().toString().replace("-", "").take(12)
            val marker = "ADT_QA_INTERACTION_$suffix"
            val option = "ADT QA option $suffix"
            val preQuestionText = "ADT QA streamed pre-question $suffix"
            val finalText = "ADT QA synthetic final response $suffix"
            val route = "adt-qa-interaction-$suffix"
            val model = "adt-qa-interaction-model-$suffix"
            providerRoute = route
            val loopbackProvider = HarnessQaLoopbackQuestionProvider(
                marker,
                option,
                preQuestionText,
                finalText
            )
            provider = loopbackProvider
            val profile = NativeHarnessCustomProviderRequest(
                route = route,
                displayName = "ADT interaction QA $suffix",
                api = "openai-completions",
                baseUrl = loopbackProvider.baseUrl,
                models = listOf(
                    NativeHarnessCustomProviderModel(
                        id = model,
                        name = "ADT interaction QA model $suffix",
                        contextWindow = 8_192,
                        maxTokens = 256,
                        inputModalities = listOf("text")
                    )
                )
            )
            installSyntheticProvider(activeClient, profile)
            providerInstalled = true
            awaitModel(activeClient, route, model)

            val created = requireSuccess(
                activeClient.createSession(buildJsonObject { put("cwd", "/workspace/projects/default_project") }),
                "session/create"
            )
            val createdSessionId = created.string("sessionId")
                ?: error("session/create returned no sessionId")
            sessionId = createdSessionId
            val title = "ADT interaction QA $suffix"
            requireSuccess(
                activeClient.call(
                    "session",
                    "rename",
                    buildJsonObject {
                        putJsonObject("request") {
                            put("sessionId", createdSessionId)
                            put("title", title)
                        }
                    }
                ),
                "session/rename"
            )

            val native = NativeHarnessController(
                parentScope = runtime.scope,
                clientProvider = { runtime.client },
                runtime = NativeHarnessRuntimeCallbacks(
                    current = { runtimeUiState(runtime) },
                    start = { runtimeUiState(runtime) },
                    stop = { runtimeUiState(runtime) },
                    forceStop = { runtimeUiState(runtime) }
                )
            )
            controller = native
            awaitCondition("native controller did not list the synthetic session") {
                native.state.value.sessions.any { it.id == createdSessionId }
            }
            native.dispatch(NativeHarnessUiAction.SelectSession(createdSessionId))
            awaitCondition("native controller did not select the synthetic session") {
                native.state.value.selectedSessionId == createdSessionId
            }

            val authenticatedWeb = HarnessQaAuthenticatedWebSurface(instrumentation, endpoint)
            web = authenticatedWeb
            authenticatedWeb.attach(requireNotNull(scenario))
            // The original Web UI restores its own selection; click the uniquely titled row so
            // its session-scoped pending interaction composer is the same session as native.
            authenticatedWeb.clickText(title)

            val frames = CopyOnWriteArrayList<JsonElement>()
            followJob = launch(Dispatchers.IO) {
                activeClient.followSession(
                    buildJsonObject {
                        put("address", buildJsonObject {
                            put("kind", "session")
                            put("sessionId", createdSessionId)
                        })
                        put("maxMessages", 120)
                        put("assistantStream", true)
                    }
                ).collect { frame -> frames += frame }
            }
            awaitCondition("session/follow did not establish a baseline") { frames.isNotEmpty() }

            requireSuccess(
                activeClient.selectSessionModel(buildJsonObject {
                    put("sessionId", createdSessionId)
                    put("provider", route)
                    put("model", model)
                }),
                "session/selectModel"
            )
            requireSuccess(
                activeClient.promptSession(buildJsonObject {
                    put("requestId", "adt-qa-prompt-$suffix")
                    put("sessionId", createdSessionId)
                    put("mode", "queue")
                    putJsonArray("content") {
                        add(buildJsonObject {
                            put("type", "text")
                            put("text", "Emit the synthetic ADT QA question now.")
                        })
                    }
                    put("clientTimeZone", "UTC")
                }),
                "session/prompt"
            )

            val providerRequest = requireNotNull(provider).awaitChatRequest()
            assertTrue("Provider request did not contain the official question tool schema", providerRequest.contains("ask_user_question"))
            awaitCondition("native controller did not receive the pending question") {
                native.state.value.questions.any { it.prompt.contains(marker) }
            }
            val nativeQuestion = native.state.value.questions.first { it.prompt.contains(marker) }
            assertEquals("ADT QA", nativeQuestion.title)
            assertEquals("Synthetic question option count changed", 1, nativeQuestion.options.size)
            assertTrue("Native question lost its option", option in nativeQuestion.options)
            awaitCondition("native transcript missed visible streamed text before the question") {
                native.state.value.transcript.any { it.text.contains(preQuestionText) }
            }
            assertTrue(
                "session/follow missed visible streamed text before the question",
                frames.any { it.toString().contains(preQuestionText) }
            )

            val webQuestion = requireNotNull(authenticatedWeb.awaitQuestion(marker, present = true))
            assertTrue("Authenticated Web UI did not render the pending question", webQuestion.contains(marker))
            awaitCondition("session/follow did not expose the streamed tool call") {
                frames.any { frame -> frame.toString().contains("ask_user_question") || frame.toString().contains(marker) }
            }

            native.dispatch(
                NativeHarnessUiAction.AnswerQuestion(
                    questionId = nativeQuestion.id,
                    answer = option,
                    selected = listOf(option)
                )
            )
            awaitCondition("native question remained after native answer") {
                native.state.value.questions.none { it.prompt.contains(marker) }
            }
            val afterAnswer = authenticatedWeb.awaitQuestion(marker, present = false)
            assertFalse("Web UI retained the answered question", afterAnswer.orEmpty().contains(marker))
            requireNotNull(provider).awaitRequestCount(2)
            awaitCondition("native transcript missed the synthetic streamed response") {
                native.state.value.transcript.any { it.text.contains(finalText) }
            }
            awaitCondition("session/follow missed the synthetic streamed response") {
                frames.any { it.toString().contains(finalText) }
            }

            requireSuccess(
                activeClient.promptSession(buildJsonObject {
                    put("requestId", "adt-qa-prompt-second-$suffix")
                    put("sessionId", createdSessionId)
                    put("mode", "queue")
                    putJsonArray("content") {
                        add(buildJsonObject {
                            put("type", "text")
                            put("text", "Emit the second synthetic ADT QA question now.")
                        })
                    }
                    put("clientTimeZone", "UTC")
                }),
                "session/prompt second"
            )
            requireNotNull(provider).awaitRequestCount(3)
            awaitCondition("native controller did not receive the second pending question") {
                native.state.value.questions.any { it.prompt.contains(marker) }
            }
            val secondQuestion = native.state.value.questions.first { it.prompt.contains(marker) }
            native.dispatch(NativeHarnessUiAction.CancelTurn)
            awaitCondition("native question remained after native turn cancellation") {
                native.state.value.questions.none { it.prompt.contains(marker) }
            }
            val afterCancel = authenticatedWeb.awaitQuestion(marker, present = false)
            assertFalse("Web UI retained the cancelled question", afterCancel.orEmpty().contains(marker))
            awaitCondition("native cancel did not clear the active turn") {
                !native.state.value.canCancelTurn
            }
            assertTrue("Second question had no selectable option", option in secondQuestion.options)
        } catch (failure: Throwable) {
            primaryFailure = failure
        } finally {
            withContext(NonCancellable) {
                suspend fun recordCleanup(action: suspend () -> Unit) {
                    try {
                        action()
                    } catch (failure: Throwable) {
                        primaryFailure = combine(primaryFailure, failure)
                    }
                }
                recordCleanup { withTimeout(10_000L) { followJob?.cancelAndJoin() } }
                recordCleanup { controller?.close() }

                val activeClient = client ?: runtime.client
                val ownedSessionId = sessionId
                if (activeClient != null && ownedSessionId != null) {
                    recordCleanup {
                        withTimeout(30_000L) {
                            requireSuccess(
                                activeClient.call(
                                    "workspace",
                                    "archiveSession",
                                    buildJsonObject {
                                        putJsonObject("request") { put("sessionId", ownedSessionId) }
                                    }
                                ),
                                "workspace/archiveSession cleanup"
                            )
                        }
                    }
                }
                val ownedProviderRoute = providerRoute
                if (activeClient != null && providerInstalled && ownedProviderRoute != null) {
                    recordCleanup {
                        withTimeout(30_000L) {
                            removeSyntheticProvider(activeClient, ownedProviderRoute)
                        }
                    }
                }
                recordCleanup { provider?.close() }
                if (ownsRuntime) {
                    recordCleanup {
                        withTimeout(30_000L) { runtime.forceStop() }
                        check(runtime.status.value?.state == HarnessRuntimeState.STOPPED.name) {
                            "Harness cleanup ended in ${runtime.status.value?.state}"
                        }
                    }
                }
                // Keep the authenticated WebView activity alive until the runtime and its
                // provider/session cleanup have completed; this also avoids a background
                // transition racing the foreground-service teardown.
                recordCleanup { web?.close() }
                recordCleanup { scenario?.close() }
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
            error("HarnessAppRuntime.start failed: ${failure.javaClass.simpleName}: ${failure.message}")
        }
        withTimeout(120_000L) {
            while (runtime.status.value?.state != HarnessRuntimeState.RUNNING.name ||
                runtime.client == null || runtime.endpoint.value == null
            ) {
                check(runtime.status.value?.state !in setOf("FAILED", "INTERRUPTED")) {
                    "Harness did not become ready: ${runtime.status.value?.state} (${runtime.errorCode.value})"
                }
                delay(100L)
            }
        }
    }

    private suspend fun installSyntheticProvider(
        client: HarnessClient,
        request: NativeHarnessCustomProviderRequest
    ) {
        val settings = requireSuccess(client.describeSettings(), "settings/describe")
        val section = settings.objectArray("namespaces").firstOrNull { it.string("ns") == "llm-pi-ai" }
            ?: error("llm-pi-ai settings namespace is unavailable")
        val providers = section.objectValue("value")?.objectValue("providers") ?: buildJsonObject {}
        check(request.route !in providers) { "synthetic route unexpectedly existed" }
        val revision = section.int("revision") ?: error("llm-pi-ai settings revision is missing")
        requireSuccess(
            client.mutateSettings(buildJsonObject {
                put("ns", "llm-pi-ai")
                putJsonArray("ops") { add(buildNativeCustomProviderOperation(request)) }
                put("expectedRevision", revision)
            }),
            "settings/mutate synthetic provider"
        )
    }

    private suspend fun awaitModel(client: HarnessClient, route: String, model: String) {
        withTimeout(60_000L) {
            while (true) {
                val catalog = requireSuccess(client.modelCatalog(), "session/modelCatalog")
                val found = catalog.objectArray("groups").any { group ->
                    group.string("id") == route && group.objectArray("models").any { it.string("id") == model }
                } && route in catalog.stringArray("routableProviders")
                if (found) return@withTimeout
                delay(250L)
            }
        }
    }

    private suspend fun removeSyntheticProvider(client: HarnessClient, route: String) {
        val settings = requireSuccess(client.describeSettings(), "settings/describe cleanup")
        val section = settings.objectArray("namespaces").firstOrNull { it.string("ns") == "llm-pi-ai" }
            ?: return
        val providers = section.objectValue("value")?.objectValue("providers") ?: buildJsonObject {}
        if (route !in providers) return
        val revision = section.int("revision") ?: error("llm-pi-ai cleanup revision is missing")
        requireSuccess(
            client.mutateSettings(buildJsonObject {
                put("ns", "llm-pi-ai")
                putJsonArray("ops") {
                    add(NativeHarnessCapabilities.unsetOperation(listOf("providers", route)))
                }
                put("expectedRevision", revision)
            }),
            "settings/unset synthetic provider"
        )
    }

    private suspend fun awaitCondition(message: String, predicate: () -> Boolean) {
        withTimeout(90_000L) {
            while (!predicate()) delay(100L)
        }
        assertTrue(message, predicate())
    }

    private fun runtimeUiState(runtime: HarnessAppRuntime): HarnessRuntimeUiState {
        val state = runtime.status.value?.state
        val uiStatus = when (state) {
            HarnessRuntimeState.STARTING.name -> HarnessRuntimeStatus.STARTING
            HarnessRuntimeState.RUNNING.name -> HarnessRuntimeStatus.RUNNING
            HarnessRuntimeState.STOP_REQUESTED.name,
            HarnessRuntimeState.FORCE_STOPPING.name -> HarnessRuntimeStatus.STOPPING
            HarnessRuntimeState.FAILED.name -> HarnessRuntimeStatus.ERROR
            HarnessRuntimeState.INTERRUPTED.name -> HarnessRuntimeStatus.INTERRUPTED
            else -> HarnessRuntimeStatus.STOPPED
        }
        return HarnessRuntimeUiState(
            status = uiStatus,
            detail = runtime.errorCode.value,
            endpointLabel = runtime.endpoint.value?.origin,
            canStart = uiStatus == HarnessRuntimeStatus.STOPPED || uiStatus == HarnessRuntimeStatus.ERROR,
            canStop = uiStatus == HarnessRuntimeStatus.RUNNING,
            canForceStop = uiStatus in setOf(
                HarnessRuntimeStatus.STARTING,
                HarnessRuntimeStatus.RUNNING,
                HarnessRuntimeStatus.STOPPING
            )
        )
    }

    private fun requireSuccess(result: HarnessRpcResult, operation: String): JsonElement = when (result) {
        is HarnessRpcResult.Success -> result.value
        is HarnessRpcResult.Failure -> error("$operation failed: ${result.error.code}: ${result.error.message}")
    }

    private fun combine(primary: Throwable?, secondary: Throwable): Throwable {
        if (primary == null) return secondary
        primary.addSuppressed(secondary)
        return primary
    }
}
