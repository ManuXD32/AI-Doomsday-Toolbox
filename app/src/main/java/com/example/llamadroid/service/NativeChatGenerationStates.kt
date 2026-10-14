package com.example.llamadroid.service

import com.example.llamadroid.service.LlamaClientService.GenerationState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/**
 * Per-chat generation states of Chat Llama Native. Several chats can generate (or wait in a
 * queue) at the same time, so every consumer asks about one chat instead of reading a single
 * app-wide state. Chats without an entry are [GenerationState.Idle].
 *
 * [LlamaClientService] is the only writer; screens, the wake dispatcher, the DeepSeek subagent,
 * call mode, the watch bridge and the tool server read from here.
 */
object NativeChatGenerationStates {
    private val mutableStates = MutableStateFlow<Map<Long, GenerationState>>(emptyMap())

    /** Non-idle states keyed by chat id (Generating, Queued, and the last Completed/Error). */
    val states: StateFlow<Map<Long, GenerationState>> = mutableStates.asStateFlow()

    fun stateFor(chatId: Long): GenerationState = mutableStates.value[chatId] ?: GenerationState.Idle

    fun flowFor(chatId: Long): Flow<GenerationState> =
        states.map { it[chatId] ?: GenerationState.Idle }.distinctUntilChanged()

    /** True while [chatId] is generating or waiting in the queue. */
    fun isBusy(chatId: Long): Boolean = stateFor(chatId).isBusy()

    /** Chats currently generating or queued. */
    fun busyChatIds(): Set<Long> = mutableStates.value.filterValues { it.isBusy() }.keys

    fun anyBusy(): Boolean = mutableStates.value.values.any { it.isBusy() }

    /** Writer API: [GenerationState.Idle] removes the entry. Ignores invalid chat ids. */
    fun update(chatId: Long, state: GenerationState) {
        if (chatId <= 0L) return
        mutableStates.update { current ->
            if (state is GenerationState.Idle) current - chatId else current + (chatId to state)
        }
    }

    /** Drops a terminal (Completed/Error) entry so the chat reads as idle again. */
    fun clearIfIdle(chatId: Long) {
        mutableStates.update { current ->
            val state = current[chatId]
            if (state == null || state.isBusy()) current else current - chatId
        }
    }

    private fun GenerationState.isBusy(): Boolean =
        this is GenerationState.Generating || this is GenerationState.Queued
}
