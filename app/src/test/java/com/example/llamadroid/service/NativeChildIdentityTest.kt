package com.example.llamadroid.service

import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NativeChildIdentityTest {
    private val proc = kotlin.io.path.createTempDirectory("native-child").toFile()
    private val binary = "/data/app/libllama_server_dotprod.so"

    @After fun cleanup() { proc.deleteRecursively() }

    private fun child(pid: Int, parent: Int, port: Int, executable: String = binary) {
        File(proc, "$pid").mkdirs()
        File(proc, "$pid/stat").writeText("$pid (llama worker) S $parent " + List(20) { "0" }.joinToString(" "))
        File(proc, "$pid/cmdline").writeText("$executable\u0000--port\u0000$port\u0000")
    }

    @Test fun `finds the direct child when Android does not expose task children`() {
        child(101, 77, 49920)
        child(102, 88, 49920)
        assertEquals(101, findNativeChildPid(binary, proc, 77, 49920))
    }

    @Test fun `two servers using the same executable are distinguished by their ports`() {
        child(101, 77, 49920)
        child(102, 77, 49921)
        assertEquals(101, findNativeChildPid(binary, proc, 77, 49920))
        assertNull(findNativeChildPid(binary, proc, 77))
    }

    @Test fun `a same filename path or model argument cannot impersonate the child`() {
        child(101, 77, 49920, "/elsewhere/libllama_server_dotprod.so")
        child(102, 77, 49920, "/system/bin/sh")
        File(proc, "102/cmdline").appendText("$binary\u0000")
        assertNull(findNativeChildPid(binary, proc, 77, 49920))
    }

    @Test fun `a child that has not execed can be discovered on a later observation`() {
        child(101, 77, 49920, "/system/bin/app_process64")
        assertNull(findNativeChildPid(binary, proc, 77, 49920))
        child(101, 77, 49920)
        assertEquals(101, findNativeChildPid(binary, proc, 77, 49920))
    }
}
