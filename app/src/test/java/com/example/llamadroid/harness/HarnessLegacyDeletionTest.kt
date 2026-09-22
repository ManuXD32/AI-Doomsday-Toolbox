package com.example.llamadroid.harness

import com.example.llamadroid.data.db.AgentConversationEntity
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files

class HarnessLegacyDeletionTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun localBackendsAndSanitizedAliasesShareTheSameFiles() {
        val legacy = AgentConversationEntity(projectFolder = "same.project", workspaceBackend = "LOCAL_SANDBOX")
        val harness = legacy.copy(projectFolder = "same_project", workspaceBackend = "LOCAL_PROOT")
        assertTrue(HarnessLegacyDeletion.sharesFiles(legacy, harness))
        assertFalse(HarnessLegacyDeletion.sharesFiles(legacy, harness.copy(projectFolder = "other")))
        assertFalse(HarnessLegacyDeletion.sharesFiles(legacy, harness.copy(workspaceBackend = "REMOTE_SSH")))
    }

    @Test fun quarantinedCleanupNeverFollowsSymlinksToOtherProjects() {
        val other = temporary.newFolder("other").resolve("keep.txt").also { it.writeText("preserved") }
        val trash = temporary.newFolder("quarantine")
        trash.resolve("owned.txt").writeText("removed")
        Files.createSymbolicLink(trash.resolve("outside").toPath(), other.parentFile.toPath())
        HarnessLegacyDeletion.deleteQuarantinedTree(trash)
        assertFalse(trash.exists())
        assertEquals("preserved", other.readText())
    }
}
