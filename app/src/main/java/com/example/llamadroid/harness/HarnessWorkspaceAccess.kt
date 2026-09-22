package com.example.llamadroid.harness

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import com.example.llamadroid.data.db.HarnessWorkspaceEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Per-operation SSH connections capture their configuration before any suspending work. */
class HarnessWorkspaceAccess(context: Context, private val credentials: HarnessCredentialStore) {
    private val receipts = context.getSharedPreferences("harness_ssh_cleanup", Context.MODE_PRIVATE)
    private val liveConnections = ConcurrentHashMap<String, Session>()
    private val activeProcessOwners = ConcurrentHashMap.newKeySet<String>()

    suspend fun prepareSshWorkspace(workspace: HarnessWorkspaceEntity) = withContext(Dispatchers.IO) {
        require(workspace.backend == "REMOTE_SSH")
        val configuration = credentials.readRecord("adt-ssh/${workspace.id}")?.getJSONObject("record")?.getJSONObject("payload")
            ?: error("SSH_CONFIGURATION_REQUIRED")
        require(workspace.projectFolder.isNotBlank() && '/' !in workspace.projectFolder && workspace.projectFolder !in setOf(".", ".."))
        val connectionId = UUID.randomUUID().toString()
        val session = connect(configuration, connectionId)
        val channel = session.openChannel("sftp") as ChannelSftp
        try {
            channel.connect(10_000)
            for (path in listOf("/workspace", "/workspace/${workspace.projectFolder}")) {
                try { require(channel.stat(path).isDir) { "WORKSPACE_PATH_NOT_DIRECTORY" } }
                catch (missing: com.jcraft.jsch.SftpException) {
                    if (missing.id != ChannelSftp.SSH_FX_NO_SUCH_FILE) throw missing
                    channel.mkdir(path)
                }
            }
        } finally { channel.disconnect(); session.disconnect(); liveConnections.remove(connectionId, session) }
    }

    suspend fun readBytes(scope: HarnessWorkspaceScope, path: String, maxBytes: Int = MAX_FILE_BYTES): ByteArray = withContext(Dispatchers.IO) {
        if (scope.localRoot != null) {
            val file = scope.localFile(path)
            require(file.length() <= maxBytes) { "FILE_TOO_LARGE" }
            return@withContext file.inputStream().use { it.readBounded(maxBytes) }
        }
        withSftp(scope) { channel ->
            val target = remotePath(scope, path, channel)
            require(channel.stat(target).size <= maxBytes) { "FILE_TOO_LARGE" }
            channel.get(target).use { it.readBounded(maxBytes) }
        }
    }

    suspend fun writeBytes(scope: HarnessWorkspaceScope, path: String, bytes: ByteArray) = withContext(Dispatchers.IO) {
        require(bytes.size <= MAX_FILE_BYTES) { "FILE_TOO_LARGE" }
        if (scope.localRoot != null) {
            val target = scope.localFile(path)
            target.parentFile?.mkdirs()
            target.outputStream().use { it.write(bytes) }
        } else withSftp(scope) { channel ->
            val target = remotePath(scope, path, channel, allowMissing = true)
            try { require(!channel.lstat(target).isLink) { "WORKSPACE_WRITE_SYMLINK_REJECTED" } }
            catch (missing: com.jcraft.jsch.SftpException) {
                if (missing.id != ChannelSftp.SSH_FX_NO_SUCH_FILE) throw missing
            }
            channel.put(bytes.inputStream(), target)
        }
    }

    /** Only archive deletion calls this after proving no other thread references the project. */
    internal suspend fun deleteArchivedProject(scope: HarnessWorkspaceScope) = withContext(Dispatchers.IO) {
        require(scope.conversation.runtimeSource == com.example.llamadroid.data.db.AgentRuntimeSource.LEGACY_ARCHIVE)
        require(scope.localRoot == null)
        withSftp(scope) { channel ->
            val root = remoteRoot(scope)
            fun remove(path: String, depth: Int) {
                require(depth < 128) { "PROJECT_CLEANUP_PENDING" }
                val attrs = try { channel.lstat(path) } catch (missing: com.jcraft.jsch.SftpException) {
                    if (missing.id == ChannelSftp.SSH_FX_NO_SUCH_FILE) return else throw missing
                }
                if (attrs.isDir && !attrs.isLink) {
                    channel.ls(path).filterIsInstance<ChannelSftp.LsEntry>().forEach { child ->
                        if (child.filename !in setOf(".", "..")) {
                            require('/' !in child.filename && '\u0000' !in child.filename)
                            remove("$path/${child.filename}", depth + 1)
                        }
                    }
                    channel.rmdir(path)
                } else channel.rm(path)
            }
            try {
                require(!channel.lstat(root).isLink && channel.realpath(root).trimEnd('/') == root) { "WORKSPACE_PATH_OUTSIDE_SCOPE" }
                remove(root, 0)
            } catch (missing: com.jcraft.jsch.SftpException) {
                if (missing.id != ChannelSftp.SSH_FX_NO_SUCH_FILE) throw missing
            }
        }
    }

