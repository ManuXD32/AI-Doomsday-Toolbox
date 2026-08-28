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
    val source: String = "cmdline",
    val parentPid: Int? = null
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

    fun cleanupSameUidLlamaServersOwnedByDirectorySync(
        reason: String,
        ownerDirectory: File,
        procRoot: File = File("/proc"),
        myPid: Int = Process.myPid(),
        myUid: Int = Process.myUid()
    ): Int {
        val ownerPath = runCatching { ownerDirectory.canonicalPath }.getOrElse { ownerDirectory.absolutePath }
        val candidates = findSameUidLlamaServers(procRoot, myPid, myUid).filter { candidate ->
            val cwd = File(procRoot, "${candidate.pid}/cwd")
            val cwdPath = runCatching { cwd.canonicalPath }.getOrNull()
            cwdPath == ownerPath
        }
        if (candidates.isEmpty()) return 0
        DebugLog.log(
            "[NativeProcessCleanup] Found ${candidates.size} owner-scoped llama-server process(es) for $reason"
        )
        candidates.forEach { runCatching { Os.kill(it.pid, OsConstants.SIGTERM) } }
        sleepQuietly(1_000L)
        val survivors = candidates.filter { File(procRoot, it.pid.toString()).exists() }
        survivors.forEach { runCatching { Os.kill(it.pid, OsConstants.SIGKILL) } }
        if (survivors.isNotEmpty()) sleepQuietly(500L)
        return candidates.size
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
        val survivors = stillAlive.filter { File(procRoot, it.pid.toString()).exists() }
        if (survivors.isNotEmpty()) {
            DebugLog.log("[NativeProcessCleanup] WARNING: llama-server process(es) remain alive after cleanup: ${survivors.joinToString { it.pid.toString() }}")
        }
        return candidates.size
    }

    /**
     * Kills the process tree rooted at the captured native child PID. Native
     * accelerator helpers do not always contain llama-server in their command
     * line and can therefore survive marker-based cleanup.
     */
    fun cleanupProcessTreeSync(
        reason: String,
        rootPid: Int,
        includeRoot: Boolean = false,
        graceMs: Long = 750L,
        forceMs: Long = 750L,
        procRoot: File = File("/proc"),
        myPid: Int = Process.myPid(),
        myUid: Int = Process.myUid()
    ): Int {
        if (rootPid <= 0) return 0
        val entries = findSameUidProcesses(procRoot, myPid, myUid)
        val candidates = selectProcessTree(entries, rootPid, includeRoot)
        if (candidates.isEmpty()) return 0
        DebugLog.log(
            "[NativeProcessCleanup] Found ${candidates.size} native process-tree member(s) for $reason: " +
                candidates.joinToString { "${it.pid}(parent=${it.parentPid}):${it.commandLine.take(80)}" }
        )
        candidates.sortedByDescending { treeDepth(it.pid, entries) }.forEach { candidate ->
            runCatching { Os.kill(candidate.pid, OsConstants.SIGTERM) }
                .onFailure { DebugLog.log("[NativeProcessCleanup] process-tree SIGTERM failed for ${candidate.pid}: ${it.message}") }
        }
        sleepQuietly(graceMs)
        val stillAlive = candidates.filter { File(procRoot, it.pid.toString()).exists() }
        stillAlive.forEach { candidate ->
            runCatching { Os.kill(candidate.pid, OsConstants.SIGKILL) }
                .onFailure { DebugLog.log("[NativeProcessCleanup] process-tree SIGKILL failed for ${candidate.pid}: ${it.message}") }
        }
        if (stillAlive.isNotEmpty()) sleepQuietly(forceMs)
        val survivors = stillAlive.filter { File(procRoot, it.pid.toString()).exists() }
        if (survivors.isNotEmpty()) {
            DebugLog.log("[NativeProcessCleanup] WARNING: process-tree member(s) remain alive: ${survivors.joinToString { it.pid.toString() }}")
        }
        return candidates.size
    }

    /**
     * Cleans a previously recorded llama-server tree only after validating the original root PID,
     * /proc start-time token, same UID, and recorded server port. The start-time token prevents a
     * recycled PID from ever being treated as the old llama-server process.
     */
    fun cleanupRecordedLlamaProcessTreeSync(
        reason: String,
        rootPid: Int,
        expectedStartTimeTicks: Long,
        expectedPort: Int,
        graceMs: Long = 750L,
        forceMs: Long = 750L,
        procRoot: File = File("/proc"),
        myPid: Int = Process.myPid(),
        myUid: Int = Process.myUid()
    ): Int {
        if (rootPid <= 0 || expectedStartTimeTicks <= 0L || expectedPort !in 1..65535) return 0
        val entries = findSameUidProcesses(procRoot, myPid, myUid)
        val root = entries.firstOrNull { it.pid == rootPid } ?: return 0
        val actualStartTimeTicks = processStartTimeTicks(rootPid, procRoot) ?: return 0
        if (!recordedLlamaOwnerMatches(
                expectedPid = rootPid,
                expectedStartTimeTicks = expectedStartTimeTicks,
                expectedPort = expectedPort,
                actualPid = root.pid,
                actualStartTimeTicks = actualStartTimeTicks,
                actualCommandLine = root.commandLine
            )
        ) {
            DebugLog.log("[NativeProcessCleanup] Recorded llama owner did not match pid=$rootPid for $reason; refusing cleanup")
            return 0
        }

        val candidates = selectProcessTree(entries, rootPid, includeRoot = true)
        if (candidates.isEmpty()) return 0
        candidates.sortedByDescending { treeDepth(it.pid, entries) }.forEach { candidate ->
            runCatching { Os.kill(candidate.pid, OsConstants.SIGTERM) }
                .onFailure { DebugLog.log("[NativeProcessCleanup] recorded-tree SIGTERM failed for ${candidate.pid}: ${it.message}") }
        }
        sleepQuietly(graceMs)
        val stillAlive = candidates.filter { File(procRoot, it.pid.toString()).exists() }
        stillAlive.forEach { candidate ->
            runCatching { Os.kill(candidate.pid, OsConstants.SIGKILL) }
                .onFailure { DebugLog.log("[NativeProcessCleanup] recorded-tree SIGKILL failed for ${candidate.pid}: ${it.message}") }
        }
        if (stillAlive.isNotEmpty()) sleepQuietly(forceMs)
        val survivors = candidates.filter { File(procRoot, it.pid.toString()).exists() }
        if (survivors.isNotEmpty()) {
            DebugLog.log(
                "[NativeProcessCleanup] Recorded tree still has survivors after recovery: " +
                    survivors.joinToString { it.pid.toString() }
            )
            return 0
        }
        return candidates.size
    }

    internal fun processStartTimeTicks(pid: Int, procRoot: File = File("/proc")): Long? = runCatching {
        val stat = File(procRoot, "$pid/stat").readText()
        val afterName = stat.substringAfterLast(") ", missingDelimiterValue = "")
        // /proc/<pid>/stat field 22 is starttime; after the process name, field 3 is index 0.
        afterName.split(Regex("\\s+")).getOrNull(19)?.toLongOrNull()
    }.getOrNull()

    internal fun recordedLlamaOwnerMatches(
        expectedPid: Int,
        expectedStartTimeTicks: Long,
        expectedPort: Int,
        actualPid: Int,
        actualStartTimeTicks: Long,
        actualCommandLine: String
    ): Boolean =
        expectedPid > 0 &&
            expectedStartTimeTicks > 0L &&
            expectedPort in 1..65535 &&
            actualPid == expectedPid &&
            actualStartTimeTicks == expectedStartTimeTicks &&
            isKnownLlamaServerCommand(actualCommandLine) &&
            commandLineHasPort(actualCommandLine, expectedPort)

    /**
     * Read-only exact-owner check for keyed session reconciliation. A PID is considered owned
     * only when its start-time token, llama command marker, and managed port all match.
     */
    fun recordedLlamaOwnerIsAliveSync(
        rootPid: Int,
        expectedStartTimeTicks: Long,
        expectedPort: Int,
        procRoot: File = File("/proc"),
        myPid: Int = Process.myPid(),
        myUid: Int = Process.myUid()
    ): Boolean {
        if (rootPid <= 0 || expectedStartTimeTicks <= 0L || expectedPort !in 1..65535) return false
        val root = findSameUidProcesses(procRoot, myPid, myUid).firstOrNull { it.pid == rootPid } ?: return false
        val actualStartTimeTicks = processStartTimeTicks(rootPid, procRoot) ?: return false
        return recordedLlamaOwnerMatches(
            expectedPid = rootPid,
            expectedStartTimeTicks = expectedStartTimeTicks,
            expectedPort = expectedPort,
            actualPid = root.pid,
            actualStartTimeTicks = actualStartTimeTicks,
            actualCommandLine = root.commandLine
        )
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
        val survivors = stillAlive.filter { File(procRoot, it.pid.toString()).exists() }
        if (survivors.isNotEmpty()) {
            DebugLog.log("[NativeProcessCleanup] WARNING: port listener process(es) remain alive on $port: ${survivors.joinToString { it.pid.toString() }}")
        }
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
        val survivors = stillAlive.filter { File(procRoot, it.pid.toString()).exists() }
        if (survivors.isNotEmpty()) {
            DebugLog.log("[NativeProcessCleanup] WARNING: stuck-port process(es) remain alive on $port: ${survivors.joinToString { it.pid.toString() }}")
        }
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

    /** A live listener belongs to another runtime and must not be swept as stale. */
    fun hasSameUidPortListenerSync(
        port: Int,
        procRoot: File = File("/proc"),
        myPid: Int = Process.myPid(),
        myUid: Int = Process.myUid()
    ): Boolean = findSameUidPortListeners(procRoot, myPid, myUid, port).isNotEmpty()

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
                source = "socket",
                parentPid = readParentPid(pidDir)
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

    private fun findSameUidProcesses(procRoot: File, myPid: Int, myUid: Int): List<NativeProcessCandidate> {
        val pidDirs = procRoot.listFiles { file -> file.isDirectory && file.name.all { it.isDigit() } } ?: return emptyList()
        return pidDirs.mapNotNull { pidDir ->
            val pid = pidDir.name.toIntOrNull() ?: return@mapNotNull null
            if (pid == myPid) return@mapNotNull null
            val uid = parseUid(File(pidDir, "status").readTextOrNull() ?: return@mapNotNull null)
                ?: return@mapNotNull null
            if (uid != myUid) return@mapNotNull null
            NativeProcessCandidate(
                pid = pid,
                uid = uid,
                commandLine = readCommandLine(File(pidDir, "cmdline")),
                source = "process-tree",
                parentPid = readParentPid(pidDir)
            )
        }
    }

    internal fun selectProcessTree(
        entries: List<NativeProcessCandidate>,
        rootPid: Int,
        includeRoot: Boolean
    ): List<NativeProcessCandidate> {
        if (rootPid <= 0) return emptyList()
        val selected = linkedSetOf(rootPid)
        var changed: Boolean
        do {
            changed = false
            entries.filter { it.pid !in selected && it.parentPid in selected }.forEach {
                changed = selected.add(it.pid) || changed
            }
        } while (changed)
        return entries.filter { it.pid in selected && (includeRoot || it.pid != rootPid) }
    }

    private fun readParentPid(pidDir: File): Int? = runCatching {
        val stat = File(pidDir, "stat").readText()
        val afterName = stat.substringAfterLast(") ", missingDelimiterValue = "")
        afterName.split(Regex("\\s+")).getOrNull(1)?.toIntOrNull()
    }.getOrNull()

    private fun treeDepth(pid: Int, entries: List<NativeProcessCandidate>): Int {
        val byPid = entries.associateBy { it.pid }
        var current = pid
        var depth = 0
        val seen = mutableSetOf<Int>()
        while (seen.add(current)) {
            val parent = byPid[current]?.parentPid ?: break
            depth++
            current = parent
        }
        return depth
    }

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
