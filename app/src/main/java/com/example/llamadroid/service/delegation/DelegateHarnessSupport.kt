package com.example.llamadroid.service.delegation

import java.util.Locale

/**
 * Failure of a harness operation, carrying a stable [code] and model-facing English [message]
 * the subagent can act on. Thrown by [DelegateHarnessDriver] and [DelegateHarnessModelBinder];
 * the tool layer renders it as `tool_error: <code>: <message>`.
 */
class DelegationHarnessException(val code: String, message: String, cause: Throwable? = null) :
    Exception(message, cause) {
    fun toToolError(): String = "tool_error: $code: $message"
}

/** Pure helpers for paths, names, text decoding and error mapping. No Android types. */
internal object DelegateHarnessSupport {
    const val MAX_READ_CHARS = 6000
    const val INPUTS_DIR = "inputs"

    /** Clamp a requested read size to 1..[MAX_READ_CHARS] (default when null). */
    fun clampChars(requested: Int?, default: Int = 4000): Int =
        (requested ?: default).coerceIn(1, MAX_READ_CHARS)

    /**
     * Validate a model-supplied project-relative path. Rejects absolute paths, `..` segments,
     * NUL, backslashes and drive letters. Returns the normalized path (`.` for the project root).
     */
    fun requireProjectRelative(path: String?): String {
        val raw = path?.trim().orEmpty()
        if (raw.isEmpty() || raw == ".") return "."
        if (raw.startsWith("/") || raw.startsWith("~") || '\u0000' in raw || '\\' in raw ||
            Regex("^[A-Za-z]:").containsMatchIn(raw)
        ) {
            throw DelegationHarnessException("PATH_OUTSIDE_PROJECT", "Only project-relative paths are allowed (no absolute paths).")
        }
        val parts = raw.split('/').filter { it.isNotEmpty() && it != "." }
        if (parts.any { it == ".." }) {
            throw DelegationHarnessException("PATH_OUTSIDE_PROJECT", "Paths must stay inside the project (no '..').")
        }
        return parts.joinToString("/").ifEmpty { "." }
    }

    /** File name safe to place under `inputs/`: base name only, conservative alphabet, <= 80 chars. */
    fun sanitizeInputName(displayName: String): String {
        val base = displayName.substringAfterLast('/').substringAfterLast('\\').trim()
        val cleaned = base.replace(Regex("[^A-Za-z0-9._ -]"), "_").replace(Regex("\\s+"), " ").trim().trim('.')
        if (cleaned.isEmpty()) return "file"
        if (cleaned.length <= 80) return cleaned
        val dot = cleaned.lastIndexOf('.')
        val ext = if (dot > 0 && cleaned.length - dot <= 12) cleaned.substring(dot) else ""
        return cleaned.take(80 - ext.length) + ext
    }

    /** Add `-2`, `-3`... before the extension until [name] is not in [taken] (case-insensitive). */
    fun uniqueName(name: String, taken: Set<String>): String {
        val lowered = taken.mapTo(HashSet()) { it.lowercase(Locale.ROOT) }
        if (name.lowercase(Locale.ROOT) !in lowered) return name
        val dot = name.lastIndexOf('.')
        val stem = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var index = 2
        while (true) {
            val candidate = "$stem-$index$ext"
            if (candidate.lowercase(Locale.ROOT) !in lowered) return candidate
            index++
        }
    }

    /** NUL bytes or many control characters in the first 8 KB mark a file as binary. */
    fun looksBinary(bytes: ByteArray, length: Int = bytes.size): Boolean {
        val n = minOf(length, 8000)
        if (n == 0) return false
        var control = 0
        for (i in 0 until n) {
            val b = bytes[i].toInt() and 0xFF
            if (b == 0) return true
            if (b < 9 || (b in 14..31)) control++
        }
        return control * 100 / n > 30
    }

    /** Decode a UTF-8 chunk that may end inside a multi-byte sequence. */
    fun decodeUtf8Chunk(bytes: ByteArray, length: Int = bytes.size): String {
        var end = length
        // Drop an incomplete trailing sequence (at most 3 continuation bytes after a lead byte).
        var back = 0
        while (end - 1 - back >= 0 && back < 3 && (bytes[end - 1 - back].toInt() and 0xC0) == 0x80) back++
        if (end - 1 - back >= 0) {
            val lead = bytes[end - 1 - back].toInt() and 0xFF
            val need = when {
                lead >= 0xF0 -> 4
                lead >= 0xE0 -> 3
                lead >= 0xC0 -> 2
                else -> 1
            }
            if (need > back + 1) end -= back + 1
        }
        return String(bytes, 0, end, Charsets.UTF_8)
    }

    fun clip(text: String, max: Int): String {
        val limit = max.coerceIn(1, MAX_READ_CHARS)
        return if (text.length <= limit) text else text.take(limit - 1) + "…"
    }

    /**
     * Map a runtime start failure to a model-facing exception. [code] is the sanitized lifecycle
     * error code; [throwableName]/[message] help recognise a foreground-service denial.
     */
    fun mapStartFailure(code: String?, throwableName: String?, message: String?): DelegationHarnessException {
        val c = code.orEmpty()
        val denied = throwableName.orEmpty().contains("ForegroundServiceStartNotAllowed") ||
            message.orEmpty().contains("startForeground", ignoreCase = true) ||
            message.orEmpty().contains("background", ignoreCase = true) && message.orEmpty().contains("not allowed", ignoreCase = true)
        return when {
            denied -> DelegationHarnessException("RUNTIME_NEEDS_APP",
                "Android refused to start the DeepSeek runtime while the app is in the background. Tell the user to open the AI Doomsday Toolbox app, then continue.")
            c == "HARNESS_CLEANUP_IN_PROGRESS" -> DelegationHarnessException(c,
                "The runtime is still cleaning up a previous run. Wait a minute and try again.")
            c == "HARNESS_RUNTIME_REQUIRED" || c == "HARNESS_RUNTIME_UNAVAILABLE" || c == "HARNESS_OPERATION_INTERRUPTED" ->
                DelegationHarnessException(c,
                    "The selected DeepSeek runtime is not installed or is busy installing, updating or repairing. Tell the user to check Settings > DeepSeek runtimes.")
            c == "HARNESS_PORT_IN_USE" -> DelegationHarnessException(c, "The runtime could not get a free network port. Try again shortly.")
            c.isNotEmpty() -> DelegationHarnessException(c, "The DeepSeek runtime failed to start ($c). Tell the user to open the app and start the runtime manually.")
            else -> DelegationHarnessException("HARNESS_START_FAILED",
                "The DeepSeek runtime did not start. Tell the user to open the app and start the runtime manually.")
        }
    }
}