    suspend fun invoke(scope: HarnessWorkspaceScope, method: String, args: JSONObject): Any = withContext(Dispatchers.IO) {
        val path = args.optString("path", ".")
        when (method) {
            "workspace.read" -> JSONObject().put("content", String(readBytes(scope, path, 1024 * 1024), Charsets.UTF_8))
            "workspace.write" -> { writeBytes(scope, path, args.getString("content").toByteArray(Charsets.UTF_8)); JSONObject().put("written", true) }
            "workspace.execute" -> execute(scope as? HarnessSessionScope ?: error("HARNESS_SESSION_REQUIRED"), args.getString("command"), args.optString("cwd", "."), args.optLong("timeoutMs", 120_000))
            else -> if (scope.localRoot != null) localOperation(scope, method, args) else withSftp(scope) { channel ->
                val target = if (method in setOf("workspace.stat", "workspace.mkdir", "workspace.rename", "workspace.delete")) remoteEntry(scope, path, channel)
                    else remotePath(scope, path, channel)
                when (method) {
                    "workspace.list" -> JSONArray().apply {
                        val entries = channel.ls(target).filterIsInstance<ChannelSftp.LsEntry>().filter { it.filename !in setOf(".", "..") }
                        require(entries.size <= 10_000) { "DIRECTORY_TOO_LARGE" }
                        entries.forEach { entry -> put(JSONObject().put("name", entry.filename)
                                .put("directory", entry.attrs.isDir && !entry.attrs.isLink).put("size", entry.attrs.size).put("symbolicLink", entry.attrs.isLink)) }
                    }
                    "workspace.stat" -> channel.lstat(target).let { JSONObject().put("directory", it.isDir && !it.isLink).put("size", it.size).put("symbolicLink", it.isLink) }
                    "workspace.mkdir" -> { channel.mkdir(target); JSONObject().put("created", true) }
                    "workspace.rename" -> {
                        require(target != channel.realpath(remoteRoot(scope))) { "WORKSPACE_ROOT_PROTECTED" }
                        channel.rename(target, remoteEntry(scope, args.getString("destination"), channel))
                        JSONObject().put("renamed", true)
                    }
                    "workspace.delete" -> {
                        require(target != channel.realpath(remoteRoot(scope))) { "WORKSPACE_ROOT_PROTECTED" }
                        if (channel.lstat(target).isDir) channel.rmdir(target) else channel.rm(target)
                        JSONObject().put("deleted", true)
                    }
                    else -> error("BRIDGE_METHOD_UNKNOWN")
                }
            }
        }
    }

    private fun localOperation(scope: HarnessWorkspaceScope, method: String, args: JSONObject): Any {
        val target = if (method in setOf("workspace.stat", "workspace.delete", "workspace.rename")) scope.localEntry(args.optString("path", "."))
            else scope.localFile(args.optString("path", "."))
        return when (method) {
            "workspace.list" -> JSONArray().apply {
                require(target.isDirectory) { "WORKSPACE_DIRECTORY_MISSING" }
                val entries = target.listFiles().orEmpty()
                require(entries.size <= 10_000) { "DIRECTORY_TOO_LARGE" }
                entries.sortedBy { it.name }.forEach {
                    val link = java.nio.file.Files.isSymbolicLink(it.toPath())
                    put(JSONObject().put("name", it.name).put("directory", it.isDirectory && !link).put("size", it.length()).put("symbolicLink", link))
                }
            }
            "workspace.stat" -> JSONObject().put("exists", target.exists()).put("directory", target.isDirectory && !java.nio.file.Files.isSymbolicLink(target.toPath()))
                .put("size", target.length()).put("symbolicLink", java.nio.file.Files.isSymbolicLink(target.toPath()))
            "workspace.mkdir" -> JSONObject().put("created", target.isDirectory || target.mkdirs())
            "workspace.rename" -> {
                require(target != scope.localRoot?.canonicalFile) { "WORKSPACE_ROOT_PROTECTED" }
                check(target.renameTo(scope.localEntry(args.getString("destination"))))
                JSONObject().put("renamed", true)
            }
            "workspace.delete" -> {
                require(target != scope.localRoot?.canonicalFile) { "WORKSPACE_ROOT_PROTECTED" }
                // Match a single filesystem operation; recursive removal belongs to the approved shell tool.
                check(target.delete())
                JSONObject().put("deleted", true)
            }
            else -> error("BRIDGE_METHOD_UNKNOWN")
        }
    }

