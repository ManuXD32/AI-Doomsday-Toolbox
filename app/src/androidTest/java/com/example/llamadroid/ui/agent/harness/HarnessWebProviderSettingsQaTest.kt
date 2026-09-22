package com.example.llamadroid.ui.agent.harness

import android.os.Build
import androidx.activity.ComponentActivity
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.llamadroid.BuildConfig
import com.example.llamadroid.harness.HarnessAppRuntime
import com.example.llamadroid.harness.client.HarnessRpcResult
import com.example.llamadroid.harness.runtime.HarnessRuntimeState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/**
 * Opt-in release-carrier proof for the actual authenticated settings WebView. It starts the
 * packaged Harness runtime, opens the production Models page, discovers a keyless zero-model
 * endpoint, creates the provider without a placeholder model, edits it, reloads it, and deletes
 * it again. The endpoint never leaves the emulator process and records only request metadata.
 */
@RunWith(AndroidJUnit4::class)
class HarnessWebProviderSettingsQaTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun qaWebProviderSettingsSupportsKeylessZeroModelCrudAcrossReload(): Unit = runBlocking {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue("Use the x86_64 Harness QA carrier", BuildConfig.HARNESS_QA_X86)
        assumeTrue("Use an x86_64 emulator", Build.SUPPORTED_ABIS.firstOrNull() == "x86_64")
        assumeTrue(
            "Pass harness_web_provider_qa=true on the isolated QA carrier",
            arguments.getString("harness_web_provider_qa") == "true",
        )

