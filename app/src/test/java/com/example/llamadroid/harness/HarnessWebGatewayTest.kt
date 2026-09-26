package com.example.llamadroid.harness

import com.example.llamadroid.harness.runtime.HarnessEndpoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class HarnessWebGatewayTest {
    @Test
    fun authenticatedIndexIsBootstrappedForEveryHttpFraming() {
        val html = "<html><head><script>start()</script></head><body>settings</body></html>"
        val bytes = html.toByteArray()
        val fixtures = listOf(
            "Content-Length: ${bytes.size}\r\n" to html,
            "Transfer-Encoding: chunked\r\n" to "${bytes.size.toString(16)};part=1\r\n$html\r\n0\r\nX-Test: done\r\n\r\n",
            "" to html,
        )
        for ((framing, body) in fixtures) {
            ServerSocket(0).use { upstream ->
                val worker = Executors.newSingleThreadExecutor()
                val request = worker.submit<String> {
                    upstream.accept().use { socket ->
                        socket.soTimeout = 5000
                        val reader = socket.getInputStream().bufferedReader()
                        val lines = buildList {
                            while (true) {
                                val line = reader.readLine() ?: break
                                if (line.isEmpty()) break
                                add(line)
                            }
                        }
                        socket.getOutputStream().write(
                            ("HTTP/1.1 200 OK\r\nContent-Type: text/html\r\n" + framing +
                                "Cache-Control: public\r\nConnection: close\r\n\r\n" + body).toByteArray(),
                        )
                        lines.joinToString("\n")
                    }
                }
                val proxy = HarnessLanWebProxy(HarnessEndpoint("http://127.0.0.1:${upstream.localPort}", "harness=test-cookie"), "test-access")
                try {
                    proxy.start()
                    val connection = URL("http://127.0.0.1:${proxy.port}/?adt_lan_token=test-access").openConnection() as HttpURLConnection
                    connection.connectTimeout = 5000
                    connection.readTimeout = 5000
                    try {
                        assertEquals(200, connection.responseCode)
                        val patched = connection.inputStream.bufferedReader().use { it.readText() }
                        assertTrue(patched.indexOf("__ADT_AUTHENTICATED_GATEWAY__=true") < patched.indexOf("start()"))
                        assertTrue(patched.contains("<body>settings</body>"))
                        assertEquals("no-store", connection.getHeaderField("Cache-Control"))
                        assertTrue(connection.getHeaderField("Set-Cookie").contains("HttpOnly"))
                        val forwarded = request.get(5, TimeUnit.SECONDS)
                        assertFalse(forwarded.contains("adt_lan_token"))
                        assertTrue(forwarded.contains("Cookie: harness=test-cookie"))
                    } finally { connection.disconnect() }
                } finally {
                    proxy.stop()
                    worker.shutdownNow()
                }
            }
        }
    }

    @Test
    fun unauthorizedBrowserCannotReachUpstream() {
        ServerSocket(0).use { upstream ->
            val proxy = HarnessLanWebProxy(HarnessEndpoint("http://127.0.0.1:${upstream.localPort}", "harness=test-cookie"), "test-access")
            try {
                proxy.start()
                val connection = URL("http://127.0.0.1:${proxy.port}/").openConnection() as HttpURLConnection
                connection.readTimeout = 5000
                try {
                    assertEquals(401, connection.responseCode)
                    assertFalse(connection.errorStream.bufferedReader().use { it.readText() }.contains("__ADT_AUTHENTICATED_GATEWAY__"))
                } finally { connection.disconnect() }
            } finally { proxy.stop() }
        }
    }

    @Test
    fun indexDecoderRejectsOversizeAndTruncatedChunks() {
        assertThrows(IllegalArgumentException::class.java) {
            readHarnessWebIndexBody(ByteArrayInputStream("4\r\n1234\r\n0\r\n\r\n".toByteArray()), null, true, 3)
        }
        assertThrows(java.io.EOFException::class.java) {
            readHarnessWebIndexBody(ByteArrayInputStream("4\r\n12".toByteArray()), null, true, 10)
        }
    }

    @Test
    fun nativeAndLanBootstrapHaveTheSameCapabilityBeforeBootScripts() {
        val html = "<html><HEAD><script>boot()</script></HEAD></html>"
        val native = patchHarnessWebViewIndexForAndroid(html)
        assertEquals(HarnessLanAccessSupport.patchIndexForLan(html), native)
        assertEquals(native, patchHarnessWebViewIndexForAndroid(native))
        assertTrue(native.indexOf("__ADT_AUTHENTICATED_GATEWAY__") < native.indexOf("boot()"))
    }
}
