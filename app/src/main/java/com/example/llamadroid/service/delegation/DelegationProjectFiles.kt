package com.example.llamadroid.service.delegation

import android.content.Context
import android.webkit.MimeTypeMap
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.data.model.LlamaMessageAttachmentEntity
import com.example.llamadroid.data.model.LlamaMessageAttachmentSource
import com.example.llamadroid.harness.HarnessAppRuntime
import com.example.llamadroid.service.AgentLocalWorkspaceSupport
import com.example.llamadroid.service.NativeChatWorkspaceSupport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

/**
 * Pure, read-only file access under one project root for `deepseek_project_files`. Every path is
 * project-relative and must stay inside the canonical root (symlinks that leave it are rejected).
 */
object DelegationProjectFiles {
    const val MAX_DEPTH = 3
    const val MAX_ENTRIES = 300
    const val PAGE_CHARS = 8_000
    const val MAX_HITS = 50
    const val MAX_SEARCH_FILE_BYTES = 1_000_000L
    const val MAX_SEARCH_FILES = 5_000
    private const val ESCAPED = "\u0000escaped"
    private const val BINARY_SAMPLE = 4_096
    private const val HIT_LINE_CHARS = 160
    val SKIPPED_DIRS = setOf(".git", "node_modules", ".dsh", "__pycache__", ".venv")

    /** The canonical file for [relative] inside [root], or null when it would escape the root. */
    fun resolve(root: File, relative: String?): File? {
        val canonicalRoot = root.canonicalFile
        val clean = relative.orEmpty().trim().trim('/').ifEmpty { return canonicalRoot }
        val target = File(canonicalRoot, clean).canonicalFile
        return target.takeIf { it == canonicalRoot || it.path.startsWith(canonicalRoot.path + File.separator) }
    }

    private fun relativeTo(root: File, file: File): String = file.relativeTo(root.canonicalFile).path.replace(File.separatorChar, '/')

