package com.example.llamadroid.service.titlegen

import android.content.Context
import android.content.res.Configuration
import com.example.llamadroid.R
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.data.repository.LlamaRepository
import com.example.llamadroid.util.DebugLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import com.example.llamadroid.data.model.LlamaServerSessionIds
import com.example.llamadroid.service.ephemeral.EphemeralLlamaServer
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.Locale

/**
 * Optional, opt-in automatic chat titles. After a completed reply in a chat that still has the
 * default title, a temporary low-impact llama-server is started (only when free RAM is sufficient),
 * short non-streaming completions write the titles, and the server is stopped.
 * Only metadata is logged; chat text is never logged or persisted here.
 */
object TitleGenerationManager {
    private const val TAG = "TitleGeneration"
    private const val RAM_RECHECK_MS = 60_000L
    private const val MAX_FAILURES = 3
    private const val SCAN_CAP = TitleGenerationLogic.MAX_PENDING

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private val failures = HashMap<Long, Int>()
    private val wake = Channel<Unit>(Channel.CONFLATED)
    /** Chats the user explicitly asked to retitle; they bypass the default-title and handled checks. */
    private val forced = java.util.Collections.synchronizedSet(HashSet<Long>())
    @Volatile private var worker: Job? = null
    private var serverFailures = 0

    private val _status = MutableStateFlow<TitleGenerationStatus>(TitleGenerationStatus.Idle)
    val status: StateFlow<TitleGenerationStatus> = _status.asStateFlow()

    /** Call after an assistant reply finished successfully. Cheap and safe from any thread. */
    fun onReplyCompleted(context: Context, chatId: Long) {
        val app = context.applicationContext
        val settings = TitleGenerationSettings(app)
        if (!settings.enabled || settings.modelPath.isBlank()) return
        scope.launch {
            runCatching {
                val repository = repository(app)
                val chat = repository.getChat(chatId) ?: return@launch
                if (chat.isEphemeral || chat.easyMode) return@launch // Easy chats are titled by Easy itself
                val messages = repository.getMessagesOnce(chatId).filter { it.kind == null && !it.isError }
                val eligible = TitleGenerationLogic.shouldEnqueue(
                    chat.title, defaultTitles(app), settings.handledIds(), chatId,
                    messages.count { it.role == "user" }, messages.count { it.role == "assistant" }
                )
                if (eligible) {
                    settings.addPending(chatId)
                    ensureWorker(app)
                }
            }.onFailure { DebugLog.log("$TAG: enqueue failed (${it.javaClass.simpleName})") }
        }
    }

    /** Re-queues persisted pending chats and scans default-titled chats that already have replies. */
    fun onAppStart(context: Context) {
        val app = context.applicationContext
        val settings = TitleGenerationSettings(app)
        _status.value = TitleGenerationLogic.baselineStatus(settings.enabled, settings.modelPath)
        if (!settings.enabled || settings.modelPath.isBlank()) {
            settings.clearPending()
            return
        }
        scanUntitled(app)
    }

    /** Settings button: scan untitled chats and process them now if RAM allows, else show the wait reason. */
    fun titleUntitledNow(context: Context) {
        val app = context.applicationContext
        val settings = TitleGenerationSettings(app)
        _status.value = TitleGenerationLogic.baselineStatus(settings.enabled, settings.modelPath)
        if (!settings.enabled || settings.modelPath.isBlank()) return
        scanUntitled(app)
    }

    /** Explicit request from a chat menu: queue [chatId] first and overwrite its title. */
    fun generateNow(context: Context, chatId: Long) {
        val app = context.applicationContext
        val settings = TitleGenerationSettings(app)
        if (settings.modelPath.isBlank()) {
            _status.value = TitleGenerationStatus.NoModel
            return
        }
        forced += chatId
        failures.remove(chatId)
        settings.pushFront(chatId)
        ensureWorker(app)
    }

