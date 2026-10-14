package com.example.llamadroid.service.delegation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class DelegateHarnessSupportTest {
    private fun rejects(path: String) {
        try {
            DelegateHarnessSupport.requireProjectRelative(path)
            fail("expected rejection of $path")
        } catch (e: DelegationHarnessException) {
            assertEquals("PATH_OUTSIDE_PROJECT", e.code)
        }
    }

    @Test fun pathValidation() {
        assertEquals(".", DelegateHarnessSupport.requireProjectRelative(null))
        assertEquals("src/a.kt", DelegateHarnessSupport.requireProjectRelative("./src//a.kt"))
        listOf("/etc/passwd", "../x", "a/../../b", "a\\b", "C:\\x", "~/x", "a\u0000b").forEach(::rejects)
    }

    @Test fun inputNames() {
        assertEquals("report.pdf", DelegateHarnessSupport.sanitizeInputName("/sdcard/Download/report.pdf"))
        assertEquals("b_.txt", DelegateHarnessSupport.sanitizeInputName("a/b?.txt"))
        assertEquals("file", DelegateHarnessSupport.sanitizeInputName("..."))
        assertTrue(DelegateHarnessSupport.sanitizeInputName("n".repeat(200) + ".png").let { it.length <= 80 && it.endsWith(".png") })
        assertEquals("a-2.txt", DelegateHarnessSupport.uniqueName("a.txt", setOf("A.txt")))
        assertEquals("a-3.txt", DelegateHarnessSupport.uniqueName("a.txt", setOf("a.txt", "a-2.txt")))
        assertEquals("b.txt", DelegateHarnessSupport.uniqueName("b.txt", setOf("a.txt")))
    }

    @Test fun binaryDetectionAndUtf8Chunks() {
        assertTrue(DelegateHarnessSupport.looksBinary(byteArrayOf(1, 0, 3)))
        assertFalse(DelegateHarnessSupport.looksBinary("hello\nworld".toByteArray()))
        val bytes = "añb€".toByteArray(Charsets.UTF_8)
        // Cut inside the euro sign (3 bytes): the partial sequence must be dropped.
        assertEquals("añb", DelegateHarnessSupport.decodeUtf8Chunk(bytes, bytes.size - 1))
        assertEquals("añb€", DelegateHarnessSupport.decodeUtf8Chunk(bytes))
    }

    @Test fun clampAndClip() {
        assertEquals(6000, DelegateHarnessSupport.clampChars(99999))
        assertEquals(1, DelegateHarnessSupport.clampChars(0))
        assertEquals(4000, DelegateHarnessSupport.clampChars(null))
        assertEquals(10, DelegateHarnessSupport.clip("x".repeat(50), 10).length)
    }

    @Test fun startFailureMapping() {
        assertEquals("RUNTIME_NEEDS_APP", DelegateHarnessSupport.mapStartFailure(null,
            "android.app.ForegroundServiceStartNotAllowedException", "x").code)
        assertEquals("HARNESS_CLEANUP_IN_PROGRESS", DelegateHarnessSupport.mapStartFailure("HARNESS_CLEANUP_IN_PROGRESS", null, null).code)
        assertEquals("HARNESS_RUNTIME_REQUIRED", DelegateHarnessSupport.mapStartFailure("HARNESS_RUNTIME_REQUIRED", null, null).code)
        assertEquals("HARNESS_START_FAILED", DelegateHarnessSupport.mapStartFailure(null, null, null).code)
        assertTrue(DelegationHarnessException("X", "msg").toToolError().startsWith("tool_error: X: msg"))
    }

    @Test fun permissionPresetPicking() {
        assertEquals("workspace-write", DelegateHarnessDriver.pickPermissionPreset("workspace-write", listOf("read-only", "workspace-write")))
        assertEquals("full-danger", DelegateHarnessDriver.pickPermissionPreset("danger-full-access", listOf("ask", "full-danger")))
        assertNull(DelegateHarnessDriver.pickPermissionPreset("danger-full-access", listOf("ask")))
        assertEquals("workspace-write", DelegateHarnessDriver.pickPermissionPreset("workspace-write", emptyList()))
    }
}
