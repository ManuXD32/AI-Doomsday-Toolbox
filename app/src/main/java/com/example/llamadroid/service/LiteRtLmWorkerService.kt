package com.example.llamadroid.service

import android.app.Application
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Process
import com.example.llamadroid.R
import com.example.llamadroid.data.model.LITERT_BACKEND_GPU
import com.example.llamadroid.data.model.LiteRtModelEntity
import com.example.llamadroid.data.model.LlamaChatEntity
import com.example.llamadroid.data.model.LlamaMessageEntity
import com.example.llamadroid.data.model.supportsLiteRtAudio
import com.example.llamadroid.data.model.supportsLiteRtVision
import com.example.llamadroid.util.DebugLog
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal object LiteRtLmWorkerProtocol {
    const val MSG_START = 1
    const val MSG_STATUS = 2
    const val MSG_CHUNK = 3
    const val MSG_DONE = 4
    const val MSG_ERROR = 5
    const val MSG_LOG = 6
    const val MSG_DOCTOR = 7
    const val MSG_THINKING = 8
    const val MSG_CACHE_STATUS = 9
    const val MSG_CACHE_UNLOAD = 10

    const val KEY_REQUEST_ID = "request_id"
    const val KEY_REQUEST_JSON = "request_json"
    const val KEY_TEXT = "text"
    const val KEY_STATS_JSON = "stats_json"
}

class LiteRtLmWorkerService : Service() {
    private val gson = Gson()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val inbound = Messenger(
        Handler(Looper.getMainLooper()) { message ->
            if (message.what == LiteRtLmWorkerProtocol.MSG_START) {
                handleStart(message)
                true
            } else if (message.what == LiteRtLmWorkerProtocol.MSG_DOCTOR) {
                handleDoctor(message)
                true
            } else if (message.what == LiteRtLmWorkerProtocol.MSG_CACHE_STATUS) {
                handleCacheStatus(message)
                true
            } else if (message.what == LiteRtLmWorkerProtocol.MSG_CACHE_UNLOAD) {
                handleCacheUnload(message)
                true
            } else {
                false
            }
        }
    )

    override fun onBind(intent: Intent?): IBinder = inbound.binder

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun handleStart(message: Message) {
        val replyTo = message.replyTo ?: return
        val requestId = message.data.getString(LiteRtLmWorkerProtocol.KEY_REQUEST_ID).orEmpty()
        val requestJson = message.data.getString(LiteRtLmWorkerProtocol.KEY_REQUEST_JSON)
        if (requestId.isBlank() || requestJson.isNullOrBlank()) {
            replyTo.sendWorkerMessage(
                what = LiteRtLmWorkerProtocol.MSG_ERROR,
                requestId = requestId,
                text = getString(R.string.litert_error_worker_bad_request)
            )
            return
        }

        serviceScope.launch {
            try {
                replyTo.sendWorkerMessage(
                    what = LiteRtLmWorkerProtocol.MSG_LOG,
                    requestId = requestId,
                    text = "worker request received"
                )
                val request = parseRequest(requestJson).let { parsed ->
                    parsed.copy(backendMode = parsed.backendMode.ifBlank { LITERT_BACKEND_GPU })
                }
                replyTo.sendWorkerMessage(
                    what = LiteRtLmWorkerProtocol.MSG_LOG,
                    requestId = requestId,
                    text = "request parsed backend=${request.backendMode} model=${request.model.displayName}"
                )
                LiteRtWorkerRequestDiagnostics.describe(applicationContext, request).forEach { line ->
                    replyTo.sendWorkerMessage(
                        what = LiteRtLmWorkerProtocol.MSG_LOG,
                        requestId = requestId,
                        text = line
                    )
                }
                val stats = LiteRtLmChatService(
                    context = applicationContext,
                    allowGpuBackend = true,
                    onDiagnostic = { diagnostic ->
                        replyTo.sendWorkerMessage(
                            what = LiteRtLmWorkerProtocol.MSG_LOG,
                            requestId = requestId,
                            text = diagnostic
                        )
                    }
                ).streamChat(
                    request = request,
                    onStatus = { status ->
                        replyTo.sendWorkerMessage(
                            what = LiteRtLmWorkerProtocol.MSG_STATUS,
                            requestId = requestId,
                            text = status
                        )
                    },
                    onChunk = { chunk ->
                        replyTo.sendWorkerMessage(
                            what = LiteRtLmWorkerProtocol.MSG_CHUNK,
                            requestId = requestId,
                            text = chunk
                        )
                    },
                    onThinkingChunk = { chunk ->
                        replyTo.sendWorkerMessage(
                            what = LiteRtLmWorkerProtocol.MSG_THINKING,
                            requestId = requestId,
                            text = chunk
                        )
                    }
                )
                replyTo.sendWorkerMessage(
                    what = LiteRtLmWorkerProtocol.MSG_DONE,
                    requestId = requestId,
                    statsJson = gson.toJson(LiteRtLmChatStatsDto.from(stats))
                )
            } catch (e: Throwable) {
                val detail = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.name
                DebugLog.log("LiteRtLmWorkerService: backend failed: $detail")
                replyTo.sendWorkerMessage(
                    what = LiteRtLmWorkerProtocol.MSG_ERROR,
                    requestId = requestId,
                    text = detail
                )
            }
        }
    }

