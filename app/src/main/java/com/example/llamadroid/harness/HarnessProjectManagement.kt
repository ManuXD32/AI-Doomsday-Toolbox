package com.example.llamadroid.harness

import android.content.Context
import androidx.room.withTransaction
import com.example.llamadroid.data.db.AgentConversationEntity
import com.example.llamadroid.data.db.AgentRuntimeSource
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.data.db.HarnessSessionEntity
import com.example.llamadroid.data.db.HarnessWorkspaceEntity
import com.example.llamadroid.service.AgentLocalWorkspaceSupport
import com.example.llamadroid.service.AgentSleepWakeScheduler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID

/** Presentation and cleanup information for one file-backed Harness project. */
data class HarnessProjectRemovalPreview(
    val id: String,
    val title: String,
    val projectFolder: String,
    val backend: String,
    val connectionKey: String = "",
    val threadCount: Int = 0,
    val legacyConversationCount: Int = 0,
    val aliasCount: Int = 0,
    val filesPresent: Boolean = false,
    val fileDeletionSupported: Boolean = backend != "REMOTE_SSH",
    val pendingRemoval: Boolean = false,
    val pendingDeleteFiles: Boolean = false,
    val cleanupErrorCode: String? = null,
    val retainedCanonicalSessions: Int = threadCount,
)

data class HarnessProjectRemovalResult(
    val projectId: String,
    val removedLegacyConversations: Int,
    val retainedCanonicalSessions: Int,
    val filesDeleted: Boolean,
)

private const val REMOTE_BACKEND = "REMOTE_SSH"
private const val TOMBSTONE_PENDING = "PENDING"
private const val TOMBSTONE_QUARANTINED = "QUARANTINED"
private const val TOMBSTONE_METADATA_REMOVED = "METADATA_REMOVED"
private const val TOMBSTONE_REMOVED = "REMOVED"

/** Durable project suppression records. They contain identity and lifecycle metadata only. */
internal data class HarnessProjectTombstone(
    val projectId: String,
    val title: String,
    val projectFolder: String,
    val backend: String,
    val connectionKey: String,
    val workspaceIds: Set<String>,
    val sessionIds: Set<String>,
    val deleteFiles: Boolean,
    val state: String,
    val quarantineName: String? = null,
    val errorCode: String? = null,
    val updatedAt: Long = System.currentTimeMillis(),
) {
    val pending: Boolean get() = state != TOMBSTONE_REMOVED

    fun toJson(): JSONObject = JSONObject()
        .put("version", 1)
        .put("projectId", projectId)
        .put("title", title)
        .put("projectFolder", projectFolder)
        .put("backend", backend)
        .put("connectionKey", connectionKey)
        .put("workspaceIds", JSONArray(workspaceIds.toList()))
        .put("sessionIds", JSONArray(sessionIds.toList()))
        .put("deleteFiles", deleteFiles)
        .put("state", state)
        .putOpt("quarantineName", quarantineName)
        .putOpt("errorCode", errorCode)
        .put("updatedAt", updatedAt)

    companion object {
        fun fromJson(row: JSONObject): HarnessProjectTombstone? = runCatching {
            val workspaceIds = row.optJSONArray("workspaceIds").toStringSet()
            val sessionIds = row.optJSONArray("sessionIds").toStringSet()
            HarnessProjectTombstone(
                projectId = row.getString("projectId"),
                title = row.optString("title", "Project"),
                projectFolder = row.getString("projectFolder"),
                backend = row.getString("backend"),
                connectionKey = row.optString("connectionKey"),
                workspaceIds = workspaceIds,
                sessionIds = sessionIds,
                deleteFiles = row.optBoolean("deleteFiles"),
                state = row.optString("state", TOMBSTONE_PENDING),
                quarantineName = row.optString("quarantineName").takeIf(String::isNotBlank),
                errorCode = row.optString("errorCode").takeIf(String::isNotBlank),
                updatedAt = row.optLong("updatedAt", System.currentTimeMillis()),
            )
        }.getOrNull()
    }
}