    suspend fun execute(scope: HarnessSessionScope, command: String, cwd: String = ".", timeoutMs: Long = 120_000): JSONObject = withContext(Dispatchers.IO) {
        require(scope.workspace.backend == "REMOTE_SSH") { "LOCAL_EXECUTION_BELONGS_TO_HARNESS" }
        val configuration = configuration(scope)
        val owner = UUID.randomUUID().toString()
        activeProcessOwners.add(owner)
        try {
            val key = "adt-ssh-cleanup/$owner"
            credentials.replaceRecord(key, null, JSONObject().put("kind", "grant").put("payload", configuration))
            commitReceipt { putString(owner, scope.session.harnessSessionId) }
            val session = connect(configuration, owner)
            val channel = session.openChannel("exec") as ChannelExec
            val output = BoundedOutput(MAX_OUTPUT_BYTES)
            val relative = relativePath(scope, cwd)
            val directory = remoteRoot(scope) + if (relative.isBlank() || relative == ".") "" else "/$relative"
            val wrapped = "cd ${quote(directory)} && ADT_HARNESS_REMOTE_OWNER=${quote(owner)} sh -c ${quote(command)}"
            try {
                channel.setCommand(wrapped)
                channel.setOutputStream(output)
                channel.setErrStream(output)
                channel.connect(10_000)
                withTimeout(timeoutMs.coerceIn(1000, 600_000)) {
                    while (!channel.isClosed) { currentCoroutineContext().ensureActive(); delay(50) }
                }
                JSONObject().put("exitCode", channel.exitStatus).put("output", output.text()).put("truncated", output.truncated)
            } finally {
                // A detached remote child can outlive a completed shell; retain the receipt until a scan proves cleanup.
                withContext(NonCancellable) { runCatching { cleanup(owner, session) } }
                channel.disconnect()
                session.disconnect()
                liveConnections.remove(owner)
            }
        } finally { activeProcessOwners.remove(owner) }
    }

    suspend fun retryCleanup(includeActive: Boolean = false, sessionId: String? = null): List<String> = withContext(Dispatchers.IO) {
        receipts.all.filterValues { sessionId == null || it == sessionId }.keys.forEach { owner ->
            currentCoroutineContext().ensureActive()
            if (!includeActive && owner in activeProcessOwners) return@forEach
            try {
                val config = credentials.readRecord("adt-ssh-cleanup/$owner")?.getJSONObject("record")?.getJSONObject("payload")
                    ?: error("SSH_CLEANUP_CONFIGURATION_MISSING")
                val session = liveConnections[owner]?.takeIf { it.isConnected } ?: connect(config)
                try { cleanup(owner, session) } finally { if (liveConnections[owner] !== session) session.disconnect() }
            } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { /* Keep its receipt for retry. */ }
        }
        pendingCleanupSessions(includeActive).filter { sessionId == null || it == sessionId }
    }

    fun pendingCleanupSessions(includeActive: Boolean = false): List<String> = receipts.all
        .filterKeys { includeActive || it !in activeProcessOwners }.values.filterIsInstance<String>().distinct()

    /** Independent of the guest API; disconnect blocked transfers as well as shell channels. */
    fun cancelOwnedRequests() {
        liveConnections.entries.toList().forEach { (id, session) -> session.disconnect(); liveConnections.remove(id, session) }
    }

