package com.example.llamadroid.quadtrix

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.File

data class QuadtrixWorkspaceSelection(
    val uri: String,
    val directPath: String?
)

object QuadtrixWorkspaceManager {
    const val TOKENIZER_FILE = "qwen3_tokenizer.json"
    private const val TOKENIZER_ASSET = "quadtrix/qwen3_tokenizer.json"
    private const val TOKENIZER_MIN_BYTES = 1_000_000L
    private val requiredFolders = listOf(
        QuadtrixPaths.MODELS,
        QuadtrixPaths.PROFILES,
        QuadtrixPaths.DATA,
        QuadtrixPaths.LOGS,
        QuadtrixPaths.EXPORTS,
        QuadtrixPaths.TOKEN_CACHE,
        "checkpoints",
        "workers"
    )

    fun configureWorkspace(context: Context, treeUri: Uri): Result<QuadtrixWorkspaceSelection> = runCatching {
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                treeUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }

        setupSafTree(context, treeUri)
        val runtimeRoot = rootForRuntime(context, null)
        QuadtrixWorkspaceSelection(treeUri.toString(), runtimeRoot.absolutePath)
    }

    fun rootForRuntime(context: Context, directPath: String?): File {
        val preferred = directPath
            ?.takeIf { it.isNotBlank() && isManagedRuntimePath(context, it) }
            ?.let(::File)
        val root = preferred ?: managedRuntimeRoot(context)
        return runCatching {
            setupFileRoot(context, root)
            root
        }.getOrElse {
            managedRuntimeRoot(context).also { fallback ->
                setupFileRoot(context, fallback)
            }
        }
    }

    fun setupFileRoot(context: Context, root: File) {
        root.mkdirs()
        requiredFolders.forEach { File(root, it).mkdirs() }
        copyTokenizerToFile(context, File(root, TOKENIZER_FILE))
    }

    private fun managedRuntimeRoot(context: Context): File {
        val external = context.getExternalFilesDir(null)
        return if (external != null) {
            File(external, QuadtrixPaths.ROOT)
        } else {
            File(context.filesDir, QuadtrixPaths.ROOT)
        }
    }

    private fun isManagedRuntimePath(context: Context, path: String): Boolean {
        val candidates = listOfNotNull(context.filesDir, context.getExternalFilesDir(null))
            .mapNotNull { runCatching { it.canonicalPath }.getOrNull() }
        val canonical = runCatching { File(path).canonicalPath }.getOrNull() ?: return false
        return candidates.any { canonical.startsWith(it) }
    }

    private fun setupSafTree(context: Context, treeUri: Uri) {
        val root = DocumentFile.fromTreeUri(context, treeUri) ?: return
        requiredFolders.forEach { name ->
            if (root.findFile(name) == null) {
                root.createDirectory(name)
            }
        }
        copyTokenizerToDocument(context, root)
    }

    private fun copyTokenizerToFile(context: Context, target: File) {
        if (target.exists() && target.length() > TOKENIZER_MIN_BYTES) return
        target.parentFile?.mkdirs()
        context.assets.open(TOKENIZER_ASSET).use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        }
    }

    private fun copyTokenizerToDocument(context: Context, root: DocumentFile) {
        val existing = root.findFile(TOKENIZER_FILE)
        if (existing != null && existing.length() > TOKENIZER_MIN_BYTES) return
        val target = existing ?: root.createFile("application/json", TOKENIZER_FILE) ?: return
        val resolver = context.contentResolver
        context.assets.open(TOKENIZER_ASSET).use { input ->
            resolver.openOutputStream(target.uri, "wt")?.use { output -> input.copyTo(output) }
        }
    }
}
