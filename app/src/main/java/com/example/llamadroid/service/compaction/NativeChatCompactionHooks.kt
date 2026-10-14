package com.example.llamadroid.service.compaction

import android.content.Context
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.data.model.LlamaChatEntity
import com.example.llamadroid.data.model.LlamaMessageEntity
import com.example.llamadroid.data.model.LlamaServerCardEntity
import com.example.llamadroid.data.model.LlamaServerEntity
import com.example.llamadroid.data.model.NativeChatCompactionEntity
import com.example.llamadroid.service.AgentTool
import com.example.llamadroid.service.KvSlotLease
import com.example.llamadroid.service.NativeChatGenerationStates
import com.example.llamadroid.service.LlamaServerChatService
import com.example.llamadroid.service.LlamaServerRequestOptions
import com.example.llamadroid.service.LlamaServerUsageLease
import com.example.llamadroid.service.ManagedLlamaServerCoordinator
import com.example.llamadroid.service.NativeChatKvCacheCoordinator
import com.example.llamadroid.service.NativeChatLocalServerStarter
import com.example.llamadroid.service.OllamaService
import com.example.llamadroid.service.managedLlamaConnectHost
import com.example.llamadroid.util.DebugLog
import com.google.gson.Gson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext

/**
 * Glue between `LlamaClientService` and the pure compaction core. Handles llama-server engines
 * (local or remote) only. Nothing here throws to the caller: failures log metadata and report
 * "not compacted". Message text is never logged.
 */
object NativeChatCompactionHooks {
    private val profile = CompactionProfile.MAIN_CHAT

    /** Result of one compaction; [ids] is parallel to [messages] (null = system/synthetic). */
    internal class Outcome(
        val messages: List<OllamaService.ChatMessage>,
        val ids: List<Long?>,
        val compactionId: Long,
        val boundaryMessageId: Long,
        val summary: String,
        val tokensBefore: Int,
        val tokensAfter: Int
    )

    /** Standalone entry used by tests and simple callers; see [compactCore]. */
    suspend fun maybeCompact(
        context: Context,
        chat: LlamaChatEntity,
        params: Map<String, Any?>,
        baseUrl: String,
        modelLabel: String?,
        contextTokens: Int,
        maxOutputTokens: Int?,
        messages: List<OllamaService.ChatMessage>,
        tools: List<AgentTool>,
        lease: KvSlotLease,
        historyEntityIds: List<Long?>,
        force: Boolean = false
    ): List<OllamaService.ChatMessage>? = compactCore(
        context, chat, params, baseUrl, modelLabel, contextTokens, maxOutputTokens,
        messages, tools, lease, historyEntityIds, force
    )?.messages

