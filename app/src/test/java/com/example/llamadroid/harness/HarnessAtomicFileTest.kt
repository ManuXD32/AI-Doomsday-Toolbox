package com.example.llamadroid.harness

import com.example.llamadroid.data.db.AgentConversationEntity
import com.example.llamadroid.data.db.HarnessSessionEntity
import com.example.llamadroid.data.db.HarnessWorkspaceEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

class HarnessAtomicFileTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun scope(root: File) = HarnessSessionScope(
        HarnessSessionEntity("s1", 1, "w1"),
        HarnessWorkspaceEntity(
            id = "w1", backend = "LOCAL_PROOT", projectFolder = "first",
            title = "First", guestPath = "/workspace/projects/first"
        ),
        AgentConversationEntity(id = 1, title = "First", projectFolder = "first"), root
    )

    @Test fun atomicPathsStayWithinCapturedProjectAndRejectSymlinkParents() {
        val root = temporary.newFolder("project")
        val stage = File(root, ".tmpdir").apply { mkdirs() }
        File(stage, "payload.tmp").writeText("payload")
        val paths = resolveHarnessAtomicPublicationPaths(
            scope(root), "/workspace/projects/first/.tmpdir/payload.tmp",
            "/workspace/projects/first/result.txt"
        )
        assertEquals(stage.toPath(), paths.source.parent)
        assertEquals(root.toPath(), paths.destination.parent)

        val outside = temporary.newFolder("outside")
        assertTrue(runCatching {
            resolveHarnessAtomicPublicationPaths(
                scope(root), outside.resolve("payload.tmp").path, "/workspace/projects/first/result.txt"
            )
        }.exceptionOrNull()?.message == "ATOMIC_PATH_OUTSIDE_SCOPE")

        val linkedParent = File(root, "linked").apply { Files.createSymbolicLink(toPath(), outside.toPath()) }
        File(outside, "payload.tmp").writeText("payload")
        assertEquals("ATOMIC_PARENT_SYMLINK", runCatching {
            resolveHarnessAtomicPublicationPaths(
                scope(root), "/workspace/projects/first/linked/payload.tmp",
                "/workspace/projects/first/linked/result.txt"
            )
        }.exceptionOrNull()?.message)
    }

    @Test fun atomicPathsRejectSymlinkSourceAndDestination() {
        val root = temporary.newFolder("project")
        val target = File(root, "target.txt").apply { writeText("owned") }
        Files.createSymbolicLink(File(root, "source.tmp").toPath(), target.toPath())
        assertEquals("ATOMIC_SOURCE_SYMLINK", runCatching {
            resolveHarnessAtomicPublicationPaths(
                scope(root), "/workspace/projects/first/source.tmp", "/workspace/projects/first/dest.txt"
            )
        }.exceptionOrNull()?.message)

        File(root, "source.tmp").delete()
        File(root, "source.tmp").writeText("payload")
        Files.createSymbolicLink(File(root, "dest.txt").toPath(), target.toPath())
        assertEquals("ATOMIC_DESTINATION_SYMLINK", runCatching {
            resolveHarnessAtomicPublicationPaths(
                scope(root), "/workspace/projects/first/source.tmp", "/workspace/projects/first/dest.txt"
            )
        }.exceptionOrNull()?.message)
    }

    @Test fun legacyLocalSandboxUsesTheSameCapturedRootScope() {
        val root = temporary.newFolder("sandbox-project")
        File(root, "stage.tmp").writeText("payload")
        val sandbox = scope(root).copy(workspace = scope(root).workspace.copy(backend = "LOCAL_SANDBOX"))
        val paths = resolveHarnessAtomicPublicationPaths(
            sandbox, "/workspace/projects/first/stage.tmp", "/workspace/projects/first/result.txt"
        )
        assertEquals(root.toPath(), paths.destination.parent)
    }
}
