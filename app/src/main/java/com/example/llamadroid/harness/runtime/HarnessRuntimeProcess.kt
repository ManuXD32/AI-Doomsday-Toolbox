package com.example.llamadroid.harness.runtime

import android.os.Process as AndroidProcess
import android.system.Os
import android.system.OsConstants
import com.example.llamadroid.data.proot.AgentProotEnvironmentPaths
import com.example.llamadroid.data.proot.AgentProotNativeBinaryProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.TimeUnit

private const val HARNESS_TERM_GRACE_MS = 9_000
private const val MAX_PROC_TEXT_BYTES = 256 * 1024
private const val MAX_LEDGER_BYTES = 64 * 1024
private const val HARNESS_MAX_PROCESSES = 256
private const val HARNESS_MAX_OPEN_FILES = 1_024
private const val HARNESS_MAX_FILE_BYTES = 4L * 1024L * 1024L * 1024L
private const val ADT_HARNESS_PATCH_GUEST =
    "/opt/adt-harness/node_modules/@manuxd32/adt-dsh-bridge/cordis.patch.yml"

/** Reads proc metadata with a hard cap so malformed or hostile mounted metadata cannot grow the
 * app process heap during Force or stale-owner recovery. */
private fun readBoundedText(file: File, maxBytes: Int = MAX_PROC_TEXT_BYTES): String? = runCatching {
    if (!file.isFile) return@runCatching null
    val bytes = ByteArray(maxBytes + 1)
    var total = 0
    file.inputStream().use { input ->
        while (total < bytes.size) {
            val count = input.read(bytes, total, bytes.size - total)
            if (count < 0) break
            if (count == 0) continue
            total += count
        }
    }
    if (total > maxBytes) null else String(bytes, 0, total, Charsets.UTF_8)
}.getOrNull()

/** Builds the only supported Harness launch: signed broker -> signed PRoot -> pinned payload. */
class AndroidHarnessProcessLauncher(
    private val context: android.content.Context
) : HarnessProcessLauncher {
    override fun launch(spec: HarnessLaunchSpec): HarnessProcessHandle {
        val childPidFile = File(spec.paths.runHost, "harness-child.pid").canonicalFile
        val processLedgerFile = File(spec.paths.runHost, "harness-processes.ledger").canonicalFile
        childPidFile.delete()
        processLedgerFile.delete()
        File(spec.paths.runHost, "harness-ready.json").let { receipt ->
            require(!receipt.exists() || receipt.delete()) { "HARNESS_READY_RECEIPT_STALE" }
        }
        val command = buildHarnessLaunchCommand(
            spec = spec,
            childPidFile = childPidFile,
            processLedgerFile = processLedgerFile,
            procRoot = File("/proc"),
            devices = (listOf("null", "zero", "random", "urandom")
                .map { File("/dev", it) } + listOf(File("/dev/ptmx"), File("/dev/pts")))
                .filter { it.exists() }
        )
        val defaultProjectHost = File(
            spec.paths.projectsHost,
            HarnessRuntimePaths.DEFAULT_PROJECT_DIRECTORY
        ).canonicalFile
        require(AgentProotEnvironmentPaths.isDescendantOrSame(
            defaultProjectHost,
            spec.paths.projectsHost.canonicalFile
        )) {
            "Harness default project directory escaped the shared projects directory"
        }
        require(defaultProjectHost.isDirectory) {
            "Harness default project directory is unavailable: ${defaultProjectHost.path}"
        }

        val process = try {
            ProcessBuilder(command)
                .directory(defaultProjectHost)
                .redirectErrorStream(false)
                .apply {
                    environment()["PROOT_NO_SECCOMP"] = "1"
                    environment()["PROOT_TMP_DIR"] = spec.paths.tempHost.absolutePath
                    environment()["LD_LIBRARY_PATH"] = context.applicationInfo.nativeLibraryDir
                    spec.loaderPath?.let { environment()["PROOT_LOADER"] = it.absolutePath }
                    environment()["HOME"] = spec.paths.dshHomeGuest
                    environment()["DSH_HOME"] = spec.paths.dshHomeGuest
                    environment()["TMPDIR"] = AgentProotEnvironmentPaths.TMP_MOUNT
                    environment()["TMP"] = AgentProotEnvironmentPaths.TMP_MOUNT
                    environment()["TEMP"] = AgentProotEnvironmentPaths.TMP_MOUNT
                    environment()["SHELL"] = "/bin/bash"
                    environment()["PATH"] = "/opt/adt-harness/bin:/opt/adt-harness/node/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
                    environment()["TERM"] = "xterm-256color"
                    environment()["COLORTERM"] = "truecolor"
                    environment()["LANG"] = "C.UTF-8"
                    environment()["LC_ALL"] = "C.UTF-8"
                    environment()["DEBIAN_FRONTEND"] = "noninteractive"
                    environment()["APT_LISTCHANGES_FRONTEND"] = "none"
                    environment()["ADT_HARNESS_OWNER_MARKER"] = spec.ownerMarker
                    environment()["ADT_HARNESS_PORT"] = spec.port.toString()
                    environment()["ADT_HARNESS_READY_FILE"] = "/run/harness-ready.json"
                    environment()[spec.preparation.authEnvironmentKey] = spec.preparation.webToken
                    spec.preparation.environment.forEach { (key, value) ->
                        require(SAFE_ENVIRONMENT_KEY.matches(key)) { "Invalid Harness environment key: $key" }
                        require(key !in RESERVED_ENVIRONMENT_KEYS &&
                            key != spec.preparation.authEnvironmentKey) {
                            "Harness environment attempts to override a protected value"
                        }
                        require(value.length <= MAX_ENVIRONMENT_VALUE_LENGTH) {
                            "Harness environment value is too long: $key"
                        }
                        environment()[key] = value
                    }
                }
                .start()
        } catch (error: Throwable) {
            throw HarnessRuntimeException(
                classifyHarnessProcessOutput(error.message.orEmpty()) ?: "HARNESS_PROCESS_START_FAILED",
                error.message?.take(1_000) ?: "Unable to start the pinned Harness payload",
                error
            )
        }
        val diagnostics = HarnessProcessDiagnosticsCollector()
        val drains = listOf(
            drainProcessStream(process.inputStream, "stdout", diagnostics, classify = true),
            drainProcessStream(process.errorStream, "stderr", diagnostics, classify = true),
        )
        return try {
            AndroidHarnessProcessHandle(process, childPidFile, spec.ownerMarker, diagnostics, drains)
        } catch (error: Throwable) {
            // An early native exit can beat the pipe reader. Bound the drain before recording
            // its classification; never persist the output and never wait on a live process.
            if (!process.isAlive) drains.forEach { runCatching { it.join(100L) } }
            val processDiagnostics = diagnostics.snapshot(process)
            val diagnosticCode = sanitizeHarnessProcessCode(processDiagnostics.stderrCode) ?:
                sanitizeHarnessProcessCode(processDiagnostics.stdoutCode) ?:
                classifyHarnessProcessExit(processDiagnostics.exitCode)
            process.destroyForcibly()
            throw HarnessRuntimeException(
                diagnosticCode ?: (error as? HarnessRuntimeException)?.code ?: "HARNESS_BROKER_PID_MISSING",
                "The Harness broker did not publish its child identity",
                error,
                processDiagnostics = processDiagnostics,
            )
        }
    }

    private companion object {
        val SAFE_ENVIRONMENT_KEY = Regex("[A-Za-z_][A-Za-z0-9_]{0,127}")
        val RESERVED_ENVIRONMENT_KEYS = setOf(
            "ADT_HARNESS_OWNER_MARKER",
            "ADT_HARNESS_PORT",
            "ADT_HARNESS_READY_FILE",
            "ADT_HARNESS_BOOTSTRAP_NONCE",
            "DSH_WEB_TOKEN",
            "HOME",
            "DSH_HOME",
            "PATH",
            "PROOT_LOADER",
            "PROOT_TMP_DIR",
            "TMPDIR",
            "TMP",
            "TEMP"
        )
        const val MAX_ENVIRONMENT_VALUE_LENGTH = 16_384
    }
}