private fun JSONArray?.toStringSet(): Set<String> {
    val array = this ?: return emptySet()
    return (0 until array.length())
        .mapNotNull { array.optString(it).takeIf(String::isNotBlank) }
        .toSet()
}

/** Shared identity rules for Room aliases, legacy imports, and cleanup receipts. */
internal object HarnessProjectManagementRules {
    fun normalizeTitle(value: String): String {
        val title = value.trim()
        require(title.isNotEmpty()) { "PROJECT_TITLE_REQUIRED" }
        require(title.length <= 120) { "PROJECT_TITLE_TOO_LONG" }
        require(title.none { it == '\u0000' }) { "PROJECT_TITLE_INVALID" }
        return title
    }

    fun sameWorkspace(a: HarnessWorkspaceEntity, b: HarnessWorkspaceEntity): Boolean {
        if (a.backend == REMOTE_BACKEND || b.backend == REMOTE_BACKEND) {
            return a.backend == REMOTE_BACKEND && b.backend == REMOTE_BACKEND &&
                a.projectFolder == b.projectFolder && a.connectionKey == b.connectionKey
        }
        return a.backend != REMOTE_BACKEND && b.backend != REMOTE_BACKEND &&
            AgentLocalWorkspaceSupport.sanitizeProjectFolder(a.projectFolder) ==
            AgentLocalWorkspaceSupport.sanitizeProjectFolder(b.projectFolder)
    }

    fun sameConversation(workspace: HarnessWorkspaceEntity, conversation: AgentConversationEntity): Boolean {
        if (workspace.backend == REMOTE_BACKEND || conversation.workspaceBackend == REMOTE_BACKEND) {
            return workspace.backend == REMOTE_BACKEND &&
                conversation.workspaceBackend == REMOTE_BACKEND &&
                workspace.projectFolder == conversation.projectFolder
        }
        return workspace.backend != REMOTE_BACKEND && conversation.workspaceBackend != REMOTE_BACKEND &&
            AgentLocalWorkspaceSupport.sanitizeProjectFolder(workspace.projectFolder) ==
            AgentLocalWorkspaceSupport.sanitizeProjectFolder(conversation.projectFolder)
    }

    fun groupBackend(backend: String): String = if (backend == REMOTE_BACKEND) REMOTE_BACKEND else "LOCAL"

    fun key(backend: String, projectFolder: String, connectionKey: String = ""): String {
        val groupBackend = groupBackend(backend)
        val folder = if (groupBackend == REMOTE_BACKEND) projectFolder
        else AgentLocalWorkspaceSupport.sanitizeProjectFolder(projectFolder)
        val connection = if (groupBackend == REMOTE_BACKEND) connectionKey else ""
        return sha256("$groupBackend\u0000$connection\u0000$folder")
    }

    fun fileDeletionSupported(backend: String): Boolean = backend != REMOTE_BACKEND

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}

/**
 * Small file-backed tombstone store shared by project management and workspace import.
 * A tombstone survives successful cleanup so old legacy rows or a stale remote session list
 * cannot recreate a project the user explicitly removed.
 */
internal class HarnessProjectTombstoneStore(private val context: Context) {
    private val directory = File(context.filesDir, "agent_harness/project-management/tombstones")

    fun readAll(): List<HarnessProjectTombstone> = directory.listFiles().orEmpty()
        .filter { it.extension == "json" }
        .mapNotNull { file -> runCatching { HarnessProjectTombstone.fromJson(JSONObject(file.readText())) }.getOrNull() }

    fun findForId(id: String): HarnessProjectTombstone? = readAll().firstOrNull {
        it.projectId == id || id in it.workspaceIds
    }

    fun findForWorkspace(workspace: HarnessWorkspaceEntity): HarnessProjectTombstone? = readAll().firstOrNull {
        matchesWorkspace(it, workspace)
    }

    fun isSuppressed(backend: String, projectFolder: String, connectionKey: String = ""): Boolean {
        val group = HarnessProjectManagementRules.groupBackend(backend)
        val folder = if (group == REMOTE_BACKEND) projectFolder
        else AgentLocalWorkspaceSupport.sanitizeProjectFolder(projectFolder)
        return readAll().any { row ->
            row.backend == group && row.projectFolder == folder &&
                (group != REMOTE_BACKEND || row.connectionKey == connectionKey)
        }
    }

