package com.example.llamadroid.service.filestation.volume

import java.text.Normalizer

/** Pure path helpers shared by every volume and API (see docs/file-station.md "Volumes"). */
object FsPaths {
    const val TRASH_DIR = ".filestation-trash"
    const val ATTACHMENTS_PREFIX = ".attachments."
    private const val MAX_SEGMENT_BYTES = 255

    /**
     * Normalizes an already-decoded volume-relative path: NFC, one optional leading and trailing
     * slash tolerated, "" and "/" are the root. Rejects NUL, backslash, ".", "..", empty inner
     * segments and segments over 255 UTF-8 bytes with INVALID_PATH.
     */
    fun normalize(raw: String): String {
        val nfc = Normalizer.normalize(raw, Normalizer.Form.NFC)
        if (nfc.isEmpty() || nfc == "/") return ""
        val trimmed = nfc.removePrefix("/").removeSuffix("/")
        val segments = trimmed.split('/')
        segments.forEach { validateSegment(it) }
        return segments.joinToString("/")
    }

    /** Percent-decodes exactly once (no '+' handling), then [normalize]s. */
    fun decodeAndNormalize(encoded: String): String = normalize(percentDecode(encoded))

    /** Validates a single file/folder name (also used for rename targets). */
    fun validateSegment(name: String): String {
        if (name.isEmpty()) invalid("empty segment")
        if (name == "." || name == "..") invalid("dot segment")
        if (name.indexOf('\u0000') >= 0) invalid("NUL in path")
        if (name.indexOf('\\') >= 0) invalid("backslash in path")
        if (name.indexOf('/') >= 0) invalid("slash in name")
        if (name.toByteArray(Charsets.UTF_8).size > MAX_SEGMENT_BYTES) invalid("segment too long")
        return Normalizer.normalize(name, Normalizer.Form.NFC)
    }

    fun join(dir: String, name: String): String = if (dir.isEmpty()) name else "$dir/$name"

    fun parent(path: String): String = path.substringBeforeLast('/', "")

    fun name(path: String): String = path.substringAfterLast('/')

    /** True when [path] equals [ancestor] or lies below it. The root is an ancestor of everything. */
    fun isSameOrInside(path: String, ancestor: String): Boolean =
        ancestor.isEmpty() || path == ancestor || path.startsWith("$ancestor/")

    /** Splits "a.tar.gz" into ("a.tar", ".gz"); dot-files and extension-less names keep an empty extension. */
    fun splitExtension(name: String): Pair<String, String> {
        val dot = name.lastIndexOf('.')
        return if (dot <= 0) name to "" else name.substring(0, dot) to name.substring(dot)
    }

    /** First free "name (1).ext", "name (2).ext", ... (the original name when it is free). */
    fun conflictName(name: String, exists: (String) -> Boolean): String {
        if (!exists(name)) return name
        val (base, ext) = splitExtension(name)
        var n = 1
        while (true) {
            val candidate = "$base ($n)$ext"
            if (!exists(candidate)) return candidate
            n++
        }
    }

    /** Attachment folder of a document: `.attachments.<docname>/` in the same directory. */
    fun attachmentsDirFor(docPath: String): String =
        join(parent(docPath), ATTACHMENTS_PREFIX + name(docPath))

    /** Hidden unless the API is asked with hidden=1. */
    fun isHiddenName(name: String): Boolean =
        name == TRASH_DIR || name.startsWith(ATTACHMENTS_PREFIX) || name.contains(".fsupload-")

    fun guessMime(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return MIME[ext] ?: "application/octet-stream"
    }

    private fun percentDecode(s: String): String {
        if (s.indexOf('%') < 0) return s
        val out = java.io.ByteArrayOutputStream(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '%') {
                val v = if (i + 2 < s.length) hex(s[i + 1], s[i + 2]) else -1
                if (v < 0) invalid("bad percent escape")
                out.write(v)
                i += 3
            } else {
                val cp = s.codePointAt(i)
                out.write(String(Character.toChars(cp)).toByteArray(Charsets.UTF_8))
                i += Character.charCount(cp)
            }
        }
        return String(out.toByteArray(), Charsets.UTF_8)
    }

    private fun hex(a: Char, b: Char): Int {
        val x = Character.digit(a, 16)
        val y = Character.digit(b, 16)
        return if (x < 0 || y < 0) -1 else x * 16 + y
    }

    private fun invalid(msg: String): Nothing = throw FsException(FsErrorCode.INVALID_PATH, msg)

    private val MIME = mapOf(
        "txt" to "text/plain", "md" to "text/markdown", "html" to "text/html", "htm" to "text/html",
        "css" to "text/css", "csv" to "text/csv", "js" to "text/javascript", "json" to "application/json",
        "xml" to "application/xml", "pdf" to "application/pdf", "zip" to "application/zip",
        "png" to "image/png", "jpg" to "image/jpeg", "jpeg" to "image/jpeg", "gif" to "image/gif",
        "webp" to "image/webp", "svg" to "image/svg+xml", "bmp" to "image/bmp",
        "mp3" to "audio/mpeg", "wav" to "audio/wav", "ogg" to "audio/ogg", "flac" to "audio/flac",
        "m4a" to "audio/mp4", "mp4" to "video/mp4", "mkv" to "video/x-matroska", "webm" to "video/webm",
        "mov" to "video/quicktime", "gz" to "application/gzip", "tar" to "application/x-tar",
        "7z" to "application/x-7z-compressed", "xz" to "application/x-xz",
        "docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "odt" to "application/vnd.oasis.opendocument.text",
    )
}