    private fun handleDoctor(message: Message) {
        val replyTo = message.replyTo ?: return
        val requestId = message.data.getString(LiteRtLmWorkerProtocol.KEY_REQUEST_ID).orEmpty()
        val requestJson = message.data.getString(LiteRtLmWorkerProtocol.KEY_REQUEST_JSON)
        if (requestId.isBlank() || requestJson.isNullOrBlank()) {
            replyTo.sendWorkerMessage(
                what = LiteRtLmWorkerProtocol.MSG_ERROR,
                requestId = requestId,
                text = getString(R.string.litert_error_worker_bad_request)
            )
            return
        }

        serviceScope.launch {
            val startedAt = System.currentTimeMillis()
            var phase = "request received"
            val dto = runCatching {
                gson.fromJson(requestJson, LiteRtLmDoctorRequestDto::class.java)
            }.getOrNull()
            if (dto?.model == null || dto.backendMode.isNullOrBlank()) {
                replyTo.sendWorkerMessage(
                    what = LiteRtLmWorkerProtocol.MSG_ERROR,
                    requestId = requestId,
                    text = getString(R.string.litert_error_worker_bad_request)
                )
                return@launch
            }
            val model = dto.model
            val backend = dto.backendMode
            fun log(message: String) {
                phase = message
                replyTo.sendWorkerMessage(
                    what = LiteRtLmWorkerProtocol.MSG_LOG,
                    requestId = requestId,
                    text = "doctor $backend: $message"
                )
            }
            try {
                log("building request")
                val request = LiteRtLmChatRequest(
                    model = model,
                    chat = LlamaChatEntity(
                        title = "LiteRT backend doctor",
                        contextSize = 128,
                        systemPrompt = "You are a backend smoke test. Reply briefly."
                    ),
                    history = emptyList(),
                    backendMode = backend,
                    params = mapOf(
                        "enable_thinking" to false,
                        "top_k" to 1,
                        "top_p" to 0.1,
                        "temperature" to 0.0,
                        "seed" to 1
                    ),
                    promptOverride = "Reply with exactly: OK"
                )
                val rendered = StringBuilder()
                log("starting backend smoke test")
                val stats = LiteRtLmChatService(
                    context = applicationContext,
                    allowGpuBackend = true,
                    onDiagnostic = { diagnostic -> log(diagnostic) }
                ).streamChat(
                    request = request,
                    onStatus = { status -> log("status: $status") },
                    onChunk = { chunk -> rendered.append(chunk) },
                    onThinkingChunk = {}
                )
                val result = LiteRtBackendDoctorResult.create(
                    context = applicationContext,
                    model = model,
                    backend = backend,
                    success = true,
                    phase = "completed",
                    detail = "Smoke response: ${rendered.toString().take(120)}",
                    processExit = null,
                    startedAt = startedAt,
                    durationMs = System.currentTimeMillis() - startedAt,
                    stats = stats
                )
                replyTo.sendWorkerMessage(
                    what = LiteRtLmWorkerProtocol.MSG_DONE,
                    requestId = requestId,
                    statsJson = gson.toJson(result)
                )
            } catch (e: Throwable) {
                val detail = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.name
                val result = LiteRtBackendDoctorResult.create(
                    context = applicationContext,
                    model = model,
                    backend = backend,
                    success = false,
                    phase = phase,
                    detail = detail,
                    processExit = null,
                    startedAt = startedAt,
                    durationMs = System.currentTimeMillis() - startedAt
                )
                replyTo.sendWorkerMessage(
                    what = LiteRtLmWorkerProtocol.MSG_DONE,
                    requestId = requestId,
                    statsJson = gson.toJson(result)
                )
            }
        }
    }

    private fun handleCacheStatus(message: Message) {
        handleCacheCommand(message) { service, request ->
            service.isEngineLoaded(
                model = request.model,
                backendMode = request.backendMode,
                contextSize = request.chat.contextSize,
                mtpEnabled = (request.params[LITERT_PARAM_MTP_ENABLED] as? Boolean) ?: false
            ).toString()
        }
    }

    private fun handleCacheUnload(message: Message) {
        handleCacheCommand(message) { service, request ->
            service.unloadEngines(
                model = request.model,
                backendMode = request.backendMode,
                contextSize = request.chat.contextSize,
                mtpEnabled = (request.params[LITERT_PARAM_MTP_ENABLED] as? Boolean) ?: false
            ).toString()
        }
    }

    private fun handleCacheCommand(
        message: Message,
        block: (LiteRtLmChatService, LiteRtLmChatRequest) -> String
    ) {
        val replyTo = message.replyTo ?: return
        val requestId = message.data.getString(LiteRtLmWorkerProtocol.KEY_REQUEST_ID).orEmpty()
        val requestJson = message.data.getString(LiteRtLmWorkerProtocol.KEY_REQUEST_JSON)
        if (requestId.isBlank() || requestJson.isNullOrBlank()) {
            replyTo.sendWorkerMessage(
                what = LiteRtLmWorkerProtocol.MSG_ERROR,
                requestId = requestId,
                text = getString(R.string.litert_error_worker_bad_request)
            )
            return
        }
        serviceScope.launch {
            try {
                val request = parseRequest(requestJson).copy(backendMode = LITERT_BACKEND_GPU)
                val service = LiteRtLmChatService(applicationContext, allowGpuBackend = true)
                replyTo.sendWorkerMessage(
                    what = LiteRtLmWorkerProtocol.MSG_DONE,
                    requestId = requestId,
                    text = block(service, request)
                )
            } catch (e: Throwable) {
                replyTo.sendWorkerMessage(
                    what = LiteRtLmWorkerProtocol.MSG_ERROR,
                    requestId = requestId,
                    text = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.name
                )
            }
        }
    }

    private fun parseRequest(requestJson: String): LiteRtLmChatRequest {
        val dto = gson.fromJson(requestJson, LiteRtLmWorkerRequestDto::class.java)
            ?: throw IllegalArgumentException(getString(R.string.litert_error_worker_bad_request))
        return dto.toChatRequest()
    }
}

