package com.example.llamadroid.service.delegation

import android.content.Context
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.data.model.LlamaServerEntity
import com.example.llamadroid.data.model.buildLlamaServerBaseUrl
import com.example.llamadroid.harness.HarnessAppRuntime
import com.example.llamadroid.harness.client.HarnessClient
import com.example.llamadroid.harness.client.HarnessRpcResult
import com.example.llamadroid.ui.agent.harness.HarnessProviderOption
import com.example.llamadroid.ui.agent.harness.NATIVE_CUSTOM_PROVIDER_SETTINGS_NAMESPACE
import com.example.llamadroid.ui.agent.harness.NativeHarnessCapabilities
import com.example.llamadroid.ui.agent.harness.NativeHarnessCustomProviderModel
import com.example.llamadroid.ui.agent.harness.NativeHarnessCustomProviderRequest
import com.example.llamadroid.ui.agent.harness.NativeHarnessProviderGateway
import com.example.llamadroid.ui.agent.harness.NATIVE_CUSTOM_PROVIDER_MAX_STREAM_IDLE_TIMEOUT_MS
import com.example.llamadroid.ui.agent.harness.buildNativeCustomProviderProfile
import com.example.llamadroid.ui.agent.harness.nativeCustomProviderTimeoutUpgradeOperations
import com.example.llamadroid.ui.agent.harness.canonicalHarnessProviderId
import com.example.llamadroid.ui.agent.harness.int
import com.example.llamadroid.ui.agent.harness.jsonObjectOrNull
import com.example.llamadroid.ui.agent.harness.long
import com.example.llamadroid.ui.agent.harness.mergeHarnessLocalModelProviders
import com.example.llamadroid.ui.agent.harness.normalizeNativeCustomProviderRequest
import com.example.llamadroid.ui.agent.harness.objectArray
import com.example.llamadroid.ui.agent.harness.objectValue
import com.example.llamadroid.ui.agent.harness.parseHarnessLocalModelCatalog
import com.example.llamadroid.ui.agent.harness.parseHarnessModelCatalog
import com.example.llamadroid.ui.agent.harness.string
import com.example.llamadroid.ui.agent.harness.validateNativeCustomProviderRequest
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.jsonObject

/** How a server row reaches the harness. */
enum class HarnessRouteKind { MANAGED_CARD, LITERT, LLAMA_SWAP, CUSTOM_ROUTE }

/** Provider and model the harness session must select, plus the limits that were applied. */
data class HarnessModelBinding(
    val provider: String,
    val model: String,
    val contextTokens: Long?,
    val maxOutputTokens: Long?,
    val routeKind: HarnessRouteKind,
)

/** Pure description of how one server row maps to a harness wire id or custom route. */
internal data class HarnessRoutePlan(
    val kind: HarnessRouteKind,
    /** Catalog wire id (`llama:<card>`, `litert:<id>`, `llama-swap:<model>`) or null for custom. */
    val wireId: String?,
    /** Custom provider route (also its provider id) for [HarnessRouteKind.CUSTOM_ROUTE]. */
    val customRoute: String,
    val baseUrl: String,
    /** Model id sent to the OpenAI-compatible endpoint. */
    val modelId: String,
)

/** Pure mapping and catalog-resolution helpers (unit tested). */
internal object DelegateHarnessRouting {
    const val ROUTE_PREFIX = "adt-chat-srv-"

    /** Maximum `streamIdleTimeoutMs` the pinned provider plugin accepts (it rejects 0 and larger values). */
    const val STREAM_IDLE_TIMEOUT_MS = NATIVE_CUSTOM_PROVIDER_MAX_STREAM_IDLE_TIMEOUT_MS
    const val DEFAULT_CONTEXT = 8192
    const val DEFAULT_MAX_OUTPUT = 2048

    fun customRouteName(rowId: Long): String = ROUTE_PREFIX + rowId.coerceAtLeast(0)

    /** `http://host:port/v1` from a row's host and port. */
    fun customBaseUrl(host: String, port: Int): String = buildLlamaServerBaseUrl(host, port).trimEnd('/') + "/v1"

    /** Model id used on the wire to a custom llama-server/llama-swap endpoint. */
    fun customModelId(server: LlamaServerEntity): String =
        server.modelName?.trim()?.takeIf { it.isNotEmpty() } ?: "default"

