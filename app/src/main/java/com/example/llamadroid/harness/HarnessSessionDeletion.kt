package com.example.llamadroid.harness

import android.content.Context
import android.util.AtomicFile
import androidx.room.withTransaction
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.harness.HarnessWorkspaceAccess.Companion.readBounded
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.security.MessageDigest

data class HarnessSessionDeletionState(val sessionId: String, val error: Boolean = false)

internal data class HarnessSessionDeletionReceipt(
    val id: String,
    val conversationId: Long,
    val directory: String? = null,
    val index: String? = null,
    val removed: Boolean = false,
    val error: Boolean = false,
)

/** Durable maintenance receipts are independent of the UI and contain no conversation content. */
internal class HarnessSessionDeletionStore(context: Context) {
    private val root = File(context.filesDir, "agent_harness/session-deletions")
    private fun file(id: String) = AtomicFile(File(root, sessionDeletionKey(id) + ".json"))

    @Synchronized fun read(id: String): HarnessSessionDeletionReceipt? = readFile(file(id))
    @Synchronized fun all(): List<HarnessSessionDeletionReceipt> = root.listFiles().orEmpty()
        .filter { it.name.matches(Regex("[a-f0-9]{64}\\.json")) }.mapNotNull { readFile(AtomicFile(it)) }

    @Synchronized fun write(row: HarnessSessionDeletionReceipt) {
        require(validDeletionId(row.id) && row.conversationId > 0)
        check(root.isDirectory || root.mkdirs())
        val target = file(row.id)
        val output = target.startWrite()
        try {
            output.write(JSONObject().put("id", row.id).put("conversationId", row.conversationId)
                .put("directory", row.directory).put("index", row.index)
                .put("removed", row.removed).put("error", row.error).toString().toByteArray())
            target.finishWrite(output)
        } catch (failure: Exception) { target.failWrite(output); throw failure }
    }

    private fun readFile(file: AtomicFile): HarnessSessionDeletionReceipt? = runCatching {
        val json = file.openRead().use { JSONObject(it.readBounded(16_384).toString(Charsets.UTF_8)) }
        HarnessSessionDeletionReceipt(json.getString("id"), json.getLong("conversationId"),
            json.optString("directory").takeIf { it.isNotEmpty() }, json.optString("index").takeIf { it.isNotEmpty() },
            json.optBoolean("removed"), json.optBoolean("error")).also {
            require(validDeletionId(it.id) && file.baseFile.name == sessionDeletionKey(it.id) + ".json")
        }
    }.getOrNull()
}

class HarnessSessionDeletion internal constructor(
    private val context: Context,
    private val database: AppDatabase,
    private val scope: CoroutineScope,
    private val perform: suspend (HarnessSessionDeletionReceipt) -> Boolean,
    private val onFailure: (String, Throwable) -> Unit = { _, _ -> },
) {
    private val store = HarnessSessionDeletionStore(context)
    private val mutablePending = MutableStateFlow<List<HarnessSessionDeletionState>>(emptyList())
    val pending = mutablePending.asStateFlow()
    private var worker: Job? = null

    init { wake() }

    suspend fun request(sessionId: String) {
        require(validDeletionId(sessionId)) { "SESSION_DELETE_INVALID" }
        val previous = store.read(sessionId)
        if (previous?.removed == true) return
        val mapping = requireNotNull(database.harnessDao().session(sessionId)) { "SESSION_NOT_FOUND" }
        store.write((previous ?: HarnessSessionDeletionReceipt(sessionId, mapping.conversationId)).copy(error = false))
        mutablePending.value = store.all().filterNot { it.removed }.map { HarnessSessionDeletionState(it.id, it.error) }
        wake()
    }

    @Synchronized fun wake() {
        if (worker?.isActive == true) return
        val launched = scope.launch(Dispatchers.IO) {
            do {
                val rows = store.all().filterNot { it.removed }
                mutablePending.value = rows.map { HarnessSessionDeletionState(it.id, it.error) }
                for (row in rows.filterNot { it.error }) {
                    try { perform(row) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: Exception) {
                        store.write((store.read(row.id) ?: row).copy(error = true))
                        onFailure(row.id, error)
                    }
                }
                mutablePending.value = store.all().filterNot { it.removed }.map { HarnessSessionDeletionState(it.id, it.error) }
                if (mutablePending.value.isEmpty() || mutablePending.value.all { it.error }) break
                delay(5_000)
            } while (isActive)
        }
        worker = launched
        launched.invokeOnCompletion {
            // A new request can arrive just as the final loop snapshot empties.
            // Restart only durable non-error work; failed work needs explicit retry.
            synchronized(this) { if (worker === launched) worker = null }
            if (store.all().any { !it.removed && !it.error } && scope.isActive) wake()
        }
    }

    internal fun prepared(row: HarnessSessionDeletionReceipt, directory: String, index: String?): HarnessSessionDeletionReceipt =
        row.copy(directory = directory, index = index, error = false).also(store::write)

    /** Must run under the runtime's stopped maintenance lock, after confirmed listener/process cleanup. */
    internal suspend fun removeStopped(row: HarnessSessionDeletionReceipt) {
        val home = File(context.filesDir, "agent_harness/dsh_home").canonicalFile
        val path = checkedSessionDeletionPath(home, requireNotNull(row.directory))
        require(path.name == row.id && path.parentFile != home) { "SESSION_DELETE_OUTSIDE_STORE" }
        // Delete only this persistence directory. Forks own independent directories;
        // project files and .adt thread attachments remain shared user deliverables.
        if (Files.exists(path.toPath(), LinkOption.NOFOLLOW_LINKS)) deleteSessionTree(path)
        row.index?.let { removeDerivedIndex(checkedSessionDeletionPath(home, it)) }
        database.withTransaction {
            database.harnessDao().session(row.id)?.let { mapping ->
                check(mapping.conversationId == row.conversationId)
                database.agentChatDao().deleteConversationById(mapping.conversationId)
            }
        }
        store.write(row.copy(removed = true, error = false))
    }
}

