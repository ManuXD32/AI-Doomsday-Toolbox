package com.example.llamadroid.ui.agent

import androidx.annotation.StringRes
import com.example.llamadroid.R
import com.example.llamadroid.service.AgentWorkspaceBackendType

internal enum class AgentWorkspaceRootPresentation {
    APP_LOCAL,
    DEBIAN,
    REMOTE
}

internal data class AgentWorkspaceRunBackendPresentation(
    @StringRes val labelRes: Int,
    val root: AgentWorkspaceRootPresentation,
    val supportsProjectRun: Boolean,
    val showsSandboxDependencyPolicy: Boolean
)

internal fun agentWorkspaceRunBackendPresentation(
    backend: AgentWorkspaceBackendType
): AgentWorkspaceRunBackendPresentation = when (backend) {
    AgentWorkspaceBackendType.LOCAL_SANDBOX -> AgentWorkspaceRunBackendPresentation(
        labelRes = R.string.agent_project_backend_local,
        root = AgentWorkspaceRootPresentation.APP_LOCAL,
        supportsProjectRun = true,
        showsSandboxDependencyPolicy = true
    )
    AgentWorkspaceBackendType.LOCAL_PROOT -> AgentWorkspaceRunBackendPresentation(
        labelRes = R.string.agent_proot_backend_label,
        root = AgentWorkspaceRootPresentation.DEBIAN,
        supportsProjectRun = true,
        showsSandboxDependencyPolicy = false
    )
    AgentWorkspaceBackendType.REMOTE_SSH -> AgentWorkspaceRunBackendPresentation(
        labelRes = R.string.agent_project_backend_remote,
        root = AgentWorkspaceRootPresentation.REMOTE,
        supportsProjectRun = false,
        showsSandboxDependencyPolicy = false
    )
}
