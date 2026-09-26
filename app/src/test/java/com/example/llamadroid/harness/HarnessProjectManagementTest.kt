package com.example.llamadroid.harness

import com.example.llamadroid.data.db.AgentConversationEntity
import com.example.llamadroid.data.db.HarnessWorkspaceEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class HarnessProjectManagementTest {
    @Test
    fun localSandboxAndProotAliasesShareIdentityWithoutMovingTheirFolder() {
        val sandbox = workspace("LOCAL_SANDBOX", "demo.project", "sandbox")
        val proot = workspace("LOCAL_PROOT", "demo_project", "proot")
        assertTrue(HarnessProjectManagementRules.sameWorkspace(sandbox, proot))
        assertTrue(HarnessProjectManagementRules.sameConversation(sandbox,
            AgentConversationEntity(projectFolder = "demo_project", workspaceBackend = "LOCAL_PROOT")))
        assertEquals(
            HarnessProjectManagementRules.key(sandbox.backend, sandbox.projectFolder),
            HarnessProjectManagementRules.key(proot.backend, proot.projectFolder),
        )
    }

    @Test
    fun remoteAliasesAreScopedToTheConnectionEvenWhenFolderNamesMatch() {
        val first = workspace("REMOTE_SSH", "repo", "host-a")
        val second = workspace("REMOTE_SSH", "repo", "host-b")
        assertFalse(HarnessProjectManagementRules.sameWorkspace(first, second))
        assertNotEquals(
            HarnessProjectManagementRules.key(first.backend, first.projectFolder, first.connectionKey),
            HarnessProjectManagementRules.key(second.backend, second.projectFolder, second.connectionKey),
        )
        assertFalse(HarnessProjectManagementRules.fileDeletionSupported(first.backend))
    }

    @Test
    fun tombstoneRoundTripPreservesPendingRecoveryAndOnlyMetadata() {
        val pending = HarnessProjectTombstone(
            projectId = "workspace-1",
            title = "Demo",
            projectFolder = "demo_project",
            backend = "LOCAL",
            connectionKey = "",
            workspaceIds = setOf("workspace-1", "workspace-alias"),
            sessionIds = setOf("session-1"),
            deleteFiles = true,
            state = "QUARANTINED",
            quarantineName = "project-" + "a".repeat(64),
            errorCode = "PROJECT_CLEANUP_PENDING",
        )
        val restored = requireNotNull(HarnessProjectTombstone.fromJson(pending.toJson()))
        assertEquals(pending, restored)
        assertTrue(restored.pending)
        assertTrue(restored.sessionIds.contains("session-1"))
        assertFalse(restored.toJson().has("prompt"))
    }

    @Test
    fun removedTombstoneDoesNotLookPendingButStillMatchesItsAliases() {
        val removed = HarnessProjectTombstone(
            projectId = "workspace-1", title = "Renamed", projectFolder = "demo_project",
            backend = "LOCAL", connectionKey = "", workspaceIds = setOf("workspace-1"),
            sessionIds = emptySet(), deleteFiles = false, state = "REMOVED",
        )
        assertFalse(removed.pending)
        assertTrue(removed.workspaceIds.contains("workspace-1"))
    }

    @Test
    fun displayRenameTrimsOnlyPresentationTextAndRejectsUnsafeValues() {
        assertEquals("New name", HarnessProjectManagementRules.normalizeTitle("  New name  "))
        assertThrows(IllegalArgumentException::class.java) {
            HarnessProjectManagementRules.normalizeTitle(" ")
        }
        assertThrows(IllegalArgumentException::class.java) {
            HarnessProjectManagementRules.normalizeTitle("x".repeat(121))
        }
        assertThrows(IllegalArgumentException::class.java) {
            HarnessProjectManagementRules.normalizeTitle("bad\u0000name")
        }
    }

    @Test
    fun sessionPathMustStayInsideItsCanonicalWorkspaceRoot() {
        assertTrue(harnessPathContains("/workspace/projects/demo", "/workspace/projects/demo"))
        assertTrue(harnessPathContains("/workspace/projects/demo", "/workspace/projects/demo/src"))
        assertFalse(harnessPathContains("/workspace/projects/demo", "/workspace/projects/demo-old"))
        assertFalse(harnessPathContains("/workspace/projects/demo", "/workspace/projects/other"))
    }

    private fun workspace(backend: String, folder: String, connection: String) = HarnessWorkspaceEntity(
        id = connection,
        backend = backend,
        projectFolder = folder,
        connectionKey = connection,
        title = "Demo",
        guestPath = if (backend == "REMOTE_SSH") "/workspace/remote/$connection" else "/workspace/projects/$folder",
    )
}
