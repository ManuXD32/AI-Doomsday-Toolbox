package com.example.llamadroid.service

import java.io.File
import java.util.Locale
import java.util.zip.ZipFile

/** A parsed `chat_file_read` id: `f12`, `f12:path/in/zip` or `img55`. */
internal sealed class ChatFileRef {
    data class Attachment(val id: Long, val zipEntry: String? = null) : ChatFileRef()
    data class MessageImage(val messageId: Long) : ChatFileRef()
}

/** Pure helpers behind [NativeChatAttachmentTools]: id parsing, type detection, paging, zip bounds. */
internal object NativeChatAttachmentSupport {
    const val DEFAULT_MAX_CHARS = 6_000
    const val MAX_MAX_CHARS = 12_000
    const val MAX_ZIP_LIST_ENTRIES = 200
    /** Largest file read into memory for decoding (text, PDF, image). */
    const val MAX_READ_BYTES = 8L * 1024 * 1024
    const val MAX_PDF_BYTES = 48L * 1024 * 1024
    /** Largest zip entry decoded into text. */
    const val MAX_ZIP_ENTRY_BYTES = 4L * 1024 * 1024
    private const val MAX_ENTRY_PATH_CHARS = 512
    private const val SNIFF_BYTES = 4096

    private val idPattern = Regex("^(f|img)(\\d{1,12})(?::(.*))?$", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))

    private val textExtensions = setOf(
        "txt", "md", "markdown", "csv", "tsv", "json", "jsonl", "xml", "yaml", "yml", "toml", "ini", "cfg", "conf",
        "properties", "html", "htm", "css", "scss", "log", "rst", "tex", "sql", "sh", "bash", "bat", "ps1",
        "kt", "kts", "java", "js", "mjs", "ts", "tsx", "jsx", "py", "rb", "go", "rs", "c", "h", "cc", "cpp", "hpp",
        "cs", "swift", "php", "lua", "r", "dart", "gradle", "gitignore", "env", "srt", "vtt", "svg"
    )
    private val imageExtensions = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp", "heic", "heif")
    private val zipExtensions = setOf("zip", "jar", "apk", "aab")

    fun extensionOf(name: String): String = name.substringAfterLast('.', "").lowercase(Locale.ROOT)

    /** Returns null for anything that is not a valid id; a zip entry with `..`, absolute or odd paths is rejected. */
    fun parseId(raw: String?): ChatFileRef? {
        val text = raw?.trim().orEmpty().trim('"', '\'', '[', ']').trim()
        if (text.isEmpty()) return null
        val match = idPattern.matchEntire(text) ?: return null
        val id = match.groupValues[2].toLongOrNull() ?: return null
        val entry = match.groups[3]?.value
        return if (match.groupValues[1].lowercase(Locale.ROOT) == "img") {
            if (entry != null) null else ChatFileRef.MessageImage(id)
        } else {
            if (entry == null) ChatFileRef.Attachment(id)
            else sanitizeZipEntry(entry)?.let { ChatFileRef.Attachment(id, it) }
        }
    }

    /** Safe relative zip entry name or null (blank, absolute, backslash, NUL, `..` segment, too long). */
    fun sanitizeZipEntry(entry: String): String? {
        val trimmed = entry.trim()
        if (trimmed.isEmpty() || trimmed.length > MAX_ENTRY_PATH_CHARS) return null
        if (trimmed.startsWith("/") || trimmed.contains('\\') || trimmed.contains('\u0000')) return null
        if (trimmed.split('/').any { it == ".." }) return null
        return trimmed
    }

    /** True when [file] resolves (symlinks included) to a path inside [dir]. */
    fun isInside(dir: File, file: File): Boolean = runCatching {
        val root = dir.canonicalFile.path.trimEnd(File.separatorChar) + File.separator
        file.canonicalFile.path.startsWith(root)
    }.getOrDefault(false)

    /** True when [file] resolves inside at least one of [dirs]. */
    fun isInsideAny(dirs: List<File>, file: File): Boolean = dirs.any { isInside(it, file) }

    fun isImage(name: String, mime: String): Boolean =
        mime.lowercase(Locale.ROOT).startsWith("image/") || extensionOf(name) in imageExtensions

    fun isPdf(name: String, mime: String): Boolean =
        mime.equals("application/pdf", ignoreCase = true) || extensionOf(name) == "pdf"

    fun isZip(name: String, mime: String): Boolean =
        mime.lowercase(Locale.ROOT).let { it == "application/zip" || it == "application/x-zip-compressed" } ||
            extensionOf(name) in zipExtensions

    /** Declared text types (mime or extension), before the binary sniff. */
    fun isTextLike(name: String, mime: String): Boolean {
        val m = mime.lowercase(Locale.ROOT)
        return m.startsWith("text/") || m.endsWith("json") || m.endsWith("xml") || m.endsWith("yaml") ||
            m == "application/javascript" || m == "application/x-sh" || extensionOf(name) in textExtensions
    }

    /** NUL byte, or more than 10% control characters / replacement characters in the first bytes. */
    fun looksBinary(bytes: ByteArray, length: Int = bytes.size): Boolean {
        val n = minOf(length, bytes.size, SNIFF_BYTES)
        if (n == 0) return false
        var control = 0
        for (i in 0 until n) {
            val b = bytes[i].toInt() and 0xFF
            if (b == 0) return true
            if (b < 32 && b != 9 && b != 10 && b != 13 && b != 12 && b != 27) control++
        }
        if (control * 10 > n) return true
        val decoded = String(bytes, 0, n, Charsets.UTF_8)
        // A trailing multi-byte character cut by the sniff window can add one replacement; allow a few.
        val replacements = decoded.count { it == '�' }
        return replacements > 3 && replacements * 10 > decoded.length
    }

    fun clampMaxChars(requested: Int?): Int = (requested ?: DEFAULT_MAX_CHARS).coerceIn(1, MAX_MAX_CHARS)

    /** One page of [text] from [offset] (chars) with the continuation footer. */
    fun page(text: String, offset: Int?, maxChars: Int?, truncatedSource: Boolean = false): String {
        val total = text.length
        val start = (offset ?: 0).coerceAtLeast(0)
        if (start >= total) {
            return if (total == 0) "(empty file)" else "(offset $start is past the end; the file has $total chars)"
        }
        val end = minOf(total, start + clampMaxChars(maxChars))
        val totalLabel = if (truncatedSource) "at least $total" else "$total"
        val footer = if (end < total || truncatedSource) {
            "(chars $start–$end of $totalLabel; continue with offset=$end)"
        } else {
            "(chars $start–$end of $totalLabel; end of file)"
        }
        return text.substring(start, end) + "\n\n" + footer
    }

    /** Entry names and sizes of a zip, at most [maxEntries] files, plus a note when more exist. */
    fun zipListing(zip: ZipFile, displayName: String, maxEntries: Int = MAX_ZIP_LIST_ENTRIES): String {
        val entries = zip.entries()
        val lines = ArrayList<String>()
        var total = 0
        while (entries.hasMoreElements()) {
            val entry = entries.nextElement()
            if (entry.isDirectory) continue
            total++
            if (lines.size < maxEntries) {
                val size = if (entry.size >= 0) formatSize(entry.size) else "?"
                lines += "${entry.name} ($size)"
            }
        }
        return buildString {
            appendLine("Zip archive $displayName: $total file(s).")
            lines.forEach { appendLine(it) }
            if (total > lines.size) appendLine("... ${total - lines.size} more entries not listed.")
            append("Read a text entry with id=\"<id>:<path in zip>\".")
        }
    }

    fun formatSize(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${(bytes + 512) / 1024} KB"
        else -> String.format(Locale.US, "%.1f MB", bytes / 1024.0 / 1024.0)
    }

    /** Reads at most [limit] bytes of [file]; the flag says whether the file was longer. */
    fun readCapped(file: File, limit: Long): Pair<ByteArray, Boolean> =
        file.inputStream().use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(32 * 1024)
            var remaining = limit + 1
            while (remaining > 0) {
                val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                if (read < 0) break
                out.write(buffer, 0, read)
                remaining -= read
            }
            val bytes = out.toByteArray()
            if (bytes.size > limit) bytes.copyOf(limit.toInt()) to true else bytes to false
        }
}
