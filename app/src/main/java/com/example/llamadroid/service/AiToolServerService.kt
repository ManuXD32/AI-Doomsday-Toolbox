package com.example.llamadroid.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Binder
import android.os.IBinder
import android.util.Base64
import com.example.llamadroid.data.RemoteSummarySettingsSnapshot
import com.example.llamadroid.data.SettingsRepository
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.example.llamadroid.data.db.AiServerArtifactEntity
import com.example.llamadroid.data.db.AiServerConfigEntity
import com.example.llamadroid.data.db.AiServerPermissionEntity
import com.example.llamadroid.data.db.AiServerSessionEntity
import com.example.llamadroid.data.db.AiServerUserEntity
import com.example.llamadroid.data.db.AiServerWebChatEntity
import com.example.llamadroid.data.db.AiServerWebMessageAttachmentEntity
import com.example.llamadroid.data.db.AiServerWebMessageEntity
import com.example.llamadroid.data.db.AiServerWebProviderEntity
import com.example.llamadroid.data.db.AiServerWebToolEventEntity
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.data.db.NoteEntity
import com.example.llamadroid.data.db.ModelEntity
import com.example.llamadroid.data.db.ModelType
import com.example.llamadroid.data.db.ONNX_CAPABILITY_BACKGROUND_REMOVAL
import com.example.llamadroid.data.db.ONNX_CAPABILITY_IMG2IMG
import com.example.llamadroid.data.db.ONNX_CAPABILITY_TTS
import com.example.llamadroid.data.db.ONNX_CAPABILITY_TXT2IMG
import com.example.llamadroid.data.db.SD_CAPABILITY_IMG2IMG
import com.example.llamadroid.data.db.SD_CAPABILITY_TXT2IMG
import com.example.llamadroid.data.db.SD_CAPABILITY_UPSCALE
import com.example.llamadroid.data.db.SD_CAPABILITY_VID_GEN
import com.example.llamadroid.data.db.hasOnnxCapability
import com.example.llamadroid.data.db.hasSdCapability
import com.example.llamadroid.data.db.PromptType
import com.example.llamadroid.data.model.LITERT_BACKEND_AUTO
import com.example.llamadroid.data.model.LITERT_BACKEND_CPU
import com.example.llamadroid.data.model.LITERT_BACKEND_GPU
import com.example.llamadroid.data.model.DatasetEntry
import com.example.llamadroid.data.model.DatasetExporter
import com.example.llamadroid.data.model.DatasetFormat
import com.example.llamadroid.data.model.DatasetSource
import com.example.llamadroid.data.model.LiteRtModelEntity
import com.example.llamadroid.data.model.LlamaChatEntity
import com.example.llamadroid.data.model.LlamaServerEntity
import com.example.llamadroid.data.model.buildLlamaServerBaseUrl
import com.example.llamadroid.data.model.normalizeLlamaServerEngine
import com.example.llamadroid.ui.dataset.DEFAULT_ANSWER_PROMPT
import com.example.llamadroid.ui.dataset.DEFAULT_CLEAN_PROMPT
import com.example.llamadroid.ui.dataset.DEFAULT_QUESTION_PROMPT
import com.example.llamadroid.ui.dataset.DEFAULT_REVIEW_PROMPT
import com.example.llamadroid.onnx.OnnxBackgroundRemovalConfig
import com.example.llamadroid.onnx.OnnxBackendOverride
import com.example.llamadroid.onnx.OnnxExecutionMode
import com.example.llamadroid.onnx.OnnxGraphOptimizationLevel
import com.example.llamadroid.onnx.OnnxImageGenConfig
import com.example.llamadroid.onnx.OnnxImageGenMode
import com.example.llamadroid.onnx.ONNX_IMAGE_GEN_DEFAULT_STRENGTH
import com.example.llamadroid.onnx.OnnxRuntimeBackend
import com.example.llamadroid.onnx.OnnxRuntimeOptions
import com.example.llamadroid.onnx.OnnxStorage
import com.example.llamadroid.onnx.SUPERTONIC_DEFAULT_LANGUAGE
import com.example.llamadroid.onnx.SUPERTONIC_DEFAULT_SPEED
import com.example.llamadroid.onnx.SUPERTONIC_DEFAULT_TOTAL_STEPS
import com.example.llamadroid.onnx.resolveSupertonicVoices
import com.example.llamadroid.sd.SdLoraApplyMode
import com.example.llamadroid.sd.SdLoraConfigurationException
import com.example.llamadroid.sd.SdLoraSpec
import com.example.llamadroid.sd.validateSdLoraModelCompatibility
import com.example.llamadroid.sd.validateSdLoras
import com.example.llamadroid.sd.toSdLoraSpecs
import com.example.llamadroid.service.AiServerArtifactTypes.AUDIO
import com.example.llamadroid.service.AiServerArtifactTypes.DATASET
import com.example.llamadroid.service.AiServerArtifactTypes.DOCUMENT
import com.example.llamadroid.service.AiServerArtifactTypes.IMAGE
import com.example.llamadroid.service.AiServerArtifactTypes.VIDEO
import com.example.llamadroid.data.db.NoteType
import com.example.llamadroid.util.DebugLog
import com.example.llamadroid.widget.NoteDisplayWidgetProvider
import com.example.llamadroid.widget.OrganizerCalendarWidgetProvider
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.Response
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLDecoder
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/** Read the ordered LoRA wire format while keeping old single-LoRA clients valid. */
private fun JSONObject.readSdLoras(
    key: String,
    legacyPathKey: String? = "loraPath",
    legacyStrengthKey: String = "loraStrength"
): List<SdLoraSpec> {
    val raw = opt(key)
    val parsed = runCatching {
        when (raw) {
            is JSONArray -> raw.toSdLoraSpecs()
            is String -> raw.trim().takeIf { it.isNotEmpty() }?.let { JSONArray(it).toSdLoraSpecs() }.orEmpty()
            else -> emptyList()
        }
    }.getOrDefault(emptyList())
    if (parsed.isNotEmpty()) return parsed
    return legacyPathKey?.let {
        SdLoraSpec.fromLegacy(
            optString(it).trim().takeIf { path -> path.isNotEmpty() },
            optDouble(legacyStrengthKey, 1.0).toFloat()
        )
    }.orEmpty()
}

class AiToolServerService : Service() {
    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val servers = mutableMapOf<String, ToolHttpServer>()
    private lateinit var db: AppDatabase
    private var notificationTaskId: Int? = null

    inner class LocalBinder : Binder() {
        fun getService(): AiToolServerService = this@AiToolServerService
    }

    override fun onCreate() {
        super.onCreate()
        db = AppDatabase.getDatabase(applicationContext)
        serviceScope.launch { ensureDefaultConfigs(applicationContext) }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val (taskId, notification) = UnifiedNotificationManager.startTaskForForeground(
            UnifiedNotificationManager.TaskType.AI_SERVERS,
            "AI Servers"
        )
        notificationTaskId = taskId
        startForeground(taskId, notification)
        return START_STICKY
    }

    override fun onDestroy() {
        stopAllServers()
        notificationTaskId?.let { UnifiedNotificationManager.dismissTask(it) }
        notificationTaskId = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        serviceScope.cancel()
        super.onDestroy()
    }

    fun startServer(config: AiServerConfigEntity): Result<Unit> = runCatching {
        val type = AiServerType.fromId(config.serverType)
            ?: error("Unknown server type: ${config.serverType}")
        if (!AiServerNetwork.isValidServerPort(config.port)) {
            error("Port must be between 10000 and 65535.")
        }
        servers[config.serverType]?.stop()
        val host = AiServerNetwork.bindHost(config.lanVisible)
        val httpServer = ToolHttpServer(type, config.copy(enabled = true), host)
        httpServer.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
        servers[config.serverType] = httpServer
        AiServerLogStore.append(config.serverType, "Started ${type.displayName} on $host:${config.port}")
        setRunning(config, true, null)
        serviceScope.launch {
            db.aiServerDao().upsertConfig(config.copy(enabled = true, updatedAt = System.currentTimeMillis()))
        }
        updateNotification()
    }.onFailure { error ->
        AiServerLogStore.append(config.serverType, "Start failed: ${error.message}")
        setRunning(config, false, error.message)
    }

    fun stopServer(serverType: String) {
        servers.remove(serverType)?.stop()
        AiServerLogStore.append(serverType, "Stopped server")
        val existing = runtimeStates.value.firstOrNull { it.serverType == serverType }
        val port = existing?.port ?: AiServerType.fromId(serverType)?.defaultPort ?: 0
        setRunning(
            AiServerConfigEntity(
                serverType = serverType,
                displayName = AiServerType.fromId(serverType)?.displayName ?: serverType,
                port = port,
                lanVisible = existing?.lanVisible ?: false
            ),
            false,
            null
        )
        serviceScope.launch {
            db.aiServerDao().getConfig(serverType)?.let {
                db.aiServerDao().upsertConfig(it.copy(enabled = false, updatedAt = System.currentTimeMillis()))
            }
        }
        updateNotification()
    }

    fun stopAllServers() {
        servers.keys.toList().forEach(::stopServer)
    }

    private fun setRunning(config: AiServerConfigEntity, running: Boolean, error: String?) {
        val urls = if (running) AiServerNetwork.urlsFor(config.port, config.lanVisible) else emptyList()
        val state = AiServerRuntimeState(
            serverType = config.serverType,
            running = running,
            port = config.port,
            lanVisible = config.lanVisible,
            urls = urls,
            error = error
        )
        val current = _runtimeStates.value.filterNot { it.serverType == config.serverType }
        _runtimeStates.value = (current + state).sortedBy { it.port }
    }

    private fun updateNotification() {
        val count = servers.size
        notificationTaskId?.let {
            UnifiedNotificationManager.updateProgress(
                it,
                if (count > 0) 1f else 0f,
                "$count AI server${if (count == 1) "" else "s"} running"
            )
        }
    }

    private data class QueuedServerJob(
        val jobId: String,
        val action: String,
        val params: JSONObject,
        val ownerUserId: Long?
    )

    private data class CancelSnapshot(
        val wasQueued: Boolean,
        val action: String,
        val runner: Job?
    )

    private data class WebChatStreamingProgress(
        var content: String = "",
        var thinking: String = "",
        var statusText: String? = null,
        var tokenCount: Int = 0,
        var promptTokens: Int = 0,
        var completionTokens: Int = 0,
        val startedAt: Long = System.currentTimeMillis()
    )

    private data class WebChatGenerationResult(
        val output: String,
        val thinking: String?,
        val promptTokens: Int,
        val completionTokens: Int,
        val tokensPerSecond: Double
    )