    fun plan(server: LlamaServerEntity): HarnessRoutePlan {
        val route = customRouteName(server.id)
        val base = customBaseUrl(server.host, server.port)
        val model = customModelId(server)
        fun custom() = HarnessRoutePlan(HarnessRouteKind.CUSTOM_ROUTE, null, route, base, model)
        return when {
            server.isOllamaEngine() -> throw DelegationHarnessException("SERVER_ENGINE_UNSUPPORTED",
                "Ollama servers cannot be used as the DeepSeek agent model. Pick a llama-server, llama-swap, LiteRT or managed model.")
            server.isLiteRtEngine() -> {
                val id = server.liteRtModelId ?: throw DelegationHarnessException("SERVER_MODEL_MISSING",
                    "The LiteRT server has no model selected.")
                HarnessRoutePlan(HarnessRouteKind.LITERT, "litert:$id", route, base, model)
            }
            server.managedServerCardId != null ->
                HarnessRoutePlan(HarnessRouteKind.MANAGED_CARD, "llama:${server.managedServerCardId}", route, base, model)
            server.isLlamaSwapEngine() && !server.modelName.isNullOrBlank() ->
                HarnessRoutePlan(HarnessRouteKind.LLAMA_SWAP, "llama-swap:${server.modelName.trim()}", route, base, model)
            else -> custom()
        }
    }

    /** Provider owning [wireId]; [preferred] wins when several providers list it. */
    fun resolveProvider(providers: List<HarnessProviderOption>, wireId: String, preferred: String? = null): String? {
        val owners = providers.filter { wireId in it.models }
        val chosen = owners.firstOrNull { it.id == preferred } ?: owners.firstOrNull() ?: return null
        return canonicalHarnessProviderId(providers, chosen.id, wireId)
    }

    fun clampContext(value: Int?): Int =
        (value ?: DEFAULT_CONTEXT).coerceIn(NativeChatDelegationConfig.MIN_CONTEXT_TOKENS, NativeChatDelegationConfig.MAX_CONTEXT_TOKENS)

    fun clampMaxOutput(value: Int?, context: Int): Int =
        (value ?: DEFAULT_MAX_OUTPUT).coerceIn(NativeChatDelegationConfig.MIN_OUTPUT_TOKENS, NativeChatDelegationConfig.MAX_OUTPUT_TOKENS)
            .coerceAtMost((context / 2).coerceAtLeast(NativeChatDelegationConfig.MIN_OUTPUT_TOKENS))

    /** True when the stored profile already equals the desired one (skip a needless write). */
    fun profileMatches(existing: JsonObject?, desired: JsonObject): Boolean {
        if (existing == null) return false
        if (existing.string("baseURL") != desired.string("baseURL") || existing.string("api") != desired.string("api")) return false
        val have = existing.objectArray("models").firstOrNull() ?: return false
        val want = desired.objectArray("models").firstOrNull() ?: return false
        if (existing.long("streamIdleTimeoutMs") != desired.long("streamIdleTimeoutMs")) return false
        return have.string("id") == want.string("id") &&
            have.long("contextWindow") == want.long("contextWindow") &&
            have.long("maxTokens") == want.long("maxTokens")
    }
}

