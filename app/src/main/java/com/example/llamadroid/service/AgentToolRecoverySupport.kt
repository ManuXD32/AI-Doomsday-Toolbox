package com.example.llamadroid.service

import java.util.Locale

/**
 * A model sometimes states an intended call as prose (`Tool call: read_file`)
 * instead of emitting a structured call. Keep this detector strict and return
 * metadata only; callers must route the result through malformed-call recovery.
 */
internal data class PlainTextToolCallAttempt(
    val suspectedToolName: String,
    val source: String = "assistant text",
    val error: String = "MALFORMED_TOOL_CALL_PLAIN_TEXT: emit one real structured tool call."
)

private val EXPLICIT_PLAIN_TEXT_TOOL_CALL = Regex(
    "^[ \\t]*Tool[ \\t]+call[ \\t]*:[ \\t]*" +
        "([A-Za-z][A-Za-z0-9_.-]{0,127})(?:[ \\t]*\\(|[ \\t]*\\r?$)",
    setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE)
)

/**
 * Finds an explicit, line-oriented plain-text call for an available tool.
 * An opening parenthesis may introduce prose arguments, but they are never
 * parsed or executed. Embedded prose, unknown names, and punctuation are ignored.
 */
internal fun detectExplicitPlainTextToolCallAttempt(
    text: String,
    availableToolNames: Collection<String>
): PlainTextToolCallAttempt? {
    if (text.isBlank() || availableToolNames.isEmpty()) return null
    val canonicalNames = availableToolNames
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .distinctBy { it.lowercase(Locale.ROOT) }
    if (canonicalNames.isEmpty()) return null

    return EXPLICIT_PLAIN_TEXT_TOOL_CALL.findAll(text)
        .mapNotNull { match ->
            val requested = match.groupValues.getOrNull(1).orEmpty()
            canonicalNames.firstOrNull {
                it.equals(requested, ignoreCase = true)
            }
        }
        .firstOrNull()
        ?.let { canonical ->
            PlainTextToolCallAttempt(
                suspectedToolName = canonical,
                error =
                    "MALFORMED_TOOL_CALL_PLAIN_TEXT: `Tool call: $canonical` was written as prose; " +
                        "emit one real structured tool call."
            )
        }
}

private val LOCAL_PATH_TOOL_NAMES = setOf(
    "list_directory",
    "read_file",
    "read_file_lines",
    "file_line_count",
    "write_file",
    "edit_lines",
    "create_folder",
    "search_code",
    "view_image",
    "generate_image",
    "remove_image_background"
)

private val LOCAL_PATH_FAILURE_MARKERS = listOf(
    "absolute paths are not allowed",
    "path must stay inside",
    "unsafe local workspace path",
    "path traversal is not allowed",
    "workspace directory is unavailable",
    "workspace directory is not available",
    "directorio del espacio de trabajo",
    "file not found",
    "no such file or directory",
    "missing required argument"
)

private const val LOCAL_ROOT_PATH_RECOVERY_HINT =
    "Use `.` for the selected project root, then observed child paths relative to it. " +
        "Do not repeat the project name or use `/workspace`; inspect `list_directory` at `.` first."

private const val LOCAL_FILE_PATH_RECOVERY_HINT =
    "Use a path relative to the project root, e.g. `src/app.js`. " +
        "Do not repeat the project name or use `/workspace`; inspect `list_directory` at `.` first, " +
        "then use an observed path."

/**
 * Returns bounded guidance for a failed project-relative path operation in LOCAL_SANDBOX.
 *
 * This is intentionally a hint only: it never rewrites the failed argument or treats an
 * unrecognized error as a path error. Callers should preserve the original failure and use the
 * returned value as the next recovery hint in the tool envelope.
 */
internal fun localPathRecoveryHint(
    toolName: String,
    localBackend: Boolean,
    error: Throwable?
): String? {
    if (!localBackend || error == null) return null
    val normalizedTool = toolName.trim().lowercase(Locale.ROOT)
    if (normalizedTool !in LOCAL_PATH_TOOL_NAMES) return null

    val errorText = generateSequence(error) { it.cause }
        .take(8)
        .mapNotNull { throwable -> throwable.message?.takeIf { it.isNotBlank() } }
        .joinToString("\n")
        .lowercase(Locale.ROOT)
    if (LOCAL_PATH_FAILURE_MARKERS.none { marker -> errorText.contains(marker) }) return null

    return if (normalizedTool == "list_directory") {
        LOCAL_ROOT_PATH_RECOVERY_HINT
    } else {
        LOCAL_FILE_PATH_RECOVERY_HINT
    }
}
