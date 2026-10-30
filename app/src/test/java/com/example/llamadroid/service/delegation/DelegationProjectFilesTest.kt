package com.example.llamadroid.service.delegation

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class DelegationProjectFilesTest {
    private lateinit var base: File
    private lateinit var root: File

    @Before
    fun setUp() {
        base = Files.createTempDirectory("projfiles").toFile()
        root = File(base, "proj").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        base.deleteRecursively()
    }

    private fun write(rel: String, text: String) = File(root, rel).apply { parentFile?.mkdirs(); writeText(text) }

    @Test
    fun resolveRejectsEscapes() {
        assertNull(DelegationProjectFiles.resolve(root, "../outside"))
        assertNull(DelegationProjectFiles.resolve(root, "a/../../x"))
        assertNotNull(DelegationProjectFiles.resolve(root, "a/b"))
        assertEquals(root.canonicalFile, DelegationProjectFiles.resolve(root, "/"))
    }

    @Test
    fun resolveRejectsSymlinkEscape() {
        val outside = File(base, "secret.txt").apply { writeText("secret") }
        try {
            Files.createSymbolicLink(File(root, "link.txt").toPath(), outside.toPath())
        } catch (_: Exception) {
            return // symlinks unsupported on this host
        }
        assertNull(DelegationProjectFiles.resolve(root, "link.txt"))
        write("a.txt", "x")
        val listing = DelegationProjectFiles.list(root, "")
        assertFalse(listing.contains("link.txt"))
        assertTrue(listing.contains("a.txt"))
    }

    @Test
    fun listSkipsHeavyFoldersAndLimitsDepth() {
        write("src/Main.kt", "fun main() {}")
        write(".git/config", "x")
        write("node_modules/p/i.js", "x")
        write(".dsh/state", "x")
        write("a/b/c/d/deep.txt", "x")
        val out = DelegationProjectFiles.list(root, "")
        assertTrue(out.contains("Main.kt"))
        assertFalse(out.contains("config"))
        assertFalse(out.contains("i.js"))
        assertFalse(out.contains("state"))
        assertFalse(out.contains("deep.txt"))
        assertTrue(out.contains("c/"))
    }

    @Test
    fun listCapsEntries() {
        repeat(400) { write("f$it.txt", "x") }
        val out = DelegationProjectFiles.list(root, "")
        assertTrue(out.contains("truncated"))
        assertEquals(DelegationProjectFiles.MAX_ENTRIES, out.lines().count { it.startsWith("f") })
    }

    @Test
    fun readPagesWithOffset() {
        write("big.txt", "a".repeat(DelegationProjectFiles.PAGE_CHARS) + "TAIL")
        val first = DelegationProjectFiles.read(root, "big.txt", 0)
        assertTrue(first.contains("[more: call read again with offset=${DelegationProjectFiles.PAGE_CHARS}]"))
        val second = DelegationProjectFiles.read(root, "big.txt", DelegationProjectFiles.PAGE_CHARS)
        assertTrue(second.contains("TAIL"))
        assertTrue(second.contains("[end of file]"))
        assertTrue(DelegationProjectFiles.read(root, "big.txt", 1_000_000).startsWith("tool_error"))
    }

    @Test
    fun readRefusesBinaryAndOutside() {
        File(root, "img.bin").writeBytes(byteArrayOf(1, 2, 0, 3))
        assertTrue(DelegationProjectFiles.read(root, "img.bin", 0).contains("action=attach"))
        assertTrue(DelegationProjectFiles.read(root, "../x", 0).startsWith("tool_error"))
        assertTrue(DelegationProjectFiles.read(root, "missing.txt", 0).startsWith("tool_error"))
    }

    @Test
    fun searchMatchesNamesAndTextWithLineNumbers() {
        write("src/notes.txt", "one\nTwo NEEDLE here\nthree")
        write("needle_file.md", "nothing")
        write(".git/x.txt", "needle")
        File(root, "bin.dat").writeBytes(byteArrayOf(0, 'n'.code.toByte()))
        val out = DelegationProjectFiles.search(root, "", "needle")
        assertTrue(out.contains("src/notes.txt:2:"))
        assertTrue(out.contains("needle_file.md (file name)"))
        assertFalse(out.contains(".git"))
        assertFalse(out.contains("bin.dat"))
    }

    @Test
    fun searchCapsHits() {
        repeat(80) { write("m$it.txt", "needle") }
        val out = DelegationProjectFiles.search(root, "", "needle")
        assertEquals(DelegationProjectFiles.MAX_HITS, out.lines().count { it.startsWith("m") })
        assertTrue(out.contains("capped"))
    }
}