    private fun scanUntitled(app: Context) {
        scope.launch {
            runCatching {
                val settings = TitleGenerationSettings(app)
                val repository = repository(app)
                val defaults = defaultTitles(app)
                val handled = settings.handledIds()
                val pending = settings.pendingIds().toSet()
                var added = 0
                val chats = repository.allChats.first()
                for (chat in chats) {
                    if (added >= SCAN_CAP) break
                    if (chat.isEphemeral || chat.easyMode || chat.id in handled || chat.id in pending) continue
                    if (!TitleGenerationLogic.isDefaultTitle(chat.title, defaults)) continue
                    val messages = repository.getMessagesOnce(chat.id).filter { it.kind == null && !it.isError }
                    if (TitleGenerationLogic.shouldEnqueue(
                            chat.title, defaults, handled, chat.id,
                            messages.count { it.role == "user" }, messages.count { it.role == "assistant" }
                        )
                    ) {
                        settings.addPending(chat.id)
                        added++
                    }
                }
                if (settings.pendingIds().isNotEmpty()) ensureWorker(app)
            }.onFailure { DebugLog.log("$TAG: scan failed (${it.javaClass.simpleName})") }
        }
    }

    private fun ensureWorker(app: Context) {
        wake.trySend(Unit)
        synchronized(this) {
            if (worker?.isActive == true) return
            worker = scope.launch { lock.withLock { runQueue(app) } }
        }
    }

    private suspend fun runQueue(app: Context) {
        val settings = TitleGenerationSettings(app)
        while (settings.pendingIds().isNotEmpty()) {
            if (settings.modelPath.isBlank()) {
                settings.clearPending()
                forced.clear()
                _status.value = TitleGenerationStatus.NoModel
                return
            }
            if (!settings.enabled && settings.pendingIds().none { it in forced }) {
                settings.clearPending()
                _status.value = TitleGenerationStatus.Disabled
                return
            }
            val model = File(settings.modelPath)
            if (!model.isFile) {
                settings.clearPending()
                forced.clear()
                _status.value = TitleGenerationStatus.Error("Title model file is missing", System.currentTimeMillis())
                return
            }
            val ram = EphemeralLlamaServer.ramDecision(app, model.length())
            if (!ram.ok) {
                _status.value = TitleGenerationStatus.WaitingForRam(ram.neededBytes, ram.availBytes)
                withTimeoutOrNull(RAM_RECHECK_MS) { wake.receive() }
                continue
            }
            processBatch(app, settings, model)
        }
        val current = _status.value
        if (current is TitleGenerationStatus.Generating || current is TitleGenerationStatus.WaitingForRam) {
            _status.value = TitleGenerationLogic.baselineStatus(settings.enabled, settings.modelPath)
        }
    }

    private suspend fun processBatch(app: Context, settings: TitleGenerationSettings, model: File) {
        val repository = repository(app)
        var backoff = false
        try {
            val profile = EphemeralLlamaServer.lowImpactProfile(null, model.absolutePath)
            EphemeralLlamaServer.run(app, LlamaServerSessionIds.TITLE_GEN, profile) { baseUrl ->
                serverFailures = 0
                while (true) {
                    val chatId = settings.pendingIds().firstOrNull() ?: break
                    if (!settings.enabled && chatId !in forced) { settings.removePending(chatId); continue }
                    try {
                        titleOne(app, repository, settings, baseUrl, chatId)
                    } catch (error: Exception) {
                        if (isRealCancellation(error)) throw error
                        val count = (failures[chatId] ?: 0) + 1
                        failures[chatId] = count
                        DebugLog.log("$TAG: chat $chatId failed ($count/$MAX_FAILURES): ${error.javaClass.simpleName}")
                        _status.value = TitleGenerationStatus.Error(
                            error.message ?: error.javaClass.simpleName, System.currentTimeMillis()
                        )
                        settings.removePending(chatId)
                        if (count >= MAX_FAILURES) {
                            // Dropped, not handled: the next completed reply or scan may queue it again.
                            failures.remove(chatId)
                            forced -= chatId
                        } else {
                            settings.addPending(chatId)
                            backoff = true
                            break
                        }
                    }
                }
            }
        } catch (error: Exception) {
            if (isRealCancellation(error)) throw error
            DebugLog.log("$TAG: server batch failed: ${error.javaClass.simpleName}")
            _status.value = TitleGenerationStatus.Error(
                error.message ?: error.javaClass.simpleName, System.currentTimeMillis()
            )
            backoff = true
            serverFailures++
            if (serverFailures >= MAX_FAILURES) {
                serverFailures = 0
                settings.clearPending()
                forced.clear()
            }
        }
        if (backoff) delay(RAM_RECHECK_MS)
    }

