package com.example.llamadroid.harness

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

class HarnessPresetFilesTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun mapsPersistentGuestMountBeforeRootfs() {
        val home = temporary.newFolder("home")
        val rootfs = temporary.newFolder("rootfs")
        val preset = File(home, ".agent-presets/mine").apply { mkdirs() }
        assertEquals(preset, resolveHarnessPresetGuestPath("/root/.dsh/.agent-presets/mine", listOf("/root/.dsh" to home, "/" to rootfs)))
        fails { resolveHarnessPresetGuestPath("/root/.dsh/../credentials", listOf("/root/.dsh" to home, "/" to rootfs)) }
    }

    @Test fun rejectsSymlinkDirectoriesAndEscapingFilePaths() {
        val root = temporary.newFolder("preset")
        val outside = temporary.newFolder("outside")
        File(outside, "private.txt").writeText("fixture only")
        Files.createSymbolicLink(File(root, "shortcut").toPath(), outside.toPath())
        val files = HarnessPresetFiles(root, "preset")
        fails { files.read("../outside/private.txt") }
        fails { files.read("shortcut/private.txt") }
        fails { files.create("shortcut", "new.txt", false) }
        fails { resolveHarnessPresetGuestPath("/preset/shortcut", listOf("/preset" to root)) }
        assertTrue(files.list("").isEmpty())
        assertFalse(File(outside, "new.txt").exists())
    }

    @Test fun staleSavePreservesTheConcurrentVersion() {
        val root = temporary.newFolder("preset")
        val file = File(root, "agent.cordis.yml").apply { writeText("- name: first\n") }
        val files = HarnessPresetFiles(root, "preset")
        val opened = files.read(file.name)
        file.writeText("- name: changed-in-web\n")
        fails { files.write(opened, "- name: stale\n") }
        assertEquals("- name: changed-in-web\n", file.readText())
        val saved = files.write(files.read(file.name), "- name: saved\n")
        assertEquals(saved, files.read(file.name))
    }

    @Test fun fileOperationsStayWithinPresetAndRefuseNonemptyDeletion() {
        val root = temporary.newFolder("preset")
        val files = HarnessPresetFiles(root, "preset")
        files.create("", "skills", true)
        files.create("skills", "SKILL.md", false)
        files.rename("skills/SKILL.md", "GUIDE.md")
        assertEquals(listOf("GUIDE.md"), files.list("skills").map { it.name })
        fails { files.delete("skills") }
        fails { files.delete("") }
        assertTrue(File(root, "skills/GUIDE.md").isFile)
        files.delete("skills/GUIDE.md")
        files.delete("skills")
        assertTrue(files.list("").isEmpty())
    }

    @Test fun refusesBinaryOrOversizedTextWithoutReplacingExistingFile() {
        val root = temporary.newFolder("preset")
        val files = HarnessPresetFiles(root, "preset")
        File(root, "binary").writeBytes(byteArrayOf(0, 1, 2))
        File(root, "invalid-utf8").writeBytes(byteArrayOf(0xC3.toByte(), 0x28))
        File(root, "large").writeText("x".repeat(HarnessPresetFiles.MAX_TEXT_BYTES + 1))
        fails { files.read("binary") }
        fails { files.read("invalid-utf8") }
        fails { files.read("large") }
        File(root, "valid").writeText("original")
        fails { files.write(files.read("valid"), "é".repeat(HarnessPresetFiles.MAX_TEXT_BYTES)) }
        assertEquals("original", File(root, "valid").readText())
    }

    private fun fails(block: () -> Unit) { assertTrue("Operation unexpectedly succeeded", runCatching(block).isFailure) }
}
