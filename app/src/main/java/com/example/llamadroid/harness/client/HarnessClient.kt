package com.example.llamadroid.harness.client

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.put

/** App-owned boundary for native screens and the WebView shell. */
interface HarnessClient : AutoCloseable {
    val state: StateFlow<HarnessConnectionState>

    /** Exchange the one-use process launch URL for the authority-bound cookie. */
    suspend fun authenticate(launchUrl: String): HarnessAuthResult

    /** Adopt the cookie already exchanged by the runtime readiness probe. */
    fun adoptAuthenticatedEndpoint(origin: String, cookieHeader: String): HarnessAuthResult

    /** Call any schema-backed DSH endpoint with named arguments. */
    suspend fun call(
        namespace: String,
        method: String,
        args: JsonObject = kotlinx.serialization.json.buildJsonObject {},
        policy: HarnessCallPolicy = HarnessCallPolicy.NoRetry,
        requestId: String = java.util.UUID.randomUUID().toString()
    ): HarnessRpcResult

    /** Authenticated JSON routes used by change review and deliverables, on the same instance. */
    suspend fun fetchJson(
        path: String,
        query: Map<String, String> = emptyMap(),
        body: JsonObject? = null,
        requestId: String = java.util.UUID.randomUUID().toString()
    ): HarnessRpcResult = HarnessRpcResult.Failure(
        HarnessRpcError("client/route-unavailable", "Harness HTTP route is unavailable")
    )

    /** Follow a DSH stream over the shared Remote mux. */
    fun stream(
        namespace: String,
        method: String,
        args: JsonObject = kotlinx.serialization.json.buildJsonObject {},
        policy: HarnessStreamPolicy = HarnessStreamPolicy.Default
    ): Flow<kotlinx.serialization.json.JsonElement>

    suspend fun listSessions(cursor: String? = null): HarnessRpcResult = call(
        namespace = "session",
        method = "list",
        args = kotlinx.serialization.json.buildJsonObject {
            put("_request", kotlinx.serialization.json.buildJsonObject {
                if (cursor != null) put("cursor", cursor)
            })
        },
        policy = HarnessCallPolicy.SafeRead
    )

    suspend fun searchSessions(query: String): HarnessRpcResult = call(
        "session",
        "search",
        kotlinx.serialization.json.buildJsonObject {
            put("request", kotlinx.serialization.json.buildJsonObject { put("query", query) })
        },
        HarnessCallPolicy.SafeRead
    )

    suspend fun createSession(args: JsonObject): HarnessRpcResult = call(
        "session", "create", kotlinx.serialization.json.buildJsonObject { put("request", args) }, HarnessCallPolicy.NoRetry
    )

    suspend fun selectSessionModel(args: JsonObject): HarnessRpcResult = call(
        "session", "selectModel", kotlinx.serialization.json.buildJsonObject { put("request", args) }, HarnessCallPolicy.NoRetry
    )

    suspend fun promptSession(args: JsonObject): HarnessRpcResult = call(
        "session", "prompt", kotlinx.serialization.json.buildJsonObject { put("request", args) }, HarnessCallPolicy.NoRetry
    )

    fun followSession(
        args: JsonObject,
        policy: HarnessStreamPolicy = HarnessStreamPolicy.SessionFollow
    ): Flow<kotlinx.serialization.json.JsonElement> = stream(
        "session", "follow", kotlinx.serialization.json.buildJsonObject { put("request", args) }, policy
    )

    /**
     * Open the official Gateway forwarded-event stream. The first item is a
     * `{type:"ready", clientId, host}` frame; later items are `emit`,
     * `waterfall`, or `cancel` frames. Reconnect generations issue a new
     * clientId, so callers must discard pending event decisions on `ready`.
     */
    fun remoteEvents(
        policy: HarnessStreamPolicy = HarnessStreamPolicy.Default
    ): Flow<kotlinx.serialization.json.JsonElement> = stream(
        HarnessWire.REMOTE_EVENT_ENDPOINT,
        "",
        kotlinx.serialization.json.buildJsonObject {},
        policy
    )

    /** Post one result for a Gateway forwarded-event waterfall. */
    suspend fun remoteEventResult(args: JsonObject): HarnessRpcResult = call(
        HarnessWire.REMOTE_EVENT_ENDPOINT,
        "result",
        args,
        HarnessCallPolicy.NoRetry
    )

    suspend fun pageSession(args: JsonObject): HarnessRpcResult = call(
        "session", "page", kotlinx.serialization.json.buildJsonObject { put("request", args) }, HarnessCallPolicy.SafeRead
    )

    suspend fun cancelSession(args: JsonObject): HarnessRpcResult = call(
        "session", "cancel", kotlinx.serialization.json.buildJsonObject { put("request", args) }, HarnessCallPolicy.NoRetry
    )

    suspend fun modelCatalog(): HarnessRpcResult = call(
        "session", "modelCatalog", policy = HarnessCallPolicy.SafeRead
    )

    suspend fun describeSettings(): HarnessRpcResult = call(
        "settings", "describe", policy = HarnessCallPolicy.SafeRead
    )

    suspend fun updateSettings(args: JsonObject): HarnessRpcResult = call(
        "settings", "update", args, HarnessCallPolicy.NoRetry
    )

    suspend fun replaceSettings(args: JsonObject): HarnessRpcResult = call(
        "settings", "replace", args, HarnessCallPolicy.NoRetry
    )

    suspend fun mutateSettings(args: JsonObject): HarnessRpcResult = call(
        "settings", "mutate", args, HarnessCallPolicy.NoRetry
    )

    suspend fun describeCredentials(refs: kotlinx.serialization.json.JsonArray): HarnessRpcResult = call(
        "credentials", "describe", kotlinx.serialization.json.buildJsonObject { put("refs", refs) }, HarnessCallPolicy.SafeRead
    )

    suspend fun listPlugins(): HarnessRpcResult = call(
        "pluginManager", "listPlugins", policy = HarnessCallPolicy.SafeRead
    )

    suspend fun listBundles(): HarnessRpcResult = call(
        "pluginManager", "listBundles", policy = HarnessCallPolicy.SafeRead
    )

    suspend fun inspectPlugin(spec: String): HarnessRpcResult = call(
        "pluginManager", "inspect", kotlinx.serialization.json.buildJsonObject { put("spec", spec) }, HarnessCallPolicy.SafeRead
    )

    suspend fun installBundle(spec: String, options: JsonObject? = null): HarnessRpcResult = call(
        "pluginManager", "installBundle", kotlinx.serialization.json.buildJsonObject {
            put("spec", spec)
            if (options != null) put("options", options)
        }, HarnessCallPolicy.NoRetry
    )

    suspend fun cancelInstall(requestId: String): HarnessRpcResult = call(
        "pluginManager", "cancelInstall", kotlinx.serialization.json.buildJsonObject { put("requestId", requestId) }, HarnessCallPolicy.NoRetry
    )

    suspend fun removeBundle(name: String): HarnessRpcResult = call(
        "pluginManager", "removeBundle", kotlinx.serialization.json.buildJsonObject { put("name", name) }, HarnessCallPolicy.NoRetry
    )

    /** Prepare the Android-owned workspace identity before session/create. */
    suspend fun prepareWorkspace(args: JsonObject): HarnessRpcResult = call(
        "adt", "prepareWorkspace", args, HarnessCallPolicy.NoRetry
    )

    override fun close()
}
