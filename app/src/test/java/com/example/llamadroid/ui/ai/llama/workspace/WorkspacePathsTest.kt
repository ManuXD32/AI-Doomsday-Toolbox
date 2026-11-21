package com.example.llamadroid.ui.ai.llama.workspace

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspacePathsTest {
    @Test
    fun sanitizeReplacesUnsafeCharsAndFallsBack() {
        assertEquals("a_b_c", WorkspacePaths.sanitizeName("a/b:c"))
        assertEquals("file", WorkspacePaths.sanitizeName(".."))
        assertEquals("chat-7", WorkspacePaths.sanitizeName("  ", "chat-7"))
        assertEquals(80, WorkspacePaths.sanitizeName("x".repeat(200)).length)
    }

    @Test
    fun downloadFolderUsesTitleOrId() {
        assertEquals("Download/AI-Doomsday-Toolbox/My chat", WorkspacePaths.downloadFolder("My chat", 3))
        assertEquals("Download/AI-Doomsday-Toolbox/chat-3", WorkspacePaths.downloadFolder(null, 3))
    }

    @Test
    fun sanitizeRelativeDropsTraversal() {
        assertEquals("a/b/c.txt", WorkspacePaths.sanitizeRelative("a/../b/./c.txt"))
    }

    @Test
    fun guardAndExpansionStayInsideRoot() {
        val root = Files.createTempDirectory("ws").toFile().canonicalFile
        try {
            File(root, "dir/sub").mkdirs()
            File(root, "dir/a.txt").writeText("a")
            File(root, "dir/sub/b.txt").writeText("b")
            File(root, "top.txt").writeText("t")
            val outside = Files.createTempDirectory("out").toFile().canonicalFile
            File(outside, "secret.txt").writeText("s")
            try {
                assertTrue(WorkspacePaths.isInside(root, File(root, "top.txt")))
                assertFalse(WorkspacePaths.isInside(root, outside))
                assertFalse(WorkspacePaths.isStrictlyInside(root, root))
                val items = WorkspacePaths.expandSelection(root, listOf("dir", "top.txt", "../x", outside.path))
                assertEquals(
                    listOf("dir/a.txt", "dir/sub/b.txt", "top.txt"),
                    items.map { it.relativePath }.sorted()
                )
                assertEquals(1, WorkspaceExport.delete(root, listOf("top.txt", "..", "")))
                assertFalse(File(root, "top.txt").exists())
                assertTrue(root.exists())
            } finally { outside.deleteRecursively() }
        } finally { root.deleteRecursively() }
    }
}
