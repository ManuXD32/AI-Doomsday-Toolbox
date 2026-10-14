package com.example.llamadroid.ui.ai.llama.chat

import com.example.llamadroid.service.LlamaClientService.GenerationState
import com.example.llamadroid.service.LlamaClientService.QueueReason
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatQueueStatusTest {
    private fun queued(reason: QueueReason, label: String?) =
        GenerationState.Queued(chatId = 1L, reason = reason, position = 2, resourceLabel = label)

    @Test
    fun slotUsesServerLabelWhenKnown() {
        assertEquals(ChatQueueMessageKind.SLOT_LABEL, chatQueueMessageKind(queued(QueueReason.SLOT, "Gemma")))
        assertEquals(ChatQueueMessageKind.SLOT, chatQueueMessageKind(queued(QueueReason.SLOT, " ")))
    }

    @Test
    fun ramFallsBackToGenericText() {
        assertEquals(ChatQueueMessageKind.RAM_LABEL, chatQueueMessageKind(queued(QueueReason.RAM, "Qwen")))
        assertEquals(ChatQueueMessageKind.RAM, chatQueueMessageKind(queued(QueueReason.RAM, null)))
    }

    @Test
    fun engineIgnoresLabel() {
        assertEquals(ChatQueueMessageKind.ENGINE, chatQueueMessageKind(queued(QueueReason.ENGINE, "LiteRT")))
    }
}
