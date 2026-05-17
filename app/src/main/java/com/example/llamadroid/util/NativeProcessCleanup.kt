package com.example.llamadroid.util

import android.os.Process
import android.system.Os
import android.system.OsConstants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files

data class NativeProcessCandidate(
    val pid: Int,
    val uid: Int,
    val commandLine: String,
    val source: String = "cmdline"
)

object NativeProcessCleanup {
    private val llamaServerMarkers = listOf(
        "libllama_server_",
        "libllama-server",
        "llama-server",
        "llama_server"
    )

    suspend fun cleanupSameUidLlamaServers(reason: String, port: Int? = null): Int = withContext(Dispatchers.IO) {
        cleanupSameUidLlamaServersSync(reason, port = port)
    }

    suspend fun cleanupSameUidPortListeners(reason: String, port: Int): Int = withContext(Dispatchers.IO) {
        cleanupSameUidPortListenersSync(reason, port = port)
    }

    suspend fun cleanupSameUidLlamaServersForStuckPort(reason: String, port: Int): Int = withContext(Dispatchers.IO) {
        cleanupSameUidLlamaServersForStuckPortSync(reason, port = port)
    }

    fun cleanupSameUidLlamaServersSync(
        reason: String,
        graceMs: Long = 1_500L,
        forceMs: Long = 1_000L,
        port: Int? = null,
        procRoot: File = File("/proc"),
        myPid: Int = Process.myPid(),
        myUid: Int = Process.myUid()
    ): Int {
        val candidates = findSameUidLlamaServers(procRoot, myPid, myUid, port)
        if (candidates.isEmpty()) return 0

        DebugLog.log(
            "[NativeProcessCleanup] Found ${candidates.size} stale llama-server process(es) for $reason: " +
                candidates.joinToString { "${it.pid}:${it.commandLine.take(80)}" }
        )

        candidates.forEach { candidate ->
            runCatching { Os.kill(candidate.pid, OsConstants.SIGTERM) }
                .onFailure { DebugLog.log("[NativeProcessCleanup] SIGTERM failed for ${candidate.pid}: ${it.message}") }
        }
        sleepQuietly(graceMs)

        val stillAlive = candidates.filter { File(procRoot, it.pid.toString()).exists() }
        stillAlive.forEach { candidate ->
            runCatching { Os.kill(candidate.pid, OsConstants.SIGKILL) }
                .onFailure { DebugLog.log("[NativeProcessCleanup] SIGKILL failed for ${candidate.pid}: ${it.message}") }
        }
        if (stillAlive.isNotEmpty()) sleepQuietly(forceMs)
        return candidates.size
    }

    fun cleanupSameUidPortListenersSync(
        reason: String,
        port: Int,
        graceMs: Long = 1_500L,
        forceMs: Long = 1_000L,
        procRoot: File = File("/proc"),
        myPid: Int = Process.myPid(),
        myUid: Int = Process.myUid()
    ): Int {
        val candidates = findSameUidPortListeners(procRoot, myPid, myUid, port)
        if (candidates.isEmpty()) return 0

        DebugLog.log(
            "[NativeProcessCleanup] Found ${candidates.size} same-UID listener(s) on port $port for $reason: " +
                candidates.joinToString { "${it.pid}:${it.commandLine.take(80)}" }
        )

        candidates.forEach { candidate ->
            runCatching { Os.kill(candidate.pid, OsConstants.SIGTERM) }
                .onFailure { DebugLog.log("[NativeProcessCleanup] SIGTERM failed for ${candidate.pid}: ${it.message}") }
        }
        sleepQuietly(graceMs)

        val stillAlive = candidates.filter { File(procRoot, it.pid.toString()).exists() }
        stillAlive.forEach { candidate ->
            runCatching { Os.kill(candidate.pid, OsConstants.SIGKILL) }
                .onFailure { DebugLog.log("[NativeProcessCleanup] SIGKILL failed for ${candidate.pid}: ${it.message}") }
        }
        if (stillAlive.isNotEmpty()) sleepQuietly(forceMs)
        return candidates.size
    }

