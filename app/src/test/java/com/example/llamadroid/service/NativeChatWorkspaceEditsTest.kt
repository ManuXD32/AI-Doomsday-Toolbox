package com.example.llamadroid.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class NativeChatWorkspaceEditsTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun file(n: Int) = (1..n).joinToString("\n") { "line $it" } + "\n"

    @Test
    fun readDefaultsToFirst300LinesWithHeaderAndNumbers() {
        val out = NativeChatWorkspaceEdits.readRange(file(1000), "a.html", null, null)
        val rows = out.lines()
        assertEquals("a.html · 1000 lines · showing 1–300", rows[0])
        assertEquals("   1| line 1", rows[1])
        assertEquals(" 300| line 300", rows[300])
        assertTrue(out.contains("start_line=301"))
    }

    @Test
    fun readRangeAndBounds() {
        val out = NativeChatWorkspaceEdits.readRange(file(50), "a", 10, 12)
        assertEquals("a · 50 lines · showing 10–12\n  10| line 10\n  11| line 11\n  12| line 12\n[more lines: continue with start_line=13]", out)
        assertThrows(IllegalArgumentException::class.java) { NativeChatWorkspaceEdits.readRange(file(5), "a", 9, null) }
        assertTrue(NativeChatWorkspaceEdits.readRange(file(5), "a", 4, 99).startsWith("a · 5 lines · showing 4–5"))
        assertTrue(NativeChatWorkspaceEdits.readRange("", "e", null, null).contains("[empty file]"))
    }

    @Test
    fun longLinesAreTruncated() {
        val out = NativeChatWorkspaceEdits.readRange("x".repeat(1000) + "\n", "a", null, null)
        assertTrue(out.contains("x".repeat(400) + "…[+600 chars]"))
        assertFalse(out.contains("x".repeat(401)))
    }

    @Test
    fun searchIsCaseInsensitiveAndCounts() {
        val text = "Alpha\nbeta\nALPHA beta\n"
        val out = NativeChatWorkspaceEdits.search(text, "f", "alpha", null, regex = false)
        assertEquals("f · 3 lines · 2 matches\n   1| Alpha\n   3| ALPHA beta", out)
        assertTrue(NativeChatWorkspaceEdits.search(text, "f", "a.p", null, regex = false).endsWith("0 matches"))
        assertTrue(NativeChatWorkspaceEdits.search(text, "f", "^b", null, regex = true).contains("   2| beta"))
        assertTrue(NativeChatWorkspaceEdits.search(file(100), "f", "line", 2, false).contains("98 more matches"))
        assertThrows(IllegalArgumentException::class.java) { NativeChatWorkspaceEdits.search(text, "f", "(", null, true) }
    }

    @Test
    fun replaceZeroOneMany() {
        val text = file(20)
        val zero = assertThrows(IllegalArgumentException::class.java) {
            NativeChatWorkspaceEdits.replaceInText(text, "f", "line  7", "x", false)
        }
        assertTrue(zero.message!!.contains("not found"))
        val near = assertThrows(IllegalArgumentException::class.java) {
            NativeChatWorkspaceEdits.replaceInText(text, "f", "line 7\nmissing", "x", false)
        }
        assertTrue(near.message!!.contains("line 7"))

        val one = NativeChatWorkspaceEdits.replaceInText(text, "f", "line 7\n", "SEVEN\n", false)
        assertTrue(one.updatedText.contains("\nSEVEN\n"))
        assertTrue(one.message.contains("File now has 20 lines"))
        assertTrue(one.message.contains("   4| line 4") && one.message.contains("  10| line 10"))
        assertFalse(one.message.contains("  11| line 11"))

        val many = assertThrows(IllegalArgumentException::class.java) {
            NativeChatWorkspaceEdits.replaceInText(text, "f", "line 1", "x", false)
        }
        assertTrue(many.message!!.contains("lines 1, 10, 11"))
        val all = NativeChatWorkspaceEdits.replaceInText(text, "f", "line 1", "L1", true)
        assertTrue(all.message.startsWith("Replaced 11 occurrences"))
    }

    @Test
    fun editEchoesShowChangedRegionAndLineCount() {
        val updated = "a\nb\nNEW1\nNEW2\ne\nf\ng\nh\ni\n"
        val echo = NativeChatWorkspaceEdits.echoLines("f", 3, 3, 2, updated)
        assertTrue(echo.contains("File now has 9 lines"))
        assertTrue(echo.contains("   3| NEW1") && echo.contains("   4| NEW2") && echo.contains("   7| g"))
        assertFalse(echo.contains("   8| h"))
        val append = NativeChatWorkspaceEdits.echoAppend("f", 4, 6, "a\nb\nc\nd\ne\nf\nXY\nZ\n")
        assertTrue(append.contains("File now has 8 lines"))
        assertTrue(append.contains("   7| XY"))
        assertTrue(NativeChatWorkspaceEdits.echoWrite("f", 3, "a\nb\n").contains("   2| b"))
    }

    @Test
    fun pathSafetyReusesResolveCore() {
        val root = tmp.newFolder("ws").canonicalFile
        assertEquals(File(root, "a/b.txt").path, NativeChatWorkspaceSupport.resolveUnder(root, "/a/b.txt").path)
        assertThrows(IllegalArgumentException::class.java) { NativeChatWorkspaceSupport.resolveUnder(root, "../x") }
        assertThrows(IllegalArgumentException::class.java) { NativeChatWorkspaceSupport.resolveUnder(root, "a/../../x") }
    }

    @Test
    fun workspaceHintListsFilesWithinBudget() {
        val root = tmp.newFolder("hint")
        assertEquals(null, NativeChatWorkspaceEdits.workspaceHint(root))
        File(root, "worlds").mkdirs()
        File(root, "worlds/rose.html").writeText(file(12))
        val hint = NativeChatWorkspaceEdits.workspaceHint(root)!!
        assertTrue(hint.startsWith("World file: worlds/rose.html (12 lines). For changes call search_workspace_file then replace_in_workspace_file"))
        assertTrue(hint.length <= 300)
        File(root, "worlds/rose.html").delete()
        File(root, "notes.txt").writeText(file(3))
        assertTrue(NativeChatWorkspaceEdits.workspaceHint(root)!!.startsWith("Workspace: notes.txt (3 lines)"))
    }

    @Test
    fun toolListDependsOnlyOnConfigFlag() {
        val ro = NativeChatWorkspaceEdits.tools(false).map { it.name }
        assertEquals(listOf("list_workspace_files", "read_workspace_file", "search_workspace_file"), ro)
        val rw = NativeChatWorkspaceEdits.tools(true).map { it.name }
        assertTrue(rw.containsAll(listOf("replace_in_workspace_file", "write_workspace_file", "append_workspace_file", "edit_workspace_lines", "apply_workspace_patch")))
        assertEquals(rw, NativeChatWorkspaceEdits.tools(true).map { it.name })
    }
}
