package com.example.llamadroid.ui.agent

import com.example.llamadroid.data.db.AiRuntimeJobEntity
import com.example.llamadroid.service.AgentLocalWorkspaceSupport
import com.example.llamadroid.service.AgentService
import com.example.llamadroid.service.AgentWorkspaceBackendType
import com.example.llamadroid.service.AiRuntimeJobStore

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