    fun cleanupSameUidLlamaServersForStuckPortSync(
        reason: String,
        port: Int,
        graceMs: Long = 1_500L,
        forceMs: Long = 1_000L,
        procRoot: File = File("/proc"),
        myPid: Int = Process.myPid(),
        myUid: Int = Process.myUid()
    ): Int {
        val candidates = findSameUidLlamaServersForStuckPort(procRoot, myPid, myUid, port)
        if (candidates.isEmpty()) return 0

        DebugLog.log(
            "[NativeProcessCleanup] Found ${candidates.size} same-UID llama-server process(es) for stuck port $port, $reason: " +
                candidates.joinToString { "${it.pid}:${it.commandLine.take(80)}" }
        )

        candidates.forEach { candidate ->
            runCatching { Os.kill(candidate.pid, OsConstants.SIGTERM) }
                .onFailure { DebugLog.log("[NativeProcessCleanup] SIGTERM failed for ${candidate.pid}: ${it.message}") }
        }
        sleepQuietly(graceMs)

        val stillAlive = candidates.filter { File(procRoot, it.pid.toString()).exists() }
        stillAlive.forEach { candidate ->
            runCatching { Os.kill(candidate.pid, OsConstants.SIGKILL) }
                .onFailure { DebugLog.log("[NativeProcessCleanup] SIGKILL failed for ${candidate.pid}: ${it.message}") }
        }
        if (stillAlive.isNotEmpty()) sleepQuietly(forceMs)
        return candidates.size
    }

    fun describeSameUidPortOccupationSync(
        port: Int,
        procRoot: File = File("/proc"),
        myPid: Int = Process.myPid(),
        myUid: Int = Process.myUid()
    ): String {
        val listeners = findSameUidPortListeners(procRoot, myPid, myUid, port)
        val llamaServers = findSameUidLlamaServers(procRoot, myPid, myUid, port)
        return (listeners + llamaServers)
            .distinctBy { it.pid }
            .joinToString("; ") { candidate ->
                "pid=${candidate.pid}, source=${candidate.source}, cmd=${candidate.commandLine.take(120)}"
            }
    }

    internal fun findSameUidLlamaServers(
        procRoot: File,
        myPid: Int,
        myUid: Int,
        port: Int? = null
    ): List<NativeProcessCandidate> {
        val pidDirs = procRoot.listFiles { file -> file.isDirectory && file.name.all { it.isDigit() } }
            ?: return emptyList()
        return pidDirs.mapNotNull { pidDir ->
            val pid = pidDir.name.toIntOrNull() ?: return@mapNotNull null
            if (pid == myPid) return@mapNotNull null
            val commandLine = readCommandLine(File(pidDir, "cmdline"))
            if (!isKnownLlamaServerCommand(commandLine)) return@mapNotNull null
            if (port != null && !commandLineHasPort(commandLine, port)) return@mapNotNull null
            val uid = parseUid(File(pidDir, "status").readTextOrNull() ?: return@mapNotNull null)
                ?: return@mapNotNull null
            if (uid != myUid) return@mapNotNull null
            NativeProcessCandidate(pid = pid, uid = uid, commandLine = commandLine)
        }.sortedBy { it.pid }
    }

    internal fun findSameUidLlamaServersForStuckPort(
        procRoot: File,
        myPid: Int,
        myUid: Int,
        port: Int
    ): List<NativeProcessCandidate> =
        findSameUidLlamaServers(procRoot, myPid, myUid)
            .filter { candidate ->
                commandLineHasPort(candidate.commandLine, port) ||
                    !commandLineHasAnyPort(candidate.commandLine)
            }