    /** Owned connection used by the official Harness SSH subprocess/PTY adapter. */
    internal fun openOwnedProcess(scope: HarnessSessionScope, owner: String, cwd: String): Pair<Session, String> {
        require(scope.workspace.backend == "REMOTE_SSH")
        activeProcessOwners.add(owner)
        try {
            val configuration = configuration(scope)
            credentials.replaceRecord("adt-ssh-cleanup/$owner", null, JSONObject().put("kind", "grant").put("payload", configuration))
            commitReceipt { putString(owner, scope.session.harnessSessionId) }
            val session = connect(configuration, owner)
            val channel = session.openChannel("sftp") as ChannelSftp
            try {
                channel.connect(10_000)
                val directory = remotePath(scope, cwd, channel)
                require(channel.stat(directory).isDir) { "WORKSPACE_DIRECTORY_MISSING" }
                return session to directory
            } catch (failure: Throwable) {
                session.disconnect(); liveConnections.remove(owner, session); throw failure
            } finally { channel.disconnect() }
        } catch (failure: Throwable) { activeProcessOwners.remove(owner); throw failure }
    }

    internal suspend fun closeOwnedProcess(owner: String, session: Session) {
        try { cleanup(owner, session) }
        finally { session.disconnect(); liveConnections.remove(owner, session); activeProcessOwners.remove(owner) }
    }

    suspend fun readByteRange(scope: HarnessWorkspaceScope, path: String, offset: Long, length: Int): Pair<ByteArray, Boolean> = withContext(Dispatchers.IO) {
        require(offset >= 0 && length in 1..512 * 1024)
        fun read(input: InputStream): Pair<ByteArray, Boolean> = input.use {
            var remaining = offset
            while (remaining > 0) {
                val skipped = it.skip(remaining)
                if (skipped == 0L) { if (it.read() < 0) return@use ByteArray(0) to true; remaining-- }
                else remaining -= skipped
            }
            val result = ByteArrayOutputStream(length)
            val buffer = ByteArray(minOf(length, 16 * 1024))
            while (result.size() < length) {
                val count = it.read(buffer, 0, minOf(buffer.size, length - result.size()))
                if (count < 0) return@use result.toByteArray() to true
                result.write(buffer, 0, count)
            }
            result.toByteArray() to (it.read() < 0)
        }
        if (scope.localRoot != null) read(scope.localFile(path).inputStream())
        else withSftp(scope) { read(it.get(remotePath(scope, path, it))) }
    }

    private suspend fun cleanup(owner: String, session: Session) {
        val script = """
import os,signal,time
marker=b'ADT_HARNESS_REMOTE_OWNER='+${quotePython(owner)}.encode()
def owned():
 result=[]
 for name in os.listdir('/proc'):
  if not name.isdigit(): continue
  try:
   if marker not in open('/proc/'+name+'/environ','rb').read().split(b'\0'): continue
   stat=open('/proc/'+name+'/stat').read().rsplit(')',1)[1].split()
   if stat[0]!='Z': result.append((int(name),stat[19]))
  except (OSError,IndexError): pass
 return result
for sig in (signal.SIGTERM,signal.SIGKILL):
 for pid,ticks in owned():
  try:
   if open('/proc/'+str(pid)+'/stat').read().rsplit(')',1)[1].split()[19]==ticks: os.kill(pid,sig)
  except (OSError,IndexError): pass
 time.sleep(.2)
print('ADT_CLEAN' if not owned() else 'ADT_PENDING')
""".trimIndent()
        val channel = session.openChannel("exec") as ChannelExec
        try {
            channel.setCommand("python3 -c ${quote(script)}")
            val input = channel.inputStream
            channel.connect(5000)
            withTimeout(5000) { while (!channel.isClosed) delay(50) }
            val result = String(input.readBounded(256), Charsets.UTF_8)
            check(channel.exitStatus == 0 && result.trim() == "ADT_CLEAN") { "SSH_CLEANUP_PENDING" }
            commitReceipt { remove(owner) }
            credentials.replaceRecord("adt-ssh-cleanup/$owner", credentials.readRecord("adt-ssh-cleanup/$owner")?.getString("revision"), null)
        } finally { channel.disconnect() }
    }

    private fun configuration(scope: HarnessWorkspaceScope): JSONObject = credentials.readRecord("adt-ssh/${scope.workspace.id}")
        ?.getJSONObject("record")?.getJSONObject("payload") ?: error("SSH_CONFIGURATION_REQUIRED")

    /**
     * Cleanup receipts must be durable before an SSH operation proceeds or is
     * considered cleaned. KTX `edit` discards commit's Boolean result, so keep
     * the checked synchronous write under this narrow suppression.
     */
    @SuppressLint("UseKtx")
    private fun commitReceipt(action: SharedPreferences.Editor.() -> Unit) {
        val editor = receipts.edit()
        action(editor)
        check(editor.commit())
    }

