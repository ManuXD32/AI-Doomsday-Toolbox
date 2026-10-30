package com.example.llamadroid.service.titlegen

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/** Own preferences for automatic chat titles (kept out of SettingsRepository on purpose). */
class TitleGenerationSettings(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(value) = prefs.edit { putBoolean(KEY_ENABLED, value) }

    var modelPath: String
        get() = prefs.getString(KEY_MODEL_PATH, "").orEmpty()
        set(value) = prefs.edit { putString(KEY_MODEL_PATH, value) }

    /** Chats already titled (or deliberately skipped); never titled again. */
    fun handledIds(): Set<Long> = readIds(KEY_HANDLED)

    fun markHandled(chatId: Long) {
        val updated = (readIds(KEY_HANDLED) + chatId).toList().takeLast(MAX_HANDLED)
        prefs.edit { putString(KEY_HANDLED, updated.joinToString(",")) }
        removePending(chatId)
    }

    fun pendingIds(): List<Long> = readIds(KEY_PENDING, ordered = true).toList()

    fun addPending(chatId: Long) {
        val current = pendingIds()
        if (chatId in current) return
        val updated = (current + chatId).takeLast(TitleGenerationLogic.MAX_PENDING)
        prefs.edit { putString(KEY_PENDING, updated.joinToString(",")) }
    }

    /** Puts [chatId] at the head of the queue (explicit user request) and lifts any handled mark. */
    fun pushFront(chatId: Long) {
        val updated = (listOf(chatId) + (pendingIds() - chatId)).take(TitleGenerationLogic.MAX_PENDING)
        prefs.edit {
            putString(KEY_PENDING, updated.joinToString(","))
            putString(KEY_HANDLED, (readIds(KEY_HANDLED) - chatId).joinToString(","))
        }
    }

    fun removePending(chatId: Long) {
        val updated = pendingIds() - chatId
        prefs.edit { putString(KEY_PENDING, updated.joinToString(",")) }
    }

    fun clearPending() = prefs.edit { remove(KEY_PENDING) }

    private fun readIds(key: String, ordered: Boolean = false): Set<Long> {
        val ids = prefs.getString(key, "").orEmpty().split(',').mapNotNull { it.trim().toLongOrNull() }
        return if (ordered) LinkedHashSet(ids) else ids.toSet()
    }

    companion object {
        private const val PREFS = "title_generation"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_MODEL_PATH = "model_path"
        private const val KEY_HANDLED = "handled_chat_ids"
        private const val KEY_PENDING = "pending_chat_ids"
        private const val MAX_HANDLED = 2000
    }
}