    internal fun findSameUidPortListeners(
        procRoot: File,
        myPid: Int,
        myUid: Int,
        port: Int
    ): List<NativeProcessCandidate> {
        val listenerInodes = findListeningSocketInodes(procRoot, myUid, port)
        if (listenerInodes.isEmpty()) return emptyList()

        val pidDirs = procRoot.listFiles { file -> file.isDirectory && file.name.all { it.isDigit() } }
            ?: return emptyList()
        return pidDirs.mapNotNull { pidDir ->
            val pid = pidDir.name.toIntOrNull() ?: return@mapNotNull null
            if (pid == myPid) return@mapNotNull null
            val uid = parseUid(File(pidDir, "status").readTextOrNull() ?: return@mapNotNull null)
                ?: return@mapNotNull null
            if (uid != myUid) return@mapNotNull null
            if (!pidOwnsAnySocketInode(pidDir, listenerInodes)) return@mapNotNull null
            val commandLine = readCommandLine(File(pidDir, "cmdline"))
                .ifBlank { "same-UID listener on port $port" }
            NativeProcessCandidate(
                pid = pid,
                uid = uid,
                commandLine = commandLine,
                source = "socket"
            )
        }.sortedBy { it.pid }
    }

    internal fun findListeningSocketInodes(procRoot: File, myUid: Int, port: Int): Set<String> {
        return listOf(File(procRoot, "net/tcp"), File(procRoot, "net/tcp6"))
            .flatMap { parseTcpListenerInodes(it, myUid, port) }
            .toSet()
    }

    internal fun isKnownLlamaServerCommand(commandLine: String): Boolean {
        val normalized = commandLine.lowercase()
        if (normalized.isBlank()) return false
        if ("termux" in normalized) return false
        return llamaServerMarkers.any { it in normalized }
    }

    internal fun commandLineHasPort(commandLine: String, port: Int): Boolean =
        Regex("""(?:^|\s)(?:--port|-p|--listen-port|--http-port)(?:\s+|=)$port(?:\s|$)""")
            .containsMatchIn(commandLine)

    internal fun commandLineHasAnyPort(commandLine: String): Boolean =
        Regex("""(?:^|\s)(?:--port|-p|--listen-port|--http-port)(?:\s+|=)\d+(?:\s|$)""")
            .containsMatchIn(commandLine)

    internal fun parseUid(statusText: String): Int? {
        val uidLine = statusText.lineSequence().firstOrNull { it.startsWith("Uid:") } ?: return null
        return uidLine
            .removePrefix("Uid:")
            .trim()
            .split(Regex("\\s+"))
            .firstOrNull()
            ?.toIntOrNull()
    }

    internal fun parseTcpListenerInodes(file: File, myUid: Int, port: Int): List<String> {
        val lines = file.readLinesOrNull() ?: return emptyList()
        return lines.drop(1).mapNotNull { line ->
            val parts = line.trim().split(Regex("\\s+"))
            if (parts.size <= 9) return@mapNotNull null
            val localAddress = parts[1]
            val state = parts[3]
            val uid = parts[7].toIntOrNull() ?: return@mapNotNull null
            val inode = parts[9].takeIf { it != "0" } ?: return@mapNotNull null
            val localPort = localAddress
                .substringAfterLast(':', missingDelimiterValue = "")
                .toIntOrNull(radix = 16)
                ?: return@mapNotNull null
            if (state.equals("0A", ignoreCase = true) && uid == myUid && localPort == port) inode else null
        }
    }

    private fun readCommandLine(file: File): String =
        runCatching {
            file.readBytes()
                .toString(Charsets.UTF_8)
                .replace('\u0000', ' ')
                .trim()
        }.getOrDefault("")

    private fun File.readTextOrNull(): String? = runCatching { readText() }.getOrNull()

    private fun File.readLinesOrNull(): List<String>? = runCatching { readLines() }.getOrNull()

    private fun pidOwnsAnySocketInode(pidDir: File, listenerInodes: Set<String>): Boolean {
        val fdDir = File(pidDir, "fd")
        val fds = fdDir.listFiles() ?: return false
        return fds.any { fd ->
            val target = runCatching { Files.readSymbolicLink(fd.toPath()).toString() }.getOrNull()
            target != null &&
                target.startsWith("socket:[") &&
                target.removePrefix("socket:[").removeSuffix("]") in listenerInodes
        }
    }

    private fun sleepQuietly(durationMs: Long) {
        runCatching { Thread.sleep(durationMs) }
    }
}
