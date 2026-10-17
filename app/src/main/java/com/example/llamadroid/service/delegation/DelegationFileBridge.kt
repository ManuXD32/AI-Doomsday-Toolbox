package com.example.llamadroid.service.delegation

import android.content.Context
import android.webkit.MimeTypeMap
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.data.model.LlamaMessageAttachmentEntity
import com.example.llamadroid.data.model.LlamaMessageAttachmentSource
import com.example.llamadroid.service.NativeChatWorkspaceSupport
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

/** A chat file copied into the project's `inputs/` folder. */
data class DelegationCopiedInput(val ref: String, val displayName: String, val path: String, val file: File)

/** Result of [DelegationFileBridge.copyPending]: [handledRefs] are to be cleared from the task options. */
data class DelegationPendingCopy(
    val copied: List<DelegationCopiedInput>,
    val unknown: List<String>,
    val handledRefs: List<String>
) {
    companion object { val NONE = DelegationPendingCopy(emptyList(), emptyList(), emptyList()) }
}

/** Pure bookkeeping of `DelegationTaskOptions.pendingAttachmentRefs`. */
object DelegationPendingRefs {
    /** [options] without the [handled] refs; refs added meanwhile (by a follow-up) stay pending. */
    fun cleared(options: DelegationTaskOptions, handled: Collection<String>): DelegationTaskOptions {
        if (handled.isEmpty() || options.pendingAttachmentRefs.isEmpty()) return options
        val done = handled.toSet()
        return options.copy(pendingAttachmentRefs = options.pendingAttachmentRefs.filterNot { it in done })
    }

    /** [options] with [refs] appended (order kept, duplicates dropped). */
    fun added(options: DelegationTaskOptions, refs: List<String>): DelegationTaskOptions =
        options.copy(pendingAttachmentRefs = (options.pendingAttachmentRefs + refs).distinct())
}

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
     * Copies every ref in [options]`.pendingAttachmentRefs` into the project's `inputs/` folder through
     * the session [sessionId]. Unknown refs are reported, not fatal. The caller clears
     * [DelegationPendingCopy.handledRefs] from the task row with [DelegationPendingRefs.cleared].
     */
    suspend fun copyPending(
        driver: DelegateHarnessDriver,
        sessionId: String,
        chatId: Long,
        options: DelegationTaskOptions
    ): DelegationPendingCopy {
        val refs = options.pendingAttachmentRefs.distinct()
        if (refs.isEmpty()) return DelegationPendingCopy.NONE
        val resolution = resolve(chatId, refs)
        val paths = if (resolution.inputs.isEmpty()) emptyList()
        else driver.copyInputs(sessionId, resolution.inputs.map { it.displayName to it.file })
        val copied = resolution.inputs.zip(paths) { input, path -> DelegationCopiedInput(input.ref, input.displayName, path, input.file) }
        return DelegationPendingCopy(copied, resolution.unknown, refs)
    }

    /**
     * Copies [paths] from the project into `files/native_chat_workspaces/<chatId>/results/` and inserts pending
     * DEEPSEEK attachment rows. Failures are skipped and reported in [DelegationExportResult.notes].
     */
    suspend fun exportResults(
        driver: DelegateHarnessDriver,
        sessionId: String,
        chatId: Long,
        taskId: Long,
        paths: List<String>
    ): DelegationExportResult {
        val dir = NativeChatWorkspaceSupport.subDir(appContext, chatId, NativeChatWorkspaceSupport.RESULTS_DIR)
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
