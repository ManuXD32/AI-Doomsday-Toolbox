package com.example.llamadroid.ui.agent

import com.example.llamadroid.R
import com.example.llamadroid.service.AgentWorkspaceBackendType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentWorkspaceBackendPresentationTest {
    @Test
    fun `local Debian is never projected as remote SSH`() {
        val presentation = agentWorkspaceRunBackendPresentation(AgentWorkspaceBackendType.LOCAL_PROOT)
        assertEquals(R.string.agent_proot_backend_label, presentation.labelRes)
        assertEquals(AgentWorkspaceRootPresentation.DEBIAN, presentation.root)
        assertTrue(presentation.supportsProjectRun)
        assertFalse(presentation.showsSandboxDependencyPolicy)
    }

    @Test
    fun `sandbox and remote retain their distinct capabilities`() {
        val sandbox = agentWorkspaceRunBackendPresentation(AgentWorkspaceBackendType.LOCAL_SANDBOX)
        val remote = agentWorkspaceRunBackendPresentation(AgentWorkspaceBackendType.REMOTE_SSH)
        assertTrue(sandbox.supportsProjectRun)
        assertTrue(sandbox.showsSandboxDependencyPolicy)
        assertFalse(remote.supportsProjectRun)
        assertEquals(AgentWorkspaceRootPresentation.REMOTE, remote.root)
    }
}
