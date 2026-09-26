package com.example.llamadroid.ui.agent.harness

import com.example.llamadroid.harness.client.HarnessCallPolicy
import com.example.llamadroid.harness.client.HarnessClient
import com.example.llamadroid.harness.client.HarnessRpcResult
import com.example.llamadroid.harness.client.HarnessStreamPolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Native mappings for the bundled management surfaces. Keeping these calls in
 * a separate controller leaves the chat/session controller focused on the
 * transcript and preserves one place for each official Remote vocabulary.
 */
internal class NativeHarnessParityController(
    private val scope: CoroutineScope,
    private val clientProvider: () -> HarnessClient?,
    private val selectedSessionProvider: () -> String?,
    private val mutateState: suspend ((NativeHarnessUiState) -> NativeHarnessUiState) -> Unit,
    private val reportFailure: suspend (String, String) -> Unit,
    private val refreshSessions: suspend () -> Unit,
    private val handleExternalAction: suspend (NativeHarnessUiAction) -> Unit,
    private val reconcileWorkspaceGroup: suspend (HarnessWorkspaceGroupUi) -> Unit = {}
) : AutoCloseable {
    private var workspaceJob: Job? = null
    private val workspaceStreamLock = Mutex()

    suspend fun initialize() {
        refreshAgentPresets(report = false)
        refreshCordis(report = false)
        startWorkspaceStream()
    }

    suspend fun refreshAfterWebView() {
        refreshAgentPresets(report = false)
        refreshCordis(report = false)
        startWorkspaceStream()
    }

    /** Reattach the one workspace subscription after an explicit runtime Continue. */
    suspend fun refreshWorkspaceStreamOnly() {
        startWorkspaceStream()
    }

    suspend fun refreshCordisAfterSessionChange() {
        refreshCordis(report = false)
    }

    suspend fun dispatch(action: NativeHarnessUiAction) {
        when (action) {
            NativeHarnessUiAction.RefreshAgentPresets -> refreshAgentPresets(report = true)
            is NativeHarnessUiAction.ReadAgentPreset -> readAgentPreset(action.presetId)
            is NativeHarnessUiAction.DuplicateAgentPreset -> duplicateAgentPreset(action)
            is NativeHarnessUiAction.DeleteAgentPreset -> deleteAgentPreset(action.presetId)
            is NativeHarnessUiAction.SetAgentPresetDefault -> setAgentPresetDefault(action.presetId)
            is NativeHarnessUiAction.SetAgentPresetModeSelection ->
                setAgentPresetModeSelection(action.enabled)
            is NativeHarnessUiAction.SelectAgentPreset -> selectAgentPreset(action)
            NativeHarnessUiAction.RefreshWorkspaces -> refreshWorkspaceStream()
            is NativeHarnessUiAction.RenameWorkspace -> renameWorkspace(action)
            is NativeHarnessUiAction.DeleteWorkspace -> deleteWorkspace(action.workspaceId)
            is NativeHarnessUiAction.MoveWorkspace -> moveWorkspace(action)
            is NativeHarnessUiAction.MoveSessionInWorkspace -> moveSession(action)
            is NativeHarnessUiAction.ExportSessionLog -> handleExternalAction(action)
            NativeHarnessUiAction.RefreshCordis -> refreshCordis(report = true)
            is NativeHarnessUiAction.RunCordis -> runCordis(action)
            is NativeHarnessUiAction.ApproveCordis -> approveCordis(action)
            is NativeHarnessUiAction.RejectCordis -> rejectCordis(action)
            is NativeHarnessUiAction.StopCordis -> cordisCall(
                "stopFromPanel",
                buildJsonObject {
                    put("agentId", selectedSessionProvider().orEmpty())
                    put("pluginId", action.pluginId)
                }
            )
            is NativeHarnessUiAction.RemoveCordis -> cordisCall(
                "undefineFromPanel",
                buildJsonObject {
                    put("agentId", selectedSessionProvider().orEmpty())
                    put("pluginId", action.pluginId)
                }
            )
            is NativeHarnessUiAction.InspectCordis -> inspectCordis(action.pluginId)
            else -> Unit
        }
    }

    override fun close() {
        workspaceJob?.cancel()
        workspaceJob = null
    }

    private suspend fun refreshAgentPresets(report: Boolean) {
        mutateState { it.copy(agentPresets = it.agentPresets.copy(isLoading = true)) }
        val result = call("agentPresets", "list", policy = HarnessCallPolicy.SafeRead)
        when (result) {
            null -> mutateState { it.copy(agentPresets = it.agentPresets.copy(isLoading = false)) }
            is HarnessRpcResult.Failure -> {
                mutateState { it.copy(agentPresets = it.agentPresets.copy(isLoading = false)) }
                if (report) reportFailure(result.error.code, result.error.message)
            }
            is HarnessRpcResult.Success -> {
                val roster = result.value.jsonObjectOrNull()
                val presets = roster?.objectArray("presets").orEmpty().mapNotNull { row ->
                    val id = row.string("id") ?: return@mapNotNull null
                    HarnessAgentPresetUi(
                        id = id,
                        trust = row.string("trust") ?: "user",
                        isDefault = row.boolean("isDefault").orDefault(false),
                        name = row.string("name"),
                        description = row.string("description"),
                        broken = row.string("broken")
                    )
                }
                mutateState { current ->
                    current.copy(agentPresets = current.agentPresets.copy(
                        presets = presets,
                        authorable = roster?.boolean("authorable").orDefault(false),
                        modeSelectionEnabled = roster?.boolean("modeSelectionEnabled").orDefault(false),
                        isLoading = false
                    ))
                }
            }
        }
    }

    private suspend fun readAgentPreset(presetId: String) {
        if (presetId.isBlank()) return
        when (val result = call(
            "agentPresets",
            "read",
            buildJsonObject { put("agentPreset", presetId) },
            HarnessCallPolicy.SafeRead
        )) {
            null -> Unit
            is HarnessRpcResult.Failure -> reportFailure(result.error.code, result.error.message)
            is HarnessRpcResult.Success -> {
                val value = result.value.jsonObjectOrNull()
                val content = value?.string("content")?.take(MAX_PRESET_PREVIEW_CHARS)
                mutateState { current ->
                    current.copy(agentPresets = current.agentPresets.copy(
                        selectedPresetId = presetId,
                        documentPreview = content
                    ))
                }
            }
        }
    }

    private suspend fun duplicateAgentPreset(action: NativeHarnessUiAction.DuplicateAgentPreset) {
        if (action.fromPresetId.isBlank() || action.presetId.isBlank()) return
        val args = buildJsonObject {
            put("from", action.fromPresetId)
            put("id", action.presetId)
            action.name?.trim()?.takeIf(String::isNotEmpty)?.let { put("name", it) }
        }
        when (val result = call("agentPresets", "copy", args)) {
            null -> mutateState { it.copy(extensions = it.extensions.copy(
                cordis = it.extensions.cordis.copy(isLoading = false)
            )) }
            is HarnessRpcResult.Failure -> reportFailure(result.error.code, result.error.message)
            is HarnessRpcResult.Success -> refreshAgentPresets(report = false)
        }
    }

    private suspend fun deleteAgentPreset(presetId: String) {
        if (presetId.isBlank()) return
        when (val result = call(
            "agentPresets",
            "deletePreset",
            buildJsonObject { put("id", presetId) }
        )) {
            null -> Unit
            is HarnessRpcResult.Failure -> reportFailure(result.error.code, result.error.message)
            is HarnessRpcResult.Success -> {
                mutateState { current ->
                    current.copy(agentPresets = current.agentPresets.copy(
                        selectedPresetId = current.agentPresets.selectedPresetId.takeUnless { it == presetId },
                        documentPreview = current.agentPresets.documentPreview.takeUnless {
                            current.agentPresets.selectedPresetId == presetId
                        }
                    ))
                }
                refreshAgentPresets(report = false)
            }
        }
    }

    private suspend fun setAgentPresetDefault(presetId: String) {
        if (presetId.isBlank()) return
        updatePresetSettings(buildJsonObject { put("default", presetId) })
    }

    private suspend fun setAgentPresetModeSelection(enabled: Boolean) {
        updatePresetSettings(buildJsonObject { put("modeSelectionEnabled", enabled) })
    }

    private suspend fun updatePresetSettings(patch: JsonObject) {
        when (val result = call(
            "settings",
            "update",
            buildJsonObject {
                put("ns", "agent-presets")
                put("patch", patch)
            }
        )) {
            null -> Unit
            is HarnessRpcResult.Failure -> reportFailure(result.error.code, result.error.message)
            is HarnessRpcResult.Success -> refreshAgentPresets(report = false)
        }
    }

    private suspend fun selectAgentPreset(action: NativeHarnessUiAction.SelectAgentPreset) {
        if (action.sessionId.isBlank() || action.presetId.isBlank()) return
        when (val result = call(
            "agentPresets",
            "select",
            buildJsonObject {
                put("agentId", action.sessionId)
                put("agentPreset", action.presetId)
            }
        )) {
            null -> Unit
            is HarnessRpcResult.Failure -> reportFailure(result.error.code, result.error.message)
            is HarnessRpcResult.Success -> {
                refreshAgentPresets(report = false)
                refreshSessions()
            }
        }
    }

    private suspend fun startWorkspaceStream() {
        workspaceStreamLock.withLock {
            val client = clientProvider()
            workspaceJob?.cancelAndJoin()
            workspaceJob = null
            if (client == null) {
                mutateState { current -> current.copy(workspaceManager = current.workspaceManager.copy(isLoading = false)) }
                return@withLock
            }
            mutateState { it.copy(workspaceManager = it.workspaceManager.copy(isLoading = true)) }
            workspaceJob = scope.launch(Dispatchers.IO) {
                try {
                    client.stream(
                        "workspace",
                        "follow",
                        policy = HarnessStreamPolicy.Default
                    ).collect { frame -> applyWorkspaceFrame(frame) }
                } catch (error: Throwable) {
                    if (error !is CancellationException) {
                        reportFailure(
                            "WORKSPACE_STREAM_FAILED",
                            "Workspace groups could not be refreshed"
                        )
                    }
                }
            }
        }
    }

    private suspend fun refreshWorkspaceStream() {
        startWorkspaceStream()
        refreshSessions()
    }

    private suspend fun applyWorkspaceFrame(frame: JsonElement) {
        val value = frame.jsonObjectOrNull() ?: return
        when (value.string("type")) {
            "baseline" -> {
                val baseline = value.objectValue("value") ?: return
                val rows = baseline.objectArray("items").mapNotNull(::parseWorkspace)
                rows.forEach { reconcileWorkspace(it) }
                mutateState { current ->
                    current.copy(workspaceManager = current.workspaceManager.copy(
                        workspaces = rows,
                        archivedSessionIds = baseline.stringArray("archivedSessionIds").toSet(),
                        isLoading = false
                    ))
                }
            }
            "upsert" -> {
                val row = value.objectValue("workspace")?.let(::parseWorkspace) ?: return
                reconcileWorkspace(row)
                mutateState { current ->
                    val previous = current.workspaceManager.workspaces
                    val existingIndex = previous.indexOfFirst { it.workspaceId == row.workspaceId }
                    val rows = if (existingIndex < 0) {
                        previous + row
                    } else {
                        previous.toMutableList().also { it[existingIndex] = row }
                    }
                    current.copy(workspaceManager = current.workspaceManager.copy(
                        workspaces = rows,
                        isLoading = false
                    ))
                }
            }
            "remove" -> {
                val id = value.string("workspaceId") ?: return
                mutateState { current ->
                    current.copy(workspaceManager = current.workspaceManager.copy(
                        workspaces = current.workspaceManager.workspaces.filterNot { it.workspaceId == id },
                        isLoading = false
                    ))
                }
            }
            "order" -> {
                val order = value.stringArray("workspaceIds")
                mutateState { current ->
                    val rows = current.workspaceManager.workspaces
                    val byId = rows.associateBy { it.workspaceId }
                    current.copy(workspaceManager = current.workspaceManager.copy(
                        workspaces = order.mapNotNull(byId::get) + rows.filterNot { order.contains(it.workspaceId) },
                        isLoading = false
                    ))
                }
            }
            "archived" -> {
                mutateState { current ->
                    current.copy(workspaceManager = current.workspaceManager.copy(
                        archivedSessionIds = value.stringArray("archivedSessionIds").toSet(),
                        isLoading = false
                    ))
                }
            }
        }
    }

    private suspend fun reconcileWorkspace(row: HarnessWorkspaceGroupUi) {
        try {
            reconcileWorkspaceGroup(row)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            // A malformed or stale remote group must not tear down the workspace stream.
        }
    }

    private fun parseWorkspace(row: JsonObject): HarnessWorkspaceGroupUi? {
        val id = row.string("workspaceId") ?: return null
        return HarnessWorkspaceGroupUi(
            workspaceId = id,
            path = row.string("path").orEmpty(),
            title = row.string("title") ?: id,
            sessionIds = row.stringArray("sessionIds"),
            createdAt = row.string("createdAt"),
            updatedAt = row.string("updatedAt")
        )
    }

    private suspend fun renameWorkspace(action: NativeHarnessUiAction.RenameWorkspace) {
        if (action.workspaceId.isBlank() || action.title.isBlank()) return
        workspaceMutation("rename", buildJsonObject {
            putJsonObject("request") {
                put("workspaceId", action.workspaceId)
                put("title", action.title.trim())
            }
        })
    }

    private suspend fun deleteWorkspace(workspaceId: String) {
        if (workspaceId.isBlank()) return
        workspaceMutation("delete", buildJsonObject {
            putJsonObject("request") { put("workspaceId", workspaceId) }
        })
    }

    private suspend fun moveWorkspace(action: NativeHarnessUiAction.MoveWorkspace) {
        if (action.workspaceId.isBlank()) return
        workspaceMutation("insertBefore", buildJsonObject {
            putJsonObject("request") {
                put("workspaceId", action.workspaceId)
                action.beforeWorkspaceId?.let { put("beforeWorkspaceId", it) }
            }
        })
    }

    private suspend fun moveSession(action: NativeHarnessUiAction.MoveSessionInWorkspace) {
        if (action.workspaceId.isBlank() || action.sessionId.isBlank()) return
        workspaceMutation("insertSessionBefore", buildJsonObject {
            putJsonObject("request") {
                put("workspaceId", action.workspaceId)
                put("sessionId", action.sessionId)
                action.beforeSessionId?.let { put("beforeSessionId", it) }
            }
        })
    }

    private suspend fun workspaceMutation(method: String, args: JsonObject) {
        when (val result = call("workspace", method, args)) {
            null -> Unit
            is HarnessRpcResult.Failure -> reportFailure(result.error.code, result.error.message)
            is HarnessRpcResult.Success -> {
                startWorkspaceStream()
                refreshSessions()
            }
        }
    }

    private suspend fun refreshCordis(report: Boolean) {
        val selected = selectedSessionProvider()
        if (selected.isNullOrBlank()) {
            mutateState { it.copy(extensions = it.extensions.copy(cordis = HarnessCordisUiState())) }
            return
        }
        mutateState { it.copy(extensions = it.extensions.copy(
            cordis = it.extensions.cordis.copy(isLoading = true)
        )) }
        when (val result = call(
            "dynamicCordisRunner",
            "inventory",
            policy = HarnessCallPolicy.SafeRead
        )) {
            null -> mutateState { it.copy(extensions = it.extensions.copy(
                cordis = it.extensions.cordis.copy(isLoading = false)
            )) }
            is HarnessRpcResult.Failure -> {
                mutateState { it.copy(extensions = it.extensions.copy(
                    cordis = it.extensions.cordis.copy(isLoading = false)
                )) }
                if (report) reportFailure(result.error.code, result.error.message)
            }
            is HarnessRpcResult.Success -> {
                val rows = result.value.objectArrayOrSelf()
                    .mapNotNull(::parseCordisPlugin)
                    .filter { it.agentId == selected }
                mutateState { current ->
                    current.copy(extensions = current.extensions.copy(
                        cordis = current.extensions.cordis.copy(plugins = rows, isLoading = false)
                    ))
                }
            }
        }
    }

    private fun parseCordisPlugin(row: JsonObject): HarnessCordisPluginUi? {
        val pluginId = row.string("pluginId") ?: return null
        val packages = row.objectArray("packages").mapNotNull { item ->
            val packageId = item.string("packageId") ?: return@mapNotNull null
            HarnessCordisPackageUi(
                packageId = packageId,
                name = item.string("name") ?: packageId,
                purpose = item.string("purpose").orEmpty(),
                hasHostHalf = item.boolean("hasHostHalf").orDefault(false),
                hasClientHalf = item.boolean("hasClientHalf").orDefault(false)
            )
        }
        val latest = row.objectValue("latestRun")?.let(::parseCordisRun)
        return HarnessCordisPluginUi(
            pluginId = pluginId,
            agentId = row.string("agentId").orEmpty(),
            packages = packages,
            currentPackageId = row.string("currentPackageId"),
            nextPackageId = row.string("nextPackageId"),
            activePackageId = row.objectValue("activeRun")?.string("packageId"),
            latestRun = latest
        )
    }

    private fun parseCordisRun(row: JsonObject): HarnessCordisRunUi? {
        val runId = row.string("pluginRunId") ?: return null
        return HarnessCordisRunUi(
            pluginRunId = runId,
            packageId = row.string("packageId").orEmpty(),
            mode = row.string("mode") ?: "run",
            status = row.string("status") ?: "unknown",
            approvalRequestId = row.string("approvalRequestId"),
            requiresApproval = row.boolean("requiresApproval").orDefault(false),
            error = row.objectValue("error")?.string("message")
        )
    }

    private suspend fun runCordis(action: NativeHarnessUiAction.RunCordis) {
        cordisCall("runHostHalf", buildCordisRunArgs(
            pluginId = action.pluginId,
            packageId = action.packageId,
            update = action.update,
            requestId = null,
            approveFutureVersions = false
        ))
    }

    private suspend fun approveCordis(action: NativeHarnessUiAction.ApproveCordis) {
        cordisCall("runHostHalf", buildCordisRunArgs(
            pluginId = action.pluginId,
            packageId = action.packageId,
            update = action.update,
            requestId = action.requestId,
            approveFutureVersions = action.approveFutureVersions
        ))
    }

    private suspend fun rejectCordis(action: NativeHarnessUiAction.RejectCordis) {
        val resolution = buildJsonObject {
            put("ok", false)
            put("reason", "rejected")
            action.pluginRunId?.let { put("pluginRunId", it) }
        }
        cordisCall("resolveRequestRun", buildJsonObject {
            put("requestId", action.requestId)
            put("resolution", resolution)
        })
    }

    private fun buildCordisRunArgs(
        pluginId: String,
        packageId: String,
        update: Boolean,
        requestId: String?,
        approveFutureVersions: Boolean
    ): JsonObject = buildJsonObject {
        put("agentId", selectedSessionProvider().orEmpty())
        put("pluginId", pluginId)
        put("packageId", packageId)
        put("mode", if (update) "update" else "run")
        if (requestId == null) put("requestId", JsonNull) else put("requestId", requestId)
        put("approveFutureVersions", approveFutureVersions)
    }

    private suspend fun inspectCordis(pluginId: String) {
        refreshCordis(report = true)
        mutateState { current ->
            current.copy(extensions = current.extensions.copy(
                cordis = current.extensions.cordis.copy(inspectedPluginId = pluginId)
            ))
        }
    }

    private suspend fun cordisCall(method: String, args: JsonObject) {
        when (val result = call("dynamicCordisRunner", method, args)) {
            null -> Unit
            is HarnessRpcResult.Failure -> reportFailure(result.error.code, result.error.message)
            is HarnessRpcResult.Success -> refreshCordis(report = false)
        }
    }

    private suspend fun call(
        namespace: String,
        method: String,
        args: JsonObject = buildJsonObject {},
        policy: HarnessCallPolicy = HarnessCallPolicy.NoRetry
    ): HarnessRpcResult? {
        val client = clientProvider()
        if (client == null) {
            reportFailure("HARNESS_UNAVAILABLE", "The Harness runtime is unavailable")
            return null
        }
        return client.call(namespace, method, args, policy)
    }

    private companion object {
        const val MAX_PRESET_PREVIEW_CHARS = 4_000
    }
}
