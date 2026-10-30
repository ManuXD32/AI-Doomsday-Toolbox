package com.example.llamadroid.service

import android.content.Context
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.data.model.LlamaMessageAttachmentSource

/** Binds files the user attached in the composer to the user message sent with them. */
object NativeChatAttachmentBinding {
    /**
     * Binds this chat's pending USER attachment rows to [messageId] and returns how many were bound.
     * Call right after the user message is saved so later turns never take the files for another message.
     */
    suspend fun bindPending(context: Context, chatId: Long, messageId: Long): Int {
        if (chatId <= 0L || messageId <= 0L) return 0
        val dao = AppDatabase.getDatabase(context.applicationContext).llamaMessageAttachmentDao()
        val pending = dao.getPending(chatId, LlamaMessageAttachmentSource.USER)
        if (pending.isEmpty()) return 0
        return dao.bind(pending.map { it.id }, messageId)
    }
}