private data class LiteRtLmWorkerRequestDto(
    @SerializedName("model") val model: LiteRtModelEntity? = null,
    @SerializedName("chat") val chat: LlamaChatEntity? = null,
    @SerializedName("history") val history: List<LlamaMessageEntity>? = null,
    @SerializedName("backend_mode") val backendMode: String? = null,
    @SerializedName("params") val params: Map<String, Any>? = null,
    @SerializedName("prompt_override") val promptOverride: String? = null,
    @SerializedName("conversation_override") val conversationOverride: LiteRtConversationOverrideDto? = null
) {
    fun toChatRequest(): LiteRtLmChatRequest = LiteRtLmChatRequest(
        model = requireNotNull(model) { "Missing LiteRT model in worker request." },
        chat = requireNotNull(chat) { "Missing chat in worker request." },
        history = history.orEmpty(),
        backendMode = backendMode ?: LITERT_BACKEND_GPU,
        params = params.orEmpty(),
        promptOverride = promptOverride,
        conversationOverride = conversationOverride?.toConversationOverride()
    )

    companion object {
        fun from(request: LiteRtLmChatRequest): LiteRtLmWorkerRequestDto = LiteRtLmWorkerRequestDto(
            model = request.model,
            chat = request.chat,
            history = request.history,
            backendMode = request.backendMode,
            params = request.params,
            promptOverride = request.promptOverride,
            conversationOverride = request.conversationOverride?.let(LiteRtConversationOverrideDto::from)
        )
    }
}

private data class LiteRtConversationOverrideDto(
    @SerializedName("system_instruction") val systemInstruction: String? = null,
    @SerializedName("initial_messages") val initialMessages: Array<LiteRtConversationMessageDto>? = null,
    @SerializedName("user_message") val userMessage: String? = null,
    @SerializedName("user_image_path") val userImagePath: String? = null,
    @SerializedName("user_audio_path") val userAudioPath: String? = null,
    @SerializedName("tools") val tools: Array<LiteRtToolDefinitionDto>? = null
) {
    fun toConversationOverride(): LiteRtConversationOverride = LiteRtConversationOverride(
        systemInstruction = systemInstruction.orEmpty(),
        initialMessages = initialMessages.orEmpty().map { it.toConversationMessage() },
        userMessage = userMessage.orEmpty(),
        userImagePath = userImagePath,
        userAudioPath = userAudioPath,
        tools = tools.orEmpty().map { it.toToolDefinition() }
    )

    companion object {
        fun from(conversation: LiteRtConversationOverride): LiteRtConversationOverrideDto =
            LiteRtConversationOverrideDto(
                systemInstruction = conversation.systemInstruction,
                initialMessages = conversation.initialMessages
                    .map(LiteRtConversationMessageDto::from)
                    .toTypedArray(),
                userMessage = conversation.userMessage,
                userImagePath = conversation.userImagePath,
                userAudioPath = conversation.userAudioPath,
                tools = conversation.tools.map(LiteRtToolDefinitionDto::from).toTypedArray()
            )
    }
}

private data class LiteRtConversationMessageDto(
    @SerializedName("role") val role: String? = null,
    @SerializedName("content") val content: String? = null,
    @SerializedName("image_path") val imagePath: String? = null,
    @SerializedName("audio_path") val audioPath: String? = null,
    @SerializedName("tool_calls") val toolCalls: Array<LiteRtToolCallSpecDto>? = null,
    @SerializedName("tool_name") val toolName: String? = null
) {
    fun toConversationMessage(): LiteRtConversationMessage = LiteRtConversationMessage(
        role = role?.takeIf { it.isNotBlank() } ?: "user",
        content = content.orEmpty(),
        imagePath = imagePath,
        audioPath = audioPath,
        toolCalls = toolCalls.orEmpty().map { it.toToolCallSpec() },
        toolName = toolName
    )

    companion object {
        fun from(message: LiteRtConversationMessage): LiteRtConversationMessageDto =
            LiteRtConversationMessageDto(
                role = message.role,
                content = message.content,
                imagePath = message.imagePath,
                audioPath = message.audioPath,
                toolCalls = message.toolCalls.map(LiteRtToolCallSpecDto::from).toTypedArray(),
                toolName = message.toolName
            )
    }
}

private data class LiteRtToolDefinitionDto(
    @SerializedName("name") val name: String? = null,
    @SerializedName("description") val description: String? = null,
    @SerializedName("parameters") val parameters: Map<String, String>? = null,
    @SerializedName("required_params") val requiredParams: List<String>? = null
) {
    fun toToolDefinition(): LiteRtToolDefinition = LiteRtToolDefinition(
        name = name.orEmpty(),
        description = description.orEmpty(),
        parameters = parameters.orEmpty(),
        requiredParams = requiredParams.orEmpty()
    )

    companion object {
        fun from(tool: LiteRtToolDefinition): LiteRtToolDefinitionDto = LiteRtToolDefinitionDto(
            name = tool.name,
            description = tool.description,
            parameters = tool.parameters,
            requiredParams = tool.requiredParams
        )
    }
}

private data class LiteRtToolCallSpecDto(
    @SerializedName("name") val name: String? = null,
    @SerializedName("arguments") val arguments: Map<String, Any?>? = null
) {
    fun toToolCallSpec(): LiteRtToolCallSpec = LiteRtToolCallSpec(
        name = name.orEmpty(),
        arguments = arguments.orEmpty()
    )

    companion object {
        fun from(call: LiteRtToolCallSpec): LiteRtToolCallSpecDto = LiteRtToolCallSpecDto(
            name = call.name,
            arguments = call.arguments
        )
    }
}