/** Keeps native pipes flowing and retains only a bounded startup failure code. */
private fun drainProcessStream(
    stream: InputStream,
    label: String,
    diagnostics: HarnessProcessDiagnosticsCollector,
    classify: Boolean
): Thread =
    Thread({
        val buffer = ByteArray(8 * 1024)
        try {
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                if (classify && count > 0) {
                    diagnostics.observe(label, String(buffer, 0, count, Charsets.UTF_8))
                }
            }
        } catch (_: Throwable) {
            // Process teardown closes the pipe. Never expose the drained content.
        } finally {
            runCatching { stream.close() }
        }
    }, "adt-harness-$label").apply {
        isDaemon = true
        start()
    }

/**
 * A short, bounded rolling window is inspected in memory for a fixed error class. Only that
 * class is exposed to callers; process text never becomes a durable diagnostic or UI log.
 */
private class HarnessProcessDiagnosticsCollector {
    private val stderrCode = AtomicReference<String?>(null)
    private val stdoutCode = AtomicReference<String?>(null)
    private val stderrTail = StringBuilder()
    private val stdoutTail = StringBuilder()

    fun observe(label: String, chunk: String) {
        val tail = synchronized(this) {
            val target = if (label == "stderr") stderrTail else stdoutTail
            target.append(chunk)
            if (target.length > MAX_CLASSIFICATION_WINDOW) {
                target.delete(0, target.length - MAX_CLASSIFICATION_WINDOW)
            }
            target.toString()
        }
        val code = classifyHarnessProcessOutput(tail) ?: return
        val target = if (label == "stderr") stderrCode else stdoutCode
        target.updateAndGet { previous -> preferHarnessProcessDiagnostic(previous, code) }
    }

    fun snapshot(process: Process): HarnessProcessDiagnostics {
        val exitCode = if (process.isAlive) null else runCatching { process.exitValue() }.getOrNull()
        return synchronized(this) {
            HarnessProcessDiagnostics(
                stderrCode = stderrCode.get(),
                stdoutCode = stdoutCode.get(),
                exitCode = exitCode
            )
        }
    }

    fun awaitDrains(drains: List<Thread>, timeoutMs: Long) {
        val deadline = System.nanoTime() + timeoutMs.coerceAtLeast(0L) * 1_000_000L
        drains.forEach { drain ->
            val remainingNanos = deadline - System.nanoTime()
            if (remainingNanos <= 0L) return
            val remainingMs = (remainingNanos / 1_000_000L).coerceAtLeast(1L)
            runCatching { drain.join(remainingMs) }
        }
    }

    private companion object {
        const val MAX_CLASSIFICATION_WINDOW = 8 * 1024
    }
}

/** Maps known native/runtime startup wording to stable, content-free diagnostic codes. */
internal fun preferHarnessProcessDiagnostic(previous: String?, next: String): String =
    if (previous == null || previous == "HARNESS_NODE_START_FAILED") next else previous

