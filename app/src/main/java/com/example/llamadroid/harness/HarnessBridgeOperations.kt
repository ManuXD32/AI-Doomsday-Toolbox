package com.example.llamadroid.harness

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import com.example.llamadroid.data.SettingsRepository
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.data.model.ZimRepository
import com.example.llamadroid.data.repository.KnowledgeBaseRepository
import com.example.llamadroid.service.AgentImageOperations
import com.example.llamadroid.service.AgentPreviewBridge
import com.example.llamadroid.service.AgentRuntimeSupport
import com.example.llamadroid.service.AgentSkillRepository
import com.example.llamadroid.service.CustomToolExecutionMode
import com.example.llamadroid.service.CustomToolHttpExecutor
import com.example.llamadroid.service.KiwixService
import com.example.llamadroid.service.SkillPermission
import com.example.llamadroid.ui.agent.harness.HarnessDiscoveredModelUi
import com.example.llamadroid.ui.agent.harness.NativeHarnessCustomProviderRequest
import com.example.llamadroid.ui.agent.harness.NativeHarnessDraftDiscoveryException
import com.example.llamadroid.ui.agent.harness.NativeHarnessDraftProviderDiscovery
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URI
import java.net.URLEncoder
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

private const val KIWIX_BIND_TIMEOUT_MS = 2_000L

internal fun parseHarnessProviderDiscoveryRequest(args: JSONObject): NativeHarnessCustomProviderRequest {
    val requestArgs = args.optJSONObject("request") ?: args
    return NativeHarnessCustomProviderRequest(
        route = "draft",
        api = requestArgs.optString("api"),
        baseUrl = requestArgs.optString("baseURL").ifBlank { requestArgs.optString("baseUrl") },
        apiKey = requestArgs.optString("apiKey"),
        models = emptyList(),
    )
}

internal fun harnessProviderDiscoveryResponse(models: List<HarnessDiscoveredModelUi>): JSONObject =
    JSONObject()
        .put("status", if (models.isEmpty()) "empty" else "ok")
        .put("models", JSONArray().apply {
            models.forEach { model ->
                put(JSONObject().apply {
                    put("id", model.id)
                    model.name?.let { put("name", it) }
                    model.contextWindow?.let { put("contextWindow", it) }
                    model.maxTokens?.let { put("maxTokens", it) }
                    put("inputModalities", JSONArray(model.inputModalities))
                })
            }
        })

internal fun harnessProviderDiscoveryError(code: String): JSONObject =
    JSONObject()
        .put("status", "error")
        .put("models", JSONArray())
        .put("errorCode", code)

