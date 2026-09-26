package com.example.llamadroid.service

import android.content.Context
import java.io.File
import java.io.InputStreamReader
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Private, per-chat workspace used only when the user enables Native Chat file tools. */
object NativeChatWorkspaceSupport {
    const val MAX_MUTATION_CHARS = 16_384
    const val MAX_MUTATION_BYTES = 16_384
    const val MAX_READ_CHARS = 24_000
    private const val MAX_EDIT_FILE_BYTES = 1_048_576L
    private val fileLock = Any()

    fun root(context: Context, chatId: Long): File {
        require(chatId > 0) { "A saved chat is required for workspace tools." }
        return File(context.filesDir, "native_chat_workspaces/$chatId").apply {
            require((isDirectory || mkdirs()) && isDirectory) { "Could not create the chat workspace." }
        }
    }

    fun resolve(context: Context, chatId: Long, relativePath: String): File {
        val root = root(context, chatId).canonicalFile
        val normalized = relativePath.trim().trimStart('/').ifBlank { "." }
        require(normalized.split('/').none { it == ".." }) { "Workspace paths cannot contain `..`." }
        val file = File(root, normalized).canonicalFile
        require(file.path == root.path || file.path.startsWith(root.path + File.separator)) {
            "Workspace path must stay inside this chat."
        }
        return file
    }

    fun list(context: Context, chatId: Long, path: String): String {
        val directory = resolve(context, chatId, path)
        require(directory.exists() && directory.isDirectory) { "Workspace directory not found: $path" }
        return directory.listFiles().orEmpty()
            .sortedWith(compareBy<File>({ !it.isDirectory }, { it.name.lowercase() }))
            .take(200)
            .joinToString("\n") { "${if (it.isDirectory) "[dir]" else "[file]"} ${it.name}" }
            .ifBlank { "[empty directory]" }
    }

    fun read(context: Context, chatId: Long, path: String): String {
        val file = resolve(context, chatId, path)
        require(file.exists() && file.isFile) { "Workspace file not found: $path" }
        val text = readBounded(file, MAX_READ_CHARS)
        return buildString {
            append(text.first)
            if (text.second) append("\n[truncated; use a more focused file or split the content]")
        }
    }

    fun write(context: Context, chatId: Long, path: String, content: String, append: Boolean): String {
        val contentBytes = content.toByteArray(Charsets.UTF_8)
        require(content.length <= MAX_MUTATION_CHARS && contentBytes.size <= MAX_MUTATION_BYTES) {
            "WRITE_BATCH_REQUIRED: content exceeds 16 KiB; use smaller write/append batches."
        }
        val file = resolve(context, chatId, path)
        require(file != root(context, chatId).canonicalFile) { "A file path is required." }
        synchronized(fileLock) {
            ensureParent(file)
            if (append) file.appendBytes(contentBytes) else replaceFile(file, contentBytes)
        }
        return "${if (append) "Appended" else "Wrote"} ${contentBytes.size} bytes to $path."
    }

    fun editLines(context: Context, chatId: Long, path: String, startLine: Int, endLine: Int, content: String): String {
        val contentBytes = content.toByteArray(Charsets.UTF_8)
        require(content.length <= MAX_MUTATION_CHARS && contentBytes.size <= MAX_MUTATION_BYTES) {
            "WRITE_BATCH_REQUIRED: replacement exceeds 16 KiB."
        }
        val file = resolve(context, chatId, path)
        require(file.exists() && file.isFile) { "Workspace file not found: $path" }
        require(file.length() <= MAX_EDIT_FILE_BYTES) { "File is too large for an in-memory line edit; split it first." }
        synchronized(fileLock) {
            val edited = AgentRuntimeSupport.computeEditedFileContent(
                file.readText(Charsets.UTF_8),
                startLine,
                endLine,
                content
            )
            replaceFile(file, edited.updatedContent.toByteArray(Charsets.UTF_8))
        }
        return "Replaced lines $startLine-$endLine in $path."
    }

    fun applyPatch(context: Context, chatId: Long, patch: String): String {
        require(patch.length <= MAX_MUTATION_CHARS) { "WRITE_BATCH_REQUIRED: patch exceeds 16 KiB." }
        val outputs = synchronized(fileLock) {
            AgentLocalPatchSupport.apply(patch) { path ->
                val file = resolve(context, chatId, path)
                file.takeIf { it.exists() && it.isFile }?.also {
                    require(it.length() <= MAX_EDIT_FILE_BYTES) {
                        "File is too large for an in-memory patch; split it first."
                    }
                }?.readText(Charsets.UTF_8)
            }.also { patchOutputs ->
                patchOutputs.forEach { output ->
                    val file = resolve(context, chatId, output.path)
                    if (output.delete) require(file.delete()) { "Failed to delete ${output.path}." }
                    else {
                        ensureParent(file)
                        replaceFile(file, output.content.orEmpty().toByteArray(Charsets.UTF_8))
                    }
                }
            }
        }
        return "Patch applied to ${outputs.joinToString { it.path }}."
    }

    private fun readBounded(file: File, maxChars: Int): Pair<String, Boolean> =
        InputStreamReader(file.inputStream(), Charsets.UTF_8).use { reader ->
            val buffer = CharArray(maxChars + 1)
            var total = 0
            while (total < buffer.size) {
                val count = reader.read(buffer, total, buffer.size - total)
                if (count < 0) break
                total += count
            }
            String(buffer, 0, total.coerceAtMost(maxChars)) to (total > maxChars)
        }

    private fun ensureParent(file: File) {
        val parent = requireNotNull(file.parentFile)
        require((parent.isDirectory || parent.mkdirs()) && parent.isDirectory) {
            "Could not create the workspace folder."
        }
    }

    private fun replaceFile(file: File, bytes: ByteArray) {
        ensureParent(file)
        val temporary = File(file.parentFile, ".${file.name}.${System.nanoTime()}.tmp")
        try {
            temporary.writeBytes(bytes)
            runCatching {
                Files.move(
                    temporary.toPath(),
                    file.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
                )
            }.getOrElse {
                Files.move(
                    temporary.toPath(),
                    file.toPath(),
                    StandardCopyOption.REPLACE_EXISTING
                )
            }
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }
}
