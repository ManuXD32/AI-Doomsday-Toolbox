package com.example.llamadroid.ui.ai.llama.workspace

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import java.io.File

/** IO side of the workspace file manager. Call these off the main thread. */
internal object WorkspaceExport {
    fun mimeOf(file: File): String =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase()) ?: "application/octet-stream"

    /** API 29+: copies into Download/AI-Doomsday-Toolbox/<chat>/ through MediaStore. Returns saved count. */
    @androidx.annotation.RequiresApi(Build.VERSION_CODES.Q)
    fun saveToDownloads(context: Context, items: List<WorkspaceExportItem>, chatTitle: String?, chatId: Long): Int {
        val base = WorkspacePaths.downloadFolder(chatTitle, chatId)
        var saved = 0
        items.forEach { item ->
            val relative = WorkspacePaths.sanitizeRelative(item.relativePath)
            val dir = relative.substringBeforeLast('/', "")
            val name = relative.substringAfterLast('/')
            val folder = if (dir.isBlank()) base else "$base/$dir"
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, mimeOf(item.file))
                put(MediaStore.Downloads.RELATIVE_PATH, folder)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return@forEach
            try {
                resolver.openOutputStream(uri)?.use { out -> item.file.inputStream().use { it.copyTo(out) } }
                    ?: error("No output stream")
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                saved++
            } catch (e: Exception) {
                runCatching { resolver.delete(uri, null, null) }
            }
        }
        return saved
    }

    /** API 26-28: copies into a folder chosen with ACTION_OPEN_DOCUMENT_TREE. Returns saved count. */
    fun saveToTree(context: Context, tree: Uri, items: List<WorkspaceExportItem>): Int {
        val resolver = context.contentResolver
        val rootDoc = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val dirs = HashMap<String, Uri>()
        fun dirFor(path: String): Uri? {
            if (path.isBlank()) return rootDoc
            dirs[path]?.let { return it }
            val parent = dirFor(path.substringBeforeLast('/', "")) ?: return null
            val created = DocumentsContract.createDocument(
                resolver, parent, DocumentsContract.Document.MIME_TYPE_DIR, path.substringAfterLast('/')
            ) ?: return null
            dirs[path] = created
            return created
        }
        var saved = 0
        items.forEach { item ->
            val relative = WorkspacePaths.sanitizeRelative(item.relativePath)
            val parent = dirFor(relative.substringBeforeLast('/', "")) ?: return@forEach
            val doc = DocumentsContract.createDocument(resolver, parent, mimeOf(item.file), relative.substringAfterLast('/'))
                ?: return@forEach
            try {
                resolver.openOutputStream(doc)?.use { out -> item.file.inputStream().use { it.copyTo(out) } }
                    ?: error("No output stream")
                saved++
            } catch (e: Exception) {
                runCatching { DocumentsContract.deleteDocument(resolver, doc) }
            }
        }
        return saved
    }

    val needsTreePicker: Boolean get() = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q

    fun openIntent(context: Context, file: File): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeOf(file))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    /** Deletes the selected workspace paths (recursively); returns how many top-level entries were removed. */
    fun delete(root: File, selected: Collection<String>): Int {
        val canonicalRoot = root.canonicalFile
        var removed = 0
        selected.forEach { rel ->
            if (rel.split('/').any { it == ".." }) return@forEach
            val target = File(canonicalRoot, rel.trimStart('/')).canonicalFile
            if (!WorkspacePaths.isStrictlyInside(canonicalRoot, target)) return@forEach
            if (target.deleteRecursively()) removed++
        }
        return removed
    }
}
