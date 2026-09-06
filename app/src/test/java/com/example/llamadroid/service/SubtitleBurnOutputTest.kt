package com.example.llamadroid.service

import java.io.File
import kotlin.io.path.createTempDirectory
import org.junit.Assert.*
import org.junit.Test

class SubtitleBurnOutputTest {
    @Test fun retainedVideoSurvivesTemporaryFileCleanup() {
        val root = createTempDirectory("subtitle-output-test").toFile()
        try {
            val cache = File(root, "cache").apply { mkdirs() }
            val input = File(cache, "output_123.mp4").apply { writeBytes(byteArrayOf(0, 1, 2, 127)) }
            val saved = preserveSubtitleOutput(input, File(root, "files"))
            input.delete()
            assertArrayEquals(byteArrayOf(0, 1, 2, 127), saved.readBytes())
            assertEquals("subtitle_outputs", saved.parentFile?.name)
        } finally { root.deleteRecursively() }
    }

    @Test fun retentionCannotOverwriteAnExistingVideo() {
        val root = createTempDirectory("subtitle-output-collision").toFile()
        try {
            val input = File(root, "output_123.mp4").apply { writeText("first result") }
            val files = File(root, "files")
            val saved = preserveSubtitleOutput(input, files)
            input.writeText("second result")
            assertThrows(FileAlreadyExistsException::class.java) { preserveSubtitleOutput(input, files) }
            assertEquals("first result", saved.readText())
        } finally { root.deleteRecursively() }
    }
}
