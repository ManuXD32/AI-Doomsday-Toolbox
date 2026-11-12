package com.example.llamadroid.service

import android.content.Context
import java.io.File

/**
 * Small-read / small-edit helpers for the chat workspace (ranged read, search, exact replace, and the
 * numbered "what changed" echo). Pure text/file logic lives here so it is unit-testable; path
 * resolution and locking stay in [NativeChatWorkspaceSupport].
 */
object NativeChatWorkspaceEdits {
    const val TOOL_SEARCH = "search_workspace_file"
    const val TOOL_REPLACE = "replace_in_workspace_file"

    const val DEFAULT_READ_LINES = 300
    const val MAX_READ_LINES = 400
    const val MAX_LINE_CHARS = 400
    const val CONTEXT_LINES = 3
    const val MAX_ECHO_LINES = 60
    const val DEFAULT_SEARCH_RESULTS = 40
    const val MAX_SEARCH_RESULTS = 200
    const val MAX_QUERY_CHARS = 500
    private const val MAX_LISTED_LINES = 20

    /** Lines of [text]: split on `\n`, a final newline does not create an extra empty line. */
    fun lines(text: String): List<String> {
        if (text.isEmpty()) return emptyList()
        val parts = text.split('\n')
        return (if (text.endsWith("\n")) parts.dropLast(1) else parts).map { it.removeSuffix("\r") }
    }

    private fun clip(line: String): String =
        if (line.length <= MAX_LINE_CHARS) line else line.take(MAX_LINE_CHARS) + "…[+${line.length - MAX_LINE_CHARS} chars]"

    private fun numbered(number: Int, line: String) = "${number.toString().padStart(4)}| ${clip(line)}"

    /** `path · N lines · showing a–b` followed by numbered lines; defaults to the first 300 lines. */
    fun readRange(text: String, displayPath: String, startLine: Int?, endLine: Int?): String {
        val all = lines(text)
        val total = all.size
        if (total == 0) return "$displayPath · 0 lines · showing 0–0\n[empty file]"
        val start = startLine ?: 1
        require(start >= 1) { "start_line must be >= 1." }
        require(start <= total) { "start_line ($start) is past the end; the file has $total lines." }
        var end = (endLine ?: (start + DEFAULT_READ_LINES - 1)).coerceAtMost(total)
        require(end >= start) { "end_line ($end) must be >= start_line ($start)." }
        end = end.coerceAtMost(start + MAX_READ_LINES - 1)
        val body = StringBuilder()
        var shown = start - 1
        for (n in start..end) {
            val row = numbered(n, all[n - 1])
            if (shown >= start && body.length + row.length > NativeChatWorkspaceSupport.MAX_READ_CHARS) break
            body.append(row).append('\n')
            shown = n
        }
        val header = "$displayPath · $total lines · showing $start–$shown"
        val more = if (shown < total) "\n[more lines: continue with start_line=${shown + 1}]" else ""
        return header + "\n" + body.toString().trimEnd('\n') + more
    }

    /** Case-insensitive substring (or regex) search; `line| text` hits plus the total count. */
    fun search(text: String, displayPath: String, query: String, maxResults: Int?, regex: Boolean): String {
        require(query.isNotEmpty()) { "query is required." }
        require(query.length <= MAX_QUERY_CHARS) { "query is too long (max $MAX_QUERY_CHARS chars)." }
        val limit = (maxResults ?: DEFAULT_SEARCH_RESULTS).coerceIn(1, MAX_SEARCH_RESULTS)
        val compiled: Regex? = if (regex) {
            try {
                Regex(query, RegexOption.IGNORE_CASE)
            } catch (e: IllegalArgumentException) {
                throw IllegalArgumentException("Invalid regex: ${e.message?.lineSequence()?.firstOrNull().orEmpty()}")
            }
        } else {
            null
        }
        val matcher = { line: String -> compiled?.containsMatchIn(line) ?: line.contains(query, ignoreCase = true) }
        val all = lines(text)
        val hits = all.withIndex().filter { matcher(it.value) }
        val out = StringBuilder("$displayPath · ${all.size} lines · ${hits.size} matches")
        if (hits.isEmpty()) return out.toString()
        hits.take(limit).forEach { out.append('\n').append(numbered(it.index + 1, it.value)) }
        if (hits.size > limit) out.append("\n[${hits.size - limit} more matches; refine the query]")
        return out.toString()
    }

    class ReplaceOutcome(val updatedText: String, val message: String)