    fun write(row: HarnessProjectTombstone) {
        check(directory.isDirectory || directory.mkdirs()) { "PROJECT_RECEIPT_DIRECTORY_FAILED" }
        val target = File(directory, "${HarnessProjectManagementRules.key(row.backend, row.projectFolder, row.connectionKey)}.json")
        val temporary = File(directory, ".${target.name}.${UUID.randomUUID()}.tmp")
        FileOutputStream(temporary).use { output ->
            output.write(row.toJson().toString().toByteArray(Charsets.UTF_8))
            output.fd.sync()
        }
        runCatching {
            Files.move(
                temporary.toPath(), target.toPath(),
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING,
            )
        }.getOrElse {
            runCatching {
                Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }.getOrElse { throw IllegalStateException("PROJECT_RECEIPT_WRITE_FAILED", it) }
        }
    }

    private fun matchesWorkspace(row: HarnessProjectTombstone, workspace: HarnessWorkspaceEntity): Boolean {
        val group = HarnessProjectManagementRules.groupBackend(workspace.backend)
        val folder = if (group == REMOTE_BACKEND) workspace.projectFolder
        else AgentLocalWorkspaceSupport.sanitizeProjectFolder(workspace.projectFolder)
        return row.backend == group && row.projectFolder == folder &&
            (group != REMOTE_BACKEND || row.connectionKey == workspace.connectionKey)
    }
}

/** True when an import/list projection belongs to a durably removed project. */
internal fun harnessProjectSuppressed(context: Context, workspace: HarnessWorkspaceEntity): Boolean =
    HarnessProjectTombstoneStore(context).findForWorkspace(workspace) != null

internal fun harnessProjectSuppressed(
    context: Context,
    backend: String,
    projectFolder: String,
    connectionKey: String = "",
): Boolean = HarnessProjectTombstoneStore(context).isSuppressed(backend, projectFolder, connectionKey)

private data class ProjectGroup(
    val id: String,
    val title: String,
    val projectFolder: String,
    val backend: String,
    val connectionKey: String,
    val workspaces: List<HarnessWorkspaceEntity>,
    val conversations: List<AgentConversationEntity>,
    val sessions: List<HarnessSessionEntity>,
    val tombstone: HarnessProjectTombstone?,
    val remoteIdentityAmbiguous: Boolean = false,
)

/**
 * Native project management. It only removes the app's legacy index rows; the DeepSeek
 * session store and DEEPSEEK conversation projections are intentionally retained.
 */