private data class LiteRtLmChatStatsDto(
    @SerializedName("prompt_tokens") val promptTokens: Int = 0,
    @SerializedName("completion_tokens") val completionTokens: Int = 0,
    @SerializedName("tokens_per_second") val tokensPerSecond: Double = 0.0,
    @SerializedName("tool_calls") val toolCalls: Array<LiteRtOllamaToolCallDto>? = null,
    @SerializedName("visible_text") val visibleText: String = "",
    @SerializedName("metered_text") val meteredText: String = ""
) {
    fun toStats(): LiteRtLmChatStats = LiteRtLmChatStats(
        promptTokens = promptTokens,
        completionTokens = completionTokens,
        tokensPerSecond = tokensPerSecond,
        toolCalls = toolCalls.orEmpty().map { it.toToolCall() },
        visibleText = visibleText,
        meteredText = meteredText
    )

    companion object {
        fun from(stats: LiteRtLmChatStats): LiteRtLmChatStatsDto = LiteRtLmChatStatsDto(
            promptTokens = stats.promptTokens,
            completionTokens = stats.completionTokens,
            tokensPerSecond = stats.tokensPerSecond,
            toolCalls = stats.toolCalls.map(LiteRtOllamaToolCallDto::from).toTypedArray(),
            visibleText = stats.visibleText,
            meteredText = stats.meteredText
        )
    }
}

private data class LiteRtOllamaToolCallDto(
    @SerializedName("name") val name: String? = null,
    @SerializedName("arguments") val arguments: Map<String, Any?>? = null,
    @SerializedName("id") val id: String? = null
) {
    fun toToolCall(): OllamaService.ToolCall = OllamaService.ToolCall(
        name = name.orEmpty(),
        arguments = AgentRuntimeSupport.normalizeToolArguments(arguments),
        id = id
    )

    companion object {
        fun from(call: OllamaService.ToolCall): LiteRtOllamaToolCallDto = LiteRtOllamaToolCallDto(
            name = call.name,
            arguments = call.arguments,
            id = call.id
        )
    }
}

private data class LiteRtLmDoctorRequestDto(
    @SerializedName("model") val model: LiteRtModelEntity? = null,
    @SerializedName("backend_mode") val backendMode: String? = null
)

private object LiteRtWorkerRequestDiagnostics {
    fun describeWorkerFileForParent(file: File?): String = describeWorkerFile(file)

    fun describe(context: Context, request: LiteRtLmChatRequest): List<String> = buildList {
        val modelFile = File(request.model.path)
        val lastMessage = request.history.lastOrNull()
        add(
            "worker diag request backend=${request.backendMode} chatId=${request.chat.id} " +
                "contextSize=${request.chat.contextSize} historyCount=${request.history.size} " +
                "promptOverrideChars=${request.promptOverride?.length ?: 0} " +
                "conversationOverride=${request.conversationOverride != null}"
        )
        add(
            "worker diag params=" +
                request.params.entries
                    .sortedBy { it.key }
                    .joinToString(",") { "${it.key}:${it.value.javaClass.simpleName}=${it.value}" }
                    .truncateWorkerDiagnostic(600)
        )
        add(
            "worker diag model id=${request.model.id} display=${request.model.displayName.truncateWorkerDiagnostic(160)} " +
                "filename=${request.model.filename.truncateWorkerDiagnostic(180)} repo=${request.model.repoId ?: "-"}"
        )
        add(
            "worker diag model support cpu=${request.model.supportsCpu} gpu=${request.model.supportsGpu} " +
                "npu=${request.model.supportsNpu} vision=${request.model.supportsLiteRtVision()} " +
                "audio=${request.model.supportsLiteRtAudio()} " +
                "preference=${request.model.backendPreference} " +
                "dbSizeBytes=${request.model.sizeBytes}"
        )
        add("worker diag model path=${request.model.path.truncateWorkerDiagnostic(500)}")
        add("worker diag model sourceUri=${request.model.sourceUri.orEmpty().ifBlank { "-" }.truncateWorkerDiagnostic(500)}")
        add("worker diag model file ${describeWorkerFile(modelFile)}")
        add("worker diag model parent ${describeWorkerFile(modelFile.parentFile)}")
        add(
            "worker diag chat title=${request.chat.title.truncateWorkerDiagnostic(160)} " +
                "systemPromptChars=${request.chat.systemPrompt?.length ?: 0}"
        )
        add(
            "worker diag history userMessages=${request.history.count { it.role == "user" }} " +
                "assistantMessages=${request.history.count { it.role == "assistant" }} " +
                "systemMessages=${request.history.count { it.role == "system" }} " +
                "imageMessages=${request.history.count { !it.imagePath.isNullOrBlank() }} " +
                "audioMessages=${request.history.count { !it.audioPath.isNullOrBlank() }} " +
                "lastRole=${lastMessage?.role ?: "-"} lastChars=${lastMessage?.content?.length ?: 0} " +
                "lastImage=${!lastMessage?.imagePath.isNullOrBlank()} " +
                "lastAudio=${!lastMessage?.audioPath.isNullOrBlank()}"
        )
        add(
            "worker diag process pid=${Process.myPid()} uid=${Process.myUid()} " +
                "name=${workerProcessName(context)} is64Bit=${workerIs64Bit()} thread=${Thread.currentThread().name}"
        )
        add(
            "worker diag package=${context.packageName} cache=${context.cacheDir.absolutePath} " +
                "noBackup=${context.noBackupFilesDir.absolutePath} nativeLibraryDir=${context.applicationInfo.nativeLibraryDir}"
        )
    }

    private fun describeWorkerFile(file: File?): String {
        if (file == null) return "path=- exists=false"
        return buildString {
            append("path=${file.absolutePath.truncateWorkerDiagnostic(500)}")
            append(" exists=${runCatching { file.exists() }.getOrDefault(false)}")
            append(" isFile=${runCatching { file.isFile }.getOrDefault(false)}")
            append(" isDirectory=${runCatching { file.isDirectory }.getOrDefault(false)}")
            append(" canRead=${runCatching { file.canRead() }.getOrDefault(false)}")
            append(" canWrite=${runCatching { file.canWrite() }.getOrDefault(false)}")
            append(" length=${runCatching { if (file.isFile) file.length() else workerDirectorySize(file) }.getOrDefault(0L)}")
            append(" lastModified=${runCatching { file.lastModified() }.getOrDefault(0L)}")
        }
    }