/** App capabilities extracted from legacy services, always scoped to the requesting Harness session. */
class HarnessBridgeOperations(
    private val context: Context,
    private val database: AppDatabase,
    private val credentials: HarnessCredentialStore,
    private val workspaces: HarnessWorkspaceRepository,
    private val files: HarnessWorkspaceAccess,
    private val models: HarnessLocalModels,
    private val diagnostics: HarnessDiagnostics,
    private val workspaceActions: HarnessWorkspaceActions? = null,
    private val executeLocal: suspend (HarnessSessionScope, String, String) -> Any
) {
    // Capture the canonical preferences on access; other app managers may edit them while Harness runs.
    private val settings: SettingsRepository get() = SettingsRepository(context)
    private val knowledge = KnowledgeBaseRepository(context, database)
    private val skills = AgentSkillRepository(context, database)
    private val zims = ZimRepository(context, database.zimDao())
    private val sshProcesses = HarnessSshProcesses(files, diagnostics)
    private val client = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()

    suspend fun invoke(method: String, sessionId: String?, args: JSONObject): Any? = withContext(Dispatchers.IO) {
        if (method.startsWith("credentials.")) {
            require(!args.optString("key").startsWith("adt-ssh/") && !args.optString("key").startsWith("adt-ssh-cleanup/")) { "CREDENTIAL_SCOPE_PRIVATE" }
            return@withContext credential(method, args)
        }
        if (method == "provider.models") return@withContext models.models()
        if (method == "provider.discover") return@withContext discoverProvider(args)
        // JSONL can publish a new/forked session before its native index exists.
        // This capability is constrained to that session's private store, not a workspace.
        if (method == "session.atomicPublish") return@withContext atomicPublishHarnessSession(
            context, requireNotNull(sessionId) { "SESSION_REQUIRED" },
            args.getString("sourcePath"), args.getString("destinationPath"),
        )
        if (method == "workspace.cleanup") {
            val ownedSession = requireNotNull(sessionId) { "SESSION_REQUIRED" }
            workspaces.scope(ownedSession)
            return@withContext JSONArray(files.retryCleanup(sessionId = ownedSession))
        }
        if (method == "workspace.prepare") {
            val workspace = requireNotNull(database.harnessDao().workspace(args.getString("workspaceId"))) { "WORKSPACE_NOT_FOUND" }
            require(workspace.guestPath == args.getString("guestPath") && workspace.backend == args.getString("backend")) { "WORKSPACE_IDENTITY_MISMATCH" }
            if (workspace.backend == "REMOTE_SSH") {
                check(workspaces.sshSummary(workspace.id).getBoolean("configured")) { "SSH_CONFIGURATION_REQUIRED" }
            } else com.example.llamadroid.service.AgentLocalWorkspaceSupport.rootForProject(context, workspace.projectFolder)
            return@withContext JSONObject().put("cwd", workspace.guestPath).put("workspaceId", workspace.id)
        }
        if (method == "session.index") {
            val id = requireNotNull(sessionId) { "SESSION_REQUIRED" }
            workspaces.importSession(id, args.optString("title", id), args.getString("cwd"), archived = args.optBoolean("archived"))
            return@withContext JSONObject().put("indexed", true)
        }
        val scope = workspaces.scope(requireNotNull(sessionId) { "SESSION_REQUIRED" })
        val settings by lazy { this@HarnessBridgeOperations.settings }
        if (method in setOf("research.kiwix", "research.kiwixRead")) check(settings.agentKiwixEnabled.value) { "KIWIX_DISABLED" }
        val started = System.currentTimeMillis()
        val journalSuccess = method !in setOf("workspace.process.read", "workspace.process.write", "workspace.process.resize")
        if (journalSuccess) diagnostics.event(sessionId, method, "STARTED")
        try {
            val result = when {
                method == "workspace.atomicPublish" -> files.atomicPublish(
                    scope, args.getString("sourcePath"), args.getString("destinationPath"),
                )
                method.startsWith("workspace.process.") -> sshProcesses.invoke(scope, method, args)
                method == "workspace.readBytes" -> {
                    val (bytes, eof) = files.readByteRange(scope, args.getString("path"), args.optLong("offset"), args.optInt("length", 512 * 1024))
                    JSONObject().put("dataBase64", android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)).put("eof", eof)
                }
                method == "workspace.writeBytes" -> {
                    val encoded = args.getString("dataBase64"); require(encoded.length <= 700_000)
                    val bytes = android.util.Base64.decode(encoded, android.util.Base64.DEFAULT); require(bytes.size <= 512 * 1024)
                    files.writeBytes(scope, args.getString("path"), bytes)
                    JSONObject().put("written", bytes.size)
                }
                method in setOf("workspace.run", "workspace.runStatus", "workspace.stopRun") -> {
                    val runs = HarnessAppRuntime.get(context).projectRuns
                    val state = when (method) {
                        "workspace.run" -> runs.run(scope.conversation.id)
                        "workspace.runStatus" -> runs.check(scope.conversation.id)
                        else -> runs.stop(scope.conversation.id)
                    }
                    JSONObject().put("status", state?.status ?: "STOPPED").put("logs", state?.logs.orEmpty())
                        .put("previewUrl", state?.previewUrl ?: JSONObject.NULL).put("exitCode", state?.exitCode ?: JSONObject.NULL)
                }
                method == "workspace.describe" -> JSONObject().put("workspaceId", scope.workspace.id)
                    .put("backend", scope.workspace.backend).put("guestPath", scope.workspace.guestPath)
                    .put("projectFolder", scope.workspace.projectFolder)
                method == "workspace.openPath" -> {
                    val path = args.getString("path")
                    val action = args.optString("action", "open")
                    require(action in setOf("open", "reveal")) { "WORKSPACE_ACTION_INVALID" }
                    val stat = files.invoke(scope, "workspace.stat", JSONObject().put("path", path)) as JSONObject
                    require(stat.optBoolean("exists", true)) { "WORKSPACE_FILE_MISSING" }
                    require(!stat.optBoolean("symbolicLink")) { "WORKSPACE_PATH_SYMLINK_REJECTED" }
                    requireNotNull(workspaceActions) { "WORKSPACE_UI_UNAVAILABLE" }
                        .open(scope, path, action == "reveal", stat.optBoolean("directory"))
                    JSONObject().put("opened", true)
                }
                method.startsWith("workspace.") -> {
                    if (method == "workspace.execute" && scope.localRoot != null) {
                        executeLocal(scope, args.getString("command"), args.optString("cwd", "."))
                    } else files.invoke(scope, method, args)
                }
                else -> when (method) {
                    "knowledge.search" -> JSONArray().apply {
                        knowledge.search(args.getString("query"), scope.knowledgeBaseIds, args.optInt("limit", 8).coerceIn(1, 30)).forEach {
                            put(JSONObject().put("chunkId", it.chunkId).put("sourceTitle", it.sourceTitle)
                                .put("knowledgeBaseId", it.knowledgeBaseId).put("text", it.text).put("citation", it.citationMarkdown).put("score", it.score))
                        }
                    }
                    "knowledge.read" -> knowledge.readChunk(args.getLong("chunkId"), args.optBoolean("neighbors"), scope.knowledgeBaseIds)
                    "knowledge.listSources" -> knowledge.listSources(scope.knowledgeBaseIds)
                    "research.kiwix" -> searchKiwix(args.getString("query"))
                    "research.kiwixRead" -> readKiwixArticle(
                        args.getString("url"),
                        args.optInt("maxChars", settings.agentKiwixMaxChars.value).coerceIn(100, 120_000)
                    )
                    "preview.observe" -> {
                        check(AgentPreviewBridge.hasActivePreview(scope.conversation.id)) { "PREVIEW_NOT_OPEN_FOR_SESSION" }
                        JSONObject(AgentPreviewBridge.observe(context, scope.conversation.id, args.optBoolean("screenshot", true)).getOrThrow().toJson())
                    }
                    "preview.interact" -> {
                        check(AgentPreviewBridge.hasActivePreview(scope.conversation.id)) { "PREVIEW_NOT_OPEN_FOR_SESSION" }
                        AgentPreviewBridge.interact(scope.conversation.id, args.getString("action"),
                            args.numberOrNull("x")?.toFloat(), args.numberOrNull("y")?.toFloat(),
                            args.optString("text").takeIf { args.has("text") }, args.optString("key").takeIf { args.has("key") },
                            args.numberOrNull("scrollDx")?.toInt(), args.numberOrNull("scrollDy")?.toInt(), args.numberOrNull("waitMs")?.toLong()).getOrThrow()
                    }
                    "images.generate", "images.removeBackground" -> image(scope, method, args)
                    "tools.list" -> JSONArray().apply { database.customToolDao().getEnabledToolsOnce().forEach {
                        put(JSONObject().put("name", it.name).put("description", it.description).put("parameters", JSONObject(it.parametersJson))
                            .put("required", JSONArray(it.requiredParamsJson)).put("needsApproval", it.needsApproval))
                    } }
                    "tools.execute" -> customTool(scope, args)
                    "skills.list" -> JSONArray().apply { database.agentWorkflowDao().getEnabledSkills().forEach {
                        val permission = skills.permissionFor(it, scope.conversation.id, "harness")
                        if (permission != SkillPermission.DENY) put(JSONObject().put("id", it.id).put("name", it.name)
                            .put("description", it.description).put("permission", permission.name))
                    } }
                    "skills.read" -> {
                        val skill = requireNotNull(skills.findSkill(args.getString("id"))) { "SKILL_NOT_FOUND" }
                        val permission = skills.permissionFor(skill, scope.conversation.id, "harness")
                        check(permission != SkillPermission.DENY) { "SKILL_DENIED" }
                        check(permission == SkillPermission.ALLOW || args.optBoolean("approved")) { "SKILL_APPROVAL_REQUIRED" }
                        skills.loadSkill(skill.id, scope.conversation.id, "harness", args.optBoolean("approved")).instructions
                    }
                    else -> error("BRIDGE_METHOD_UNKNOWN")
                }
            }
            if (journalSuccess) diagnostics.event(sessionId, method, "COMPLETED", System.currentTimeMillis() - started)
            result
        } catch (error: Exception) {
            diagnostics.event(sessionId, method, if (error is CancellationException) "CANCELLED" else "FAILED",
                System.currentTimeMillis() - started, error.javaClass.simpleName)
            throw error
        }
    }

    /**
     * Discovers an unsaved provider draft without persisting settings or returning its key.
     * The WebUI resolves saved provider credentials before calling this operation and passes the
    * same ephemeral `{api, baseURL, apiKey}` shape, so both paths share bounded native probing.
     */
    private suspend fun discoverProvider(args: JSONObject): JSONObject {
        val request = parseHarnessProviderDiscoveryRequest(args)
        return try {
            val models = NativeHarnessDraftProviderDiscovery.discover(request)
            harnessProviderDiscoveryResponse(models)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: NativeHarnessDraftDiscoveryException) {
            harnessProviderDiscoveryError(error.code)
        } catch (_: Exception) {
            harnessProviderDiscoveryError("PROVIDER_DISCOVERY_FAILED")
        }
    }

    fun cancelOwnedRequests() {
        sshProcesses.cancelAll(); client.dispatcher.cancelAll()
        models.cancelOwnedRequests(); files.cancelOwnedRequests()
        workspaceActions?.cancelPending()
    }

    private fun credential(method: String, args: JSONObject): Any? = when (method) {
        "credentials.resolve" -> credentials.resolve(args.getString("ref"))?.let { JSONObject().put("value", it).put("source", "android-keystore") }
        "credentials.describe" -> credentials.describe(args.getString("ref"))
        "credentials.set" -> credentials.set(args.getString("ref"), args.getString("value")).let { true }
        "credentials.unset" -> credentials.unset(args.getString("ref")).let { true }
        "credentials.readRecord" -> credentials.readRecord(args.getString("key"))
        "credentials.describeRecord" -> credentials.describeRecord(args.getString("key"))
        "credentials.listRecords" -> credentials.listRecords()
        "credentials.replaceRecord" -> credentials.replaceRecord(args.getString("key"), args.optString("expectedRevision").takeIf { it.isNotBlank() && it != "null" }, args.optJSONObject("record"))
        else -> error("BRIDGE_METHOD_UNKNOWN")
    }

    private suspend fun image(scope: HarnessSessionScope, method: String, args: JSONObject): JSONObject {
        val temp = File(context.cacheDir, "harness_images/${UUID.randomUUID()}").apply { mkdirs() }
        val operations = AgentImageOperations(
            context,
            inputFileForPath = { path ->
                if (scope.localRoot != null) scope.localFile(path) else File(temp, "input." + File(path).extension.lowercase().takeIf { it in setOf("png", "jpg", "jpeg", "webp", "bmp", "gif") }.orEmpty().ifBlank { "png" })
                    .apply { writeBytes(files.readBytes(scope, path)) }
            },
            sanitizePath = { path ->
                if (scope.localRoot != null) scope.localFile(path)
                val relative = path.removePrefix(scope.workspace.guestPath + "/")
                require(!relative.startsWith('/') && relative.split('/').none { it == ".." }) { "WORKSPACE_PATH_OUTSIDE_SCOPE" }
                scope.workspace.guestPath + "/" + relative
            },
            persistBytes = { path, bytes -> files.writeBytes(scope, path, bytes) },
            toProjectRelativePath = { it.removePrefix(scope.workspace.guestPath + "/") }
        )
        return try {
            val outputRelativePath = imageOutputRelativePath(scope, method, args)
            val message = if (method == "images.generate") {
                operations.generateImage(args.getString("prompt"), args.optString("negativePrompt"), outputRelativePath, settings).getOrThrow()
            } else {
                operations.removeImageBackground(args.getString("imagePath"), outputRelativePath, settings).getOrThrow()
            }
            val artifactPaths = buildList {
                add(outputRelativePath)
                if (method == "images.removeBackground" && settings.agentBackgroundRemovalExportMask.value) {
                    add(outputRelativePath.substringBeforeLast(".") + "_mask.png")
                }
            }.distinct()
            val artifacts = JSONArray().apply {
                artifactPaths.forEach { relative ->
                    val receipt = artifactReceipt(scope, relative)
                    if (receipt != null) put(receipt)
                }
            }
            val primary = artifactReceipt(scope, outputRelativePath)
                ?: error("IMAGE_OUTPUT_MISSING")
            JSONObject()
                .put("operation", method.removePrefix("images."))
                .put("path", outputRelativePath)
                .put("workspacePath", primary.getString("workspacePath"))
                .put("mimeType", primary.getString("mimeType"))
                .put("sizeBytes", primary.getLong("sizeBytes"))
                .put("read", primary.getJSONObject("read"))
                .put("artifacts", artifacts)
                .put("message", message)
        } finally { temp.deleteRecursively() }
    }

    private suspend fun artifactReceipt(scope: HarnessSessionScope, relative: String): JSONObject? {
        val guestPath = workspacePath(scope, relative)
        val stat = runCatching {
            files.invoke(scope, "workspace.stat", JSONObject().put("path", guestPath)) as JSONObject
        }.getOrNull() ?: return null
        if (stat.has("exists") && !stat.optBoolean("exists")) return null
        if (stat.optBoolean("directory") || stat.optBoolean("symbolicLink")) return null
        return JSONObject()
            .put("path", relative)
            .put("workspacePath", guestPath)
            // AgentImageOperations writes the pipeline result as PNG even
            // when the caller chooses a different filename extension. Declare
            // the bytes' real media type for dsh-attachment admission.
            .put("mimeType", "image/png")
            .put("sizeBytes", stat.optLong("size", -1L))
            .put("read", JSONObject()
                .put("method", "workspace.readBytes")
                .put("path", guestPath)
                .put("maxBytes", 512 * 1024))
    }

    private suspend fun customTool(scope: HarnessSessionScope, args: JSONObject): Any {
        val tool = requireNotNull(database.customToolDao().getToolByNameIgnoreCase(args.getString("name"))) { "TOOL_NOT_FOUND" }
        check(tool.isEnabled) { "TOOL_DISABLED" }
        check(!tool.needsApproval || args.optBoolean("approved")) { "TOOL_APPROVAL_REQUIRED" }
        val supplied = args.optJSONObject("arguments") ?: JSONObject()
        val values = supplied.keys().asSequence().associateWith { supplied.get(it).toString() }
        val required = JSONArray(tool.requiredParamsJson)
        check((0 until required.length()).all { values[required.getString(it)]?.isNotBlank() == true }) { "TOOL_ARGUMENT_REQUIRED" }
        if (CustomToolHttpExecutor.supports(tool.commandTemplate)) return CustomToolHttpExecutor.execute(tool, values) { prepared ->
            client.newBuilder().connectTimeout(prepared.connectTimeoutSeconds.toLong(), TimeUnit.SECONDS)
                .readTimeout(prepared.maxTimeSeconds.toLong(), TimeUnit.SECONDS)
                .callTimeout(prepared.maxTimeSeconds.toLong(), TimeUnit.SECONDS)
                .followRedirects(false).followSslRedirects(false).build()
        }
        val command = if (AgentRuntimeSupport.inferCustomToolExecutionMode(tool.commandTemplate) == CustomToolExecutionMode.SHELL) {
            AgentRuntimeSupport.renderShellTemplate(tool.commandTemplate, values)
        } else AgentRuntimeSupport.tokenizeArgvTemplate(tool.commandTemplate, values).joinToString(" ", transform = HarnessWorkspaceAccess::quote)
        val cwd = tool.workingDirectory.takeUnless { it.isBlank() || it == "/workspace" } ?: "."
        return if (scope.localRoot != null) executeLocal(scope, command, cwd) else files.execute(scope, command, cwd)
    }

    private data class KiwixEndpoint(
        val baseUrl: String,
        val source: String,
        val serverPort: Int?,
        val loadedZims: List<String>,
        val serviceRunning: Boolean?
    )

    private data class KiwixServiceSnapshot(
        val serverUrl: String?,
        val serverPort: Int,
        val loadedZims: List<String>,
        val running: Boolean
    )

    private suspend fun searchKiwix(query: String): JSONObject {
        val trimmedQuery = query.trim()
        require(trimmedQuery.isNotBlank()) { "KIWIX_QUERY_REQUIRED" }
        val endpoint = resolveKiwixEndpoint()
        val baseUri = checkedKiwixBase(endpoint.baseUrl)
        val base = baseUri.toString().trimEnd('/')
        val request = Request.Builder().url("$base/search?pattern=${URLEncoder.encode(trimmedQuery, "UTF-8")}").build()
        val html = client.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "KIWIX_UNAVAILABLE" }
            requireNotNull(response.body).source().let { source ->
                source.request(512 * 1024L)
                source.readUtf8(minOf(source.buffer.size, 512 * 1024L))
            }
        }
        val links = Regex("""<a[^>]*href=["'](/content/[^"']*)["'][^>]*>(.*?)</a>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .findAll(html)
            .map { match ->
                JSONObject()
                    .put("title", decodeKiwixText(match.groupValues[2]))
                    .put("url", base + match.groupValues[1])
                    .put("readMethod", "research.kiwixRead")
            }
            .distinctBy { it.optString("url") }
            .take(settings.agentKiwixMaxResults.value.coerceIn(1, 30))
            .toList()
        val installed = if (endpoint.serviceRunning == true) zims.getInstalledZims().first().take(32) else emptyList()
        return JSONObject()
            .put("source", endpoint.source)
            .put("baseUrl", base)
            .put("serverRunning", endpoint.serviceRunning ?: JSONObject.NULL)
            .put("serverPort", endpoint.serverPort ?: JSONObject.NULL)
            .put("loadedZims", JSONArray().apply { endpoint.loadedZims.forEach { put(it) } })
            .put("query", trimmedQuery)
            .put("results", JSONArray().apply { links.forEach { put(it) } })
            .put("installedZims", JSONArray().apply {
                installed.forEach { zim ->
                    put(JSONObject().put("id", zim.id).put("title", zim.title).put("language", zim.language))
                }
            })
    }

    private suspend fun readKiwixArticle(url: String, maxChars: Int): JSONObject {
        val endpoint = resolveKiwixEndpoint()
        val baseUri = checkedKiwixBase(endpoint.baseUrl)
        val articleUri = URI(url)
        require(articleUri.scheme.equals(baseUri.scheme, ignoreCase = true) &&
            articleUri.host?.equals(baseUri.host, ignoreCase = true) == true &&
            effectivePort(articleUri) == effectivePort(baseUri) &&
            articleUri.path?.startsWith("/content/") == true) { "KIWIX_URL_OUTSIDE_SERVICE" }
        val html = client.newCall(Request.Builder().url(articleUri.toString()).build()).execute().use { response ->
            check(response.isSuccessful) { "KIWIX_ARTICLE_UNAVAILABLE" }
            requireNotNull(response.body).source().let { source ->
                source.request(2 * 1024 * 1024L)
                source.readUtf8(minOf(source.buffer.size, 2 * 1024 * 1024L))
            }
        }
        val text = decodeKiwixText(AgentRuntimeSupport.stripHtmlTags(html))
        return JSONObject()
            .put("source", endpoint.source)
            .put("serverRunning", endpoint.serviceRunning ?: JSONObject.NULL)
            .put("serverPort", endpoint.serverPort ?: JSONObject.NULL)
            .put("loadedZims", JSONArray().apply { endpoint.loadedZims.forEach { put(it) } })
            .put("url", articleUri.toString())
            .put("title", articleUri.path.substringAfterLast('/').ifBlank { "Kiwix article" })
            .put("text", text.take(maxChars))
            .put("truncated", text.length > maxChars)
    }

    /**
     * Prefer the app's already-running KiwixService. The configured URL is
     * used only when the user explicitly saved an override, so Harness cannot
     * silently turn an inactive local service into an arbitrary HTTP client.
     * Binding uses flags=0 and therefore never starts KiwixService.
     */
    private suspend fun resolveKiwixEndpoint(): KiwixEndpoint {
        val configured = settings.agentKiwixUrl.value.trim().trimEnd('/')
        val preferences = context.getSharedPreferences("llamadroid_settings", Context.MODE_PRIVATE)
        val explicitOverride = preferences.contains("agent_kiwix_url") ||
            configured.isNotBlank() && configured != defaultKiwixUrl()
        if (explicitOverride) {
            checkedKiwixBase(configured)
            return KiwixEndpoint(
                baseUrl = configured,
                source = "configured-remote",
                serverPort = null,
                loadedZims = emptyList(),
                serviceRunning = null
            )
        }

        val service = checkNotNull(activeKiwixService()) { "KIWIX_SERVICE_UNAVAILABLE" }
        check(service.running && !service.serverUrl.isNullOrBlank()) {
            "KIWIX_SERVICE_UNAVAILABLE"
        }
        val base = service.serverUrl!!.trimEnd('/')
        checkedKiwixBase(base)
        return KiwixEndpoint(
            baseUrl = base,
            source = "KiwixService",
            serverPort = service.serverPort,
            loadedZims = service.loadedZims,
            serviceRunning = true
        )
    }

    private fun defaultKiwixUrl(): String = "http://127.0.0.1:${KiwixService.DEFAULT_PORT}"

    private suspend fun activeKiwixService(): KiwixServiceSnapshot? = withTimeoutOrNull(KIWIX_BIND_TIMEOUT_MS) {
        suspendCancellableCoroutine { continuation ->
            val appContext = context.applicationContext
            val lock = Any()
            var bindReturned = false
            var bindAccepted = false
            var finished = false
            var unbindAttempted = false
            lateinit var connection: ServiceConnection

            fun unbindIfNeeded() {
                val shouldUnbind = synchronized(lock) {
                    if (!finished || !bindReturned || !bindAccepted || unbindAttempted) {
                        false
                    } else {
                        unbindAttempted = true
                        true
                    }
                }
                if (shouldUnbind) runCatching { appContext.unbindService(connection) }
            }

            fun finish(snapshot: KiwixServiceSnapshot?) {
                val shouldResume = synchronized(lock) {
                    if (finished) false else {
                        finished = true
                        true
                    }
                }
                unbindIfNeeded()
                if (shouldResume && continuation.isActive) continuation.resume(snapshot)
            }

            connection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                    val service = (binder as? KiwixService.LocalBinder)?.getService()
                    finish(service?.let {
                        KiwixServiceSnapshot(
                            serverUrl = it.serverUrl.value,
                            serverPort = it.serverPort.value,
                            loadedZims = it.loadedZims.value,
                            running = it.isRunning.value
                        )
                    })
                }

                override fun onServiceDisconnected(name: ComponentName?) = finish(null)

                override fun onBindingDied(name: ComponentName?) = finish(null)

                override fun onNullBinding(name: ComponentName?) = finish(null)
            }

            continuation.invokeOnCancellation {
                synchronized(lock) {
                    finished = true
                }
                unbindIfNeeded()
            }

            val shouldBind = synchronized(lock) { !finished }
            if (!shouldBind) return@suspendCancellableCoroutine
            val didBind = runCatching {
                appContext.bindService(Intent(appContext, KiwixService::class.java), connection, 0)
            }.getOrDefault(false)
            synchronized(lock) {
                bindReturned = true
                bindAccepted = didBind
            }
            if (!didBind) finish(null) else unbindIfNeeded()
        }
    }

    private fun checkedKiwixBase(raw: String): URI {
        val uri = URI(raw)
        require(uri.scheme.equals("http", ignoreCase = true) || uri.scheme.equals("https", ignoreCase = true)) {
            "KIWIX_URL_INVALID"
        }
        require(uri.userInfo == null && uri.rawQuery == null && uri.rawFragment == null && uri.host != null) { "KIWIX_URL_INVALID" }
        val localHost = uri.host.equals("localhost", ignoreCase = true) ||
            uri.host == "127.0.0.1" || uri.host == "::1" || uri.host == "[::1]"
        if (!localHost) {
            require(AgentRuntimeSupport.blockedUrlReason(uri.toString()) == null) { "KIWIX_URL_BLOCKED" }
        }
        return uri
    }

    private fun effectivePort(uri: URI): Int = if (uri.port > 0) uri.port else if (uri.scheme.equals("https", true)) 443 else 80

    private fun decodeKiwixText(raw: String): String = raw
        .replace(Regex("\\s+"), " ")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .trim()

    private fun imageOutputRelativePath(scope: HarnessSessionScope, method: String, args: JSONObject): String {
        val requested = if (method == "images.generate") {
            args.getString("outputPath")
        } else {
            args.optString("outputPath").takeIf { it.isNotBlank() }
                ?: "generated/background-removal/${File(args.getString("imagePath")).nameWithoutExtension}_bgr.png"
        }
        val withExtension = if (File(requested).extension.isBlank()) "$requested.png" else requested
        return imageRelativePath(scope, withExtension)
    }

    private fun imageRelativePath(scope: HarnessSessionScope, raw: String): String {
        val path = raw.trim()
        val relative = when {
            path == scope.workspace.guestPath -> ""
            path.startsWith(scope.workspace.guestPath + "/") -> path.removePrefix(scope.workspace.guestPath + "/")
            else -> path
        }
        require(relative.isNotBlank() && !relative.startsWith('/') && '\u0000' !in relative &&
            relative.split('/').none { it.isBlank() || it == "." || it == ".." }) { "WORKSPACE_PATH_OUTSIDE_SCOPE" }
        return relative
    }

    private fun workspacePath(scope: HarnessSessionScope, relative: String): String =
        scope.workspace.guestPath.trimEnd('/') + "/" + relative

    private fun JSONObject.numberOrNull(key: String): Double? = if (has(key) && !isNull(key)) optDouble(key) else null
}
