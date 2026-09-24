package com.example.llamadroid.harness

import android.content.Context
import androidx.room.withTransaction
import com.example.llamadroid.data.db.AgentConversationEntity
import com.example.llamadroid.data.db.AgentRuntimeSource
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.data.db.HarnessSessionEntity
import com.example.llamadroid.data.db.HarnessWorkspaceEntity
import com.example.llamadroid.service.AgentLocalWorkspaceSupport
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.util.UUID

data class HarnessSessionScope(
    val session: HarnessSessionEntity,
    override val workspace: HarnessWorkspaceEntity,
    override val conversation: AgentConversationEntity,
    override val localRoot: File?
) : HarnessWorkspaceScope

/** File browsing can capture a project before any Harness session has been created. */
data class HarnessOfflineWorkspaceScope(
    override val workspace: HarnessWorkspaceEntity,
    override val conversation: AgentConversationEntity,
    override val localRoot: File?
) : HarnessWorkspaceScope

interface HarnessWorkspaceScope {
    val workspace: HarnessWorkspaceEntity
    val conversation: AgentConversationEntity
    val localRoot: File?
    val knowledgeBaseIds: List<Long> get() = conversation.knowledgeBaseIds.split(',')
        .mapNotNull { it.trim().toLongOrNull() }.filter { it > 0 }.distinct()

    fun localFile(path: String): File {
        val root = requireNotNull(localRoot) { "WORKSPACE_IS_REMOTE" }.canonicalFile
        val relative = when {
            path == workspace.guestPath -> ""
            path.startsWith(workspace.guestPath + "/") -> path.removePrefix(workspace.guestPath + "/")
            path.startsWith('/') -> throw IllegalArgumentException("WORKSPACE_PATH_OUTSIDE_SCOPE")
            else -> path
        }
        require(!relative.contains('\u0000'))
        val candidate = File(root, relative).canonicalFile
        require(candidate == root || candidate.toPath().startsWith(root.toPath())) { "WORKSPACE_PATH_OUTSIDE_SCOPE" }
        return candidate
    }

    /** Unlink/rename address the directory entry itself, without following its final symlink. */
    fun localEntry(path: String): File {
        val relative = when {
            path == workspace.guestPath -> "."
            path.startsWith(workspace.guestPath + "/") -> path.removePrefix(workspace.guestPath + "/")
            else -> path
        }.trimEnd('/')
        require(!relative.startsWith('/') && '\u0000' !in relative && relative.split('/').none { it == ".." })
        if (relative.isEmpty() || relative == ".") return requireNotNull(localRoot).canonicalFile
        return File(localFile(relative.substringBeforeLast('/', ".")), relative.substringAfterLast('/'))
    }
}

