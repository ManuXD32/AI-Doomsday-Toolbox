package com.example.llamadroid.service

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeChatWorkspaceListingTest {
    private fun tempRoot(): File = Files.createTempDirectory("ws-listing").toFile().canonicalFile

    @Test
    fun listsRecursivelyWithIdsAndFooter() {
        val root = tempRoot()
        try {
            File(root, "attachments").mkdirs()
            val att = File(root, "attachments/notes.txt").apply { writeText("a\nb\n") }
            File(root, "main.py").writeText("print(1)\n")
            File(root, ".hidden").writeText("x")
            val out = NativeChatWorkspaceSupport.formatListing(
                root, mapOf(att.canonicalPath to "f12"), listOf("img55" to "photo.jpg")
            )
            val lines = out.lines()
            assertTrue(lines.contains("[dir] attachments/"))
            assertTrue(lines.any { it.startsWith("[file] attachments/notes.txt") && it.endsWith(" id=f12") })
            assertTrue(lines.any { it.startsWith("[file] main.py") && !it.contains("id=") })
            assertTrue(lines.contains("[image] photo.jpg id=img55"))
            assertTrue(!out.contains(".hidden"))
            assertEquals(NativeChatWorkspaceSupport.LISTING_FOOTER, lines.last())
        } finally { root.deleteRecursively() }
    }

    @Test
    fun limitsDepthAndEntries() {
        val root = tempRoot()
        try {
            File(root, "a/b/c/d").mkdirs()
            File(root, "a/b/c/d/deep.txt").writeText("x")
            File(root, "a/b/c/ok.txt").writeText("x")
            val out = NativeChatWorkspaceSupport.formatListing(root, emptyMap())
            assertTrue(out.contains("a/b/c/ok.txt"))
            assertTrue(!out.contains("deep.txt"))
            val many = tempRoot()
            try {
                repeat(230) { File(many, "f$it.txt").writeText("x") }
                val big = NativeChatWorkspaceSupport.formatListing(many, emptyMap())
                assertEquals(200, big.lines().count { it.startsWith("[file]") })
                assertTrue(big.contains("truncated"))
            } finally { many.deleteRecursively() }
        } finally { root.deleteRecursively() }
    }

    @Test
    fun emptyWorkspaceStillShowsFooter() {
        val root = tempRoot()
        try {
            val out = NativeChatWorkspaceSupport.formatListing(root, emptyMap())
            assertTrue(out.startsWith("[empty workspace]"))
            assertTrue(out.endsWith(NativeChatWorkspaceSupport.LISTING_FOOTER))
        } finally { root.deleteRecursively() }
    }

    @Test
    fun hintTexts() {
        assertNull(NativeChatWorkspaceSupport.filesCountHint(0))
        assertEquals(
            "This chat has 1 file. Call list_workspace_files to see them before saying a file is missing or asking the user to send it again.",
            NativeChatWorkspaceSupport.filesCountHint(1)
        )
        assertTrue(NativeChatWorkspaceSupport.filesCountHint(3)!!.startsWith("This chat has 3 files."))
    }

    @Test
    fun hintWithListStaysWithin300Chars() {
        val root = tempRoot()
        try {
            assertNull(NativeChatWorkspaceEdits.workspaceHintWithList(root))
            repeat(5) { File(root, "a_rather_long_file_name_number_$it.txt").writeText("x\n") }
            val hint = NativeChatWorkspaceEdits.workspaceHintWithList(root)!!
            assertTrue(hint.length <= 300)
            assertTrue(hint.endsWith(NativeChatWorkspaceSupport.LIST_FILES_HINT))
            assertEquals(5, NativeChatWorkspaceSupport.countFiles(root))
        } finally { root.deleteRecursively() }
    }
}