    private fun children(root: File, dir: File): List<File> =
        dir.listFiles().orEmpty()
            .filter { child -> !(child.isDirectory && child.name in SKIPPED_DIRS) }
            .filter { child -> safeRel(root, child) != ESCAPED }
            .sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase(Locale.ROOT) }))

    /** Relative path of [child] under [root] without resolving symlinks, or a never-matching marker. */
    private fun safeRel(root: File, child: File): String {
        val canonicalChild = child.canonicalFile
        val canonicalRoot = root.canonicalFile
        return if (canonicalChild == canonicalRoot || canonicalChild.path.startsWith(canonicalRoot.path + File.separator))
            canonicalChild.relativeTo(canonicalRoot).path else ESCAPED
    }

    fun isBinary(file: File): Boolean = try {
        file.inputStream().use { input ->
            val sample = ByteArray(BINARY_SAMPLE)
            val n = input.read(sample)
            (0 until maxOf(n, 0)).any { sample[it].toInt() == 0 }
        }
    } catch (_: Exception) {
        true
    }

    private fun sizeText(bytes: Long): String = when {
        bytes < 1_024 -> "$bytes B"
        bytes < 1_024 * 1_024 -> "${bytes / 1_024} KB"
        else -> "${bytes / (1_024 * 1_024)} MB"
    }

    /** Tree of [relative] up to [MAX_DEPTH] levels and [MAX_ENTRIES] entries, with file sizes. */
    fun list(root: File, relative: String?): String {
        val dir = resolve(root, relative) ?: return "tool_error: path is outside the project."
        if (!dir.exists()) return "tool_error: '${relative.orEmpty()}' does not exist in the project."
        if (!dir.isDirectory) return "'${relative.orEmpty()}' is a file (${sizeText(dir.length())}); use action=read."
        val lines = ArrayList<String>()
        var truncated = false
        fun walk(current: File, depth: Int) {
            for (child in children(root, current)) {
                if (lines.size >= MAX_ENTRIES) { truncated = true; return }
                val indent = "  ".repeat(depth)
                if (child.isDirectory) {
                    lines += "$indent${child.name}/"
                    if (depth + 1 < MAX_DEPTH) walk(child.canonicalFile, depth + 1)
                } else lines += "$indent${child.name} (${sizeText(child.length())})"
                if (truncated) return
            }
        }
        walk(dir, 0)
        if (lines.isEmpty()) return "The folder is empty (hidden: ${SKIPPED_DIRS.joinToString()})."
        val header = "Project files under /${relativeTo(root, dir).ifEmpty { "" }} (depth $MAX_DEPTH, skipping ${SKIPPED_DIRS.joinToString()}):"
        return header + "\n" + lines.joinToString("\n") + if (truncated) "\n... truncated at $MAX_ENTRIES entries; list a subfolder with path." else ""
    }

    /** One [PAGE_CHARS] page of a text file starting at char [offset]; binary files are refused. */
    fun read(root: File, relative: String?, offset: Int): String {
        val file = resolve(root, relative) ?: return "tool_error: path is outside the project."
        if (relative.isNullOrBlank()) return "tool_error: path is required for read."
        if (!file.exists()) return "tool_error: '$relative' does not exist in the project."
        if (file.isDirectory) return "'$relative' is a folder; use action=list."
        if (isBinary(file)) return "'$relative' is a binary file (${sizeText(file.length())}); it cannot be shown as text. Use action=attach to copy it into this chat's files."
        val start = offset.coerceAtLeast(0)
        val buffer = CharArray(PAGE_CHARS + 1)
        val read = file.bufferedReader(Charsets.UTF_8).use { reader ->
            var skipped = 0L
            while (skipped < start) {
                val s = reader.skip(start - skipped)
                if (s <= 0) break
                skipped += s
            }
            if (skipped < start) return "tool_error: offset $start is past the end of the file."
            var total = 0
            while (total < buffer.size) {
                val n = reader.read(buffer, total, buffer.size - total)
                if (n < 0) break
                total += n
            }
            total
        }
        val shown = minOf(read, PAGE_CHARS)
        val more = read > PAGE_CHARS
        val tail = if (more) "\n[more: call read again with offset=${start + shown}]" else "\n[end of file]"
        return "$relative (chars $start-${start + shown})\n" + String(buffer, 0, shown) + tail
    }

    /** Filename and text matches (case-insensitive) under [relative], at most [MAX_HITS]. */
    fun search(root: File, relative: String?, query: String?): String {
        val q = query?.trim().orEmpty()
        if (q.isEmpty()) return "tool_error: query is required for search."
        val base = resolve(root, relative) ?: return "tool_error: path is outside the project."
        if (!base.isDirectory) return "tool_error: '${relative.orEmpty()}' is not a folder."
        val needle = q.lowercase(Locale.ROOT)
        val hits = ArrayList<String>()
        var visited = 0
        var capped = false
        fun walk(dir: File) {
            for (child in children(root, dir)) {
                if (hits.size >= MAX_HITS || visited >= MAX_SEARCH_FILES) { capped = true; return }
                val rel = relativeTo(root, child.canonicalFile)
                if (child.isDirectory) { walk(child.canonicalFile); if (capped) return; continue }
                visited++
                if (child.name.lowercase(Locale.ROOT).contains(needle)) hits += "$rel (file name)"
                if (hits.size >= MAX_HITS) { capped = true; return }
                if (child.length() > MAX_SEARCH_FILE_BYTES || child.length() == 0L || isBinary(child)) continue
                try {
                    child.bufferedReader(Charsets.UTF_8).useLines { lines ->
                        for ((index, line) in lines.withIndex()) {
                            if (line.lowercase(Locale.ROOT).contains(needle)) {
                                hits += "$rel:${index + 1}: ${line.trim().take(HIT_LINE_CHARS)}"
                                if (hits.size >= MAX_HITS) { capped = true; break }
                            }
                        }
                    }
                } catch (_: Exception) {
                    // Unreadable or badly encoded file: skip.
                }
            }
        }
        walk(base)
        if (hits.isEmpty()) return "No matches for \"${q.take(60)}\"."
        return "Matches for \"${q.take(60)}\" (${hits.size}${if (capped) "+, capped" else ""}):\n" + hits.joinToString("\n")
    }

    // ---- Android-backed entry point ------------------------------------------------------------

    private class Resolved(val root: File?, val message: String?, val taskId: Long?)

    private suspend fun resolveRoot(context: Context, chatId: Long, projectId: String?): Resolved {
        val db = AppDatabase.getDatabase(context)
        val task = db.nativeChatDelegateTaskDao().getLatestWithWorkspace(chatId)
        val chat = db.llamaChatDao().getChatById(chatId)
        val configured = chat?.let { NativeChatDelegationConfig.fromParams(parseDelegationParams(it.apiParams)).runtimeId }
        val runtimeId = task?.runtimeId?.takeIf { it.isNotBlank() } ?: configured?.takeIf { it.isNotBlank() }
            ?: return Resolved(null, "No DeepSeek runtime is configured for this chat.", task?.id)
        val workspaceId = projectId?.takeIf { it.isNotBlank() } ?: task?.workspaceId?.takeIf { it.isNotBlank() }
            ?: return Resolved(null, "This chat has no DeepSeek project yet. deepseek_task_start creates one.", task?.id)
        val runtime = HarnessAppRuntime.forRuntime(context, runtimeId)
        val workspace = runtime.dao.workspace(workspaceId)
            ?: return Resolved(null, "Unknown project id '$workspaceId'. Call deepseek_projects to list the valid ids.", task?.id)
        if (workspace.backend == "REMOTE_SSH") return Resolved(null, "Project files are not available for remote projects.", task?.id)
        val root = AgentLocalWorkspaceSupport.rootPathForProject(runtime.applicationContext, workspace.projectFolder)
        if (!root.isDirectory) return Resolved(null, "The project folder does not exist on this device.", task?.id)
        return Resolved(root, null, task?.id)
    }

    suspend fun execute(context: Context, chatId: Long, args: Map<String, String>): String {
        val app = context.applicationContext
        val action = DelegationToolArgs.parseEnum(
            DelegationToolArgs.arg(args, "action"), setOf("list", "read", "search", "attach"), "list"
        )
        val path = DelegationToolArgs.boundedText(DelegationToolArgs.arg(args, "path"), 400)
        val query = DelegationToolArgs.boundedText(DelegationToolArgs.arg(args, "query"), 200)
        val offset = DelegationToolArgs.coerceInt(DelegationToolArgs.arg(args, "offset")) ?: 0
        val project = DelegationToolArgs.boundedText(DelegationToolArgs.arg(args, "project"), 120)
        return withContext(Dispatchers.IO) {
            try {
                val resolved = resolveRoot(app, chatId, project)
                val root = resolved.root ?: return@withContext "tool_error: ${resolved.message}"
                when (action) {
                    "read" -> read(root, path, offset)
                    "search" -> search(root, path, query)
                    "attach" -> attach(app, root, chatId, resolved.taskId, path)
                    else -> list(root, path)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                "tool_error: ${e.message ?: e.javaClass.simpleName}"
            }
        }
    }

    /** Copies one project file into the chat's results folder and records a DEEPSEEK attachment row. */
    private suspend fun attach(context: Context, root: File, chatId: Long, taskId: Long?, relative: String?): String {
        if (relative.isNullOrBlank()) return "tool_error: path is required for attach."
        val file = resolve(root, relative) ?: return "tool_error: path is outside the project."
        if (!file.isFile) return "tool_error: '$relative' is not a file in the project."
        if (file.length() > DelegationFileRefs.MAX_EXPORT_BYTES) return "tool_error: '$relative' is too large to attach."
        val dir = NativeChatWorkspaceSupport.subDir(context, chatId, NativeChatWorkspaceSupport.RESULTS_DIR)
        var target = File(dir, DelegationFileRefs.resultFileName(taskId ?: 0L, DelegationFileRefs.displayNameOf(relative)))
        var n = 2
        while (target.exists()) {
            val stem = target.nameWithoutExtension.replace(Regex("-\\d+$"), "")
            target = File(dir, "$stem-$n" + target.extension.let { if (it.isEmpty()) "" else ".$it" })
            n++
        }
        file.copyTo(target)
        val name = DelegationFileRefs.displayNameOf(relative)
        val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        val id = AppDatabase.getDatabase(context).llamaMessageAttachmentDao().insert(
            LlamaMessageAttachmentEntity(
                chatId = chatId, path = target.absolutePath, displayName = name,
                mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream",
                sizeBytes = target.length(), source = LlamaMessageAttachmentSource.DEEPSEEK, taskId = taskId
            )
        )
        return "Attached '$relative' to this chat as f$id ($name, ${sizeText(target.length())})."
    }

    fun schema(): String {
        fun prop(type: String, description: String) = JSONObject().put("type", type).put("description", description)
        return JSONObject().put("type", "object")
            .put(
                "properties", JSONObject()
                    .put("action", JSONObject().put("type", "string").put("enum", JSONArray(listOf("list", "read", "search", "attach")))
                        .put("description", "list = file tree, read = text page, search = name/text matches, attach = copy a file into this chat"))
                    .put("path", prop("string", "Project-relative file or folder (default: project root)"))
                    .put("query", prop("string", "Text or file name to find (search)"))
                    .put("offset", prop("integer", "Character offset for read (use the next offset from the previous page)"))
                    .put("project", prop("string", "Project id from deepseek_projects (default: this chat's project)"))
            )
            .put("required", JSONArray(listOf("action")))
            .toString()
    }
}
