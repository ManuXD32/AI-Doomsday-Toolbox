package com.example.llamadroid.service.delegation

import android.content.Context
import android.webkit.MimeTypeMap
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.data.model.LlamaMessageAttachmentEntity
import com.example.llamadroid.data.model.LlamaMessageAttachmentSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

/** A chat file resolved for `copyInputs`. */
data class DelegationResolvedInput(val ref: String, val displayName: String, val file: File)

data class DelegationRefResolution(
    val inputs: List<DelegationResolvedInput>,
    /** References that do not exist in this chat (or whose file is gone). */
    val unknown: List<String>
)

/** A project file copied into chat storage. */
data class DelegationExportedFile(val path: String, val name: String, val attachmentId: Long, val sizeBytes: Long)

data class DelegationExportResult(val files: List<DelegationExportedFile>, val notes: List<String>)

/** Pure reference parsing and naming. */
object DelegationFileRefs {
    private val pattern = Regex("^(f|img)(\\d{1,12})$")
    const val MAX_EXPORT_BYTES = 256L * 1024 * 1024
    const val MAX_RESULT_FILES = 20

    /** (`f`|`img`, id) or null for anything else. */
    fun parse(ref: String): Pair<String, Long>? {
        val m = pattern.matchEntire(ref.trim().lowercase(Locale.ROOT)) ?: return null
        val id = m.groupValues[2].toLongOrNull() ?: return null
        return m.groupValues[1] to id
    }

    /** `result_<taskId>_<name>` with a conservative alphabet and bounded length. */
    fun resultFileName(taskId: Long, rawName: String): String {
        val base = rawName.substringAfterLast('/').substringAfterLast('\\').trim()
        val cleaned = base.replace(Regex("[^A-Za-z0-9._ -]"), "_").replace(Regex("\\s+"), " ").trim().trim('.')
            .ifEmpty { "file" }
        val dot = cleaned.lastIndexOf('.')
        val ext = if (dot > 0 && cleaned.length - dot <= 12) cleaned.substring(dot) else ""
        val stem = cleaned.removeSuffix(ext).take(80)
        return "result_${taskId}_$stem$ext"
    }

    fun displayNameOf(path: String): String = path.substringAfterLast('/').ifBlank { "file" }
}

/** Chat files into the project and project files into chat storage. */
class DelegationFileBridge(context: Context) {
    private val appContext = context.applicationContext
    private val db get() = AppDatabase.getDatabase(appContext)

    suspend fun resolve(chatId: Long, refs: List<String>): DelegationRefResolution = withContext(Dispatchers.IO) {
        val inputs = mutableListOf<DelegationResolvedInput>()
        val unknown = mutableListOf<String>()
        var messages: List<com.example.llamadroid.data.model.LlamaMessageEntity>? = null
        for (ref in refs.distinct()) {
            val parsed = DelegationFileRefs.parse(ref)
            if (parsed == null) { unknown += ref; continue }
            val (kind, id) = parsed
            if (kind == "f") {
                val row = db.llamaMessageAttachmentDao().getById(id)
                val file = row?.takeIf { it.chatId == chatId }?.let { File(it.path) }
                if (row != null && file != null && file.isFile) inputs += DelegationResolvedInput(ref, row.displayName, file)
                else unknown += ref
            } else {
                val all = messages ?: db.llamaMessageDao().getMessagesOnce(chatId).also { messages = it }
                val path = all.firstOrNull { it.id == id }?.imagePath
                val file = path?.takeIf { it.isNotBlank() }?.let { File(it) }
                if (file != null && file.isFile) inputs += DelegationResolvedInput(ref, file.name, file)
                else unknown += ref
            }
        }
        DelegationRefResolution(inputs, unknown)
    }

    /**
     * Copies [paths] from the project into `files/llama_chat_files/<chatId>/` and inserts pending
     * DEEPSEEK attachment rows. Failures are skipped and reported in [DelegationExportResult.notes].
     */
    suspend fun exportResults(
        driver: DelegateHarnessDriver,
        sessionId: String,
        chatId: Long,
        taskId: Long,
        paths: List<String>
    ): DelegationExportResult {
        val dir = File(appContext.filesDir, "llama_chat_files/$chatId")
        val files = mutableListOf<DelegationExportedFile>()
        val notes = mutableListOf<String>()
        for (path in paths.distinct().take(DelegationFileRefs.MAX_RESULT_FILES)) {
            try {
                var target = File(dir, DelegationFileRefs.resultFileName(taskId, DelegationFileRefs.displayNameOf(path)))
                var n = 2
                while (target.exists()) {
                    val stem = target.nameWithoutExtension.replace(Regex("-\\d+$"), "")
                    target = File(dir, "$stem-$n" + target.extension.let { if (it.isEmpty()) "" else ".$it" })
                    n++
                }
                val size = driver.exportFile(sessionId, path, target, DelegationFileRefs.MAX_EXPORT_BYTES)
                val name = DelegationFileRefs.displayNameOf(path)
                val id = db.llamaMessageAttachmentDao().insert(
                    LlamaMessageAttachmentEntity(
                        chatId = chatId, path = target.absolutePath, displayName = name,
                        mimeType = mimeOf(name), sizeBytes = size,
                        source = LlamaMessageAttachmentSource.DEEPSEEK, taskId = taskId
                    )
                )
                files += DelegationExportedFile(path, name, id, size)
            } catch (e: CancellationException) {
                throw e
            } catch (e: DelegationHarnessException) {
                notes += "$path: ${e.code}"
            } catch (e: Exception) {
                notes += "$path: ${e.javaClass.simpleName}"
            }
        }
        return DelegationExportResult(files, notes)
    }

    fun resultFilesJson(files: List<DelegationExportedFile>): String = JSONArray().also { array ->
        files.forEach { array.put(JSONObject().put("path", it.path).put("name", it.name).put("attachmentId", it.attachmentId)) }
    }.toString()

    private fun mimeOf(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
    }
}
