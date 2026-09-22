package com.example.llamadroid.ui.agent.harness

import com.example.llamadroid.harness.client.HarnessCallPolicy
import com.example.llamadroid.harness.client.HarnessClient
import com.example.llamadroid.harness.client.HarnessRpcResult
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

internal fun harnessPluginInstallCancellationPhase(status: String?): String = when (status) {
    "cancelled" -> "cancelled"
    "too-late" -> "applying"
    else -> "failed"
}

/** Maps bundle patch rows without confusing a declared row id with a live entry id. */
internal fun parseHarnessPluginRows(
    rows: List<JsonObject>,
    pluginsByEntry: Map<String, JsonObject>
): List<HarnessPluginRowUi> = rows.mapNotNull row@{ row ->
    val rowId = row.string("rowId") ?: return@row null
    val entryId = row.string("entryId")
    val plugin = entryId?.let(pluginsByEntry::get)
    HarnessPluginRowUi(
        rowId = rowId,
        entryId = entryId,
        label = row.string("moduleName") ?: plugin?.string("name") ?: rowId,
        enabled = plugin?.boolean("enabled").orDefault(entryId?.isNotBlank() == true),
        canChange = entryId?.isNotBlank() == true && plugin?.string("readOnlyReason") == null,
        phase = plugin?.string("fiberPhase") ?: plugin?.string("phase"),
        readOnlyReason = plugin?.string("readOnlyReason")
            ?: entryId?.takeIf { it.isBlank() }?.let { HARNESS_PLUGIN_UNADDRESSABLE_REASON }
            ?: if (entryId == null) HARNESS_PLUGIN_UNADDRESSABLE_REASON else null
    )
}

/** Produces the only valid per-row mutation; inactive bundle rows have no live target. */
internal fun pluginRowToggleArgs(entryId: String?, enabled: Boolean): JsonObject? =
    entryId?.takeIf { it.isNotBlank() }?.let { liveEntryId ->
        buildJsonObject {
            put("id", liveEntryId)
            put("enabled", enabled)
        }
    }

/** `pluginInventory/list` omits this flag when no managed profile exists. */
internal fun parseHarnessPluginManagementAvailable(value: JsonElement): Boolean =
    value.jsonObjectOrNull()?.boolean("managementAvailable") == true