/** Workspace identity is captured by the session mapping, never the currently visible screen. */
class HarnessWorkspaceRepository(
    private val context: Context,
    private val database: AppDatabase,
    private val credentials: HarnessCredentialStore
) {
    private val dao = database.harnessDao()
    private val chats = database.agentChatDao()

    suspend fun importLegacyWorkspaces() {
        dao.conversations().filter {
            it.runtimeSource == AgentRuntimeSource.LEGACY_ARCHIVE &&
                !harnessProjectSuppressed(context, it.workspaceBackend, it.projectFolder)
        }.forEach { conversation ->
            ensureLegacyWorkspace(conversation)
        }
    }

    private suspend fun ensureLegacyWorkspace(conversation: AgentConversationEntity): HarnessWorkspaceEntity {
        require(!harnessProjectSuppressed(context, conversation.workspaceBackend, conversation.projectFolder)) {
            "PROJECT_REMOVED"
        }
        return dao.workspaceForRoot(conversation.workspaceBackend, conversation.projectFolder) ?: run {
                val folder = AgentLocalWorkspaceSupport.sanitizeProjectFolder(conversation.projectFolder)
                HarnessWorkspaceEntity(
                    id = "legacy-${conversation.id}", backend = conversation.workspaceBackend,
                    projectFolder = conversation.projectFolder, title = conversation.title,
                    guestPath = if (conversation.workspaceBackend == "REMOTE_SSH") "/workspace/remote/legacy-${conversation.id}" else "/workspace/projects/$folder",
                    prootEnvironmentId = conversation.prootEnvironmentId,
                    createdAt = conversation.createdAt
                ).also { dao.saveWorkspace(it) }
        }
    }

    suspend fun createWorkspace(title: String, remote: Boolean = false): HarnessWorkspaceEntity {
        val baseFolder = AgentLocalWorkspaceSupport.sanitizeProjectFolder(title).take(80).ifBlank { "project" }
        val folder = if (remote) baseFolder else baseFolder.take(71) + "-" + UUID.randomUUID().toString().take(8)
        if (!remote) {
            requireManagedLocalProjectRoot(folder)
            AgentLocalWorkspaceSupport.rootForProject(context, folder)
        }
        val id = UUID.randomUUID().toString()
        return HarnessWorkspaceEntity(
            id = id, backend = if (remote) "REMOTE_SSH" else "LOCAL_PROOT", projectFolder = folder,
            connectionKey = if (remote) id else "",
            title = title, guestPath = if (remote) "/workspace/remote/$id" else "/workspace/projects/$folder"
        ).also { dao.saveWorkspace(it) }
    }

    fun sshSummary(workspaceId: String): JSONObject {
        val saved = credentials.readRecord("adt-ssh/$workspaceId")?.getJSONObject("record")?.getJSONObject("payload")
        return JSONObject().put("host", saved?.optString("host") ?: "127.0.0.1")
            .put("port", saved?.optInt("port") ?: 8023).put("username", saved?.optString("username") ?: "root")
            .put("configured", saved != null)
    }

    suspend fun connectExplorer(scope: HarnessSessionScope, agent: com.example.llamadroid.service.AgentService) {
        if (scope.workspace.backend != "REMOTE_SSH") return
        val configuration = requireNotNull(credentials.readRecord("adt-ssh/${scope.workspace.id}")) { "SSH_CONFIGURATION_REQUIRED" }
            .getJSONObject("record").getJSONObject("payload")
        agent.connect(configuration.getString("host"), configuration.getInt("port"), configuration.getString("username"),
            configuration.optString("password")).getOrThrow()
    }

    suspend fun configureSsh(workspaceId: String, configuration: JSONObject) {
        val workspace = requireNotNull(dao.workspace(workspaceId))
        require(workspace.backend == "REMOTE_SSH")
        require(configuration.getString("host").isNotBlank())
        require(configuration.getInt("port") in 1..65535)
        require(configuration.getString("username").isNotBlank())
        val key = "adt-ssh/$workspaceId"
        val previous = credentials.readRecord(key)
        if (configuration.optString("password").isEmpty()) {
            previous?.getJSONObject("record")?.getJSONObject("payload")?.optString("password")?.let { configuration.put("password", it) }
        }
        credentials.replaceRecord(key, previous?.getString("revision"),
            JSONObject().put("kind", "grant").put("payload", configuration))
    }

    suspend fun importSession(
        sessionId: String,
        title: String,
        cwd: String,
        preferredWorkspaceId: String? = null,
        archived: Boolean = false,
        harnessWorkspaceId: String? = null,
        workspaceTitle: String? = null,
    ): HarnessSessionEntity = database.withTransaction {
        require(sessionId.isNotBlank() && sessionId.length <= 256)
        require(HarnessSessionDeletionStore(context).read(sessionId)?.removed != true) { "SESSION_REMOVED" }
        dao.session(sessionId)?.let { old ->
            val existingWorkspace = requireNotNull(dao.workspace(old.workspaceId)) { "WORKSPACE_NOT_FOUND" }
            requireProjectAvailable(existingWorkspace)
            require(harnessSessionPathMatchesProject(cwd, existingWorkspace.guestPath,
                exact = harnessWorkspaceId != null)) {
                "WORKSPACE_IDENTITY_MISMATCH"
            }
            if (harnessWorkspaceId != null) {
                val wasUnbound = existingWorkspace.harnessWorkspaceId == null
                val bound = bindHarnessWorkspace(existingWorkspace, harnessWorkspaceId)
                if (wasUnbound) adoptCanonicalGroupTitle(bound, harnessWorkspaceId, workspaceTitle)
            }
            if (title.isNotBlank() && title != sessionId) chats.updateConversationTitle(old.conversationId, title)
            return@withTransaction old.copy(archived = archived, updatedAt = System.currentTimeMillis())
                .also { dao.saveSession(it) }
        }
        val preferred = preferredWorkspaceId?.let { dao.workspace(it) }
        require(preferredWorkspaceId == null || preferred != null) { "WORKSPACE_NOT_FOUND" }
        require(preferred == null || harnessSessionPathMatchesProject(cwd, preferred.guestPath,
            exact = harnessWorkspaceId != null)) {
            "WORKSPACE_IDENTITY_MISMATCH"
        }
        val known = preferred
            ?: dao.workspaces().firstOrNull {
                harnessSessionPathMatchesProject(cwd, it.guestPath, exact = harnessWorkspaceId != null)
            }
        val workspace = (known ?: workspaceForGuestPath(cwd, workspaceTitle ?: title)).let { candidate ->
            require(harnessSessionPathMatchesProject(cwd, candidate.guestPath,
                exact = harnessWorkspaceId != null)) { "WORKSPACE_IDENTITY_MISMATCH" }
            if (harnessWorkspaceId == null) candidate else {
                val wasUnbound = candidate.harnessWorkspaceId == null
                val bound = bindHarnessWorkspace(candidate, harnessWorkspaceId)
                if (wasUnbound) adoptCanonicalGroupTitle(bound, harnessWorkspaceId, workspaceTitle)
                else bound
            }
        }
        requireProjectAvailable(workspace)
        // Copy project presentation/configuration from legacy history without copying its execution state.
        val legacy = dao.conversations().firstOrNull {
            it.projectFolder == workspace.projectFolder && it.workspaceBackend == workspace.backend
        }
        val conversationId = chats.insertConversation(AgentConversationEntity(
            title = title, projectFolder = workspace.projectFolder, workspaceBackend = workspace.backend,
            prootEnvironmentId = workspace.prootEnvironmentId, runtimeSource = AgentRuntimeSource.DEEPSEEK,
            projectFolderId = legacy?.projectFolderId, knowledgeBaseIds = legacy?.knowledgeBaseIds.orEmpty(),
            runEntrypointPath = legacy?.runEntrypointPath, runUiMode = legacy?.runUiMode ?: "CONSOLE",
            lastRunProfileJson = legacy?.lastRunProfileJson.orEmpty(), previewUrlOverride = legacy?.previewUrlOverride,
            directReanchorState = "COMPLETE"
        ))
        HarnessSessionEntity(sessionId, conversationId, workspace.id, archived = archived)
            .also { dao.saveSession(it) }
    }

    /** Register an existing managed guest directory selected in the WebUI as an app project. */
    suspend fun registerManagedHarnessWorkspace(guestPath: String, title: String): HarnessWorkspaceEntity =
        database.withTransaction {
            val normalizedPath = normalizeHarnessGuestPath(guestPath)
            val folder = normalizedPath.removePrefix("/workspace/projects/")
            require(normalizedPath == "/workspace/projects/$folder" &&
                folder.isNotBlank() && '/' !in folder &&
                folder == AgentLocalWorkspaceSupport.sanitizeProjectFolder(folder)) {
                "WORKSPACE_NOT_MANAGED"
            }
            requireManagedLocalProjectRoot(folder)
            val existing = dao.workspaces().firstOrNull {
                normalizeHarnessGuestPath(it.guestPath) == normalizedPath
            }
            if (existing != null) {
                // A stale WebUI create request must not turn a durable removal
                // receipt back into a visible Room project with the same path.
                requireProjectAvailable(existing)
                return@withTransaction existing
            }
            workspaceForGuestPath(normalizedPath, title.trim().take(120).ifBlank { folder })
        }

    /** A guest project must resolve to its own direct child of the app-managed root. */
    internal fun verifyManagedLocalProjectRoot(projectFolder: String) {
        requireManagedLocalProjectRoot(projectFolder)
    }

    private fun requireManagedLocalProjectRoot(projectFolder: String) {
        val entry = AgentLocalWorkspaceSupport.rootPathForProject(context, projectFolder)
        val appFiles = context.filesDir.canonicalFile
        val managedRoot = requireNotNull(entry.parentFile).canonicalFile
        val physical = entry.canonicalFile
        require(managedRoot.name == "agent_local_workspaces" && managedRoot.parentFile == appFiles &&
            physical.parentFile == managedRoot && !Files.isSymbolicLink(entry.toPath())) {
            "WORKSPACE_NOT_MANAGED"
        }
    }

    private suspend fun bindHarnessWorkspace(
        workspace: HarnessWorkspaceEntity,
        harnessWorkspaceId: String,
    ): HarnessWorkspaceEntity {
        require(harnessWorkspaceId.isNotBlank() && harnessWorkspaceId.length <= 256) {
            "WORKSPACE_IDENTITY_MISMATCH"
        }
        require(workspace.harnessWorkspaceId == null || workspace.harnessWorkspaceId == harnessWorkspaceId) {
            "HARNESS_WORKSPACE_ID_CONFLICT"
        }
        val owner = dao.workspaces().firstOrNull {
            it.id != workspace.id && it.harnessWorkspaceId == harnessWorkspaceId
        }
        require(owner == null || harnessAliasesMayShareGroup(owner, workspace)) {
            "HARNESS_WORKSPACE_ID_CONFLICT"
        }
        // Room keeps this ID unique. A legacy alias of the same project may
        // use its canonical owner's ID without writing a duplicate index row.
        if (owner != null) return workspace
        if (workspace.harnessWorkspaceId == harnessWorkspaceId) return workspace
        return workspace.copy(harnessWorkspaceId = harnessWorkspaceId, updatedAt = System.currentTimeMillis())
            .also { dao.saveWorkspace(it) }
    }

    /** Adopt DSH's title once when binding a legacy row; later renames use the explicit route. */
    private suspend fun adoptCanonicalGroupTitle(
        workspace: HarnessWorkspaceEntity,
        harnessWorkspaceId: String,
        title: String?,
    ): HarnessWorkspaceEntity {
        val normalized = title?.let { runCatching { HarnessProjectManagementRules.normalizeTitle(it) }.getOrNull() }
            ?: return workspace
        val now = System.currentTimeMillis()
        var result = workspace
        dao.workspaces()
            .filter { candidate ->
                HarnessProjectManagementRules.sameWorkspace(workspace, candidate) &&
                    normalizeHarnessGuestPath(candidate.guestPath) == normalizeHarnessGuestPath(workspace.guestPath) &&
                    (candidate.harnessWorkspaceId == null || candidate.harnessWorkspaceId == harnessWorkspaceId)
            }
            .forEach { candidate ->
                if (candidate.title != normalized) {
                    val updated = candidate.copy(title = normalized, updatedAt = now)
                    dao.saveWorkspace(updated)
                    if (candidate.id == workspace.id) result = updated
                }
            }
        return result
    }

    /**
     * Stores the canonical DSH identity returned by `adt.prepareWorkspace` before a session is
     * created.  The guest path is checked against the local workspace so a response for a stale
     * or different project cannot attach a new session to the wrong group.
     */
    internal suspend fun persistPreparedWorkspace(
        localWorkspaceId: String,
        prepared: HarnessPreparedWorkspace,
    ): HarnessWorkspaceEntity? = database.withTransaction {
        val workspace = dao.workspace(localWorkspaceId) ?: return@withTransaction null
        require(normalizeHarnessGuestPath(workspace.guestPath) == prepared.guestPath) {
            "WORKSPACE_IDENTITY_MISMATCH"
        }
        require(workspace.harnessWorkspaceId == null || workspace.harnessWorkspaceId == prepared.workspaceId) {
            "HARNESS_WORKSPACE_ID_CONFLICT"
        }
        val conflict = dao.workspaces().firstOrNull {
            it.id != workspace.id && it.harnessWorkspaceId == prepared.workspaceId
        }
        require(conflict == null || harnessAliasesMayShareGroup(conflict, workspace)) {
            "HARNESS_WORKSPACE_ID_CONFLICT"
        }
        if (conflict != null) return@withTransaction workspace
        val updated = workspace.copy(
            harnessWorkspaceId = prepared.workspaceId,
            updatedAt = System.currentTimeMillis(),
        )
        if (updated != workspace) dao.saveWorkspace(updated)
        updated
    }

    /**
     * Persists the DSH workspace identity against the app workspace that owns the same guest
     * path.  The DSH workspace id is the canonical grouping key used by the WebUI; retaining it
     * here lets native and WebUI sessions resolve to the same project after a cold start.
     */
    suspend fun reconcileHarnessWorkspace(
        harnessWorkspaceId: String,
        guestPath: String,
        title: String? = null,
    ): HarnessWorkspaceEntity? = database.withTransaction {
        if (harnessWorkspaceId.isBlank()) return@withTransaction null
        val normalizedPath = normalizeHarnessGuestPath(guestPath)
        if (normalizedPath.isBlank()) return@withTransaction null
        val current = dao.workspaces()
        val existing = current.firstOrNull { it.harnessWorkspaceId == harnessWorkspaceId }
        val candidate = current
            .filter { normalizeHarnessGuestPath(it.guestPath) == normalizedPath }
            .firstOrNull { it.id == existing?.id }
            ?: current.firstOrNull { normalizeHarnessGuestPath(it.guestPath) == normalizedPath }
            ?: normalizedPath.takeIf { path ->
                val folder = path.removePrefix("/workspace/projects/")
                path == "/workspace/projects/$folder" && folder.isNotBlank() && '/' !in folder &&
                    folder == AgentLocalWorkspaceSupport.sanitizeProjectFolder(folder)
            }?.let { path ->
                workspaceForGuestPath(path, title?.trim()?.take(120).orEmpty()
                    .ifBlank { path.substringAfterLast('/') })
            }
            ?: return@withTransaction null
        // A stale DSH row must never steal an identity that is already bound to a different
        // managed path. The stream can retry once the authoritative group is emitted again.
        if (existing != null && existing.id != candidate.id) return@withTransaction null
        if (candidate.harnessWorkspaceId != null && candidate.harnessWorkspaceId != harnessWorkspaceId) {
            return@withTransaction null
        }
        val updated = candidate.copy(
            harnessWorkspaceId = harnessWorkspaceId,
            updatedAt = System.currentTimeMillis(),
        )
        if (updated != candidate) dao.saveWorkspace(updated)
        // A Workspace group event is the authoritative DSH title observation.
        // Native offline renames pass no title here until their durable pending
        // receipt is confirmed, while ordinary WebUI renames update every alias.
        if (title != null) adoptCanonicalGroupTitle(updated, harnessWorkspaceId, title) else updated
    }

    /** Associate the exact Android project selected by a native or WebUI caller. */
    suspend fun associateHarnessWorkspace(
        localWorkspaceId: String,
        harnessWorkspaceId: String,
        guestPath: String,
        title: String? = null,
    ): Boolean = database.withTransaction {
        val workspace = dao.workspace(localWorkspaceId) ?: return@withTransaction false
        if (normalizeHarnessGuestPath(workspace.guestPath) != normalizeHarnessGuestPath(guestPath)) {
            return@withTransaction false
        }
        requireProjectAvailable(workspace)
        val wasUnbound = workspace.harnessWorkspaceId == null
        val bound = bindHarnessWorkspace(workspace, harnessWorkspaceId)
        if (wasUnbound) adoptCanonicalGroupTitle(bound, harnessWorkspaceId, title)
        true
    }

    /** Returns every app workspace alias in the same project group for DSH rename propagation. */
    suspend fun harnessWorkspaceIdsForProject(workspaceId: String): List<String> = database.withTransaction {
        val anchor = dao.workspace(workspaceId) ?: return@withTransaction emptyList()
        dao.workspaces()
            .filter { HarnessProjectManagementRules.sameWorkspace(anchor, it) }
            .mapNotNull { it.harnessWorkspaceId }
            .distinct()
    }

    /** Mirror a WebUI group rename to every Android alias of the exact canonical group. */
    suspend fun renameCanonicalHarnessWorkspace(
        harnessWorkspaceId: String,
        title: String,
    ): Boolean {
        val renamed = database.withTransaction {
            val normalized = HarnessProjectManagementRules.normalizeTitle(title)
            val anchor = dao.workspaces().firstOrNull { it.harnessWorkspaceId == harnessWorkspaceId }
                ?: return@withTransaction false
            requireProjectAvailable(anchor)
            val now = System.currentTimeMillis()
            dao.workspaces().filter { HarnessProjectManagementRules.sameWorkspace(anchor, it) }
                .forEach { dao.saveWorkspace(it.copy(title = normalized, updatedAt = now)) }
            true
        }
        if (renamed) HarnessWorkspaceTitleSyncStore(context).clear(harnessWorkspaceId)
        return renamed
    }

    suspend fun scope(sessionId: String): HarnessSessionScope {
        val session = requireNotNull(dao.session(sessionId)) { "SESSION_NOT_INDEXED" }
        val workspace = requireNotNull(dao.workspace(session.workspaceId)) { "WORKSPACE_NOT_FOUND" }
        requireProjectAvailable(workspace)
        val conversation = requireNotNull(chats.getConversation(session.conversationId)) { "SESSION_NOT_FOUND" }
        val root = if (workspace.backend == "REMOTE_SSH") null else
            AgentLocalWorkspaceSupport.rootForProject(context, workspace.projectFolder)
        return HarnessSessionScope(session, workspace, conversation, root).also { captured ->
            if (root != null) threadDirectory(captured)
        }
    }

    suspend fun fileScopeForConversation(conversationId: Long, allowPendingDeletion: Boolean = false): HarnessWorkspaceScope {
        dao.sessionForConversation(conversationId)?.let { return scope(it.harnessSessionId) }
        val conversation = requireNotNull(chats.getConversation(conversationId)) { "WORKSPACE_NOT_FOUND" }
        val workspace = if (conversation.runtimeSource == AgentRuntimeSource.WORKSPACE_ONLY) dao.workspace(conversation.title)
        else dao.workspaces().firstOrNull {
            it.projectFolder == conversation.projectFolder && it.backend == conversation.workspaceBackend
        } ?: conversation.takeIf { it.runtimeSource == AgentRuntimeSource.LEGACY_ARCHIVE }?.let { ensureLegacyWorkspace(it) }
        requireNotNull(workspace) { "WORKSPACE_NOT_FOUND" }
        // Browsing must not recreate a folder already moved into cleanup quarantine.
        // Only the explicit cleanup retry may obtain a scope for its remaining SFTP work.
        if (!allowPendingDeletion) requireProjectAvailable(workspace)
        return HarnessOfflineWorkspaceScope(workspace, conversation,
            if (workspace.backend == "REMOTE_SSH") null else AgentLocalWorkspaceSupport.rootForProject(context, workspace.projectFolder))
    }

    /** Presentation anchor only; this never invents or submits a DeepSeek session. */
    suspend fun openWorkspace(workspaceId: String): HarnessWorkspaceScope = database.withTransaction {
        val workspace = requireNotNull(dao.workspace(workspaceId)) { "WORKSPACE_NOT_FOUND" }
        requireProjectAvailable(workspace)
        val conversations = dao.conversations()
        val bound = conversations.firstOrNull {
            it.runtimeSource == AgentRuntimeSource.DEEPSEEK && dao.sessionForConversation(it.id)?.workspaceId == workspaceId
        }
        val anchor = conversations.firstOrNull { it.runtimeSource == AgentRuntimeSource.WORKSPACE_ONLY && it.title == workspaceId }
        val template = conversations.firstOrNull {
            it.runtimeSource == AgentRuntimeSource.LEGACY_ARCHIVE && it.projectFolder == workspace.projectFolder && it.workspaceBackend == workspace.backend
        }
        val conversation = bound ?: anchor ?: AgentConversationEntity(
            title = workspace.id, projectFolder = workspace.projectFolder, workspaceBackend = workspace.backend,
            prootEnvironmentId = workspace.prootEnvironmentId, runtimeSource = AgentRuntimeSource.WORKSPACE_ONLY,
            projectFolderId = template?.projectFolderId, knowledgeBaseIds = template?.knowledgeBaseIds.orEmpty(),
            runEntrypointPath = template?.runEntrypointPath, runUiMode = template?.runUiMode ?: "CONSOLE",
            lastRunProfileJson = template?.lastRunProfileJson.orEmpty(), previewUrlOverride = template?.previewUrlOverride,
            directReanchorState = "COMPLETE"
        ).let { fresh -> fresh.copy(id = chats.insertConversation(fresh)) }
        fileScopeForConversation(conversation.id)
    }

    /** Thread attachments and a pointer to the canonical Harness store live beside project files. */
    fun threadDirectory(scope: HarnessSessionScope): File {
        val id = scope.session.harnessSessionId
        val folder = if (id.matches(Regex("[A-Za-z0-9_-]{1,128}"))) id else
            java.security.MessageDigest.getInstance("SHA-256").digest(id.toByteArray()).joinToString("") { "%02x".format(it) }
        val directory = scope.localFile(".adt/threads/$folder")
        check(directory.isDirectory || directory.mkdirs())
        val reference = scope.localFile(".adt/threads/$folder/session.json")
        if (!reference.exists()) reference.writeText(JSONObject().put("sessionId", id)
            .put("workspaceId", scope.workspace.id).put("store", "deepseek-harness").toString())
        return directory
    }

    private fun requireProjectAvailable(workspace: HarnessWorkspaceEntity) {
        require(!harnessProjectSuppressed(context, workspace)) { "PROJECT_REMOVED" }
        val pending = File(context.filesDir, "agent_harness/project-deletions").listFiles().orEmpty().any { receipt ->
            receipt.name.matches(Regex("[0-9]+\\.json")) && runCatching {
                val row = JSONObject(receipt.readText())
                val remote = row.getString("backend") == "REMOTE_SSH"
                remote == (workspace.backend == "REMOTE_SSH") &&
                    (if (remote) row.getString("folder") == workspace.projectFolder else
                        AgentLocalWorkspaceSupport.sanitizeProjectFolder(row.getString("folder")) ==
                            AgentLocalWorkspaceSupport.sanitizeProjectFolder(workspace.projectFolder))
            }.getOrDefault(false)
        }
        require(!pending) { "PROJECT_CLEANUP_PENDING" }
    }

    private suspend fun workspaceForGuestPath(cwd: String, title: String): HarnessWorkspaceEntity {
        require(cwd.startsWith("/workspace/projects/")) { "WORKSPACE_NOT_MANAGED" }
        val suffix = cwd.removePrefix("/workspace/projects/").trimEnd('/')
        require(suffix.split('/').none { it == ".." || it == "." }) { "WORKSPACE_NOT_MANAGED" }
        val folder = suffix.substringBefore('/')
        require(folder.isNotBlank() && '/' !in folder && folder == AgentLocalWorkspaceSupport.sanitizeProjectFolder(folder)) {
            "WORKSPACE_NOT_MANAGED"
        }
        require(!harnessProjectSuppressed(context, "LOCAL_PROOT", folder)) { "PROJECT_REMOVED" }
        requireManagedLocalProjectRoot(folder)
        dao.workspaceForRoot("LOCAL_PROOT", folder)?.let { return it }
        AgentLocalWorkspaceSupport.rootForProject(context, folder)
        return HarnessWorkspaceEntity(
            id = UUID.randomUUID().toString(), backend = "LOCAL_PROOT", projectFolder = folder,
            title = title, guestPath = "/workspace/projects/$folder"
        ).also { dao.saveWorkspace(it) }
    }
}
