package com.example.llamadroid.util

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
class DownloaderResumeTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun `unchanged strong etag resumes only the verified range`() = runBlocking {
        val range = AtomicReference<String>()
        val ifRange = AtomicReference<String>()
        LocalHttpServer { headers, _ ->
            range.set(headers["range"])
            ifRange.set(headers["if-range"])
            Reply(206, "TAIL", "\"v1\"", "bytes 4-7/8")
        }.use { server ->
            val dest = folder.newFile("model.bin").apply { delete() }
            val part = java.io.File(folder.root, "model.bin.part").apply { writeText("OLD!") }
            DownloadResumeMetadata.write(DownloadResumeMetadata.companionFile(part),
                DownloadResumeMetadata(DownloadResumeMetadata.fingerprint(server.url), "\"v1\"", 8L))
            assertEquals(1f, Downloader.download(server.url, dest).toList().last())
            assertEquals("OLD!TAIL", dest.readText())
            assertEquals("bytes=4-", range.get())
            assertEquals("\"v1\"", ifRange.get())
            assertFalse(DownloadResumeMetadata.companionFile(part).exists())
        }
    }

    @Test fun `changed or malformed range restarts without mixed bytes`() = runBlocking {
        val requests = AtomicInteger()
        val secondRange = AtomicReference<String>()
        LocalHttpServer { headers, _ ->
            if (requests.incrementAndGet() == 1) {
                Reply(206, "!TAIL", "\"v2\"", "bytes 3-7/8")
            } else {
                secondRange.set(headers["range"])
                Reply(200, "NEW!TAIL", "\"v2\"")
            }
        }.use { server ->
            val dest = folder.newFile("model.bin").apply { delete() }
            val part = java.io.File(folder.root, "model.bin.part").apply { writeText("OLD!") }
            DownloadResumeMetadata.write(DownloadResumeMetadata.companionFile(part),
                DownloadResumeMetadata(DownloadResumeMetadata.fingerprint(server.url), "\"v1\"", 8L))
            Downloader.download(server.url, dest).toList()
            assertEquals("NEW!TAIL", dest.readText())
            assertEquals(2, requests.get())
            assertEquals(null, secondRange.get())
        }
    }

    @Test fun `changed validator and ignored range each restart with new bytes`() = runBlocking {
        for (ignored in listOf(false, true)) {
            val requests = AtomicInteger()
            val firstRange = AtomicReference<String>()
            LocalHttpServer { headers, _ ->
                if (requests.incrementAndGet() == 1) {
                    firstRange.set(headers["range"])
                    if (ignored) Reply(200, "NEW!TAIL", "\"v2\"")
                    else Reply(206, "TAIL", "\"v2\"", "bytes 4-7/8")
                } else Reply(200, "NEW!TAIL", "\"v2\"")
            }.use { server ->
                val dir = folder.newFolder()
                val dest = java.io.File(dir, "model.bin")
                val part = java.io.File(dir, "model.bin.part").apply { writeText("OLD!") }
                DownloadResumeMetadata.write(DownloadResumeMetadata.companionFile(part),
                    DownloadResumeMetadata(DownloadResumeMetadata.fingerprint(server.url), "\"v1\"", 8L))
                Downloader.download(server.url, dest).toList()
                assertEquals("bytes=4-", firstRange.get())
                assertEquals("NEW!TAIL", dest.readText())
                assertEquals(if (ignored) 1 else 2, requests.get())
            }
        }
    }

    @Test fun `weak or missing validator discards saved bytes before requesting`() = runBlocking {
        for (validator in listOf("W/\"v1\"", "")) {
            val range = AtomicReference<String>()
            LocalHttpServer { headers, _ ->
                range.set(headers["range"])
                Reply(200, "NEW!TAIL", "\"v2\"")
            }.use { server ->
                val dir = folder.newFolder()
                val dest = java.io.File(dir, "model.bin")
                val part = java.io.File(dir, "model.bin.part").apply { writeText("OLD!") }
                DownloadResumeMetadata.companionFile(part).writeText(
                    "version=1\nsource=${DownloadResumeMetadata.fingerprint(server.url)}\netag=$validator\ntotal=8\n"
                )
                Downloader.download(server.url, dest).toList()
                assertEquals(null, range.get())
                assertEquals("NEW!TAIL", dest.readText())
            }
        }
    }

    @Test fun `several verified ranges complete the declared representation`() = runBlocking {
        val ranges = mutableListOf<String?>()
        LocalHttpServer { headers, request ->
            ranges += headers["range"]
            if (request == 1) Reply(206, "TA", "\"v1\"", "bytes 4-5/8")
            else Reply(206, "IL", "\"v1\"", "bytes 6-7/8")
        }.use { server ->
            val dest = folder.newFile("multi-range.bin").apply { delete() }
            val part = java.io.File(folder.root, "multi-range.bin.part").apply { writeText("OLD!") }
            DownloadResumeMetadata.write(DownloadResumeMetadata.companionFile(part),
                DownloadResumeMetadata(DownloadResumeMetadata.fingerprint(server.url), "\"v1\"", 8L))
            Downloader.download(server.url, dest).toList()
            assertEquals("OLD!TAIL", dest.readText())
            assertEquals(listOf("bytes=4-", "bytes=6-"), ranges)
        }
    }

    @Test fun `legacy partial without metadata starts from zero`() = runBlocking {
        val range = AtomicReference<String>()
        LocalHttpServer { headers, _ ->
            range.set(headers["range"])
            Reply(200, "NEW!TAIL", "\"v2\"")
        }.use { server ->
            val dest = folder.newFile("model.bin").apply { delete() }
            java.io.File(folder.root, "model.bin.part").writeText("OLD!")
            Downloader.download(server.url, dest).toList()
            assertEquals("NEW!TAIL", dest.readText())
            assertEquals(null, range.get())
        }
    }

    @Test fun `truncated response and repeated range rejection never publish destination`() = runBlocking {
        LocalHttpServer { _, _ -> Reply(200, "short", "\"v1\"", declaredLength = 9) }.use { server ->
            val dest = java.io.File(folder.newFolder(), "truncated.bin")
            assertTrue(runCatching { Downloader.download(server.url, dest).toList() }.isFailure)
            assertFalse(dest.exists())
        }

        val requests = AtomicInteger()
        LocalHttpServer { _, _ ->
            requests.incrementAndGet()
            Reply(416, "")
        }.use { server ->
            val dir = folder.newFolder()
            val dest = java.io.File(dir, "rejected.bin")
            val part = java.io.File(dir, "rejected.bin.part").apply { writeText("OLD!") }
            DownloadResumeMetadata.write(DownloadResumeMetadata.companionFile(part),
                DownloadResumeMetadata(DownloadResumeMetadata.fingerprint(server.url), "\"v1\"", 8L))
            assertTrue(runCatching { Downloader.download(server.url, dest).toList() }.isFailure)
            assertFalse(dest.exists())
            assertEquals(5, requests.get())
        }
    }

    @Test fun `encoded response is rejected even when server ignores identity request`() = runBlocking {
        LocalHttpServer { _, _ -> Reply(200, "compressed", "\"v1\"", contentEncoding = "gzip") }.use { server ->
            val dest = java.io.File(folder.newFolder(), "encoded.bin")
            assertTrue(runCatching { Downloader.download(server.url, dest).toList() }.isFailure)
            assertFalse(dest.exists())
        }
    }

    @Test fun `cancellation retains only a verified durable partial when requested`() = runBlocking {
        for (preserve in listOf(true, false)) {
            LocalHttpServer { _, _ ->
                Reply(200, "OLD!TAIL", "\"v1\"", splitAt = 4,
                    delayBeforeBodyMs = 350L, delayAfterFirstMs = 500L)
            }.use { server ->
                val dir = folder.newFolder()
                val dest = java.io.File(dir, "cancel.bin")
                val part = java.io.File(dir, "cancel.bin.part")
                val result = runCatching {
                    Downloader.download(server.url, dest, preservePartialOnCancel = preserve).collect { progress ->
                        if (progress > 0f && progress < 1f) throw CancellationException("cancel test")
                    }
                }
                assertTrue(result.exceptionOrNull() is CancellationException)
                assertFalse(dest.exists())
                assertEquals(preserve, part.exists())
                assertEquals(preserve, DownloadResumeMetadata.companionFile(part).exists())
                if (preserve) assertEquals("OLD!", part.readText())
            }
        }
    }

    private data class Reply(val code: Int, val body: String, val etag: String? = null,
        val contentRange: String? = null, val declaredLength: Int? = null,
        val contentEncoding: String? = null, val splitAt: Int? = null,
        val delayBeforeBodyMs: Long = 0L, val delayAfterFirstMs: Long = 0L)

    private class LocalHttpServer(private val reply: (Map<String, String>, Int) -> Reply) : AutoCloseable {
        private val socket = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        private val count = AtomicInteger()
        val url: String = "http://127.0.0.1:${socket.localPort}/file"
        private val worker = Thread {
            while (!socket.isClosed) {
                try {
                    socket.accept().use { client ->
                        client.soTimeout = 10_000
                        val reader = BufferedReader(InputStreamReader(client.getInputStream(), Charsets.US_ASCII))
                        reader.readLine() ?: return@use
                        val headers = mutableMapOf<String, String>()
                        while (true) {
                            val line = reader.readLine() ?: break
                            if (line.isEmpty()) break
                            val key = line.substringBefore(':').trim().lowercase()
                            headers[key] = line.substringAfter(':').trim()
                        }
                        val response = reply(headers, count.incrementAndGet())
                        val body = response.body.toByteArray(Charsets.UTF_8)
                        val output = client.getOutputStream()
                        val header = buildString {
                            append("HTTP/1.1 ${response.code} OK\r\n")
                            append("Content-Length: ${response.declaredLength ?: body.size}\r\n")
                            append("Connection: close\r\n")
                            response.etag?.let { append("ETag: $it\r\n") }
                            response.contentRange?.let { append("Content-Range: $it\r\n") }
                            response.contentEncoding?.let { append("Content-Encoding: $it\r\n") }
                            append("\r\n")
                        }
                        output.write(header.toByteArray(Charsets.US_ASCII))
                        if (response.delayBeforeBodyMs > 0L) Thread.sleep(response.delayBeforeBodyMs)
                        val split = response.splitAt
                        if (split != null) {
                            output.write(body, 0, split)
                            output.flush()
                            if (response.delayAfterFirstMs > 0L) Thread.sleep(response.delayAfterFirstMs)
                            output.write(body, split, body.size - split)
                        } else output.write(body)
                        output.flush()
                    }
                } catch (_: SocketException) {
                    if (!socket.isClosed) throw IllegalStateException("HTTP fixture socket failed")
                }
            }
        }.apply { isDaemon = true; start() }

        override fun close() {
            socket.close()
            worker.join(1_000L)
        }
    }
}