    internal suspend fun compactCore(
        context: Context,
        chat: LlamaChatEntity,
        params: Map<String, Any?>,
        baseUrl: String,
        modelLabel: String?,
        contextTokens: Int,
        maxOutputTokens: Int?,
        messages: List<OllamaService.ChatMessage>,
        tools: List<AgentTool>,
        lease: KvSlotLease,
        historyEntityIds: List<Long?>,
        force: Boolean
    ): Outcome? {
        if (!NativeChatCompactionParams.autoCompactEnabled(params) && !force) return null
        return try {
            val estimator = TokenEstimator.calibrated(baseUrl)
            val toolTokens = estimator.estimateTools(tools)
            val before = estimator.estimateMessages(messages) + toolTokens
            val reserve = ConversationCompactor.outputReserve(contextTokens, maxOutputTokens)
            if (!force && !ConversationCompactor.shouldCompact(before, contextTokens, reserve)) return null
            val ids = if (historyEntityIds.size >= messages.size) historyEntityIds
            else historyEntityIds + List(messages.size - historyEntityIds.size) { null }

            val pruned = ConversationCompactor.pruneToolResults(messages)
            val plan = ConversationCompactor.plan(pruned, contextTokens, estimator) ?: return null
            val boundary = NativeChatTurnDecoratorLogic.boundaryMessageId(
                ids, plan.leadingSystem.size, plan.keptStartIndex
            ) ?: return null

            val summary = requestSummary(plan, baseUrl, modelLabel, contextTokens, tools, lease)
            val compacted = ConversationCompactor.applySummary(plan, summary)
            val newIds = List(plan.leadingSystem.size) { null } + ids.subList(plan.keptStartIndex, ids.size)
            val after = estimator.estimateMessages(compacted) + toolTokens

            val id = AppDatabase.getDatabase(context.applicationContext).nativeChatCompactionDao().insert(
                NativeChatCompactionEntity(
                    chatId = chat.id,
                    boundaryMessageId = boundary,
                    summary = summary,
                    tokensBefore = before,
                    tokensAfter = after,
                    measured = false
                )
            )
            NativeChatContextUsageStore.recordCompaction(context, chat.id, (before - after).coerceAtLeast(0), false)
            NativeChatContextUsageStore.record(context, chat.id, after, contextTokens, false)
            DebugLog.log("NativeChatCompaction: compacted chat=${chat.id} before=$before after=$after")
            Outcome(compacted, newIds, id, boundary, summary, before, after)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            DebugLog.log("NativeChatCompaction: failed ${error.javaClass.simpleName}")
            null
        }
    }

    private suspend fun requestSummary(
        plan: CompactionPlan,
        baseUrl: String,
        modelLabel: String?,
        contextTokens: Int,
        tools: List<AgentTool>,
        lease: KvSlotLease
    ): String {
        val request = ConversationCompactor.buildSummaryRequest(plan, profile)
        val text = try {
            // Same tools keep the cached prefix identical; the instruction forbids tool calls.
            // llama-server's "none" tool choice is not accepted by LlamaServerRequestOptions.
            val response = lease.request { slot ->
                LlamaServerChatService().chatWithToolsStreaming(
                    baseUrl = baseUrl,
                    messages = request,
                    tools = tools,
                    modelLabel = modelLabel,
                    thinkingEnabled = false,
                    maxTokens = ConversationCompactor.summaryMaxTokens(contextTokens),
                    requestOptions = LlamaServerRequestOptions(slotId = slot, returnPromptProgress = false)
                ).getOrThrow()
            }
            response.message.content
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            DebugLog.log("NativeChatCompaction: summary request failed ${error.javaClass.simpleName}")
            ""
        }
        return if (ConversationCompactor.isValidSummary(text, profile)) text.trim()
        else ConversationCompactor.deterministicSummary(plan.toSummarize, profile)
    }

