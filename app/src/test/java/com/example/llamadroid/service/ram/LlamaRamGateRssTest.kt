package com.example.llamadroid.service.ram

import org.junit.Assert.assertEquals
import org.junit.Test

class LlamaRamGateRssTest {
    @Test fun parsesRssFileKb() {
        val status = "Name:\tllama-server\nRssAnon:\t  1000 kB\nRssFile:\t  2048 kB\nRssShmem:\t 0 kB\n"
        assertEquals(2048L * 1024L, LlamaRamGate.parseRssFileBytes(status))
    }

    @Test fun missingLineIsZero() {
        assertEquals(0L, LlamaRamGate.parseRssFileBytes("Name:\tx\nRssAnon:\t5 kB\n"))
    }

    @Test fun parsesRssAnonKb() {
        val status = "Name:\tllama-server\nRssAnon:\t  1000 kB\nRssFile:\t  2048 kB\n"
        assertEquals(1000L * 1024L, LlamaRamGate.parseRssAnonBytes(status))
        assertEquals(0L, LlamaRamGate.parseRssAnonBytes("Name:\tx\nRssFile:\t5 kB\n"))
    }

    @Test fun residentBytesOfPidSumsFileAndAnon() {
        val root = java.nio.file.Files.createTempDirectory("proc").toFile()
        try {
            java.io.File(root, "42").mkdirs()
            java.io.File(root, "42/status").writeText("RssAnon:\t 10 kB\nRssFile:\t 30 kB\n")
            assertEquals(40L * 1024L, LlamaRamGate.residentBytesOfPid(42, root))
            assertEquals(0L, LlamaRamGate.residentBytesOfPid(43, root))
        } finally {
            root.deleteRecursively()
        }
    }
}
