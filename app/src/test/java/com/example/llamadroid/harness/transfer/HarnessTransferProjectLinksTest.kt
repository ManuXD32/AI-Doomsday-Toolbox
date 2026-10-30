package com.example.llamadroid.harness.transfer

import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class HarnessTransferProjectLinksTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun copiedProjectsKeepInternalLinksAndRebaseCrossProjectLinksIncludingMissingTargets() {
        val projects = temporary.newFolder("projects")
        val folder = File(projects, "first/nested").apply { mkdirs() }
        Files.createSymbolicLink(File(folder, "internal").toPath(), Paths.get("../file.txt"))
        Files.createSymbolicLink(File(folder, "other").toPath(), Paths.get("../../second/file.txt"))
        HarnessTransferProjectLinks.prepare(projects) { "$it-copy" }
        assertEquals("../file.txt", Files.readSymbolicLink(File(folder, "internal").toPath()).toString())
        assertEquals("../../second-copy/file.txt", Files.readSymbolicLink(File(folder, "other").toPath()).toString())
        assertFalse(File(projects, "second").exists())
    }

    @Test(expected = IllegalArgumentException::class)
    fun escapingLinksAreRejectedWithoutFollowingThem() {
        val projects = temporary.newFolder("projects")
        val folder = File(projects, "first").apply { mkdirs() }
        Files.createSymbolicLink(File(folder, "escape").toPath(), Paths.get("../../outside"))
        HarnessTransferProjectLinks.prepare(projects) { "$it-copy" }
    }
}
