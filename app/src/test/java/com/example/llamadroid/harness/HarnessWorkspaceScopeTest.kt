package com.example.llamadroid.harness

import com.example.llamadroid.data.db.AgentConversationEntity
import com.example.llamadroid.data.db.HarnessSessionEntity
import com.example.llamadroid.data.db.HarnessWorkspaceEntity
import com.example.llamadroid.harness.HarnessWorkspaceAccess.Companion.readBounded
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files

class HarnessWorkspaceScopeTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun scope(root: File) = HarnessSessionScope(
        HarnessSessionEntity("s1", 1, "w1"),
        HarnessWorkspaceEntity(id = "w1", backend = "LOCAL_PROOT", projectFolder = "first", title = "First", guestPath = "/workspace/projects/first"),
        AgentConversationEntity(id = 1, title = "First", projectFolder = "first"), root
    )

    @Test fun capturedWorkspaceCannotReadAnotherProjectOrFollowEscapeSymlink() {
        val root = temporary.newFolder("first")
        val outside = temporary.newFolder("second")
        File(outside, "private.txt").writeText("outside")
        Files.createSymbolicLink(File(root, "escape").toPath(), outside.toPath())
        val selected = scope(root)
        assertEquals(File(root, "inside.txt"), selected.localFile("/workspace/projects/first/inside.txt"))
        listOf("../second/private.txt", "/workspace/projects/second/private.txt", "escape/private.txt", "/etc/passwd").forEach {
            assertTrue("Must reject $it", runCatching { selected.localFile(it) }.isFailure)
        }
    }

    @Test fun boundedReadsAcceptExactLimitAndRejectGrowthBeyondReportedSize() {
        val bytes = byteArrayOf(1, 2, 3)
        assertArrayEquals(bytes, ByteArrayInputStream(bytes).readBounded(3))
        val error = runCatching { ByteArrayInputStream(bytes).readBounded(2) }.exceptionOrNull()
        assertEquals("FILE_TOO_LARGE", error?.message)
    }

    @Test fun deletingSymlinkEntryKeepsItsTargetIntact() {
        val root = temporary.newFolder("links")
        val target = File(root, "keep.txt").apply { writeText("preserved") }
        val link = File(root, "link.txt")
        Files.createSymbolicLink(link.toPath(), target.toPath())
        assertTrue(scope(root).localEntry("link.txt").delete())
        assertEquals("preserved", target.readText())
    }
}