internal fun validDeletionId(id: String): Boolean = id.matches(Regex("[A-Za-z0-9._-]{1,256}")) && id != "." && id != ".."
internal fun sessionDeletionKey(id: String): String = MessageDigest.getInstance("SHA-256")
    .digest(id.toByteArray()).joinToString("") { "%02x".format(it) }

internal fun checkedSessionDeletionPath(home: File, relative: String): File {
    require(relative.isNotBlank() && !relative.startsWith('/') && '\u0000' !in relative)
    require(relative.split('/').none { it.isEmpty() || it == "." || it == ".." })
    var path = home.canonicalFile
    relative.split('/').forEach { segment ->
        path = File(path, segment)
        require(!Files.isSymbolicLink(path.toPath())) { "SESSION_DELETE_SYMLINK" }
    }
    require(path.canonicalFile.toPath().startsWith(home.canonicalFile.toPath()) && path.canonicalFile != home.canonicalFile)
    return path
}

private fun deleteSessionTree(directory: File) {
    // NOFOLLOW traversal removes a session-owned symlink entry, never its target.
    Files.walkFileTree(directory.toPath(), object : java.nio.file.SimpleFileVisitor<java.nio.file.Path>() {
        override fun visitFile(file: java.nio.file.Path, attributes: java.nio.file.attribute.BasicFileAttributes): java.nio.file.FileVisitResult {
            Files.delete(file); return java.nio.file.FileVisitResult.CONTINUE
        }
        override fun postVisitDirectory(dir: java.nio.file.Path, error: java.io.IOException?): java.nio.file.FileVisitResult {
            if (error != null) throw error
            Files.delete(dir); return java.nio.file.FileVisitResult.CONTINUE
        }
    })
}

private fun removeDerivedIndex(file: File) {
    if (!file.exists()) return
    // The live pinned query service validates all table names before returning
    // its configured path. Check its dedicated application/schema marker again
    // offline without opening Node's FTS5/STRICT schema in older Android SQLite.
    val header = ByteArray(100)
    java.io.DataInputStream(file.inputStream()).use { it.readFully(header) }
    require(isHarnessDerivedIndexHeader(header)) { "SESSION_DELETE_INDEX_IDENTITY" }
    // The whole index is derived; rebuilding it avoids leaving deleted text in FTS pages/WAL.
    listOf(file, File(file.path + "-wal"), File(file.path + "-shm"), File(file.path + "-journal")).forEach {
        if (it.exists()) check(it.delete()) { "SESSION_DELETE_INDEX_FAILED" }
    }
}

internal fun isHarnessDerivedIndexHeader(header: ByteArray): Boolean = header.size >= 100 &&
    header.copyOfRange(0, 16).contentEquals("SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)) &&
    java.nio.ByteBuffer.wrap(header).getInt(68) == 1146308689 &&
    java.nio.ByteBuffer.wrap(header).getInt(60) == 8