/** Resolves a [DelegationServerChoice] to the provider/model a harness session should use. */
object DelegateHarnessModelBinder {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * @param fallbackContextTokens used for custom routes when the choice carries no entered
     * limits (for example "Same as chat" on a remote server).
     * @throws DelegationHarnessException with model-facing text on any failure.
     */
    suspend fun bind(
        context: Context,
        runtime: HarnessAppRuntime,
        client: HarnessClient,
        choice: DelegationServerChoice,
        chatServer: LlamaServerEntity?,
        fallbackContextTokens: Int? = null,
        fallbackMaxOutputTokens: Int? = null,
    ): HarnessModelBinding {
        val server = when (choice) {
            DelegationServerChoice.SameAsChat -> chatServer ?: throw DelegationHarnessException(
                "SERVER_MISSING", "The chat has no server to use for the DeepSeek agent model.")
            is DelegationServerChoice.Server ->
                AppDatabase.getDatabase(context.applicationContext).llamaServerDao().getServerById(choice.rowId)
                    ?: throw DelegationHarnessException("SERVER_MISSING", "The configured DeepSeek agent server no longer exists.")
        }
        val enteredContext = (choice as? DelegationServerChoice.Server)?.contextTokens ?: fallbackContextTokens
        val enteredOutput = (choice as? DelegationServerChoice.Server)?.maxOutputTokens ?: fallbackMaxOutputTokens
        val plan = DelegateHarnessRouting.plan(server)
        try {
            return when (plan.kind) {
                HarnessRouteKind.MANAGED_CARD -> bindCatalogModel(runtime, client, plan, null, null)
                HarnessRouteKind.LITERT -> {
                    val wire = requireNotNull(plan.wireId)
                    val existing = runtime.localModelCapabilities.get(wire)
                    if (enteredContext != null || enteredOutput != null) {
                        runtime.updateLocalModelCapability(
                            wire,
                            (enteredContext ?: existing?.contextTokens?.toInt())?.toLong(),
                            (enteredOutput ?: existing?.maxOutputTokens?.toInt())?.toLong(),
                            existing?.mtpEnabled,
                            existing?.thinkingEnabled,
                        )
                    }
                    bindCatalogModel(runtime, client, plan, enteredContext, enteredOutput)
                }
                HarnessRouteKind.LLAMA_SWAP -> {
                    val catalog = loadCatalog(runtime, client)
                    if (DelegateHarnessRouting.resolveProvider(catalog, requireNotNull(plan.wireId)) != null) {
                        bindFromProviders(catalog, plan, enteredContext, enteredOutput)
                    } else {
                        bindCustom(runtime, client, plan.copy(kind = HarnessRouteKind.CUSTOM_ROUTE), enteredContext, enteredOutput)
                            .copy(routeKind = HarnessRouteKind.LLAMA_SWAP)
                    }
                }
                HarnessRouteKind.CUSTOM_ROUTE -> bindCustom(runtime, client, plan, enteredContext, enteredOutput)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: DelegationHarnessException) {
            throw failure
        } catch (failure: Exception) {
            throw DelegationHarnessException("MODEL_BIND_FAILED",
                "Could not select the DeepSeek agent model (${failure.javaClass.simpleName}). Tell the user to check the DeepSeek model settings.", failure)
        }
    }

    private suspend fun bindCatalogModel(
        runtime: HarnessAppRuntime,
        client: HarnessClient,
        plan: HarnessRoutePlan,
        enteredContext: Int?,
        enteredOutput: Int?,
    ): HarnessModelBinding = bindFromProviders(loadCatalog(runtime, client), plan, enteredContext, enteredOutput)

    private fun bindFromProviders(
        providers: List<HarnessProviderOption>,
        plan: HarnessRoutePlan,
        enteredContext: Int?,
        enteredOutput: Int?,
    ): HarnessModelBinding {
        val wire = requireNotNull(plan.wireId)
        val provider = DelegateHarnessRouting.resolveProvider(providers, wire) ?: throw DelegationHarnessException(
            "MODEL_NOT_IN_CATALOG",
            "The DeepSeek runtime does not list the model '$wire'. The model may be missing, unavailable or the runtime may need a restart.")
        val option = providers.firstOrNull { it.id == provider && wire in it.models } ?: providers.first { wire in it.models }
        return HarnessModelBinding(
            provider = provider,
            model = wire,
            contextTokens = option.modelContextWindows[wire] ?: enteredContext?.toLong(),
            maxOutputTokens = option.modelMaxOutputTokens[wire] ?: enteredOutput?.toLong(),
            routeKind = plan.kind,
        )
    }

    /** Create or update `adt-chat-srv-<rowId>` (never a duplicate) and resolve it from the catalog. */
    private suspend fun bindCustom(
        runtime: HarnessAppRuntime,
        client: HarnessClient,
        plan: HarnessRoutePlan,
        enteredContext: Int?,
        enteredOutput: Int?,
    ): HarnessModelBinding {
        val context = DelegateHarnessRouting.clampContext(enteredContext)
        val maxOut = DelegateHarnessRouting.clampMaxOutput(enteredOutput, context)
        val request = normalizeNativeCustomProviderRequest(NativeHarnessCustomProviderRequest(
            route = plan.customRoute,
            displayName = "ADT chat server ${plan.customRoute.removePrefix(DelegateHarnessRouting.ROUTE_PREFIX)}",
            api = "openai-completions",
            baseUrl = plan.baseUrl,
            models = listOf(NativeHarnessCustomProviderModel(id = plan.modelId, contextWindow = context, maxTokens = maxOut)),
            // Slow phones can take minutes to process the harness prompt; use the largest idle limit the plugin accepts.
            streamIdleTimeoutMs = DelegateHarnessRouting.STREAM_IDLE_TIMEOUT_MS,
        ))
        validateNativeCustomProviderRequest(request)?.let { error ->
            throw DelegationHarnessException(error.code, "The server cannot be used as a DeepSeek provider: ${error.detail}")
        }
        val settings = when (val result = client.describeSettings()) {
            is HarnessRpcResult.Failure -> throw DelegationHarnessException("SETTINGS_UNAVAILABLE",
                "The DeepSeek runtime settings could not be read (${result.error.code}).")
            is HarnessRpcResult.Success -> result.value
        }
        val namespace = settings.objectArray("namespaces").firstOrNull {
            it.string("ns") == NATIVE_CUSTOM_PROVIDER_SETTINGS_NAMESPACE
        } ?: throw DelegationHarnessException("SETTINGS_UNAVAILABLE", "The DeepSeek runtime does not expose custom provider settings.")
        val revision = namespace.int("revision") ?: 0
        val existing = namespace["value"]?.jsonObjectOrNull()?.objectValue("providers")?.objectValue(request.route)
        val desired = buildNativeCustomProviderProfile(request)
        if (!DelegateHarnessRouting.profileMatches(existing, desired)) {
            val result = if (existing == null) {
                NativeHarnessProviderGateway.create(client, request, revision)
            } else {
                NativeHarnessCapabilities.mutateSettings(
                    client, NATIVE_CUSTOM_PROVIDER_SETTINGS_NAMESPACE,
                    buildJsonArray { add(NativeHarnessCapabilities.setOperation(listOf("providers", request.route), desired)) },
                    revision,
                )
            }
            if (result is HarnessRpcResult.Failure) throw DelegationHarnessException(result.error.code.ifBlank { "PROVIDER_WRITE_FAILED" },
                "The custom provider for the chat server could not be written (${result.error.code}).")
        } else {
            // Nothing was written: take the chance to give older routes the default stream idle limit (conflicts ignored).
            val upgrades = nativeCustomProviderTimeoutUpgradeOperations(namespace["value"]?.jsonObjectOrNull())
            if (upgrades.isNotEmpty()) {
                NativeHarnessCapabilities.mutateSettings(
                    client, NATIVE_CUSTOM_PROVIDER_SETTINGS_NAMESPACE, buildJsonArray { upgrades.forEach { add(it) } }, revision,
                )
            }
        }
        val providers = loadCatalog(runtime, client)
        val provider = DelegateHarnessRouting.resolveProvider(providers, plan.modelId, preferred = request.route)
            ?.takeIf { it == request.route }
            ?: throw DelegationHarnessException("MODEL_NOT_IN_CATALOG",
                "The custom provider '${request.route}' was saved but its model is not listed yet. Try again in a moment.")
        return HarnessModelBinding(provider, plan.modelId, context.toLong(), maxOut.toLong(), HarnessRouteKind.CUSTOM_ROUTE)
    }

    /** Harness catalog groups merged with the Android bridge's local models (same as the Harness UI). */
    private suspend fun loadCatalog(runtime: HarnessAppRuntime, client: HarnessClient): List<HarnessProviderOption> {
        val remote = when (val result = client.modelCatalog()) {
            is HarnessRpcResult.Failure -> throw DelegationHarnessException("MODEL_CATALOG_UNAVAILABLE",
                "The DeepSeek model catalog could not be read (${result.error.code}).")
            is HarnessRpcResult.Success -> result.value.jsonObjectOrNull() ?: throw DelegationHarnessException(
                "MODEL_CATALOG_UNAVAILABLE", "The DeepSeek runtime returned an invalid model catalog.")
        }
        val parsed = parseHarnessModelCatalog(remote).providers
        val local = try {
            val raw = runtime.localModelCapabilities.apply(json.parseToJsonElement(runtime.models.models().toString()).jsonObject)
            parseHarnessLocalModelCatalog(raw)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            emptyList()
        }
        return mergeHarnessLocalModelProviders(parsed, local)
    }
}
