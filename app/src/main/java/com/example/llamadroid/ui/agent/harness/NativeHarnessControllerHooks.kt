package com.example.llamadroid.ui.agent.harness

import com.example.llamadroid.harness.client.HarnessClient
import com.example.llamadroid.harness.client.HarnessRpcError
import com.example.llamadroid.harness.client.HarnessRpcResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject

internal fun capabilityUnavailableResult(code: String, message: String): HarnessRpcResult =
    HarnessRpcResult.Failure(HarnessRpcError(code, message))

/** Runtime operations supplied by the app's shared-process owner. */
data class NativeHarnessRuntimeCallbacks(
    val current: suspend () -> HarnessRuntimeUiState,
    val start: suspend () -> HarnessRuntimeUiState,
    val stop: suspend () -> HarnessRuntimeUiState,
    val forceStop: suspend () -> HarnessRuntimeUiState
)

/** Upstream session metadata used to refresh the Room presentation mirror. */
data class NativeHarnessSessionProjection(
    val title: String? = null,
    val archived: Boolean? = null
)

/** Room-backed identity available while the shared Harness process is stopped. */
data class NativeHarnessOfflineSession(
    val sessionId: String,
    val title: String,
    val cwd: String? = null,
    val archived: Boolean = false
)

/** Workspace and retained-history seams supplied by the root integration. */
data class NativeHarnessWorkspaceHooks(
    val resolveSession: suspend (
        sessionId: String,
        title: String,
        cwd: String?,
        archived: Boolean
    ) -> HarnessWorkspaceUiState? = { _, _, _, _ -> null },
    /** Reads authoritative Harness metadata after remote mutations. */
    val readSessionProjection: suspend (sessionId: String) -> NativeHarnessSessionProjection? = { null },
    /** Starts a new authoritative metadata pass before a session-list refresh. */
    val invalidateSessionProjection: suspend () -> Unit = {},
    /** Reads retained session/workspace identities without starting the runtime. */
    val readOfflineSessions: suspend () -> List<NativeHarnessOfflineSession> = { emptyList() },
    /** Filters stale native/remote rows after a project tombstone is committed. */
    val isSessionRemoved: suspend (sessionId: String, cwd: String?) -> Boolean = { _, _ -> false },
    /** Persists the authoritative DSH workspace group id for native/WebUI project parity. */
    val reconcileWorkspaceGroup: suspend (HarnessWorkspaceGroupUi) -> Unit = {},
    val createSessionArgs: suspend () -> JsonObject = { buildJsonObject {} },
    val openWorkspace: suspend (HarnessWorkspaceUiState) -> Unit = { _ -> },
    val openLegacyHistory: suspend () -> Unit = { },
    val openAttachment: suspend (sessionId: String, attachmentId: String) -> Unit = { _, _ -> }
)

/** Navigation seams that must remain outside the native Harness surface. */
data class NativeHarnessNavigationHooks(
    val openOriginalWebUi: suspend () -> Unit = {},
    val openModelManager: suspend () -> Unit = {}
)

/** Optional host overrides for the alpha2 Remote Event waterfalls. */
data class NativeHarnessInteractionHooks(
    val answerQuestion: suspend (questionId: String, answer: String) -> HarnessRpcResult = { _: String, _: String ->
        capabilityUnavailableResult("INTERACTION_BRIDGE_UNAVAILABLE", "The Harness question event bridge is not connected")
    },
    val decideApproval: suspend (approvalId: String, approved: Boolean) -> HarnessRpcResult = { _: String, _: Boolean ->
        capabilityUnavailableResult("INTERACTION_BRIDGE_UNAVAILABLE", "The Harness approval event bridge is not connected")
    },
    val decidePlan: suspend (planId: String, approved: Boolean) -> HarnessRpcResult = { _: String, _: Boolean ->
        capabilityUnavailableResult("INTERACTION_BRIDGE_UNAVAILABLE", "The Harness plan event bridge is not connected")
    }
)

/** Host-owned job operations retained by the app's existing tool services. */
data class NativeHarnessJobHooks(
    val canRead: Boolean = false,
    val canKill: Boolean = false,
    val read: suspend (jobId: String) -> HarnessRpcResult = { jobId ->
        capabilityUnavailableResult("JOB_READ_UNAVAILABLE", "Job details are not exposed by this Harness release: $jobId")
    },
    val kill: suspend (jobId: String) -> HarnessRpcResult = { jobId ->
        capabilityUnavailableResult("JOB_KILL_UNAVAILABLE", "Job control is not exposed by this Harness release: $jobId")
    }
)
