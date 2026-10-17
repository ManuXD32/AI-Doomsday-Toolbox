package com.example.llamadroid.service.chat

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Which native chats have a model request in flight right now (prompt processing or decoding), as
 * opposed to merely "generating" while a tool runs. The DeepSeek subagent holds back its own model
 * calls on a shared endpoint only while the main chat's model call is in flight, so a main chat that
 * is waiting inside `deepseek_task_message` never blocks the subagent it waits for.
 *
 * Written by the chat service around each model HTTP call; read by `DelegateSubagentLoop`.
 */
object NativeChatModelActivity {
    private val counts = MutableStateFlow<Map<Long, Int>>(emptyMap())

    /** Chat ids with at least one model call in flight. */
    val inFlight: StateFlow<Map<Long, Int>> = counts.asStateFlow()

    fun isModelCallInFlight(chatId: Long): Boolean = (counts.value[chatId] ?: 0) > 0

    /** Runs [block] marked as a model call of [chatId] (no-op marking when [chatId] is null or <= 0). */
    suspend fun <T> track(chatId: Long?, block: suspend () -> T): T {
        val id = chatId?.takeIf { it > 0L } ?: return block()
        counts.update { it + (id to ((it[id] ?: 0) + 1)) }
        try {
            return block()
        } finally {
            counts.update { current ->
                val next = (current[id] ?: 1) - 1
                if (next <= 0) current - id else current + (id to next)
            }
        }
    }
}
