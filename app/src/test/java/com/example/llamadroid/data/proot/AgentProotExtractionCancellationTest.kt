package com.example.llamadroid.data.proot

import kotlinx.coroutines.CancellationException
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.zip.GZIPOutputStream

class AgentProotExtractionCancellationTest {
    private lateinit var temporaryRoot: File

    @Before
    fun setUp() {
        temporaryRoot = Files.createTempDirectory("agent-proot-cancel").toFile()
    }

    @After
    fun tearDown() {
        temporaryRoot.deleteRecursively()
    }

    @Test
    fun checksumCancellationPropagatesAtAChunkBoundary() {
        val archive = File(temporaryRoot, "large.bin").apply {
            writeBytes(ByteArray(128 * 1024) { it.toByte() })
        }
        var checks = 0

        val failure = runCatching {
            AgentProotRootfsExtractor.sha256(archive, AgentProotCancellation {
                if (++checks == 2) throw CancellationException("test checksum cancellation")
            })
        }.exceptionOrNull()

        assertTrue(failure is CancellationException)
        assertEquals(2, checks)
    }

    @Test
    fun extractionCancellationLeavesExistingDestinationDataUntouched() {
        val archive = createArchive()
        val destination = File(temporaryRoot, "rootfs").apply { mkdirs() }
        val existing = File(destination, "existing/keep.txt").apply {
            parentFile?.mkdirs()
            writeText("keep")
        }
        var sawFileChunk = false

        val failure = runCatching {
            AgentProotRootfsExtractor.extract(
                archive = archive,
                destination = destination,
                cancellation = AgentProotCancellation {
                    // FileOutputStreamCompat creates this path immediately before the first
                    // extraction-chunk checkpoint, so this cancels inside the file copy loop.
                    if (File(destination, "etc/os-release").isFile) {
                        sawFileChunk = true
                        throw CancellationException("test extraction cancellation")
                    }
                }
            )
        }.exceptionOrNull()

        assertTrue(failure is CancellationException)
        assertTrue(sawFileChunk)
        assertEquals("keep", existing.readText())
    }

    private fun createArchive(): File {
        val archive = File(temporaryRoot, "rootfs.tar.gz")
        val bytes = ByteArray(128 * 1024) { (it % 251).toByte() }
        GZIPOutputStream(archive.outputStream()).use { compressed ->
            TarArchiveOutputStream(compressed).use { tar ->
                val entry = TarArchiveEntry("etc/os-release").apply {
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
}
