package com.example.llamadroid.harness

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class HarnessWebViewBootDiagnosticsTest {
    @Test
    fun `live graph with boot overlay remains pending until mounted UI exists`() {
        val raw = "\"{\\\"graphEntries\\\":58,\\\"graphBatches\\\":3,\\\"loaderMode\\\":\\\"live\\\",\\\"pendingRegistrations\\\":0,\\\"pluginScripts\\\":3,\\\"mountedUi\\\":false,\\\"bootOverlay\\\":true,\\\"failedOverlay\\\":false,\\\"bootSpinner\\\":true}\""

        val diagnostic = requireNotNull(parseHarnessWebViewBootProbe(raw)).toDiagnostic()

        assertEquals("pending", diagnostic.outcome)
        assertEquals("HARNESS_WEBUI_BOOT_PENDING", diagnostic.code)
        assertEquals(false, diagnostic.mountedUi)
        assertEquals(true, diagnostic.bootOverlay)
        assertTrue(buildHarnessWebViewBootProbeScript().contains("data-dsh-boot"))
        assertTrue(buildHarnessWebViewBootProbeScript().contains("data-dsh-boot-spinner"))
    }

    @Test
    fun `mounted UI with dismissed overlay is the only ready state`() {
        val raw = "\"{\\\"graphEntries\\\":58,\\\"graphBatches\\\":3,\\\"loaderMode\\\":\\\"live\\\",\\\"pendingRegistrations\\\":0,\\\"pluginScripts\\\":3,\\\"mountedUi\\\":true,\\\"bootOverlay\\\":false,\\\"failedOverlay\\\":false,\\\"bootSpinner\\\":false}\""

        val diagnostic = requireNotNull(parseHarnessWebViewBootProbe(raw)).toDiagnostic()

        assertEquals(58, diagnostic.graphEntries)
        assertEquals(3, diagnostic.graphBatches)
        assertEquals("live", diagnostic.loaderMode)
        assertEquals("ready", diagnostic.outcome)
        assertEquals(false, diagnostic.bootOverlay)
    }

    @Test
    fun `failed frontend overlay is distinct from pending and ready`() {
        val raw = "\"{\\\"graphEntries\\\":58,\\\"graphBatches\\\":3,\\\"loaderMode\\\":\\\"live\\\",\\\"pendingRegistrations\\\":0,\\\"pluginScripts\\\":3,\\\"mountedUi\\\":false,\\\"bootOverlay\\\":true,\\\"failedOverlay\\\":true,\\\"bootSpinner\\\":false}\""

        val diagnostic = requireNotNull(parseHarnessWebViewBootProbe(raw)).toDiagnostic()

        assertEquals("failure", diagnostic.outcome)
        assertEquals(HARNESS_WEBUI_BOOT_FAILED_OVERLAY, diagnostic.code)
        assertEquals(true, diagnostic.failedOverlay)
    }

    @Test
    fun `live graph still fails at bounded deadline when overlay never dismisses`() {
        val raw = "\"{\\\"graphEntries\\\":58,\\\"graphBatches\\\":3,\\\"loaderMode\\\":\\\"live\\\",\\\"pendingRegistrations\\\":0,\\\"pluginScripts\\\":3,\\\"mountedUi\\\":false,\\\"bootOverlay\\\":true,\\\"failedOverlay\\\":false,\\\"bootSpinner\\\":true}\""

        val diagnostic = requireNotNull(parseHarnessWebViewBootProbe(raw)).toDiagnostic(deadlineReached = true)

        assertEquals("failure", diagnostic.outcome)
        assertEquals(HARNESS_WEBUI_BOOT_TIMEOUT, diagnostic.code)
    }

    @Test
    fun `missing graph is pending before deadline and failure after deadline`() {
        val raw = "\"{\\\"graphEntries\\\":null,\\\"graphBatches\\\":null,\\\"loaderMode\\\":null,\\\"pendingRegistrations\\\":null,\\\"pluginScripts\\\":null,\\\"mountedUi\\\":false,\\\"bootOverlay\\\":false,\\\"failedOverlay\\\":false,\\\"bootSpinner\\\":false}\""
        val snapshot = requireNotNull(parseHarnessWebViewBootProbe(raw))

        assertEquals("pending", snapshot.toDiagnostic().outcome)
        assertEquals("failure", snapshot.toDiagnostic(deadlineReached = true).outcome)
        assertEquals("HARNESS_WEBUI_BOOT_GRAPH_MISSING", snapshot.toDiagnostic(deadlineReached = true).code)
    }

    @Test
    fun `module import failures become a terminal boot diagnostic`() {
        val raw = "\"{\\\"graphEntries\\\":58,\\\"graphBatches\\\":3,\\\"loaderMode\\\":\\\"live\\\",\\\"pendingRegistrations\\\":1,\\\"pluginScripts\\\":3,\\\"mountedUi\\\":false,\\\"bootOverlay\\\":true,\\\"failedOverlay\\\":false,\\\"bootSpinner\\\":true,\\\"essentialAssets\\\":7,\\\"stylesheetAssets\\\":2,\\\"moduleImportFailures\\\":1}\""

        val diagnostic = requireNotNull(parseHarnessWebViewBootProbe(raw)).toDiagnostic()

        assertEquals("failure", diagnostic.outcome)
        assertEquals(HARNESS_WEBUI_BOOT_IMPORT_FAILED, diagnostic.code)
        assertEquals(7, diagnostic.essentialAssets)
        assertEquals(2, diagnostic.stylesheetAssets)
        assertEquals(1, diagnostic.moduleImportFailures)
    }

    @Test
    fun `probe exposes resource graph and import failure counters`() {
        val script = buildHarnessWebViewBootProbeScript()

        assertTrue(script.contains("performance.getEntriesByType(\"resource\")"))
        assertTrue(script.contains("graph.importErrors"))
        assertTrue(script.contains("/assets/"))
        assertTrue(script.contains("/plugins/"))
    }

    @Test
    fun `android index patch provides a boot promise fallback without changing ordinary pages`() {
        val source = "<script>(globalThis.__DSH_BOOT_READY__ ??= Promise.withResolvers()).resolve()</script>"

        val patched = patchHarnessWebViewIndexForAndroid(source)

        assertFalse(patched.contains("??= Promise.withResolvers()"))
        assertTrue(patched.contains("Promise.withResolvers ? Promise.withResolvers()"))
        assertTrue(patched.contains("new Promise"))
        assertTrue(patchHarnessWebViewIndexForAndroid("<html></html>").contains("__ADT_AUTHENTICATED_GATEWAY__=true"))
    }

    @Test
    fun `only authenticated static boot paths are eligible for interception`() {
        assertTrue(isHarnessWebViewStaticPath("/"))
        assertTrue(isHarnessWebViewStaticPath("/assets/index.js"))
        assertTrue(isHarnessWebViewStaticPath("/plugins/bridge.js?rev=1"))
        assertFalse(isHarnessWebViewStaticPath("/api/session/list"))
        assertFalse(isHarnessWebViewStaticPath("/api/remote.mux"))
    }

    @Test
    fun `web resource error metadata is bounded and journal-safe`() {
        assertEquals(-6, boundedHarnessWebResourceErrorCode(-6))
        assertEquals(6, harnessWebResourceErrorMagnitude(-6))
        assertEquals(null, boundedHarnessWebResourceErrorCode(-101))
        assertEquals(null, boundedHarnessWebResourceErrorCode(7))

        val diagnostic = HarnessWebViewBootDiagnostic(
            outcome = "failure",
            code = "HARNESS_WEBUI_PLUGIN_LOAD_FAILED",
            resourceKind = "plugin",
            webResourceErrorCode = boundedHarnessWebResourceErrorCode(-6),
        )
        assertEquals(-6, diagnostic.webResourceErrorCode)
    }

    @Test
    fun `native static failures expose only bounded transport metadata`() {
        assertEquals("timeout", classifyHarnessNativeStaticError(SocketTimeoutException()))
        assertEquals("dns", classifyHarnessNativeStaticError(UnknownHostException()))
        assertEquals("connect", classifyHarnessNativeStaticError(ConnectException()))
        assertEquals("io", classifyHarnessNativeStaticError(IOException()))
        assertEquals("unknown", classifyHarnessNativeStaticError(IllegalStateException()))
        assertEquals("timeout", classifyHarnessNativeStaticError(IOException(SocketTimeoutException())))
        assertEquals("timeout", boundedHarnessNativeStaticErrorKind("TIMEOUT"))
        assertNull(boundedHarnessNativeStaticErrorKind("java.net.SocketTimeoutException"))
        assertEquals(60_000, boundedHarnessNativeStaticElapsedMs(99_999L))
        assertEquals(0, boundedHarnessNativeStaticElapsedMs(-1L))
        assertNull(boundedHarnessNativeStaticElapsedMs(null))

        val diagnostic = HarnessWebViewBootDiagnostic(
            outcome = "failure",
            code = HARNESS_WEBVIEW_STATIC_INTERCEPT_FAILED,
            resourceKind = "plugin",
            nativeStaticErrorKind = "timeout",
            nativeStaticElapsedMs = 15_000,
        )
        assertEquals("timeout", diagnostic.nativeStaticErrorKind)
        assertEquals(15_000, diagnostic.nativeStaticElapsedMs)
    }
}