    /** Exact-text replace. Throws [IllegalArgumentException] with a recovery hint on 0 or ambiguous matches. */
    fun replaceInText(text: String, displayPath: String, oldText: String, newText: String, replaceAll: Boolean): ReplaceOutcome {
        require(oldText.isNotEmpty()) { "old_text is required." }
        require(oldText.length <= NativeChatWorkspaceSupport.MAX_MUTATION_CHARS && newText.length <= NativeChatWorkspaceSupport.MAX_MUTATION_CHARS) {
            "WRITE_BATCH_REQUIRED: old_text/new_text exceed 16 KiB; use a smaller exact snippet."
        }
        val starts = ArrayList<Int>()
        var from = 0
        while (true) {
            val i = text.indexOf(oldText, from)
            if (i < 0) break
            starts += i
            from = i + oldText.length
        }
        if (starts.isEmpty()) {
            val firstLine = oldText.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }
            val near = firstLine?.let { probe -> lines(text).indexOfFirst { it.contains(probe) } }?.takeIf { it >= 0 }
            val hint = if (near != null) "its first line appears near line ${near + 1}; read lines around ${near + 1}" else "use search_workspace_file or read lines around the spot"
            throw IllegalArgumentException("old_text not found (exact match, whitespace included); $hint, then retry with the exact text.")
        }
        fun lineOf(offset: Int): Int = 1 + text.substring(0, offset).count { it == '\n' }
        if (starts.size > 1 && !replaceAll) {
            val where = starts.take(MAX_LISTED_LINES).joinToString(", ") { lineOf(it).toString() }
            throw IllegalArgumentException("old_text matches ${starts.size} places (lines $where). Include more surrounding text to make it unique, or set replace_all=true.")
        }
        val used = if (replaceAll) starts else starts.take(1)
        val updated = if (replaceAll) text.replace(oldText, newText) else text.substring(0, used[0]) + newText + text.substring(used[0] + oldText.length)
        val firstLine = lineOf(used[0])
        val lastLine = firstLine + maxOf(0, newText.count { it == '\n' } - if (newText.endsWith("\n")) 1 else 0)
        val newLines = lines(updated)
        val head = "Replaced ${used.size} occurrence${if (used.size == 1) "" else "s"} in $displayPath" +
            (if (used.size > 1) " (first at line $firstLine)" else "") + ". File now has ${newLines.size} lines."
        return ReplaceOutcome(updated, head + "\n" + echo(newLines, firstLine, lastLine))
    }

    /** Numbered changed region [first]..[last] (1-based, inclusive) with ±3 lines of context. */
    fun echo(allLines: List<String>, first: Int, last: Int): String {
        if (allLines.isEmpty()) return "[file is now empty]"
        val from = (first - CONTEXT_LINES).coerceIn(1, allLines.size)
        var to = (maxOf(last, first) + CONTEXT_LINES).coerceAtMost(allLines.size)
        var note = ""
        if (to - from + 1 > MAX_ECHO_LINES) {
            to = from + MAX_ECHO_LINES - 1
            note = "\n[region longer than $MAX_ECHO_LINES lines; read the rest with start_line=${to + 1}]"
        }
        return "Changed region (numbered, ±$CONTEXT_LINES lines context):\n" +
            (from..to).joinToString("\n") { numbered(it, allLines[it - 1]) } + note
    }

    /** Echo for a whole-file write. */
    fun echoWrite(path: String, bytes: Int, text: String): String {
        val all = lines(text)
        return "Wrote $bytes bytes to $path. File now has ${all.size} lines.\n" + echo(all, 1, minOf(all.size, MAX_ECHO_LINES))
    }

    /** Echo for an append; [oldByteLength] is the file size before the append. */
    fun echoAppend(path: String, bytes: Int, oldByteLength: Long, newText: String): String {
        val all = lines(newText)
        val prefix = String(newText.toByteArray(Charsets.UTF_8).copyOf(oldByteLength.toInt().coerceAtLeast(0)), Charsets.UTF_8)
        val first = (1 + prefix.count { it == '\n' }).coerceIn(1, maxOf(1, all.size))
        return "Appended $bytes bytes to $path. File now has ${all.size} lines.\n" + echo(all, first, all.size)
    }

    /** Echo for a line-range edit. */
    fun echoLines(path: String, startLine: Int, endLine: Int, inserted: Int, updatedText: String): String {
        val all = lines(updatedText)
        return "Replaced lines $startLine-$endLine in $path with $inserted line${if (inserted == 1) "" else "s"}. File now has ${all.size} lines.\n" +
            echo(all, startLine, startLine + maxOf(inserted, 1) - 1)
    }

    /** Up to 5 workspace files with line counts, ≤ 300 chars, or null when there are none. */
    fun workspaceHint(root: File): String? {
        worldFilesHint(root)?.let { return it }
        val files = root.walkTopDown().maxDepth(3)
            .filter { it.isFile && !it.name.startsWith(".") && it.length() <= 1_048_576L }
            .take(5).toList()
        if (files.isEmpty()) return null
        val parts = files.map { "${it.relativeTo(root).path} (${lineCount(it)} lines)" }
        val line = "Workspace: ${parts.joinToString(", ")}. Fix files with search/replace tools; don't re-output them."
        return if (line.length <= 300) line else line.take(299) + "…"
    }

    /** When the chat has 3D world pages (`worlds/<name>.html`), steer the model to targeted edits instead of rewrites. */
    private fun worldFilesHint(root: File): String? {
        val worlds = File(root, "worlds").listFiles { f -> f.isFile && f.name.endsWith(".html", ignoreCase = true) && f.length() <= 1_048_576L }
            ?.sortedBy { it.name }?.take(2) ?: return null
        if (worlds.isEmpty()) return null
        val parts = worlds.joinToString(", ") { "worlds/${it.name} (${lineCount(it)} lines)" }
        val label = if (worlds.size == 1) "World file" else "World files"
        val line = "$label: $parts. For changes call search_workspace_file then replace_in_workspace_file; do not output the whole file."
        return if (line.length <= 300) line else line.take(299) + "…"
    }

    fun lineCount(file: File): Int = lines(file.readText(Charsets.UTF_8)).size

    // ---- tool definitions and dispatch (a pure function of config) ----

    fun tools(fileToolsEnabled: Boolean): List<AgentTool> = buildList {
        add(AgentTool(NativeChatToolRuntime.TOOL_LIST_WORKSPACE_FILES, "List files and folders in this chat's private workspace, with line counts.", mapOf("path" to "Optional workspace-relative directory")))
        add(AgentTool(
            NativeChatToolRuntime.TOOL_READ_WORKSPACE_FILE,
            "Read part of a UTF-8 file from this chat's workspace as numbered lines. Without a range you get the first $DEFAULT_READ_LINES lines; read big files in ranges.",
            mapOf("path" to "Workspace-relative file path", "start_line" to "Optional first line, 1-based", "end_line" to "Optional last line, inclusive"),
            listOf("path")
        ))
        add(AgentTool(
            TOOL_SEARCH,
            "Find lines in a workspace file (case-insensitive). Use it to locate code before reading or editing: search, then read around the hit, then replace.",
            mapOf("path" to "Workspace-relative file path", "query" to "Text to find", "max_results" to "Optional, default $DEFAULT_SEARCH_RESULTS", "regex" to "Optional true to treat query as a regex"),
            listOf("path", "query")
        ))
        if (!fileToolsEnabled) return@buildList
        add(AgentTool(
            TOOL_REPLACE,
            "Replace exact text in a workspace file; the preferred way to fix a file. Never rewrite the whole file to fix something. old_text must match exactly and be unique unless replace_all is true.",
            mapOf("path" to "Workspace-relative file path", "old_text" to "Exact text to replace, maximum 16 KiB", "new_text" to "Replacement text, maximum 16 KiB", "replace_all" to "Optional true to replace every match"),
            listOf("path", "old_text", "new_text")
        ))
        add(AgentTool(NativeChatToolRuntime.TOOL_WRITE_WORKSPACE_FILE, "Write a NEW small file (one bounded batch). To change an existing file use replace_in_workspace_file; never re-output a whole file.", mapOf("path" to "Workspace-relative file path", "content" to "Content, maximum 16 KiB"), listOf("path", "content")))
        add(AgentTool(NativeChatToolRuntime.TOOL_APPEND_WORKSPACE_FILE, "Append one bounded batch to a workspace file.", mapOf("path" to "Workspace-relative file path", "content" to "Content, maximum 16 KiB"), listOf("path", "content")))
        add(AgentTool(NativeChatToolRuntime.TOOL_EDIT_WORKSPACE_LINES, "Replace an exact line range (read the numbered lines first). Prefer replace_in_workspace_file for text edits.", mapOf("path" to "Workspace-relative file path", "start_line" to "First line, 1-based", "end_line" to "Last line, inclusive", "content" to "Replacement content"), listOf("path", "start_line", "end_line", "content")))
        add(AgentTool(NativeChatToolRuntime.TOOL_APPLY_WORKSPACE_PATCH, "Apply a bounded unified diff inside this chat workspace.", mapOf("patch" to "Unified diff, maximum 16 KiB"), listOf("patch")))
    }

    fun handles(toolName: String) = toolName == TOOL_SEARCH || toolName == TOOL_REPLACE

    fun execute(context: Context, chatId: Long, config: NativeChatToolConfig, name: String, args: Map<String, String>): String {
        require(config.toolsEnabled) { "Workspace file tools are disabled for this chat." }
        val path = args["path"].orEmpty()
        return when (name) {
            TOOL_SEARCH -> NativeChatWorkspaceSupport.search(
                context, chatId, path, args["query"].orEmpty(),
                args["max_results"]?.trim()?.toIntOrNull(), args["regex"]?.trim().equals("true", ignoreCase = true)
            )
            TOOL_REPLACE -> {
                require(config.fileToolsEnabled) { "Workspace file tools are disabled for this chat." }
                NativeChatWorkspaceSupport.replaceInFile(
                    context, chatId, path, args["old_text"].orEmpty(), args["new_text"].orEmpty(),
                    args["replace_all"]?.trim().equals("true", ignoreCase = true)
                )
            }
            else -> error("Unknown workspace tool $name")
        }
    }
}