    private fun connect(configuration: JSONObject, connectionId: String? = null): Session {
        val jsch = JSch()
        configuration.optString("privateKeyPath").takeIf { it.isNotBlank() }?.let { jsch.addIdentity(it) }
        val session = jsch.getSession(configuration.getString("username"), configuration.getString("host"), configuration.getInt("port")).apply {
            setPassword(configuration.optString("password"))
            setConfig("StrictHostKeyChecking", "no") // Preserve the existing app SSH connection policy.
            serverAliveInterval = 15_000
            serverAliveCountMax = 2
            timeout = 15_000
        }
        connectionId?.let { liveConnections[it] = session }
        return try { session.connect(15_000); session } catch (failure: Exception) {
            session.disconnect(); connectionId?.let { liveConnections.remove(it, session) }; throw failure
        }
    }

    private inline fun <T> withSftp(scope: HarnessWorkspaceScope, block: (ChannelSftp) -> T): T {
        val connectionId = UUID.randomUUID().toString()
        val session = connect(configuration(scope), connectionId)
        val channel = session.openChannel("sftp") as ChannelSftp
        try { channel.connect(10_000); return block(channel) }
        finally { channel.disconnect(); session.disconnect(); liveConnections.remove(connectionId, session) }
    }

    private fun remoteRoot(scope: HarnessWorkspaceScope): String {
        val folder = scope.workspace.projectFolder
        require(folder.isNotBlank() && '/' !in folder && '\u0000' !in folder && folder !in setOf(".", "..")) {
            "WORKSPACE_PATH_OUTSIDE_SCOPE"
        }
        return "/workspace/$folder"
    }
    private fun relativePath(scope: HarnessWorkspaceScope, path: String): String {
        val relative = when {
            path == scope.workspace.guestPath -> "."
            path.startsWith(scope.workspace.guestPath + "/") -> path.removePrefix(scope.workspace.guestPath + "/")
            else -> path
        }
        require(!relative.startsWith('/') && '\u0000' !in relative && relative.split('/').none { it == ".." }) { "WORKSPACE_PATH_OUTSIDE_SCOPE" }
        return relative
    }

    private fun remotePath(scope: HarnessWorkspaceScope, path: String, channel: ChannelSftp, allowMissing: Boolean = false): String {
        val root = channel.realpath(remoteRoot(scope)).trimEnd('/')
        val relative = relativePath(scope, path)
        val target = "$root/$relative"
        val canonical = if (allowMissing) {
            channel.realpath(target.substringBeforeLast('/')) + "/" + target.substringAfterLast('/')
        } else channel.realpath(target)
        require(canonical == root || canonical.startsWith("$root/")) { "WORKSPACE_PATH_OUTSIDE_SCOPE" }
        return canonical
    }

    private fun remoteEntry(scope: HarnessWorkspaceScope, path: String, channel: ChannelSftp): String {
        val root = channel.realpath(remoteRoot(scope)).trimEnd('/')
        val relative = relativePath(scope, path).trimEnd('/')
        if (relative.isBlank() || relative == ".") return root
        val parent = channel.realpath(root + "/" + relative.substringBeforeLast('/', "."))
        require(parent == root || parent.startsWith("$root/")) { "WORKSPACE_PATH_OUTSIDE_SCOPE" }
        return parent.trimEnd('/') + "/" + relative.substringAfterLast('/')
    }

    private class BoundedOutput(private val maximum: Int) : java.io.OutputStream() {
        private val bytes = ByteArrayOutputStream()
        var truncated = false; private set
        @Synchronized override fun write(value: Int) { if (bytes.size() < maximum) bytes.write(value) else truncated = true }
        @Synchronized override fun write(value: ByteArray, offset: Int, length: Int) {
            val count = length.coerceAtMost(maximum - bytes.size())
            bytes.write(value, offset, count)
            if (count < length) truncated = true
        }
        @Synchronized fun text(): String = bytes.toString(Charsets.UTF_8.name())
    }

    companion object {
        internal fun InputStream.readBounded(limit: Int): ByteArray {
            require(limit >= 0)
            val output = ByteArrayOutputStream(minOf(limit, 16 * 1024))
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val count = read(buffer)
                if (count < 0) break
                require(count <= limit - output.size()) { "FILE_TOO_LARGE" }
                output.write(buffer, 0, count)
            }
            return output.toByteArray()
        }
        fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
        private fun quotePython(value: String): String = JSONObject.quote(value)
        const val MAX_FILE_BYTES = 64 * 1024 * 1024
        private const val MAX_OUTPUT_BYTES = 1024 * 1024
    }
}
