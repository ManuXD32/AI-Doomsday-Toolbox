package com.example.llamadroid.ui.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AgentPreviewDownloadSupportTest {
    private val previewUrl = "http://127.0.0.1:43123/"

    @Test
    fun `same origin blob is accepted while foreign blob is rejected`() {
        assertEquals(
            AgentPreviewDownloadKind.BLOB,
            classifyAgentPreviewDownload(
                "blob:http://127.0.0.1:43123/6f37f7d0-0000-4000-8000-000000000001",
                previewUrl
            )
        )
        assertEquals(
            AgentPreviewDownloadKind.UNSUPPORTED,
            classifyAgentPreviewDownload("blob:http://127.0.0.1:43124/foreign", previewUrl)
        )
        assertEquals(
            AgentPreviewDownloadKind.UNSUPPORTED,
            classifyAgentPreviewDownload("blob:https://127.0.0.1:43123/foreign", previewUrl)
        )
    }

    @Test
    fun `unsupported schemes and credentials cannot cross preview boundary`() {
        val unsupported = listOf(
            "data:text/csv,secret",
            "file:///data/data/example.csv",
            "content://example.provider/download/1",
            "javascript:alert(1)",
            "blob:null/opaque",
            "blob:http://127.0.0.1:43123",
            "blob:http://127.0.0.1%40evil:43123/opaque",
            "blob:http://127.0.0.1:43123@evil.example/opaque"
        )
        unsupported.forEach { url ->
            assertEquals(
                "Expected unsupported URL: $url",
                AgentPreviewDownloadKind.UNSUPPORTED,
                classifyAgentPreviewDownload(url, previewUrl)
            )
        }
    }

    @Test
    fun `only exact origin http is classified as fallback transport`() {
        assertEquals(
            AgentPreviewDownloadKind.SAME_ORIGIN_HTTP,
            classifyAgentPreviewDownload("http://127.0.0.1:43123/results.csv", previewUrl)
        )
        assertEquals(
            AgentPreviewDownloadKind.UNSUPPORTED,
            classifyAgentPreviewDownload("http://localhost:43123/results.csv", previewUrl)
        )
        assertEquals(
            AgentPreviewDownloadKind.UNSUPPORTED,
            classifyAgentPreviewDownload("http://127.0.0.1:43124/results.csv", previewUrl)
        )
    }

    @Test
    fun `filename is basename limited and gets mime extension`() {
        assertEquals(
            "primes.csv",
            sanitizeAgentPreviewDownloadFilename(
                "attachment; filename=\"../../primes\"",
                "text/csv"
            )
        )
        val fallback = sanitizeAgentPreviewDownloadFilename(null, "text/csv")
        assertTrue(fallback.endsWith(".csv"))
        assertTrue(fallback.length <= 96)
        assertFalse(fallback.contains('/'))
        assertFalse(fallback.contains('\\'))
    }

    @Test
    fun `blob script is escaped and uses bounded reader acknowledgements`() {
        val script = buildAgentPreviewBlobExportScript(
            token = "token\"\\\n",
            blobUrl = "blob:http://127.0.0.1:43123/id\"\\\n"
        )
        assertTrue(script.contains("getReader"))
        assertTrue(script.contains("bridge.chunk"))
        assertTrue(script.contains("sequence"))
        assertTrue(script.contains("Promise.resolve"))
        assertTrue(script.contains("var maxBytes = 16777216;"))
        assertFalse(script.contains("arrayBuffer"))
        assertTrue(script.contains("window.location.origin"))
    }

    @Test
    fun `pin script defers only same origin downloadable blob revocation`() {
        val script = buildAgentPreviewDownloadPinScript(previewUrl)

        assertTrue(script.contains("URL.revokeObjectURL"))
        assertTrue(script.contains("HTMLAnchorElement.prototype.click"))
        assertTrue(script.contains("hasAttribute(\"download\")"))
        assertTrue(script.contains("new NativeURL(value).origin === configuredOrigin"))
        assertTrue(script.contains("var maxPins = 8;"))
        assertTrue(script.contains("pinOrder.length >= maxPins"))
        assertTrue(script.contains("deferredRevocation: false"))
        assertTrue(script.contains("record.deferredRevocation = true"))
        assertTrue(script.contains("if (record.deferredRevocation)"))
        assertTrue(script.contains("setTimeout(function() { release(value); }, 120000);"))
        assertTrue(script.contains("clearTimeout(record.timer)"))
        assertTrue(script.contains("window[stateName] = { release: release };"))
    }

    @Test
    fun `pin and release scripts quote configured and blob URLs`() {
        val configured = "http://127.0.0.1:43123/\"\\\n"
        val blob = "blob:http://127.0.0.1:43123/id\"\\\n"

        val pinScript = buildAgentPreviewDownloadPinScript(configured)
        val releaseScript = buildAgentPreviewDownloadReleaseScript(blob)

        assertTrue(pinScript.contains("\\\""))
        assertTrue(releaseScript.contains("__adtPreviewDownloadPins"))
        assertTrue(releaseScript.contains("release("))
        assertFalse(releaseScript.contains("window.location"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `pin script rejects an empty configured preview URL`() {
        buildAgentPreviewDownloadPinScript("")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `release script rejects an empty blob URL`() {
        buildAgentPreviewDownloadReleaseScript("")
    }

    @Test
    fun `known content length must fit the hard limit`() {
        assertTrue(isAgentPreviewDownloadSizeAllowed(-1L))
        assertTrue(isAgentPreviewDownloadSizeAllowed(AGENT_PREVIEW_MAX_DOWNLOAD_BYTES))
        assertFalse(isAgentPreviewDownloadSizeAllowed(AGENT_PREVIEW_MAX_DOWNLOAD_BYTES + 1L))
    }

    @Test
    fun `reported mime is normalized before it influences the destination name`() {
        assertEquals("text/csv", normalizedAgentPreviewDownloadMimeType("TEXT/CSV; charset=utf-8"))
        assertEquals("application/octet-stream", normalizedAgentPreviewDownloadMimeType("javascript:alert(1)"))
    }
}
