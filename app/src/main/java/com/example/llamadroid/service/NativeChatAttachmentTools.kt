package com.example.llamadroid.service

import android.content.Context
import com.example.llamadroid.R
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.service.chat.ToolImageResults
import com.example.llamadroid.service.delegation.DelegationToolArgs
import com.example.llamadroid.service.vision.VisionImageDescription
import com.example.llamadroid.service.vision.VisionImageDescriptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipFile

/**
 * `chat_file_read`: lets the chat model read files the user attached (`[Attached files: f12 name (16 KB)]`
 * lines) and DeepSeek result files, plus message images (`img55`). Reads are bounded and confined to the chat workspace
 * `files/native_chat_workspaces/<chatId>/` or the legacy `files/llama_chat_files/<chatId>/` (canonical path check) and to messages of this chat.
 *
 * @param chatModelSeesImages whether the chat's model can look at images directly (then images are not
 * described by the vision helper). Defaults to false: the helper description is tried first.
 */
class NativeChatAttachmentTools(
    context: Context,
    private val chatModelSeesImages: suspend (chatId: Long) -> Boolean = { false }
) : NativeChatToolExtension {
    private val appContext = context.applicationContext

    override val id: String = "chat_attachments"

    override fun isEnabled(config: NativeChatToolConfig): Boolean = config.hasEnabledTools()

    override fun handles(toolName: String): Boolean = toolName == TOOL_READ

    override fun statusText(toolName: String): String? =
        if (toolName == TOOL_READ) appContext.getString(R.string.native_chat_attachments_read_status) else null

    override fun awareness(config: NativeChatToolConfig): String =
        "Messages may end with [Attached files: f12 name (size), img55 photo.jpg] — files the user attached. " +
            "You can read them with chat_file_read(id) and pass their ids to DeepSeek tools. Never say a listed file is missing. " +
            "Images marked \"shown to you as a picture\" are already visible to you: do not read them with a tool. Any other image comes back from chat_file_read as a picture or a description."

    override fun tools(config: NativeChatToolConfig): List<AgentTool> = listOf(
        AgentTool(
            name = TOOL_READ,
            description = "Read a file the user attached to this chat (ids from the [Attached files: ...] lines, e.g. f12, or a message image img55), or a DeepSeek result file. " +
                "Text files are returned in pages (offset/max_chars); PDFs as extracted text; zip files as an entry list (read one with id=\"f12:path/in/zip\"); " +
                "images come back as a picture you can see, or as a description from the vision helper. " +
                "Do not read images marked \"shown to you as a picture\" with this tool: look at them directly.",
            parameters = mapOf(
                "id" to "File id such as f12, img55, or f12:path/in/zip",
                "offset" to "Start position in characters (default 0)",
                "max_chars" to "Characters to return, up to ${NativeChatAttachmentSupport.MAX_MAX_CHARS} (default ${NativeChatAttachmentSupport.DEFAULT_MAX_CHARS})"
            ),
            requiredParams = listOf("id"),
            schemaJson = SCHEMA
        )
    )

    override suspend fun execute(
        toolCall: OllamaService.ToolCall,
        config: NativeChatToolConfig,
        chatId: Long?,
        onProgress: (NativeChatToolProgress) -> Unit
    ): Result<NativeChatToolResult> {
        val result = try {
            if (chatId == null || chatId <= 0L) {
                NativeChatToolResult("tool_error: attached files need a saved chat.")
            } else {
                withContext(Dispatchers.IO) { read(toolCall.arguments, chatId, onProgress) }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            NativeChatToolResult("tool_error: ${e.message ?: e.javaClass.simpleName}")
        }
        return Result.success(result)
    }

    private suspend fun read(
        args: Map<String, String>,
        chatId: Long,
        onProgress: (NativeChatToolProgress) -> Unit
    ): NativeChatToolResult {
        val rawId = DelegationToolArgs.arg(args, "id", "file", "file_id")
        val ref = NativeChatAttachmentSupport.parseId(rawId)
            ?: return NativeChatToolResult("tool_error: invalid id '${rawId.orEmpty().take(80)}'. Use an id from the [Attached files: ...] list, e.g. f12, img55 or f12:path/in/zip.")
        val offset = DelegationToolArgs.arg(args, "offset", "start")?.trim()?.toIntOrNull()
        val maxChars = DelegationToolArgs.arg(args, "max_chars", "maxchars", "limit")?.trim()?.toIntOrNull()
        val db = AppDatabase.getDatabase(appContext)
        return when (ref) {
            is ChatFileRef.MessageImage -> {
                val path = db.llamaMessageDao().getMessagesOnce(chatId).firstOrNull { it.id == ref.messageId }?.imagePath
                val file = path?.takeIf { it.isNotBlank() }?.let { File(it) }
                if (file == null || !file.isFile) NativeChatToolResult("tool_error: image img${ref.messageId} not found in this chat.")
                else readImage(chatId, file, file.name, mimeFromName(file.name), TOOL_READ, onProgress)
            }
            is ChatFileRef.Attachment -> {
                val row = db.llamaMessageAttachmentDao().getById(ref.id)?.takeIf { it.chatId == chatId }
                    ?: return NativeChatToolResult("tool_error: file f${ref.id} not found in this chat.")
                val roots = listOf(
                    NativeChatWorkspaceSupport.rootIfPresent(appContext, chatId),
                    NativeChatWorkspaceSupport.legacyAttachmentRoot(appContext, chatId)
                )
                val file = File(row.path)
                if (!file.isFile || !NativeChatAttachmentSupport.isInsideAny(roots, file)) {
                    return NativeChatToolResult("tool_error: file f${ref.id} (${row.displayName}) is no longer available.")
                }
                readAttachment(chatId, file, row.displayName, row.mimeType, ref.zipEntry, offset, maxChars, onProgress)
            }
        }
    }

    private suspend fun readAttachment(
        chatId: Long, file: File, name: String, mime: String, zipEntry: String?, offset: Int?, maxChars: Int?,
        onProgress: (NativeChatToolProgress) -> Unit
    ): NativeChatToolResult {
        val support = NativeChatAttachmentSupport
        if (zipEntry != null) return NativeChatToolResult(readZipEntry(file, name, zipEntry, offset, maxChars))
        // Images are detected by extension/mime AND magic bytes and never decoded as text.
        if (support.isImage(name, mime) || NativeChatWorkspaceSupport.imageMimeOf(file) != null) {
            return readImage(chatId, file, name, mime, TOOL_READ, onProgress)
        }
        return NativeChatToolResult(when {
            support.isPdf(name, mime) -> readPdf(file, name, offset, maxChars)
            com.example.llamadroid.knowledge.DocumentTextExtractor.supports(name, mime) -> readDocument(file, name, offset, maxChars)
            support.isZip(name, mime) -> ZipFile(file).use { support.zipListing(it, name) }
            else -> {
                val (bytes, truncated) = support.readCapped(file, support.MAX_READ_BYTES)
                // Declared text types and unknown types alike must pass the binary sniff.
                if (!support.looksBinary(bytes)) {
                    "File $name:\n" + support.page(String(bytes, Charsets.UTF_8), offset, maxChars, truncated)
                } else {
                    binaryInfo(name, mime, file.length())
                }
            }
        })
    }

    private fun binaryInfo(name: String, mime: String, size: Long): String =
        "File $name (type $mime, ${NativeChatAttachmentSupport.formatSize(size)}) is a binary format that cannot be read as text. " +
            "If it needs processing, hand it to DeepSeek by id."

    private fun readZipEntry(file: File, name: String, entryName: String, offset: Int?, maxChars: Int?): String {
        val support = NativeChatAttachmentSupport
        ZipFile(file).use { zip ->
            val entry = zip.getEntry(entryName)
            if (entry == null || entry.isDirectory) return "tool_error: '$entryName' is not a file in $name."
            val bytes = zip.getInputStream(entry).use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(32 * 1024)
                var total = 0L
                var cut = false
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    val take = minOf(read.toLong(), support.MAX_ZIP_ENTRY_BYTES - total).toInt()
                    out.write(buffer, 0, take)
                    total += take
                    if (take < read || total >= support.MAX_ZIP_ENTRY_BYTES) { cut = true; break }
                }
                out.toByteArray() to cut
            }
            if (bytes.first.isEmpty()) return "Entry $entryName in $name is empty."
            if (support.looksBinary(bytes.first)) return binaryInfo(entryName, "application/octet-stream", entry.size)
            return "Entry $entryName in $name:\n" + support.page(String(bytes.first, Charsets.UTF_8), offset, maxChars, bytes.second)
        }
    }

    private fun readDocument(file: File, name: String, offset: Int?, maxChars: Int?): String {
        val extractor = com.example.llamadroid.knowledge.DocumentTextExtractor
        if (file.length() > NativeChatAttachmentSupport.MAX_DOCUMENT_BYTES) {
            return "Document $name is too large to read here (${NativeChatAttachmentSupport.formatSize(file.length())})."
        }
        val doc = extractor.extract(file, name, null, NativeChatToolConfig.MAX_FETCH_CHARS)
            ?: return binaryInfo(name, "application/octet-stream", file.length())
        if (doc.text.isBlank()) return "Document $name has no extractable text."
        return "Document $name (${doc.format.uppercase()}, extracted text):\n" +
            NativeChatAttachmentSupport.page(doc.text, offset, maxChars, doc.truncated)
    }

    private fun readPdf(file: File, name: String, offset: Int?, maxChars: Int?): String {
        val support = NativeChatAttachmentSupport
        if (file.length() > support.MAX_PDF_BYTES) {
            return "PDF $name is too large to read here (${support.formatSize(file.length())}). Hand it to DeepSeek by id."
        }
        com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(appContext)
        val text = extractNativePdfTextFromBytes(file.readBytes(), NativeChatToolConfig.MAX_FETCH_CHARS)
        if (text.isBlank()) return "PDF $name has no extractable text (it may be scanned images)."
        return "PDF $name (extracted text):\n" + support.page(text, offset, maxChars)
    }

    /**
     * Image read shared by `chat_file_read` and `read_workspace_file`: a picture for a vision chat model
     * ([NativeChatToolResult.viewImagePath], attached by the tool loop) or the helper's description, whose
     * progress is forwarded to [onProgress] as the tool status.
     */
    suspend fun readImage(
        chatId: Long,
        file: File,
        name: String,
        mime: String,
        toolName: String,
        onProgress: (NativeChatToolProgress) -> Unit
    ): NativeChatToolResult {
        if (chatModelSeesImages(chatId)) {
            return NativeChatToolResult(ToolImageResults.shownContent(name), viewImagePath = file.absolutePath)
        }
        val (bytes, truncated) = NativeChatAttachmentSupport.readCapped(file, NativeChatAttachmentSupport.MAX_READ_BYTES)
        if (truncated) {
            return NativeChatToolResult(ToolImageResults.tooLargeContent(name, NativeChatAttachmentSupport.formatSize(file.length())))
        }
        val realMime = NativeChatWorkspaceSupport.imageMimeFromMagic(bytes.copyOf(minOf(bytes.size, 16))) ?: mime
        val text = when (val result = VisionImageDescriptions.describe(appContext, bytes, realMime, onProgress = { line ->
            onProgress(NativeChatToolProgress(status = line))
        })) {
            is VisionImageDescription.Described -> ToolImageResults.describedContent(name, result.text)
            is VisionImageDescription.Unavailable -> ToolImageResults.unavailableContent(name, result.reason)
        }
        return NativeChatToolResult(text)
    }

    private fun mimeFromName(name: String): String = when (NativeChatAttachmentSupport.extensionOf(name)) {
        "png" -> "image/png"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        "bmp" -> "image/bmp"
        else -> "image/jpeg"
    }

    companion object {
        /** Instance for the chat service: images are described only when the chat's server can't see them. */
        fun forChatService(context: Context): NativeChatAttachmentTools {
            val db = AppDatabase.getDatabase(context.applicationContext)
            return NativeChatAttachmentTools(context) { chatId ->
                val serverId = db.llamaChatDao().getChatById(chatId)?.serverId
                serverId != null && db.llamaServerDao().getServerById(serverId)?.supportsVision == true
            }
        }

        const val TOOL_READ = "chat_file_read"

        private val SCHEMA = """{"type":"object","properties":{"id":{"type":"string","description":"File id from the [Attached files: ...] list: f12, img55, or f12:path/in/zip."},"offset":{"type":"integer","description":"Start position in characters (default 0).","minimum":0},"max_chars":{"type":"integer","description":"Characters to return, 1 to ${NativeChatAttachmentSupport.MAX_MAX_CHARS} (default ${NativeChatAttachmentSupport.DEFAULT_MAX_CHARS}).","minimum":1,"maximum":${NativeChatAttachmentSupport.MAX_MAX_CHARS}}},"required":["id"]}"""
    }
}
