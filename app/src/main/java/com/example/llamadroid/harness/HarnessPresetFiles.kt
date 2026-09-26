package com.example.llamadroid.harness

import android.content.Context
import com.example.llamadroid.data.proot.AgentProotEnvironmentPaths
import com.example.llamadroid.harness.client.HarnessClient
import com.example.llamadroid.harness.client.HarnessRpcResult
import com.example.llamadroid.harness.runtime.HarnessRuntimePaths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

internal data class HarnessPresetEntry(val path: String, val name: String, val directory: Boolean, val size: Long)
internal data class HarnessPresetDocument(val path: String, val text: String, val revision: String)

/** The official headless desktop action returns the resolved, user-owned directory. */
internal suspend fun openHarnessPresetFiles(
    context: Context,
    client: HarnessClient,
    presetId: String
): HarnessPresetFiles = withContext(Dispatchers.IO) {
    require(presetId.isNotBlank()) { "PRESET_DIRECTORY_INVALID" }
    val result = client.call("settings", "openAgentPresetDirectory", buildJsonObject { put("agentPreset", presetId) })
    val value = when (result) {
        is HarnessRpcResult.Failure -> error("PRESET_DIRECTORY_UNAVAILABLE")
        is HarnessRpcResult.Success -> result.value as? JsonObject ?: error("PRESET_DIRECTORY_UNAVAILABLE")
    }
    val path = value["path"]?.jsonPrimitive?.contentOrNull ?: error("PRESET_DIRECTORY_UNAVAILABLE")
    val rootfs = AgentProotEnvironmentPaths.rootfs(context, HarnessRuntimePaths.SHARED_ENVIRONMENT_ID)
    val host = resolveHarnessPresetGuestPath(path, listOf(
        "/root/.dsh" to HarnessRuntimePaths.harnessHome(context),
        "/workspace/projects" to HarnessRuntimePaths.projects(context),
        "/" to rootfs
    ))
    require(host.name == presetId) { "PRESET_DIRECTORY_INVALID" }
    HarnessPresetFiles(host, presetId)
}

/** Resolve only guest paths returned by the authenticated preset action, never arbitrary host paths. */
internal fun resolveHarnessPresetGuestPath(guestPath: String, mounts: List<Pair<String, File>>): File {
    require(guestPath.startsWith('/') && '\u0000' !in guestPath &&
        guestPath.split('/').none { it == "." || it == ".." }) { "PRESET_DIRECTORY_INVALID" }
    val mount = mounts.firstOrNull { (guest, _) ->
        guest == "/" || guestPath == guest || guestPath.startsWith("$guest/")
    } ?: error("PRESET_DIRECTORY_INVALID")
    val root = mount.second.canonicalFile
    val relative = if (mount.first == "/") guestPath.removePrefix("/") else guestPath.removePrefix(mount.first).removePrefix("/")
    val target = File(root, relative)
    require(target.toPath().normalize().startsWith(root.toPath()) && target.canonicalFile == target.absoluteFile) {
        "PRESET_PATH_OUTSIDE_SCOPE"
    }
    var current = root
    relative.split('/').filter(String::isNotEmpty).forEach { segment ->
        current = File(current, segment)
        require(!Files.isSymbolicLink(current.toPath())) { "PRESET_PATH_SYMLINK" }
    }
    require(target.isDirectory) { "PRESET_DIRECTORY_UNAVAILABLE" }
    return target
}

/** Shared bounded file operations for the native preset directory view. No credentials or sessions are indexed. */
internal class HarnessPresetFiles(root: File, val presetId: String) {
    private val root = root.absoluteFile

    init {
        require(this.root.isDirectory && this.root == this.root.canonicalFile) { "PRESET_DIRECTORY_INVALID" }
    }

    fun list(path: String): List<HarnessPresetEntry> {
        val directory = checked(path)
        require(directory.isDirectory) { "PRESET_DIRECTORY_UNAVAILABLE" }
        val rows = mutableListOf<HarnessPresetEntry>()
        Files.newDirectoryStream(directory.toPath()).use { entries ->
            for (entry in entries) {
                require(rows.size < MAX_ENTRIES) { "PRESET_DIRECTORY_TOO_LARGE" }
                // A directory shortcut must never expose another preset or the credentials home.
                if (Files.isSymbolicLink(entry)) continue
                val file = entry.toFile()
                rows += HarnessPresetEntry(relative(file), file.name, file.isDirectory, file.length())
            }
        }
        return rows.sortedWith(compareByDescending<HarnessPresetEntry> { it.directory }.thenBy { it.name.lowercase() })
    }