    private fun workerDirectorySize(root: File): Long {
        if (!root.exists()) return 0L
        if (root.isFile) return root.length()
        return runCatching {
            root.walkTopDown()
                .filter { it.isFile }
                .sumOf { it.length() }
        }.getOrDefault(0L)
    }
}

private fun workerProcessName(context: Context): String {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        runCatching { Application.getProcessName() }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }
    }
    return context.applicationInfo.processName.orEmpty().ifBlank { "unknown" }
}

private fun workerIs64Bit(): String =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        Process.is64Bit().toString()
    } else {
        "unknown"
    }

private fun String.truncateWorkerDiagnostic(maxChars: Int): String {
    val sanitized = replace('\n', ' ')
    if (sanitized.length <= maxChars) return sanitized
    val head = (maxChars / 2).coerceAtLeast(1)
    val tail = (maxChars - head - 3).coerceAtLeast(1)
    return "${sanitized.take(head)}...${sanitized.takeLast(tail)}"
}

internal class LiteRtLmWorkerCrashedException(
    message: String,
    val requestId: String,
    val workerLabel: String,
    val backendMode: String,
    val contextSize: Int,
    val mtpEnabled: Boolean,
    val lastPhase: String,
    val recentExit: String?,
    val elapsedMs: Long
) : IllegalStateException(message) {
    val diedBeforeEngineInitialized: Boolean =
        !lastPhase.contains("Engine initialized", ignoreCase = true)

    fun diagnosticDetail(): String = buildString {
        append(message ?: javaClass.name)
        append(" requestId=")
        append(requestId)
        append(" backend=")
        append(backendMode)
        append(" contextSize=")
        append(contextSize)
        append(" mtp=")
        append(mtpEnabled)
        append(" elapsedMs=")
        append(elapsedMs)
        append(" lastPhase=")
        append(lastPhase.truncateWorkerDiagnostic(240))
        recentExit?.takeIf { it.isNotBlank() }?.let { exit ->
            append(" recentExit=")
            append(exit.truncateWorkerDiagnostic(300))
        }
    }
}

internal class LiteRtLmWorkerClient(private val context: Context) {
    private val gson = Gson()

    suspend fun streamGpuChat(
        request: LiteRtLmChatRequest,
        onStatus: suspend (String) -> Unit,
        onChunk: suspend (String) -> Unit,
        onThinkingChunk: suspend (String) -> Unit = {}
    ): LiteRtLmChatStats = streamBackendChat(
        request = request.copy(backendMode = LITERT_BACKEND_GPU),
        workerLabel = "GPU",
        workerCrashedMessage = context.getString(R.string.litert_error_gpu_worker_crashed),
        onStatus = onStatus,
        onChunk = onChunk,
        onThinkingChunk = onThinkingChunk
    )

    suspend fun runBackendDoctor(
        model: LiteRtModelEntity,
        backendMode: String
    ): LiteRtBackendDoctorResult = streamDoctor(
        model = model,
        backendMode = backendMode
    )

    suspend fun isGpuEngineLoaded(request: LiteRtLmChatRequest): Boolean =
        streamCacheCommand(
            request = request.copy(backendMode = LITERT_BACKEND_GPU),
            messageType = LiteRtLmWorkerProtocol.MSG_CACHE_STATUS
        ).toBooleanStrictOrNull() ?: false

    suspend fun unloadGpuEngines(request: LiteRtLmChatRequest): Int =
        streamCacheCommand(
            request = request.copy(backendMode = LITERT_BACKEND_GPU),
            messageType = LiteRtLmWorkerProtocol.MSG_CACHE_UNLOAD
        ).toIntOrNull() ?: 0

    suspend fun runInAppGpuParityDoctor(model: LiteRtModelEntity): LiteRtBackendDoctorResult = withContext(Dispatchers.Default) {
        val appContext = context.applicationContext
        val startedAt = System.currentTimeMillis()
        val diagnostics = mutableListOf<String>()
        var phase = "building in-app GPU parity request"
        fun record(message: String) {
            phase = message
            diagnostics += message
            DebugLog.log("LiteRT parity doctor: $message")
        }

        try {
            val request = LiteRtLmChatRequest(
                model = model,
                chat = LlamaChatEntity(
                    title = "LiteRT in-app GPU parity doctor",
                    contextSize = 128,
                    systemPrompt = "You are a backend smoke test. Reply briefly."
                ),
                history = emptyList(),
                backendMode = LITERT_BACKEND_GPU,
                params = mapOf(
                    "enable_thinking" to false,
                    "top_k" to 1,
                    "top_p" to 0.1,
                    "temperature" to 0.0,
                    "seed" to 1
                ),
                promptOverride = "Reply with exactly: OK"
            )
            val rendered = StringBuilder()
            record("starting Gallery-style in-app GPU parity smoke test")
            val stats = LiteRtLmChatService(
                context = appContext,
                allowGpuBackend = true,
                onDiagnostic = ::record
            ).streamGalleryStyleGpuChat(
                request = request,
                onStatus = { status -> record("status: $status") },
                onChunk = { chunk -> rendered.append(chunk) },
                onThinkingChunk = {}
            )
            LiteRtBackendDoctorResult.create(
                context = appContext,
                model = model,
                backend = LITERT_BACKEND_GPU_PARITY,
                success = true,
                phase = "completed",
                detail = appContext.getString(
                    R.string.litert_doctor_parity_detail,
                    rendered.toString().take(120),
                    diagnostics.takeLast(24).joinToString("\n")
                ),
                processExit = null,
                startedAt = startedAt,
                durationMs = System.currentTimeMillis() - startedAt,
                stats = stats
            )
        } catch (e: Throwable) {
            val detail = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.name
            LiteRtBackendDoctorResult.create(
                context = appContext,
                model = model,
                backend = LITERT_BACKEND_GPU_PARITY,
                success = false,
                phase = phase,
                detail = appContext.getString(
                    R.string.litert_doctor_parity_failed_detail,
                    detail,
                    diagnostics.takeLast(24).joinToString("\n")
                ),
                processExit = null,
                startedAt = startedAt,
                durationMs = System.currentTimeMillis() - startedAt
            )
        }
    }

