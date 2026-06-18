package com.example.llamadroid.data

import org.junit.Assert.assertEquals
import org.junit.Test

class RemoteBackendUrlSupportTest {

    @Test
    fun blankInputFallsBackToDefaultEndpoint() {
        val parsed = RemoteBackendUrlSupport.parseForStorage("", defaultPort = 8080)

        assertEquals("http://localhost:8080", parsed.normalizedUrl)
        assertEquals("localhost", parsed.host)
        assertEquals(8080, parsed.port)
    }

    @Test
    fun plainHostGetsSchemeAndDefaultPort() {
        val parsed = RemoteBackendUrlSupport.parseForStorage("demo.local", defaultPort = 11434)

        assertEquals("http://demo.local:11434", parsed.normalizedUrl)
        assertEquals("demo.local", parsed.host)
        assertEquals(11434, parsed.port)
    }

    @Test
    fun hostWithPortIsPreserved() {
        val parsed = RemoteBackendUrlSupport.parseForStorage("demo.local:9090", defaultPort = 11434)

        assertEquals("http://demo.local:9090", parsed.normalizedUrl)
        assertEquals("demo.local", parsed.host)
        assertEquals(9090, parsed.port)
    }

    @Test
    fun explicitSchemeAndPathArePreservedAndMissingPortGetsDefault() {
        val parsed = RemoteBackendUrlSupport.parseForStorage("https://demo.local/custom/path", defaultPort = 8080)

        assertEquals("https://demo.local:8080/custom/path", parsed.normalizedUrl)
        assertEquals("demo.local", parsed.host)
        assertEquals(8080, parsed.port)
    }

    @Test
    fun queryAndTrailingSlashArePreserved() {
        val parsed = RemoteBackendUrlSupport.parseForStorage("http://demo.local/service/?q=1", defaultPort = 8080)

        assertEquals("http://demo.local:8080/service/?q=1", parsed.normalizedUrl)
        assertEquals("demo.local", parsed.host)
        assertEquals(8080, parsed.port)
    }

    @Test
    fun malformedInputFallsBackAtActionTime() {
        val parsed = RemoteBackendUrlSupport.parseForStorage("://bad url", defaultPort = 8080)

        assertEquals("http://localhost:8080", parsed.normalizedUrl)
        assertEquals("localhost", parsed.host)
        assertEquals(8080, parsed.port)
    }
}
