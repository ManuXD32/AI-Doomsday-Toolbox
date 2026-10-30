package com.example.llamadroid.service.bubble

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Chat (and optional llama server) the floating bubble opens. */
data class ChatBubbleTarget(val chatId: Long, val serverId: Long?)

/**
 * Process-wide state shared by the bubble service and the bubble window activity: the active
 * target (null when no bubble runs) and whether the floating chat window is currently open.
 */
object ChatBubbleState {
    private val mutableWindowOpen = MutableStateFlow(false)
    private val mutableActive = MutableStateFlow<ChatBubbleTarget?>(null)

    val windowOpen: StateFlow<Boolean> = mutableWindowOpen.asStateFlow()
    val active: StateFlow<ChatBubbleTarget?> = mutableActive.asStateFlow()

    fun setWindowOpen(open: Boolean) {
        mutableWindowOpen.value = open
    }

    internal fun setActive(target: ChatBubbleTarget?) {
        mutableActive.value = target
    }
}