    private suspend fun streamBackendChat(
        request: LiteRtLmChatRequest,
        workerLabel: String,
        workerCrashedMessage: String,
        onStatus: suspend (String) -> Unit,
        onChunk: suspend (String) -> Unit,
        onThinkingChunk: suspend (String) -> Unit
    ): LiteRtLmChatStats = coroutineScope {
        val appContext = context.applicationContext
        val startedAt = System.currentTimeMillis()
        val requestId = "${System.currentTimeMillis()}-${request.model.id}-${request.chat.id}"
        val serviceMessenger = CompletableDeferred<Messenger>()
        val events = Channel<WorkerEvent>(Channel.UNLIMITED)
        var lastPhase = "binding worker"
        var recentExitSummary: String? = null
        fun logRecentWorkerExit(callback: String): String? {
            val elapsedMs = System.currentTimeMillis() - startedAt
            val modelFile = File(request.model.path)
            DebugLog.log(
                "LiteRT worker: $workerLabel worker $callback context requestId=$requestId " +
                    "elapsedMs=$elapsedMs modelId=${request.model.id} model=${request.model.displayName} " +
                    "backend=${request.backendMode} chatId=${request.chat.id} lastPhase=${lastPhase.truncateWorkerDiagnostic(180)}"
            )
            DebugLog.log(
                "LiteRT worker: $workerLabel worker $callback mainProcess pid=${Process.myPid()} " +
                    "uid=${Process.myUid()} name=${workerProcessName(appContext)} " +
                    "is64Bit=${workerIs64Bit()} thread=${Thread.currentThread().name}"
            )
            DebugLog.log(
                "LiteRT worker: $workerLabel worker $callback requestSummary " +
                    "contextSize=${request.chat.contextSize} historyCount=${request.history.size} " +
                    "promptOverrideChars=${request.promptOverride?.length ?: 0} " +
                    "params=${request.params.entries.sortedBy { it.key }.joinToString(",") { "${it.key}=${it.value}" }.truncateWorkerDiagnostic(500)}"
            )
            DebugLog.log(
                "LiteRT worker: $workerLabel worker $callback modelFile ${LiteRtWorkerRequestDiagnostics.describeWorkerFileForParent(modelFile)}"
            )
            val summary = GenerationDiagnosticsStore.describeRecentProcessExit(
                processNameSuffix = ":litert_lm",
                sinceTimestamp = startedAt - 2_000L
            )
            recentExitSummary = summary
            if (summary.isNullOrBlank()) {
                DebugLog.log("LiteRT worker: $workerLabel worker $callback; no recent worker exit info available")
            } else {
                DebugLog.log("LiteRT worker: $workerLabel worker $callback; recent exit=$summary")
            }
            return summary
        }
        fun workerCrashException(): LiteRtLmWorkerCrashedException {
            return LiteRtLmWorkerCrashedException(
                message = workerCrashedMessage,
                requestId = requestId,
                workerLabel = workerLabel,
                backendMode = request.backendMode,
                contextSize = request.chat.contextSize,
                mtpEnabled = (request.params[LITERT_PARAM_MTP_ENABLED] as? Boolean) ?: false,
                lastPhase = lastPhase,
                recentExit = recentExitSummary,
                elapsedMs = System.currentTimeMillis() - startedAt
            )
        }
        val replyMessenger = Messenger(
            Handler(Looper.getMainLooper()) { message ->
                val eventRequestId = message.data.getString(LiteRtLmWorkerProtocol.KEY_REQUEST_ID).orEmpty()
                if (eventRequestId != requestId) return@Handler true
                when (message.what) {
                    LiteRtLmWorkerProtocol.MSG_STATUS -> {
                        events.trySend(WorkerEvent.Status(message.data.getString(LiteRtLmWorkerProtocol.KEY_TEXT).orEmpty()))
                    }
                    LiteRtLmWorkerProtocol.MSG_CHUNK -> {
                        events.trySend(WorkerEvent.Chunk(message.data.getString(LiteRtLmWorkerProtocol.KEY_TEXT).orEmpty()))
                    }
                    LiteRtLmWorkerProtocol.MSG_THINKING -> {
                        events.trySend(WorkerEvent.Thinking(message.data.getString(LiteRtLmWorkerProtocol.KEY_TEXT).orEmpty()))
                    }
                    LiteRtLmWorkerProtocol.MSG_DONE -> {
                        val statsJson = message.data.getString(LiteRtLmWorkerProtocol.KEY_STATS_JSON).orEmpty()
                        events.trySend(
                            WorkerEvent.Done(
                                gson.fromJson(statsJson, LiteRtLmChatStatsDto::class.java).toStats()
                            )
                        )
                    }
                    LiteRtLmWorkerProtocol.MSG_ERROR -> {
                        events.trySend(WorkerEvent.Error(message.data.getString(LiteRtLmWorkerProtocol.KEY_TEXT).orEmpty()))
                    }
                    LiteRtLmWorkerProtocol.MSG_LOG -> {
                        val text = message.data.getString(LiteRtLmWorkerProtocol.KEY_TEXT).orEmpty()
                        if (text.isNotBlank()) {
                            lastPhase = text
                            DebugLog.log("LiteRT worker: $text")
                        }
                    }
                }
                true
            }
        )
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                if (service == null) {
                    serviceMessenger.completeExceptionally(
                        IllegalStateException(appContext.getString(R.string.litert_error_worker_bind))
                    )
                } else {
                    serviceMessenger.complete(Messenger(service))
                }
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                logRecentWorkerExit("disconnected")
                serviceMessenger.completeExceptionally(
                    workerCrashException()
                )
                events.trySend(WorkerEvent.Crashed(workerCrashException()))
            }

