package com.example.llamadroid.ui.ai.llama.workspace

import java.io.File

/** One file to export: the source and its path relative to the export folder (forward slashes). */
internal data class WorkspaceExportItem(val file: File, val relativePath: String)

/** Pure path helpers for the chat workspace file manager (guard, naming, selection expansion). */
internal object WorkspacePaths {
    const val DOWNLOAD_ROOT = "Download/AI-Doomsday-Toolbox"
    private const val MAX_NAME = 80
    private const val MAX_FILES = 2000

    /** True when [target] is [root] itself or inside it. Both must already be canonical. */
    fun isInside(root: File, target: File): Boolean =
        target.path == root.path || target.path.startsWith(root.path + File.separator)

    /** Like [isInside] but never the root itself (used for delete). */
    fun isStrictlyInside(root: File, target: File): Boolean =
        target.path != root.path && target.path.startsWith(root.path + File.separator)

    /** Makes a single path segment safe for MediaStore/SAF; never blank, never `.`/`..`. */
    fun sanitizeName(name: String, fallback: String = "file"): String {
        val cleaned = name.map { if (it.isISOControl() || it in "\\/:*?\"<>|") '_' else it }
            .joinToString("")
            .trim()
            .trim('.')
            .take(MAX_NAME)
            .trim()
        return cleaned.ifBlank { fallback }
    }

    /** `Download/AI-Doomsday-Toolbox/<chat title or chat-ID>` (RELATIVE_PATH without trailing slash). */
    fun downloadFolder(chatTitle: String?, chatId: Long): String =
        "$DOWNLOAD_ROOT/" + sanitizeName(chatTitle.orEmpty(), "chat-$chatId")

    /** Sanitizes each segment of a relative path and joins with `/`. */
    fun sanitizeRelative(path: String): String =
        path.split('/', '\\').filter { it.isNotBlank() && it != "." && it != ".." }
            .joinToString("/") { sanitizeName(it) }

    fun join(directory: String, name: String): String =
        listOf(directory, name).filter { it.isNotBlank() }.joinToString("/")

    /**
     * Expands [selected] workspace-relative paths into files (folders recursively), skipping
     * anything that resolves outside [root] and capping the total. Paths stay relative to
     * the parent of each selected entry so a selected folder keeps its name.
     */
    fun expandSelection(root: File, selected: Collection<String>): List<WorkspaceExportItem> {
        val canonicalRoot = root.canonicalFile
        val out = ArrayList<WorkspaceExportItem>()
        fun add(file: File, prefix: String) {
            if (out.size >= MAX_FILES) return
            val canonical = file.canonicalFile
            if (!isStrictlyInside(canonicalRoot, canonical)) return
            if (canonical.isDirectory) {
                canonical.listFiles().orEmpty().sortedBy { it.name }.forEach {
                    add(File(canonical, it.name), join(prefix, canonical.name))
                }
            } else if (canonical.isFile) {
                out += WorkspaceExportItem(canonical, join(prefix, canonical.name))
            }
        }
        selected.forEach { rel ->
            if (rel.split('/').any { it == ".." }) return@forEach
            add(File(canonicalRoot, rel.trimStart('/')), "")
        }
        return out
    }
}
