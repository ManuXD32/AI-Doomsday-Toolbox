package com.example.llamadroid.data.proot

import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.zip.GZIPOutputStream

class AgentProotEnvironmentSecurityTest {
    private lateinit var temporaryRoot: File

    @Before
    fun setUp() {
        temporaryRoot = Files.createTempDirectory("agent-proot-security").toFile()
    }

    @After
    fun tearDown() {
        temporaryRoot.deleteRecursively()
    }

    @Test
    fun environmentIdsRejectTraversalAndAbsoluteValues() {
        assertEquals("project_01", AgentProotEnvironmentPaths.requireSafeEnvironmentId("project_01"))
        listOf("../escape", "/absolute", "", "project/child", "project\\child").forEach { value ->
            assertFalse(runCatching { AgentProotEnvironmentPaths.requireSafeEnvironmentId(value) }.isSuccess)
        }
    }

    @Test
    fun extractorAllowsNormalRootfsAndPreservesRelativeSymlink() {
        val archive = createArchive(
            listOf(
                fileEntry("etc/os-release", "PRETTY_NAME=\"Debian GNU/Linux trixie\"\n"),
                fileEntry("usr/bin/sh", "#!/bin/sh\n"),
                symlinkEntry("bin", "usr/bin")
            )
        )
        val destination = File(temporaryRoot, "rootfs")

        AgentProotRootfsExtractor.extract(archive, destination)

        assertTrue(File(destination, "etc/os-release").isFile)
        assertTrue(Files.isSymbolicLink(File(destination, "bin").toPath()))
        assertEquals("usr/bin", Files.readSymbolicLink(File(destination, "bin").toPath()).toString())
    }

    @Test
    fun extractorPreservesExecutableModeForHardlink() {
        val archive = createArchive(
            listOf(
                fileEntry("etc/os-release", "Debian\n"),
                executableFileEntry("usr/bin/sh", "#!/bin/sh\n"),
                hardlinkEntry("usr/bin/sh-copy", "usr/bin/sh")
            )
        )
        val destination = File(temporaryRoot, "hardlink-executable")

        AgentProotRootfsExtractor.extract(archive, destination)

        assertTrue(File(destination, "usr/bin/sh-copy").canExecute())
        assertEquals("#!/bin/sh\n", File(destination, "usr/bin/sh-copy").readText())
    }

    @Test
    fun extractorRejectsSelfHardlinkBeforeTruncatingSource() {
        val archive = createArchive(
            listOf(
                fileEntry("etc/os-release", "Debian\n"),
                executableFileEntry("usr/bin/sh", "preserve-me"),
                hardlinkEntry("usr/bin/sh", "usr/bin/sh")
            )
        )
        val destination = File(temporaryRoot, "self-hardlink")

        val failure = runCatching {
            AgentProotRootfsExtractor.extract(archive, destination)
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertEquals("preserve-me", File(destination, "usr/bin/sh").readText())
    }

    @Test
    fun extractorRejectsTraversalAndRewritesGuestRootAbsoluteLinks() {
        val traversal = createArchive(
            listOf(
                fileEntry("etc/os-release", "Debian\n"),
                fileEntry("bin/sh", "shell\n"),
                symlinkEntry("escape", "../outside")
            )
        )
        assertFalse(runCatching {
            AgentProotRootfsExtractor.extract(traversal, File(temporaryRoot, "traversal"))
        }.isSuccess)

        val absolute = createArchive(
            listOf(
                fileEntry("etc/os-release", "Debian\n"),
                fileEntry("bin/sh", "shell\n"),
                symlinkEntry("escape", "/tmp/outside")
            )
        )
        assertTrue(runCatching {
            AgentProotRootfsExtractor.extract(absolute, File(temporaryRoot, "absolute"))
        }.isSuccess)
        assertTrue(Files.isSymbolicLink(File(temporaryRoot, "absolute/escape").toPath()))
        assertEquals("tmp/outside", Files.readSymbolicLink(File(temporaryRoot, "absolute/escape").toPath()).toString())
    }

    private fun createArchive(entries: List<TarArchiveEntrySpec>): File {
        val archive = File(temporaryRoot, "rootfs.tar.gz")
        GZIPOutputStream(archive.outputStream()).use { compressed ->
            TarArchiveOutputStream(compressed).use { tar ->
                entries.forEach { entrySpec ->
                    val entry = entrySpec.entry
                    tar.putArchiveEntry(entry)
                    if (entry.isFile) tar.write(entrySpec.content.toByteArray())
                    tar.closeArchiveEntry()
                }
                tar.finish()
            }
        }
        return archive
    }

    private fun fileEntry(name: String, content: String): TarArchiveEntrySpec {
        val bytes = content.toByteArray()
        return TarArchiveEntrySpec(TarArchiveEntry(name).apply { size = bytes.size.toLong() }, content)
    }

    private fun executableFileEntry(name: String, content: String): TarArchiveEntrySpec =
        fileEntry(name, content).also { it.entry.mode = 0b111101101 }

    private fun hardlinkEntry(name: String, target: String): TarArchiveEntrySpec =
        TarArchiveEntrySpec(
            TarArchiveEntry(name, TarArchiveEntry.LF_LINK).apply { linkName = target },
            ""
        )

    private fun symlinkEntry(name: String, target: String): TarArchiveEntrySpec =
        TarArchiveEntrySpec(TarArchiveEntry(name, TarArchiveEntry.LF_SYMLINK).apply { linkName = target }, "")

    private data class TarArchiveEntrySpec(val entry: TarArchiveEntry, val content: String)
}
