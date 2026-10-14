package com.example.llamadroid.harness

import com.example.llamadroid.data.db.HarnessWorkspaceEntity
import com.example.llamadroid.harness.client.HarnessRpcResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Capture the project identity before an asynchronous session create. Shared by the Harness
 * screen and the headless DeepSeek delegation driver.
 */
internal suspend fun prepareHarnessProject(runtime: HarnessAppRuntime, selected: HarnessWorkspaceEntity): JsonObject {
    val client = requireNotNull(runtime.client)
    val result = client.prepareWorkspace(buildJsonObject {
        put("workspaceId", selected.id); put("guestPath", selected.guestPath); put("backend", selected.backend)
    })
    val prepared = when (result) {
        is HarnessRpcResult.Failure -> error(result.error.code)
        is HarnessRpcResult.Success -> parseHarnessPreparedWorkspace(result.value)
            ?: error("WORKSPACE_IDENTITY_INVALID")
    }
    require(prepared.guestPath == normalizeHarnessGuestPath(selected.guestPath)) {
        "WORKSPACE_IDENTITY_MISMATCH"
    }
    // The single authenticated prepare route validates Android's project, registers its DSH
    // group and persists their mapping before session/create. A failed registration must
    // surface here rather than create an ungrouped chat.
    requireNotNull(runtime.workspaces.persistPreparedWorkspace(selected.id, prepared)) {
        "WORKSPACE_NOT_FOUND"
    }
    return harnessSessionCreateLocation(selected.guestPath, prepared)
}
