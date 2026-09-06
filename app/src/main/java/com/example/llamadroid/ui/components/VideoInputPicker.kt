package com.example.llamadroid.ui.components

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.StatFs
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import com.example.llamadroid.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.UUID

/** Media is copied once off the UI thread, so native tools and restored drafts can read it. */
@Composable
internal fun VideoInputPicker(
    label: String,
    paths: List<String>,
    mimeTypes: Array<String>,
    multiple: Boolean,
    directorySelection: Boolean = false,
    enabled: Boolean = true,
    onPathsChange: (List<String>) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val currentPaths by rememberUpdatedState(paths)
    val onChange by rememberUpdatedState(onPathsChange)
    var copying by remember { mutableStateOf(false) }
    var error by rememberSaveable { mutableStateOf(false) }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) scope.launch {
            copying = true
            error = false
            val imported = mutableListOf<File>()
            try {
                (if (multiple) uris else uris.take(1)).forEach { uri ->
                    imported += importVideoInput(context, uri)
                }
                onChange((if (multiple) currentPaths else emptyList()) + imported.map { it.absolutePath })
            } catch (cancelled: CancellationException) {
                // These files are new, unreferenced copies owned by this incomplete selection.
                imported.forEach(File::delete)
                throw cancelled
            } catch (_: Exception) {
                imported.forEach(File::delete)
                error = true
            } finally {
                copying = false
            }
        }
    }
    val directoryPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) scope.launch {
            copying = true
            error = false
            var imported: File? = null
            try {
                imported = importVideoFrameDirectory(context, uri)
                onChange((if (multiple) currentPaths else emptyList()) + imported.absolutePath)
            } catch (cancelled: CancellationException) {
                imported?.deleteRecursively()
                throw cancelled
            } catch (_: Exception) {
                imported?.deleteRecursively()
                error = true
            } finally {
                copying = false
            }
        }
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        paths.forEachIndexed { index, path ->
            Column(Modifier.fillMaxWidth()) {
                SelectionContainer {
                    Text(File(path).name, style = MaterialTheme.typography.bodyMedium)
                }
                TextButton(
                    enabled = enabled && !copying,
                    onClick = { onChange(currentPaths.filterIndexed { i, _ -> i != index }) }
                ) { Text(stringResource(R.string.video_controls_clear)) }
            }
        }
        OutlinedButton(
            onClick = {
                if (directorySelection) directoryPicker.launch(null) else filePicker.launch(mimeTypes)
            },
            enabled = enabled && !copying,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                stringResource(
                    when {
                        directorySelection && multiple -> R.string.video_input_add_frame_directories
                        directorySelection -> R.string.video_input_choose_frame_directory
                        multiple -> R.string.video_input_add_files
                        else -> R.string.video_input_choose_file
                    }
                )
            )
        }
        if (copying) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(stringResource(R.string.video_input_copying), style = MaterialTheme.typography.bodySmall)
        }
        if (error) Text(
            stringResource(R.string.video_input_import_failed),
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall
        )
    }
}

/**
 * Stage one shared image without decoding its pixels on the caller thread. The copied bytes are
 * kept in their original aspect ratio; only bounds are read so the editor can show the source
 * dimensions without allocating a full bitmap.
 */
internal data class ImportedVideoImage(
    val file: File,
    val width: Int,
    val height: Int
)

internal suspend fun importVideoImage(context: Context, uri: Uri): ImportedVideoImage =
    withContext(Dispatchers.IO) {
        val file = importVideoInput(context, uri)
        try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                throw IOException("Unreadable image")
            }
            ImportedVideoImage(file, bounds.outWidth, bounds.outHeight)
        } catch (error: Throwable) {
            file.delete()
            throw error
        }
    }

internal suspend fun importVideoInput(context: Context, uri: Uri): File = withContext(Dispatchers.IO) {
    val root = File(context.filesDir, "video_inputs").apply { mkdirs() }
    val document = DocumentFile.fromSingleUri(context, uri)
    val safeName = document?.name.orEmpty().substringAfterLast('/').substringAfterLast('\\')
        .replace(Regex("[^\\p{L}\\p{N}._ -]"), "_").takeLast(160).ifBlank { "input" }
    val final = File(root, "${UUID.randomUUID()}_$safeName")
    val partial = File(root, "${final.name}.part")
    val reserve = 32L * 1024L * 1024L
    try {
        if ((document?.length() ?: 0L) > StatFs(root.path).availableBytes - reserve) throw IOException("Storage")
        context.contentResolver.openInputStream(uri)?.use { input ->
            partial.outputStream().use { output ->
                val buffer = ByteArray(256 * 1024)
                var copiedSinceCheck = 0L
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                    copiedSinceCheck += count
                    if (copiedSinceCheck >= 4L * 1024L * 1024L) {
                        if (StatFs(root.path).availableBytes < reserve) throw IOException("Storage")
                        copiedSinceCheck = 0L
                    }
                }
            }
        } ?: throw IOException("Unreadable input")
        if (partial.length() == 0L || !partial.renameTo(final)) throw IOException("Incomplete input")
        final
    } finally {
        // Only remove the temporary copy; existing selected inputs and source documents are untouched.
        partial.delete()
    }
}