    private suspend fun titleOne(
        app: Context,
        repository: LlamaRepository,
        settings: TitleGenerationSettings,
        baseUrl: String,
        chatId: Long
    ) {
        val chat = repository.getChat(chatId)
        if (chat == null) {
            settings.markHandled(chatId)
            forced -= chatId
            return
        }
        if (chat.easyMode) {
            settings.markHandled(chatId)
            forced -= chatId
            return
        }
        val isForced = chatId in forced
        val messages = repository.getMessagesOnce(chatId).filter { it.kind == null && !it.isError }
        val firstUser = messages.firstOrNull { it.role == "user" }
        val firstAssistant = messages.firstOrNull { it.role == "assistant" }
        val eligible = firstUser != null && firstAssistant != null && (
            isForced || TitleGenerationLogic.stillEligible(
                chat.title, defaultTitles(app), settings.handledIds(), chatId,
                messages.count { it.role == "user" }, messages.count { it.role == "assistant" }
            )
            )
        if (!eligible || firstUser == null || firstAssistant == null) {
            settings.markHandled(chatId)
            forced -= chatId
            return
        }
        _status.value = TitleGenerationStatus.Generating(chat.title.takeIf { it.isNotBlank() })
        val prompt = TitleGenerationLogic.buildUserPrompt(firstUser.content, firstAssistant.content)
        val title = requestTitle(baseUrl, prompt) ?: error("empty title")
        // The user may have renamed the chat while the model was working; never overwrite that
        // automatically (an explicit request always wins).
        val latest = repository.getChat(chatId)
        if (latest != null && (isForced || TitleGenerationLogic.isDefaultTitle(latest.title, defaultTitles(app)))) {
            repository.updateChatTitle(chatId, title)
        }
        failures.remove(chatId)
        forced -= chatId
        settings.markHandled(chatId)
    }

    /** Short request first; thinking models that still return nothing get one retry with more room. */
    private suspend fun requestTitle(baseUrl: String, prompt: String): String? {
        for (maxTokens in intArrayOf(TitleGenerationLogic.SHORT_MAX_TOKENS, TitleGenerationLogic.RETRY_MAX_TOKENS)) {
            val body = withTimeout(EphemeralLlamaServer.REQUEST_TIMEOUT_MS + 5_000L) {
                EphemeralLlamaServer.postJson(
                    baseUrl, "/v1/chat/completions", TitleGenerationLogic.buildRequestBody(prompt, maxTokens)
                )
            }
            TitleGenerationLogic.parseTitleResponse(body)?.let { return it }
        }
        return null
    }

    /** withTimeout failures are CancellationExceptions but must count as ordinary failures here. */
    private fun isRealCancellation(error: Exception): Boolean =
        error is kotlinx.coroutines.CancellationException && error !is kotlinx.coroutines.TimeoutCancellationException

    private fun repository(app: Context): LlamaRepository {
        val db = AppDatabase.getDatabase(app)
        return LlamaRepository(db.llamaServerDao(), db.llamaChatDao(), db.llamaChatFolderDao(), db.llamaMessageDao())
    }

    /** Default chat titles in every shipped locale so a Spanish-created chat is still recognised. */
    internal fun defaultTitles(app: Context): Set<String> {
        val result = HashSet<String>()
        for (tag in listOf("en", "es")) {
            val config = Configuration(app.resources.configuration).apply { setLocale(Locale.forLanguageTag(tag)) }
            val localized = app.createConfigurationContext(config)
            result += localized.getString(R.string.llama_new_chat)
            result += localized.getString(R.string.wear_chat_new_title)
        }
        result += app.getString(R.string.llama_new_chat)
        return result
    }
}
