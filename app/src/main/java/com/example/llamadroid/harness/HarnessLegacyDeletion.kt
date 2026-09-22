package com.example.llamadroid.harness

import android.content.Context
import androidx.room.withTransaction
import com.example.llamadroid.data.db.AgentConversationEntity
import com.example.llamadroid.data.db.AgentRuntimeSource
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.service.AgentLocalWorkspaceSupport
import com.example.llamadroid.service.AgentSleepWakeScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.SimpleFileVisitor
import java.nio.file.FileVisitResult
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

data class HarnessLegacyDeletePreview(val conversation: AgentConversationEntity, val otherThreads: Int)
data class HarnessPendingDeletion(val conversationId: Long, val projectFolder: String)

/** User-confirmed archive cleanup, never called by a model or the legacy execution loop. */
class HarnessLegacyDeletion(
    private val context: Context,
    private val database: AppDatabase,
    private val workspaces: HarnessWorkspaceRepository,
    private val files: HarnessWorkspaceAccess,
) {
    private val receipts = File(context.filesDir, "agent_harness/project-deletions")
    private val quarantine = File(context.filesDir, "agent_harness/deleted-projects")

    suspend fun preview(id: Long): HarnessLegacyDeletePreview = withContext(Dispatchers.IO) {
        val conversation = requireNotNull(database.agentChatDao().getConversation(id))
        require(conversation.runtimeSource == AgentRuntimeSource.LEGACY_ARCHIVE)
        HarnessLegacyDeletePreview(conversation, database.harnessDao().conversations().count {
            it.id != id && it.runtimeSource != AgentRuntimeSource.WORKSPACE_ONLY && sharesFiles(conversation, it)
        })
    }

    suspend fun pending(): List<HarnessPendingDeletion> = withContext(Dispatchers.IO) {
        receipts.listFiles().orEmpty().filter { it.name.matches(Regex("[0-9]+\\.json")) }.mapNotNull { file ->
            runCatching { JSONObject(file.readText()).let { HarnessPendingDeletion(it.getLong("id"), it.getString("folder")) } }.getOrNull()
        }
    }

    suspend fun delete(id: Long, projectFiles: Boolean) = withContext(Dispatchers.IO) {
        val preview = preview(id)
        if (!projectFiles) {
            // A history-only request must not strand a partly completed file deletion receipt.
            require(!receipt(id).exists()) { "PROJECT_CLEANUP_PENDING" }
            deleteHistory(id)
            return@withContext
        }
        require(preview.otherThreads == 0) { "PROJECT_SHARED_BY_OTHER_THREADS" }
        check(receipts.isDirectory || receipts.mkdirs())
        val row = JSONObject().put("id", id).put("folder", preview.conversation.projectFolder)
            .put("backend", preview.conversation.workspaceBackend)
        // Durable intent precedes any filesystem change, so partial cleanup remains retryable.
        if (!receipt(id).exists()) FileOutputStream(receipt(id)).use { it.write(row.toString().toByteArray()); it.fd.sync() }
        finish(id)
    }

    suspend fun retry(id: Long) = withContext(Dispatchers.IO) { finish(id) }

    private suspend fun finish(id: Long) {
        require(id > 0)
        val intent = JSONObject(receipt(id).readText())
        require(intent.getLong("id") == id)
        val folder = intent.getString("folder")
        val backend = intent.getString("backend")
        val trash = File(quarantine, id.toString())
        val conversation = database.agentChatDao().getConversation(id)
        if (conversation != null) {
            // Recheck inside the same Room transaction used by session import; a new mapping
            // cannot slip between the shared-folder check and removing this project's index.
            database.withTransaction {
                require(preview(id).otherThreads == 0) { "PROJECT_SHARED_BY_OTHER_THREADS" }
                require(conversation.projectFolder == folder && conversation.workspaceBackend == backend)
                if (backend == "REMOTE_SSH") {
                    files.deleteArchivedProject(workspaces.fileScopeForConversation(id, allowPendingDeletion = true))
                } else {
                    val managed = File(context.filesDir, "agent_local_workspaces").canonicalFile
                    val root = File(managed, AgentLocalWorkspaceSupport.sanitizeProjectFolder(folder))
                    require(root.canonicalFile.parentFile == managed && !Files.isSymbolicLink(root.toPath())) { "WORKSPACE_PATH_OUTSIDE_SCOPE" }
                    if (root.exists()) {
                        check(quarantine.isDirectory || quarantine.mkdirs())
                        check(!trash.exists()) { "PROJECT_CLEANUP_PENDING" }
                        Files.move(root.toPath(), trash.toPath())
                    }
                }
                val dao = database.harnessDao()
                val related = dao.workspaces().filter {
                    if (backend == "REMOTE_SSH") it.projectFolder == folder && it.backend == backend
                    else it.backend != "REMOTE_SSH" && AgentLocalWorkspaceSupport.sanitizeProjectFolder(it.projectFolder) ==
                        AgentLocalWorkspaceSupport.sanitizeProjectFolder(folder)
                }
                dao.conversations().filter {
                    it.runtimeSource == AgentRuntimeSource.WORKSPACE_ONLY && sharesFiles(conversation, it)
                }.forEach { deleteHistory(it.id) }
                deleteHistory(id)
                related.forEach { dao.deleteUnreferencedWorkspace(it.id) }
            }
        }
        // Only the uniquely quarantined directory is removed. walkFileTree never follows links;
        // shared models, environments, credentials and other projects are outside this root.
        if (trash.exists()) deleteQuarantinedTree(trash)
        check(receipt(id).delete()) { "PROJECT_CLEANUP_PENDING" }
    }

    private suspend fun deleteHistory(id: Long) {
        AgentSleepWakeScheduler.cancelConversation(context, id)
        database.withTransaction {
            database.aiRuntimeJobDao().deleteByConversationId(id)
            database.agentChatDao().deleteConversationById(id)
        }
    }

    private fun receipt(id: Long): File { require(id > 0); return File(receipts, "$id.json") }

    companion object {
        internal fun sharesFiles(a: AgentConversationEntity, b: AgentConversationEntity): Boolean {
            if ((a.workspaceBackend == "REMOTE_SSH") != (b.workspaceBackend == "REMOTE_SSH")) return false
            return if (a.workspaceBackend == "REMOTE_SSH") a.projectFolder == b.projectFolder
            else AgentLocalWorkspaceSupport.sanitizeProjectFolder(a.projectFolder) == AgentLocalWorkspaceSupport.sanitizeProjectFolder(b.projectFolder)
        }

        internal fun deleteQuarantinedTree(root: File) {
            Files.walkFileTree(root.toPath(), object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    Files.delete(file)
                    return FileVisitResult.CONTINUE
                }
                override fun postVisitDirectory(dir: Path, error: java.io.IOException?): FileVisitResult {
                    if (error != null) throw error
                    Files.delete(dir)
                    return FileVisitResult.CONTINUE
                }
            })
        }
    }
}
