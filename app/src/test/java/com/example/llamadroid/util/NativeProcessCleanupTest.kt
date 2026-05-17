package com.example.llamadroid.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.io.path.createTempDirectory

class NativeProcessCleanupTest {
    @Test
    fun processFilterOnlyMatchesKnownLlamaServers() {
        assertTrue(NativeProcessCleanup.isKnownLlamaServerCommand("/data/app/lib/arm64/libllama_server_dotprod.so --port 8080"))
        assertTrue(NativeProcessCleanup.isKnownLlamaServerCommand("/data/app/lib/arm64/llama-server --port 8080"))
        assertFalse(NativeProcessCleanup.isKnownLlamaServerCommand("/data/data/com.termux/files/usr/bin/llama-server --port 8080"))
        assertFalse(NativeProcessCleanup.isKnownLlamaServerCommand("/system/bin/sh"))
    }

    @Test
    fun procScanOnlyReturnsSameUidKnownServers() {
        val root = createTempDirectory(prefix = "proc-test").toFile()
        try {
            writeProc(root, pid = 10, uid = 10042, cmdline = "/data/app/lib/arm64/libllama_server_dotprod.so\u0000--port\u00008080")
            writeProc(root, pid = 11, uid = 10043, cmdline = "/data/app/lib/arm64/libllama_server_dotprod.so\u0000--port\u00008080")
            writeProc(root, pid = 12, uid = 10042, cmdline = "/system/bin/sh\u0000-c\u0000echo")

            val candidates = NativeProcessCleanup.findSameUidLlamaServers(
                procRoot = root,
                myPid = 99,
                myUid = 10042
            )

            assertEquals(listOf(10), candidates.map { it.pid })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun procScanCanFilterKnownServersByPort() {
        val root = createTempDirectory(prefix = "proc-test").toFile()
        try {
            writeProc(root, pid = 10, uid = 10042, cmdline = "/data/app/lib/arm64/libllama_server_dotprod.so\u0000--port\u00008080")
            writeProc(root, pid = 11, uid = 10042, cmdline = "/data/app/lib/arm64/libllama_server_dotprod.so\u0000--port\u00008081")

            val candidates = NativeProcessCleanup.findSameUidLlamaServers(
                procRoot = root,
                myPid = 99,
                myUid = 10042,
                port = 8081
            )

            assertEquals(listOf(11), candidates.map { it.pid })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun portFilterAcceptsCommonPortFlagForms() {
        assertTrue(NativeProcessCleanup.commandLineHasPort("llama-server --port=8081", 8081))
        assertTrue(NativeProcessCleanup.commandLineHasPort("llama-server -p 8081", 8081))
        assertTrue(NativeProcessCleanup.commandLineHasPort("llama-server --http-port 8081", 8081))
        assertFalse(NativeProcessCleanup.commandLineHasPort("llama-server --port 8080", 8081))
    }

    @Test
    fun stuckPortCleanupCandidatesExcludeExplicitDifferentPorts() {
        val root = createTempDirectory(prefix = "proc-test").toFile()
        try {
            writeProc(root, pid = 10, uid = 10042, cmdline = "/data/app/lib/arm64/libllama_server_dotprod.so\u0000--port\u00008080")
            writeProc(root, pid = 11, uid = 10042, cmdline = "/data/app/lib/arm64/libllama_server_dotprod.so\u0000--port\u00008081")
            writeProc(root, pid = 12, uid = 10042, cmdline = "/data/app/lib/arm64/libllama_server_dotprod.so")

            val candidates = NativeProcessCleanup.findSameUidLlamaServersForStuckPort(
                procRoot = root,
                myPid = 99,
                myUid = 10042,
                port = 8081
            )

            assertEquals(listOf(11, 12), candidates.map { it.pid })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun procNetScanFindsSameUidListenerInodeForPort() {
        val root = createTempDirectory(prefix = "proc-test").toFile()
        try {
            writeTcpListener(root, portHex = "1F91", uid = 10042, inode = "12345")

            val inodes = NativeProcessCleanup.findListeningSocketInodes(
                procRoot = root,
                myUid = 10042,
                port = 8081
            )

            assertEquals(setOf("12345"), inodes)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun portListenerScanMatchesSameUidPidBySocketInodeEvenWithBlankCommandLine() {
        val root = createTempDirectory(prefix = "proc-test").toFile()
        try {
            writeTcpListener(root, portHex = "1F91", uid = 10042, inode = "12345")
            writeProcWithSocket(root, pid = 10, uid = 10042, cmdline = "", inode = "12345")
            writeProcWithSocket(root, pid = 11, uid = 10043, cmdline = "", inode = "12345")
            writeProc(root, pid = 12, uid = 10042, cmdline = "/system/bin/sh")

            val candidates = NativeProcessCleanup.findSameUidPortListeners(
                procRoot = root,
                myPid = 99,
                myUid = 10042,
                port = 8081
            )

            assertEquals(listOf(10), candidates.map { it.pid })
            assertEquals("socket", candidates.single().source)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun describeSameUidPortOccupationReportsVisibleListener() {
        val root = createTempDirectory(prefix = "proc-test").toFile()
        try {
            writeTcpListener(root, portHex = "1F91", uid = 10042, inode = "12345")
            writeProcWithSocket(
                root = root,
                pid = 10,
                uid = 10042,
                cmdline = "/data/app/lib/arm64/libllama_server_dotprod.so\u0000--port\u00008081",
                inode = "12345"
            )

            val description = NativeProcessCleanup.describeSameUidPortOccupationSync(
                port = 8081,
                procRoot = root,
                myPid = 99,
                myUid = 10042
            )

            assertTrue(description.contains("pid=10"))
            assertTrue(description.contains("libllama_server_dotprod.so"))
        } finally {
            root.deleteRecursively()
        }
    }

    private fun writeProc(root: File, pid: Int, uid: Int, cmdline: String) {
        val dir = File(root, pid.toString()).apply { mkdirs() }
        File(dir, "cmdline").writeBytes(cmdline.toByteArray())
        File(dir, "status").writeText("Name:\ttest\nUid:\t$uid\t$uid\t$uid\t$uid\n")
    }

    private fun writeProcWithSocket(root: File, pid: Int, uid: Int, cmdline: String, inode: String) {
        writeProc(root, pid, uid, cmdline)
        val fdDir = File(root, "$pid/fd").apply { mkdirs() }
        Files.createSymbolicLink(File(fdDir, "3").toPath(), File("socket:[$inode]").toPath())
    }

    private fun writeTcpListener(root: File, portHex: String, uid: Int, inode: String) {
        val netDir = File(root, "net").apply { mkdirs() }
        File(netDir, "tcp").writeText(
            "  sl  local_address rem_address   st tx_queue rx_queue tr tm->when retrnsmt   uid  timeout inode\n" +
                "   0: 0100007F:$portHex 00000000:0000 0A 00000000:00000000 00:00000000 00000000 $uid 0 $inode 1 0000000000000000 100 0 0 10 0\n"
        )
    }
}