internal fun classifyHarnessProcessOutput(text: String): String? {
    val normalized = text.lowercase(Locale.ROOT)
    return when {
        "harness_broker_owner_unavailable" in normalized -> "HARNESS_BROKER_OWNER_UNAVAILABLE"
        "proot_broker: setrlimit" in normalized -> "HARNESS_BROKER_LIMIT_FAILED"
        "proot_broker: clear inherited pr_set_pdeathsig" in normalized ||
            "proot_broker: pr_set_child_subreaper" in normalized -> "HARNESS_BROKER_SUPERVISION_FAILED"
        "exec format error" in normalized ||
            "wrong elf class" in normalized ||
            "wrong architecture" in normalized ||
            "bad cpu type" in normalized -> "HARNESS_ARCHITECTURE_MISMATCH"
        "permission denied" in normalized || "eacces" in normalized -> "HARNESS_PERMISSION_DENIED"
        "address already in use" in normalized || "eaddrinuse" in normalized -> "HARNESS_PORT_IN_USE"
        "enosys" in normalized || "function not implemented" in normalized -> "HARNESS_SYSCALL_UNAVAILABLE"
        "cannot open shared object" in normalized ||
            "invalid elf" in normalized ||
            "native module" in normalized ||
            "cannot find package" in normalized ||
            "cannot find module" in normalized ||
            "err_module_not_found" in normalized ||
            "failed to load native" in normalized -> "HARNESS_NATIVE_MODULE_UNAVAILABLE"
        "no such file or directory" in normalized ||
            "enoent" in normalized ||
            "command not found" in normalized -> "HARNESS_EXECUTABLE_MISSING"
        "ptrace" in normalized ||
            "seccomp" in normalized ||
            ("proot" in normalized && "failed" in normalized) -> "HARNESS_PROOT_START_FAILED"
        "segmentation fault" in normalized || "sigsegv" in normalized -> "HARNESS_PROCESS_CRASHED"
        "out of memory" in normalized || "cannot allocate memory" in normalized ||
            "enomem" in normalized -> "HARNESS_MEMORY_LIMIT"
        "node" in normalized && ("module" in normalized || "startup" in normalized) ->
            "HARNESS_NODE_START_FAILED"
        else -> null
    }
}

internal fun classifyHarnessProcessExit(exitCode: Int?): String? = when (exitCode) {
    126 -> "HARNESS_PERMISSION_DENIED"
    127 -> "HARNESS_EXECUTABLE_MISSING"
    137 -> "HARNESS_PROCESS_KILLED"
    139 -> "HARNESS_PROCESS_CRASHED"
    143 -> "HARNESS_PROCESS_TERMINATED"
    else -> null
}

/** Rejects arbitrary launcher-provided values before they can reach durable metadata. */
internal fun sanitizeHarnessProcessCode(code: String?): String? = when (code) {
    "HARNESS_BROKER_OWNER_UNAVAILABLE",
    "HARNESS_BROKER_LIMIT_FAILED",
    "HARNESS_BROKER_SUPERVISION_FAILED",
    "HARNESS_PROCESS_TERMINATED",
    "HARNESS_ARCHITECTURE_MISMATCH",
    "HARNESS_NATIVE_MODULE_UNAVAILABLE",
    "HARNESS_EXECUTABLE_MISSING",
    "HARNESS_PERMISSION_DENIED",
    "HARNESS_PORT_IN_USE",
    "HARNESS_SYSCALL_UNAVAILABLE",
    "HARNESS_PROOT_START_FAILED",
    "HARNESS_NODE_START_FAILED",
    "HARNESS_PROCESS_CRASHED",
    "HARNESS_PROCESS_KILLED",
    "HARNESS_PROCESS_EXITED",
    "HARNESS_MEMORY_LIMIT" -> code
    else -> null
}

/** Pure command construction keeps the signed broker/PRoot invariants unit-testable. */
internal fun buildHarnessLaunchCommand(
    spec: HarnessLaunchSpec,
    childPidFile: File,
    processLedgerFile: File,
    procRoot: File,
    devices: List<File>
): List<String> {
    val command = mutableListOf(
        spec.brokerPath.absolutePath,
        "--max-processes", HARNESS_MAX_PROCESSES.toString(),
        "--max-open-files", HARNESS_MAX_OPEN_FILES.toString(),
        "--max-file-bytes", HARNESS_MAX_FILE_BYTES.toString(),
        "--term-grace-ms", HARNESS_TERM_GRACE_MS.toString(),
        // Graceful Stop owns the ten-second deadline in Kotlin. The broker must leave the
        // PRoot/Node tree alive after its internal grace interval so Force can make the final,
        // owner-validated decision. TERM is sent to PRoot only after Node has exited.
        "--no-force-on-timeout",
        "--term-child-only",
        "--child-pid-file", childPidFile.canonicalPath,
        "--process-ledger-file", processLedgerFile.canonicalPath,
        "--",
        spec.prootPath.absolutePath,
        "-v", "-1",
        "-0",
        "--link2symlink",
        "-r", spec.paths.rootfs.absolutePath,
        "--kill-on-exit"
    )
    fun bind(host: File, guest: String) {
        val canonicalHost = host.canonicalFile
        require(canonicalHost.exists()) { "Harness bind source is missing: ${canonicalHost.path}" }
        require(guest.startsWith('/') && !guest.contains("..")) { "Harness guest bind path is invalid" }
        command += listOf("-b", "${canonicalHost.path}:$guest")
    }
    bind(spec.paths.projectsHost, spec.paths.projectsGuest)
    bind(spec.paths.dshHomeHost, spec.paths.dshHomeGuest)
    bind(spec.paths.tempHost, AgentProotEnvironmentPaths.TMP_MOUNT)
    bind(spec.paths.runHost, AgentProotEnvironmentPaths.RUN_MOUNT)
    bind(spec.paths.resolverFile, "/etc/resolv.conf")
    bind(procRoot, "/proc")
    devices.forEach { device ->
        val guestName = device.name
        require(guestName.matches(Regex("[A-Za-z0-9._-]+"))) { "Harness device bind name is invalid" }
        bind(device, "/dev/$guestName")
    }
    // Keep the shared parent bind for project discovery/history, but launch the WebUI in the
    // guaranteed scoped default project so new sessions do not inherit the mount root as cwd.
    command += listOf("-w", HarnessRuntimePaths.DEFAULT_PROJECT_GUEST)
    command += spec.payload.command
    if (spec.payload.command.first() == "/opt/adt-harness/bin/dsh") {
        require(spec.payload.command.drop(1) == listOf("--profile", "web")) {
            "The pinned Harness must launch the official web profile"
        }
        // The bundled ADT plugin is installed in the signed payload, but a package dependency
        // alone does not add its Cordis rows to the Web profile. Keep this invocation overlay
        // outside DSH_HOME so user and home patch files remain untouched and reusable.
        command += listOf("--patch", ADT_HARNESS_PATCH_GUEST)
        command += listOf("--host", "127.0.0.1", "--port", spec.port.toString(), "--no-open")
    }
    return command
}