/** Plugin manager response and inventory mapping kept outside the screen controller. */
internal class NativeHarnessPluginActions(
    private val clientProvider: suspend () -> HarnessClient?,
    private val stateProvider: () -> NativeHarnessUiState,
    private val mutate: suspend ((NativeHarnessUiState) -> NativeHarnessUiState) -> Unit,
    private val reportFailure: suspend (String, String) -> Unit
) {
    /** Requests cancelled while the Host is still inspecting a package. */
    private val cancelledPreflights: MutableSet<String> = ConcurrentHashMap.newKeySet()
    /** Requests that have not yet crossed the local inspect -> install boundary. */
    private val preflightRequests: MutableSet<String> = ConcurrentHashMap.newKeySet()
    /** Serializes the local checking -> failed/installing transition with Cancel. */
    private val preflightLock = Mutex()

    suspend fun install(action: NativeHarnessUiAction.InstallPlugin) {
        val client = clientProvider() ?: return
        val packageSpec = action.packageSpec.trim()
        if (packageSpec.isBlank()) return reportFailure(
            "PLUGIN_INVALID_SPEC",
            "Enter a plugin package or bundle specification"
        )
        if (stateProvider().extensions.pluginManagementAvailable != true) return
        val requestId = UUID.randomUUID().toString()
        val previousExtensions = stateProvider().extensions
        val retryingApprovedBuilds = action.approvedBuilds.isNotEmpty() &&
            previousExtensions.pluginInstall.packageSpec == packageSpec &&
            previousExtensions.pluginInstall.pendingBuilds.containsAll(action.approvedBuilds) &&
            previousExtensions.pluginInspection?.accepted == true
        val retainedInspection = previousExtensions.pluginInspection
        mutate { current ->
            current.copy(extensions = current.extensions.copy(
                pluginInstall = HarnessPluginInstallUiState(
                    requestId = requestId,
                    packageSpec = packageSpec,
                    phase = "checking"
                ),
                pluginInspection = if (retryingApprovedBuilds) retainedInspection else null
            ))
        }
        preflightRequests += requestId
        // The upstream manager always inspects the spec before it starts pnpm.
        // This keeps invalid, already-installed, and non-bundle specs from
        // reaching the mutating install endpoint.
        val inspected = if (retryingApprovedBuilds) {
            true
        } else {
            inspectForInstall(client, packageSpec, requestId)
        }
        val admitted = preflightLock.withLock {
            val installState = stateProvider().extensions.pluginInstall
            val cancelledBeforeTransition = cancelledPreflights.remove(requestId)
            if (installState.requestId != requestId || cancelledBeforeTransition) {
                if (cancelledBeforeTransition) {
                    mutate { current ->
                        if (current.extensions.pluginInstall.requestId != requestId) current else current.copy(
                            extensions = current.extensions.copy(
                                pluginInstall = current.extensions.pluginInstall.copy(
                                    phase = "cancelled",
                                    errorCode = null
                                )
                            )
                        )
                    }
                }
                preflightRequests.remove(requestId)
                false
            } else if (!inspected) {
                mutate { current ->
                    if (current.extensions.pluginInstall.requestId != requestId ||
                        current.extensions.pluginInstall.phase != "checking"
                    ) {
                        current
                    } else {
                        current.copy(extensions = current.extensions.copy(
                            pluginInstall = current.extensions.pluginInstall.copy(
                                phase = "failed",
                                errorCode = current.extensions.pluginInstall.errorCode
                                    ?: "PLUGIN_INSTALL_FAILED"
                            )
                        ))
                    }
                }
                preflightRequests.remove(requestId)
                false
            } else {
                mutate { current ->
                    if (current.extensions.pluginInstall.requestId != requestId ||
                        current.extensions.pluginInstall.phase != "checking"
                    ) {
                        current
                    } else {
                        current.copy(extensions = current.extensions.copy(
                            pluginInstall = current.extensions.pluginInstall.copy(phase = "installing")
                        ))
                    }
                }
                // Cancel records intent without waiting for this state
                // publication. Recheck after the suspend point before the
                // request crosses into the mutating Host call.
                val cancelledAfterTransition = cancelledPreflights.remove(requestId)
                if (cancelledAfterTransition) {
                    mutate { current ->
                        if (current.extensions.pluginInstall.requestId != requestId) current else current.copy(
                            extensions = current.extensions.copy(
                                pluginInstall = current.extensions.pluginInstall.copy(
                                    phase = "cancelled",
                                    errorCode = null
                                )
                            )
                        )
                    }
                    preflightRequests.remove(requestId)
                    false
                } else {
                    preflightRequests.remove(requestId)
                    stateProvider().extensions.pluginInstall.requestId == requestId &&
                        stateProvider().extensions.pluginInstall.phase == "installing"
                }
            }
        }
        if (!admitted) return
        val options = buildJsonObject {
            put("enabled", false)
            put("requestId", requestId)
            if (action.approvedBuilds.isNotEmpty()) {
                putJsonArray("approvedBuilds") {
                    action.approvedBuilds.forEach { add(JsonPrimitive(it)) }
                }
            }
        }
        when (val result = client.installBundle(packageSpec, options)) {
            is HarnessRpcResult.Failure -> {
                mutate { current ->
                    if (current.extensions.pluginInstall.requestId != requestId) current else current.copy(
                        extensions = current.extensions.copy(
                            pluginInstall = current.extensions.pluginInstall.copy(
                                phase = "failed",
                                errorCode = result.error.code,
                                logs = (current.extensions.pluginInstall.logs + result.error.message).takeLast(40)
                            )
                        )
                    )
                }
                reportFailure(result.error.code, result.error.message)
            }
            is HarnessRpcResult.Success -> {
                val outcome = result.value.jsonObjectOrNull()?.let(::parseHarnessPluginInstallOutcome)
                if (outcome == null) {
                    mutate { current ->
                        if (current.extensions.pluginInstall.requestId != requestId) current else current.copy(
                            extensions = current.extensions.copy(
                                pluginInstall = current.extensions.pluginInstall.copy(
                                    phase = "failed",
                                    errorCode = "PLUGIN_INSTALL_INVALID_RESULT"
                                )
                            )
                        )
                    }
                    reportFailure(
                        "PLUGIN_INSTALL_INVALID_RESULT",
                        "Harness returned an invalid plugin installation result"
                    )
                } else {
                    val failed = outcome.application == "failed"
                    val cancelled = outcome.application == "cancelled"
                    mutate { current ->
                        if (current.extensions.pluginInstall.requestId != requestId) current else current.copy(
                            extensions = current.extensions.copy(
                                pluginInstall = current.extensions.pluginInstall.copy(
                                    phase = when {
                                        failed -> "failed"
                                        cancelled -> "cancelled"
                                        outcome.restartRequired -> "restart-required"
                                        else -> "done"
                                    },
                                    errorCode = outcome.errorCode,
                                    pendingBuilds = outcome.pendingBuilds,
                                    approvedBuilds = outcome.approvedBuilds,
                                    restartRequired = outcome.restartRequired,
                                    logs = (current.extensions.pluginInstall.logs + listOfNotNull(
                                        outcome.packageKind,
                                        outcome.packageOutput
                                    )).takeLast(40)
                                )
                            )
                        )
                    }
                    if (failed) reportFailure(
                        "PLUGIN_INSTALL_FAILED",
                        outcome.packageOutput ?: "Harness could not install this plugin"
                    )
                    if (cancelled) reportFailure(
                        "PLUGIN_INSTALL_CANCELLED",
                        "The plugin installation was cancelled"
                    )
                }
                refresh(reportFailure = false)
            }
        }
    }

    suspend fun inspect(packageSpec: String) {
        val spec = packageSpec.trim()
        if (spec.isBlank()) return reportFailure(
            "PLUGIN_INVALID_SPEC",
            "Enter a plugin package or bundle specification"
        )
        if (stateProvider().extensions.pluginManagementAvailable != true) return
        val client = clientProvider() ?: return
        when (val result = client.inspectPlugin(spec)) {
            is HarnessRpcResult.Failure -> reportFailure(result.error.code, result.error.message)
            is HarnessRpcResult.Success -> publishInspection(spec, result.value)
        }
    }

    suspend fun cancel(requestId: String) {
        if (requestId.isBlank() || requestId != stateProvider().extensions.pluginInstall.requestId) return
        // Record intent before waiting for the transition lock. The install
        // transition itself calls mutate, which can suspend while publishing
        // state; waiting first would let cancellation race into installBundle.
        val observed = stateProvider().extensions.pluginInstall
        if (observed.requestId == requestId &&
            (observed.phase == "checking" || requestId in preflightRequests)
        ) {
            cancelledPreflights += requestId
        }
        val handledPreflight = preflightLock.withLock {
            val installState = stateProvider().extensions.pluginInstall
            val preflight = requestId in preflightRequests || installState.phase == "checking"
            if (installState.requestId != requestId || (!preflight && requestId !in cancelledPreflights)) {
                false
            } else {
                cancelledPreflights.remove(requestId)
                mutate { current ->
                    if (current.extensions.pluginInstall.requestId != requestId ||
                        current.extensions.pluginInstall.phase != "checking"
                    ) {
                        current
                    } else {
                        current.copy(extensions = current.extensions.copy(
                            pluginInstall = current.extensions.pluginInstall.copy(
                                phase = "cancelled",
                                errorCode = null
                            )
                        ))
                    }
                }
                preflightRequests.remove(requestId)
                true
            }
        }
        if (handledPreflight) return
        val phase = stateProvider().extensions.pluginInstall.phase
        if (phase == "cancelled" || phase == "failed" || phase == "done" || phase == "restart-required") return
        val client = clientProvider() ?: return
        when (val result = client.cancelInstall(requestId)) {
            is HarnessRpcResult.Failure -> reportFailure(result.error.code, result.error.message)
            is HarnessRpcResult.Success -> {
                val status = result.value.string("status")
                if (status != "cancelled" && status != "too-late") {
                    reportFailure(
                        "PLUGIN_INSTALL_CANCEL_UNAVAILABLE",
                        if (status == "not-running") {
                            "The plugin installation could not be cancelled"
                        } else {
                            "Harness returned an unknown plugin cancellation status"
                        }
                    )
                }
                mutate { current ->
                    if (current.extensions.pluginInstall.requestId != requestId) current else current.copy(
                        extensions = current.extensions.copy(
                            pluginInstall = current.extensions.pluginInstall.copy(
                                phase = harnessPluginInstallCancellationPhase(status),
                                errorCode = if (status == "cancelled" || status == "too-late") {
                                    current.extensions.pluginInstall.errorCode
                                } else {
                                    "PLUGIN_INSTALL_CANCEL_UNAVAILABLE"
                                }
                            )
                        )
                    )
                }
                refresh(reportFailure = false)
            }
        }
    }

    suspend fun call(method: String, args: JsonObject) {
        if (!pluginMutationAllowed(method, args)) return
        val client = clientProvider() ?: return
        when (val result = client.call("pluginManager", method, args, HarnessCallPolicy.NoRetry)) {
            is HarnessRpcResult.Failure -> reportFailure(result.error.code, result.error.message)
            is HarnessRpcResult.Success -> refresh(reportFailure = false)
        }
    }

    suspend fun setRowEnabled(entryId: String?, enabled: Boolean) {
        if (stateProvider().extensions.pluginManagementAvailable != true) return
        val inventoryEntry = entryId?.let { id ->
            stateProvider().extensions.pluginInventory.firstOrNull { it.entryId == id }
        }
        if (inventoryEntry?.readOnlyReason != null) return
        pluginRowToggleArgs(entryId, enabled)?.let { args ->
            call("setPluginEnabled", args)
        }
    }

    suspend fun refresh(reportFailure: Boolean) {
        val client = clientProvider() ?: return
        val inventoryResult = client.call(
            "pluginInventory",
            "list",
            policy = HarnessCallPolicy.SafeRead
        )
        val inventoryFailure = inventoryResult as? HarnessRpcResult.Failure
        if (inventoryFailure != null) {
            if (reportFailure) reportFailure(inventoryFailure.error.code, inventoryFailure.error.message)
            return
        }
        val inventoryValue = (inventoryResult as? HarnessRpcResult.Success)?.value ?: return
        val inventorySnapshot = parseHarnessPluginInventory(inventoryValue)
        val managementAvailable = parseHarnessPluginManagementAvailable(inventoryValue)
        if (!managementAvailable) {
            mutate { current ->
                current.copy(extensions = current.extensions.copy(
                    plugins = emptyList(),
                    pluginInventory = inventorySnapshot,
                    pluginManagementAvailable = false
                ))
            }
            return
        }
        val pluginsResult = client.listPlugins()
        val bundlesResult = client.listBundles()
        val failure = listOf(pluginsResult, bundlesResult)
            .filterIsInstance<HarnessRpcResult.Failure>()
            .firstOrNull()
        if (failure != null) {
            if (reportFailure) reportFailure(failure.error.code, failure.error.message)
            return
        }
        val plugins = (pluginsResult as? HarnessRpcResult.Success)?.value?.objectArrayOrSelf().orEmpty()
        val bundles = (bundlesResult as? HarnessRpcResult.Success)?.value?.objectArrayOrSelf().orEmpty()
        // `pluginInventory/list` is the capability probe; `pluginManager/listPlugins`
        // carries the addressability and read-only metadata needed for mutations.
        val managedInventory = (pluginsResult as? HarnessRpcResult.Success)
            ?.value
            ?.let(::parseHarnessPluginInventory)
            .orEmpty()
        val inventory = mergeHarnessPluginInventory(inventorySnapshot, managedInventory)
        val rowsByEntry = plugins.associateBy {
            it.string("entryId") ?: it.string("patchId") ?: it.string("id").orEmpty()
        }
        val mapped = bundles.mapNotNull { bundle ->
            val name = bundle.string("name") ?: return@mapNotNull null
            val pendingBuilds = bundle.stringArray("pendingBuilds")
            val installed = bundle.boolean("installed").orDefault(false)
            val optional = bundle.boolean("optional").orDefault(false)
            val readOnlyReason = bundle.string("readOnlyReason")
            val error = bundle.objectValue("error")
            val errorCode = error?.string("code")
            HarnessPluginUi(
                id = name,
                name = name,
                summary = bundle.string("description").orEmpty(),
                versionLabel = bundle.string("version"),
                installed = installed,
                optional = optional,
                enabled = bundle.boolean("enabled").orDefault(false),
                canInstall = !installed && !optional && readOnlyReason == null && error == null,
                canUninstall = bundle.boolean("removable").orDefault(false) && readOnlyReason == null,
                canToggle = (installed || optional) && readOnlyReason == null && error == null,
                readOnlyReason = readOnlyReason,
                errorCode = errorCode,
                errorDiagnostic = error?.string("diagnostic"),
                pendingBuildApproval = pendingBuilds.isNotEmpty() ||
                    errorCode == "build-blocked",
                pendingBuilds = pendingBuilds,
                bundleName = name,
                overrides = bundle.stringArray("overrides"),
                rows = parseHarnessPluginRows(bundle.objectArray("rows"), rowsByEntry)
            )
        }
        mutate { current ->
            current.copy(extensions = current.extensions.copy(
                plugins = mapped,
                pluginInventory = inventory,
                pluginManagementAvailable = true
            ))
        }
    }

    private suspend fun inspectForInstall(
        client: HarnessClient,
        spec: String,
        requestId: String
    ): Boolean {
        val alreadyInstalled = stateProvider().extensions.plugins.any { plugin ->
            plugin.id == spec || plugin.name == spec || plugin.bundleName == spec
        }
        if (alreadyInstalled) {
            publishInspection(spec, buildJsonObject {
                put("status", "refused")
                put("problem", "already-installed")
            })
            return false
        }
        return when (val result = client.inspectPlugin(spec)) {
            is HarnessRpcResult.Failure -> {
                if (!isPreflightCancelled(requestId)) {
                    reportFailure(result.error.code, result.error.message)
                }
                false
            }
            is HarnessRpcResult.Success -> {
                val value = result.value.jsonObjectOrNull()
                if (value == null) {
                    if (!isPreflightCancelled(requestId)) {
                        reportFailure(
                            "PLUGIN_INSTALL_INVALID_RESULT",
                            "Harness returned an invalid plugin inspection result"
                        )
                    }
                    false
                } else {
                    publishInspection(spec, value)
                    // Git and tarball inspections may return `bundle: null`;
                    // the manager resolves that package after installation.
                    value.string("status") == "accepted" && value.boolean("bundle") != false
                }
            }
        }
    }

    private suspend fun isPreflightCancelled(requestId: String): Boolean = preflightLock.withLock {
        requestId in cancelledPreflights || stateProvider().extensions.pluginInstall.let {
            it.requestId == requestId && it.phase == "cancelled"
        }
    }

    private suspend fun publishInspection(spec: String, value: JsonElement) {
        val objectValue = value.jsonObjectOrNull() ?: return mutate { current ->
            current.copy(extensions = current.extensions.copy(
                    pluginInspection = HarnessPluginInspectionUi(
                        spec = spec,
                        accepted = false,
                        problem = "invalid-result",
                        reason = null
                    )
            ))
        }
        mutate { current ->
            current.copy(extensions = current.extensions.copy(
                pluginInspection = HarnessPluginInspectionUi(
                    spec = spec,
                    accepted = objectValue.string("status") == "accepted",
                    name = objectValue.string("name"),
                    version = objectValue.string("version"),
                    description = objectValue.string("description"),
                    isBundle = objectValue.boolean("bundle"),
                    problem = objectValue.string("problem"),
                    reason = objectValue.string("reason")
                )
            ))
        }
    }

    /** Refuse known read-only/error targets before crossing the mutating RPC. */
    private fun pluginMutationAllowed(method: String, args: JsonObject): Boolean {
        if (stateProvider().extensions.pluginManagementAvailable != true) return false
        return when (method) {
            "setBundleEnabled" -> {
                val name = args.string("name") ?: return false
                stateProvider().extensions.plugins.firstOrNull {
                    it.bundleName == name || it.id == name
                }?.canToggle == true
            }
            "removeBundle" -> {
                val name = args.string("name") ?: return false
                stateProvider().extensions.plugins.firstOrNull {
                    it.bundleName == name || it.id == name
                }?.canUninstall == true
            }
            "setPluginEnabled" -> {
                val id = args.string("id") ?: return false
                val entry = stateProvider().extensions.pluginInventory
                    .firstOrNull { it.entryId == id }
                id.isNotBlank() && entry != null && entry.readOnlyReason == null
            }
            else -> true
        }
    }
}