    private inner class ToolHttpServer(
        private val type: AiServerType,
        private var config: AiServerConfigEntity,
        host: String
    ) : NanoHTTPD(host, config.port) {
        private val queueLock = Any()
        private val queuedJobs = ArrayDeque<QueuedServerJob>()
        private var queueRunner: Job? = null
        private val jobActions = mutableMapOf<String, String>()
        private val activeTaskJobs = mutableMapOf<String, Job>()

        override fun serve(session: IHTTPSession): Response {
            return runCatching {
                route(session)
            }.getOrElse { error ->
                AiServerLogStore.append(type.id, "Request failed ${session.method} ${session.uri}: ${error.message}")
                jsonResponse(
                    JSONObject()
                        .put("ok", false)
                        .put("error", error.message ?: "Server error"),
                    Response.Status.INTERNAL_ERROR
                )
            }
        }

        private fun route(session: IHTTPSession): Response {
            val path = session.uri.substringBefore('?').ifBlank { "/" }
            if (!isQuietRequest(path, session.method)) {
                AiServerLogStore.append(type.id, "HTTP ${session.method} $path")
            }
            return when {
                path == "/" || path == "/index.html" -> htmlResponse(indexHtml(type))
                path == "/assets/styles.css" -> assetResponse("ai_servers_webui/styles.css", "text/css")
                path == "/assets/app.js" -> assetResponse("ai_servers_webui/app.js", "application/javascript")
                path == "/api/auth/login" && session.method == Method.POST -> login(session)
                path == "/api/auth/logout" -> logout(session)
                path.startsWith("/api/") -> {
                    val user = authorize(session)
                    if (config.accessMode == AiServerAccessMode.USERS && user == null) {
                        jsonResponse(JSONObject().put("ok", false).put("error", "Unauthorized"), Response.Status.UNAUTHORIZED)
                    } else {
                        routeApi(path, session, user)
                    }
                }
                else -> jsonResponse(JSONObject().put("ok", false).put("error", "Not found"), Response.Status.NOT_FOUND)
            }
        }

        private fun routeApi(path: String, session: IHTTPSession, user: AiServerUserEntity?): Response {
            return when {
                path == "/api/health" -> jsonResponse(healthJson(user))
                path == "/api/options" -> jsonResponse(optionsJson(type, user))
                path == "/api/jobs" && session.method == Method.GET -> jsonResponse(jobsJson(type.id))
                path == "/api/jobs" && session.method == Method.POST -> createJob(session, user)
                path == "/api/jobs/clear-failed" && session.method == Method.POST -> clearFailedJobs()
                path.startsWith("/api/jobs/") && path.endsWith("/retry") && session.method == Method.POST -> {
                    retryJob(path.removePrefix("/api/jobs/").removeSuffix("/retry"), user)
                }
                path.startsWith("/api/jobs/") && path.endsWith("/cancel") && session.method == Method.POST -> {
                    cancelJob(path.removePrefix("/api/jobs/").removeSuffix("/cancel"))
                }
                path.startsWith("/api/jobs/") && session.method == Method.DELETE -> removeJob(path.substringAfterLast('/'))
                path == "/api/logs" && session.method == Method.GET -> jsonResponse(logsJson(type.id))
                path == "/api/logs/clear" && session.method == Method.POST -> {
                    AiServerLogStore.clear(type.id)
                    jsonResponse(JSONObject().put("ok", true))
                }
                path == "/api/qr" -> serveQr(queryParam(session, "data"))
                path == "/api/gallery" -> jsonResponse(galleryJson(type, user))
                path.startsWith("/api/gallery/") && session.method == Method.DELETE -> deleteGalleryArtifact(path.substringAfterLast('/'), user)
                path == "/api/upload" && session.method == Method.POST -> upload(session)
                path == "/api/media/info" -> jsonResponse(mediaInfoJson(queryParam(session, "path")))
                path == "/api/media" -> serveMedia(queryParam(session, "path"))
                path == "/api/chat" && type == AiServerType.LLAMA_CHAT && session.method == Method.GET -> jsonResponse(chatJson(session, user))
                path == "/api/chat/create" && type == AiServerType.LLAMA_CHAT && session.method == Method.POST -> createChat(session, user)
                path == "/api/chat/rename" && type == AiServerType.LLAMA_CHAT && session.method == Method.POST -> renameChat(session, user)
                path == "/api/chat/delete" && type == AiServerType.LLAMA_CHAT && session.method == Method.POST -> deleteChat(session, user)
                path == "/api/chat/continue" && type == AiServerType.LLAMA_CHAT && session.method == Method.POST -> continueChat(session, user)
                path == "/api/chat/message/edit" && type == AiServerType.LLAMA_CHAT && session.method == Method.POST -> editChatMessage(session, user)
                path == "/api/chat/message/delete" && type == AiServerType.LLAMA_CHAT && session.method == Method.POST -> deleteChatMessage(session, user)
                path == "/api/chat/message/regenerate" && type == AiServerType.LLAMA_CHAT && session.method == Method.POST -> regenerateChatMessage(session, user)
                path == "/api/chat/tool-events/clear" && type == AiServerType.LLAMA_CHAT && session.method == Method.POST -> clearChatToolEvents(session, user)
                path == "/api/chat/provider" && type == AiServerType.LLAMA_CHAT && session.method == Method.POST -> upsertChatProvider(session, user)
                path == "/api/chat/provider/models" && type == AiServerType.LLAMA_CHAT && session.method == Method.GET -> chatProviderModels(session)
                path == "/api/chat/provider/delete" && type == AiServerType.LLAMA_CHAT && session.method == Method.POST -> deleteChatProvider(session, user)
                else -> jsonResponse(JSONObject().put("ok", false).put("error", "Unknown endpoint"), Response.Status.NOT_FOUND)
            }
        }

        private fun isQuietRequest(path: String, method: Method): Boolean =
            method == Method.GET && (
                path == "/api/jobs" ||
                    path == "/api/gallery" ||
                    path == "/api/health" ||
                    path == "/api/chat" ||
                    path == "/assets/app.js" ||
                    path == "/assets/styles.css"
                )

        private fun login(session: IHTTPSession): Response = runBlocking {
            val request = readJsonBody(session)
            val username = request.optString("username").trim()
            val password = request.optString("password")
            val user = db.aiServerDao().getUserByUsername(username)
            val valid = user != null &&
                user.enabled &&
                AiServerAuth.verifyPassword(password, user.passwordSalt, user.passwordHash) &&
                userCanAccess(user.id, type.id)
            if (!valid || user == null) {
                return@runBlocking jsonResponse(
                    JSONObject().put("ok", false).put("error", "Invalid username or password"),
                    Response.Status.UNAUTHORIZED
                )
            }
            val token = AiServerAuth.createSessionToken()
            val now = System.currentTimeMillis()
            db.aiServerDao().upsertSession(
                AiServerSessionEntity(
                    tokenHash = AiServerAuth.tokenHash(token),
                    userId = user.id,
                    createdAt = now,
                    expiresAt = now + SESSION_DURATION_MS,
                    lastSeenAt = now
                )
            )
            val response = jsonResponse(JSONObject().put("ok", true).put("username", user.username))
            response.addHeader(
                "Set-Cookie",
                "$SESSION_COOKIE=${AiServerAuth.signToken(token, sessionSecret())}; Path=/; HttpOnly; SameSite=Lax; Max-Age=${SESSION_DURATION_MS / 1000}"
            )
            response
        }

        private fun logout(session: IHTTPSession): Response = runBlocking {
            AiServerAuth.verifySignedToken(session.cookies.read(SESSION_COOKIE), sessionSecret())
                ?.let { token -> db.aiServerDao().deleteSession(AiServerAuth.tokenHash(token)) }
            val response = jsonResponse(JSONObject().put("ok", true))
            response.addHeader(
                "Set-Cookie",
                "$SESSION_COOKIE=; Path=/; HttpOnly; SameSite=Lax; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT"
            )
            response
        }

        private fun authorize(session: IHTTPSession): AiServerUserEntity? = runBlocking {
            if (config.accessMode == AiServerAccessMode.PUBLIC) return@runBlocking null
            val token = AiServerAuth.verifySignedToken(session.cookies.read(SESSION_COOKIE), sessionSecret())
                ?: return@runBlocking null
            val now = System.currentTimeMillis()
            db.aiServerDao().deleteExpiredSessions(now)
            val stored = db.aiServerDao().getSession(AiServerAuth.tokenHash(token))
                ?.takeIf { it.expiresAt > now }
                ?: return@runBlocking null
            val user = db.aiServerDao().getUserById(stored.userId)?.takeIf { it.enabled }
                ?: return@runBlocking null
            user.takeIf { userCanAccess(it.id, type.id) }
        }

        private suspend fun userCanAccess(userId: Long, serverType: String): Boolean {
            val permission = db.aiServerDao().getPermission(userId, serverType)
            return permission?.canAccess ?: true
        }

        private fun createJob(session: IHTTPSession, user: AiServerUserEntity?): Response {
            val body = readJsonBody(session)
            val params = body.optJSONObject("params") ?: body
            val action = body.optString("action").ifBlank { legacyActionFrom(params) }
            val jobId = UUID.randomUUID().toString()
            val title = body.optString("title").ifBlank { jobTitleFor(action, params) }
            val ownerUserId = user?.id
            runCatching {
                if (type == AiServerType.LLAMA_CHAT) {
                    normalizeChatParamsForOwner(params, user)
                }
                validateActionForServer(action, params)
            }.onFailure { error ->
                return jsonResponse(
                    JSONObject().put("ok", false).put("error", error.message ?: "Invalid task"),
                    Response.Status.BAD_REQUEST
                )
            }
            val acceptedJob = AiServerJob(
                id = jobId,
                serverType = type.id,
                title = title,
                status = "QUEUED",
                message = "Queued from web UI",
                ownerUserId = ownerUserId,
                action = action,
                paramsJson = params.toString()
            )
            AiServerJobStore.add(acceptedJob)
            synchronized(queueLock) {
                jobActions[jobId] = action
                queuedJobs += QueuedServerJob(jobId, action, JSONObject(params.toString()), ownerUserId)
            }
            ensureQueueRunner()
            return jsonResponse(JSONObject().put("ok", true).put("job", jobToJson(acceptedJob)))
        }

        private fun launchTracked(jobId: String, block: suspend CoroutineScope.() -> Unit): Job {
            val launched = serviceScope.launch {
                try {
                    block()
                } finally {
                    synchronized(queueLock) {
                        activeTaskJobs.remove(jobId)
                    }
                }
            }
            synchronized(queueLock) {
                activeTaskJobs[jobId] = launched
            }
            return launched
        }

        private fun ensureQueueRunner() {
            synchronized(queueLock) {
                if (queueRunner?.isActive == true) return
                queueRunner = serviceScope.launch {
                    while (isActive) {
                        val next = synchronized(queueLock) {
                            if (queuedJobs.isEmpty()) null else queuedJobs.removeFirst()
                        } ?: break
                        val current = AiServerJobStore.getJob(type.id, next.jobId)
                        if (current == null || current.status == "CANCELLED") continue
                        AiServerLogStore.append(type.id, "${next.jobId}: starting ${next.action}")
                        val result = startQueuedJob(next)
                        result.onFailure { error ->
                            updateJob(next.jobId, type.id, "FAILED", 0f, error.message ?: "Could not start task", null)
                        }
                        waitForTerminalJob(next.jobId)
                        synchronized(queueLock) {
                            activeTaskJobs.remove(next.jobId)
                            jobActions.remove(next.jobId)
                        }
                    }
                    synchronized(queueLock) {
                        queueRunner = null
                        if (queuedJobs.isNotEmpty()) ensureQueueRunner()
                    }
                }
            }
        }

        private fun startQueuedJob(queued: QueuedServerJob): Result<AiServerJob> =
            when (type) {
                AiServerType.IMAGE -> startImageJob(queued.jobId, queued.action, queued.params, queued.ownerUserId)
                AiServerType.VIDEO -> startVideoJob(queued.jobId, queued.action, queued.params, queued.ownerUserId)
                AiServerType.WORKFLOWS -> startWorkflowJob(queued.jobId, queued.action, queued.params, queued.ownerUserId)
                AiServerType.TTS -> startTtsJob(queued.jobId, queued.action, queued.params, queued.ownerUserId)
                AiServerType.VIDEO_UPSCALE -> startVideoUpscaleJob(queued.jobId, queued.params, queued.ownerUserId)
                AiServerType.DOCS_DATASETS -> startDocsDatasetJob(queued.jobId, queued.action, queued.params, queued.ownerUserId)
                AiServerType.LLAMA_CHAT -> startLlamaChatJob(queued.jobId, queued.action, queued.params, queued.ownerUserId)
                AiServerType.AI_HUB -> Result.failure(IllegalStateException("AI HUB does not run queued jobs"))
            }

        private suspend fun waitForTerminalJob(jobId: String) {
            while (true) {
                val status = AiServerJobStore.getJob(type.id, jobId)?.status
                if (status in setOf("COMPLETED", "FAILED", "CANCELLED", "READY")) return
                delay(800)
            }
        }

        private fun removeJob(jobId: String): Response {
            synchronized(queueLock) {
                val kept = queuedJobs.filterNot { it.jobId == jobId }
                queuedJobs.clear()
                queuedJobs.addAll(kept)
                jobActions.remove(jobId)
            }
            val removed = AiServerJobStore.remove(type.id, jobId)
            return jsonResponse(JSONObject().put("ok", removed))
        }

        private fun retryJob(jobId: String, user: AiServerUserEntity?): Response {
            val failedJob = AiServerJobStore.getJob(type.id, jobId)
                ?: return jsonResponse(JSONObject().put("ok", false).put("error", "Task not found"), Response.Status.NOT_FOUND)
            if (failedJob.status != "FAILED") {
                return jsonResponse(JSONObject().put("ok", false).put("error", "Only failed tasks can be retried"), Response.Status.BAD_REQUEST)
            }
            if (config.accessMode == AiServerAccessMode.USERS && failedJob.ownerUserId != user?.id) {
                return jsonResponse(JSONObject().put("ok", false).put("error", "Forbidden"), Response.Status.FORBIDDEN)
            }
            val action = failedJob.action
                ?: return jsonResponse(JSONObject().put("ok", false).put("error", "Retry details are missing"), Response.Status.BAD_REQUEST)
            val params = runCatching { JSONObject(failedJob.paramsJson.orEmpty()) }.getOrNull()
                ?: return jsonResponse(JSONObject().put("ok", false).put("error", "Retry parameters are missing"), Response.Status.BAD_REQUEST)
            runCatching {
                if (type == AiServerType.LLAMA_CHAT) {
                    normalizeChatParamsForOwner(params, user)
                }
                validateActionForServer(action, params)
            }.onFailure { error ->
                return jsonResponse(
                    JSONObject().put("ok", false).put("error", error.message ?: "Invalid task"),
                    Response.Status.BAD_REQUEST
                )
            }
            val retryId = UUID.randomUUID().toString()
            val ownerUserId = user?.id ?: failedJob.ownerUserId
            val retry = AiServerJob(
                id = retryId,
                serverType = type.id,
                title = failedJob.title,
                status = "QUEUED",
                progress = 0f,
                message = "Queued from retry",
                ownerUserId = ownerUserId,
                action = action,
                paramsJson = params.toString()
            )
            AiServerJobStore.add(retry)
            synchronized(queueLock) {
                jobActions[retryId] = action
                queuedJobs += QueuedServerJob(retryId, action, JSONObject(params.toString()), ownerUserId)
            }
            AiServerLogStore.append(type.id, "$retryId: retry queued from $jobId")
            ensureQueueRunner()
            return jsonResponse(JSONObject().put("ok", true).put("job", jobToJson(retry)))
        }

        private fun cancelJob(jobId: String): Response {
            val current = AiServerJobStore.getJob(type.id, jobId)
                ?: return jsonResponse(JSONObject().put("ok", false).put("error", "Task not found"), Response.Status.NOT_FOUND)
            if (current.status in setOf("COMPLETED", "FAILED", "CANCELLED", "READY")) {
                return jsonResponse(JSONObject().put("ok", false).put("error", "Task already finished"), Response.Status.BAD_REQUEST)
            }
            val (wasQueued, action, runner) = synchronized(queueLock) {
                val queued = queuedJobs.any { it.jobId == jobId }
                if (queued) {
                    val kept = queuedJobs.filterNot { it.jobId == jobId }
                    queuedJobs.clear()
                    queuedJobs.addAll(kept)
                }
                CancelSnapshot(
                    wasQueued = queued,
                    action = jobActions[jobId].orEmpty(),
                    runner = activeTaskJobs.remove(jobId)
                )
            }
            runner?.cancel(CancellationException("Cancelled from AI Servers Hub"))
            if (!wasQueued) {
                requestNativeCancellation(action)
            }
            val message = if (wasQueued) "Cancelled before start" else "Cancellation requested"
            val marked = AiServerJobStore.markCancelled(type.id, jobId, message)
            AiServerLogStore.append(type.id, "$jobId: $message")
            return jsonResponse(JSONObject().put("ok", marked).put("cancelled", marked))
        }

        private fun clearFailedJobs(): Response =
            jsonResponse(JSONObject().put("ok", true).put("removed", AiServerJobStore.clearFailed(type.id)))

        private fun requestNativeCancellation(action: String) {
            runCatching {
                when (type) {
                    AiServerType.IMAGE -> when {
                        action == "onnx_bgr" -> OnnxBackgroundRemovalService.cancel(applicationContext)
                        action.startsWith("onnx_") -> applicationContext.startService(
                            OnnxImageGenerationService.createCancelIntent(applicationContext)
                        )
                        action == "sd_upscale" -> applicationContext.startService(
                            StableDiffusionService.createCancelModeIntent(applicationContext, SDMode.UPSCALE)
                        )
                        action == "sd_img2img" -> applicationContext.startService(
                            StableDiffusionService.createCancelModeIntent(applicationContext, SDMode.IMG2IMG)
                        )
                        else -> applicationContext.startService(
                            StableDiffusionService.createCancelModeIntent(applicationContext, SDMode.TXT2IMG)
                        )
                    }
                    AiServerType.VIDEO -> applicationContext.startService(
                        VideoGenerationService.createCancelIntent(
                            applicationContext,
                            if (action == "img2vid") VideoGenerationMode.IMG2VID else VideoGenerationMode.TXT2VID
                        )
                    )
                    AiServerType.WORKFLOWS -> when (action) {
                        "txt2img_upscale" -> applicationContext.startService(
                            StableDiffusionService.createCancelWorkflowIntent(applicationContext)
                        )
                        "transcribe_summary" -> VideoSumupService.cancel()
                        "manga_translation" -> PDFTranslationJobService.cancel()
                        "media_translation", "subtitle_translation" -> MediaTranslationWorkflowService.cancel(applicationContext)
                        else -> Unit
                    }
                    AiServerType.TTS -> applicationContext.startService(OnnxTtsGenerationService.cancelIntent(applicationContext))
                    AiServerType.VIDEO_UPSCALE -> applicationContext.startService(VideoUpscalerService.createCancelIntent(applicationContext))
                    AiServerType.DOCS_DATASETS -> when (action) {
                        "pdf_summary" -> PDFSummaryService.cancel()
                        "video_summary" -> VideoSumupService.cancel()
                        "pdf_translate_ocr", "pdf_translate_text_layer" -> PDFTranslationJobService.cancel()
                        "dataset_import", "dataset_pipeline" -> DatasetForegroundService.cancelCurrent(applicationContext)
                        else -> Unit
                    }
                    AiServerType.LLAMA_CHAT -> Unit
                    AiServerType.AI_HUB -> Unit
                }
            }.onFailure { error ->
                AiServerLogStore.append(type.id, "Cancel dispatch failed: ${error.message}")
            }
        }

        private fun isJobCancelled(jobId: String): Boolean =
            AiServerJobStore.getJob(type.id, jobId)?.status == "CANCELLED"

        private fun validateActionForServer(action: String, params: JSONObject) {
            when (type) {
                AiServerType.IMAGE -> require(action in imageActions) { "Choose a valid image action." }
                AiServerType.VIDEO -> require(action in videoActions) { "Choose a valid video action." }
                AiServerType.WORKFLOWS -> require(action in workflowActions) { "Choose a valid workflow." }
                AiServerType.TTS -> require(action in ttsActions) { "Choose a valid voice action." }
                AiServerType.VIDEO_UPSCALE -> require(action == "video_upscale") { "Choose a valid video upscale action." }
                AiServerType.DOCS_DATASETS -> require(action in docsActions) { "Choose a valid docs or dataset action." }
                AiServerType.LLAMA_CHAT -> require(action in chatActions) { "Choose a valid chat action." }
                AiServerType.AI_HUB -> error("AI HUB is a launcher and does not accept jobs.")
            }
            if (type == AiServerType.LLAMA_CHAT && action != "web_chat_send") {
                require(params.optLong("chatId", -1L) > 0L) { "Choose a chat." }
            } else {
                validateJobRequest(if (type == AiServerType.LLAMA_CHAT) "web_chat_send" else action, params)
            }
            validateModelCapabilities(action, params)
        }

        private fun startImageJob(jobId: String, action: String, body: JSONObject, ownerUserId: Long?): Result<AiServerJob> = runCatching {
            require(action in imageActions) { "Choose a valid image action." }
            validateJobRequest(action, body)
            val engine = if (action.startsWith("onnx_")) "onnx" else "sd"
            val mode = action.removePrefix("${engine}_")
            val modelPath = body.optString("modelPath").trim()
            val modelName = File(modelPath).name
            val settings = SettingsRepository(applicationContext)
            val sdModel = if (engine == "sd") {
                runBlocking { db.modelDao().getAllModels().first() }.firstOrNull { it.path == modelPath }
            } else {
                null
            }
            val sdMaxVramCpuGiB = body.optString("maxVramCpuGiB")
                .ifBlank { if (settings.sdMaxCpuRamEnabled.value) settings.sdMaxCpuRamGiB.value else "" }
            val prompt = body.optString("prompt").ifBlank { "A cute modern AI toolbox illustration" }
            val outputFile = imageOutputFile(engine, mode, jobId)
            val metadata = JSONObject()
                .put("origin", "SERVER")
                .put("serverType", type.id)
                .put("ownerUserId", ownerUserId)
                .put("jobId", jobId)
                .put("engine", engine)
                .put("mode", mode)
                .put("prompt", prompt)

            when {
                action == "onnx_bgr" -> {
                    val inputPath = body.optString("inputPath").trim()
                    OnnxBackgroundRemovalService.start(
                        applicationContext,
                        OnnxBackgroundRemovalConfig(
                            modelPath = modelPath,
                            modelName = modelName,
                            inputPaths = listOf(inputPath),
                            inputNames = listOf(File(inputPath).name),
                            backend = parseOnnxBackend(body.optString("backend")),
                            runtimeOptions = parseOnnxRuntimeOptions(body),
                            alphaThreshold = body.optDouble("alphaThreshold", 0.5).toFloat(),
                            featherRadius = body.optInt("featherRadius", 1),
                            maskSoftness = body.optDouble("maskSoftness", 1.0).toFloat(),
                            maskContrast = body.optDouble("maskContrast", 1.0).toFloat(),
                            exportMask = body.optBoolean("exportMask", false),
                            resizeBeforeProcessing = body.optBoolean("resizeBeforeProcessing", true),
                            resizeMaxEdge = body.optInt("resizeMaxEdge", 512)
                        )
                    )
                    watchBackgroundRemovalJob(jobId, ownerUserId, prompt, metadata)
                }
                engine == "onnx" -> {
                    val onnxMode = if (action == "onnx_img2img") OnnxImageGenMode.IMG2IMG else OnnxImageGenMode.TXT2IMG
                    startForegroundService(
                        OnnxImageGenerationService.createStartIntent(
                            applicationContext,
                            OnnxImageGenConfig(
                                modelPath = modelPath,
                                modelName = modelName,
                                mode = onnxMode,
                                prompt = prompt,
                                negativePrompt = body.optString("negativePrompt"),
                                width = body.optInt("width", 512),
                                height = body.optInt("height", 512),
                                steps = body.optInt("steps", 20),
                                cfgScale = body.optDouble("cfgScale", 7.5).toFloat(),
                                seed = body.optLong("seed", -1L),
                                initImagePath = body.optString("inputPath").ifBlank { null },
                                strength = if (onnxMode == OnnxImageGenMode.IMG2IMG) {
                                    body.optDouble("strength", ONNX_IMAGE_GEN_DEFAULT_STRENGTH.toDouble()).toFloat()
                                } else {
                                    null
                                },
                                backend = parseOnnxBackend(body.optString("backend")),
                                runtimeOptions = parseOnnxRuntimeOptions(body),
                                outputPath = outputFile.absolutePath
                            )
                        )
                    )
                    writeArtifactSidecar(outputFile, metadata)
                    recordArtifact(type.id, ownerUserId, jobId, IMAGE, outputFile, "image/png", prompt, metadata)
                    watchOnnxImageJob(jobId, outputFile)
                }
                action == "sd_upscale" -> {
                    val inputPath = body.optString("inputPath").trim()
                    startForegroundService(
                        StableDiffusionService.createStartUpscaleIntent(
                            applicationContext,
                            SDUpscaleConfig(
                                modelPath = modelPath,
                                inputImagePath = inputPath,
                                outputPath = outputFile.absolutePath,
                                upscaleRepeats = body.optInt("upscaleRepeats", 1),
                                threads = body.optInt("threads", -1),
                                sdParamsBackendMode = body.optString("sdParamsBackendMode").ifBlank { sdModel?.sdParamsBackendMode ?: "auto" },
                                sdRuntimeBackendMode = body.optString("sdRuntimeBackendMode").ifBlank { sdModel?.sdRuntimeBackendMode ?: "auto" },
                                maxVramCpuGiB = sdMaxVramCpuGiB
                            )
                        )
                    )
                    writeArtifactSidecar(outputFile, metadata)
                    recordArtifact(type.id, ownerUserId, jobId, IMAGE, outputFile, "image/png", "Upscaled image", metadata)
                    watchSdJob(jobId, SDModeStateHolder.upscale, outputFile)
                }
                else -> {
                    val sdMode = when (action) {
                        "sd_img2img", "sd_inpaint" -> SDMode.IMG2IMG
                        "sd_adetailer" -> SDMode.ADETAILER
                        else -> SDMode.TXT2IMG
                    }
                    val inputImagePath = body.optString("inputPath").ifBlank { null }
                    val adetailer = if (action == "sd_adetailer") {
                        SdADetailerConfig(
                            modelPath = body.optString("adModelPath").trim(),
                            prompt = body.optString("adPrompt"),
                            negativePrompt = body.optString("adNegativePrompt"),
                            confidence = body.optDouble("adConfidence", 0.30).toFloat(),
                            denoisingStrength = body.optDouble("adDenoisingStrength", 0.40).toFloat(),
                            maskBlur = body.optInt("adMaskBlur", 4),
                            padding = body.optInt("adPadding", 32),
                            maxDetections = body.optInt("adMaxDetections", 8),
                            detailSteps = body.optInt("adSteps", 0).takeIf { it > 0 },
                            detailCfgScale = body.optDouble("adCfgScale", -1.0)
                                .toFloat()
                                .takeIf { it >= 0f },
                            loras = body.readSdLoras(
                                "adLoras",
                                legacyPathKey = "adLoraPath",
                                legacyStrengthKey = "adLoraStrength"
                            )
                        )
                    } else {
                        null
                    }
                    startForegroundService(
                        StableDiffusionService.createStartIntent(
                            applicationContext,
                            SDConfig(
                                modelPath = modelPath,
                                prompt = prompt,
                                negativePrompt = body.optString("negativePrompt"),
                                width = body.optInt("width", 512),
                                height = body.optInt("height", 512),
                                steps = body.optInt("steps", 20),
                                cfgScale = body.optDouble("cfgScale", 7.0).toFloat(),
                                seed = body.optLong("seed", -1L),
                                outputPath = outputFile.absolutePath,
                                initImage = inputImagePath,
                                maskImage = body.optString("maskPath").ifBlank { null },
                                strength = body.optDouble("strength", 0.75).toFloat(),
                                mode = sdMode,
                                threads = body.optInt("threads", -1),
                                samplingMethod = parseEnum(body.optString("samplingMethod"), SamplingMethod.EULER_A),
                                vaeTiling = body.optBoolean("vaeTiling", false),
                                vaeTileOverlap = body.optDouble("vaeTileOverlap", 0.5).toFloat(),
                                vaeTileSize = body.optString("vaeTileSize", "32x32"),
                                cacheMode = parseEnumOrNull<SdCacheMode>(body.optString("cacheMode")),
                                cacheOption = body.optString("cacheOption"),
                                scmMask = body.optString("scmMask"),
                                scmPolicy = parseEnumOrNull<SdCacheScmPolicy>(body.optString("scmPolicy")),
                                vaePath = body.optNullableString("vaePath"),
                                taePath = body.optNullableString("taePath"),
                                clipLPath = body.optNullableString("clipLPath"),
                                clipGPath = body.optNullableString("clipGPath"),
                                t5xxlPath = body.optNullableString("t5xxlPath"),
                                llmPath = body.optNullableString("llmPath"),
                                llmVisionPath = body.optNullableString("llmVisionPath"),
                                controlNetPath = body.optNullableString("controlNetPath"),
                                controlImagePath = body.optNullableString("controlImagePath"),
                                controlStrength = body.optDouble("controlStrength", 0.9).toFloat(),
                                loraPath = body.optNullableString("loraPath"),
                                loraStrength = body.optDouble("loraStrength", 1.0).toFloat(),
                                loraApplyMode = parseEnumOrNull<SdLoraApplyMode>(body.optString("loraApplyMode")),
                                loras = body.readSdLoras("loras"),
                                adetailer = adetailer,
                                textualInversionPath = body.optNullableString("textualInversionPath"),
                                photoMakerPath = body.optNullableString("photoMakerPath"),
                                flowShift = body.optDoubleOrNull("flowShift")?.toFloat(),
                                diffusionFa = body.optBoolean("diffusionFa", false),
                                mmap = body.optBoolean("mmap", false),
                                vaeConvDirect = body.optBoolean("vaeConvDirect", false),
                                qwenImageZeroCondT = body.optBoolean("qwenImageZeroCondT", false),
                                chromaDisableDitMask = body.optBoolean("chromaDisableDitMask", false),
                                quantizationType = body.optString("quantizationType"),
                                sdParamsBackendMode = body.optString("sdParamsBackendMode").ifBlank { sdModel?.sdParamsBackendMode ?: "auto" },
                                sdRuntimeBackendMode = body.optString("sdRuntimeBackendMode").ifBlank { sdModel?.sdRuntimeBackendMode ?: "auto" },
                                maxVramCpuGiB = sdMaxVramCpuGiB
                            )
                        )
                    )
                    writeArtifactSidecar(outputFile, metadata)
                    recordArtifact(type.id, ownerUserId, jobId, IMAGE, outputFile, "image/png", prompt, metadata)
                    watchSdJob(jobId, SDModeStateHolder.getForMode(sdMode), outputFile)
                }
            }
            val job = AiServerJob(
                id = jobId,
                serverType = type.id,
                title = prompt.take(80),
                status = "RUNNING",
                message = "Generation started",
                ownerUserId = ownerUserId,
                artifactPath = outputFile.absolutePath
            )
            AiServerJobStore.update(job)
            AiServerLogStore.append(type.id, "Started image job $jobId -> ${outputFile.name}")
            job
        }

        private fun startVideoJob(jobId: String, action: String, body: JSONObject, ownerUserId: Long?): Result<AiServerJob> = runCatching {
            require(action in videoActions) { "Choose a valid video action." }
            validateJobRequest(action, body)
            val modelPath = body.optString("modelPath").trim()
            val prompt = body.optString("prompt").ifBlank { "A cute tiny animated AI toolbox scene" }
            val mode = if (action == "img2vid") VideoGenerationMode.IMG2VID else VideoGenerationMode.TXT2VID
            val settings = SettingsRepository(applicationContext)
            val sdModel = runBlocking { db.modelDao().getAllModels().first() }.firstOrNull { it.path == modelPath }
            val sdMaxVramCpuGiB = body.optString("maxVramCpuGiB")
                .ifBlank { if (settings.sdMaxCpuRamEnabled.value) settings.sdMaxCpuRamGiB.value else "" }
            val dir = File(filesDir, "video_gen_output/${mode.folderName}").apply { mkdirs() }
            val stamp = timestamp()
            val avi = File(dir, "server_${jobId}_$stamp.avi")
            val mp4 = File(dir, "server_${jobId}_$stamp.mp4")
            val metadata = File(dir, "server_${jobId}_$stamp.json")
            startForegroundService(
                VideoGenerationService.createStartIntent(
                    applicationContext,
                    VideoGenerationConfig(
                        mode = mode,
                        prompt = prompt,
                        negativePrompt = body.optString("negativePrompt"),
                        diffusionModelPath = modelPath,
                        outputAviPath = avi.absolutePath,
                        outputMp4Path = mp4.absolutePath,
                        metadataPath = metadata.absolutePath,
                        initImagePath = body.optString("inputPath").ifBlank { null },
                        useVae = body.optBoolean("useVae", false),
                        vaePath = body.optString("vaePath").ifBlank { null },
                        useT5xxl = body.optBoolean("useT5xxl", false),
                        t5xxlPath = body.optString("t5xxlPath").ifBlank { null },
                        videoFrames = body.optInt("videoFrames", 8),
                        fps = body.optInt("fps", 5),
                        width = body.optInt("width", 480),
                        height = body.optInt("height", 832),
                        steps = body.optInt("steps", 18),
                        cfgScale = body.optDouble("cfgScale", 6.0).toFloat(),
                        flowShift = body.optDoubleOrNull("flowShift")?.toFloat(),
                        samplingMethod = parseEnum(body.optString("samplingMethod"), SamplingMethod.EULER),
                        loras = body.readSdLoras("loras"),
                        // High-noise adapters are a separate ordered list.  Do
                        // not fall back to the legacy regular `loraPath`, or
                        // one adapter is silently applied to both Wan passes.
                        highNoiseLoras = body.readSdLoras("highNoiseLoras", legacyPathKey = null),
                        loraApplyMode = parseEnumOrNull<SdLoraApplyMode>(body.optString("loraApplyMode")),
                        cacheMode = parseEnumOrNull<SdCacheMode>(body.optString("cacheMode")),
                        cacheOption = body.optString("cacheOption"),
                        scmMask = body.optString("scmMask"),
                        scmPolicy = parseEnumOrNull<SdCacheScmPolicy>(body.optString("scmPolicy")),
                        vaeTiling = body.optBoolean("vaeTiling", true),
                        vaeTileSize = body.optString("vaeTileSize", "24x24"),
                        diffusionFa = body.optBoolean("diffusionFa", true),
                        mmap = body.optBoolean("mmap", true),
                        threads = body.optInt("threads", -1),
                        sdParamsBackendMode = body.optString("sdParamsBackendMode").ifBlank { sdModel?.sdParamsBackendMode ?: "auto" },
                        sdRuntimeBackendMode = body.optString("sdRuntimeBackendMode").ifBlank { sdModel?.sdRuntimeBackendMode ?: "auto" },
                        maxVramCpuGiB = sdMaxVramCpuGiB
                    )
                )
            )
            val artifactJson = JSONObject()
                .put("origin", "SERVER")
                .put("serverType", type.id)
                .put("ownerUserId", ownerUserId)
                .put("jobId", jobId)
                .put("prompt", prompt)
            recordArtifact(type.id, ownerUserId, jobId, VIDEO, mp4, "video/mp4", prompt, artifactJson)
            val job = AiServerJob(
                id = jobId,
                serverType = type.id,
                title = prompt.take(80),
                status = "RUNNING",
                message = "Video generation started",
                ownerUserId = ownerUserId,
                artifactPath = mp4.absolutePath
            )
            AiServerJobStore.update(job)
            watchVideoJob(jobId, VideoGenerationStateHolder.getForMode(mode), mp4)
            job
        }

        private fun startTtsJob(jobId: String, action: String, body: JSONObject, ownerUserId: Long?): Result<AiServerJob> = runCatching {
            require(action in ttsActions) { "Choose a valid voice action." }
            validateJobRequest(action, body)
            val modelPath = body.optString("modelPath").trim()
            val text = body.optString("text").trim()
            val uploadedText = text.ifBlank {
                body.optString("inputPath").trim().takeIf { it.isNotBlank() }?.let { path ->
                    val file = File(path)
                    if (file.extension.equals("pdf", ignoreCase = true)) {
                        extractNativePdfTextFromBytes(file.readBytes(), 200_000)
                    } else {
                        file.readText().take(200_000)
                    }
                }.orEmpty()
            }
            val modelName = File(modelPath).name
            OnnxTtsGenerationService.start(
                applicationContext,
                OnnxTtsGenerationJobSpec(
                    modelPath = modelPath,
                    modelName = modelName,
                    text = uploadedText.ifBlank { null },
                    sourceUri = body.optString("sourceUri").ifBlank { null },
                    sourceName = body.optString("sourceName").ifBlank { null },
                    language = body.optString("language", SUPERTONIC_DEFAULT_LANGUAGE),
                    voiceName = body.optString("voiceName").ifBlank { null },
                    totalSteps = body.optInt("totalSteps", SUPERTONIC_DEFAULT_TOTAL_STEPS),
                    speed = body.optDouble("speed", SUPERTONIC_DEFAULT_SPEED.toDouble()).toFloat()
                )
            )
            val job = AiServerJob(
                id = jobId,
                serverType = type.id,
                title = uploadedText.take(80).ifBlank { modelName },
                status = "RUNNING",
                message = "Voice generation started",
                ownerUserId = ownerUserId
            )
            AiServerJobStore.update(job)
            AiServerLogStore.append(type.id, "Started TTS job $jobId")
            watchTtsJob(jobId, ownerUserId, uploadedText.ifBlank { modelName })
            job
        }

        private fun startVideoUpscaleJob(jobId: String, body: JSONObject, ownerUserId: Long?): Result<AiServerJob> = runCatching {
            validateJobRequest("video_upscale", body)
            val inputPath = body.optString("inputPath").trim()
            val engine = parseEnum(body.optString("engine"), UpscalerEngine.REALSR)
            val model = body.optString("model").trim()
            val outputFile = File(File(filesDir, "video_upscale_output").apply { mkdirs() }, "server_${jobId}_${timestamp()}.mp4")
            val config = VideoUpscalerConfig(
                inputPath = inputPath,
                outputPath = outputFile.absolutePath,
                engine = engine,
                model = model,
                scale = body.optInt("scale", 2),
                denoise = body.optInt("denoise", -1),
                loadThreads = body.optInt("loadThreads", 1),
                procThreads = body.optInt("procThreads", 1),
                saveThreads = body.optInt("saveThreads", 1)
            )
            startForegroundService(VideoUpscalerService.createStartIntent(applicationContext, config))
            val metadata = JSONObject()
                .put("origin", "SERVER")
                .put("serverType", type.id)
                .put("ownerUserId", ownerUserId)
                .put("jobId", jobId)
                .put("engine", engine.name)
                .put("model", model)
            recordArtifact(type.id, ownerUserId, jobId, VIDEO, outputFile, "video/mp4", File(inputPath).name, metadata)
            val job = AiServerJob(jobId, type.id, "Video upscale", "RUNNING", 0.05f, "Video upscale started", ownerUserId, artifactPath = outputFile.absolutePath)
            AiServerJobStore.update(job)
            watchVideoUpscaleJob(jobId, outputFile)
            job
        }

        private fun startWorkflowJob(jobId: String, action: String, body: JSONObject, ownerUserId: Long?): Result<AiServerJob> = runCatching {
            require(action in workflowActions) { "Choose a valid workflow." }
            validateJobRequest(action, body)
            when (action) {
                "txt2img_upscale" -> startTxt2ImgUpscaleWorkflow(jobId, body, ownerUserId)
                "manga_translation" -> startMangaTranslationWorkflow(jobId, body, ownerUserId)
                "transcribe_summary" -> {
                    val settings = summarySnapshotFromParams(
                        body,
                        SettingsRepository(applicationContext).workflowSummarySettings.snapshot()
                    )
                    VideoSumupService.startSummarization(
                        context = applicationContext,
                        videoPath = body.optString("inputPath"),
                        videoFileName = body.optString("sourceName").ifBlank { File(body.optString("inputPath")).name },
                        whisperModelPath = body.optString("whisperModelPath"),
                        language = body.optString("whisperLanguage", "auto"),
                        threads = body.optInt("whisperThreads", 4),
                        vadConfig = SettingsRepository(applicationContext).whisperVadConfigSnapshot(),
                        saveToNotes = true,
                        noteType = NoteType.WORKFLOW,
                        settingsOverride = settings
                    )
                    val job = AiServerJob(jobId, type.id, "Transcribe and summarize", "RUNNING", 0.05f, "Workflow started", ownerUserId)
                    AiServerJobStore.update(job)
                    watchTranscribeWorkflowJob(jobId)
                    job
                }
                "media_translation" -> {
                    val settings = summarySnapshotFromParams(
                        body,
                        SettingsRepository(applicationContext).workflowSummarySettings.snapshot()
                    )
                    val ttsModelPath = body.optString("ttsModelPath")
                    MediaTranslationWorkflowService.start(
                        applicationContext,
                        MediaTranslationJobSpec(
                            sourcePath = body.optString("inputPath"),
                            sourceName = body.optString("sourceName").ifBlank { File(body.optString("inputPath")).name },
                            sourceMimeType = body.optString("sourceMimeType").ifBlank { null },
                            whisperModelPath = body.optString("whisperModelPath"),
                            whisperLanguage = body.optString("whisperLanguage", "auto"),
                            whisperThreads = body.optInt("whisperThreads", 4),
                            whisperVad = SettingsRepository(applicationContext).whisperVadConfigSnapshot(),
                            targetLanguage = body.optString("targetLanguage", settings.targetLanguage),
                            ttsModelPath = ttsModelPath,
                            ttsModelName = File(ttsModelPath).name,
                            ttsLanguage = body.optString("ttsLanguage", SUPERTONIC_DEFAULT_LANGUAGE),
                            ttsVoiceName = body.optString("ttsVoiceName").ifBlank { null },
                            ttsSteps = body.optInt("ttsSteps", SUPERTONIC_DEFAULT_TOTAL_STEPS),
                            outputMode = parseEnum(body.optString("outputMode"), MediaTranslationOutputMode.AUTO),
                            replaceOriginalAudio = body.optBoolean("replaceOriginalAudio", true),
                            backendSnapshot = settings.copy(targetLanguage = body.optString("targetLanguage", settings.targetLanguage)),
                            skipFailedTranslationLines = body.optBoolean("skipFailedTranslationLines", false)
                        )
                    )
                    val job = AiServerJob(jobId, type.id, "Media translation", "RUNNING", 0.05f, "Workflow started", ownerUserId)
                    AiServerJobStore.update(job)
                    watchMediaWorkflowJob(jobId, ownerUserId)
                    job
                }
                "subtitle_translation" -> {
                    val settings = summarySnapshotFromParams(
                        body,
                        SettingsRepository(applicationContext).workflowSummarySettings.snapshot()
                    )
                    MediaTranslationWorkflowService.startSubtitleTranslation(
                        applicationContext,
                        SubtitleTranslationJobSpec(
                            videoPath = body.optString("inputPath"),
                            videoName = body.optString("sourceName").ifBlank { File(body.optString("inputPath")).name },
                            sourceSubtitlePath = body.optString("subtitlePath").ifBlank { null },
                            sourceSubtitleName = body.optString("subtitleName").ifBlank { null },
                            whisperModelPath = body.optString("whisperModelPath").ifBlank { null },
                            whisperLanguage = body.optString("whisperLanguage", "auto"),
                            whisperThreads = body.optInt("whisperThreads", 4),
                            whisperVad = SettingsRepository(applicationContext).whisperVadConfigSnapshot(),
                            targetLanguage = body.optString("targetLanguage", settings.targetLanguage),
                            translateSubtitles = body.optBoolean("translateSubtitles", true),
                            burnIntoVideo = body.optBoolean("burnIntoVideo", true),
                            burnStyle = SubtitleBurnStyleSpec(
                                fontSize = body.optInt("fontSize", 24),
                                alignment = body.optInt("alignment", 2),
                                marginV = body.optInt("marginV", 20),
                                marginL = body.optInt("marginL", 0),
                                primaryColorRed = body.optDouble("primaryColorRed", 1.0).toFloat(),
                                primaryColorGreen = body.optDouble("primaryColorGreen", 1.0).toFloat(),
                                primaryColorBlue = body.optDouble("primaryColorBlue", 1.0).toFloat(),
                                fontName = body.optString("fontName", "Default")
                            ),
                            backendSnapshot = settings.copy(targetLanguage = body.optString("targetLanguage", settings.targetLanguage)),
                            skipFailedTranslationLines = body.optBoolean("skipFailedTranslationLines", false)
                        )
                    )
                    val job = AiServerJob(jobId, type.id, "Subtitle translation", "RUNNING", 0.05f, "Workflow started", ownerUserId)
                    AiServerJobStore.update(job)
                    watchMediaWorkflowJob(jobId, ownerUserId)
                    job
                }
                else -> markInformationalJob(jobId, "Workflow action started.").getOrThrow()
            }
        }

        private fun startDocsDatasetJob(jobId: String, action: String, body: JSONObject, ownerUserId: Long?): Result<AiServerJob> = runCatching {
            require(action in docsActions) { "Choose a valid docs or dataset action." }
            validateJobRequest(action, body)
            when (action) {
                "pdf_summary" -> {
                    val settings = summarySnapshotFromParams(
                        body,
                        SettingsRepository(applicationContext).pdfSummarySettings.snapshot()
                    )
                    val inputPath = body.optString("inputPath")
                    val text = extractNativePdfTextFromBytes(File(inputPath).readBytes(), body.optInt("maxChars", 250_000))
                    PDFSummaryService.startSummarization(applicationContext, text, File(inputPath).name, settingsOverride = settings)
                    val job = AiServerJob(jobId, type.id, "PDF summary", "RUNNING", 0.05f, "PDF summary started", ownerUserId)
                    AiServerJobStore.update(job)
                    watchPdfSummaryJob(jobId)
                    job
                }
                "video_summary" -> {
                    val settings = summarySnapshotFromParams(
                        body,
                        SettingsRepository(applicationContext).videoSummarySettings.snapshot()
                    )
                    VideoSumupService.startSummarization(
                        context = applicationContext,
                        videoPath = body.optString("inputPath"),
                        videoFileName = body.optString("sourceName").ifBlank { File(body.optString("inputPath")).name },
                        whisperModelPath = body.optString("whisperModelPath"),
                        language = body.optString("whisperLanguage", "auto"),
                        threads = body.optInt("whisperThreads", 4),
                        vadConfig = SettingsRepository(applicationContext).whisperVadConfigSnapshot(),
                        saveToNotes = true,
                        noteType = NoteType.VIDEO_SUMMARY,
                        settingsOverride = settings
                    )
                    val job = AiServerJob(jobId, type.id, "Video summary", "RUNNING", 0.05f, "Video summary started", ownerUserId)
                    AiServerJobStore.update(job)
                    watchVideoSummaryJob(jobId)
                    job
                }
                in pdfToolActions -> startPdfToolJob(jobId, action, body, ownerUserId)
                "dataset_import" -> startDatasetImportJob(jobId, body, ownerUserId)
                "dataset_pipeline" -> startDatasetPipelineJob(jobId, body, ownerUserId)
                "dataset_export" -> startDatasetExportJob(jobId, body, ownerUserId)
                "pdf_tools" -> startLegacyPdfToolsJob(jobId, body, ownerUserId)
                "dataset_creator" -> startLegacyDatasetCreatorJob(jobId, body, ownerUserId)
                else -> error("Unsupported docs or dataset action: $action")
            }
        }

        private fun startLlamaChatJob(jobId: String, action: String, body: JSONObject, ownerUserId: Long?): Result<AiServerJob> = runCatching {
            val message = body.optString("message").trim()
            val isNewUserMessage = action == "web_chat_send"
            if (isNewUserMessage) {
                validateJobRequest("web_chat_send", body)
            } else {
                require(body.optLong("chatId", -1L) > 0L) { "Choose a chat." }
            }
            val jobTitle = when (action) {
                "web_chat_continue" -> "Continue chat"
                "web_chat_regenerate" -> "Regenerate reply"
                else -> message.take(80).ifBlank { "Chat message" }
            }
            val job = AiServerJob(jobId, type.id, jobTitle, "RUNNING", 0.05f, "Preparing chat", ownerUserId)
            AiServerJobStore.update(job)
            launchTracked(jobId) {
                var pendingAssistantMessageId: Long? = null
                runCatching {
                    val provider = resolveWebProvider(body.optLong("providerId", -1L), ownerUserId)
                    val chat = resolveWebChat(body.optLong("chatId", -1L), provider.id, body.optString("chatTitle"), ownerUserId)
                    val now = System.currentTimeMillis()
                    if (chat.providerId != provider.id) {
                        db.aiServerDao().updateWebChat(chat.copy(providerId = provider.id, updatedAt = now))
                    }
                    if (isNewUserMessage) {
                        val hasAttachments = (body.optJSONArray("attachments")?.length() ?: 0) > 0
                        require(message.isNotBlank() || hasAttachments) {
                            "Write a message or attach a file."
                        }
                        val attachments = chatAttachmentsFromParams(body)
                        val imagePath = body.optString("imagePath").takeIf { it.isNotBlank() }
                            ?: attachments.firstOrNull { it.attachmentType == "image" }?.path
                        val audioPath = body.optString("audioPath").takeIf { it.isNotBlank() }
                            ?: attachments.firstOrNull { it.attachmentType == "audio" }?.path
                        val documentPath = body.optString("documentPath").takeIf { it.isNotBlank() }
                            ?: attachments.firstOrNull { it.attachmentType == "document" }?.path
                        val userMessageId = db.aiServerDao().upsertWebMessage(
                            AiServerWebMessageEntity(
                                chatId = chat.id,
                                role = "user",
                                content = message,
                                imagePath = imagePath,
                                audioPath = audioPath,
                                documentPath = documentPath,
                                createdAt = now
                            )
                        )
                        attachments.forEach { attachment ->
                            db.aiServerDao().upsertWebMessageAttachment(attachment.copy(messageId = userMessageId))
                        }
                    }
                    val assistantMessageId = db.aiServerDao().upsertWebMessage(
                        AiServerWebMessageEntity(
                            chatId = chat.id,
                            role = "assistant",
                            content = "",
                            toolActivity = "Generating reply",
                            createdAt = System.currentTimeMillis()
                        )
                    )
                    pendingAssistantMessageId = assistantMessageId
                    recordWebToolEvent(
                        messageId = assistantMessageId,
                        toolName = "chat_generation",
                        phase = "started",
                        status = "RUNNING",
                        arguments = JSONObject()
                            .put("action", action)
                            .put("providerId", provider.id)
                            .put("engine", provider.engine)
                            .put("model", provider.modelName ?: provider.liteRtModelId ?: JSONObject.NULL)
                    )
                    updateJob(jobId, type.id, "RUNNING", 0.2f, "Generating reply", null)
                    val messages = db.aiServerDao().getWebMessages(chat.id).filterNot { it.id == assistantMessageId }
                    val reply = generateWebChatReply(
                        jobId = jobId,
                        chat = chat,
                        provider = provider,
                        messages = messages,
                        body = body,
                        assistantMessageId = assistantMessageId,
                        ownerUserId = ownerUserId
                    )
                    db.aiServerDao().updateWebMessageContent(
                        id = assistantMessageId,
                        content = reply.output,
                        thinking = reply.thinking,
                        toolActivity = null,
                        isError = false
                    )
                    recordWebToolEvent(
                        messageId = assistantMessageId,
                        toolName = "chat_generation",
                        phase = "completed",
                        status = "COMPLETED",
                        resultText = reply.output.take(1200)
                    )
                    db.aiServerDao().updateWebChat(chat.copy(providerId = provider.id, updatedAt = System.currentTimeMillis()))
                    updateJob(jobId, type.id, "COMPLETED", 1f, "Reply complete", null)
                }.onFailure { error ->
                    if (error is CancellationException) {
                        val cancelledMessage = "Generation cancelled"
                        pendingAssistantMessageId?.let { pendingId ->
                            db.aiServerDao().updateWebMessageContent(
                                id = pendingId,
                                content = cancelledMessage,
                                toolActivity = null,
                                isError = true
                            )
                            recordWebToolEvent(
                                messageId = pendingId,
                                toolName = "chat_generation",
                                phase = "cancelled",
                                status = "CANCELLED",
                                errorText = cancelledMessage
                            )
                        }
                        updateJob(jobId, type.id, "CANCELLED", 0f, cancelledMessage, null)
                        return@launchTracked
                    }
                    val chatId = body.optLong("chatId", -1L)
                    val failureMessage = error.message ?: "Chat failed"
                    val pendingId = pendingAssistantMessageId
                    if (pendingId != null) {
                        db.aiServerDao().updateWebMessageContent(pendingId, failureMessage, isError = true)
                        recordWebToolEvent(
                            messageId = pendingId,
                            toolName = "chat_generation",
                            phase = "failed",
                            status = "FAILED",
                            errorText = failureMessage
                        )
                    } else if (chatId > 0L) {
                        db.aiServerDao().upsertWebMessage(
                            AiServerWebMessageEntity(
                                chatId = chatId,
                                role = "assistant",
                                content = failureMessage,
                                isError = true,
                                createdAt = System.currentTimeMillis()
                            )
                        )
                    }
                    updateJob(jobId, type.id, "FAILED", 0f, failureMessage, null)
                }
            }
            job
        }

        private fun markInformationalJob(jobId: String, message: String): Result<AiServerJob> = runCatching {
            val job = AiServerJob(
                id = jobId,
                serverType = type.id,
                title = type.displayName,
                status = "READY",
                progress = 1f,
                message = message
            )
            AiServerJobStore.update(job)
            AiServerLogStore.append(type.id, "$jobId: $message")
            job
        }

        private val imageActions = setOf(
            "sd_txt2img",
            "sd_img2img",
            "sd_inpaint",
            "sd_adetailer",
            "sd_upscale",
            "onnx_txt2img",
            "onnx_img2img",
            "onnx_bgr"
        )
        private val videoActions = setOf("txt2vid", "img2vid")
        private val workflowActions = setOf("transcribe_summary", "txt2img_upscale", "manga_translation", "media_translation", "subtitle_translation")
        private val ttsActions = setOf("tts_text", "tts_document")
        private val chatActions = setOf("web_chat_send", "web_chat_continue", "web_chat_regenerate")
        private val pdfToolActions = setOf(
            "pdf_merge",
            "pdf_split",
            "pdf_extract_text",
            "pdf_ocr_text",
            "pdf_ocr_searchable",
            "pdf_translate_ocr",
            "pdf_translate_text_layer",
            "pdf_images_to_pdf",
            "pdf_compress",
            "pdf_split_size"
        )
        private val docsActions = pdfToolActions + setOf(
            "pdf_tools",
            "pdf_summary",
            "video_summary",
            "dataset_creator",
            "dataset_import",
            "dataset_pipeline",
            "dataset_export"
        )

        private fun validateJobRequest(action: String, params: JSONObject) {
            val fields = optionsJson(type).optJSONObject("fields")?.optJSONArray(action)
                ?: error("Unsupported action: $action")
            for (index in 0 until fields.length()) {
                val field = fields.optJSONObject(index) ?: continue
                if (!field.optBoolean("required", false)) continue
                if (!fieldVisible(field, params)) continue
                val id = field.optString("id")
                val rawValue = params.opt(id)
                val hasValue = when (rawValue) {
                    is JSONArray -> rawValue.length() > 0
                    null -> false
                    JSONObject.NULL -> false
                    else -> rawValue.toString().trim().isNotBlank()
                }
                require(hasValue) { "Missing ${fieldLabel(field)}." }
            }
        }

        private fun validateModelCapabilities(action: String, params: JSONObject) {
            when {
                action.startsWith("sd_") && action != "sd_upscale" -> {
                    val modelPath = params.optString("modelPath")
                    val model = runBlocking { db.modelDao().getAllModels().first() }.firstOrNull { it.path == modelPath }
                    require(model != null) { "Choose an installed SD image model." }
                    require(!model.hasSdCapability(SD_CAPABILITY_VID_GEN)) { "Video generation models are only available in Video Studio." }
                    validateSdLoraRequest(action, params, model)
                    if (action == "sd_adetailer") {
                        val detectorPath = params.optString("adModelPath").trim()
                        val detector = runBlocking {
                            db.modelDao().getModelsByTypesSync(listOf(ModelType.SD_ADETAILER))
                        }.firstOrNull { it.path == detectorPath }
                        require(detector != null && isCompatibleSdADetailerDetector(File(detectorPath))) {
                            "Choose an installed, compatible ADetailer detector."
                        }
                    }
                    if (action == "sd_txt2img") {
                        require(model.sdCapabilities.isNullOrBlank() || model.hasSdCapability(SD_CAPABILITY_TXT2IMG)) { "This model does not support text to image." }
                    }
                    if (action == "sd_img2img" || action == "sd_inpaint" || action == "sd_adetailer") {
                        require(model.sdCapabilities.isNullOrBlank() || model.hasSdCapability(SD_CAPABILITY_IMG2IMG)) { "This model does not support image to image." }
                    }
                }
                action in setOf("txt2vid", "img2vid") -> {
                    val modelPath = params.optString("modelPath")
                    val model = runBlocking { db.modelDao().getAllModels().first() }.firstOrNull { it.path == modelPath }
                    require(model != null && model.hasSdCapability(SD_CAPABILITY_VID_GEN)) { "Choose an installed video diffusion model." }
                    // Wan 2.2 has a distinct high-noise prompt contract.  The
                    // installed-file/readability checks still apply here, but
                    // family matching is intentionally deferred for legacy
                    // video rows whose database family predates Wan metadata.
                    validateSdLoraRequest(action, params, model)
                }
                action.startsWith("tts_") -> {
                    val rawSpeed = params.opt("speed")?.toString().orEmpty()
                    val decimals = rawSpeed.substringAfter('.', "").trimEnd('0')
                    require(decimals.length <= 2) { "Speed can use no more than two decimals." }
                }
                action == "video_upscale" -> {
                    val engine = parseEnum(params.optString("engine"), UpscalerEngine.REALSR)
                    val model = UpscalerModels.getByName(params.optString("model"))
                        ?: error("Choose a valid video upscale model.")
                    require(model.engine == engine) { "This model is not available for the selected engine." }
                    val scale = params.optInt("scale", model.scales.firstOrNull() ?: 2)
                    require(scale in model.scales) { "This model only supports ${model.scales.joinToString("x, ", postfix = "x")}." }
                    val denoise = params.optInt("denoise", -1)
                    require(model.supportsDenoise || denoise == -1) { "This model does not support denoise." }
                }
                action in chatActions -> {
                    val providerId = params.optLong("providerId", -1L)
                    val provider = runBlocking { db.aiServerDao().getWebProvider(providerId) }
                        ?: error("Provider not found")
                    require(params.optString("imagePath").isBlank() || provider.supportsVision) {
                        "The selected provider does not support image attachments."
                    }
                    require(params.optString("audioPath").isBlank() || provider.supportsAudio) {
                        "The selected provider does not support audio attachments."
                    }
                }
            }
        }

        private fun validateSdLoraRequest(action: String, params: JSONObject, baseModel: ModelEntity) {
            val videoRequest = action == "txt2vid" || action == "img2vid"
            val baseLoras = params.readSdLoras("loras")
            val secondaryLoras = when {
                videoRequest -> params.readSdLoras("highNoiseLoras", legacyPathKey = null)
                action == "sd_adetailer" -> params.readSdLoras(
                    "adLoras",
                    legacyPathKey = "adLoraPath",
                    legacyStrengthKey = "adLoraStrength"
                )
                else -> emptyList()
            }
            val loras = baseLoras + secondaryLoras
            if (loras.isEmpty()) return

            try {
                // Base/detail and regular/high-noise are separate native scopes,
                // so the same adapter may intentionally appear once in each.
                validateSdLoras(baseLoras, requireReadableFiles = true)
                validateSdLoras(secondaryLoras, requireReadableFiles = true)
            } catch (error: SdLoraConfigurationException) {
                throw IllegalArgumentException(error.message ?: "Invalid LoRA configuration")
            }

            val selectedPaths = loras.map { it.path }.toSet()
            val installedLoras = runBlocking {
                db.modelDao().getModelsByTypesSync(listOf(ModelType.SD_LORA))
            }.filter { it.path in selectedPaths }
            require(installedLoras.size == selectedPaths.size) {
                "Every selected LoRA must be an installed Stable Diffusion model."
            }
            if (!videoRequest) {
                val issues = validateSdLoraModelCompatibility(baseModel, installedLoras)
                require(issues.isEmpty()) {
                    "Selected LoRA is not compatible with the chosen model family: " +
                        issues.joinToString(", ") { "${it.index}:${it.code.name.lowercase()}" }
                }
            }
        }

        private fun fieldVisible(field: JSONObject, params: JSONObject): Boolean {
            val rule = field.optJSONObject("visibleWhen") ?: return true
            val key = rule.optString("field").ifBlank { return true }
            val expected = rule.opt("equals") ?: return true
            return when (expected) {
                is Boolean -> params.optBoolean(key) == expected
                else -> params.optString(key) == expected.toString()
            }
        }

        private fun fieldLabel(field: JSONObject): String =
            field.optJSONObject("label")?.optString("en")?.ifBlank { null }
                ?: field.optString("id")

        private fun legacyActionFrom(params: JSONObject): String {
            val serverMode = params.optString("mode")
            val engine = params.optString("engine")
            return when (type) {
                AiServerType.IMAGE -> when {
                    serverMode.startsWith("sd_") || serverMode.startsWith("onnx_") -> serverMode
                    engine == "onnx" && serverMode == "bgr" -> "onnx_bgr"
                    engine == "onnx" && serverMode == "img2img" -> "onnx_img2img"
                    engine == "onnx" -> "onnx_txt2img"
                    serverMode == "inpaint" -> "sd_inpaint"
                    serverMode == "adetailer" -> "sd_adetailer"
                    serverMode == "img2img" -> "sd_img2img"
                    serverMode == "upscale" -> "sd_upscale"
                    else -> "sd_txt2img"
                }
                AiServerType.VIDEO -> if (serverMode == "img2vid") "img2vid" else "txt2vid"
                AiServerType.WORKFLOWS -> serverMode.ifBlank { "transcribe_summary" }
                AiServerType.TTS -> if (params.optString("sourceUri").isNotBlank()) "tts_document" else "tts_text"
                AiServerType.VIDEO_UPSCALE -> "video_upscale"
                AiServerType.DOCS_DATASETS -> serverMode.ifBlank { "pdf_summary" }
                AiServerType.LLAMA_CHAT -> "web_chat_send"
                AiServerType.AI_HUB -> ""
            }
        }

        private fun jobTitleFor(action: String, params: JSONObject): String =
            params.optString("prompt")
                .ifBlank { params.optString("text") }
                .ifBlank { params.optString("message") }
                .ifBlank { labelForAction(action) }
                .take(96)

        private fun labelForAction(action: String): String =
            action.replace('_', ' ').replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.US) else it.toString() }

        private fun startTxt2ImgUpscaleWorkflow(jobId: String, body: JSONObject, ownerUserId: Long?): AiServerJob {
            val prompt = body.optString("prompt").ifBlank { "A cute modern AI toolbox illustration" }
            val settings = SettingsRepository(applicationContext)
            val modelPath = body.optString("modelPath")
            val upscalerPath = body.optString("upscalerPath")
            val allModels = runBlocking { db.modelDao().getAllModels().first() }
            val sdModel = allModels.firstOrNull { it.path == modelPath }
            val upscalerModel = allModels.firstOrNull { it.path == upscalerPath }
            val sdMaxVramCpuGiB = body.optString("maxVramCpuGiB")
                .ifBlank { if (settings.sdMaxCpuRamEnabled.value) settings.sdMaxCpuRamGiB.value else "" }
            val outputDir = File(filesDir, "sd_output/workflow").apply { mkdirs() }
            val firstOutput = File(outputDir, "server_${jobId}_${timestamp()}.png")
            val finalOutput = File(outputDir, "server_${jobId}_upscaled_${timestamp()}.png")
            val txt2img = SDConfig(
                modelPath = modelPath,
                prompt = prompt,
                negativePrompt = body.optString("negativePrompt"),
                width = body.optInt("width", 512),
                height = body.optInt("height", 512),
                steps = body.optInt("steps", 20),
                cfgScale = body.optDouble("cfgScale", 7.0).toFloat(),
                seed = body.optLong("seed", -1L),
                samplingMethod = parseEnum(body.optString("samplingMethod"), SamplingMethod.EULER_A),
                outputPath = firstOutput.absolutePath,
                mode = SDMode.TXT2IMG,
                threads = body.optInt("threads", 4),
                vaePath = body.optNullableString("vaePath"),
                taePath = body.optNullableString("taePath"),
                clipLPath = body.optNullableString("clipLPath"),
                clipGPath = body.optNullableString("clipGPath"),
                t5xxlPath = body.optNullableString("t5xxlPath"),
                llmPath = body.optNullableString("llmPath"),
                llmVisionPath = body.optNullableString("llmVisionPath"),
                photoMakerPath = body.optNullableString("photoMakerPath"),
                loras = body.readSdLoras("loras"),
                loraApplyMode = parseEnumOrNull<SdLoraApplyMode>(body.optString("loraApplyMode")),
                sdParamsBackendMode = body.optString("sdParamsBackendMode").ifBlank { sdModel?.sdParamsBackendMode ?: "auto" },
                sdRuntimeBackendMode = body.optString("sdRuntimeBackendMode").ifBlank { sdModel?.sdRuntimeBackendMode ?: "auto" },
                maxVramCpuGiB = sdMaxVramCpuGiB
            )
            val upscale = SDConfig(
                modelPath = upscalerPath,
                prompt = prompt,
                outputPath = finalOutput.absolutePath,
                upscaleModel = upscalerPath,
                upscaleRepeats = body.optInt("upscaleRepeats", 1),
                mode = SDMode.UPSCALE,
                threads = body.optInt("upscaleThreads", 4),
                sdParamsBackendMode = body.optString("upscalerSdParamsBackendMode").ifBlank { upscalerModel?.sdParamsBackendMode ?: "auto" },
                sdRuntimeBackendMode = body.optString("upscalerSdRuntimeBackendMode").ifBlank { upscalerModel?.sdRuntimeBackendMode ?: "auto" },
                maxVramCpuGiB = sdMaxVramCpuGiB
            )
            startForegroundService(
                StableDiffusionService.createStartWorkflowIntent(
                    applicationContext,
                    SDWorkflowConfig(txt2imgConfig = txt2img, upscaleConfig = upscale)
                )
            )
            val metadata = JSONObject()
                .put("origin", "SERVER")
                .put("serverType", type.id)
                .put("ownerUserId", ownerUserId)
                .put("jobId", jobId)
                .put("workflow", "txt2img_upscale")
                .put("prompt", prompt)
            recordArtifact(type.id, ownerUserId, jobId, IMAGE, finalOutput, "image/png", prompt, metadata)
            val job = AiServerJob(jobId, type.id, prompt.take(80), "RUNNING", 0.05f, "Workflow started", ownerUserId, artifactPath = finalOutput.absolutePath)
            AiServerJobStore.update(job)
            watchSdWorkflowJob(jobId, finalOutput)
            return job
        }

        private fun startMangaTranslationWorkflow(jobId: String, body: JSONObject, ownerUserId: Long?): AiServerJob {
            val inputUris = body.pathList("inputPaths").map(::uriForPath)
            require(inputUris.isNotEmpty()) { "Upload at least one CBZ, ZIP, or PDF file." }
            val exportPdf = body.optBoolean("exportPdf", true)
            val exportCbz = body.optBoolean("exportCbz", true)
            require(exportPdf || exportCbz) { "Choose at least one manga output format." }
            val translationSettings = summarySnapshotFromParams(
                body,
                SettingsRepository(applicationContext).pdfTranslationSettings.snapshot()
            )
            val job = AiServerJob(jobId, type.id, "Manga translation", "RUNNING", 0.05f, "Manga translation started", ownerUserId)
            AiServerJobStore.update(job)
            launchTracked(jobId) {
                runCatching {
                    PDFService(applicationContext).translateMangaCbzBatch(
                        cbzUris = inputUris,
                        exportPdf = exportPdf,
                        exportCbz = exportCbz,
                        settingsOverride = translationSettings
                    ) { progress ->
                        updateJob(
                            jobId,
                            type.id,
                            "RUNNING",
                            pdfProgressFraction(progress),
                            pdfProgressMessage(progress),
                            null
                        )
                    }.getOrThrow()
                }.fold(
                    onSuccess = { results ->
                        val outputFiles = results.flatMap { result ->
                            listOfNotNull(
                                result.pdfUri?.let { copyUriToServerFile(it, "manga", "translated_${safeFileName(result.sourceName)}.pdf") },
                                result.cbzUri?.let { copyUriToServerFile(it, "manga", "translated_${safeFileName(result.sourceName)}.cbz") }
                            )
                        }
                        outputFiles.forEach { file ->
                            recordArtifact(
                                type.id,
                                ownerUserId,
                                jobId,
                                DOCUMENT,
                                file,
                                mimeForFile(file),
                                file.name,
                                artifactMetadata(jobId, "manga_translation")
                            )
                        }
                        val failed = results.count { !it.isSuccess }
                        val message = if (failed == 0) {
                            "Manga translation complete"
                        } else {
                            "Manga translation complete with $failed failed file(s)"
                        }
                        updateJob(jobId, type.id, "COMPLETED", 1f, message, outputFiles.firstOrNull()?.absolutePath)
                    },
                    onFailure = { error ->
                        updateJob(jobId, type.id, "FAILED", 0f, error.message ?: "Manga translation failed", null)
                    }
                )
            }
            return job
        }

        private fun startPdfToolJob(jobId: String, action: String, body: JSONObject, ownerUserId: Long?): AiServerJob {
            val job = AiServerJob(jobId, type.id, labelForAction(action), "RUNNING", 0.05f, "PDF task started", ownerUserId)
            AiServerJobStore.update(job)
            launchTracked(jobId) {
                val pdfService = PDFService(applicationContext)
                runCatching {
                    when (action) {
                        "pdf_merge" -> {
                            val paths = body.pathList("inputPaths")
                            require(paths.size >= 2) { "Upload at least two PDF files." }
                            val uri = pdfService.mergePdfs(paths.map(::uriForPath)).getOrThrow()
                            listOf(copyUriToServerFile(uri, "pdfs", "merged_${timestamp()}.pdf"))
                        }
                        "pdf_split" -> {
                            val uri = pdfService.splitPdf(uriForPath(body.requiredPath()), body.optString("pageRange")).getOrThrow()
                            listOf(copyUriToServerFile(uri, "pdfs", "split_${timestamp()}.pdf"))
                        }
                        "pdf_extract_text" -> {
                            val inputPath = body.requiredPath()
                            val extraction = pdfService.extractTextDetailed(uriForPath(inputPath)) { progress ->
                                updateJob(
                                    jobId,
                                    type.id,
                                    "RUNNING",
                                    if (progress.totalPages > 0) progress.processedPages.toFloat() / progress.totalPages.toFloat() else 0.2f,
                                    "Extracting PDF text ${progress.processedPages}/${progress.totalPages}",
                                    null
                                )
                            }.getOrThrow()
                            val text = extraction.text.take(body.optInt("maxChars", 250_000).coerceAtLeast(1_000))
                            insertPdfNote("PDF extract ${sourceNameFromPath(inputPath)}", text, inputPath)
                            listOf(writeTextArtifact("pdf_extract_${timestamp()}.txt", text))
                        }
                        "pdf_ocr_text" -> {
                            val inputPath = body.requiredPath()
                            val uri = uriForPath(inputPath)
                            val text = if (isImagePath(inputPath, body.optString("sourceMimeType"))) {
                                pdfService.performOCR(uri).getOrThrow()
                            } else {
                                pdfService.performOcrOnPdf(uri) { progress ->
                                    updateJob(
                                        jobId,
                                        type.id,
                                        "RUNNING",
                                        if (progress.totalPages > 0) progress.processedPages.toFloat() / progress.totalPages.toFloat() else 0.2f,
                                        "OCR ${progress.processedPages}/${progress.totalPages}",
                                        null
                                    )
                                }.getOrThrow().text
                            }.take(body.optInt("maxChars", 250_000).coerceAtLeast(1_000))
                            insertPdfNote("OCR ${sourceNameFromPath(inputPath)}", text, inputPath)
                            listOf(writeTextArtifact("ocr_${timestamp()}.txt", text))
                        }
                        "pdf_ocr_searchable" -> {
                            val uri = pdfService.exportSearchableOcrPdf(uriForPath(body.requiredPath())) { progress ->
                                updateJob(
                                    jobId,
                                    type.id,
                                    "RUNNING",
                                    if (progress.totalPages > 0) progress.processedPages.toFloat() / progress.totalPages.toFloat() else 0.2f,
                                    "Building searchable PDF ${progress.processedPages}/${progress.totalPages}",
                                    null
                                )
                            }.getOrThrow()
                            listOf(copyUriToServerFile(uri, "pdfs", "ocr_searchable_${timestamp()}.pdf"))
                        }
                        "pdf_translate_ocr" -> {
                            val translationSettings = summarySnapshotFromParams(
                                body,
                                SettingsRepository(applicationContext).pdfTranslationSettings.snapshot()
                            )
                            val uri = pdfService.exportTranslatedOcrPdf(
                                pdfUri = uriForPath(body.requiredPath()),
                                settingsOverride = translationSettings
                            ) { progress ->
                                updateJob(jobId, type.id, "RUNNING", pdfProgressFraction(progress), pdfProgressMessage(progress), null)
                            }.getOrThrow()
                            listOf(copyUriToServerFile(uri, "pdfs", "translated_ocr_${timestamp()}.pdf"))
                        }
                        "pdf_translate_text_layer" -> {
                            val paths = body.pathList("inputPaths").ifEmpty { listOf(body.requiredPath()) }
                            val translationSettings = summarySnapshotFromParams(
                                body,
                                SettingsRepository(applicationContext).pdfTranslationSettings.snapshot()
                            )
                            paths.mapIndexed { index, path ->
                                val uri = pdfService.exportTranslatedTextLayerPdf(
                                    pdfUri = uriForPath(path),
                                    outputFileName = "translated_text_layer_${timestamp()}_$index.pdf",
                                    settingsOverride = translationSettings
                                ) { progress ->
                                    val perFile = pdfProgressFraction(progress)
                                    val totalProgress = ((index.toFloat() + perFile) / paths.size.toFloat()).coerceIn(0.05f, 0.95f)
                                    updateJob(jobId, type.id, "RUNNING", totalProgress, pdfProgressMessage(progress), null)
                                }.getOrThrow()
                                copyUriToServerFile(uri, "pdfs", "translated_text_layer_${timestamp()}_$index.pdf")
                            }
                        }
                        "pdf_images_to_pdf" -> {
                            val paths = body.pathList("inputPaths")
                            require(paths.isNotEmpty()) { "Upload at least one image." }
                            val uri = pdfService.imagesToPdf(paths.map(::uriForPath)).getOrThrow()
                            listOf(copyUriToServerFile(uri, "pdfs", "images_${timestamp()}.pdf"))
                        }
                        "pdf_compress" -> {
                            val uri = pdfService.compressPdf(body.requiredUri(), body.optInt("compressionLevel", 5)).getOrThrow()
                            listOf(copyUriToServerFile(uri, "pdfs", "compressed_${timestamp()}.pdf"))
                        }
                        "pdf_split_size" -> {
                            val maxBytes = body.optLong("maxSizeMb", 5L).coerceAtLeast(1L) * 1024L * 1024L
                            pdfService.splitBySize(body.requiredUri(), maxBytes).getOrThrow().mapIndexed { index, uri ->
                                copyUriToServerFile(uri, "pdfs", "part_${index + 1}_${timestamp()}.pdf")
                            }
                        }
                        else -> error("Unsupported PDF action: $action")
                    }
                }.fold(
                    onSuccess = { files ->
                        files.forEach { file ->
                            recordArtifact(
                                type.id,
                                ownerUserId,
                                jobId,
                                if (file.extension.equals("txt", ignoreCase = true)) DOCUMENT else DOCUMENT,
                                file,
                                mimeForFile(file),
                                file.name,
                                artifactMetadata(jobId, action)
                            )
                        }
                        updateJob(jobId, type.id, "COMPLETED", 1f, "PDF task complete", files.firstOrNull()?.absolutePath)
                    },
                    onFailure = { error ->
                        updateJob(jobId, type.id, "FAILED", 0f, error.message ?: "PDF task failed", null)
                    }
                )
            }
            return job
        }

        private fun startLegacyPdfToolsJob(jobId: String, body: JSONObject, ownerUserId: Long?): AiServerJob {
            val mappedAction = when (body.optString("tool")) {
                "translate", "translate_text_layer" -> "pdf_translate_text_layer"
                "translate_ocr" -> "pdf_translate_ocr"
                "split" -> "pdf_split"
                "compress" -> "pdf_compress"
                else -> "pdf_extract_text"
            }
            return startPdfToolJob(jobId, mappedAction, body, ownerUserId)
        }

        private fun startDatasetImportJob(jobId: String, body: JSONObject, ownerUserId: Long?): AiServerJob {
            val projectId = body.optLong("projectId", -1L)
            require(projectId > 0) { "Choose a dataset project." }
            val inputPath = body.requiredPath()
            val sourceName = sourceNameFromPath(inputPath)
            val jobName = if (inputPath.endsWith(".pdf", ignoreCase = true) || body.optString("sourceMimeType") == "application/pdf") {
                "Import PDF"
            } else {
                "Import text"
            }
            val datasetJob = if (jobName == "Import PDF") {
                DatasetProcessor.Job.ImportPdf(projectId, uriForPath(inputPath).toString(), sourceName, jobName)
            } else {
                DatasetProcessor.Job.ImportTxt(projectId, uriForPath(inputPath).toString(), sourceName, jobName)
            }
            DatasetForegroundService.enqueue(applicationContext, datasetJob)
            val job = AiServerJob(jobId, type.id, jobName, "RUNNING", 0.05f, "Dataset import queued", ownerUserId)
            AiServerJobStore.update(job)
            watchDatasetJob(jobId, projectId, "Dataset import complete")
            return job
        }

        private fun startDatasetPipelineJob(jobId: String, body: JSONObject, ownerUserId: Long?): AiServerJob {
            val projectId = body.optLong("projectId", -1L)
            require(projectId > 0) { "Choose a dataset project." }
            val stage = body.optString("stage", "all")
            val cleanPrompt = body.optString("cleanPrompt").ifBlank { defaultDatasetPrompt(PromptType.CLEAN, DEFAULT_CLEAN_PROMPT) }
            val questionPrompt = body.optString("questionPrompt").ifBlank { defaultDatasetPrompt(PromptType.QUESTION, DEFAULT_QUESTION_PROMPT) }
            val answerPrompt = body.optString("answerPrompt").ifBlank { defaultDatasetPrompt(PromptType.ANSWER, DEFAULT_ANSWER_PROMPT) }
            val reviewPrompt = body.optString("reviewPrompt").ifBlank { defaultDatasetPrompt(PromptType.REVIEW, DEFAULT_REVIEW_PROMPT) }
            val jobs = when (stage) {
                "clean" -> listOf(DatasetProcessor.Job.Clean(projectId, cleanPrompt, "Clean chunks"))
                "questions" -> listOf(DatasetProcessor.Job.Questions(projectId, questionPrompt, "Generate questions"))
                "answers" -> listOf(DatasetProcessor.Job.Answers(projectId, answerPrompt, "Generate answers"))
                "rating" -> listOf(DatasetProcessor.Job.Rating(projectId, reviewPrompt, "Rate answers"))
                else -> listOf(
                    DatasetProcessor.Job.Clean(projectId, cleanPrompt, "Clean chunks"),
                    DatasetProcessor.Job.Questions(projectId, questionPrompt, "Generate questions"),
                    DatasetProcessor.Job.Answers(projectId, answerPrompt, "Generate answers"),
                    DatasetProcessor.Job.Rating(projectId, reviewPrompt, "Rate answers")
                )
            }
            DatasetForegroundService.enqueueBatch(applicationContext, jobs)
            val job = AiServerJob(jobId, type.id, "Dataset queue", "RUNNING", 0.05f, "Dataset queue started", ownerUserId)
            AiServerJobStore.update(job)
            watchDatasetJob(jobId, projectId, "Dataset queue complete")
            return job
        }

        private fun startDatasetExportJob(jobId: String, body: JSONObject, ownerUserId: Long?): AiServerJob {
            val projectId = body.optLong("projectId", -1L)
            require(projectId > 0) { "Choose a dataset project." }
            val job = AiServerJob(jobId, type.id, "Dataset export", "RUNNING", 0.1f, "Exporting dataset", ownerUserId)
            AiServerJobStore.update(job)
            launchTracked(jobId) {
                runCatching {
                    val qaList = db.datasetDao().getQAForProjectSync(projectId)
                    val minScore = body.optInt("minScore", 3)
                    val exportItems = qaList.filter { (it.score ?: 0) >= minScore && !it.answer.isNullOrBlank() }
                    require(exportItems.isNotEmpty()) { "No reviewed dataset rows match the export filter." }
                    val entries = exportItems.map { qa ->
                        DatasetEntry(
                            source = DatasetSource.MANUAL,
                            sourceName = "Generated",
                            instruction = qa.question,
                            input = "",
                            output = qa.answer.orEmpty()
                        )
                    }
                    val format = when (body.optString("exportFormat", "jsonl").lowercase(Locale.US)) {
                        "alpaca" -> DatasetFormat.ALPACA
                        "sharegpt", "chatml" -> DatasetFormat.SHAREGPT
                        else -> DatasetFormat.JSONL
                    }
                    val output = DatasetExporter.export(entries, format)
                    writeTextArtifact("dataset_${projectId}_${timestamp()}.${format.extension}", output)
                }.fold(
                    onSuccess = { file ->
                        recordArtifact(
                            type.id,
                            ownerUserId,
                            jobId,
                            DATASET,
                            file,
                            mimeForFile(file),
                            file.name,
                            artifactMetadata(jobId, "dataset_export")
                        )
                        updateJob(jobId, type.id, "COMPLETED", 1f, "Dataset export complete", file.absolutePath)
                    },
                    onFailure = { error ->
                        updateJob(jobId, type.id, "FAILED", 0f, error.message ?: "Dataset export failed", null)
                    }
                )
            }
            return job
        }

        private fun startLegacyDatasetCreatorJob(jobId: String, body: JSONObject, ownerUserId: Long?): AiServerJob {
            val hasSource = body.optString("inputPath").isNotBlank()
            return if (hasSource) {
                startDatasetImportJob(jobId, body, ownerUserId)
            } else {
                startDatasetExportJob(jobId, body, ownerUserId)
            }
        }

        private fun JSONObject.requiredPath(key: String = "inputPath"): String =
            optString(key).trim().ifBlank { error("Upload a source file first.") }

        private fun JSONObject.requiredUri(key: String = "inputPath"): Uri =
            uriForPath(requiredPath(key))

        private fun JSONObject.pathList(key: String): List<String> {
            val array = optJSONArray(key)
            if (array != null) {
                return buildList {
                    for (index in 0 until array.length()) {
                        array.optString(index).trim().takeIf { it.isNotBlank() }?.let(::add)
                    }
                }
            }
            return optString(key)
                .lineSequence()
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .toList()
        }

        private fun chatAttachmentsFromParams(body: JSONObject): List<AiServerWebMessageAttachmentEntity> {
            val array = body.optJSONArray("attachments") ?: return emptyList()
            return buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val path = item.optString("path").trim()
                    if (path.isBlank()) continue
                    val file = File(path)
                    val mimeType = item.optString("mimeType").ifBlank {
                        if (file.isFile) mimeForFile(file) else "application/octet-stream"
                    }
                    add(
                        AiServerWebMessageAttachmentEntity(
                            messageId = 0L,
                            attachmentType = item.optString("attachmentType").ifBlank { attachmentTypeFor(path, mimeType) },
                            path = path,
                            mimeType = mimeType,
                            name = item.optString("name").ifBlank { file.name },
                            sizeBytes = item.optLong("sizeBytes", if (file.isFile) file.length() else 0L)
                        )
                    )
                }
            }
        }

        private fun attachmentTypeFor(path: String, mimeType: String): String =
            when {
                mimeType.startsWith("image/") -> "image"
                mimeType.startsWith("audio/") -> "audio"
                mimeType.startsWith("video/") -> "video"
                path.endsWith(".png", true) || path.endsWith(".jpg", true) || path.endsWith(".jpeg", true) || path.endsWith(".webp", true) -> "image"
                path.endsWith(".wav", true) || path.endsWith(".mp3", true) || path.endsWith(".m4a", true) || path.endsWith(".ogg", true) -> "audio"
                path.endsWith(".mp4", true) || path.endsWith(".webm", true) || path.endsWith(".avi", true) || path.endsWith(".mkv", true) -> "video"
                else -> "document"
            }

        private fun uriForPath(path: String): Uri =
            when {
                path.startsWith("content://", ignoreCase = true) -> Uri.parse(path)
                path.startsWith("file://", ignoreCase = true) -> Uri.parse(path)
                else -> Uri.fromFile(File(path))
            }

        private fun sourceNameFromPath(path: String): String {
            val uri = Uri.parse(path)
            return File(path).name.takeIf { it.isNotBlank() && it != path }
                ?: uri.lastPathSegment?.substringAfterLast('/')?.ifBlank { null }
                ?: "source_${timestamp()}"
        }

        private fun isImagePath(path: String, mimeType: String): Boolean =
            mimeType.startsWith("image/") || path.substringAfterLast('.', "").lowercase(Locale.US) in setOf("png", "jpg", "jpeg", "webp")

        private suspend fun insertPdfNote(title: String, text: String, sourcePath: String) {
            db.noteDao().insert(
                NoteEntity(
                    title = title.take(120),
                    content = text,
                    type = NoteType.PDF_SUMMARY,
                    sourceFile = sourcePath
                )
            )
        }

        private fun writeTextArtifact(filename: String, text: String): File {
            val outputDir = File(filesDir, "ai_server_artifacts/${type.id}").apply { mkdirs() }
            val output = File(outputDir, safeFileName(filename))
            output.writeText(text)
            return output
        }

        private fun copyUriToServerFile(uri: Uri, subfolder: String, fallbackName: String): File {
            val outputDir = File(filesDir, "ai_server_artifacts/${type.id}/$subfolder").apply { mkdirs() }
            val rawName = uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.contains('.') } ?: fallbackName
            val output = File(outputDir, "${timestamp()}_${safeFileName(rawName)}")
            val input = when (uri.scheme?.lowercase(Locale.US)) {
                null, "", "file" -> File(uri.path ?: uri.toString()).inputStream()
                else -> applicationContext.contentResolver.openInputStream(uri)
                    ?: error("Could not open generated file: $uri")
            }
            input.use { source ->
                output.outputStream().use { target -> source.copyTo(target) }
            }
            return output
        }

        private fun artifactMetadata(jobId: String, action: String): JSONObject =
            JSONObject()
                .put("origin", "SERVER")
                .put("serverType", type.id)
                .put("jobId", jobId)
                .put("action", action)

        private fun summarySnapshotFromParams(
            body: JSONObject,
            fallback: RemoteSummarySettingsSnapshot
        ): RemoteSummarySettingsSnapshot =
            fallback.copy(
                backend = SettingsRepository.normalizeOllamaOrLlamaBackend(
                    body.optString("summaryBackend", fallback.backend)
                ),
                ollamaUrl = body.optString("summaryOllamaUrl", fallback.ollamaUrl).ifBlank { fallback.ollamaUrl },
                llamaServerUrl = body.optString("summaryLlamaServerUrl", fallback.llamaServerUrl).ifBlank { fallback.llamaServerUrl },
                llamaSwapUrl = body.optString("summaryLlamaSwapUrl", fallback.llamaSwapUrl).ifBlank { fallback.llamaSwapUrl },
                ollamaModel = body.optNullableString("summaryOllamaModel") ?: fallback.ollamaModel,
                llamaSwapModel = body.optNullableString("summaryLlamaSwapModel") ?: fallback.llamaSwapModel,
                liteRtModelId = body.optLong("summaryLiteRtModelId", fallback.liteRtModelId ?: -1L)
                    .takeIf { it > 0L },
                liteRtBackend = body.optString("summaryLiteRtBackend", fallback.liteRtBackend).ifBlank { fallback.liteRtBackend },
                liteRtMtpEnabled = body.optBoolean("summaryLiteRtMtpEnabled", fallback.liteRtMtpEnabled),
                thinkingEnabled = body.optBoolean("summaryThinkingEnabled", fallback.thinkingEnabled),
                llamaServerModelLabel = body.optNullableString("summaryLlamaServerModelLabel") ?: fallback.llamaServerModelLabel,
                llamaServerContextTokens = body.optInt("summaryLlamaServerContextTokens", fallback.llamaServerContextTokens),
                llamaServerContextLabel = body.optNullableString("summaryLlamaServerContextLabel") ?: fallback.llamaServerContextLabel,
                chunkContext = body.optInt("summaryChunkContext", fallback.chunkContext),
                chunkMaxTokens = body.optInt("summaryChunkMaxTokens", fallback.chunkMaxTokens),
                mergeContext = body.optInt("summaryMergeContext", fallback.mergeContext),
                mergeMaxTokens = body.optInt("summaryMergeMaxTokens", fallback.mergeMaxTokens),
                temperature = body.optDouble("summaryTemperature", fallback.temperature.toDouble()).toFloat(),
                timeoutMinutes = body.optInt("summaryTimeoutMinutes", fallback.timeoutMinutes),
                targetLanguage = body.optString("targetLanguage", fallback.targetLanguage).ifBlank { fallback.targetLanguage },
                summaryPrompt = body.optNullableString("summaryPrompt") ?: fallback.summaryPrompt,
                mergePrompt = body.optNullableString("summaryMergePrompt") ?: fallback.mergePrompt
            )

        private fun defaultDatasetPrompt(type: PromptType, fallback: String): String = runBlocking {
            db.datasetDao().getDefaultPrompt(type)?.content ?: fallback
        }

        private fun pdfProgressFraction(progress: PdfOcrTranslationProgress): Float {
            val pageFraction = if (progress.totalPages > 0) {
                progress.processedPages.toFloat() / progress.totalPages.toFloat()
            } else {
                0f
            }
            val blockFraction = if (progress.totalBlocks > 0) {
                progress.translatedBlocks.toFloat() / progress.totalBlocks.toFloat()
            } else {
                0f
            }
            return when (progress.stage) {
                PdfOcrTranslationStage.READING_TEXT -> 0.05f + pageFraction * 0.15f
                PdfOcrTranslationStage.EXTRACTING -> 0.08f
                PdfOcrTranslationStage.PDF_CREATION -> 0.16f
                PdfOcrTranslationStage.OCR -> 0.18f + pageFraction * 0.22f
                PdfOcrTranslationStage.TRANSLATING -> 0.4f + blockFraction * 0.35f
                PdfOcrTranslationStage.CORRECTING -> 0.75f + blockFraction * 0.1f
                PdfOcrTranslationStage.WRITING -> 0.85f + pageFraction * 0.1f
                PdfOcrTranslationStage.RENDERING -> 0.95f
                PdfOcrTranslationStage.PACKING -> 0.98f
            }.coerceIn(0.05f, 0.98f)
        }

        private fun pdfProgressMessage(progress: PdfOcrTranslationProgress): String =
            when (progress.stage) {
                PdfOcrTranslationStage.READING_TEXT -> "Reading PDF text ${progress.processedPages}/${progress.totalPages}"
                PdfOcrTranslationStage.EXTRACTING -> "Extracting comic pages"
                PdfOcrTranslationStage.PDF_CREATION -> "Creating source PDF"
                PdfOcrTranslationStage.OCR -> "OCR ${progress.processedPages}/${progress.totalPages}"
                PdfOcrTranslationStage.TRANSLATING -> "Translating ${progress.translatedBlocks}/${progress.totalBlocks}"
                PdfOcrTranslationStage.CORRECTING -> "Correcting layout"
                PdfOcrTranslationStage.WRITING -> "Writing PDF ${progress.processedPages}/${progress.totalPages}"
                PdfOcrTranslationStage.RENDERING -> "Rendering translated pages"
                PdfOcrTranslationStage.PACKING -> "Packing CBZ"
            }

        private fun watchSdJob(jobId: String, holder: SDModeStateHolder, outputFile: File) {
            serviceScope.launch {
                while (isActive) {
                    if (isJobCancelled(jobId)) return@launch
                    when (val state = holder.state.value) {
                        is SDGenerationState.Generating -> updateJob(jobId, type.id, "RUNNING", holder.progress.value, holder.status.value.ifBlank { "Generating" }, outputFile.absolutePath)
                        is SDGenerationState.Complete -> {
                            updateJob(jobId, type.id, "COMPLETED", 1f, "Complete", state.outputPath)
                            return@launch
                        }
                        is SDGenerationState.Error -> {
                            updateJob(jobId, type.id, "FAILED", 0f, state.message, outputFile.absolutePath)
                            return@launch
                        }
                        SDGenerationState.Idle -> Unit
                    }
                    delay(900)
                }
            }
        }

        private fun watchSdWorkflowJob(jobId: String, outputFile: File) {
            serviceScope.launch {
                while (isActive) {
                    if (isJobCancelled(jobId)) return@launch
                    val txtState = SDModeStateHolder.workflowTxt2img.state.value
                    val upscaleState = SDModeStateHolder.workflowUpscale.state.value
                    when {
                        upscaleState is SDGenerationState.Complete -> {
                            updateJob(jobId, type.id, "COMPLETED", 1f, "Workflow complete", upscaleState.outputPath)
                            return@launch
                        }
                        txtState is SDGenerationState.Error -> {
                            updateJob(jobId, type.id, "FAILED", 0f, txtState.message, outputFile.absolutePath)
                            return@launch
                        }
                        upscaleState is SDGenerationState.Error -> {
                            updateJob(jobId, type.id, "FAILED", 0f, upscaleState.message, outputFile.absolutePath)
                            return@launch
                        }
                        upscaleState is SDGenerationState.Generating -> updateJob(jobId, type.id, "RUNNING", 0.5f + SDModeStateHolder.workflowUpscale.progress.value * 0.5f, SDModeStateHolder.workflowUpscale.status.value.ifBlank { "Upscaling" }, outputFile.absolutePath)
                        txtState is SDGenerationState.Generating -> updateJob(jobId, type.id, "RUNNING", SDModeStateHolder.workflowTxt2img.progress.value * 0.5f, SDModeStateHolder.workflowTxt2img.status.value.ifBlank { "Generating" }, outputFile.absolutePath)
                    }
                    delay(900)
                }
            }
        }

        private fun watchOnnxImageJob(jobId: String, outputFile: File) {
            serviceScope.launch {
                val holder = OnnxImageGenerationStateStore.imageGen
                while (isActive) {
                    if (isJobCancelled(jobId)) return@launch
                    when (val state = holder.state.value) {
                        is OnnxImageGenerationState.Preparing -> updateJob(jobId, type.id, "RUNNING", holder.progress.value, state.status, outputFile.absolutePath)
                        is OnnxImageGenerationState.Generating -> updateJob(jobId, type.id, "RUNNING", state.progress, state.status, outputFile.absolutePath)
                        is OnnxImageGenerationState.Complete -> {
                            updateJob(jobId, type.id, "COMPLETED", 1f, state.warningMessage ?: "Complete", state.outputPath)
                            return@launch
                        }
                        is OnnxImageGenerationState.Error -> {
                            updateJob(jobId, type.id, "FAILED", 0f, state.message, outputFile.absolutePath)
                            return@launch
                        }
                        OnnxImageGenerationState.Idle -> Unit
                    }
                    delay(900)
                }
            }
        }

        private fun watchBackgroundRemovalJob(jobId: String, ownerUserId: Long?, title: String, metadata: JSONObject) {
            serviceScope.launch {
                while (isActive) {
                    if (isJobCancelled(jobId)) return@launch
                    when (val state = OnnxBackgroundRemovalStateStore.state.value) {
                        is OnnxBackgroundRemovalState.Running -> updateJob(jobId, type.id, "RUNNING", state.progress, state.status, null)
                        is OnnxBackgroundRemovalState.Complete -> {
                            val firstOutput = state.outputPaths.firstOrNull()?.let(::File)
                            if (firstOutput != null) {
                                recordArtifact(type.id, ownerUserId, jobId, IMAGE, firstOutput, "image/png", title, metadata)
                            }
                            updateJob(jobId, type.id, "COMPLETED", 1f, "Background removal complete", firstOutput?.absolutePath)
                            return@launch
                        }
                        is OnnxBackgroundRemovalState.Error -> {
                            updateJob(jobId, type.id, "FAILED", 0f, state.message, null)
                            return@launch
                        }
                        OnnxBackgroundRemovalState.Idle -> Unit
                    }
                    delay(900)
                }
            }
        }

        private fun watchVideoJob(jobId: String, holder: VideoGenerationStateHolder, outputFile: File) {
            serviceScope.launch {
                while (isActive) {
                    if (isJobCancelled(jobId)) return@launch
                    when (val state = holder.state.value) {
                        is VideoGenerationState.Generating -> updateJob(jobId, type.id, "RUNNING", state.progress, state.status, outputFile.absolutePath)
                        is VideoGenerationState.Converting -> updateJob(jobId, type.id, "RUNNING", state.progress, state.status, outputFile.absolutePath)
                        is VideoGenerationState.Copying -> updateJob(jobId, type.id, "RUNNING", state.progress, state.status, outputFile.absolutePath)
                        is VideoGenerationState.Complete -> {
                            updateJob(jobId, type.id, "COMPLETED", 1f, state.warningMessage ?: "Complete", state.metadata.mp4Path)
                            return@launch
                        }
                        is VideoGenerationState.Error -> {
                            updateJob(jobId, type.id, "FAILED", 0f, state.message, outputFile.absolutePath)
                            return@launch
                        }
                        VideoGenerationState.Idle -> Unit
                    }
                    delay(900)
                }
            }
        }

        private fun watchTtsJob(jobId: String, ownerUserId: Long?, title: String) {
            serviceScope.launch {
                while (isActive) {
                    if (isJobCancelled(jobId)) return@launch
                    when (val state = OnnxTtsGenerationStateStore.state.value) {
                        is OnnxTtsGenerationState.Running -> updateJob(jobId, type.id, "RUNNING", state.progress, state.status, null)
                        is OnnxTtsGenerationState.Complete -> {
                            val file = File(state.audioPath)
                            val metadata = JSONObject()
                                .put("origin", "SERVER")
                                .put("serverType", type.id)
                                .put("ownerUserId", ownerUserId)
                                .put("jobId", jobId)
                                .put("durationSeconds", state.durationSeconds.toDouble())
                            recordArtifact(type.id, ownerUserId, jobId, AUDIO, file, mimeForFile(file), title, metadata)
                            updateJob(jobId, type.id, "COMPLETED", 1f, "Voice generation complete", state.audioPath)
                            return@launch
                        }
                        is OnnxTtsGenerationState.Error -> {
                            updateJob(jobId, type.id, "FAILED", 0f, state.message, null)
                            return@launch
                        }
                        OnnxTtsGenerationState.Idle -> Unit
                    }
                    delay(900)
                }
            }
        }

        private fun watchFileJob(jobId: String, serverType: String, outputFile: File, completeMessage: String) {
            serviceScope.launch {
                var ticks = 0
                while (isActive && ticks < 3600) {
                    if (isJobCancelled(jobId)) return@launch
                    if (outputFile.isFile && outputFile.length() > 0L) {
                        updateJob(jobId, serverType, "COMPLETED", 1f, completeMessage, outputFile.absolutePath)
                        return@launch
                    }
                    val progress = (0.08f + ticks / 900f).coerceAtMost(0.92f)
                    updateJob(jobId, serverType, "RUNNING", progress, "Working", outputFile.absolutePath)
                    ticks++
                    delay(1000)
                }
            }
        }

        private fun watchVideoUpscaleJob(jobId: String, outputFile: File) {
            serviceScope.launch {
                while (isActive) {
                    if (isJobCancelled(jobId)) return@launch
                    val error = VideoUpscalerStateHolder.error.value
                    if (!error.isNullOrBlank()) {
                        updateJob(jobId, type.id, "FAILED", 0f, error, outputFile.absolutePath)
                        return@launch
                    }

                    val resultPath = VideoUpscalerStateHolder.resultPath.value
                    if (!VideoUpscalerStateHolder.isProcessing.value && !resultPath.isNullOrBlank()) {
                        updateJob(jobId, type.id, "COMPLETED", 1f, "Video upscale complete", resultPath)
                        return@launch
                    }

                    if (outputFile.isFile && outputFile.length() > 0L && !VideoUpscalerStateHolder.isProcessing.value) {
                        updateJob(jobId, type.id, "COMPLETED", 1f, "Video upscale complete", outputFile.absolutePath)
                        return@launch
                    }

                    val status = VideoUpscalerStateHolder.status.value.ifBlank {
                        val current = VideoUpscalerStateHolder.currentFrame.value
                        val total = VideoUpscalerStateHolder.totalFrames.value
                        if (total > 0) "Upscaling $current/$total frames" else "Preparing video upscale"
                    }
                    updateJob(
                        jobId = jobId,
                        serverType = type.id,
                        status = "RUNNING",
                        progress = VideoUpscalerStateHolder.progress.value.coerceIn(0.05f, 0.98f),
                        message = status,
                        artifactPath = resultPath ?: outputFile.absolutePath
                    )
                    delay(900)
                }
            }
        }

        private fun watchTranscribeWorkflowJob(jobId: String) {
            serviceScope.launch {
                while (isActive) {
                    if (isJobCancelled(jobId)) return@launch
                    WorkflowStateHolder.error.value?.let {
                        updateJob(jobId, type.id, "FAILED", 0f, it, null)
                        return@launch
                    }
                    if (!WorkflowStateHolder.isRunning.value && WorkflowStateHolder.summaryText.value.isNotBlank()) {
                        updateJob(jobId, type.id, "COMPLETED", 1f, "Workflow complete", null)
                        return@launch
                    }
                    updateJob(jobId, type.id, "RUNNING", WorkflowStateHolder.progress.value, WorkflowStateHolder.step.value.ifBlank { "Working" }, null)
                    delay(900)
                }
            }
        }

        private fun watchMediaWorkflowJob(jobId: String, ownerUserId: Long?) {
            serviceScope.launch {
                while (isActive) {
                    if (isJobCancelled(jobId)) return@launch
                    val state = MediaTranslationWorkflowStateHolder.state.value
                    state.errorMessage?.let {
                        updateJob(jobId, type.id, "FAILED", 0f, it, state.finalOutputPath)
                        return@launch
                    }
                    if (!state.isRunning && !state.finalOutputPath.isNullOrBlank()) {
                        val file = File(state.finalOutputPath)
                        val artifactType = if (file.extension.equals("srt", ignoreCase = true)) DOCUMENT else VIDEO
                        recordArtifact(type.id, ownerUserId, jobId, artifactType, file, mimeForFile(file), file.name, JSONObject().put("origin", "SERVER").put("serverType", type.id).put("jobId", jobId))
                        updateJob(jobId, type.id, "COMPLETED", 1f, "Workflow complete", state.finalOutputPath)
                        return@launch
                    }
                    updateJob(jobId, type.id, "RUNNING", state.progress, state.status.ifBlank { "Working" }, state.finalOutputPath)
                    delay(900)
                }
            }
        }

        private fun watchPdfSummaryJob(jobId: String) {
            serviceScope.launch {
                while (isActive) {
                    if (isJobCancelled(jobId)) return@launch
                    val result = PDFSummaryService.result.value
                    if (result != null) {
                        result.fold(
                            onSuccess = { updateJob(jobId, type.id, "COMPLETED", 1f, "PDF summary complete", null) },
                            onFailure = { updateJob(jobId, type.id, "FAILED", 0f, it.message ?: "PDF summary failed", null) }
                        )
                        return@launch
                    }
                    val total = PDFSummaryService.totalChunks.value
                    val current = PDFSummaryService.currentChunk.value
                    val progress = if (total > 0) (current.toFloat() / total.toFloat()).coerceIn(0.05f, 0.95f) else 0.12f
                    updateJob(jobId, type.id, "RUNNING", progress, PDFSummaryService.currentPhase.value.ifBlank { "Summarizing PDF" }, null)
                    delay(1000)
                }
            }
        }

        private fun watchVideoSummaryJob(jobId: String) {
            serviceScope.launch {
                while (isActive) {
                    if (isJobCancelled(jobId)) return@launch
                    val error = VideoSummaryStateHolder.error.value
                    if (!error.isNullOrBlank()) {
                        updateJob(jobId, type.id, "FAILED", 0f, error, null)
                        return@launch
                    }
                    if (!VideoSummaryStateHolder.isRunning.value && VideoSummaryStateHolder.summary.value.isNotBlank()) {
                        updateJob(jobId, type.id, "COMPLETED", 1f, "Video summary complete", null)
                        return@launch
                    }
                    updateJob(jobId, type.id, "RUNNING", VideoSummaryStateHolder.progressFraction.value, VideoSummaryStateHolder.progress.value.ifBlank { "Summarizing video" }, null)
                    delay(1000)
                }
            }
        }

        private fun watchDatasetJob(jobId: String, projectId: Long, completeMessage: String) {
            serviceScope.launch {
                var sawProjectWork = false
                var idleTicks = 0
                while (isActive) {
                    if (isJobCancelled(jobId)) return@launch
                    val progress = DatasetProcessor.progress.value
                    val activeJob = DatasetProcessor.activeJob.value
                    val queuedForProject = DatasetProcessor.jobQueue.value.any { it.projectId == projectId }
                    val activeForProject = activeJob?.projectId == projectId

                    if (progress?.projectId == projectId) {
                        sawProjectWork = true
                        idleTicks = 0
                        val fraction = if (progress.total > 0) {
                            progress.current.toFloat() / progress.total.toFloat()
                        } else {
                            0.12f
                        }
                        updateJob(
                            jobId,
                            type.id,
                            "RUNNING",
                            fraction.coerceIn(0.05f, 0.95f),
                            "${progress.stage}: ${progress.current}/${progress.total} ${progress.currentItem}".trim(),
                            null
                        )
                    } else if (activeForProject || queuedForProject) {
                        sawProjectWork = true
                        idleTicks = 0
                        updateJob(jobId, type.id, "RUNNING", 0.08f, activeJob?.name ?: "Dataset job queued", null)
                    } else {
                        idleTicks += 1
                    }

                    if (sawProjectWork && idleTicks >= 3 && !DatasetProcessor.isProcessing.value && !queuedForProject && !activeForProject) {
                        val runtimeJob = AiRuntimeJobStore.getByJobKey(applicationContext, DATASET_RUNTIME_JOB_KEY)
                        when (runtimeJob?.status) {
                            AiRuntimeJobStore.STATUS_FAILED -> updateJob(
                                jobId,
                                type.id,
                                "FAILED",
                                0f,
                                runtimeJob.errorMessage ?: "Dataset job failed",
                                null
                            )
                            AiRuntimeJobStore.STATUS_CANCELLED -> updateJob(jobId, type.id, "FAILED", 0f, "Dataset job cancelled", null)
                            else -> updateJob(jobId, type.id, "COMPLETED", 1f, completeMessage, null)
                        }
                        return@launch
                    }
                    delay(1000)
                }
            }
        }

        private fun watchLlamaJob(jobId: String, chatId: Long) {
            serviceScope.launch {
                while (isActive) {
                    if (isJobCancelled(jobId)) return@launch
                    when (val state = LlamaClientService.generationState.value) {
                        is LlamaClientService.GenerationState.Generating -> if (state.chatId == chatId) {
                            val progress = if (state.tokenCount > 0) 0.35f else 0.15f
                            updateJob(jobId, type.id, "RUNNING", progress, state.statusText ?: "Generating reply", null)
                        }
                        is LlamaClientService.GenerationState.Completed -> if (state.chatId == chatId) {
                            updateJob(jobId, type.id, "COMPLETED", 1f, "Reply complete", null)
                            return@launch
                        }
                        is LlamaClientService.GenerationState.Error -> if (state.chatId == chatId) {
                            updateJob(jobId, type.id, "FAILED", 0f, state.message, null)
                            return@launch
                        }
                        LlamaClientService.GenerationState.Idle -> Unit
                    }
                    delay(900)
                }
            }
        }

        private fun updateJob(jobId: String, serverType: String, status: String, progress: Float, message: String, artifactPath: String?) {
            if (status != "CANCELLED" && AiServerJobStore.getJob(serverType, jobId)?.status == "CANCELLED") {
                return
            }
            AiServerJobStore.update(serverType, jobId) {
                it.copy(
                    status = status,
                    progress = progress.coerceIn(0f, 1f),
                    message = message,
                    artifactPath = artifactPath ?: it.artifactPath,
                    errorMessage = if (status == "FAILED") message else it.errorMessage,
                    updatedAt = System.currentTimeMillis()
                )
            }
        }

        private fun mimeForFile(file: File): String = when (file.extension.lowercase(Locale.US)) {
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "mp4" -> "video/mp4"
            "avi" -> "video/avi"
            "wav" -> "audio/wav"
            "mp3" -> "audio/mpeg"
            "srt" -> "text/plain"
            "txt", "md", "vtt" -> "text/plain"
            "json" -> "application/json"
            "jsonl" -> "application/x-ndjson"
            "pdf" -> "application/pdf"
            "cbz" -> "application/vnd.comicbook+zip"
            else -> "application/octet-stream"
        }

        private fun webOwnerUserId(user: AiServerUserEntity?): Long? =
            if (config.accessMode == AiServerAccessMode.USERS) user?.id else null

        private fun chatJson(session: IHTTPSession, user: AiServerUserEntity?): JSONObject = runBlocking {
            val ownerUserId = webOwnerUserId(user)
            val provider = ensureDefaultWebProvider(ownerUserId)
            var chats = db.aiServerDao().getWebChatsForOwner(ownerUserId)
            if (chats.isEmpty()) {
                val id = db.aiServerDao().upsertWebChat(
                    AiServerWebChatEntity(
                        ownerUserId = ownerUserId,
                        title = "Web chat",
                        providerId = provider.id
                    )
                )
                chats = listOf(AiServerWebChatEntity(id = id, ownerUserId = ownerUserId, title = "Web chat", providerId = provider.id))
            }
            val requestedChatId = queryParam(session, "chatId").toLongOrNull()
            val activeChat = chats.firstOrNull { it.id == requestedChatId } ?: chats.first()
            val messages = db.aiServerDao().getWebMessages(activeChat.id)
            val attachments = if (messages.isEmpty()) {
                emptyMap()
            } else {
                db.aiServerDao()
                    .getWebMessageAttachments(messages.map { it.id })
                    .groupBy { it.messageId }
            }
            val toolEvents = if (messages.isEmpty()) {
                emptyMap()
            } else {
                db.aiServerDao()
                    .getWebToolEvents(messages.map { it.id })
                    .groupBy { it.messageId }
            }
            JSONObject()
                .put("ok", true)
                .put("providers", JSONArray(db.aiServerDao().getWebProvidersForOwner(ownerUserId).map(::webProviderToJson)))
                .put("chats", JSONArray(chats.map(::webChatToJson)))
                .put("activeChatId", activeChat.id)
                .put("activeProviderId", activeChat.providerId ?: provider.id)
                .put("messages", JSONArray(messages.map {
                    webMessageToJson(it, attachments[it.id].orEmpty(), toolEvents[it.id].orEmpty())
                }))
        }

        private fun createChat(session: IHTTPSession, user: AiServerUserEntity?): Response = runBlocking {
            val body = readJsonBody(session)
            val ownerUserId = webOwnerUserId(user)
            val provider = ensureDefaultWebProvider(ownerUserId)
            val title = body.optString("title").ifBlank { "Web chat ${timestamp()}" }.take(80)
            val providerId = body.optLong("providerId", provider.id).takeIf { it > 0L } ?: provider.id
            val ownedProvider = resolveWebProvider(providerId, ownerUserId)
            val id = db.aiServerDao().upsertWebChat(
                AiServerWebChatEntity(
                    ownerUserId = ownerUserId,
                    title = title,
                    providerId = ownedProvider.id,
                    systemPrompt = body.optString("systemPrompt").ifBlank { null },
                    apiParamsJson = body.optJSONObject("params")?.toString()
                )
            )
            jsonResponse(JSONObject().put("ok", true).put("chat", webChatToJson(AiServerWebChatEntity(id = id, ownerUserId = ownerUserId, title = title, providerId = ownedProvider.id))))
        }

        private fun renameChat(session: IHTTPSession, user: AiServerUserEntity?): Response = runBlocking {
            val body = readJsonBody(session)
            val ownerUserId = webOwnerUserId(user)
            val id = body.optLong("chatId", -1L)
            val title = body.optString("title").trim().take(80)
            val chat = db.aiServerDao().getWebChatForOwner(id, ownerUserId)
                ?: return@runBlocking jsonResponse(JSONObject().put("ok", false).put("error", "Chat not found"), Response.Status.NOT_FOUND)
            require(title.isNotBlank()) { "Enter a chat name." }
            db.aiServerDao().updateWebChat(chat.copy(title = title, updatedAt = System.currentTimeMillis()))
            jsonResponse(JSONObject().put("ok", true))
        }

        private fun deleteChat(session: IHTTPSession, user: AiServerUserEntity?): Response = runBlocking {
            val body = readJsonBody(session)
            val ownerUserId = webOwnerUserId(user)
            val id = body.optLong("chatId", -1L)
            db.aiServerDao().getWebChatForOwner(id, ownerUserId)
                ?: return@runBlocking jsonResponse(JSONObject().put("ok", false).put("error", "Chat not found"), Response.Status.NOT_FOUND)
            db.aiServerDao().deleteWebChat(id)
            jsonResponse(JSONObject().put("ok", true))
        }

        private fun continueChat(session: IHTTPSession, user: AiServerUserEntity?): Response = runBlocking {
            val body = readJsonBody(session)
            val params = mergedChatParams(body)
            normalizeChatParamsForOwner(params, user)
            val ownerUserId = webOwnerUserId(user)
            val chatId = params.optLong("chatId", -1L)
            val chat = db.aiServerDao().getWebChatForOwner(chatId, ownerUserId)
                ?: return@runBlocking jsonResponse(JSONObject().put("ok", false).put("error", "Chat not found"), Response.Status.NOT_FOUND)
            if (params.optLong("providerId", -1L) <= 0L) {
                params.put("providerId", chat.providerId ?: ensureDefaultWebProvider(ownerUserId).id)
            }
            params.put("chatId", chat.id)
            queueChatAction("web_chat_continue", params, user, "Continue chat")
        }

        private fun editChatMessage(session: IHTTPSession, user: AiServerUserEntity?): Response = runBlocking {
            val body = readJsonBody(session)
            val ownerUserId = webOwnerUserId(user)
            val messageId = body.optLong("messageId", -1L)
            val content = body.optString("content").trim()
            val message = db.aiServerDao().getWebMessage(messageId)
                ?: return@runBlocking jsonResponse(JSONObject().put("ok", false).put("error", "Message not found"), Response.Status.NOT_FOUND)
            db.aiServerDao().getWebChatForOwner(message.chatId, ownerUserId)
                ?: return@runBlocking jsonResponse(JSONObject().put("ok", false).put("error", "Message not found"), Response.Status.NOT_FOUND)
            require(content.isNotBlank()) { "Message cannot be empty." }
            db.aiServerDao().updateWebMessageContent(
                id = message.id,
                content = content,
                thinking = message.thinking,
                toolActivity = message.toolActivity,
                isError = false
            )
            db.aiServerDao().getWebChat(message.chatId)?.let { chat ->
                db.aiServerDao().updateWebChat(chat.copy(updatedAt = System.currentTimeMillis()))
            }
            jsonResponse(JSONObject().put("ok", true))
        }

        private fun deleteChatMessage(session: IHTTPSession, user: AiServerUserEntity?): Response = runBlocking {
            val body = readJsonBody(session)
            val ownerUserId = webOwnerUserId(user)
            val messageId = body.optLong("messageId", -1L)
            val message = db.aiServerDao().getWebMessage(messageId)
                ?: return@runBlocking jsonResponse(JSONObject().put("ok", false).put("error", "Message not found"), Response.Status.NOT_FOUND)
            db.aiServerDao().getWebChatForOwner(message.chatId, ownerUserId)
                ?: return@runBlocking jsonResponse(JSONObject().put("ok", false).put("error", "Message not found"), Response.Status.NOT_FOUND)
            val attachments = db.aiServerDao().getWebMessageAttachments(listOf(message.id))
            db.aiServerDao().deleteWebMessage(message.id)
            attachments.forEach { attachment ->
                if (db.aiServerDao().countWebMessageAttachmentsByPath(attachment.path) == 0) {
                    deleteServerOwnedArtifactFiles(File(attachment.path))
                }
            }
            db.aiServerDao().getWebChat(message.chatId)?.let { chat ->
                db.aiServerDao().updateWebChat(chat.copy(updatedAt = System.currentTimeMillis()))
            }
            jsonResponse(JSONObject().put("ok", true))
        }

        private fun regenerateChatMessage(session: IHTTPSession, user: AiServerUserEntity?): Response = runBlocking {
            val body = readJsonBody(session)
            val params = mergedChatParams(body)
            normalizeChatParamsForOwner(params, user)
            val ownerUserId = webOwnerUserId(user)
            val messageId = params.optLong("messageId", -1L)
            val message = db.aiServerDao().getWebMessage(messageId)
                ?: return@runBlocking jsonResponse(JSONObject().put("ok", false).put("error", "Message not found"), Response.Status.NOT_FOUND)
            require(message.role == "assistant") { "Only assistant messages can be regenerated." }
            val chat = db.aiServerDao().getWebChatForOwner(message.chatId, ownerUserId)
                ?: return@runBlocking jsonResponse(JSONObject().put("ok", false).put("error", "Chat not found"), Response.Status.NOT_FOUND)
            db.aiServerDao().deleteWebMessagesFrom(message.chatId, message.createdAt, message.id)
            if (params.optLong("providerId", -1L) <= 0L) {
                params.put("providerId", chat.providerId ?: ensureDefaultWebProvider(ownerUserId).id)
            }
            params.put("chatId", chat.id)
            queueChatAction("web_chat_regenerate", params, user, "Regenerate reply")
        }

        private fun clearChatToolEvents(session: IHTTPSession, user: AiServerUserEntity?): Response = runBlocking {
            val body = readJsonBody(session)
            val ownerUserId = webOwnerUserId(user)
            val chatId = body.optLong("chatId", -1L)
            db.aiServerDao().getWebChatForOwner(chatId, ownerUserId)
                ?: return@runBlocking jsonResponse(JSONObject().put("ok", false).put("error", "Chat not found"), Response.Status.NOT_FOUND)
            val removed = db.aiServerDao().clearWebToolEventsForChat(chatId)
            jsonResponse(JSONObject().put("ok", true).put("removed", removed))
        }

        private fun mergedChatParams(body: JSONObject): JSONObject {
            val params = JSONObject()
            body.optJSONObject("params")?.let { incoming ->
                incoming.keys().forEach { key -> params.put(key, incoming.opt(key)) }
            }
            body.keys().forEach { key ->
                if (key != "params") params.put(key, body.opt(key))
            }
            return params
        }

        private fun queueChatAction(
            action: String,
            params: JSONObject,
            user: AiServerUserEntity?,
            title: String
        ): Response {
            runCatching {
                normalizeChatParamsForOwner(params, user)
                validateActionForServer(action, params)
            }.onFailure { error ->
                return jsonResponse(
                    JSONObject().put("ok", false).put("error", error.message ?: "Invalid chat action"),
                    Response.Status.BAD_REQUEST
                )
            }
            val jobId = UUID.randomUUID().toString()
            val ownerUserId = user?.id
            val acceptedJob = AiServerJob(
                id = jobId,
                serverType = type.id,
                title = title,
                status = "QUEUED",
                message = "Queued from web chat",
                ownerUserId = ownerUserId,
                action = action,
                paramsJson = params.toString()
            )
            AiServerJobStore.add(acceptedJob)
            synchronized(queueLock) {
                jobActions[jobId] = action
                queuedJobs += QueuedServerJob(jobId, action, JSONObject(params.toString()), ownerUserId)
            }
            ensureQueueRunner()
            return jsonResponse(JSONObject().put("ok", true).put("job", jobToJson(acceptedJob)))
        }

        private fun upsertChatProvider(session: IHTTPSession, user: AiServerUserEntity?): Response = runBlocking {
            val body = readJsonBody(session)
            val now = System.currentTimeMillis()
            val ownerUserId = webOwnerUserId(user)
            val id = body.optLong("id", 0L)
            val existing = if (id > 0L) db.aiServerDao().getWebProviderForOwner(id, ownerUserId) else null
            if (id > 0L && existing == null) {
                return@runBlocking jsonResponse(JSONObject().put("ok", false).put("error", "Provider not found"), Response.Status.NOT_FOUND)
            }
            val engine = SettingsRepository.normalizeOllamaOrLlamaBackend(
                body.optString("engine", existing?.engine ?: SettingsRepository.PDF_BACKEND_OLLAMA)
            )
            val params = providerParamsFromBody(body, existing)
            val entity = AiServerWebProviderEntity(
                id = existing?.id ?: 0L,
                ownerUserId = ownerUserId,
                name = body.optString("name").ifBlank { "Web provider" },
                engine = engine,
                baseUrl = providerBaseUrl(engine, body.optString("baseUrl", existing?.baseUrl ?: "")),
                modelName = body.optString("modelName").ifBlank {
                    if (engine == SettingsRepository.PDF_BACKEND_LITERT) null else existing?.modelName
                },
                liteRtModelId = if (engine == SettingsRepository.PDF_BACKEND_LITERT) {
                    body.optFlexibleLong("liteRtModelId")
                        ?: existing?.liteRtModelId
                } else {
                    null
                },
                liteRtBackend = if (engine == SettingsRepository.PDF_BACKEND_LITERT) {
                    body.optString("liteRtBackend", existing?.liteRtBackend ?: LITERT_BACKEND_AUTO)
                        .ifBlank { existing?.liteRtBackend ?: LITERT_BACKEND_AUTO }
                } else {
                    LITERT_BACKEND_AUTO
                },
                supportsVision = body.optBoolean("supportsVision", existing?.supportsVision ?: false),
                supportsAudio = body.optBoolean("supportsAudio", existing?.supportsAudio ?: false),
                defaultParamsJson = params.takeIf { it.length() > 0 }?.toString(),
                createdAt = existing?.createdAt ?: now,
                updatedAt = now
            )
            val providerId = db.aiServerDao().upsertWebProvider(entity)
            jsonResponse(JSONObject().put("ok", true).put("provider", webProviderToJson(entity.copy(id = providerId))))
        }

        private fun chatProviderModels(session: IHTTPSession): Response = runBlocking {
            val engine = SettingsRepository.normalizeOllamaOrLlamaBackend(queryParam(session, "engine"))
            val baseUrl = providerBaseUrl(engine, queryParam(session, "baseUrl"))
            val models = runCatching {
                when (engine) {
                    SettingsRepository.PDF_BACKEND_OLLAMA -> fetchOllamaModelNames(baseUrl)
                    SettingsRepository.PDF_BACKEND_LLAMA_SWAP,
                    SettingsRepository.PDF_BACKEND_LLAMA_SERVER -> fetchOpenAiModelIds(baseUrl)
                    SettingsRepository.PDF_BACKEND_LITERT -> db.liteRtModelDao().observeAll().first()
                        .map { it.displayName.ifBlank { it.filename } }
                    else -> emptyList()
                }
            }.getOrElse { error ->
                return@runBlocking jsonResponse(
                    JSONObject()
                        .put("ok", true)
                        .put("engine", engine)
                        .put("baseUrl", baseUrl)
                        .put("models", JSONArray())
                        .put("warning", error.message ?: "Could not refresh models")
                )
            }
            jsonResponse(
                JSONObject()
                    .put("ok", true)
                    .put("engine", engine)
                    .put("baseUrl", baseUrl)
                    .put("models", JSONArray(models))
            )
        }

        private fun deleteChatProvider(session: IHTTPSession, user: AiServerUserEntity?): Response = runBlocking {
            val body = readJsonBody(session)
            val ownerUserId = webOwnerUserId(user)
            val id = body.optLong("id", -1L)
            db.aiServerDao().getWebProviderForOwner(id, ownerUserId)
                ?: return@runBlocking jsonResponse(JSONObject().put("ok", false).put("error", "Provider not found"), Response.Status.NOT_FOUND)
            db.aiServerDao().deleteWebProvider(id)
            jsonResponse(JSONObject().put("ok", true))
        }

        private fun providerParamsFromBody(body: JSONObject, existing: AiServerWebProviderEntity?): JSONObject {
            val params = existing?.defaultParamsJson
                ?.let { runCatching { JSONObject(it) }.getOrNull() }
                ?: JSONObject()
            body.optJSONObject("params")?.let { incoming ->
                incoming.keys().forEach { key ->
                    params.put(key, incoming.opt(key))
                }
            }
            if (body.has("liteRtMtpEnabled")) {
                params.put("liteRtMtpEnabled", body.optBoolean("liteRtMtpEnabled", false))
            }
            return params
        }

        private fun providerBaseUrl(engine: String, rawBaseUrl: String): String {
            if (engine == SettingsRepository.PDF_BACKEND_LITERT) return ""
            val fallback = when (engine) {
                SettingsRepository.PDF_BACKEND_OLLAMA -> com.example.llamadroid.util.AIConstants.Urls.OLLAMA_DEFAULT
                SettingsRepository.PDF_BACKEND_LLAMA_SWAP -> SettingsRepository.PDF_LLAMA_SWAP_DEFAULT_URL
                SettingsRepository.PDF_BACKEND_LLAMA_SERVER -> SettingsRepository.PDF_LLAMA_SERVER_DEFAULT_URL
                else -> com.example.llamadroid.util.AIConstants.Urls.OLLAMA_DEFAULT
            }
            return normalizeHttpBaseUrl(rawBaseUrl.ifBlank { fallback })
        }

        private fun normalizeHttpBaseUrl(rawBaseUrl: String): String {
            val trimmed = rawBaseUrl.trim().ifBlank { return "" }
            val withScheme = if (trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true)) {
                trimmed
            } else {
                "http://$trimmed"
            }
            return withScheme.removeSuffix("/")
        }

        private fun fetchOllamaModelNames(baseUrl: String): List<String> {
            val payload = fetchJsonObject("${normalizeHttpBaseUrl(baseUrl)}/api/tags")
            val models = payload.optJSONArray("models") ?: JSONArray()
            val names = mutableListOf<String>()
            for (index in 0 until models.length()) {
                val name = models.optJSONObject(index)?.optString("name").orEmpty().trim()
                if (name.isNotBlank()) names += name
            }
            return names.distinct()
        }

        private fun fetchOpenAiModelIds(baseUrl: String): List<String> {
            val payload = fetchJsonObject("${normalizeHttpBaseUrl(baseUrl)}/v1/models")
            val data = payload.optJSONArray("data") ?: JSONArray()
            val names = mutableListOf<String>()
            for (index in 0 until data.length()) {
                val id = data.optJSONObject(index)?.optString("id").orEmpty().trim()
                if (id.isNotBlank()) names += id
            }
            return names.distinct()
        }

        private fun fetchJsonObject(url: String): JSONObject {
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 8_000
                readTimeout = 8_000
                setRequestProperty("Accept", "application/json")
            }
            return try {
                val code = connection.responseCode
                val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                if (code !in 200..299) {
                    error("HTTP $code ${if (body.isBlank()) "model refresh failed" else body.take(180)}")
                }
                if (body.isBlank()) JSONObject() else JSONObject(body)
            } finally {
                connection.disconnect()
            }
        }

        private suspend fun ensureDefaultWebProvider(ownerUserId: Long?): AiServerWebProviderEntity {
            db.aiServerDao().getWebProvidersForOwner(ownerUserId).firstOrNull()?.let { return it }
            val now = System.currentTimeMillis()
            val entity = AiServerWebProviderEntity(
                ownerUserId = ownerUserId,
                name = "Ollama localhost",
                engine = SettingsRepository.PDF_BACKEND_OLLAMA,
                baseUrl = com.example.llamadroid.util.AIConstants.Urls.OLLAMA_DEFAULT,
                createdAt = now,
                updatedAt = now
            )
            val id = db.aiServerDao().upsertWebProvider(entity)
            return entity.copy(id = id)
        }

        private suspend fun resolveWebProvider(requestedId: Long, ownerUserId: Long?): AiServerWebProviderEntity {
            requestedId.takeIf { it > 0L }?.let { id ->
                return db.aiServerDao().getWebProviderForOwner(id, ownerUserId)
                    ?: error("Provider not found")
            }
            return ensureDefaultWebProvider(ownerUserId)
        }

        private suspend fun resolveWebChat(
            requestedId: Long,
            providerId: Long,
            requestedTitle: String,
            ownerUserId: Long?
        ): AiServerWebChatEntity {
            requestedId.takeIf { it > 0L }?.let { id ->
                return db.aiServerDao().getWebChatForOwner(id, ownerUserId)
                    ?: error("Chat not found")
            }
            val title = requestedTitle.ifBlank { "Web chat ${timestamp()}" }.take(80)
            val chat = AiServerWebChatEntity(ownerUserId = ownerUserId, title = title, providerId = providerId)
            val id = db.aiServerDao().upsertWebChat(chat)
            return chat.copy(id = id)
        }

        private fun normalizeChatParamsForOwner(params: JSONObject, user: AiServerUserEntity?) {
            if (type != AiServerType.LLAMA_CHAT) return
            runBlocking {
                val ownerUserId = webOwnerUserId(user)
                val requestedChatId = params.optLong("chatId", -1L)
                val chat = requestedChatId.takeIf { it > 0L }
                    ?.let { db.aiServerDao().getWebChatForOwner(it, ownerUserId) ?: error("Chat not found") }
                val requestedProviderId = params.optLong("providerId", -1L)
                val provider = when {
                    requestedProviderId > 0L -> resolveWebProvider(requestedProviderId, ownerUserId)
                    chat?.providerId != null -> resolveWebProvider(chat.providerId, ownerUserId)
                    else -> ensureDefaultWebProvider(ownerUserId)
                }
                chat?.let { params.put("chatId", it.id) }
                params.put("providerId", provider.id)
            }
        }

        private suspend fun generateWebChatReply(
            jobId: String,
            chat: AiServerWebChatEntity,
            provider: AiServerWebProviderEntity,
            messages: List<AiServerWebMessageEntity>,
            body: JSONObject,
            assistantMessageId: Long,
            ownerUserId: Long?
        ): WebChatGenerationResult {
            val paramsJson = mergedWebChatParams(provider, body)
            val params = jsonObjectToMap(paramsJson)
            val server = webProviderAsNativeServer(provider, paramsJson)
            val contextTokens = paramsJson.optInt("contextTokens", 8192).coerceAtLeast(512)
            val nativeChat = LlamaChatEntity(
                id = chat.id,
                title = chat.title,
                contextSize = contextTokens,
                systemPrompt = chat.systemPrompt ?: body.optString("systemPrompt").ifBlank { "You are a helpful assistant." },
                apiParams = paramsJson.toString()
            )
            val thinkingEnabled = paramsJson.optBoolean("enable_thinking", paramsJson.optBoolean("thinkingEnabled", false))
            val rawToolConfig = NativeChatToolConfig.fromParams(params)
            val toolRuntime = createWebNativeToolRuntime()
            val toolConfig = toolRuntime.configWithOrganizerPermissions(rawToolConfig)
            val tools = toolRuntime.availableTools(toolConfig)
            val nativeMessages = buildWebNativeMessages(nativeChat, server, messages, toolConfig).toMutableList()
            val modelName = when {
                server.isOllamaEngine() || server.isLlamaSwapEngine() -> server.modelName?.takeIf { it.isNotBlank() }
                    ?: error(if (server.isOllamaEngine()) "Choose an Ollama model." else "Choose a llama-swap model.")
                server.isLiteRtEngine() -> null
                else -> server.modelName?.takeIf { it.isNotBlank() }
            }
            val progress = WebChatStreamingProgress()
            val generatedImagePaths = mutableListOf<String>()
            var lastDbUpdate = 0L

            suspend fun publishProgress(force: Boolean = false) {
                val now = System.currentTimeMillis()
                if (!force && now - lastDbUpdate < 350L) return
                lastDbUpdate = now
                db.aiServerDao().updateWebMessageContent(
                    id = assistantMessageId,
                    content = progress.content,
                    thinking = progress.thinking.takeIf { it.isNotBlank() },
                    toolActivity = progress.statusText,
                    isError = false
                )
                val elapsedSeconds = ((now - progress.startedAt).coerceAtLeast(1L)) / 1000.0
                val tps = progress.tokenCount / elapsedSeconds
                val status = progress.statusText
                    ?: "%d tok · %.1f t/s".format(Locale.US, progress.tokenCount, tps)
                updateJob(jobId, type.id, "RUNNING", 0.35f, status, null)
            }

            suspend fun runModelCall(
                availableTools: List<AgentTool>,
                callMessages: List<OllamaService.ChatMessage> = nativeMessages,
                useThinking: Boolean = thinkingEnabled
            ): OllamaService.ChatResponse {
                val callOutput = StringBuilder()
                val callThinking = StringBuilder()
                val onChunk: (String?, String?) -> Unit = chunkHandler@{ delta, thinkingDelta ->
                    val visible = delta.orEmpty()
                    val thought = thinkingDelta.orEmpty()
                    if (visible.isBlank() && thought.isBlank()) return@chunkHandler
                    if (visible.isNotEmpty()) {
                        callOutput.append(visible)
                        progress.content += visible
                    }
                    if (thought.isNotEmpty()) {
                        callThinking.append(thought)
                        progress.thinking += thought
                    }
                    progress.tokenCount += 1
                    runBlocking { publishProgress() }
                }
                val response = when {
                    server.isOllamaEngine() -> OllamaService(applicationContext)
                        .also { syncWebOllamaService(server, it) }
                        .chatWithToolsStreaming(
                            model = modelName.orEmpty(),
                            messages = callMessages,
                            tools = availableTools,
                            thinkingEnabled = useThinking,
                            numCtxOverride = contextTokens,
                            onChunk = onChunk
                        ).getOrElse { throw it }
                    server.isLiteRtEngine() -> runLiteRtWebModelCall(
                        chat = nativeChat,
                        server = server,
                        paramsJson = paramsJson,
                        messages = callMessages,
                        tools = availableTools,
                        thinkingEnabled = useThinking,
                        onChunk = { visible, thought ->
                            if (visible.isNotEmpty()) {
                                callOutput.append(visible)
                                progress.content += visible
                            }
                            if (thought.isNotEmpty()) {
                                callThinking.append(thought)
                                progress.thinking += thought
                            }
                            progress.tokenCount += 1
                            publishProgress()
                        }
                    )
                    else -> LlamaServerChatService().chatWithToolsStreaming(
                        baseUrl = server.baseUrl(),
                        messages = callMessages,
                        tools = availableTools,
                        modelLabel = modelName,
                        thinkingEnabled = useThinking,
                        maxTokens = contextTokens,
                        samplingParams = LlamaServerSamplingParams.fromParams(params.filterValues { it != null }.mapValues { it.value as Any }),
                        onChunk = onChunk
                    ).getOrElse { throw it }
                }
                response.usage?.promptTokens?.let { progress.promptTokens += it }
                response.usage?.completionTokens?.let {
                    progress.completionTokens += it
                    progress.tokenCount = progress.completionTokens.coerceAtLeast(progress.tokenCount)
                }
                val content = callOutput.toString().ifBlank { response.message.content }
                val thinking = callThinking.toString().ifBlank { response.message.thinking.orEmpty() }
                if (content.isNotBlank() && progress.content.isBlank()) progress.content = content
                if (thinking.isNotBlank() && progress.thinking.isBlank()) progress.thinking = thinking
                return response.copy(
                    message = response.message.copy(
                        content = content,
                        thinking = thinking.takeIf { it.isNotBlank() }
                    )
                )
            }

            suspend fun executeToolCall(toolCall: OllamaService.ToolCall): NativeChatToolResult {
                val argsJson = JSONObject().apply { toolCall.arguments.forEach { (key, value) -> put(key, value) } }
                recordWebToolEvent(
                    messageId = assistantMessageId,
                    toolName = toolCall.name,
                    phase = "started",
                    status = "RUNNING",
                    arguments = argsJson
                )
                progress.statusText = "Tool: ${toolCall.name}"
                publishProgress(force = true)
                return try {
                    val result = toolRuntime.executeToolCall(
                        toolCall = toolCall,
                        config = toolConfig,
                        chatId = chat.id,
                        onProgress = { toolProgress ->
                            runBlocking {
                                progress.statusText = toolProgress.status.ifBlank { toolCall.name }
                                recordWebToolEvent(
                                    messageId = assistantMessageId,
                                    toolName = toolCall.name,
                                    phase = toolProgress.phase.ifBlank { "progress" },
                                    status = if (toolProgress.isComplete) "COMPLETED" else "RUNNING",
                                    arguments = argsJson,
                                    resultText = toolProgress.outputPreview
                                )
                                publishProgress(force = true)
                            }
                        },
                        searchSummarizer = { request ->
                            summarizeWebSearchPage(server, modelName, request)
                        }
                    ).getOrElse { error ->
                        NativeChatToolResult("tool_error: ${error.message ?: error::class.java.simpleName}")
                    }
                    val failed = result.content.startsWith("tool_error:", ignoreCase = true)
                    recordWebToolEvent(
                        messageId = assistantMessageId,
                        toolName = toolCall.name,
                        phase = "finished",
                        status = if (failed) "FAILED" else "COMPLETED",
                        arguments = argsJson,
                        resultText = result.content.take(2000),
                        errorText = result.content.take(2000).takeIf { failed }
                    )
                    result.generatedImagePath?.takeIf { it.isNotBlank() }?.let { generatedImagePaths += it }
                    result
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    val message = error.message ?: error::class.java.simpleName
                    recordWebToolEvent(
                        messageId = assistantMessageId,
                        toolName = toolCall.name,
                        phase = "failed",
                        status = "FAILED",
                        arguments = argsJson,
                        errorText = message
                    )
                    NativeChatToolResult("tool_error: $message")
                }
            }

            if (tools.isEmpty()) {
                runModelCall(emptyList())
            } else {
                var answered = false
                repeat(toolConfig.maxToolRounds.coerceAtLeast(1)) { round ->
                    if (answered) return@repeat
                    currentCoroutineContext().ensureActive()
                    val visibleBeforeCall = progress.content
                    val response = runModelCall(tools)
                    val toolCalls = normalizeWebToolCalls(response.toolCalls.orEmpty(), round)
                    if (toolCalls.isEmpty()) {
                        if (response.message.content.isNotBlank() && visibleBeforeCall == progress.content) {
                            progress.content += response.message.content
                        }
                        answered = true
                        return@repeat
                    }
                    progress.content = visibleBeforeCall
                    publishProgress(force = true)
                    nativeMessages += response.message.copy(content = "", toolCalls = toolCalls)
                    toolCalls.forEach { toolCall ->
                        val toolResult = executeToolCall(toolCall)
                        nativeMessages += OllamaService.ChatMessage(
                            role = "tool",
                            content = toolResult.content,
                            toolCallId = toolCall.id
                        )
                    }
                    if (round == toolConfig.maxToolRounds - 1) {
                        nativeMessages += OllamaService.ChatMessage(
                            role = "system",
                            content = "The native chat tool round limit has been reached. Answer now using only the tool results already provided. Do not call more tools."
                        )
                        progress.statusText = "Finalizing"
                        publishProgress(force = true)
                        runModelCall(emptyList(), useThinking = false)
                    }
                }
            }

            progress.statusText = null
            publishProgress(force = true)
            generatedImagePaths.distinct().forEach { imagePath ->
                val file = File(imagePath)
                if (file.isFile) {
                    db.aiServerDao().upsertWebMessageAttachment(
                        AiServerWebMessageAttachmentEntity(
                            messageId = assistantMessageId,
                            attachmentType = "image",
                            path = file.absolutePath,
                            mimeType = mimeForFile(file),
                            name = file.name,
                            sizeBytes = file.length()
                        )
                    )
                    recordArtifact(
                        serverType = AiServerType.LLAMA_CHAT.id,
                        ownerUserId = ownerUserId,
                        jobId = jobId,
                        artifactType = IMAGE,
                        file = file,
                        mimeType = mimeForFile(file),
                        title = file.name,
                        metadata = JSONObject().put("source", "web_chat_tool")
                    )
                }
            }
            val elapsedSeconds = ((System.currentTimeMillis() - progress.startedAt).coerceAtLeast(1L)) / 1000.0
            return WebChatGenerationResult(
                output = progress.content.trim().ifBlank { "No response." },
                thinking = progress.thinking.trim().takeIf { it.isNotBlank() },
                promptTokens = progress.promptTokens,
                completionTokens = progress.completionTokens.coerceAtLeast(progress.tokenCount),
                tokensPerSecond = progress.tokenCount / elapsedSeconds
            )
        }

        private fun mergedWebChatParams(
            provider: AiServerWebProviderEntity,
            body: JSONObject
        ): JSONObject {
            val merged = provider.defaultParamsJson
                ?.let { runCatching { JSONObject(it) }.getOrNull() }
                ?: JSONObject()
            body.keys().forEach { key ->
                if (key != "attachments") merged.put(key, body.opt(key))
            }
            if (merged.has("topP")) merged.put("top_p", merged.optDouble("topP"))
            if (merged.has("topK")) merged.put("top_k", merged.optInt("topK"))
            if (merged.has("repeatPenalty")) merged.put("repeat_penalty", merged.optDouble("repeatPenalty"))
            if (merged.has("thinkingEnabled")) merged.put("enable_thinking", merged.optBoolean("thinkingEnabled"))
            if (merged.has("maxOutputTokens")) merged.put(LITERT_PARAM_MAX_OUTPUT_TOKENS, merged.optInt("maxOutputTokens"))
            if (merged.has("providerLiteRtMtpEnabled")) merged.put(LITERT_PARAM_MTP_ENABLED, merged.optBoolean("providerLiteRtMtpEnabled"))
            if (!merged.has("enable_thinking")) merged.put("enable_thinking", false)
            return merged
        }

        private fun jsonObjectToMap(json: JSONObject): Map<String, Any?> =
            buildMap {
                json.keys().forEach { key ->
                    val value = json.opt(key)
                    put(key, if (value == JSONObject.NULL) null else value)
                }
            }

        private fun webProviderAsNativeServer(
            provider: AiServerWebProviderEntity,
            params: JSONObject
        ): LlamaServerEntity {
            val engine = normalizeLlamaServerEngine(provider.engine)
            if (engine == LlamaServerEntity.ENGINE_LITERT_LM) {
                return LlamaServerEntity(
                    name = provider.name,
                    host = "litert",
                    port = 0,
                    engine = engine,
                    supportsVision = provider.supportsVision,
                    supportsAudio = provider.supportsAudio,
                    liteRtModelId = provider.liteRtModelId,
                    liteRtBackend = provider.liteRtBackend,
                    defaultApiParams = params.toString()
                )
            }
            val base = providerBaseUrl(engine, provider.baseUrl)
            val uri = runCatching { URI(base) }.getOrNull()
            val scheme = uri?.scheme?.takeIf { it.isNotBlank() } ?: "http"
            val host = uri?.host?.takeIf { it.isNotBlank() }
                ?: base.removePrefix("http://").removePrefix("https://").substringBefore('/').substringBefore(':')
            val port = uri?.port?.takeIf { it > 0 } ?: when {
                engine == LlamaServerEntity.ENGINE_OLLAMA -> 11434
                scheme == "https" -> 443
                else -> 8080
            }
            return LlamaServerEntity(
                name = provider.name,
                host = "$scheme://$host",
                port = port,
                engine = engine,
                supportsVision = provider.supportsVision,
                supportsAudio = provider.supportsAudio,
                modelName = provider.modelName,
                defaultApiParams = params.toString()
            )
        }

        private fun createWebNativeToolRuntime(): NativeChatToolRuntime =
            NativeChatToolRuntime(
                context = applicationContext,
                noteDao = db.noteDao(),
                organizerDao = db.organizerDao(),
                alarmScheduler = { alarm -> OrganizerAlarmScheduler.scheduleAlarm(applicationContext, alarm) },
                alarmCanceler = { alarmId -> OrganizerAlarmScheduler.cancelAlarm(applicationContext, alarmId) },
                organizerChanged = { OrganizerCalendarWidgetProvider.refreshAll(applicationContext) },
                notesChanged = { NoteDisplayWidgetProvider.refreshAll(applicationContext) },
                knowledgeBaseRepository = com.example.llamadroid.data.repository.KnowledgeBaseRepository(applicationContext, db),
                imageGenerator = NativeChatUnifiedImageGenerator(applicationContext, db),
                backgroundRemover = NativeChatOnnxBackgroundRemover(applicationContext, db),
                pdfTextExtractor = { pdfBytes, maxChars ->
                    com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(applicationContext)
                    extractNativePdfTextFromBytes(pdfBytes, maxChars)
                }
            )

        private suspend fun buildWebNativeMessages(
            chat: LlamaChatEntity,
            server: LlamaServerEntity,
            messages: List<AiServerWebMessageEntity>,
            toolConfig: NativeChatToolConfig
        ): List<OllamaService.ChatMessage> = buildList {
            chat.systemPrompt?.takeIf { it.isNotBlank() }?.let {
                add(OllamaService.ChatMessage(role = "system", content = it))
            }
            if (toolConfig.hasEnabledTools()) {
                addAll(nativeChatToolAwarenessMessages(toolConfig))
            }
            messages.takeLast(64).forEach { message ->
                add(webMessageToNativeMessage(message, server))
            }
        }

        private suspend fun webMessageToNativeMessage(
            message: AiServerWebMessageEntity,
            server: LlamaServerEntity
        ): OllamaService.ChatMessage {
            val imagePath = message.imagePath?.takeIf { server.supportsVision && it.isNotBlank() && File(it).isFile }
            val audioPath = message.audioPath
                ?.takeIf { server.supportsDirectAudioInput() && it.isNotBlank() && File(it).isFile }
                ?.let { prepareAudioPathForNativeLlama(applicationContext, it, forcePcmWav = server.isLiteRtEngine()).getOrThrow() }
            val documentPreview = message.documentPath?.let(::readDocumentPreview)
            val content = buildString {
                append(message.content)
                if (!message.audioPath.isNullOrBlank() && audioPath == null) {
                    if (isNotBlank()) append('\n')
                    append("[Audio attachment: ${File(message.audioPath).name}]")
                }
                if (!message.documentPath.isNullOrBlank()) {
                    if (isNotBlank()) append('\n')
                    append("[Document: ${File(message.documentPath).name}]")
                    documentPreview?.takeIf { it.isNotBlank() }?.let {
                        append('\n')
                        append(it)
                    }
                }
            }.trim()
            return OllamaService.ChatMessage(
                role = message.role,
                content = content,
                images = if (server.isOllamaEngine() && imagePath != null) listOf(fileToBase64(imagePath)) else null,
                imagePath = imagePath,
                audioPath = audioPath,
                thinking = message.thinking
            )
        }

        private fun syncWebOllamaService(server: LlamaServerEntity, service: OllamaService = OllamaService(applicationContext)) {
            val settings = SettingsRepository(applicationContext)
            service.setBaseUrl(server.baseUrl().trimEnd('/'))
            service.setUseMmap(settings.ollamaMmap.value)
            service.setNumThreads(settings.ollamaThreads.value)
            service.setNumCtx(settings.ollamaNumCtx.value)
        }

        private suspend fun runLiteRtWebModelCall(
            chat: LlamaChatEntity,
            server: LlamaServerEntity,
            paramsJson: JSONObject,
            messages: List<OllamaService.ChatMessage>,
            tools: List<AgentTool>,
            thinkingEnabled: Boolean,
            onChunk: suspend (visible: String, thinking: String) -> Unit
        ): OllamaService.ChatResponse {
            val modelId = server.liteRtModelId ?: error("Choose a LiteRT model.")
            val model = db.liteRtModelDao().getById(modelId) ?: error("LiteRT model not found.")
            val latestUserIndex = messages.indexOfLast { it.role == "user" }
            val latestUser = latestUserIndex.takeIf { it >= 0 }?.let { messages[it] }
            val initialMessages = (if (latestUserIndex >= 0) messages.take(latestUserIndex) else messages)
                .mapNotNull(::liteRtConversationMessageFromChat)
            val toolInstruction = if (tools.isEmpty()) "" else buildLiteRtWebToolInstruction(tools)
            val systemPrompt = listOf(chat.systemPrompt.orEmpty(), toolInstruction)
                .filter { it.isNotBlank() }
                .joinToString("\n\n")
                .ifBlank { "You are a helpful assistant." }
            val visible = StringBuilder()
            val thinking = StringBuilder()
            val result = LiteRtTextGenerationClient(applicationContext).generate(
                model = model,
                title = chat.title,
                systemPrompt = systemPrompt,
                messages = initialMessages,
                userPrompt = latestUser?.content ?: "Continue.",
                contextSize = chat.contextSize,
                maxTokens = paramsJson.optInt(LITERT_PARAM_MAX_OUTPUT_TOKENS, paramsJson.optInt("maxOutputTokens", 1024)),
                temperature = paramsJson.optDouble("temperature", 0.7).toFloat().coerceIn(0f, 1f),
                thinkingEnabled = thinkingEnabled,
                backendMode = server.liteRtBackend,
                mtpEnabled = paramsJson.optBoolean(LITERT_PARAM_MTP_ENABLED, paramsJson.optBoolean("liteRtMtpEnabled", false)),
                userImagePath = latestUser?.imagePath,
                userAudioPath = latestUser?.audioPath,
                onChunk = { chunk ->
                    visible.append(chunk)
                    onChunk(chunk, "")
                },
                onThinkingChunk = { chunk ->
                    thinking.append(chunk)
                    onChunk("", chunk)
                }
            )
            val raw = result.rawOutput.ifBlank { result.output }
            val parsed = parseWebLiteRtToolCalls(raw, tools)
            return OllamaService.ChatResponse(
                message = OllamaService.ChatMessage(
                    role = "assistant",
                    content = parsed.first.ifBlank { result.output },
                    thinking = thinking.toString().takeIf { it.isNotBlank() },
                    toolCalls = parsed.second.takeIf { it.isNotEmpty() }
                ),
                done = true,
                toolCalls = parsed.second.takeIf { it.isNotEmpty() },
                usage = OllamaService.ChatUsage(
                    promptTokens = result.stats.promptTokens,
                    completionTokens = result.stats.completionTokens,
                    totalTokens = result.stats.promptTokens + result.stats.completionTokens,
                    backend = server.liteRtBackend
                )
            )
        }

        private fun liteRtConversationMessageFromChat(message: OllamaService.ChatMessage): LiteRtConversationMessage? {
            val content = message.content.ifBlank {
                when {
                    !message.imagePath.isNullOrBlank() -> "Use the attached image."
                    !message.audioPath.isNullOrBlank() -> "Use the attached audio."
                    else -> ""
                }
            }
            if (content.isBlank() && message.toolCalls.isNullOrEmpty()) return null
            return LiteRtConversationMessage(
                role = message.role,
                content = content,
                imagePath = message.imagePath,
                audioPath = message.audioPath,
                toolName = message.toolName,
                toolCalls = message.toolCalls.orEmpty().map { call ->
                    LiteRtToolCallSpec(
                        name = call.name,
                        arguments = call.arguments.mapValues { it.value }
                    )
                }
            )
        }

        private fun buildLiteRtWebToolInstruction(tools: List<AgentTool>): String =
            buildString {
                appendLine("App tools are available. When you need a tool, output exactly one or more blocks and no other text in that assistant turn:")
                appendLine("<tool_call>{\"name\":\"tool_name\",\"arguments\":{\"param\":\"value\"}}</tool_call>")
                appendLine("Use only these tool names:")
                appendLine(tools.joinToString(", ") { it.name })
                appendLine("Tool schemas:")
                append(JSONArray().apply {
                    tools.forEach { tool ->
                        put(JSONObject().apply {
                            put("name", tool.name)
                            put("description", tool.description)
                            put("parameters", JSONObject(tool.parameters))
                            put("required", JSONArray(tool.requiredParams))
                        })
                    }
                })
            }

        private fun parseWebLiteRtToolCalls(
            text: String,
            tools: List<AgentTool>
        ): Pair<String, List<OllamaService.ToolCall>> {
            val available = tools.map { it.name }.toSet()
            val calls = mutableListOf<OllamaService.ToolCall>()
            val pattern = Regex("""<tool_call>([\s\S]*?)</tool_call>""", RegexOption.IGNORE_CASE)
            val visible = pattern.replace(text) { match ->
                parseWebToolCallJson(match.groupValues[1], available)?.let { calls += it }
                ""
            }.trim()
            if (calls.isEmpty()) {
                parseWebToolCallJson(text.trim(), available)?.let { calls += it }
            }
            return visible to calls
        }

        private fun parseWebToolCallJson(raw: String, available: Set<String>): OllamaService.ToolCall? =
            runCatching {
                val json = JSONObject(raw)
                val wrapper = json.optJSONObject("tool_call")
                    ?: json.optJSONObject("function_call")
                    ?: json
                val function = wrapper.optJSONObject("function")
                val source = function ?: wrapper
                val name = source.optString("name").trim()
                if (name.isBlank() || name !in available) return@runCatching null
                val argsSource = source.opt("arguments")
                    ?: source.opt("args")
                    ?: source.opt("parameters")
                OllamaService.ToolCall(
                    name = name,
                    arguments = AgentRuntimeSupport.normalizeToolArguments(argsSource),
                    id = wrapper.optString("id").takeIf { it.isNotBlank() }
                )
            }.getOrNull()

        private suspend fun summarizeWebSearchPage(
            server: LlamaServerEntity,
            modelName: String?,
            request: NativeChatSearchSummaryRequest
        ): String {
            val text = request.content
                .replace(Regex("""[ \t\r\f]+"""), " ")
                .replace(Regex("""\n{3,}"""), "\n\n")
                .trim()
                .take(6_000)
            require(text.isNotBlank()) { "No readable text found." }
            val messages = listOf(
                OllamaService.ChatMessage(
                    role = "system",
                    content = "You are a compact search-result summarizer. Return only a factual 2-3 sentence summary of the page content. Do not quote long passages."
                ),
                OllamaService.ChatMessage(
                    role = "user",
                    content = "Title: ${request.title}\nURL: ${request.url}\n\n$text"
                )
            )
            val response = if (server.isOllamaEngine()) {
                OllamaService(applicationContext)
                    .also { syncWebOllamaService(server, it) }
                    .chatWithToolsStreaming(
                        model = modelName.orEmpty(),
                        messages = messages,
                        tools = emptyList(),
                        thinkingEnabled = false,
                        numCtxOverride = 4096
                    ).getOrElse { throw it }
            } else if (server.isLiteRtEngine()) {
                runLiteRtWebModelCall(
                    chat = LlamaChatEntity(title = "Search summary", contextSize = 4096),
                    server = server,
                    paramsJson = JSONObject().put("maxOutputTokens", 512).put("temperature", 0.2),
                    messages = messages,
                    tools = emptyList(),
                    thinkingEnabled = false,
                    onChunk = { _, _ -> }
                )
            } else {
                LlamaServerChatService().chatWithToolsStreaming(
                    baseUrl = server.baseUrl(),
                    messages = messages,
                    tools = emptyList(),
                    modelLabel = modelName,
                    thinkingEnabled = false,
                    maxTokens = 4096,
                    samplingParams = LlamaServerSamplingParams(temperature = 0.2f, topP = 0.9f)
                ).getOrElse { throw it }
            }
            return response.message.content
                .replace(Regex("""<think>.*?</think>""", RegexOption.DOT_MATCHES_ALL), "")
                .trim()
                .take(request.maxChars.coerceIn(200, 900))
                .ifBlank { "No summary available." }
        }

        private fun normalizeWebToolCalls(
            toolCalls: List<OllamaService.ToolCall>,
            round: Int
        ): List<OllamaService.ToolCall> =
            toolCalls.mapIndexed { index, call ->
                if (!call.id.isNullOrBlank()) call else call.copy(id = "web_${round}_${index}_${System.nanoTime()}")
            }

        private fun readDocumentPreview(path: String): String? =
            runCatching {
                val file = File(path)
                if (!file.isFile) return@runCatching null
                if (file.extension.equals("pdf", ignoreCase = true)) {
                    extractNativePdfTextFromBytes(file.readBytes(), 12_000)
                } else {
                    file.readText().take(12_000)
                }
            }.getOrNull()

        private fun webProviderToJson(provider: AiServerWebProviderEntity): JSONObject =
            JSONObject()
                .put("id", provider.id)
                .put("ownerUserId", provider.ownerUserId ?: JSONObject.NULL)
                .put("name", provider.name)
                .put("engine", provider.engine)
                .put("baseUrl", provider.baseUrl)
                .put("modelName", provider.modelName)
                .put("liteRtModelId", provider.liteRtModelId ?: JSONObject.NULL)
                .put("liteRtBackend", provider.liteRtBackend)
                .put("supportsVision", provider.supportsVision)
                .put("supportsAudio", provider.supportsAudio)
                .put("params", provider.defaultParamsJson?.let { runCatching { JSONObject(it) }.getOrNull() } ?: JSONObject())

        private fun webChatToJson(chat: AiServerWebChatEntity): JSONObject =
            JSONObject()
                .put("id", chat.id)
                .put("ownerUserId", chat.ownerUserId ?: JSONObject.NULL)
                .put("title", chat.title)
                .put("providerId", chat.providerId ?: JSONObject.NULL)
                .put("systemPrompt", chat.systemPrompt)
                .put("updatedAt", chat.updatedAt)

        private fun webMessageToJson(
            message: AiServerWebMessageEntity,
            attachments: List<AiServerWebMessageAttachmentEntity> = emptyList(),
            toolEvents: List<AiServerWebToolEventEntity> = emptyList()
        ): JSONObject =
            JSONObject()
                .put("id", message.id)
                .put("chatId", message.chatId)
                .put("role", message.role)
                .put("content", message.content)
                .put("imagePath", message.imagePath)
                .put("audioPath", message.audioPath)
                .put("documentPath", message.documentPath)
                .put("timestamp", message.createdAt)
                .put("thinking", message.thinking)
                .put("toolActivity", message.toolActivity)
                .put("isError", message.isError)
                .put("attachments", JSONArray(attachments.map { attachment ->
                    JSONObject()
                        .put("id", attachment.id)
                        .put("attachmentType", attachment.attachmentType)
                        .put("path", attachment.path)
                        .put("mimeType", attachment.mimeType)
                        .put("name", attachment.name)
                        .put("sizeBytes", attachment.sizeBytes)
                        .put("url", "/api/media?path=${java.net.URLEncoder.encode(attachment.path, Charsets.UTF_8.name())}")
                }))
                .put("toolEvents", JSONArray(toolEvents.map(::webToolEventToJson)))

        private fun webToolEventToJson(event: AiServerWebToolEventEntity): JSONObject =
            JSONObject()
                .put("id", event.id)
                .put("messageId", event.messageId)
                .put("toolName", event.toolName)
                .put("phase", event.phase)
                .put("status", event.status)
                .put("arguments", event.argumentsJson?.let { runCatching { JSONObject(it) }.getOrNull() } ?: JSONObject.NULL)
                .put("resultText", event.resultText)
                .put("errorText", event.errorText)
                .put("createdAt", event.createdAt)
                .put("updatedAt", event.updatedAt)

        private suspend fun recordWebToolEvent(
            messageId: Long,
            toolName: String,
            phase: String,
            status: String,
            arguments: JSONObject? = null,
            resultText: String? = null,
            errorText: String? = null
        ) {
            val now = System.currentTimeMillis()
            db.aiServerDao().upsertWebToolEvent(
                AiServerWebToolEventEntity(
                    messageId = messageId,
                    toolName = toolName,
                    phase = phase,
                    status = status,
                    argumentsJson = arguments?.toString(),
                    resultText = resultText,
                    errorText = errorText,
                    createdAt = now,
                    updatedAt = now
                )
            )
        }

        private fun upload(session: IHTTPSession): Response {
            val files = HashMap<String, String>()
            session.parseBody(files)
            val firstFile = files.values.firstOrNull { it.isNotBlank() }
                ?: return jsonResponse(JSONObject().put("ok", false).put("error", "No file uploaded"), Response.Status.BAD_REQUEST)
            val source = File(firstFile)
            val originalName = session.parameters.values.flatten().firstOrNull()?.takeIf { it.contains('.') }
                ?: "upload_${timestamp()}"
            val target = File(File(cacheDir, "ai_server_uploads").apply { mkdirs() }, "${UUID.randomUUID()}_${safeFileName(originalName)}")
            source.copyTo(target, overwrite = true)
            return jsonResponse(
                JSONObject()
                    .put("ok", true)
                    .put("path", target.absolutePath)
                    .put("name", target.name)
            )
        }

        private fun healthJson(user: AiServerUserEntity?): JSONObject =
            JSONObject()
                .put("ok", true)
                .put("serverType", type.id)
                .put("displayName", type.displayName)
                .put("emoji", type.emoji)
                .put("port", config.port)
                .put("lanVisible", config.lanVisible)
                .put("accessMode", config.accessMode)
                .put("authenticated", user != null)
                .put("username", user?.username)
                .put("urls", JSONArray(AiServerNetwork.urlsFor(config.port, config.lanVisible).map {
                    JSONObject().put("label", it.first).put("url", it.second)
                }))

        private fun optionsJson(type: AiServerType, user: AiServerUserEntity? = null): JSONObject = runBlocking {
            val ownerUserId = webOwnerUserId(user)
            val models = if (type == AiServerType.AI_HUB) JSONObject() else sharedModelBuckets(type, ownerUserId)
            val descriptor = JSONObject()
                .put("ok", true)
                .put("serverType", type.id)
                .put("engines", JSONArray())
                .put("modes", JSONArray())
                .put("fields", JSONObject())
                .put("models", models)
                .put("defaults", JSONObject())

            when (type) {
                AiServerType.IMAGE -> descriptor
                    .put("engines", JSONArray(listOf(
                        engineJson(
                            "sd",
                            "Stable Diffusion",
                            "Stable Diffusion",
                            "sd_txt2img",
                            "sd_img2img",
                            "sd_inpaint",
                            "sd_adetailer",
                            "sd_upscale"
                        ),
                        engineJson("onnx", "ONNX", "ONNX", "onnx_txt2img", "onnx_img2img", "onnx_bgr")
                    )))
                    .put("modes", JSONArray(listOf(
                        modeJson("sd_txt2img", "sd", "SD text to image", "SD texto a imagen", "Prompt driven image generation.", "Generacion de imagen desde prompt."),
                        modeJson("sd_img2img", "sd", "SD image to image", "SD imagen a imagen", "Transform an uploaded image.", "Transforma una imagen subida."),
                        modeJson("sd_inpaint", "sd", "SD inpaint", "SD inpainting", "Edit the masked area of an uploaded image.", "Edita el area enmascarada de una imagen subida."),
                        modeJson("sd_adetailer", "sd", "SD ADetailer", "SD ADetailer", "Detect and refine faces or objects in an uploaded image.", "Detecta y refina caras u objetos en una imagen subida."),
                        modeJson("sd_upscale", "sd", "SD upscale", "SD escalado", "Upscale an uploaded image.", "Escala una imagen subida."),
                        modeJson("onnx_txt2img", "onnx", "ONNX text to image", "ONNX texto a imagen", "Run ONNX text to image.", "Ejecuta texto a imagen con ONNX."),
                        modeJson("onnx_img2img", "onnx", "ONNX image to image", "ONNX imagen a imagen", "Run ONNX image to image.", "Ejecuta imagen a imagen con ONNX."),
                        modeJson("onnx_bgr", "onnx", "Background removal", "Quitar fondo", "Remove image backgrounds with ONNX.", "Quita fondos con ONNX.")
                    )))
                    .put("fields", JSONObject()
                        .put("sd_txt2img", sdImageFields(includeInput = false, upscaleOnly = false))
                        .put("sd_img2img", sdImageFields(includeInput = true, upscaleOnly = false))
                        .put("sd_inpaint", sdInpaintFields())
                        .put("sd_adetailer", sdAdetailerFields())
                        .put("sd_upscale", sdImageFields(includeInput = true, upscaleOnly = true))
                        .put("onnx_txt2img", onnxImageFields(includeInput = false, backgroundRemoval = false))
                        .put("onnx_img2img", onnxImageFields(includeInput = true, backgroundRemoval = false))
                        .put("onnx_bgr", onnxImageFields(includeInput = true, backgroundRemoval = true)))
                    .put("defaults", JSONObject()
                        .put("sd_txt2img", JSONObject().put("width", 512).put("height", 512).put("steps", 20).put("cfgScale", 7.0).put("seed", -1).put("threads", -1))
                        .put("sd_img2img", JSONObject().put("width", 512).put("height", 512).put("steps", 20).put("cfgScale", 7.0).put("strength", 0.75).put("seed", -1).put("threads", -1))
                        .put("sd_inpaint", JSONObject().put("width", 512).put("height", 512).put("steps", 20).put("cfgScale", 7.0).put("strength", 0.75).put("seed", -1).put("threads", -1))
                        .put("sd_adetailer", JSONObject().put("width", 512).put("height", 512).put("steps", 20).put("cfgScale", 7.0).put("seed", -1).put("threads", -1).put("adConfidence", 0.30).put("adDenoisingStrength", 0.40).put("adMaskBlur", 4).put("adPadding", 32).put("adMaxDetections", 8))
                        .put("sd_upscale", JSONObject().put("upscaleRepeats", 1).put("threads", -1))
                        .put("onnx_txt2img", JSONObject().put("width", 512).put("height", 512).put("steps", 20).put("cfgScale", 7.5).put("seed", -1))
                        .put("onnx_img2img", JSONObject().put("width", 512).put("height", 512).put("steps", 20).put("cfgScale", 7.5).put("strength", ONNX_IMAGE_GEN_DEFAULT_STRENGTH.toDouble()).put("seed", -1))
                        .put("onnx_bgr", JSONObject().put("alphaThreshold", 0.5).put("featherRadius", 1).put("maskSoftness", 1.0).put("maskContrast", 1.0).put("resizeBeforeProcessing", true).put("resizeMaxEdge", 512)))

                AiServerType.VIDEO -> descriptor
                    .put("engines", JSONArray(listOf(engineJson("sd", "Stable Diffusion video", "Video Stable Diffusion", "txt2vid", "img2vid"))))
                    .put("modes", JSONArray(listOf(
                        modeJson("txt2vid", "sd", "Text to video", "Texto a video", "Generate video from a prompt.", "Genera video desde un prompt."),
                        modeJson("img2vid", "sd", "Image to video", "Imagen a video", "Animate an uploaded image.", "Anima una imagen subida.")
                    )))
                    .put("fields", JSONObject()
                        .put("txt2vid", videoFields(includeInput = false))
                        .put("img2vid", videoFields(includeInput = true)))
                    .put("defaults", JSONObject()
                        .put("txt2vid", JSONObject().put("width", 480).put("height", 832).put("videoFrames", 8).put("fps", 5).put("steps", 18).put("cfgScale", 6.0).put("threads", -1).put("vaeTiling", true).put("diffusionFa", true).put("mmap", true))
                        .put("img2vid", JSONObject().put("width", 480).put("height", 832).put("videoFrames", 8).put("fps", 5).put("steps", 18).put("cfgScale", 6.0).put("threads", -1).put("vaeTiling", true).put("diffusionFa", true).put("mmap", true)))

                AiServerType.WORKFLOWS -> {
                    val templates = db.workflowTemplateDao().getAll().first()
                    descriptor
                        .put("templates", JSONArray(templates.map {
                            JSONObject().put("id", it.id).put("name", it.name).put("type", it.type.name)
                        }))
                        .put("engines", JSONArray(listOf(engineJson("workflow", "Workflow", "Flujo", "transcribe_summary", "txt2img_upscale", "manga_translation", "media_translation", "subtitle_translation"))))
                        .put("modes", JSONArray(listOf(
                            modeJson("transcribe_summary", "workflow", "Transcribe and summarize", "Transcribir y resumir", "Audio/video transcription followed by summary.", "Transcripcion de audio/video y resumen."),
                            modeJson("txt2img_upscale", "workflow", "Text to image plus upscale", "Texto a imagen y escalado", "Generate an image and upscale it.", "Genera una imagen y la escala."),
                            modeJson("manga_translation", "workflow", "Manga translation", "Traduccion de manga", "Translate CBZ manga batches with the native PDF translation pipeline.", "Traduce lotes CBZ con el flujo nativo de traduccion PDF."),
                            modeJson("media_translation", "workflow", "Media translation and dubbing", "Traduccion y doblaje", "Translate and dub a media file.", "Traduce y dobla un archivo multimedia."),
                            modeJson("subtitle_translation", "workflow", "Subtitle translation", "Traduccion de subtitulos", "Translate or burn subtitles.", "Traduce o incrusta subtitulos.")
                        )))
                        .put("fields", JSONObject()
                            .put("transcribe_summary", transcribeWorkflowFields())
                            .put("txt2img_upscale", workflowTxt2ImgUpscaleFields())
                            .put("manga_translation", mangaWorkflowFields())
                            .put("media_translation", mediaTranslationFields())
                            .put("subtitle_translation", subtitleTranslationFields()))
                }

                AiServerType.TTS -> descriptor
                    .put("engines", JSONArray(listOf(engineJson("onnx", "ONNX TTS", "ONNX TTS", "tts_text", "tts_document"))))
                    .put("modes", JSONArray(listOf(
                        modeJson("tts_text", "onnx", "Text to speech", "Texto a voz", "Generate audio from typed text.", "Genera audio desde texto escrito."),
                        modeJson("tts_document", "onnx", "File to speech", "Archivo a voz", "Read an uploaded text or PDF file.", "Lee un archivo de texto o PDF subido.")
                    )))
                    .put("fields", JSONObject()
                        .put("tts_text", ttsFields(includeFile = false))
                        .put("tts_document", ttsFields(includeFile = true)))
                    .put("languages", JSONArray(com.example.llamadroid.onnx.supertonicLanguageCodes))
                    .put("defaults", JSONObject()
                        .put("tts_text", JSONObject().put("language", SUPERTONIC_DEFAULT_LANGUAGE).put("speed", SUPERTONIC_DEFAULT_SPEED.toDouble()).put("totalSteps", SUPERTONIC_DEFAULT_TOTAL_STEPS))
                        .put("tts_document", JSONObject().put("language", SUPERTONIC_DEFAULT_LANGUAGE).put("speed", SUPERTONIC_DEFAULT_SPEED.toDouble()).put("totalSteps", SUPERTONIC_DEFAULT_TOTAL_STEPS)))

                AiServerType.VIDEO_UPSCALE -> descriptor
                    .put("engines", JSONArray(listOf(
                        engineJson("REALSR", "RealSR", "RealSR", "video_upscale"),
                        engineJson("REALCUGAN", "RealCUGAN", "RealCUGAN", "video_upscale")
                    )))
                    .put("modes", JSONArray(listOf(modeJson("video_upscale", "REALSR", "Video upscale", "Escalar video", "Upscale an uploaded video.", "Escala un video subido."))))
                    .put("fields", JSONObject().put("video_upscale", videoUpscaleFields()))
                    .put("defaults", JSONObject().put("video_upscale", JSONObject()
                        .put("engine", "REALSR")
                        .put("model", UpscalerModels.getForEngine(UpscalerEngine.REALSR).firstOrNull()?.name)
                        .put("scale", UpscalerModels.getForEngine(UpscalerEngine.REALSR).firstOrNull()?.scales?.firstOrNull() ?: 2)
                        .put("denoise", if (UpscalerModels.getForEngine(UpscalerEngine.REALSR).firstOrNull()?.supportsDenoise == true) 0 else -1)
                        .put("loadThreads", 1)
                        .put("procThreads", 1)
                        .put("saveThreads", 1)))

                AiServerType.DOCS_DATASETS -> {
                    val projects = db.datasetDao().getAllProjects().first()
                    descriptor
                        .put("projects", JSONArray(projects.map {
                            JSONObject().put("id", it.id).put("name", it.name).put("backend", it.backend)
                        }))
                        .put("engines", JSONArray(listOf(
                            engineJson(
                                "docs",
                                "Documents",
                                "Documentos",
                                "pdf_merge",
                                "pdf_split",
                                "pdf_extract_text",
                                "pdf_ocr_text",
                                "pdf_ocr_searchable",
                                "pdf_translate_ocr",
                                "pdf_translate_text_layer",
                                "pdf_images_to_pdf",
                                "pdf_compress",
                                "pdf_split_size",
                                "pdf_summary"
                            ),
                            engineJson("video_summary", "Video summary", "Resumen de video", "video_summary"),
                            engineJson("datasets", "Datasets", "Datasets", "dataset_import", "dataset_pipeline", "dataset_export")
                        )))
                        .put("modes", JSONArray(listOf(
                            modeJson("pdf_merge", "docs", "Merge PDFs", "Unir PDFs", "Combine multiple PDFs into one document.", "Combina varios PDFs en un documento."),
                            modeJson("pdf_split", "docs", "Split PDF pages", "Separar paginas PDF", "Extract selected pages from one PDF.", "Extrae paginas seleccionadas de un PDF."),
                            modeJson("pdf_extract_text", "docs", "Extract PDF text", "Extraer texto PDF", "Extract text and save it as a downloadable file.", "Extrae texto y guardalo como archivo descargable."),
                            modeJson("pdf_ocr_text", "docs", "OCR text", "Texto OCR", "Read text from a PDF or image using OCR.", "Lee texto de un PDF o imagen usando OCR."),
                            modeJson("pdf_ocr_searchable", "docs", "Searchable OCR PDF", "PDF OCR buscable", "Create a searchable OCR PDF.", "Crea un PDF OCR con busqueda."),
                            modeJson("pdf_translate_ocr", "docs", "Translate OCR PDF", "Traducir PDF OCR", "Translate a scanned PDF using the native OCR translation path.", "Traduce un PDF escaneado con el flujo OCR nativo."),
                            modeJson("pdf_translate_text_layer", "docs", "Translate text-layer PDF", "Traducir PDF con texto", "Translate searchable PDFs with the native text-layer translator.", "Traduce PDFs buscables con el traductor de capa de texto."),
                            modeJson("pdf_images_to_pdf", "docs", "Images to PDF", "Imagenes a PDF", "Build a PDF from uploaded images.", "Crea un PDF desde imagenes subidas."),
                            modeJson("pdf_compress", "docs", "Compress PDF", "Comprimir PDF", "Compress images inside a PDF.", "Comprime imagenes dentro de un PDF."),
                            modeJson("pdf_split_size", "docs", "Split by size", "Separar por tamano", "Split a PDF into size-limited parts.", "Divide un PDF en partes por tamano."),
                            modeJson("pdf_summary", "docs", "PDF summary", "Resumen PDF", "Summarize an uploaded PDF.", "Resume un PDF subido."),
                            modeJson("video_summary", "video_summary", "Video summary", "Resumen de video", "Transcribe and summarize video.", "Transcribe y resume video."),
                            modeJson("dataset_import", "datasets", "Import dataset source", "Importar fuente dataset", "Import a PDF or text file into a dataset project.", "Importa un PDF o texto a un proyecto dataset."),
                            modeJson("dataset_pipeline", "datasets", "Run dataset queue", "Ejecutar cola dataset", "Queue clean, question, answer, and rating jobs.", "Encola limpieza, preguntas, respuestas y revision."),
                            modeJson("dataset_export", "datasets", "Export dataset", "Exportar dataset", "Download accepted Q&A pairs.", "Descarga pares P/R aceptados.")
                        )))
                        .put("fields", JSONObject()
                            .put("pdf_tools", pdfToolsFields())
                            .put("pdf_merge", pdfMergeFields())
                            .put("pdf_split", pdfSplitFields())
                            .put("pdf_extract_text", pdfSingleInputFields("PDF file", "Archivo PDF", accept = "application/pdf,.pdf", includeMaxChars = true))
                            .put("pdf_ocr_text", pdfSingleInputFields("PDF or image", "PDF o imagen", accept = "application/pdf,.pdf,image/*", includeMaxChars = true))
                            .put("pdf_ocr_searchable", pdfSingleInputFields("PDF file", "Archivo PDF", accept = "application/pdf,.pdf"))
                            .put("pdf_translate_ocr", withSummaryProvider(
                                pdfSingleInputFields("PDF file", "Archivo PDF", accept = "application/pdf,.pdf"),
                                summaryPromptDefault = PDFTranslationLogic.DEFAULT_PAGE_TRANSLATION_SYSTEM_PROMPT
                            ))
                            .put("pdf_translate_text_layer", withSummaryProvider(
                                pdfBatchFields(),
                                summaryPromptDefault = PDFTranslationLogic.DEFAULT_PAGE_TRANSLATION_SYSTEM_PROMPT
                            ))
                            .put("pdf_images_to_pdf", imagesToPdfFields())
                            .put("pdf_compress", pdfCompressFields())
                            .put("pdf_split_size", pdfSplitSizeFields())
                            .put("pdf_summary", pdfSummaryFields())
                            .put("video_summary", videoSummaryFields())
                            .put("dataset_creator", datasetFields(projects.map { it.id to it.name }))
                            .put("dataset_import", datasetImportFields(projects.map { it.id to it.name }))
                            .put("dataset_pipeline", datasetPipelineFields(projects.map { it.id to it.name }))
                            .put("dataset_export", datasetExportFields(projects.map { it.id to it.name })))
                }

                AiServerType.LLAMA_CHAT -> descriptor
                    .put("engines", JSONArray(listOf(engineJson("web_chat", "Chat", "Chat", "web_chat_send"))))
                    .put("modes", JSONArray(listOf(modeJson("web_chat_send", "web_chat", "Chat", "Chat", "Send a message.", "Envia un mensaje."))))
                    .put("fields", JSONObject().put("web_chat_send", llamaFields()))
                    .put("languages", JSONArray(com.example.llamadroid.onnx.supertonicLanguageCodes))
                    .put("defaults", JSONObject().put("web_chat_send", JSONObject()
                        .put("temperature", 0.7)
                        .put("contextTokens", 8192)
                        .put("maxTokens", 2048)
                        .put("maxOutputTokens", 1024)
                        .put("topP", 0.95)
                        .put("topK", 40)
                        .put("repeatPenalty", 1.1)
                        .put("thinkingEnabled", false)))

                AiServerType.AI_HUB -> descriptor
                    .put("hubServers", hubServersJson())
            }
            finalizeNativeDescriptor(type, descriptor)
        }

        private suspend fun hubServersJson(): JSONArray {
            val configs = db.aiServerDao().getConfigs()
            val entries = AiHubDirectory.entries(configs, runtimeStates.value)
            return JSONArray(entries.map { entry ->
                val type = AiServerType.fromId(entry.serverType) ?: return@map JSONObject()
                JSONObject()
                    .put("serverType", entry.serverType)
                    .put("label", localizedServerName(type))
                    .put("description", localizedServerDescription(type))
                    .put("displayName", entry.displayName)
                    .put("emoji", entry.emoji)
                    .put("port", entry.port)
                    .put("running", entry.running)
                    .put("lanVisible", entry.lanVisible)
            })
        }

        private fun finalizeNativeDescriptor(type: AiServerType, descriptor: JSONObject): JSONObject {
            val exposedActions = mutableSetOf<String>()
            val modes = descriptor.optJSONArray("modes") ?: JSONArray()
            for (index in 0 until modes.length()) {
                modes.optJSONObject(index)?.optString("id")?.takeIf { it.isNotBlank() }?.let(exposedActions::add)
            }
            val coveredActions = AiServerNativeContracts.actionIdsForServer(type)
            val missing = exposedActions - coveredActions
            require(missing.isEmpty()) {
                "Missing native web contract for ${type.id}: ${missing.joinToString()}"
            }
            return descriptor.put("nativeContract", AiServerNativeContracts.serverJson(type))
        }

        private suspend fun sharedModelBuckets(type: AiServerType, ownerUserId: Long?): JSONObject {
            val chats = if (type == AiServerType.LLAMA_CHAT) db.aiServerDao().getWebChatsForOwner(ownerUserId) else emptyList()
            val providers = if (type == AiServerType.LLAMA_CHAT) db.aiServerDao().getWebProvidersForOwner(ownerUserId) else emptyList()
            return JSONObject()
                .put("sdGeneration", sdImageModelsJson())
                .put("sdImageGeneration", sdImageModelsJson())
                .put("sdUpscalers", modelsJson(ModelType.SD_UPSCALER))
                .put("vae", modelsJson(ModelType.SD_VAE))
                .put("tae", modelsJson(ModelType.SD_TAE))
                .put("clipL", modelsJson(ModelType.SD_CLIP_L))
                .put("clipG", modelsJson(ModelType.SD_CLIP_G))
                .put("t5xxl", modelsJson(ModelType.SD_T5XXL))
                .put("llm", modelsJson(ModelType.LLM))
                .put("llmVision", modelsJson(ModelType.VISION, ModelType.MMPROJ, ModelType.VISION_PROJECTOR))
                .put("controlNet", modelsJson(ModelType.SD_CONTROLNET))
                .put("lora", modelsJson(ModelType.SD_LORA))
                .put("adetailer", modelsJson(ModelType.SD_ADETAILER))
                .put("textualInversion", modelsJson(ModelType.SD_TEXTUAL_INVERSION))
                .put("photoMaker", modelsJson(ModelType.SD_PHOTOMAKER))
                .put("onnxImage", modelsJson(ModelType.ONNX_IMAGE_GEN))
                .put("onnxBgr", modelsJson(ModelType.ONNX_BACKGROUND_REMOVAL))
                .put("tts", modelsJson(ModelType.ONNX_TTS))
                .put("ttsVoices", ttsVoicesJson())
                .put("whisper", modelsJson(ModelType.WHISPER))
                .put("video", videoModelsJson())
                .put("liteRtLlm", liteRtModelsJson())
                .put("upscalers", JSONArray(UpscalerModels.models.map {
                    JSONObject()
                        .put("name", it.name)
                        .put("displayName", it.displayName)
                        .put("engine", it.engine.name)
                        .put("scales", JSONArray(it.scales))
                        .put("supportsDenoise", it.supportsDenoise)
                }))
                .put("webProviders", JSONArray(providers.map(::webProviderToJson)))
                .put("webChats", JSONArray(chats.map(::webChatToJson)))
        }

        private fun engineJson(id: String, en: String, es: String, vararg modes: String): JSONObject =
            JSONObject()
                .put("id", id)
                .put("label", localized(en, es))
                .put("modes", JSONArray(modes.toList()))

        private fun modeJson(id: String, engine: String, en: String, es: String, hintEn: String, hintEs: String): JSONObject =
            JSONObject()
                .put("id", id)
                .put("engine", engine)
                .put("label", localized(en, es))
                .put("hint", localized(hintEn, hintEs))

        private fun localized(en: String, es: String): JSONObject =
            JSONObject().put("en", en).put("es", es)

        private fun localizedServerName(type: AiServerType): JSONObject = when (type) {
            AiServerType.IMAGE -> localized("Image Studio", "Estudio de imagen")
            AiServerType.VIDEO -> localized("Video Studio", "Estudio de video")
            AiServerType.WORKFLOWS -> localized("Workflows", "Flujos de trabajo")
            AiServerType.TTS -> localized("Voice Studio", "Estudio de voz")
            AiServerType.VIDEO_UPSCALE -> localized("Video Upscale", "Escalar video")
            AiServerType.DOCS_DATASETS -> localized("Docs and Datasets", "Docs y datasets")
            AiServerType.LLAMA_CHAT -> localized("Llama Chat", "Chat Llama")
            AiServerType.AI_HUB -> localized("AI HUB", "AI HUB")
        }

        private fun localizedServerDescription(type: AiServerType): JSONObject = when (type) {
            AiServerType.IMAGE -> localized(
                "SD, ONNX, background removal, img2img, scaling, and per-user images.",
                "SD, ONNX, quitar fondo, img2img, escalado e imagenes por usuario."
            )
            AiServerType.VIDEO -> localized(
                "Stable Diffusion text-to-video and image-to-video with saved outputs.",
                "Stable Diffusion de texto a video e imagen a video con salidas guardadas."
            )
            AiServerType.WORKFLOWS -> localized(
                "Browser access for built-in workflow templates and queued runs.",
                "Acceso desde navegador a plantillas integradas y ejecuciones en cola."
            )
            AiServerType.TTS -> localized(
                "ONNX TTS with voices, languages, speed, and saved audio.",
                "TTS ONNX con voces, idiomas, velocidad y audio guardado."
            )
            AiServerType.VIDEO_UPSCALE -> localized(
                "RealSR and RealCUGAN options for browser-uploaded videos.",
                "Opciones RealSR y RealCUGAN para videos subidos desde el navegador."
            )
            AiServerType.DOCS_DATASETS -> localized(
                "PDF tools, summaries, translation paths, video summaries, and dataset projects.",
                "Herramientas PDF, resumenes, traduccion, resumen de video y proyectos de datasets."
            )
            AiServerType.LLAMA_CHAT -> localized(
                "Native Llama chats, server status, attachments, and chat history from the browser.",
                "Chats nativos de Llama, estado del servidor, adjuntos e historial desde el navegador."
            )
            AiServerType.AI_HUB -> localized(
                "One web launcher that links to the active built-in AI servers.",
                "Un lanzador web que enlaza a los servidores de IA integrados que estan activos."
            )
        }

        private fun fieldJson(
            id: String,
            type: String,
            en: String,
            es: String,
            required: Boolean = false,
            modelKey: String? = null,
            defaultValue: Any? = null,
            options: List<Pair<String, String>> = emptyList(),
            visibleField: String? = null,
            visibleEquals: Any? = null,
            accept: String? = null,
            multiple: Boolean = false,
            section: String? = null,
            min: Double? = null,
            max: Double? = null,
            step: Double? = null
        ): JSONObject {
            val json = JSONObject()
                .put("id", id)
                .put("type", type)
                .put("label", localized(en, es))
                .put("required", required)
            modelKey?.let { json.put("modelKey", it) }
            defaultValue?.let { json.put("default", it) }
            if (options.isNotEmpty()) {
                json.put("options", JSONArray(options.map { (value, label) ->
                    JSONObject().put("value", value).put("label", localized(label, label))
                }))
            }
            if (!visibleField.isNullOrBlank() && visibleEquals != null) {
                json.put("visibleWhen", JSONObject().put("field", visibleField).put("equals", visibleEquals))
            }
            accept?.let { json.put("accept", it) }
            if (multiple) json.put("multiple", true)
            section?.let { json.put("section", it) }
            min?.let { json.put("min", it) }
            max?.let { json.put("max", it) }
            step?.let { json.put("step", it) }
            return json
        }

        private fun modelField(id: String, modelKey: String, en: String, es: String, required: Boolean = false, section: String? = null): JSONObject =
            fieldJson(id, "model", en, es, required = required, modelKey = modelKey, section = section)

        private fun fileField(id: String, en: String, es: String, required: Boolean = false, accept: String? = null, multiple: Boolean = false, section: String? = null): JSONObject =
            fieldJson(id, "file", en, es, required = required, accept = accept, multiple = multiple, section = section)

        private fun sdImageFields(includeInput: Boolean, upscaleOnly: Boolean): JSONArray {
            val fields = mutableListOf<JSONObject>()
            if (upscaleOnly) {
                fields += modelField("modelPath", "sdUpscalers", "Upscaler model", "Modelo escalador", required = true)
                fields += fileField("inputPath", "Input image", "Imagen de entrada", required = true, accept = "image/*")
                fields += fieldJson("upscaleRepeats", "number", "Upscale repeats", "Repeticiones de escalado", defaultValue = 1, min = 1.0, max = 4.0, step = 1.0)
                fields += fieldJson("threads", "number", "CPU threads", "Hilos CPU", defaultValue = -1, min = -1.0, step = 1.0)
                return JSONArray(fields)
            }
            fields += modelField("modelPath", "sdImageGeneration", "Diffusion model", "Modelo de difusion", required = true, section = "Model")
            fields += fieldJson("prompt", "textarea", "Prompt", "Prompt", required = true, section = "Prompt")
            fields += fieldJson("negativePrompt", "textarea", "Negative prompt", "Prompt negativo", section = "Prompt")
            if (includeInput) {
                fields += fileField("inputPath", "Init image", "Imagen inicial", required = true, accept = "image/*", section = "Prompt")
                fields += fieldJson("strength", "number", "Img2img strength", "Fuerza img2img", defaultValue = ONNX_IMAGE_GEN_DEFAULT_STRENGTH.toDouble(), min = 0.0, max = 1.0, step = 0.05, section = "Prompt")
            }
            fields += sizeAndSamplingFields()
            fields += sdComponentFields()
            fields += sdRuntimeFields()
            return JSONArray(fields)
        }

        /** Stable Diffusion inpaint is img2img plus a required source mask. */
        private fun sdInpaintFields(): JSONArray = sdImageFields(
            includeInput = true,
            upscaleOnly = false
        ).apply {
            put(
                fileField(
                    "maskPath",
                    "Inpaint mask",
                    "Mascara de inpainting",
                    required = true,
                    accept = "image/*",
                    section = "Inpaint"
                )
            )
        }

        /**
         * ADetailer keeps the regular ordered LoRA stack for the base pass and
         * exposes a second ordered stack for the detector's detail pass.
         * Keeping the two JSON fields distinct prevents a detail adapter from
         * leaking into the base image prompt.
         */
        private fun sdAdetailerFields(): JSONArray = sdImageFields(
            includeInput = true,
            upscaleOnly = false
        ).apply {
            put(modelField("adModelPath", "adetailer", "ADetailer detector", "Detector ADetailer", required = true, section = "ADetailer"))
            put(fieldJson("adPrompt", "textarea", "Detail prompt", "Prompt de detalle", section = "ADetailer"))
            put(fieldJson("adNegativePrompt", "textarea", "Detail negative prompt", "Prompt negativo de detalle", section = "ADetailer"))
            put(fieldJson("adConfidence", "number", "Detector confidence", "Confianza del detector", defaultValue = 0.30, min = 0.0, max = 1.0, step = 0.01, section = "ADetailer"))
            put(fieldJson("adDenoisingStrength", "number", "Detail denoising strength", "Fuerza de denoising de detalle", defaultValue = 0.40, min = 0.0, max = 1.0, step = 0.05, section = "ADetailer"))
            put(fieldJson("adMaskBlur", "number", "Detail mask blur", "Desenfoque de mascara de detalle", defaultValue = 4, min = 0.0, max = 128.0, step = 1.0, section = "ADetailer"))
            put(fieldJson("adPadding", "number", "Detail inpaint padding", "Margen de inpainting de detalle", defaultValue = 32, min = 0.0, max = 2048.0, step = 1.0, section = "ADetailer"))
            put(fieldJson("adMaxDetections", "number", "Maximum detections", "Detecciones maximas", defaultValue = 8, min = 1.0, max = 128.0, step = 1.0, section = "ADetailer"))
            put(fieldJson("adSteps", "number", "Detail steps", "Pasos de detalle", min = 1.0, max = 200.0, step = 1.0, section = "ADetailer"))
            put(fieldJson("adCfgScale", "number", "Detail CFG scale", "Escala CFG de detalle", min = 0.0, max = 50.0, step = 0.1, section = "ADetailer"))
            put(modelField("adLoraPath", "lora", "Legacy detail LoRA", "LoRA de detalle heredado", section = "ADetailer"))
            put(fieldJson("adLoraStrength", "number", "Legacy detail LoRA strength", "Fuerza del LoRA de detalle heredado", defaultValue = 1.0, min = -4.0, max = 4.0, step = 0.05, section = "ADetailer"))
            put(fieldJson("adLoras", "textarea", "Ordered detail LoRAs (JSON)", "LoRAs de detalle ordenados (JSON)", defaultValue = "[]", section = "ADetailer"))
        }

        private fun sizeAndSamplingFields(): List<JSONObject> = listOf(
            fieldJson("width", "number", "Width", "Ancho", defaultValue = 512, min = 64.0, step = 8.0, section = "Sampling"),
            fieldJson("height", "number", "Height", "Alto", defaultValue = 512, min = 64.0, step = 8.0, section = "Sampling"),
            fieldJson("steps", "number", "Steps", "Pasos", defaultValue = 20, min = 1.0, step = 1.0, section = "Sampling"),
            fieldJson("cfgScale", "number", "CFG scale", "Escala CFG", defaultValue = 7.0, min = 0.0, step = 0.1, section = "Sampling"),
            fieldJson("seed", "number", "Seed", "Semilla", defaultValue = -1, step = 1.0, section = "Sampling"),
            fieldJson("samplingMethod", "select", "Sampler", "Sampler", defaultValue = SamplingMethod.EULER_A.name, options = enumOptions<SamplingMethod>(), section = "Sampling")
        )

        private fun sdComponentFields(): List<JSONObject> = listOf(
            modelField("vaePath", "vae", "VAE", "VAE", section = "Components"),
            modelField("taePath", "tae", "TAE", "TAE", section = "Components"),
            modelField("clipLPath", "clipL", "CLIP-L", "CLIP-L", section = "Components"),
            modelField("clipGPath", "clipG", "CLIP-G", "CLIP-G", section = "Components"),
            modelField("t5xxlPath", "t5xxl", "T5-XXL", "T5-XXL", section = "Components"),
            modelField("llmPath", "llm", "LLM text encoder", "Codificador LLM", section = "Components"),
            modelField("llmVisionPath", "llmVision", "Vision projector", "Proyector de vision", section = "Components"),
            modelField("controlNetPath", "controlNet", "ControlNet", "ControlNet", section = "Components"),
            fileField("controlImagePath", "Control image", "Imagen ControlNet", accept = "image/*", section = "Components"),
            modelField("loraPath", "lora", "LoRA", "LoRA", section = "Components"),
            fieldJson("loraStrength", "number", "LoRA strength", "Fuerza LoRA", defaultValue = 1.0, min = -4.0, max = 4.0, step = 0.05, section = "Components"),
            fieldJson("loras", "textarea", "Ordered LoRAs (JSON)", "LoRAs ordenados (JSON)", defaultValue = "[]", section = "Components"),
            fieldJson("loraApplyMode", "select", "LoRA apply mode", "Modo LoRA", options = enumOptions<SdLoraApplyMode>(), section = "Components"),
            modelField("textualInversionPath", "textualInversion", "Textual inversion", "Inversion textual", section = "Components"),
            modelField("photoMakerPath", "photoMaker", "PhotoMaker", "PhotoMaker", section = "Components")
        )

        private fun sdRuntimeFields(): List<JSONObject> = listOf(
            fieldJson("threads", "number", "CPU threads", "Hilos CPU", defaultValue = -1, min = -1.0, step = 1.0, section = "Runtime"),
            fieldJson("cacheMode", "select", "Cache mode", "Modo cache", options = enumOptions<SdCacheMode>(), section = "Runtime"),
            fieldJson("cacheOption", "text", "Cache option", "Opcion cache", section = "Runtime"),
            fieldJson("scmMask", "text", "SCM mask", "Mascara SCM", section = "Runtime"),
            fieldJson("scmPolicy", "select", "SCM policy", "Politica SCM", options = enumOptions<SdCacheScmPolicy>(), section = "Runtime"),
            fieldJson("vaeTiling", "checkbox", "VAE tiling", "VAE por teselas", defaultValue = false, section = "Runtime"),
            fieldJson("vaeTileSize", "text", "VAE tile size", "Tamano tesela VAE", defaultValue = "32x32", section = "Runtime"),
            fieldJson("vaeTileOverlap", "number", "VAE tile overlap", "Solape tesela VAE", defaultValue = 0.5, min = 0.0, max = 1.0, step = 0.05, section = "Runtime"),
            fieldJson("flowShift", "number", "Flow shift", "Flow shift", step = 0.1, section = "Runtime"),
            fieldJson("diffusionFa", "checkbox", "Diffusion FA", "Diffusion FA", defaultValue = false, section = "Runtime"),
            fieldJson("mmap", "checkbox", "Memory map", "Mapa de memoria", defaultValue = false, section = "Runtime"),
            fieldJson("vaeConvDirect", "checkbox", "VAE conv direct", "VAE conv directo", defaultValue = false, section = "Runtime"),
            fieldJson("qwenImageZeroCondT", "checkbox", "Qwen image zero cond", "Qwen image zero cond", defaultValue = false, section = "Runtime"),
            fieldJson("chromaDisableDitMask", "checkbox", "Chroma disable DiT mask", "Chroma desactivar mascara DiT", defaultValue = false, section = "Runtime"),
            fieldJson("quantizationType", "text", "Quantization", "Cuantizacion", section = "Runtime")
        )

        private fun onnxImageFields(includeInput: Boolean, backgroundRemoval: Boolean): JSONArray {
            val fields = mutableListOf<JSONObject>()
            fields += modelField("modelPath", if (backgroundRemoval) "onnxBgr" else "onnxImage", "ONNX model", "Modelo ONNX", required = true, section = "Model")
            if (includeInput) fields += fileField("inputPath", "Input image", "Imagen de entrada", required = true, accept = "image/*", section = "Input")
            if (!backgroundRemoval) {
                fields += fieldJson("prompt", "textarea", "Prompt", "Prompt", required = true, section = "Prompt")
                fields += fieldJson("negativePrompt", "textarea", "Negative prompt", "Prompt negativo", section = "Prompt")
                fields += sizeAndSamplingFields().filterNot { it.optString("id") == "samplingMethod" }
                if (includeInput) {
                    fields += fieldJson("strength", "number", "Img2img strength", "Fuerza img2img", defaultValue = ONNX_IMAGE_GEN_DEFAULT_STRENGTH.toDouble(), min = 0.0, max = 1.0, step = 0.05, section = "Prompt")
                }
            } else {
                fields += fieldJson("alphaThreshold", "number", "Alpha threshold", "Umbral alpha", defaultValue = 0.5, min = 0.0, max = 1.0, step = 0.05, section = "Mask")
                fields += fieldJson("featherRadius", "number", "Feather radius", "Radio suavizado", defaultValue = 1, min = 0.0, max = 32.0, step = 1.0, section = "Mask")
                fields += fieldJson("maskSoftness", "number", "Mask softness", "Suavidad mascara", defaultValue = 1.0, min = 0.0, max = 4.0, step = 0.1, section = "Mask")
                fields += fieldJson("maskContrast", "number", "Mask contrast", "Contraste mascara", defaultValue = 1.0, min = 0.0, max = 4.0, step = 0.1, section = "Mask")
                fields += fieldJson("exportMask", "checkbox", "Export mask", "Exportar mascara", defaultValue = false, section = "Mask")
                fields += fieldJson("resizeBeforeProcessing", "checkbox", "Resize before processing", "Redimensionar antes", defaultValue = true, section = "Mask")
                fields += fieldJson("resizeMaxEdge", "number", "Resize max edge", "Lado maximo", defaultValue = 512, min = 64.0, step = 8.0, section = "Mask")
            }
            fields += onnxRuntimeFields()
            return JSONArray(fields)
        }

        private fun onnxRuntimeFields(): List<JSONObject> = listOf(
            fieldJson("backend", "select", "Backend", "Backend", defaultValue = OnnxRuntimeBackend.CPU.name, options = enumOptions<OnnxRuntimeBackend>(), section = "Runtime"),
            fieldJson("runtimeThreadCount", "number", "Runtime threads", "Hilos runtime", min = 1.0, step = 1.0, section = "Runtime"),
            fieldJson("graphOptimizationLevel", "select", "Graph optimization", "Optimizacion grafo", defaultValue = OnnxGraphOptimizationLevel.ALL.name, options = enumOptions<OnnxGraphOptimizationLevel>(), section = "Runtime"),
            fieldJson("executionMode", "select", "Execution mode", "Modo ejecucion", defaultValue = OnnxExecutionMode.SEQUENTIAL.name, options = enumOptions<OnnxExecutionMode>(), section = "Runtime"),
            fieldJson("intraOpThreads", "number", "Intra-op threads", "Hilos intra-op", min = 1.0, step = 1.0, section = "Runtime"),
            fieldJson("interOpThreads", "number", "Inter-op threads", "Hilos inter-op", min = 1.0, step = 1.0, section = "Runtime"),
            fieldJson("unetBackendOverride", "select", "UNet backend", "Backend UNet", defaultValue = OnnxBackendOverride.DEFAULT.name, options = enumOptions<OnnxBackendOverride>(), section = "Runtime"),
            fieldJson("vaeDecoderBackendOverride", "select", "VAE decoder backend", "Backend decoder VAE", defaultValue = OnnxBackendOverride.DEFAULT.name, options = enumOptions<OnnxBackendOverride>(), section = "Runtime"),
            fieldJson("vaeEncoderBackendOverride", "select", "VAE encoder backend", "Backend encoder VAE", defaultValue = OnnxBackendOverride.DEFAULT.name, options = enumOptions<OnnxBackendOverride>(), section = "Runtime"),
            fieldJson("memoryPatternOptimization", "checkbox", "Memory pattern optimization", "Optimizacion patron memoria", defaultValue = true, section = "Runtime"),
            fieldJson("cpuArenaAllocator", "checkbox", "CPU arena allocator", "Asignador arena CPU", defaultValue = true, section = "Runtime"),
            fieldJson("nnapiCpuDisabled", "checkbox", "NNAPI CPU disabled", "NNAPI CPU desactivado", defaultValue = true, section = "Runtime"),
            fieldJson("nnapiUseFp16", "checkbox", "NNAPI FP16", "NNAPI FP16", defaultValue = false, section = "Runtime")
        )

        private fun videoFields(includeInput: Boolean): JSONArray {
            val fields = mutableListOf<JSONObject>()
            fields += modelField("modelPath", "video", "Video diffusion model", "Modelo de video", required = true, section = "Model")
            fields += fieldJson("prompt", "textarea", "Prompt", "Prompt", required = true, section = "Prompt")
            fields += fieldJson("negativePrompt", "textarea", "Negative prompt", "Prompt negativo", section = "Prompt")
            if (includeInput) fields += fileField("inputPath", "Init image", "Imagen inicial", required = true, accept = "image/*", section = "Prompt")
            fields += listOf(
                fieldJson("useVae", "checkbox", "Use VAE", "Usar VAE", defaultValue = false, section = "Components"),
                modelField("vaePath", "vae", "VAE", "VAE", section = "Components"),
                fieldJson("useT5xxl", "checkbox", "Use T5-XXL", "Usar T5-XXL", defaultValue = false, section = "Components"),
                modelField("t5xxlPath", "t5xxl", "T5-XXL", "T5-XXL", section = "Components"),
                fieldJson("loras", "textarea", "Ordered LoRAs (JSON)", "LoRAs ordenados (JSON)", defaultValue = "[]", section = "Components"),
                fieldJson("highNoiseLoras", "textarea", "Wan high-noise LoRAs (JSON)", "LoRAs Wan de alto ruido (JSON)", defaultValue = "[]", section = "Components"),
                fieldJson("loraApplyMode", "select", "LoRA apply mode", "Modo LoRA", options = enumOptions<SdLoraApplyMode>(), section = "Components"),
                fieldJson("videoFrames", "number", "Frames", "Fotogramas", defaultValue = 8, min = 1.0, step = 1.0, section = "Video"),
                fieldJson("fps", "number", "FPS", "FPS", defaultValue = 5, min = 1.0, step = 1.0, section = "Video"),
                fieldJson("width", "number", "Width", "Ancho", defaultValue = 480, min = 64.0, step = 8.0, section = "Video"),
                fieldJson("height", "number", "Height", "Alto", defaultValue = 832, min = 64.0, step = 8.0, section = "Video"),
                fieldJson("steps", "number", "Steps", "Pasos", defaultValue = 18, min = 1.0, step = 1.0, section = "Sampling"),
                fieldJson("cfgScale", "number", "CFG scale", "Escala CFG", defaultValue = 6.0, min = 0.0, step = 0.1, section = "Sampling"),
                fieldJson("samplingMethod", "select", "Sampler", "Sampler", defaultValue = SamplingMethod.EULER.name, options = enumOptions<SamplingMethod>(), section = "Sampling"),
                fieldJson("flowShift", "number", "Flow shift", "Flow shift", step = 0.1, section = "Sampling")
            )
            fields += sdRuntimeFields().filterNot { it.optString("id") in setOf("quantizationType", "qwenImageZeroCondT", "chromaDisableDitMask", "vaeConvDirect") }
            return JSONArray(fields)
        }

        private fun transcribeWorkflowFields(): JSONArray = JSONArray(
            listOf(
                fileField("inputPath", "Media file", "Archivo multimedia", required = true, accept = "audio/*,video/*"),
                modelField("whisperModelPath", "whisper", "Whisper model", "Modelo Whisper", required = true),
                fieldJson("whisperLanguage", "text", "Source language", "Idioma origen", defaultValue = "auto"),
                fieldJson("whisperThreads", "number", "Whisper threads", "Hilos Whisper", defaultValue = 4, min = 1.0, step = 1.0)
            ) + summaryProviderFields(
                summaryPromptDefault = SettingsRepository.DEFAULT_TRANSCRIPT_SUMMARY_PROMPT,
                mergePromptDefault = SettingsRepository.DEFAULT_TRANSCRIPT_MERGE_PROMPT
            )
        )

        private fun workflowTxt2ImgUpscaleFields(): JSONArray {
            val fields = mutableListOf<JSONObject>()
            fields += modelField("modelPath", "sdImageGeneration", "Diffusion model", "Modelo de difusion", required = true, section = "Image")
            fields += modelField("upscalerPath", "sdUpscalers", "Upscaler model", "Modelo escalador", required = true, section = "Image")
            fields += fieldJson("prompt", "textarea", "Prompt", "Prompt", required = true, section = "Image")
            fields += fieldJson("negativePrompt", "textarea", "Negative prompt", "Prompt negativo", section = "Image")
            fields += sizeAndSamplingFields()
            fields += sdComponentFields()
            fields += fieldJson("upscaleRepeats", "number", "Upscale repeats", "Repeticiones de escalado", defaultValue = 1, min = 1.0, step = 1.0, section = "Upscale")
            fields += fieldJson("upscaleThreads", "number", "Upscale threads", "Hilos escalado", defaultValue = 4, min = 1.0, step = 1.0, section = "Upscale")
            return JSONArray(fields)
        }

        private fun mangaWorkflowFields(): JSONArray = JSONArray(
            listOf(
                fileField("inputPaths", "CBZ, ZIP, or PDF files", "Archivos CBZ, ZIP o PDF", required = true, accept = ".cbz,.zip,.pdf,application/vnd.comicbook+zip,application/x-cbz,application/zip,application/pdf", multiple = true),
                fieldJson("exportPdf", "checkbox", "Export translated PDF", "Exportar PDF traducido", defaultValue = true),
                fieldJson("exportCbz", "checkbox", "Export translated CBZ", "Exportar CBZ traducido", defaultValue = true)
            ) + summaryProviderFields(
                summaryPromptDefault = PDFTranslationLogic.DEFAULT_PAGE_TRANSLATION_SYSTEM_PROMPT,
                mergePromptDefault = SettingsRepository.DEFAULT_TRANSCRIPT_MERGE_PROMPT
            )
        )

        private fun mediaTranslationFields(): JSONArray = JSONArray(
            listOf(
                fileField("inputPath", "Media file", "Archivo multimedia", required = true, accept = "audio/*,video/*"),
                modelField("whisperModelPath", "whisper", "Whisper model", "Modelo Whisper", required = true),
                fieldJson("whisperLanguage", "text", "Source language", "Idioma origen", defaultValue = "auto"),
                fieldJson("whisperThreads", "number", "Whisper threads", "Hilos Whisper", defaultValue = 4, min = 1.0, step = 1.0),
                fieldJson("targetLanguage", "text", "Target language", "Idioma destino", defaultValue = "Spanish"),
                modelField("ttsModelPath", "tts", "TTS model", "Modelo TTS", required = true),
                fieldJson("ttsLanguage", "select", "TTS language", "Idioma TTS", defaultValue = SUPERTONIC_DEFAULT_LANGUAGE, options = languageOptions()),
                fieldJson("ttsVoiceName", "select", "Voice", "Voz", modelKey = "ttsVoices"),
                fieldJson("ttsSteps", "number", "TTS steps", "Pasos TTS", defaultValue = SUPERTONIC_DEFAULT_TOTAL_STEPS, min = 1.0, step = 1.0),
                fieldJson("outputMode", "select", "Output mode", "Modo salida", defaultValue = MediaTranslationOutputMode.AUTO.name, options = enumOptions<MediaTranslationOutputMode>()),
                fieldJson("replaceOriginalAudio", "checkbox", "Replace original audio", "Reemplazar audio original", defaultValue = true),
                fieldJson("skipFailedTranslationLines", "checkbox", "Skip failed lines after retries", "Omitir lineas fallidas tras reintentos", defaultValue = false)
            ) + summaryProviderFields(
                includeTargetLanguage = false,
                summaryPromptDefault = SettingsRepository.DEFAULT_TRANSCRIPT_SUMMARY_PROMPT,
                mergePromptDefault = SettingsRepository.DEFAULT_TRANSCRIPT_MERGE_PROMPT
            )
        )

        private fun subtitleTranslationFields(): JSONArray = JSONArray(
            listOf(
                fileField("inputPath", "Video file", "Archivo de video", required = true, accept = "video/*"),
                fileField("subtitlePath", "Subtitle file", "Archivo subtitulos", accept = ".srt,.vtt,.ass"),
                modelField("whisperModelPath", "whisper", "Whisper model", "Modelo Whisper"),
                fieldJson("whisperLanguage", "text", "Source language", "Idioma origen", defaultValue = "auto"),
                fieldJson("whisperThreads", "number", "Whisper threads", "Hilos Whisper", defaultValue = 4, min = 1.0, step = 1.0),
                fieldJson("targetLanguage", "text", "Target language", "Idioma destino", defaultValue = "Spanish"),
                fieldJson("translateSubtitles", "checkbox", "Translate subtitles", "Traducir subtitulos", defaultValue = true),
                fieldJson("skipFailedTranslationLines", "checkbox", "Skip failed lines after retries", "Omitir lineas fallidas tras reintentos", defaultValue = false),
                fieldJson("burnIntoVideo", "checkbox", "Burn into video", "Incrustar en video", defaultValue = true),
                fieldJson("fontSize", "number", "Font size", "Tamano fuente", defaultValue = 24, min = 8.0, step = 1.0),
                fieldJson("alignment", "number", "Alignment", "Alineacion", defaultValue = 2, min = 1.0, max = 9.0, step = 1.0),
                fieldJson("marginV", "number", "Vertical margin", "Margen vertical", defaultValue = 20, min = 0.0, step = 1.0)
            ) + summaryProviderFields(
                includeTargetLanguage = false,
                summaryPromptDefault = SettingsRepository.DEFAULT_TRANSCRIPT_SUMMARY_PROMPT,
                mergePromptDefault = SettingsRepository.DEFAULT_TRANSCRIPT_MERGE_PROMPT
            )
        )

        private fun ttsFields(includeFile: Boolean): JSONArray {
            val fields = mutableListOf<JSONObject>()
            fields += modelField("modelPath", "tts", "TTS model", "Modelo TTS", required = true)
            if (includeFile) {
                fields += fileField("inputPath", "Text or PDF file", "Archivo texto o PDF", required = true, accept = ".txt,.md,.pdf,text/plain,application/pdf")
            } else {
                fields += fieldJson("text", "textarea", "Text", "Texto", required = true)
            }
            fields += fieldJson("language", "select", "Language", "Idioma", defaultValue = SUPERTONIC_DEFAULT_LANGUAGE, options = languageOptions())
            fields += fieldJson("voiceName", "select", "Voice", "Voz", modelKey = "ttsVoices")
            fields += fieldJson("speed", "number", "Speed", "Velocidad", defaultValue = SUPERTONIC_DEFAULT_SPEED.toDouble(), min = 0.2, max = 2.0, step = 0.01)
            fields += fieldJson("totalSteps", "number", "Quality steps", "Pasos de calidad", defaultValue = SUPERTONIC_DEFAULT_TOTAL_STEPS, min = 1.0, step = 1.0)
            return JSONArray(fields)
        }

        private fun videoUpscaleFields(): JSONArray = JSONArray(listOf(
            fileField("inputPath", "Video file", "Archivo de video", required = true, accept = "video/*"),
            fieldJson("model", "upscalerModel", "Model", "Modelo", required = true, modelKey = "upscalers"),
            fieldJson("scale", "select", "Scale", "Escala", required = true),
            fieldJson("denoise", "number", "Denoise", "Denoise", defaultValue = -1, min = -1.0, max = 3.0, step = 1.0),
            fieldJson("loadThreads", "number", "Load threads", "Hilos carga", defaultValue = 1, min = 1.0, step = 1.0),
            fieldJson("procThreads", "number", "Process threads", "Hilos proceso", defaultValue = 1, min = 1.0, step = 1.0),
            fieldJson("saveThreads", "number", "Save threads", "Hilos guardado", defaultValue = 1, min = 1.0, step = 1.0)
        ))

        private fun withSummaryProvider(
            fields: JSONArray,
            includeTargetLanguage: Boolean = true,
            summaryPromptDefault: String = SettingsRepository.DEFAULT_TRANSCRIPT_SUMMARY_PROMPT,
            mergePromptDefault: String = SettingsRepository.DEFAULT_TRANSCRIPT_MERGE_PROMPT
        ): JSONArray {
            val items = mutableListOf<JSONObject>()
            for (index in 0 until fields.length()) {
                fields.optJSONObject(index)?.let(items::add)
            }
            items += summaryProviderFields(includeTargetLanguage, summaryPromptDefault, mergePromptDefault)
            return JSONArray(items)
        }

        private fun summaryProviderFields(
            includeTargetLanguage: Boolean = true,
            summaryPromptDefault: String = SettingsRepository.DEFAULT_TRANSCRIPT_SUMMARY_PROMPT,
            mergePromptDefault: String = SettingsRepository.DEFAULT_TRANSCRIPT_MERGE_PROMPT
        ): List<JSONObject> {
            val fields = mutableListOf(
                fieldJson(
                    "summaryBackend",
                    "select",
                    "Summarizer engine",
                    "Motor de resumen",
                    defaultValue = SettingsRepository.PDF_BACKEND_OLLAMA,
                    options = listOf(
                        SettingsRepository.PDF_BACKEND_LLAMA_SERVER to "llama-server",
                        SettingsRepository.PDF_BACKEND_OLLAMA to "Ollama",
                        SettingsRepository.PDF_BACKEND_LLAMA_SWAP to "llama-swap",
                        SettingsRepository.PDF_BACKEND_LITERT to "LiteRT"
                    ),
                    section = "Summarizer"
                ),
                fieldJson("summaryLlamaServerUrl", "text", "llama-server URL", "URL llama-server", defaultValue = SettingsRepository.PDF_LLAMA_SERVER_DEFAULT_URL, visibleField = "summaryBackend", visibleEquals = SettingsRepository.PDF_BACKEND_LLAMA_SERVER, section = "Summarizer"),
                fieldJson("summaryLlamaServerModelLabel", "text", "llama-server model", "Modelo llama-server", visibleField = "summaryBackend", visibleEquals = SettingsRepository.PDF_BACKEND_LLAMA_SERVER, section = "Summarizer"),
                fieldJson("summaryLlamaServerContextTokens", "number", "llama-server context", "Contexto llama-server", defaultValue = -1, min = -1.0, step = 1.0, visibleField = "summaryBackend", visibleEquals = SettingsRepository.PDF_BACKEND_LLAMA_SERVER, section = "Summarizer"),
                fieldJson("summaryOllamaUrl", "text", "Ollama URL", "URL Ollama", defaultValue = com.example.llamadroid.util.AIConstants.Urls.OLLAMA_DEFAULT, visibleField = "summaryBackend", visibleEquals = SettingsRepository.PDF_BACKEND_OLLAMA, section = "Summarizer"),
                fieldJson("summaryOllamaModel", "text", "Ollama model", "Modelo Ollama", visibleField = "summaryBackend", visibleEquals = SettingsRepository.PDF_BACKEND_OLLAMA, section = "Summarizer"),
                fieldJson("summaryLlamaSwapUrl", "text", "llama-swap URL", "URL llama-swap", defaultValue = SettingsRepository.PDF_LLAMA_SWAP_DEFAULT_URL, visibleField = "summaryBackend", visibleEquals = SettingsRepository.PDF_BACKEND_LLAMA_SWAP, section = "Summarizer"),
                fieldJson("summaryLlamaSwapModel", "text", "llama-swap model", "Modelo llama-swap", visibleField = "summaryBackend", visibleEquals = SettingsRepository.PDF_BACKEND_LLAMA_SWAP, section = "Summarizer"),
                fieldJson("summaryLiteRtModelId", "model", "LiteRT model", "Modelo LiteRT", modelKey = "liteRtLlm", visibleField = "summaryBackend", visibleEquals = SettingsRepository.PDF_BACKEND_LITERT, section = "Summarizer"),
                fieldJson("summaryLiteRtBackend", "select", "LiteRT backend", "Backend LiteRT", defaultValue = LITERT_BACKEND_AUTO, options = liteRtBackendOptions(), visibleField = "summaryBackend", visibleEquals = SettingsRepository.PDF_BACKEND_LITERT, section = "Summarizer"),
                fieldJson("summaryLiteRtMtpEnabled", "checkbox", "LiteRT MTP", "LiteRT MTP", defaultValue = false, visibleField = "summaryBackend", visibleEquals = SettingsRepository.PDF_BACKEND_LITERT, section = "Summarizer"),
                fieldJson("summaryThinkingEnabled", "checkbox", "Thinking", "Razonamiento", defaultValue = false, section = "Summarizer"),
                fieldJson("summaryChunkContext", "number", "Chunk context", "Contexto por bloque", defaultValue = 8192, min = 1.0, step = 1.0, section = "Summarizer"),
                fieldJson("summaryChunkMaxTokens", "number", "Chunk max tokens", "Tokens max bloque", defaultValue = 2048, min = 1.0, step = 1.0, section = "Summarizer"),
                fieldJson("summaryMergeContext", "number", "Merge context", "Contexto de union", defaultValue = 8192, min = 1.0, step = 1.0, section = "Summarizer"),
                fieldJson("summaryMergeMaxTokens", "number", "Merge max tokens", "Tokens max union", defaultValue = 2048, min = 1.0, step = 1.0, section = "Summarizer"),
                fieldJson("summaryTemperature", "number", "Temperature", "Temperatura", defaultValue = 0.2, min = 0.0, max = 2.0, step = 0.05, section = "Summarizer"),
                fieldJson("summaryTimeoutMinutes", "number", "Timeout minutes", "Minutos de espera", defaultValue = 30, min = 0.0, step = 1.0, section = "Summarizer")
            )
            if (includeTargetLanguage) {
                fields += fieldJson("targetLanguage", "text", "Target language", "Idioma destino", defaultValue = "Spanish", section = "Summarizer")
            }
            fields += fieldJson("summaryPrompt", "textarea", "Summary prompt", "Prompt de resumen", defaultValue = summaryPromptDefault, section = "Prompts")
            fields += fieldJson("summaryMergePrompt", "textarea", "Merge prompt", "Prompt de union", defaultValue = mergePromptDefault, section = "Prompts")
            return fields
        }

        private fun pdfToolsFields(): JSONArray = JSONArray(listOf(
            fileField("inputPath", "PDF file", "Archivo PDF", required = true, accept = "application/pdf,.pdf"),
            fieldJson(
                "tool",
                "select",
                "Tool",
                "Herramienta",
                defaultValue = "extract_text",
                options = listOf(
                    "extract_text" to "Extract text",
                    "translate_text_layer" to "Translate text-layer PDF",
                    "translate_ocr" to "Translate OCR PDF",
                    "split" to "Split pages",
                    "compress" to "Compress PDF"
                )
            ),
            fieldJson("pageRange", "text", "Page range", "Rango paginas", defaultValue = "1-1"),
            fieldJson("compressionLevel", "number", "Compression level", "Nivel compresion", defaultValue = 5, min = 1.0, max = 9.0, step = 1.0),
            fieldJson("maxChars", "number", "Max characters", "Maximo caracteres", defaultValue = 250000, min = 1000.0, step = 1000.0)
        ))

        private fun pdfMergeFields(): JSONArray = JSONArray(listOf(
            fileField("inputPaths", "PDF files", "Archivos PDF", required = true, accept = "application/pdf,.pdf", multiple = true)
        ))

        private fun pdfBatchFields(): JSONArray = JSONArray(listOf(
            fileField("inputPaths", "PDF files", "Archivos PDF", required = true, accept = "application/pdf,.pdf", multiple = true)
        ))

        private fun pdfSplitFields(): JSONArray = JSONArray(listOf(
            fileField("inputPath", "PDF file", "Archivo PDF", required = true, accept = "application/pdf,.pdf"),
            fieldJson("pageRange", "text", "Page range", "Rango paginas", required = true, defaultValue = "1-3,5")
        ))

        private fun pdfSingleInputFields(en: String, es: String, accept: String, includeMaxChars: Boolean = false): JSONArray {
            val fields = mutableListOf(fileField("inputPath", en, es, required = true, accept = accept))
            if (includeMaxChars) {
                fields += fieldJson("maxChars", "number", "Max characters", "Maximo caracteres", defaultValue = 250000, min = 1000.0, step = 1000.0)
            }
            return JSONArray(fields)
        }

        private fun imagesToPdfFields(): JSONArray = JSONArray(listOf(
            fileField("inputPaths", "Images", "Imagenes", required = true, accept = "image/*", multiple = true)
        ))

        private fun pdfCompressFields(): JSONArray = JSONArray(listOf(
            fileField("inputPath", "PDF file", "Archivo PDF", required = true, accept = "application/pdf,.pdf"),
            fieldJson("compressionLevel", "number", "Compression level", "Nivel compresion", defaultValue = 5, min = 1.0, max = 9.0, step = 1.0)
        ))

        private fun pdfSplitSizeFields(): JSONArray = JSONArray(listOf(
            fileField("inputPath", "PDF file", "Archivo PDF", required = true, accept = "application/pdf,.pdf"),
            fieldJson("maxSizeMb", "number", "Max size MB", "Tamano maximo MB", defaultValue = 5, min = 1.0, step = 1.0)
        ))

        private fun pdfSummaryFields(): JSONArray = JSONArray(
            listOf(
                fileField("inputPath", "PDF file", "Archivo PDF", required = true, accept = "application/pdf,.pdf"),
                fieldJson("maxChars", "number", "Max characters", "Maximo caracteres", defaultValue = 250000, min = 1000.0, step = 1000.0)
            ) + summaryProviderFields(
                summaryPromptDefault = PDFSummaryService.DEFAULT_SUMMARY_PROMPT,
                mergePromptDefault = PDFSummaryService.DEFAULT_UNIFICATION_PROMPT
            )
        )

        private fun videoSummaryFields(): JSONArray = JSONArray(
            listOf(
                fileField("inputPath", "Video file", "Archivo de video", required = true, accept = "video/*"),
                modelField("whisperModelPath", "whisper", "Whisper model", "Modelo Whisper", required = true),
                fieldJson("whisperLanguage", "text", "Source language", "Idioma origen", defaultValue = "auto"),
                fieldJson("whisperThreads", "number", "Whisper threads", "Hilos Whisper", defaultValue = 4, min = 1.0, step = 1.0)
            ) + summaryProviderFields(
                summaryPromptDefault = SettingsRepository.DEFAULT_TRANSCRIPT_SUMMARY_PROMPT,
                mergePromptDefault = SettingsRepository.DEFAULT_TRANSCRIPT_MERGE_PROMPT
            )
        )

        private fun datasetFields(projects: List<Pair<Long, String>>): JSONArray = datasetPipelineFields(projects)

        private fun datasetImportFields(projects: List<Pair<Long, String>>): JSONArray = JSONArray(listOf(
            fieldJson("projectId", "select", "Dataset project", "Proyecto dataset", required = true, options = projects.map { it.first.toString() to it.second }),
            fileField("inputPath", "Source PDF or text", "PDF o texto fuente", required = true, accept = ".txt,.md,.pdf,text/plain,application/pdf")
        ))

        private fun datasetPipelineFields(projects: List<Pair<Long, String>>): JSONArray = JSONArray(listOf(
            fieldJson("projectId", "select", "Dataset project", "Proyecto dataset", required = true, options = projects.map { it.first.toString() to it.second }),
            fieldJson(
                "stage",
                "select",
                "Queue action",
                "Accion de cola",
                defaultValue = "all",
                options = listOf(
                    "all" to "Full pipeline",
                    "clean" to "Clean chunks",
                    "questions" to "Generate questions",
                    "answers" to "Generate answers",
                    "rating" to "Rate answers"
                )
            ),
            fieldJson("cleanPrompt", "textarea", "Clean prompt", "Prompt limpieza", defaultValue = defaultDatasetPrompt(PromptType.CLEAN, DEFAULT_CLEAN_PROMPT)),
            fieldJson("questionPrompt", "textarea", "Question prompt", "Prompt preguntas", defaultValue = defaultDatasetPrompt(PromptType.QUESTION, DEFAULT_QUESTION_PROMPT)),
            fieldJson("answerPrompt", "textarea", "Answer prompt", "Prompt respuestas", defaultValue = defaultDatasetPrompt(PromptType.ANSWER, DEFAULT_ANSWER_PROMPT)),
            fieldJson("reviewPrompt", "textarea", "Review prompt", "Prompt revision", defaultValue = defaultDatasetPrompt(PromptType.REVIEW, DEFAULT_REVIEW_PROMPT))
        ))

        private fun datasetExportFields(projects: List<Pair<Long, String>>): JSONArray = JSONArray(listOf(
            fieldJson("projectId", "select", "Dataset project", "Proyecto dataset", required = true, options = projects.map { it.first.toString() to it.second }),
            fieldJson("exportFormat", "select", "Export format", "Formato exportacion", defaultValue = "jsonl", options = listOf("jsonl" to "JSONL", "alpaca" to "Alpaca", "sharegpt" to "ShareGPT")),
            fieldJson("minScore", "number", "Minimum score", "Puntuacion minima", defaultValue = 3, min = 0.0, max = 5.0, step = 1.0)
        ))

        private fun llamaFields(): JSONArray = JSONArray(listOf(
            fieldJson("providerId", "select", "Provider", "Proveedor", required = true, modelKey = "webProviders", section = "Chat"),
            fieldJson("chatId", "select", "Chat", "Chat", modelKey = "webChats", section = "Chat"),
            fieldJson("chatTitle", "text", "New chat name", "Nombre del chat nuevo", section = "Chat"),
            fieldJson("message", "textarea", "Message", "Mensaje", required = true, section = "Chat"),
            fileField("imagePath", "Image attachment", "Imagen adjunta", accept = "image/*", section = "Attachments"),
            fileField("audioPath", "Audio attachment", "Audio adjunto", accept = "audio/*", section = "Attachments"),
            fileField("documentPath", "Document attachment", "Documento adjunto", accept = ".txt,.md,.pdf,text/plain,application/pdf", section = "Attachments"),
            fieldJson("contextTokens", "number", "Context tokens", "Tokens de contexto", defaultValue = 8192, min = 512.0, step = 1.0, section = "Generation"),
            fieldJson("maxTokens", "number", "Max reply tokens", "Tokens max respuesta", defaultValue = 2048, min = 1.0, step = 1.0, section = "Generation"),
            fieldJson("maxOutputTokens", "number", "Max output tokens", "Tokens max salida", defaultValue = 1024, min = 1.0, step = 1.0, section = "Generation"),
            fieldJson("temperature", "number", "Temperature", "Temperatura", defaultValue = 0.7, min = 0.0, max = 2.0, step = 0.05, section = "Generation"),
            fieldJson("topP", "number", "Top P", "Top P", defaultValue = 0.95, min = 0.0, max = 1.0, step = 0.01, section = "Generation"),
            fieldJson("topK", "number", "Top K", "Top K", defaultValue = 40, min = 1.0, max = 100.0, step = 1.0, section = "Generation"),
            fieldJson("repeatPenalty", "number", "Repeat penalty", "Penalizacion repeticion", defaultValue = 1.1, min = 0.8, max = 2.0, step = 0.05, section = "Generation"),
            fieldJson("thinkingEnabled", "checkbox", "Thinking", "Razonamiento", defaultValue = false, section = "Generation")
        ))

        private fun languageOptions(): List<Pair<String, String>> =
            com.example.llamadroid.onnx.supertonicLanguageCodes.map { it to it }

        private inline fun <reified T : Enum<T>> enumOptions(): List<Pair<String, String>> =
            enumValues<T>().map { it.name to it.name.replace('_', ' ') }

        private fun liteRtBackendOptions(): List<Pair<String, String>> =
            listOf(
                LITERT_BACKEND_AUTO to "Auto",
                LITERT_BACKEND_CPU to "CPU",
                LITERT_BACKEND_GPU to "GPU"
            )

        private suspend fun modelsJson(vararg types: ModelType): JSONArray {
            val models = db.modelDao().getModelsByTypesSync(types.toList())
            return modelsToJson(models)
        }

        private fun modelsToJson(models: List<ModelEntity>): JSONArray =
            JSONArray(models.map { model ->
                JSONObject()
                    .put("filename", model.filename)
                    .put("path", model.path)
                    .put("type", model.type.name)
                    .put("sizeBytes", model.sizeBytes)
                    .put("capabilities", JSONArray(capabilitiesFor(model)))
            })

        private suspend fun sdImageModelsJson(): JSONArray {
            val models = db.modelDao().getModelsByTypesSync(listOf(ModelType.SD_CHECKPOINT, ModelType.SD_DIFFUSION))
                .filterNot { it.hasSdCapability(SD_CAPABILITY_VID_GEN) }
            return modelsToJson(models)
        }

        private suspend fun videoModelsJson(): JSONArray {
            val models = db.modelDao().getModelsByTypesSync(listOf(ModelType.SD_DIFFUSION))
                .filter { it.hasSdCapability(SD_CAPABILITY_VID_GEN) }
            return modelsToJson(models)
        }

        private suspend fun ttsVoicesJson(): JSONArray {
            val voices = db.modelDao().getModelsByTypesSync(listOf(ModelType.ONNX_TTS))
                .flatMap { model ->
                    runCatching { resolveSupertonicVoices(File(model.path)) }
                        .getOrDefault(emptyList())
                }
                .distinct()
                .sortedWith(compareBy<String> { if (it.equals("M1", ignoreCase = true)) 0 else 1 }.thenBy { it })
            return JSONArray(voices.map { voice ->
                JSONObject()
                    .put("value", voice)
                    .put("label", localized(voice, voice))
            })
        }

        private suspend fun liteRtModelsJson(): JSONArray {
            val models = db.liteRtModelDao().observeAll().first()
            return JSONArray(models.map { model ->
                liteRtModelToJson(model)
            })
        }

        private fun liteRtModelToJson(model: LiteRtModelEntity): JSONObject =
            JSONObject()
                .put("id", model.id)
                .put("value", model.id.toString())
                .put("displayName", model.displayName)
                .put("filename", model.filename)
                .put("path", model.path)
                .put("supportsVision", model.supportsVision)
                .put("supportsAudio", model.supportsAudio)
                .put("maxContextTokens", model.maxContextTokens ?: JSONObject.NULL)

        private fun capabilitiesFor(model: ModelEntity): List<String> =
            when (model.type) {
                ModelType.SD_UPSCALER -> listOf(SD_CAPABILITY_UPSCALE)
                ModelType.SD_CHECKPOINT, ModelType.SD_DIFFUSION -> listOfNotNull(
                    SD_CAPABILITY_TXT2IMG.takeIf { model.sdCapabilities == null || model.hasSdCapability(it) },
                    SD_CAPABILITY_IMG2IMG.takeIf { model.sdCapabilities == null || model.hasSdCapability(it) },
                    SD_CAPABILITY_VID_GEN.takeIf { model.hasSdCapability(it) }
                )
                ModelType.ONNX_IMAGE_GEN -> listOfNotNull(
                    ONNX_CAPABILITY_TXT2IMG.takeIf { model.onnxCapabilities == null || model.hasOnnxCapability(it) },
                    ONNX_CAPABILITY_IMG2IMG.takeIf { model.hasOnnxCapability(it) }
                )
                ModelType.ONNX_BACKGROUND_REMOVAL -> listOf(ONNX_CAPABILITY_BACKGROUND_REMOVAL)
                ModelType.ONNX_TTS -> listOf(ONNX_CAPABILITY_TTS)
                else -> emptyList()
            }

        private fun galleryJson(type: AiServerType, user: AiServerUserEntity?): JSONObject = runBlocking {
            val ownerUserId = if (config.accessMode == AiServerAccessMode.USERS) user?.id else null
            val dbArtifacts = db.aiServerDao().getArtifactsForServer(type.id, ownerUserId)
            val artifacts = dbArtifacts.filter { File(it.path).exists() || type != AiServerType.IMAGE }
            JSONObject()
                .put("ok", true)
                .put("artifacts", JSONArray(artifacts.map { artifactToJson(it) }))
        }

        private fun deleteGalleryArtifact(rawId: String, user: AiServerUserEntity?): Response = runBlocking {
            val artifactId = rawId.toLongOrNull()
                ?: return@runBlocking jsonResponse(JSONObject().put("ok", false).put("error", "Invalid artifact"), Response.Status.BAD_REQUEST)
            val artifact = db.aiServerDao().getArtifactById(artifactId)
                ?: return@runBlocking jsonResponse(JSONObject().put("ok", false).put("error", "Artifact not found"), Response.Status.NOT_FOUND)
            if (artifact.serverType != type.id) {
                return@runBlocking jsonResponse(JSONObject().put("ok", false).put("error", "Artifact belongs to another server"), Response.Status.FORBIDDEN)
            }
            if (config.accessMode == AiServerAccessMode.USERS && artifact.ownerUserId != user?.id) {
                return@runBlocking jsonResponse(JSONObject().put("ok", false).put("error", "Forbidden"), Response.Status.FORBIDDEN)
            }
            val deletedFiles = deleteServerOwnedArtifactFiles(File(artifact.path))
            db.aiServerDao().deleteArtifactById(artifact.id)
            AiServerLogStore.append(type.id, "Removed gallery item ${artifact.id}: ${artifact.title}")
            jsonResponse(JSONObject().put("ok", true).put("deletedFiles", deletedFiles))
        }

        private fun jobsJson(serverType: String): JSONObject =
            JSONObject()
                .put("ok", true)
                .put("jobs", JSONArray(AiServerJobStore.get(serverType).map(::jobToJson)))

        private fun logsJson(serverType: String): JSONObject =
            JSONObject()
                .put("ok", true)
                .put("logs", JSONArray(AiServerLogStore.get(serverType).map {
                    JSONObject().put("timestamp", it.timestamp).put("message", it.message)
                }))

        private fun mediaInfoJson(rawPath: String): JSONObject {
            val path = URLDecoder.decode(rawPath, Charsets.UTF_8.name())
            val file = File(path)
            val allowedRoots = listOf(filesDir, cacheDir).map { it.absoluteFile }
            val canonical = runCatching { file.canonicalFile }.getOrNull()
            val allowed = canonical != null && allowedRoots.any { root ->
                canonical.path.startsWith(root.canonicalPath)
            }
            if (!allowed || !file.isFile) {
                return JSONObject().put("ok", false).put("error", "File not available")
            }

            val retriever = MediaMetadataRetriever()
            return runCatching {
                retriever.setDataSource(file.absolutePath)
                val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                JSONObject()
                    .put("ok", true)
                    .put("path", file.absolutePath)
                    .put("name", file.name)
                    .put("mimeType", mimeForFile(file))
                    .put("sizeBytes", file.length())
                    .put("width", width)
                    .put("height", height)
                    .put("durationSeconds", durationMs / 1000.0)
            }.getOrElse { error ->
                JSONObject()
                    .put("ok", false)
                    .put("error", error.message ?: "Could not read media info")
            }.also {
                runCatching { retriever.release() }
            }
        }

        private fun serveMedia(rawPath: String): Response {
            val path = URLDecoder.decode(rawPath, Charsets.UTF_8.name())
            val file = File(path)
            val allowedRoots = listOf(filesDir, cacheDir).map { it.absoluteFile }
            val canonical = file.canonicalFile
            val allowed = allowedRoots.any { root -> canonical.path.startsWith(root.canonicalPath) }
            if (!allowed || !file.isFile) {
                return jsonResponse(JSONObject().put("ok", false).put("error", "File not available"), Response.Status.NOT_FOUND)
            }
            val mime = when (file.extension.lowercase(Locale.US)) {
                "png" -> "image/png"
                "jpg", "jpeg" -> "image/jpeg"
                "mp4" -> "video/mp4"
                "wav" -> "audio/wav"
                "mp3" -> "audio/mpeg"
                "json" -> "application/json"
                "jsonl" -> "application/x-ndjson"
                "txt", "srt", "vtt", "md" -> "text/plain"
                "pdf" -> "application/pdf"
                "cbz" -> "application/vnd.comicbook+zip"
                else -> "application/octet-stream"
            }
            return newFixedLengthResponse(Response.Status.OK, mime, file.inputStream(), file.length())
        }

        private fun deleteServerOwnedArtifactFiles(file: File): Int {
            val canonical = runCatching { file.canonicalFile }.getOrNull() ?: return 0
            val roots = listOf(
                File(filesDir, "ai_server_artifacts"),
                File(filesDir, "sd_output"),
                File(filesDir, "onnx_image_output"),
                File(cacheDir, "ai_server_uploads")
            ).mapNotNull { runCatching { it.canonicalFile }.getOrNull() }
            val allowed = roots.any { root ->
                canonical.path == root.path || canonical.path.startsWith("${root.path}${File.separator}")
            }
            if (!allowed) return 0
            val candidates = listOf(
                canonical,
                File(canonical.parentFile, "${canonical.name}.ai_server.json"),
                File(canonical.parentFile, "${canonical.nameWithoutExtension}.json"),
                File(canonical.parentFile, "${canonical.nameWithoutExtension}.mask.png"),
                File(canonical.parentFile, "${canonical.nameWithoutExtension}.preview.png")
            )
            return candidates.distinctBy { it.absolutePath }.count { candidate ->
                runCatching { candidate.isFile && candidate.delete() }.getOrDefault(false)
            }
        }

        private fun serveQr(rawData: String): Response {
            val data = URLDecoder.decode(rawData, Charsets.UTF_8.name()).ifBlank { "http://127.0.0.1:${config.port}" }
            val bitmap = qrBitmap(data, 180)
            val output = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
            val bytes = output.toByteArray()
            return newFixedLengthResponse(Response.Status.OK, "image/png", ByteArrayInputStream(bytes), bytes.size.toLong())
        }
    }

    private fun indexHtml(type: AiServerType): String =
        readAsset("ai_servers_webui/index.html")
            .replace("__SERVER_TYPE__", type.id)
            .replace("__SERVER_NAME__", type.displayName)
            .replace("__SERVER_EMOJI__", type.emoji)

    private fun assetResponse(path: String, mime: String): Response =
        NanoHTTPD.newFixedLengthResponse(Response.Status.OK, mime, readAsset(path))

    private fun htmlResponse(html: String): Response =
        NanoHTTPD.newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", html)

    private fun jsonResponse(json: JSONObject, status: Response.Status = Response.Status.OK): Response =
        NanoHTTPD.newFixedLengthResponse(status, "application/json; charset=utf-8", json.toString())

    private fun readAsset(path: String): String =
        assets.open(path).bufferedReader().use { it.readText() }

    private fun readJsonBody(session: NanoHTTPD.IHTTPSession): JSONObject {
        val files = HashMap<String, String>()
        session.parseBody(files)
        val raw = files["postData"].orEmpty()
        return if (raw.isBlank()) JSONObject() else JSONObject(raw)
    }

    private fun queryParam(session: NanoHTTPD.IHTTPSession, key: String): String =
        session.parameters[key]?.firstOrNull().orEmpty()

    private fun parseOnnxBackend(value: String): OnnxRuntimeBackend =
        OnnxRuntimeBackend.entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
            ?: OnnxRuntimeBackend.CPU

    private fun parseOnnxRuntimeOptions(body: JSONObject): OnnxRuntimeOptions =
        OnnxRuntimeOptions(
            runtimeThreadCount = body.optPositiveIntOrNull("runtimeThreadCount"),
            graphOptimizationLevel = parseEnum(body.optString("graphOptimizationLevel"), OnnxGraphOptimizationLevel.ALL),
            unetBackendOverride = parseEnum(body.optString("unetBackendOverride"), OnnxBackendOverride.DEFAULT),
            vaeDecoderBackendOverride = parseEnum(body.optString("vaeDecoderBackendOverride"), OnnxBackendOverride.DEFAULT),
            vaeEncoderBackendOverride = parseEnum(body.optString("vaeEncoderBackendOverride"), OnnxBackendOverride.DEFAULT),
            intraOpThreads = body.optPositiveIntOrNull("intraOpThreads"),
            interOpThreads = body.optPositiveIntOrNull("interOpThreads"),
            executionMode = parseEnum(body.optString("executionMode"), OnnxExecutionMode.SEQUENTIAL),
            memoryPatternOptimization = body.optBoolean("memoryPatternOptimization", true),
            cpuArenaAllocator = body.optBoolean("cpuArenaAllocator", true),
            nnapiCpuDisabled = body.optBoolean("nnapiCpuDisabled", true),
            nnapiUseFp16 = body.optBoolean("nnapiUseFp16", false)
        )

    private inline fun <reified T : Enum<T>> parseEnum(value: String, defaultValue: T): T =
        enumValues<T>().firstOrNull { it.name.equals(value, ignoreCase = true) } ?: defaultValue

    private inline fun <reified T : Enum<T>> parseEnumOrNull(value: String): T? =
        enumValues<T>().firstOrNull { it.name.equals(value, ignoreCase = true) }

    private fun JSONObject.optDoubleOrNull(key: String): Double? =
        if (has(key) && !isNull(key)) optDouble(key) else null

    private fun JSONObject.optPositiveIntOrNull(key: String): Int? =
        if (has(key) && !isNull(key)) optInt(key).takeIf { it > 0 } else null

    private fun JSONObject.optNullableString(key: String): String? =
        optString(key).trim().ifBlank { null }

    private fun JSONObject.optFlexibleLong(key: String): Long? {
        if (!has(key) || isNull(key)) return null
        val value = opt(key)
        val parsed = when (value) {
            is Number -> value.toLong()
            is String -> value.trim().toLongOrNull()
            else -> null
        }
        return parsed?.takeIf { it > 0L }
    }

    private fun imageOutputFile(engine: String, mode: String, jobId: String): File {
        val folder = when {
            engine == "onnx" -> File(filesDir, "onnx_image_output/$mode")
            mode == "img2img" -> File(filesDir, "sd_output/img2img")
            mode == "upscale" -> File(filesDir, "sd_output/upscaled")
            else -> File(filesDir, "sd_output/txt2img")
        }.apply { mkdirs() }
        return File(folder, "server_${jobId}_${timestamp()}.png")
    }

    private fun writeArtifactSidecar(file: File, metadata: JSONObject) {
        runCatching {
            file.parentFile?.mkdirs()
            File(file.parentFile, "${file.name}.ai_server.json").writeText(metadata.toString(2))
        }
    }

    private fun recordArtifact(
        serverType: String,
        ownerUserId: Long?,
        jobId: String,
        artifactType: String,
        file: File,
        mimeType: String,
        title: String,
        metadata: JSONObject
    ) {
        serviceScope.launch {
            db.aiServerDao().upsertArtifact(
                AiServerArtifactEntity(
                    serverType = serverType,
                    ownerUserId = ownerUserId,
                    origin = "SERVER",
                    jobId = jobId,
                    artifactType = artifactType,
                    path = file.absolutePath,
                    mimeType = mimeType,
                    title = title,
                    metadataJson = metadata.toString(),
                    createdAt = System.currentTimeMillis()
                )
            )
        }
    }

    private fun jobToJson(job: AiServerJob): JSONObject =
        JSONObject()
            .put("id", job.id)
            .put("serverType", job.serverType)
            .put("title", job.title)
            .put("status", job.status)
            .put("progress", job.progress.toDouble())
            .put("message", job.message)
            .put("errorMessage", job.errorMessage)
            .put("action", job.action)
            .put("params", job.paramsJson?.let { runCatching { JSONObject(it) }.getOrNull() } ?: JSONObject.NULL)
            .put("canRetry", job.status == "FAILED" && !job.action.isNullOrBlank() && !job.paramsJson.isNullOrBlank())
            .put("createdAt", job.createdAt)
            .put("updatedAt", job.updatedAt)
            .put("artifactPath", job.artifactPath)

    private fun artifactToJson(artifact: AiServerArtifactEntity): JSONObject =
        JSONObject()
            .put("id", artifact.id)
            .put("serverType", artifact.serverType)
            .put("ownerUserId", artifact.ownerUserId ?: JSONObject.NULL)
            .put("artifactType", artifact.artifactType)
            .put("title", artifact.title)
            .put("mimeType", artifact.mimeType)
            .put("createdAt", artifact.createdAt)
            .put("path", artifact.path)
            .put("url", "/api/media?path=${java.net.URLEncoder.encode(artifact.path, Charsets.UTF_8.name())}")
            .put("metadata", artifact.metadataJson?.let { runCatching { JSONObject(it) }.getOrNull() })

    private fun safeFileName(value: String): String =
        value.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "upload" }

    private fun timestamp(): String =
        SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())

    private fun qrBitmap(content: String, size: Int): Bitmap {
        val bitMatrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
        for (x in 0 until size) {
            for (y in 0 until size) {
                bitmap.setPixel(x, y, if (bitMatrix[x, y]) Color.BLACK else Color.WHITE)
            }
        }
        return bitmap
    }

    private fun sessionSecret(): String {
        val prefs = getSharedPreferences("ai_server_auth", Context.MODE_PRIVATE)
        val existing = prefs.getString("session_secret", null)
        if (!existing.isNullOrBlank()) return existing
        val created = AiServerAuth.createSessionToken()
        prefs.edit().putString("session_secret", created).apply()
        return created
    }

    companion object {
        private const val SESSION_COOKIE = "ADT_AI_SERVER_SESSION"
        private const val SESSION_DURATION_MS = 7L * 24L * 60L * 60L * 1000L
        private val _runtimeStates = MutableStateFlow<List<AiServerRuntimeState>>(emptyList())
        val runtimeStates = _runtimeStates.asStateFlow()

        suspend fun ensureDefaultConfigs(context: Context) {
            val db = AppDatabase.getDatabase(context)
            val existing = db.aiServerDao().getConfigs().map { it.serverType }.toSet()
            val missing = AiServerType.defaultConfigs().filterNot { it.serverType in existing }
            if (missing.isNotEmpty()) {
                db.aiServerDao().upsertConfigs(missing)
            }
        }

        fun start(context: Context) {
            context.startForegroundService(Intent(context, AiToolServerService::class.java))
        }
    }
}