    /**
     * Per-turn compaction state for one model/server. The tool loop calls [prepare] once after the
     * request messages are built, [onOverflow] when the server rejects the prompt, and [onCallUsage]
     * after every model call (per-call usage, never the turn sum).
     */
    class Turn(
        private val context: Context,
        private val chat: LlamaChatEntity,
        private val params: Map<String, Any?>,
        private val baseUrl: String,
        private val modelLabel: String?,
        private val contextTokens: Int,
        private val maxOutputTokens: Int?,
        private val tools: List<AgentTool>,
        private val lease: KvSlotLease
    ) {
        private var ids: List<Long?> = emptyList()
        private var overflowRetried = false
        private var pendingCompactionId: Long? = null
        private var pendingTokensBefore = 0
        private var pendingEstimate = 0

        /** Compacts [messages] in place when needed and records the pre-send estimate. True if compacted. */
        suspend fun prepare(messages: MutableList<OllamaService.ChatMessage>, historyEntityIds: List<Long?>): Boolean {
            ids = historyEntityIds
            val outcome = compactCore(
                context, chat, params, baseUrl, modelLabel, contextTokens, maxOutputTokens,
                messages, tools, lease, ids, false
            )
            if (outcome != null) adopt(messages, outcome) else recordEstimate(messages)
            return outcome != null
        }

        /** Forced single retry after a context-overflow error. True when [messages] was replaced. */
        suspend fun onOverflow(messages: MutableList<OllamaService.ChatMessage>, error: Throwable): Boolean {
            if (overflowRetried || !NativeChatTurnDecoratorLogic.isContextOverflowMessage(error.message)) return false
            overflowRetried = true
            val outcome = compactCore(
                context, chat, params, baseUrl, modelLabel, contextTokens, maxOutputTokens,
                messages, tools, lease, ids, true
            ) ?: return false
            adopt(messages, outcome)
            return true
        }

        suspend fun onCallUsage(promptTokens: Int?, completionTokens: Int?) {
            val prompt = promptTokens ?: return
            if (prompt <= 0) return
            try {
                NativeChatContextUsageStore.record(
                    context, chat.id, prompt + (completionTokens ?: 0).coerceAtLeast(0), contextTokens, true
                )
                if (pendingEstimate > 0) {
                    TokenEstimator.recordExact(baseUrl, pendingEstimate, prompt)
                    pendingEstimate = 0
                }
                pendingCompactionId?.let { id ->
                    pendingCompactionId = null
                    AppDatabase.getDatabase(context.applicationContext).nativeChatCompactionDao().setMeasured(id, prompt)
                    NativeChatContextUsageStore.recordCompaction(
                        context, chat.id, (pendingTokensBefore - prompt).coerceAtLeast(0), true
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                DebugLog.log("NativeChatCompaction: usage record failed ${error.javaClass.simpleName}")
            }
        }

        private fun adopt(messages: MutableList<OllamaService.ChatMessage>, outcome: Outcome) {
            messages.clear()
            messages.addAll(outcome.messages)
            ids = outcome.ids
            pendingCompactionId = outcome.compactionId
            pendingTokensBefore = outcome.tokensBefore
            pendingEstimate = outcome.tokensAfter
        }

        private fun recordEstimate(messages: List<OllamaService.ChatMessage>) {
            val estimator = TokenEstimator.calibrated(baseUrl)
            pendingEstimate = estimator.estimateMessages(messages) + estimator.estimateTools(tools)
            NativeChatContextUsageStore.record(context, chat.id, pendingEstimate, contextTokens, false)
        }
    }

    /**
     * Plain (no-tool) llama-server turn: compacts at entity level before the request is built, and
     * retries once after a context-overflow error. [block] streams the reply for the given history
     * and returns that single call's (promptTokens, completionTokens).
     */
    suspend fun runPlainTurn(
        context: Context,
        chat: LlamaChatEntity,
        params: Map<String, Any?>,
        server: LlamaServerEntity,
        maxOutputTokens: Int?,
        lease: KvSlotLease,
        history: List<LlamaMessageEntity>,
        block: suspend (List<LlamaMessageEntity>) -> Pair<Int, Int>
    ) {
        val turn = Turn(
            context, chat, params, server.baseUrl(), server.modelName, chat.contextSize,
            maxOutputTokens, emptyList(), lease
        )
        var current = history
        val messages = toChatMessages(chat, current).toMutableList()
        if (turn.prepare(messages, entityIds(chat, current))) current = reapplyCompaction(context, chat, current)
        val usage = try {
            block(current)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            val retryMessages = toChatMessages(chat, current).toMutableList()
            if (!turn.onOverflow(retryMessages, error)) throw error
            block(reapplyCompaction(context, chat, current))
        }
        turn.onCallUsage(usage.first, usage.second)
    }

    private suspend fun reapplyCompaction(
        context: Context, chat: LlamaChatEntity, history: List<LlamaMessageEntity>
    ): List<LlamaMessageEntity> {
        val latest = AppDatabase.getDatabase(context.applicationContext).nativeChatCompactionDao().getLatest(chat.id)
            ?: return history
        return NativeChatTurnDecoratorLogic.applyBoundary(history, chat.id, latest.boundaryMessageId, latest.summary)
    }

    internal fun toChatMessages(chat: LlamaChatEntity, history: List<LlamaMessageEntity>): List<OllamaService.ChatMessage> =
        buildList {
            if (!chat.systemPrompt.isNullOrBlank()) add(OllamaService.ChatMessage(role = "system", content = chat.systemPrompt))
            history.forEach {
                add(
                    OllamaService.ChatMessage(
                        role = it.role,
                        content = it.content,
                        images = if (it.role == "user" && !it.imagePath.isNullOrBlank()) listOf("") else null,
                        thinking = it.thinking
                    )
                )
            }
        }

    internal fun entityIds(chat: LlamaChatEntity, history: List<LlamaMessageEntity>): List<Long?> =
        (if (chat.systemPrompt.isNullOrBlank()) emptyList() else listOf<Long?>(null)) +
            history.map { it.id.takeIf { id -> id > 0L } }

    /**
     * Manual "Compact now": summarizes the chat's persisted history on its llama-server. Fails
     * while this chat is generating, for non-llama-server engines, or when nothing can be
     * compacted. Returns the tokens saved (estimate).
     */
    suspend fun compactNow(context: Context, chatId: Long): Result<Int> = try {
        Result.success(compactNowInternal(context.applicationContext, chatId))
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        DebugLog.log("NativeChatCompaction: manual compaction failed ${error.javaClass.simpleName}")
        Result.failure(error)
    }

    private suspend fun compactNowInternal(context: Context, chatId: Long): Int {
        check(!NativeChatGenerationStates.isBusy(chatId)) {
            "The chat is generating"
        }
        val db = AppDatabase.getDatabase(context)
        val chat = db.llamaChatDao().getChatById(chatId) ?: error("Chat not found")
        var server = chat.serverId?.let { db.llamaServerDao().getServerById(it) } ?: error("Chat has no server")
        check(!server.isOllamaEngine() && !server.isLiteRtEngine()) { "Unsupported engine" }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        var usage: LlamaServerUsageLease? = null
        var contextTokens = chat.contextSize
        try {
            val cardId = server.managedServerCardId
            if (cardId != null) {
                usage = withContext(Dispatchers.IO) {
                    LlamaServerUsageLease(context, LlamaServerCardEntity.sessionIdForCard(cardId), scope)
                }
                val ready = ManagedLlamaServerCoordinator(context, db).prepare(cardId)
                val launch = requireNotNull(ready.profile)
                server = server.copy(host = managedLlamaConnectHost(launch.host), port = launch.serverPort)
                ready.contextTokens?.let { contextTokens = minOf(contextTokens, it) }
            } else {
                NativeChatLocalServerStarter(context).ensureReady(server)
            }
            @Suppress("UNCHECKED_CAST")
            val params = runCatching {
                Gson().fromJson(chat.apiParams ?: "{}", Map::class.java) as? Map<String, Any?>
            }.getOrNull().orEmpty()
            val history = NativeChatTurnDecorators.apply(
                context, chatId, db.llamaMessageDao().getMessagesOnce(chatId).filterNot { it.isError }
            )
            val messages = toChatMessages(chat, history)
            val kv = NativeChatKvCacheCoordinator(context)
            val outcome = kv.withChatLease(server, chat, pinOnly = true) { lease ->
                compactCore(
                    context, chat, params, server.baseUrl(), server.modelName, contextTokens, null,
                    messages, emptyList(), lease, entityIds(chat, history), true
                )
            } ?: error("Nothing to compact")
            return (outcome.tokensBefore - outcome.tokensAfter).coerceAtLeast(0)
        } finally {
            withContext(NonCancellable + Dispatchers.IO) { usage?.close() }
            scope.cancel()
        }
    }
}