private class AndroidHarnessProcessHandle(
    private val process: Process,
    childPidFile: File,
    private val ownerMarker: String,
    private val diagnosticsCollector: HarnessProcessDiagnosticsCollector,
    private val drainThreads: List<Thread>
) : HarnessProcessHandle {
    override val brokerPid: Int = findBrokerPid(childPidFile)
    private val brokerStartTimeTicks: Long = findBrokerStartTimeTicks()
    private val brokerUid: Int = AndroidProcess.myUid()

    override fun isAlive(): Boolean = process.isAlive

    override fun diagnostics(): HarnessProcessDiagnostics = diagnosticsCollector.snapshot(process)

    override suspend fun diagnosticsAfterExit(timeoutMs: Long): HarnessProcessDiagnostics =
        withContext(Dispatchers.IO) {
            if (!process.isAlive) diagnosticsCollector.awaitDrains(drainThreads, timeoutMs)
            diagnosticsCollector.snapshot(process)
        }

    override fun requestGracefulStop() {
        signalIfExactOwner(OsConstants.SIGTERM)
    }

    override fun requestForceStop() {
        // The broker treats SIGINT as an immediate force signal. SIGKILL is reserved for the
        // owner-aware fallback after the broker has had a chance to reap its subreaper children.
        signalIfExactOwner(OsConstants.SIGINT)
    }

    override suspend fun awaitExit(timeoutMs: Long): Boolean = withContext(Dispatchers.IO) {
        process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
    }

    /** Startup can be cancelled before the supervisor has persisted its full identity. */
    private fun signalIfExactOwner(signalNumber: Int) {
        val stat = readStat(brokerPid) ?: return
        if (stat.state == 'Z' || stat.startTimeTicks != brokerStartTimeTicks ||
            readUid(brokerPid) != brokerUid
        ) return
        val marker = runCatching {
            readBoundedText(File("/proc/$brokerPid/environ"))
                ?.split('\u0000')
                ?.contains("ADT_HARNESS_OWNER_MARKER=$ownerMarker")
                ?: false
        }.getOrDefault(false)
        if (marker) runCatching { Os.kill(brokerPid, signalNumber) }
    }

    private fun findBrokerStartTimeTicks(): Long {
        val deadline = System.nanoTime() + 1_000_000_000L
        while (System.nanoTime() < deadline) {
            readStat(brokerPid)?.startTimeTicks?.let { return it }
            Thread.sleep(20L)
        }
        throw HarnessRuntimeException(
            "HARNESS_BROKER_IDENTITY_MISSING",
            "The Harness broker identity was not available"
        )
    }

    private fun readStat(pid: Int): BrokerStat? = runCatching {
        val stat = readBoundedText(File("/proc/$pid/stat")) ?: return@runCatching null
        val fields = stat.substringAfterLast(") ", missingDelimiterValue = "")
            .split(Regex("\\s+"))
        BrokerStat(
            state = fields.getOrNull(0)?.firstOrNull() ?: return@runCatching null,
            startTimeTicks = fields.getOrNull(19)?.toLongOrNull()
                ?: return@runCatching null
        )
    }.getOrNull()

    private fun readUid(pid: Int): Int? = runCatching {
        readBoundedText(File("/proc/$pid/status"))
            ?.lineSequence()
            ?.firstOrNull { it.startsWith("Uid:") }
            ?.removePrefix("Uid:")
            ?.trim()
            ?.split(Regex("\\s+"))
            ?.firstOrNull()
            ?.toIntOrNull()
    }.getOrNull()

    private data class BrokerStat(val state: Char, val startTimeTicks: Long)

    private fun findBrokerPid(childPidFile: File): Int {
        val deadline = System.nanoTime() + 2_000_000_000L
        while (System.nanoTime() < deadline) {
            if (!process.isAlive) throw HarnessRuntimeException(
                "HARNESS_BROKER_EXITED", "The Harness broker exited before publishing its child identity"
            )
            val childPid = runCatching {
                childPidFile.takeIf { it.isFile }
                    ?.let { readBoundedText(it, 64) }
                    ?.trim()
                    ?.toIntOrNull()
                    ?.takeIf { it > 0 }
            }.getOrNull()
            if (childPid != null) {
                val stat = File("/proc/$childPid/stat")
                if (stat.isFile) {
                    runCatching {
                        val fields = readBoundedText(stat)
                            ?.substringAfterLast(") ", missingDelimiterValue = "")
                            ?.split(Regex("\\s+"))
                            ?: return@runCatching null
                        fields.getOrNull(1)?.toIntOrNull()?.takeIf { it > 0 }
                    }.getOrNull()?.let { return it }
                }
            }
            Thread.sleep(20L)
        }
        throw HarnessRuntimeException(
            "HARNESS_BROKER_PID_MISSING",
            "The Harness broker child PID file was not available"
        )
    }
}