    fun read(path: String): HarnessPresetDocument {
        val file = checked(path)
        require(file.isFile) { "PRESET_FILE_UNAVAILABLE" }
        val bytes = readBytes(file)
        require(bytes.none { it == 0.toByte() }) { "PRESET_FILE_NOT_TEXT" }
        val text = runCatching {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        }.getOrElse { error("PRESET_FILE_NOT_TEXT") }
        return HarnessPresetDocument(path, text, digest(bytes))
    }

    @Synchronized
    fun write(document: HarnessPresetDocument, text: String): HarnessPresetDocument {
        val file = checked(document.path)
        require(file.isFile && digest(readBytes(file)) == document.revision) { "PRESET_FILE_CONFLICT" }
        val bytes = text.toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_TEXT_BYTES && bytes.none { it == 0.toByte() }) { "PRESET_FILE_TOO_LARGE" }
        val temp = File.createTempFile(".adt-edit-", ".tmp", requireNotNull(file.parentFile))
        try {
            temp.outputStream().use { it.write(bytes) }
            check(temp.setExecutable(file.canExecute(), true)) { "PRESET_FILE_WRITE_FAILED" }
            require(checked(document.path) == file && digest(readBytes(file)) == document.revision) { "PRESET_FILE_CONFLICT" }
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            // Only the staging file created for this write is removed; the user's file survives failures.
            temp.delete()
        }
        return HarnessPresetDocument(document.path, text, digest(bytes))
    }

    fun create(directory: String, name: String, folder: Boolean) {
        validateName(name)
        val parent = checked(directory)
        require(parent.isDirectory) { "PRESET_DIRECTORY_UNAVAILABLE" }
        val target = checked(relative(File(parent, name)))
        require(!Files.exists(target.toPath(), LinkOption.NOFOLLOW_LINKS)) { "PRESET_FILE_EXISTS" }
        if (folder) Files.createDirectory(target.toPath()) else Files.createFile(target.toPath())
    }

    fun rename(path: String, name: String) {
        validateName(name)
        val source = checked(path)
        require(source != root) { "PRESET_PATH_OUTSIDE_SCOPE" }
        val destination = checked(relative(File(requireNotNull(source.parentFile), name)))
        require(!Files.exists(destination.toPath(), LinkOption.NOFOLLOW_LINKS)) { "PRESET_FILE_EXISTS" }
        Files.move(source.toPath(), destination.toPath())
    }

    /** Called only after the native explicit delete confirmation; directories must be empty. */
    fun delete(path: String) {
        val file = checked(path)
        require(file != root) { "PRESET_PATH_OUTSIDE_SCOPE" }
        // Non-recursive deletion preserves every unrelated file and refuses nonempty directories.
        Files.delete(file.toPath())
    }

    private fun checked(path: String): File {
        require(!path.startsWith('/') && '\u0000' !in path && path.split('/').none { it == ".." }) { "PRESET_PATH_OUTSIDE_SCOPE" }
        val normalized = if (path.isEmpty() || path == ".") root else File(root, path)
        require(normalized.toPath().normalize().startsWith(root.toPath()) && normalized.canonicalFile.toPath().startsWith(root.toPath())) {
            "PRESET_PATH_OUTSIDE_SCOPE"
        }
        var current = root
        require(!Files.isSymbolicLink(current.toPath()) && current == current.canonicalFile) { "PRESET_PATH_SYMLINK" }
        path.split('/').filter { it.isNotEmpty() && it != "." }.forEach { segment ->
            current = File(current, segment)
            require(!Files.isSymbolicLink(current.toPath())) { "PRESET_PATH_SYMLINK" }
        }
        return normalized.canonicalFile
    }

    private fun relative(file: File): String = file.relativeTo(root).invariantSeparatorsPath
    private fun validateName(name: String) = require(name.isNotBlank() && name.length <= 200 && name !in setOf(".", "..") &&
        name.none { it == '/' || it == '\\' || it.isISOControl() }) { "PRESET_FILE_NAME_INVALID" }
    private fun readBytes(file: File): ByteArray = file.inputStream().use { input ->
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            require(output.size() + count <= MAX_TEXT_BYTES) { "PRESET_FILE_TOO_LARGE" }
            output.write(buffer, 0, count)
        }
        output.toByteArray()
    }
    private fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    companion object {
        const val MAX_TEXT_BYTES = 256 * 1024
        const val MAX_ENTRIES = 512
    }
}