/**
 * Native `--control-video` and `--ref-video` consume directories of image
 * frames, rather than movie containers. Copy the selected tree into a private
 * flat directory with deterministic lexicographic names so the native loader
 * sees the same frame order on every run.
 */
internal suspend fun importVideoFrameDirectory(context: Context, treeUri: Uri): File = withContext(Dispatchers.IO) {
    val root = File(context.filesDir, "video_inputs").apply { mkdirs() }
    val source = DocumentFile.fromTreeUri(context, treeUri)
        ?: throw IOException("Unreadable frame directory")
    val frames = source.listFiles()
        .filter { it.isFile && isVideoFrameDocument(it) }
        .sortedWith(compareBy<DocumentFile, String>(String.CASE_INSENSITIVE_ORDER) { it.name.orEmpty() }
            .thenBy { it.name.orEmpty() })
    if (frames.isEmpty()) throw IOException("No image frames found")

    val directoryName = source.name.orEmpty()
        .substringAfterLast('/')
        .substringAfterLast('\\')
        .replace(Regex("[^\\p{L}\\p{N}._ -]"), "_")
        .takeLast(120)
        .ifBlank { "frames" }
    val final = File(root, "${UUID.randomUUID()}_$directoryName")
    val partial = File(root, "${final.name}.part")
    val reserve = 32L * 1024L * 1024L
    try {
        partial.mkdirs()
        frames.forEachIndexed { index, document ->
            currentCoroutineContext().ensureActive()
            if (StatFs(root.path).availableBytes < reserve) throw IOException("Storage")
            val originalName = document.name.orEmpty()
            val sanitizedName = originalName
                .substringAfterLast('/')
                .substringAfterLast('\\')
                .replace(Regex("[^\\p{L}\\p{N}._ -]"), "_")
            val safeName = if (sanitizedName.isBlank()) {
                "frame.${imageExtension(document)}"
            } else {
                val extension = sanitizedName.substringAfterLast('.', "").lowercase(Locale.ROOT)
                if (extension in VIDEO_FRAME_EXTENSIONS) sanitizedName
                else "$sanitizedName.${imageExtension(document)}"
            }
            val destination = File(partial, "${index.toString().padStart(8, '0')}_$safeName")
            val expectedLength = document.length()
            if (expectedLength > 0L && expectedLength > StatFs(root.path).availableBytes - reserve) {
                throw IOException("Storage")
            }
            var copiedSinceCheck = 0L
            context.contentResolver.openInputStream(document.uri)?.use { input ->
                destination.outputStream().use { output ->
                    val buffer = ByteArray(256 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        copiedSinceCheck += count
                        if (copiedSinceCheck >= 4L * 1024L * 1024L) {
                            if (StatFs(root.path).availableBytes < reserve) throw IOException("Storage")
                            copiedSinceCheck = 0L
                        }
                    }
                }
            } ?: throw IOException("Unreadable frame")
            if (destination.length() == 0L) throw IOException("Empty frame")
        }
        if (!partial.renameTo(final)) throw IOException("Incomplete frame directory")
        final
    } finally {
        partial.deleteRecursively()
    }
}

private val VIDEO_FRAME_EXTENSIONS = setOf("png", "jpg", "jpeg", "webp", "bmp")
private val VIDEO_FRAME_MIME_TYPES = setOf("image/png", "image/jpeg", "image/jpg", "image/webp", "image/bmp")

private fun isVideoFrameDocument(document: DocumentFile): Boolean {
    val mime = document.type.orEmpty().lowercase(Locale.ROOT)
    if (mime in VIDEO_FRAME_MIME_TYPES) return true
    val extension = document.name.orEmpty().substringAfterLast('.', "").lowercase(Locale.ROOT)
    return extension in VIDEO_FRAME_EXTENSIONS
}

private fun imageExtension(document: DocumentFile): String {
    val extension = document.name.orEmpty().substringAfterLast('.', "").lowercase(Locale.ROOT)
    if (extension in VIDEO_FRAME_EXTENSIONS) return extension
    return when (document.type.orEmpty().lowercase(Locale.ROOT)) {
        "image/jpeg" -> "jpg"
        "image/webp" -> "webp"
        "image/bmp" -> "bmp"
        else -> "png"
    }
}
