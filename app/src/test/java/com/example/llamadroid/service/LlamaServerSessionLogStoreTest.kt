package com.example.llamadroid.service

import android.content.Context
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class LlamaServerSessionLogStoreTest {

    @Test
    fun `appendBatch preserves order and a second reader sees the durable tail`() {
        val root = Files.createTempDirectory("llama-session-log").toFile()
        try {
            val context = testContext(root)
            val first = LlamaServerSessionLogStore(context)
            first.appendBatch("card:1", listOf("first", "second"))
            first.append("card:1", "third")

            assertEquals(listOf("first", "second", "third"), first.read("card:1"))
            assertEquals(listOf("first", "second", "third"), LlamaServerSessionLogStore(context).read("card:1"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `compaction keeps at most the newest 2000 lines and one mib`() {
        val root = Files.createTempDirectory("llama-session-log").toFile()
        try {
            val context = testContext(root)
            val store = LlamaServerSessionLogStore(context)
            store.appendBatch("card:2", (0 until 2_100).map { "line-$it" })

            val lines = store.read("card:2")
            val file = root.resolve("llama_server_session_logs/card:2.log")
            assertEquals(2_000, lines.size)
            assertEquals("line-100", lines.first())
            assertEquals("line-2099", lines.last())
            assertTrue(file.length() <= 1024L * 1024L)
        } finally {
            root.deleteRecursively()
        }
    }

    private fun testContext(root: java.io.File): Context = mockk<Context>(relaxed = true).also { context ->
        every { context.applicationContext } returns context
        every { context.filesDir } returns root
    }
}