        val runtime = HarnessAppRuntime.get(context)
        val alreadyRunning = runtime.status.value?.state == HarnessRuntimeState.RUNNING.name && runtime.client != null
        val ownsRuntime = !alreadyRunning
        var scenario: ActivityScenario<ComponentActivity>? = null
        var web: HarnessWebProviderSettingsQaSurface? = null
        var provider: HarnessQaEmptyModelProvider? = null
        var route: String? = null
        var primaryFailure: Throwable? = null
        try {
            scenario = ActivityScenario.launch(ComponentActivity::class.java)
            if (ownsRuntime) startAndAwait(runtime)
            val endpoint = requireNotNull(runtime.endpoint.value) { "Harness runtime published no endpoint" }
            requireNotNull(runtime.client) { "Harness runtime has no client" }

            val suffix = UUID.randomUUID().toString().replace("-", "").take(10)
            val providerRoute = "qa-keyless-$suffix"
            route = providerRoute
            val displayName = "ADT QA keyless $suffix"
            val localProvider = HarnessQaEmptyModelProvider()
            provider = localProvider
            val surface = HarnessWebProviderSettingsQaSurface(
                InstrumentationRegistry.getInstrumentation(),
                endpoint,
            )
            web = surface
            surface.attach(requireNotNull(scenario))

            // A fresh QA runtime can show the pinned welcome/onboarding modal. Dismiss only those
            // optional notices; the settings route itself is still opened through its real button.
            surface.awaitText("Settings")
            surface.clickTextIfPresent("Continue")
            surface.clickTextIfPresent("Configure later")
            openModels(surface)
            surface.awaitText("Add a custom provider")
            surface.clickText("Add a custom provider")

            surface.fillInput("Provider ID", providerRoute)
            surface.fillInput("Display name", displayName)
            surface.fillInput("Base URL", localProvider.baseUrl)
            // Leave the API key blank. The click must exercise the unsaved draft route and the
            // native bounded probe; no model row is entered before discovery or save.
            surface.clickText("Fetch available models")
            localProvider.awaitRequestCount(2)
            surface.awaitText("The provider listed no models. Add them by hand.")
            assertTrue(
                "Keyless discovery unexpectedly sent an Authorization header",
                localProvider.requests().all { it.authorization == null },
            )
            assertEquals(
                listOf("/v1/models", "/props"),
                localProvider.requests().map { it.path },
            )

            surface.clickText("Create provider")
            awaitProviderProfile(surface, providerRoute) { profile ->
                profile.string("baseURL") == localProvider.baseUrl &&
                    profile["apiKeyEnv"] == null &&
                    (profile["models"] as? JsonArray).orEmpty().isEmpty()
            }

            // Reload the actual original WebView before editing. This catches stale page state and
            // proves the newly-created keyless profile is rehydrated from the host settings store.
            surface.reload()
            openModels(surface)
            // The compact provider row may render only the display name in body text; the route
            // remains in the authenticated settings snapshot and in the edit control's aria label.
            awaitProviderProfile(surface, providerRoute) { it.string("baseURL") == localProvider.baseUrl }

            surface.clickAriaLabelContaining("Edit $displayName")
            surface.clickText("Customized settings")
            val editedBaseUrl = localProvider.baseUrl.removeSuffix("/v1") + "/edited/v1"
            surface.fillInput("Base URL", editedBaseUrl)
            surface.clickText("Apply")
            awaitProviderProfile(surface, providerRoute) { profile ->
                profile.string("baseURL") == editedBaseUrl && profile["apiKeyEnv"] == null
            }

            surface.clickAriaLabelContaining("Delete $displayName")
            surface.clickText("Delete $displayName ($providerRoute)")
            awaitNoProviderProfile(surface, providerRoute)
            surface.reload()
            openModels(surface)
            surface.awaitTextAbsent(providerRoute)
            awaitNoProviderProfile(surface, providerRoute)
        } catch (cancelled: CancellationException) {
            throw cancelled
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
                val cleanupRoute = route
                if (cleanupRoute != null) {
                    recordCleanup {
                        // If the WebView has already reported a plugin failure, its JavaScript
                        // fetch bridge may never settle. Prefer the still-authenticated native
                        // client so cleanup cannot block the runtime force-stop path on a broken
                        // page; the page fallback remains useful when no client is available.
                        withTimeout(10_000L) {
                            cleanupProvider(web = web, client = authenticatedClientOrNull(runtime), providerId = cleanupRoute)
                        }
                    }
                }
                recordCleanup { provider?.close() }
                if (ownsRuntime) {
                    recordCleanup { withTimeout(30_000L) { runtime.forceStop() } }
                }
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
            val record = runtime.status.value
            val diagnostic = runtime.diagnostics.runtimeEntries.value.asReversed().firstOrNull { entry ->
                entry.event in setOf("startup_output", "process_exited", "start_failed")
            }
            val code = runtime.errorCode.value ?: record?.errorCode ?: diagnostic?.errorCode
            error(
                "HarnessAppRuntime.start failed: ${failure.javaClass.simpleName}: " +
                    "code=${code ?: "UNKNOWN"} " +
                    "state=${record?.state ?: "UNKNOWN"} " +
                    "phase=${diagnostic?.phase ?: "UNKNOWN"} " +
                    "exitCode=${diagnostic?.exitCode ?: "UNKNOWN"}"
            )
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

    private suspend fun openModels(surface: HarnessWebProviderSettingsQaSurface) {
        surface.clickAriaLabel("Settings")
        surface.clickSettingsNav("Models")
    }

    private suspend fun awaitProviderProfile(
        web: HarnessWebProviderSettingsQaSurface,
        providerId: String,
        predicate: (JsonObject) -> Boolean,
    ): JsonObject {
        var profile: JsonObject? = null
        withTimeout(90_000L) {
            while (profile == null) {
                val section = findNamespace(web.rpc("settings", "describe"), "llm-pi-ai")
                val candidate = section?.objectValue("value")?.objectValue("providers")?.objectValue(providerId)
                if (candidate != null && predicate(candidate)) profile = candidate else delay(200L)
            }
        }
        return requireNotNull(profile) { "WebView did not expose provider $providerId" }
    }

    private suspend fun awaitNoProviderProfile(web: HarnessWebProviderSettingsQaSurface, providerId: String) {
        withTimeout(90_000L) {
            while (true) {
                val section = findNamespace(web.rpc("settings", "describe"), "llm-pi-ai")
                val candidate = section?.objectValue("value")?.objectValue("providers")?.get(providerId)
                if (candidate == null) return@withTimeout
                delay(200L)
            }
        }
    }

    private suspend fun cleanupProvider(
        web: HarnessWebProviderSettingsQaSurface?,
        client: com.example.llamadroid.harness.client.HarnessClient?,
        providerId: String,
    ) {
        val section = when {
            client != null -> {
                val result = client.call("settings", "describe")
                when (result) {
                    is HarnessRpcResult.Success -> findNamespace(result.value, "llm-pi-ai")
                    is HarnessRpcResult.Failure -> null
                }
            }
            web != null -> findNamespace(web.rpc("settings", "describe"), "llm-pi-ai")
            else -> null
        } ?: return
        section.objectValue("value")?.objectValue("providers")?.get(providerId) ?: return
        val revision = section.int("revision") ?: return
        val operation = buildJsonObject {
            put("op", "unset")
            putJsonArray("path") {
                add(kotlinx.serialization.json.JsonPrimitive("providers"))
                add(kotlinx.serialization.json.JsonPrimitive(providerId))
            }
        }
        val args = buildJsonObject {
            put("ns", "llm-pi-ai")
            putJsonArray("ops") { add(operation) }
            put("expectedRevision", revision)
        }
        when {
            client != null -> client.call("settings", "mutate", args)
            web != null -> web.rpc("settings", "mutate", args)
        }
    }

    private fun findNamespace(value: kotlinx.serialization.json.JsonElement, namespace: String): JsonObject? =
        value.objectArray("namespaces").firstOrNull { it.string("ns") == namespace }

    private fun authenticatedClientOrNull(runtime: HarnessAppRuntime) = runtime.client

    private fun combine(primary: Throwable?, secondary: Throwable): Throwable {
        if (primary == null) return secondary
        primary.addSuppressed(secondary)
        return primary
    }
}
