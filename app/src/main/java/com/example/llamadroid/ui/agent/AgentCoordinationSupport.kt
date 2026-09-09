package com.example.llamadroid.ui.agent

import com.example.llamadroid.data.db.AiRuntimeJobEntity
import com.example.llamadroid.service.AgentLocalWorkspaceSupport
import com.example.llamadroid.service.AgentService
import com.example.llamadroid.service.AgentWorkspaceBackendType
import com.example.llamadroid.service.AiRuntimeJobStore
import java.util.Locale

internal fun newestConversationIdExcluding(
    conversationIds: List<Long>,
    excludedId: Long? = null
): Long? = conversationIds.firstOrNull { it != excludedId }

internal fun shouldAutosaveConversationSnapshot(
    runtimeConversationId: Long?,
    activeConversationId: Long?,
    isConversationRestoring: Boolean,
    messagesEmpty: Boolean,
    hasStreamingMessages: Boolean,
    targetConversationId: Long
): Boolean {
    if (runtimeConversationId == null) return false
    if (isConversationRestoring || messagesEmpty || hasStreamingMessages) return false
    if (runtimeConversationId != targetConversationId) return false
    if (activeConversationId != targetConversationId) return false
    return true
}

internal fun shouldPreferLiveRuntimeMessages(
    selectedConversationId: Long?,
    runtimeConversationId: Long?,
    activeConversationId: Long?,
    showConversationLoading: Boolean,
    liveMessagesEmpty: Boolean
): Boolean {
    if (selectedConversationId == null) return !liveMessagesEmpty
    if (showConversationLoading) return false
    if (liveMessagesEmpty) return false
    if (activeConversationId == selectedConversationId) return true
    return runtimeConversationId == selectedConversationId
}

internal fun shouldAttachToLiveConversationRuntime(
    targetConversationId: Long,
    activeConversationId: Long?,
    isLoading: Boolean,
    liveMessagesEmpty: Boolean
): Boolean {
    return activeConversationId == targetConversationId &&
        isLoading &&
        !liveMessagesEmpty
}

internal fun shouldAdoptLiveRuntimeConversation(
    selectedConversationId: Long?,
    liveConversationId: Long?,
    knownConversationIds: List<Long>
): Boolean {
    if (liveConversationId == null) return false
    if (selectedConversationId == null) return true
    if (selectedConversationId == liveConversationId) return true
    return selectedConversationId !in knownConversationIds
}

internal fun shouldSkipPersistedRuntimeRestore(
    isLoading: Boolean,
    liveMessagesEmpty: Boolean
): Boolean = isLoading && !liveMessagesEmpty

/**
 * Keeps a same-process live conversation from being replaced by its older Room checkpoint.
 * A process generation is written to runtime checkpoints, so a matching generation proves
 * that the checkpoint belongs to this process; missing or different generations remain eligible
 * for cold-process restoration.
 */
internal fun shouldSkipSameProcessRuntimeRestore(
    selectedConversationId: Long?,
    liveConversationId: Long?,
    jobConversationId: Long?,
    liveMessagesEmpty: Boolean,
    checkpointProcessGeneration: String?,
    currentProcessGeneration: String,
    checkpointConversationId: Long? = null
): Boolean {
    if (liveMessagesEmpty) return false
    if (selectedConversationId == null ||
        liveConversationId == null ||
        jobConversationId == null
    ) {
        return false
    }
    if (selectedConversationId != liveConversationId ||
        selectedConversationId != jobConversationId
    ) {
        return false
    }
    if (checkpointProcessGeneration.isNullOrBlank() || currentProcessGeneration.isBlank()) {
        return false
    }
    if (checkpointProcessGeneration != currentProcessGeneration) return false
    if (checkpointConversationId != null && checkpointConversationId != selectedConversationId) {
        return false
    }
    return true
}

/**
 * Durable evidence used when a process disappears without leaving a live
 * AgentService owner. The conversation resume flag is a UI projection and can
 * still be IDLE after a crash, so it is deliberately absent from this check.
 */
internal fun shouldSurfaceColdRecovery(
    hasLiveOwner: Boolean,
    hasOpenTurnContext: Boolean,
    hasPendingToolContinuation: Boolean,
    latestBoundaryEventType: String?,
    latestBoundaryStatus: String?,
    latestBoundaryToolName: String? = null,
    latestBoundaryTimestamp: Long? = null,
    pendingContinuationTimestamp: Long? = null
): Boolean {
    if (hasLiveOwner) return false
    if (!hasOpenTurnContext && !hasPendingToolContinuation) return false

    val eventType = latestBoundaryEventType?.trim()?.lowercase(Locale.ROOT)
    val status = latestBoundaryStatus?.trim()?.lowercase(Locale.ROOT)
    val toolName = latestBoundaryToolName?.trim()?.lowercase(Locale.ROOT)
    val pendingIsNewerThanBoundary = hasPendingToolContinuation &&
        pendingContinuationTimestamp != null &&
        (latestBoundaryTimestamp == null || pendingContinuationTimestamp > latestBoundaryTimestamp)

    // A terminal model/session boundary suppresses old ENQUEUED receipts. The
    // receipt table predates the terminal marker and some completed projects
    // therefore retain those rows indefinitely. A receipt written after the
    // boundary is newer work and remains recoverable.
    val terminalBoundary = when (eventType) {
        "agent_session_end", "agent_stop" -> true
        "generation_finished" -> status !in setOf("tool_calls", "function_call", "tool_call")
        "chat_message_assistant" -> status in setOf("ok", "completed", "done", "stopped", "cancelled", "canceled")
        else -> false
    }
    val terminalToolBoundary = eventType in setOf(
        "tool_output_prepared",
        "tool_result",
        "tool_success",
        "tool_failure",
        "tool_error"
    ) && toolName == "finish_task" && status in setOf("ok", "success", "completed")
    // finish_task itself can leave an ENQUEUED receipt after its terminal event.
    // That receipt is not authorization for another generation.
    if (terminalToolBoundary) return false
    if (terminalBoundary) {
        // A receipt created after the terminal event belongs to a newer
        // continuation. Older ENQUEUED receipts are historical residue.
        return hasPendingToolContinuation && pendingIsNewerThanBoundary
    }

    // An assistant generation event is written before the response arrives.
    // A tool-call generation also leaves work to execute even before its tool
    // boundary is journaled, so both are authoritative open-turn evidence.
    if (eventType == "chat_message_assistant" && status == "running") return true
    if (eventType == "generation_finished" && status in setOf("tool_calls", "function_call", "tool_call")) return true
    if (eventType == "tool_call") return true

    // Tool output/result events are the current continuation boundary. Their
    // receipt is normally created just before the event, so compare only for
    // terminal suppression above rather than requiring receipt > event time.
    if (hasPendingToolContinuation && eventType in setOf(
            "tool_output_prepared",
            "tool_result",
            "tool_success",
            "tool_failure",
            "tool_error"
        )
    ) {
        return true
    }

    // With no boundary row at all, an open turn plus a receipt is still the
    // only durable evidence available after process death.
    return hasPendingToolContinuation && eventType == null
}

