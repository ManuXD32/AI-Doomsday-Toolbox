package com.example.llamadroid.harness

import android.util.Base64
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.Session
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Session-scoped PTYs and subprocess pipes for the pinned official SSH adapter. */
class HarnessSshProcesses(private val files: HarnessWorkspaceAccess, private val diagnostics: HarnessDiagnostics) {
    private class Process(
        val id: String, val sessionId: String, val connection: Session, val channel: ChannelExec,
        val stdout: HarnessProcessBuffer = HarnessProcessBuffer(), val stderr: HarnessProcessBuffer = HarnessProcessBuffer()
    ) {
        @Volatile var state = "running"
        @Volatile var exitCode: Int? = null
        var pump: Job? = null
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val processes = ConcurrentHashMap<String, Process>()
    private val lifecycle = Mutex()
    @Volatile private var generation = 0L

    suspend fun invoke(owner: HarnessSessionScope, method: String, args: JSONObject): JSONObject {
        require(owner.workspace.backend == "REMOTE_SSH")
        if (method == "workspace.process.start") return start(owner, args)
        val process = requireNotNull(processes[args.getString("id")]) { "REMOTE_PROCESS_MISSING" }
        require(process.sessionId == owner.session.harnessSessionId) { "REMOTE_PROCESS_SCOPE_MISMATCH" }
        return when (method) {
            "workspace.process.read" -> {
                val stdout = process.stdout.read(args.optLong("stdoutOffset"))
                val stderr = process.stderr.read(args.optLong("stderrOffset"))
                JSONObject().put("stdoutBase64", Base64.encodeToString(stdout.bytes, Base64.NO_WRAP))
                    .put("stderrBase64", Base64.encodeToString(stderr.bytes, Base64.NO_WRAP))
                    .put("stdoutOffset", stdout.offset).put("stderrOffset", stderr.offset)
                    .put("truncated", stdout.truncated || stderr.truncated).put("status", process.state)
                    .put("exitCode", process.exitCode ?: JSONObject.NULL)
            }
            "workspace.process.write" -> {
                val encoded = args.getString("dataBase64"); require(encoded.length <= 90_000)
                val data = Base64.decode(encoded, Base64.DEFAULT); require(data.size <= 64 * 1024)
                synchronized(process) {
                    check(process.channel.isConnected) { "REMOTE_PROCESS_EXITED" }
                    process.channel.outputStream.apply { write(data); flush() }
                }
                JSONObject().put("written", data.size)
            }
            "workspace.process.resize" -> {
                process.channel.setPtySize(args.getInt("cols").coerceIn(2, 500), args.getInt("rows").coerceIn(1, 200), 0, 0)
                JSONObject().put("resized", true)
            }
            "workspace.process.endInput" -> {
                synchronized(process) { process.channel.outputStream.close() }
                JSONObject().put("ended", true)
            }
            "workspace.process.close" -> {
                close(process)
                JSONObject().put("closed", true)
            }
            else -> error("BRIDGE_METHOD_UNKNOWN")
        }
    }

    private suspend fun start(owner: HarnessSessionScope, args: JSONObject): JSONObject = lifecycle.withLock {
        val currentGeneration = generation
        require(processes.values.count { it.state == "running" } < 16 && processes.size < 64) { "REMOTE_PROCESS_LIMIT" }
        val command = args.getString("command"); require(command.isNotBlank() && command.length <= 64 * 1024)
        val id = UUID.randomUUID().toString()
        val (connection, directory) = files.openOwnedProcess(owner, id, args.optString("cwd", "."))
        var openedChannel: ChannelExec? = null
        try {
            val channel = (connection.openChannel("exec") as ChannelExec).also { openedChannel = it }
            check(currentGeneration == generation) { "HARNESS_STOPPING" }
            val environment = args.optJSONObject("env") ?: JSONObject()
            val prefix = environment.keys().asSequence().map { key ->
                require(key.matches(Regex("[A-Za-z_][A-Za-z0-9_]*")) && !key.startsWith("ADT_HARNESS_"))
                val value = environment.getString(key); require(value.length <= 8192 && '\u0000' !in value)
                "$key=${quote(value)}"
            }.toList().also { require(it.size <= 100) }.joinToString(" ")
            channel.setCommand("cd ${quote(directory)} && $prefix ADT_HARNESS_REMOTE_OWNER=${quote(id)} sh -c ${quote(command)}")
            if (args.optBoolean("pty")) {
                channel.setPty(true)
                channel.setPtyType("xterm-256color", args.optInt("cols", 80).coerceIn(2, 500), args.optInt("rows", 24).coerceIn(1, 200), 0, 0)
            }
            val stdout = channel.inputStream
            val stderr = channel.errStream
            channel.connect(10_000)
            check(currentGeneration == generation) { "HARNESS_STOPPING" }
            val process = Process(id, owner.session.harnessSessionId, connection, channel)
            synchronized(processes) {
                check(currentGeneration == generation) { "HARNESS_STOPPING" }
                processes[id] = process
            }
            process.pump = scope.launch {
                try {
                    coroutineScope {
                        val out = async { pump(stdout, process.stdout) }
                        val err = async { pump(stderr, process.stderr) }
                        while (!channel.isClosed) delay(40)
                        out.await(); err.await()
                    }
                    process.exitCode = channel.exitStatus.takeIf { it >= 0 }
                    process.state = "exited"
                } catch (cancel: CancellationException) { process.state = "interrupted"; throw cancel }
                catch (error: Exception) {
                    process.state = "interrupted"
                    diagnostics.event(process.sessionId, "ssh_process", "INTERRUPTED", errorCode = error.javaClass.simpleName)
                }
            }
            JSONObject().put("id", id).put("status", "running")
        } catch (failure: Throwable) {
            openedChannel?.disconnect()
            processes.remove(id)?.pump?.cancel()
            withContext(NonCancellable) {
                try { files.closeOwnedProcess(id, connection) }
                catch (_: Exception) {
                    diagnostics.event(owner.session.harnessSessionId, "ssh_process", "INTERRUPTED", errorCode = "SSH_CLEANUP_PENDING")
                }
            }
            throw failure
        }
    }

    private fun pump(input: InputStream, buffer: HarnessProcessBuffer) {
        input.use {
            val chunk = ByteArray(16 * 1024)
            while (true) {
                val count = it.read(chunk)
                if (count < 0) break
                buffer.append(chunk, count)
            }
        }
    }
    private suspend fun close(process: Process) {
        try { files.closeOwnedProcess(process.id, process.connection) }
        finally {
            process.channel.disconnect(); process.pump?.cancel(); process.state = "interrupted"
            processes.remove(process.id, process)
        }
    }
    fun cancelAll() {
        val owned = synchronized(processes) {
            generation++
            processes.values.toList().also { processes.clear() }
        }
        owned.forEach { process ->
            process.channel.disconnect(); process.connection.disconnect(); process.pump?.cancel(); process.state = "interrupted"
        }
        // Durable SSH cleanup receipts remain available for the runtime's independent retry path.
    }
    private fun quote(value: String) = "'" + value.replace("'", "'\"'\"'") + "'"
}

/** Byte offsets remain monotonic even when a slow reader loses older output. */
internal class HarnessProcessBuffer(private val limit: Int = 1024 * 1024) {
    data class Read(val bytes: ByteArray, val offset: Long, val truncated: Boolean)
    private val bytes = ByteArray(limit)
    private var position = 0L
    @Synchronized fun append(chunk: ByteArray, count: Int) {
        require(count in 0..chunk.size)
        for (index in 0 until count) { bytes[(position % limit).toInt()] = chunk[index]; position++ }
    }
    @Synchronized fun read(offset: Long): Read {
        require(offset >= 0 && offset <= position)
        val oldest = maxOf(0L, position - limit)
        val start = maxOf(offset, oldest)
        val size = minOf(position - start, 64 * 1024L).toInt()
        val result = ByteArray(size) { index -> bytes[((start + index) % limit).toInt()] }
        return Read(result, start + size, offset < oldest)
    }
}
