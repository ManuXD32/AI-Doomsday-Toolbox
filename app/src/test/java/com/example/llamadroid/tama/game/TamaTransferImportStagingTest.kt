package com.example.llamadroid.tama.game

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import java.util.zip.CRC32
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TamaTransferImportStagingTest {
    @Test
    fun overwriteThenSecondTargetFailureRestoresOriginal() {
        val root = Files.createTempDirectory("tama-transfer-staging").toFile()
        try {
            val target = File(root, "existing.bin").apply { writeText("old bytes") }
            val directoryTarget = File(root, "blocked").apply { mkdirs() }
            TamaTransferImportStaging(File(root, "stage")).use { staging ->
                staging.stageZipEntry("first", ByteArrayInputStream("new bytes".toByteArray()))
                assertTrue(staging.claim("first", target))
                staging.stageZipEntry("second", ByteArrayInputStream("should fail".toByteArray()))
                assertTrue(staging.claim("second", directoryTarget))

                assertThrows(IllegalArgumentException::class.java) { staging.install() }
                assertEquals("old bytes", target.readText())
                assertTrue(directoryTarget.isDirectory)
            }
            assertFalse(File(root, "stage").exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun mediaEntryBeforeManifestCanBeStagedAndInstalled() {
        val root = Files.createTempDirectory("tama-transfer-zip").toFile()
        try {
            val mediaName = "gallery/pet-1/art-1.png"
            val archiveBytes = zipBytes(
                mediaName to "media bytes".toByteArray(),
                "manifest.json" to "{}".toByteArray()
            )
            val target = File(root, "gallery/pet-1/art-1.png")
            TamaTransferImportStaging(File(root, "stage")).use { staging ->
                val archive = staging.stageArchive(ByteArrayInputStream(archiveBytes))
                ZipFile(archive).use { zipFile ->
                    val entries = zipFile.entries()
                    assertEquals(mediaName, entries.nextElement().name)
                    val media = zipFile.getEntry(mediaName)
                    zipFile.readVerifiedTamaEntry(media) { input -> staging.stageZipEntry(mediaName, input) }
                    assertEquals("manifest.json", entries.nextElement().name)
                }
                assertTrue(staging.claim(mediaName, target))
                staging.install()
                staging.commit()
            }
            assertEquals("media bytes", target.readText())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun missingEndOfCentralDirectoryIsRejectedBeforeEntriesAreRead() {
        val root = Files.createTempDirectory("tama-transfer-truncated").toFile()
        try {
            val valid = zipBytes("manifest.json" to "{}".toByteArray())
            val withoutEndRecord = valid.copyOf(valid.size - 22)
            TamaTransferImportStaging(File(root, "stage")).use { staging ->
                val archive = staging.stageArchive(ByteArrayInputStream(withoutEndRecord))
                assertThrows(ZipException::class.java) {
                    ZipFile(archive).use { zipFile ->
                        val entries = zipFile.entries()
                        while (entries.hasMoreElements()) entries.nextElement()
                    }
                }
            }
        } finally {
            root.deleteRecursively()
        }
    }

    private fun zipBytes(vararg entries: Pair<String, ByteArray>): ByteArray {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            entries.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content)
                zip.closeEntry()
            }
        }
        return bytes.toByteArray()
    }

    @Test
    fun readableStoredEntryWithCorruptPayloadFailsChecksumValidation() {
        val root = Files.createTempDirectory("tama-transfer-checksum").toFile()
        try {
            val content = "original bytes".toByteArray()
            val output = ByteArrayOutputStream()
            ZipOutputStream(output).use { zip ->
                zip.putNextEntry(ZipEntry("media.bin").apply {
                    method = ZipEntry.STORED
                    size = content.size.toLong()
                    compressedSize = size
                    crc = CRC32().apply { update(content) }.value
                })
                zip.write(content)
                zip.closeEntry()
            }
            val corrupted = output.toByteArray()
            val nameLength = (corrupted[26].toInt() and 255) or ((corrupted[27].toInt() and 255) shl 8)
            val extraLength = (corrupted[28].toInt() and 255) or ((corrupted[29].toInt() and 255) shl 8)
            val contentOffset = 30 + nameLength + extraLength
            corrupted[contentOffset] = (corrupted[contentOffset].toInt() xor 1).toByte()
            TamaTransferImportStaging(File(root, "stage")).use { staging ->
                val archive = staging.stageArchive(ByteArrayInputStream(corrupted))
                ZipFile(archive).use { zip ->
                    assertThrows(IllegalArgumentException::class.java) {
                        zip.readVerifiedTamaEntry(zip.getEntry("media.bin")) { it.readBytes() }
                    }
                }
            }
        } finally {
            root.deleteRecursively()
        }
    }
}