/**
 * Projects a durable resumable conversation into the selected chat without starting work.
 * The action remains explicit because a stale resume flag must never dispatch a model turn.
 */
internal fun shouldShowSelectedConversationRecovery(
    selectedConversationId: Long?,
    conversationId: Long?,
    resumeState: String?,
    isWorking: Boolean,
    hasLiveOwner: Boolean
): Boolean {
    if (selectedConversationId == null || selectedConversationId != conversationId) return false
    if (resumeState !in setOf(
            AgentService.RESUME_STATE_INTERRUPTED,
            AgentService.RESUME_STATE_NEEDS_DIRECTION,
            AgentService.RESUME_STATE_STOPPED_BY_USER
        )) return false
    if (isWorking || hasLiveOwner) return false
    return true
}

internal fun shouldStackAgentKnowledgeHeader(fontScale: Float): Boolean = fontScale >= 1.5f

/**
 * A persisted runtime job is only a crash-recovery checkpoint. The conversation row owns the
 * project backend, so an older or malformed checkpoint must not turn a local project into SSH.
 */
internal fun resolveRestoredWorkspaceBackend(
    canonicalConversationBackend: String?,
    snapshotBackend: AgentWorkspaceBackendType
): AgentWorkspaceBackendType = AgentWorkspaceBackendType.entries.firstOrNull { backend ->
    backend.name.equals(canonicalConversationBackend.orEmpty(), ignoreCase = true)
} ?: snapshotBackend

internal fun shouldUseSelectedConversationPreview(
    selectedConversationId: Long?,
    runtimeConversationId: Long?,
    activeConversationId: Long?,
    showConversationLoading: Boolean
): Boolean {
    if (selectedConversationId == null) return false
    if (showConversationLoading) return true
    if (selectedConversationId == runtimeConversationId) return false
    return selectedConversationId != activeConversationId
}

internal fun resolveRelevantAgentRuntimeJob(
    activeRuntimeJobs: List<AiRuntimeJobEntity>,
    runtimeActiveConversationId: Long?,
    runtimeConversationId: Long?,
    selectedConversationId: Long?
): AiRuntimeJobEntity? {
    val pinnedConversationIds = listOfNotNull(
        runtimeConversationId,
        selectedConversationId
    ).distinct()
    if (pinnedConversationIds.isNotEmpty()) {
        return activeRuntimeJobs.firstOrNull { job ->
            job.type == AiRuntimeJobStore.TYPE_AGENT_CHAT && job.conversationId in pinnedConversationIds
        }
    }
    if (runtimeActiveConversationId != null) {
        return activeRuntimeJobs.firstOrNull { job ->
            job.type == AiRuntimeJobStore.TYPE_AGENT_CHAT && job.conversationId == runtimeActiveConversationId
        }
    }
    return activeRuntimeJobs.firstOrNull { it.type == AiRuntimeJobStore.TYPE_AGENT_CHAT }
}

internal fun resolveWorkspaceConversationAnchor(
    preferredConversationId: Long?,
    activeConversationId: Long?
): Long? {
    return preferredConversationId ?: activeConversationId
}

internal fun resolveWorkspaceProjectRoot(
    conversationAnchorId: Long?,
    currentProjectFolder: String?,
    backend: AgentWorkspaceBackendType = AgentWorkspaceBackendType.REMOTE_SSH
): String? {
    val projectFolder = currentProjectFolder?.takeIf { it.isNotBlank() } ?: return null
    if (conversationAnchorId == null) return null
    return if (backend == AgentWorkspaceBackendType.LOCAL_SANDBOX) {
        AgentLocalWorkspaceSupport.displayRoot(projectFolder)
    } else {
        "${AgentService.WORKSPACE_PATH}/$projectFolder"
    }
}

internal fun clampWorkspaceParentPath(
    currentPath: String,
    projectRoot: String
): String {
    if (currentPath.isBlank() || currentPath == projectRoot) return projectRoot
    val parent = currentPath.substringBeforeLast("/", missingDelimiterValue = projectRoot).ifBlank { projectRoot }
    return if (parent.length < projectRoot.length || !parent.startsWith(projectRoot)) {
        projectRoot
    } else {
        parent
    }
}
