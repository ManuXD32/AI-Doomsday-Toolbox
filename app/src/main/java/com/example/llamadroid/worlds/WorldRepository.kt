package com.example.llamadroid.worlds

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class WorldMeta(
    val id: String,
    val title: String,
    val chatId: Long?,
    val messageId: Long?,
    val createdAt: Long,
    val updatedAt: Long,
)

/**
 * Saved worlds under `filesDir/worlds/<id>/` with `source.txt` (the raw model output) and `meta.json`.
 * The runnable page is rebuilt from the source on every read, so runtime/shim updates apply to old worlds.
 */
class WorldRepository internal constructor(
    private val root: File,
    private val labels: () -> Map<String, String>,
) {
    constructor(context: Context) : this(
        File(context.filesDir, "worlds"),
        { WorldRuntimeStrings.labels(context) },
    )

    suspend fun save(title: String, sourceMarkdownOrHtml: String, chatId: Long?, messageId: Long?): WorldMeta =
        withContext(Dispatchers.IO) {
            val id = UUID.randomUUID().toString().replace("-", "")
            val now = System.currentTimeMillis()
            val meta = WorldMeta(id, title.trim().ifBlank { DEFAULT_TITLE }.take(MAX_TITLE), chatId, messageId, now, now)
            val dir = File(root, id).also { it.mkdirs() }
            writeAtomic(File(dir, SOURCE_FILE), sourceMarkdownOrHtml)
            writeMeta(dir, meta)
            meta
        }

    suspend fun list(): List<WorldMeta> = withContext(Dispatchers.IO) {
        root.listFiles { f -> f.isDirectory && validId(f.name) }.orEmpty()
            .mapNotNull { readMeta(it) }
            .sortedByDescending { it.updatedAt }
    }

    suspend fun rename(id: String, newTitle: String): WorldMeta? = withContext(Dispatchers.IO) {
        val dir = dirFor(id) ?: return@withContext null
        val meta = readMeta(dir) ?: return@withContext null
        val updated = meta.copy(
            title = newTitle.trim().ifBlank { meta.title }.take(MAX_TITLE),
            updatedAt = System.currentTimeMillis(),
        )
        writeMeta(dir, updated)
        updated
    }

    suspend fun delete(id: String): Boolean = withContext(Dispatchers.IO) {
        val dir = dirFor(id) ?: return@withContext false
        dir.deleteRecursively()
    }

    /** The runnable HTML page for a saved world, or null when the id is unknown/invalid. */
    suspend fun page(id: String): String? = withContext(Dispatchers.IO) {
        val dir = dirFor(id) ?: return@withContext null
        val file = File(dir, SOURCE_FILE)
        if (!file.isFile) return@withContext null
        val raw = file.readText()
        val source = WorldPageBuilder.detect(raw) ?: WorldSource(raw, emptyList(), emptyList())
        WorldPageBuilder.build(source, labels())
    }

    /** The raw stored source (for re-saving or sharing), or null. */
    suspend fun source(id: String): String? = withContext(Dispatchers.IO) {
        dirFor(id)?.let { File(it, SOURCE_FILE) }?.takeIf { it.isFile }?.readText()
    }

    private fun dirFor(id: String): File? {
        if (!validId(id)) return null
        val dir = File(root, id)
        val rootPath = root.canonicalFile
        val canonical = dir.canonicalFile
        // Reject anything that resolves outside the worlds root (symlinks, odd separators).
        if (canonical.parentFile != rootPath || !canonical.isDirectory) return null
        return canonical
    }

    private fun readMeta(dir: File): WorldMeta? = try {
        val o = JSONObject(File(dir, META_FILE).readText())
        WorldMeta(
            id = dir.name,
            title = o.optString("title", DEFAULT_TITLE),
            chatId = if (o.has("chatId") && !o.isNull("chatId")) o.getLong("chatId") else null,
            messageId = if (o.has("messageId") && !o.isNull("messageId")) o.getLong("messageId") else null,
            createdAt = o.optLong("createdAt"),
            updatedAt = o.optLong("updatedAt"),
        )
    } catch (_: Exception) {
        null
    }

    private fun writeMeta(dir: File, meta: WorldMeta) {
        val o = JSONObject()
            .put("title", meta.title)
            .put("chatId", meta.chatId ?: JSONObject.NULL)
            .put("messageId", meta.messageId ?: JSONObject.NULL)
            .put("createdAt", meta.createdAt)
            .put("updatedAt", meta.updatedAt)
        writeAtomic(File(dir, META_FILE), o.toString())
    }

    private fun writeAtomic(target: File, text: String) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(target)) {
            target.delete()
            check(tmp.renameTo(target)) { "Could not write ${target.name}" }
        }
    }

    companion object {
        private const val SOURCE_FILE = "source.txt"
        private const val META_FILE = "meta.json"
        private const val DEFAULT_TITLE = "3D world"
        private const val MAX_TITLE = 120
        private val ID_PATTERN = Regex("[A-Za-z0-9_-]{1,64}")

        internal fun validId(id: String): Boolean = ID_PATTERN.matches(id)
    }
}
