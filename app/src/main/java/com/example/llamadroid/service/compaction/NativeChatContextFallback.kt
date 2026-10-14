package com.example.llamadroid.service.compaction

import android.content.Context
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.data.model.LlamaMessageEntity

/** Context-ring value derived from persisted history when no live measurement exists. */
data class FallbackContextUsage(val usedTokens: Int, val measured: Boolean)

/**
 * Keeps the context ring populated for every engine and for conversations that predate usage
 * recording: prefers the last assistant message's reported token counts, otherwise estimates
 * the prompt (system prompt + summary + messages after the latest compaction boundary).
 */
object NativeChatContextFallback {
    private const val MESSAGE_OVERHEAD = 4

    /** Pure computation; [boundaryId]/[summary] come from the latest compaction, if any. */
    fun compute(
        systemPrompt: String?,
        history: List<LlamaMessageEntity>,
        windowTokens: Int,
        boundaryId: Long? = null,
        summary: String? = null
    ): FallbackContextUsage? {
        val visible = history.filter { !it.isError }
        if (visible.isEmpty()) return null
        val lastAssistant = visible.lastOrNull { it.role == "assistant" }
        if (lastAssistant != null && lastAssistant.promptTokens > 0) {
            val used = lastAssistant.promptTokens + lastAssistant.completionTokens
            return FallbackContextUsage(if (windowTokens > 0) used.coerceAtMost(windowTokens) else used, true)
        }
        val estimator = TokenEstimator.calibrated("native-chat-fallback")
        var total = 0
        if (!systemPrompt.isNullOrBlank()) total += estimator.estimateText(systemPrompt) + MESSAGE_OVERHEAD
        if (!summary.isNullOrBlank()) total += estimator.estimateText(summary) + MESSAGE_OVERHEAD
        visible.filter { boundaryId == null || it.id > boundaryId }.forEach {
            total += estimator.estimateText(it.content + (it.thinking ?: "")) + MESSAGE_OVERHEAD
        }
        if (total <= 0) return null
        return FallbackContextUsage(if (windowTokens > 0) total.coerceAtMost(windowTokens) else total, false)
    }

    /** Seeds the store from the database when the chat has no recorded usage yet. */
    suspend fun seedIfMissing(context: Context, chatId: Long) {
        val app = context.applicationContext
        if (NativeChatContextUsageStore.current(chatId)?.windowTokens?.let { it > 0 } == true) return
        runCatching {
            val db = AppDatabase.getDatabase(app)
            val chat = db.llamaChatDao().getChatById(chatId) ?: return
            val history = db.llamaMessageDao().getMessagesOnce(chatId)
            val latest = db.nativeChatCompactionDao().getLatest(chatId)
            // Chats created with contextSize 0 use the server's window: resolve it from the bound row.
            val window = if (chat.contextSize > 0) chat.contextSize else {
                val server = chat.serverId?.let { db.llamaServerDao().getServerById(it) }
                server?.let { NativeChatContextWindow.resolve(it, chat, managedContext = null) } ?: 0
            }
            if (window <= 0) return
            val usage = compute(chat.systemPrompt, history, window, latest?.boundaryMessageId, latest?.summary)
                ?: return
            if (NativeChatContextUsageStore.current(chatId)?.windowTokens?.let { it > 0 } == true) return
            NativeChatContextUsageStore.record(app, chatId, usage.usedTokens, window, usage.measured)
        }
    }

    /** End-of-turn recording for engines that do not go through [NativeChatCompactionHooks.Turn]. */
    suspend fun recordAfterTurn(
        context: Context,
        chatId: Long,
        promptTokens: Int,
        completionTokens: Int,
        windowTokens: Int
    ) {
        val app = context.applicationContext
        if (promptTokens > 0 && windowTokens > 0) {
            NativeChatContextUsageStore.record(
                app, chatId, (promptTokens + completionTokens).coerceAtMost(windowTokens), windowTokens, true
            )
            return
        }
        runCatching {
            val db = AppDatabase.getDatabase(app)
            val chat = db.llamaChatDao().getChatById(chatId) ?: return
            val history = db.llamaMessageDao().getMessagesOnce(chatId)
            val latest = db.nativeChatCompactionDao().getLatest(chatId)
            val window = if (windowTokens > 0) windowTokens else chat.contextSize
            // Estimate only: ignore stored token counts of the just-saved reply (they were 0).
            val usage = compute(chat.systemPrompt, history.map { it.copy(promptTokens = 0) }, window,
                latest?.boundaryMessageId, latest?.summary) ?: return
            NativeChatContextUsageStore.record(app, chatId, usage.usedTokens, window, false)
        }
    }
}