class HarnessProjectManagement(
    private val context: Context,
    private val database: AppDatabase,
    @Suppress("UNUSED_PARAMETER") private val workspaces: HarnessWorkspaceRepository,
    @Suppress("UNUSED_PARAMETER") private val files: HarnessWorkspaceAccess,
    private val ensureStopped: suspend () -> Unit = {},
) {
    private val dao = database.harnessDao()
    private val chats = database.agentChatDao()
    private val tombstones = HarnessProjectTombstoneStore(context)
    private val operations = Mutex()
    private val quarantine = File(context.filesDir, "agent_harness/deleted-projects")

    suspend fun preview(workspaceId: String): HarnessProjectRemovalPreview = operations.withLock {
        withContext(Dispatchers.IO) { previewLocked(workspaceId) }
    }

    /**
     * Preview several projects using the same identity grouping as the single-project path.
     * Duplicate aliases therefore appear only once when a caller supplies more than one
     * workspace id for the same managed project.
     */
    suspend fun previewBatch(workspaceIds: Collection<String>): List<HarnessProjectRemovalPreview> =
        operations.withLock {
            withContext(Dispatchers.IO) {
                loadBatchGroups(workspaceIds).map { previewLocked(it.id) }
            }
        }

    suspend fun rename(workspaceId: String, displayName: String): HarnessProjectRemovalPreview = operations.withLock {
        withContext(Dispatchers.IO) {
            val title = HarnessProjectManagementRules.normalizeTitle(displayName)
            val group = loadGroup(workspaceId)
            require(group.tombstone == null) { "PROJECT_REMOVED" }
            require(group.workspaces.isNotEmpty()) { "PROJECT_NOT_FOUND" }
            val now = System.currentTimeMillis()
            database.withTransaction {
                group.workspaces.forEach { dao.saveWorkspace(it.copy(title = title, updatedAt = now)) }
            }
            previewLocked(workspaceId)
        }
    }

    suspend fun remove(workspaceId: String, deleteFiles: Boolean): HarnessProjectRemovalResult = operations.withLock {
        withContext(Dispatchers.IO) {
            ensureStopped()
            val group = loadGroup(workspaceId)
            removeLocked(group, deleteFiles)
        }
    }

    /**
     * Remove several projects after one stopped-runtime check. Every project still gets its
     * own durable tombstone and quarantine transaction, so a later failure remains recoverable
     * through the existing retry path instead of turning a batch into an all-or-nothing guess.
     */
    suspend fun removeBatch(
        workspaceIds: Collection<String>,
        deleteFiles: Boolean,
    ): List<HarnessProjectRemovalResult> = operations.withLock {
        withContext(Dispatchers.IO) {
            ensureStopped()
            val groups = loadBatchGroups(workspaceIds)
            groups.forEach { validateRemoval(it, deleteFiles) }
            groups.map { removeLocked(it, deleteFiles) }
        }
    }

    suspend fun retryRemoval(workspaceId: String): HarnessProjectRemovalResult = operations.withLock {
        withContext(Dispatchers.IO) {
            ensureStopped()
            val row = tombstones.findForId(workspaceId) ?: error("PROJECT_CLEANUP_NOT_FOUND")
            if (row.state == TOMBSTONE_REMOVED) {
                return@withContext resultFor(row, filesDeleted = row.deleteFiles)
            }
            require(!row.deleteFiles || row.backend != REMOTE_BACKEND) {
                "REMOTE_PROJECT_FILE_DELETE_UNSUPPORTED"
            }
            try {
                completeRemoval(loadGroup(workspaceId), row)
            } catch (cancelled: CancellationException) {
                recordFailure(tombstones.findForId(workspaceId) ?: row, cancelled)
                throw cancelled
            } catch (failure: Throwable) {
                recordFailure(tombstones.findForId(workspaceId) ?: row, failure)
                throw failure
            }
        }
    }

    /** Route/session filters can use this while a stale remote list is still in flight. */
    suspend fun isSessionRemoved(sessionId: String, cwd: String? = null): Boolean = withContext(Dispatchers.IO) {
        if (tombstones.readAll().any { sessionId in it.sessionIds }) return@withContext true
        val session = dao.session(sessionId)
        val workspace = session?.let { dao.workspace(it.workspaceId) }
        workspace?.let { workspaceRow ->
            harnessProjectSuppressed(context, workspaceRow) ||
                // A DSH session id is never adopted by a different managed path. This guards
                // against an old session list arriving after a runtime reinstall or after a
                // project was recreated with the same display title.
                (cwd != null && cwd.isNotBlank() &&
                    !harnessPathContains(workspaceRow.guestPath, cwd))
        } == true ||
            (cwd != null && cwd.startsWith("/workspace/projects/") &&
                harnessProjectSuppressed(
                    context,
                    "LOCAL_PROOT",
                    cwd.removePrefix("/workspace/projects/").substringBefore('/'),
                ))
    }

    /** Lets the project landing page hide completed/pending tombstones without duplicating identity rules. */
    fun visibleWorkspaceIds(rows: List<HarnessWorkspaceEntity>): Set<String> = rows
        .filterNot { tombstones.findForWorkspace(it)?.state == TOMBSTONE_REMOVED }
        .mapTo(mutableSetOf()) { it.id }

    private suspend fun completeRemoval(group: ProjectGroup, row: HarnessProjectTombstone): HarnessProjectRemovalResult {
        ensureStopped()
        val prepared = if (row.deleteFiles && row.quarantineName == null) {
            val trashName = quarantineLocalRoot(group)
            val next = row.copy(state = TOMBSTONE_QUARANTINED, quarantineName = trashName, errorCode = null)
            tombstones.write(next)
            next
        } else row

        ensureStopped()
        if (prepared.deleteFiles) {
            val trash = quarantineDirectory(prepared.quarantineName ?: error("PROJECT_CLEANUP_PENDING"))
            if (trash.exists()) HarnessLegacyDeletion.deleteQuarantinedTree(trash)
        }
        val current = loadGroup(group.id)
        deleteLegacyIndex(current)
        val afterMetadata = prepared.copy(state = TOMBSTONE_METADATA_REMOVED, errorCode = null)
        tombstones.write(afterMetadata)
        val removed = afterMetadata.copy(state = TOMBSTONE_REMOVED, errorCode = null)
        tombstones.write(removed)
        return resultFor(removed, filesDeleted = afterMetadata.deleteFiles)
    }

    private suspend fun removeLocked(
        group: ProjectGroup,
        deleteFiles: Boolean,
    ): HarnessProjectRemovalResult {
        validateRemoval(group, deleteFiles)
        val row = group.tombstone ?: newTombstone(group, deleteFiles)
        val pending = row.copy(deleteFiles = deleteFiles, state = TOMBSTONE_PENDING, errorCode = null)
        tombstones.write(pending)
        try {
            return completeRemoval(group, pending)
        } catch (cancelled: CancellationException) {
            recordFailure(tombstones.findForId(group.id) ?: row, cancelled)
            throw cancelled
        } catch (failure: Throwable) {
            recordFailure(tombstones.findForId(group.id) ?: row, failure)
            throw failure
        }
    }

    private fun validateRemoval(group: ProjectGroup, deleteFiles: Boolean) {
        require(group.workspaces.isNotEmpty()) { "PROJECT_NOT_FOUND" }
        require(group.tombstone == null || group.tombstone.state != TOMBSTONE_REMOVED) {
            "PROJECT_REMOVED"
        }
        require(!group.remoteIdentityAmbiguous) { "REMOTE_PROJECT_IDENTITY_AMBIGUOUS" }
        if (deleteFiles) {
            require(HarnessProjectManagementRules.fileDeletionSupported(group.backend)) {
                "REMOTE_PROJECT_FILE_DELETE_UNSUPPORTED"
            }
        }
        require(group.tombstone == null || group.tombstone.deleteFiles == deleteFiles) {
            "PROJECT_CLEANUP_PENDING"
        }
    }

    private suspend fun loadBatchGroups(workspaceIds: Collection<String>): List<ProjectGroup> {
        require(workspaceIds.isNotEmpty()) { "PROJECT_NOT_FOUND" }
        val groups = workspaceIds.map { loadGroup(it) }
        val activeGroups = groups.filterNot { it.tombstone?.state == TOMBSTONE_REMOVED }
        require(activeGroups.isNotEmpty()) { "PROJECT_NOT_FOUND" }
        return activeGroups
            .distinctBy { group ->
                HarnessProjectManagementRules.key(
                    group.backend,
                    group.projectFolder,
                    group.connectionKey,
                )
            }
    }

    private suspend fun deleteLegacyIndex(group: ProjectGroup) {
        require(!group.remoteIdentityAmbiguous) { "REMOTE_PROJECT_IDENTITY_AMBIGUOUS" }
        val removable = group.conversations.filter {
            it.runtimeSource == AgentRuntimeSource.LEGACY_ARCHIVE ||
                it.runtimeSource == AgentRuntimeSource.WORKSPACE_ONLY
        }
        removable.forEach { AgentSleepWakeScheduler.cancelConversation(context, it.id) }
        database.withTransaction {
            removable.forEach { conversation ->
                database.aiRuntimeJobDao().deleteByConversationId(conversation.id)
                chats.deleteConversationById(conversation.id)
            }
        }
    }

    private fun quarantineLocalRoot(group: ProjectGroup): String {
        require(group.backend != REMOTE_BACKEND) { "REMOTE_PROJECT_FILE_DELETE_UNSUPPORTED" }
        val managed = File(context.filesDir, "agent_local_workspaces").canonicalFile
        val root = AgentLocalWorkspaceSupport.rootPathForProject(context, group.projectFolder)
        require(!Files.isSymbolicLink(root.toPath())) { "WORKSPACE_PATH_OUTSIDE_SCOPE" }
        require(root.canonicalFile.parentFile == managed) { "WORKSPACE_PATH_OUTSIDE_SCOPE" }
        val name = "project-${HarnessProjectManagementRules.key(group.backend, group.projectFolder, group.connectionKey)}"
        val trash = quarantineDirectory(name)
        if (root.exists()) {
            check(!trash.exists()) { "PROJECT_CLEANUP_PENDING" }
            check(quarantine.isDirectory || quarantine.mkdirs()) { "PROJECT_CLEANUP_PENDING" }
            runCatching {
                Files.move(root.toPath(), trash.toPath(), StandardCopyOption.ATOMIC_MOVE)
            }.getOrElse {
                Files.move(root.toPath(), trash.toPath())
            }
        }
        return name
    }

    private fun quarantineDirectory(name: String): File {
        require(name.matches(Regex("project-[0-9a-f]{64}"))) { "PROJECT_CLEANUP_PENDING" }
        val root = quarantine.canonicalFile
        val target = File(root, name).canonicalFile
        require(target.parentFile == root) { "PROJECT_CLEANUP_PENDING" }
        return target
    }

    private suspend fun recordFailure(row: HarnessProjectTombstone, failure: Throwable) {
        val knownCodes = setOf(
            "PROJECT_CLEANUP_PENDING", "PROJECT_RECEIPT_DIRECTORY_FAILED", "PROJECT_RECEIPT_WRITE_FAILED",
            "PROJECT_PATH_OUTSIDE_SCOPE", "WORKSPACE_PATH_OUTSIDE_SCOPE", "REMOTE_PROJECT_FILE_DELETE_UNSUPPORTED",
            "REMOTE_PROJECT_IDENTITY_AMBIGUOUS", "PROJECT_SHARED_BY_OTHER_THREADS", "PROJECT_NOT_FOUND",
        )
        val code = failure.message?.takeIf { it in knownCodes }
            ?: failure.javaClass.simpleName
        try {
            withContext(NonCancellable) {
                tombstones.write(row.copy(state = row.state.takeIf { it != TOMBSTONE_REMOVED } ?: TOMBSTONE_PENDING, errorCode = code))
            }
        } catch (_: Throwable) { /* Keep the original cleanup failure authoritative. */ }
    }

    private suspend fun previewLocked(id: String): HarnessProjectRemovalPreview {
        val group = loadGroup(id)
        val row = group.tombstone
        val filesPresent = group.backend != REMOTE_BACKEND && localRoot(group.projectFolder).exists()
        return HarnessProjectRemovalPreview(
            id = id,
            title = group.title,
            projectFolder = group.projectFolder,
            backend = group.backend,
            connectionKey = group.connectionKey,
            threadCount = group.sessions.size,
            legacyConversationCount = group.conversations.count { it.runtimeSource == AgentRuntimeSource.LEGACY_ARCHIVE },
            aliasCount = group.workspaces.size,
            filesPresent = filesPresent,
            fileDeletionSupported = HarnessProjectManagementRules.fileDeletionSupported(group.backend),
            pendingRemoval = row?.pending == true,
            pendingDeleteFiles = row?.takeIf { it.pending }?.deleteFiles == true,
            cleanupErrorCode = row?.errorCode,
            retainedCanonicalSessions = group.sessions.size,
        )
    }

    private fun resultFor(row: HarnessProjectTombstone, filesDeleted: Boolean): HarnessProjectRemovalResult =
        HarnessProjectRemovalResult(row.projectId, 0, row.sessionIds.size, filesDeleted)

    private fun newTombstone(group: ProjectGroup, deleteFiles: Boolean) = HarnessProjectTombstone(
        projectId = group.id,
        title = group.title,
        projectFolder = if (group.backend == REMOTE_BACKEND) group.projectFolder
        else AgentLocalWorkspaceSupport.sanitizeProjectFolder(group.projectFolder),
        backend = HarnessProjectManagementRules.groupBackend(group.backend),
        connectionKey = group.connectionKey,
        workspaceIds = group.workspaces.mapTo(mutableSetOf()) { it.id },
        sessionIds = group.sessions.mapTo(mutableSetOf()) { it.harnessSessionId },
        deleteFiles = deleteFiles,
        state = TOMBSTONE_PENDING,
    )

    private suspend fun loadGroup(id: String): ProjectGroup {
        val allWorkspaces = dao.workspaces()
        val anchor = allWorkspaces.firstOrNull { it.id == id }
        val row = tombstones.findForId(id)
        if (anchor == null && row == null) error("PROJECT_NOT_FOUND")
        val folder = anchor?.projectFolder ?: row!!.projectFolder
        val connection = anchor?.connectionKey ?: row!!.connectionKey
        val workspaces = if (anchor != null) allWorkspaces.filter { HarnessProjectManagementRules.sameWorkspace(anchor, it) }
        else allWorkspaces.filter {
            HarnessProjectManagementRules.groupBackend(it.backend) == row!!.backend &&
                (if (row.backend == REMOTE_BACKEND) it.projectFolder == row.projectFolder && it.connectionKey == row.connectionKey
                else AgentLocalWorkspaceSupport.sanitizeProjectFolder(it.projectFolder) == row.projectFolder)
        }
        val candidateConversations = dao.conversations().filter { conversation ->
            if (anchor != null) HarnessProjectManagementRules.sameConversation(anchor, conversation)
            else if (row!!.backend == REMOTE_BACKEND) conversation.workspaceBackend == REMOTE_BACKEND && conversation.projectFolder == row.projectFolder
            else conversation.workspaceBackend != REMOTE_BACKEND &&
                AgentLocalWorkspaceSupport.sanitizeProjectFolder(conversation.projectFolder) == row.projectFolder
        }
        val remoteConnections = if (anchor?.backend == REMOTE_BACKEND) allWorkspaces
            .filter { it.backend == REMOTE_BACKEND && it.projectFolder == anchor.projectFolder }
            .map { it.connectionKey }.toSet()
        else emptySet<String>()
        val remoteIdentityAmbiguous = anchor?.backend == REMOTE_BACKEND && remoteConnections.size > 1 &&
            candidateConversations.any {
                it.runtimeSource == AgentRuntimeSource.LEGACY_ARCHIVE || it.runtimeSource == AgentRuntimeSource.WORKSPACE_ONLY
            }
        val conversations = candidateConversations
        val sessions = dao.observeSessionsOnce().filter { it.workspaceId in workspaces.map { workspace -> workspace.id }.toSet() }
        return ProjectGroup(
            id = anchor?.id ?: row!!.projectId,
            title = anchor?.title ?: row!!.title,
            projectFolder = folder,
            backend = anchor?.backend ?: if (row!!.backend == REMOTE_BACKEND) REMOTE_BACKEND else "LOCAL_PROOT",
            connectionKey = connection,
            workspaces = workspaces,
            conversations = conversations,
            sessions = sessions,
            tombstone = row,
            remoteIdentityAmbiguous = remoteIdentityAmbiguous,
        )
    }

    private fun localRoot(folder: String): File = AgentLocalWorkspaceSupport.rootPathForProject(context, folder)
}

/** Guest cwd belongs to a workspace only when it is the workspace root or a child of it. */
internal fun harnessPathContains(workspacePath: String, cwd: String): Boolean {
    fun normalize(path: String): String = path.trim().trimEnd('/').ifBlank { "/" }
    val root = normalize(workspacePath)
    val child = normalize(cwd)
    return child == root || child.startsWith("$root/")
}

private suspend fun com.example.llamadroid.data.db.HarnessDao.observeSessionsOnce(): List<HarnessSessionEntity> =
    observeSessions().first()
