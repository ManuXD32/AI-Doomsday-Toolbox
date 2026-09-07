package com.example.llamadroid.service

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.example.llamadroid.data.model.TermuxTools
import com.example.llamadroid.data.SettingsRepository
import com.example.llamadroid.util.DebugLog
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Properties

data class SSHConfig(
    val host: String = "127.0.0.1",
    val port: Int = 8025,
    val user: String = "root",
    val password: String = "",
    val privateKeyPath: String? = null
)

/**
 * SSH Service for connecting to the Ubuntu SSH server used by Termux tools.
 * Uses SINGLETON pattern to persist connection across navigation.
 *
 * Default/example setup:
 * - Host: user-configurable
 * - Port: 8025 by default, but user-configurable
 * - User: root
 * - Auth: password or optional key
 */
class SSHService(private val context: Context) {
    
    companion object {
        // Global CoroutineScope that persists across navigation
        private val persistentScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        
        // Global connection state (persists across navigation)
        private val _isConnected = MutableStateFlow(false)
        val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()
        
        // Global output (persists across navigation)
        private val _output = MutableStateFlow("")
        val output: StateFlow<String> = _output.asStateFlow()
        
        // Global execution state
        private val _isExecuting = MutableStateFlow(false)
        val isExecuting: StateFlow<Boolean> = _isExecuting.asStateFlow()
        
        private val _currentCommand = MutableStateFlow("")
        val currentCommand: StateFlow<String> = _currentCommand.asStateFlow()

        // Tools running state (persists across navigation)
        private val _runningTools = MutableStateFlow<Set<String>>(emptySet())
        val runningTools: StateFlow<Set<String>> = _runningTools.asStateFlow()

        private val _installedTools = MutableStateFlow<Set<String>>(emptySet())
        val installedTools: StateFlow<Set<String>> = _installedTools.asStateFlow()
        
        // Per-tool output tracking (persists across navigation)
        private val _toolOutputs = MutableStateFlow<Map<String, String>>(emptyMap())
        val toolOutputs: StateFlow<Map<String, String>> = _toolOutputs.asStateFlow()
        
        private val _toolExecuting = MutableStateFlow<Set<String>>(emptySet())
        val toolExecuting: StateFlow<Set<String>> = _toolExecuting.asStateFlow()

        fun setRunningTools(tools: Set<String>) {
            _runningTools.value = tools
        }

        fun setInstalledTools(tools: Set<String>) {
            _installedTools.value = tools
        }
        
        fun appendToolOutput(toolId: String, text: String) {
            val current = _toolOutputs.value.toMutableMap()
            val existing = current[toolId] ?: ""
            val newOutput = existing + text
            // Limit per-tool output to 30KB
            current[toolId] = if (newOutput.length > 30_000) {
                "...[trimmed]...\n" + newOutput.takeLast(29_980)
            } else {
                newOutput
            }
            _toolOutputs.value = current
        }
        
        fun clearToolOutput(toolId: String) {
            val current = _toolOutputs.value.toMutableMap()
            current.remove(toolId)
            _toolOutputs.value = current
        }
        
        fun setToolExecuting(toolId: String, executing: Boolean) {
            val current = _toolExecuting.value.toMutableSet()
            if (executing) current.add(toolId) else current.remove(toolId)
            _toolExecuting.value = current
        }

        // Host settings (persistent)
        private val _config = MutableStateFlow(SSHConfig())
        val config: StateFlow<SSHConfig> = _config.asStateFlow()

        fun setConfig(host: String, port: Int, user: String, pass: String) {
            _config.value = SSHConfig(host, port, user, pass)
        }
        
        // Singleton session (survives navigation)
        private var session: Session? = null
        private val jsch = JSch()
        private val lanAddressRegex = Regex("""\b(?:\d{1,3}\.){3}\d{1,3}\b""")
        private val toolLogTailJobs = mutableMapOf<String, Job>()
        private val toolLogTailSessions = mutableMapOf<String, Session>()
        
        const val DEFAULT_PORT = 8025
        const val DEFAULT_HOST = "127.0.0.1"
        private const val SSH_SERVER_ALIVE_INTERVAL_MS = 15_000
        private const val SSH_SERVER_ALIVE_COUNT_MAX = 6
        
        // Limit output to prevent lag (50KB max)
        private const val MAX_OUTPUT_LENGTH = 50_000
        
        // Clear output
        fun clearOutput() {
            _output.value = ""
        }
        
        // Append to output with size limit
        fun appendOutput(text: String) {
            val newOutput = _output.value + text
            // Trim from start if too long
            _output.value = if (newOutput.length > MAX_OUTPUT_LENGTH) {
                "...[trimmed]...\n" + newOutput.takeLast(MAX_OUTPUT_LENGTH - 20)
            } else {
                newOutput
            }
        }
        
        // Check if actually connected (connection health check)
        fun checkConnection(): Boolean {
            val currentSession = session
            val connected = currentSession?.isConnected == true
            if (!connected && _isConnected.value) {
                // Server disconnected while we thought we were connected
                _isConnected.value = false
                _runningTools.value = emptySet()
                _installedTools.value = emptySet()
                stopAllToolLogTails()
                DebugLog.log("SSH: Connection lost (detected)")
            }
            return connected
        }
        
        /**
         * Execute a command quietly and return output string (or null on error).
         * Use for background polling without any UI feedback.
         */
        suspend fun executeQuiet(command: String): String? = withContext(Dispatchers.IO) {
            val currentSession = session
            if (currentSession == null || !currentSession.isConnected) {
                return@withContext null
            }
            
            try {
                val channel = currentSession.openChannel("exec") as ChannelExec
                channel.setCommand(command)
                
                val outputStream = java.io.ByteArrayOutputStream()
                channel.inputStream = null
                channel.setOutputStream(outputStream)
                channel.setErrStream(outputStream)
                
                channel.connect(5000)  // Shorter timeout for polling
                
                while (!channel.isClosed) {
                    Thread.sleep(50)
                }
                
                val output = outputStream.toString()
                channel.disconnect()
                output
            } catch (e: Exception) {
                null
            }
        }

        /**
         * Execute a small read-only query against the current session without reconnecting.
         * Unlike [executeQuiet], this path has a hard command deadline and caps combined stdout
         * and stderr so a remote listing cannot retain an unbounded buffer or block a gallery
         * refresh indefinitely.
         */
        suspend fun executeQuietBounded(
            command: String,
            timeoutMillis: Long = 10_000L,
            maxOutputBytes: Int = 2 * 1024 * 1024
        ): String? = withContext(Dispatchers.IO) {
            if (timeoutMillis <= 0L || maxOutputBytes <= 0) return@withContext null
            val currentSession = session
            if (currentSession == null || !currentSession.isConnected) {
                return@withContext null
            }

            val channel = try {
                currentSession.openChannel("exec") as ChannelExec
            } catch (_: Exception) {
                return@withContext null
            }
            val output = BoundedOutputStream(maxOutputBytes)
            val timeoutNanos = timeoutMillis.coerceAtMost(Long.MAX_VALUE / 1_000_000L) * 1_000_000L
            val deadlineNanos = System.nanoTime() + timeoutNanos
            try {
                channel.setCommand(command)
                channel.inputStream = null
                channel.setOutputStream(output)
                channel.setErrStream(output)
                channel.connect(timeoutMillis.coerceIn(1L, 10_000L).toInt())

                while (!channel.isClosed) {
                    currentCoroutineContext().ensureActive()
                    if (output.exceeded || System.nanoTime() >= deadlineNanos) {
                        return@withContext null
                    }
                    // delay is cancellable, unlike the Thread.sleep loop used by the legacy
                    // quiet helper. The finally block below closes the channel on cancellation.
                    delay(50L)
                }
                currentCoroutineContext().ensureActive()
                if (output.exceeded || System.nanoTime() >= deadlineNanos || channel.exitStatus != 0) {
                    null
                } else {
                    output.asUtf8String()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            } finally {
                runCatching { channel.disconnect() }
            }
        }

        private class BoundedOutputStream(maxBytes: Int) : java.io.OutputStream() {
            private val limit = maxBytes
            private val buffer = ByteArrayOutputStream(maxBytes.coerceAtMost(8192))

            @Volatile
            var exceeded: Boolean = false
                private set

            @Synchronized
            override fun write(value: Int) {
                if (buffer.size() >= limit) {
                    exceeded = true
                } else {
                    buffer.write(value)
                }
            }

            @Synchronized
            override fun write(bytes: ByteArray, offset: Int, length: Int) {
                if (offset < 0 || length < 0 || offset > bytes.size - length) {
                    throw IndexOutOfBoundsException()
                }
                val remaining = limit - buffer.size()
                if (remaining <= 0) {
                    exceeded = true
                } else if (length > remaining) {
                    buffer.write(bytes, offset, remaining)
                    exceeded = true
                } else {
                    buffer.write(bytes, offset, length)
                }
            }

            @Synchronized
            fun asUtf8String(): String = buffer.toString(Charsets.UTF_8.name())
        }

        private fun configureSession(target: Session) {
            target.setServerAliveInterval(SSH_SERVER_ALIVE_INTERVAL_MS)
            target.setServerAliveCountMax(SSH_SERVER_ALIVE_COUNT_MAX)
            target.timeout = 30_000
        }

        private fun isRecoverableSessionFailure(error: Throwable?): Boolean {
            val message = error?.message?.lowercase().orEmpty()
            return error is java.net.SocketException ||
                error is java.io.EOFException ||
                message.contains("software caused connection abort") ||
                message.contains("connection reset") ||
                message.contains("broken pipe") ||
                message.contains("socket closed") ||
                message.contains("session is down") ||
                message.contains("channel is not opened")
        }

        fun stopToolLogTail(toolId: String) {
            val job = toolLogTailJobs.remove(toolId) ?: return
            job.cancel()
            toolLogTailSessions.remove(toolId)?.let { tailSession ->
                try {
                    tailSession.disconnect()
                } catch (_: Exception) {}
            }
        }

        private fun stopAllToolLogTails() {
            toolLogTailJobs.keys.toList().forEach(::stopToolLogTail)
        }
    }
    
    private val settingsRepo = SettingsRepository(context)
    
    /**
     * Connect to SSH server.
     */
    suspend fun connect(config: SSHConfig): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            // Disconnect existing session first
            session?.disconnect()
            
            DebugLog.log("SSH: Connecting to ${config.host}:${config.port}")
            
            // Configure session
            session = jsch.getSession(config.user, config.host, config.port).apply {
                // Set password or key
                if (config.privateKeyPath != null) {
                    jsch.addIdentity(config.privateKeyPath)
                } else {
                    setPassword(config.password)
                }
                
                // Disable strict host key checking (for localhost)
                val props = Properties()
                props["StrictHostKeyChecking"] = "no"
                setConfig(props)
            }
            session?.let { configureSession(it) }
            
            session?.connect()
            _isConnected.value = true
            _output.value = "Connected to ${config.host}:${config.port}\n"
            refreshInstalledToolsNow()
            DebugLog.log("SSH: Connected successfully")
            
            Result.success(Unit)
        } catch (e: Exception) {
            DebugLog.log("SSH: Connection failed - ${e.message}")
            _isConnected.value = false
            _output.value = "Connection failed: ${e.message}\n"
            Result.failure(e)
        }
    }
    
    /**
     * Execute a command silently and return output (doesn't update global StateFlow).
     */
    suspend fun executeCommand(command: String): Result<String> = withContext(Dispatchers.IO) {
        suspend fun runExec(targetSession: Session): Result<String> {
            return try {
                val channel = targetSession.openChannel("exec") as ChannelExec
                channel.setCommand(command)

                val outputStream = ByteArrayOutputStream()
                channel.inputStream = null
                channel.setOutputStream(outputStream)
                channel.setErrStream(outputStream)

                channel.connect(10000)

                while (!channel.isClosed) {
                    Thread.sleep(50)
                }

                val output = outputStream.toString()
                val exitStatus = channel.exitStatus
                channel.disconnect()

                if (exitStatus == 0) {
                    Result.success(output)
                } else {
                    Result.failure(Exception(output.ifBlank { "Command exited with code $exitStatus" }))
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

        val currentSession = session
        if (currentSession == null || !currentSession.isConnected) {
            return@withContext Result.failure(Exception("Not connected"))
        }

        val firstAttempt = runExec(currentSession)
        val firstError = firstAttempt.exceptionOrNull()
        if (firstAttempt.isSuccess || !isRecoverableSessionFailure(firstError)) {
            return@withContext firstAttempt
        }

        try {
            session?.disconnect()
        } catch (_: Exception) {}
        _isConnected.value = false

        val reconnect = connect(_config.value)
        if (reconnect.isFailure) {
            return@withContext Result.failure(firstError ?: Exception("SSH command failed"))
        }

        val retriedSession = session ?: return@withContext Result.failure(firstError ?: Exception("SSH reconnect failed"))
        runExec(retriedSession)
    }

    suspend fun refreshInstalledToolsNow(): Set<String> = withContext(Dispatchers.IO) {
        if (!checkConnection()) {
            setInstalledTools(emptySet())
            return@withContext emptySet()
        }

        val detected = mutableSetOf<String>()
        TermuxTools.allTools.forEach { tool ->
            val output = executeQuiet(tool.installCheckCommand)?.trim()
            if (output == "installed") {
                detected.add(tool.id)
            }
        }
        setInstalledTools(detected)
        detected
    }

    suspend fun uploadToRemotePath(
        localFile: File,
        remotePath: String,
        onProgress: ((Long, Long) -> Unit)? = null
    ): Result<Unit> = withContext(Dispatchers.IO) {
        if (!localFile.exists()) {
            return@withContext Result.failure(Exception("Local upload staging file is missing"))
        }
        if (!localFile.canRead()) {
            return@withContext Result.failure(Exception("Local upload staging file is not readable"))
        }

        val parentPath = remotePath.substringBeforeLast("/", missingDelimiterValue = "/").ifBlank { "/" }
        val tempRemotePath = "$remotePath.upload.${System.currentTimeMillis()}.tmp"
        val mainHandler = Handler(Looper.getMainLooper())
        val totalBytes = localFile.length().coerceAtLeast(0L)
        val uploadConfig = _config.value

        fun postProgress(sent: Long, total: Long = totalBytes) {
            onProgress ?: return
            mainHandler.post {
                onProgress.invoke(sent.coerceAtLeast(0L), total.coerceAtLeast(0L))
            }
        }

        fun runExecUploadAttempt(): Result<Unit> {
            var uploadSession: Session? = null
            var channel: ChannelExec? = null
            return try {
                uploadSession = createConfiguredSession(uploadConfig)
                uploadSession.connect(30_000)
                channel = uploadSession.openChannel("exec") as ChannelExec
                val uploadScript = """
                    set -e
                    mkdir -p ${shellQuote(parentPath)}
                    rm -f ${shellQuote(tempRemotePath)}
                    cat > ${shellQuote(tempRemotePath)}
                    mv -f ${shellQuote(tempRemotePath)} ${shellQuote(remotePath)}
                """.trimIndent()
                channel.setCommand("bash -lc ${shellQuote(uploadScript)}")

                val remoteStdin = channel.outputStream.buffered()
                val remoteStdout = channel.inputStream
                val remoteStderr = channel.extInputStream
                channel.connect(30_000)

                postProgress(0L, totalBytes)
                localFile.inputStream().buffered().use { input ->
                    val buffer = ByteArray(256 * 1024)
                    var sentBytes = 0L
                    var reportedBytes = -1L
                    var reportedAtMs = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        if (read == 0) continue
                        remoteStdin.write(buffer, 0, read)
                        sentBytes += read
                        val now = System.currentTimeMillis()
                        val shouldReport = sentBytes == totalBytes ||
                            sentBytes - reportedBytes >= 256 * 1024 ||
                            now - reportedAtMs >= 200
                        if (shouldReport) {
                            reportedBytes = sentBytes
                            reportedAtMs = now
                            postProgress(sentBytes, totalBytes)
                        }
                    }
                }
                remoteStdin.flush()
                remoteStdin.close()

                val stdout = ByteArrayOutputStream()
                val stderr = ByteArrayOutputStream()
                val readBuffer = ByteArray(8 * 1024)
                while (true) {
                    while (remoteStdout.available() > 0) {
                        val read = remoteStdout.read(readBuffer, 0, readBuffer.size)
                        if (read < 0) break
                        stdout.write(readBuffer, 0, read)
                    }
                    while (remoteStderr.available() > 0) {
                        val read = remoteStderr.read(readBuffer, 0, readBuffer.size)
                        if (read < 0) break
                        stderr.write(readBuffer, 0, read)
                    }
                    if (channel.isClosed) {
                        if (remoteStdout.available() > 0 || remoteStderr.available() > 0) continue
                        break
                    }
                    Thread.sleep(100)
                }

                val exitStatus = channel.exitStatus
                if (exitStatus == 0) {
                    postProgress(totalBytes, totalBytes)
                    Result.success(Unit)
                } else {
                    val output = listOf(stdout.toString(), stderr.toString())
                        .joinToString("\n")
                        .trim()
                    Result.failure(Exception(output.ifBlank { "SSH upload exited with code $exitStatus" }))
                }
            } catch (e: Exception) {
                Result.failure(e)
            } finally {
                try {
                    channel?.disconnect()
                } catch (_: Exception) {}
                try {
                    uploadSession?.disconnect()
                } catch (_: Exception) {}
            }
        }

        if (uploadConfig.password.isBlank() && uploadConfig.privateKeyPath == null) {
            return@withContext Result.failure(Exception("Missing SSH credentials"))
        }

        val firstAttempt = runExecUploadAttempt()
        val firstError = firstAttempt.exceptionOrNull()
        if (firstAttempt.isSuccess || !isRecoverableSessionFailure(firstError)) {
            return@withContext firstAttempt
        }

        val retryResult = runExecUploadAttempt()
        if (retryResult.isSuccess) {
            retryResult
        } else {
            Result.failure(retryResult.exceptionOrNull() ?: firstError ?: Exception("SSH upload failed"))
        }
    }
    
    /**
     * Execute a command quietly and return output string (or null on error).
     * Use for background polling without any UI feedback.
     */
    suspend fun executeQuiet(command: String): String? {
        return executeCommand(command).getOrNull()
    }

    suspend fun detectLanAddress(): String? {
        val detectionCommands = listOf(
            "hostname -I 2>/dev/null || true",
            "ip route get 1.1.1.1 2>/dev/null | sed -n 's/.* src \\([0-9.]*\\).*/\\1/p'",
            "ip -o -4 addr show up scope global 2>/dev/null || true",
            """python3 -c 'import socket; s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM); s.connect(("8.8.8.8", 80)); print(s.getsockname()[0]); s.close()' 2>/dev/null || true""",
            "ifconfig 2>/dev/null || true",
            "getprop dhcp.wlan0.ipaddress 2>/dev/null || true",
            "/system/bin/getprop dhcp.wlan0.ipaddress 2>/dev/null || true"
        )

        detectionCommands.forEach { command ->
            val output = executeQuiet(command).orEmpty()
            lanAddressRegex.findAll(output)
                .map { it.value }
                .firstOrNull { ip ->
                    !ip.startsWith("127.") && ip != "0.0.0.0"
                }
                ?.let { return it }
        }

        return null
    }
    
    /**
     * Execute a command and stream output to global StateFlow.
     */
    suspend fun executeCommandStreaming(command: String): Result<Int> = withContext(Dispatchers.IO) {
        // Check connection first
        if (!checkConnection()) {
            appendOutput("\n[Error] Not connected to SSH server\n")
            return@withContext Result.failure(Exception("Not connected"))
        }
        
        val currentSession = session!!
        
        try {
            DebugLog.log("SSH: Executing: $command")
            _isExecuting.value = true
            _currentCommand.value = command
            appendOutput("\n\$ $command\n")
            
            val channel = currentSession.openChannel("exec") as ChannelExec
            channel.setCommand(command)
            
            val inputStream = channel.inputStream
            val errorStream = channel.extInputStream
            channel.connect(30000)
            
            val buffer = ByteArray(1024)
            
            while (true) {
                // Read stdout
                while (inputStream.available() > 0) {
                    val read = inputStream.read(buffer, 0, 1024)
                    if (read < 0) break
                    val text = String(buffer, 0, read)
                    appendOutput(text)
                }
                
                // Read stderr
                while (errorStream.available() > 0) {
                    val read = errorStream.read(buffer, 0, 1024)
                    if (read < 0) break
                    val text = String(buffer, 0, read)
                    appendOutput("[stderr] $text")
                }
                
                if (channel.isClosed) {
                    if (inputStream.available() > 0 || errorStream.available() > 0) continue
                    break
                }
                Thread.sleep(100)
            }
            
            val exitStatus = channel.exitStatus
            channel.disconnect()
            
            appendOutput("\n[Exit code: $exitStatus]\n")
            _isExecuting.value = false
            _currentCommand.value = ""
            
            DebugLog.log("SSH: Command completed with exit code $exitStatus")
            if (exitStatus == 0) {
                Result.success(exitStatus)
            } else {
                Result.failure(Exception("Command exited with code $exitStatus"))
            }
        } catch (e: Exception) {
            DebugLog.log("SSH: Command failed - ${e.message}")
            appendOutput("\n[Error] ${e.message}\n")
            _isExecuting.value = false
            _currentCommand.value = ""
            
            // Check if connection was lost
            checkConnection()
            
            Result.failure(e)
        }
    }
    
    /**
     * Execute a command and stream output to per-tool StateFlow.
     * Each tool gets its own output channel.
     */
    suspend fun executeCommandForTool(
        toolId: String,
        command: String,
        followLogFile: String? = null,
        clearExistingOutput: Boolean = false
    ): Result<Int> = withContext(Dispatchers.IO) {
        if (!checkConnection()) {
            appendToolOutput(toolId, "\n[Error] Not connected to SSH server\n")
            return@withContext Result.failure(Exception("Not connected"))
        }
        
        val currentSession = session!!
        
        try {
            stopToolLogTail(toolId)
            if (clearExistingOutput) {
                clearToolOutput(toolId)
            }
            DebugLog.log("SSH[$toolId]: Executing: $command")
            setToolExecuting(toolId, true)
            appendToolOutput(toolId, "\n\$ $command\n")
            
            val channel = currentSession.openChannel("exec") as ChannelExec
            channel.setCommand(command)
            
            val inputStream = channel.inputStream
            val errorStream = channel.extInputStream
            channel.connect(30000)
            
            val buffer = ByteArray(1024)
            
            while (true) {
                while (inputStream.available() > 0) {
                    val read = inputStream.read(buffer, 0, 1024)
                    if (read < 0) break
                    val text = String(buffer, 0, read)
                    appendToolOutput(toolId, text)
                }
                
                while (errorStream.available() > 0) {
                    val read = errorStream.read(buffer, 0, 1024)
                    if (read < 0) break
                    val text = String(buffer, 0, read)
                    appendToolOutput(toolId, "[stderr] $text")
                }
                
                if (channel.isClosed) {
                    if (inputStream.available() > 0 || errorStream.available() > 0) continue
                    break
                }
                Thread.sleep(100)
            }
            
            val exitStatus = channel.exitStatus
            channel.disconnect()
            
            appendToolOutput(toolId, "\n[Exit code: $exitStatus]\n")
            setToolExecuting(toolId, false)

            if (exitStatus == 0 && !followLogFile.isNullOrBlank()) {
                startToolLogTail(toolId, followLogFile)
            }
            
            DebugLog.log("SSH[$toolId]: Command completed with exit code $exitStatus")
            if (exitStatus == 0) {
                Result.success(exitStatus)
            } else {
                Result.failure(Exception("Command exited with code $exitStatus"))
            }
        } catch (e: Exception) {
            DebugLog.log("SSH[$toolId]: Command failed - ${e.message}")
            appendToolOutput(toolId, "\n[Error] ${e.message}\n")
            setToolExecuting(toolId, false)
            checkConnection()
            Result.failure(e)
        }
    }

    private fun startToolLogTail(toolId: String, logFile: String) {
        stopToolLogTail(toolId)
        val tailConfig = _config.value

        val job = persistentScope.launch {
            var tailSession: Session? = null
            var channel: ChannelExec? = null
            try {
                appendToolOutput(toolId, "\n[Following log: $logFile]\n")
                val currentTailSession = createConfiguredSession(tailConfig)
                tailSession = currentTailSession
                currentTailSession.connect(30_000)
                toolLogTailSessions[toolId] = currentTailSession

                channel = currentTailSession.openChannel("exec") as ChannelExec
                channel.setCommand("bash -lc 'touch \"$logFile\" 2>/dev/null || true; tail -n +1 -F \"$logFile\"'")
                val inputStream = channel.inputStream
                val errorStream = channel.extInputStream
                channel.connect(10000)

                val buffer = ByteArray(1024)
                while (true) {
                    while (inputStream.available() > 0) {
                        val read = inputStream.read(buffer, 0, buffer.size)
                        if (read < 0) break
                        appendToolOutput(toolId, String(buffer, 0, read))
                    }

                    while (errorStream.available() > 0) {
                        val read = errorStream.read(buffer, 0, buffer.size)
                        if (read < 0) break
                        appendToolOutput(toolId, "[stderr] ${String(buffer, 0, read)}")
                    }

                    if (channel.isClosed) {
                        if (inputStream.available() > 0 || errorStream.available() > 0) continue
                        break
                    }
                    Thread.sleep(200)
                }
            } catch (e: Exception) {
                appendToolOutput(toolId, "\n[Log follow failed] ${e.message ?: "Unknown error"}\n")
            } finally {
                try {
                    channel?.disconnect()
                } catch (_: Exception) {}
                try {
                    tailSession?.disconnect()
                } catch (_: Exception) {}
                toolLogTailSessions.remove(toolId)
                if (toolLogTailJobs[toolId] == this.coroutineContext[Job]) {
                    toolLogTailJobs.remove(toolId)
                }
            }
        }
        toolLogTailJobs[toolId] = job
    }

    private fun createConfiguredSession(config: SSHConfig): Session {
        return jsch.getSession(config.user, config.host, config.port).apply {
            if (config.privateKeyPath != null) {
                jsch.addIdentity(config.privateKeyPath)
            } else {
                setPassword(config.password)
            }
            val props = Properties()
            props["StrictHostKeyChecking"] = "no"
            setConfig(props)
            configureSession(this)
        }
    }

    private fun shellQuote(value: String): String = "'${value.replace("'", "'\"'\"'")}'"
    
    /**
     * Execute multiple commands sequentially. Shows confirmation for each.
     */
    suspend fun executeCommandsSequentially(
        commands: List<String>,
        onProgress: (Int, Int, String) -> Unit
    ): Result<Unit> = withContext(Dispatchers.IO) {
        for ((index, command) in commands.withIndex()) {
            onProgress(index + 1, commands.size, command)
            val result = executeCommandStreaming(command)
            if (result.isFailure) {
                return@withContext Result.failure(result.exceptionOrNull() ?: Exception("Command failed"))
            }
        }
        Result.success(Unit)
    }
    
    /**
     * Launch sequential commands in PERSISTENT scope (survives navigation).
     * Use this for Run All installations that should continue in background.
     */
    fun launchSequentialCommandsPersistent(commands: List<String>, toolName: String) {
        persistentScope.launch {
            appendOutput("\n=== Installing $toolName (${commands.size} steps) ===\n")
            appendOutput("⚠️ This will continue even if you navigate away\n\n")

            var failedStep: Int? = null
            for ((index, command) in commands.withIndex()) {
                appendOutput("[Step ${index + 1}/${commands.size}]\n")
                _currentCommand.value = command
                
                val result = executeCommandStreaming(command)
                if (result.isFailure) {
                    appendOutput("\n❌ Installation failed at step ${index + 1}\n")
                    failedStep = index + 1
                    break
                }
            }

            if (failedStep == null) {
                appendOutput("\n=== $toolName installation complete ===\n")
            } else {
                appendOutput("\n=== $toolName installation stopped after step $failedStep ===\n")
            }
            refreshInstalledToolsNow()
        }
    }
    
    /**
     * Disconnect from SSH server.
     */
    fun disconnect() {
        stopAllToolLogTails()
        session?.disconnect()
        session = null
        _isConnected.value = false
        _isExecuting.value = false
        _currentCommand.value = ""
        _runningTools.value = emptySet()
        _installedTools.value = emptySet()
        appendOutput("\n[Disconnected]\n")
        DebugLog.log("SSH: Disconnected")
    }
}
