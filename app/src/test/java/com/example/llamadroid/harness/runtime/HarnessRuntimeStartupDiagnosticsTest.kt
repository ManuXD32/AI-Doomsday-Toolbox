package com.example.llamadroid.harness.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HarnessRuntimeStartupDiagnosticsTest {
    @Test
    fun `a stack header cannot hide the following syscall failure`() {
        val header = classifyHarnessProcessOutput("node:internal/modules/run_main")
        val failure = classifyHarnessProcessOutput("Error: ENOSYS: function not implemented, rename")!!
        assertEquals("HARNESS_SYSCALL_UNAVAILABLE", preferHarnessProcessDiagnostic(header, failure))
        assertEquals(failure, sanitizeHarnessProcessCode(failure))
        assertEquals(failure, preferHarnessProcessDiagnostic(failure, "HARNESS_NODE_START_FAILED"))
    }

    @Test
    fun `startup output maps only stable native failure classes`() {
        assertEquals(
            "HARNESS_ARCHITECTURE_MISMATCH",
            classifyHarnessProcessOutput("proot: Exec format error")
        )
        assertEquals(
            "HARNESS_NATIVE_MODULE_UNAVAILABLE",
            classifyHarnessProcessOutput("Error loading shared object: invalid ELF")
        )
        assertEquals(
            "HARNESS_NATIVE_MODULE_UNAVAILABLE",
            classifyHarnessProcessOutput("Error [ERR_MODULE_NOT_FOUND]: Cannot find package '@koromix/koffi-linux-arm64'")
        )
        assertEquals(
            "HARNESS_EXECUTABLE_MISSING",
            classifyHarnessProcessOutput("/opt/adt-harness/bin/node: No such file or directory")
        )
        assertEquals(
            "HARNESS_PORT_IN_USE",
            classifyHarnessProcessOutput("listen EADDRINUSE 127.0.0.1")
        )
        assertNull(classifyHarnessProcessOutput("user-facing session title"))
        assertEquals("HARNESS_BROKER_OWNER_UNAVAILABLE",
            classifyHarnessProcessOutput("HARNESS_BROKER_OWNER_UNAVAILABLE"))
        assertEquals("HARNESS_BROKER_LIMIT_FAILED",
            classifyHarnessProcessOutput("proot_broker: setrlimit: Operation not permitted"))
        assertEquals("HARNESS_BROKER_SUPERVISION_FAILED",
            classifyHarnessProcessOutput("proot_broker: PR_SET_CHILD_SUBREAPER: Operation not permitted"))
    }

    @Test
    fun `known exit statuses are classified without retaining process output`() {
        assertEquals("HARNESS_PROCESS_KILLED", classifyHarnessProcessExit(137))
        assertEquals("HARNESS_PROCESS_CRASHED", classifyHarnessProcessExit(139))
        assertEquals("HARNESS_EXECUTABLE_MISSING", classifyHarnessProcessExit(127))
        assertEquals("HARNESS_PROCESS_TERMINATED", classifyHarnessProcessExit(143))
        assertNull(classifyHarnessProcessExit(0))
        assertNull(sanitizeHarnessProcessCode("raw stderr must never be durable"))
    }
}