            override fun onBindingDied(name: ComponentName?) {
                logRecentWorkerExit("binding died")
                serviceMessenger.completeExceptionally(
                    workerCrashException()
                )
                events.trySend(WorkerEvent.Crashed(workerCrashException()))
            }

            override fun onNullBinding(name: ComponentName?) {
                serviceMessenger.completeExceptionally(
                    IllegalStateException(appContext.getString(R.string.litert_error_worker_bind))
                )
                events.trySend(WorkerEvent.Error(appContext.getString(R.string.litert_error_worker_bind)))
            }
        }

        var bound = false
        var finishedStats: LiteRtLmChatStats? = null
        try {
            val intent = Intent(appContext, LiteRtLmWorkerService::class.java)
            bound = appContext.bindService(intent, connection, Context.BIND_AUTO_CREATE)
            if (!bound) {
                throw IllegalStateException(appContext.getString(R.string.litert_error_worker_bind))
            }
            val remote = serviceMessenger.await()
            remote.send(
                Message.obtain(null, LiteRtLmWorkerProtocol.MSG_START).apply {
                    replyTo = replyMessenger
                    data = Bundle().apply {
                        putString(LiteRtLmWorkerProtocol.KEY_REQUEST_ID, requestId)
                        putString(
                            LiteRtLmWorkerProtocol.KEY_REQUEST_JSON,
                            gson.toJson(
                                LiteRtLmWorkerRequestDto.from(request)
                            )
                        )
                    }
                }
            )

            while (finishedStats == null) {
                currentCoroutineContext().ensureActive()
                when (val event = events.receive()) {
                    is WorkerEvent.Status -> onStatus(event.text)
                    is WorkerEvent.Chunk -> onChunk(event.text)
                    is WorkerEvent.Thinking -> onThinkingChunk(event.text)
                    is WorkerEvent.Done -> finishedStats = event.stats
                    is WorkerEvent.Error -> {
                        val errorText = event.text.ifBlank { workerCrashedMessage }
                        if (errorText == workerCrashedMessage) {
                            logRecentWorkerExit("reported crash")
                            throw workerCrashException()
                        }
                        throw IllegalStateException(errorText)
                    }
                    is WorkerEvent.Crashed -> throw event.error
                }
            }
        } finally {
            events.close()
            if (bound) {
                runCatching { appContext.unbindService(connection) }
            }
        }
        finishedStats ?: throw workerCrashException()
    }

    private suspend fun streamCacheCommand(
        request: LiteRtLmChatRequest,
        messageType: Int
    ): String = withContext(Dispatchers.Default) {
        val appContext = context.applicationContext
        val requestId = "cache-${System.currentTimeMillis()}-${request.model.id}-${request.chat.contextSize}"
        val serviceMessenger = CompletableDeferred<Messenger>()
        val result = CompletableDeferred<String>()
        val replyMessenger = Messenger(
            Handler(Looper.getMainLooper()) { message ->
                val eventRequestId = message.data.getString(LiteRtLmWorkerProtocol.KEY_REQUEST_ID).orEmpty()
                if (eventRequestId != requestId) return@Handler true
                when (message.what) {
                    LiteRtLmWorkerProtocol.MSG_DONE -> {
                        result.complete(message.data.getString(LiteRtLmWorkerProtocol.KEY_TEXT).orEmpty())
                    }
                    LiteRtLmWorkerProtocol.MSG_ERROR -> {
                        result.completeExceptionally(
                            IllegalStateException(message.data.getString(LiteRtLmWorkerProtocol.KEY_TEXT).orEmpty())
                        )
                    }
                }
                true
            }
        )
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                if (service == null) {
                    serviceMessenger.completeExceptionally(
                        IllegalStateException(appContext.getString(R.string.litert_error_worker_bind))
                    )
                } else {
                    serviceMessenger.complete(Messenger(service))
                }
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                result.completeExceptionally(
                    IllegalStateException(appContext.getString(R.string.litert_error_gpu_worker_crashed))
                )
            }

            override fun onBindingDied(name: ComponentName?) {
                result.completeExceptionally(
                    IllegalStateException(appContext.getString(R.string.litert_error_gpu_worker_crashed))
                )
            }

            override fun onNullBinding(name: ComponentName?) {
                result.completeExceptionally(
                    IllegalStateException(appContext.getString(R.string.litert_error_worker_bind))
                )
            }
        }
        var bound = false
        try {
            bound = appContext.bindService(
                Intent(appContext, LiteRtLmWorkerService::class.java),
                connection,
                Context.BIND_AUTO_CREATE
            )
            if (!bound) throw IllegalStateException(appContext.getString(R.string.litert_error_worker_bind))
            val remote = serviceMessenger.await()
            remote.send(
                Message.obtain(null, messageType).apply {
                    replyTo = replyMessenger
                    data = Bundle().apply {
                        putString(LiteRtLmWorkerProtocol.KEY_REQUEST_ID, requestId)
                        putString(
                            LiteRtLmWorkerProtocol.KEY_REQUEST_JSON,
                            gson.toJson(LiteRtLmWorkerRequestDto.from(request))
                        )
                    }
                }
            )
            result.await()
        } finally {
            if (bound) {
                runCatching { appContext.unbindService(connection) }
            }
        }
    }

    private sealed interface WorkerEvent {
        data class Status(val text: String) : WorkerEvent
        data class Chunk(val text: String) : WorkerEvent
        data class Thinking(val text: String) : WorkerEvent
        data class Done(val stats: LiteRtLmChatStats) : WorkerEvent
        data class Error(val text: String) : WorkerEvent
        data class Crashed(val error: LiteRtLmWorkerCrashedException) : WorkerEvent
    }

    private suspend fun streamDoctor(
        model: LiteRtModelEntity,
        backendMode: String
    ): LiteRtBackendDoctorResult = coroutineScope {
        val appContext = context.applicationContext
        val startedAt = System.currentTimeMillis()
        val requestId = "doctor-${System.currentTimeMillis()}-${model.id}-$backendMode"
        val serviceMessenger = CompletableDeferred<Messenger>()
        val events = Channel<WorkerDoctorEvent>(Channel.UNLIMITED)
        var lastPhase = "binding worker"
        fun recentWorkerExit(): String? = GenerationDiagnosticsStore.describeRecentProcessExit(
            processNameSuffix = ":litert_lm",
            sinceTimestamp = startedAt - 2_000L
        )
        val replyMessenger = Messenger(
            Handler(Looper.getMainLooper()) { message ->
                val eventRequestId = message.data.getString(LiteRtLmWorkerProtocol.KEY_REQUEST_ID).orEmpty()
                if (eventRequestId != requestId) return@Handler true
                when (message.what) {
                    LiteRtLmWorkerProtocol.MSG_LOG -> {
                        val text = message.data.getString(LiteRtLmWorkerProtocol.KEY_TEXT).orEmpty()
                        if (text.isNotBlank()) {
                            lastPhase = text
                            DebugLog.log("LiteRT worker: $text")
                        }
                    }
                    LiteRtLmWorkerProtocol.MSG_DONE -> {
                        val json = message.data.getString(LiteRtLmWorkerProtocol.KEY_STATS_JSON).orEmpty()
                        val result = runCatching {
                            gson.fromJson(json, LiteRtBackendDoctorResult::class.java)
                        }.getOrNull()
                        if (result == null) {
                            events.trySend(
                                WorkerDoctorEvent.Error(appContext.getString(R.string.litert_error_worker_bad_request))
                            )
                        } else {
                            events.trySend(WorkerDoctorEvent.Done(result))
                        }
                    }
                    LiteRtLmWorkerProtocol.MSG_ERROR -> {
                        events.trySend(WorkerDoctorEvent.Error(message.data.getString(LiteRtLmWorkerProtocol.KEY_TEXT).orEmpty()))
                    }
                }
                true
            }
        )
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                if (service == null) {
                    serviceMessenger.completeExceptionally(
                        IllegalStateException(appContext.getString(R.string.litert_error_worker_bind))
                    )
                } else {
                    serviceMessenger.complete(Messenger(service))
                }
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                val exit = recentWorkerExit()
                DebugLog.log("LiteRT worker: doctor $backendMode disconnected; recent exit=${exit ?: "unavailable"}")
                events.trySend(
                    WorkerDoctorEvent.Done(
                        LiteRtBackendDoctorResult.create(
                            context = appContext,
                            model = model,
                            backend = backendMode,
                            success = false,
                            phase = lastPhase,
                            detail = appContext.getString(R.string.litert_doctor_worker_crashed),
                            processExit = exit,
                            startedAt = startedAt,
                            durationMs = System.currentTimeMillis() - startedAt
                        )
                    )
                )
            }

            override fun onBindingDied(name: ComponentName?) = onServiceDisconnected(name)

            override fun onNullBinding(name: ComponentName?) {
                events.trySend(WorkerDoctorEvent.Error(appContext.getString(R.string.litert_error_worker_bind)))
            }
        }
        var bound = false
        try {
            val intent = Intent(appContext, LiteRtLmWorkerService::class.java)
            bound = appContext.bindService(intent, connection, Context.BIND_AUTO_CREATE)
            if (!bound) {
                throw IllegalStateException(appContext.getString(R.string.litert_error_worker_bind))
            }
            val remote = serviceMessenger.await()
            remote.send(
                Message.obtain(null, LiteRtLmWorkerProtocol.MSG_DOCTOR).apply {
                    replyTo = replyMessenger
                    data = Bundle().apply {
                        putString(LiteRtLmWorkerProtocol.KEY_REQUEST_ID, requestId)
                        putString(
                            LiteRtLmWorkerProtocol.KEY_REQUEST_JSON,
                            gson.toJson(LiteRtLmDoctorRequestDto(model = model, backendMode = backendMode))
                        )
                    }
                }
            )
            when (val event = events.receive()) {
                is WorkerDoctorEvent.Done -> event.result
                is WorkerDoctorEvent.Error -> LiteRtBackendDoctorResult.create(
                    context = appContext,
                    model = model,
                    backend = backendMode,
                    success = false,
                    phase = lastPhase,
                    detail = event.text,
                    processExit = recentWorkerExit(),
                    startedAt = startedAt,
                    durationMs = System.currentTimeMillis() - startedAt
                )
            }
        } finally {
            events.close()
            if (bound) {
                runCatching { appContext.unbindService(connection) }
            }
        }
    }

    private sealed interface WorkerDoctorEvent {
        data class Done(val result: LiteRtBackendDoctorResult) : WorkerDoctorEvent
        data class Error(val text: String) : WorkerDoctorEvent
    }

    companion object {
        const val LITERT_BACKEND_GPU_PARITY = "GPU-PARITY"
    }
}

private fun Messenger.sendWorkerMessage(
    what: Int,
    requestId: String,
    text: String? = null,
    statsJson: String? = null
) {
    runCatching {
        send(
            Message.obtain(null, what).apply {
                data = Bundle().apply {
                    putString(LiteRtLmWorkerProtocol.KEY_REQUEST_ID, requestId)
                    text?.let { putString(LiteRtLmWorkerProtocol.KEY_TEXT, it) }
                    statsJson?.let { putString(LiteRtLmWorkerProtocol.KEY_STATS_JSON, it) }
                }
            }
        )
    }
}
