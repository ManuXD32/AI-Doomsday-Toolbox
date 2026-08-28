package com.example.llamadroid.service

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class ProcessOutputStreamSupportTest {

    @Test
    fun `carriage return redraws update progress instead of permanent logs`() = runBlocking {
        val logs = mutableListOf<String>()
        val progress = mutableListOf<String>()
        val output = "[VIDEO-GEN] |####### | 110/825 - 0.18MB/s\u001B[K\r" +
            "[VIDEO-GEN] |####### | 112/825 - 0.18MB/s\u001B[K\r" +
            "model loaded\n"

        consumeBoundedProcessOutput(
            input = ByteArrayInputStream(output.toByteArray(Charsets.UTF_8)),
            onLogLine = logs::add,
            onProgress = progress::add
        )

        assertEquals(listOf("model loaded"), logs)
        assertEquals(
            listOf(
                "[VIDEO-GEN] |####### | 110/825 - 0.18MB/s",
                "[VIDEO-GEN] |####### | 112/825 - 0.18MB/s"
            ),
            progress
        )
    }

    @Test
    fun `ansi sequences are stripped from newline logs`() = runBlocking {
        val logs = mutableListOf<String>()

        consumeBoundedProcessOutput(
            input = ByteArrayInputStream("loading\u001B[K\n".toByteArray(Charsets.UTF_8)),
            onLogLine = logs::add,
            onProgress = {}
        )

        assertEquals(listOf("loading"), logs)
    }

    @Test
    fun `complete output can be streamed to disk sink without buffering in memory`() = runBlocking {
        val logs = mutableListOf<String>()
        val rawLog = ByteArrayOutputStream()
        val output = "first line\nprogress 1/2\u001B[K\rsecond line\n"

        consumeBoundedProcessOutput(
            input = ByteArrayInputStream(output.toByteArray(Charsets.UTF_8)),
            rawLogOutput = rawLog,
            onLogLine = logs::add,
            onProgress = {}
        )

        assertEquals(listOf("first line", "second line"), logs)
        assertTrue(rawLog.toString(Charsets.UTF_8.name()).contains("progress 1/2\u001B[K\r"))
    }
}