interface HarnessProcessSupervisor {
    suspend fun capture(handle: HarnessProcessHandle, spec: HarnessLaunchSpec): HarnessProcessIdentity

    /** Revalidates the persisted owner tuple without signalling or mutating any process. */
    fun observe(identity: HarnessProcessIdentity): HarnessProcessObservation {
        val alive = isOwnerAlive(identity)
        return HarnessProcessObservation(brokerAlive = alive, ownerAlive = alive)
    }

    /**
     * Reconstructs an owner identity from the broker's durable descendant ledger when Room was
     * not updated before the app process died. The returned identity is anchored to a ledger
     * tuple, not a guessed or recycled numeric PID; implementations must revalidate UID and
     * start ticks against the current process table before returning it.
     */
    suspend fun recoverIdentity(ownerMarker: String, expectedUid: Int): HarnessProcessIdentity? = null

    suspend fun gracefulStop(
        handle: HarnessProcessHandle?,
        identity: HarnessProcessIdentity,
        deadlineMs: Long
    ): Boolean

    suspend fun forceStop(handle: HarnessProcessHandle?, identity: HarnessProcessIdentity): Boolean

    fun isOwnerAlive(identity: HarnessProcessIdentity): Boolean
}

/**
 * Owner-aware Android supervisor. It validates UID, PID start ticks, executable identity and a
 * non-secret inherited generation marker before signaling anything. The marker also lets recovery
 * find reparented descendants after the broker has already exited.
 */
