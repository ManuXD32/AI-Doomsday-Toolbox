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
    /** Limit for saves typed by the user in the workspace editor (the model tools keep the 16 KiB batch cap). */
    const val MAX_USER_WRITE_BYTES = 2 * 1024 * 1024
    private const val MAX_EDIT_FILE_BYTES = 1_048_576L
    private const val LIST_LINE_COUNT_MAX_BYTES = 262_144L
    private val fileLock = Any()

    fun root(context: Context, chatId: Long): File {
        require(chatId > 0) { "A saved chat is required for workspace tools." }
        return File(context.filesDir, "native_chat_workspaces/$chatId").apply {
            require((isDirectory || mkdirs()) && isDirectory) { "Could not create the chat workspace." }
        }
    }

    /** Folder inside the workspace where composer attachments are stored. */
    const val ATTACHMENTS_DIR = "attachments"
    /** Folder inside the workspace where DeepSeek result files are exported. */
    const val RESULTS_DIR = "results"

    /** Workspace subfolder [name], created on demand. */
    fun subDir(context: Context, chatId: Long, name: String): File =
        File(root(context, chatId), name).apply {
            require((isDirectory || mkdirs()) && isDirectory) { "Could not create the chat workspace folder." }
        }

    /** Workspace root without creating it (for read-only checks). */
    fun rootIfPresent(context: Context, chatId: Long): File =
        File(context.filesDir, "native_chat_workspaces/$chatId")

    /** Legacy attachment root, still honored for rows imported before round 8. */
    fun legacyAttachmentRoot(context: Context, chatId: Long): File =
        File(context.filesDir, "llama_chat_files/$chatId")

    /** A name not yet used in [dir]: `report.zip`, then `report-2.zip`, `report-3.zip`... */
    fun uniqueFile(dir: File, name: String): File {
        var target = File(dir, name)
        if (!target.exists()) return target
        val dot = name.lastIndexOf('.')
        val stem = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var n = 2
        while (target.exists()) {
            target = File(dir, "$stem-$n$ext")
            n++
        }
        return target
    }

    fun resolve(context: Context, chatId: Long, relativePath: String): File =
        resolveUnder(root(context, chatId).canonicalFile, relativePath)

    /** Path-safety core of [resolve]: [root] must be canonical. */
    internal fun resolveUnder(root: File, relativePath: String): File {
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
            .joinToString("\n") {
                if (it.isDirectory) "[dir] ${it.name}"
                else "[file] ${it.name}" + lineCountSuffix(it)
            }
            .ifBlank { "[empty directory]" }
    }

    const val LISTING_MAX_DEPTH = 3
    const val LISTING_MAX_ENTRIES = 200
    const val LISTING_FOOTER = "Read attachments with chat_file_read <id>; other files with read_workspace_file <path>."
    const val LIST_FILES_HINT =
        "Call list_workspace_files to see them before saying a file is missing or asking the user to send it again."

    /** Context-bound recursive listing of the whole workspace (attachment ids resolved from the database). */
    suspend fun listAll(context: Context, chatId: Long): String {
        val root = root(context, chatId).canonicalFile
        val db = com.example.llamadroid.data.db.AppDatabase.getDatabase(context)
        val idsByPath = HashMap<String, String>()
        runCatching { db.llamaMessageAttachmentDao().getForChat(chatId) }.getOrDefault(emptyList()).forEach { row ->
            runCatching { File(row.path).canonicalPath }.getOrNull()?.let { idsByPath[it] = "f${row.id}" }
        }
        val outside = ArrayList<Pair<String, String>>()
        runCatching { db.llamaMessageDao().getMessagesOnce(chatId) }.getOrDefault(emptyList()).forEach { msg ->
            val file = msg.imagePath?.takeIf { it.isNotBlank() }?.let { File(it) } ?: return@forEach
            if (!file.isFile) return@forEach
            val canonical = runCatching { file.canonicalPath }.getOrNull() ?: return@forEach
            if (canonical.startsWith(root.path + File.separator)) idsByPath[canonical] = "img${msg.id}"
            else outside += "img${msg.id}" to file.name
        }
        return formatListing(root, idsByPath, outside)
    }

    /**
     * Pure recursive listing: relative paths (depth <= [LISTING_MAX_DEPTH], <= [LISTING_MAX_ENTRIES] entries),
     * files known by [idsByPath] (canonical path -> `f12`/`img55`) show their chat_file_read id, and
     * [outsideImages] (id to name) are message images stored outside the workspace.
     */
    internal fun formatListing(root: File, idsByPath: Map<String, String>, outsideImages: List<Pair<String, String>> = emptyList()): String {
        val lines = ArrayList<String>()
        var truncated = false
        fun walk(dir: File, depth: Int) {
            val children = dir.listFiles().orEmpty()
                .filter { !it.name.startsWith(".") }
                .sortedWith(compareBy<File>({ !it.isDirectory }, { it.name.lowercase() }))
            for (child in children) {
                if (lines.size >= LISTING_MAX_ENTRIES) { truncated = true; return }
                val rel = child.relativeTo(root).path.replace(File.separatorChar, '/')
                if (child.isDirectory) {
                    lines += "[dir] $rel/"
                    if (depth <= LISTING_MAX_DEPTH) walk(child, depth + 1)
                } else {
                    val id = runCatching { idsByPath[child.canonicalPath] }.getOrNull()
                    lines += "[file] $rel" + lineCountSuffix(child) + (id?.let { " id=$it" } ?: "")
                }
            }
        }
        walk(root, 1)
        for ((id, name) in outsideImages) {
            if (lines.size >= LISTING_MAX_ENTRIES) { truncated = true; break }
            lines += "[image] $name id=$id"
        }
        if (lines.isEmpty()) return "[empty workspace]\n$LISTING_FOOTER"
        return lines.joinToString("\n") + (if (truncated) "\n(listing truncated at $LISTING_MAX_ENTRIES entries)" else "") + "\n$LISTING_FOOTER"
    }

    /** Number of visible files (depth <= 3) under [root]; 0 when it does not exist. */
    fun countFiles(root: File): Int =
        if (!root.isDirectory) 0
        else root.walkTopDown().maxDepth(LISTING_MAX_DEPTH + 1)
            .onEnter { !it.name.startsWith(".") || it == root }
            .count { it.isFile && !it.name.startsWith(".") }

    /** Per-turn hint when file tools are OFF; null when the chat has no files. */
    fun filesCountHint(count: Int): String? =
        if (count <= 0) null else "This chat has $count file${if (count == 1) "" else "s"}. $LIST_FILES_HINT"

    private fun lineCountSuffix(file: File): String =
        if (file.length() <= LIST_LINE_COUNT_MAX_BYTES) {
            runCatching { " (${NativeChatWorkspaceEdits.lineCount(file)} lines)" }.getOrDefault("")
        } else {
            " (${file.length() / 1024} KB)"
        }

    /** Numbered ranged read; with no range, the first 300 lines. */
    fun read(context: Context, chatId: Long, path: String, startLine: Int? = null, endLine: Int? = null): String {
        val file = resolve(context, chatId, path)
        require(file.exists() && file.isFile) { "Workspace file not found: $path" }
        return NativeChatWorkspaceEdits.readRange(readEditable(file, "read"), path, startLine, endLine)
    }

    private val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp", "heic", "heif")

    /** MIME type when [header] (the first bytes of a file) is a known image format, else null. Never decodes anything. */
    fun imageMimeFromMagic(header: ByteArray): String? {
        fun at(i: Int) = if (i < header.size) header[i].toInt() and 0xFF else -1
        fun ascii(from: Int, text: String) = text.indices.all { at(from + it) == text[it].code }
        return when {
            at(0) == 0x89 && ascii(1, "PNG") -> "image/png"
            at(0) == 0xFF && at(1) == 0xD8 && at(2) == 0xFF -> "image/jpeg"
            ascii(0, "GIF8") -> "image/gif"
            ascii(0, "RIFF") && ascii(8, "WEBP") -> "image/webp"
            header.size >= 14 && ascii(0, "BM") && (6..9).all { at(it) == 0 } -> "image/bmp"
            ascii(4, "ftyp") && (ascii(8, "heic") || ascii(8, "heix") || ascii(8, "mif1") || ascii(8, "heif")) -> "image/heic"
            else -> null
        }
    }

    /** True when [file] is an image by extension or by magic bytes (so text tools never decode it as text). */
    fun isImageFile(file: File): Boolean {
        if (!file.isFile) return false
        if (file.name.substringAfterLast('.', "").lowercase(java.util.Locale.ROOT) in IMAGE_EXTENSIONS) return true
        return imageMimeOf(file) != null
    }

    /** MIME type from the file's magic bytes, or null. */
    fun imageMimeOf(file: File): String? = runCatching {
        val header = ByteArray(16)
        val n = file.inputStream().use { it.read(header) }
        if (n <= 0) null else imageMimeFromMagic(header.copyOf(n))
    }.getOrNull()

    fun search(context: Context, chatId: Long, path: String, query: String, maxResults: Int?, regex: Boolean): String {
        val file = resolve(context, chatId, path)
        require(file.exists() && file.isFile) { "Workspace file not found: $path" }
        return NativeChatWorkspaceEdits.search(readEditable(file, "search"), path, query, maxResults, regex)
    }

    fun replaceInFile(context: Context, chatId: Long, path: String, oldText: String, newText: String, replaceAll: Boolean): String {
        val file = resolve(context, chatId, path)
        require(file.exists() && file.isFile) { "Workspace file not found: $path" }
        return synchronized(fileLock) {
            val outcome = NativeChatWorkspaceEdits.replaceInText(readEditable(file, "edit"), path, oldText, newText, replaceAll)
            val bytes = outcome.updatedText.toByteArray(Charsets.UTF_8)
            require(bytes.size <= MAX_EDIT_FILE_BYTES) { "The edited file would exceed 1 MiB; split it first." }
            replaceFile(file, bytes)
            outcome.message
        }
    }

    private fun readEditable(file: File, verb: String): String {
        require(file.length() <= MAX_EDIT_FILE_BYTES) { "File is larger than 1 MiB and cannot be used to $verb in memory; split it first." }
        return file.readText(Charsets.UTF_8)
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
            val oldLength = if (file.isFile) file.length() else 0L
            if (append) file.appendBytes(contentBytes) else replaceFile(file, contentBytes)
            if (file.length() > MAX_EDIT_FILE_BYTES) {
                return "${if (append) "Appended" else "Wrote"} ${contentBytes.size} bytes to $path (file is larger than 1 MiB; no echo)."
            }
            val text = file.readText(Charsets.UTF_8)
            return if (append) NativeChatWorkspaceEdits.echoAppend(path, contentBytes.size, oldLength, text)
            else NativeChatWorkspaceEdits.echoWrite(path, contentBytes.size, text)
        }
    }

    /** Saves editor text typed by the user: same path safety and atomic replace as [write], 2 MiB limit. */
    fun writeUser(context: Context, chatId: Long, path: String, text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_USER_WRITE_BYTES) { "File exceeds the 2 MiB editor limit." }
        val file = resolve(context, chatId, path)
        require(file != root(context, chatId).canonicalFile) { "A file path is required." }
        synchronized(fileLock) { replaceFile(file, bytes) }
    }

    fun editLines(context: Context, chatId: Long, path: String, startLine: Int, endLine: Int, content: String): String {
        val contentBytes = content.toByteArray(Charsets.UTF_8)
        require(content.length <= MAX_MUTATION_CHARS && contentBytes.size <= MAX_MUTATION_BYTES) {
            "WRITE_BATCH_REQUIRED: replacement exceeds 16 KiB."
        }
        val file = resolve(context, chatId, path)
        require(file.exists() && file.isFile) { "Workspace file not found: $path" }
        require(file.length() <= MAX_EDIT_FILE_BYTES) { "File is too large for an in-memory line edit; split it first." }
        return synchronized(fileLock) {
            val edited = AgentRuntimeSupport.computeEditedFileContent(
                file.readText(Charsets.UTF_8),
                startLine,
                endLine,
                content
            )
            replaceFile(file, edited.updatedContent.toByteArray(Charsets.UTF_8))
            NativeChatWorkspaceEdits.echoLines(path, startLine, endLine, edited.insertedLineCount, edited.updatedContent)
        }
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
