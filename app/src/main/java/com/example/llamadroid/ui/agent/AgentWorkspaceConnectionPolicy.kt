package com.example.llamadroid.ui.agent

import com.example.llamadroid.service.AgentWorkspaceBackendType

/**
 * Keeps SSH UI tied to the workspace that actually owns an SSH connection.
 * Model/backend health is intentionally outside this policy: a local sandbox
 * can still show llama-server state without being described as disconnected.
 */
data class AgentWorkspaceConnectionVisibility(
    val showConnectionStatus: Boolean,
    val showSshWarning: Boolean
)

fun agentWorkspaceConnectionVisibility(
    backend: AgentWorkspaceBackendType?,
    isSshConnected: Boolean,
    isSshConnecting: Boolean
): AgentWorkspaceConnectionVisibility {
    val remote = backend == AgentWorkspaceBackendType.REMOTE_SSH
    return AgentWorkspaceConnectionVisibility(
        showConnectionStatus = remote,
        showSshWarning = remote && !isSshConnected && !isSshConnecting
    )
}