class AndroidHarnessProcessSupervisor(
    private val procRoot: File = File("/proc"),
    private val currentUid: Int = AndroidProcess.myUid(),
    private val signal: (Int, Int) -> Unit = { pid, signalNumber -> Os.kill(pid, signalNumber) },
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
    private val ledgerFile: File? = null,
    private val currentPid: Int = AndroidProcess.myPid()
) : HarnessProcessSupervisor {
    override suspend fun capture(handle: HarnessProcessHandle, spec: HarnessLaunchSpec): HarnessProcessIdentity =
        withContext(Dispatchers.IO) {
            val broker = waitForSnapshot(handle.brokerPid, 2_000L)
                ?: throw HarnessRuntimeException("HARNESS_OWNER_MISSING", "Harness broker disappeared after launch")
            require(broker.uid == currentUid) { "Harness broker UID does not belong to this app" }
            require(broker.commandLine.contains(spec.brokerPath.name)) {
                "Harness broker executable identity could not be verified"
            }
            require(broker.environ.contains(ownerEntry(spec.ownerMarker))) {
                "Harness broker owner marker could not be verified"
            }
            val childPid = waitForChildPid(spec.paths.runHost, 1_000L)
            val child = childPid?.let { waitForSnapshot(it, 1_000L) }
            val node = child?.takeIf { isNodeProcess(it.commandLine) }
                ?: child?.let {
                    waitForNodeSnapshot(
                        rootPid = it.pid,
                        ownerMarker = spec.ownerMarker,
                        timeoutMs = 2_000L
                    )
                }
            HarnessProcessIdentity(
                brokerPid = broker.pid,
                brokerStartTimeTicks = broker.startTimeTicks,
                brokerUid = broker.uid,
                processGroupId = child?.processGroupId ?: broker.processGroupId,
                childPid = child?.pid,
                childStartTimeTicks = child?.startTimeTicks,
                ownerMarker = spec.ownerMarker,
                nodePid = node?.pid,
                nodeStartTimeTicks = node?.startTimeTicks
            )
        }

    override suspend fun recoverIdentity(
        ownerMarker: String,
        expectedUid: Int
    ): HarnessProcessIdentity? = withContext(Dispatchers.IO) {
        if (expectedUid < 0 || !ownerMarker.matches(HarnessProcessIdentity.OWNER_MARKER_PATTERN)) {
            return@withContext null
        }
        // The native ledger is generation scoped and each tuple is re-read from /proc. A tuple
        // whose PID was recycled, whose UID changed, or whose start ticks changed is discarded.
        val ledger = readLedger(ownerMarker)
        val anchor = ledger.asSequence()
            .mapNotNull { entry ->
                readSnapshot(entry.pid)?.takeIf { snapshot ->
                    entry.matches(snapshot) && snapshot.uid == expectedUid
                }
            }
            .firstOrNull()
            ?: return@withContext null
        val node = ledger.asSequence()
            .mapNotNull { entry ->
                readSnapshot(entry.pid)?.takeIf { snapshot ->
                    entry.matches(snapshot) && snapshot.uid == expectedUid &&
                        isNodeProcess(snapshot.commandLine)
                }
            }
            .firstOrNull()
        HarnessProcessIdentity(
            // The broker itself is not written to the ledger. The exact live tuple is used as a
            // cleanup anchor; ownedSnapshots() still includes every matching ledger descendant.
            brokerPid = anchor.pid,
            brokerStartTimeTicks = anchor.startTimeTicks,
            brokerUid = anchor.uid,
            processGroupId = anchor.processGroupId,
            ownerMarker = ownerMarker,
            nodePid = node?.pid,
            nodeStartTimeTicks = node?.startTimeTicks
        )
    }

    override fun observe(identity: HarnessProcessIdentity): HarnessProcessObservation {
        val broker = readSnapshot(identity.brokerPid)?.takeIf { matches(identity, it) }
        val child = identity.childPid?.let { pid ->
            readSnapshot(pid)?.takeIf { snapshot -> matchesChild(identity, snapshot) }
        }
        val node = identity.nodePid?.let { pid ->
            readSnapshot(pid)?.takeIf { snapshot -> matchesNode(identity, snapshot) }
        }
        return HarnessProcessObservation(
            brokerAlive = broker != null,
            childAlive = identity.childPid?.let { child != null },
            nodeAlive = identity.nodePid?.let { node != null },
            ownerAlive = isOwnerAlive(identity),
        )
    }

    override suspend fun gracefulStop(
        handle: HarnessProcessHandle?,
        identity: HarnessProcessIdentity,
        deadlineMs: Long
    ): Boolean = withContext(Dispatchers.IO) {
        val deadline = System.nanoTime() + deadlineMs.coerceAtLeast(0L) * 1_000_000L
        // PRoot's --kill-on-exit tears down the traced guest when PRoot receives TERM. Give the
        // actual Node process the first signal and keep PRoot alive until Node has exited so DSH
        // can flush its state and close the listener cleanly.
        if (identity.nodePid != null) {
            signalNodeIfOwner(identity, OsConstants.SIGTERM)
            if (!waitUntilNodeGone(identity, remainingMillis(deadline))) return@withContext false
        }
        val remainingBeforeBroker = remainingMillis(deadline)
        if (remainingBeforeBroker <= 0L) return@withContext false
        signalIfOwner(identity, OsConstants.SIGTERM)
        val stopped = handle?.awaitExit(remainingBeforeBroker)
            ?: waitUntilGone(identity, remainingBeforeBroker)
        if (!stopped) return@withContext false
        val remainingBeforeCleanup = remainingMillis(deadline)
        if (remainingBeforeCleanup <= 0L) return@withContext false
        cleanupOwned(identity, OsConstants.SIGTERM)
        sleep(minOf(100L, remainingBeforeCleanup))
        !isOwnerAlive(identity)
    }

    override suspend fun forceStop(
        handle: HarnessProcessHandle?,
        identity: HarnessProcessIdentity
    ): Boolean = withContext(Dispatchers.IO) {
        signalIfOwner(identity, OsConstants.SIGINT)
        sleep(100L)
        signalProcessGroup(identity, OsConstants.SIGKILL)
        cleanupOwned(identity, OsConstants.SIGKILL)
        handle?.awaitExit(1_000L)
        sleep(100L)
        cleanupOwned(identity, OsConstants.SIGKILL)
        !isOwnerAlive(identity)
    }

    override fun isOwnerAlive(identity: HarnessProcessIdentity): Boolean {
        val broker = readSnapshot(identity.brokerPid)
        if (broker != null && matches(identity, broker)) return true
        return ownedSnapshots(identity).isNotEmpty()
    }

    private fun signalIfOwner(identity: HarnessProcessIdentity, signalNumber: Int) {
        val broker = readSnapshot(identity.brokerPid)
        if (broker != null && (matches(identity, broker) || readLedger(identity).any { it.matches(broker) })) {
            signalOwnedCandidate(identity, broker, signalNumber)
        }
    }

    private fun signalNodeIfOwner(identity: HarnessProcessIdentity, signalNumber: Int) {
        val nodePid = identity.nodePid ?: return
        val node = readSnapshot(nodePid) ?: return
        if (matchesNode(identity, node)) signalOwnedCandidate(identity, node, signalNumber)
    }

    private fun signalProcessGroup(identity: HarnessProcessIdentity, signalNumber: Int) {
        // A numeric process-group ID can be recycled independently of the broker PID. Signal
        // each revalidated owner instead of sending a negative-PID signal to a possibly reused
        // group that could contain unrelated app work.
        ownedSnapshots(identity)
            .sortedByDescending { treeDepth(it.pid, identity.brokerPid) }
            .forEach { candidate -> signalOwnedCandidate(identity, candidate, signalNumber) }
    }

    private fun cleanupOwned(identity: HarnessProcessIdentity, signalNumber: Int) {
        val candidates = ownedSnapshots(identity)
            .sortedByDescending { treeDepth(it.pid, identity.brokerPid) }
        candidates.forEach { candidate -> signalOwnedCandidate(identity, candidate, signalNumber) }
    }

    private fun ownedSnapshots(identity: HarnessProcessIdentity): List<ProcSnapshot> {
        val entries = scanSnapshots()
        val brokerAtPid = entries.firstOrNull { it.pid == identity.brokerPid }
        val verifiedBroker = brokerAtPid?.takeIf { matches(identity, it) }
        // A marker is sufficient to recover descendants after the broker exits. A recycled
        // broker PID itself is the one exception: keep detached marker-bearing descendants, but
        // never let the reused numeric broker entry make isOwnerAlive report a false owner.
        val markerOwned = entries.filter { snapshot ->
            snapshot.environ.contains(ownerEntry(identity.ownerMarker)) &&
                (snapshot.pid != identity.brokerPid || verifiedBroker != null)
        }
        val ledger = readLedger(identity)
        val ledgerOwned = entries.filter { snapshot ->
            ledger.any { it.matches(snapshot) }
        }
        // Never derive a descendant tree from a recycled numeric broker PID. Marker ownership
        // can still find detached descendants after the broker exits; attached tree ownership
        // requires the persisted broker start-time, UID, and generation to match now.
        val treeOwned = verifiedBroker?.let { broker ->
            selectTree(entries, broker.pid).filter { it.uid == identity.brokerUid }
        }.orEmpty()
        return (markerOwned + ledgerOwned + treeOwned)
            .distinctBy { it.pid }
            .filter { it.pid != currentPid }
    }

    /** Re-read identity and ownership immediately before every signal to narrow PID-reuse races. */
    private fun signalOwnedCandidate(
        identity: HarnessProcessIdentity,
        candidate: ProcSnapshot,
        signalNumber: Int
    ) {
        val current = readSnapshot(candidate.pid) ?: return
        if (current.startTimeTicks != candidate.startTimeTicks || current.uid != candidate.uid) return
        if (!isOwnedCandidate(identity, current)) return
        runCatching { signal(current.pid, signalNumber) }
    }

    private fun isOwnedCandidate(identity: HarnessProcessIdentity, snapshot: ProcSnapshot): Boolean {
        if (snapshot.pid == currentPid || snapshot.state == 'Z' || snapshot.uid != identity.brokerUid) return false
        if (snapshot.pid == identity.brokerPid) return matches(identity, snapshot)
        if (snapshot.pid == identity.childPid && identity.childStartTimeTicks != null &&
            snapshot.startTimeTicks != identity.childStartTimeTicks
        ) return false
        if (snapshot.environ.contains(ownerEntry(identity.ownerMarker))) return true
        if (readLedger(identity).any { it.matches(snapshot) }) return true

        // Markerless descendants are safe to target only while their verified broker ancestry is
        // still present. Once the broker is gone, recovery cannot prove ownership for them.
        val entries = scanSnapshots()
        val broker = entries.firstOrNull { matches(identity, it) } ?: return false
        return selectTree(entries, broker.pid).any { it.pid == snapshot.pid }
    }

    private fun matches(identity: HarnessProcessIdentity, snapshot: ProcSnapshot): Boolean =
        snapshot.pid == identity.brokerPid &&
            snapshot.uid == identity.brokerUid &&
            snapshot.startTimeTicks == identity.brokerStartTimeTicks &&
            snapshot.environ.contains(ownerEntry(identity.ownerMarker))

    private fun matchesNode(identity: HarnessProcessIdentity, snapshot: ProcSnapshot): Boolean =
        identity.nodePid == snapshot.pid &&
            identity.nodeStartTimeTicks == snapshot.startTimeTicks &&
            snapshot.uid == identity.brokerUid &&
            snapshot.state != 'Z' &&
            (snapshot.environ.contains(ownerEntry(identity.ownerMarker)) ||
                readLedger(identity).any { it.matches(snapshot) }) &&
            isNodeProcess(snapshot.commandLine)

    private fun matchesChild(identity: HarnessProcessIdentity, snapshot: ProcSnapshot): Boolean =
        identity.childPid == snapshot.pid &&
            identity.childStartTimeTicks == snapshot.startTimeTicks &&
            snapshot.uid == identity.brokerUid &&
            snapshot.state != 'Z' &&
            (snapshot.environ.contains(ownerEntry(identity.ownerMarker)) ||
                readLedger(identity).any { it.matches(snapshot) })

    private fun waitUntilNodeGone(identity: HarnessProcessIdentity, timeoutMs: Long): Boolean {
        val nodePid = identity.nodePid ?: return true
        val deadline = System.nanoTime() + timeoutMs.coerceAtLeast(0L) * 1_000_000L
        while (System.nanoTime() < deadline) {
            val node = readSnapshot(nodePid)
            if (node == null || !matchesNode(identity, node)) return true
            sleep(minOf(50L, remainingMillis(deadline).coerceAtLeast(1L)))
        }
        val node = readSnapshot(nodePid)
        return node == null || !matchesNode(identity, node)
    }

    private fun remainingMillis(deadline: Long): Long =
        ((deadline - System.nanoTime()) / 1_000_000L).coerceAtLeast(0L)

    private fun waitUntilGone(identity: HarnessProcessIdentity, timeoutMs: Long): Boolean {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        while (System.nanoTime() < deadline) {
            if (!isOwnerAlive(identity)) return true
            sleep(50L)
        }
        return !isOwnerAlive(identity)
    }

    private fun waitForSnapshot(pid: Int, timeoutMs: Long): ProcSnapshot? {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        while (System.nanoTime() < deadline) {
            readSnapshot(pid)?.let { return it }
            sleep(25L)
        }
        return readSnapshot(pid)
    }

    private fun waitForNodeSnapshot(
        rootPid: Int,
        ownerMarker: String,
        timeoutMs: Long
    ): ProcSnapshot? {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        while (System.nanoTime() < deadline) {
            val entries = scanSnapshots()
            val node = selectTree(entries, rootPid)
                .filter { it.environ.contains(ownerEntry(ownerMarker)) && isNodeProcess(it.commandLine) }
                .minByOrNull { treeDepth(it.pid, rootPid) }
            if (node != null) return node
            sleep(25L)
        }
        val entries = scanSnapshots()
        return selectTree(entries, rootPid)
            .filter { it.environ.contains(ownerEntry(ownerMarker)) && isNodeProcess(it.commandLine) }
            .minByOrNull { treeDepth(it.pid, rootPid) }
    }

    private fun scanSnapshots(): List<ProcSnapshot> =
        procRoot.listFiles { file -> file.isDirectory && file.name.all(Char::isDigit) }
            ?.mapNotNull { readSnapshot(it.name.toIntOrNull() ?: return@mapNotNull null) }
            ?.filter { it.uid == currentUid && it.pid != currentPid }
            ?: emptyList()

    private fun readSnapshot(pid: Int): ProcSnapshot? = runCatching {
        val directory = File(procRoot, pid.toString())
        if (!directory.isDirectory) return@runCatching null
        val stat = readBoundedText(File(directory, "stat")) ?: return@runCatching null
        val afterName = stat.substringAfterLast(") ", missingDelimiterValue = "")
        val fields = afterName.split(Regex("\\s+"))
        val parentPid = fields.getOrNull(1)?.toIntOrNull() ?: return@runCatching null
        val processGroupId = fields.getOrNull(2)?.toIntOrNull() ?: return@runCatching null
        val startTimeTicks = fields.getOrNull(19)?.toLongOrNull() ?: return@runCatching null
        val state = fields.getOrNull(0)?.firstOrNull() ?: return@runCatching null
        if (state == 'Z') return@runCatching null
        val uid = parseUid(
            readBoundedText(File(directory, "status"), MAX_PROC_TEXT_BYTES)
                ?: return@runCatching null
        ) ?: return@runCatching null
        val commandLine = readBoundedText(File(directory, "cmdline")) ?: return@runCatching null
        val environment = readBoundedText(File(directory, "environ")) ?: return@runCatching null
        ProcSnapshot(
            pid = pid,
            uid = uid,
            parentPid = parentPid,
            processGroupId = processGroupId,
            startTimeTicks = startTimeTicks,
            state = state,
            commandLine = commandLine.replace('\u0000', ' ').trim(),
            environ = environment.split('\u0000').toSet()
        )
    }.getOrNull()

    private fun waitForChildPid(runHost: File, timeoutMs: Long): Int? {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        while (System.nanoTime() < deadline) {
            runCatching {
                File(runHost, "harness-child.pid").readText().trim().toIntOrNull()
            }.getOrNull()?.takeIf { it > 0 }?.let { return it }
            sleep(25L)
        }
        return runCatching {
            File(runHost, "harness-child.pid").readText().trim().toIntOrNull()
        }.getOrNull()?.takeIf { it > 0 }
    }

    private fun selectTree(entries: List<ProcSnapshot>, rootPid: Int): List<ProcSnapshot> {
        val selected = linkedSetOf(rootPid)
        var changed: Boolean
        do {
            changed = false
            entries.filter { it.pid !in selected && it.parentPid in selected }.forEach {
                changed = selected.add(it.pid) || changed
            }
        } while (changed)
        return entries.filter { it.pid in selected }
    }

    private fun treeDepth(snapshotPid: Int, rootPid: Int): Int {
        var current = snapshotPid
        var depth = 0
        val seen = mutableSetOf<Int>()
        while (seen.add(current) && current != rootPid) {
            current = readSnapshot(current)?.parentPid ?: break
            depth += 1
        }
        return depth
    }

    private fun parseUid(status: String): Int? = status.lineSequence()
        .firstOrNull { it.startsWith("Uid:") }
        ?.removePrefix("Uid:")
        ?.trim()
        ?.split(Regex("\\s+"))
        ?.firstOrNull()
        ?.toIntOrNull()

    private fun ownerEntry(marker: String): String = "ADT_HARNESS_OWNER_MARKER=$marker"

    private fun isNodeProcess(commandLine: String): Boolean {
        val executable = commandLine.trim().substringBefore(' ').substringAfterLast('/')
        return executable == "node" || executable == "nodejs" || executable.startsWith("node-")
    }

    private fun readLedger(identity: HarnessProcessIdentity): List<LedgerEntry> =
        readLedger(identity.ownerMarker)

    private fun readLedger(ownerMarker: String): List<LedgerEntry> {
        val file = ledgerFile?.takeIf { it.isFile } ?: return emptyList()
        return runCatching {
            val contents = readBoundedText(file, MAX_LEDGER_BYTES) ?: return@runCatching emptyList()
            val lines = contents.lineSequence().toList()
            if (lines.firstOrNull() != "adt-harness-process-ledger-v1") return@runCatching emptyList()
            if (lines.getOrNull(1) != "marker=$ownerMarker") return@runCatching emptyList()
            lines.drop(2)
                .asSequence()
                .filter { it.startsWith("process ") }
                .take(MAX_LEDGER_ENTRIES)
                .mapNotNull { line ->
                    val fields = line.split(' ')
                    if (fields.size != 4) return@mapNotNull null
                    val pid = fields[1].toIntOrNull() ?: return@mapNotNull null
                    val start = fields[2].toLongOrNull() ?: return@mapNotNull null
                    val uid = fields[3].toIntOrNull() ?: return@mapNotNull null
                    if (pid <= 0 || start <= 0L || uid < 0) null else LedgerEntry(pid, start, uid)
                }
                .toList()
        }.getOrDefault(emptyList())
    }

    private data class ProcSnapshot(
        val pid: Int,
        val uid: Int,
        val parentPid: Int,
        val processGroupId: Int,
        val startTimeTicks: Long,
        val state: Char,
        val commandLine: String,
        val environ: Set<String>
    )

    private data class LedgerEntry(
        val pid: Int,
        val startTimeTicks: Long,
        val uid: Int
    ) {
        fun matches(snapshot: ProcSnapshot): Boolean =
            snapshot.pid == pid && snapshot.startTimeTicks == startTimeTicks && snapshot.uid == uid
    }

    private companion object {
        const val MAX_LEDGER_ENTRIES = 256
    }
}
