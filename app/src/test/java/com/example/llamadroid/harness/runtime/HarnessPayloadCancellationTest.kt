package com.example.llamadroid.harness.runtime

import kotlinx.coroutines.CancellationException
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.xz.XZCompressorOutputStream
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

class HarnessPayloadCancellationTest {
    private lateinit var temporaryRoot: File

    @Before
    fun setUp() {
        temporaryRoot = Files.createTempDirectory("harness-payload-cancel").toFile()
    }

    @After
    fun tearDown() {
        temporaryRoot.deleteRecursively()
    }

    @Test
    fun cancelledExtractionLeavesPreviousInstallAndNeverActivatesStagedTree() {
        val rootfs = File(temporaryRoot, "rootfs").apply { mkdirs() }
        val oldInstall = File(rootfs, "opt/adt-harness").apply { mkdirs() }
        File(oldInstall, "bin").mkdirs()
        File(oldInstall, "bin/dsh").writeText("old-payload")
        File(oldInstall, ".adt-harness-payload.json").writeText("old-marker")
        val archive = createArchive()
        val payload = payloadFor(archive)
        val phases = mutableListOf<HarnessPayloadWorkPhase>()

        val failure = runCatching {
            installHarnessPayload(
                rootfs = rootfs,
                payload = payload,
                archiveSource = { archive.inputStream() },
                cancellation = HarnessPayloadCancellation { phase ->
                    phases += phase
                    if (phase == HarnessPayloadWorkPhase.EXTRACT_CHUNK) {
                        throw CancellationException("test extraction cancellation")
                    }
                }
            )
        }.exceptionOrNull()

        assertTrue(failure is CancellationException)
        assertTrue(phases.contains(HarnessPayloadWorkPhase.COPY))
        assertTrue(phases.contains(HarnessPayloadWorkPhase.CHECKSUM))
        assertTrue(phases.contains(HarnessPayloadWorkPhase.EXTRACT_ENTRY))
        assertTrue(phases.contains(HarnessPayloadWorkPhase.EXTRACT_CHUNK))
        assertFalse(phases.contains(HarnessPayloadWorkPhase.ACTIVATE))
        assertEquals("old-payload", File(oldInstall, "bin/dsh").readText())
        assertEquals("old-marker", File(oldInstall, ".adt-harness-payload.json").readText())
        assertFalse(File(rootfs, "opt/adt-harness/new-file").exists())
        assertTrue(File(rootfs, ".adt-harness-staging").listFiles().orEmpty().isEmpty())
    }

    @Test
    fun cancellationBeforeActivationRestoresPreviousInstall() {
        val rootfs = File(temporaryRoot, "rootfs").apply { mkdirs() }
        val oldInstall = File(rootfs, "opt/adt-harness").apply { mkdirs() }
        File(oldInstall, "bin").mkdirs()
        File(oldInstall, "bin/dsh").writeText("old-payload")
        val archive = createArchive()
        val payload = payloadFor(archive)
        val phases = mutableListOf<HarnessPayloadWorkPhase>()
        var activationChecks = 0

        val failure = runCatching {
            installHarnessPayload(
                rootfs = rootfs,
                payload = payload,
                archiveSource = { archive.inputStream() },
                cancellation = HarnessPayloadCancellation { phase ->
                    phases += phase
                    if (phase == HarnessPayloadWorkPhase.ACTIVATE && ++activationChecks == 2) {
                        throw CancellationException("test activation cancellation")
                    }
                }
            )
        }.exceptionOrNull()

        assertTrue(failure is CancellationException)
        assertEquals(2, activationChecks)
        assertTrue(phases.contains(HarnessPayloadWorkPhase.ACTIVATE))
        assertEquals("old-payload", File(oldInstall, "bin/dsh").readText())
        assertFalse(
            rootfs.listFiles().orEmpty().any { it.name.startsWith(".adt-harness-previous-") }
        )
    }

    @Test
    fun secureExtractorPreservesExecutableModeForHardlink() {
        val archive = createExecutableHardlinkArchive(selfLink = false)
        val destination = File(temporaryRoot, "hardlink-executable")

        HarnessSecureArchiveExtractor.extract(archive, destination, "/opt/adt-harness")

        val copy = File(destination, "opt/adt-harness/bin/dsh-copy")
        assertTrue(copy.canExecute())
        assertEquals("new-payload", copy.readText())
    }

    @Test
    fun secureExtractorRejectsSelfHardlinkBeforeTruncatingSource() {
        val archive = createExecutableHardlinkArchive(selfLink = true)
        val destination = File(temporaryRoot, "self-hardlink")

        val failure = runCatching {
            HarnessSecureArchiveExtractor.extract(archive, destination, "/opt/adt-harness")
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertEquals(
            "new-payload",
            File(destination, "opt/adt-harness/bin/dsh").readText()
        )
    }

    private fun createExecutableHardlinkArchive(selfLink: Boolean): File {
        val archive = File(temporaryRoot, "payload-hardlink.tar.xz")
        val bytes = "new-payload".toByteArray()
        val sourceName = "opt/adt-harness/bin/dsh"
        val targetName = if (selfLink) sourceName else "opt/adt-harness/bin/dsh-copy"
        XZCompressorOutputStream(archive.outputStream()).use { compressed ->
            TarArchiveOutputStream(compressed).use { tar ->
                val source = TarArchiveEntry(sourceName).apply {
                    size = bytes.size.toLong()
                    mode = 0b111101101
                }
                tar.putArchiveEntry(source)
                tar.write(bytes)
                tar.closeArchiveEntry()
                val link = TarArchiveEntry(targetName, TarArchiveEntry.LF_LINK).apply {
                    linkName = sourceName
                }
                tar.putArchiveEntry(link)
                tar.closeArchiveEntry()
                tar.finish()
            }
        }
        return archive
    }

    private fun payloadFor(archive: File): HarnessPayload = HarnessPayload(
        version = "0.1.6-alpha.2",
        commit = "ddefc45fbc7f8e46dd73185e68295696d1297887",
        archiveAsset = "harness/payload.tar.xz",
        archiveSha256 = sha256(archive),
        command = listOf("/opt/adt-harness/bin/dsh"),
        healthPath = "/"
    )

    private fun createArchive(): File {
        val archive = File(temporaryRoot, "payload.tar.xz")
        val bytes = "new-payload".toByteArray()
        XZCompressorOutputStream(archive.outputStream()).use { compressed ->
            TarArchiveOutputStream(compressed).use { tar ->
                val entry = TarArchiveEntry("opt/adt-harness/bin/dsh").apply {
                    size = bytes.size.toLong()
                }
                tar.putArchiveEntry(entry)
                tar.write(bytes)
                tar.closeArchiveEntry()
                tar.finish()
            }
        }
        return archive
    }

    private fun sha256(file: File): String = MessageDigest.getInstance("SHA-256").let { digest ->
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count > 0) digest.update(buffer, 0, count)
            }
        }
        digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }
}
