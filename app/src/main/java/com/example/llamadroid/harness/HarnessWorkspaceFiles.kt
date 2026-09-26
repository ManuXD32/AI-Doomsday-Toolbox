package com.example.llamadroid.harness

import android.content.Context
import android.net.Uri
import com.example.llamadroid.R
import com.example.llamadroid.harness.HarnessWorkspaceAccess.Companion.readBounded
import com.example.llamadroid.service.AgentService
import com.example.llamadroid.service.FileInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/** Captured explorer ownership. A later session selection cannot redirect a pending SAF callback. */
class HarnessWorkspaceFiles(
    private val context: Context,
    private val legacy: AgentService,
    private val conversationId: Long?
) {
    private val runtime by lazy { HarnessAppRuntime.get(context) }
    private suspend fun capture(): HarnessWorkspaceScope =
        runtime.workspaces.fileScopeForConversation(requireNotNull(conversationId))
    private suspend fun <T> perform(fallback: suspend () -> Result<T>, action: suspend (HarnessWorkspaceScope) -> T): Result<T> {
        if (conversationId == null) return fallback()
        return withContext(Dispatchers.IO) {
            try { Result.success(action(capture())) }
            catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) {
                runtime.diagnostics.eventForConversation(conversationId, "workspace_file_action", "FAILED", errorCode = error.javaClass.simpleName)
                Result.failure(IllegalStateException(context.getString(R.string.harness_workspace_file_failed)))
            }
        }
    }

    suspend fun listDirectory(path: String): Result<List<FileInfo>> = perform({ legacy.listDirectory(path) }) { scope ->
        list(scope, relative(scope, path)).map { row ->
            val name = row.getString("name")
            FileInfo(name, path.trimEnd('/') + "/" + name, row.getBoolean("directory"), row.optLong("size"), "")
        }
    }
    suspend fun readFileBytes(path: String): Result<ByteArray> = perform({ legacy.readFileBytes(path) }) {
        runtime.files.readBytes(it, relative(it, path))
    }
    suspend fun readFile(path: String): Result<String> = perform({ legacy.readFile(path) }) {
        runtime.files.readBytes(it, relative(it, path), 1024 * 1024).toString(Charsets.UTF_8)
    }
    suspend fun writeFile(path: String, text: String): Result<Unit> = perform({ legacy.writeFile(path, text) }) {
        runtime.files.writeBytes(it, relative(it, path), text.toByteArray(Charsets.UTF_8))
    }
    suspend fun createFolder(path: String): Result<String> = perform({ legacy.createFolder(path) }) {
        runtime.files.invoke(it, "workspace.mkdir", JSONObject().put("path", relative(it, path))); ""
    }
    suspend fun uploadFile(uri: Uri, path: String): Result<Unit> = perform({ legacy.uploadFile(uri, path) }) {
        val bytes = requireNotNull(context.contentResolver.openInputStream(uri)).use { input -> input.readBounded(MAX_BYTES) }
        runtime.files.writeBytes(it, relative(it, path), bytes)
    }
    suspend fun downloadFile(path: String, uri: Uri): Result<Unit> = perform({ legacy.downloadFile(path, uri) }) {
        val target = relative(it, path)
        val info = stat(it, target)
        val bytes = if (info.getBoolean("directory")) {
            val temporary = File.createTempFile("harness-folder-", ".tgz", context.cacheDir)
            try {
                TarArchiveOutputStream(GZIPOutputStream(temporary.outputStream())).use { archive ->
                    archive.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
                    archiveTree(it, target, path.substringAfterLast('/').ifBlank { "root" }, archive, Budget())
                }
                require(temporary.length() <= MAX_BYTES)
                temporary.readBytes()
            } finally {
                temporary.delete()
            }
        } else {
            runtime.files.readBytes(it, target)
        }
        requireNotNull(context.contentResolver.openOutputStream(uri)).use { output -> output.write(bytes) }
    }
    suspend fun deletePath(path: String, recursive: Boolean): Result<String> = perform({ legacy.deletePath(path, recursive) }) {
        val target = relative(it, path)
        require(target.isNotBlank() && target != ".")
        remove(it, target, recursive, Budget()); ""
    }
    suspend fun copy(source: String, destination: String): Result<Unit> = perform({ legacy.copy(source, destination) }) {
        val from = relative(it, source); val to = relative(it, destination)
        require(from.isNotBlank() && from != "." && to != from && !to.startsWith("$from/"))
        copyTree(it, from, to, Budget())
    }
    suspend fun move(source: String, destination: String): Result<Unit> = perform({ legacy.move(source, destination) }) {
        val from = relative(it, source); val to = relative(it, destination)
        require(from.isNotBlank() && from != "." && to.isNotBlank() && to != "." && !to.startsWith("$from/"))
        runtime.files.invoke(it, "workspace.rename", JSONObject().put("path", from).put("destination", to)); Unit
    }

    suspend fun compress(paths: List<String>, destination: String): Result<Unit> = perform({ legacy.compress(paths, destination) }) { scope ->
        val target = relative(scope, destination)
        val sources = paths.map { relative(scope, it) }.distinct()
        require(sources.isNotEmpty() && sources.none { it == target || target.startsWith("$it/") })
        val temporary = File.createTempFile("harness-archive-", ".tgz", context.cacheDir)
        try {
            TarArchiveOutputStream(GZIPOutputStream(temporary.outputStream())).use { archive ->
                archive.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
                val budget = Budget()
                for (source in sources) archiveTree(scope, source, source, archive, budget)
            }
            require(temporary.length() <= MAX_BYTES)
            runtime.files.writeBytes(scope, target, temporary.inputStream().use { it.readBounded(MAX_BYTES) })
        } finally { temporary.delete() }
    }

    suspend fun uncompress(path: String, destination: String): Result<Unit> = perform({ legacy.uncompress(path, destination) }) { scope ->
        val bytes = runtime.files.readBytes(scope, relative(scope, path))
        val target = relative(scope, destination).trimEnd('/').takeUnless { it == "." }.orEmpty()
        val budget = Budget()
        TarArchiveInputStream(GZIPInputStream(bytes.inputStream())).use { archive ->
            while (true) {
                val entry = archive.nextEntry ?: break
                currentCoroutineContext().ensureActive()
                require(entry.name.isNotBlank() && !entry.name.startsWith('/') && '\\' !in entry.name &&
                    entry.name.split('/').none { it == ".." } && (entry.isDirectory || entry.isFile) && !entry.isLink && !entry.isSymbolicLink)
                val child = (if (target.isBlank()) "" else "$target/") + entry.name
                budget.accept(entry.size)
                if (entry.isDirectory) ensureDirectories(scope, child)
                else {
                    ensureDirectories(scope, child.substringBeforeLast('/', ""))
                    runtime.files.writeBytes(scope, child, archive.readBounded(MAX_BYTES))
                }
            }
        }
    }

    private suspend fun list(scope: HarnessWorkspaceScope, path: String): List<JSONObject> {
        val rows = runtime.files.invoke(scope, "workspace.list", JSONObject().put("path", path)) as JSONArray
        return (0 until rows.length()).map { rows.getJSONObject(it) }
    }
    private suspend fun stat(scope: HarnessWorkspaceScope, path: String) =
        runtime.files.invoke(scope, "workspace.stat", JSONObject().put("path", path)) as JSONObject

    private suspend fun remove(scope: HarnessWorkspaceScope, path: String, recursive: Boolean, budget: Budget) {
        require(path.count { it == '/' } <= 64)
        currentCoroutineContext().ensureActive(); budget.accept(0)
        if (recursive && stat(scope, path).getBoolean("directory")) {
            for (child in list(scope, path)) remove(scope, path + "/" + child.getString("name"), true, budget)
        }
        runtime.files.invoke(scope, "workspace.delete", JSONObject().put("path", path))
    }
    private suspend fun copyTree(scope: HarnessWorkspaceScope, from: String, to: String, budget: Budget) {
        require(from.count { it == '/' } <= 64 && to.count { it == '/' } <= 64)
        currentCoroutineContext().ensureActive()
        val info = stat(scope, from); budget.accept(info.optLong("size"))
        if (info.getBoolean("directory")) {
            ensureDirectories(scope, to)
            for (child in list(scope, from)) {
                val name = child.getString("name"); copyTree(scope, "$from/$name", "$to/$name", budget)
            }
        } else {
            ensureDirectories(scope, to.substringBeforeLast('/', ""))
            runtime.files.writeBytes(scope, to, runtime.files.readBytes(scope, from))
        }
    }
    private suspend fun archiveTree(scope: HarnessWorkspaceScope, path: String, name: String, archive: TarArchiveOutputStream, budget: Budget) {
        require(path.count { it == '/' } <= 64)
        currentCoroutineContext().ensureActive()
        val info = stat(scope, path); val directory = info.getBoolean("directory")
        budget.accept(if (directory) 0 else info.optLong("size"))
        val bytes = if (directory) ByteArray(0) else runtime.files.readBytes(scope, path)
        val entry = TarArchiveEntry(name + if (directory) "/" else "").apply { size = bytes.size.toLong() }
        archive.putArchiveEntry(entry); if (!directory) archive.write(bytes); archive.closeArchiveEntry()
        if (directory) for (child in list(scope, path)) {
            val childName = child.getString("name"); archiveTree(scope, "$path/$childName", "$name/$childName", archive, budget)
        }
    }
    private suspend fun ensureDirectories(scope: HarnessWorkspaceScope, path: String) {
        var parent = ""
        for (part in path.split('/').filter { it.isNotBlank() && it != "." }) {
            require(part != "..")
            parent = if (parent.isEmpty()) part else "$parent/$part"
            try { require(stat(scope, parent).getBoolean("directory")) }
            catch (missing: com.jcraft.jsch.SftpException) {
                if (missing.id != com.jcraft.jsch.ChannelSftp.SSH_FX_NO_SUCH_FILE) throw missing
                runtime.files.invoke(scope, "workspace.mkdir", JSONObject().put("path", parent))
            } catch (missing: IllegalArgumentException) {
                if (scope.localRoot == null || scope.localFile(parent).exists()) throw missing
                runtime.files.invoke(scope, "workspace.mkdir", JSONObject().put("path", parent))
            }
        }
    }
    private fun relative(scope: HarnessWorkspaceScope, path: String): String = relativeExplorerPath(
        path,
        if (scope.localRoot != null) com.example.llamadroid.service.AgentLocalWorkspaceSupport.displayRoot(scope.workspace.projectFolder)
        else "/workspace/${scope.workspace.projectFolder}",
        scope.workspace.guestPath
    )
    private class Budget {
        var entries = 0; var bytes = 0L
        fun accept(size: Long) { require(size >= 0 && size <= MAX_BYTES); entries++; bytes += size; require(entries <= 10_000 && bytes <= 256L * 1024 * 1024) }
    }
    companion object {
        private const val MAX_BYTES = 64 * 1024 * 1024
        internal fun relativeExplorerPath(path: String, displayRoot: String, guestRoot: String): String {
            val relative = when {
                path == displayRoot || path == guestRoot -> "."
                path.startsWith("$displayRoot/") -> path.removePrefix("$displayRoot/")
                path.startsWith("$guestRoot/") -> path.removePrefix("$guestRoot/")
                else -> path
            }
            require(!relative.startsWith('/') && '\u0000' !in relative && relative.split('/').none { it == ".." })
            return relative
        }
    }
}
