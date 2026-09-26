package com.example.llamadroid.service

import android.content.Context
import android.util.Log
import com.example.llamadroid.data.proot.AgentProotEnvironmentManager
import com.example.llamadroid.data.proot.AgentProotEnvironmentPaths
import com.example.llamadroid.data.proot.AgentProotEnvironmentSpec
import com.example.llamadroid.data.proot.AgentProotNetworkConfig
import com.example.llamadroid.data.proot.AgentProotNativeBinaryProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.nio.charset.Charset
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** A shell command executed inside one app-private Debian environment. */
data class AgentProotCommandRequest(
    /** Stable identity allocated and persisted by the coordinator before process creation. */
    val runId: String = UUID.randomUUID().toString(),
    val environmentId: String,
    val projectFolder: String,
    val command: String,
    val workingDirectory: String = AgentProotEnvironmentPaths.WORKSPACE_MOUNT,
    val environment: Map<String, String> = emptyMap(),
    val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    val maxOutputBytes: Int = DEFAULT_OUTPUT_BYTES,
    val background: Boolean = false,
    val previewPort: Int? = null,
    val maxProcesses: Int = DEFAULT_MAX_PROCESSES,
    val maxOpenFiles: Int = DEFAULT_MAX_OPEN_FILES,
    val maxFileBytes: Long = DEFAULT_MAX_FILE_BYTES
) {
    init {
        require(runId.matches(Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,95}"))) { "Run ID is invalid" }
        require(command.isNotBlank()) { "Command must not be blank" }
        require(timeoutMs in 1L..MAX_TIMEOUT_MS) { "Command timeout is outside the allowed range" }
        require(maxOutputBytes in 1..MAX_OUTPUT_BYTES) { "Command output limit is outside the allowed range" }
        require(previewPort == null || previewPort in 1024..65535) { "Preview port is invalid" }
        require(maxProcesses in 1..MAX_PROCESSES) { "Process limit is outside the allowed range" }
        require(maxOpenFiles in 16..MAX_OPEN_FILES) { "Open-file limit is outside the allowed range" }
        require(maxFileBytes in 1L..MAX_FILE_BYTES) { "File-size limit is outside the allowed range" }
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS = 30 * 60 * 1_000L
        const val MAX_TIMEOUT_MS = 2 * 60 * 60 * 1_000L
        const val DEFAULT_OUTPUT_BYTES = 2 * 1024 * 1024
        const val MAX_OUTPUT_BYTES = 8 * 1024 * 1024
        const val DEFAULT_MAX_PROCESSES = 128
        const val MAX_PROCESSES = 512
        const val DEFAULT_MAX_OPEN_FILES = 512
        const val MAX_OPEN_FILES = 2_048
        const val DEFAULT_MAX_FILE_BYTES = 512L * 1024L * 1024L
        const val MAX_FILE_BYTES = 4L * 1024L * 1024L * 1024L
    }
}

enum class AgentProotCommandStatus {
    STARTING,
    RUNNING,
    SUCCEEDED,
    FAILED,
    TIMED_OUT,
    CANCELLED,
    INTERRUPTED
}

data class AgentProotCommandResult(
    val runId: String,
    val environmentId: String,
    val projectFolder: String,
    val status: AgentProotCommandStatus,
    val exitCode: Int? = null,
    val output: String = "",
    val outputTruncated: Boolean = false,
    val startedAt: Long? = null,
    val endedAt: Long? = null,
    val previewUrl: String? = null,
    val error: String? = null
)

/**
 * Workspace execution seam shared by local Debian and the existing remote implementation.
 * A foreground command returns its terminal receipt; a background command returns a RUNNING
 * receipt and can be inspected/cancelled by run ID.
 */
interface AgentWorkspaceCommandExecutor {
    suspend fun execute(request: AgentProotCommandRequest): AgentProotCommandResult
    fun status(runId: String): AgentProotCommandResult?
    suspend fun cancel(runId: String): AgentProotCommandResult?
}

/** Options shared by every launch of the pinned Termux PRoot executable. */
internal object AgentProotLaunchOptions {
    fun forRootfs(rootfs: File): List<String> = listOf(
        "-v", "-1",
        "-0",
        "--link2symlink",
        "-r", rootfs.absolutePath
    )
}

/**
 * Executes project commands through the packaged PRoot binary. The caller is responsible for
 * plan/command approval; this class only enforces the filesystem boundary and process limits.
 */
class AgentProotCommandExecutor(
    private val context: Context,
    private val environmentManager: AgentProotEnvironmentManager = AgentProotEnvironmentManager(context)
) : AgentWorkspaceCommandExecutor {
    private data class ActiveRun(
        val request: AgentProotCommandRequest,
        val result: AgentProotCommandResult,
        val process: Process,
        val environmentLock: Mutex,
        val job: Job
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val active = ConcurrentHashMap<String, ActiveRun>()
    private val pending = ConcurrentHashMap<String, Job>()
    private val results = ConcurrentHashMap<String, AgentProotCommandResult>()
    private val environmentLocks = ConcurrentHashMap<String, Mutex>()

    override suspend fun execute(request: AgentProotCommandRequest): AgentProotCommandResult {
        val runId = request.runId
        val initial = AgentProotCommandResult(
            runId = runId,
            environmentId = request.environmentId,
            projectFolder = request.projectFolder,
            status = AgentProotCommandStatus.STARTING,
            startedAt = System.currentTimeMillis(),
            previewUrl = request.previewPort?.let { "http://127.0.0.1:$it/" }
        )
        results[runId] = initial

        val lock = environmentLocks.getOrPut(request.environmentId) { Mutex() }
        if (request.background) {
            val job = scope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
                try {
                    lock.withLock {
                        runCommand(runId, request, lock)
                    }
                } catch (cancelled: CancellationException) {
                    results[runId] = (results[runId] ?: AgentProotCommandResult(
                        runId = runId,
                        environmentId = request.environmentId,
                        projectFolder = request.projectFolder,
                        status = AgentProotCommandStatus.STARTING,
                        startedAt = System.currentTimeMillis()
                    )).copy(
                        status = AgentProotCommandStatus.CANCELLED,
                        endedAt = System.currentTimeMillis(),
                        error = "Command cancelled before it started."
                    )
                } finally {
                    pending.remove(runId)
                }
            }
            pending[runId] = job
            job.start()
            // The Process is only available after the coroutine enters runCommand. Keep a
            // durable STARTING receipt and let status() expose RUNNING as soon as it starts.
            // Cancellation before process creation cancels the launch job safely.
            return initial.copy(status = AgentProotCommandStatus.RUNNING)
        }

        return lock.withLock { runCommand(runId, request, lock) }
    }

    override fun status(runId: String): AgentProotCommandResult? =
        results[runId] ?: active[runId]?.result

    override suspend fun cancel(runId: String): AgentProotCommandResult? = withContext(Dispatchers.IO) {
        val current = results[runId] ?: return@withContext null
        val running = active[runId]
        if (running == null) {
            pending.remove(runId)?.let { job ->
                job.cancel(CancellationException("PRoot command cancelled before start"))
                val cancelled = current.copy(
                    status = AgentProotCommandStatus.CANCELLED,
                    endedAt = System.currentTimeMillis(),
                    error = "Command cancelled before it started."
                )
                results[runId] = cancelled
                return@withContext cancelled
            }
            return@withContext current.takeIf {
                it.status == AgentProotCommandStatus.STARTING || it.status == AgentProotCommandStatus.RUNNING
            }
        }
        terminate(running.process)
        running.job.cancel(CancellationException("PRoot command cancelled by user"))
        val cancelled = current.copy(
            status = AgentProotCommandStatus.CANCELLED,
            endedAt = System.currentTimeMillis(),
            error = "Command cancelled by user."
        )
        results[runId] = cancelled
        cancelled
    }

    private suspend fun runCommand(
        runId: String,
        request: AgentProotCommandRequest,
        environmentLock: Mutex
    ): AgentProotCommandResult {
        val startedAt = System.currentTimeMillis()
        val runningReceipt = AgentProotCommandResult(
            runId = runId,
            environmentId = request.environmentId,
            projectFolder = request.projectFolder,
            status = AgentProotCommandStatus.RUNNING,
            startedAt = startedAt,
            previewUrl = request.previewPort?.let { "http://127.0.0.1:$it/" }
        )
        results[runId] = runningReceipt
        val prepared = runCatching {
            environmentManager.prepare(AgentProotEnvironmentSpec(request.environmentId)).getOrThrow()
        }
        val rootfs = prepared.getOrElse { error ->
            val failed = runningReceipt.copy(
                status = AgentProotCommandStatus.FAILED,
                endedAt = System.currentTimeMillis(),
                error = error.message?.take(1_000) ?: "Unable to prepare Debian environment"
            )
            results[runId] = failed
            return failed
        }

        val projectRoot = AgentLocalWorkspaceSupport.rootForProject(context, request.projectFolder).canonicalFile
        val tempRoot = File(context.cacheDir, "agent-proot/${request.environmentId}/tmp").canonicalFile
        val runRoot = File(context.cacheDir, "agent-proot/${request.environmentId}/run").canonicalFile
        tempRoot.mkdirs()
        runRoot.mkdirs()
        val resolverFile = AgentProotNetworkConfig.write(context, request.environmentId)
        val cwd = resolveWorkingDirectory(request.workingDirectory, projectRoot)
        val command = buildCommand(rootfs, projectRoot, tempRoot, runRoot, resolverFile, cwd, request)
        val process = try {
            ProcessBuilder(command)
                .directory(projectRoot)
                .redirectErrorStream(false)
                .apply {
                    environment()["PROOT_NO_SECCOMP"] = "1"
                    environment()["PROOT_TMP_DIR"] = tempRoot.absolutePath
                    environment()["LD_LIBRARY_PATH"] = context.applicationInfo.nativeLibraryDir
                    AgentProotNativeBinaryProvider.locateLoader(context)?.let { loader ->
                        // The packaged Termux-compatible loader is relocatable through this
                        // override; never rely on a writable-storage or Termux absolute path.
                        environment()["PROOT_LOADER"] = loader.absolutePath
                    }
                    request.environment.forEach { (key, value) ->
                        require(SAFE_ENVIRONMENT_KEY.matches(key)) { "Invalid environment variable name: $key" }
                        require(value.length <= 16_384) { "Environment variable is too long: $key" }
                        environment()[key] = value
                    }
                    // Agent commands have no controlling terminal. Package tools must not wait
                    // for input here; the separate user terminal remains fully interactive.
                    environment()["HOME"] = "/root"
                    environment()["SHELL"] = "/bin/bash"
                    environment()["PATH"] = "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
                    environment()["TERM"] = "xterm-256color"
                    environment()["COLORTERM"] = "truecolor"
                    environment()["LANG"] = "C.UTF-8"
                    environment()["LC_ALL"] = "C.UTF-8"
                    environment()["DEBIAN_FRONTEND"] = "noninteractive"
                    environment()["APT_LISTCHANGES_FRONTEND"] = "none"
                    environment()["ADT_PROJECT_ID"] = request.projectFolder
                    request.previewPort?.let { environment()["ADT_PORT"] = it.toString() }
                }
                .start()
        } catch (error: Throwable) {
            val failed = runningReceipt.copy(
                status = AgentProotCommandStatus.FAILED,
                endedAt = System.currentTimeMillis(),
                error = error.message?.take(1_000) ?: "Unable to start PRoot"
            )
            results[runId] = failed
            return failed
        }

        val job = kotlinx.coroutines.currentCoroutineContext()[Job] ?: error("Missing command job")
        active[runId] = ActiveRun(request, runningReceipt, process, environmentLock, job)
        try {
            val output = BoundedProcessOutput(request.maxOutputBytes)
            val latch = CountDownLatch(2)
            val readers = Executors.newFixedThreadPool(2)
            readers.execute { drain(process.inputStream, "stdout", output, latch) }
            readers.execute { drain(process.errorStream, "stderr", output, latch) }
            val completed = process.waitFor(request.timeoutMs, TimeUnit.MILLISECONDS)
            val status = when {
                completed && process.exitValue() == 0 -> AgentProotCommandStatus.SUCCEEDED
                completed -> AgentProotCommandStatus.FAILED
                else -> AgentProotCommandStatus.TIMED_OUT
            }
            if (!completed) terminate(process)
            latch.await(5, TimeUnit.SECONDS)
            readers.shutdownNow()
            val result = runningReceipt.copy(
                status = status,
                exitCode = if (completed) process.exitValue() else null,
                output = output.value(),
                outputTruncated = output.truncated,
                endedAt = System.currentTimeMillis(),
                error = if (status == AgentProotCommandStatus.TIMED_OUT) {
                    "Command timed out after ${request.timeoutMs} ms."
                } else null
            )
            results[runId] = result
            return result
        } catch (cancelled: CancellationException) {
            terminate(process)
            val result = runningReceipt.copy(
                status = AgentProotCommandStatus.CANCELLED,
                endedAt = System.currentTimeMillis(),
                error = "Command cancelled."
            )
            results[runId] = result
            return result
        } catch (error: Throwable) {
            terminate(process)
            val result = runningReceipt.copy(
                status = AgentProotCommandStatus.FAILED,
                endedAt = System.currentTimeMillis(),
                error = error.message?.take(1_000) ?: "PRoot command failed"
            )
            results[runId] = result
            return result
        } finally {
            active.remove(runId)
        }
    }

    private fun buildCommand(
        rootfs: File,
        projectRoot: File,
        tempRoot: File,
        runRoot: File,
        resolverFile: File,
        cwd: String,
        request: AgentProotCommandRequest
    ): List<String> {
        val broker = AgentProotNativeBinaryProvider.requireBroker(context)
        val proot = AgentProotNativeBinaryProvider.requireProot(context)
        val command = mutableListOf(
            broker.absolutePath,
            "--max-processes", request.maxProcesses.toString(),
            "--max-open-files", request.maxOpenFiles.toString(),
            "--max-file-bytes", request.maxFileBytes.toString(),
            "--term-grace-ms", "3000", "--",
            proot.absolutePath
        )
        command += AgentProotLaunchOptions.forRootfs(rootfs)
        command += listOf(
            "-b", "${projectRoot.absolutePath}:${AgentProotEnvironmentPaths.WORKSPACE_MOUNT}",
            "-b", "${tempRoot.absolutePath}:${AgentProotEnvironmentPaths.TMP_MOUNT}",
            "-b", "${runRoot.absolutePath}:${AgentProotEnvironmentPaths.RUN_MOUNT}",
            "-b", "${resolverFile.absolutePath}:/etc/resolv.conf",
            "-b", "/proc:/proc",
        )
        listOf("null", "zero", "random", "urandom").forEach { device ->
            val host = File("/dev", device)
            if (host.exists()) {
                command += "-b"
                command += "${host.absolutePath}:/dev/$device"
            }
        }
        command += "-w"
        command += cwd
        command += "/bin/sh"
        command += "-c"
        command += request.command
        return command
    }

    private fun resolveWorkingDirectory(raw: String, projectRoot: File): String {
        val normalized = raw.replace('\\', '/').trim().ifBlank { AgentProotEnvironmentPaths.WORKSPACE_MOUNT }
        val relative = when {
            normalized == AgentProotEnvironmentPaths.WORKSPACE_MOUNT -> ""
            normalized.startsWith(AgentProotEnvironmentPaths.WORKSPACE_MOUNT + "/") ->
                normalized.removePrefix(AgentProotEnvironmentPaths.WORKSPACE_MOUNT + "/")
            !normalized.startsWith("/") -> normalized
            else -> throw IllegalArgumentException("PRoot working directory must stay in /workspace")
        }
        require(AgentLocalWorkspaceSupport.isSafeRelativePath(relative.ifBlank { "workspace" })) {
            "PRoot working directory contains unsafe path segments"
        }
        val host = if (relative.isBlank()) projectRoot else File(projectRoot, relative)
        val canonical = host.canonicalFile
        require(canonical == projectRoot || canonical.path.startsWith(projectRoot.path + File.separator)) {
            "PRoot working directory escaped the project"
        }
        require(canonical.isDirectory) { "PRoot working directory does not exist: $raw" }
        return if (relative.isBlank()) AgentProotEnvironmentPaths.WORKSPACE_MOUNT
        else "${AgentProotEnvironmentPaths.WORKSPACE_MOUNT}/$relative"
    }

    private fun terminate(process: Process) {
        runCatching { process.destroy() }
        runCatching {
            if (!process.waitFor(3, TimeUnit.SECONDS)) process.destroyForcibly()
        }
    }

    private fun drain(input: InputStream, streamName: String, output: BoundedProcessOutput, latch: CountDownLatch) {
        try {
            input.bufferedReader(Charset.defaultCharset()).useLines { lines ->
                lines.forEach { line -> output.append("[$streamName] $line\n") }
            }
        } catch (error: Throwable) {
            Log.d(TAG, "PRoot $streamName reader stopped: ${error.message}")
        } finally {
            latch.countDown()
        }
    }

    private class BoundedProcessOutput(private val maxBytes: Int) {
        private val lock = Any()
        private val builder = StringBuilder()
        private var encodedBytes = 0
        var truncated: Boolean = false
            private set

        fun append(value: String) {
            synchronized(lock) {
                if (encodedBytes >= maxBytes) {
                    truncated = true
                    return@synchronized
                }
                val remaining = maxBytes - encodedBytes
                val bytes = value.toByteArray(Charsets.UTF_8)
                if (bytes.size <= remaining) {
                    builder.append(value)
                    encodedBytes += bytes.size
                } else {
                    val clipped = bytes.copyOf(remaining)
                        .toString(Charsets.UTF_8)
                        .trimEnd('\uFFFD')
                    builder.append(clipped)
                    encodedBytes += clipped.toByteArray(Charsets.UTF_8).size
                    truncated = true
                }
            }
        }

        fun value(): String = synchronized(lock) { builder.toString() }
    }

    private companion object {
        const val TAG = "AgentProotCommand"
        val SAFE_ENVIRONMENT_KEY = Regex("[A-Za-z_][A-Za-z0-9_]{0,63}")
    }
}
